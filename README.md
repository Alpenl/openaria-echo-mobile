# Open Aria Echo Mobile

Open Aria Echo Mobile is now a native Android app. The Flutter implementation is preserved on the `flutter-bak` branch.

The current app implements the Aperture viewfinder body design from `tmp/aperture-app.html` as a full-screen native Android `Activity` backed by a custom `View` and Canvas drawing. The first screen is the camera body, not a settings form: mount a body, switch between `RECORD`, `ROLL`, `BODY`, and `NET`, open focus controls, review sessions, and inspect artifacts.

## Build

```bash
./gradlew assembleDebug
```

The Android package name remains `com.openaria.openaria_echo_mobile`.
