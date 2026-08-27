import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:openaria_echo_mobile/src/app_update.dart';

void main() {
  test('checks, downloads, verifies, and hands APK to installer', () async {
    final apkBytes = utf8.encode('signed apk bytes');
    final apkDigest = sha256.convert(apkBytes).toString();
    final temp = await Directory.systemTemp.createTemp('openaria-update-test-');
    final installed = <File>[];
    final progress = <AppUpdateDownloadProgress>[];

    final service = AppUpdateService(
      client: MockClient((request) async {
        if (request.url.path.endsWith('android-update.json')) {
          return http.Response(
            jsonEncode({
              'schema': 'openaria.echo.mobile.android-update.v1',
              'version': '1.1.0',
              'versionCode': 2,
              'packageName': androidApplicationId,
              'pubDate': '2026-08-27T00:00:00Z',
              'notes': 'Updater test',
              'android': {
                'apk': {
                  'url':
                      'https://github.com/Alpenl/openaria-echo-mobile/releases/download/v1.1.0/app.apk',
                  'sha256': apkDigest,
                  'bytes': apkBytes.length,
                },
              },
            }),
            200,
          );
        }
        if (request.url.path.endsWith('/app.apk')) {
          return http.Response.bytes(apkBytes, 200);
        }
        return http.Response('not found', 404);
      }),
      currentBuildNumber: () async => 1,
      tempDirectory: () async => temp,
      installer: (apk) async {
        installed.add(apk);
        expect(await apk.readAsBytes(), apkBytes);
      },
    );

    try {
      final result = await service.check();
      expect(result.currentBuildNumber, 1);
      expect(result.manifest?.versionCode, 2);

      await service.downloadAndInstall(
        result.manifest!,
        onProgress: progress.add,
      );

      expect(installed, hasLength(1));
      expect(installed.single.path, endsWith('openaria-echo-mobile-2.apk'));
      expect(progress.last.downloadedBytes, apkBytes.length);
      expect(progress.last.totalBytes, apkBytes.length);
    } finally {
      service.close();
      await temp.delete(recursive: true);
    }
  });

  test('rejects a manifest for a different Android package', () async {
    final service = AppUpdateService(
      client: MockClient((_) async {
        return http.Response(
          jsonEncode({
            'schema': 'openaria.echo.mobile.android-update.v1',
            'version': '1.1.0',
            'versionCode': 2,
            'packageName': 'com.example.other',
            'android': {
              'apk': {
                'url':
                    'https://github.com/Alpenl/openaria-echo-mobile/releases/download/v1.1.0/app.apk',
                'sha256': List.filled(64, 'a').join(),
                'bytes': 1,
              },
            },
          }),
          200,
        );
      }),
      currentBuildNumber: () async => 1,
      installer: (_) async {},
    );

    try {
      await expectLater(service.check(), throwsA(isA<AppUpdateException>()));
    } finally {
      service.close();
    }
  });
}
