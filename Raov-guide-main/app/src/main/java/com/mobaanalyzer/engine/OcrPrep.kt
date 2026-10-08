package com.mobaanalyzer.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.text.Text

/** Small helpers so OCR runs on the right pixels instead of the whole busy frame. */
object OcrPrep {
    /** Top-left: kill score + match clock. */
    val HUD = NormRect(0f, 0f, 0.25f, 0.10f)

    /** Pick screen, right column where enemy names are drawn in red. */
    val ENEMY_COL = NormRect(0.76f, 0.14f, 0.93f, 0.84f)

    fun crop(src: Bitmap, r: NormRect, scale: Int = 1): Bitmap {
        val x = (src.width * r.left).toInt().coerceIn(0, src.width - 1)
        val y = (src.height * r.top).toInt().coerceIn(0, src.height - 1)
        val w = (src.width * (r.right - r.left)).toInt().coerceIn(1, src.width - x)
        val h = (src.height * (r.bottom - r.top)).toInt().coerceIn(1, src.height - y)
        val c = Bitmap.createBitmap(src, x, y, w, h)
        return if (scale > 1) Bitmap.createScaledBitmap(c, w * scale, h * scale, true) else c
    }

    /** Red-on-purple text is low contrast in grayscale; the red channel (stretched) makes it stand out. */
    fun redChannel(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val m = ColorMatrix(
            floatArrayOf(
                1.6f, 0f, 0f, 0f, -60f,
                1.6f, 0f, 0f, 0f, -60f,
                1.6f, 0f, 0f, 0f, -60f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** ML Kit result -> OcrLine list, positions as fractions of the bitmap that was scanned. */
    fun linesOf(text: Text, w: Int, h: Int): List<OcrLine> =
        text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            l.boundingBox?.let { OcrLine(l.text, it.exactCenterX() / w, it.exactCenterY() / h) }
        }

    /** Lines scanned on a crop -> fractions of the full screen. */
    fun toScreen(lines: List<OcrLine>, r: NormRect): List<OcrLine> =
        lines.map { OcrLine(it.text, r.left + it.cx * (r.right - r.left), r.top + it.cy * (r.bottom - r.top)) }
}
