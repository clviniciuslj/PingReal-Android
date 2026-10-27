
package br.com.pingreal

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val bg = Color.rgb(10, 14, 11)
    private val panel = Color.rgb(23, 31, 25)
    private val green = Color.rgb(74, 222, 128)
    private val white = Color.rgb(232, 238, 233)
    private val muted = Color.rgb(145, 160, 150)

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var traceButton: Button
    private lateinit var networkFooter: LinearLayout

    @Volatile private var pingProcess: Process? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    @Volatile private var pingStartedAt = 0L

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildShell()
        showPingScreen()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(34), dp(20), dp(16))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }

        header.addView(TextView(this).apply {
            text = "⌁"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
        })

        header.addView(TextView(this).apply {
            text = "REAL PING"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
        })

        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showPingScreen() {
        content.removeAllViews()

        content.addView(TextView(this).apply {
            text = "ENDEREÇO"
            textSize = 11f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(8))
        })

        command = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "IP ou host"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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

        content.addView(command, LinearLayout.LayoutParams(-1, dp(52)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }

        runButton = Button(this).apply {
            text = "INICIAR"
            isAllCaps = false
            setBackgroundColor(green)
            setTextColor(Color.rgb(5, 20, 10))
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { startPing() }
        }

        stopButton = Button(this).apply {
            text = "PARAR"
            isAllCaps = false
            setBackgroundColor(Color.rgb(62, 39, 39))
            setTextColor(white)
            isEnabled = false
            setOnClickListener { stopPing() }
        }

        buttons.addView(runButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginStart = dp(8)
        })
        content.addView(buttons)

        traceButton = Button(this).apply {
            text = "TRACEROUTE"
            isAllCaps = false
            setBackgroundColor(panel)
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { startTraceRoute() }
        }

        content.addView(traceButton, LinearLayout.LayoutParams(-1, dp(42)).apply {
            topMargin = dp(2)
            bottomMargin = dp(4)
        })

        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }

        consoleHeader.addView(TextView(this).apply {
            text = "CONSOLE"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        consoleHeader.addView(TextView(this).apply {
            text = "↻"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(green)
            contentDescription = "Limpar console"
            isClickable = true
            setOnClickListener {
                if (!running) clearConsole()
            }
        }, LinearLayout.LayoutParams(dp(44), dp(40)))

        content.addView(consoleHeader)

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(4, 8, 5))
            isFillViewport = true
        }

        output = TextView(this).apply {
            textSize = 12.5f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        networkFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(14), dp(2), dp(2))
        }

        content.addView(networkFooter, LinearLayout.LayoutParams(-1, -2))
        updateNetworkFooter()
    }

    override fun onResume() {
        super.onResume()
        if (::networkFooter.isInitialized) updateNetworkFooter()
    }

    private fun updateNetworkFooter() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val props = network?.let { cm.getLinkProperties(it) }

            val type = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Móvel"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                else -> "—"
            }

            val ip = props?.linkAddresses
                ?.firstOrNull {
                    it.address is java.net.Inet4Address &&
                            !it.address.isLoopbackAddress
                }?.address?.hostAddress ?: "—"

            val gateway = props?.routes
                ?.firstOrNull { route ->
                    val destination = route.destination
                    destination.prefixLength == 0 &&
                            destination.address.isAnyLocalAddress &&
                            route.gateway is java.net.Inet4Address
                }?.gateway?.hostAddress ?: "—"

            val dns = props?.dnsServers
                ?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                ?: props?.dnsServers?.firstOrNull()?.hostAddress ?: "—"

            renderNetworkFooter(gateway, dns, ip, type)
        } catch (_: Exception) {
            renderNetworkFooter("N/D", "N/D", "N/D", "Indisponível")
        }
    }

    private fun renderNetworkFooter(
        gateway: String,
        dns: String,
        ip: String,
        type: String
    ) {
        if (!::networkFooter.isInitialized) return
        networkFooter.removeAllViews()

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        row1.addView(
            networkInfoCell("GW", gateway),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }
        )
        row1.addView(networkInfoCell("DNS", dns), LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(
            networkInfoCell("IP LOCAL", ip),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }
        )
        row2.addView(networkInfoCell("REDE", type), LinearLayout.LayoutParams(0, -2, 1f))

        networkFooter.addView(row1, LinearLayout.LayoutParams(-1, -2))
        networkFooter.addView(row2, LinearLayout.LayoutParams(-1, -2))
    }

    private fun networkInfoCell(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(dp(10), dp(10), dp(8), dp(10))

            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 10f
                setTextColor(muted)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
            })

            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 11f
                setTextColor(green)
                typeface = Typeface.MONOSPACE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun startPing() {
        if (running) return

        val raw = command.text.toString().trim()
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (parts.isEmpty()) {
            append("Informe um IP ou host.")
            return
        }

        val host = parts[0]
        if (host.startsWith("-") || !host.matches(Regex("[A-Za-z0-9._:%-]+"))) {
            append("Endereço inválido.")
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
                        append("Quantidade inválida.")
                        return
                    }
                    val parsed = parts[index + 1].toIntOrNull()
                    if (parsed == null || parsed !in 1..10000) {
                        append("Quantidade deve estar entre 1 e 10000.")
                        return
                    }
                    count = parsed
                    countSpecified = true
                    continuous = false
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}")
                    return
                }
            }
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(command.windowToken, 0)
        command.clearFocus()

        val args = mutableListOf("/system/bin/ping", "-W", "1", "-s", "32")
        if (!continuous) {
            args.add("-c")
            args.add(count.toString())
        }
        args.add(host)

        append("")
        append("────────────────────────────")
        append("> $raw")
        append(if (continuous) "Ping contínuo para $host" else "Ping para $host ($count tentativas)")
        append("")

        running = true
        stopRequested = false
        pingStartedAt = System.currentTimeMillis()
        runButton.isEnabled = false
        stopButton.isEnabled = true
        traceButton.isEnabled = false

        executor.execute {
            var process: Process? = null
            val capturedLines = mutableListOf<String>()
            var readError: String? = null

            try {
                process = ProcessBuilder(args).redirectErrorStream(true).start()
                pingProcess = process

                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(capturedLines) { capturedLines.add(line) }

                        val formatted = formatPingLine(line)
                        if (formatted != null) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) append(formatted)
                            }
                        } else if (isErrorLine(line)) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) append(line)
                            }
                        }
                    }
                }
                process.waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!stopRequested) readError = "execução interrompida"
            } catch (e: Exception) {
                if (!stopRequested) readError = e.message ?: "falha ao executar ping"
            } finally {
                val snapshot = synchronized(capturedLines) { capturedLines.toList() }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        append("")
                        append("Estatísticas do Ping para $host:")
                        appendSummary(snapshot, continuous, count)

                        when {
                            stopRequested -> append("Interrompido pelo usuário.")
                            readError != null -> append("Erro: $readError")
                            else -> append("Concluído.")
                        }
                        append("────────────────────────────")
                    }
                }

                pingProcess = null
                running = false

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        traceButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun startTraceRoute() {
        if (running) return

        val host = command.text.toString().trim()
            .split(Regex("\\s+"))
            .firstOrNull().orEmpty()

        if (host.isBlank() || host.startsWith("-") ||
            !host.matches(Regex("[A-Za-z0-9._:%-]+"))
        ) {
            append("Informe um IP ou hostname válido para o traceroute.")
            return
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(command.windowToken, 0)
        command.clearFocus()

        append("")
        append("────────────────────────────")
        append("> traceroute $host")
        append("Rastreando rota para $host (máximo de 20 saltos)...")
        append("")

        running = true
        stopRequested = false
        runButton.isEnabled = false
        stopButton.isEnabled = true
        traceButton.isEnabled = false

        executor.execute {
            var failure: String? = null
            var reachedDestination = false

            try {
                for (ttl in 1..20) {
                    if (stopRequested || Thread.currentThread().isInterrupted) break

                    val args = listOf(
                        "/system/bin/ping",
                        "-n", "-c", "1", "-W", "2",
                        "-t", ttl.toString(), host
                    )

                    val process = ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .start()

                    pingProcess = process
                    val lines = mutableListOf<String>()

                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (true) {
                            if (stopRequested) {
                                process.destroy()
                                break
                            }

                            val line = reader.readLine() ?: break
                            if (line.isNotBlank()) lines.add(line)
                        }
                    }

                    try {
                        process.waitFor()
                    } catch (_: InterruptedException) {
                        process.destroy()
                        Thread.currentThread().interrupt()
                    }

                    pingProcess = null

                    if (stopRequested || Thread.currentThread().isInterrupted) break

                    val ttlReply = lines.firstOrNull {
                        it.contains("time to live exceeded", ignoreCase = true) ||
                        it.contains("ttl exceeded", ignoreCase = true) ||
                        it.contains("time exceeded", ignoreCase = true)
                    }

                    val destinationReply = lines.firstOrNull {
                        it.contains("bytes from", ignoreCase = true) &&
                        Regex("""\bttl[= ]\d+""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(it)
                    }

                    val reply = ttlReply ?: destinationReply

                    val hopAddress = reply?.let {
                        Regex(
                            """from\s+(\d{1,3}(?:\.\d{1,3}){3})""",
                            RegexOption.IGNORE_CASE
                        ).find(it)?.groupValues?.getOrNull(1)
                    }

                    val time = reply?.let {
                        Regex(
                            """time[=<]\s*([\d.]+)\s*ms""",
                            RegexOption.IGNORE_CASE
                        ).find(it)?.groupValues?.getOrNull(1)
                    }

                    val line = when {
                        hopAddress != null && time != null ->
                            "$ttl   $hopAddress   ${time} ms"
                        hopAddress != null ->
                            "$ttl   $hopAddress"
                        else ->
                            "$ttl   *"
                    }

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) append(line)
                    }

                    if (destinationReply != null) {
                        reachedDestination = true
                        break
                    }
                }
            } catch (e: Exception) {
                if (!stopRequested) {
                    failure = e.message ?: "falha ao executar as sondagens do traceroute"
                }
            } finally {
                pingProcess = null

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        when {
                            stopRequested -> append("Traceroute interrompido pelo usuário.")
                            failure != null -> append("Falha no traceroute: $failure")
                            reachedDestination -> append("Destino alcançado.")
                            else -> append("Rastreamento encerrado.")
                        }

                        append("────────────────────────────")
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        traceButton.isEnabled = true
                    }
                    running = false
                }
            }
        }
    }

    private fun parseReply(line: String): PingReply? {
        val match = Regex(
            """(\d+)\s+bytes from\s+(.+?):.*?\bttl[= ](\d+).*?\btime[=<]([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        ).find(line) ?: return null

        val address = match.groupValues[2].trim().removeSuffix(":")
        val ttl = match.groupValues[3]
        val time = match.groupValues[4].toDoubleOrNull() ?: return null

        return PingReply(address, ttl, time)
    }

    private data class PingReply(
        val address: String,
        val ttl: String,
        val timeMs: Double
    )

    private fun formatPingLine(line: String): String? {
        val reply = parseReply(line) ?: return null
        return "Resposta de ${reply.address}: bytes=32 tempo=${reply.timeMs.roundToInt()}ms TTL=${reply.ttl}"
    }

    private fun isErrorLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("unknown host") ||
                lower.contains("cannot resolve") ||
                lower.contains("network is unreachable") ||
                lower.contains("destination host unreachable") ||
                lower.contains("operation not permitted") ||
                lower.contains("name or service not known") ||
                lower.contains("bad address") ||
                lower.contains("100% packet loss") ||
                lower.contains("connect:") ||
                lower.contains("ping:")
    }

    private fun appendSummary(
        lines: List<String>,
        continuous: Boolean,
        requestedCount: Int
    ) {
        val replies = lines.mapNotNull { parseReply(it)?.timeMs }
        val nativeSummary = lines.firstOrNull {
            it.contains("packets transmitted")
        }
        val nativeSent = nativeSummary?.let {
            Regex("""(\d+)\s+packets transmitted""")
                .find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        val sent = when {
            nativeSent != null -> nativeSent
            !continuous && !stopRequested -> requestedCount
            stopRequested && continuous -> {
                val elapsed = (System.currentTimeMillis() - pingStartedAt)
                    .coerceAtLeast(0)
                maxOf(replies.size, (elapsed / 1000L).toInt())
            }
            else -> replies.size
        }

        val received = replies.size
        val lost = (sent - received).coerceAtLeast(0)
        val loss = if (sent > 0) {
            ((lost * 100.0) / sent).roundToInt()
        } else {
            0
        }

        append("    Pacotes: Enviados = $sent, Recebidos = $received, Perdidos = $lost (${loss}% de perda),")

        if (replies.isNotEmpty()) {
            append(
                "    Mínimo = ${replies.minOrNull()!!.roundToInt()}ms, " +
                "Máximo = ${replies.maxOrNull()!!.roundToInt()}ms, " +
                "Média = ${replies.average().roundToInt()}ms"
            )
        } else {
            append("    Nenhuma resposta válida recebida.")
        }
    }

    private fun stopPing() {
        val process = pingProcess ?: return
        stopRequested = true
        append("Interrompendo ping e calculando resultados...")
        process.destroy()
    }

    private fun clearConsole() {
        output.text = ""
    }

    private fun append(line: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) append(line)
            }
            return
        }

        output.append("$line\n")
        scroll.post {
            scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pingProcess?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
