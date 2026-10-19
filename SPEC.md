# Spec: CamLight – Camera-based TV Ambient Lighting for WLED (Android)

Version 0.1 · 2026-10-18 · Status: Draft for review

---

## 1. Purpose and goals

An Android app that uses the phone's rear camera, pointed at a TV, to continuously sample the picture and stream matching colors to a WLED-controlled LED strip on the local network (TV bias lighting / "Ambilight").

| Goal | Target |
|---|---|
| Color accuracy | Perceived match on typical content; measured mean ΔE2000 ≤ 8 on a calibration set (≤ 5 on neutral greys), hue-correct on skin tones and greens |
| Latency (glass-to-LED) | ≤ 80 ms typical, ≤ 120 ms worst case. Feels "in sync" with the picture |
| Update rate | 60 fps output if the camera supports it, otherwise 30 fps |
| Stability | No flicker on static scenes, no visible banding from TV PWM or mains-frequency light |
| Runtime | Hours of continuous use without thermal throttling or the OS killing the app |

Non-goals (v1): capturing DRM content via screen capture, controlling the TV, audio reactivity, non-WLED outputs (kept behind an interface for later, e.g. Hyperion/HyperHDR, Adalight).

### Why camera capture
Other approaches (HDMI grabber + Hyperion/HyperHDR, Android `MediaProjection` grabbers) are more accurate but need hardware, or fail on DRM apps (Netflix etc. show black) and on TVs that draw video on a separate hardware layer. A camera sees what the viewer sees, works with every source, and needs no wiring. The price is accuracy: commercial camera kits are reported to struggle with greens, skin tones and desaturated yellow/red gradients, and to wash out if the picture is too bright for the camera exposure. This spec's main differentiators are therefore **calibration** and **exposure control**.

---

## 2. Prior art and what we take from it

| Project | Takeaway |
|---|---|
| **Ambient Light TV** (Android, `com.ambientlight.tv`) | Closest existing app: samples screen or camera, user marks four screen corners, outputs to WLED/Hyperion/Adalight. Validates the UX of corner calibration + WLED discovery. |
| **Hyperion / HyperHDR** | Mature pipeline: sampling zones, per-channel calibration, LUT-based color correction, smoothing integrated with the LED driver clock (v22 adds "Hybrid Inertia" smoothing and temporal dithering). Copy the idea: smoothing must be driven by the output clock, with fast response to scene changes. |
| **hyperion-android-reborn** | Reduce capture resolution/rate if the device stutters. Confirms DRM and hardware-layer limits of screen capture. |
| **Govee Envisual / camera kits** | Camera + fisheye correction improves edge accuracy; reviewers still see color errors. Confirms the need for perspective + lens correction and a color matrix. |
| **WLED realtime protocols** | See section 7. |

---

## 3. System overview

```
 Camera2 (YUV_420_888, low-res, 60/30 fps, AE/AWB locked)
        │  newest frame only (drop stale)
        ▼
 Zone sampler  ── homography (4 corners) + inset ──► per-LED sample patches
        │  (reads Y/U/V at sample points only)
        ▼
 Color pipeline: YUV→linear RGB → black-level → white balance → 3×3 matrix → gamut/sat → LED gamma curve
        ▼
 Temporal filter (adaptive, scene-cut aware)
        ▼
 Output: DDP (UDP 4048) ── Wi-Fi ──► WLED (ESP32)
```

Single foreground service owns the camera, processor and network socket. UI is a thin client that binds to it.

---

## 4. Camera capture

### 4.1 API choice
Use **Camera2 directly** (not CameraX `ImageAnalysis`). Reasons: precise control of AE/AWB lock, exposure time, ISO, frame-rate range, noise reduction/edge modes, and plain access to `ImageReader`. Community reports show CameraX `ImageAnalysis` throttled around 16 fps on some devices while Camera2 reaches 30 fps on most and 60 fps on some (anecdotal, so verify per device).

### 4.2 Stream configuration
- Format `YUV_420_888`, resolution **640×480 or 1280×720** (smallest size that keeps the TV ≥ ~300 px wide in frame; we only need ~20×12 zones of data). Higher resolution only costs latency and heat.
- `ImageReader` with `maxImages = 3`; consume with `acquireLatestImage()` on a dedicated high-priority thread so we always process the newest frame and drop the rest. Close images immediately.
- Target FPS: query `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. The user chooses **30 or 60 fps** (setting *Camera speed*, on the phone and on the remote page, **default 30**). The app picks a fixed `[N,N]` range at the largest YUV size that supports it (1080p, else 720p) and falls back to the other rate if the chosen one is unavailable; the screen shows the rate actually in use. Changing it restarts the camera session. Rationale: LED output is capped at 30 packets/s by the controller, so 60 fps only shortens frame age by roughly 16 ms at the cost of CPU, heat and (at 1080p) more bandwidth; 30 fps is the safer default and 60 is there for comparison. Keep multiple requests in flight (repeating request) because the Camera2 pipeline needs that for full frame rate.
- **Preview is drawn from the analysis frames**, not from a separate camera preview surface. A platform preview (TextureView/SurfaceView) applies its own rotation and scaling, which produced a stretched/rotated image and handles that moved on the wrong axis in the first build. Rendering the same YUV frames we sample (downscaled to ~640×360, ~15 fps, rotated by one known angle from sensor orientation and display rotation) guarantees that what the user sees, the corner handles and the sample positions all share one coordinate system.
- Camera selection: the main (wide) rear lens, not ultra-wide (heavy distortion, worse sensor). Optional: user-selectable lens ID. Apply `DISTORTION_CORRECTION_MODE_FAST` if supported.
- Latency-oriented request flags: `NOISE_REDUCTION_MODE_FAST/OFF`, `EDGE_MODE_OFF`, `COLOR_CORRECTION_ABERRATION_MODE_OFF`, video stabilization off, flash off, `CONTROL_CAPTURE_INTENT_VIDEO_RECORD`. (Test per device; some HALs add frames of latency in certain modes.)

### 4.3 Exposure and white balance: the key to accuracy
Auto exposure/white balance fight the content: a bright scene makes AE darken everything, a dark scene makes it boost noise and shift colors, and AWB reinterprets the colors of the picture itself. Therefore:

1. **Setup phase**: run auto AE/AWB while the user shows a mid-bright reference pattern, then **lock** `CONTROL_AE_LOCK` and `CONTROL_AWB_LOCK` (or go fully manual with `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, and `COLOR_CORRECTION_GAINS` + transform where `MANUAL_SENSOR`/`MANUAL_POST_PROCESSING` are supported).
2. **Exposure**: choose exposure low enough that the brightest TV content (full white at the user's usual brightness) is below ~85% of sensor range (no clipping), while dark scenes stay above noise floor. Provide an on-screen clipping indicator (zebra) and a histogram.
3. **Flicker/banding**: TVs refresh at 50/60/100/120 Hz (and many OLED/LED backlights PWM). Exposure time should be an integer multiple of the display period (e.g. 1/60 s, 1/120 s or 1/100 s; ≥ 8.3 ms avoids most rolling-shutter bands). Set `CONTROL_AE_ANTIBANDING_MODE` to match the region while in auto and expose a "Flicker" slider in manual. Detect banding as high row-wise variance across a uniform patch and warn.
4. A "Re-lock" button, plus optional slow auto-relock when the ambient room light changes (off by default).

### 4.4 Placement guidance (in-app tips)
Camera on a small stand or soft mount, as straight on as practical (centered below/above or in front of the TV), full screen visible with margin, no direct lights or reflections on the screen, phone plugged in, screen dimmed. Reduce glare by turning off bright room lights during setup.

**Reference placement: phone on a shelf across the room, ~10 ft (3 m) from the TV, in landscape.**
- At that distance a 55" TV fills only ~25–35% of the frame width at 1× zoom. The camera screen therefore has a **zoom slider** (`CONTROL_ZOOM_RATIO`, or `SCALER_CROP_REGION` below Android 11) with a tip to zoom until the TV fills ~60–70% of the width, which raises the pixels per LED sample.
- The camera screen is landscape-only (sensor-landscape, both orientations handled by flipping buffer coordinates 180° when the display rotation requires it).
- Stream choice favors **60 fps over resolution**: prefer a 60 fps YUV size (1080p, else 720p), otherwise 1080p at 30 fps. Max fps per size comes from `StreamConfigurationMap.getOutputMinFrameDuration`.
- Sample zones sit **inside the picture edge** (small inset, no bezel or wall) so the LEDs' own glow on the wall and bezel does not feed back into the measurement.
- People walking between the phone and the TV occlude samples. Mitigation is a later milestone (reject sudden single-zone drops / hold last value briefly).
- The phone should be plugged in. The camera screen keeps the display on.

---

## 5. Calibration (first-run wizard + stored profile)

### 5.1 Geometry: finding the TV (automatic, hands-off)

**Context.** The phone is on a shelf ~10 ft away and is not attached to the TV (unlike Govee/Nanoleaf cameras), so (a) the user should not have to touch the phone during calibration, and (b) the phone may be bumped later. Research found no published automatic screen-edge algorithm for those products (their setup is manual corner marking/edge tracing), so this is our own design and must be validated on the reference setup.

**Primary method: LED-halo silhouette (no TV content, works with the TV off).**
The strip faces the wall, so with the LEDs lit the wall shows a bright halo and the TV body blocks it, leaving a dark rectangle (the TV's outer silhouette) with a sharp cut-off at its edge.
1. Capture a **dark reference** (all LEDs off), average several frames.
2. Light all used LEDs at a fixed, moderate white and capture a **lit frame** (average several frames). Exposure/white balance are locked first (4.3).
3. **Difference** (lit − dark) → halo mask, independent of room light and TV content.
4. Find the dark quadrilateral enclosed by the halo: threshold the difference (Otsu), take the largest hole in the halo, and fit **four lines** to the boundary points along each side (robust least squares / RANSAC), then intersect adjacent lines to get **sub-pixel corners**.
5. Edges with no halo (a side with no LEDs, e.g. the bottom/stand, or occluded by furniture) are **inferred** from the other three lines plus a 16:9 (configurable) aspect constraint; the result is flagged lower-confidence.
6. The silhouette includes the bezel. The picture area is the silhouette inset by a per-TV **bezel inset** (default 1% of width; adjustable in fine-tuning, or measured with the bright-screen method below).
7. **Per-corner confirmation on the TV, not on the phone:** after detecting, the app lights the four corner LEDs and runs a chase around the frame; the user sees whether the lights sit on the TV edges.

**Secondary / refinement methods** (optional, selectable):
- **Bright-screen detection:** the user shows a plain white image/video on the TV once; the lit rectangle is segmented and edge lines fitted. Gives the exact picture edge (bezel inset for free).
- **Marker pattern:** an optional calibration video with corner markers (ArUco/checkerboard), shared with the color-calibration patches (5.2). Most accurate; requires playing the video.
- **Motion mask while content plays:** variance of the frame over a few seconds marks the picture area; used for drift checks, not for primary calibration.
- Not used: infrared/glow-in-the-dark stickers (rear phone cameras filter IR; glow too dim) and ML TV detectors (no precise edges; no accuracy data found).

**Fine-tuning UI (kept).** After detection, the live view shows the four corner handles. The user can drag them; while dragging, a **magnifier loupe** (3-4× zoom of the area under the finger, offset above it so the finger does not cover it) shows the corner precisely. Handles snap to detected corners when released nearby. Because the phone should be firmly mounted, touching the screen is acceptable; any small shift is compensated by 5.5.

**Compute the homography** from the four corners to a normalized 16:9 (or user-set) screen rectangle. Include an optional radial lens-distortion coefficient if not corrected by the HAL.

**Map LEDs to samples:** each LED gets a sample rectangle along its screen edge inside the picture (depth default ~8–14% of the dimension, width = LED pitch, inset from the bezel). Pre-compute the camera-space pixel set for each LED once.

### 5.2 Color: map camera RGB → what the TV intended
The camera sees the TV's panel through its own sensor, ISP and the room. Estimate a correction from measurements:

1. Wizard asks the user to play a **calibration clip/image** (we ship it and a QR/URL; the app cannot control the TV): full-screen black, white, 25/50/75% greys, saturated R, G, B, and C/M/Y, plus skin-tone and mid-green patches. Each shown ~2 s with a marker in the corner for sync.
2. For each patch capture the mean camera YUV→RGB; the known source color is the target (converted to linear RGB).
3. Fit, by least squares:
   - **Black offset** (from the black patch, includes room reflection and panel glow) subtracted before everything.
   - **Per-channel gains** (white balance) from the white/grey patches.
   - **3×3 color matrix** from the saturated and grey patches (and optionally a small 3D LUT, 9³ nodes, if a matrix leaves ΔE high on skin/green).
   - **Per-channel tone curve** (1D LUT, 256 entries) from the grey ramp.
4. Store the profile per camera/phone/TV. Show before/after preview and estimated ΔE.

### 5.3 Output look controls (user taste, applied after calibration)
Brightness (max), saturation boost (default +20%, LED backlight looks best slightly more saturated than the TV), black threshold (below which LEDs fade to off), white point tint, gamma. Presets: *Accurate*, *Vivid*, *Movie (dimmer, warmer)*, *Gaming (lowest latency, minimal smoothing)*.

**Findings from the first real test (reference setup)** — symptoms: dark scenes drift toward blue instead of fading to off; bright colors look washed out. Causes and fixes:
- *WLED color gamma 2.8 was on* (read from `/json/cfg` → `light.gc.col`). It applies `value^2.8` to every byte we send; at 8 bits, low values collapse (byte 20 → ~0–1), so a dim color keeps only its strongest channel, usually blue (blue camera/room cast + strong blue in WS2812). Fix: the app turns WLED's color gamma off (with explicit user consent, original value saved and restorable) and performs the conversion itself, with temporal dithering.
- *No black level removal.* The camera reads a TV's "black" (backlight bleed, room reflection, sensor offset) as a dim, often blue-tinted gray. Fix: subtract a per-channel black floor (learned automatically from the darkest zones, or measured by a *Calibrate black* button with the TV off/black) and apply a soft knee so near-black becomes fully off.
- *WS2812 white is cool/blue.* Equal R=G=B looks bluish. Fix: per-channel LED white-balance gains applied in linear light, controlled by a *Warmth* slider (default ≈ R 1.0 / G 0.88 / B 0.55, a starting point recalled from FastLED's "typical pixel string" correction, not verified), tuned by eye with an on-LED gray test.
- *Blue concentrated in one region (reported: top right).* Not explained by the global fixes above. Candidates: IPS glow / backlight bleed / off-axis color shift of the TV panel in dark scenes (the camera sees it truthfully), camera lens/color shading, or the LED strip itself. The on-LED gray test separates "strip" from "camera/TV": if that corner is bluer in the gray test it is the strip. Per-zone black (and later per-zone white) calibration, i.e. a flat-field correction, is the proper fix for the camera/TV case.
- *A/B testing aid.* The app ships selectable **looks A-F** (baseline; dark fade; dark fade + gray; blue guard; per-zone black; raw) and a **Speed** selector (fast/normal/smooth), switchable live with the lights on, so variants can be compared on the real TV. A 2-second log line (`adb logcat -s CamLight`) reports frame rate, processing time, frame age, UDP send time, and per side (first/middle/last third) the camera reading versus the bytes sent.
- *Lag.* Camera-to-LED latency is dominated by camera frame age, smoothing and Wi-Fi. The app holds a Wi-Fi low-latency lock while on the camera screen, and the live preview is isolated from the control panel so the UI does not redraw at preview rate.
- *Washed-out brights.* Clipped camera channels turn saturated colors pale (lower exposure until the zebra is empty), room light adds a veil, and averaging mixes colors. Fixes: saturation boost (luma-preserving), black removal, and later the color calibration in 5.2 (3×3 matrix) plus weighting toward the dominant color in a zone.

### 5.4 Strip setup: guided "walk the strip" wizard (live, on-phone)
The strip has **300 WS2812 LEDs on the controller, but only a subset is mounted on the TV**. The user must tell the app which LEDs form each TV side, while seeing the real strip react.

**Design principle: the user never types a number and never sets a "start" value.** The strip is one continuous line that goes around the TV, so each side begins where the previous one ended. The only input per side is *where this side ends* (the corner). Total input for a full TV: two taps (start corner, direction) plus one drag per side.

**Data model** (stored in the profile; all indices are in strip order):
- `totalLeds` (from WLED `/json/info`), `startCorner` (TL/TR/BR/BL), `clockwise` (bool).
- Per position `p = 0..3` along the strip: `gap[p]` (hidden LEDs before this side; default 0, used for the first LED offset or a stand gap) and `count[p]` (LEDs on this side; 0 means side not used).
- Derived: the side at each position (from corner + direction), `start[p] = sum(gap[0..p]) + sum(count[0..p-1])`, and `reversed` (true for counter-clockwise, so LED order along each edge can be mapped to screen positions for sampling). Editing one side ripples: later sides keep their counts and shift; counts are clamped so the total never exceeds `totalLeds`.

**Screens (one wizard, 6 steps, one task each):**

1. **Start.** Large TV diagram. Prompt: *"Tap the corner where the strip begins."* Tapping a corner moves a green dot there. Two big buttons: *Clockwise / Counter-clockwise*, which animate an arrow around the diagram. The first LEDs of the real strip light green so the user can find the physical start. Next.
2. **Side 1..4** (same layout, the diagram highlights the current edge, header "Top edge · 2 of 4"). Prompt: *"Drag until the lights reach the corner of your TV."*
   - One large slider sets the LED count of this side. The real strip shows: finished sides dimly in their colors, the current run in white, and the final LED of the run as a bright red marker, so the user just slides the red dot to the corner.
   - Large ◀ ▶ fine-tune buttons beside the slider (tap = ±1 LED, hold = repeat and accelerate, light haptic tick).
   - Readout: "52 LEDs".
   - Secondary actions as text buttons: *No LEDs on this side* (e.g. bottom behind the stand) and *Hidden LEDs before this side* (reveals a small − / + stepper, for a gap or a strip start offset).
   - A newly visited side starts with a sensible default run (30 LEDs) so something is already lit. *Back* / *Next* at the bottom.
3. **Review.** Diagram shows all four sides in their colors with counts as chips ("Top 52 · Right 30 …"); tapping a chip or an edge jumps back to that step. A *Play chase* toggle runs a light around the whole loop in strip order so the user can confirm continuity and direction on the TV. *Done* saves.
4. **Home** (after setup, and on every later launch): a summary of the saved layout, *Edit layout*, and a placeholder for the next milestone (*Start ambient light*). The strip returns to its normal WLED effect while on Home.

**Behavior notes**
- Streams the same DDP frames the final feature will use (no per-change HTTP), at ~30 fps, so every drag appears on the TV within a frame. The camera is not needed.
- Settings persist on every change; a saved layout opens straight to Home. Tapping the diagram's edges or the progress chips allows jumping between steps at any time.
- Setup brightness is capped (~35%) to avoid a surprise current draw.
- Accessibility: all targets ≥ 48 dp, the diagram never carries information by color alone (current edge is thicker with an arrow showing travel direction).

### 5.5 Placement guide and drift handling (phone is not rigidly attached)
- **Placement guide (before calibration):** live checks, shown as green ticks: TV fills 55-75% of frame width (adjust zoom), TV roughly centered, phone level (roll from the accelerometer), TV not cropped, exposure not clipping. Text tips: firm mount (stand with putty/gel pad or flexible tripod), loose cable with right-angle plug.
- **Hands-off flow:** Start → 10 s countdown (user steps away) → detection → verification lights on the TV.
- **Reference image:** at calibration the app stores a downsampled gray reference frame and a set of background features (outside the TV area).
- **Bump detection:** gyroscope/accelerometer (`SensorManager`) flags a physical bump/tilt immediately. Image check: compare the live frame's background features to the reference (feature matching or phase correlation) a few times per second.
- **Automatic re-anchoring:** for a small shift/rotation, estimate the transform of the background between reference and live view and apply it to the stored TV corners, so no recalibration is needed. For a large shift, pause the lights, re-run the halo detection (~1-2 s, visible flash), and if that fails prompt the user.
- **Confidence indicator** in the UI (detected / re-anchored / needs recalibration).

### 5.6 Remote control and fine-tune web page (phone screen is not reachable)
**Problem.** In the reference placement the phone sits behind the viewer with its screen facing the wall, so on-phone controls and corner dragging are unusable once mounted. Touching the phone also risks moving it.

**Solution.** While the camera screen is open, the phone runs a small HTTP server on the local network. Any browser (reference: laptop or second phone for setup; the Google TV's browser also works) opens the page and gets a live view plus every control. No separate app is needed.

- **Address and access:** `http://<phone-ip>:8080/?k=<key>`. The phone shows the address as text and a QR code on the camera screen (scan from a second phone), and logs it to logcat. `<key>` is a random 8-character secret created once and stored; every request must carry it (LAN-only, no accounts). The port falls back to the next free one if 8080 is taken.
- **Endpoints:** `GET /` (page), `GET /frame.jpg` (latest preview as JPEG, ~640×360, ~8 fps polled; `?debug=1` serves the detection image), `GET /state` (JSON), `POST /cmd` (JSON action), plus the TV test page endpoints `GET /p/<code>` (page for the TV's browser) and `GET /pattern` (which pattern to show), which accept a short 4-digit code instead of the key (see 14.4). No streaming connection is held open, so any browser works.
- **State JSON:** corner quad, detected silhouette, per-LED sample dots (x, y, color), zebra cells (already in display orientation), lights on/off, look, speed, brightness, saturation, warmth, zoom range and value, exposure range and value, lock, detection status, black calibration status, stats (fps, ms, frame age), errors.
- **Commands:** move/set corners, reset corners, detect TV, show corners on the TV, lights on/off, look A-F, speed, brightness, saturation, warmth, zoom, exposure, lock, calibrate/clear black, gray test, zebra, detection image.
- **Corner fine-tuning in the page:**
  - Drag a handle with mouse or touch; a magnifier loupe (~6× zoom of the live image around the handle, drawn above the pointer) shows the exact spot.
  - Keyboard / TV-remote D-pad: Enter or 1-4 selects the next corner, arrow keys nudge 1 preview pixel, Shift+arrow 5 pixels, key repeat accelerates. The selected corner is drawn large with the current coordinates.
  - Edits apply to the LED sample zones on the phone immediately (throttled to ~15 updates/s) and are persisted.
- **Why polling, not WebSocket/MJPEG:** a hand-rolled single-file server stays dependency-free; polling needs no persistent connections; ~300 KB/s of JPEG is negligible on Wi-Fi.
- **Lifecycle:** the server runs only while the camera screen is open (the phone must stay awake and on that screen, display dimmed). Settings changed remotely are saved immediately.
- **Security notes:** HTTP without TLS on the home LAN, protected by the key; no file access, no shell, request bodies capped at 16 KB, small connection pool. Anyone on the LAN who knows the key could change lighting settings, nothing more. The short TV-page code opens only the test page and the pattern feed (it cannot read frames, state or send commands), so someone who learns it could at most see the test screen or probe the pattern feed.

### 5.7 Light modes
The user-facing choice is a **mode**; each mode is a preset of processing parameters (and a default speed). Modes are switchable live, from the phone or the remote page. The first test variants (looks A-F) remain available as *test variants*; **A is now called Basic** and is the reference behavior (global black floor, saturation 120%, warmth 70%).

**Observations driving the new modes (reference setup, 2026-10-18):**
1. *Natural scenes (forest, landscapes) lean toward bright maroon or cyan.* Hypothesis: per-channel operations push low-saturation colors toward primaries. The saturation boost and the near-black knee act on each channel independently; a channel that goes slightly negative is clamped to 0, which turns a muted brown/green into a saturated maroon/green/cyan. Fixes: hue-preserving gamut clipping, a luma-based knee, a *vibrance* style boost that boosts already-saturated colors more than muted ones, and a lower overall boost.
2. *Yellow room bulbs (opposite the TV) make blacks read blue.* Hypothesis: the camera's auto white balance cancels the room's yellow by adding blue, so the TV's own light (and black reflections) look bluish; ambient light also reflects off the screen and into the dark zones. Fixes: lock exposure and white balance, calibrate black and white **with the room lights as they will be used**, learn a black floor per zone, attenuate color in dark zones, and optionally reduce blue there.

**Modes (all in linear light after the camera black floor is removed):**

| Mode | Intent | Key settings |
|---|---|---|
| **Basic** | Current behavior, good for bright content | global black floor, saturation 120%, warmth 70%, no dark handling |
| **Natural** | Muted, natural scenes | vibrance boost (≈105%), hue-preserving gamut clip, luma-based knee, dark zones keep 50% of their color, dark fade (0.008→0.06), warmth 50%, uses the TV-white calibration if present |
| **Room light** | Ambient light on in the room (e.g. yellow bulbs) | per-zone black floor, dark zones keep 35% color and blue is reduced there, dark fade (0.012→0.12), gamut clip, TV-white calibration, saturation 110%; hint to lock exposure & color and calibrate black/white under the room lights |
| **Cinema** | Dim, calm, dark movies | strong dark fade (0.02→0.15), dark zones keep 50% color, vibrance ≈110%, warmth 80%, output scaled to 70%, default speed Smooth |
| **Vivid** | Games, sports, animation | saturation 140%, warmth 50%, no gamut clip, default speed Fast |

**White calibration (new, with black calibration from 5.3):** with the TV showing a plain white screen, the app averages the zone colors, subtracts the black floor, and stores per-channel gains so white reads neutral to the camera (green gain = 1, clamped 0.4-2.5). It makes the camera's color cast, including the one caused by room lighting, part of the calibration instead of an error. Modes with *use white calibration* apply the gains in linear light before the saturation step. Persisted in the camera settings; a *Clear white* action returns to uncalibrated.

**Pipeline additions (per LED, linear light):** black floor (global or per zone) → white gains (if the mode uses them and they exist) → luma knee or per-channel knee → dark-zone color attenuation → saturation or vibrance → hue-preserving gamut clip → blue guard → dark fade → brightness × mode brightness scale × LED white-balance → LED encoding (see 6).
---

## 6. Processing pipeline (per frame)

Budget: **≤ 3 ms** on a mid-range phone. Do not convert the full frame.

1. For each LED sample rectangle, read a small grid of pixels (e.g. 6×6 to 10×10 samples from the Y plane, and the co-sited U/V at half resolution) via direct `ByteBuffer` access in native code (C++/NDK via JNI, or tightly written Kotlin with preallocated arrays; no per-frame allocation).
2. Convert YUV→RGB with the matrix/range reported by the capture metadata (Camera2 documents the default as JFIF/full-range BT.601 matrix with sRGB primaries). Convert to **linear light** before averaging.
3. Aggregate with a **trimmed mean** (drop brightest/darkest ~10%) or weighted median to resist subtitles, logos and specular highlights. Option: mix 70% mean + 30% dominant color so bright highlights are not averaged to grey.
4. Apply, in linear light: black floor subtraction (per channel) → soft knee near black → gains → 3×3 matrix → saturation (luma-preserving) → brightness × LED white-balance gains → clamp → encode with the **LED gamma `g`** (`out = 255·L^(1/g)`, where `g` is WLED's color gamma: 1.0 when WLED gamma is off, so the output is linear light; read from `/json/cfg`, see 7.3).
5. Temporal filter (6.1). Quantise to 8-bit with **temporal dithering** (per-LED, per-channel error carried to the next frame) to avoid banding and flicker in dark gradients; exact-zero targets reset the error so black stays fully off.
6. Hand the byte array to the sender thread (single-slot mailbox; newest wins).

Optional later: OpenGL ES / Vulkan compute path if sampling count grows (>300 LEDs). Not needed for v1.

### 6.1 Temporal filtering (noise vs lag)
- Camera sensor noise at low light causes flicker, but lag is the enemy, so use an **adaptive filter**: per LED, exponential smoothing whose alpha rises with the size of the change (One-Euro-filter style). Small changes (noise) are smoothed heavily; large changes pass nearly unfiltered.
- **Scene-cut detection**: if mean absolute change across all LEDs exceeds a threshold, reset the filter (jump) for that frame.
- Per-channel brightness limiting rate (slew) only when going *down* in very dark scenes if flicker is visible.
- Fixed parameters per preset; expose a single "Smoothness" slider mapped to alpha curves.
- Default smoothing time constant ≈ 40–60 ms for steady content, ≈ 0 for cuts.

---

## 7. Network output to WLED

### 7.1 Protocol
Primary: **DDP** (Distributed Display Protocol), UDP port **4048**, supported natively by WLED (≥ 0.13; may need enabling in Sync settings on older builds). DDP carries up to 1440 bytes/packet (480 RGB LEDs), no universe juggling, low overhead. WLED does not read the optional DDP timecodes.

Fallback: **WLED UDP Realtime** on port **21324**:

| Byte | Meaning |
|---|---|
| 0 | Protocol: 1 = WARLS (max 255 LEDs), 2 = DRGB (max 490), 3 = DRGBW (max 367), 4 = DNRGB (max 489 per packet) |
| 1 | Timeout in seconds (return to normal after last packet; 255 = never) |
| 2+ | WARLS: `idx,R,G,B` repeated; DRGB: `R,G,B` repeated from LED 0; DNRGB: 2-byte start index (hi, lo) then `R,G,B` repeated |

Use **DRGB** (full-frame) for ≤ 490 LEDs, **DNRGB** for more (multiple packets). WARLS only when few LEDs change (not our case). DRGB is fastest when all LEDs change.

Behavior to design for (per WLED docs): the web UI is disabled while realtime is active (HTTP API still works); LEDs hold colors until changed or the timeout expires; WLED applies its own master brightness and gamma settings.

Optional Phase 2: E1.31/Art-Net (not needed), Hyperion JSON (port 19446) no.

### 7.2 Transport details
- Unicast UDP via `DatagramChannel`/`DatagramSocket` bound once; reuse a pre-allocated packet buffer. No allocation in the send loop.
- Send at the capture frame rate (cap at configurable max, default 60, min 20). Also send a **keep-alive** frame every ≤ 500 ms when the picture is static so the realtime timeout (use 2 s) never expires, and one final packet with timeout=0/or a brightness command when the user stops so WLED returns to normal immediately (also via HTTP `{"live":false}` through `/json/state`).
- Hold a Wi-Fi lock (`WifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY)` on Android 10+, otherwise `FULL_HIGH_PERF`) while streaming; foreground service and partial wake lock.
- Packets from a single sender thread, drop frames rather than queue if the socket blocks.

### 7.3 WLED configuration advice (shown in a setup checklist and, where possible, applied over `/json/cfg`)
- **Disable WiFi sleep** in WLED Wi-Fi settings (a common cause of periodic stutter).
- Prefer an **ESP32** (or ESP32 with Ethernet) with decent antenna; Ethernet is the most reliable and lowest-jitter option. Community reports vary from ~5 ms to seconds of latency depending on protocol, LED count and signal strength (anecdotal).
- Enable the matching realtime protocol in Sync settings and verify the port.
- **Gamma**: WLED can apply its own color gamma (default on, 2.8 in the reference setup; `light.gc.col`). Choose exactly one place for gamma correction. Default: the app asks permission to set WLED's color gamma to 1.0 (off) via `POST /json/cfg {"light":{"gc":{"col":1.0}}}`, remembers the original value so it can be restored, and outputs linear light with its own dithering. The app always reads the current value and adapts its encoding (`out = 255·L^(1/g)`), so it stays correct even if the user keeps WLED's gamma on (at the cost of the dark-end collapse described in 5.3). A gray test on the LEDs verifies neutrality.
- Check LED data limits: some APA102 setups were unstable above ~30 fps until moved to hardware SPI pins; avoid chipsets/pins that cannot sustain the target FPS. WS281x at 30 µs/LED limits fps (≈ 1 ms/33 LEDs: 150 LEDs ≈ 4.5 ms per frame, fine for 60 fps).
- Disable WLED's own transition time/blending for realtime (not applied in realtime mode, but verify per version) and set max brightness / power limit (mA) on the device.

### 7.4 Discovery and control
- mDNS/NSD discovery of `_wled._tcp`; manual IP entry fallback; remember last IP and re-resolve by hostname (`wled-xxxx.local`) on failure.
- `GET /json/info` → LED count, version, MAC, Wi-Fi RSSI (show signal quality warning below −70 dBm).
- `POST /json/state` for on/off, brightness, and ending realtime. Optional: ping/RTT test using small UDP echo? Not supported by WLED, so use HTTP RTT as a rough indicator only.
- Phone must be on the same subnet as WLED (WLED is 2.4 GHz only; the phone can be on 5 GHz if the router bridges them). Warn about AP/client isolation.

---

## 8. Latency budget (target)

| Stage | Typical | Notes |
|---|---|---|
| Display → sensor exposure | 4–16 ms | Exposure ≈ 1/120–1/60 s |
| Sensor readout + ISP + HAL | 20–50 ms | Largest and device-dependent; 60 fps and reduced ISP processing reduce it |
| ImageReader hand-off | 0–16 ms | Latest-image strategy |
| Sampling + color pipeline | 1–3 ms | Native code, sparse sampling |
| Smoothing | 0–40 ms | Adaptive; ~0 on cuts |
| UDP send → WLED | 2–10 ms | Wi-Fi, depends on RSSI |
| LED refresh | 1–5 ms | |
| **Total** | **~40–110 ms** | Target ≤ 80 ms |

Measure, do not assume: ship a **latency test mode** (see 11).

---

## 9. App structure and UX

### 9.1 Architecture
- Kotlin, min SDK 26 (Android 8, Camera2 + modern ByteBuffer APIs), target latest. Native C++ module for the hot path.
- Modules: `camera` (Camera2 session, locks), `geometry` (homography, LED mapping), `color` (calibration solve + runtime), `filter`, `output` (interface `LedSink` → `WledDdpSink`, `WledUdpSink`), `service` (foreground service), `ui` (Jetpack Compose), `storage` (profiles as JSON in DataStore).
- Threads: camera callback thread (high priority, `THREAD_PRIORITY_URGENT_DISPLAY`), processing thread, sender thread; communication through lock-free single-slot buffers.

### 9.2 Screens
1. **Connect**: discover/choose WLED, show LED count, signal, firmware, setup checklist (7.3).
2. **Camera setup**: preview, exposure/WB lock, clipping and flicker indicators, placement tips.
3. **Geometry**: 4-corner drag, LED layout, overlay of sample zones on the live preview.
4. **Color calibration wizard**: test patches, progress, before/after.
5. **Run**: big start/stop, FPS in/out, estimated latency, dropped frames, thermal state, quick presets, brightness. Screen can go to a black "OLED-dim" overlay while keeping the service alive.
6. **Settings**: smoothing, saturation, black threshold, FPS cap, protocol, debug overlay.

### 9.3 Platform concerns
- Foreground service type `camera` with persistent notification; required runtime permissions: `CAMERA`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` (wifi lock), `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (mDNS), `NEARBY_WIFI_DEVICES` as needed. On Android 14+, a camera foreground service started in the background is restricted; the user must start it from the UI.
- Request battery-optimisation exemption and keep the screen on (dimmed) while running; Android can still throttle camera access when the app is backgrounded on some OEMs, so recommend leaving the app in foreground.
- Thermal: monitor `PowerManager.getThermalHeadroom` / thermal status; auto-drop to 30 fps / lower resolution before throttling; recommend plugged-in operation with the phone case off.
- Privacy: no frames stored or uploaded; processing is entirely on device. Say so in the UI.

---

## 10. Edge cases and known limits

- **Dark scenes**: noise dominates; apply black threshold + stronger smoothing below a luminance level.
- **Letterboxed content (cinema bars)**: black bars at top/bottom would darken the top/bottom LEDs correctly; optional detection of bars and extending sampling inward (v1.1).
- **HDR content**: camera sees the TV's rendered output, so tone mapping is already applied; calibrate with the TV in the mode normally used. Re-calibrate if the picture mode changes.
- **Screen reflections and room light**: handled partly by black offset; recommend a dim room.
- **Phone moved/bumped**: detect shift by tracking the four corners (cheap edge search around each) and prompt to recalibrate.
- **Wi-Fi drops**: show status, auto-reconnect, send keep-alives; WLED returns to its normal mode after the timeout.
- **Camera interruptions** (incoming call, other app): handle `onDisconnected`, restart session.
- **Device variance**: some phones lack manual sensor control or 60 fps in Camera2; fall back to AE/AWB lock plus 30 fps, and show capability level (`INFO_SUPPORTED_HARDWARE_LEVEL`).

---

## 11. Testing and acceptance

### 11.1 Latency measurement
- Test pattern flashing white/black at ~2 Hz on the TV; film TV + LEDs with a second phone in 240 fps slow-motion; count frames between screen change and LED change. Repeat 20 times, report median and p95.
- Built-in estimate: log timestamps `SENSOR_TIMESTAMP` → processing done → packet sent, giving the app-side portion (excluding display + LED).

### 11.2 Color accuracy
- Show a reference set (grayscale ramp, primaries/secondaries, skin tones, foliage greens, sunset gradients). Measure LED output with a colorimeter if available (or compare photographs with fixed exposure) and compute ΔE2000 vs the source colors; compare calibrated vs uncalibrated.
- Subjective A/B on real content: a few movies, sports, gaming, UI-heavy content.

### 11.3 Acceptance criteria (v1)
1. Median glass-to-LED latency ≤ 80 ms on reference device at 60 fps; ≤ 110 ms at 30 fps.
2. Sustained 30 min run at ≥ 55 fps with ≤ 1% frame drops, phone temperature stable, no OS kill.
3. Grey ramp visually neutral (no color cast); ΔE2000 targets from section 1 on the test set.
4. No visible flicker on static images; no banding artifacts at the chosen exposure.
5. Reconnect to WLED within 3 s after a Wi-Fi interruption without user action.
6. Setup (connect + geometry + color calibration) completable in under 5 minutes.

---

## 12. Milestones

| M | Status | Scope |
|---|---|---|
| M0 | Done | WLED connect + **Strip setup UI (5.4)**: discover controller, live-light a chosen side from the phone, save segments. No camera needed |
| M1 | Done | Camera2 pipeline + AE/AWB lock + device capability dump; DDP sender; sampled zones → working lights |
| M1b | Done | Preview from analysis frames; automatic TV detection via LED-halo silhouette (5.1); corner fine-tuning with magnifier; verification lights on the TV |
| M1c | Done | Remote control web page (5.6): live view, corner dragging with magnifier, D-pad nudging, all controls, QR/URL on the phone |
| M1d | Done | Light modes (5.7): Basic, Natural, Room light, Cinema, Vivid; black and white calibration; hue-preserving color processing |
| M1e | Done | Camera speed 30/60 fps (4.2); paced, de-duplicated DDP output; worker-thread preview (see 15 for the reference-setup findings) |
| M1f | Built, not yet tested on the TV | TV test page, route 1 of 14.4: `/p/<code>` page for the TV browser, `/pattern` feed with 12 patterns, TV-page status on the remote page, manual pattern buttons, *Calibrate black + white with the TV page* sequence; routes 2 and 3 (Cast, Android TV Remote protocol) not started |
| M2 | Planned | **Everyday use (14.1-14.3):** auto off for TV-off/static screens, background run with the screen off, bump detection with automatic re-anchoring |
| M3 | Planned | **Setup and profiles (14.4-14.6):** calibration test patterns served to the TV's own browser, guided setup on the remote page, lamps-on/lamps-off profiles |
| M4 | Planned | **Color quality (14.7-14.9):** letterbox/pillarbox detection, per-zone flat-field calibration, dominant-color weighting |
| M5 | Planned | **Supportability (14.10-14.12):** diagnostics export, CI build of the APK, remote-page LED strip simulator |
| M6 | Planned | **Integrations and robustness (14.13-14.16):** control API for Home Assistant, thermal management, device profiles (second phone), optional automatic mode selection |

---

## 13. Open questions / risks

1. **Latency floor depends on the phone's camera HAL** (30-60 ms is outside our control). Measure on each target phone; the app already logs frame age (`adb logcat -s CamLight`).
2. Is manual sensor control (exposure time, ISO, `COLOR_CORRECTION_GAINS`) available on each target phone, or only AE/AWB lock? This decides whether calibration profiles (14.6) can restore exact exposure or must re-lock.
3. Whether a 3×3 matrix is enough for panels with strong gamut differences (wide-gamut OLED vs sensor); a 3D LUT is the fallback.
4. A companion mode on Android TV using `MediaProjection` (no camera, exact colors) for non-DRM content is out of scope, but the frame-source interface should allow it.
5. **Camera in the background (14.2) on Xiaomi HyperOS:** even a correctly declared camera foreground service may be killed when the app is cleared from recents, and camera access while the screen is off has not been verified on the reference phones. Needs an on-device test before promising "lights with the screen off".
6. **Second phone (Tecno Spark 6, Android 10, Helio G70, 2.4 GHz Wi-Fi only):** its Camera2 hardware level, AE/AWB lock availability and frame-rate ranges are unknown until its *Camera info* output is read. See 14.15.
7. **Controller Wi-Fi jitter:** the ESP32 shows packet loss and spikes under sustained streaming even from a PC; output is paced (30 packets/s, unchanged frames skipped). Further relief would be a better channel/position or a wired controller.
8. External USB cameras are not exposed by Camera2 on most phones; supporting one means a UVC library inside the app. Not researched in depth; see 14.15.

---

## 14. Planned features (backlog)

Priority: **P1** = fixes a visible annoyance found in testing, **P2** = quality and supportability, **P3** = convenience. Dependencies are listed per item. Everything here is a design, not yet built; statements marked *(researched)* come from the sources in section 16, statements marked *(design)* are our own proposal and must be validated on the reference setup.

| # | Feature | Priority | Depends on |
|---|---|---|---|
| 14.1 | Auto off for TV-off / blank / static screens | P1 | none |
| 14.2 | Background run (foreground service, screen off) | P1 | 14.1 helps (idle camera rate) |
| 14.3 | Bump detection and automatic re-anchoring | P1 | none |
| 14.4 | Calibration test patterns shown by the TV's browser | P1 | remote page (5.6) |
| 14.5 | Guided setup flow on the remote page | P1 | 14.4 |
| 14.6 | Calibration profiles (lamps on / lamps off) | P1 | 5.7 calibrations |
| 14.7 | Letterbox / pillarbox detection | P2 | 5.1 geometry |
| 14.8 | Per-zone flat-field (black + white) calibration | P2 | 14.4, 14.6 |
| 14.9 | Dominant-color weighting | P2 | none |
| 14.10 | Diagnostics export | P2 | none |
| 14.11 | CI build of the APK on GitHub | P2 | none |
| 14.12 | LED strip simulator on the remote page | P3 | none |
| 14.13 | Control API for Home Assistant and automations | P3 | 14.2 |
| 14.14 | Thermal management | P3 | none |
| 14.15 | Device profiles and the second phone; external camera research | P3 | camera info |
| 14.16 | Automatic mode selection | P3 | 5.7 modes |

### 14.1 Auto off for TV-off, blank and static screens (P1)
**Why.** Today the LEDs show whatever the camera sees, so a TV that is off, paused or showing a blank screen still produces light (and, in the reference setup, tinted dark readings). Camera-based competitors turn the lights off automatically when they detect a blank or static screen *(researched: Govee reviews)*. Ambilight clones also gate updates on frame difference *(researched: an open-source Rust ambilight skips updates below a change threshold, then smooths)*.

**Design.**
- **Per-frame measurements** on a coarse 16×9 grid of samples *inside the picture quad* (cheap, from the existing homography): mean luma `L`, frame-to-frame change `D` (mean absolute difference vs the previous grid), and `L` relative to the calibrated black floor.
- **States:** `ACTIVE` → `BLANK` → `IDLE`, and `ACTIVE` → `STATIC` → `IDLE`.
  - `BLANK`: `L ≤ blackFloorLuma + 2 %` and `D < noiseFloor` for `blankAfter` (default 15 s).
  - `STATIC`: `D < 1 %` of full scale for `staticAfter` (default 120 s). Cinema mode doubles both timers.
  - Leave `IDLE` as soon as `D > 3 %` or `L` rises above the blank threshold for 0.5 s (wake quickly).
- **Idle action (setting):** *off* (fade to black over 2 s, then `{"live":false}`), *WLED preset* (`POST /json/state {"ps":N}`; presets are applied with `ps` *(researched: WLED JSON API)*), or *dim warm ambient* (fixed color at a set brightness).
- **While idle:** lower the camera to ~5 fps and skip the preview and zebra work to save heat and CPU; keep the check running.
- **Ignore small bright spots** (the TV's power LED) by computing `L` and `D` on the trimmed mean of the grid, not the maximum. Nanoleaf's camera was reported picking up a TV power LED *(researched: reviews)*.
- **Noise floor:** measured once per session as `D` over the first 2 s of a known-static scene, or taken from the black calibration frames.
- **UI:** status chip (Active / Blank / Static / Idle), timers and idle action on the phone and remote page, a *Wake now* button.

**Edge cases.** Dark movie scenes and credits can look "static"; mitigated by longer Cinema timers and the fast wake rule. Screensavers or slow ambient video may stay `ACTIVE`, which is acceptable. Flicker from the camera in near-black scenes must not read as motion (use the noise floor).

**Acceptance.** TV powered off → LEDs off within `blankAfter + 2 s`; paused frame → idle within `staticAfter + 2 s`; resuming playback → LEDs back within 1 s; no flapping in a 30-minute dark-film test.

### 14.2 Background run with the screen off (P1)
**Why.** With the phone mounted screen-to-wall nobody needs its display, and a continuously lit screen wastes power and generates heat.

**Platform rules *(researched, Android docs)*.**
- A camera foreground service needs `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CAMERA` in the manifest, `android:foregroundServiceType="camera"`, and the granted `CAMERA` runtime permission.
- The camera permission is *while-in-use*: on Android 14+ **a camera foreground service cannot be created while the app is in the background**, even if a background-start exemption applies. It must be started with a visible activity (or via a user tap on a notification or widget). Once running it may keep using the camera in the background, which is the stated purpose of the type.
- Consequence: **the remote page cannot start the camera from a cold, closed app.** The app must have been started once from the phone's UI (or a notification action), after which the service keeps running.
- On Xiaomi/HyperOS, background work is restricted by default; clearing the app from recents can kill even a foreground service. Required user steps: enable *Autostart*, set battery to *No restrictions*, and *lock* the app in the recents list *(researched: dontkillmyapp and vendor guides; menu names vary by version)*.

**Design.**
- `CameraService` (foreground, type camera) owns the camera engine, processor, DDP sender, remote web server and WLED client; the UI becomes a thin client that binds to it. Persistent notification with actions *Lights on/off* and *Stop*.
- **Standby state:** the service can run with the camera closed and only the web server alive (low power). *Lights on* from the remote page opens the camera inside the already-running service. Whether the camera can be opened from standby while the screen is off must be verified on the reference phones; if not, fall back to keeping the camera open at a low frame rate.
- Screen: the camera screen sets `FLAG_KEEP_SCREEN_ON` today; in service mode it is released so the screen can time out. A partial wake lock and the existing Wi-Fi low-latency lock stay held while lights are on.
- **Onboarding:** a one-time checklist screen deep-links to the app's battery, autostart and notification settings and explains the recents lock.
- **Self-check:** if the service is killed unexpectedly, the next app start shows "background run was interrupted" with the checklist.

**Acceptance.** Start lights, press the power button: lights keep following the picture for 30 minutes; the remote page still works; phone temperature stays under the thermal limit in 14.14.

### 14.3 Bump detection and automatic re-anchoring (P1)
**Why.** The phone sits on a shelf and is touched during setup; any shift invalidates the TV box (extends 5.5). Even Govee's fixed camera drifts when its adhesive weakens *(researched: forum report)*.

**Design.**
- **Bump detection (instant):** gyroscope and accelerometer via `SensorManager`. Flag a bump when angular speed exceeds 0.05 rad/s for > 100 ms or an acceleration spike exceeds 0.3 g; track pitch/roll drift from the gravity vector (> 0.5° from the calibration pose). Sensor events only *trigger* an image check; they do not move the box themselves.
- **Image registration:** keep a reference gray image (160×90 from the 640×360 preview) of the *background* (everything outside the TV box, with the LED-glow areas masked). On a trigger, and at 2 Hz in the background, estimate the translation between reference and live with **phase correlation** and cheap sub-pixel refinement *(researched: basic phase correlation gives whole-pixel peaks; sub-pixel accuracy is reached by upsampling the DFT only near the peak, or by peak fitting)*. Apply a Hann window and a high-pass to cope with low-texture shelves and walls.
  - To also capture small rotation/scale, run it on four background patches and fit a similarity transform.
- **Decision rule:** peak ratio < 0.2 → "no confident estimate". Shift within 3 % of the frame → move the stored corners by the estimated transform automatically and log it. Larger shift, or no confident estimate three checks in a row → pause lights.
- **Recovery:** if the TV is idle (14.1) run a short single-cycle halo detection (≈3 s) automatically; otherwise show "Phone moved: recalibrate" on the remote page and the phone, with a one-tap *Re-detect*.
- **Confidence indicator** in both UIs: *Detected*, *Re-anchored (Δ x px)*, *Needs recalibration*.

**Edge cases.** A moved person or furniture in the background is an outlier: use a robust (median) combination across patches. The TV picture itself must be excluded from the reference.

**Acceptance.** Nudge the phone by 5-30 px: corners follow within 1 px without user action; a 10 cm shove triggers the recalibrate prompt.

### 14.4 Calibration test patterns shown by the TV (P1)
**Why.** Black and white calibration currently asks the user to find a white video. The TV (Google TV with a browser) can display exact patterns itself, driven by the phone.

**Delivery routes, in the order we try them** *(researched where noted; none tested on the reference TV yet)*:
1. **TV browser page (built first, certain to work).** The user opens a short address in the TV's browser once and presses OK for full screen. Everything after that is automatic.
2. **Google Cast (experiment).** Google's Default Media Receiver (app ID `CC1AD845`) needs no registration; a third-party source lists supported image formats as BMP, GIF, JPEG, PNG and WEBP. The phone would host solid-color images and cast one per pattern, removing the one-time step. Unverified: whether the receiver accepts cleartext `http://` media URLs, whether an image fills the screen without letterboxing, whether colors stay exact. Needs the Cast sender library and Google Play services on the phone.
3. **Android TV Remote protocol (later, only if 2 fails).** The protocol used by the Google TV phone app (and by Home Assistant's Android TV Remote integration) can send keys and open a link on the TV after a one-time pairing code. Opening an HTTP link depends on a TV app handling it; users report mixed results and trial and error, and the protocol is not officially documented, so it would have to be implemented from scratch.

**Design of route 1 (this milestone).**
- **Address:** `http://<phone-ip>:<port>/p/<code>` where `<code>` is a 4-digit code shown on the phone and on the remote page. The code only grants access to the test page and the pattern endpoint, not to any control, so it is short enough to type with a TV remote. (The full-key addresses of 5.6 are unchanged.)
- **Page:** a full-screen div; click or Enter requests browser fullscreen; cursor hidden; a screen wake lock is requested where supported. While no pattern is active it shows a dark screen with the text "CamLight test screen, press OK for full screen, waiting for the phone".
- **Polling:** the page calls `GET /pattern?c=<code>&w=&h=&fs=&ua=` every 300 ms and receives `{id, label, rgb, style}` (or `{id: "none"}` when nothing should be shown). The query reports the page's viewport size, whether it is full screen and a short user-agent string, so the phone can tell the user when the browser chrome is visible or the page is scaled (overscan). The call also acts as the heartbeat: the phone treats the TV page as connected if it was seen in the last 2 s.
- **Patterns:** black, white, 25/50/75 % gray, red, green, blue, cyan, magenta, yellow, and *edge markers* (white field with red/green/blue/yellow lines on the four extreme edges plus a center cross, to reveal overscan or cropping). Values are exact sRGB; no gradients, no animation.
- **Remote page:** a *TV test patterns* section: connection status (size, full screen, user agent), the address to open on the TV, one button per pattern and *Off*. A button **Calibrate black + white with the TV page** runs: black → wait 1.8 s → measure black (existing procedure, 60 frames) → white → wait 1.8 s → measure white → patterns off. Each step uses the existing checks (black must read black, white must not clip, with the same messages).
- **Phone:** the Remote control box also shows the TV test page address and code.

**Next, using the same channel.** Gray steps (camera tone response), primaries and secondaries (fit the 3×3 color matrix of 5.2), full-field white/gray (flat-field maps, 14.8), a white field to fit the exact picture edges (bezel inset), and the guided setup (14.5).

**Caveats.** The TV's picture mode (brightness, local dimming, automatic brightness limiter, HDR tone mapping) affects what the camera sees; calibrate in the mode normally used for ambient lighting. Some TV browsers keep an address bar visible, overscan the page or sleep the screen during long sessions; the page reports its size back and sessions are kept short (< 30 s). Pixel values can be altered if the page is cast from another device instead of opened natively (video compression and scaling), so cast only as a convenience.

**Status.** Route 1 is implemented (M1f). Verified so far: server routes and codes by unit tests; the page renders exact colors in a headless Chrome (white 255,255,255; cyan 0,255,255; edge markers on all four edges). Not yet verified on the reference TV's browser: full-screen behavior, hidden address bar, overscan, screen-saver timing, and the measured black/white values.

**Acceptance.** With the TV page open, one tap on *Calibrate black + white with the TV page* produces a stored black floor and white gains with no external video; the remote page shows the TV page's size and full-screen state; if the page is closed, the button explains how to open it.

### 14.5 Guided setup flow on the remote page (P1)
A single ordered flow with validation, replacing the scattered buttons: 1 *Placement* (TV fills 55-75 % of the frame width, phone level via accelerometer, TV not cropped) → 2 *Exposure* (zebra < 1 % of the picture, then lock exposure and color) → 3 *Detect TV* and fine-tune → 4 *Black* (pattern: black, room lights as normally used) → 5 *White* (pattern: white) → 6 *Mode* (shows A/B on a sample scene) → 7 *Verify* (corner LEDs and a chase). Each step shows pass/fail, can be repeated, and the finished state is stored per profile (14.6). The phone shows a one-line "setup in progress on the remote page" screen so nobody taps it by accident.

### 14.6 Calibration profiles: lamps on / lamps off (P1)
**Why.** Yellow room lamps changed what the camera sees and made blacks read blue; the right calibration depends on the lighting.

**Design.**
- **Profile** = black floor (global and per zone), white gains, per-zone gains (14.8), selected mode, brightness/saturation/warmth, exposure settings (EV, lock state and, where `MANUAL_SENSOR` is available, exposure time and ISO), and a name. Geometry stays global (per placement).
- **Switching:** manual from either UI; optional automatic switching. Primary trigger: brightness of the *background* region (outside the TV) with exposure locked, since a lamp switching on is a step change there (hysteresis, 3 s hold). Secondary: the phone's light sensor (`TYPE_LIGHT`), unreliable when the phone is mounted screen-to-wall.
- **Re-lock on switch:** when exact manual exposure cannot be restored, unlock AE/AWB for ~1 s, let it converge, relock, then apply the stored calibrations.
- **Creation:** *Duplicate current as…* then rerun black/white (14.5) under the new lighting.

**Acceptance.** Turn the lamps on/off and the profile follows within 5 s with correct blacks in both states.

### 14.7 Letterbox and pillarbox detection (P2)
**Why.** Widescreen films show black bars; with fixed zones the top and bottom LEDs sample black while the picture edge is further inside.

**Reference *(researched)*:** Hyperion's detector counts a pixel as non-black when any channel exceeds a percentage of 255 (a user-posted default-style config uses a threshold of 0.16) and has frame-count settings (posted values: `borderFrameCnt` 50, `maxInconsistentCnt` 10, `unknownFrameCnt` 600). The sources found did not document exactly what each counter does, and forum users report the threshold needing tuning per setup, so the hold times below are our own design informed by those values.

**Design.**
- Evaluate rows (top/bottom) and columns (left/right) *inside the picture quad* using the existing sample grid. A row is "black" when its trimmed-mean luma is below `max(0.16·255, blackFloor + margin)`; scan inward from each edge up to 30 % of the dimension.
- *(design)* Require the same border for ≥ 50 consecutive frames (~1.7 s at 30 fps) to adopt it; drop it after 10 inconsistent frames; recheck on scene cuts.
- Apply by shrinking the picture rectangle used by `ZoneMapper` (per-side insets); animate changes over 300 ms. Optional snap to common ratios (2.39:1, 2.35:1, 1.85:1 on a 16:9 panel: bars of ≈ 12.8 %, 12 %, 3 % each).
- Failure is benign: a dark scene mistaken for bars just samples darker, uniform pixels.
- Settings: on/off, sensitivity (threshold), hold time; per-mode default (on in Cinema, off in Vivid).

**Acceptance.** A 2.39:1 film: top and bottom LEDs track the picture edge within 3 s of the bars appearing; no hunting on dark scenes.

### 14.8 Per-zone flat-field calibration (P2)
**Why.** Corners and edges differ in brightness and tint because of lens shading, the TV's viewing angle and backlight bleed.

**Model *(researched: flat-field correction)*:** `corrected = (raw - offset_k) × gain_k` per zone `k` (per channel), where the offset comes from a dark reference and the gain from a uniform bright reference; recalibrate if the camera, lighting or optics change. Color-channel specifics (separate gain maps per channel for color shading) are standard practice but were not confirmed in the sources found; verify on the reference setup.

**Design.**
- `offset_k` from the black pattern (14.4) with room lights as used; `white_k` from the white pattern; `gain_k = ref / (white_k - offset_k)` with `ref` the median of all zones, clamped to 0.5-2.0 and smoothed across neighbors (3-tap median) to suppress noise.
- Stored per profile (14.6) per LED zone (not per pixel), so it survives re-anchoring (14.3).
- Replaces the global white gains in modes that use white calibration; Basic stays global.
- UI: *Calibrate zones* in guided setup; a bar chart of per-zone gains (diagnostic) on the remote page.

**Acceptance.** With a white pattern all 267 zones read within ±5 % after correction; a black pattern shows no zone brighter than 2 % in any channel.

### 14.9 Dominant-color weighting (P2)
**Why.** Natural scenes with a bright highlight against a muted background are washed out by a plain average.

**Reference *(researched)*:** an open-source ambilight extracts a perceptual dominant color in the Oklab color space, gates updates on a frame-difference check and smooths the result; Philips patents describe real-time dominant-color extraction from subsampled video *(researched: patent listings only; no algorithm details used)*. A simple average is cheapest; histogram or clustering methods give more vivid results at more cost *(general knowledge, not from the sources)*.

**Design.**
- Raise the per-LED samples from 3×3 to 4×4 (16); still ~4 k samples per frame.
- **Trimmed mean** (drop the brightest and darkest 2 of 16) as the new default for Natural/Cinema.
- **Dominant mode:** convert samples to Oklab, find two clusters (k = 2) by a few Lloyd iterations, weight each cluster by `count × (0.5 + chroma)`, output `0.7·dominant + 0.3·mean`.
- Add as test variants **G** (trimmed mean) and **H** (dominant) first and promote the winner into the modes after A/B comparison on the TV.

**Acceptance.** Forest and sky scenes keep their hue (no maroon/cyan drift) in A/B tests against Basic; processing time stays < 3 ms per frame.

### 14.10 Diagnostics export (P2)
*Share diagnostics* on the phone (Android share sheet) and `GET /diagnostics?k=` on the remote page: device model and Android version, `CameraInfoDump`, configuration JSON (remote key and Wi-Fi details removed), the last 300 log lines and a short performance summary (fps, frame age, send time). Removes the need for adb to report a problem.

### 14.11 CI build of the APK on GitHub (P2)
- Workflow `.github/workflows/build.yml` on push and pull request: `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 17, `cache: gradle`), `chmod +x gradlew`, `./gradlew testDebugUnitTest assembleDebug`, then `actions/upload-artifact@v4` for `app/build/outputs/apk/debug/app-debug.apk` with a short retention. *(researched: common workflow shape; older examples use deprecated action versions)*
- **Signing:** debug APKs are signed with the machine's debug keystore. If CI and local builds use different keys, Android will refuse to update one over the other. Pick one signing source (a project keystore in encrypted secrets, used for CI and local builds) before sharing builds. Release builds always need an own keystore stored as secrets.
- The repository is private, so artifacts need a GitHub login; for quick installs without a cable a helper script that serves the APK on the LAN (as used during development) is also kept in `tools/`.
- Test results are published in the job summary.

### 14.12 LED strip simulator on the remote page (P3)
Add the current per-LED output (267 × 3 bytes, base64, ~5 Hz) to `/state` or a `/leds` endpoint and draw the LEDs as dots around the TV box. It lets a developer check the pipeline without looking at the TV and makes remote debugging of corner tints possible.

### 14.13 Control API for Home Assistant and automations (P3)
- Document the existing `POST /cmd` actions (lights, mode, brightness, profile, detect) as a stable API behind the key; Home Assistant can call it with `rest_command`.
- WLED side: Home Assistant's WLED integration exposes presets and palettes for WLED 0.14+ *(researched)*; WLED's docs advise against MQTT for Home Assistant and no webhook control was found, so plain REST is the supported route *(researched)*.
- **Limit (from 14.2):** Android prevents starting a camera foreground service from the background, so automations can switch lights on only while the service is already running.
- Optional: an endpoint that returns state for HA sensors (fps, idle state, temperature).

### 14.14 Thermal management (P3)
Subscribe to the thermal status (`PowerManager`, API 29+; headroom where available). Policy: *Moderate* → drop the camera to 30 fps, preview to 5 fps, zebra off; *Severe* → warn on both UIs and enter idle; log temperature in the diagnostics. Useful on a budget phone running a camera continuously.

### 14.15 Device profiles, the second phone, and external cameras (P3)
- **Device profile** from the *Camera info* dump: hardware level (LEGACY/LIMITED/FULL), AE/AWB lock availability, frame-rate ranges, zoom range, timestamp source. Disable or warn about features that depend on missing capabilities (profile auto-restore, per-zone calibration quality, 60 fps) and pre-select sensible defaults (e.g. 30 fps).
- **Second phone: Tecno Spark 6** *(researched: GSMArena)*: Helio G70, 4 GB RAM, Android 10, 16 MP autofocus main camera with 1440p@30 video (no 60 fps listed), 2.4 GHz-only Wi-Fi (802.11 b/g/n), microUSB 2.0 with OTG. Expect 30 fps, more Wi-Fi interference on the shared 2.4 GHz band, and no 5 GHz option. Its real Camera2 capabilities are unknown until read.
- **External USB camera (research item):** Android's Camera2 normally does not expose USB webcams, so this needs a UVC library in the app (own decoding, OTG power draw through microUSB, extra latency) and gains manual exposure/white balance and a fixed mount near the TV. Considered only if a target phone cannot lock exposure and color; not researched further yet. A clip-on telephoto lens or moving the phone closer are simpler first steps.

### 14.16 Automatic mode selection (P3)
Optional: classify the scene (mean luma, chroma, motion, black-bar state) and switch among Basic, Natural, Cinema and Vivid with hysteresis (minimum 20 s between changes, announced in the UI). Off by default; per-mode locks so a manual choice is never overridden.

---

## 15. Target hardware (reference setup)

| Item | Value | Implications |
|---|---|---|
| LEDs | WS2812 (5 V); WLED is configured for **267** LEDs, the user's strip is 300; ~267 used on the TV | One frame ≈ 267 × 24 × 1.25 µs ≈ 8 ms; the LED bus is not the bottleneck. Unused LEDs are sent black. |
| Controller | Gledopto GL-C-010-WL (ESP32, 240 MHz) running WLED 0.15.4 "SoundReactive", AudioReactive disabled, Wi-Fi sleep off, RSSI about -57..-60 dBm, WiFi 2.4 GHz only | Sustained UDP streaming above ~15-30 packets/s caused ~16-20 % ping loss and spikes up to ~300 ms even from a PC. Output is capped at 30 packets/s, unchanged frames skipped. |
| WLED realtime gamma | `light.gc.col = 2.8` but `if.live.no-gc = true` | WLED does **not** gamma-correct DDP frames. The app reads this and sends linear values; changing WLED's gamma is unnecessary and is not offered when `no-gc` is true. |
| Power | 5 V, WLED current limit 3100 mA | The limit protects the supply; detection and test flashes use a moderate level. |
| Camera phone | Xiaomi **Redmi Note 13 Pro** (model 23117RA68G), landscape on a shelf ≈ 10 ft from the TV, screen toward the wall | MIUI/HyperOS background restrictions (14.2); no USB access once mounted, so the remote page is the control surface. |
| Second phone | Tecno Spark 6 (see 14.15) | Candidate dedicated camera phone. |
| TV | Google TV with a browser | The TV's own browser can show calibration patterns (14.4) and the remote page. |
| Controller PC | Windows 11, JDK 17, Android SDK on D:, adb | Build and install via USB or (fallback) a temporary LAN file server. |

The phone model does not change the architecture; it determines the achievable latency floor and which exposure controls are available.

---

## 16. Sources

- Ambient Light TV listing: https://chrome-stats.com/d/com.ambientlight.tv
- Hyperion Android Reborn wiki: https://github.com/evanwhitt/hyperion-android-reborn/wiki
- WLED UDP Realtime (protocol bytes, timeouts, limits): https://kno.wled.ge/interfaces/udp-realtime/
- WLED DDP: https://kno.wled.ge/interfaces/ddp/
- WLED JSON API (power, presets via `ps`, live mode): https://kno.wled.ge/interfaces/json-api/
- WLED presets: https://kno.wled.ge/features/presets/
- WLED home automation and Home Assistant integration: https://kno.wled.ge/advanced/home-automation and https://www.home-assistant.io/integrations/wled
- HyperHDR releases (smoothing, LUT calibration): https://github.com/awawa-dev/HyperHDR/releases
- Hyperion black-border settings as reported by users (threshold, frame counts): https://community.home-assistant.io/t/hyperion-black-bar-question/30042 and https://discourse.osmc.tv/t/osmc-and-hyperion/23293/343
- Automatic letter/pillarbox detection research paper: https://www.scitepress.org/Papers/2014/50642/50642.pdf
- Open-source ambilight with Oklab dominant color, change gate and smoothing: https://git.pvv.ntnu.no/adriangl/ambiligth
- WLED forum, APA102 above 30 fps: https://wled.discourse.group/t/apa102-max-30fps-in-live-mode/5121
- WLED forum, latency discussion: https://wled.discourse.group/t/x-lights-latency/3632
- Android Camera2 package docs (YUV/JFIF, pipelining): https://developer.android.com/reference/android/hardware/camera2/package-summary
- CameraX analysis guide: https://developer.android.com/training/camerax/analyze
- Android foreground service types (camera type, permissions, while-in-use rule): https://developer.android.com/develop/background-work/services/fgs/service-types
- Android restrictions on starting foreground services from the background (exemptions, while-in-use creation rule): https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Xiaomi background-process restrictions (autostart, battery, recents lock): https://dontkillmyapp.com/xiaomi and https://appuals.com/xiaomi-phone-closes-background-apps/
- Phase correlation and sub-pixel registration: https://strathprints.strath.ac.uk/29259/ and https://bmva-archive.org.uk/bmvc/2008/papers/292.pdf
- Flat-field correction (offset and gain per pixel): https://documentation.euresys.com/Products/COAXLINK/COAXLINK_10_3/Content/03_Using_Coaxlink/functional-guide/flat-field-correction/what-is-ffc.htm and https://docs.adaptive-vision.com/studio/filters/ImageCombinators/CorrectFlatField.html
- GitHub Actions for Android builds (workflow shape; check current action versions): https://proandroiddev.com/android-ci-cd-pipeline-with-github-actions-demystifying-github-actions-83258e76a18f
- Tecno Spark 6 specifications: https://www.gsmarena.com/tecno_spark_6-10471.php
- Camera-based backlight reviews (green / skin tone / washout issues, blank-screen auto off): https://www.notebookcheck.net/Govee-Envisual-T2-The-camera-based-Philips-Ambilight-alternative-in-a-hands-on.681256.0.html and https://us.govee.com/products/govee-dreamview-t1-tv-backlight
- Nanoleaf 4D review (calibration, TV power LED false detections): https://www.macrumors.com/review/nanoleaf-4d-camera-and-light-strip/

Note: items marked "anecdotal" come from forum posts; statements marked *(design)* are our own proposals; and items about exposure/flicker/Wi-Fi lock APIs are from general Android knowledge and should be verified on target devices.
