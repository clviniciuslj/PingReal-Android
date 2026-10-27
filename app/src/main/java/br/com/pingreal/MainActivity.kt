package br.com.pingreal

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
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
    private lateinit var networkFooter: TextView

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
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }

        header.addView(TextView(this).apply {
            text = "⌁"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
        })

        header.addView(TextView(this).apply {
            text = "PING REAL"
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
            hint = "IP ou host  -n 20"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
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
                if (!running) output.text = ""
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

        networkFooter = TextView(this).apply {
            textSize = 10.5f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(0f, 1f)
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
                ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress ?: "—"

            val gateway = props?.routes
                ?.firstOrNull { route ->
                    val destination = route.destination
                    destination.prefixLength == 0 && destination.address.isAnyLocalAddress
                }
                ?.gateway?.hostAddress ?: "—"

            val dns = props?.dnsServers
                ?.firstOrNull { it is java.net.Inet4Address }
                ?.hostAddress ?: props?.dnsServers?.firstOrNull()?.hostAddress ?: "—"

            networkFooter.text = "GW $gateway                 DNS $dns\nIP $ip                 NET $type"
        } catch (_: SecurityException) {
            // O rodapé não deve impedir o aplicativo de abrir se faltar permissão de rede.
            networkFooter.text = "GW N/D                 DNS N/D\nIP N/D                 NET indisponível"
        } catch (_: Exception) {
            // Falha ao consultar a rede não deve derrubar a tela principal.
            networkFooter.text = "GW N/D                 DNS N/D\nIP N/D                 NET indisponível"
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

        var continuous = false
        var count = 4
        var countSpecified = false
        var index = 1

        while (index < parts.size) {
            when (parts[index].lowercase()) {
                "-t" -> {
                    if (continuous) {
                        append("Parâmetro -t repetido.")
                        return
                    }
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
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}")
                    return
                }
            }
        }

        if (continuous && countSpecified) {
            append("Use -t ou -n quantidade, não os dois.")
            return
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

                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            synchronized(capturedLines) { capturedLines.add(line) }

                            val formatted = formatPingLine(line)
                            if (formatted != null) {
                                runOnUiThread { if (!isFinishing && !isDestroyed) append(formatted) }
                            } else if (isErrorLine(line)) {
                                runOnUiThread { if (!isFinishing && !isDestroyed) append(line) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!stopRequested) readError = e.message ?: "falha ao ler a saída do ping"
                }

                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } catch (e: Exception) {
                if (!stopRequested) readError = e.message ?: "falha ao executar ping"
            } finally {
                val snapshot = synchronized(capturedLines) { capturedLines.toList() }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        append("")
                        append("Estatísticas do Ping para $host:")
                        appendSummary(snapshot, continuous, count)
                        if (stopRequested) {
                            append("Interrompido pelo usuário.")
                        } else if (readError != null) {
                            append("Erro: $readError")
                        } else {
                            append("Concluído.")
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

        val host = command.text.toString().trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (host.isBlank() || host.startsWith("-") || !host.matches(Regex("[A-Za-z0-9._:%-]+"))) {
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
        append("Método compatível: sondas ping com TTL crescente.")
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
                        "/system/bin/ping", "-n", "-c", "1", "-W", "1",
                        "-t", ttl.toString(), host
                    )
                    val process = ProcessBuilder(args).redirectErrorStream(true).start()
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
                    try { process.waitFor() } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    pingProcess = null

                    val reply = lines.firstOrNull {
                        it.contains("bytes from", ignoreCase = true) ||
                            it.contains("Time to live exceeded", ignoreCase = true) ||
                            it.contains("Time exceeded", ignoreCase = true)
                    }
                    val hopAddress = reply?.let {
                        Regex("""from\s+([^: ]+)""", RegexOption.IGNORE_CASE)
                            .find(it)?.groupValues?.getOrNull(1)
                    }
                    val time = reply?.let {
                        Regex("""time[=<]([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
                            .find(it)?.groupValues?.getOrNull(1)
                    }

                    val line = when {
                        hopAddress != null && time != null -> "$ttl  $hopAddress  ${time} ms"
                        hopAddress != null -> "$ttl  $hopAddress"
                        else -> "$ttl  *"
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) append(line)
                    }

                    if (reply != null && (
                        reply.contains("bytes from", ignoreCase = true) &&
                            (reply.contains("ttl=", ignoreCase = true) ||
                             reply.contains("ttl ", ignoreCase = true))
                    )) {
                        reachedDestination = true
                        break
                    }
                }
            } catch (e: Exception) {
                if (!stopRequested) failure = e.message ?: "falha ao executar as sondas do traceroute"
            } finally {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (stopRequested) append("Traceroute interrompido pelo usuário.")
                        else if (failure != null) append("Falha no traceroute: $failure")
                        else if (reachedDestination) append("Destino alcançado.")
                        else append("Rastreamento encerrado; saltos sem resposta podem estar filtrados.")
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

    private fun parseReply(line: String): PingReply? {
        // Exemplo Linux: 64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=2.34 ms
        val match = Regex(
            """(\d+)\s+bytes from\s+(.+?):.*?\bttl[= ](\d+).*?\btime[=<]([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        ).find(line) ?: return null

        val address = match.groupValues[2].trim().removeSuffix(":")
        val ttl = match.groupValues[3]
        val time = match.groupValues[4].toDoubleOrNull() ?: return null
        return PingReply(address, ttl, time)
    }

    private data class PingReply(val address: String, val ttl: String, val timeMs: Double)

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

    private fun appendSummary(lines: List<String>, continuous: Boolean, requestedCount: Int) {
        val replies = lines.mapNotNull { parseReply(it)?.timeMs }

        val nativeSummary = lines.firstOrNull { it.contains("packets transmitted") }
        val nativeSent = nativeSummary?.let {
            Regex("""(\d+)\s+packets transmitted""")
                .find(it)?.groupValues?.get(1)?.toIntOrNull()
        }

        val sent = when {
            nativeSent != null -> nativeSent
            !continuous && !stopRequested -> requestedCount
            stopRequested && continuous -> {
                val elapsed = (System.currentTimeMillis() - pingStartedAt).coerceAtLeast(0)
                maxOf(replies.size, (elapsed / 1000L).toInt())
            }
            else -> replies.size
        }

        val received = replies.size
        val lost = (sent - received).coerceAtLeast(0)
        val loss = if (sent > 0) ((lost * 100.0) / sent).roundToInt() else 0

        append("    Pacotes: Enviados = $sent, Recebidos = $received, Perdidos = $lost (${loss}% de perda),")

        if (stopRequested && nativeSent == null && continuous) {
            append("    Nota: quantidade enviada é uma estimativa após a interrupção.")
        }

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

    private fun append(line: String) {
        output.append("$line\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pingProcess?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
