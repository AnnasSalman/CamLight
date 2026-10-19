# CamLight

<img src="docs/icon-512.png" alt="CamLight icon" width="96" align="right">

[![Build](https://github.com/AnnasSalman/CamLight/actions/workflows/build.yml/badge.svg)](https://github.com/AnnasSalman/CamLight/actions/workflows/build.yml)

Android app that uses the phone camera, pointed at a TV, to drive a WLED LED strip as ambient (bias) lighting. The camera samples the picture around the TV edges and streams the colors to WLED over the local network (DDP, UDP port 4048).

The full design, findings and decisions live in [SPEC.md](SPEC.md).

## Features

- **Strip setup wizard**: walk the strip around the TV and set how many LEDs each side has, with the real strip lighting up live.
- **Automatic TV detection**: lights the strip, compares frames with the strip on and off, and finds the TV as the dark shape in the wall glow.
- **Light modes**: Basic, Natural, Room light, Cinema, Vivid, plus black and white calibration.
- **Remote control page**: the phone serves a web page (live view, corner dragging with a magnifier, every setting) so it can be controlled from a laptop, another phone or a TV browser while the phone sits mounted with its screen facing away.
- **TV test page**: open `http://<phone-ip>:<port>/p/<code>` in the TV's browser and the phone shows black, white and color patterns on it for calibration.
- Camera speed 30 or 60 fps; output paced at 30 packets per second for the controller.

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36). Create `local.properties` with `sdk.dir=<path to the SDK>`.

```
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Installing

- **From GitHub:** every push to `main` runs the *Build* workflow (tests plus a debug APK). The APK is kept as the `camlight-debug-apk` artifact for 14 days. With the GitHub CLI and a phone connected over USB:
  ```
  .\tools\install-ci-apk.ps1
  ```
- **Without a cable:** `.\tools\serve-apk.ps1` serves the APK on your Wi-Fi and prints an address to open in the phone's browser.
- **Signing:** the Build workflow signs with the same debug key as the developer's PC (stored as the `DEBUG_KEYSTORE_BASE64` repository secret), so builds from either place can update each other. Without the secret it uses a fresh key and the APK can not update an app signed with another key.

## Reference setup

Redmi Note 13 Pro on a shelf about 10 ft from the TV, WLED 0.15.x on an ESP32 (Gledopto GL-C-010-WL), WS2812 strip facing the wall. See SPEC.md section 15.
