package barilyuk.mobilosignal

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * The signal strength gauge: a row of coloured segments, a marker under the current reading and
 * the dBm value below it.
 *
 * This replaces a fixed PNG gradient plus two absolutely positioned views. Drawing it means the
 * colours come from the theme (the PNG carried its own grey backdrop that never matched the app
 * background), the geometry is density independent, the marker can animate, and the scale can
 * follow the network technology instead of being hardcoded to one dBm window.
 */
class SignalBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val SEGMENT_COUNT = 20
        /** Opacity of the segments above the current reading. */
        const val INACTIVE_ALPHA = 60
        const val ANIMATION_MS = 350L
        const val NO_READING = "-- dBm"
    }

    private val segmentHeight = dp(20f)
    private val segmentGap = dp(2f)
    private val segmentRadius = dp(2f)
    private val markerWidth = dp(10f)
    private val markerHeight = dp(7f)
    private val verticalGap = dp(4f)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val colorPoor = ContextCompat.getColor(context, R.color.quality_poor)
    private val colorFair = ContextCompat.getColor(context, R.color.quality_fair)
    private val colorGood = ContextCompat.getColor(context, R.color.quality_good)

    private val segmentRect = RectF()
    private val markerPath = Path()

    private var dbm: Int? = null
    private var scale: SignalScale = NetworkGeneration.UNKNOWN.scale
    private var hasReading = false

    /** 0f at the left edge of the bar, 1f at the right. Animated towards the current reading. */
    private var position = 0f
    private var animator: ValueAnimator? = null

    init {
        labelPaint.color = resolveTextColor()
    }

    fun setReading(dbm: Int?, generation: NetworkGeneration) {
        val newScale = generation.scale
        if (hasReading && this.dbm == dbm && scale == newScale) return
        hasReading = true
        this.dbm = dbm
        scale = newScale

        contentDescription = if (dbm == null) {
            context.getString(R.string.no_signal_reading)
        } else {
            context.getString(R.string.signal_reading, dbm, generation.label)
        }

        animateTo(positionOf(dbm))
    }

    private fun positionOf(dbm: Int?): Float {
        if (dbm == null) return 0f
        val span = (scale.max - scale.min).toFloat()
        if (span <= 0f) return 0f
        return ((dbm - scale.min) / span).coerceIn(0f, 1f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (!isAttachedToWindow) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = labelPaint.fontMetrics
        val labelHeight = metrics.descent - metrics.ascent
        val desiredHeight = paddingTop + segmentHeight + verticalGap + markerHeight +
            verticalGap + labelHeight + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight.toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val barLeft = paddingLeft.toFloat()
        val barWidth = (width - paddingLeft - paddingRight).toFloat()
        if (barWidth <= 0f) return

        drawSegments(canvas, barLeft, barWidth)

        // Keep the whole triangle inside the view: at either extreme an uncentred marker would be
        // half clipped by the edge.
        val halfMarker = markerWidth / 2f
        val markerCentre = (barLeft + position * barWidth)
            .coerceIn(barLeft + halfMarker, barLeft + barWidth - halfMarker)
        val markerTop = paddingTop + segmentHeight + verticalGap
        if (dbm != null) drawMarker(canvas, markerCentre, markerTop)

        drawLabel(canvas, barLeft, barWidth, markerCentre, markerTop + markerHeight + verticalGap)
    }

    private fun drawSegments(canvas: Canvas, barLeft: Float, barWidth: Float) {
        val segmentWidth = (barWidth - segmentGap * (SEGMENT_COUNT - 1)) / SEGMENT_COUNT
        if (segmentWidth <= 0f) return
        val top = paddingTop.toFloat()

        for (index in 0 until SEGMENT_COUNT) {
            // Colour each segment by the signal quality it represents, so the colours mean
            // something on their own rather than just being a decorative ramp.
            val centre = (index + 0.5f) / SEGMENT_COUNT
            val colour = colourAt(centre)
            val filled = dbm != null && position >= centre

            barPaint.color = colour
            barPaint.alpha = if (filled) 255 else INACTIVE_ALPHA

            val left = barLeft + index * (segmentWidth + segmentGap)
            segmentRect.set(left, top, left + segmentWidth, top + segmentHeight)
            canvas.drawRoundRect(segmentRect, segmentRadius, segmentRadius, barPaint)
        }
    }

    private fun drawMarker(canvas: Canvas, centre: Float, top: Float) {
        markerPaint.color = colourAt(position)
        markerPath.reset()
        markerPath.moveTo(centre, top)
        markerPath.lineTo(centre - markerWidth / 2f, top + markerHeight)
        markerPath.lineTo(centre + markerWidth / 2f, top + markerHeight)
        markerPath.close()
        canvas.drawPath(markerPath, markerPaint)
    }

    private fun drawLabel(
        canvas: Canvas,
        barLeft: Float,
        barWidth: Float,
        markerCentre: Float,
        top: Float,
    ) {
        val text = dbm?.let { "$it dBm" } ?: NO_READING
        val textWidth = labelPaint.measureText(text)
        val ideal = markerCentre - textWidth / 2f
        val maxLeft = (barLeft + barWidth - textWidth).coerceAtLeast(barLeft)
        val left = ideal.coerceIn(barLeft, maxLeft)
        canvas.drawText(text, left, top - labelPaint.fontMetrics.ascent, labelPaint)
    }

    /** @param fraction 0f..1f across the bar. */
    private fun colourAt(fraction: Float): Int {
        val value = scale.min + fraction * (scale.max - scale.min)
        return when {
            value <= scale.poorMax -> colorPoor
            value <= scale.fairMax -> colorFair
            else -> colorGood
        }
    }

    private fun resolveTextColor(): Int {
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            attributes.getColor(0, Color.GRAY)
        } finally {
            attributes.recycle()
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
