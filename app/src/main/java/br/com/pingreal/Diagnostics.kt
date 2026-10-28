package br.com.pingreal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.math.max
import kotlin.random.Random

data class NetInfo(
    val gateway: String,
    val dns: String,
    val ip: String,
    val type: String
)

enum class Status { PENDING, RUNNING, OK, WARN, FAIL, SKIP }

class StepOutcome(val id: String, val title: String, var status: Status, var detail: String)

class Problem(
    val level: Status,
    val title: String,
    val causes: List<String>,
    val questions: List<String>
)

class DiagnosticReport(
    val steps: List<StepOutcome>,
    val problems: List<Problem>,
    val cancelled: Boolean
) {
    val main: Problem? get() = problems.firstOrNull()
}

/** Ping bloqueante de poucos pacotes (usado pelo diagnóstico e pelo teste de velocidade). */
fun quickPing(host: String, count: Int, procs: MutableList<Process>): PingStats {
    val stats = PingStats()
    val process = ProcessBuilder("/system/bin/ping", "-n", "-W", "1", "-s", "32", "-c", count.toString(), host)
        .redirectErrorStream(true)
        .start()
    procs.add(process)
    try {
        process.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                PingSession.parseReply(line)?.let { stats.add(it.timeMs) }
            }
        }
        process.waitFor()
    } finally {
        procs.remove(process)
        process.destroy()
    }
    stats.lost = max(0, count - stats.received)
    return stats
}

/** Sonda de traceroute: um ping com TTL limitado. Compartilhado entre o traceroute e o diagnóstico. */
object Tracer {

    class Probe(val address: String?, val ms: Double?, val reached: Boolean, val raw: String?)

    private val TTL_FIELD = Regex("""\bttl[= ]\d+""", RegexOption.IGNORE_CASE)
    private val TIME_FIELD = Regex("""time[=<]\s*([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
    private val FROM_ADDR = Regex(
        """\bfrom\s+(?:\S+\s+\()?([0-9a-fA-F:.]+)\)?""",
        RegexOption.IGNORE_CASE
    )
    private val EXCEEDED = listOf(
        "time to live exceeded", "ttl exceeded", "time exceeded",
        "hop limit", "ttl expired"
    )

    /** Custo fixo de abrir o processo do ping, descontado quando o roteador não informa `time=`. */
    fun calibrateOverhead(procs: MutableList<Process>): Double = try {
        val started = System.nanoTime()
        val process = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "1", "127.0.0.1")
            .redirectErrorStream(true)
            .start()
        procs.add(process)
        try {
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        } finally {
            procs.remove(process)
            process.destroy()
        }
        (System.nanoTime() - started) / 1_000_000.0
    } catch (_: Exception) {
        0.0
    }

    private fun isNoise(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("ping ") ||
            lower.startsWith("---") ||
            lower.startsWith("rtt") ||
            lower.startsWith("round-trip") ||
            lower.contains("statistics") ||
            lower.contains("packets transmitted") ||
            (lower.contains("%") && lower.contains("loss"))
    }

    private fun extractAddress(line: String): String? =
        FROM_ADDR.find(line)?.groupValues?.get(1)
            ?.removeSuffix(":")
            ?.takeIf { it.contains('.') || it.contains(':') }
            ?.substringBefore('%')

    fun probe(host: String, ttl: Int, overheadMs: Double, procs: MutableList<Process>): Probe {
        val args = listOf("/system/bin/ping", "-n", "-c", "1", "-W", "2", "-t", ttl.toString(), host)
        val started = System.nanoTime()
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        procs.add(process)
        val lines = try {
            process.inputStream.bufferedReader().use { it.readLines() }
        } finally {
            procs.remove(process)
            process.destroy()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        val reply = lines.map { it.trim() }.filter { it.isNotEmpty() && !isNoise(it) }

        val destination = reply.firstOrNull {
            it.contains("bytes from", ignoreCase = true) && TTL_FIELD.containsMatchIn(it)
        }
        val exceeded = reply.firstOrNull { l -> EXCEEDED.any { l.contains(it, ignoreCase = true) } }
        val anyFrom = reply.firstOrNull { FROM_ADDR.containsMatchIn(it) && extractAddress(it) != null }

        val source = destination ?: exceeded ?: anyFrom
        val address = source?.let { extractAddress(it) }

        if (address == null) return Probe(null, null, false, reply.firstOrNull())

        val reportedTime = source?.let { TIME_FIELD.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val ms = reportedTime ?: (elapsedMs - overheadMs).coerceAtLeast(0.5)
        return Probe(address, ms, destination != null, null)
    }
}

/**
 * Diagnóstico em camadas: da conexão do celular até a navegação. Roda bloqueando a thread
 * que o chamou e avisa cada etapa por [run]'s `update`.
 */
class DiagnosticEngine(private val context: Context) {

    companion object {
        val STEP_TITLES = listOf(
            "link" to "Conexão com a rede",
            "ip" to "Endereço IP (DHCP)",
            "gw" to "Roteador (gateway)",
            "inet" to "Internet por IP",
            "dns" to "DNS",
            "web" to "Navegação (HTTP/HTTPS)",
            "pub" to "IP público e provedor",
            "route" to "Rota até a internet"
        )

        private val INTERNET_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private const val DNS_NAME = "www.google.com"
        private const val PING_COUNT = 6

        private val FIBER_QUESTIONS = listOf(
            "Olhe a ONU: a luz LOS está vermelha ou piscando? (vermelha = sem sinal da fibra: cabo dobrado, rompido ou conector sujo)",
            "A luz PON está acesa fixa? (apagada ou piscando = a ONU não sincronizou com a OLT)",
            "A luz de internet/WAN está acesa? (apagada com PON fixa = falha de autenticação/PPPoE ou bloqueio na conta)",
            "O cabo entre a ONU e o roteador está bem encaixado? Teste outro cabo ou outra porta.",
            "Já reiniciou ONU e roteador (desligados por 30 s)? Se sim e não voltou, abra uma OS.",
            "Outros clientes da mesma região ou caixa estão sem internet? Se sim, é falha de rede, não do cliente."
        )
    }

    @Volatile var cancelled = false
        private set

    private val procs = CopyOnWriteArrayList<Process>()
    private val pool = Executors.newFixedThreadPool(4)

    fun cancel() {
        cancelled = true
        procs.forEach { it.destroy() }
        pool.shutdownNow()
    }

    fun run(net: NetInfo, update: (StepOutcome) -> Unit): DiagnosticReport {
        val steps = STEP_TITLES.map { StepOutcome(it.first, it.second, Status.PENDING, "") }
        val byId = steps.associateBy { it.id }

        fun set(id: String, status: Status, detail: String) {
            val s = byId.getValue(id)
            s.status = status
            s.detail = detail
            update(s)
        }

        fun partial() = DiagnosticReport(steps, emptyList(), true)

        try {
            set("link", Status.RUNNING, "")
            linkStep(net).let { set("link", it.first, it.second) }
            if (cancelled) return partial()

            set("ip", Status.RUNNING, "")
            ipStep(net).let { set("ip", it.first, it.second) }
            if (cancelled) return partial()

            val noLink = byId.getValue("link").status == Status.FAIL
            val noIp = byId.getValue("ip").status == Status.FAIL
            if (noLink || noIp) {
                for (id in listOf("gw", "inet", "dns", "web", "pub", "route")) {
                    set(id, Status.SKIP, "Não testado: sem conexão local")
                }
                return DiagnosticReport(steps, buildProblems(byId, net, null), false)
            }

            set("gw", Status.RUNNING, "")
            gatewayStep(net).let { set("gw", it.first, it.second) }
            if (cancelled) return partial()

            set("inet", Status.RUNNING, "")
            internetStep().let { set("inet", it.first, it.second) }
            if (cancelled) return partial()

            val internetDown = byId.getValue("inet").status == Status.FAIL

            set("dns", Status.RUNNING, "")
            dnsStep(net).let { set("dns", it.first, it.second) }
            if (cancelled) return partial()

            set("web", Status.RUNNING, "")
            val web = webStep()
            set("web", web.status, web.detail)
            if (cancelled) return partial()

            if (web.status == Status.FAIL) {
                set("pub", Status.SKIP, "Sem navegação para consultar")
            } else {
                set("pub", Status.RUNNING, "")
                pubStep(web.traceIp).let { set("pub", it.first, it.second) }
            }
            if (cancelled) return partial()

            var routeDetail: String? = null
            if (internetDown && byId.getValue("gw").status != Status.FAIL) {
                set("route", Status.RUNNING, "")
                val r = routeStep()
                routeDetail = r.second
                set("route", r.first, r.second)
            } else {
                set("route", Status.SKIP, "Não necessário")
            }

            return DiagnosticReport(steps, buildProblems(byId, net, routeDetail), cancelled)
        } catch (e: Exception) {
            // Cancelar interrompe as threads no meio de uma etapa; isso não é erro.
            if (cancelled) return partial()
            throw e
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ etapas

    @Suppress("DEPRECATION")
    private fun linkStep(net: NetInfo): Pair<Status, String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val airplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) {
            false
        }
        val active = cm.activeNetwork
            ?: return Status.FAIL to (if (airplane) "Modo avião ligado" else "Sem conexão: nem Wi-Fi nem dados móveis")
        val caps = cm.getNetworkCapabilities(active)

        var status = Status.OK
        val parts = ArrayList<String>()

        if (net.type.startsWith("Wi-Fi")) {
            val info = try {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            } catch (_: Exception) {
                null
            }
            val freq = info?.frequency ?: 0
            val band = when {
                freq >= 5925 -> "6 GHz"
                freq >= 4900 -> "5 GHz"
                freq >= 2400 -> "2,4 GHz"
                else -> null
            }
            parts.add(if (band != null) "Wi-Fi $band" else "Wi-Fi")

            val rssi = info?.rssi ?: -127
            if (rssi in -126..0) {
                val quality = when {
                    rssi >= -67 -> "bom"
                    rssi >= -75 -> "regular"
                    rssi >= -85 -> "fraco"
                    else -> "muito fraco"
                }
                if (rssi < -75) status = Status.WARN
                parts.add("$rssi dBm ($quality)")
            }
            val speed = info?.linkSpeed ?: -1
            if (speed > 0) parts.add("link $speed Mbps")
        } else if (net.type.startsWith("Móvel")) {
            status = Status.WARN
            parts.add("Dados móveis (para testar a fibra do cliente, conecte ao Wi-Fi dele)")
        } else {
            parts.add(net.type)
        }

        if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (status == Status.OK) status = Status.WARN
            parts.add("o Android marcou esta rede como sem internet")
        }
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            if (status == Status.OK) status = Status.WARN
            parts.add("portal cativo detectado")
        }
        if (net.type.contains("VPN")) parts.add("VPN ativa pode interferir nos testes")

        return status to parts.joinToString(" · ")
    }

    private fun hasGlobalIpv6(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        props?.linkAddresses?.any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc
        } == true
    } catch (_: Exception) {
        false
    }

    private fun ipStep(net: NetInfo): Pair<Status, String> {
        val ip = net.ip
        val v6 = if (hasGlobalIpv6()) "IPv6: sim" else "IPv6: não"
        return when {
            ip == "—" || ip == "N/D" -> Status.FAIL to "O celular não tem endereço IP"
            ip.startsWith("169.254.") ->
                Status.FAIL to "IP $ip (autoatribuído): o roteador não entregou IP via DHCP"
            else -> Status.OK to "IP $ip · $v6"
        }
    }

    private fun usable(value: String) =
        value != "—" && value != "N/D" && value.matches(Regex("[A-Za-z0-9._:%-]+"))

    private fun gatewayStep(net: NetInfo): Pair<Status, String> {
        if (!usable(net.gateway)) {
            return if (net.type.startsWith("Móvel")) {
                Status.SKIP to "Sem gateway nos dados móveis"
            } else {
                Status.WARN to "Gateway não identificado"
            }
        }
        val st = quickPing(net.gateway, PING_COUNT, procs)
        return when {
            st.received == 0 -> Status.FAIL to "${net.gateway} não responde ($PING_COUNT pacotes sem resposta)"
            st.lossPct >= 10 || st.avg > 60 ->
                Status.WARN to "${net.gateway} · ${st.received}/$PING_COUNT · média ${ms(st.avg)} · perda ${st.lossPct.toInt()}%"
            else -> Status.OK to "${net.gateway} · média ${ms(st.avg)} · perda ${st.lossPct.toInt()}%"
        }
    }

    private fun ms(v: Double) = if (v < 10) String.format("%.1f ms", v) else "${v.toInt()} ms"

    private fun internetStep(): Pair<Status, String> {
        val futures = INTERNET_TARGETS.map { target ->
            pool.submit(Callable { target to quickPing(target, PING_COUNT, procs) })
        }
        val results = futures.map { it.get() }
        val responding = results.filter { it.second.received > 0 }

        val line = results.joinToString(" · ") { (target, st) ->
            if (st.received > 0) "$target ${ms(st.avg)}" else "$target sem resposta"
        }
        if (responding.isEmpty()) return Status.FAIL to "Nenhum destino respondeu · $line"

        val avgLoss = responding.map { it.second.lossPct }.average()
        val avgMs = responding.map { it.second.avg }.average()
        return if (responding.size < results.size || avgLoss >= 10 || avgMs > 150) {
            Status.WARN to "$line · perda ${avgLoss.toInt()}%"
        } else {
            Status.OK to "$line · perda ${avgLoss.toInt()}%"
        }
    }

    // DNS ------------------------------------------------------------------

    private fun dnsQuery(server: String, name: String, timeoutMs: Int = 2500): Long? = try {
        val id = Random.nextInt(0xFFFF)
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id)
        d.writeShort(0x0100) // consulta recursiva
        d.writeShort(1)
        d.writeShort(0)
        d.writeShort(0)
        d.writeShort(0)
        name.split('.').forEach { label ->
            d.writeByte(label.length)
            d.write(label.toByteArray())
        }
        d.writeByte(0)
        d.writeShort(1) // tipo A
        d.writeShort(1) // classe IN
        val data = out.toByteArray()

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            val started = System.nanoTime()
            socket.send(DatagramPacket(data, data.size, address, 53))
            val buf = ByteArray(512)
            socket.receive(DatagramPacket(buf, buf.size))
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val rid = ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
            val rcode = buf[3].toInt() and 0x0f
            if (rid == id && rcode == 0) elapsed else null
        }
    } catch (_: Exception) {
        null
    }

    private fun systemResolve(name: String): Long? {
        val future = pool.submit(Callable {
            val started = System.nanoTime()
            InetAddress.getAllByName(name)
            (System.nanoTime() - started) / 1_000_000
        })
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun dnsStep(net: NetInfo): Pair<Status, String> {
        val clientIp = net.dns.takeIf { usable(it) }
        val clientMs = clientIp?.let { dnsQuery(it, DNS_NAME) }
        val publicMs = dnsQuery("1.1.1.1", DNS_NAME)
        val systemMs = systemResolve(DNS_NAME)

        val details = ArrayList<String>()
        if (clientIp != null) details.add("DNS do cliente ($clientIp): ${clientMs?.let { "$it ms" } ?: "sem resposta"}")
        details.add("DNS público (1.1.1.1): ${publicMs?.let { "$it ms" } ?: "sem resposta"}")
        details.add("resolução do sistema: ${systemMs?.let { "$it ms" } ?: "falhou"}")
        val text = details.joinToString(" · ")

        return when {
            systemMs == null && publicMs != null && clientIp != null ->
                Status.FAIL to "O DNS do cliente não resolve, mas o público sim · $text"
            systemMs == null && publicMs != null ->
                Status.FAIL to "O celular não consegue resolver nomes · $text"
            systemMs == null -> Status.FAIL to "Nenhum DNS responde · $text"
            systemMs > 400 || (clientIp != null && clientMs != null && clientMs > 400) ->
                Status.WARN to "DNS lento · $text"
            else -> Status.OK to text
        }
    }

    // Navegação -------------------------------------------------------------

    private class HttpResult(val code: Int, val ms: Long, val body: String)

    private fun http(url: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "RealPing/1.0")
        val started = System.nanoTime()
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { s ->
                val buf = ByteArray(4096)
                val n = s.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            } ?: ""
            return HttpResult(code, (System.nanoTime() - started) / 1_000_000, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "falha de DNS"
        is SSLException -> "certificado/TLS inválido (confira data e hora do celular)"
        is SocketTimeoutException -> "tempo esgotado"
        is ConnectException -> "sem rota ou conexão recusada"
        else -> e.javaClass.simpleName
    }

    private class WebResult(val status: Status, val detail: String, val traceIp: String?)

    private fun webStep(): WebResult {
        var httpOk = false
        var portal = false
        var httpInfo: String
        try {
            val r = http("http://connectivitycheck.gstatic.com/generate_204")
            when {
                r.code == 204 -> { httpOk = true; httpInfo = "HTTP ok (${r.ms} ms)" }
                else -> { portal = true; httpInfo = "HTTP devolveu ${r.code}" }
            }
        } catch (e: Exception) {
            httpInfo = "HTTP falhou: ${describe(e)}"
        }

        var httpsOk = false
        var traceIp: String? = null
        val httpsInfo: String = try {
            val r = http("https://www.cloudflare.com/cdn-cgi/trace")
            if (r.code == 200) {
                httpsOk = true
                traceIp = r.body.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()
                "HTTPS ok (${r.ms} ms)"
            } else {
                "HTTPS devolveu ${r.code}"
            }
        } catch (e: Exception) {
            "HTTPS falhou: ${describe(e)}"
        }

        val text = "$httpInfo · $httpsInfo"
        return when {
            portal -> WebResult(Status.FAIL, "Portal cativo ou redirecionamento: a rede pode exigir login · $text", traceIp)
            httpOk && httpsOk -> WebResult(Status.OK, text, traceIp)
            httpOk || httpsOk -> WebResult(Status.WARN, text, traceIp)
            else -> WebResult(Status.FAIL, text, traceIp)
        }
    }

    private fun pubStep(traceIp: String?): Pair<Status, String> {
        var ip = traceIp
        var provider: String? = null
        var place: String? = null
        try {
            val r = http("https://ipinfo.io/json")
            if (r.code == 200) {
                val json = JSONObject(r.body)
                ip = json.optString("ip", ip ?: "").ifBlank { ip }
                provider = json.optString("org", "").ifBlank { null }
                val city = json.optString("city", "")
                val country = json.optString("country", "")
                place = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            }
        } catch (_: Exception) {
            // sem o provedor: mostra só o IP, se houver
        }
        if (ip == null) return Status.WARN to "Não foi possível descobrir o IP público"
        val parts = listOfNotNull("IP público $ip", provider, place)
        return Status.OK to parts.joinToString(" · ")
    }

    private fun routeStep(): Pair<Status, String> {
        val overhead = Tracer.calibrateOverhead(procs)
        val hops = ArrayList<String>()
        var lastAnswered = 0
        var lastAddress: String? = null
        var reached = false
        for (ttl in 1..8) {
            if (cancelled) break
            val p = Tracer.probe("1.1.1.1", ttl, overhead, procs)
            if (p.address != null) {
                lastAnswered = ttl
                lastAddress = p.address
            }
            hops.add("$ttl ${p.address ?: "*"}")
            if (p.reached) {
                reached = true
                break
            }
        }
        val path = hops.joinToString("  ›  ")
        return when {
            reached -> Status.OK to path
            lastAddress != null ->
                Status.FAIL to "A rota para no salto $lastAnswered ($lastAddress) · $path"
            else -> Status.FAIL to "Nenhum salto respondeu · $path"
        }
    }

    // ------------------------------------------------------------------ veredito

    private fun buildProblems(byId: Map<String, StepOutcome>, net: NetInfo, routeDetail: String?): List<Problem> {
        fun st(id: String) = byId.getValue(id).status
        fun detail(id: String) = byId.getValue(id).detail

        val problems = ArrayList<Problem>()

        if (st("link") == Status.FAIL) {
            problems.add(
                Problem(
                    Status.FAIL, "Sem conexão com a rede",
                    listOf(detail("link")),
                    listOf(
                        "O Wi-Fi do celular está ligado e conectado à rede da casa?",
                        "O modo avião está desligado?",
                        "O roteador está ligado, com as luzes acesas?"
                    )
                )
            )
            return problems
        }

        if (st("ip") == Status.FAIL) {
            problems.add(
                Problem(
                    Status.FAIL, "O roteador não entregou endereço IP (DHCP)",
                    listOf(detail("ip"), "O DHCP do roteador pode estar travado ou desativado."),
                    listOf(
                        "Reinicie o roteador: tire da tomada por 30 s e ligue de novo.",
                        "No celular, esqueça a rede Wi-Fi e conecte novamente.",
                        "Se persistir, o roteador pode precisar de reset ou troca."
                    )
                )
            )
            return problems
        }

        val internetDown = st("inet") == Status.FAIL

        when (st("gw")) {
            Status.FAIL -> if (internetDown) {
                problems.add(
                    Problem(
                        Status.FAIL, "O roteador não responde",
                        listOf(
                            detail("gw"),
                            "O celular conecta, mas o roteador não responde: pode estar travado, sobrecarregado ou o Wi-Fi está instável."
                        ),
                        listOf(
                            "As luzes do roteador estão acesas? Alguma vermelha?",
                            "Reinicie o roteador (30 s desligado).",
                            "Teste perto do roteador, ou por cabo se possível."
                        )
                    )
                )
            } else {
                problems.add(
                    Problem(
                        Status.WARN, "O roteador não responde a ping, mas a internet funciona",
                        listOf("Alguns roteadores bloqueiam ping. Não é problema por si só."),
                        emptyList()
                    )
                )
            }
            Status.WARN -> problems.add(
                Problem(
                    Status.WARN, "Rede local instável (Wi-Fi)",
                    listOf(detail("gw")),
                    listOf(
                        "O cliente está longe do roteador ou há paredes no caminho?",
                        "Se o aparelho tiver 5 GHz, teste nessa rede (mais rápida, menos interferência).",
                        "Há muitos aparelhos usando a rede ao mesmo tempo?"
                    )
                )
            )
            else -> {}
        }

        if (internetDown && st("gw") != Status.FAIL) {
            val causes = ArrayList<String>()
            causes.add("O roteador responde, mas nenhum destino da internet responde.")
            causes.add("Causas prováveis: fibra ou ONU sem sinal, autenticação (PPPoE) falhando, bloqueio na conta ou falha na rede do provedor.")
            if (routeDetail != null) causes.add("Rota: $routeDetail")
            problems.add(Problem(Status.FAIL, "Problema depois do roteador (ONU, fibra ou provedor)", causes, FIBER_QUESTIONS))
        } else if (st("inet") == Status.WARN) {
            problems.add(
                Problem(
                    Status.WARN, "Internet instável ou lenta",
                    listOf(detail("inet")),
                    listOf(
                        "Teste de novo em outro horário: pode ser congestionamento.",
                        "Compare com o teste de velocidade para ver a banda real."
                    )
                )
            )
        }

        if (!internetDown) {
            when (st("dns")) {
                Status.FAIL -> problems.add(
                    Problem(
                        Status.FAIL, "Problema de DNS",
                        listOf(detail("dns"), "A internet funciona por IP, mas os nomes não resolvem: os sites não abrem."),
                        listOf(
                            "Configure o DNS do roteador (ou do celular) para 1.1.1.1 e 8.8.8.8.",
                            "Se o DNS do provedor estiver fora, avise o suporte técnico."
                        )
                    )
                )
                Status.WARN -> problems.add(
                    Problem(Status.WARN, "DNS lento", listOf(detail("dns")), listOf("Trocar o DNS para 1.1.1.1 ou 8.8.8.8 costuma resolver."))
                )
                else -> {}
            }

            when (st("web")) {
                Status.FAIL -> problems.add(
                    Problem(
                        Status.FAIL, "A navegação falha mesmo com o ping funcionando",
                        listOf(detail("web")),
                        listOf(
                            "A data e a hora do celular estão corretas?",
                            "Há algum proxy, VPN ou filtro de conteúdo ativo?",
                            "Alguma página pede login (portal cativo)?"
                        )
                    )
                )
                Status.WARN -> problems.add(
                    Problem(Status.WARN, "Navegação parcial", listOf(detail("web")), emptyList())
                )
                else -> {}
            }
        }

        if (st("link") == Status.WARN) {
            problems.add(
                Problem(
                    Status.WARN, "Atenção na conexão do celular",
                    listOf(detail("link")),
                    if (net.type.startsWith("Móvel")) listOf("Conecte o celular ao Wi-Fi do cliente e rode o diagnóstico de novo.") else emptyList()
                )
            )
        }

        return problems.sortedBy { if (it.level == Status.FAIL) 0 else 1 }
    }
}

// ============================================================================
// Teste de velocidade
// ============================================================================

class SpeedResult(val downMbps: Double, val upMbps: Double, val pingMs: Double, val jitterMs: Double)

class SpeedTest {

    interface Listener {
        fun onPhase(phase: Int, label: String)
        fun onProgress(phase: Int, mbps: Double, fraction: Float)
    }

    companion object {
        const val PHASE_LATENCY = 0
        const val PHASE_DOWN = 1
        const val PHASE_UP = 2

        private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=25000000"
        private const val UP_URL = "https://speed.cloudflare.com/__up"
        private const val DOWN_SECONDS = 8
        private const val UP_SECONDS = 6
        private const val WARMUP_NS = 1_500_000_000L
    }

    @Volatile var cancelled = false
        private set

    private val procs = CopyOnWriteArrayList<Process>()
    private val connections = CopyOnWriteArrayList<HttpURLConnection>()

    fun cancel() {
        cancelled = true
        procs.forEach { it.destroy() }
        connections.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }

    fun run(listener: Listener): SpeedResult {
        listener.onPhase(PHASE_LATENCY, "Latência")
        listener.onProgress(PHASE_LATENCY, 0.0, 0f)
        val ping = try {
            quickPing("1.1.1.1", 6, procs)
        } catch (_: Exception) {
            PingStats()
        }
        listener.onProgress(PHASE_LATENCY, 0.0, 1f)
        if (cancelled) return SpeedResult(0.0, 0.0, ping.avg, ping.jitter)

        listener.onPhase(PHASE_DOWN, "Download")
        val down = transfer(download = true, seconds = DOWN_SECONDS, threads = 4, phase = PHASE_DOWN, listener = listener)
        if (cancelled) return SpeedResult(down, 0.0, ping.avg, ping.jitter)

        listener.onPhase(PHASE_UP, "Upload")
        val up = transfer(download = false, seconds = UP_SECONDS, threads = 3, phase = PHASE_UP, listener = listener)

        return SpeedResult(down, up, ping.avg, ping.jitter)
    }

    private fun transfer(download: Boolean, seconds: Int, threads: Int, phase: Int, listener: Listener): Double {
        val total = AtomicLong(0)
        val startNs = System.nanoTime()
        val endNs = startNs + seconds * 1_000_000_000L
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                val buffer = ByteArray(64 * 1024)
                while (System.nanoTime() < endNs && !cancelled) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(if (download) DOWN_URL else UP_URL).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 6000
                            useCaches = false
                            if (!download) {
                                requestMethod = "POST"
                                doOutput = true
                                setChunkedStreamingMode(64 * 1024)
                                setRequestProperty("Content-Type", "application/octet-stream")
                            }
                        }
                        connections.add(conn)
                        if (download) {
                            conn.inputStream.use { input ->
                                while (System.nanoTime() < endNs && !cancelled) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total.addAndGet(n.toLong())
                                }
                            }
                        } else {
                            conn.outputStream.use { out ->
                                while (System.nanoTime() < endNs && !cancelled) {
                                    out.write(buffer)
                                    total.addAndGet(buffer.size.toLong())
                                }
                            }
                        }
                    } catch (_: Exception) {
                        try { Thread.sleep(200) } catch (_: InterruptedException) { return@execute }
                    } finally {
                        conn?.let {
                            connections.remove(it)
                            try { it.disconnect() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Amostrador: calcula a velocidade a cada 200 ms (média das últimas 5 amostras).
        val recent = ArrayDeque<Double>()
        var lastNs = startNs
        var lastBytes = 0L
        var warmBytes = -1L
        var warmNs = 0L
        while (System.nanoTime() < endNs && !cancelled) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            val now = System.nanoTime()
            val bytes = total.get()
            val dt = (now - lastNs) / 1e9
            if (dt > 0) {
                recent.addLast((bytes - lastBytes) * 8 / dt / 1e6)
                if (recent.size > 5) recent.removeFirst()
            }
            if (warmBytes < 0 && now - startNs >= WARMUP_NS) {
                warmBytes = bytes
                warmNs = now
            }
            lastNs = now
            lastBytes = bytes
            listener.onProgress(phase, recent.average(), ((now - startNs) / (seconds * 1e9)).toFloat().coerceIn(0f, 1f))
        }

        val finishNs = System.nanoTime()
        val finishBytes = total.get()
        connections.forEach { try { it.disconnect() } catch (_: Exception) {} }
        pool.shutdownNow()
        listener.onProgress(phase, recent.lastOrNull() ?: 0.0, 1f)

        return if (warmBytes >= 0 && finishNs > warmNs) {
            (finishBytes - warmBytes) * 8 / ((finishNs - warmNs) / 1e9) / 1e6
        } else if (finishNs > startNs) {
            finishBytes * 8 / ((finishNs - startNs) / 1e9) / 1e6
        } else {
            0.0
        }
    }
}
