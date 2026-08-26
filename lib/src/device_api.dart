import 'dart:convert';
import 'dart:async';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:uuid/uuid.dart';

import 'models.dart';

const deviceApiBasePath = '/api/v4';
const deviceApiConsumerSupport = {
  'schema': 'ylx.device-api-consumer-support.v1',
  'consumer': 'openaria-echo-mobile',
  'supported_device_api_majors': [4],
  'unknown_major_policy': 'fail_closed',
};

Uri normalizeDeviceBaseUri(String input) {
  var value = input.trim();
  if (value.isEmpty) {
    throw const FormatException('Device address is empty');
  }
  if (!value.contains('://')) {
    value = 'http://$value';
  }
  final uri = Uri.parse(value);
  if (uri.host.isEmpty) {
    throw const FormatException('Device address must include a host');
  }
  var path = uri.path;
  path = path.replaceFirst(RegExp(r'/api/v\d+/?$'), '');
  if (path == '/') {
    path = '';
  }
  return uri.replace(path: path, query: null, fragment: null);
}

Uri apiUri(Uri baseUri, String path, [Map<String, String>? query]) {
  final normalizedPath = path.startsWith('/') ? path : '/$path';
  final basePath = baseUri.path.endsWith('/')
      ? baseUri.path.substring(0, baseUri.path.length - 1)
      : baseUri.path;
  return baseUri.replace(
    path: '$basePath$deviceApiBasePath$normalizedPath',
    queryParameters: query,
    fragment: null,
  );
}

String formatDeviceApiError(Object error) {
  if (error is DeviceApiException) {
    return error.message;
  }
  if (error is FormatException) {
    return error.message;
  }
  return error.toString();
}

class DeviceApiClient {
  DeviceApiClient({
    required this.baseUri,
    this.accessToken,
    http.Client? httpClient,
  }) : _http = httpClient ?? http.Client();

  final Uri baseUri;
  final String? accessToken;
  final http.Client _http;

  Map<String, String> _headers(String accept, {bool post = false}) {
    final headers = <String, String>{'Accept': accept};
    final token = accessToken?.trim();
    if (token != null && token.isNotEmpty) {
      headers['Authorization'] = 'Bearer $token';
      if (post) {
        headers['X-CSRF-Token'] = token;
      }
    }
    return headers;
  }

  Map<String, String> _jsonHeaders({bool post = false}) {
    return {
      ..._headers('application/json', post: post),
      'Content-Type': 'application/json',
    };
  }

  Future<DeviceApiException> _apiError(http.Response response) async {
    try {
      final decoded = jsonDecode(response.body);
      final envelope = decoded is Map
          ? asJsonMap(decoded, 'error envelope')
          : <String, dynamic>{};
      final error =
          envelope['schema'] == 'ylx.api-error.v2' && envelope['error'] is Map
          ? asJsonMap(envelope['error'], 'error')
          : <String, dynamic>{};
      return DeviceApiException(
        stringField(
          error,
          'message',
          fallback: 'Device API returned ${response.statusCode}',
        ),
        response.statusCode,
        stringField(error, 'code', fallback: 'http_${response.statusCode}'),
      );
    } catch (_) {
      return DeviceApiException(
        'Device API returned ${response.statusCode}',
        response.statusCode,
        'http_${response.statusCode}',
      );
    }
  }

  Future<JsonMap?> _requestJson(
    String path, {
    String method = 'GET',
    Object? body,
    Map<String, String>? query,
  }) async {
    final uri = apiUri(baseUri, path, query);
    final normalizedMethod = method.toUpperCase();
    final response = switch (normalizedMethod) {
      'GET' => await _http.get(uri, headers: _headers('application/json')),
      'POST' => await _http.post(
        uri,
        headers: _jsonHeaders(post: true),
        body: body == null ? null : jsonEncode(body),
      ),
      _ => throw ArgumentError('Unsupported method $method'),
    };
    if (response.statusCode == 404) {
      return null;
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw await _apiError(response);
    }
    if (response.statusCode == 204 || response.body.isEmpty) {
      return null;
    }
    final decoded = jsonDecode(response.body);
    return asJsonMap(decoded, path);
  }

  Future<DeviceDescriptor> getDevice() async {
    final json = await _requestJson('/device');
    if (json == null) {
      throw const DeviceApiException(
        'Device descriptor was empty',
        502,
        'empty_response',
      );
    }
    return DeviceDescriptor.fromJson(json);
  }

  Future<CaptureStatus> getCaptureStatus() async {
    final json = await _requestJson('/capture/status');
    if (json == null) {
      throw const DeviceApiException(
        'Capture status was empty',
        502,
        'empty_response',
      );
    }
    return CaptureStatus.fromJson(json);
  }

  Future<SafeSwapReceipt?> getSafeSwap() async {
    final json = await _requestJson('/capture/safe-swap');
    if (json == null) {
      return null;
    }
    return SafeSwapReceipt.fromJson(asJsonMap(json['receipt'], 'receipt'));
  }

  Future<SessionList> listSessions({int limit = 25, String? cursor}) async {
    final query = <String, String>{'limit': '$limit'};
    if (cursor != null && cursor.isNotEmpty) {
      query['cursor'] = cursor;
    }
    final json = await _requestJson('/sessions', query: query);
    if (json == null) {
      return const SessionList(items: [], nextCursor: null);
    }
    return SessionList.fromJson(json);
  }

  Future<SessionDetailView> getSession(String sessionId) async {
    final json = await _requestJson(
      '/sessions/${Uri.encodeComponent(sessionId)}',
    );
    if (json == null) {
      throw const DeviceApiException(
        'Session detail was empty',
        502,
        'empty_response',
      );
    }
    return SessionDetailView.fromJson(json);
  }

  Uri artifactUri(String sessionId, String artifactId) {
    return apiUri(
      baseUri,
      '/sessions/${Uri.encodeComponent(sessionId)}/artifacts/${Uri.encodeComponent(artifactId)}',
    );
  }

  Future<NetworkStatusView?> getNetwork() async {
    final json = await _requestJson('/network');
    if (json == null) {
      return null;
    }
    return NetworkStatusView.fromJson(json);
  }

  Future<List<NetworkScanEntry>> scanNetworks() async {
    final json = await _requestJson('/network/scan');
    if (json == null || stringField(json, 'schema') != 'ylx.network-scan.v1') {
      throw const DeviceApiException(
        'Unsupported network scan schema.',
        502,
        'unsupported_device_api_schema',
      );
    }
    return (json['networks'] as List? ?? const [])
        .whereType<Object>()
        .map((item) => NetworkScanEntry.fromJson(asJsonMap(item, 'network')))
        .toList(growable: false);
  }

  Future<String> createNetworkCredential(String passphrase) async {
    final json = await _requestJson(
      '/network/credentials',
      method: 'POST',
      body: {
        'schema': 'ylx.network-credential-request.v1',
        'passphrase': passphrase,
      },
    );
    if (json == null ||
        stringField(json, 'schema') != 'ylx.network-credential-receipt.v1') {
      throw const DeviceApiException(
        'Unsupported network credential receipt schema.',
        502,
        'unsupported_device_api_schema',
      );
    }
    return stringField(json, 'credential_ref');
  }

  Future<void> applyNetwork(Map<String, Object?> desired) async {
    await _requestJson(
      '/network/apply',
      method: 'POST',
      body: {'schema': 'ylx.network-apply-request.v1', 'desired': desired},
    );
  }

  Future<CameraFocusStatus?> getCameraFocus() async {
    final json = await _requestJson('/camera/focus');
    return json == null ? null : CameraFocusStatus.fromJson(json);
  }

  Future<CameraFocusStatus> setCameraFocus({
    double? value,
    bool? autoEnabled,
  }) async {
    final body = <String, Object?>{'schema': 'ylx.camera-focus-set.v1'};
    if (value != null) {
      body['value'] = value;
    }
    if (autoEnabled != null) {
      body['auto_enabled'] = autoEnabled;
    }
    final json = await _requestJson(
      '/camera/focus',
      method: 'POST',
      body: body,
    );
    if (json == null) {
      throw const DeviceApiException(
        'Camera focus response was empty',
        502,
        'empty_response',
      );
    }
    return CameraFocusStatus.fromJson(json);
  }

  Future<CaptureStatus> startCapture({String? displayName}) async {
    final normalizedName = displayName?.trim();
    final json = await _requestJson(
      '/capture/start',
      method: 'POST',
      body: {
        'schema': 'ylx.capture-start.v2',
        'mode': 'production',
        if (normalizedName != null && normalizedName.isNotEmpty)
          'display_name': normalizedName,
        'take': {'kind': 'new'},
      },
    );
    if (json == null) {
      throw const DeviceApiException(
        'Start capture response was empty',
        502,
        'empty_response',
      );
    }
    return CaptureStatus.fromJson(json);
  }

  Future<CaptureStatus> stopCapture({required String reason}) async {
    final json = await _requestJson(
      '/capture/stop',
      method: 'POST',
      body: {'schema': 'ylx.capture-stop.v2', 'reason': reason},
    );
    if (json == null) {
      throw const DeviceApiException(
        'Stop capture response was empty',
        502,
        'empty_response',
      );
    }
    return CaptureStatus.fromJson(json);
  }

  Future<PreviewFrame> getPreviewFrame() async {
    final response = await _http.get(
      apiUri(baseUri, '/preview', {'t': const Uuid().v4()}),
      headers: _headers('image/jpeg'),
    );
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw await _apiError(response);
    }
    final contentType = response.headers['content-type']
        ?.split(';')
        .first
        .trim();
    if (contentType != 'image/jpeg') {
      throw const DeviceApiException(
        'Device preview is not JPEG.',
        502,
        'invalid_preview_content_type',
      );
    }
    return PreviewFrame(
      bytes: Uint8List.fromList(response.bodyBytes),
      receivedAt: DateTime.now(),
    );
  }

  Stream<JsonMap> captureEvents() async* {
    final request = http.Request('GET', apiUri(baseUri, '/capture/events'));
    request.headers.addAll(_headers('text/event-stream'));
    final response = await _http.send(request);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final body = await response.stream.bytesToString();
      throw DeviceApiException(
        body.isEmpty
            ? 'Device event stream returned ${response.statusCode}'
            : body,
        response.statusCode,
        'http_${response.statusCode}',
      );
    }

    var buffer = '';
    await for (final chunk in response.stream.transform(utf8.decoder)) {
      buffer += chunk;
      var boundary = _eventBoundary(buffer);
      while (boundary != null) {
        final block = buffer.substring(0, boundary.start);
        buffer = buffer.substring(boundary.end);
        final event = _parseSseBlock(block);
        if (event != null) {
          yield event;
        }
        boundary = _eventBoundary(buffer);
      }
    }
  }

  void close() {
    _http.close();
  }
}

RegExpMatch? _eventBoundary(String buffer) {
  return RegExp(r'\r?\n\r?\n').firstMatch(buffer);
}

JsonMap? _parseSseBlock(String block) {
  String? id;
  var eventName = 'message';
  final data = <String>[];
  for (final line in block.split(RegExp(r'\r?\n'))) {
    if (line.isEmpty || line.startsWith(':')) {
      continue;
    }
    final separator = line.indexOf(':');
    final field = separator == -1 ? line : line.substring(0, separator);
    final raw = separator == -1 ? '' : line.substring(separator + 1);
    final value = raw.startsWith(' ') ? raw.substring(1) : raw;
    switch (field) {
      case 'id':
        id = value;
      case 'event':
        eventName = value;
      case 'data':
        data.add(value);
    }
  }
  if (id == null || data.isEmpty) {
    return null;
  }
  final decoded = jsonDecode(data.join('\n'));
  final payload = asJsonMap(decoded, 'capture event');
  if (stringField(payload, 'schema') != 'ylx.capture-event.v4' ||
      stringField(payload, 'sse_delivery_id') != id ||
      stringField(payload, 'type') != eventName) {
    throw const DeviceApiException(
      'Capture event does not match its SSE envelope.',
      502,
      'invalid_sse_envelope',
    );
  }
  return payload;
}
