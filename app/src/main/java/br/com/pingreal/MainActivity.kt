
package br.com.pingreal

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
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

    private lateinit var command: EditText
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var traceButton: Button
    private lateinit var networkFooter: TextView

    @Volatile private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false

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

        root.addView(TextView(this).apply {
            text = "PING REAL"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(20))
        })

        root.addView(TextView(this).apply {
            text = "ENDEREÇO"
            textSize = 11f
            setTextColor(muted)
        })

        command = EditText(this).apply {
            singleLine = true
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "IP ou host"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(panel)
            typeface = Typeface.MONOSPACE
        }
        root.addView(command, LinearLayout.LayoutParams(-1, dp(52)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }

        runButton = makeButton("INICIAR", green, Color.BLACK) {
            startCommand(false)
        }
        stopButton = makeButton("PARAR", Color.rgb(62, 39, 39), white) {
            stopCommand()
        }
        stopButton.isEnabled = false

        buttons.addView(runButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(buttons)

        traceButton = makeButton("TRACEROUTE", panel, green) {
            startCommand(true)
        }
        root.addView(traceButton, LinearLayout.LayoutParams(-1, dp(44)))

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
        }

        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        networkFooter = TextView(this).apply {
            textSize = 10f
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(networkFooter)

        setContentView(root)
        updateNetworkFooter()
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

    private fun startCommand(traceroute: Boolean) {
        if (running) return

        val host = command.text.toString().trim().split(Regex("\\s+")).firstOrNull().orEmpty()

        if (host.isBlank() || host.startsWith("-") ||
            !host.matches(Regex("[A-Za-z0-9._:%-]+"))
        ) {
            append("Informe um IP ou hostname válido.")
            return
        }

        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(command.windowToken, 0)
        command.clearFocus()

        val args = if (traceroute) {
            listOf("/system/bin/traceroute", "-m", "20", "-w", "1", host)
        } else {
            listOf("/system/bin/ping", "-W", "1", "-c", "4", host)
        }

        append("")
        append("> ${args.joinToString(" ")}")

        running = true
        stopRequested = false
        runButton.isEnabled = false
        traceButton.isEnabled = false
        stopButton.isEnabled = true

        executor.execute {
            var error: String? = null
            try {
                val child = ProcessBuilder(args).redirectErrorStream(true).start()
                process = child

                BufferedReader(InputStreamReader(child.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) append(line)
                            }
                        }
                    }
                }
                child.waitFor()
            } catch (e: Exception) {
                error = e.message ?: "Comando indisponível neste Android."
            } finally {
                process = null
                running = false

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (error != null) append("Erro: $error")
                        else if (stopRequested) append("Interrompido.")
                        else append("Concluído.")

                        stopButton.isEnabled = false
                        runButton.isEnabled = true
                        traceButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun stopCommand() {
        stopRequested = true
        process?.destroy()
    }

    private fun updateNetworkFooter() {
        try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val caps = network?.let { manager.getNetworkCapabilities(it) }
            val props = network?.let { manager.getLinkProperties(it) }

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
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway?.hostAddress ?: "—"

            val dns = props?.dnsServers
                ?.firstOrNull { it is java.net.Inet4Address }
                ?.hostAddress ?: "—"

            networkFooter.text = "GW $gateway\nDNS $dns\nIP $ip   NET $type"
        } catch (_: Exception) {
            networkFooter.text = "Informações de rede indisponíveis."
        }
    }

    private fun append(line: String) {
        output.append("$line\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        process?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
