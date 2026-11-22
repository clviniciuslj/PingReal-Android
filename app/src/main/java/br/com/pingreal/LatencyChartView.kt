package br.com.pingreal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Gráfico de latência em tempo real, com uma ou mais séries.
 *
 * Cada série é uma linha em degraus: o valor de uma resposta fica reto até a próxima chegar, e o valor
 * novo aparece como um salto vertical. O eixo X é o tempo real e a ponta direita é "agora": entre uma
 * resposta e outra a linha é estendida até lá, então ela anda continuamente. A escala (à direita, fora do
 * gráfico) usa passos redondos e troca de uma vez quando o maior valor visível muda de faixa.
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
    }

    companion object {
        const val IDLE_TEXT = "Inicie um ping para ver a latência"

        private const val HEAD_GRACE_MS = 2500L
        private const val WINDOW_MS = 35_000L

        // A linha anda poucos pixels por segundo: 30 quadros/s ficam iguais a 60/120 e gastam menos bateria.
        private const val FRAME_MS = 33L

        private val GOOD get() = Palette.green
        private val WARN get() = Palette.amber
        private val BAD get() = Palette.red

        private val SCALE_STEPS = floatArrayOf(
            20f, 30f, 50f, 80f, 100f, 150f, 200f, 300f, 500f, 800f, 1000f, 2000f, 5000f
        )

        /** Quantas faixas cada escala tem, para os números da grade saírem redondos (50: 10, 20…; 100: 25, 50…). */
        private val SCALE_DIVISIONS = intArrayOf(4, 3, 5, 4, 4, 3, 4, 3, 5, 4, 4, 4, 5)

        fun colorFor(ms: Double): Int = when {
            ms < 60 -> GOOD
            ms < 150 -> WARN
            else -> BAD
        }
    }

    var placeholder: String = IDLE_TEXT
        set(value) {
            field = value
            invalidate()
        }

    /** Modo fino: só a linha que oscila, sem grade nem números (o gráfico da tela do Terminal). */
    var compact = false
        set(value) {
            field = value
            updateShader(height)
            invalidate()
        }

    /** Altura que o gráfico completo teria (para a imagem de relatório, que sempre usa o gráfico cheio). */
    val reportHeight: Int get() = if (compact) (dp(84f)).toInt() else height

    private var overrideW = 0
    private var overrideH = 0

    /** Desenha a versão completa (com grade) num tamanho dado, mesmo estando em modo fino. */
    fun drawFull(canvas: Canvas, w: Int, h: Int) {
        val wasCompact = compact
        compact = false
        overrideW = w
        overrideH = h
        updateShader(h)
        onDraw(canvas)
        overrideW = 0
        overrideH = 0
        compact = wasCompact
        updateShader(height)
    }

    /** Toque rápido no gráfico (expande ou recolhe). */
    var onTap: (() -> Unit)? = null

    private var scrubX = -1f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // Expandido: o dedo em cima do gráfico mostra o valor daquele instante. Toque rápido: recolhe/expande.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                if (!compact) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    scrubX = e.x
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) moved = true
                if (!compact) {
                    scrubX = e.x
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tap = !moved
                scrubX = -1f
                invalidate()
                if (tap) onTap?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubX = -1f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var series: List<Series> = listOf(Series(GOOD))
    private var live = false
    private var displayT = System.currentTimeMillis()
    private var curMax = 50f
    private var lastFrame = 0L
    private var runHeadX = 0f

    private val textBounds = android.graphics.Rect()
    private val path = Path()
    private val fillPath = Path()
    private val frameTick = Runnable { invalidate() }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.overlay(22)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = dp(10f)
        typeface = Fonts.number
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        typeface = Fonts.medium
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2f) }
    private val lossDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Define as séries (uma cor por alvo) e limpa os dados. */
    fun setSeries(colors: IntArray) {
        series = if (colors.isEmpty()) listOf(Series(GOOD)) else colors.map { Series(it) }
        clear()
    }

    fun clear() {
        series.forEach { it.samples.clear() }
        curMax = 50f
        lastFrame = 0L
        displayT = System.currentTimeMillis()
        invalidate()
    }

    fun setLive(value: Boolean) {
        if (value && !live) displayT = System.currentTimeMillis()
        live = value
        invalidate()
    }

    fun addSample(index: Int, timeMs: Long, valueMs: Float) =
        insert(index, Sample(timeMs, max(0f, valueMs)))

    fun addLoss(index: Int, timeMs: Long) = insert(index, Sample(timeMs, -1f))

    private fun insert(index: Int, sample: Sample) {
        val s = series.getOrNull(index) ?: return
        val list = s.samples
        var i = list.size
        while (i > 0 && list[i - 1].t > sample.t) i--
        list.add(i, sample)

        val cutoff = System.currentTimeMillis() - WINDOW_MS * 2
        while (list.size > 2 && list[0].t < cutoff) list.removeAt(0)

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateShader(h)

    private fun updateShader(h: Int) {
        val pad = if (compact) dp(3f) else dp(12f)
        val topPad = if (compact) dp(3f) else dp(10f)
        fillPaint.shader = LinearGradient(
            0f, topPad, 0f, h - pad,
            Color.argb(80, Color.red(Palette.accent), Color.green(Palette.accent), Color.blue(Palette.accent)), Color.argb(0, Color.red(Palette.accent), Color.green(Palette.accent), Color.blue(Palette.accent)),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    /** Agenda o próximo quadro. Um só pendente por vez, mesmo que várias coisas peçam redesenho. */
    private fun scheduleFrame() {
        removeCallbacks(frameTick)
        postDelayed(frameTick, FRAME_MS)
    }

    override fun onDraw(canvas: Canvas) {
        val w = (if (overrideW > 0) overrideW else width).toFloat()
        val h = (if (overrideH > 0) overrideH else height).toFloat()
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) FRAME_MS else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        var animating = false
        val single = series.size == 1

        val lastSampleT = series.maxOfOrNull { it.samples.lastOrNull()?.t ?: Long.MIN_VALUE }
            ?.takeIf { it != Long.MIN_VALUE }

        // Tempo exibido na ponta direita do gráfico.
        if (live) {
            displayT = now
        } else {
            val targetT = lastSampleT ?: displayT
            val diff = targetT - displayT
            if (abs(diff) > 2L) {
                val step = (diff * min(1.0, dt / 140.0)).roundToLong()
                displayT += if (step == 0L) (if (diff > 0) 1L else -1L) else step
                animating = true
            } else {
                displayT = targetT
            }
        }

        val top = if (compact) dp(3f) else dp(10f)
        val bottom = h - if (compact) dp(3f) else dp(12f)
        // À direita fica a faixa dos números da escala, fora da área do gráfico (no modo fino não há faixa).
        val xRight = w - if (compact) dp(1f) else dp(36f)
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
        // Escala mínima de 50 ms; só sobe quando o maior valor passa da faixa, sem folga extra.
        curMax = niceCeil(max(visibleMax, 50f))

        fun xOf(t: Long): Float = xRight - (displayT - t).toFloat() / WINDOW_MS * xRight
        fun yOf(v: Float): Float = bottom - (v / curMax).coerceIn(0f, 1.04f) * (bottom - top)

        // Grade: linhas horizontais nos números redondos (valores à direita) e duas verticais fixas nos terços.
        val divisions = if (compact) 0 else divisionsFor(curMax)
        val labelOffset = (labelPaint.descent() + labelPaint.ascent()) / 2f
        for (i in 1..divisions) {
            val frac = i / divisions.toFloat()
            val y = bottom - frac * (bottom - top)
            canvas.drawLine(0f, y, xRight, y, gridPaint)
            canvas.drawText((curMax * frac).roundToInt().toString(), xRight + dp(6f), y - labelOffset, labelPaint)
        }
        canvas.drawLine(0f, bottom, xRight, bottom, gridPaint)
        if (!compact) {
            canvas.drawLine(xRight / 3f, top, xRight / 3f, bottom, gridPaint)
            canvas.drawLine(xRight * 2f / 3f, top, xRight * 2f / 3f, bottom, gridPaint)
        }

        if (series.all { it.samples.isEmpty() }) {
            if (compact) return
            // Centro de verdade: pelo retângulo real do texto, para ele ficar entre duas linhas da grade.
            textBounds.setEmpty()
            emptyPaint.getTextBounds(placeholder, 0, placeholder.length, textBounds)
            canvas.drawText(placeholder, xRight / 2f, (top + bottom) / 2f - textBounds.exactCenterY(), emptyPaint)
            if (animating || live) scheduleFrame()
            return
        }

        val heads = FloatArray(series.size) { Float.NaN }
        val headXs = FloatArray(series.size) { xRight }

        for ((index, s) in series.withIndex()) {
            val list = s.samples

            // Estende a curva até "agora" com um ponto virtual: a linha continua andando entre respostas.
            var virtual: Sample? = null
            val tail = list.lastOrNull()
            if (live && tail != null && !tail.lost && displayT > tail.t && displayT - tail.t <= HEAD_GRACE_MS) {
                virtual = Sample(displayT, tail.v)
                list.add(virtual)
            }

            // Perdas
            val lossColor = if (single) BAD else s.color
            lossPaint.color = Color.argb(50, Color.red(lossColor), Color.green(lossColor), Color.blue(lossColor))
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

            if (virtual != null) list.removeAt(list.size - 1)
        }

        if (scrubX >= 0f && !compact && overrideW == 0) drawScrub(canvas, top, bottom, xRight)

        if (live || animating) scheduleFrame()
    }

    private val scrubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.overlay(70)
        strokeWidth = dp(1f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.panelHigh }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        typeface = Fonts.number
    }

    /** Linha vertical sob o dedo e o valor de cada série naquele instante (a amostra mais próxima, até 2,5 s). */
    private fun drawScrub(canvas: Canvas, top: Float, bottom: Float, xRight: Float) {
        val x = scrubX.coerceIn(0f, xRight)
        val t = displayT - ((xRight - x) / xRight * WINDOW_MS).toLong()
        canvas.drawLine(x, top, x, bottom, scrubPaint)
        val parts = ArrayList<Pair<String, Int>>()
        val agoS = (displayT - t) / 1000f
        parts.add((if (agoS < 0.5f) "agora" else String.format(java.util.Locale("pt", "BR"), "há %.0f s", agoS)) to Palette.muted)
        for (s in series) {
            var best: Sample? = null
            for (sm in s.samples) if (best == null || abs(sm.t - t) < abs(best.t - t)) best = sm
            if (best == null || abs(best.t - t) > 2500L) continue
            val color = if (series.size == 1) (if (best.lost) BAD else colorFor(best.v.toDouble())) else s.color
            if (best.lost) {
                parts.add("perdido" to color)
            } else {
                val txt = if (best.v < 10f) String.format(java.util.Locale("pt", "BR"), "%.1f ms", best.v) else "${best.v.roundToInt()} ms"
                parts.add(txt to color)
                val y = bottom - (best.v / curMax).coerceIn(0f, 1.04f) * (bottom - top)
                dotPaint.color = color
                canvas.drawCircle(x, y, dp(4f), dotPaint)
            }
        }
        val gap = dp(10f)
        val pad = dp(8f)
        var total = pad * 2 + gap * (parts.size - 1)
        for (p in parts) total += bubbleText.measureText(p.first)
        val left = (x - total / 2f).coerceIn(0f, max(0f, width - total))
        val bTop = top - dp(2f)
        val bBottom = bTop + dp(24f)
        canvas.drawRoundRect(left, bTop, left + total, bBottom, dp(12f), dp(12f), bubblePaint)
        var cx = left + pad
        val baseline = (bTop + bBottom) / 2f - (bubbleText.descent() + bubbleText.ascent()) / 2f
        for (p in parts) {
            bubbleText.color = p.second
            canvas.drawText(p.first, cx, baseline, bubbleText)
            cx += bubbleText.measureText(p.first) + gap
        }
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

        // Degraus: cada valor fica reto até a próxima amostra, e então salta na vertical.
        path.reset()
        fillPath.reset()
        var started = false
        var x = 0f
        var y = 0f
        for (k in from..to) {
            val sample = list[k]
            if (sample.t < winStart && k < to && list[k + 1].t <= winStart) continue
            val nextX = xOf(max(sample.t, winStart))
            if (!started) {
                x = nextX
                y = yOf(sample.v)
                path.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
                started = true
            } else {
                // Horizontal até o instante da amostra nova, depois o salto.
                path.lineTo(nextX, y)
                fillPath.lineTo(nextX, y)
                y = yOf(sample.v)
                path.lineTo(nextX, y)
                fillPath.lineTo(nextX, y)
                x = nextX
            }
        }
        fillPath.lineTo(x, bottom)
        fillPath.close()

        if (single) canvas.drawPath(fillPath, fillPaint)

        linePaint.color = s.color
        linePaint.strokeWidth = if (single) dp(1.5f) else dp(1.5f)
        linePaint.strokeJoin = Paint.Join.MITER
        linePaint.strokeCap = Paint.Cap.BUTT
        canvas.drawPath(path, linePaint)

        return if (t0 <= displayT && t1 >= displayT) {
            runHeadX = xRight
            list[to].v
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

    private fun niceCeil(v: Float): Float {
        for (step in SCALE_STEPS) if (v <= step) return step
        return (v / 1000f).toInt() * 1000f + 1000f
    }

    private fun divisionsFor(scale: Float): Int {
        val i = SCALE_STEPS.indexOfFirst { it == scale }
        return if (i >= 0) SCALE_DIVISIONS[i] else 4
    }
}
