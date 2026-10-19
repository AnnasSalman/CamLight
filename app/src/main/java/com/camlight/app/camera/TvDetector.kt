package com.camlight.app.camera

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

data class DetectResult(
    /** Outer silhouette of the TV (including bezel), normalized 0..1. */
    val silhouette: Quad,
    /** Picture area: the silhouette inset by the bezel. */
    val picture: Quad,
    val sidesFound: Int,
    val message: String,
)

data class DetectOutcome(val result: DetectResult?, val message: String)

/**
 * Finds the TV from a "LED halo" difference image (strip lit minus strip off). The strip faces the wall,
 * so the wall glows around the TV and the TV body is a dark hole with sharp edges. We cast rays from
 * inside the hole, take the first dark-to-lit transition, fit one line per side with RANSAC and intersect
 * neighbours for sub-pixel corners. One missing side is rebuilt from the aspect ratio.
 */
object TvDetector {
    private const val MIN_SIGNAL = 12f
    private const val RAY_STEP = 0.5f
    private const val MARGIN_DEG = 5.0
    private const val MIN_POINTS = 12
    private const val BEZEL_INSET = 0.012f

    private class Line(val px: Double, val py: Double, val dx: Double, val dy: Double)

    private class Hit(val x: Float, val y: Float, val angleDeg: Double)

    fun detect(diffIn: FloatArray, w: Int, h: Int, aspect: Float = 16f / 9f): DetectOutcome {
        val d = boxBlur(diffIn, w, h, 2)
        val max = d.max()
        if (max < MIN_SIGNAL) return fail("The LED glow on the wall is too faint (peak %.0f of 255). Dim the room lights and check the strip is on.".format(max))
        val t = otsu(d, max)

        // Seed: the dark pixel nearest to the centroid of the glow (the TV hole).
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in 0 until h) for (x in 0 until w) if (d[y * w + x] > t) { sx += x; sy += y; n++ }
        if (n < w * h / 200) return fail("Could not see a glow around the TV.")
        val seed = findDark(d, w, h, t, (sx / n).toFloat(), (sy / n).toFloat())
            ?: return fail("Could not find the dark TV area inside the glow.")

        val hits = castRays(d, w, h, t, seed)
        if (hits.size < 40) return fail("Too few TV edge points found (${hits.size}).")

        val a = Math.toDegrees(atan(1.0 / aspect))
        fun sector(angle: Double): Int? { // 0 top, 1 right, 2 bottom, 3 left
            val ang = angle
            return when {
                abs(ang) < a - MARGIN_DEG -> 1
                ang > a + MARGIN_DEG && ang < 180 - a - MARGIN_DEG -> 2
                abs(ang) > 180 - a + MARGIN_DEG -> 3
                ang < -(a + MARGIN_DEG) && ang > -(180 - a - MARGIN_DEG) -> 0
                else -> null
            }
        }
        val groups = List(4) { ArrayList<Hit>() }
        hits.forEach { hit -> sector(hit.angleDeg)?.let { groups[it].add(hit) } }
        val lines = groups.map { fitLine(it) }
        val found = lines.count { it != null }
        if (found < 3) return fail("Only $found TV edges found. Need at least 3.")

        val corners = buildCorners(lines, aspect) ?: return fail("The detected edges do not form a TV shape.")
        val (tl, tr, br, bl) = corners
        val sil = Quad(norm(tl, w, h), norm(tr, w, h), norm(br, w, h), norm(bl, w, h))
        if (!plausible(sil)) return fail("The detected shape does not look like a TV.")
        val msg = if (found == 4) "Found the TV (all 4 edges)."
        else "Found the TV (3 edges, the ${listOf("top", "right", "bottom", "left")[lines.indexOfFirst { it == null }]} was inferred from 16:9)."
        return DetectOutcome(DetectResult(sil, insetQuad(sil, BEZEL_INSET, aspect), found, msg), msg)
    }

    /** Shrinks the silhouette by [frac] of its width on every side (same physical amount top/bottom). */
    fun insetQuad(q: Quad, frac: Float, aspect: Float): Quad {
        val hg = Homography(q)
        val v = frac * aspect
        val tl = hg.map(frac, v)
        val tr = hg.map(1f - frac, v)
        val br = hg.map(1f - frac, 1f - v)
        val bl = hg.map(frac, 1f - v)
        return Quad(tl, tr, br, bl)
    }

    private fun fail(msg: String) = DetectOutcome(null, msg)

    private fun norm(p: Pt, w: Int, h: Int) = Pt(p.x / w, p.y / h)

    private fun plausible(q: Quad): Boolean {
        val p = q.toList()
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        var area = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            area += a.x * b.y - b.x * a.y
        }
        return abs(area) / 2f in 0.03f..1.2f
    }

    // ───────────── geometry ─────────────

    private fun buildCorners(lines: List<Line?>, aspect: Float): List<Pt>? {
        val top = lines[0]
        val right = lines[1]
        val bottom = lines[2]
        val left = lines[3]
        fun x(a: Line?, b: Line?): Pt? = if (a == null || b == null) null else intersect(a, b)

        var tl = x(top, left)
        var tr = x(top, right)
        var br = x(bottom, right)
        var bl = x(bottom, left)

        // Direction "down" of the vertical edges and "right" of the horizontal edges, for inference.
        fun dirDown(): Pair<Double, Double> {
            val v = listOfNotNull(left, right).map { if (it.dy >= 0) it.dx to it.dy else -it.dx to -it.dy }
            val dx = v.sumOf { it.first } / v.size
            val dy = v.sumOf { it.second } / v.size
            val len = hypot(dx, dy)
            return dx / len to dy / len
        }

        fun dirRight(): Pair<Double, Double> {
            val v = listOfNotNull(top, bottom).map { if (it.dx >= 0) it.dx to it.dy else -it.dx to -it.dy }
            val dx = v.sumOf { it.first } / v.size
            val dy = v.sumOf { it.second } / v.size
            val len = hypot(dx, dy)
            return dx / len to dy / len
        }

        fun move(p: Pt, dir: Pair<Double, Double>, dist: Double) =
            Pt((p.x + dir.first * dist).toFloat(), (p.y + dir.second * dist).toFloat())

        when {
            bottom == null -> {
                if (tl == null || tr == null) return null
                val hpx = dist(tl, tr) / aspect
                val dn = dirDown()
                bl = move(tl, dn, hpx.toDouble()); br = move(tr, dn, hpx.toDouble())
            }
            top == null -> {
                if (bl == null || br == null) return null
                val hpx = dist(bl, br) / aspect
                val dn = dirDown()
                tl = move(bl, dn, -hpx.toDouble()); tr = move(br, dn, -hpx.toDouble())
            }
            right == null -> {
                if (tl == null || bl == null) return null
                val wpx = dist(tl, bl) * aspect
                val rt = dirRight()
                tr = move(tl, rt, wpx.toDouble()); br = move(bl, rt, wpx.toDouble())
            }
            left == null -> {
                if (tr == null || br == null) return null
                val wpx = dist(tr, br) * aspect
                val rt = dirRight()
                tl = move(tr, rt, -wpx.toDouble()); bl = move(br, rt, -wpx.toDouble())
            }
        }
        if (tl == null || tr == null || br == null || bl == null) return null
        return listOf(tl, tr, br, bl)
    }

    private fun dist(a: Pt, b: Pt) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun intersect(a: Line, b: Line): Pt? {
        val det = a.dx * b.dy - a.dy * b.dx
        if (abs(det) < 1e-6) return null
        val t = ((b.px - a.px) * b.dy - (b.py - a.py) * b.dx) / det
        return Pt((a.px + t * a.dx).toFloat(), (a.py + t * a.dy).toFloat())
    }

    /** RANSAC line fit refined by total least squares. Null if there are too few consistent points. */
    private fun fitLine(points: List<Hit>): Line? {
        if (points.size < MIN_POINTS) return null
        val rnd = java.util.Random(7)
        var best: List<Hit> = emptyList()
        repeat(200) {
            val p = points[rnd.nextInt(points.size)]
            val q = points[rnd.nextInt(points.size)]
            val dx = (q.x - p.x).toDouble()
            val dy = (q.y - p.y).toDouble()
            val len = hypot(dx, dy)
            if (len < 8) return@repeat
            val ux = dx / len
            val uy = dy / len
            val inl = points.filter { abs((it.x - p.x) * uy - (it.y - p.y) * ux) < 1.5 }
            if (inl.size > best.size) best = inl
        }
        if (best.size < MIN_POINTS || best.size < points.size * 0.5) return null
        var line = pca(best)
        val refined = points.filter { abs((it.x - line.px) * line.dy - (it.y - line.py) * line.dx) < 2.0 }
        if (refined.size >= MIN_POINTS) line = pca(refined)
        return line
    }

    private fun pca(pts: List<Hit>): Line {
        val n = pts.size
        val mx = pts.sumOf { it.x.toDouble() } / n
        val my = pts.sumOf { it.y.toDouble() } / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        pts.forEach {
            val x = it.x - mx
            val y = it.y - my
            sxx += x * x; sxy += x * y; syy += y * y
        }
        val th = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(mx, my, cos(th), sin(th))
    }

    // ───────────── image helpers ─────────────

    private fun castRays(d: FloatArray, w: Int, h: Int, t: Float, seed: Pt): List<Hit> {
        val out = ArrayList<Hit>()
        val maxR = hypot(w.toFloat(), h.toFloat())
        for (deg in 0 until 360) {
            val rad = Math.toRadians(deg.toDouble() - 180.0)
            val ca = cos(rad).toFloat()
            val sa = sin(rad).toFloat()
            var r = 3f
            while (r < maxR) {
                r += RAY_STEP
                val x = seed.x + ca * r
                val y = seed.y + sa * r
                if (x < 1 || y < 1 || x > w - 2 || y > h - 2) break
                val v = sample(d, w, x, y)
                if (v <= t) continue
                var ok = true
                var rr = r
                for (k in 0 until 16) {
                    rr += RAY_STEP
                    val xx = seed.x + ca * rr
                    val yy = seed.y + sa * rr
                    if (xx < 1 || yy < 1 || xx > w - 2 || yy > h - 2) break
                    if (sample(d, w, xx, yy) < 0.7f * t) { ok = false; break }
                }
                if (ok) {
                    val pv = sample(d, w, seed.x + ca * (r - RAY_STEP), seed.y + sa * (r - RAY_STEP))
                    val frac = if (v > pv) ((t - pv) / (v - pv)).coerceIn(0f, 1f) else 1f
                    val rHit = r - RAY_STEP + RAY_STEP * frac
                    out.add(Hit(seed.x + ca * rHit, seed.y + sa * rHit, deg.toDouble() - 180.0))
                    break
                }
            }
        }
        return out
    }

    private fun sample(d: FloatArray, w: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val i = y0 * w + x0
        return d[i] * (1 - fx) * (1 - fy) + d[i + 1] * fx * (1 - fy) + d[i + w] * (1 - fx) * fy + d[i + w + 1] * fx * fy
    }

    private fun findDark(d: FloatArray, w: Int, h: Int, t: Float, cx: Float, cy: Float): Pt? {
        for (r in 0..maxOf(w, h) step 2) {
            for (a in 0 until 24) {
                val rad = Math.toRadians(a * 15.0)
                val x = (cx + cos(rad) * r).toInt()
                val y = (cy + sin(rad) * r).toInt()
                if (x in 2 until w - 2 && y in 2 until h - 2 && d[y * w + x] <= t) return Pt(x.toFloat(), y.toFloat())
            }
        }
        return null
    }

    private fun otsu(d: FloatArray, max: Float): Float {
        val hist = IntArray(256)
        d.forEach { hist[(it / max * 255f).toInt().coerceIn(0, 255)]++ }
        val total = d.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var wB = 0.0
        var sumB = 0.0
        var bestVar = -1.0
        var bestT = 0
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val v = wB * wF * (mB - mF) * (mB - mF)
            if (v > bestVar) { bestVar = v; bestT = i }
        }
        return (bestT + 1) / 255f * max
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val k = 2 * r + 1
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / k
                acc += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc / k
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }
}
