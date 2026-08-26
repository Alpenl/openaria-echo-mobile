import 'package:flutter_test/flutter_test.dart';
import 'package:openaria_echo_mobile/src/device_api.dart';
import 'package:openaria_echo_mobile/src/models.dart';

void main() {
  test('normalizes manual device addresses to a stable origin', () {
    expect(
      normalizeDeviceBaseUri('10.42.0.1:8080').toString(),
      'http://10.42.0.1:8080',
    );
    expect(
      normalizeDeviceBaseUri('http://10.42.0.1:8080/api/v4').toString(),
      'http://10.42.0.1:8080',
    );
    expect(
      apiUri(
        normalizeDeviceBaseUri('https://rp-ylx.local'),
        '/device',
      ).toString(),
      'https://rp-ylx.local/api/v4/device',
    );
  });

  test('fails closed on unsupported Device API major', () {
    expect(
      () => DeviceDescriptor.fromJson({
        'schema': 'ylx.device.v3',
        'api_version': '3.0.0',
        'device': {'device_id': 'device-1', 'device_label': 'YLX-00000001'},
        'capabilities': {},
        'storage': {},
        'runtime': {},
      }),
      throwsA(isA<DeviceApiException>()),
    );
  });

  test('parses capture status with authoritative recording state', () {
    final status = CaptureStatus.fromJson({
      'schema': 'ylx.capture-status.v4',
      'authority_epoch': 'epoch-1',
      'source_revision': 7,
      'snapshot': {
        'schema': 'ylx.capture-snapshot-event.v4',
        'device_state': 'recording',
        'active_recording': {
          'generation_id': 'generation-1',
          'recording_state': {
            'state': 'recording',
            'session_id': 'session-1',
            'display_name': 'Test take',
            'storage': {'volume_id': 'volume-1'},
            'progress': {
              'elapsed_seconds': 3,
              'captured_frames': 90,
              'bytes_written': 1024,
            },
            'diagnostics': [],
          },
        },
        'retained_unsuccessful': null,
        'runtime': {
          'camera': {'state': 'connected'},
          'live_imu': null,
        },
      },
    });

    expect(status.sourceRevision, 7);
    expect(status.snapshot.deviceState, 'recording');
    expect(
      status.snapshot.activeRecording?.recordingState.sessionId,
      'session-1',
    );
    expect(
      status.snapshot.activeRecording?.recordingState.progress.capturedFrames,
      90,
    );
  });

  test(
    'collects artifacts from nested session detail without guessing paths',
    () {
      final detail = SessionDetailView.fromJson({
        'session_id': 'session-1',
        'manifest_id': 'manifest-1',
        'display_name': 'Test take',
        'capture_mode': 'production',
        'sealed': true,
        'device': {'device_label': 'YLX-00000001'},
        'time': {'duration_seconds': 12},
        'video': {
          'segments': [
            {
              'artifacts': {
                'left': {
                  'artifact_id': 'sha256-left',
                  'role': 'left_video',
                  'media_type': 'video/mp4',
                  'path': 'video/left.mp4',
                  'bytes': 2048,
                  'sha256': 'left',
                },
              },
            },
          ],
        },
        'imu': {
          'artifact': {
            'artifact_id': 'sha256-imu',
            'role': 'imu',
            'media_type': 'application/octet-stream',
            'path': 'imu/raw.bin',
            'bytes': 128,
            'sha256': 'imu',
          },
        },
      });

      expect(detail.artifacts.map((artifact) => artifact.path), [
        'imu/raw.bin',
        'video/left.mp4',
      ]);
    },
  );
}
