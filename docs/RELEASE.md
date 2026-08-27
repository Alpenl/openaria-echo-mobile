# Release

Open Aria Echo / Mobile releases are produced by GitHub Actions in the public
`Alpenl/openaria-echo-mobile` repository.

## Release Flow

1. Update the Flutter version in `pubspec.yaml`.
   The `version:` field controls Android `versionName`/`versionCode` and the
   app update manifest `version`/`versionCode`.
2. Commit the version change and any release fixes.
3. Create an annotated release tag:

   ```bash
   git tag -a v0.1.0 -m "Open Aria Echo Mobile v0.1.0"
   git push origin main
   git push origin v0.1.0
   ```

4. The `Mobile Release` workflow builds Android release artifacts, writes
   `android-update.json` plus `SHA256SUMS.txt`, and creates or updates the
   GitHub Release for the tag.

Manual workflow runs are supported for dry-run validation. Set `ref` to the
branch, tag, or commit to build, set `release_tag` to the filename tag to use,
and leave `publish` disabled. Enabling `publish` requires `release_tag` to
already exist as a Git tag.

## Android Signing

Release signing is optional but explicit. Android release builds never fall back
to the debug key.

For local signed builds, create `android/key.properties`:

```properties
storeFile=upload-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is resolved relative to `android/app`, so the example above expects
the keystore at `android/app/upload-keystore.jks`. The repository ignores
`android/key.properties`, `*.keystore`, and `*.jks`.

For GitHub Actions signed builds, configure all four repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded contents of the JKS/keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If no Android signing secrets are configured, the release workflow still builds
Android release artifacts and marks them as `unsigned`. If only some signing
secrets are configured, the workflow fails instead of producing a misleading
artifact.

## In-App Updates

The Android app checks
`https://github.com/Alpenl/openaria-echo-mobile/releases/latest/download/android-update.json`.
The manifest binds the released APK to:

- `schema`: `openaria.echo.mobile.android-update.v1`
- `version` and `versionCode` from `pubspec.yaml`
- `packageName`: `com.openaria.openaria_echo_mobile`
- `android.apk.url`, `android.apk.sha256`, and `android.apk.bytes`

The app downloads the APK inside the app, verifies size and SHA-256, then hands
the APK to the Android package installer. Android still asks the user to confirm
installation and enforces same-package signing for upgrades.

There is no iOS release workflow, release asset, update manifest entry, or
support target.

## Artifacts

Each release contains:

- `openaria-echo-mobile-<tag>-android-signed.apk` or
  `openaria-echo-mobile-<tag>-android-unsigned.apk`
- `openaria-echo-mobile-<tag>-android-signed.aab` or
  `openaria-echo-mobile-<tag>-android-unsigned.aab`
- `android-update.json`
- `SHA256SUMS.txt`

The same files are also available as workflow artifacts during the Actions run.
