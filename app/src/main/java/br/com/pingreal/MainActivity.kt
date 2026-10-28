package br.com.pingreal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { IDLE, PING, TRACE }

    private class PingStats {
        var received = 0
        var lost = 0
        var min = Double.MAX_VALUE
        var max = 0.0
        var sum = 0.0
        var last = -1.0
        var lastWasLoss = false
        var jitterSum = 0.0
        var jitterN = 0

        val sent: Int get() = received + lost
        val avg: Double get() = if (received > 0) sum / received else 0.0
        val jitter: Double get() = if (jitterN > 0) jitterSum / jitterN else 0.0
        val lossPct: Double get() = if (sent > 0) lost * 100.0 / sent else 0.0

        fun add(ms: Double) {
            if (last >= 0 && !lastWasLoss) {
                jitterSum += kotlin.math.abs(ms - last)
                jitterN++
            }
            received++
            sum += ms
            if (ms < min) min = ms
            if (ms > max) max = ms
            last = ms
            lastWasLoss = false
        }
    }

    private class Probe(
        val address: String?,
        val ms: Double?,
        val reached: Boolean,
        val raw: String?
    )

    private data class NetInfo(
        val gateway: String,
        val dns: String,
        val ip: String,
        val type: String
    )

    private companion object {
        const val MAX_HOPS = 30
        const val PROBES_PER_HOP = 3
        const val SILENT_HOPS_LIMIT = 6
        const val LOSS_TIMEOUT_MS = 2000L
        const val MAX_CONSOLE_LINES = 600
        const val TRIM_LINES = 100
        const val DASH = "—"
        const val PREFS = "pingreal"
        const val PREF_HOST = "host"

        val HOST_REGEX = Regex("[A-Za-z0-9._:%-]+")
        val WHITESPACE = Regex("\\s+")

        val REPLY_STRICT = Regex(
            """(\d+)\s+bytes from\s+(.+?):?\s+icmp_\w+=(\d+)\s+ttl=(\d+)\s+time[=<]\s*([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        )
        val REPLY_LOOSE = Regex(
            """(\d+)\s+bytes from\s+(.+?):.*?\bttl[= ](\d+).*?\btime[=<]\s*([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        )
        val TTL_FIELD = Regex("""\bttl[= ]\d+""", RegexOption.IGNORE_CASE)
        val TIME_FIELD = Regex("""time[=<]\s*([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
        val FROM_ADDR = Regex(
            """\bfrom\s+(?:\S+\s+\()?([0-9a-fA-F:.]+)\)?""",
            RegexOption.IGNORE_CASE
        )

        val EXCEEDED = listOf(
            "time to live exceeded", "ttl exceeded", "time exceeded",
            "hop limit", "ttl expired"
        )
    }

    // ---- paleta ----
    private val bg = Color.rgb(8, 12, 10)
    private val panel = Color.rgb(17, 25, 20)
    private val panelStroke = Color.rgb(30, 43, 35)
    private val green = Color.rgb(74, 222, 128)
    private val amber = Color.rgb(251, 191, 36)
    private val red = Color.rgb(248, 113, 113)
    private val white = Color.rgb(232, 238, 233)
    private val muted = Color.rgb(125, 143, 132)
    private val consoleBg = Color.rgb(5, 9, 7)

    // ---- views ----
    private lateinit var root: LinearLayout
    private lateinit var hostInput: EditText
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var traceButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusDot: GradientDrawable
    private lateinit var chart: LatencyChartView
    private lateinit var latencyNow: TextView
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

    // ---- estado ----
    private var mode = Mode.IDLE
    @Volatile private var stopRequested = false

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val probePool = Executors.newFixedThreadPool(PROBES_PER_HOP)
    private val activeProcesses = CopyOnWriteArrayList<Process>()

    private val buffer = SpannableStringBuilder()
    private var consoleLines = 0

    // sessão de ping (somente thread da UI)
    private var stats = PingStats()
    private var sessionHost = ""
    private var sessionContinuous = true
    private var sessionCount = 0
    private var slotStart = 0L
    private var slotAnchored = false
    private var nextSeq = 1
    private var lastSeq = 0
    private var hardError = false
    private val answered = HashSet<Int>()

    private val lossTick = object : Runnable {
        override fun run() {
            if (mode != Mode.PING) return
            checkLosses(false)
            updateStats()
            handler.postDelayed(this, 500)
        }
    }

    private val footerRunnable = Runnable { updateNetworkFooter() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleFooter()
        override fun onLost(network: Network) = scheduleFooter()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleFooter()
        override fun onLinkPropertiesChanged(network: Network, props: android.net.LinkProperties) = scheduleFooter()
    }
    private var callbackRegistered = false

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        buildUi()
        setContentView(root)

        hostInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_HOST, "") ?: "")
        setMode(Mode.IDLE)
        updateStats()
        updateNetworkFooter()
    }

    override fun onStart() {
        super.onStart()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (_: Exception) {
            callbackRegistered = false
        }
        updateNetworkFooter()
    }

    override fun onStop() {
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
        activeProcesses.forEach { it.destroy() }
        executor.shutdownNow()
        probePool.shutdownNow()
        super.onDestroy()
    }

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
        background = roundRect(panel, 16f, panelStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun actionButton(label: String, fill: Int, textColor: Int, stroke: Int = 0, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setTextColor(textColor)
            background = rippled(fill, 12f, stroke)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

    private fun textAction(label: String, onClick: () -> Unit) =
        tv(label, 11f, green, bold = true, spacing = 0.08f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rippled(Color.TRANSPARENT, 8f)
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

    private fun infoCell(label: String): Pair<LinearLayout, TextView> {
        val value = tv(DASH, 12f, green, mono = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(panel, 12f, panelStroke)
            setPadding(dp(12), dp(8), dp(10), dp(8))
            addView(tv(label, 9f, muted, bold = true, spacing = 0.08f))
            addView(value, lp(-1, -2).apply { topMargin = dp(3) })
        }
        return cell to value
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            val pad = IntArray(4) // esquerda, topo, direita, base
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                pad[0] = bars.left
                pad[1] = bars.top
                pad[2] = bars.right
                pad[3] = max(bars.bottom, ime.bottom)
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
            insets
        }

        // ---- título + status ----
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        titleRow.addView(tv("⌁", 22f, green, bold = true))
        titleRow.addView(
            tv("REAL PING", 17f, white, bold = true, spacing = 0.14f).apply { setPadding(dp(8), 0, 0, 0) },
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

        // ---- entrada ----
        hostInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "IP ou host  (ex.: 8.8.8.8 -n 10)"
            typeface = Typeface.MONOSPACE
            background = roundRect(panel, 12f, panelStroke)
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
        }
        root.addView(hostInput, lp(-1, dp(50)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        runButton = actionButton("▶  INICIAR", green, Color.rgb(5, 20, 10)) { startPing() }
        stopButton = actionButton("■  PARAR", Color.rgb(58, 34, 34), white, Color.rgb(90, 50, 50)) { stopRun() }
        traceButton = actionButton("TRACE", panel, green, panelStroke) { startTraceRoute() }
        buttons.addView(runButton, lp(0, dp(46), 1.35f))
        buttons.addView(stopButton, lp(0, dp(46), 1f).apply { marginStart = dp(8) })
        buttons.addView(traceButton, lp(0, dp(46), 1f).apply { marginStart = dp(8) })
        root.addView(buttons, lp(-1, -2))

        // ---- gráfico ----
        val chartCard = card()
        val chartHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chartTitle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("LATÊNCIA", 11f, green, bold = true, spacing = 0.1f))
            addView(tv("últimos 60 s", 10f, muted).apply { setPadding(0, dp(1), 0, 0) })
        }
        latencyNow = tv("— ms", 24f, white, bold = true, mono = true)
        chartHeader.addView(chartTitle, lp(0, -2, 1f))
        chartHeader.addView(latencyNow)
        chartCard.addView(chartHeader, lp(-1, -2))

        chart = LatencyChartView(this)
        chartCard.addView(chart, lp(-1, dp(130)).apply { topMargin = dp(6); bottomMargin = dp(8) })

        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (minCell, minV) = statCell("MÍN")
        val (avgCell, avgV) = statCell("MÉD")
        val (maxCell, maxV) = statCell("MÁX")
        val (jitCell, jitV) = statCell("JITTER")
        val (lossCell, lossV) = statCell("PERDA")
        statMin = minV; statAvg = avgV; statMax = maxV; statJitter = jitV; statLoss = lossV
        for (cell in listOf(minCell, avgCell, maxCell, jitCell, lossCell)) statsRow.addView(cell, lp(0, -2, 1f))
        chartCard.addView(statsRow, lp(-1, -2))
        root.addView(chartCard, lp(-1, -2))

        // ---- console ----
        val consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(consoleBg, 16f, panelStroke)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        consoleHeader.addView(tv("CONSOLE", 11f, green, bold = true, spacing = 0.1f), lp(0, -2, 1f))
        consoleHeader.addView(textAction("COPIAR") { copyConsole() })
        consoleHeader.addView(textAction("LIMPAR") { if (mode == Mode.IDLE) clearConsole() })
        consoleCard.addView(consoleHeader, lp(-1, -2))

        scroll = ScrollView(this).apply { isFillViewport = true }
        output = tv("", 12f, green, mono = true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextIsSelectable(true)
            setLineSpacing(dpf(2f), 1f)
        }
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        consoleCard.addView(scroll, lp(-1, 0, 1f))
        root.addView(consoleCard, lp(-1, 0, 1f).apply { topMargin = dp(10) })

        // ---- rodapé de rede ----
        val (gwCell, gwV) = infoCell("GATEWAY")
        val (dnsCell, dnsV) = infoCell("DNS")
        val (ipCell, ipV) = infoCell("IP LOCAL")
        val (netCell, netV) = infoCell("REDE")
        gwValue = gwV; dnsValue = dnsV; ipValue = ipV; netValue = netV

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(gwCell, lp(0, -2, 1f).apply { marginEnd = dp(8) })
        row1.addView(dnsCell, lp(0, -2, 1f))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(ipCell, lp(0, -2, 1f).apply { marginEnd = dp(8) })
        row2.addView(netCell, lp(0, -2, 1f))
        root.addView(row1, lp(-1, -2).apply { topMargin = dp(10) })
        root.addView(row2, lp(-1, -2).apply { topMargin = dp(8) })
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val idle = newMode == Mode.IDLE
        runButton.isEnabled = idle
        traceButton.isEnabled = idle
        stopButton.isEnabled = !idle
        runButton.alpha = if (idle) 1f else 0.4f
        traceButton.alpha = if (idle) 1f else 0.4f
        stopButton.alpha = if (idle) 0.4f else 1f

        val (label, color) = when (newMode) {
            Mode.IDLE -> "PRONTO" to muted
            Mode.PING -> "PING ATIVO" to green
            Mode.TRACE -> "TRACEROUTE" to amber
        }
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.setColor(color)
    }

    private fun updateStats() {
        val s = stats
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
        statJitter.text = if (s.jitterN > 0) "${fmt(s.jitter)}ms" else DASH
        statLoss.text = if (s.sent > 0) "${s.lossPct.roundToInt()}%" else DASH
        statLoss.setTextColor(if (s.lost > 0) red else white)
    }

    private fun fmt(ms: Double): String =
        if (ms < 10) String.format(Locale.US, "%.1f", ms) else ms.roundToInt().toString()

    // =====================================================================
    // Console
    // =====================================================================

    private fun append(line: String, color: Int = green) {
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    private fun post(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    // =====================================================================
    // Rodapé de rede
    // =====================================================================

    private fun scheduleFooter() {
        handler.removeCallbacks(footerRunnable)
        handler.postDelayed(footerRunnable, 300)
    }

    private fun updateNetworkFooter() {
        val info = try {
            readNetworkInfo()
        } catch (_: Exception) {
            NetInfo("N/D", "N/D", "N/D", "Indisponível")
        }
        gwValue.text = info.gateway
        dnsValue.text = info.dns
        ipValue.text = info.ip
        netValue.text = info.type
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
    // Ping
    // =====================================================================

    private fun parseHost(candidate: String): String? =
        candidate.takeIf { it.isNotBlank() && !it.startsWith("-") && it.matches(HOST_REGEX) }

    private fun startPing() {
        if (mode != Mode.IDLE) return

        val raw = hostInput.text.toString().trim()
        val parts = raw.split(WHITESPACE).filter { it.isNotBlank() }

        if (parts.isEmpty()) {
            append("Informe um IP ou host.", amber)
            return
        }

        val host = parseHost(parts[0])
        if (host == null) {
            append("Endereço inválido.", red)
            return
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
                        append("Quantidade inválida.", red)
                        return
                    }
                    val parsed = parts[index + 1].toIntOrNull()
                    if (parsed == null || parsed !in 1..10000) {
                        append("Quantidade deve estar entre 1 e 10000.", red)
                        return
                    }
                    count = parsed
                    countSpecified = true
                    continuous = false
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}", red)
                    return
                }
            }
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_HOST, raw).apply()
        hideKeyboard()

        val args = mutableListOf("/system/bin/ping", "-W", "1", "-s", "32")
        if (!continuous) {
            args.add("-c")
            args.add(count.toString())
        }
        args.add(host)

        append("")
        append("────────────────────────────", muted)
        append("> $raw", white)
        append(if (continuous) "Ping contínuo para $host" else "Ping para $host ($count tentativas)", muted)
        append("")

        stats = PingStats()
        answered.clear()
        nextSeq = 1
        lastSeq = 0
        hardError = false
        slotAnchored = false
        slotStart = System.currentTimeMillis()
        sessionHost = host
        sessionContinuous = continuous
        sessionCount = count
        stopRequested = false

        chart.clear()
        chart.setLive(true)
        updateStats()
        setMode(Mode.PING)
        handler.postDelayed(lossTick, 500)

        executor.execute { runPingProcess(args) }
    }

    private fun runPingProcess(args: List<String>) {
        var error: String? = null
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            activeProcesses.add(process)
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) post { handlePingLine(line) }
                    }
                }
                process.waitFor()
            } finally {
                activeProcesses.remove(process)
                process.destroy()
            }
        } catch (e: Exception) {
            if (!stopRequested) error = e.message ?: "falha ao executar ping"
        }
        post { finishPing(error) }
    }

    private class PingReply(val address: String, val ttl: String, val timeMs: Double, val seq: Int?)

    private fun parseReply(line: String): PingReply? {
        REPLY_STRICT.find(line)?.let { m ->
            val time = m.groupValues[5].toDoubleOrNull() ?: return null
            return PingReply(m.groupValues[2].trim(), m.groupValues[4], time, m.groupValues[3].toIntOrNull())
        }
        REPLY_LOOSE.find(line)?.let { m ->
            val time = m.groupValues[4].toDoubleOrNull() ?: return null
            return PingReply(m.groupValues[2].trim().removeSuffix(":"), m.groupValues[3], time, null)
        }
        return null
    }

    private fun isHardError(lower: String): Boolean =
        lower.contains("unknown host") ||
            lower.contains("cannot resolve") ||
            lower.contains("network is unreachable") ||
            lower.contains("operation not permitted") ||
            lower.contains("name or service not known") ||
            lower.contains("bad address")

    private fun isErrorLine(line: String): Boolean {
        val lower = line.lowercase()
        return isHardError(lower) ||
            lower.contains("destination host unreachable") ||
            lower.contains("connect:") ||
            lower.contains("ping:")
    }

    private fun handlePingLine(line: String) {
        val reply = parseReply(line)
        if (reply == null) {
            if (isErrorLine(line)) {
                if (isHardError(line.lowercase())) hardError = true
                append(line, red)
            }
            return
        }

        val now = System.currentTimeMillis()
        val seq = reply.seq ?: (lastSeq + 1)
        lastSeq = max(lastSeq, seq)

        if (!slotAnchored) {
            slotStart = now - reply.timeMs.toLong() - (seq - 1) * 1000L
            slotAnchored = true
        }
        if (!answered.add(seq)) return // duplicado

        if (seq < nextSeq) stats.lost = max(0, stats.lost - 1) // chegou depois de marcado como perdido

        stats.add(reply.timeMs)
        chart.addSample(now - reply.timeMs.toLong(), reply.timeMs.toFloat())

        val shown = if (reply.timeMs < 1) "<1" else reply.timeMs.roundToInt().toString()
        append("Resposta de ${reply.address}: bytes=32 tempo=${shown}ms TTL=${reply.ttl}")

        checkLosses(false)
        updateStats()
    }

    /** Marca como perdidos os pacotes cujo prazo venceu (ou todos os pendentes, se [force] e for ping finito). */
    private fun checkLosses(force: Boolean) {
        val now = System.currentTimeMillis()
        val limit = if (sessionContinuous) Int.MAX_VALUE else sessionCount
        while (nextSeq <= limit) {
            if (force && sessionContinuous) break
            val sendTime = slotStart + (nextSeq - 1) * 1000L
            if (!force && sendTime + LOSS_TIMEOUT_MS > now) break
            if (nextSeq !in answered) {
                stats.lost++
                stats.last = -1.0
                stats.lastWasLoss = true
                chart.addLoss(sendTime)
                append("Esgotado o tempo limite da solicitação.", amber)
            }
            nextSeq++
        }
    }

    private fun finishPing(error: String?) {
        handler.removeCallbacks(lossTick)
        val clean = !stopRequested && error == null && !hardError
        checkLosses(force = clean)

        if (stats.sent > 0) {
            append("")
            append("Estatísticas do Ping para $sessionHost:", white)
            val s = stats
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
            stopRequested -> append("Interrompido pelo usuário.", muted)
            error != null -> append("Erro: $error", red)
            hardError -> append("Falha ao resolver ou alcançar o destino.", red)
            else -> append("Concluído.", muted)
        }
        append("────────────────────────────", muted)

        chart.setLive(false)
        updateStats()
        setMode(Mode.IDLE)
    }

    private fun stopRun() {
        if (mode == Mode.IDLE) return
        stopRequested = true
        append("Interrompendo...", muted)
        activeProcesses.forEach { it.destroy() }
    }

    // =====================================================================
    // Traceroute
    // =====================================================================

    private fun startTraceRoute() {
        if (mode != Mode.IDLE) return

        val host = parseHost(hostInput.text.toString().trim().split(WHITESPACE).firstOrNull().orEmpty())
        if (host == null) {
            append("Informe um IP ou hostname válido para o traceroute.", amber)
            return
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_HOST, host).apply()
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

    private fun runTrace(host: String) {
        var failure: String? = null
        var reached = false
        var silentHops = 0
        var gaveUp = false

        try {
            val overhead = calibrateOverhead()

            for (ttl in 1..MAX_HOPS) {
                if (stopRequested) break

                val futures = (1..PROBES_PER_HOP).map {
                    probePool.submit(Callable<Probe> { probe(host, ttl, overhead) })
                }
                val probes = futures.map { it.get() }
                if (stopRequested) break

                val hardRaw = probes.firstNotNullOfOrNull { p ->
                    p.raw?.takeIf { isHardError(it.lowercase()) }
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
                val color = when {
                    hopReached -> green
                    addresses.isEmpty() -> amber
                    else -> green
                }
                val rawNote = if (addresses.isEmpty()) probes.firstNotNullOfOrNull { it.raw } else null

                post {
                    append("${ttl.toString().padStart(2)}$cells  $where", color)
                    if (rawNote != null) append("      ↳ $rawNote", muted)
                }

                if (hopReached) {
                    reached = true
                    break
                }

                silentHops = if (addresses.isEmpty()) silentHops + 1 else 0
                if (silentHops >= SILENT_HOPS_LIMIT) {
                    gaveUp = true
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
                gaveUp -> append("Sem resposta nos últimos $SILENT_HOPS_LIMIT saltos; rastreamento encerrado.", amber)
                else -> append("Rastreamento encerrado.", muted)
            }
            append("────────────────────────────", muted)
            setMode(Mode.IDLE)
        }
    }

    /** Custo fixo de abrir o processo do ping, descontado dos tempos medidos quando o roteador não informa `time=`. */
    private fun calibrateOverhead(): Double = try {
        val started = System.nanoTime()
        val process = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "1", "127.0.0.1")
            .redirectErrorStream(true)
            .start()
        activeProcesses.add(process)
        try {
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        } finally {
            activeProcesses.remove(process)
            process.destroy()
        }
        (System.nanoTime() - started) / 1_000_000.0
    } catch (_: Exception) {
        0.0
    }

    private fun isNoise(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("ping ") ||
            lower.startsWith("---") ||
            lower.startsWith("rtt") ||
            lower.startsWith("round-trip") ||
            lower.contains("statistics") ||
            lower.contains("packets transmitted") ||
            (lower.contains("%") && lower.contains("loss"))
    }

    private fun extractAddress(line: String): String? =
        FROM_ADDR.find(line)?.groupValues?.get(1)
            ?.removeSuffix(":")
            ?.takeIf { it.contains('.') || it.contains(':') }
            ?.substringBefore('%')

    private fun probe(host: String, ttl: Int, overheadMs: Double): Probe {
        val args = listOf("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-t", ttl.toString(), host)
        val started = System.nanoTime()
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        activeProcesses.add(process)
        val lines = try {
            process.inputStream.bufferedReader().use { it.readLines() }
        } finally {
            activeProcesses.remove(process)
            process.destroy()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        val reply = lines.map { it.trim() }.filter { it.isNotEmpty() && !isNoise(it) }

        val destination = reply.firstOrNull {
            it.contains("bytes from", ignoreCase = true) && TTL_FIELD.containsMatchIn(it)
        }
        val exceeded = reply.firstOrNull { l -> EXCEEDED.any { l.contains(it, ignoreCase = true) } }
        val anyFrom = reply.firstOrNull { FROM_ADDR.containsMatchIn(it) && extractAddress(it) != null }

        val source = destination ?: exceeded ?: anyFrom
        val address = source?.let { extractAddress(it) }

        if (address == null) return Probe(null, null, false, reply.firstOrNull())

        val reportedTime = source?.let { TIME_FIELD.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val ms = reportedTime ?: (elapsedMs - overheadMs).coerceAtLeast(0.5)
        return Probe(address, ms, destination != null, null)
    }
}
