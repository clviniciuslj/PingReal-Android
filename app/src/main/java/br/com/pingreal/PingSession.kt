package br.com.pingreal

import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max

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
        fun onLoss(session: PingSession, sendTime: Long)
        fun onErrorLine(session: PingSession, line: String)
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

    private val answered = HashSet<Int>()
    private var nextSeq = 1
    private var lastSeq = 0
    private var slotStart = 0L
    private var slotAnchored = false

    fun start() {
        slotStart = System.currentTimeMillis()
        pool.execute { runProcess() }
    }

    fun stop() {
        stopRequested = true
        process?.destroy()
    }

    /** Chamado periodicamente pela UI para detectar pacotes cujo prazo venceu. */
    fun tick() {
        if (!finished) checkLosses(false)
    }

    private fun runProcess() {
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
            if (stopRequested) p.destroy()
            try {
                p.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) postToUi { handleLine(line) }
                    }
                }
                p.waitFor()
            } finally {
                process = null
                p.destroy()
            }
        } catch (e: Exception) {
            if (!stopRequested) error = e.message ?: "falha ao executar ping"
        }
        postToUi { finish(error) }
    }

    private fun handleLine(line: String) {
        if (finished) return
        val reply = parseReply(line)
        if (reply == null) {
            if (isErrorLine(line)) {
                if (isHardError(line.lowercase())) hardError = true
                listener.onErrorLine(this, line)
            }
            return
        }

        val now = System.currentTimeMillis()
        val seq = reply.seq ?: (lastSeq + 1)
        lastSeq = max(lastSeq, seq)

        if (!slotAnchored) {
            slotStart = now - reply.timeMs.toLong() - (seq - 1) * 1000L
            slotAnchored = true
        }
        if (!answered.add(seq)) return // duplicado

        if (seq < nextSeq) stats.lost = max(0, stats.lost - 1) // chegou depois de marcado como perdido

        stats.add(reply.timeMs)
        listener.onReply(this, reply, now - reply.timeMs.toLong())
        checkLosses(false)
    }

    /** Marca como perdidos os pacotes cujo prazo venceu (ou todos os pendentes, se [force] e o ping for finito). */
    private fun checkLosses(force: Boolean) {
        val now = System.currentTimeMillis()
        val limit = if (continuous) Int.MAX_VALUE else count
        while (nextSeq <= limit) {
            if (force && continuous) break
            val sendTime = slotStart + (nextSeq - 1) * 1000L
            if (!force && sendTime + LOSS_TIMEOUT_MS > now) break
            if (nextSeq !in answered) {
                stats.lost++
                stats.last = -1.0
                stats.lastWasLoss = true
                listener.onLoss(this, sendTime)
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
