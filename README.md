# CamLight

Android app that uses the phone camera, pointed at a TV, to drive a WLED LED strip as ambient (bias) lighting. The camera samples the picture around the TV edges and streams the colors to WLED over the local network (DDP, UDP port 4048).

The full design, findings and decisions live in [SPEC.md](SPEC.md).

## Features

- **Strip setup wizard**: walk the strip around the TV and set how many LEDs each side has, with the real strip lighting up live.
- **Automatic TV detection**: lights the strip, compares frames with the strip on and off, and finds the TV as the dark shape in the wall glow.
- **Light modes**: Basic, Natural, Room light, Cinema, Vivid, plus black and white calibration.
- **Remote control page**: the phone serves a web page (live view, corner dragging with a magnifier, every setting) so it can be controlled from a laptop, another phone or a TV browser while the phone sits mounted with its screen facing away.
- Camera speed 30 or 60 fps; output paced at 30 packets per second for the controller.

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36). Create `local.properties` with `sdk.dir=<path to the SDK>`.

```
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Reference setup

Redmi Note 13 Pro on a shelf about 10 ft from the TV, WLED 0.15.x on an ESP32 (Gledopto GL-C-010-WL), WS2812 strip facing the wall. See SPEC.md section 15.
