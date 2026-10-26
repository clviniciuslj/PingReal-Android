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
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 11, 10)
    private val green = Color.rgb(74, 222, 128)
    private val white = Color.rgb(224, 231, 226)
    private val muted = Color.rgb(137, 153, 143)
    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private var pingProcess: Process? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        append("PING REAL / ANDROID")
        append("Digite: ping 172.16.1.1")
        append("Para manter contínuo: ping 172.16.1.1 -t")
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
            textSize = 22f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        val subtitle = TextView(this).apply {
            text = "Teste de conectividade • execução local"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(14))
        }
        root.addView(subtitle)

        command = EditText(this).apply {
            setSingleLine(true)
            setText("ping 172.16.1.1")
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            setHint("ping endereço -t")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(20, 27, 23))
            typeface = Typeface.MONOSPACE
        }
        root.addView(command, LinearLayout.LayoutParams(-1, dp(52)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        runButton = Button(this).apply {
            text = "EXECUTAR"
            setTextColor(Color.BLACK)
            setBackgroundColor(green)
            isAllCaps = false
            setOnClickListener { startPing() }
        }
        buttons.addView(runButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        val stopButton = Button(this).apply {
            text = "PARAR"
            setTextColor(white)
            setBackgroundColor(Color.rgb(73, 35, 35))
            isAllCaps = false
            setOnClickListener { stopPing() }
        }
        val stopParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
        buttons.addView(stopButton, stopParams)
        root.addView(buttons)

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
        val raw = command.text.toString().trim()
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty() || !parts[0].equals("ping", true)) {
            append("Erro: use o formato ping ENDEREÇO [ -t ]")
            return
        }
        val host = parts.drop(1).firstOrNull { !it.startsWith("-") }
        if (host.isNullOrBlank() || !host.matches(Regex("[A-Za-z0-9._:-]+"))) {
            append("Erro: endereço IP ou nome de host inválido.")
            return
        }
        val continuous = parts.any { it.equals("-t", true) }
        val args = mutableListOf("/system/bin/ping", "-W", "1")
        if (!continuous) {
            args.add("-c")
            args.add("4")
        }
        args.add(host)
        append("")
        append("> $raw")
        append("Executando ping real para $host${if (continuous) " (contínuo; pressione PARAR)" else " (4 tentativas)"}...")
        running = true
        runButton.isEnabled = false
        executor.execute {
            try {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                pingProcess = process
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val current = line ?: continue
                        runOnUiThread { append(current) }
                    }
                }
                val code = process.waitFor()
                runOnUiThread { append("Processo encerrado (código $code).") }
            } catch (e: Exception) {
                runOnUiThread {
                    append("Falha ao executar ping: ${e.message ?: e.javaClass.simpleName}")
                    append("Este Android pode não disponibilizar /system/bin/ping.")
                }
            } finally {
                pingProcess = null
                running = false
                runOnUiThread { runButton.isEnabled = true }
            }
        }
    }

    private fun stopPing() {
        val p = pingProcess
        if (p != null) {
            append("^C — interrompendo ping...")
            p.destroy()
            try { p.descendants().forEach { it.destroyForcibly() } } catch (_: Exception) { }
        } else {
            append("Nenhum ping em execução.")
        }
    }

    private fun append(line: String) {
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        pingProcess?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
