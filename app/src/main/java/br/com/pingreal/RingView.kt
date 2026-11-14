package br.com.pingreal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/**
 * Anel dividido em fatias (por exemplo: respondendo, instáveis, sem resposta), com um número grande no centro.
 * Usado no resumo do Condomínio, em cada quadra e no resumo do Diagnóstico.
 */
class RingView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var values = IntArray(0)
    private var colors = IntArray(0)

    /** Espessura do anel, em dp. */
    var strokeDp = 11f
        set(value) { field = value; invalidate() }

    var centerText = ""
        set(value) { field = value; invalidate() }

    /** Linha pequena embaixo do número (por exemplo "de 199"). Vazia = só o número, centralizado. */
    var subText = ""
        set(value) { field = value; invalidate() }

    /** Tamanho do número do centro, em sp. */
    var centerSp = 30f
        set(value) { field = value; invalidate() }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    /** Define as fatias, na ordem em que aparecem a partir do topo, no sentido horário. */
    fun set(values: IntArray, colors: IntArray) {
        this.values = values
        this.colors = colors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = strokeDp * density
        val size = min(width, height).toFloat()
        val r = size / 2f - stroke / 2f
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)

        arc.strokeWidth = stroke
        arc.color = Palette.overlay(26)
        canvas.drawCircle(cx, cy, r, arc)

        val total = values.sum()
        if (total > 0) {
            var start = -90f
            for (i in values.indices) {
                val sweep = 360f * values[i] / total
                if (values[i] > 0 && colors[i] != 0) {
                    arc.color = colors[i]
                    canvas.drawArc(box, start, sweep, false, arc)
                }
                start += sweep
            }
        }

        if (centerText.isNotEmpty()) {
            val scaled = resources.displayMetrics.scaledDensity
            centerPaint.color = Palette.white
            centerPaint.typeface = Fonts.numberBold
            centerPaint.textSize = centerSp * scaled
            subPaint.color = Palette.muted
            subPaint.typeface = Fonts.medium
            subPaint.textSize = centerSp * 0.4f * scaled
            val m = centerPaint.fontMetrics
            if (subText.isEmpty()) {
                canvas.drawText(centerText, cx, cy - (m.ascent + m.descent) / 2f, centerPaint)
            } else {
                val gap = centerSp * 0.42f * scaled
                canvas.drawText(centerText, cx, cy - (m.ascent + m.descent) / 2f - gap / 2f, centerPaint)
                canvas.drawText(subText, cx, cy + centerSp * 0.62f * scaled, subPaint)
            }
        }
    }
}

/**
 * Área das abas que reconhece o deslize para os lados. Avisa com +1 (próxima aba) ou -1 (anterior).
 * Não rouba o gesto de quem rola de lado por dentro (a fileira de atalhos, um campo de texto) nem o gesto
 * de voltar do Android, que começa nas bordas da tela.
 */
class SwipeFrame(context: Context, private val onSwipe: (Int) -> Unit) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var intercepted = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                val edge = EDGE_DP * density
                tracking = ev.x > edge && ev.x < width - edge
                intercepted = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2f) {
                    // dedo indo para a direita = o conteúdo rolaria para a esquerda (direção -1), e vice-versa
                    if (scrollableUnder(this, if (dx > 0) -1 else 1, downX, downY)) {
                        tracking = false
                    } else {
                        intercepted = true
                        return true
                    }
                } else if (abs(dy) > slop * 2) {
                    tracking = false
                }
            }
        }
        return false
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // Ninguém embaixo quis o toque (área sem nada clicável): fica com ele para poder reconhecer o deslize.
            MotionEvent.ACTION_DOWN -> return tracking
            MotionEvent.ACTION_MOVE -> if (tracking && !intercepted) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2f) {
                    intercepted = true
                } else if (abs(dy) > slop * 2) {
                    tracking = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (intercepted && abs(dx) > MIN_SWIPE_DP * density) onSwipe(if (dx < 0) 1 else -1)
                intercepted = false
                tracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                intercepted = false
                tracking = false
            }
        }
        return true
    }

    /** Existe, embaixo do dedo, algo que ainda rola de lado nessa direção? */
    private fun scrollableUnder(v: View, direction: Int, x: Float, y: Float): Boolean {
        if (v !== this && v.canScrollHorizontally(direction)) return true
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                if (child.visibility != VISIBLE) continue
                val cx = x + v.scrollX - child.left
                val cy = y + v.scrollY - child.top
                if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height) {
                    if (scrollableUnder(child, direction, cx, cy)) return true
                }
            }
        }
        return false
    }

    private companion object {
        const val EDGE_DP = 28f
        const val MIN_SWIPE_DP = 64f
    }
}
