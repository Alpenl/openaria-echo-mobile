import 'dart:typed_data';

typedef JsonMap = Map<String, dynamic>;

JsonMap asJsonMap(Object? value, [String field = 'value']) {
  if (value is Map<String, dynamic>) {
    return value;
  }
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  throw FormatException('$field must be a JSON object');
}

String stringField(JsonMap json, String key, {String fallback = ''}) {
  final value = json[key];
  return value is String ? value : fallback;
}

String? nullableStringField(JsonMap json, String key) {
  final value = json[key];
  return value is String ? value : null;
}

num numberField(JsonMap json, String key, {num fallback = 0}) {
  final value = json[key];
  return value is num ? value : fallback;
}

bool boolField(JsonMap json, String key, {bool fallback = false}) {
  final value = json[key];
  return value is bool ? value : fallback;
}

int? deviceApiMajor(String? apiVersion) {
  if (apiVersion == null) {
    return null;
  }
  final match = RegExp(r'^(\d+)(?:\.|$)').firstMatch(apiVersion);
  return match == null ? null : int.tryParse(match.group(1)!);
}

class DeviceApiException implements Exception {
  const DeviceApiException(this.message, this.status, this.code);

  final String message;
  final int status;
  final String code;

  @override
  String toString() => '$message ($status/$code)';
}

class DeviceIdentity {
  const DeviceIdentity({required this.deviceId, required this.deviceLabel});

  factory DeviceIdentity.fromJson(JsonMap json) {
    return DeviceIdentity(
      deviceId: stringField(json, 'device_id'),
      deviceLabel: stringField(json, 'device_label', fallback: 'Open Aria'),
    );
  }

  final String deviceId;
  final String deviceLabel;
}

class StorageStatus {
  const StorageStatus({
    required this.volumeId,
    required this.totalBytes,
    required this.availableBytes,
    required this.writable,
  });

  factory StorageStatus.fromJson(JsonMap json) {
    return StorageStatus(
      volumeId: nullableStringField(json, 'volume_id'),
      totalBytes: numberField(json, 'total_bytes').toInt(),
      availableBytes: numberField(json, 'available_bytes').toInt(),
      writable: boolField(json, 'writable'),
    );
  }

  final String? volumeId;
  final int totalBytes;
  final int availableBytes;
  final bool writable;
}

class DeviceCapabilities {
  const DeviceCapabilities({
    required this.capture,
    required this.preview,
    required this.rangeDownload,
    required this.networkMutation,
  });

  factory DeviceCapabilities.fromJson(JsonMap json) {
    return DeviceCapabilities(
      capture: boolField(json, 'capture', fallback: true),
      preview: boolField(json, 'preview', fallback: true),
      rangeDownload: boolField(json, 'range_download', fallback: true),
      networkMutation: boolField(json, 'network_mutation'),
    );
  }

  final bool capture;
  final bool preview;
  final bool rangeDownload;
  final bool networkMutation;
}

class Vector3 {
  const Vector3({required this.x, required this.y, required this.z});

  factory Vector3.fromJson(JsonMap json) {
    return Vector3(
      x: numberField(json, 'x').toDouble(),
      y: numberField(json, 'y').toDouble(),
      z: numberField(json, 'z').toDouble(),
    );
  }

  final double x;
  final double y;
  final double z;
}

class LiveImu {
  const LiveImu({
    required this.sessionId,
    required this.accelerometer,
    required this.gyroscope,
    required this.syncQuality,
  });

  factory LiveImu.fromJson(JsonMap json) {
    final raw = asJsonMap(json['raw'], 'live_imu.raw');
    return LiveImu(
      sessionId: stringField(json, 'session_id'),
      accelerometer: Vector3.fromJson(
        asJsonMap(raw['accelerometer'], 'accelerometer'),
      ),
      gyroscope: Vector3.fromJson(asJsonMap(raw['gyroscope'], 'gyroscope')),
      syncQuality: stringField(
        asJsonMap(json['sync'], 'sync'),
        'quality',
        fallback: 'unknown',
      ),
    );
  }

  final String sessionId;
  final Vector3 accelerometer;
  final Vector3 gyroscope;
  final String syncQuality;
}

class RuntimeStatus {
  const RuntimeStatus({
    required this.observedAt,
    required this.connectionMethod,
    required this.temperatureCelsius,
    required this.cameraState,
    required this.liveImu,
  });

  factory RuntimeStatus.fromJson(JsonMap json) {
    final camera = json['camera'] is Map
        ? asJsonMap(json['camera'], 'camera')
        : <String, dynamic>{};
    return RuntimeStatus(
      observedAt: stringField(json, 'observed_at'),
      connectionMethod: stringField(
        json,
        'connection_method',
        fallback: 'unknown',
      ),
      temperatureCelsius: numberField(json, 'temperature_celsius').toDouble(),
      cameraState: stringField(camera, 'state', fallback: 'unknown'),
      liveImu: json['live_imu'] == null
          ? null
          : LiveImu.fromJson(asJsonMap(json['live_imu'], 'live_imu')),
    );
  }

  final String observedAt;
  final String connectionMethod;
  final double temperatureCelsius;
  final String cameraState;
  final LiveImu? liveImu;
}

class CameraFocusStatus {
  const CameraFocusStatus({
    required this.value,
    required this.minimum,
    required this.maximum,
    required this.step,
    required this.autoSupported,
    required this.autoEnabled,
  });

  factory CameraFocusStatus.fromJson(JsonMap json) {
    return CameraFocusStatus(
      value: numberField(json, 'value').toDouble(),
      minimum: numberField(json, 'minimum').toDouble(),
      maximum: numberField(json, 'maximum').toDouble(),
      step: numberField(json, 'step', fallback: 1).toDouble(),
      autoSupported: boolField(json, 'auto_supported'),
      autoEnabled: json['auto_enabled'] is bool
          ? json['auto_enabled'] as bool
          : null,
    );
  }

  final double value;
  final double minimum;
  final double maximum;
  final double step;
  final bool autoSupported;
  final bool? autoEnabled;
}

class DeviceDescriptor {
  const DeviceDescriptor({
    required this.schema,
    required this.device,
    required this.apiVersion,
    required this.buildCommit,
    required this.packageVersion,
    required this.capabilities,
    required this.storage,
    required this.runtime,
    required this.raw,
  });

  factory DeviceDescriptor.fromJson(JsonMap json) {
    final build = json['build'] is Map
        ? asJsonMap(json['build'], 'build')
        : <String, dynamic>{};
    final descriptor = DeviceDescriptor(
      schema: nullableStringField(json, 'schema'),
      device: DeviceIdentity.fromJson(asJsonMap(json['device'], 'device')),
      apiVersion: nullableStringField(json, 'api_version'),
      buildCommit: stringField(build, 'commit'),
      packageVersion: stringField(build, 'package_version'),
      capabilities: DeviceCapabilities.fromJson(
        json['capabilities'] is Map
            ? asJsonMap(json['capabilities'], 'capabilities')
            : <String, dynamic>{},
      ),
      storage: StorageStatus.fromJson(
        json['storage'] is Map
            ? asJsonMap(json['storage'], 'storage')
            : <String, dynamic>{},
      ),
      runtime: RuntimeStatus.fromJson(
        json['runtime'] is Map
            ? asJsonMap(json['runtime'], 'runtime')
            : <String, dynamic>{},
      ),
      raw: json,
    );
    final major = deviceApiMajor(descriptor.apiVersion);
    if (descriptor.schema != 'ylx.device.v4' || major != 4) {
      throw const DeviceApiException(
        'Unsupported Device API major. Echo Mobile requires Device API v4.',
        426,
        'unsupported_device_api_major',
      );
    }
    return descriptor;
  }

  final String? schema;
  final DeviceIdentity device;
  final String? apiVersion;
  final String buildCommit;
  final String packageVersion;
  final DeviceCapabilities capabilities;
  final StorageStatus storage;
  final RuntimeStatus runtime;
  final JsonMap raw;
}

class Diagnostic {
  const Diagnostic({
    required this.code,
    required this.severity,
    required this.message,
  });

  factory Diagnostic.fromJson(JsonMap json) {
    return Diagnostic(
      code: stringField(json, 'code'),
      severity: stringField(json, 'severity'),
      message: stringField(json, 'message'),
    );
  }

  final String code;
  final String severity;
  final String message;
}

class RecordingProgress {
  const RecordingProgress({
    required this.elapsedSeconds,
    required this.capturedFrames,
    required this.bytesWritten,
  });

  factory RecordingProgress.fromJson(JsonMap json) {
    return RecordingProgress(
      elapsedSeconds: numberField(json, 'elapsed_seconds').toDouble(),
      capturedFrames: numberField(json, 'captured_frames').toInt(),
      bytesWritten: numberField(json, 'bytes_written').toInt(),
    );
  }

  final double elapsedSeconds;
  final int capturedFrames;
  final int bytesWritten;
}

class RecordingState {
  const RecordingState({
    required this.state,
    required this.sessionId,
    required this.displayName,
    required this.volumeId,
    required this.progress,
    required this.diagnostics,
  });

  factory RecordingState.fromJson(JsonMap json) {
    final diagnostics = (json['diagnostics'] as List? ?? const [])
        .whereType<Object>()
        .map((item) => Diagnostic.fromJson(asJsonMap(item, 'diagnostic')))
        .toList(growable: false);
    return RecordingState(
      state: stringField(json, 'state'),
      sessionId: stringField(json, 'session_id'),
      displayName: stringField(json, 'display_name'),
      volumeId: stringField(
        json['storage'] is Map
            ? asJsonMap(json['storage'], 'recording.storage')
            : <String, dynamic>{},
        'volume_id',
      ),
      progress: RecordingProgress.fromJson(
        json['progress'] is Map
            ? asJsonMap(json['progress'], 'progress')
            : <String, dynamic>{},
      ),
      diagnostics: diagnostics,
    );
  }

  final String state;
  final String sessionId;
  final String displayName;
  final String volumeId;
  final RecordingProgress progress;
  final List<Diagnostic> diagnostics;
}

class RecordingGeneration {
  const RecordingGeneration({
    required this.generationId,
    required this.recordingState,
  });

  factory RecordingGeneration.fromJson(JsonMap json) {
    return RecordingGeneration(
      generationId: stringField(json, 'generation_id'),
      recordingState: RecordingState.fromJson(
        asJsonMap(json['recording_state'], 'recording_state'),
      ),
    );
  }

  final String generationId;
  final RecordingState recordingState;
}

class CaptureSnapshot {
  const CaptureSnapshot({
    required this.deviceState,
    required this.activeRecording,
    required this.retainedUnsuccessful,
    required this.runtime,
  });

  factory CaptureSnapshot.fromJson(JsonMap json) {
    if (stringField(json, 'schema') != 'ylx.capture-snapshot-event.v4') {
      throw const DeviceApiException(
        'Unsupported capture snapshot schema.',
        502,
        'unsupported_device_api_schema',
      );
    }
    return CaptureSnapshot(
      deviceState: stringField(json, 'device_state', fallback: 'unknown'),
      activeRecording: json['active_recording'] == null
          ? null
          : RecordingGeneration.fromJson(
              asJsonMap(json['active_recording'], 'active_recording'),
            ),
      retainedUnsuccessful: json['retained_unsuccessful'] == null
          ? null
          : RecordingGeneration.fromJson(
              asJsonMap(json['retained_unsuccessful'], 'retained_unsuccessful'),
            ),
      runtime: RuntimeStatus.fromJson(
        json['runtime'] is Map
            ? asJsonMap(json['runtime'], 'runtime')
            : <String, dynamic>{},
      ),
    );
  }

  final String deviceState;
  final RecordingGeneration? activeRecording;
  final RecordingGeneration? retainedUnsuccessful;
  final RuntimeStatus runtime;
}

class CaptureStatus {
  const CaptureStatus({
    required this.authorityEpoch,
    required this.sourceRevision,
    required this.snapshot,
  });

  factory CaptureStatus.fromJson(JsonMap json) {
    if (stringField(json, 'schema') != 'ylx.capture-status.v4') {
      throw const DeviceApiException(
        'Unsupported capture status schema.',
        502,
        'unsupported_device_api_schema',
      );
    }
    return CaptureStatus(
      authorityEpoch: stringField(json, 'authority_epoch'),
      sourceRevision: numberField(json, 'source_revision').toInt(),
      snapshot: CaptureSnapshot.fromJson(
        asJsonMap(json['snapshot'], 'snapshot'),
      ),
    );
  }

  final String authorityEpoch;
  final int sourceRevision;
  final CaptureSnapshot snapshot;
}

class SafeSwapReceipt {
  const SafeSwapReceipt({
    required this.sessionId,
    required this.volumeId,
    required this.generationId,
    required this.releaseState,
    required this.openHandleCount,
  });

  factory SafeSwapReceipt.fromJson(JsonMap json) {
    if (stringField(json, 'schema') != 'ylx.safe-swap-receipt.v3') {
      throw const DeviceApiException(
        'Unsupported safe-swap receipt schema.',
        502,
        'unsupported_device_api_schema',
      );
    }
    return SafeSwapReceipt(
      sessionId: stringField(json, 'session_id'),
      volumeId: stringField(json, 'volume_id'),
      generationId: stringField(json, 'generation_id'),
      releaseState: stringField(json, 'release_state'),
      openHandleCount: numberField(
        json,
        'open_handle_count',
        fallback: -1,
      ).toInt(),
    );
  }

  final String sessionId;
  final String volumeId;
  final String generationId;
  final String releaseState;
  final int openHandleCount;
}

class SessionSummary {
  const SessionSummary({
    required this.sessionId,
    required this.displayName,
    required this.producerOutcome,
    required this.durationSeconds,
    required this.totalBytes,
    required this.verdict,
  });

  factory SessionSummary.fromJson(JsonMap json) {
    final verification = json['verification'];
    String? verdict;
    if (verification is Map) {
      verdict = nullableStringField(
        asJsonMap(verification, 'verification'),
        'verdict',
      );
    }
    return SessionSummary(
      sessionId: stringField(json, 'session_id'),
      displayName: stringField(json, 'display_name'),
      producerOutcome: stringField(json, 'producer_outcome'),
      durationSeconds: numberField(json, 'duration_seconds').toDouble(),
      totalBytes: numberField(json, 'total_bytes').toInt(),
      verdict: verdict,
    );
  }

  final String sessionId;
  final String displayName;
  final String producerOutcome;
  final double durationSeconds;
  final int totalBytes;
  final String? verdict;
}

class SessionList {
  const SessionList({required this.items, required this.nextCursor});

  factory SessionList.fromJson(JsonMap json) {
    return SessionList(
      items: (json['items'] as List? ?? const [])
          .whereType<Object>()
          .map((item) => SessionSummary.fromJson(asJsonMap(item, 'session')))
          .toList(growable: false),
      nextCursor: nullableStringField(json, 'next_cursor'),
    );
  }

  final List<SessionSummary> items;
  final String? nextCursor;
}

class SessionArtifactView {
  const SessionArtifactView({
    required this.artifactId,
    required this.role,
    required this.mediaType,
    required this.path,
    required this.bytes,
    required this.sha256,
  });

  factory SessionArtifactView.fromJson(JsonMap json) {
    return SessionArtifactView(
      artifactId: stringField(json, 'artifact_id'),
      role: stringField(json, 'role'),
      mediaType: stringField(json, 'media_type'),
      path: stringField(json, 'path'),
      bytes: numberField(json, 'bytes').toInt(),
      sha256: stringField(json, 'sha256'),
    );
  }

  final String artifactId;
  final String role;
  final String mediaType;
  final String path;
  final int bytes;
  final String sha256;
}

class SessionDetailView {
  const SessionDetailView({
    required this.sessionId,
    required this.manifestId,
    required this.displayName,
    required this.captureMode,
    required this.sealed,
    required this.durationSeconds,
    required this.deviceLabel,
    required this.artifacts,
  });

  factory SessionDetailView.fromJson(JsonMap json) {
    return SessionDetailView(
      sessionId: stringField(json, 'session_id'),
      manifestId: stringField(json, 'manifest_id'),
      displayName: stringField(json, 'display_name'),
      captureMode: stringField(json, 'capture_mode'),
      sealed: boolField(json, 'sealed'),
      durationSeconds: numberField(
        json['time'] is Map
            ? asJsonMap(json['time'], 'time')
            : <String, dynamic>{},
        'duration_seconds',
      ).toDouble(),
      deviceLabel: stringField(
        json['device'] is Map
            ? asJsonMap(json['device'], 'device')
            : <String, dynamic>{},
        'device_label',
      ),
      artifacts: collectSessionArtifacts(json),
    );
  }

  final String sessionId;
  final String manifestId;
  final String displayName;
  final String captureMode;
  final bool sealed;
  final double durationSeconds;
  final String deviceLabel;
  final List<SessionArtifactView> artifacts;
}

List<SessionArtifactView> collectSessionArtifacts(Object? value) {
  final artifacts = <String, SessionArtifactView>{};

  void visit(Object? node) {
    if (node is Map) {
      final map = asJsonMap(node, 'artifact node');
      if (map.containsKey('artifact_id') &&
          map.containsKey('path') &&
          map.containsKey('role') &&
          map.containsKey('sha256')) {
        final artifact = SessionArtifactView.fromJson(map);
        artifacts[artifact.artifactId] = artifact;
        return;
      }
      for (final child in map.values) {
        visit(child);
      }
    } else if (node is List) {
      for (final child in node) {
        visit(child);
      }
    }
  }

  visit(value);
  final result = artifacts.values.toList(growable: false);
  result.sort((a, b) => a.path.compareTo(b.path));
  return result;
}

class NetworkInterfaceView {
  const NetworkInterfaceView({
    required this.name,
    required this.state,
    required this.addresses,
    required this.peerOrSsid,
  });

  factory NetworkInterfaceView.fromJson(String name, JsonMap json) {
    return NetworkInterfaceView(
      name: name,
      state: stringField(json, 'state', fallback: 'unknown'),
      addresses: (json['addresses'] as List? ?? const [])
          .whereType<String>()
          .toList(growable: false),
      peerOrSsid: nullableStringField(json, 'peer_or_ssid'),
    );
  }

  final String name;
  final String state;
  final List<String> addresses;
  final String? peerOrSsid;
}

class NetworkStatusView {
  const NetworkStatusView({
    required this.mode,
    required this.verified,
    required this.defaultRoute,
    required this.ap,
    required this.wifiClient,
    required this.wired,
    required this.mutationEnabled,
    required this.disabledReason,
  });

  factory NetworkStatusView.fromJson(JsonMap json) {
    if (stringField(json, 'schema') != 'ylx.network-status.v1') {
      throw const DeviceApiException(
        'Unsupported network status schema.',
        502,
        'unsupported_device_api_schema',
      );
    }
    final desired = json['desired'] is Map
        ? asJsonMap(json['desired'], 'desired')
        : <String, dynamic>{};
    final observed = json['observed'] is Map
        ? asJsonMap(json['observed'], 'observed')
        : <String, dynamic>{};
    final mutation = json['mutation_capability'] is Map
        ? asJsonMap(json['mutation_capability'], 'mutation_capability')
        : <String, dynamic>{};
    return NetworkStatusView(
      mode: stringField(desired, 'mode', fallback: 'unknown'),
      verified: boolField(json, 'verified'),
      defaultRoute: stringField(observed, 'default_route', fallback: 'none'),
      ap: NetworkInterfaceView.fromJson(
        'AP',
        observed['ap'] is Map
            ? asJsonMap(observed['ap'], 'ap')
            : <String, dynamic>{},
      ),
      wifiClient: NetworkInterfaceView.fromJson(
        'Wi-Fi',
        observed['wifi_client'] is Map
            ? asJsonMap(observed['wifi_client'], 'wifi_client')
            : <String, dynamic>{},
      ),
      wired: NetworkInterfaceView.fromJson(
        'Ethernet',
        observed['wired'] is Map
            ? asJsonMap(observed['wired'], 'wired')
            : <String, dynamic>{},
      ),
      mutationEnabled: boolField(mutation, 'enabled'),
      disabledReason: nullableStringField(mutation, 'disabled_reason'),
    );
  }

  final String mode;
  final bool verified;
  final String defaultRoute;
  final NetworkInterfaceView ap;
  final NetworkInterfaceView wifiClient;
  final NetworkInterfaceView wired;
  final bool mutationEnabled;
  final String? disabledReason;
}

class NetworkScanEntry {
  const NetworkScanEntry({
    required this.ssid,
    required this.security,
    required this.signalDbm,
    required this.credentialRequired,
  });

  factory NetworkScanEntry.fromJson(JsonMap json) {
    return NetworkScanEntry(
      ssid: nullableStringField(json, 'ssid'),
      security: stringField(json, 'security', fallback: 'open'),
      signalDbm: numberField(json, 'signal_dbm').toInt(),
      credentialRequired: boolField(json, 'credential_required'),
    );
  }

  final String? ssid;
  final String security;
  final int signalDbm;
  final bool credentialRequired;
}

class DeviceEndpoint {
  const DeviceEndpoint({
    required this.baseUri,
    required this.source,
    required this.host,
    required this.port,
    this.serviceName,
    this.device,
    this.error,
  });

  final Uri baseUri;
  final String source;
  final String host;
  final int port;
  final String? serviceName;
  final DeviceDescriptor? device;
  final String? error;

  String get title => device?.device.deviceLabel ?? host;
  String get subtitle =>
      '${baseUri.origin}${serviceName == null ? '' : '  $serviceName'}';
  String get storageKey => device?.device.deviceId ?? baseUri.origin;

  DeviceEndpoint copyWith({DeviceDescriptor? device, String? error}) {
    return DeviceEndpoint(
      baseUri: baseUri,
      source: source,
      host: host,
      port: port,
      serviceName: serviceName,
      device: device ?? this.device,
      error: error,
    );
  }
}

class PreviewFrame {
  const PreviewFrame({required this.bytes, required this.receivedAt});

  final Uint8List bytes;
  final DateTime receivedAt;
}
