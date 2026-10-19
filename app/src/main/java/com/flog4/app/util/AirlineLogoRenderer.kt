package com.flog4.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG

object AirlineLogoRenderer {

    const val PDF_SIZE_PX = 192
    const val UI_SIZE_PX = 144
    private const val DEFAULT_CODE = "default"
    private val cache = LruCache<String, Bitmap>(32)

    fun render(context: Context, airlineCode: String, sizePx: Int): Bitmap? {
        val size = sizePx.coerceIn(16, 1024)
        val code = sanitizeCode(airlineCode)
        val key = "$code@$size"
        cache.get(key)?.let { return it }

        val svg = loadSvg(context, code) ?: loadSvg(context, DEFAULT_CODE) ?: return null
        val bitmap = renderToBitmap(svg, size) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun sanitizeCode(airlineCode: String): String {
        val code = airlineCode.trim().take(2).lowercase()
        return if (code.length == 2 && code.all { it in 'a'..'z' || it in '0'..'9' }) code else ""
    }

    private fun loadSvg(context: Context, code: String): SVG? {
        if (code.isEmpty()) return null
        return try {
            context.assets.open("logos/$code.svg").use { input -> SVG.getFromInputStream(input) }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderToBitmap(svg: SVG, sizePx: Int): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            svg.renderToCanvas(canvas, RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
