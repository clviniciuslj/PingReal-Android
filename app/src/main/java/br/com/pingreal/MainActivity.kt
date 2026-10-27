package br.com.pingreal

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 11, 10)
    private val panel = Color.rgb(20, 27, 23)
    private val green = Color.rgb(74, 222, 128)
    private val white = Color.rgb(224, 231, 226)
    private val muted = Color.rgb(137, 153, 143)

    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button

    @Volatile private var pingProcess: Process? = null
    @Volatile private var running = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        append("PING REAL / ANDROID")
        append("Digite um IP ou hostname, por exemplo: 8.8.8.8")
        append("O ping será executado pelo Android; alguns aparelhos podem não disponibilizar o comando.")
        append("----------------------------------------")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(bg)
        }

        val title = TextView(this).apply {
            text = "PING REAL"
            textSize = 24f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Teste de conectividade • execução local"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(14))
        })

        command = EditText(this).apply {
            setSingleLine(true)
            setText("8.8.8.8")
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "Endereço IP ou hostname"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
        }
        root.addView(command, LinearLayout.LayoutParams(-1, dp(52)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        runButton = Button(this).apply {
            text = "▶  EXECUTAR"
            setTextColor(Color.BLACK)
            setBackgroundColor(green)
            isAllCaps = false
            setOnClickListener { startPing() }
        }
        stopButton = Button(this).apply {
            text = "■  PARAR"
            setTextColor(white)
            setBackgroundColor(Color.rgb(73, 35, 35))
            isAllCaps = false
            isEnabled = false
            setOnClickListener { stopPing() }
        }
        buttons.addView(runButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(buttons)

        root.addView(TextView(this).apply {
            text = "CONSOLE"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        })

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
        }
        scroll.addView(output, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startPing() {
        if (running) return

        val host = command.text.toString().trim()
        if (host.isEmpty() || host.startsWith("-") ||
            !host.matches(Regex("[A-Za-z0-9._:%-]+"))) {
            append("Erro: informe um IP ou hostname válido.")
            return
        }

        running = true
        runButton.isEnabled = false
        stopButton.isEnabled = true
        append("")
        append("> ping $host")
        append("Executando ping contínuo. Pressione PARAR para interromper.")

        executor.execute {
            var localProcess: Process? = null
            try {
                // Android normally exposes ping in /system/bin; use PATH lookup for compatibility.
                localProcess = ProcessBuilder("ping", "-W", "1", host)
                    .redirectErrorStream(true)
                    .start()
                pingProcess = localProcess

                BufferedReader(InputStreamReader(localProcess.inputStream)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        append(line)
                    }
                }
                if (running) append("Processo de ping encerrado.")
            } catch (e: Exception) {
                append("Não foi possível executar ping: ${e.message ?: e.javaClass.simpleName}")
                append("O Android deste aparelho pode não disponibilizar o executável ping.")
            } finally {
                try { localProcess?.destroy() } catch (_: Exception) { }
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

    private fun stopPing() {
        running = false
        try { pingProcess?.destroy() } catch (_: Exception) { }
        runButton.isEnabled = true
        stopButton.isEnabled = false
        append("Ping interrompido.")
    }

    private fun append(message: String) {
        runOnUiThread {
            if (!::output.isInitialized || !::scroll.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            output.append(message)
            output.append("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        try { pingProcess?.destroy() } catch (_: Exception) { }
        executor.shutdownNow()
        super.onDestroy()
    }
}
