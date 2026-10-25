package com.lightroom.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * On-device checks that every editing feature does what it should, on real Android bitmaps.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FeatureTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        LiteRtModels.init(context); Inpainter.refresh()
    }

    // ---- helpers ----
    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun gray(v: Int) = rgb(v, v, v)
    private fun luma(c: Int) = ((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8
    private fun checker(w: Int, h: Int, cell: Int = 2) = Bitmap.createBitmap(IntArray(w * h) { i -> if (((i % w) / cell + (i / w) / cell) % 2 == 0) gray(40) else gray(215) }, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    /** Standard deviation of luminance in a rectangle. */
    private fun spread(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val values = (y0 until y1).flatMap { y -> (x0 until x1).map { x -> luma(b.getPixel(x, y)).toDouble() } }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
    private fun meanLuma(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int) =
        (y0 until y1).flatMap { y -> (x0 until x1).map { x -> luma(b.getPixel(x, y)) } }.average()
    private fun pngFile(bitmap: Bitmap, name: String): Uri {
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    // ---- geometry ----
    @Test fun cropRotateAndStraightenProduceTheRightFrame() {
        val (cropped, _) = ImageExporter.transform(solid(800, 600, gray(120)), DevelopState(cropAspect = "Free", cropLeft = 0f, cropTop = 0f, cropRight = .5f, cropBottom = .5f))
        assertEquals(400, cropped.width); assertEquals(300, cropped.height)
        val (turned, _) = ImageExporter.transform(solid(800, 600, gray(120)), DevelopState(rotation = 90))
        assertEquals(600, turned.width); assertEquals(800, turned.height)
        val (straight, _) = ImageExporter.transform(solid(800, 600, gray(120)), DevelopState(straighten = 12f))
        assertEquals(800, straight.width)
        // Auto-zoom after straightening: no empty (black/transparent) corners.
        listOf(0 to 0, 799 to 0, 0 to 599, 799 to 599).forEach { (x, y) -> assertTrue(luma(straight.getPixel(x, y)) > 100) }
    }

    @Test fun masksFollowTheCropOnExport() {
        val state = DevelopState(cropAspect = "Free", cropRight = .5f, cropBottom = .5f, masks = listOf(LocalMask("m", type = "Radial", centerX = .25f, centerY = .25f, size = .1f, feather = .02f, exposure = 2f)))
        val (framed, frame) = ImageExporter.transform(solid(800, 800, gray(110)), state)
        val out = ImageExporter.adjustPixels(framed, frame.remap(state), detail = false)
        assertTrue(luma(out.getPixel(200, 200)) > 200) // centre of the cropped frame
        assertTrue(luma(out.getPixel(40, 40)) < 130)
    }

    @Test fun lensDistortionAndGeometryRun() {
        val (warped, _) = ImageExporter.transform(checker(300, 200, 10), DevelopState(lensDistortion = 40f, geometryVertical = 30f))
        assertEquals(300, warped.width); assertEquals(200, warped.height)
    }

    // ---- local tools ----
    @Test fun healCloneCopiesTheSource() {
        val image = solid(400, 400, gray(60)).also { b -> for (y in 0 until 400) for (x in 0 until 100) b.setPixel(x, y, rgb(200, 40, 40)) }
        val healed = ImageExporter.applyHealing(image, DevelopState(healingSpots = listOf(HealingSpot("h", targetX = .6f, targetY = .5f, sourceX = .1f, sourceY = .5f, radius = .05f, feather = .1f))))
        val c = healed.getPixel(240, 200)
        assertTrue((c shr 16 and 255) > 150 && (c shr 8 and 255) < 90)
    }

    @Test fun generativeRemoveFillsAPaintedShape() {
        val image = solid(400, 300, gray(150)).also { b -> for (y in 120 until 180) for (x in 150 until 250) b.setPixel(x, y, gray(10)) }
        val stroke = (0..10).map { MaskPoint((150 + it * 10) / 399f, 150 / 299f) }
        val spot = HealingSpot("g", contentAware = true, brush = true, radius = 40f / 300f, feather = .3f, strokes = listOf(stroke))
        val out = ImageExporter.applyHealing(image, DevelopState(healingSpots = listOf(spot)))
        assertTrue(meanLuma(out, 160, 130, 240, 170) > 120)
        assertEquals(150, luma(out.getPixel(10, 10)))
    }

    @Test fun lamaGenerativeRemoveWhenInstalled() {
        assumeTrue("LaMa model not downloaded on this device", Inpainter.isReady)
        val image = solid(512, 512, rgb(90, 140, 210)).also { b -> for (y in 200 until 300) for (x in 200 until 300) b.setPixel(x, y, gray(5)) }
        val region = RemovalRegion.of(HealingSpot("g", contentAware = true, brush = true, radius = .12f, strokes = listOf(listOf(MaskPoint(.49f, .49f)))), 512, 512)!!
        assertTrue(Inpainter.inpaint(image, region, 1f))
        val c = image.getPixel(250, 250)
        assertTrue("filled with sky colour, got ${Integer.toHexString(c)}", (c and 255) > 120)
    }

    @Test fun brushAndLinearMasksAdjustOnlyTheirArea() {
        val brush = LocalMask("b", type = "Brush", size = .05f, exposure = 2f, points = listOf(MaskPoint(.2f, .5f), MaskPoint(.4f, .5f)))
        val linear = LocalMask("l", type = "Linear", centerX = .5f, centerY = .5f, angle = 90f, size = .2f, feather = 1f, exposure = -2f)
        val out = ImageExporter.adjustPixels(solid(400, 400, gray(120)), DevelopState(masks = listOf(brush, linear)), detail = false)
        assertTrue(luma(out.getPixel(120, 200)) > 180) // brush stroke, above the linear gradient's midpoint
        assertTrue(luma(out.getPixel(350, 390)) < 80) // bottom: linear mask darkens
        assertTrue(abs(luma(out.getPixel(350, 30)) - 120) < 6) // top: untouched
    }

    @Test fun lensBlurBlursOutsideTheFocus() {
        val out = ImageExporter.applyLensBlur(checker(400, 400), DevelopState(lensBlur = 80f, lensBlurFocusX = .5f, lensBlurFocusY = .5f, lensBlurFocusRadius = .15f))
        assertTrue(spread(out, 190, 190, 210, 210) > 60)
        assertTrue(spread(out, 5, 5, 40, 40) < 20)
    }

    // ---- detail, effects ----
    @Test fun sharpeningAddsContrastAndNoiseReductionRemovesNoise() {
        val edge = solid(200, 200, gray(80)).also { b -> for (y in 0 until 200) for (x in 100 until 200) b.setPixel(x, y, gray(170)) }
        val sharp = ImageExporter.adjustPixels(edge, DevelopState(sharpen = 100f, sharpenDetail = 60f), frameEffects = false)
        assertTrue(luma(sharp.getPixel(99, 100)) < 80 || luma(sharp.getPixel(100, 100)) > 170)
        val random = java.util.Random(4)
        val noisy = Bitmap.createBitmap(IntArray(200 * 200) { gray((128 + random.nextGaussian() * 25).toInt().coerceIn(0, 255)) }, 200, 200, Bitmap.Config.ARGB_8888)
        val before = spread(noisy, 20, 20, 180, 180)
        val smooth = ImageExporter.adjustPixels(noisy.copy(Bitmap.Config.ARGB_8888, true), DevelopState(sharpen = 0f, noiseReduction = 100f), frameEffects = false)
        assertTrue(spread(smooth, 20, 20, 180, 180) < before * .8)
    }

    @Test fun vignetteAndGrainAreFrameEffects() {
        val vignette = ImageExporter.adjustPixels(solid(400, 300, gray(150)), DevelopState(vignette = -80f, sharpen = 0f))
        assertTrue(luma(vignette.getPixel(2, 2)) < 110); assertTrue(abs(luma(vignette.getPixel(200, 150)) - 150) < 4)
        val grain = ImageExporter.adjustPixels(solid(300, 300, gray(128)), DevelopState(grain = 80f, sharpen = 0f))
        assertTrue(spread(grain, 0, 0, 300, 300) > 3)
        // The preview shows grain (position-independent) but never the post-crop vignette (drawn over the frame instead).
        val preview = ImageExporter.adjustPixels(solid(300, 300, gray(128)), DevelopState(grain = 80f, vignette = -80f, sharpen = 0f), frameEffects = false)
        assertTrue(spread(preview, 0, 0, 300, 300) > 3)
        assertTrue(abs(meanLuma(preview, 0, 0, 20, 20) - meanLuma(preview, 140, 140, 160, 160)) < 6)
    }

    @Test fun clarityRaisesLocalContrast() {
        val image = checker(300, 300, 30)
        val before = spread(image, 0, 0, 300, 300)
        val out = ImageExporter.adjustPixels(image.copy(Bitmap.Config.ARGB_8888, true), DevelopState(clarity = 100f, sharpen = 0f), frameEffects = false)
        assertTrue(spread(out, 0, 0, 300, 300) > before * 1.05)
    }

    @Test fun peopleSegmentationRuns() {
        val mask = SubjectSegmenter.mask(solid(300, 200, rgb(90, 140, 70)), SubjectSegmenter.Part.PERSON)
        assertNotNull(mask)
        assertEquals(256, mask!!.width)
        assertTrue(mask.confidence.average() < .2) // no person in a flat green frame
    }

    // ---- decoding, writing ----
    @Test fun exifOrientationIsApplied() {
        val file = File(context.cacheDir, "exif.jpg")
        file.outputStream().use { solid(300, 200, gray(100)).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val decoded = RawDecoder.decode(context, Uri.fromFile(file), 1000)!!
        assertEquals(200, decoded.width); assertEquals(300, decoded.height)
    }

    @Test fun decodeHonoursMaxSize() {
        val decoded = RawDecoder.decode(context, pngFile(solid(3000, 2000, gray(90)), "big.png"), 1200)!!
        assertEquals(1200, maxOf(decoded.width, decoded.height))
    }

    @Test fun sixteenBitWritersProduceValidFiles() {
        val pixels = java.nio.ByteBuffer.allocateDirect(8 * 4 * 3 * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val tiff = ByteArrayOutputStream().also { TiffPngWriter.writeTiff16(pixels, 8, 4, it) }.toByteArray()
        assertTrue((tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte()) || (tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte()))
        val png = ByteArrayOutputStream().also { TiffPngWriter.writePng16(pixels, 8, 4, it) }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(8, decoded.width); assertEquals(4, decoded.height)
    }

    @Test fun fullExportWritesAnImage() = runBlocking {
        val uri = pngFile(solid(640, 480, gray(100)), "export_src.png")
        val out = ImageExporter.export(context, PhotoItem(uri.toString(), "featuretest.png"), DevelopState(exposure = .5f, cropAspect = "1:1"), ExportOptions(quality = 90, maxSide = 2048, format = "JPEG"))
        assertTrue("export failed: $out", out is ImageExporter.ExportResult.Saved)
        val saved = (out as ImageExporter.ExportResult.Saved).uri
        val bitmap = context.contentResolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it) }
        assertEquals(bitmap.width, bitmap.height)
        assertTrue(luma(bitmap.getPixel(10, 10)) > 110)
        context.contentResolver.delete(saved, null, null)
        Unit
    }

    @Test fun legacyCropIsTurnedIntoTheRotatedFrame() {
        // Old recipe: 2x zoom on the top-left quarter of the original, then rotated 90° clockwise -> now the top-right quarter.
        val state = developStateFromJson(org.json.JSONObject("""{"cropZoom":2,"cropOffsetX":-1,"cropOffsetY":-1,"rotation":90,"cropAspect":"4:5"}"""))
        assertEquals(.5f, state.cropLeft, .001f); assertEquals(0f, state.cropTop, .001f)
        assertEquals(1f, state.cropRight, .001f); assertEquals(.5f, state.cropBottom, .001f)
        assertEquals("5:4", state.cropAspect)
    }

    @Test fun masksFollowLensDistortion() {
        val image = solid(800, 600, gray(60)).also { b -> for (y in 140..160) for (x in 190..210) b.setPixel(x, y, gray(250)) }
        val (out, frame) = ImageExporter.transform(image, DevelopState(lensDistortion = 60f))
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until out.height) for (x in 0 until out.width) if (luma(out.getPixel(x, y)) > 200) { sx += x; sy += y; n++ }
        val (px, py) = frame.point(200f / 800f, 150f / 600f)
        assertEquals(sx / n, (px * out.width).toDouble(), 4.0); assertEquals(sy / n, (py * out.height).toDouble(), 4.0)
    }

    @Test fun sixteenBitExportIncludesLensBlur() = runBlocking {
        val uri = pngFile(checker(400, 400), "export16_src.png")
        val out = ImageExporter.export(context, PhotoItem(uri.toString(), "featuretest16.png"), DevelopState(lensBlur = 80f, lensBlurFocusRadius = .1f, sharpen = 0f), ExportOptions(maxSide = 2048, format = "PNG16"))
        val saved = (out as ImageExporter.ExportResult.Saved).uri
        val bitmap = context.contentResolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it) }
        assertTrue(spread(bitmap, 5, 5, 40, 40) < 20) // blurred corner
        assertTrue(spread(bitmap, 190, 190, 210, 210) > 60) // sharp focus
        context.contentResolver.delete(saved, null, null)
        Unit
    }

    @Test fun separateBrushStrokesAreNotJoined() {
        val brush = LocalMask("b", type = "Brush", size = .03f, feather = .1f, exposure = 3f,
            strokes = listOf(listOf(MaskPoint(.1f, .1f), MaskPoint(.2f, .1f)), listOf(MaskPoint(.8f, .9f), MaskPoint(.9f, .9f))))
        val out = ImageExporter.adjustPixels(solid(400, 400, gray(80)), DevelopState(masks = listOf(brush)), detail = false)
        assertTrue(luma(out.getPixel(60, 40)) > 150); assertTrue(luma(out.getPixel(340, 360)) > 150)
        assertTrue(abs(luma(out.getPixel(200, 200)) - 80) < 4) // nothing between the two strokes
    }

    @Test fun flipMirrorsTheRotatedViewLeftToRight() {
        // Left half red, right half blue; rotate 90° clockwise -> red on top. Flip must swap left/right of that view,
        // so red stays on top (a flip before rotating would move red to the bottom).
        val image = solid(400, 200, rgb(0, 0, 220)).also { b -> for (y in 0 until 200) for (x in 0 until 200) b.setPixel(x, y, rgb(220, 0, 0)) }
        val (out, _) = ImageExporter.transform(image, DevelopState(rotation = 90, flipHorizontal = true))
        assertEquals(200, out.width); assertEquals(400, out.height)
        assertTrue((out.getPixel(100, 50) shr 16 and 255) > 150)
    }

    @Test fun colorNoiseReductionRemovesColourSpeckle() {
        val random = java.util.Random(7)
        val noisy = Bitmap.createBitmap(IntArray(200 * 200) {
            val n = (random.nextGaussian() * 30).toInt()
            rgb((128 + n).coerceIn(0, 255), (128 - n).coerceIn(0, 255), 128)
        }, 200, 200, Bitmap.Config.ARGB_8888)
        fun chromaSpread(b: Bitmap) = (20 until 180).flatMap { y -> (20 until 180).map { x -> val c = b.getPixel(x, y); ((c shr 16 and 255) - (c shr 8 and 255)).toDouble() } }
            .let { v -> val m = v.average(); sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }
        val before = chromaSpread(noisy)
        val out = ImageExporter.adjustPixels(noisy.copy(Bitmap.Config.ARGB_8888, true), DevelopState(colorNoise = 100f), frameEffects = false)
        assertTrue(chromaSpread(out) < before * .5)
    }

    @Test fun largeSixteenBitPngStreams() {
        val w = 4000; val h = 3000
        val pixels = java.nio.ByteBuffer.allocateDirect(w * h * 3 * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
        val counting = object : java.io.OutputStream() { var n = 0L; override fun write(b: Int) { n++ }; override fun write(b: ByteArray, o: Int, l: Int) { n += l } }
        TiffPngWriter.writePng16(pixels, w, h, counting)
        assertTrue(counting.n > 100)
    }

    @Test fun editedThumbnailsApplyTheCrop() = runBlocking {
        val thumb = ImageExporter.renderEdited(solid(300, 200, gray(100)), DevelopState(cropAspect = "1:1", exposure = 1f))
        assertEquals(thumb.width, thumb.height)
        assertTrue(luma(thumb.getPixel(10, 10)) > 120)
    }

    @Test fun presetsRoundTripAndRemoveCleanly() {
        val curve = List(65) { CurvePoint(it / 64f, (it / 64f) * (it / 64f)) }
        val saved = developStateFromJson(DevelopState(curves = mapOf("RGB" to curve)).toJson())
        assertEquals(65, saved.curves.getValue("RGB").size)
        val preset = Preset("p", "Curve look", "c", DevelopState(curves = mapOf("RGB" to curve), contrast = 20f), fields = setOf("curves", "contrast"))
        val applied = blendPreset(DevelopState(), preset.state, 1f, preset.fields).copy(presetBase = DevelopState(), presetFields = preset.fields)
        val removed = blendPreset(applied, applied.presetBase!!, 1f, applied.presetFields)
        assertTrue(removed.curves.isEmpty()); assertEquals(0f, removed.contrast, 0f)
    }

    // ---- catalog, presets ----
    @Test fun catalogKeepsEditsWhenPhotosAreResaved() {
        context.deleteDatabase("featuretest.db")
        val store = LibraryStore(context, "featuretest.db", "featuretest_prefs")
        val photo = PhotoItem("test://one", "one.jpg")
        store.savePhotos(listOf(photo))
        store.saveEdit(photo.uri, DevelopState(exposure = 1f, colorMix = mapOf("Blue" to ColorMix(luminance = -50f))))
        store.savePhotos(listOf(photo.copy(rating = 4)))
        assertEquals(1f, store.loadEdit(photo.uri).exposure, 0f)
        assertEquals(-50f, store.loadEdit(photo.uri).colorMix.getValue("Blue").luminance, 0f)
        assertEquals(setOf(photo.uri), store.editedUris())
        assertEquals(4, store.loadPhotos().single().rating)
        store.saveCustomPresets(listOf(Preset("c", "Mine", "Yours", DevelopState(temperature = 10f), false, setOf("temperature"), 5f to 2f)))
        val preset = store.loadCustomPresets().single()
        assertEquals(setOf("temperature"), preset.fields); assertEquals(5f to 2f, preset.rawWhiteBalance)
        context.deleteDatabase("featuretest.db")
    }

    @Test fun bundledPresetsLoadAndApply() {
        val presets = AssetPresets.load(context)
        assertTrue(presets.size > 400)
        val base = DevelopState(exposure = .4f)
        presets.take(60).forEach { preset ->
            val (look, fields) = preset.lookFor(isRaw = false)
            val applied = blendPreset(base, look, 1f, fields)
            if (fields?.contains("exposure") != true) assertEquals(.4f, applied.exposure, 0f)
            val out = ImageExporter.adjustPixels(solid(40, 30, rgb(150, 120, 100)), applied, detail = false)
            assertEquals(40, out.width)
        }
    }

    @Test fun previewRenderMatchesExportColours() = runBlocking {
        val state = DevelopState(exposure = .3f, contrast = 20f, colorMix = mapOf("Red" to ColorMix(hue = 60f)))
        val source = solid(200, 200, rgb(200, 50, 50))
        val preview = ImageExporter.renderPreview(source, state)
        val export = ImageExporter.adjustPixels(source.copy(Bitmap.Config.ARGB_8888, true), state, frameEffects = false)
        val a = preview.getPixel(100, 100); val b = export.getPixel(100, 100)
        assertTrue(abs((a shr 16 and 255) - (b shr 16 and 255)) <= 2 && abs((a shr 8 and 255) - (b shr 8 and 255)) <= 2)
    }
}
