package com.chouchang.cctvdl.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 从封面提取 4 个主色，作为环境光背景（按色相分桶，按饱和度加权） */
object CoverPalette {
    suspend fun extract(ctx: Context, url: String, dark: Boolean): List<Color>? = withContext(Dispatchers.Default) {
        if (url.isEmpty()) return@withContext null
        val req = ImageRequest.Builder(ctx).data(url).size(64, 36).allowHardware(false).build()
        val res = SingletonImageLoader.get(ctx).execute(req) as? SuccessResult ?: return@withContext null
        fromBitmap(res.image.toBitmap(), dark)
    }

    fun fromBitmap(bmp: Bitmap, dark: Boolean): List<Color>? {
        val w = bmp.width; val h = bmp.height
        if (w == 0 || h == 0) return null
        val bins = 12
        val weight = FloatArray(bins); val sumS = FloatArray(bins); val sumL = FloatArray(bins); val sumH = FloatArray(bins)
        val hsl = FloatArray(3)
        val step = maxOf(1, (w * h) / 1500)
        var i = 0
        while (i < w * h) {
            val p = bmp.getPixel(i % w, i / w)
            androidx.core.graphics.ColorUtils.colorToHSL(p, hsl)
            val (hue, s, l) = Triple(hsl[0], hsl[1], hsl[2])
            if (l in 0.08f..0.94f) {
                val b = ((hue / 360f) * bins).toInt().coerceIn(0, bins - 1)
                val wt = s * s + 0.02f
                weight[b] += wt; sumS[b] += s * wt; sumL[b] += l * wt; sumH[b] += hue * wt
            }
            i += step
        }
        val order = (0 until bins).sortedByDescending { weight[it] }.filter { weight[it] > 0f }
        if (order.isEmpty()) return null
        val picked = mutableListOf<Int>()
        for (b in order) {
            if (picked.none { abs(it - b) <= 1 || abs(it - b) >= bins - 1 }) picked += b
            if (picked.size == 3) break
        }
        while (picked.size < 3) picked += order[picked.size % order.size]
        val colors = picked.map { b ->
            val hue = sumH[b] / weight[b]
            val s = (sumS[b] / weight[b]).coerceIn(0.35f, 0.9f)
            val l = if (dark) 0.42f else 0.68f
            Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, s, l)))
        }
        return listOf(colors[0], colors[1], colors[2], colors[0].copy(alpha = 0.9f))
    }
}
