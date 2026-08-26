import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'device_api.dart';
import 'discovery.dart';
import 'models.dart';

class OpenAriaController extends ChangeNotifier {
  OpenAriaController({
    this._discovery = const DeviceDiscoveryService(),
    this._secureStorage = const FlutterSecureStorage(),
  });

  final DeviceDiscoveryService _discovery;
  final FlutterSecureStorage _secureStorage;
  DeviceApiClient? _api;
  Timer? _captureTimer;
  Timer? _previewTimer;
  Timer? _slowTimer;

  bool scanning = false;
  bool connecting = false;
  bool busy = false;
  String? error;
  List<DeviceEndpoint> endpoints = const [];

  DeviceEndpoint? connectedEndpoint;
  DeviceDescriptor? device;
  CaptureStatus? capture;
  SessionList sessions = const SessionList(items: [], nextCursor: null);
  NetworkStatusView? network;
  List<NetworkScanEntry> networkScan = const [];
  CameraFocusStatus? cameraFocus;
  SafeSwapReceipt? safeSwapReceipt;
  PreviewFrame? previewFrame;
  DateTime? lastRefresh;

  bool get connected => connectedEndpoint != null && device != null;
  bool get recording => capture?.snapshot.deviceState == 'recording';

  RecordingGeneration? get currentOrRetainedRecording {
    return capture?.snapshot.activeRecording ??
        capture?.snapshot.retainedUnsuccessful;
  }

  bool get safeSwapAuthorized {
    final receipt = safeSwapReceipt;
    final subject = currentOrRetainedRecording;
    final volumeId = device?.storage.volumeId;
    if (receipt == null || subject == null || volumeId == null) {
      return false;
    }
    final releaseStateOk =
        receipt.releaseState == 'unmounted' ||
        receipt.releaseState == 'device-released';
    return receipt.openHandleCount == 0 &&
        releaseStateOk &&
        receipt.sessionId == subject.recordingState.sessionId &&
        receipt.generationId == subject.generationId &&
        receipt.volumeId == subject.recordingState.volumeId &&
        receipt.volumeId == volumeId;
  }

  Future<void> scan() async {
    scanning = true;
    error = null;
    notifyListeners();
    try {
      endpoints = await _discovery.discover();
    } catch (exception) {
      error = formatDeviceApiError(exception);
    } finally {
      scanning = false;
      notifyListeners();
    }
  }

  Future<void> addManualEndpoint(String address, {String? token}) async {
    scanning = true;
    error = null;
    notifyListeners();
    try {
      final endpoint = await _discovery.endpointFromManualAddress(
        address,
        token: token,
      );
      endpoints = [
        endpoint,
        ...endpoints.where(
          (item) => item.baseUri.origin != endpoint.baseUri.origin,
        ),
      ];
    } catch (exception) {
      error = formatDeviceApiError(exception);
    } finally {
      scanning = false;
      notifyListeners();
    }
  }

  Future<void> connect(DeviceEndpoint endpoint, {String? token}) async {
    connecting = true;
    error = null;
    notifyListeners();
    try {
      final storedToken = token?.trim().isNotEmpty == true
          ? token!.trim()
          : await _secureStorage.read(key: _tokenKey(endpoint.storageKey));
      final api = DeviceApiClient(
        baseUri: endpoint.baseUri,
        accessToken: storedToken,
      );
      final nextDevice = await api.getDevice();
      _api?.close();
      _api = api;
      connectedEndpoint = endpoint.copyWith(device: nextDevice);
      device = nextDevice;
      if (storedToken != null && storedToken.isNotEmpty) {
        await _secureStorage.write(
          key: _tokenKey(nextDevice.device.deviceId),
          value: storedToken,
        );
      }
      await refreshAll();
      _startLoops();
    } catch (exception) {
      error = formatDeviceApiError(exception);
    } finally {
      connecting = false;
      notifyListeners();
    }
  }

  Future<void> disconnect() async {
    _stopLoops();
    _api?.close();
    _api = null;
    connectedEndpoint = null;
    device = null;
    capture = null;
    sessions = const SessionList(items: [], nextCursor: null);
    network = null;
    networkScan = const [];
    cameraFocus = null;
    safeSwapReceipt = null;
    previewFrame = null;
    notifyListeners();
  }

  Future<void> refreshAll() async {
    final api = _api;
    if (api == null) {
      return;
    }
    try {
      final results = await Future.wait<Object?>([
        api.getDevice(),
        api.getCaptureStatus(),
        api.listSessions(),
        api.getNetwork(),
        api.getCameraFocus(),
        api.getSafeSwap(),
      ]);
      device = results[0] as DeviceDescriptor;
      capture = results[1] as CaptureStatus;
      sessions = results[2] as SessionList;
      network = results[3] as NetworkStatusView?;
      cameraFocus = results[4] as CameraFocusStatus?;
      safeSwapReceipt = results[5] as SafeSwapReceipt?;
      lastRefresh = DateTime.now();
      error = null;
    } catch (exception) {
      error = formatDeviceApiError(exception);
    }
    notifyListeners();
  }

  Future<void> refreshCapture() async {
    final api = _api;
    if (api == null) {
      return;
    }
    try {
      capture = await api.getCaptureStatus();
      lastRefresh = DateTime.now();
      error = null;
    } catch (exception) {
      error = formatDeviceApiError(exception);
    }
    notifyListeners();
  }

  Future<void> refreshPreview() async {
    final api = _api;
    final supportsPreview = device?.capabilities.preview ?? false;
    if (api == null || !supportsPreview) {
      return;
    }
    try {
      previewFrame = await api.getPreviewFrame();
    } catch (_) {
      // Preview is best-effort; snapshot errors are surfaced separately.
    }
    notifyListeners();
  }

  Future<void> startCapture(String displayName) async {
    await _runCommand(() async {
      capture = await _api!.startCapture(displayName: displayName);
      await refreshPreview();
    });
  }

  Future<void> stopCapture() async {
    await _runCommand(() async {
      capture = await _api!.stopCapture(reason: 'user');
      await refreshAll();
    });
  }

  Future<void> requestSafeSwap() async {
    await _runCommand(() async {
      capture = await _api!.stopCapture(reason: 'safe_swap');
      safeSwapReceipt = await _api!.getSafeSwap();
      await refreshAll();
    });
  }

  Future<void> scanNetworks() async {
    await _runCommand(() async {
      networkScan = await _api!.scanNetworks();
    });
  }

  Future<void> joinWifi(NetworkScanEntry entry, String passphrase) async {
    await _runCommand(() async {
      final credentialRef = entry.credentialRequired && passphrase.isNotEmpty
          ? await _api!.createNetworkCredential(passphrase)
          : null;
      final wifiClient = <String, Object?>{
        'ssid': entry.ssid,
        'security': entry.security,
      };
      if (credentialRef != null) {
        wifiClient['credential_ref'] = credentialRef;
      }
      await _api!.applyNetwork({
        'mode': 'wifi-client',
        'wifi_client': wifiClient,
        'ethernet': null,
      });
      network = await _api!.getNetwork();
    });
  }

  Future<void> setHotspotMode() async {
    await _runCommand(() async {
      await _api!.applyNetwork({
        'mode': 'hotspot',
        'wifi_client': null,
        'ethernet': null,
      });
      network = await _api!.getNetwork();
    });
  }

  Future<void> setEthernetDhcp() async {
    await _runCommand(() async {
      await _api!.applyNetwork({
        'mode': 'ethernet-dhcp',
        'wifi_client': null,
        'ethernet': {'addressing': 'dhcp', 'static_ipv4': null},
      });
      network = await _api!.getNetwork();
    });
  }

  Future<void> setCameraFocus(double value) async {
    await _runCommand(() async {
      cameraFocus = await _api!.setCameraFocus(
        value: value,
        autoEnabled: false,
      );
    });
  }

  Future<void> setCameraAutofocus(bool enabled) async {
    await _runCommand(() async {
      cameraFocus = await _api!.setCameraFocus(autoEnabled: enabled);
    });
  }

  Future<void> _runCommand(Future<void> Function() command) async {
    final api = _api;
    if (api == null) {
      return;
    }
    busy = true;
    error = null;
    notifyListeners();
    try {
      await command();
      error = null;
    } catch (exception) {
      error = formatDeviceApiError(exception);
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  void _startLoops() {
    _stopLoops();
    _captureTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => refreshCapture(),
    );
    _previewTimer = Timer.periodic(
      const Duration(milliseconds: 900),
      (_) => refreshPreview(),
    );
    _slowTimer = Timer.periodic(
      const Duration(seconds: 12),
      (_) => refreshAll(),
    );
  }

  void _stopLoops() {
    _captureTimer?.cancel();
    _previewTimer?.cancel();
    _slowTimer?.cancel();
    _captureTimer = null;
    _previewTimer = null;
    _slowTimer = null;
  }

  String _tokenKey(String deviceKey) => 'openaria.echo.mobile.token.$deviceKey';

  @override
  void dispose() {
    _stopLoops();
    _api?.close();
    super.dispose();
  }
}
