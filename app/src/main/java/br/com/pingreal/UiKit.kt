package br.com.pingreal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** Paleta e construtores de view usados pelas telas montadas em código. */
class UiKit(private val context: Context) {

    val bg = Color.rgb(8, 12, 10)
    val panel = Color.rgb(17, 25, 20)
    val panelStroke = Color.rgb(30, 43, 35)
    val green = Color.rgb(74, 222, 128)
    val cyan = Color.rgb(56, 189, 248)
    val amber = Color.rgb(251, 191, 36)
    val red = Color.rgb(248, 113, 113)
    val white = Color.rgb(232, 238, 233)
    val muted = Color.rgb(125, 143, 132)

    private val density = context.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()
    fun dpf(value: Float): Float = value * density

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun roundRect(fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != 0) setStroke(max(1, dp(1)), stroke)
        }

    fun rippled(fill: Int, radiusDp: Float, stroke: Int = 0): Drawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpf(radiusDp)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 255, 255, 255)),
            roundRect(fill, radiusDp, stroke),
            mask
        )
    }

    fun tv(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        spacing: Float = 0f
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        letterSpacing = spacing
    }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(panel, 16f, panelStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun button(label: String, fill: Int, textColor: Int, stroke: Int = 0, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            background = rippled(fill, 12f, stroke)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }
}

/** Ícones da barra inferior, desenhados em código. */
class NavIconView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val TERMINAL = 0
        const val DIAGNOSTIC = 1
    }

    var activeColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        paint.color = activeColor
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = paint.strokeWidth

        when (kind) {
            TERMINAL -> {
                // moldura + prompt ">_"
                canvas.drawRoundRect(RectF(inset, inset + h * 0.08f, w - inset, h - inset - h * 0.08f), 5f * density, 5f * density, paint)
                path.reset()
                path.moveTo(w * 0.27f, h * 0.36f)
                path.lineTo(w * 0.43f, h * 0.50f)
                path.lineTo(w * 0.27f, h * 0.64f)
                canvas.drawPath(path, paint)
                canvas.drawLine(w * 0.52f, h * 0.65f, w * 0.72f, h * 0.65f, paint)
            }
            else -> {
                // anel + linha de batimento
                canvas.drawCircle(w / 2f, h / 2f, (w / 2f) - inset, paint)
                path.reset()
                path.moveTo(w * 0.16f, h * 0.52f)
                path.lineTo(w * 0.34f, h * 0.52f)
                path.lineTo(w * 0.43f, h * 0.30f)
                path.lineTo(w * 0.55f, h * 0.74f)
                path.lineTo(w * 0.64f, h * 0.52f)
                path.lineTo(w * 0.84f, h * 0.52f)
                canvas.drawPath(path, paint)
            }
        }
    }
}

/** Barra de progresso fina e arredondada. */
class MeterBar(context: Context) : View(context) {

    var fraction: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var fillColor: Int = Color.rgb(74, 222, 128)
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, trackPaint)
        if (fraction > 0f) {
            fillPaint.color = fillColor
            rect.set(0f, 0f, max(height.toFloat(), width * fraction), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (6 * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }
}
