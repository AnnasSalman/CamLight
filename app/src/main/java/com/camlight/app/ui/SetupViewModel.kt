package com.camlight.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.camlight.app.data.ConfigStore
import com.camlight.app.model.Corner
import com.camlight.app.model.Preview
import com.camlight.app.model.StripConfig
import com.camlight.app.model.StripRenderer
import com.camlight.app.wled.DdpSender
import com.camlight.app.wled.DiscoveredDevice
import com.camlight.app.wled.WledClient
import com.camlight.app.wled.WledDiscovery
import com.camlight.app.wled.WledInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Wizard steps: 0 = strip start, 1..4 = the four sides in strip order, 5 = review. */
object Step {
    const val START = 0
    const val REVIEW = 5
    fun side(pos: Int) = pos + 1
}

data class UiState(
    val host: String = "",
    val connecting: Boolean = false,
    val info: WledInfo? = null,
    val error: String? = null,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val config: StripConfig = StripConfig(),
    /** null = home screen (no live preview). */
    val step: Int? = null,
    val chase: Boolean = false,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ConfigStore(app)
    private val client = WledClient()
    private val discovery = WledDiscovery(app)

    private val _state = MutableStateFlow(UiState(host = store.lastHost, config = store.loadStrip()))
    val state: StateFlow<UiState> = _state

    private var liveJob: Job? = null
    private var discoveryJob: Job? = null
    private var liveHost: String? = null
    private val seeded = mutableSetOf<Int>()

    fun setHost(host: String) = _state.update { it.copy(host = host) }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            runCatching {
                discovery.discover().collect { d ->
                    _state.update { s ->
                        if (s.discovered.any { it.host == d.host }) s else s.copy(discovered = s.discovered + d)
                    }
                }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun connect(rawHost: String = _state.value.host) {
        val host = WledClient.normalizeHost(rawHost)
        if (host.isEmpty()) return
        _state.update { it.copy(host = host, connecting = true, error = null) }
        viewModelScope.launch {
            runCatching { client.fetchInfo(host) }
                .onSuccess { info ->
                    store.lastHost = host
                    val cfg = _state.value.config.withTotal(info.ledCount).also(store::saveStrip)
                    liveHost = host
                    _state.update { it.copy(connecting = false, info = info, config = cfg) }
                    goTo(if (cfg.configured) null else Step.START)
                }
                .onFailure { e ->
                    _state.update { it.copy(connecting = false, error = "Could not reach $host: ${e.message}") }
                }
        }
    }

    fun disconnect() {
        goTo(null)
        liveHost = null
        _state.update { it.copy(info = null) }
    }

    /** Navigate the wizard; null leaves it (home) and hands the strip back to WLED. */
    fun goTo(step: Int?) {
        val host = liveHost ?: return
        if (step != null && step in 1..4) seedSide(step - 1)
        _state.update { it.copy(step = step, chase = false) }
        if (step == null) {
            stopLive()
            viewModelScope.launch { client.exitLive(host) }
        } else if (liveJob?.isActive != true) {
            startLive(host)
        }
    }

    fun setCorner(corner: Corner) = edit { it.withLayout(corner, it.clockwise) }

    fun setClockwise(cw: Boolean) = edit { it.withLayout(it.startCorner, cw) }

    fun setCount(pos: Int, count: Int) = edit { it.withCount(pos, count) }

    fun setGap(pos: Int, gap: Int) = edit { it.withGap(pos, gap) }

    fun setChase(on: Boolean) = _state.update { it.copy(chase = on) }

    fun finish() {
        edit { it.copy(configured = true) }
        goTo(null)
    }

    private fun edit(transform: (StripConfig) -> StripConfig) {
        _state.update { s ->
            val cfg = transform(s.config)
            store.saveStrip(cfg)
            s.copy(config = cfg)
        }
    }

    /** First visit to a side shows a sensible run immediately so the user has something to adjust. */
    private fun seedSide(pos: Int) {
        if (!seeded.add(pos)) return
        val cfg = _state.value.config
        if (!cfg.configured && cfg.counts[pos] == 0) {
            edit { it.withCount(pos, minOf(DEFAULT_RUN, it.roomAt(pos))) }
        }
    }

    private fun previewFor(s: UiState): Preview = when (val step = s.step) {
        null -> Preview.Off
        Step.START -> Preview.StripStart
        Step.REVIEW -> if (s.chase) Preview.Chase else Preview.AllSides
        else -> Preview.EditSide(step - 1)
    }

    /** Streams the current preview frame at ~30 fps while a wizard step is showing. */
    private fun startLive(host: String) {
        stopLive()
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                DdpSender(host).use { sender ->
                    var tick = 0
                    var last: ByteArray? = null
                    var lastSent = 0L
                    while (isActive) {
                        val s = _state.value
                        val frame = StripRenderer.render(s.config, previewFor(s), tick / 2)
                        val now = System.nanoTime()
                        // Only send when something changed (plus a keep-alive), so the controller is not flooded.
                        if (last == null || !frame.contentEquals(last) || now - lastSent > KEEPALIVE_NS) {
                            sender.send(frame)
                            last = frame
                            lastSent = now
                        }
                        tick++
                        delay(FRAME_MS)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(info = null, step = null, error = "Lost connection: ${e.message}") }
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    override fun onCleared() {
        val host = liveHost
        stopLive()
        if (host != null) {
            // viewModelScope is cancelled; fire the exit request on a detached thread.
            Thread { runCatching { runBlocking { client.exitLive(host) } } }.start()
        }
        super.onCleared()
    }

    private companion object {
        const val FRAME_MS = 33L
        const val KEEPALIVE_NS = 400_000_000L
        const val DEFAULT_RUN = 30
    }
}
