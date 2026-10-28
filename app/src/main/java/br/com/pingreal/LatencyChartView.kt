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
 * Gráfico de latência em tempo real, com uma ou mais séries.
 *
 * Cada curva é uma interpolação cúbica monotônica (Fritsch–Carlson), amostrada pixel a pixel, então
 * nunca "estoura" abaixo de zero nem faz picos falsos. O eixo X é o tempo real: o gráfico rola
 * continuamente a cada quadro, e a ponta direita fica [DELAY_MS] atrás do relógio, só o
 * bastante para a resposta (RTT) já ter chegado quando o ponto é desenhado.
 *
 * Com uma série só, a curva ganha preenchimento e o ponto atual muda de cor conforme a latência.
 * Com várias, cada série usa a sua cor.
 */
class LatencyChartView(context: Context) : View(context) {

    private class Sample(val t: Long, val v: Float) {
        val lost: Boolean get() = v < 0f
    }

    private class Series(val color: Int) {
        val samples = ArrayList<Sample>()
        var tangents = FloatArray(64)
    }

    companion object {
        // Atraso entre o relógio e a ponta direita. Precisa cobrir só o RTT da resposta;
        // valores maiores deixam o gráfico visivelmente atrás do console.
        private const val DELAY_MS = 600L
        private const val HEAD_GRACE_MS = 2500L
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

    private var series: List<Series> = listOf(Series(GOOD))
    private var live = false
    private var displayT = System.currentTimeMillis() - DELAY_MS
    private var curMax = 50f
    private var lastFrame = 0L
    private var runHeadX = 0f

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
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f) }
    private val lossDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Define as séries (uma cor por alvo) e limpa os dados. */
    fun setSeries(colors: IntArray) {
        series = if (colors.isEmpty()) listOf(Series(GOOD)) else colors.map { Series(it) }
        clear()
    }

    fun clear() {
        series.forEach { it.samples.clear() }
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

    fun addSample(index: Int, timeMs: Long, valueMs: Float) =
        insert(index, Sample(timeMs, max(0f, valueMs)))

    fun addLoss(index: Int, timeMs: Long) = insert(index, Sample(timeMs, -1f))

    private fun insert(index: Int, sample: Sample) {
        val list = series.getOrNull(index)?.samples ?: return
        var i = list.size
        while (i > 0 && list[i - 1].t > sample.t) i--
        list.add(i, sample)

        val cutoff = System.currentTimeMillis() - WINDOW_MS * 2
        while (list.size > 2 && list[0].t < cutoff) list.removeAt(0)

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
        val single = series.size == 1

        val lastSampleT = series.maxOfOrNull { it.samples.lastOrNull()?.t ?: Long.MIN_VALUE }
            ?.takeIf { it != Long.MIN_VALUE }

        // Tempo exibido na ponta direita do gráfico.
        val targetT = if (live) now - DELAY_MS else (lastSampleT ?: displayT)
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

        // Escala vertical suave (considera todas as séries).
        var visibleMax = 0f
        for (s in series) {
            for (sample in s.samples) {
                if (!sample.lost && sample.t >= winStart - 3000 && sample.t <= displayT + 3000) {
                    visibleMax = max(visibleMax, sample.v)
                }
            }
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

        if (series.all { it.samples.isEmpty() }) {
            canvas.drawText(placeholder, w / 2f, (top + bottom) / 2f, emptyPaint)
            if (animating || live) postInvalidateOnAnimation()
            return
        }

        val heads = FloatArray(series.size) { Float.NaN }
        val headXs = FloatArray(series.size) { xRight }

        for ((index, s) in series.withIndex()) {
            val list = s.samples

            // Perdas
            val lossColor = if (single) BAD else s.color
            lossPaint.color = Color.argb(70, Color.red(lossColor), Color.green(lossColor), Color.blue(lossColor))
            lossDotPaint.color = lossColor
            for (sample in list) {
                if (!sample.lost || sample.t < winStart || sample.t > displayT) continue
                val x = xOf(sample.t)
                canvas.drawLine(x, top, x, bottom, lossPaint)
                canvas.drawCircle(x, bottom, dp(3f), lossDotPaint)
            }

            // Trechos contínuos (separados por perdas)
            var i = 0
            val n = list.size
            while (i < n) {
                if (list[i].lost) { i++; continue }
                var j = i
                while (j + 1 < n && !list[j + 1].lost) j++
                val head = drawRun(canvas, s, i, j, winStart, xRight, bottom, single, ::xOf, ::yOf)
                if (!head.isNaN()) {
                    heads[index] = head
                    headXs[index] = runHeadX
                }
                i = j + 1
            }
        }

        // Ponto na ponta (valor atual de cada série)
        for ((index, headValue) in heads.withIndex()) {
            if (headValue.isNaN()) continue
            val x = headXs[index]
            val y = yOf(headValue)
            val color = if (single) colorFor(headValue.toDouble()) else series[index].color
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
        s: Series,
        from: Int,
        to: Int,
        winStart: Long,
        xRight: Float,
        bottom: Float,
        single: Boolean,
        xOf: (Long) -> Float,
        yOf: (Float) -> Float
    ): Float {
        val list = s.samples
        val t0 = list[from].t
        val t1 = list[to].t
        if (t1 < winStart || t0 > displayT) return Float.NaN

        if (from == to) {
            dotPaint.color = if (single) colorFor(list[from].v.toDouble()) else s.color
            canvas.drawCircle(xOf(t0), yOf(list[from].v), dp(3f), dotPaint)
            return headOf(list[from].v, t0, xOf)
        }

        computeTangents(s, from, to)

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
            while (k < to - 1 && list[k + 1].t <= t) k++
            v = hermite(s, k, t)
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

        if (single) canvas.drawPath(fillPath, fillPaint)

        glowPaint.color = Color.argb(38, Color.red(s.color), Color.green(s.color), Color.blue(s.color))
        glowPaint.strokeWidth = if (single) dp(7f) else dp(5f)
        linePaint.color = s.color
        linePaint.strokeWidth = if (single) dp(2.4f) else dp(2f)
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, linePaint)

        return if (t0 <= displayT && t1 >= displayT) {
            runHeadX = xRight
            v
        } else {
            headOf(list[to].v, t1, xOf)
        }
    }

    /**
     * Ponto "atual" quando a última amostra ainda não alcançou a ponta direita (próxima resposta a caminho):
     * o ponto fica na própria amostra, em vez de sumir.
     */
    private fun headOf(value: Float, t: Long, xOf: (Long) -> Float): Float {
        if (t > displayT || displayT - t > HEAD_GRACE_MS) return Float.NaN
        runHeadX = xOf(t)
        return value
    }

    /** Tangentes de Fritsch–Carlson: garantem curva monotônica entre pontos (sem overshoot). */
    private fun computeTangents(s: Series, from: Int, to: Int) {
        val list = s.samples
        if (s.tangents.size < list.size) s.tangents = FloatArray(list.size + 64)
        val m = s.tangents
        val d = FloatArray(to - from)
        for (i in from until to) {
            val h = max(1f, (list[i + 1].t - list[i].t).toFloat())
            d[i - from] = (list[i + 1].v - list[i].v) / h
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
                val sq = a * a + b * b
                if (sq > 9f) {
                    val tau = 3f / sqrt(sq)
                    m[i] = tau * a * di
                    m[i + 1] = tau * b * di
                }
            }
        }
    }

    private fun hermite(s: Series, k: Int, t: Long): Float {
        val a = s.samples[k]
        val b = s.samples[k + 1]
        val h = max(1f, (b.t - a.t).toFloat())
        val u = ((t - a.t) / h).coerceIn(0f, 1f)
        val u2 = u * u
        val u3 = u2 * u
        return (2 * u3 - 3 * u2 + 1) * a.v +
            (u3 - 2 * u2 + u) * h * s.tangents[k] +
            (-2 * u3 + 3 * u2) * b.v +
            (u3 - u2) * h * s.tangents[k + 1]
    }

    private fun niceCeil(v: Float): Float {
        for (step in SCALE_STEPS) if (v <= step) return step
        return (v / 1000f).toInt() * 1000f + 1000f
    }
}
