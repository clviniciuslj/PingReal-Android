package br.com.pingreal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticVerdictTest {
    private val ids = listOf("link", "ip", "gw", "inet", "dns", "web", "pub")

    private fun steps(vararg s: Status) = ids.zip(s.toList())

    private val ok = Status.OK
    private val fail = Status.FAIL
    private val warn = Status.WARN
    private val skip = Status.SKIP
    private val pend = Status.PENDING

    @Test
    fun beforeAnyRunItInvitesToStart() {
        val v = DiagnosticVerdict.of(steps(pend, pend, pend, pend, pend, pend, pend), running = false)
        assertEquals("Pronto", v.label)
        assertTrue(v.text.contains("7 etapas"))
    }

    @Test
    fun whileRunningItSaysTesting() {
        assertEquals("Testando…", DiagnosticVerdict.of(steps(ok, ok, pend, pend, pend, pend, pend), running = true).label)
    }

    @Test
    fun allGreenIsAllRight() {
        val v = DiagnosticVerdict.of(steps(ok, ok, ok, ok, ok, ok, ok), running = false)
        assertEquals("Tudo certo", v.label)
        assertEquals(Status.OK, v.tone)
    }

    @Test
    fun theFirstFailureNamesTheProblem() {
        // fibra fora: rede local boa, internet e o que vem depois falham
        val v = DiagnosticVerdict.of(steps(ok, ok, ok, fail, fail, fail, skip), running = false)
        assertEquals("Sem internet", v.label)
        assertTrue(v.text.contains("depois do roteador"))
        assertEquals(Status.FAIL, v.tone)

        assertEquals("Roteador não responde", DiagnosticVerdict.of(steps(ok, ok, fail, fail, fail, fail, skip), false).label)
        assertEquals("DNS com falha", DiagnosticVerdict.of(steps(ok, ok, ok, ok, fail, fail, skip), false).label)
        assertEquals("Navegação com falha", DiagnosticVerdict.of(steps(ok, ok, ok, ok, ok, fail, skip), false).label)
        assertEquals("Sem conexão", DiagnosticVerdict.of(steps(fail, skip, skip, skip, skip, skip, skip), false).label)
        assertEquals("Sem endereço IP", DiagnosticVerdict.of(steps(ok, fail, skip, skip, skip, skip, skip), false).label)
    }

    @Test
    fun warningsWithoutFailuresAskForAttention() {
        val one = DiagnosticVerdict.of(steps(warn, ok, ok, ok, ok, ok, ok), running = false)
        assertEquals("Atenção", one.label)
        assertTrue(one.text.contains("1 etapa com aviso"))
        assertTrue(DiagnosticVerdict.of(steps(warn, ok, ok, ok, warn, ok, ok), false).text.contains("2 etapas com aviso"))
    }

    @Test
    fun aCancelledRunIsIncomplete() {
        assertEquals("Incompleto", DiagnosticVerdict.of(steps(ok, ok, ok, skip, skip, skip, skip), running = false).label)
    }
}
