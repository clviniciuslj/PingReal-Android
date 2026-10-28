package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { IDLE, PING, TRACE, DIAG }

    private class Target(
        val label: String,
        val host: String,
        val continuous: Boolean,
        val count: Int
    )

    private class RowViews(val cur: TextView, val avg: TextView, val loss: TextView)

    private companion object {
        const val MAX_TARGETS = 4
        const val MAX_HOPS = 30
        const val PROBES_PER_HOP = 3
        const val MAX_CONSOLE_LINES = 600
        const val TRIM_LINES = 100
        const val MAX_HISTORY = 12
        const val DASH = "—"
        const val PREFS = "pingreal"
        const val PREF_HOST = "host"
        const val PREF_FAVS = "favorites"
        const val PREF_HIST = "history"
        const val STATE_RESUME_PING = "resume_ping"

        val HOST_REGEX = Regex("[A-Za-z0-9._:%-]+")
        val WHITESPACE = Regex("\\s+")
    }

    // ---- paleta ----
    private val bg = Color.rgb(11, 11, 12)
    private val panel = Color.rgb(18, 19, 21)
    private val panelStroke = Color.rgb(36, 38, 41)
    private val green = Color.rgb(111, 207, 151)
    private val cyan = Color.rgb(108, 180, 238)
    private val violet = Color.rgb(183, 156, 237)
    private val orange = Color.rgb(232, 162, 99)
    private val amber = Color.rgb(229, 180, 84)
    private val red = Color.rgb(229, 115, 115)
    private val white = Color.rgb(230, 232, 234)
    private val text = Color.rgb(190, 194, 198)
    private val muted = Color.rgb(138, 143, 148)
    private val consoleBg = Color.rgb(8, 9, 10)
    private val seriesColors = intArrayOf(green, cyan, violet, orange)

    // ---- views ----
    private lateinit var ui: UiKit
    private lateinit var diagPage: DiagnosticPage
    private lateinit var root: LinearLayout
    private lateinit var pageTerminal: LinearLayout
    private lateinit var pageDiag: View
    private lateinit var nav: LinearLayout
    private val tabIcons = ArrayList<NavIconView>()
    private val tabLabels = ArrayList<TextView>()

    private lateinit var hostInput: EditText
    private lateinit var favButton: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var traceButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusDot: GradientDrawable
    private lateinit var chart: LatencyChartView
    private lateinit var chartSubtitle: TextView
    private lateinit var latencyNow: TextView
    private lateinit var singleStats: LinearLayout
    private lateinit var multiBox: LinearLayout
    private lateinit var statMin: TextView
    private lateinit var statAvg: TextView
    private lateinit var statMax: TextView
    private lateinit var statJitter: TextView
    private lateinit var statLoss: TextView
    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var gwValue: TextView
    private lateinit var dnsValue: TextView
    private lateinit var ipValue: TextView
    private lateinit var netValue: TextView

    private val multiRows = ArrayList<RowViews>()

    // ---- estado ----
    private var mode = Mode.IDLE
    private var lastRunMode = Mode.IDLE
    @Volatile private var stopRequested = false
    private var traceDebug = false

    // Momento em que o app saiu da tela; ao voltar, o ping em andamento é reiniciado.
    private var backgroundedAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val sessionPool = Executors.newCachedThreadPool()
    private val probePool = Executors.newFixedThreadPool(PROBES_PER_HOP)
    private val activeProcesses = CopyOnWriteArrayList<Process>()

    private val buffer = SpannableStringBuilder()
    private var consoleLines = 0

    private var sessions: List<PingSession> = emptyList()
    private val emptyStats = PingStats()
    private var netInfo = NetInfo(DASH, DASH, DASH, DASH)

    private val lossTick = object : Runnable {
        override fun run() {
            if (mode != Mode.PING) return
            sessions.forEach { it.tick() }
            updateStats()
            handler.postDelayed(this, 500)
        }
    }

    private val footerRunnable = Runnable { updateNetworkFooter() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleFooter()
        override fun onLost(network: Network) = scheduleFooter()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleFooter()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = scheduleFooter()
    }
    private var callbackRegistered = false

    private val sessionListener = object : PingSession.Listener {
        override fun onReply(session: PingSession, reply: PingReply, sampleTime: Long) {
            chart.addSample(sessions.indexOf(session), sampleTime, reply.timeMs.toFloat())
            val shown = if (reply.timeMs < 1) "<1" else reply.timeMs.roundToInt().toString()
            append(
                "${prefix(session)}Resposta de ${reply.address}: bytes=32 tempo=${shown}ms TTL=${reply.ttl}",
                if (sessions.size > 1) session.color else text
            )
            updateStats()
        }

        override fun onLoss(session: PingSession, sendTime: Long, blocked: Boolean) {
            chart.addLoss(sessions.indexOf(session), sendTime)
            append("${prefix(session)}Esgotado o tempo limite da solicitação.", amber)
            updateStats()
        }

        override fun onRestarted(session: PingSession) {}

        override fun onBlockedChanged(session: PingSession, blocked: Boolean) {}

        override fun onErrorLine(session: PingSession, line: String) {
            append("${prefix(session)}$line", red)
        }

        override fun onFinished(session: PingSession, error: String?) {
            if (mode == Mode.PING) printSummary(session, error)
            if (sessions.all { it.finished }) completeRun()
        }
    }

    /** Ponte entre a aba de diagnóstico e a activity (modo ocupado, rede atual, compartilhamento). */
    private val diagHost = object : DiagnosticPage.Host {
        override fun acquire(label: String): Boolean {
            if (mode != Mode.IDLE) return false
            stopRequested = false
            setMode(Mode.DIAG, label)
            return true
        }

        override fun release() = setMode(Mode.IDLE)

        override fun netInfo(): NetInfo = try {
            readNetworkInfo()
        } catch (_: Exception) {
            this@MainActivity.netInfo
        }

        override fun post(block: () -> Unit) = this@MainActivity.post(block)
        override fun shareText(text: String) = this@MainActivity.shareText(text)
        override fun shareImage(title: String, body: String) =
            this@MainActivity.shareImage(title, body, withChart = false)
    }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        ui = UiKit(this)
        buildUi()
        setContentView(root)

        hostInput.setText(prefs().getString(PREF_HOST, "") ?: "")
        selectTab(0)
        setMode(Mode.IDLE)
        updateStats()
        updateNetworkFooter()

        // O Android encerrou o app em segundo plano enquanto ele pingava: ao voltar, continua o ping.
        if (savedInstanceState?.getBoolean(STATE_RESUME_PING) == true) {
            handler.post { startPing() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Marca que havia um ping ativo, para continuar se o sistema encerrar o app em segundo plano.
        if (mode == Mode.PING) outState.putBoolean(STATE_RESUME_PING, true)
    }

    override fun onStart() {
        super.onStart()
        // O ping segue rodando em segundo plano (registrando o que falhar). Ao voltar, reinicia o processo
        // para garantir que está pingando de verdade, mantendo estatísticas e gráfico.
        if (mode == Mode.PING && backgroundedAt != 0L && System.currentTimeMillis() - backgroundedAt > 2000) {
            sessions.forEach { it.restart() }
        }
        backgroundedAt = 0L
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (_: Exception) {
            callbackRegistered = false
        }
        updateNetworkFooter()
    }

    override fun onStop() {
        backgroundedAt = System.currentTimeMillis()
        if (callbackRegistered) {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            callbackRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopRequested = true
        handler.removeCallbacksAndMessages(null)
        sessions.forEach { it.stop() }
        activeProcesses.forEach { it.destroy() }
        if (::diagPage.isInitialized) diagPage.shutdown()
        executor.shutdownNow()
        sessionPool.shutdownNow()
        probePool.shutdownNow()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    // =====================================================================
    // Interface
    // =====================================================================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun roundRect(fill: Int, radiusDp: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp)
            if (stroke != 0) setStroke(max(1, dp(1)), stroke)
        }

    private fun rippled(fill: Int, radiusDp: Float, stroke: Int = 0): Drawable {
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

    private fun tv(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        mono: Boolean = false,
        spacing: Float = 0f
    ): TextView = TextView(this).apply {
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

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(panel, 10f, panelStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun actionButton(label: String, fill: Int, textColor: Int, stroke: Int = 0, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            background = rippled(fill, 8f, stroke)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }

    private fun textAction(label: String, onClick: () -> Unit) =
        tv(label, 11f, muted, bold = true, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rippled(Color.TRANSPARENT, 6f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun statCell(label: String): Pair<LinearLayout, TextView> {
        val value = tv(DASH, 13f, white, bold = true, mono = true).apply { maxLines = 1 }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(label, 9f, muted, bold = true, spacing = 0.08f))
            addView(value, lp(-2, -2).apply { topMargin = dp(2) })
        }
        return cell to value
    }

    /** Célula compacta do rodapé: rótulo e valor na mesma linha. */
    private fun compactCell(label: String): Pair<LinearLayout, TextView> {
        val value = tv(DASH, 11f, text, mono = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(panel, 8f, panelStroke)
            setPadding(dp(10), dp(6), dp(8), dp(6))
            addView(tv(label, 9f, muted, bold = true, spacing = 0.06f), lp(dp(34), -2))
            addView(value, lp(0, -2, 1f))
        }
        return cell to value
    }

    private fun navItem(index: Int, kind: Int, label: String): LinearLayout {
        val icon = NavIconView(this, kind)
        val text = tv(label, 9f, muted, bold = true, spacing = 0.08f).apply { gravity = Gravity.CENTER }
        tabIcons.add(icon)
        tabLabels.add(text)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            background = rippled(Color.TRANSPARENT, 8f)
            isClickable = true
            addView(icon, lp(dp(24), dp(24)))
            addView(text, lp(-2, -2).apply { topMargin = dp(2) })
            setOnClickListener { selectTab(index) }
        }
    }

    private fun selectTab(index: Int) {
        pageTerminal.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageDiag.visibility = if (index == 1) View.VISIBLE else View.GONE
        for (i in tabIcons.indices) {
            val color = if (i == index) white else muted
            tabIcons[i].activeColor = color
            tabLabels[i].setTextColor(color)
        }
        if (index != 0) hideKeyboard()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            val pad = IntArray(4) // esquerda, topo, direita, base
            var imeHeight = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                pad[0] = bars.left
                pad[1] = bars.top
                pad[2] = bars.right
                pad[3] = max(bars.bottom, ime.bottom)
                imeHeight = ime.bottom
            } else {
                @Suppress("DEPRECATION")
                pad[0] = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                pad[1] = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                pad[2] = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                pad[3] = insets.systemWindowInsetBottom
            }
            v.setPadding(dp(16) + pad[0], dp(8) + pad[1], dp(16) + pad[2], dp(8) + pad[3])
            // Com o teclado aberto, a barra de abas só atrapalha.
            if (::nav.isInitialized) nav.visibility = if (imeHeight > dp(120)) View.GONE else View.VISIBLE
            insets
        }

        // ---- título + status (fixo, acima das abas) ----
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(10))
        }
        titleRow.addView(
            tv("REAL PING", 15f, white, bold = true, spacing = 0.18f),
            lp(0, -2, 1f)
        )
        statusText = tv("PRONTO", 10f, muted, bold = true, spacing = 0.1f)
        statusDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(muted)
        }
        titleRow.addView(statusText)
        titleRow.addView(View(this).apply { background = statusDot }, lp(dp(8), dp(8)).apply { marginStart = dp(8) })
        root.addView(titleRow, lp(-1, -2))

        // ---- páginas ----
        pageTerminal = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buildTerminalPage(pageTerminal)

        diagPage = DiagnosticPage(this, ui, diagHost)
        pageDiag = diagPage.view

        val pages = FrameLayout(this)
        pages.addView(pageTerminal, FrameLayout.LayoutParams(-1, -1))
        pages.addView(pageDiag, FrameLayout.LayoutParams(-1, -1))
        root.addView(pages, lp(-1, 0, 1f))

        // ---- barra de abas ----
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(bg, 0f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        nav.addView(navItem(0, NavIconView.TERMINAL, "TERMINAL"), lp(0, -2, 1f))
        nav.addView(navItem(1, NavIconView.DIAGNOSTIC, "DIAGNÓSTICO"), lp(0, -2, 1f))
        root.addView(View(this).apply { setBackgroundColor(panelStroke) }, lp(-1, dp(1)).apply { topMargin = dp(8) })
        root.addView(nav, lp(-1, -2).apply { topMargin = dp(2) })
    }

    private fun buildTerminalPage(page: LinearLayout) {
        // ---- entrada + favorito ----
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "IP ou host  (vários: 8.8.8.8, 1.1.1.1)"
            typeface = Typeface.MONOSPACE
            background = roundRect(panel, 8f, panelStroke)
            setPadding(dp(14), 0, dp(14), 0)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    startPing()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateStar()
            })
        }
        favButton = tv("☆", 22f, muted).apply {
            gravity = Gravity.CENTER
            background = rippled(panel, 8f, panelStroke)
            isClickable = true
            contentDescription = "Favoritar"
            setOnClickListener { toggleFavorite() }
        }
        inputRow.addView(hostInput, lp(0, dp(50), 1f))
        inputRow.addView(favButton, lp(dp(50), dp(50)).apply { marginStart = dp(8) })
        page.addView(inputRow, lp(-1, -2))

        // ---- atalhos (gateway, DNS, favoritos, recentes) ----
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipsRow, ViewGroup.LayoutParams(-2, -2))
        }
        page.addView(chipsScroll, lp(-1, -2).apply { topMargin = dp(8) })

        // ---- botões ----
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        runButton = actionButton("▶ INICIAR", green, Color.rgb(5, 20, 10)) { startPing() }
        stopButton = actionButton("PARAR", Color.TRANSPARENT, red, panelStroke) { stopRun() }
        traceButton = actionButton("TRACEROUTE", Color.TRANSPARENT, white, panelStroke) { startTraceRoute() }
        // Toque longo: mostra a linha crua que o ping devolveu em cada salto (para depurar o formato).
        traceButton.setOnLongClickListener {
            traceDebug = !traceDebug
            Toast.makeText(
                this,
                if (traceDebug) "Traceroute: modo depuração ligado" else "Traceroute: modo depuração desligado",
                Toast.LENGTH_SHORT
            ).show()
            true
        }
        buttons.addView(runButton, lp(0, dp(46), 1.5f))
        buttons.addView(stopButton, lp(0, dp(46), 1f).apply { marginStart = dp(8) })
        buttons.addView(traceButton, lp(0, dp(46), 1.3f).apply { marginStart = dp(8) })
        page.addView(buttons, lp(-1, -2))

        // ---- gráfico ----
        val chartCard = card()
        val chartHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chartSubtitle = tv("últimos 60 s", 10f, muted).apply { setPadding(0, dp(1), 0, 0) }
        val chartTitle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("LATÊNCIA", 11f, muted, bold = true, spacing = 0.1f))
            addView(chartSubtitle)
        }
        latencyNow = tv("— ms", 24f, white, bold = true, mono = true)
        chartHeader.addView(chartTitle, lp(0, -2, 1f))
        chartHeader.addView(latencyNow)
        chartCard.addView(chartHeader, lp(-1, -2))

        chart = LatencyChartView(this)
        chartCard.addView(chart, lp(-1, dp(115)).apply { topMargin = dp(6); bottomMargin = dp(8) })

        singleStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (minCell, minV) = statCell("MÍN")
        val (avgCell, avgV) = statCell("MÉD")
        val (maxCell, maxV) = statCell("MÁX")
        val (jitCell, jitV) = statCell("JITTER")
        val (lossCell, lossV) = statCell("PERDA")
        statMin = minV; statAvg = avgV; statMax = maxV; statJitter = jitV; statLoss = lossV
        for (cell in listOf(minCell, avgCell, maxCell, jitCell, lossCell)) singleStats.addView(cell, lp(0, -2, 1f))
        chartCard.addView(singleStats, lp(-1, -2))

        multiBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        chartCard.addView(multiBox, lp(-1, -2))
        page.addView(chartCard, lp(-1, -2))

        // ---- console ----
        val consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(consoleBg, 10f, panelStroke)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        consoleHeader.addView(tv("CONSOLE", 11f, muted, bold = true, spacing = 0.1f), lp(0, -2, 1f))
        consoleHeader.addView(textAction("ENVIAR") { showShareDialog() })
        consoleHeader.addView(textAction("COPIAR") { copyConsole() })
        consoleHeader.addView(textAction("LIMPAR") { if (mode == Mode.IDLE) clearConsole() })
        consoleCard.addView(consoleHeader, lp(-1, -2))

        scroll = ScrollView(this).apply { isFillViewport = true }
        output = tv("", 12f, text, mono = true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextIsSelectable(true)
            setLineSpacing(dpf(2f), 1f)
        }
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        consoleCard.addView(scroll, lp(-1, 0, 1f))
        page.addView(consoleCard, lp(-1, 0, 1f).apply { topMargin = dp(10) })

        // ---- rodapé de rede (compacto) ----
        val (gwCell, gwV) = compactCell("GW")
        val (dnsCell, dnsV) = compactCell("DNS")
        val (ipCell, ipV) = compactCell("IP")
        val (netCell, netV) = compactCell("REDE")
        gwValue = gwV; dnsValue = dnsV; ipValue = ipV; netValue = netV

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(gwCell, lp(0, -2, 1f).apply { marginEnd = dp(6) })
        row1.addView(dnsCell, lp(0, -2, 1f))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(ipCell, lp(0, -2, 1f).apply { marginEnd = dp(6) })
        row2.addView(netCell, lp(0, -2, 1f))
        page.addView(row1, lp(-1, -2).apply { topMargin = dp(8) })
        page.addView(row2, lp(-1, -2).apply { topMargin = dp(6) })
    }

    private fun setMode(newMode: Mode, label: String? = null) {
        mode = newMode
        val idle = newMode == Mode.IDLE
        for (b in listOf(runButton, traceButton)) {
            b.isEnabled = idle
            b.alpha = if (idle) 1f else 0.4f
        }
        stopButton.isEnabled = newMode == Mode.PING || newMode == Mode.TRACE
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.4f

        val (text, color) = when (newMode) {
            Mode.IDLE -> "PRONTO" to muted
            Mode.PING -> "PING ATIVO" to green
            Mode.TRACE -> "TRACEROUTE" to amber
            Mode.DIAG -> (label ?: "DIAGNÓSTICO") to white
        }
        statusText.text = text
        statusText.setTextColor(color)
        statusDot.setColor(color)

        if (::diagPage.isInitialized) diagPage.onModeChanged(idle)
    }

    // =====================================================================
    // Estatísticas (uma linha de resumo ou uma linha por alvo)
    // =====================================================================

    private fun buildStatsPanel() {
        val multi = sessions.size > 1
        singleStats.visibility = if (multi) View.GONE else View.VISIBLE
        multiBox.visibility = if (multi) View.VISIBLE else View.GONE
        latencyNow.visibility = if (multi) View.GONE else View.VISIBLE
        chartSubtitle.text = if (multi) "${sessions.size} alvos · últimos 60 s" else "últimos 60 s"

        multiBox.removeAllViews()
        multiRows.clear()
        if (!multi) return

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(View(this), lp(0, 1, 1f))
        for ((text, width) in listOf("ATUAL" to 56, "MÉD" to 56, "PERDA" to 44)) {
            header.addView(
                tv(text, 9f, muted, bold = true, spacing = 0.08f).apply { gravity = Gravity.END },
                lp(dp(width), -2)
            )
        }
        multiBox.addView(header, lp(-1, -2))

        for (s in sessions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(s.color)
                }
            }
            val name = tv(s.label, 11f, white, mono = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val cur = tv(DASH, 11f, white, bold = true, mono = true).apply { gravity = Gravity.END }
            val avg = tv(DASH, 11f, white, mono = true).apply { gravity = Gravity.END }
            val loss = tv(DASH, 11f, white, mono = true).apply { gravity = Gravity.END }
            row.addView(dot, lp(dp(8), dp(8)))
            row.addView(name, lp(0, -2, 1f).apply { marginStart = dp(8) })
            row.addView(cur, lp(dp(56), -2))
            row.addView(avg, lp(dp(56), -2))
            row.addView(loss, lp(dp(44), -2))
            multiBox.addView(row, lp(-1, -2))
            multiRows.add(RowViews(cur, avg, loss))
        }
    }

    private fun updateStats() {
        if (sessions.size > 1) updateMultiStats() else updateSingleStats(sessions.firstOrNull()?.stats ?: emptyStats)
    }

    private fun updateSingleStats(s: PingStats) {
        when {
            s.lastWasLoss -> {
                latencyNow.text = "perdido"
                latencyNow.setTextColor(red)
            }
            s.last >= 0 -> {
                latencyNow.text = "${fmt(s.last)} ms"
                latencyNow.setTextColor(LatencyChartView.colorFor(s.last))
            }
            else -> {
                latencyNow.text = "— ms"
                latencyNow.setTextColor(white)
            }
        }
        statMin.text = if (s.received > 0) "${fmt(s.min)}ms" else DASH
        statAvg.text = if (s.received > 0) "${fmt(s.avg)}ms" else DASH
        statMax.text = if (s.received > 0) "${fmt(s.max)}ms" else DASH
        statJitter.text = if (s.jitterSamples > 0) "${fmt(s.jitter)}ms" else DASH
        statLoss.text = if (s.sent > 0) "${s.lossPct.roundToInt()}%" else DASH
        statLoss.setTextColor(if (s.lost > 0) red else white)
    }

    private fun updateMultiStats() {
        for ((i, s) in sessions.withIndex()) {
            val row = multiRows.getOrNull(i) ?: continue
            val st = s.stats
            when {
                st.lastWasLoss -> {
                    row.cur.text = "perdido"
                    row.cur.setTextColor(red)
                }
                st.last >= 0 -> {
                    row.cur.text = "${fmt(st.last)}ms"
                    row.cur.setTextColor(LatencyChartView.colorFor(st.last))
                }
                else -> {
                    row.cur.text = DASH
                    row.cur.setTextColor(white)
                }
            }
            row.avg.text = if (st.received > 0) "${fmt(st.avg)}ms" else DASH
            row.loss.text = if (st.sent > 0) "${st.lossPct.roundToInt()}%" else DASH
            row.loss.setTextColor(if (st.lost > 0) red else white)
        }
    }

    private fun fmt(ms: Double): String =
        if (ms < 10) String.format(Locale.US, "%.1f", ms) else ms.roundToInt().toString()

    // =====================================================================
    // Favoritos e histórico
    // =====================================================================

    private fun loadList(key: String): MutableList<String> =
        (prefs().getString(key, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()

    private fun saveList(key: String, list: List<String>) {
        prefs().edit().putString(key, list.joinToString("\n")).apply()
    }

    private fun addHistory(entry: String) {
        val list = loadList(PREF_HIST)
        list.remove(entry)
        list.add(0, entry)
        saveList(PREF_HIST, list.take(MAX_HISTORY))
        refreshChips()
    }

    private fun toggleFavorite() {
        val entry = hostInput.text.toString().trim()
        if (entry.isEmpty()) {
            Toast.makeText(this, "Digite um alvo para favoritar", Toast.LENGTH_SHORT).show()
            return
        }
        val favs = loadList(PREF_FAVS)
        if (!favs.remove(entry)) favs.add(0, entry)
        saveList(PREF_FAVS, favs)
        refreshChips()
    }

    private fun updateStar() {
        if (!::favButton.isInitialized || !::hostInput.isInitialized) return
        val fav = hostInput.text.toString().trim().let { it.isNotEmpty() && it in loadList(PREF_FAVS) }
        favButton.text = if (fav) "★" else "☆"
        favButton.setTextColor(if (fav) amber else muted)
    }

    private fun usableIp(value: String): Boolean =
        value != DASH && value != "N/D" && parseHost(value) != null

    private fun chip(label: String, color: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): TextView =
        tv(label, 11f, color, mono = true).apply {
            maxLines = 1
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = rippled(panel, 8f, panelStroke)
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }

    private fun refreshChips() {
        chipsRow.removeAllViews()
        fun add(view: View) = chipsRow.addView(view, lp(-2, -2).apply { marginEnd = dp(6) })
        fun short(s: String) = if (s.length > 24) s.take(23) + "…" else s
        fun fill(entry: String) {
            hostInput.setText(entry)
            hostInput.setSelection(entry.length)
        }

        if (usableIp(netInfo.gateway)) add(chip("⌂ Gateway", white, { addTarget(netInfo.gateway) }))
        if (usableIp(netInfo.dns)) add(chip("DNS", white, { addTarget(netInfo.dns) }))

        val favs = loadList(PREF_FAVS)
        for (f in favs) {
            add(chip("★ ${short(f)}", amber, { fill(f) }, {
                favs.remove(f)
                saveList(PREF_FAVS, favs)
                Toast.makeText(this, "Favorito removido", Toast.LENGTH_SHORT).show()
                refreshChips()
            }))
        }
        for (h in loadList(PREF_HIST).filter { it !in favs }) {
            add(chip(short(h), muted, { fill(h) }, {
                saveList(PREF_HIST, loadList(PREF_HIST).filter { it != h })
                Toast.makeText(this, "Removido do histórico", Toast.LENGTH_SHORT).show()
                refreshChips()
            }))
        }

        chipsScroll.visibility = if (chipsRow.childCount == 0) View.GONE else View.VISIBLE
        updateStar()
    }

    /** Atalho de gateway/DNS: preenche o campo, ou acrescenta como mais um alvo se já houver texto. */
    private fun addTarget(ip: String) {
        val current = hostInput.text.toString().trim()
        val text = when {
            current.isEmpty() -> ip
            current.split(',').any { it.trim().split(WHITESPACE).firstOrNull() == ip } -> current
            else -> "$current, $ip"
        }
        hostInput.setText(text)
        hostInput.setSelection(text.length)
    }

    // =====================================================================
    // Console
    // =====================================================================

    private fun append(line: String, color: Int = text) {
        val atBottom = scroll.getChildAt(0).let { child ->
            child == null || child.bottom - (scroll.height + scroll.scrollY) < dp(48)
        }
        val span = SpannableString("$line\n")
        span.setSpan(ForegroundColorSpan(color), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        buffer.append(span)
        output.append(span)
        consoleLines++
        if (consoleLines > MAX_CONSOLE_LINES) trimConsole()
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun trimConsole() {
        var cut = 0
        repeat(TRIM_LINES) {
            val idx = buffer.indexOf('\n', cut)
            if (idx >= 0) cut = idx + 1
        }
        if (cut > 0) {
            buffer.delete(0, cut)
            consoleLines -= TRIM_LINES
            output.text = buffer
        }
    }

    private fun clearConsole() {
        buffer.clear()
        consoleLines = 0
        output.text = ""
    }

    private fun copyConsole() {
        if (buffer.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Real Ping", buffer.toString()))
        Toast.makeText(this, "Console copiado", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        if (!::hostInput.isInitialized) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    private fun post(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun prefix(session: PingSession): String =
        if (sessions.size > 1) "[${session.label}] " else ""

    // =====================================================================
    // Rodapé de rede
    // =====================================================================

    private fun scheduleFooter() {
        handler.removeCallbacks(footerRunnable)
        handler.postDelayed(footerRunnable, 300)
    }

    private fun updateNetworkFooter() {
        netInfo = try {
            readNetworkInfo()
        } catch (_: Exception) {
            NetInfo("N/D", "N/D", "N/D", "Indisponível")
        }
        gwValue.text = netInfo.gateway
        dnsValue.text = netInfo.dns
        ipValue.text = netInfo.ip
        netValue.text = netInfo.type
        refreshChips()
    }

    private fun cleanAddress(address: InetAddress?): String? =
        address?.hostAddress?.substringBefore('%')

    @Suppress("DEPRECATION")
    private fun readNetworkInfo(): NetInfo {
        val cm = getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return NetInfo(DASH, DASH, DASH, "Sem conexão")
        val activeCaps = cm.getNetworkCapabilities(active)
        val viaVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // Com VPN ativa, gateway/DNS/IP da VPN não ajudam: usa a rede física por baixo dela.
        val base = if (viaVpn) {
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: active
        } else {
            active
        }

        val caps = cm.getNetworkCapabilities(base)
        val props = cm.getLinkProperties(base)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val baseType = when {
            isWifi -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Móvel"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "Bluetooth"
            else -> null
        }
        val type = when {
            baseType != null && viaVpn -> "$baseType + VPN"
            baseType != null -> baseType
            viaVpn -> "VPN"
            else -> DASH
        }

        val dhcp = if (isWifi) {
            try {
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        fun dhcpIp(v: Int?): String? =
            if (v == null || v == 0) null
            else "${v and 0xff}.${(v shr 8) and 0xff}.${(v shr 16) and 0xff}.${(v shr 24) and 0xff}"

        val addresses = props?.linkAddresses?.map { it.address }.orEmpty()
        val ip = cleanAddress(addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress })
            ?: cleanAddress(addresses.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress })
            ?: DASH

        val defaultRoutes = props?.routes?.filter { it.isDefaultRoute }.orEmpty()
        val gateway = cleanAddress(
            defaultRoutes.mapNotNull { it.gateway }
                .firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }
        )
            ?: cleanAddress(
                defaultRoutes.mapNotNull { it.gateway }.firstOrNull { !it.isAnyLocalAddress }
            )
            ?: dhcpIp(dhcp?.gateway)
            ?: DASH

        val dnsList = props?.dnsServers.orEmpty().filter { !it.isAnyLocalAddress }
        val dns = cleanAddress(dnsList.firstOrNull { it is Inet4Address })
            ?: cleanAddress(dnsList.firstOrNull())
            ?: dhcpIp(dhcp?.dns1)
            ?: DASH

        return NetInfo(gateway, dns, ip, type)
    }

    // =====================================================================
    // Ping (um ou vários alvos)
    // =====================================================================

    private fun parseHost(candidate: String): String? =
        candidate.takeIf { it.isNotBlank() && !it.startsWith("-") && it.matches(HOST_REGEX) }

    /** Interpreta um alvo no formato `host [-t] [-n N]`. Mostra o erro no console e devolve null se inválido. */
    private fun parseSegment(segment: String): Target? {
        val parts = segment.split(WHITESPACE).filter { it.isNotBlank() }
        val host = parseHost(parts.firstOrNull().orEmpty())
        if (host == null) {
            append("Endereço inválido: ${parts.firstOrNull().orEmpty()}", red)
            return null
        }

        var continuous = true
        var count = 4
        var countSpecified = false
        var index = 1

        while (index < parts.size) {
            when (parts[index].lowercase()) {
                "-t" -> {
                    continuous = true
                    index++
                }
                "-n", "-c" -> {
                    if (countSpecified || index + 1 >= parts.size) {
                        append("Quantidade inválida em: $segment", red)
                        return null
                    }
                    val parsed = parts[index + 1].toIntOrNull()
                    if (parsed == null || parsed !in 1..10000) {
                        append("Quantidade deve estar entre 1 e 10000.", red)
                        return null
                    }
                    count = parsed
                    countSpecified = true
                    continuous = false
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}", red)
                    return null
                }
            }
        }
        return Target(host, host, continuous, count)
    }

    private fun startPing() {
        if (mode != Mode.IDLE) return

        val raw = hostInput.text.toString().trim()
        val segments = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            append("Informe um IP ou host.", amber)
            return
        }
        if (segments.size > MAX_TARGETS) {
            append("No máximo $MAX_TARGETS alvos ao mesmo tempo.", red)
            return
        }

        val targets = ArrayList<Target>()
        for (segment in segments) targets.add(parseSegment(segment) ?: return)

        prefs().edit().putString(PREF_HOST, raw).apply()
        addHistory(raw)

        append("")
        append("────────────────────────────", muted)
        append("> $raw", white)
        if (targets.size == 1) {
            val t = targets[0]
            append(
                if (t.continuous) "Ping contínuo para ${t.host}" else "Ping para ${t.host} (${t.count} tentativas)",
                muted
            )
        } else {
            append("Ping simultâneo para ${targets.size} alvos", muted)
        }
        append("")

        startSessions(targets)
    }

    private fun startSessions(targets: List<Target>) {
        hideKeyboard()
        stopRequested = false

        val colors = IntArray(targets.size) { seriesColors[it % seriesColors.size] }
        sessions = targets.mapIndexed { i, t ->
            PingSession(t.label, t.host, t.continuous, t.count, colors[i], sessionListener, sessionPool, ::post)
        }

        chart.placeholder = "Aguardando respostas…"
        chart.setSeries(colors)
        chart.setLive(true)
        buildStatsPanel()
        updateStats()
        setMode(Mode.PING)
        handler.postDelayed(lossTick, 500)

        sessions.forEach { it.start() }
    }

    private fun printSummary(session: PingSession, error: String?) {
        val s = session.stats
        val tag = prefix(session)
        if (s.sent > 0) {
            append("")
            append("${tag}Estatísticas do Ping para ${session.host}:", white)
            append(
                "    Pacotes: Enviados = ${s.sent}, Recebidos = ${s.received}, " +
                    "Perdidos = ${s.lost} (${s.lossPct.roundToInt()}% de perda),",
                white
            )
            if (s.received > 0) {
                append(
                    "    Mínimo = ${fmt(s.min)}ms, Máximo = ${fmt(s.max)}ms, " +
                        "Média = ${fmt(s.avg)}ms, Jitter = ${fmt(s.jitter)}ms",
                    white
                )
            } else {
                append("    Nenhuma resposta válida recebida.", amber)
            }
        }
        when {
            error != null -> append("${tag}Erro: $error", red)
            session.hardError -> append("${tag}Falha ao resolver ou alcançar o destino.", red)
        }
    }

    private fun completeRun() {
        handler.removeCallbacks(lossTick)
        chart.setLive(false)
        append(if (stopRequested) "Interrompido pelo usuário." else "Concluído.", muted)
        append("────────────────────────────", muted)

        lastRunMode = Mode.PING
        setMode(Mode.IDLE)
        updateStats()
    }

    private fun stopRun() {
        if (mode != Mode.PING && mode != Mode.TRACE) return
        stopRequested = true
        append("Interrompendo...", muted)
        sessions.forEach { it.stop() }
        activeProcesses.forEach { it.destroy() }
    }

    // =====================================================================
    // Compartilhar resultado
    // =====================================================================

    private fun reportTitle(): String = "Real Ping — relatório de ping"

    private fun reportDate(): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())

    private fun reportBody(): String {
        val sb = StringBuilder()
        sb.append("Rede: ${netInfo.type} · IP ${netInfo.ip}\n")
        sb.append("Gateway ${netInfo.gateway} · DNS ${netInfo.dns}\n")
        for (s in sessions) {
            val st = s.stats
            sb.append('\n')
            sb.append("▸ ").append(s.label)
            if (s.label != s.host) sb.append(" (").append(s.host).append(')')
            sb.append('\n')
            sb.append("  Enviados ${st.sent} · Recebidos ${st.received} · Perda ${st.lossPct.roundToInt()}%\n")
            if (st.received > 0) {
                sb.append(
                    "  Mín ${fmt(st.min)}ms · Méd ${fmt(st.avg)}ms · Máx ${fmt(st.max)}ms · Jitter ${fmt(st.jitter)}ms\n"
                )
            } else {
                sb.append("  Nenhuma resposta\n")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun reportText(): String = "${reportTitle()}\n${reportDate()}\n\n${reportBody()}"

    private fun showShareDialog() {
        // Traceroute (ou nada executado ainda): compartilha o texto do console.
        if (sessions.isEmpty() || lastRunMode == Mode.TRACE) {
            if (buffer.isEmpty()) {
                Toast.makeText(this, "Nada para enviar ainda", Toast.LENGTH_SHORT).show()
            } else {
                shareText(buffer.toString())
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enviar resultado")
            .setItems(arrayOf("Texto (resumo)", "Imagem (gráfico + estatísticas)")) { _, which ->
                if (which == 0) shareText(reportText()) else shareImage(reportTitle(), reportBody(), withChart = true)
            }
            .show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Enviar resultado"))
    }

    private fun shareImage(title: String, body: String, withChart: Boolean) {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "pingreal_relatorio.png")
            FileOutputStream(file).use { out ->
                buildReportBitmap(title, body, withChart).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar resultado"))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível gerar a imagem: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildReportBitmap(title: String, body: String, withChart: Boolean): Bitmap {
        val width = 1080
        val pad = 48
        val inner = width - 2 * pad

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 30f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = 30f
            typeface = Typeface.MONOSPACE
        }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, inner)
            .setLineSpacing(8f, 1f)
            .build()

        val chartW = max(1, chart.width)
        val chartH = max(1, chart.height)
        val scale = inner.toFloat() / chartW
        val scaledChartH = if (withChart) (chartH * scale).toInt() else 0

        var y = pad + 52
        val titleBaseline = y.toFloat()
        y += 44
        val dateBaseline = y.toFloat()
        y += 36
        val chartTop = y
        if (withChart) y += scaledChartH + 36
        val bodyTop = y
        val totalH = bodyTop + layout.height + pad

        val bitmap = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)
        canvas.drawText(title, pad.toFloat(), titleBaseline, titlePaint)
        canvas.drawText(reportDate(), pad.toFloat(), dateBaseline, datePaint)

        if (withChart) {
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panel }
            canvas.drawRoundRect(
                RectF(pad.toFloat(), chartTop.toFloat(), (pad + inner).toFloat(), (chartTop + scaledChartH).toFloat()),
                28f, 28f, panelPaint
            )
            canvas.save()
            canvas.translate(pad.toFloat(), chartTop.toFloat())
            canvas.scale(scale, scale)
            chart.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(pad.toFloat(), bodyTop.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    // =====================================================================
    // Traceroute
    // =====================================================================

    private fun startTraceRoute() {
        if (mode != Mode.IDLE) return

        val firstSegment = hostInput.text.toString().split(',').firstOrNull().orEmpty().trim()
        val host = parseHost(firstSegment.split(WHITESPACE).firstOrNull().orEmpty())
        if (host == null) {
            append("Informe um IP ou hostname válido para o traceroute.", amber)
            return
        }

        prefs().edit().putString(PREF_HOST, host).apply()
        hideKeyboard()

        append("")
        append("────────────────────────────", muted)
        append("> traceroute $host", white)
        append("Rastreando rota para $host (máx. $MAX_HOPS saltos, $PROBES_PER_HOP sondas por salto)", muted)
        append("")

        stopRequested = false
        setMode(Mode.TRACE)
        executor.execute { runTrace(host) }
    }

    /** IP fora das faixas privadas/locais (um destino assim nunca está a 1 salto do celular). */
    private fun isPublicIp(ip: String): Boolean {
        val v4 = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4) {
            val (a, b) = v4[0] to v4[1]
            return !(a == 10 || a == 127 || a == 0 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254))
        }
        val lower = ip.lowercase()
        return !(lower == "::1" || lower.startsWith("fe80") || lower.startsWith("fc") || lower.startsWith("fd"))
    }

    private fun runTrace(host: String) {
        var failure: String? = null
        var reached = false
        val debug = traceDebug

        try {
            val overhead = Tracer.calibrateOverhead(activeProcesses)

            for (ttl in 1..MAX_HOPS) {
                if (stopRequested) break

                val futures = (1..PROBES_PER_HOP).map {
                    probePool.submit(Callable<Tracer.Probe> { Tracer.probe(host, ttl, overhead, activeProcesses) })
                }
                val probes = futures.map { it.get() }
                if (stopRequested) break

                val hardRaw = probes.firstNotNullOfOrNull { p ->
                    p.raw?.takeIf { PingSession.isHardError(it.lowercase()) }
                }
                if (hardRaw != null) {
                    failure = hardRaw
                    break
                }

                val addresses = probes.mapNotNull { it.address }.distinct()
                val cells = probes.joinToString("") { p ->
                    val text = p.ms?.let { if (it < 1.0) "<1 ms" else "${it.roundToInt()} ms" } ?: "*"
                    text.padStart(8)
                }
                val where = if (addresses.isEmpty()) "Esgotado o tempo limite" else addresses.joinToString(", ")
                val hopReached = probes.any { it.reached }
                val color = if (addresses.isEmpty()) amber else text
                val rawNotes: List<String> = when {
                    debug -> probes.mapNotNull { it.line }.distinct()
                    addresses.isEmpty() -> listOfNotNull(probes.firstNotNullOfOrNull { it.raw })
                    else -> emptyList()
                }

                post {
                    append("${ttl.toString().padStart(2)}$cells  $where", color)
                    rawNotes.forEach { append("      ↳ $it", muted) }
                }

                // Um IP público não pode estar a 1 salto: o ping deste aparelho não respeitou o TTL.
                val farAtFirstHop = ttl == 1 && probes.any { p -> p.reached && p.address?.let { isPublicIp(it) } == true }
                if (farAtFirstHop) {
                    failure = "o destino respondeu no salto 1 mesmo com TTL=1, então o ping deste aparelho ignora o TTL " +
                        "e o traceroute não consegue mostrar os saltos intermediários."
                    break
                }

                if (hopReached) {
                    reached = true
                    break
                }
            }
        } catch (e: Exception) {
            if (!stopRequested) {
                failure = (e.cause?.message ?: e.message) ?: "falha ao executar as sondagens do traceroute"
            }
        }

        post {
            when {
                stopRequested -> append("Traceroute interrompido pelo usuário.", muted)
                failure != null -> append("Falha no traceroute: $failure", red)
                reached -> append("Destino alcançado.", green)
                else -> append("Rastreamento encerrado ($MAX_HOPS saltos sem alcançar o destino).", amber)
            }
            append("────────────────────────────", muted)
            lastRunMode = Mode.TRACE
            setMode(Mode.IDLE)
        }
    }
}
