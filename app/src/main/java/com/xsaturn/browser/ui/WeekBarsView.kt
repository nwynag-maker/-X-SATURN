package com.xsaturn.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.xsaturn.browser.R

/** أعمدة بسيطة لعدد ما حُجب في آخر 7 أيام. البيانات حقيقية من SecurityStats. */
class WeekBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Pair<String, Long>> = emptyList()
    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xs_red)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xs_outline)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xs_muted)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.xs_white)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }

    fun setData(days: List<Pair<String, Long>>) {
        data = days
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = data.size
        if (n == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 18f * density
        val valueH = 16f * density
        val chartTop = valueH
        val chartBottom = h - labelH
        val chartH = chartBottom - chartTop
        if (chartH <= 0f) return

        val maxV = (data.maxOf { it.second }).coerceAtLeast(1L).toFloat()
        val slot = w / n
        val barW = slot * 0.5f
        val radius = 4f * density

        for (i in 0 until n) {
            val (date, value) = data[i]
            val cx = slot * i + slot / 2f
            val barH = if (value <= 0L) 3f * density else (chartH * (value / maxV)).coerceAtLeast(3f * density)
            val rect = RectF(cx - barW / 2f, chartBottom - barH, cx + barW / 2f, chartBottom)
            canvas.drawRoundRect(rect, radius, radius, if (value > 0L) barPaint else emptyPaint)
            if (value > 0L) canvas.drawText(shortNumber(value), cx, rect.top - 4f * density, valuePaint)
            canvas.drawText(date.takeLast(2), cx, h - 4f * density, labelPaint)
        }
    }

    private fun shortNumber(v: Long): String = when {
        v >= 1_000_000L -> (v / 1_000_000L).toString() + "M"
        v >= 10_000L -> (v / 1_000L).toString() + "k"
        else -> v.toString()
    }
}
