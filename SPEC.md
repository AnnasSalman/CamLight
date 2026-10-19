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
- **Endpoints:** `GET /` (page), `GET /frame.jpg` (latest preview as JPEG, ~640×360, ~8 fps polled; `?debug=1` serves the detection image), `GET /state` (JSON), `POST /cmd` (JSON action). No streaming connection is held open, so any browser works.
- **State JSON:** corner quad, detected silhouette, per-LED sample dots (x, y, color), zebra cells (already in display orientation), lights on/off, look, speed, brightness, saturation, warmth, zoom range and value, exposure range and value, lock, detection status, black calibration status, stats (fps, ms, frame age), errors.
- **Commands:** move/set corners, reset corners, detect TV, show corners on the TV, lights on/off, look A-F, speed, brightness, saturation, warmth, zoom, exposure, lock, calibrate/clear black, gray test, zebra, detection image.
- **Corner fine-tuning in the page:**
  - Drag a handle with mouse or touch; a magnifier loupe (~6× zoom of the live image around the handle, drawn above the pointer) shows the exact spot.
  - Keyboard / TV-remote D-pad: Enter or 1-4 selects the next corner, arrow keys nudge 1 preview pixel, Shift+arrow 5 pixels, key repeat accelerates. The selected corner is drawn large with the current coordinates.
  - Edits apply to the LED sample zones on the phone immediately (throttled to ~15 updates/s) and are persisted.
- **Why polling, not WebSocket/MJPEG:** a hand-rolled single-file server stays dependency-free; polling needs no persistent connections; ~300 KB/s of JPEG is negligible on Wi-Fi.
- **Lifecycle:** the server runs only while the camera screen is open (the phone must stay awake and on that screen, display dimmed). Settings changed remotely are saved immediately.
- **Security notes:** HTTP without TLS on the home LAN, protected by the key; no file access, no shell, request bodies capped at 16 KB, small connection pool. Anyone on the LAN who knows the key could change lighting settings, nothing more.

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

| M | Scope |
|---|---|
| M0 | WLED connect + **Strip setup UI (5.4)**: discover controller, live-light a chosen side from the phone, save segments. No camera needed |
| M1 | Camera2 pipeline + manual preview + AE/AWB lock + device capability dump (Redmi Note 13); DDP/UDP sender; fixed zones with simple averaging → working lights |
| M1b | **Preview from analysis frames (fixes stretch/axis bug); automatic TV detection via LED-halo silhouette (5.1); corner fine-tuning with magnifier; verification lights on the TV** |
| M1d | **Light modes (5.7): Basic, Natural, Room light, Cinema, Vivid; white calibration; hue-preserving color processing** |
| M1c | **Remote control web page (5.6): live view, corner dragging with magnifier, D-pad nudging, all controls, QR/URL on the phone** |
| M2 | Homography + LED mapping UI refinements; native sampler; adaptive smoothing; placement guide, bump detection and automatic re-anchoring (5.5) |
| M3 | Color calibration wizard (offset, gains, 3×3, tone curve); flicker/clipping tools; presets |
| M4 | Foreground service, thermal management, discovery, WLED setup checklist; latency test mode |
| M5 | Polish, letterbox detection, 3D LUT option, corner-tracking drift warning, beta on multiple phones |

---

## 13. Open questions / risks

1. **Latency floor depends on the phone's camera HAL** (30–60 ms is outside our control); we need to benchmark 3–4 devices early, ideally in M1.
2. Is manual white balance (`COLOR_CORRECTION_GAINS`) reliably supported on target devices, or will we depend on AWB lock only?
3. DDP vs plain UDP realtime: both should work on current WLED; confirm on the user's firmware version (DDP may need enabling on pre-0.13 builds).
4. Whether a 3×3 matrix is enough for panels with strong gamut differences (wide-gamut OLED vs sensor); 3D LUT is the fallback.
5. Do we want a companion mode on **Android TV** using `MediaProjection` (no camera, exact colors) for non-DRM content? Out of scope for v1 but the `Frame source` interface should allow it.
6. ~~LED count / controller~~ Answered, see section 15.
7. Redmi Note 13 camera capability (60 fps at 720p in Camera2? manual sensor controls? hardware level?) is unknown until tested; M1 includes a capability dump screen.

---

## 15. Target hardware (reference setup)

| Item | Value | Implications |
|---|---|---|
| LEDs | 300 × WS2812 (5 V), fewer used on the TV | One frame = 300 × 24 bits × ~1.25 µs ≈ 9 ms + reset, so max ~100 fps; fine for 60 fps. Unused LEDs are sent black. |
| Controller | Gledopto GL-C-010-WL (ESP32, WLED) | ESP32 handles DDP/UDP at 60 fps comfortably. 2.4 GHz Wi-Fi only; no Ethernet, so signal quality matters (check RSSI, disable Wi-Fi sleep in WLED). |
| Power | 5 V | Set the WLED **max current (mA) limit** and a brightness cap; 300 LEDs at full white can draw up to ~18 A. Size the supply accordingly, with power injection if needed. Verify the data-line level suits your 5 V WS2812 wiring. |
| Phone | Xiaomi Redmi Note 13 | Device-specific risks: camera HAL latency, whether 60 fps / manual exposure / manual white balance are exposed through Camera2, and aggressive background-app killing in MIUI/HyperOS. Mitigations: capability dump in M1, fallback to AE/AWB lock at 30 fps, onboarding step to set battery to "No restrictions", enable autostart, lock the app in recents. |

The phone model does not change the architecture; it determines the achievable latency floor and which exposure controls are available.

---

## 14. Sources

- Ambient Light TV listing: https://chrome-stats.com/d/com.ambientlight.tv
- Hyperion Android Reborn wiki: https://github.com/evanwhitt/hyperion-android-reborn/wiki
- WLED UDP Realtime (protocol bytes, timeouts, limits): https://kno.wled.ge/interfaces/udp-realtime/
- WLED DDP: https://kno.wled.ge/interfaces/ddp/
- HyperHDR releases (smoothing, LUT calibration): https://github.com/awawa-dev/HyperHDR/releases
- WLED forum, APA102 above 30 fps: https://wled.discourse.group/t/apa102-max-30fps-in-live-mode/5121
- WLED forum, latency discussion: https://wled.discourse.group/t/x-lights-latency/3632
- Android Camera2 package docs (YUV/JFIF, pipelining): https://developer.android.com/reference/android/hardware/camera2/package-summary
- CameraX analysis guide: https://developer.android.com/training/camerax/analyze
- Camera-based backlight reviews (green / skin tone / washout issues): https://www.notebookcheck.net/Govee-Envisual-T2-The-camera-based-Philips-Ambilight-alternative-in-a-hands-on.681256.0.html

Note: items marked "anecdotal" come from forum posts, and items about exposure/flicker/Wi-Fi lock APIs are from general Android knowledge and should be verified on target devices.
