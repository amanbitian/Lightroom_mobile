package com.lightroom.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * The area a Generative Remove spot replaces, rasterized in image pixels over its bounding box.
 * [hole] (0/1) is what gets regenerated: the painted strokes widened by a feather ring. [alpha] is how much of
 * the regenerated result is blended back: 1 over the strokes, fading to 0 inside the ring, so every blended
 * pixel is generated content and pixels outside the ring stay untouched.
 */
class RemovalRegion(val left: Int, val top: Int, val width: Int, val height: Int, val hole: FloatArray, val alpha: FloatArray) {
    companion object {
        fun of(spot: HealingSpot, imageWidth: Int, imageHeight: Int): RemovalRegion? {
            val radius = max(2f, spot.radius * min(imageWidth, imageHeight))
            val ring = radius * (.1f + spot.feather.coerceIn(0f, 1f) * .25f) + 2f
            val strokes = if (spot.isPainted) spot.strokes.filter { it.isNotEmpty() }.ifEmpty { return null }
            else listOf(listOf(MaskPoint(spot.targetX, spot.targetY)))
            val xs = strokes.flatten().map { it.x * (imageWidth - 1) }; val ys = strokes.flatten().map { it.y * (imageHeight - 1) }
            val pad = radius + ring + 2f
            val left = (xs.min() - pad).toInt().coerceIn(0, imageWidth - 1); val right = (xs.max() + pad).toInt().coerceIn(left + 1, imageWidth)
            val top = (ys.min() - pad).toInt().coerceIn(0, imageHeight - 1); val bottom = (ys.max() + pad).toInt().coerceIn(top + 1, imageHeight)
            val w = right - left; val h = bottom - top
            fun rasterize(halfWidth: Float): FloatArray {
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = -0x1; strokeWidth = halfWidth * 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                strokes.forEach { stroke ->
                    val pts = stroke.map { it.x * (imageWidth - 1) - left to it.y * (imageHeight - 1) - top }
                    if (pts.size == 1) canvas.drawCircle(pts[0].first, pts[0].second, halfWidth, paint.apply { style = Paint.Style.FILL })
                    else canvas.drawPath(Path().apply { moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { lineTo(it.first, it.second) } }, paint.apply { style = Paint.Style.STROKE })
                }
                val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h); bitmap.recycle()
                return FloatArray(w * h) { ((pixels[it] ushr 24) and 255) / 255f }
            }
            val hole = rasterize(radius + ring).also { for (i in it.indices) it[i] = if (it[i] > .02f) 1f else 0f }
            // Fade: strokes widened by half the ring, then blurred by half the ring -> 1 on the strokes, 0 at the ring's edge.
            val alpha = boxBlur(rasterize(radius + ring / 2f), w, h, max(1, (ring / 2f).toInt()))
            for (i in alpha.indices) alpha[i] = (alpha[i] * 2f).coerceIn(0f, 1f) * hole[i]
            return RemovalRegion(left, top, w, h, hole, alpha)
        }

        private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
            fun pass(input: FloatArray, horizontal: Boolean): FloatArray {
                val out = FloatArray(input.size)
                val len = if (horizontal) w else h; val lines = if (horizontal) h else w; val window = radius * 2 + 1
                for (line in 0 until lines) {
                    fun idx(p: Int) = if (horizontal) line * w + p else p * w + line
                    var sum = 0f
                    for (k in -radius..radius) sum += input[idx(k.coerceIn(0, len - 1))]
                    for (p in 0 until len) {
                        out[idx(p)] = sum / window
                        sum += input[idx((p + radius + 1).coerceIn(0, len - 1))] - input[idx((p - radius).coerceIn(0, len - 1))]
                    }
                }
                return out
            }
            return pass(pass(src, true), false)
        }
    }

    /** Blends [fill] (a bitmap positioned at [fillLeft], [fillTop] in image pixels) into [image] using [alpha]. */
    fun blend(image: Bitmap, fill: Bitmap, fillLeft: Int, fillTop: Int, opacity: Float) {
        val row = IntArray(width); val fillRow = IntArray(width)
        for (y in 0 until height) {
            image.getPixels(row, 0, width, left, top + y, width, 1)
            fill.getPixels(fillRow, 0, width, left - fillLeft, top + y - fillTop, width, 1)
            for (x in 0 until width) {
                val a = alpha[y * width + x] * opacity
                if (a <= .002f) continue
                val o = row[x]; val f = fillRow[x]
                fun mix(sh: Int) = ((o shr sh and 255) + ((f shr sh and 255) - (o shr sh and 255)) * a).toInt().coerceIn(0, 255)
                row[x] = (o and -0x1000000) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
            image.setPixels(row, 0, width, left, top + y, width, 1)
        }
    }
}

/**
 * Classic fill for any shape (used before the LaMa model is installed and while painting): a push-pull
 * pyramid averages the known pixels around the hole at ever coarser scales, then fills the hole from the
 * coarsest level back up. Smooth rather than textured, but it never leaves a gap whatever the shape.
 */
object PushPullFill {
    fun fill(image: Bitmap, region: RemovalRegion, opacity: Float) {
        // Work on the region plus a margin, so the hole sees real surroundings on every side.
        val margin = max(region.width, region.height) / 2 + 8
        val left = max(0, region.left - margin); val top = max(0, region.top - margin)
        val right = min(image.width, region.left + region.width + margin); val bottom = min(image.height, region.top + region.height + margin)
        val w = right - left; val h = bottom - top
        val pixels = IntArray(w * h); image.getPixels(pixels, 0, w, left, top, w, h)
        val weight = FloatArray(w * h) { i ->
            val x = i % w + left - region.left; val y = i / w + top - region.top
            if (x in 0 until region.width && y in 0 until region.height && region.hole[y * region.width + x] > .5f) 0f else 1f
        }
        val r = FloatArray(w * h) { (pixels[it] shr 16 and 255) * weight[it] }
        val g = FloatArray(w * h) { (pixels[it] shr 8 and 255) * weight[it] }
        val b = FloatArray(w * h) { (pixels[it] and 255) * weight[it] }
        val filled = pull(r, g, b, weight, w, h)
        // Push-pull alone is a smooth blur. Copy high-frequency texture from valid neighbours (mirrored across
        // the hole edge) onto the smooth base, so the fill reads as surrounding detail instead of a plastic patch.
        addTexture(filled, pixels, weight, w, h)
        val fillBitmap = Bitmap.createBitmap(IntArray(w * h) { i ->
            -0x1000000 or (filled[0][i].toInt().coerceIn(0, 255) shl 16) or (filled[1][i].toInt().coerceIn(0, 255) shl 8) or filled[2][i].toInt().coerceIn(0, 255)
        }, w, h, Bitmap.Config.ARGB_8888)
        region.blend(image, fillBitmap, left, top, opacity)
        fillBitmap.recycle()
    }

    /**
     * Adds local texture to the smooth [filled] base inside the hole (weight == 0). For each hole pixel it finds
     * the nearest valid pixel by an 8-direction ray search, mirrors to a donor the same depth into valid territory,
     * and adds that donor's high-frequency detail (donor minus its 3x3 valid neighbourhood). The low frequencies
     * stay from push-pull (so the colour is right); only fine detail is borrowed, clamped so no hard edge leaks in.
     */
    private fun addTexture(filled: Array<FloatArray>, pixels: IntArray, weight: FloatArray, w: Int, h: Int) {
        fun valid(x: Int, y: Int) = x in 0 until w && y in 0 until h && weight[y * w + x] > 0f
        val dirs = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
        val reach = max(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            if (weight[y * w + x] > 0f) continue // only fill holes
            var bestT = Int.MAX_VALUE; var dxBest = 0; var dyBest = 0
            for ((dx, dy) in dirs) {
                var t = 1
                while (t <= reach && x + dx * t in 0 until w && y + dy * t in 0 until h && weight[(y + dy * t) * w + (x + dx * t)] <= 0f) t++
                if (t <= reach && x + dx * t in 0 until w && y + dy * t in 0 until h && t < bestT) { bestT = t; dxBest = dx; dyBest = dy }
            }
            if (bestT == Int.MAX_VALUE) continue
            // Mirror the hole depth onto the far (valid) side; fall back to the boundary pixel if that lands off/again in the hole.
            var dx = x + dxBest * (2 * bestT - 1); var dy = y + dyBest * (2 * bestT - 1)
            if (!valid(dx, dy)) { dx = x + dxBest * bestT; dy = y + dyBest * bestT }
            if (!valid(dx, dy)) continue
            var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            for (ny in dy - 1..dy + 1) for (nx in dx - 1..dx + 1) if (valid(nx, ny)) {
                val c = pixels[ny * w + nx]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++
            }
            if (n == 0) continue
            val c = pixels[dy * w + dx]
            val i = y * w + x
            filled[0][i] = (filled[0][i] + ((c shr 16 and 255) - sr / n).coerceIn(-48f, 48f)).coerceIn(0f, 255f)
            filled[1][i] = (filled[1][i] + ((c shr 8 and 255) - sg / n).coerceIn(-48f, 48f)).coerceIn(0f, 255f)
            filled[2][i] = (filled[2][i] + ((c and 255) - sb / n).coerceIn(-48f, 48f)).coerceIn(0f, 255f)
        }
    }

    /** Smoothly interpolates three channels known where [w] > 0 (values given unweighted) over the whole grid. */
    fun interpolate(r: FloatArray, g: FloatArray, b: FloatArray, w: FloatArray, width: Int, height: Int): Array<FloatArray> =
        pull(FloatArray(r.size) { r[it] * w[it] }, FloatArray(g.size) { g[it] * w[it] }, FloatArray(b.size) { b[it] * w[it] }, w, width, height)

    /** Returns normalized colours with every zero-weight pixel filled from coarser levels. */
    private fun pull(r: FloatArray, g: FloatArray, b: FloatArray, w: FloatArray, width: Int, height: Int): Array<FloatArray> {
        if (width <= 2 || height <= 2) {
            val total = w.sum().coerceAtLeast(1e-4f)
            val avg = floatArrayOf(r.sum() / total, g.sum() / total, b.sum() / total)
            return Array(3) { c -> FloatArray(width * height) { i -> if (w[i] > 0f) arrayOf(r, g, b)[c][i] / w[i] else avg[c] } }
        }
        val cw = (width + 1) / 2; val ch = (height + 1) / 2
        val cr = FloatArray(cw * ch); val cg = FloatArray(cw * ch); val cb = FloatArray(cw * ch); val cwt = FloatArray(cw * ch)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x; val j = (y / 2) * cw + x / 2
            cr[j] += r[i]; cg[j] += g[i]; cb[j] += b[i]; cwt[j] += w[i]
        }
        val coarse = pull(cr, cg, cb, cwt, cw, ch)
        return Array(3) { c ->
            val src = arrayOf(r, g, b)[c]
            FloatArray(width * height) { i ->
                val x = i % width; val y = i / width
                // Bilinear sample of the coarser level (centre-aligned).
                val fx = ((x - .5f) / 2f).coerceIn(0f, cw - 1f); val fy = ((y - .5f) / 2f).coerceIn(0f, ch - 1f)
                val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, cw - 1); val y1 = min(y0 + 1, ch - 1)
                val tx = fx - x0; val ty = fy - y0
                val coarseValue = coarse[c][y0 * cw + x0] * (1 - tx) * (1 - ty) + coarse[c][y0 * cw + x1] * tx * (1 - ty) +
                    coarse[c][y1 * cw + x0] * (1 - tx) * ty + coarse[c][y1 * cw + x1] * tx * ty
                // Known pixels keep their own value; partially known blend toward it.
                val known = w[i].coerceAtMost(1f)
                if (w[i] > 0f) (src[i] / w[i]) * known + coarseValue * (1 - known) else coarseValue
            }
        }
    }
}
