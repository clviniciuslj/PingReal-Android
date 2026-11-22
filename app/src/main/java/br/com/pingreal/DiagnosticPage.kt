package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Aba "Diagnóstico": o resultado em cima, as etapas numa lista que abre com um toque (a que tem problema
 * já abre com a dica) e, fixos embaixo, Iniciar diagnóstico e os ícones do teste de velocidade e do relatório.
 */
class DiagnosticPage(
    private val activity: Activity,
    private val ui: UiKit,
    private val host: PageHost
) {
    private class Row(
        val badge: StatusBadgeView,
        val word: TextView,
        val chevron: IconView,
        val more: View,
        val detail: TextView,
        val tip: TextView
    ) {
        var status = Status.PENDING
        var open = false
    }

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

    private lateinit var diagButton: Button
    private lateinit var verdictTitle: TextView
    private lateinit var verdictText: TextView
    private lateinit var speedSheetView: View
    private var speedDialog: Dialog? = null
    private lateinit var speedButton: Button
    private lateinit var gauge: GaugeView
    private lateinit var speedDown: TextView
    private lateinit var speedUp: TextView
    private lateinit var speedPing: TextView
    private lateinit var speedJitter: TextView

    init {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(buildVerdict(), ui.lp(-1, -2))
        content.addView(buildList(), ui.lp(-1, -2).apply { topMargin = ui.dp(4) })
        content.addView(View(activity), ui.lp(-1, ui.dp(8)))

        // A lista rola; os botões ficam fixos embaixo, como no Terminal.
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, ui.lp(-1, 0, 1f))
            addView(buildActions(), ui.lp(-1, -2).apply { topMargin = ui.dp(12) })
        }
        speedSheetView = buildSpeedSheet()
        refreshButtons()
        updateVerdict()
    }

    // ------------------------------------------------------------------ construção

    /** O resultado em cima: o rótulo grande na cor do resultado e uma frase. */
    private fun buildVerdict(): View {
        verdictTitle = ui.tv("", 26f, ui.white, bold = true).apply { gravity = Gravity.CENTER }
        verdictText = ui.tv("", 14f, ui.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(ui.dpf(2f), 1f)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(14))
            addView(verdictTitle, ui.lp(-1, -2))
            addView(verdictText, ui.lp(-1, -2).apply { topMargin = ui.dp(6) })
        }
    }

    private fun updateVerdict() {
        val steps = DiagnosticEngine.STEP_TITLES.map { (id, _) -> id to (rows[id]?.status ?: Status.PENDING) }
        val verdict = DiagnosticVerdict.of(steps, diagRunning)
        val color = when (verdict.tone) {
            Status.OK -> ui.green
            Status.WARN -> ui.orange
            Status.FAIL -> ui.red
            else -> ui.white
        }
        verdictTitle.text = verdict.label
        verdictTitle.setTextColor(color)
        verdictText.text = verdict.text
    }

    /** As 7 etapas: selo, nome, estado e uma seta; um toque abre o detalhe (e a dica, se houver). */
    private fun buildList(): View {
        val card = ui.card().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)) }
        for ((index, step) in DiagnosticEngine.STEP_TITLES.withIndex()) {
            val (id, title) = step
            val badge = StatusBadgeView(activity)
            val word = ui.tv("—", 13f, ui.muted, mono = true)
            val chevron = IconView(activity, IconView.CHEVRON, 18f).apply { color = ui.muted; alpha = 0.7f }
            val head = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(50)
                background = ui.rippled(Color.TRANSPARENT, 12f)
                isClickable = true
                setOnClickListener { toggle(id) }
                addView(badge, ui.lp(ui.dp(24), ui.dp(24)))
                addView(ui.tv(title, 15f, ui.white, bold = true), ui.lp(0, -2, 1f).apply { marginStart = ui.dp(12) })
                addView(word, ui.lp(-2, -2).apply { marginStart = ui.dp(8) })
                addView(chevron, ui.lp(ui.dp(22), ui.dp(22)).apply { marginStart = ui.dp(6) })
            }
            val detail = ui.tv("Ainda não testado.", 13f, ui.muted).apply { setLineSpacing(ui.dpf(1.5f), 1f) }
            val tip = ui.tv("", 13f, ui.text).apply {
                visibility = View.GONE
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                setLineSpacing(ui.dpf(1.5f), 1f)
            }
            val more = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(36), 0, 0, ui.dp(12))
                visibility = View.GONE
                addView(detail, ui.lp(-1, -2))
                addView(tip, ui.lp(-1, -2).apply { topMargin = ui.dp(8) })
            }
            if (index > 0) card.addView(View(activity).apply { setBackgroundColor(ui.panelStroke) }, ui.lp(-1, 1))
            card.addView(head, ui.lp(-1, -2))
            card.addView(more, ui.lp(-1, -2))
            rows[id] = Row(badge, word, chevron, more, detail, tip)
        }
        return card
    }

    private fun setOpen(row: Row, open: Boolean) {
        row.open = open
        row.more.visibility = if (open) View.VISIBLE else View.GONE
        row.chevron.rotation = if (open) 180f else 0f
    }

    private fun toggle(id: String) {
        val row = rows[id] ?: return
        setOpen(row, !row.open)
    }

    /** Embaixo: Iniciar diagnóstico e dois ícones redondos (teste de velocidade e enviar relatório). */
    private fun buildActions(): View {
        diagButton = ui.button("Iniciar diagnóstico", ui.accent, ui.onAccent) {
            if (diagRunning) engine?.cancel() else startDiagnostic()
        }
        fun round(kind: Int, label: String, onClick: () -> Unit) = IconView(activity, kind, 22f).apply {
            color = ui.accentInk
            background = ui.rippled(ui.panel, 26f, ui.panelStroke)
            contentDescription = label
            isClickable = true
            setOnClickListener { onClick() }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(diagButton, ui.lp(0, ui.dp(52), 1f))
            addView(round(IconView.GAUGE, "Testar velocidade") {
                openSpeedSheet()
                if (!speedRunning) confirmSpeed()
            }, ui.lp(ui.dp(52), ui.dp(52)).apply { marginStart = ui.dp(10) })
            addView(round(IconView.SHARE, "Enviar relatório") { showShareDialog() },
                ui.lp(ui.dp(52), ui.dp(52)).apply { marginStart = ui.dp(10) })
        }
    }

    /** A folha do teste de velocidade: o velocímetro e os quatro números, como no cartão da 1.17. */
    private fun buildSpeedSheet(): View {
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.cornerRect(ui.panel, floatArrayOf(24f, 24f, 0f, 0f), ui.panelStroke)
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(20))
        }
        sheet.addView(View(activity).apply { background = ui.roundRect(Palette.overlay(60), 2f) },
            ui.lp(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(14) })
        sheet.addView(ui.title("Teste de velocidade").apply { gravity = Gravity.CENTER }, ui.lp(-1, -2))

        gauge = GaugeView(activity)
        sheet.addView(gauge, ui.lp(-1, -2).apply { topMargin = ui.dp(10) })

        val results = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(label: String): TextView {
            val value = ui.tv("—", 17f, ui.white, bold = true, mono = true)
            results.addView(ui.metric(label, value), ui.lp(0, -2, 1f))
            return value
        }
        speedDown = cell("Download")
        speedUp = cell("Upload")
        speedPing = cell("Ping")
        speedJitter = cell("Jitter")
        sheet.addView(results, ui.lp(-1, -2).apply { topMargin = ui.dp(6) })

        speedButton = ui.button("Fechar", ui.softFill(ui.accent), ui.accentInk) {
            if (speedRunning) speed?.cancel() else speedDialog?.dismiss()
        }
        sheet.addView(speedButton, ui.lp(-1, ui.dp(48)).apply { topMargin = ui.dp(16) })
        return sheet
    }

    private fun openSpeedSheet() {
        val dialog = speedDialog ?: Dialog(activity).also { d ->
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setContentView(speedSheetView)
            d.setOnDismissListener { if (speedRunning) speed?.cancel() }
            speedDialog = d
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
        }
    }

    // ------------------------------------------------------------------ estado

    fun onModeChanged(idle: Boolean) {
        idleGlobal = idle
        refreshButtons()
    }

    private fun refreshButtons() {
        if (diagRunning) {
            diagButton.text = "Parar"
            ui.danger(diagButton)
        } else {
            diagButton.text = "Iniciar diagnóstico"
            ui.primary(diagButton)
        }
        ui.setEnabled(diagButton, diagRunning || idleGlobal)

        if (speedRunning) {
            speedButton.text = "Parar"
            ui.danger(speedButton)
        } else {
            speedButton.text = "Fechar"
            ui.secondary(speedButton)
        }
    }

    fun shutdown() {
        engine?.cancel()
        speed?.cancel()
        speedDialog?.dismiss()
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ diagnóstico

    /** Símbolo de cada estado no relatório em texto. */
    private fun glyph(s: Status) = when (s) {
        Status.PENDING -> "○"
        Status.RUNNING -> "◔"
        Status.OK -> "✔"
        Status.WARN -> "▲"
        Status.FAIL -> "✘"
        Status.SKIP -> "–"
    }

    private fun updateRow(step: StepOutcome) {
        val row = rows[step.id] ?: return
        row.status = step.status
        row.badge.status = step.status

        val (word, wordColor) = when (step.status) {
            Status.OK -> "OK" to ui.green
            Status.FAIL -> "Falha" to ui.red
            Status.WARN -> "Aviso" to ui.orange
            Status.RUNNING -> "verificando…" to ui.accentInk
            Status.SKIP -> "Pulado" to ui.muted
            Status.PENDING -> "—" to ui.muted
        }
        row.word.text = word
        row.word.setTextColor(wordColor)

        row.detail.text = when (step.status) {
            Status.PENDING -> "Ainda não testado."
            Status.RUNNING -> "verificando…"
            else -> step.detail.ifBlank { "—" }
        }
        row.detail.setTextColor(
            when (step.status) {
                Status.WARN -> ui.orange
                Status.FAIL -> ui.red
                else -> ui.muted
            }
        )

        val tip = tipFor(step)
        row.tip.text = tip.orEmpty()
        row.tip.visibility = if (tip == null) View.GONE else View.VISIBLE
        if (tip != null) {
            row.tip.background = ui.roundRect(ui.softFill(if (step.status == Status.FAIL) ui.red else ui.orange), 12f)
        }
        // A primeira etapa com problema abre sozinha, já com a dica.
        if ((step.status == Status.FAIL || step.status == Status.WARN) && rows.values.none { it.open }) setOpen(row, true)
        if (::verdictTitle.isInitialized) updateVerdict()
    }

    /** Uma sugestão do que pode ser, para cada etapa que falhou ou ficou com aviso. */
    private fun tipFor(step: StepOutcome): String? {
        if (step.status != Status.FAIL && step.status != Status.WARN) return null
        val d = step.detail
        return when (step.id) {
            "link" -> when {
                d.contains("portal", true) -> "A rede exige login: abra o navegador e autentique."
                d.contains("sem internet", true) -> "O Android considera esta rede sem internet: o problema costuma estar depois do roteador (ONU/provedor). Veja as etapas abaixo."
                d.contains("móveis", true) -> "Está em dados móveis, não na rede do cliente. Conecte ao Wi-Fi para testar a rede dele."
                step.status == Status.WARN -> "Sinal fraco: aproxime-se do roteador ou use a banda de 2,4 GHz, que tem mais alcance."
                d.contains("avião", true) -> "Desligue o modo avião."
                else -> "Ligue o Wi-Fi ou os dados móveis. Se for Wi-Fi, confira se o aparelho está conectado à rede certa e se o cabo do roteador/ONU está firme."
            }
            "ip" -> when {
                d.contains("autoatribu", true) -> "O servidor DHCP não respondeu. Reinicie o roteador, confira se o cabo da ONU/WAN está ligado e se há outro servidor DHCP na rede."
                else -> "Esqueça a rede e conecte de novo. Se continuar, o DHCP do roteador pode estar desligado ou com a faixa de IPs esgotada."
            }
            "gw" -> if (step.status == Status.WARN) {
                "Respostas lentas ou com perda até o roteador: indica Wi-Fi ruim, cabo defeituoso ou roteador sobrecarregado."
            } else {
                "O roteador não responde ao ping. Ele pode estar travado (reinicie), o cliente pode estar em outra rede/VLAN, ou o roteador bloqueia ping (o resto dos testes indica isso)."
            }
            "inet" -> if (step.status == Status.WARN) {
                "Há perda de pacotes até a internet. Compare com o roteador: se o roteador está bom, o problema está no link da fibra/ONU ou no provedor."
            } else {
                "Rede local ok e sem internet: confira o sinal óptico da ONU (LOS vermelho = fibra), a autenticação PPPoE no roteador e se há bloqueio/manutenção no provedor."
            }
            "dns" -> when {
                d.contains("cliente", true) -> "O DNS configurado (do roteador/DHCP) falha, mas um público funciona. Troque o DNS do roteador por 1.1.1.1 ou 8.8.8.8, ou reinicie o roteador."
                d.contains("lento", true) -> "DNS demorando. Teste outro servidor DNS (1.1.1.1 ou 8.8.8.8) no roteador."
                else -> "Não resolve nomes. Verifique se o DNS informado pelo provedor está correto, troque por 1.1.1.1 / 8.8.8.8 e confirme que a internet por IP está funcionando."
            }
            "web" -> when {
                d.contains("portal", true) -> "A rede pede login (portal cativo) ou redireciona o tráfego. Abra o navegador e autentique, ou confira filtros do provedor/roteador."
                d.contains("certificado", true) || d.contains("TLS", true) -> "Confira data e hora do celular. Se estiverem certas, algum equipamento da rede pode estar interceptando HTTPS."
                step.status == Status.WARN -> "Só um dos protocolos (HTTP ou HTTPS) funcionou. Pode haver bloqueio de porta, proxy ou firewall no roteador."
                else -> "IP e DNS funcionam mas a navegação não: pode ser MTU/PPPoE mal configurado, firewall bloqueando 80/443, proxy ou filtro do provedor."
            }
            "pub" -> "Não conseguiu consultar o IP público. Pode ser bloqueio do serviço consultado ou internet instável; não afeta o resultado dos outros testes."
            else -> null
        }
    }

    private fun resetRows() {
        for (row in rows.values) setOpen(row, false)
        for ((id, title) in DiagnosticEngine.STEP_TITLES) updateRow(StepOutcome(id, title, Status.PENDING, ""))
    }

    private fun startDiagnostic() {
        if (diagRunning || speedRunning) return
        if (!host.acquire("Diagnóstico", ui.accentInk)) return

        diagRunning = true
        refreshButtons()
        resetRows()

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

    private fun onDiagnosticDone(report: DiagnosticReport?, cancelled: Boolean) {
        diagRunning = false
        host.release()
        refreshButtons()
        updateVerdict()

        if (cancelled || report == null) {
            for ((id, title) in DiagnosticEngine.STEP_TITLES) {
                val row = rows[id] ?: continue
                if (row.status == Status.RUNNING || row.status == Status.PENDING) {
                    updateRow(StepOutcome(id, title, Status.SKIP, if (cancelled) "Interrompido" else "Falhou"))
                }
            }
            if (!cancelled) Toast.makeText(activity, "O diagnóstico falhou", Toast.LENGTH_SHORT).show()
            return
        }

        lastReport = report
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

    private fun startSpeed() {
        if (diagRunning || speedRunning) return
        if (!host.acquire("Velocidade", ui.accentInk)) return

        speedRunning = true
        refreshButtons()
        for (t in listOf(speedDown, speedUp, speedPing, speedJitter)) t.text = "—"
        lastNet = host.netInfo()
        lastAt = Date()

        val test = SpeedTest()
        speed = test
        executor.execute {
            val result = try {
                test.run(object : SpeedTest.Listener {
                    override fun onPhase(phase: Int) {
                        host.post {
                            when (phase) {
                                SpeedTest.PHASE_LATENCY -> {
                                    gauge.setPhase("Ping", ui.muted)
                                    gauge.setText("…", "ms")
                                }
                                SpeedTest.PHASE_DOWN -> gauge.setPhase("Download", ui.cyan)
                                else -> gauge.setPhase("Upload", ui.violet)
                            }
                        }
                    }

                    override fun onLatency(pingMs: Double, jitterMs: Double) {
                        host.post {
                            if (pingMs > 0) {
                                speedPing.text = "${ui.formatMs(pingMs)} ms"
                                speedJitter.text = "${ui.formatMs(jitterMs)} ms"
                                gauge.setText(ui.formatMs(pingMs), "ms")
                            }
                        }
                    }

                    override fun onProgress(phase: Int, mbps: Double, fraction: Float) {
                        if (phase == SpeedTest.PHASE_LATENCY) return
                        host.post {
                            gauge.setSpeed(mbps)
                            (if (phase == SpeedTest.PHASE_DOWN) speedDown else speedUp).text = ui.formatMbps(mbps)
                        }
                    }
                })
            } catch (_: Exception) {
                null
            }
            host.post { onSpeedDone(result, test.cancelled) }
        }
    }

    private fun onSpeedDone(result: SpeedResult?, cancelled: Boolean) {
        speedRunning = false
        host.release()
        refreshButtons()

        if (result == null) {
            gauge.settle("Falhou", "—", "Mbps")
            Toast.makeText(activity, "O teste de velocidade falhou", Toast.LENGTH_SHORT).show()
            return
        }
        speedDown.text = if (result.downMbps > 0) ui.formatMbps(result.downMbps) else "falhou"
        speedUp.text = if (result.upMbps > 0) ui.formatMbps(result.upMbps) else if (cancelled) "—" else "falhou"

        if (cancelled) {
            gauge.settle("Interrompido", "—", "Mbps")
            return
        }
        gauge.settle("Concluído", if (result.downMbps > 0) ui.formatMbps(result.downMbps) else "—", "Mbps de download")

        if (result.downMbps > 0 || result.upMbps > 0) {
            lastSpeed = result
        }
    }

    // ------------------------------------------------------------------ relatório

    private fun title() = "PingLab — diagnóstico completo"

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
            sb.append("Download ${ui.formatMbps(speedResult.downMbps)} Mbps · Upload ${ui.formatMbps(speedResult.upMbps)} Mbps\n")
            sb.append("Ping ${ui.formatMs(speedResult.pingMs)} ms · Jitter ${ui.formatMs(speedResult.jitterMs)} ms\n")
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
            .setItems(arrayOf("Enviar texto", "Enviar imagem", "Copiar texto")) { _, which ->
                when (which) {
                    0 -> host.shareText(fullText())
                    1 -> host.shareImage(title(), body(), null)
                    else -> host.copyText(fullText())
                }
            }
            .show()
    }
}
