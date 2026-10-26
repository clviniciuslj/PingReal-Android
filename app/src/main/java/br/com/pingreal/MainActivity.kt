
package br.com.pingreal

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val bg = Color.rgb(8, 12, 10)
    private val panel = Color.rgb(20, 28, 23)
    private val green = Color.rgb(74, 222, 128)
    private val white = Color.rgb(232, 238, 233)
    private val muted = Color.rgb(145, 160, 150)

    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button

    @Volatile private var pingProcess: Process? = null
    @Volatile private var running = false

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
            setPadding(dp(18), dp(22), dp(18), dp(14))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(26))
        }

        val title = TextView(this).apply {
            text = "PING REAL"
            textSize = 25f
            setTextColor(green)
            typeface = Typeface.create(
                "sans-serif-medium",
                Typeface.NORMAL
            )
        }
        header.addView(title)

        val subtitle = TextView(this).apply {
            text = "Teste de conectividade de rede"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(subtitle)
        root.addView(header)

        val inputLabel = TextView(this).apply {
            text = "ENDEREÇO"
            textSize = 12f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(inputLabel)

        command = EditText(this).apply {
            setSingleLine(true)
            setText("172.16.1.1")
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "IP ou host -n 20"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        root.addView(
            command,
            LinearLayout.LayoutParams(-1, dp(54))
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
        }

        runButton = Button(this).apply {
            text = "INICIAR"
            setTextColor(Color.rgb(5, 20, 10))
            setBackgroundColor(green)
            isAllCaps = false
            setOnClickListener { startPing() }
        }

        buttons.addView(
            runButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val stopButton = Button(this).apply {
            text = "PARAR"
            setTextColor(white)
            setBackgroundColor(Color.rgb(65, 37, 37))
            isAllCaps = false
            setOnClickListener { stopPing() }
        }

        buttons.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        )

        root.addView(buttons)

        val consoleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }

        val consoleTitle = TextView(this).apply {
            text = "CONSOLE"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
        }

        consoleHeader.addView(
            consoleTitle,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val clearButton = Button(this).apply {
            text = "LIMPAR"
            textSize = 12f
            setTextColor(white)
            setBackgroundColor(panel)
            isAllCaps = false
            setOnClickListener {
                output.text = ""
            }
        }

        consoleHeader.addView(
            clearButton,
            LinearLayout.LayoutParams(-2, dp(40))
        )

        root.addView(consoleHeader)

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(3, 6, 4))
            isFillViewport = true
        }

        output = TextView(this).apply {
            textSize = 12.5f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
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
                    continuous = true
                    index++
                }

                "-n", "-c" -> {
                    if (countSpecified || index + 1 >= parts.size) {
                        append("Parâmetro de quantidade inválido.")
                        return
                    }

                    val parsedCount =
                        parts[index + 1].toIntOrNull()

                    if (parsedCount == null ||
                        parsedCount !in 1..10000
                    ) {
                        append("Quantidade inválida.")
                        return
                    }

                    count = parsedCount
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
            append("Use -t ou -n quantidade.")
            return
        }

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
        append("> $raw")
        append(
            if (continuous) {
                "Ping contínuo: $host"
            } else {
                "Ping: $host ($count tentativas)"
            }
        )

        running = true
        runButton.isEnabled = false

        executor.execute {
            var process: Process? = null

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
                        runOnUiThread {
                            append(line)
                        }
                    }
                }

                val exitCode = process.waitFor()

                runOnUiThread {
                    append("Finalizado (código $exitCode).")
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
                    }
                }
            }
        }
    }

    private fun stopPing() {
        val process = pingProcess

        if (process != null) {
            process.destroy()
            append("Ping interrompido.")
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
