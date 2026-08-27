# Release

Open Aria Echo / Mobile releases are produced by GitHub Actions in the public
`Alpenl/openaria-echo-mobile` repository.

## Release Flow

1. Update the Flutter version in `pubspec.yaml`.
   The `version:` field controls Android `versionName`/`versionCode` and the
   app update manifest `version`/`versionCode`.
2. Commit the version change and any release fixes.
3. Start the `Mobile Release` workflow from GitHub Actions. Set `ref` to the
   branch, tag, or commit to build and set `release_tag` to the Release tag to
   create or update, for example `v1.0.0`.

4. The `Mobile Release` workflow builds Android release artifacts, writes
   `android-update.json` plus `SHA256SUMS.txt`, creates the Git tag if it does
   not already exist at the checked-out commit, and creates or updates the
   GitHub Release for that tag.

Pushing a `v*` tag also runs the same release build. Manual Release runs are not
dry-runs; after a successful build they publish the GitHub Release. Use the
separate `Mobile CI` workflow for validation that should not publish.

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

If no Android signing secrets are configured, the release workflow can still
build Android artifacts and mark them as `unsigned`, but it refuses to publish a
GitHub Release. The app-consumed `android-update.json` must not point at an
unsigned APK. If only some signing secrets are configured, the workflow fails
instead of producing a misleading artifact.

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

- `openaria-echo-mobile-<tag>-android-signed.apk`
- `openaria-echo-mobile-<tag>-android-signed.aab`
- `android-update.json`
- `SHA256SUMS.txt`

The same files are also available as workflow artifacts during the Actions run.
If signing secrets are absent, unsigned workflow artifacts may be produced for
inspection, but the GitHub Release is not published.
