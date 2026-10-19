package com.camlight.app.data

import android.content.Context
import com.camlight.app.camera.Pt
import com.camlight.app.camera.Quad
import com.camlight.app.model.StripConfig
import org.json.JSONArray
import org.json.JSONObject

data class CameraSettings(
    val zoom: Float = 1f,
    val brightness: Float = 0.8f,
    val quad: Quad = Quad.DEFAULT,
    val saturation: Float = 1.2f,
    val warmth: Float = 0.7f,
    /** Black floor measured with the TV black (linear light, per channel); null = learn automatically. */
    val black: FloatArray? = null,
    val look: String = "basic",
    /** Preferred camera frame rate, 30 or 60. */
    val fps: Int = 30,
    /** White balance gains measured with the TV white (green = 1); null = not calibrated. */
    val white: FloatArray? = null,
    val smoothing: String = "NORMAL",
)

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("camlight", Context.MODE_PRIVATE)

    var lastHost: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v).apply()

    /** Secret in the remote page address; created once. */
    val remoteKey: String
        get() = prefs.getString("remoteKey", null) ?: run {
            val chars = "abcdefghijkmnpqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            val k = String(CharArray(8) { chars[rnd.nextInt(chars.length)] })
            prefs.edit().putString("remoteKey", k).apply()
            k
        }

    /** 4-digit code that opens only the TV test page; created once. */
    val patternCode: String
        get() = prefs.getString("patternCode", null) ?: run {
            val c = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
            prefs.edit().putString("patternCode", c).apply()
            c
        }

    /** WLED color gamma before CamLight changed it (0 = we have not changed it). */
    var originalGamma: Float
        get() = prefs.getFloat("originalGamma", 0f)
        set(v) = prefs.edit().putFloat("originalGamma", v).apply()

    fun loadStrip(): StripConfig = StripConfig.fromJson(prefs.getString("strip", null))

    fun saveStrip(config: StripConfig) = prefs.edit().putString("strip", config.toJson()).apply()

    fun loadCamera(): CameraSettings = runCatching {
        val o = JSONObject(prefs.getString("camera", null) ?: return CameraSettings())
        val q = o.getJSONArray("quad")
        fun pt(i: Int) = Pt(q.getDouble(i * 2).toFloat(), q.getDouble(i * 2 + 1).toFloat())
        val b = o.optJSONArray("black")
        CameraSettings(
            zoom = o.getDouble("zoom").toFloat(),
            brightness = o.getDouble("brightness").toFloat(),
            quad = Quad(pt(0), pt(1), pt(2), pt(3)),
            saturation = o.optDouble("saturation", 1.2).toFloat(),
            warmth = o.optDouble("warmth", 0.7).toFloat(),
            black = if (b != null && b.length() == 3) FloatArray(3) { b.getDouble(it).toFloat() } else null,
            look = o.optString("look", "basic"),
            fps = o.optInt("fps", 30).let { if (it == 60) 60 else 30 },
            white = o.optJSONArray("white")?.takeIf { it.length() == 3 }?.let { w -> FloatArray(3) { w.getDouble(it).toFloat() } },
            smoothing = o.optString("smoothing", "NORMAL"),
        )
    }.getOrDefault(CameraSettings())

    fun saveCamera(s: CameraSettings) {
        val q = JSONArray().also { a -> s.quad.toList().forEach { a.put(it.x.toDouble()); a.put(it.y.toDouble()) } }
        val o = JSONObject()
            .put("zoom", s.zoom.toDouble())
            .put("brightness", s.brightness.toDouble())
            .put("quad", q)
            .put("saturation", s.saturation.toDouble())
            .put("warmth", s.warmth.toDouble())
            .put("look", s.look)
            .put("fps", s.fps)
            .put("smoothing", s.smoothing)
        s.white?.let { w -> o.put("white", JSONArray().also { a -> w.forEach { a.put(it.toDouble()) } }) }
        s.black?.let { bl -> o.put("black", JSONArray().also { a -> bl.forEach { a.put(it.toDouble()) } }) }
        prefs.edit().putString("camera", o.toString()).apply()
    }
}
