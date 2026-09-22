# Brush Alarm

Android alarm-clock MVP that keeps ringing until the front camera detects 30 seconds of live brushing-like motion around a visible face.

## Privacy

Camera frames are processed on-device and are not recorded, saved, or uploaded.

## Important limitation

A normal consumer Android app cannot prevent OS-level Force stop, uninstall, reboot, or permission revocation. This app blocks in-app back navigation, uses a full-screen alarm, keeps the screen awake, and runs ringing audio as an ongoing foreground service.

## Build

Requires Android 12 or newer, Google Play services, JDK 17, and Android SDK 35. On first install, Google Play services may need a moment and network access to download the on-device face detector.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```
