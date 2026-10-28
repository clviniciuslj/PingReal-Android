package br.com.pingreal

import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class PingStats {
    var received = 0
    var lost = 0
    var min = Double.MAX_VALUE
    var max = 0.0
    var sum = 0.0
    var last = -1.0
    var lastWasLoss = false
    private var jitterSum = 0.0
    private var jitterN = 0

    val sent: Int get() = received + lost
    val avg: Double get() = if (received > 0) sum / received else 0.0
    val jitter: Double get() = if (jitterN > 0) jitterSum / jitterN else 0.0
    val jitterSamples: Int get() = jitterN
    val lossPct: Double get() = if (sent > 0) lost * 100.0 / sent else 0.0

    fun add(ms: Double) {
        if (last >= 0 && !lastWasLoss) {
            jitterSum += abs(ms - last)
            jitterN++
        }
        received++
        sum += ms
        if (ms < min) min = ms
        if (ms > max) max = ms
        last = ms
        lastWasLoss = false
    }
}

class PingReply(val address: String, val ttl: String, val timeMs: Double, val seq: Int?)

/**
 * Um ping contra um único alvo. Roda o binário do sistema numa thread própria e entrega tudo
 * (respostas, perdas, erros, fim) ao [Listener] **na thread da UI**.
 *
 * Cada execução do processo é uma "época": o `ping` recomeça a numerar os pacotes do 1, então ao
 * reiniciar o processo (por [restart] ou porque o sistema o encerrou) a contagem de sequência é zerada,
 * mas as estatísticas ([stats]) continuam acumulando.
 */
class PingSession(
    val label: String,
    val host: String,
    val continuous: Boolean,
    val count: Int,
    val color: Int,
    private val listener: Listener,
    private val pool: ExecutorService,
    private val postToUi: (() -> Unit) -> Unit
) {
    interface Listener {
        fun onReply(session: PingSession, reply: PingReply, sampleTime: Long)

        /** [blocked]: o envio falhou porque o Android bloqueou a rede do app (e não por falta de resposta). */
        fun onLoss(session: PingSession, sendTime: Long, blocked: Boolean)
        fun onBlockedChanged(session: PingSession, blocked: Boolean)
        fun onErrorLine(session: PingSession, line: String)

        /** O processo do ping foi encerrado pelo sistema e foi iniciado de novo. */
        fun onRestarted(session: PingSession)
        fun onFinished(session: PingSession, error: String?)
    }

    companion object {
        private const val LOSS_TIMEOUT_MS = 2000L

        private val REPLY_STRICT = Regex(
            """(\d+)\s+bytes from\s+(.+?):?\s+icmp_\w+=(\d+)\s+ttl=(\d+)\s+time[=<]\s*([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        )
        private val REPLY_LOOSE = Regex(
            """(\d+)\s+bytes from\s+(.+?):.*?\bttl[= ](\d+).*?\btime[=<]\s*([\d.]+)\s*ms""",
            RegexOption.IGNORE_CASE
        )

        fun parseReply(line: String): PingReply? {
            REPLY_STRICT.find(line)?.let { m ->
                val time = m.groupValues[5].toDoubleOrNull() ?: return null
                return PingReply(m.groupValues[2].trim(), m.groupValues[4], time, m.groupValues[3].toIntOrNull())
            }
            REPLY_LOOSE.find(line)?.let { m ->
                val time = m.groupValues[4].toDoubleOrNull() ?: return null
                return PingReply(m.groupValues[2].trim().removeSuffix(":"), m.groupValues[3], time, null)
            }
            return null
        }

        fun isHardError(lower: String): Boolean =
            lower.contains("unknown host") ||
                lower.contains("cannot resolve") ||
                lower.contains("network is unreachable") ||
                lower.contains("operation not permitted") ||
                lower.contains("name or service not known") ||
                lower.contains("bad address")

        fun isErrorLine(line: String): Boolean {
            val lower = line.lowercase()
            return isHardError(lower) ||
                lower.contains("destination host unreachable") ||
                lower.contains("connect:") ||
                lower.contains("ping:")
        }
    }

    val stats = PingStats()

    var hardError = false
        private set
    var finished = false
        private set
    @Volatile var stopRequested = false
        private set

    @Volatile private var process: Process? = null
    @Volatile private var epoch = 0

    // Controle de sequência da execução atual do processo (somente thread da UI).
    private val answered = HashSet<Int>()
    private val settled = HashSet<Int>()
    private var nextSeq = 1
    private var lastSeq = 0
    private var slotStart = 0L
    private var slotAnchored = false
    private var lastTickAt = 0L
    private var blocked = false
    private var epochReplies = 0

    fun start() {
        resetTracking()
        launch()
    }

    fun stop() {
        stopRequested = true
        process?.destroy()
    }

    /**
     * Reinicia o processo do ping mantendo as estatísticas. Usado ao voltar para o app, para não depender
     * de um processo que pode ter ficado preso durante o bloqueio da tela.
     */
    fun restart() {
        // Ping com quantidade fixa seguiria a própria contagem: reiniciar o faria enviar pacotes a mais.
        if (finished || stopRequested || !continuous) return
        epoch++
        process?.destroy()
        resetTracking()
        launch()
    }

    /** Chamado periodicamente pela UI para detectar pacotes cujo prazo venceu. */
    fun tick() {
        if (finished || stopRequested) return
        val now = System.currentTimeMillis()
        // Se a UI ficou parada (tela bloqueada, app em segundo plano), o relógio dos pacotes não andou
        // junto: sem este ajuste, todos os pacotes "atrasados" seriam contados como perdidos de uma vez.
        if (lastTickAt != 0L && now - lastTickAt > 3000) slotStart += now - lastTickAt
        lastTickAt = now
        checkLosses(false)
    }

    private fun resetTracking() {
        answered.clear()
        settled.clear()
        nextSeq = 1
        lastSeq = 0
        slotStart = System.currentTimeMillis()
        slotAnchored = false
        lastTickAt = 0L
        blocked = false
        epochReplies = 0
    }

    private fun launch() {
        val myEpoch = epoch
        pool.execute { runProcess(myEpoch) }
    }

    private fun runProcess(myEpoch: Int) {
        var error: String? = null
        try {
            val args = mutableListOf("/system/bin/ping", "-W", "1", "-s", "32")
            if (!continuous) {
                args.add("-c")
                args.add(count.toString())
            }
            args.add(host)

            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            process = p
            if (stopRequested || myEpoch != epoch) p.destroy()
            try {
                p.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // O horário vale o da leitura, não o do processamento na UI (que pode atrasar).
                        val readAt = System.currentTimeMillis()
                        postToUi { if (myEpoch == epoch) handleLine(line, readAt) }
                    }
                }
                p.waitFor()
            } finally {
                if (process === p) process = null
                p.destroy()
            }
        } catch (e: Exception) {
            if (!stopRequested && myEpoch == epoch) error = e.message ?: "falha ao executar ping"
        }
        postToUi { onProcessEnded(myEpoch, error) }
    }

    private fun onProcessEnded(myEpoch: Int, error: String?) {
        if (myEpoch != epoch || finished) return // já substituído por um processo novo

        // Ping contínuo que morreu sozinho (ex.: o sistema encerrou o processo em segundo plano): reinicia.
        // Só se já tinha funcionado, para não ficar num laço infinito com destino inválido.
        val diedOnItsOwn = continuous && !stopRequested && error == null && !hardError && epochReplies > 0
        if (diedOnItsOwn) {
            listener.onRestarted(this)
            epoch++
            resetTracking()
            launch()
            return
        }
        finish(error)
    }

    private fun handleLine(line: String, readAt: Long) {
        // Depois de parar, linhas que ainda estavam na fila chegam com horário errado: ignora.
        if (finished || stopRequested) return

        // "ping: sendmsg: Operation not permitted": o Android bloqueou a rede do app (segundo plano,
        // economia de bateria/dados). É temporário, não um erro de destino.
        val lowerLine = line.lowercase()
        if (lowerLine.contains("sendmsg") && lowerLine.contains("not permitted")) {
            onSendBlocked(readAt)
            return
        }

        val reply = parseReply(line)
        if (reply == null) {
            if (isErrorLine(line)) {
                if (isHardError(lowerLine)) hardError = true
                listener.onErrorLine(this, line)
            }
            return
        }

        val seq = reply.seq ?: (lastSeq + 1)
        lastSeq = max(lastSeq, seq)

        if (!slotAnchored) {
            slotStart = readAt - reply.timeMs.toLong() - (seq - 1) * 1000L
            slotAnchored = true
        }
        if (!answered.add(seq)) return // duplicado

        if (seq < nextSeq) stats.lost = max(0, stats.lost - 1) // chegou depois de marcado como perdido

        if (blocked) {
            blocked = false
            listener.onBlockedChanged(this, false)
        }
        epochReplies++
        stats.add(reply.timeMs)
        listener.onReply(this, reply, readAt - reply.timeMs.toLong())
        checkLosses(false)
    }

    /** O envio do pacote atual falhou: registra a perda na hora, em vez de esperar o prazo vencer. */
    private fun onSendBlocked(at: Long) {
        if (!blocked) {
            blocked = true
            listener.onBlockedChanged(this, true)
        }
        val seq = ((at - slotStart) / 1000.0).roundToInt() + 1
        val limit = if (continuous) Int.MAX_VALUE else count
        if (seq < nextSeq || seq > limit || seq in answered || !settled.add(seq)) return
        stats.lost++
        stats.last = -1.0
        stats.lastWasLoss = true
        listener.onLoss(this, at, true)
    }

    /** Marca como perdidos os pacotes cujo prazo venceu (ou todos os pendentes, se [force] e o ping for finito). */
    private fun checkLosses(force: Boolean) {
        val now = System.currentTimeMillis()
        val limit = if (continuous) Int.MAX_VALUE else count
        while (nextSeq <= limit) {
            if (force && continuous) break
            val sendTime = slotStart + (nextSeq - 1) * 1000L
            if (!force && sendTime + LOSS_TIMEOUT_MS > now) break
            if (nextSeq !in answered && nextSeq !in settled) {
                stats.lost++
                stats.last = -1.0
                stats.lastWasLoss = true
                listener.onLoss(this, sendTime, false)
            }
            nextSeq++
        }
    }

    private fun finish(error: String?) {
        if (finished) return
        val clean = !stopRequested && error == null && !hardError
        checkLosses(force = clean)
        finished = true
        listener.onFinished(this, error)
    }
}
