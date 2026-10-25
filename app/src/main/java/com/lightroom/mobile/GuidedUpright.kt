package com.lightroom.mobile

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Guided perspective correction ("Guided Upright"). The user draws reference lines on the photo that should be
 * truly vertical or horizontal; [solve] finds the straighten + vertical/horizontal keystone that makes them so,
 * expressed in the same geometry parameters the renderer already applies (no new render path needed).
 */
object GuidedUpright {
    /** A drawn guide in image-normalized coordinates (0..1). Classified as vertical when it's taller than wide. */
    data class Guide(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
        val vertical: Boolean get() = abs(y2 - y1) >= abs(x2 - x1)
        val lengthSq: Float get() = (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
    }

    /** Returns (straighten°, geometryVertical, geometryHorizontal) that best level/plumb the [guides]. */
    fun solve(guides: List<Guide>, aspect: Float): Triple<Float, Float, Float> {
        val usable = guides.filter { it.lengthSq > 0.0025f } // ignore stray taps (< 5% of the frame)
        if (usable.isEmpty()) return Triple(0f, 0f, 0f)
        val h = 1000f; val w = aspect.coerceIn(.2f, 5f) * h
        val lines = usable.map { floatArrayOf(it.x1 * w, it.y1 * h, it.x2 * w, it.y2 * h) to it.vertical }

        fun cost(st: Float, gv: Float, gh: Float): Float {
            val m = perspective(w, h, gv, gh).apply { preConcat(Matrix().apply { setRotate(st, w / 2f, h / 2f) }) }
            var c = 0f
            for ((p, vertical) in lines) {
                val q = p.copyOf(); m.mapPoints(q)
                val dx = (q[2] - q[0]).toDouble(); val dy = (q[3] - q[1]).toDouble()
                // Deviation from the target axis (radians); vertical lines want dx≈0, horizontal want dy≈0.
                val dev = if (vertical) atan2(dx, dy) else atan2(dy, dx)
                c += (dev * dev).toFloat()
            }
            return c
        }

        // Coordinate descent: each parameter is solved by a golden-ish ternary search holding the others fixed.
        var st = 0f; var gv = 0f; var gh = 0f
        repeat(6) {
            st = minimize(-20f, 20f) { cost(it, gv, gh) }
            gv = minimize(-100f, 100f) { cost(st, it, gh) }
            gh = minimize(-100f, 100f) { cost(st, gv, it) }
        }
        return Triple(st.coerceIn(-20f, 20f), gv.coerceIn(-100f, 100f), gh.coerceIn(-100f, 100f))
    }

    private inline fun minimize(lo: Float, hi: Float, f: (Float) -> Float): Float {
        var a = lo; var b = hi
        repeat(44) { val m1 = a + (b - a) / 3f; val m2 = b - (b - a) / 3f; if (f(m1) < f(m2)) b = m2 else a = m1 }
        return (a + b) / 2f
    }

    /** Exactly the keystone warp ImageExporter.perspectiveMatrix builds, so the solved values match the render. */
    private fun perspective(w: Float, h: Float, gv: Float, gh: Float): Matrix {
        val vertical = gv / 100f * w * .22f; val horizontal = gh / 100f * h * .22f
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val dst = floatArrayOf(
            if (vertical > 0f) vertical else 0f, if (horizontal > 0f) horizontal else 0f,
            if (vertical > 0f) w - vertical else w, if (horizontal < 0f) -horizontal else 0f,
            if (vertical < 0f) w + vertical else w, if (horizontal < 0f) h + horizontal else h,
            if (vertical < 0f) -vertical else 0f, if (horizontal > 0f) h - horizontal else h,
        )
        return Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
    }
}
