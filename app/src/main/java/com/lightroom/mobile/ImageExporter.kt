package com.lightroom.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

object ImageExporter {
    private data class BrushMaskMap(val width: Int, val height: Int, val alpha: IntArray)

    /** What an export produced: the saved file and where, or why it failed. */
    sealed interface ExportResult {
        data class Saved(val uri: Uri, val location: String) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    suspend fun export(context: Context, photo: PhotoItem, state: DevelopState, options: ExportOptions = ExportOptions()): ExportResult = withContext(Dispatchers.IO) {
        try {
            // Each stage consumes (recycles) its input, so roughly one full-size copy is alive at a time.
            val source = decodeScaled(context, Uri.parse(photo.uri), options.maxSide.coerceIn(512, 6000))
                ?: return@withContext ExportResult.Failed("Couldn't read the original photo")
            // AI masks and the background effect are segmented on the original (as in the editor's preview), then looked
            // up through the inverse frame mapping, so crop/rotation never change what they select.
            val aiTypes = state.masks.filter { it.enabled && it.type in aiMaskTypes }.map { it.type }.toSet() +
                (if (state.background != "None") setOf("Subject") else emptySet())
            val sourceMasks = aiTypes.associateWith { segment(it, source) }
            val (framed, frame) = transform(source, state)
            // Masks, heal spots and the blur focus are positioned on the original photo; move them into the output frame.
            val local = frame.remap(state)
            val grid = if (sourceMasks.isEmpty()) null else frame.sourceGrid()
            val ai = if (grid == null) null else SourceMasks(sourceMasks, grid)
            val healed = applyHealing(framed, local, aiFill = true)
            val baseName = photo.name.substringBeforeLast('.').ifBlank { "edit" }
            val isTiff = options.format.equals("TIFF", ignoreCase = true)
            val isPng16 = options.format.equals("PNG16", ignoreCase = true)
            if (isTiff || isPng16) {
                val w = healed.width; val h = healed.height
                // Off the Java heap: at 24 MP this buffer alone is 144 MB, more than many apps' heap limit.
                val out16 = java.nio.ByteBuffer.allocateDirect(w * h * 3 * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                // The colour engine writes the 16-bit tonal result; detail, background and lens blur are 8-bit passes,
                // so their per-pixel change is added on top (pixels they don't touch keep full 16-bit precision).
                val finalBitmap = applyLensBlur(applyBackground(adjustPixels(healed, local, out16, sourceMasks = ai), local, sourceMasks = ai), local)
                addEightBitEffects(out16, finalBitmap)
                finalBitmap.recycle()
                val extension = if (isTiff) "tif" else "png"
                val mime = if (isTiff) "image/tiff" else "image/png"
                writeImage(context, "${baseName}_16_${System.currentTimeMillis()}.$extension", mime) { os ->
                    if (isTiff) TiffPngWriter.writeTiff16(out16, w, h, os) else TiffPngWriter.writePng16(out16, w, h, os)
                }
            } else {
                val isPng = options.format.equals("PNG", ignoreCase = true)
                val compressFormat = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = options.quality.coerceIn(50, 100)
                val finalBitmap = applyLensBlur(applyBackground(adjustPixels(healed, local, sourceMasks = ai), local, sourceMasks = ai), local)
                val extension = if (isPng) "png" else "jpg"
                val mime = if (isPng) "image/png" else "image/jpeg"
                try {
                    writeImage(context, "${baseName}_${System.currentTimeMillis()}.$extension", mime) { os ->
                        check(finalBitmap.compress(compressFormat, quality, os)) { "Encoding failed" }
                    }
                } finally { finalBitmap.recycle() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            ExportResult.Failed("Not enough memory at this size. Try a smaller Max dimension.")
        } catch (error: Throwable) {
            ExportResult.Failed("Couldn't save: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** AI masks segmented on the original photo plus the lookup from the current frame back to it. */
    internal class SourceMasks(val masks: Map<String, SubjectSegmenter.Mask?>, val grid: SourceGrid) {
        fun alpha(type: String, px: Float, py: Float) = grid.alpha(masks[type], px, py)
    }

    private fun segment(type: String, bitmap: Bitmap): SubjectSegmenter.Mask? = when (type) {
        "Sky" -> SkySegmenter.skyMask(bitmap)
        "Hair" -> SubjectSegmenter.mask(bitmap, SubjectSegmenter.Part.HAIR)
        "Skin" -> SubjectSegmenter.mask(bitmap, SubjectSegmenter.Part.SKIN)
        "Clothes" -> SubjectSegmenter.mask(bitmap, SubjectSegmenter.Part.CLOTHES)
        else -> SubjectSegmenter.mask(bitmap, SubjectSegmenter.Part.PERSON)
    }

    /** Adds (final 8-bit − tonal 8-bit) to the 16-bit buffer, so 8-bit effect passes reach 16-bit exports too. */
    private fun addEightBitEffects(out16: java.nio.ShortBuffer, finalBitmap: Bitmap) {
        val w = finalBitmap.width; val h = finalBitmap.height
        val row = IntArray(w)
        for (y in 0 until h) {
            finalBitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val base = (y * w + x) * 3
                for (k in 0..2) {
                    val v16 = out16.get(base + k).toInt() and 0xFFFF
                    val tonal8 = (v16 + 128) / 257
                    val final8 = (c shr (16 - k * 8)) and 255
                    if (final8 != tonal8) out16.put(base + k, (v16 + (final8 - tonal8) * 257).coerceIn(0, 65535).toShort())
                }
            }
        }
    }

    /** Maps normalized positions on the original photo to normalized positions on the exported frame. */
    // Internal (not private) so the on-device tests in androidTest can drive each pipeline stage directly.
    /** Bilinear lookup of output -> source positions (see [FrameMap.sourceGrid]). */
    internal class SourceGrid(private val n: Int, private val gx: FloatArray, private val gy: FloatArray) {
        fun alpha(mask: SubjectSegmenter.Mask?, px: Float, py: Float): Float {
            if (mask == null) return 0f
            val fx = (px * (n - 1)).coerceIn(0f, n - 1f); val fy = (py * (n - 1)).coerceIn(0f, n - 1f)
            val i = min(fx.toInt(), n - 2); val j = min(fy.toInt(), n - 2); val tx = fx - i; val ty = fy - j
            fun lerp2(a: FloatArray) = (a[j * n + i] * (1 - tx) + a[j * n + i + 1] * tx) * (1 - ty) + (a[(j + 1) * n + i] * (1 - tx) + a[(j + 1) * n + i + 1] * tx) * ty
            val sx = lerp2(gx); val sy = lerp2(gy)
            if (sx < 0f || sx > 1f || sy < 0f || sy > 1f) return 0f
            return sampleMaskAlpha(mask, sx, sy)
        }
    }

    internal class FrameMap(
        private val before: Matrix, private val srcW: Int, private val srcH: Int, private val outW: Int, private val outH: Int,
        private val distortion: Distortion? = null, private val after: Matrix = Matrix(),
    ) {
        /** Lens distortion applied between [before] and [after], in that intermediate frame's pixels. */
        class Distortion(val cx: Float, val cy: Float, val norm: Float, val k: Float)

        private val scale = before.mapRadius(1f) * after.mapRadius(1f)
        private val identity = before.isIdentity && after.isIdentity && distortion == null && srcW == outW && srcH == outH

        fun point(x: Float, y: Float): Pair<Float, Float> {
            val p = floatArrayOf(x * srcW, y * srcH)
            before.mapPoints(p)
            distortion?.let { d ->
                // The warp samples the source at c + (o - c)(1 + k r²); a source point lands at about c + (s - c)/(1 + k r²).
                val dx = (p[0] - d.cx) / d.norm; val dy = (p[1] - d.cy) / d.norm
                val factor = 1f + d.k * (dx * dx + dy * dy)
                p[0] = d.cx + (p[0] - d.cx) / factor; p[1] = d.cy + (p[1] - d.cy) / factor
            }
            after.mapPoints(p)
            return p[0] / outW to p[1] / outH
        }

        private val beforeInverse = Matrix().also { before.invert(it) }
        private val afterInverse = Matrix().also { after.invert(it) }

        /** Output-normalized position -> source-normalized position (inverse of [point]). */
        fun source(x: Float, y: Float): Pair<Float, Float> {
            val p = floatArrayOf(x * outW, y * outH)
            afterInverse.mapPoints(p)
            distortion?.let { d ->
                // Exact inverse: the warp sampled the source at c + (o - c)(1 + k r²).
                val dx = (p[0] - d.cx) / d.norm; val dy = (p[1] - d.cy) / d.norm
                val factor = 1f + d.k * (dx * dx + dy * dy)
                p[0] = d.cx + (p[0] - d.cx) * factor; p[1] = d.cy + (p[1] - d.cy) * factor
            }
            beforeInverse.mapPoints(p)
            return p[0] / srcW to p[1] / srcH
        }

        /** [source] sampled on an n×n grid, for per-pixel lookups of masks segmented on the original photo. */
        fun sourceGrid(n: Int = 33): SourceGrid {
            val gx = FloatArray(n * n); val gy = FloatArray(n * n)
            for (j in 0 until n) for (i in 0 until n) {
                val (sx, sy) = source(i / (n - 1f), j / (n - 1f)); gx[j * n + i] = sx; gy[j * n + i] = sy
            }
            return SourceGrid(n, gx, gy)
        }

        /** Converts a length measured in units of the source's [srcUnit] pixels to the output's [outUnit]. */
        private fun length(value: Float, srcUnit: Int, outUnit: Int) = value * srcUnit * scale / outUnit.coerceAtLeast(1)

        fun remap(state: DevelopState): DevelopState {
            if (identity) return state
            val srcShort = min(srcW, srcH); val outShort = min(outW, outH)
            val spots = state.healingSpots.map { spot ->
                val (tx, ty) = point(spot.targetX, spot.targetY); val (sx, sy) = point(spot.sourceX, spot.sourceY)
                spot.copy(
                    targetX = tx, targetY = ty, sourceX = sx, sourceY = sy, radius = length(spot.radius, srcShort, outShort),
                    strokes = spot.strokes.map { stroke -> stroke.map { p -> point(p.x, p.y).let { (x, y) -> MaskPoint(x, y) } } },
                )
            }
            val masks = state.masks.map { mask ->
                when (mask.type) {
                    in aiMaskTypes -> mask // segmented on the original and looked up through the inverse mapping
                    "Brush" -> mask.copy(
                        points = emptyList(),
                        strokes = mask.brushStrokes.map { stroke -> stroke.map { pt -> point(pt.x, pt.y).let { (x, y) -> MaskPoint(x, y) } } },
                        size = length(mask.size, srcShort, outShort),
                    )
                    else -> {
                        // Radial/Linear sizes and angles are in real pixels (size in short-side units), so map a short
                        // pixel step along the gradient direction to get the output angle and scale.
                        val (cx, cy) = point(mask.centerX, mask.centerY)
                        val stepPx = srcShort * .01f
                        val angle = Math.toRadians(mask.angle.toDouble())
                        val (nx, ny) = point(mask.centerX + (cos(angle) * stepPx / srcW).toFloat(), mask.centerY + (sin(angle) * stepPx / srcH).toFloat())
                        val dxPx = (nx - cx) * outW; val dyPx = (ny - cy) * outH
                        val stretch = sqrt(dxPx * dxPx + dyPx * dyPx) / stepPx
                        mask.copy(
                            centerX = cx, centerY = cy,
                            size = mask.size * stretch * srcShort / outShort.coerceAtLeast(1),
                            angle = Math.toDegrees(kotlin.math.atan2(dyPx, dxPx).toDouble()).toFloat(),
                        )
                    }
                }
            }
            val (fx, fy) = point(state.lensBlurFocusX, state.lensBlurFocusY)
            // Transform the focus ellipse through the same geometry: map its main and cross axis tips and rebuild
            // radius/angle/aspect, so an angled elliptical blur matches across rotation, crop and lens correction.
            val theta = Math.toRadians(state.lensBlurAngle.toDouble())
            val cosT = cos(theta).toFloat(); val sinT = sin(theta).toFloat()
            val focusR = state.lensBlurFocusRadius; val asp = state.lensBlurAspect.coerceIn(.2f, 5f)
            val sHW = srcH.toFloat() / srcW // focus is measured in height-isotropic units
            val (mx, my) = point(state.lensBlurFocusX + cosT * focusR * sHW, state.lensBlurFocusY + sinT * focusR)
            val (crx, cry) = point(state.lensBlurFocusX - sinT * focusR * asp * sHW, state.lensBlurFocusY + cosT * focusR * asp)
            val oHW = outW.toFloat() / outH
            val mainX = (mx - fx) * oHW; val mainY = my - fy
            val crossX = (crx - fx) * oHW; val crossY = cry - fy
            val newRadius = sqrt(mainX * mainX + mainY * mainY).coerceIn(.05f, 1f)
            return state.copy(
                healingSpots = spots, masks = masks,
                lensBlurFocusX = fx, lensBlurFocusY = fy,
                lensBlurFocusRadius = newRadius,
                lensBlurAngle = Math.toDegrees(kotlin.math.atan2(mainY.toDouble(), mainX.toDouble())).toFloat(),
                lensBlurAspect = (sqrt(crossX * crossX + crossY * crossY) / newRadius.coerceAtLeast(1e-4f)).coerceIn(.2f, 5f),
            )
        }
    }

    /** Writes an image to Pictures/LumaRoom via MediaStore (Q+) or the app's external files dir. */
    /**
     * Writes an image to Pictures/LumaRoom and returns where it went. A failed write removes its half-created
     * MediaStore entry. Android 8-9 need the storage permission for the public folder; without it the file goes to
     * the app's own Pictures folder and the result says so.
     */
    private fun writeImage(context: Context, fileName: String, mime: String, write: (java.io.OutputStream) -> Unit): ExportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LumaRoom")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return ExportResult.Failed("The gallery refused a new file")
            try {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("Couldn't open the new file")
                stream.use(write)
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
            return ExportResult.Saved(uri, "Pictures/LumaRoom")
        }
        val public = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val root = if (public) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) else context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val directory = File(root, "LumaRoom").apply { mkdirs() }
        val file = File(directory, fileName)
        try { FileOutputStream(file).use(write) } catch (error: Throwable) { file.delete(); throw error }
        // Index the file so it appears in gallery apps.
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
        return ExportResult.Saved(Uri.fromFile(file), if (public) "Pictures/LumaRoom" else "the app's Pictures folder (allow storage to use Pictures)")
    }

    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? =
        RawDecoder.decode(context, uri, maxSide)

    /**
     * Orients, straightens, crops and warps [source] (recycling it), returning the result and the source-to-output
     * mapping. The order matches the editor: 90° turns/flip, straighten (auto-zoomed so no corners show), crop.
     */
    internal fun transform(source: Bitmap, state: DevelopState): Pair<Bitmap, FrameMap> {
        val srcW = source.width; val srcH = source.height
        val mapping = Matrix()
        var bitmap = source
        fun replace(next: Bitmap) { if (next !== bitmap) bitmap.recycle(); bitmap = next }
        val quarterTurns = ((state.rotation % 360) + 360) % 360
        if (quarterTurns != 0 || state.flipHorizontal) {
            // Rotate, then flip: Flip mirrors the photo as you see it (left-right), whatever its rotation.
            val orient = Matrix().apply { postRotate(quarterTurns.toFloat()); postScale(if (state.flipHorizontal) -1f else 1f, 1f) }
            // createBitmap draws through the matrix, then shifts the result so its bounds start at (0, 0).
            val bounds = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            orient.mapRect(bounds)
            replace(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, orient, true))
            mapping.postConcat(orient); mapping.postTranslate(-bounds.left, -bounds.top)
        }
        if (state.straighten != 0f) {
            val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
            val zoom = straightenScale(state.straighten, w / h)
            val straighten = Matrix().apply {
                setTranslate(-w / 2f, -h / 2f); postScale(zoom, zoom); postRotate(state.straighten); postTranslate(w / 2f, h / 2f)
            }
            val turned = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(turned).drawBitmap(bitmap, straighten, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            replace(turned)
            mapping.postConcat(straighten)
        }
        val (left, top, right, bottom) = state.effectiveCrop(bitmap.width.toFloat() / bitmap.height).let { listOf(it[0], it[1], it[2], it[3]) }
        val cropX = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val cropY = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val cropWidth = ((right - left) * bitmap.width).toInt().coerceIn(1, bitmap.width - cropX)
        val cropHeight = ((bottom - top) * bitmap.height).toInt().coerceIn(1, bitmap.height - cropY)
        if (cropWidth != bitmap.width || cropHeight != bitmap.height) {
            replace(Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight))
            mapping.postTranslate(-cropX.toFloat(), -cropY.toFloat())
        }
        // Lens distortion: a radial warp; FrameMap moves positions through it so masks stay on their content.
        var distortion: FrameMap.Distortion? = null
        if (state.lensDistortion != 0f) {
            val cx = (bitmap.width - 1) / 2f; val cy = (bitmap.height - 1) / 2f
            distortion = FrameMap.Distortion(cx, cy, sqrt(cx * cx + cy * cy).coerceAtLeast(1f), state.lensDistortion / 100f * 0.35f)
            replace(applyDistortion(bitmap, state.lensDistortion))
        }
        val after = Matrix()
        if (state.geometryVertical != 0f || state.geometryHorizontal != 0f || state.geometryAspect != 0f) {
            val projected = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(projected)
            canvas.drawColor(Color.BLACK)
            val projective = perspectiveMatrix(bitmap.width.toFloat(), bitmap.height.toFloat(), state)
            canvas.drawBitmap(bitmap, projective, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            replace(projected)
            after.postConcat(projective)
        }
        return bitmap to FrameMap(mapping, srcW, srcH, bitmap.width, bitmap.height, distortion, after)
    }

    /** Radial (barrel/pincushion) warp drawn through a native mesh, so no full-frame pixel arrays sit on the heap. */
    /**
     * The Geometry (vertical / horizontal keystone + aspect) warp of a frame of this size. Shared with the editor,
     * which draws its preview through the same matrix so what you see is what exports.
     */
    fun perspectiveMatrix(width: Float, height: Float, state: DevelopState): Matrix {
        val vertical = state.geometryVertical / 100f * width * .22f
        val horizontal = state.geometryHorizontal / 100f * height * .22f
        val sourcePoints = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
        val destinationPoints = floatArrayOf(
            if (vertical > 0f) vertical else 0f, if (horizontal > 0f) horizontal else 0f,
            if (vertical > 0f) width - vertical else width, if (horizontal < 0f) -horizontal else 0f,
            if (vertical < 0f) width + vertical else width, if (horizontal < 0f) height + horizontal else height,
            if (vertical < 0f) -vertical else 0f, if (horizontal > 0f) height - horizontal else height,
        )
        return Matrix().apply {
            setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)
            val aspectScale = (1f + state.geometryAspect / 200f).coerceIn(.5f, 1.5f)
            postScale(aspectScale, 1f, width / 2f, height / 2f)
        }
    }

    internal fun applyDistortion(src: Bitmap, amount: Float): Bitmap {
        val w = src.width; val h = src.height
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val norm = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val k = amount / 100f * 0.35f
        val mesh = 48
        // Output pixel d samples source c + (d - c)(1 + k r²); each source grid point is placed at the inverse (first order).
        val verts = FloatArray((mesh + 1) * (mesh + 1) * 2)
        var v = 0
        for (gy in 0..mesh) for (gx in 0..mesh) {
            val sx = gx * (w - 1f) / mesh; val sy = gy * (h - 1f) / mesh
            val dx = (sx - cx) / norm; val dy = (sy - cy) / norm
            val factor = 1f + k * (dx * dx + dy * dy)
            verts[v++] = cx + (sx - cx) / factor; verts[v++] = cy + (sy - cy) / factor
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmapMesh(src, mesh, mesh, verts, 0, null, 0, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** One box-blur pass from [src] into [out] (reused buffers keep large exports from allocating per pass). */
    private fun boxBlurPass(src: IntArray, out: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean): IntArray {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            fun idx(pos: Int) = if (horizontal) line * w + pos else pos * w + line
            var sr = 0; var sg = 0; var sb = 0
            for (kk in -radius..radius) {
                val p = src[idx(kk.coerceIn(0, len - 1))]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (pos in 0 until len) {
                val o = src[idx(pos)]
                out[idx(pos)] = (o and -0x1000000) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val po = src[idx((pos - radius).coerceIn(0, len - 1))]
                val pi = src[idx((pos + radius + 1).coerceIn(0, len - 1))]
                sr += ((pi shr 16) and 255) - ((po shr 16) and 255)
                sg += ((pi shr 8) and 255) - ((po shr 8) and 255)
                sb += (pi and 255) - (po and 255)
            }
        }
        return out
    }

    internal fun applyLensBlur(src: Bitmap, state: DevelopState): Bitmap {
        if (state.lensBlur <= 0f) return src
        val w = src.width; val h = src.height
        if (w < 4 || h < 4) return src
        val radius = (state.lensBlur / 100f * minOf(w, h) * 0.03f).toInt().coerceIn(1, 60)
        val blur = blurredBitmap(src, radius)
        val target = mutable(src)
        val cx = state.lensBlurFocusX; val cy = state.lensBlurFocusY
        val focus = state.lensBlurFocusRadius.coerceIn(.05f, 1f)
        val feather = focus * 0.8f + 0.1f
        val aspect = w.toFloat() / h
        val ellipseAspect = state.lensBlurAspect.coerceIn(.2f, 5f)
        val la = Math.toRadians(state.lensBlurAngle.toDouble()); val lc = cos(la).toFloat(); val ls = sin(la).toFloat()
        // Subject focus: keep the detected subject sharp (or, inverted, blur it) instead of a geometric area.
        val subjectMask = when (state.lensBlurSubject) {
            "Person" -> SubjectSegmenter.foregroundMask(src)
            "Face" -> SubjectSegmenter.mask(src, SubjectSegmenter.Part.FACE)
            else -> null
        }?.let { SubjectSegmenter.Mask(it.width, it.height, featherMask(it.confidence, it.width, it.height, (minOf(it.width, it.height) * 0.02f).toInt().coerceIn(1, 24))) }
        val useSubject = subjectMask != null
        // Subject focus was requested but segmentation is unavailable: leave the photo sharp rather than
        // silently applying the hidden geometric focus area (which the subject UI doesn't expose).
        if (state.lensBlurSubject != "None" && subjectMask == null) { blur.recycle(); return target }
        val orig = IntArray(w); val blurRow = IntArray(w)
        for (y in 0 until h) {
            target.getPixels(orig, 0, w, 0, y, w, 1); blur.getPixels(blurRow, 0, w, 0, y, w, 1)
            val ny = y.toFloat() / (h - 1)
            for (x in 0 until w) {
                val nx = x.toFloat() / (w - 1)
                val weight = if (useSubject) {
                    val sharp = sampleMaskAlpha(subjectMask, nx, ny).let { if (state.lensBlurInvert) 1f - it else it }
                    (1f - sharp).coerceIn(0f, 1f)
                } else {
                    val ddx = (nx - cx) * aspect; val ddy = ny - cy
                    val rx = ddx * lc + ddy * ls; val ry = (-ddx * ls + ddy * lc) / ellipseAspect
                    ((sqrt(rx * rx + ry * ry) - focus) / feather).coerceIn(0f, 1f).let { if (state.lensBlurInvert) 1f - it else it }
                }
                if (weight <= .001f) continue
                val o = orig[x]; val b = blurRow[x]
                fun mix(sh: Int): Int { val a = (o shr sh) and 255; val c = (b shr sh) and 255; return (a + (c - a) * weight).toInt().coerceIn(0, 255) }
                orig[x] = (o and -0x1000000) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
            }
            target.setPixels(orig, 0, w, 0, y, w, 1)
        }
        blur.recycle()
        return target
    }

    /** [src] itself when mutable, otherwise a mutable copy (recycling [src]). */
    private fun mutable(src: Bitmap): Bitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }

    /**
     * A full-size blurred copy of [src]. The blur runs on a downscaled image (scaled so the radius is a few
     * pixels) and is scaled back up; for large radii that is visually identical and avoids full-frame arrays.
     */
    private fun blurredBitmap(src: Bitmap, radius: Int): Bitmap {
        val factor = (radius / 3).coerceIn(1, 16)
        val sw = max(1, src.width / factor); val sh = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val pixels = IntArray(sw * sh); small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()
        val smallBlur = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        smallBlur.setPixels(blurred(pixels, sw, sh, max(1, radius / factor)), 0, sw, 0, 0, sw, sh)
        val full = Bitmap.createScaledBitmap(smallBlur, src.width, src.height, true)
        if (full !== smallBlur) smallBlur.recycle()
        return full
    }

    /** Two horizontal+vertical box passes (close to a Gaussian) using two scratch buffers. */
    private fun blurred(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val a = IntArray(w * h); val b = IntArray(w * h)
        boxBlurPass(pixels, a, w, h, radius, true); boxBlurPass(a, b, w, h, radius, false)
        boxBlurPass(b, a, w, h, radius, true); boxBlurPass(a, b, w, h, radius, false)
        return b
    }

    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun sampleMaskAlpha(mask: SubjectSegmenter.Mask?, px: Float, py: Float): Float {
        if (mask == null) return 0f
        // Bilinear: the mask is only 256x256, so nearest-neighbour upscaling left the subject edge stair-stepped.
        val fx = (px * (mask.width - 1)).coerceIn(0f, mask.width - 1f)
        val fy = (py * (mask.height - 1)).coerceIn(0f, mask.height - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, mask.width - 1); val y1 = min(y0 + 1, mask.height - 1)
        val tx = fx - x0; val ty = fy - y0
        val c = mask.confidence
        val top = c[y0 * mask.width + x0] * (1 - tx) + c[y0 * mask.width + x1] * tx
        val bottom = c[y1 * mask.width + x0] * (1 - tx) + c[y1 * mask.width + x1] * tx
        return (top * (1 - ty) + bottom * ty).coerceIn(0f, 1f)
    }

    /** Separable box blur over a float mask so the foreground edge feathers instead of cutting hard. */
    private fun featherMask(mask: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius < 1) return mask
        fun pass(src: FloatArray, horizontal: Boolean): FloatArray {
            val out = FloatArray(src.size)
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val window = radius * 2 + 1
            for (line in 0 until lines) {
                fun idx(pos: Int) = if (horizontal) line * w + pos else pos * w + line
                var sum = 0f
                for (k in -radius..radius) sum += src[idx(k.coerceIn(0, len - 1))]
                for (pos in 0 until len) {
                    out[idx(pos)] = sum / window
                    sum += src[idx((pos + radius + 1).coerceIn(0, len - 1))] - src[idx((pos - radius).coerceIn(0, len - 1))]
                }
            }
            return out
        }
        return pass(pass(mask, true), false)
    }

    internal fun applyBackground(src: Bitmap, state: DevelopState, segmentationKey: Any? = null, sourceMasks: SourceMasks? = null): Bitmap {
        if (state.background == "None") return src
        val raw = if (sourceMasks != null) sourceMasks.masks["Subject"] else cachedSegmentation(segmentationKey, "Subject") { SubjectSegmenter.foregroundMask(src) }
        raw ?: return src
        val w = src.width; val h = src.height
        val mw = raw.width; val mh = raw.height
        val conf = featherMask(raw.confidence, mw, mh, (minOf(mw, mh) * 0.012f).toInt().coerceIn(1, 24))
        val feathered = SubjectSegmenter.Mask(mw, mh, conf)
        val blurBitmap = if (state.background == "Blur") blurredBitmap(src, (minOf(w, h) * 0.02f).toInt().coerceIn(2, 50)) else null
        val target = mutable(src)
        val out = IntArray(w); val blurRow = IntArray(w)
        for (y in 0 until h) {
            target.getPixels(out, 0, w, 0, y, w, 1)
            blurBitmap?.getPixels(blurRow, 0, w, 0, y, w, 1)
            val py = y.toFloat() / max(1, h - 1)
            for (x in 0 until w) {
                // Smoothstep on the feathered confidence for a gentle subject-to-background ramp.
                val px = x.toFloat() / max(1, w - 1)
                val c = (sourceMasks?.grid?.alpha(feathered, px, py) ?: sampleMaskAlpha(feathered, px, py)).coerceIn(0f, 1f)
                val smooth = c * c * (3f - 2f * c)
                val bg = (1f - smooth).coerceIn(0f, 1f)
                val o = out[x]
                if (bg <= 0.02f) continue
                val r = (o shr 16) and 255; val g = (o shr 8) and 255; val bch = o and 255
                val nr: Int; val ng: Int; val nb: Int
                when (state.background) {
                    "Blur" -> { val bl = blurRow[x]; nr = lerp(r, (bl shr 16) and 255, bg); ng = lerp(g, (bl shr 8) and 255, bg); nb = lerp(bch, bl and 255, bg) }
                    "B&W" -> { val lum = (0.2126f * r + 0.7152f * g + 0.0722f * bch).toInt().coerceIn(0, 255); nr = lerp(r, lum, bg); ng = lerp(g, lum, bg); nb = lerp(bch, lum, bg) }
                    "Darken" -> { val f = 1f - 0.55f * bg; nr = (r * f).toInt().coerceIn(0, 255); ng = (g * f).toInt().coerceIn(0, 255); nb = (bch * f).toInt().coerceIn(0, 255) }
                    "Pop" -> { val lum = (0.2126f * r + 0.7152f * g + 0.0722f * bch) * (1f - 0.3f * bg); nr = lerp(r, lum.toInt().coerceIn(0, 255), bg * 0.7f); ng = lerp(g, lum.toInt().coerceIn(0, 255), bg * 0.7f); nb = lerp(bch, lum.toInt().coerceIn(0, 255), bg * 0.7f) }
                    else -> { nr = r; ng = g; nb = bch }
                }
                out[x] = (o and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
            target.setPixels(out, 0, w, 0, y, w, 1)
        }
        blurBitmap?.recycle()
        return target
    }

    /**
     * The base layer for local tone: linear luminance downscaled to ~1/48 of the frame and blurred, so shadows,
     * highlights and clarity respond to regions rather than single pixels (Lightroom's local tone mapping).
     */
    private fun luminanceBase(bitmap: Bitmap): LuminanceBase {
        val scale = (64f / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val w = max(2, (bitmap.width * scale).toInt()); val h = max(2, (bitmap.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        var values = FloatArray(w * h) { i ->
            val c = px[i]
            .2126f * ColorEngine.srgbToLinear((c shr 16 and 255) / 255f) + .7152f * ColorEngine.srgbToLinear((c shr 8 and 255) / 255f) + .0722f * ColorEngine.srgbToLinear((c and 255) / 255f)
        }
        repeat(2) { values = featherMask(values, w, h, 1) }
        return LuminanceBase(w, h, values)
    }

    // Per source bitmap; two sources are kept because the editor alternates draft and full-size renders.
    private val segmentationCaches = ArrayDeque<Pair<Any, HashMap<String, SubjectSegmenter.Mask?>>>()
    /** Segmentation is expensive, so the editor preview reuses each mask kind for the same source bitmap. */
    @Synchronized
    private fun cachedSegmentation(key: Any?, kind: String, compute: () -> SubjectSegmenter.Mask?): SubjectSegmenter.Mask? {
        if (key == null) return compute()
        val entry = segmentationCaches.firstOrNull { it.first === key }
            ?: (key to HashMap<String, SubjectSegmenter.Mask?>()).also { segmentationCaches.addFirst(it); while (segmentationCaches.size > 2) segmentationCaches.removeLast() }
        if (kind in entry.second) return entry.second[kind]
        return compute().also { entry.second[kind] = it }
    }

    private var healCache: Triple<Bitmap, List<HealingSpot>, Bitmap>? = null
    /**
     * Healing for the preview, cached per source + spots so LaMa only re-runs when a spot changes, not on every
     * slider move. Drafts (while dragging) use the classic fill to stay responsive.
     */
    private fun previewHealing(source: Bitmap, spots: List<HealingSpot>, draft: Boolean): Bitmap {
        if (spots.isEmpty()) return source.copy(Bitmap.Config.ARGB_8888, true)
        if (!draft) synchronized(this) {
            healCache?.let { (key, cachedSpots, healed) -> if (key === source && cachedSpots == spots && !healed.isRecycled) return healed.copy(Bitmap.Config.ARGB_8888, true) }
        }
        val healed = applyHealing(source.copy(Bitmap.Config.ARGB_8888, true), DevelopState(healingSpots = spots), aiFill = !draft)
        if (!draft) synchronized(this) {
            healCache?.third?.takeIf { it !== healed }?.recycle()
            healCache = Triple(source, spots, healed.copy(Bitmap.Config.ARGB_8888, false))
        }
        return healed
    }

    /**
     * Renders an edit onto [source] (left untouched) in the photo's own frame, for the editor preview. Crop,
     * rotation and the post-crop vignette are drawn by the editor itself; everything else runs the export code.
     */
    suspend fun renderPreview(source: Bitmap, state: DevelopState, draft: Boolean = false, thumbnail: Boolean = false, maskOverlayId: String? = null): Bitmap = withContext(Dispatchers.Default) {
        val check = { ensureActive() }
        // Thumbnails (preset strip) bypass the shared caches, so they never evict the main preview's segmentation/heal.
        val healed = if (thumbnail) applyHealing(source.copy(Bitmap.Config.ARGB_8888, true), state) else previewHealing(source, state.healingSpots, draft)
        check()
        val key: Any? = if (thumbnail) null else source
        // Drafts (while dragging) skip sharpening/noise reduction, which only read at full size anyway.
        val adjusted = adjustPixels(healed, state, frameEffects = thumbnail, segmentationKey = key, checkCancelled = check, detail = !draft)
        check()
        val result = applyLensBlur(applyBackground(adjusted, state, segmentationKey = key), state)
        if (maskOverlayId != null) maskOverlay(result, state, maskOverlayId, key) else result
    }

    /**
     * Paints a translucent red over everything the mask [maskId] covers (its weight > 0), so the editor can show
     * exactly what a mask/filter affects — like Lightroom's mask overlay. Handles geometric, brush and AI masks.
     */
    internal fun maskOverlay(frame: Bitmap, state: DevelopState, maskId: String, segmentationKey: Any? = null): Bitmap {
        val mask = state.masks.firstOrNull { it.id == maskId && it.enabled } ?: return frame
        val w = frame.width; val h = frame.height
        val shortSide = minOf(w, h).toFloat()
        val cosA = cos(Math.toRadians(mask.angle.toDouble())).toFloat(); val sinA = sin(Math.toRadians(mask.angle.toDouble())).toFloat()
        val brush = if (mask.type == "Brush") renderBrushMask(mask, w, h) else null
        val ai = if (mask.type in aiMaskTypes) cachedSegmentation(segmentationKey, mask.type) { segment(mask.type, frame) }
            ?.let { SubjectSegmenter.Mask(it.width, it.height, featherMask(it.confidence, it.width, it.height, (minOf(it.width, it.height) * 0.012f).toInt().coerceIn(1, 24))) } else null
        val target = mutable(frame)
        val row = IntArray(w)
        for (y in 0 until h) {
            target.getPixels(row, 0, w, 0, y, w, 1)
            val py = y.toFloat() / max(1, h - 1)
            for (x in 0 until w) {
                val px = x.toFloat() / max(1, w - 1)
                var weight = when (mask.type) {
                    "Linear" -> {
                        val projected = ((px - mask.centerX) * w * cosA + (py - mask.centerY) * h * sinA) / shortSide
                        (projected / (mask.size * mask.feather).coerceAtLeast(.02f) + .5f).coerceIn(0f, 1f)
                    }
                    "Radial" -> {
                        val dx = (px - mask.centerX) * w / shortSide; val dy = (py - mask.centerY) * h / shortSide
                        val rx = dx * cosA + dy * sinA; val ry = (-dx * sinA + dy * cosA) / mask.aspect.coerceIn(.2f, 5f)
                        val distance = sqrt(rx * rx + ry * ry)
                        val outer = mask.size.coerceAtLeast(.05f); val inner = outer * (1f - mask.feather.coerceIn(.02f, 1f))
                        when { distance <= inner -> 1f; distance >= outer -> 0f; else -> 1f - (distance - inner) / (outer - inner).coerceAtLeast(.001f) }
                    }
                    "Brush" -> brush?.let { b ->
                        val bx = (px * (b.width - 1)).toInt().coerceIn(0, b.width - 1); val by = (py * (b.height - 1)).toInt().coerceIn(0, b.height - 1)
                        ((b.alpha[by * b.width + bx] ushr 24) and 255) / 255f
                    } ?: 0f
                    else -> sampleMaskAlpha(ai, px, py)
                }
                if (mask.invert) weight = 1f - weight
                if (weight <= .01f) continue
                val a = (weight * .5f).coerceIn(0f, .5f)
                val o = row[x]
                val nr = ((o shr 16 and 255) + (230 - (o shr 16 and 255)) * a).toInt().coerceIn(0, 255)
                val ng = ((o shr 8 and 255) + (45 - (o shr 8 and 255)) * a).toInt().coerceIn(0, 255)
                val nb = ((o and 255) + (70 - (o and 255)) * a).toInt().coerceIn(0, 255)
                row[x] = (o and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
            target.setPixels(row, 0, w, 0, y, w, 1)
        }
        return target
    }

    /**
     * The whole edit (crop, rotation, colour, local tools, effects) on a small [source], left untouched: library
     * thumbnails. Generative Remove uses the classic fill here so the grid stays fast.
     */
    suspend fun renderEdited(source: Bitmap, state: DevelopState): Bitmap = withContext(Dispatchers.Default) {
        val (framed, frame) = transform(source.copy(Bitmap.Config.ARGB_8888, true), state)
        val local = frame.remap(state)
        val healed = applyHealing(framed, local)
        applyLensBlur(applyBackground(adjustPixels(healed, local, detail = false), local), local)
    }

    /**
     * Applies the global and local adjustments ([ColorEngine]), consuming [source] (edited in place when mutable),
     * then the detail pass (sharpening, noise reduction, texture). [frameEffects] adds the post-crop vignette,
     * lens shading and grain, which belong to the final frame.
     */
    internal fun adjustPixels(
        source: Bitmap, state: DevelopState, out16: java.nio.ShortBuffer? = null,
        frameEffects: Boolean = true, segmentationKey: Any? = null, checkCancelled: (() -> Unit)? = null,
        detail: Boolean = true, sourceMasks: SourceMasks? = null,
    ): Bitmap {
        val bitmap = if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true).also { source.recycle() }
        val width = bitmap.width
        val height = bitmap.height
        // Rasters for brush and AI masks, computed once on the pre-adjustment pixels; geometric masks are analytic.
        val masks = state.masks
        val brushMaps = HashMap<String, BrushMaskMap>()
        val aiMasks = HashMap<String, SubjectSegmenter.Mask?>()
        masks.filter { it.enabled }.forEach { mask ->
            when {
                mask.type == "Brush" && mask.brushStrokes.isNotEmpty() -> brushMaps[mask.id] = renderBrushMask(mask, width, height)
                mask.type in aiMaskTypes && sourceMasks == null && mask.type !in aiMasks ->
                    aiMasks[mask.type] = cachedSegmentation(segmentationKey, mask.type) { segment(mask.type, bitmap) }
            }
        }
        lateinit var engine: ColorEngine
        val sampler = MaskSampler { index, px, py ->
            val mask = engine.localMasks[index]
            if (mask.type == "Brush") brushMaps[mask.id]?.let { brush ->
                val bx = (px * (brush.width - 1)).toInt().coerceIn(0, brush.width - 1)
                val by = (py * (brush.height - 1)).toInt().coerceIn(0, brush.height - 1)
                ((brush.alpha[by * brush.width + bx] ushr 24) and 255) / 255f * mask.flow
            } ?: 0f
            else sourceMasks?.alpha(mask.type, px, py) ?: sampleMaskAlpha(aiMasks[mask.type], px, py)
        }
        engine = ColorEngine(state, width, height, frameEffects, sampler, luminanceBase(bitmap))
        val tileHeight = 64
        val pixels = IntArray(width * min(tileHeight, height))
        for (top in 0 until height step tileHeight) {
            checkCancelled?.invoke()
            val rows = min(tileHeight, height - top)
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
            engine.process(pixels, top, rows, out16)
            bitmap.setPixels(pixels, 0, width, 0, top, width, rows)
        }
        // Sharpening amount is modulated by Detail (fine vs. coarse) and Radius (halo width),
        // mirroring Lightroom's Detail panel. Texture adds fine local contrast (Clarity is a base-layer effect in ColorEngine).
        val sharpenBase = (state.sharpen / 100f) * (0.4f + state.sharpenDetail / 100f) * (0.7f + state.sharpenRadius * 0.3f)
        val detailAmount = (sharpenBase + state.texture * .35f / 100f).coerceIn(-.6f, 1.8f)
        val smoothing = (state.noiseReduction / 100f * .65f).coerceIn(0f, .65f)
        val maskThreshold = state.sharpenMasking / 100f * .18f
        val nrEdgePreserve = state.nrDetail / 100f
        val nrLocalContrast = state.nrContrast / 100f * .4f
        val colorSmoothing = (state.colorNoise / 100f * .9f).coerceIn(0f, .9f)
        if (detail && (abs(detailAmount) > .01f || smoothing > .01f || colorSmoothing > .01f) && width > 2 && height > 2) {
            // Colour noise: blend each pixel's chroma toward a blurred chroma while keeping its own luminance.
            val chromaSource = if (colorSmoothing > .01f) blurredBitmap(bitmap, max(2, min(width, height) / 160)) else null
            val chromaRow = IntArray(width)
            val detailed = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            var above = IntArray(width); var center = IntArray(width); var below = IntArray(width)
            val output = IntArray(width)
            bitmap.getPixels(above, 0, width, 0, 0, width, 1)
            bitmap.getPixels(center, 0, width, 0, 1, width, 1)
            fun channel(color: Int, shift: Int) = (color shr shift) and 255
            for (y in 1 until height - 1) {
                bitmap.getPixels(below, 0, width, 0, y + 1, width, 1)
                chromaSource?.getPixels(chromaRow, 0, width, 0, y, width, 1)
                output[0] = center[0]; output[width - 1] = center[width - 1]
                for (x in 1 until width - 1) {
                    val color = center[x]
                    fun refined(shift: Int): Int {
                        val c = channel(color, shift).toFloat()
                        val average = (channel(center[x - 1], shift) + channel(center[x + 1], shift) + channel(above[x], shift) + channel(below[x], shift)) / 4f
                        val edge = abs(c - average) / 255f
                        val maskFactor = if (maskThreshold <= 0f) 1f else ((edge - maskThreshold) / .12f).coerceIn(0f, 1f)
                        val localSmoothing = smoothing * (1f - nrEdgePreserve * (edge * 6f).coerceIn(0f, 1f))
                        var softened = c + (average - c) * localSmoothing
                        if (nrLocalContrast > 0f && localSmoothing > 0f) softened = average + (softened - average) * (1f + nrLocalContrast)
                        return (softened + (softened - average) * detailAmount * maskFactor).toInt().coerceIn(0, 255)
                    }
                    var r = refined(16); var g = refined(8); var b = refined(0)
                    if (chromaSource != null) {
                        val luma = .299f * r + .587f * g + .114f * b
                        val c = chromaRow[x]
                        val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
                        val cl = .299f * cr + .587f * cg + .114f * cb
                        r = (luma + (r - luma) * (1f - colorSmoothing) + (cr - cl) * colorSmoothing).toInt().coerceIn(0, 255)
                        g = (luma + (g - luma) * (1f - colorSmoothing) + (cg - cl) * colorSmoothing).toInt().coerceIn(0, 255)
                        b = (luma + (b - luma) * (1f - colorSmoothing) + (cb - cl) * colorSmoothing).toInt().coerceIn(0, 255)
                    }
                    output[x] = (color and -0x1000000) or (r shl 16) or (g shl 8) or b
                }
                detailed.setPixels(output, 0, width, 0, y, width, 1)
                val oldAbove = above; above = center; center = below; below = oldAbove
            }
            chromaSource?.recycle()
            bitmap.recycle()
            return detailed
        }
        return bitmap
    }

    /** Heal/clone spots and Generative Remove; [aiFill] uses LaMa when installed (classic fill otherwise). */
    internal fun applyHealing(source: Bitmap, state: DevelopState, aiFill: Boolean = false): Bitmap {
        if (state.healingSpots.isEmpty()) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = source.width; val height = source.height
        val sourceRow = IntArray(width); val targetRow = IntArray(width)
        val shortSide = min(width, height).toFloat()
        state.healingSpots.forEach { spot ->
            if (spot.contentAware) {
                // Generative Remove: the painted shape (or legacy circle) is regenerated by LaMa, else push-pull filled.
                val region = RemovalRegion.of(spot, width, height) ?: return@forEach
                if (!(aiFill && Inpainter.inpaint(output, region, spot.opacity))) PushPullFill.fill(output, region, spot.opacity)
                return@forEach
            }
            val targetCenterX = (spot.targetX * (width - 1)).toInt()
            val targetCenterY = (spot.targetY * (height - 1)).toInt()
            val sourceCenterX = (spot.sourceX * (width - 1)).toInt()
            val sourceCenterY = (spot.sourceY * (height - 1)).toInt()
            val radius = (spot.radius * shortSide).coerceAtLeast(2f)
            val inner = radius * (1f - spot.feather.coerceIn(.02f, 1f))
            val top = max(0, (targetCenterY - radius).toInt())
            val bottom = min(height - 1, (targetCenterY + radius).toInt())
            val left = max(0, (targetCenterX - radius).toInt())
            val right = min(width - 1, (targetCenterX + radius).toInt())
            for (y in top..bottom) {
                val sourceY = (sourceCenterY + y - targetCenterY).coerceIn(0, height - 1)
                source.getPixels(sourceRow, 0, width, 0, sourceY, width, 1)
                output.getPixels(targetRow, 0, width, 0, y, width, 1)
                for (x in left..right) {
                    val dx = (x - targetCenterX).toFloat(); val dy = (y - targetCenterY).toFloat()
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance >= radius) continue
                    val featherWeight = if (distance <= inner) 1f else 1f - (distance - inner) / (radius - inner).coerceAtLeast(1f)
                    val alpha = (spot.opacity * featherWeight).coerceIn(0f, 1f)
                    if (alpha <= .001f) continue
                    val sourceX = (sourceCenterX + x - targetCenterX).coerceIn(0, width - 1)
                    val from = sourceRow[sourceX]; val to = targetRow[x]
                    fun blend(shift: Int): Int {
                        val sourceChannel = (from shr shift) and 255
                        val targetChannel = (to shr shift) and 255
                        return (targetChannel + (sourceChannel - targetChannel) * alpha).toInt().coerceIn(0, 255)
                    }
                    targetRow[x] = (to and -0x1000000) or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
                }
                output.setPixels(targetRow, 0, width, 0, y, width, 1)
            }
        }
        source.recycle()
        return output
    }

    private fun renderBrushMask(mask: LocalMask, sourceWidth: Int, sourceHeight: Int): BrushMaskMap {
        val scale = min(1f, 512f / max(sourceWidth, sourceHeight).coerceAtLeast(1))
        val width = max(1, (sourceWidth * scale).toInt())
        val height = max(1, (sourceHeight * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Each stroke is its own sub-path, so separate dabs are never joined by a line.
        val path = Path()
        val strokes = mask.brushStrokes
        strokes.forEach { stroke ->
            stroke.forEachIndexed { index, point ->
                val x = point.x * width; val y = point.y * height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        val radius = (mask.size * min(width, height)).coerceAtLeast(1f)
        fun paint(alpha: Int, widthScale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = radius * 2f * widthScale
        }
        val layers = listOf(
            paint(72, 1f),
            paint(150, (1f - mask.feather * .55f).coerceAtLeast(.25f)),
            paint(255, (1f - mask.feather).coerceAtLeast(.08f)),
        )
        layers.forEach { layer ->
            canvas.drawPath(path, layer)
            // Single-point strokes (taps) have no path length; draw them as dots.
            strokes.filter { it.size == 1 }.forEach { stroke ->
                canvas.drawCircle(stroke[0].x * width, stroke[0].y * height, layer.strokeWidth / 2f, Paint(layer).apply { style = Paint.Style.FILL })
            }
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return BrushMaskMap(width, height, pixels)
    }

    private fun hueColor(hue: Float): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(h % 2f - 1f)
        val sector = floor(h).toInt()
        return when (sector) {
            0 -> floatArrayOf(1f, x, 0f)
            1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f)
            4 -> floatArrayOf(x, 0f, 1f)
            else -> floatArrayOf(1f, 0f, x)
        }
    }
}
