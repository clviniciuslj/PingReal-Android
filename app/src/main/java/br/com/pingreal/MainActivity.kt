
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

        append("PING REAL")
        append("Pronto para testar a conexão.")
        append("----------------------------------------")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(14))
            setBackgroundColor(bg)
        }

        // Cabeçalho com espaçamento próprio
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(22))
        }

        val title = TextView(this).apply {
            text = "PING REAL"
            textSize = 25f
            setTextColor(green)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = true
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            text = "Teste de conectividade de rede"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(header)

        val inputLabel = TextView(this).apply {
            text = "ENDEREÇO IP"
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
            hint = "Ex.: 172.16.1.1 -t"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        root.addView(
            command,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        val help = TextView(this).apply {
            text = "IP • -t contínuo • -n 10 para 10 tentativas"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(14))
        }
        root.addView(help)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        runButton = Button(this).apply {
            text = "INICIAR PING"
            textSize = 14f
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
            textSize = 14f
            setTextColor(white)
            setBackgroundColor(Color.rgb(65, 37, 37))
            isAllCaps = false
            setOnClickListener { stopPing() }
        }

        buttons.addView(
            stopButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(10)
            }
        )

        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        )

        val outputTitle = TextView(this).apply {
            text = "RESULTADOS"
            textSize = 12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(outputTitle)

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
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

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

    private fun startPing() {
        if (running) return

        val raw = command.text.toString().trim()
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (parts.isEmpty()) {
            append("Digite um endereço IP ou nome de host.")
            return
        }

        val host = parts[0]

        if (host.startsWith("-") ||
            !host.matches(Regex("[A-Za-z0-9._:-]+"))
        ) {
            append("Endereço inválido. Ex.: 172.16.1.1")
            return
        }

        var continuous = false
        var count = 4
        var countSpecified = false
        var i = 1

        while (i < parts.size) {
            when (parts[i].lowercase()) {
                "-t" -> {
                    continuous = true
                    i++
                }

                "-n", "-c" -> {
                    if (i + 1 >= parts.size) {
                        append("Informe a quantidade. Ex.: $host -n 10")
                        return
                    }

                    val value = parts[i + 1].toIntOrNull()

                    if (value == null || value !in 1..10000) {
                        append("A quantidade deve estar entre 1 e 10000.")
                        return
                    }

                    count = value
                    countSpecified = true
                    i += 2
                }

                else -> {
                    append("Opção desconhecida: ${parts[i]}")
                    append("Use apenas o IP, -t ou -n quantidade.")
                    return
                }
            }
        }

        if (continuous && countSpecified) {
            append("Use -t para contínuo ou -n quantidade para tentativas limitadas.")
            return
        }

        val args = mutableListOf("/system/bin/ping", "-W", "1")

        if (!continuous) {
            args.add("-c")
            args.add(count.toString())
        }

        args.add(host)

        append("")
        append("> ping $raw")
        append(
            if (continuous) {
                "Ping contínuo para $host. Pressione PARAR para interromper."
            } else {
                "Enviando $count tentativas para $host..."
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
                        runOnUiThread { append(line) }
                    }
                }

                val exitCode = process.waitFor()

                runOnUiThread {
                    append("Ping encerrado (código $exitCode).")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    append("Erro ao executar ping: ${e.message ?: e.javaClass.simpleName}")
                    append("Verifique se /system/bin/ping está disponível neste Android.")
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
            append("Solicitando interrupção do ping...")
            process.destroy()
        } else if (running) {
            append("Aguardando o processo de ping iniciar para interrompê-lo.")
        } else {
            append("Nenhum ping em execução.")
        }
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
