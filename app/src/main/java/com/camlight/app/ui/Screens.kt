package com.camlight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.camlight.app.model.Corner
import com.camlight.app.model.Side
import com.camlight.app.model.StripConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ScreenPadding = 16.dp

@Composable
fun AppRoot(state: UiState, vm: SetupViewModel, camVm: CameraViewModel) {
    var showCamera by rememberSaveable { mutableStateOf(false) }
    when {
        state.info == null -> ConnectScreen(state, vm)
        state.step != null -> WizardScreen(state, vm)
        showCamera -> CameraScreen(state, camVm) { showCamera = false }
        else -> HomeScreen(state, vm, onOpenCamera = { showCamera = true })
    }
}

// ───────────────────────────── Connect ─────────────────────────────

@Composable
fun ConnectScreen(state: UiState, vm: SetupViewModel) {
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CamLight", style = MaterialTheme.typography.headlineMedium)
        Text("Connect to your WLED controller", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = state.host,
            onValueChange = vm::setHost,
            label = { Text("WLED IP or hostname") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.connect() },
            enabled = !state.connecting && state.host.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (state.connecting) "Connecting..." else "Connect") }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Found on network", style = MaterialTheme.typography.titleSmall)
        if (state.discovered.isEmpty()) Text("Searching...", style = MaterialTheme.typography.bodyMedium)
        state.discovered.forEach { d ->
            OutlinedButton(
                onClick = { vm.connect(d.host) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("${d.name}  (${d.host})") }
        }
    }
}

// ───────────────────────────── Home ─────────────────────────────

@Composable
fun HomeScreen(state: UiState, vm: SetupViewModel, onOpenCamera: () -> Unit) {
    val info = state.info ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(info.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${info.ledCount} LEDs  ·  WLED ${info.version}" + (info.rssi?.let { "  ·  $it dBm" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Your TV", style = MaterialTheme.typography.titleMedium)
        TvDiagram(state.config, currentSide = null)
        LayoutSummary(state.config, onClick = null)

        Button(
            onClick = onOpenCamera,
            enabled = state.config.counts.sum() > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Camera & ambient light") }
        OutlinedButton(
            onClick = { vm.goTo(Step.START) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Edit LED layout") }
        OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
    }
}

@Composable
private fun LayoutSummary(config: StripConfig, onClick: ((Int) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        config.order.forEachIndexed { pos, side ->
            val count = config.counts[pos]
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .let { if (onClick != null) it.clickable { onClick(pos) } else it }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000 or side.rgb.toLong())))
                Text(side.label, style = MaterialTheme.typography.labelMedium)
                Text(if (count > 0) "$count" else "–", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    Text(
        "${config.counts.sum()} of ${config.totalLeds} LEDs used",
        style = MaterialTheme.typography.bodySmall,
    )
}

// ───────────────────────────── Wizard ─────────────────────────────

@Composable
fun WizardScreen(state: UiState, vm: SetupViewModel) {
    val step = state.step ?: return
    val config = state.config
    val currentSide = if (step in 1..4) config.order[step - 1] else null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepDots(step, Modifier.weight(1f))
            if (config.configured) TextButton(onClick = { vm.goTo(null) }) { Text("Close") }
        }

        TvDiagram(
            config = config,
            currentSide = currentSide,
            onCornerTap = if (step == Step.START) vm::setCorner else null,
            onSideTap = if (step != Step.START) { side -> vm.goTo(Step.side(config.order.indexOf(side))) } else null,
        )

        when (step) {
            Step.START -> StartStep(config, vm)
            Step.REVIEW -> ReviewStep(state, vm)
            else -> SideStep(config, step - 1, vm)
        }

        Spacer(Modifier.height(4.dp))
        NavBar(step, vm)
    }
}

@Composable
private fun StepDots(step: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..Step.REVIEW) {
            Box(
                Modifier
                    .size(if (i == step) 12.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartStep(config: StripConfig, vm: SetupViewModel) {
    Text("Where does the strip begin?", style = MaterialTheme.typography.titleLarge)
    Text(
        "The first 3 LEDs of the strip are glowing green. Tap the corner of the TV where they are, " +
            "then choose which way the strip runs around the TV.",
        style = MaterialTheme.typography.bodyMedium,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = config.clockwise,
            onClick = { vm.setClockwise(true) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            modifier = Modifier.height(52.dp),
        ) { Text("↻  Clockwise") }
        SegmentedButton(
            selected = !config.clockwise,
            onClick = { vm.setClockwise(false) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            modifier = Modifier.height(52.dp),
        ) { Text("↺  Counter-clockwise") }
    }
    Text(
        "Strip starts ${config.startCorner.label}, running " +
            (if (config.clockwise) "clockwise" else "counter-clockwise") +
            ", first along the ${config.order.first().label.lowercase()} edge.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SideStep(config: StripConfig, pos: Int, vm: SetupViewModel) {
    val side = config.order[pos]
    val count = config.counts[pos]
    val room = config.roomAt(pos)
    val sliderMax = maxOf(minOf(room, SLIDER_RANGE), count, 1).toFloat()
    var showGap by remember(pos) { mutableStateOf(config.gaps[pos] > 0) }

    Text("${side.label} edge · ${pos + 1} of 4", style = MaterialTheme.typography.titleLarge)
    Text(
        "Drag until the white lights reach the corner of your TV. The red LED marks where this side ends.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Text(
        if (count > 0) "$count LEDs" else "No LEDs",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RepeatButton("◀", enabled = count > 0) { vm.setCount(pos, count - 1) }
        Slider(
            value = count.toFloat().coerceAtMost(sliderMax),
            onValueChange = { vm.setCount(pos, it.roundToInt()) },
            valueRange = 0f..sliderMax,
            modifier = Modifier.weight(1f),
        )
        RepeatButton("▶", enabled = count < room) { vm.setCount(pos, count + 1) }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { vm.setCount(pos, 0) }) { Text("No LEDs on this side") }
        TextButton(onClick = { showGap = !showGap }) {
            Text(if (showGap) "Hide gap option" else "Skip LEDs before")
        }
    }

    if (showGap) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hidden LEDs before this side: ${config.gaps[pos]}", Modifier.weight(1f))
            RepeatButton("−", enabled = config.gaps[pos] > 0) { vm.setGap(pos, config.gaps[pos] - 1) }
            RepeatButton("+") { vm.setGap(pos, config.gaps[pos] + 1) }
        }
    }
}

@Composable
private fun ReviewStep(state: UiState, vm: SetupViewModel) {
    val config = state.config
    Text("Does this match your TV?", style = MaterialTheme.typography.titleLarge)
    Text(
        "Each side should glow in its own color along the matching edge. Play the chase to check the " +
            "light runs smoothly around the whole TV. Tap a side to fix it.",
        style = MaterialTheme.typography.bodyMedium,
    )
    LayoutSummary(config, onClick = { pos -> vm.goTo(Step.side(pos)) })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Play chase around the TV", Modifier.weight(1f))
        Switch(checked = state.chase, onCheckedChange = vm::setChase)
    }
}

@Composable
private fun NavBar(step: Int, vm: SetupViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (step > Step.START) {
            OutlinedButton(
                onClick = { vm.goTo(step - 1) },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Back") }
        }
        if (step < Step.REVIEW) {
            Button(
                onClick = { vm.goTo(step + 1) },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text(if (step == 4) "Review" else "Next") }
        } else {
            Button(onClick = vm::finish, modifier = Modifier.weight(1f).height(52.dp)) { Text("Done") }
        }
    }
}

/** Tap = one step; press and hold repeats, speeding up. */
@Composable
private fun RepeatButton(label: String, enabled: Boolean = true, onStep: () -> Unit) {
    val current by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val container = if (enabled) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(container)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    current()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val job = scope.launch {
                        delay(350)
                        var interval = 120L
                        while (true) {
                            current()
                            delay(interval)
                            interval = (interval * 0.88).toLong().coerceAtLeast(20L)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

private const val SLIDER_RANGE = 150
