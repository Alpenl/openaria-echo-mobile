import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';

const defaultAndroidUpdateManifestUrl =
    'https://github.com/Alpenl/openaria-echo-mobile/releases/latest/download/android-update.json';
final defaultAndroidUpdateManifestUri = Uri.parse(
  defaultAndroidUpdateManifestUrl,
);

typedef CurrentBuildNumberLoader = Future<int> Function();
typedef TempDirectoryLoader = Future<Directory> Function();
typedef ApkInstaller = Future<void> Function(File apk);

const androidApplicationId = 'com.openaria.openaria_echo_mobile';

class AppUpdateException implements Exception {
  const AppUpdateException(this.message);

  final String message;

  @override
  String toString() => message;
}

enum AppUpdatePhase {
  idle,
  checking,
  current,
  available,
  downloading,
  installing,
  failed,
}

class AppUpdateStatus {
  const AppUpdateStatus({
    required this.phase,
    this.currentBuildNumber,
    this.manifest,
    this.downloadedBytes = 0,
    this.totalBytes,
    this.message,
  });

  const AppUpdateStatus.idle() : this(phase: AppUpdatePhase.idle);

  final AppUpdatePhase phase;
  final int? currentBuildNumber;
  final AndroidUpdateManifest? manifest;
  final int downloadedBytes;
  final int? totalBytes;
  final String? message;

  bool get canCheck =>
      phase != AppUpdatePhase.checking &&
      phase != AppUpdatePhase.downloading &&
      phase != AppUpdatePhase.installing;
  bool get canInstall => phase == AppUpdatePhase.available && manifest != null;
}

class AndroidUpdateArtifact {
  const AndroidUpdateArtifact({
    required this.url,
    required this.sha256,
    required this.bytes,
  });

  factory AndroidUpdateArtifact.fromJson(Map<String, dynamic> json) {
    final url = Uri.tryParse(json['url'] as String? ?? '');
    final sha256 = json['sha256'] as String? ?? '';
    final bytes = json['bytes'];
    if (url == null || !url.hasScheme || url.scheme != 'https') {
      throw const FormatException('android.apk.url must be an HTTPS URL');
    }
    if (!RegExp(r'^[0-9a-fA-F]{64}$').hasMatch(sha256)) {
      throw const FormatException(
        'android.apk.sha256 must be a 64-character hex digest',
      );
    }
    if (bytes is! int || bytes <= 0) {
      throw const FormatException(
        'android.apk.bytes must be a positive integer',
      );
    }
    return AndroidUpdateArtifact(
      url: url,
      sha256: sha256.toLowerCase(),
      bytes: bytes,
    );
  }

  final Uri url;
  final String sha256;
  final int bytes;
}

class AndroidUpdateManifest {
  const AndroidUpdateManifest({
    required this.version,
    required this.versionCode,
    required this.packageName,
    required this.pubDate,
    required this.notes,
    required this.apk,
  });

  factory AndroidUpdateManifest.fromJson(Map<String, dynamic> json) {
    if (json['schema'] != 'openaria.echo.mobile.android-update.v1') {
      throw const FormatException('unsupported update manifest schema');
    }
    final android = json['android'];
    final apk = android is Map<String, dynamic> ? android['apk'] : null;
    final version = json['version'] as String? ?? '';
    final versionCode = json['versionCode'];
    final packageName = json['packageName'] as String? ?? '';
    if (version.trim().isEmpty) {
      throw const FormatException('version must be non-empty');
    }
    if (versionCode is! int || versionCode <= 0) {
      throw const FormatException('versionCode must be a positive integer');
    }
    if (packageName.trim().isEmpty) {
      throw const FormatException('packageName must be non-empty');
    }
    if (apk is! Map<String, dynamic>) {
      throw const FormatException('android.apk must be an object');
    }
    return AndroidUpdateManifest(
      version: version,
      versionCode: versionCode,
      packageName: packageName,
      pubDate: json['pubDate'] as String?,
      notes: json['notes'] as String?,
      apk: AndroidUpdateArtifact.fromJson(apk),
    );
  }

  final String version;
  final int versionCode;
  final String packageName;
  final String? pubDate;
  final String? notes;
  final AndroidUpdateArtifact apk;
}

class AppUpdateCheckResult {
  const AppUpdateCheckResult({
    required this.currentBuildNumber,
    required this.manifest,
  });

  final int currentBuildNumber;
  final AndroidUpdateManifest? manifest;
}

class AppUpdateDownloadProgress {
  const AppUpdateDownloadProgress({
    required this.downloadedBytes,
    required this.totalBytes,
  });

  final int downloadedBytes;
  final int? totalBytes;
}

class AndroidApkInstaller {
  const AndroidApkInstaller();

  static const _channel = MethodChannel(
    'com.openaria.openaria_echo_mobile/app_update',
  );

  Future<void> install(File apk) async {
    await _channel.invokeMethod<void>('installApk', {'path': apk.path});
  }
}

class AppUpdateService {
  AppUpdateService({
    http.Client? client,
    Uri? manifestUri,
    CurrentBuildNumberLoader? currentBuildNumber,
    TempDirectoryLoader? tempDirectory,
    ApkInstaller? installer,
    this._expectedPackageName = androidApplicationId,
  }) : _client = client ?? http.Client(),
       _manifestUri = manifestUri ?? defaultAndroidUpdateManifestUri,
       _currentBuildNumber = currentBuildNumber ?? _loadCurrentBuildNumber,
       _tempDirectory = tempDirectory ?? getTemporaryDirectory,
       _installer = installer ?? const AndroidApkInstaller().install;

  final http.Client _client;
  final Uri _manifestUri;
  final CurrentBuildNumberLoader _currentBuildNumber;
  final TempDirectoryLoader _tempDirectory;
  final ApkInstaller _installer;
  final String _expectedPackageName;

  Future<AppUpdateCheckResult> check() async {
    final currentBuild = await _currentBuildNumber();
    final response = await _client.get(_manifestUri);
    if (response.statusCode == 404) {
      return AppUpdateCheckResult(
        currentBuildNumber: currentBuild,
        manifest: null,
      );
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw AppUpdateException(
        'Update manifest request failed: HTTP ${response.statusCode}',
      );
    }
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const AppUpdateException('Update manifest must be a JSON object');
    }
    final manifest = AndroidUpdateManifest.fromJson(decoded);
    if (manifest.packageName != _expectedPackageName) {
      throw AppUpdateException(
        'Update package mismatch: expected $_expectedPackageName, got ${manifest.packageName}',
      );
    }
    return AppUpdateCheckResult(
      currentBuildNumber: currentBuild,
      manifest: manifest.versionCode > currentBuild ? manifest : null,
    );
  }

  Future<void> downloadAndInstall(
    AndroidUpdateManifest manifest, {
    required void Function(AppUpdateDownloadProgress progress) onProgress,
  }) async {
    final temp = await _tempDirectory();
    final updateDir = Directory('${temp.path}${Platform.pathSeparator}updates');
    await updateDir.create(recursive: true);
    final target = File(
      '${updateDir.path}${Platform.pathSeparator}openaria-echo-mobile-${manifest.versionCode}.apk',
    );
    final partial = File('${target.path}.part');
    await _deleteIfPresent(partial);
    await _deleteIfPresent(target);

    final request = http.Request('GET', manifest.apk.url);
    final response = await _client.send(request);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw AppUpdateException(
        'APK download failed: HTTP ${response.statusCode}',
      );
    }

    final sink = partial.openWrite();
    var downloaded = 0;
    try {
      await for (final chunk in response.stream) {
        downloaded += chunk.length;
        sink.add(chunk);
        onProgress(
          AppUpdateDownloadProgress(
            downloadedBytes: downloaded,
            totalBytes: response.contentLength ?? manifest.apk.bytes,
          ),
        );
      }
    } finally {
      await sink.close();
    }

    final digest = sha256.convert(await partial.readAsBytes()).toString();
    if (downloaded != manifest.apk.bytes) {
      await _deleteIfPresent(partial);
      throw AppUpdateException(
        'APK size mismatch: expected ${manifest.apk.bytes}, got $downloaded',
      );
    }
    if (digest != manifest.apk.sha256) {
      await _deleteIfPresent(partial);
      throw const AppUpdateException('APK SHA-256 verification failed');
    }

    await partial.rename(target.path);
    await _installer(target);
  }

  void close() => _client.close();

  static Future<int> _loadCurrentBuildNumber() async {
    final info = await PackageInfo.fromPlatform();
    return int.tryParse(info.buildNumber) ?? 0;
  }
}

Future<void> _deleteIfPresent(File file) async {
  try {
    if (await file.exists()) {
      await file.delete();
    }
  } catch (_) {
    // A stale partial should not hide the real update error.
  }
}

String formatAppUpdateError(Object error) {
  if (error is AppUpdateException) return error.message;
  if (error is FormatException) return error.message;
  return error.toString();
}
