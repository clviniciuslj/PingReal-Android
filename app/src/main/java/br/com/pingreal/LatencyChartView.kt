package br.com.pingreal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Gráfico de latência em tempo real.
 *
 * A curva é uma interpolação cúbica monotônica (Fritsch–Carlson), amostrada pixel a pixel, então
 * nunca "estoura" abaixo de zero nem faz picos falsos. O eixo X é o tempo real: o gráfico rola
 * continuamente a cada quadro, e a ponta direita fica [DELAY_MS] atrás do relógio para que a
 * curva já esteja fechada quando aparece na tela.
 */
class LatencyChartView(context: Context) : View(context) {

    private class Sample(val t: Long, val v: Float) {
        val lost: Boolean get() = v < 0f
    }

    companion object {
        private const val DELAY_MS = 2200L
        private const val WINDOW_MS = 60_000L

        private val GOOD = Color.rgb(74, 222, 128)
        private val WARN = Color.rgb(251, 191, 36)
        private val BAD = Color.rgb(248, 113, 113)

        private val SCALE_STEPS = floatArrayOf(
            20f, 30f, 50f, 80f, 100f, 150f, 200f, 300f, 500f, 800f, 1000f, 2000f, 5000f
        )

        fun colorFor(ms: Double): Int = when {
            ms < 60 -> GOOD
            ms < 150 -> WARN
            else -> BAD
        }
    }

    var placeholder: String = "Inicie um ping para ver a latência"

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val samples = ArrayList<Sample>()
    private var live = false
    private var displayT = System.currentTimeMillis() - DELAY_MS
    private var curMax = 50f
    private var lastFrame = 0L
    private var tangents = FloatArray(64)

    private val path = Path()
    private val fillPath = Path()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(22, 255, 255, 255)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 215, 205)
        textSize = dp(9f)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 200, 215, 205)
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GOOD
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 74, 222, 128)
        style = Paint.Style.STROKE
        strokeWidth = dp(7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 248, 113, 113)
        strokeWidth = dp(2f)
    }
    private val lossDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAD }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun clear() {
        samples.clear()
        curMax = 50f
        lastFrame = 0L
        displayT = System.currentTimeMillis() - DELAY_MS
        postInvalidateOnAnimation()
    }

    fun setLive(value: Boolean) {
        if (value && !live) displayT = System.currentTimeMillis() - DELAY_MS
        live = value
        postInvalidateOnAnimation()
    }

    fun addSample(timeMs: Long, valueMs: Float) = insert(Sample(timeMs, max(0f, valueMs)))

    fun addLoss(timeMs: Long) = insert(Sample(timeMs, -1f))

    private fun insert(sample: Sample) {
        var i = samples.size
        while (i > 0 && samples[i - 1].t > sample.t) i--
        samples.add(i, sample)

        val cutoff = System.currentTimeMillis() - WINDOW_MS * 2
        while (samples.size > 2 && samples[0].t < cutoff) samples.removeAt(0)

        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            0f, dp(10f), 0f, h - dp(12f),
            Color.argb(105, 74, 222, 128), Color.argb(0, 74, 222, 128),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        var animating = false

        // Tempo exibido na ponta direita do gráfico.
        val targetT = if (live) now - DELAY_MS else (samples.lastOrNull()?.t ?: displayT)
        if (live) {
            displayT = targetT
        } else {
            val diff = targetT - displayT
            if (abs(diff) > 2L) {
                val step = (diff * min(1.0, dt / 140.0)).roundToLong()
                displayT += if (step == 0L) (if (diff > 0) 1L else -1L) else step
                animating = true
            } else {
                displayT = targetT
            }
        }

        val top = dp(10f)
        val bottom = h - dp(12f)
        val xRight = w - dp(8f)
        val winStart = displayT - WINDOW_MS

        // Escala vertical suave.
        var visibleMax = 0f
        for (s in samples) {
            if (!s.lost && s.t >= winStart - 3000 && s.t <= displayT + 3000) visibleMax = max(visibleMax, s.v)
        }
        val targetMax = niceCeil(max(visibleMax * 1.2f, 20f))
        val maxDiff = targetMax - curMax
        if (abs(maxDiff) > 0.4f) {
            curMax += maxDiff * (1f - exp(-dt / 160f))
            animating = true
        } else {
            curMax = targetMax
        }

        fun xOf(t: Long): Float = xRight - (displayT - t).toFloat() / WINDOW_MS * xRight
        fun yOf(v: Float): Float = bottom - (v / curMax).coerceIn(0f, 1.04f) * (bottom - top)

        // Grade
        for (i in 1..3) {
            val frac = i / 3f
            val y = bottom - frac * (bottom - top)
            canvas.drawLine(0f, y, w, y, gridPaint)
            val label = (curMax * frac).roundToInt().toString() + if (i == 3) " ms" else ""
            canvas.drawText(label, dp(4f), y - dp(3f), labelPaint)
        }
        canvas.drawLine(0f, bottom, w, bottom, gridPaint)

        if (samples.isEmpty()) {
            canvas.drawText(placeholder, w / 2f, (top + bottom) / 2f, emptyPaint)
            if (animating || live) postInvalidateOnAnimation()
            return
        }

        // Perdas
        for (s in samples) {
            if (!s.lost || s.t < winStart || s.t > displayT) continue
            val x = xOf(s.t)
            canvas.drawLine(x, top, x, bottom, lossPaint)
            canvas.drawCircle(x, bottom, dp(3f), lossDotPaint)
        }

        // Trechos contínuos (separados por perdas)
        var headValue = Float.NaN
        var i = 0
        val n = samples.size
        while (i < n) {
            if (samples[i].lost) { i++; continue }
            var j = i
            while (j + 1 < n && !samples[j + 1].lost) j++
            val head = drawRun(canvas, i, j, winStart, xRight, bottom, ::xOf, ::yOf)
            if (!head.isNaN()) headValue = head
            i = j + 1
        }

        // Ponto na ponta (valor atual)
        if (!headValue.isNaN()) {
            val x = xRight
            val y = yOf(headValue)
            val color = colorFor(headValue.toDouble())
            if (live) {
                val phase = (now % 1400L) / 1400f
                haloPaint.color = Color.argb(((1f - phase) * 90).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawCircle(x, y, dp(5f) + dp(7f) * phase, haloPaint)
            }
            dotPaint.color = color
            canvas.drawCircle(x, y, dp(4f), dotPaint)
            dotPaint.color = Color.argb(255, 8, 12, 10)
            canvas.drawCircle(x, y, dp(1.8f), dotPaint)
        }

        if (live || animating) postInvalidateOnAnimation()
    }

    /** Desenha um trecho [from..to] de amostras válidas. Devolve o valor na ponta direita, se coberta. */
    private fun drawRun(
        canvas: Canvas,
        from: Int,
        to: Int,
        winStart: Long,
        xRight: Float,
        bottom: Float,
        xOf: (Long) -> Float,
        yOf: (Float) -> Float
    ): Float {
        val t0 = samples[from].t
        val t1 = samples[to].t
        if (t1 < winStart || t0 > displayT) return Float.NaN

        if (from == to) {
            dotPaint.color = colorFor(samples[from].v.toDouble())
            canvas.drawCircle(xOf(t0), yOf(samples[from].v), dp(3f), dotPaint)
            return if (t0 == displayT) samples[from].v else Float.NaN
        }

        computeTangents(from, to)

        val startT = max(t0, winStart)
        val endT = min(t1, displayT)
        val stepMs = max(1L, (WINDOW_MS * dp(3f) / xRight).toLong())

        path.reset()
        fillPath.reset()

        var k = from
        var t = startT
        var first = true
        var x = 0f
        var v = 0f
        while (true) {
            while (k < to - 1 && samples[k + 1].t <= t) k++
            v = hermite(k, t)
            x = xOf(t)
            val y = yOf(v)
            if (first) {
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (t >= endT) break
            t = min(t + stepMs, endT)
        }
        fillPath.lineTo(x, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, linePaint)

        return if (t0 <= displayT && t1 >= displayT) v else Float.NaN
    }

    /** Tangentes de Fritsch–Carlson: garantem curva monotônica entre pontos (sem overshoot). */
    private fun computeTangents(from: Int, to: Int) {
        if (tangents.size < samples.size) tangents = FloatArray(samples.size + 64)
        val m = tangents
        val d = FloatArray(to - from)
        for (i in from until to) {
            val h = max(1f, (samples[i + 1].t - samples[i].t).toFloat())
            d[i - from] = (samples[i + 1].v - samples[i].v) / h
        }
        m[from] = d[0]
        m[to] = d[to - from - 1]
        for (i in from + 1 until to) {
            val a = d[i - 1 - from]
            val b = d[i - from]
            m[i] = if (a * b <= 0f) 0f else (a + b) / 2f
        }
        for (i in from until to) {
            val di = d[i - from]
            if (di == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / di
                val b = m[i + 1] / di
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / sqrt(s)
                    m[i] = tau * a * di
                    m[i + 1] = tau * b * di
                }
            }
        }
    }

    private fun hermite(k: Int, t: Long): Float {
        val a = samples[k]
        val b = samples[k + 1]
        val h = max(1f, (b.t - a.t).toFloat())
        val s = ((t - a.t) / h).coerceIn(0f, 1f)
        val s2 = s * s
        val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * a.v +
            (s3 - 2 * s2 + s) * h * tangents[k] +
            (-2 * s3 + 3 * s2) * b.v +
            (s3 - s2) * h * tangents[k + 1]
    }

    private fun niceCeil(v: Float): Float {
        for (step in SCALE_STEPS) if (v <= step) return step
        return (v / 1000f).toInt() * 1000f + 1000f
    }
}
