package com.camlight.app.wled

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class WledInfo(
    val name: String,
    val version: String,
    val ledCount: Int,
    val rssi: Int?,
    val mac: String,
)

class WledClient {
    suspend fun fetchInfo(host: String): WledInfo = withContext(Dispatchers.IO) {
        val o = JSONObject(request(host, "/json/info", "GET", null))
        WledInfo(
            name = o.optString("name", "WLED"),
            version = o.optString("ver"),
            ledCount = o.getJSONObject("leds").getInt("count"),
            rssi = o.optJSONObject("wifi")?.optInt("rssi")?.takeIf { it != 0 },
            mac = o.optString("mac"),
        )
    }

    /**
     * Gamma situation on the controller. WLED can skip gamma for realtime frames (`if.live.no-gc`, on by
     * default in recent versions), in which case the bytes we send reach the LEDs unchanged.
     */
    data class GammaInfo(val col: Float?, val appliedToRealtime: Boolean?) {
        /** The exponent the LEDs effectively apply to our realtime bytes (1.0 = none). */
        val effective: Float get() = if (appliedToRealtime == false) 1f else (col ?: 2.8f)
    }

    suspend fun gammaInfo(host: String): GammaInfo = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(request(host, "/json/cfg", "GET", null))
            val col = o.getJSONObject("light").getJSONObject("gc").getDouble("col").toFloat()
            val noGc = o.optJSONObject("if")?.optJSONObject("live")?.let { if (it.has("no-gc")) it.getBoolean("no-gc") else null }
            GammaInfo(col, noGc?.not())
        }.getOrDefault(GammaInfo(null, null))
    }

    /** WLED's color gamma exponent (`light.gc.col`); 1.0 means off. Null if it cannot be read. */
    suspend fun colorGamma(host: String): Float? = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(request(host, "/json/cfg", "GET", null))
                .getJSONObject("light").getJSONObject("gc").getDouble("col").toFloat()
        }.getOrNull()
    }

    /** Sets WLED's color gamma and reads it back; returns the value WLED now reports (null on failure). */
    suspend fun setColorGamma(host: String, gamma: Float): Float? = withContext(Dispatchers.IO) {
        runCatching {
            request(host, "/json/cfg", "POST", """{"light":{"gc":{"col":$gamma}}}""")
        }
        colorGamma(host)
    }

    /** Ends realtime mode so WLED returns to its normal effect immediately. */
    suspend fun exitLive(host: String) = withContext(Dispatchers.IO) {
        runCatching { request(host, "/json/state", "POST", """{"live":false}""") }
    }

    private fun request(host: String, path: String, method: String, body: String?): String {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun normalizeHost(input: String): String =
            input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    }
}
