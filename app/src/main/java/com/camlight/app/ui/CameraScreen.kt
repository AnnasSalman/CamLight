package com.camlight.app.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.Surface
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camlight.app.camera.AmbientProcessor
import com.camlight.app.camera.Look
import com.camlight.app.camera.Pt
import com.camlight.app.camera.Smoothing
import kotlin.math.roundToInt

@Composable
fun CameraScreen(setup: UiState, vm: CameraViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val state by vm.state.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        vm.enter(setup.host, setup.config)
        onDispose {
            vm.leave()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    // The camera buffer is landscape-left; if the phone sits the other landscape way it is upside down.
    val rotation = view.display?.rotation
    LaunchedEffect(rotation) { vm.setFlip(rotation == Surface.ROTATION_270) }

    if (granted && landscape) {
        LifecycleStartEffect(Unit) {
            vm.startCamera()
            onStopOrDispose { vm.stopCamera() }
        }
    }

    when {
        !granted -> Centered {
            Text("CamLight needs the camera to see your TV.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            OutlinedButton(onClick = onClose) { Text("Back") }
        }
        !landscape -> Centered { Text("Turn your phone sideways (landscape).") }
        else -> CameraContent(state, vm, onClose)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraContent(state: CameraUiState, vm: CameraViewModel, onClose: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    val sel = state.selected

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(16f / 9f)) { Overlay(state, vm) }
        }

        Column(
            Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Ambient light", style = MaterialTheme.typography.titleLarge)

            state.remoteUrl?.let { url ->
                Text("Remote control", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Open this on a laptop, another phone or your TV's browser (same Wi-Fi) to control everything without touching this phone:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                val qr = remember(url) { qrBitmap(url).asImageBitmap() }
                Image(qr, contentDescription = "QR code for the remote page", modifier = Modifier.size(150.dp))
            }

            Button(
                onClick = vm::detectTv,
                enabled = !state.detecting && sel != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.detecting) "Working…" else "Detect TV automatically") }
            Text(
                state.detectStatus
                    ?: "Dim the room lights, then tap. The strip glows on the wall around the TV and the app finds the TV edges.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Then drag a white handle to fine-tune a corner. A magnifier shows the exact spot.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = vm::showCornersOnTv, enabled = !state.detecting, modifier = Modifier.fillMaxWidth()) {
                Text("Show corners on the TV")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Light the LEDs", Modifier.weight(1f))
                Switch(checked = state.lightsOn, onCheckedChange = vm::setLights, enabled = !state.detecting)
            }

            Text("Brightness ${(state.brightness * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = state.brightness, onValueChange = vm::setBrightness, valueRange = 0.05f..1f)

            Text("Light mode", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Look.MODES.forEach { lk ->
                    FilterChip(selected = state.look == lk.id, onClick = { vm.setLook(lk.id) }, label = { Text(lk.name) })
                }
            }
            val active = Look.byId(state.look)
            Text("${active.name}: ${active.description}", style = MaterialTheme.typography.bodySmall)
            Text("Test variants", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Look.TESTS.forEach { lk ->
                    FilterChip(selected = state.look == lk.id, onClick = { vm.setLook(lk.id) }, label = { Text(lk.id) })
                }
            }
            Text("Speed", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Smoothing.entries.forEach { sm ->
                    FilterChip(selected = state.smoothing == sm, onClick = { vm.setSmoothing(sm) }, label = { Text(sm.label) })
                }
            }

            Text("Color", style = MaterialTheme.typography.titleMedium)
            Text("Saturation %.0f%%".format(state.saturation * 100), style = MaterialTheme.typography.labelLarge)
            Slider(value = state.saturation, onValueChange = vm::setSaturation, valueRange = 1f..1.6f)
            Text("Warmth %.0f%%".format(state.warmth * 100), style = MaterialTheme.typography.labelLarge)
            Slider(value = state.warmth, onValueChange = vm::setWarmth, valueRange = 0f..1f)
            Text(
                "Raise warmth if grays look blue. Use the gray test to judge.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gray test on the LEDs", Modifier.weight(1f))
                Switch(checked = state.grayTest, onCheckedChange = vm::setGrayTest, enabled = !state.detecting)
            }

            val gamma = state.wledGamma
            val gammaOff = gamma != null && gamma <= 1.05f
            val applied = state.gammaOnRealtime
            Text(
                "WLED gamma: " + when {
                    gamma == null -> "unknown"
                    applied == false -> "%.1f, not applied to the app's frames".format(gamma)
                    gammaOff -> "off"
                    else -> "%.1f (applied)".format(gamma)
                },
                style = MaterialTheme.typography.labelLarge,
            )
            if (applied == false) {
                Text(
                    "WLED sends the app's colors to the LEDs unchanged, so no gamma change is needed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (gammaOff && state.gammaOriginal > 1.05f) {
                    OutlinedButton(
                        onClick = { vm.setWledGammaOff(false) },
                        enabled = !state.gammaBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Restore WLED gamma to %.1f".format(state.gammaOriginal)) }
                }
            } else if (gamma != null) {
                OutlinedButton(
                    onClick = { vm.setWledGammaOff(!gammaOff) },
                    enabled = !state.gammaBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (gammaOff) "Restore WLED gamma" else "Turn WLED gamma off (recommended)") }
                Text(
                    "WLED's gamma crushes dark colors, which pushes dim scenes toward blue. The app does its own conversion instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedButton(
                onClick = vm::calibrateBlack,
                enabled = !state.detecting && sel != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Calibrate black (TV off)") }
            Text(
                state.blackStatus ?: "With the TV off or on a black screen, tap to teach the app what black looks like to the camera.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.black != null) TextButton(onClick = vm::clearBlack) { Text("Back to automatic black") }

            OutlinedButton(
                onClick = vm::calibrateWhite,
                enabled = !state.detecting && sel != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Calibrate white (TV on white)") }
            Text(
                state.whiteStatus ?: "Show a plain white screen on the TV, with your room lights as you normally use them, then tap. Used by Natural, Room light and Cinema.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.white != null) TextButton(onClick = vm::clearWhite) { Text("Clear white calibration") }

            Text("Camera speed", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(30, 60).forEach { f ->
                    FilterChip(
                        selected = state.cameraFps == f,
                        onClick = { vm.setCameraFps(f) },
                        enabled = !state.detecting && (f == 30 || sel?.has60 != false),
                        label = { Text("$f fps") },
                    )
                }
            }
            if (sel != null && sel.fps != state.cameraFps) {
                Text("${state.cameraFps} fps is not available here; running at ${sel.fps} fps.", style = MaterialTheme.typography.bodySmall)
            }

            if (sel != null) {
                Text("Zoom %.1f×".format(state.params.zoom), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.params.zoom,
                    onValueChange = vm::setZoom,
                    valueRange = sel.minZoom..maxOf(sel.maxZoom, sel.minZoom + 0.01f),
                )
                Text(
                    "Exposure %+.1f EV".format(state.params.ev * sel.evStep),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = state.params.ev.toFloat(),
                    onValueChange = { vm.setEv(it.roundToInt()) },
                    valueRange = sel.evMin.toFloat()..maxOf(sel.evMax, sel.evMin + 1).toFloat(),
                    enabled = !state.params.lock && sel.evMax > sel.evMin,
                )
            }

            Button(onClick = { vm.setLock(!state.params.lock) }, enabled = !state.detecting, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.params.lock) "Unlock exposure & color" else "Lock exposure & color")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show zebra (over-exposed)", Modifier.weight(1f))
                Switch(checked = state.showZebra, onCheckedChange = vm::setShowZebra)
            }
            if (state.debug != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show detection image", Modifier.weight(1f))
                    Switch(checked = state.showDebug, onCheckedChange = vm::setShowDebug)
                }
            }

            val s = state.stats
            Text(
                (sel?.summary ?: "starting camera…") + "\n" +
                    "${s.fps} fps in · ${"%.1f".format(s.processMs)} ms/frame" +
                    (if (s.ageMs > 0) " · frame age ${"%.0f".format(s.ageMs)} ms" else ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::resetCorners) { Text("Reset corners") }
                OutlinedButton(onClick = { showInfo = true }) { Text("Camera info") }
            }
            TextButton(onClick = onClose) { Text("Done") }
        }
    }

    if (showInfo) {
        val text = remember { vm.cameraInfo() }
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Close") } },
            title = { Text("Camera capabilities") },
            text = {
                Text(
                    text,
                    Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            },
        )
    }
}

/** Live image, corner handles with a magnifier, sample dots and the zebra overlay, in one coordinate system. */
@Composable
private fun Overlay(state: CameraUiState, vm: CameraViewModel) {
    val quad by rememberUpdatedState(state.quad)
    var active by remember { mutableIntStateOf(-1) }
    val pf by vm.preview.collectAsStateWithLifecycle()
    val frame = if (state.showDebug && state.debug != null) state.debug else pf.bitmap
    val image = remember(frame, pf.version, state.showDebug) { frame?.asImageBitmap() }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val w = size.width
                        val h = size.height
                        val hit = quad.toList().withIndex().minBy { (_, p) ->
                            (Offset(p.x * w, p.y * h) - start).getDistance()
                        }
                        active = if ((Offset(hit.value.x * w, hit.value.y * h) - start).getDistance() < 56.dp.toPx()) hit.index else -1
                    },
                    onDrag = { change, _ ->
                        if (active >= 0) {
                            change.consume()
                            vm.moveCorner(active, Pt(change.position.x / size.width, change.position.y / size.height))
                        }
                    },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                )
            },
    ) {
        image?.let {
            drawImage(
                it,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }

        val z = pf.zebra
        if (state.showZebra && z != null) {
            val cw = size.width / z.cols
            val ch = size.height / z.rows
            for (r in 0 until z.rows) for (c in 0 until z.cols) {
                if (z.clipped[r * z.cols + c]) {
                    val dc = if (state.flip180) z.cols - 1 - c else c
                    val dr = if (state.flip180) z.rows - 1 - r else r
                    drawRect(Color(0x66FF1744), Offset(dc * cw, dr * ch), Size(cw, ch))
                }
            }
        }

        state.dots.forEach { d ->
            drawCircle(Color(0xFF000000 or d.rgb.toLong()), 3.dp.toPx(), Offset(d.at.x * size.width, d.at.y * size.height))
        }

        state.silhouette?.let { sil ->
            val pts = sil.toList().map { Offset(it.x * size.width, it.y * size.height) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, Color(0xFFFFD600), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }

        val pts = state.quad.toList().map { Offset(it.x * size.width, it.y * size.height) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
        pts.forEachIndexed { i, p ->
            drawCircle(Color(0x55000000), 22.dp.toPx(), p)
            drawCircle(if (i == active) Color(0xFF00E676) else Color.White, 10.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        }

        // Magnifier: shows the pixels under the dragged handle, drawn above (or below) the finger.
        val bmp = image
        if (active >= 0 && bmp != null) {
            val p = pts[active]
            val r = 54.dp.toPx()
            val above = p.y > r * 2.6f
            val center = Offset(p.x.coerceIn(r, size.width - r), if (above) p.y - r * 1.7f else p.y + r * 1.7f)
            val win = 36
            val sx = (quad.toList()[active].x * AmbientProcessor.PREVIEW_W - win / 2).roundToInt()
                .coerceIn(0, AmbientProcessor.PREVIEW_W - win)
            val sy = (quad.toList()[active].y * AmbientProcessor.PREVIEW_H - win / 2).roundToInt()
                .coerceIn(0, AmbientProcessor.PREVIEW_H - win)
            val circle = Path().apply { addOval(Rect(center, r)) }
            clipPath(circle) {
                drawImage(
                    bmp,
                    srcOffset = IntOffset(sx, sy),
                    srcSize = IntSize(win, win),
                    dstOffset = IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()),
                    dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
            val cross = 10.dp.toPx()
            drawLine(Color(0xFFFF1744), center - Offset(cross, 0f), center + Offset(cross, 0f), 1.5.dp.toPx())
            drawLine(Color(0xFFFF1744), center - Offset(0f, cross), center + Offset(0f, cross), 1.5.dp.toPx())
        }
    }
}
