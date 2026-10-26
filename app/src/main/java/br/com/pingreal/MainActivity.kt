
package br.com.pingreal

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
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

    private val bg = Color.rgb(10, 14, 11)
    private val panel = Color.rgb(24, 31, 26)
    private val green = Color.rgb(91, 255, 143)
    private val white = Color.rgb(235, 245, 237)
    private val muted = Color.rgb(130, 150, 135)

    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button

    @Volatile
    private var process: Process? = null

    @Volatile
    private var running = false

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(bg)
        }

        val title = TextView(this).apply {
            text = "PING REAL"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(24))
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(TextView(this).apply {
            text = "ENDEREÇO IP OU HOST"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(6))
        })

        command = EditText(this).apply {
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "Ex.: 8.8.8.8"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
        }

        root.addView(
            command,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }

        runButton = makeButton("EXECUTAR", green, Color.BLACK) {
            startPing()
        }

        stopButton = makeButton(
            "PARAR",
            Color.rgb(62, 39, 39),
            white
        ) {
            stopPing()
        }

        stopButton.isEnabled = false

        buttons.addView(
            runButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        buttons.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        )

        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = "CONSOLE"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(8))
        })

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(4, 8, 5))
            isFillViewport = true
        }

        output = TextView(this).apply {
            textSize = 12.5f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            text = "Ping Real iniciado.\nAguardando comando...\n"
        }

        scroll.addView(output)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun makeButton(
        label: String,
        background: Int,
        foreground: Int,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setBackgroundColor(background)
            setTextColor(foreground)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { action() }
        }
    }

    private fun startPing() {
        if (running) return

        val host = command.text.toString().trim()

        if (host.isBlank() ||
            !host.matches(Regex("[A-Za-z0-9._:%-]+")) ||
            host.startsWith("-")
        ) {
            appendOutput("Informe um IP ou hostname válido.\n")
            return
        }

        running = true
        runButton.isEnabled = false
        stopButton.isEnabled = true

        appendOutput("\n> ping $host\n")
        appendOutput("Iniciando teste de conectividade...\n\n")

        executor.execute {
            try {
                val newProcess = ProcessBuilder(
                    "ping",
                    "-c",
                    "1000000",
                    host
                )
                    .redirectErrorStream(true)
                    .start()

                process = newProcess

                BufferedReader(
                    InputStreamReader(newProcess.inputStream)
                ).use { reader ->
                    var line: String?

                    while (running && reader.readLine().also {
                            line = it
                        } != null
                    ) {
                        val currentLine = line ?: continue
                        appendOutput(currentLine + "\n")
                    }
                }

                if (running) {
                    appendOutput("\nTeste finalizado.\n")
                }
            } catch (e: Exception) {
                appendOutput(
                    "\nErro ao executar ping: ${e.message}\n"
                )
            } finally {
                process = null
                running = false

                runOnUiThread {
                    runButton.isEnabled = true
                    stopButton.isEnabled = false
                }
            }
        }
    }

    private fun stopPing() {
        running = false

        try {
            process?.destroy()
        } catch (_: Exception) {
        }

        appendOutput("\n> Teste interrompido pelo usuário.\n")

        runButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun appendOutput(message: String) {
        runOnUiThread {
            output.append(message)
            scroll.post {
                scroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        running = false

        try {
            process?.destroy()
        } catch (_: Exception) {
        }

        executor.shutdownNow()
        super.onDestroy()
    }
}
