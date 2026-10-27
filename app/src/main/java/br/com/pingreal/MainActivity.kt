                    index++
                }
                "-n", "-c" -> {
                    if (countSpecified || index + 1 >= parts.size) {
                        append("Quantidade inválida.")
                        return
                    }
                    val parsed = parts[index + 1].toIntOrNull()
                    if (parsed == null || parsed !in 1..10000) {
                        append("Quantidade deve estar entre 1 e 10000.")
                        return
                    }
                    count = parsed
                    countSpecified = true
                    index += 2
                }
                else -> {
                    append("Parâmetro desconhecido: ${parts[index]}")
                    return
                }
            }
        }

        if (continuous && countSpecified) {
            append("Use -t ou -n quantidade, não os dois.")
            return
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(command.windowToken, 0)
        command.clearFocus()

        val args = mutableListOf("/system/bin/ping", "-W", "1", "-s", "32")
        if (!continuous) {
            args.add("-c")
            args.add(count.toString())
        }
        args.add(host)

        append("")
        append("────────────────────────────")
        append("> $raw")
        append(if (continuous) "Ping contínuo para $host" else "Ping para $host ($count tentativas)")
        append("")

        running = true
        stopRequested = false
        pingStartedAt = System.currentTimeMillis()
        runButton.isEnabled = false
        stopButton.isEnabled = true
        traceButton.isEnabled = false

        executor.execute {
            var process: Process? = null
            val capturedLines = mutableListOf<String>()
            var readError: String? = null

            try {
                process = ProcessBuilder(args).redirectErrorStream(true).start()
                pingProcess = process

                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            synchronized(capturedLines) { capturedLines.add(line) }

                            val formatted = formatPingLine(line)
                            if (formatted != null) {
                                runOnUiThread { if (!isFinishing && !isDestroyed) append(formatted) }
                            } else if (isErrorLine(line)) {
                                runOnUiThread { if (!isFinishing && !isDestroyed) append(line) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!stopRequested) readError = e.message ?: "falha ao ler a saída do ping"
                }

                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } catch (e: Exception) {
                if (!stopRequested) readError = e.message ?: "falha ao executar ping"
            } finally {
                val snapshot = synchronized(capturedLines) { capturedLines.toList() }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        append("")
                        append("Estatísticas do Ping para $host:")
                        appendSummary(snapshot, continuous, count)
                        if (stopRequested) {
                            append("Interrompido pelo usuário.")
                        } else if (readError != null) {
                            append("Erro: $readError")
                        } else {
                            append("Concluído.")
                        }
                        append("────────────────────────────")
                    }
                }

                pingProcess = null
                running = false
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        runButton.isEnabled = true
                        stopButton.isEnabled = false
                        traceButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun startTraceRoute() {
        if (running) return

        val host = command.text.toString().trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (host.isBlank() || host.startsWith("-") || !host.matches(Regex("[A-Za-z0-9._:%-]+"))) {
            append("Informe um IP ou hostname válido para o traceroute.")
            return
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(command.windowToken, 0)
        command.clearFocus()

        append("")
        append("────────────────────────────")
        append("> traceroute $host")
        append("Rastreando rota para $host (máximo de 20 saltos)...")
        append("Cada salto pode levar alguns segundos; * indica ausência de resposta.")
        append("")

        running = true
        stopRequested = false
        runButton.isEnabled = false
        stopButton.isEnabled = true
        traceButton.isEnabled = false

        executor.execute {
            var process: Process? = null
            var failure: String? = null
            try {
                val commands = listOf(
                    listOf("/system/bin/traceroute", "-m", "20", "-w", "1", host),
                    listOf("traceroute", "-m", "20", "-w", "1", host),
                    listOf("toybox", "traceroute", "-m", "20", "-w", "1", host)
                )
                var lastStartError: Exception? = null
                for (args in commands) {
                    try {
                        process = ProcessBuilder(args).redirectErrorStream(true).start()
                        pingProcess = process
                        lastStartError = null
                        break
                    } catch (e: Exception) {
                        lastStartError = e
                    }
                }
                if (process == null) {
                    throw lastStartError ?: IllegalStateException("Traceroute não está disponível neste Android.")
                }

                BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) append(line)
                            }
                        }
                    }
                }
                process!!.waitFor()
            } catch (e: Exception) {
                if (!stopRequested) failure = e.message ?: "não foi possível executar o traceroute"
            } finally {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (stopRequested) append("Traceroute interrompido pelo usuário.")
                        else if (failure != null) {
                            append("Não foi possível executar o traceroute: $failure")
                            append("Este Android pode não incluir o comando traceroute.")
                        } else append("Traceroute concluído.")
                        append("────────────────────────────")
                    }
                }
                pingProcess = null
