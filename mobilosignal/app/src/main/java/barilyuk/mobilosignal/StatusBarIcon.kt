package barilyuk.mobilosignal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders the dBm number that goes into the status bar as a bitmap icon.
 *
 * The result is cached: the notification used to allocate a fresh bitmap on every single modem
 * callback, which can be several per second.
 */
object StatusBarIcon {

    /**
     * The status bar draws the icon at 24 dp, which is 96 px at the densest bucket (xxxhdpi).
     * Anything larger is scaled down by the system anyway, but still has to cross Binder with
     * every notification update: the old 190 px bitmap was about 140 KB a time, this is 36 KB.
     */
    private const val SIZE = 96

    /** Same proportion to [SIZE] as the original 120 px text on a 190 px canvas. */
    private const val TEXT_SIZE = 60.6f

    private var cachedText: String? = null
    private var cachedColor: Int = 0
    private var cached: Bitmap? = null

    fun render(text: String, textColor: Int): Bitmap {
        cached?.let {
            if (text == cachedText && textColor == cachedColor) return it
        }
        val bitmap = draw(text, textColor)
        cachedText = text
        cachedColor = textColor
        cached = bitmap
        return bitmap
    }

    private fun draw(text: String, textColor: Int): Bitmap {
        val paint = Paint().apply {
            textSize = TEXT_SIZE
            color = textColor
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Black glyphs need an opaque backdrop, otherwise the status bar's own tinting eats them.
        canvas.drawColor(if (textColor == Color.BLACK) Color.WHITE else Color.TRANSPARENT)

        canvas.save()
        // Squeeze horizontally and stretch vertically so four characters fit the square.
        canvas.scale(0.6f, 1.8f, SIZE / 2f, SIZE / 2f)

        val x = (SIZE - paint.measureText(text)) / 2f
        val metrics = paint.fontMetrics
        val y = (SIZE - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(text, x, y, paint)

        canvas.restore()
        return bitmap
    }
}
