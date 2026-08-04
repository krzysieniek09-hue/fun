package com.krzys.hardwareinfo

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * A minimal fintech-style line chart: just the line, a soft gradient
 * under it and a dot on the latest value. No axes, no gridlines, no
 * labels. Feed it samples with [addSample]; it keeps a rolling window
 * and rescales itself. Hand-drawn with Canvas, no libraries.
 */
class LineChartView(context: Context) : View(context) {

    var capacity = 120                       // rolling window, ~3 min at 1.5 s
    var lineColor = 0xFF0F0F14.toInt()
        set(value) {
            field = value
            linePaint.color = value
            dotPaint.color = value
            rebuildShader()
        }

    private val samples = ArrayDeque<Float>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = lineColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = lineColor
    }

    private val linePath = Path()
    private val fillPath = Path()

    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    fun addSample(value: Float) {
        if (!value.isFinite()) return
        samples.addLast(value)
        while (samples.size > capacity) samples.removeFirst()
        invalidate()
    }

    /** History carry-over so a UI rebuild (theme switch) keeps the graph. */
    fun exportSamples(): List<Float> = samples.toList()

    fun importSamples(history: List<Float>) {
        samples.clear()
        for (s in history) samples.addLast(s)
        while (samples.size > capacity) samples.removeFirst()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    private fun rebuildShader() {
        if (height <= 0) return
        val top = (lineColor and 0x00FFFFFF) or 0x14000000  // ~8% alpha
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            top, 0x00000000, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dpF(3f)

        if (samples.size < 2) {
            // Not enough data yet: a quiet flat line.
            canvas.drawLine(pad, h / 2f, w - pad, h / 2f, linePaint)
            return
        }

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (s in samples) {
            if (s < min) min = s
            if (s > max) max = s
        }
        var range = max - min
        if (range < 1e-6f) {
            // Flat series: pad the range so the line sits mid-chart.
            val padRange = if (max == 0f) 1f else max * 0.05f
            min -= padRange
            range = padRange * 2f
        } else {
            // Breathing room so peaks don't touch the edges.
            min -= range * 0.12f
            range *= 1.24f
        }

        linePath.rewind()
        fillPath.rewind()
        val n = samples.size
        var lastX = 0f
        var lastY = 0f
        samples.forEachIndexed { i, s ->
            val x = pad + (w - 2 * pad) * i / (n - 1)
            val y = h - pad - (h - 2 * pad) * ((s - min) / range)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            lastX = x
            lastY = y
        }

        fillPath.addPath(linePath)
        fillPath.lineTo(lastX, h)
        fillPath.lineTo(pad, h)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        canvas.drawCircle(lastX, lastY, dpF(2.6f), dotPaint)
    }
}
