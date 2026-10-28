package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Aba "Diagnóstico": diagnóstico completo em camadas e teste de velocidade. */
class DiagnosticPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: Host
) {
    interface Host {
        fun acquire(label: String): Boolean
        fun release()
        fun netInfo(): NetInfo
        fun post(block: () -> Unit)
        fun shareText(text: String)
        fun shareImage(title: String, body: String)
    }

    private class Row(val glyph: TextView, val detail: TextView)

    val view: View

    private val executor = Executors.newSingleThreadExecutor()
    private val rows = HashMap<String, Row>()

    private var engine: DiagnosticEngine? = null
    private var speed: SpeedTest? = null
    private var diagRunning = false
    private var speedRunning = false
    private var idleGlobal = true

    private var lastReport: DiagnosticReport? = null
    private var lastNet: NetInfo? = null
    private var lastSpeed: SpeedResult? = null
    private var lastAt: Date? = null

    private lateinit var diagStart: Button
    private lateinit var diagStop: Button
    private lateinit var speedStart: Button
    private lateinit var speedStop: Button
    private lateinit var shareButton: Button
    private lateinit var speedValue: TextView
    private lateinit var speedPhase: TextView
    private lateinit var speedBar: MeterBar
    private lateinit var speedDown: TextView
    private lateinit var speedUp: TextView
    private lateinit var speedPing: TextView
    private lateinit var speedJitter: TextView

    init {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun add(v: View) = content.addView(v, ui.lp(-1, -2).apply { bottomMargin = ui.dp(12) })

        add(buildDiagnosticCard())
        add(buildStepsCard())
        add(buildSpeedCard())

        shareButton = ui.button("ENVIAR RELATÓRIO", ui.panel, ui.green, ui.panelStroke) { showShareDialog() }
        shareButton.visibility = View.GONE
        content.addView(shareButton, ui.lp(-1, ui.dp(46)).apply { bottomMargin = ui.dp(12) })

        view = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        refreshButtons()
    }

    // ------------------------------------------------------------------ construção

    private fun buildDiagnosticCard(): View {
        val card = ui.card()
        card.addView(ui.tv("DIAGNÓSTICO COMPLETO", 11f, ui.cyan, bold = true, spacing = 0.1f))
        card.addView(
            ui.tv(
                "Testa a conexão camada por camada (rede, IP, roteador, internet, DNS e navegação) e aponta onde está a falha. Use no Wi-Fi do cliente.",
                12f, ui.muted
            ).apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) }
        )
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        diagStart = ui.button("▶ INICIAR DIAGNÓSTICO", ui.green, android.graphics.Color.rgb(5, 20, 10)) { startDiagnostic() }
        diagStop = ui.button("PARAR", android.graphics.Color.rgb(58, 34, 34), ui.white, android.graphics.Color.rgb(90, 50, 50)) { stopDiagnostic() }
        buttons.addView(diagStart, ui.lp(0, ui.dp(46), 2f))
        buttons.addView(diagStop, ui.lp(0, ui.dp(46), 1f).apply { marginStart = ui.dp(8) })
        card.addView(buttons, ui.lp(-1, -2))
        return card
    }

    private fun buildStepsCard(): View {
        val card = ui.card()
        card.addView(ui.tv("ETAPAS", 11f, ui.green, bold = true, spacing = 0.1f).apply { setPadding(0, 0, 0, ui.dp(6)) })
        for ((id, title) in DiagnosticEngine.STEP_TITLES) {
            val glyph = ui.tv("○", 16f, ui.muted, bold = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val titleView = ui.tv(title, 13f, ui.white, bold = true)
            val detail = ui.tv("", 11f, ui.muted).apply { visibility = View.GONE }
            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(detail, ui.lp(-1, -2).apply { topMargin = ui.dp(1) })
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(6), 0, ui.dp(6))
            }
            row.addView(glyph, ui.lp(ui.dp(26), -2))
            row.addView(text, ui.lp(0, -2, 1f))
            card.addView(row, ui.lp(-1, -2))
            rows[id] = Row(glyph, detail)
        }
        return card
    }

    private fun buildSpeedCard(): View {
        val card = ui.card()
        card.addView(ui.tv("TESTE DE VELOCIDADE", 11f, ui.green, bold = true, spacing = 0.1f))
        card.addView(
            ui.tv("Mede download, upload e latência até a internet usando a conexão do celular.", 12f, ui.muted)
                .apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) }
        )

        val valueRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        speedValue = ui.tv("—", 34f, ui.white, bold = true, mono = true)
        valueRow.addView(speedValue)
        valueRow.addView(ui.tv(" Mbps", 13f, ui.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
        valueRow.addView(View(activity), ui.lp(0, 1, 1f))
        speedPhase = ui.tv("", 11f, ui.cyan, bold = true, spacing = 0.08f).apply { setPadding(0, 0, 0, ui.dp(8)) }
        valueRow.addView(speedPhase)
        card.addView(valueRow, ui.lp(-1, -2))

        speedBar = MeterBar(activity)
        card.addView(speedBar, ui.lp(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(12) })

        val results = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(label: String): TextView {
            val value = ui.tv("—", 14f, ui.white, bold = true, mono = true)
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.tv(label, 9f, ui.muted, bold = true, spacing = 0.08f))
                addView(value, ui.lp(-2, -2).apply { topMargin = ui.dp(2) })
            }
            results.addView(box, ui.lp(0, -2, 1f))
            return value
        }
        speedDown = cell("DOWNLOAD")
        speedUp = cell("UPLOAD")
        speedPing = cell("PING")
        speedJitter = cell("JITTER")
        card.addView(results, ui.lp(-1, -2))

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        speedStart = ui.button("▶ TESTAR VELOCIDADE", ui.panel, ui.green, ui.panelStroke) { confirmSpeed() }
        speedStop = ui.button("PARAR", android.graphics.Color.rgb(58, 34, 34), ui.white, android.graphics.Color.rgb(90, 50, 50)) { stopSpeed() }
        buttons.addView(speedStart, ui.lp(0, ui.dp(46), 2f))
        buttons.addView(speedStop, ui.lp(0, ui.dp(46), 1f).apply { marginStart = ui.dp(8) })
        card.addView(buttons, ui.lp(-1, -2).apply { topMargin = ui.dp(14) })
        return card
    }

    // ------------------------------------------------------------------ estado

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        refreshButtons()
    }

    private fun setEnabled(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
    }

    private fun refreshButtons() {
        setEnabled(diagStart, idleGlobal)
        setEnabled(speedStart, idleGlobal)
        setEnabled(diagStop, diagRunning)
        setEnabled(speedStop, speedRunning)
    }

    fun shutdown() {
        engine?.cancel()
        speed?.cancel()
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ diagnóstico

    private fun glyph(s: Status) = when (s) {
        Status.PENDING -> "○"
        Status.RUNNING -> "◔"
        Status.OK -> "✔"
        Status.WARN -> "▲"
        Status.FAIL -> "✘"
        Status.SKIP -> "–"
    }

    private fun colorOf(s: Status) = when (s) {
        Status.PENDING, Status.SKIP -> ui.muted
        Status.RUNNING -> ui.cyan
        Status.OK -> ui.green
        Status.WARN -> ui.amber
        Status.FAIL -> ui.red
    }

    private fun updateRow(step: StepOutcome) {
        val row = rows[step.id] ?: return
        row.glyph.text = glyph(step.status)
        row.glyph.setTextColor(colorOf(step.status))
        val text = if (step.status == Status.RUNNING) "verificando…" else step.detail
        row.detail.text = text
        row.detail.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        row.detail.setTextColor(
            when (step.status) {
                Status.WARN -> ui.amber
                Status.FAIL -> ui.red
                Status.RUNNING -> ui.cyan
                else -> ui.muted
            }
        )
    }

    private fun resetRows() {
        for ((id, title) in DiagnosticEngine.STEP_TITLES) updateRow(StepOutcome(id, title, Status.PENDING, ""))
    }

    private fun startDiagnostic() {
        if (diagRunning || speedRunning) return
        if (!host.acquire("DIAGNÓSTICO")) return

        diagRunning = true
        refreshButtons()
        resetRows()
        shareButton.visibility = View.GONE

        val net = host.netInfo()
        lastNet = net
        lastAt = Date()
        lastReport = null

        val eng = DiagnosticEngine(activity)
        engine = eng
        executor.execute {
            val report = try {
                eng.run(net) { step -> host.post { updateRow(step) } }
            } catch (_: Exception) {
                null
            }
            host.post { onDiagnosticDone(report, eng.cancelled) }
        }
    }

    private fun stopDiagnostic() {
        engine?.cancel()
    }

    private fun onDiagnosticDone(report: DiagnosticReport?, cancelled: Boolean) {
        diagRunning = false
        host.release()
        refreshButtons()

        if (cancelled || report == null) {
            for ((id, title) in DiagnosticEngine.STEP_TITLES) {
                val row = rows[id] ?: continue
                if (row.glyph.text.toString().let { it == glyph(Status.RUNNING) || it == glyph(Status.PENDING) }) {
                    updateRow(StepOutcome(id, title, Status.SKIP, if (cancelled) "Interrompido" else "Falhou"))
                }
            }
            if (!cancelled) Toast.makeText(activity, "O diagnóstico falhou", Toast.LENGTH_SHORT).show()
            return
        }

        lastReport = report
        shareButton.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ velocidade

    private fun confirmSpeed() {
        if (!idleGlobal) return
        if (host.netInfo().type.startsWith("Móvel")) {
            AlertDialog.Builder(activity)
                .setTitle("Dados móveis")
                .setMessage("O teste de velocidade baixa e envia centenas de MB. Continuar usando dados móveis?")
                .setPositiveButton("Continuar") { _, _ -> startSpeed() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            startSpeed()
        }
    }

    private fun fmtMbps(v: Double): String = if (v >= 100) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun startSpeed() {
        if (diagRunning || speedRunning) return
        if (!host.acquire("VELOCIDADE")) return

        speedRunning = true
        refreshButtons()
        shareButton.visibility = View.GONE
        for (t in listOf(speedDown, speedUp, speedPing, speedJitter)) t.text = "—"
        speedValue.text = "—"
        speedBar.fraction = 0f
        lastNet = host.netInfo()
        lastAt = Date()

        val test = SpeedTest()
        speed = test
        executor.execute {
            val result = try {
                test.run(object : SpeedTest.Listener {
                    override fun onPhase(phase: Int, label: String) {
                        host.post { speedPhase.text = label.uppercase() }
                    }

                    override fun onProgress(phase: Int, mbps: Double, fraction: Float) {
                        host.post {
                            val overall = when (phase) {
                                SpeedTest.PHASE_LATENCY -> 0.1f * fraction
                                SpeedTest.PHASE_DOWN -> 0.1f + 0.5f * fraction
                                else -> 0.6f + 0.4f * fraction
                            }
                            speedBar.fraction = overall
                            if (phase != SpeedTest.PHASE_LATENCY) {
                                speedValue.text = fmtMbps(mbps)
                                (if (phase == SpeedTest.PHASE_DOWN) speedDown else speedUp).text = fmtMbps(mbps)
                            }
                        }
                    }
                })
            } catch (_: Exception) {
                null
            }
            host.post { onSpeedDone(result, test.cancelled) }
        }
    }

    private fun stopSpeed() {
        speed?.cancel()
    }

    private fun onSpeedDone(result: SpeedResult?, cancelled: Boolean) {
        speedRunning = false
        host.release()
        refreshButtons()
        speedPhase.text = if (cancelled) "INTERROMPIDO" else "CONCLUÍDO"

        if (result == null) {
            Toast.makeText(activity, "O teste de velocidade falhou", Toast.LENGTH_SHORT).show()
            return
        }
        speedDown.text = if (result.downMbps > 0) fmtMbps(result.downMbps) else "falhou"
        speedUp.text = if (result.upMbps > 0) fmtMbps(result.upMbps) else if (cancelled) "—" else "falhou"
        speedPing.text = if (result.pingMs > 0) "${result.pingMs.toInt()} ms" else "—"
        speedJitter.text = if (result.pingMs > 0) String.format(Locale.US, "%.1f ms", result.jitterMs) else "—"
        speedValue.text = if (result.downMbps > 0) fmtMbps(result.downMbps) else "—"
        if (!cancelled) speedBar.fraction = 1f

        if (!cancelled && (result.downMbps > 0 || result.upMbps > 0)) {
            lastSpeed = result
            shareButton.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ relatório

    private fun title() = "Real Ping — diagnóstico completo"

    private fun body(): String {
        val sb = StringBuilder()
        val net = lastNet
        if (net != null) {
            sb.append("Rede: ${net.type} · IP ${net.ip}\n")
            sb.append("Gateway ${net.gateway} · DNS ${net.dns}\n")
        }

        val report = lastReport
        if (report != null) {
            sb.append("\nETAPAS\n")
            for (s in report.steps) {
                if (s.status == Status.PENDING) continue
                sb.append("${glyph(s.status)} ${s.title}")
                if (s.detail.isNotBlank()) sb.append(": ${s.detail}")
                sb.append('\n')
            }
        }

        val speedResult = lastSpeed
        if (speedResult != null) {
            sb.append("\nVELOCIDADE\n")
            sb.append("Download ${fmtMbps(speedResult.downMbps)} Mbps · Upload ${fmtMbps(speedResult.upMbps)} Mbps\n")
            sb.append("Ping ${speedResult.pingMs.toInt()} ms · Jitter ${String.format(Locale.US, "%.1f", speedResult.jitterMs)} ms\n")
        }
        return sb.toString().trimEnd()
    }

    private fun fullText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(lastAt ?: Date())
        return "${title()}\n$date\n\n${body()}"
    }

    private fun showShareDialog() {
        if (lastReport == null && lastSpeed == null) {
            Toast.makeText(activity, "Rode o diagnóstico ou o teste de velocidade primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Enviar relatório")
            .setItems(arrayOf("Texto", "Imagem")) { _, which ->
                if (which == 0) host.shareText(fullText()) else host.shareImage(title(), body())
            }
            .show()
    }
}
