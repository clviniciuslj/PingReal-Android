package br.com.pingreal

/** O resumo de uma linha do diagnóstico: um rótulo curto, uma frase e o tom (OK, aviso, falha ou neutro). */
class Verdict(val label: String, val text: String, val tone: Status)

object DiagnosticVerdict {
    /** [steps] na ordem das etapas: id e estado de cada uma. */
    fun of(steps: List<Pair<String, Status>>, running: Boolean): Verdict {
        val total = steps.size
        if (running) return Verdict("Testando…", "Verificando cada etapa da conexão.", Status.RUNNING)
        if (steps.all { it.second == Status.PENDING }) {
            return Verdict("Pronto", "Toque em Iniciar diagnóstico para testar a conexão em $total etapas.", Status.PENDING)
        }

        // A primeira falha é a que explica as outras: tudo que vem depois dela costuma falhar por consequência.
        val firstFail = steps.firstOrNull { it.second == Status.FAIL }?.first
        if (firstFail != null) {
            return when (firstFail) {
                "link" -> Verdict("Sem conexão", "O celular não está ligado a nenhuma rede.", Status.FAIL)
                "ip" -> Verdict("Sem endereço IP", "O roteador não entregou um IP ao celular (DHCP).", Status.FAIL)
                "gw" -> Verdict("Roteador não responde", "A falha está entre o celular e o roteador.", Status.FAIL)
                "inet" -> Verdict("Sem internet", "A rede local está boa. A falha começa depois do roteador.", Status.FAIL)
                "dns" -> Verdict("DNS com falha", "A internet responde por IP, mas os nomes não resolvem.", Status.FAIL)
                "web" -> Verdict("Navegação com falha", "IP e DNS funcionam, mas as páginas não abrem.", Status.FAIL)
                else -> Verdict("Falha", "Uma etapa falhou. Veja a dica na etapa em vermelho.", Status.FAIL)
            }
        }

        val warns = steps.count { it.second == Status.WARN }
        if (warns > 0) {
            val what = if (warns == 1) "1 etapa com aviso" else "$warns etapas com aviso"
            return Verdict("Atenção", "A conexão funciona, mas há $what. Veja as dicas em laranja.", Status.WARN)
        }
        if (steps.any { it.second == Status.SKIP }) {
            return Verdict("Incompleto", "O diagnóstico foi interrompido antes do fim.", Status.SKIP)
        }
        return Verdict("Tudo certo", "Nenhuma falha nas $total etapas.", Status.OK)
    }
}
