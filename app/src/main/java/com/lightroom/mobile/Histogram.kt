package com.lightroom.mobile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.ln

private data class HistogramData(val red: IntArray, val green: IntArray, val blue: IntArray, val luminance: IntArray, val peak: Int)

@Composable
fun HistogramOverlay(photo: PhotoItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val data by produceState<HistogramData?>(null, photo.uri) {
        val bitmap = BitmapRepository.load(context, Uri.parse(photo.uri), 384)
        value = withContext(Dispatchers.Default) { bitmap?.histogram() }
    }
    Box(modifier.size(142.dp, 72.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .58f))) {
        Canvas(Modifier.matchParentSize()) {
            val histogram = data ?: return@Canvas
            fun pathFor(values: IntArray): Path {
                val path = Path().apply { moveTo(0f, size.height) }
                values.forEachIndexed { index, count ->
                    val x = index / 255f * size.width
                    val normalized = ln(count.toFloat() + 1f) / ln(histogram.peak.toFloat() + 1f)
                    val y = size.height - normalized * size.height * .92f
                    path.lineTo(x, y)
                }
                return path.apply { lineTo(size.width, size.height) }
            }
            drawPath(pathFor(histogram.luminance), Color.White.copy(alpha = .38f))
            drawPath(pathFor(histogram.red), Color(0xFFFF5C5C).copy(alpha = .72f), style = Stroke(1.1.dp.toPx()))
            drawPath(pathFor(histogram.green), Color(0xFF62E58A).copy(alpha = .72f), style = Stroke(1.1.dp.toPx()))
            drawPath(pathFor(histogram.blue), Color(0xFF5BA7FF).copy(alpha = .78f), style = Stroke(1.1.dp.toPx()))
        }
    }
}

private fun Bitmap.histogram(): HistogramData {
    val red = IntArray(256); val green = IntArray(256); val blue = IntArray(256); val luminance = IntArray(256)
    val stride = max(1, max(width, height) / 256)
    val row = IntArray(width)
    for (y in 0 until height step stride) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width step stride) {
            val color = row[x]
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            red[r]++; green[g]++; blue[b]++; luminance[(r * 54 + g * 183 + b * 19) shr 8]++
        }
    }
    return HistogramData(red, green, blue, luminance, max(1, maxOf(red.max(), green.max(), blue.max(), luminance.max())))
}
