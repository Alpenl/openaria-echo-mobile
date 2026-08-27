# Open Aria Echo / Mobile

Open Aria Echo / Mobile is the Android native client for discovering nearby
Open Aria Conductor devices and controlling capture at the recording site.

The app has two product surfaces:

- **Discovery and connection**, aligned with Bridge / Desktop: scan LAN mDNS
  services, manually add a device address, probe Device API v4, connect, and
  retain the access token in the platform secure store.
- **Connected control**, aligned with Echo / Web: render preview frames, show
  authoritative device/capture state, start and stop recording, inspect
  sessions, view device health, configure network mode, and adjust
  camera focus when the device exposes that capability.
- **Application updates**, aligned with Score D-050: check for Android updates,
  download APK bytes inside the app, verify the release SHA-256, and hand the
  APK to the Android package installer.

Conductor owns device state, capture lifecycle, storage, networking, and Device
API behavior. Echo / Mobile only sends current commands and projects Conductor
facts. It must not infer recording success or device state from button taps or
local timers, and it does not expose the retired safe-swap workflow.

## Repository

- Public repository: <https://github.com/Alpenl/openaria-echo-mobile>
- Product area: Open Aria Echo / Mobile
- Reference clients:
  - Echo / Web: <https://github.com/Alpenl/openaria-echo-web>
  - Bridge / Desktop: <https://github.com/Alpenl/openaria-bridge-desktop>

The legacy private repository `mirrorbloom/ylx-preview` is historical reference
material only. This repository is the public mobile app development line.

## Development

Prerequisites:

- Flutter 3.44 or newer
- Android SDK for Android builds

Run locally:

```bash
flutter pub get
flutter analyze
flutter test
flutter run
```

Build artifacts:

```bash
flutter build apk --debug
```

## Device Discovery

The app scans these mDNS service names:

- `_ylx-capture._tcp`
- `_http._tcp`

mDNS is treated only as candidate discovery. The app probes `/api/v4/device` and
requires `schema = ylx.device.v4` with Device API major `4` before showing a
candidate as connectable. Manual addresses are normalized to the device origin,
so `10.42.0.1:8080`, `http://10.42.0.1:8080`, and
`http://10.42.0.1:8080/api/v4` all target the same API root.

## GitHub Actions

CI runs on pull requests and pushes to `main`:

- `flutter analyze`
- `flutter test`
- Android debug APK build

Release builds run from the `Mobile Release` workflow on `v*` tags. The workflow
uploads Android APK/AAB artifacts, `android-update.json`, release notes, and
`SHA256SUMS.txt`. Android release signing is driven by repository secrets and
never falls back to the debug key. There is no iOS workflow, artifact, or
release target. See [docs/RELEASE.md](docs/RELEASE.md).

## License

See [LICENSE](LICENSE).
