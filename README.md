# Ping Real (Android)

Aplicativo Android nativo simples para executar ping real no próprio telefone, com saída em terminal.

## Comandos
- `ping 172.16.1.1` — 4 tentativas.
- `ping 172.16.1.1 -t` — contínuo até tocar em **PARAR**.

O app chama `/system/bin/ping` do Android; a saída exibida vem do processo real, não é simulada. A disponibilidade e as opções do executável podem variar entre versões/fabricantes Android. Teste conectado à rede Wi-Fi que tem rota para o endereço desejado.

## Gerar o APK automaticamente
Este projeto inclui um workflow do GitHub Actions em `.github/workflows/build-apk.yml`.

1. Crie um repositório no GitHub.
2. Envie o conteúdo desta pasta para o repositório.
3. Abra a aba **Actions** e execute **Build Ping Real APK** (ou aguarde o build automático após o push).
4. Quando terminar, abra a execução e baixe o artefato **PingReal-debug-apk**.
5. Extraia o ZIP do artefato para obter `app-debug.apk` e instale no Android.

O APK gerado é de depuração, não publicado na Play Store. O ping precisa ser testado no telefone e na rede do condomínio antes de confiar nele para diagnóstico.
