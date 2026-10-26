
package br.com.pingreal

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val bg = Color.rgb(10, 14, 11)
    private val panel = Color.rgb(23, 31, 25)
    private val green = Color.rgb(74, 222, 128)
    private val white = Color.rgb(232, 238, 233)
    private val muted = Color.rgb(145, 160, 150)
    private val red = Color.rgb(248, 113, 113)

    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button

    @Volatile private var pingProcess: Process? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(16))
            setBackgroundColor(bg)
            isFocusableInTouchMode = true
        }

        // Cabeçalho centralizado
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(32))
        }

        val symbol = TextView(this).apply {
            text = "⌁"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(symbol)

        val title = TextView(this).apply {
            text = "PING REAL"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(white)
            typeface = Typeface.create(
                "sans-serif-medium",
                Typeface.NORMAL
            )
            letterSpacing = 0.12f
        }
        header.addView(title)

        val subtitle = TextView(this).apply {
            text = "NETWORK DIAGNOSTICS"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            letterSpacing = 0.14f
            setPadding(0, dp(6), 0, 0)
        }
        header.addView(subtitle)
        root.addView(header)

        val inputLabel = TextView(this).apply {
            text = "ENDEREÇO"
            textSize = 11f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(9))
        }
        root.addView(inputLabel)

        command = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "IP ou host  -n 20"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        }

        root.addView(
            command,
            LinearLayout.LayoutParams(-1, dp(54))
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(10))
        }

        runButton = Button(this).apply {
            text = "INICIAR"
            textSize = 13f
            setTextColor(Color.rgb(5, 20, 10))
            setBackgroundColor(green)
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { startPing() }
        }

        buttons.addView(
            runButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        stopButton = Button(this).apply {
            text = "PARAR"
            textSize = 13f
            setTextColor(white)
            setBackgroundColor(Color.rgb(62, 39, 39))
            isAllCaps = false
            isEnabled = false
            setOnClickListener { stopPing() }
        }

        buttons.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(10)
            }
        )

        root.addView(buttons)

        // Cabeçalho do console e botão de limpar
        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(10))
        }

        val consoleTitle = TextView(this).apply {
            text = "CONSOLE"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
        }

        consoleHeader.addView(
            consoleTitle,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val clearButton = TextView(this).apply {
            text = "↻"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(green)
            contentDescription = "Limpar console"
            setPadding(dp(8), 0, dp(4), dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                output.text = ""
            }
        }

        consoleHeader.addView(
            clearButton,
            LinearLayout.LayoutParams(dp(44), dp(40))
        )

        root.addView(consoleHeader)

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

        scroll.addView(
            output,
            ViewGroup.LayoutParams(-1, -2)
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
        root.requestFocus()
    }

    private fun startPing() {
        if (running) return

        val raw = command.text.toString().trim()
        val parts = raw.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (parts.isEmpty()) {
            append("Informe um IP ou host.")
            return
        }

        val host = parts[0]

        if (host.startsWith("-") ||
            !host.matches(Regex("[A-Za-z0-9._:-]+"))
        ) {
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

        // Fecha o teclado ao iniciar.
        val imm = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager

        imm.hideSoftInputFromWindow(command.windowToken, 0)
        command.clearFocus()

        val args = mutableListOf(
            "/system/bin/ping",
            "-W",
            "1"
        )

        if (!continuous) {
            args.add("-c")
            args.add(count.toString())
        }

        args.add(host)

        append("")
        append("────────────────────────────")
        append("> $raw")
        append(
            if (continuous) {
                "Ping contínuo para $host"
            } else {
                "Ping para $host ($count tentativas)"
            }
        )
        append("")

        running = true
        stopRequested = false
        runButton.isEnabled = false
        stopButton.isEnabled = true

        executor.execute {
            var process: Process? = null
            val capturedLines = mutableListOf<String>()

            try {
                process = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()

                pingProcess = process

                BufferedReader(
                    InputStreamReader(process.inputStream)
                ).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        capturedLines.add(line)

                        runOnUiThread {
                            append(line)
                        }
                    }
                }

                val exitCode = process.waitFor()

                runOnUiThread {
                    append("")
                    append("──────── RESUMO ────────")
                    appendSummary(capturedLines)
                    append(
                        when {
                            stopRequested -> "Interrompido."
                            exitCode == 0 -> "Concluído."
                            else -> "Finalizado (código $exitCode)."
                        }
                    )
                    append("────────────────────────")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    append("Erro: ${e.message ?: "falha ao executar ping"}")
                }
            } finally {
                pingProcess = null
                running = false

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                    }
                }
            }
        }
    }

    private fun appendSummary(lines: List<String>) {
        val packetLine = lines.firstOrNull {
            it.contains("packets transmitted") ||
                it.contains("pacotes transmitidos")
        }

        if (packetLine != null) {
            val transmitted = Regex("""(\d+)\s+packets transmitted""")
                .find(packetLine)?.groupValues?.get(1)
                ?: Regex("""(\d+)\s+pacotes transmitidos""")
                    .find(packetLine)?.groupValues?.get(1)

            val received = Regex("""(\d+)\s+(?:packets )?received""")
                .find(packetLine)?.groupValues?.get(1)
                ?: Regex("""(\d+)\s+recebidos?""")
                    .find(packetLine)?.groupValues?.get(1)

            val loss = Regex("""([\d.]+)%\s*packet loss""")
                .find(packetLine)?.groupValues?.get(1)
                ?: Regex("""([\d.]+)%\s+de perda""")
                    .find(packetLine)?.groupValues?.get(1)

            if (transmitted != null) append("Enviados: $transmitted")
            if (received != null) append("Recebidos: $received")
            if (loss != null) append("Perda: $loss%")
        }

        val latencyLine = lines.firstOrNull {
            it.contains("min/avg/max") ||
                it.contains("round-trip")
        }

        if (latencyLine != null) {
            val values = Regex("""=\s*([\d.]+)/([\d.]+)/([\d.]+)""")
                .find(latencyLine)

            if (values != null) {
                append("Mínima: ${values.groupValues[1]} ms")
                append("Média: ${values.groupValues[2]} ms")
                append("Máxima: ${values.groupValues[3]} ms")
            }
        }

        if (packetLine == null && latencyLine == null) {
            append("Resumo estatístico indisponível.")
        }
    }

    private fun stopPing() {
        val process = pingProcess

        if (process != null) {
            stopRequested = true
            append("Solicitando interrupção...")
            process.destroy()
        }
    }

    private fun append(line: String) {
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
