# Validação — chamadas privadas 1.4

Data: 17/11/2026. Windows x64, JDK 21.0.13, JavaFX 21.0.13, webrtc-java 1.0.1.

## Resultado automatizado

O reator Maven completo passou às 12:55:44 (São Paulo): **80 casos, 79 aprovados, 1 ignorado por ser opt-in, zero falhas/erros**. Foram 7 casos de protocolo, 34 de servidor e 39 de cliente. O teste ignorado é o OnlineSmokeTest, que exige configuração explícita para usar o servidor público.

- WebSockets reais verificaram convite, aceite, sinais privados e desconexão; o provedor TURN foi simulado.
- Estado de sala cobriu terceiros sem acesso à chamada, pessoa ocupada, convite expirado, negociação expirada, 55 minutos, troca de canal, substituição de sessão, cancelamento enquanto credenciais estão pendentes e compatibilidade com clientes antigos.
- Fixture HTTP local verificou a requisição ao provedor, resposta válida, limite de corpo, timeout e configuração inválida. Nenhuma chave real foi usada.
- Dois peers nativos sem hardware estabeleceram WebRTC local por ICE host. O destinatário recebeu PCM de um tom sintetizado e registrou pacotes RTP de áudio. Mudo e liberação de peer/faixa também foram verificados.
- Controlador e JavaFX verificaram ausência de microfone antes de consentimento, aceite/recusa, mudo, encerramento, texto durante chamada e callbacks antigos. Cinco estados visuais foram inspecionados a 900 × 720.
- Sinalização do cliente não é renderizada como mensagem, não leva tokens/sessões e não é reenviada após uma nova entrada. A regressão de anúncio de capacidade falhou com a alteração deliberada `voice-v1 → null` e passou após restaurar a implementação.
- A revisão independente de código/especificação não encontrou defeitos acionáveis. `git diff --check` passou.

## Limites

Não houve teste com microfone ou alto-falantes físicos nem audição entre duas pessoas. O proprietário concluiu pessoalmente a ativação e autorização de cobrança na Cloudflare e aprovou criar a chave TURN `bate-papo-javafx` e salvar ID/segredo nas variáveis do Render. O diagnóstico real abaixo valida tráfego por relay a partir desta máquina; não substitui o teste humano em duas redes diferentes.

O instalador não contém chave permanente de TURN nem código de acesso da sala. Os JARs originais e seus avisos de licença são mantidos. O pacote é local; não foi publicada uma release binária no GitHub.

## Pacote e publicação

`scripts/package-windows.ps1 -SkipTests -Installer` terminou com sucesso após o reator completo acima, sem mudanças de código entre teste e pacote. Pasta `dist/20261117-125710`. O MSI informa `ProductVersion=1.4.0`; a configuração do launcher contém os dois JARs WebRTC (API e Windows x64). A versão portátil foi aberta pelo Explorador de Arquivos e a tela de entrada exibiu `WINDOWS / v1.4`. A instalação via MSI não foi executada neste teste.

SHA-256:

- MSI: `6F2E78D1BA7CCED5B6A4820178372AFAB3095EBDACAAFF53A364DC4BF5250167`
- ZIP: `4A2E3D3B9DFB029EF5DDC5B8C2D1CA2CFC11857BFE90C7E8ED0BAD52B361847C`

Código do cliente publicado no commit `e4f25fc00991763b2a9ad2f57d6abff02eff5cef`. Deploy Render `dep-dbu7isbl550s73a5rt9g` ficou `live` às 15:58:45 UTC. Após a publicação, `https://bate-papo-java.onrender.com/health` respondeu HTTP 200 com `{"status":"ok"}`; não havia logs de erro na janela consultada. A configuração TURN e sua validação ocorreram depois, conforme abaixo.

## Relay Cloudflare real

Após aprovação explícita do proprietário, a chave foi criada na Cloudflare e transferida diretamente para `TURN_KEY_ID` e `TURN_API_TOKEN` no serviço existente do Render, preservando `CHAT_ACCESS_TOKEN`. Nenhum segredo permanente foi salvo no código, pacote ou relatório. O deploy `dep-dbu7qmvf3r2c73d9oc3g` ativou as variáveis às 16:14:49 UTC.

O diagnóstico manual `TurnRelayDiagnostic`, separado do aplicativo distribuído, abriu duas sessões WSS autenticadas com apelidos temporários, trocou convite/aceite e recebeu credenciais efêmeras individuais. Os dois peers nativos usaram `AudioMode.HEADLESS` e `relayOnly(true)`. O sucesso exige candidato selecionado `relay`, PCM não silencioso decodificado e contadores positivos de RTP de áudio em ambos os sentidos. Usa o runtime e as bibliotecas do pacote Windows 1.4; não acessa microfone ou alto-falantes.

A primeira tentativa falhou na preparação. Instrumentação com códigos fixos, publicada em `cc7408a` / `dep-dbu7ub7f3r2c73da5blg`, identificou timeout de 5 segundos nas duas requisições. Uma nova tentativa às 16:24 UTC concluiu com **OK — relay TURN selecionado nos dois peers. Áudio PCM e RTP recebidos nos dois sentidos.** Isso confirma chave, autenticação, resposta HTTP 201, configuração ICE, sinalização online e mídia por TURN. Não identifica qual fase da primeira requisição causou o atraso.

O teste adicional do provedor confirmou que os diagnósticos omitem segredo, ID da chave, token e resposta bruta: cinco testes focados passaram antes de publicar a instrumentação. O procedimento reproduzível está em [VOICE-SETUP.md](VOICE-SETUP.md#diagnóstico-local-do-relay-turn).

Para tolerar demora transitória, o prazo HTTP passou de 5 para 12 segundos e o prazo de preparação da sala de 8 para 15 segundos. As três regressões novas falharam com os limites anteriores e passaram após a alteração: resposta HTTP local após 5,5 segundos, credenciais prontas aos 10 segundos e expiração aos 15 segundos com descarte do resultado tardio. A negociação permanece com 45 segundos contados após as credenciais. A suíte completa de protocolo/servidor passou às 13:29:39 (São Paulo): **45 testes, zero falhas/erros/ignorados**. Revisão independente aprovada. O cliente de produção e os binários 1.4 permanecem os mesmos.

Publicação final do servidor: commit `21aa5926113e4b7f3b531214488b3c4fbe332aef`, deploy `dep-dbu82bmk1f9s739niidg`, live às 16:32:15 UTC. A primeira execução do diagnóstico após esse deploy passou com relay e áudio PCM/RTP nos dois sentidos. `/health` retornou HTTP 200 e `{"status":"ok"}`. O aplicativo portátil 1.4 também entrou no servidor público e exibiu conexão ativa e controles de voz. O MSI/ZIP no workspace original continuam com os hashes acima; nenhuma chave foi incorporada.

Instruções para o proprietário: [VOICE-SETUP.md](VOICE-SETUP.md). Instruções de chamada: [CHAMADAS-DE-VOZ.txt](CHAMADAS-DE-VOZ.txt).
