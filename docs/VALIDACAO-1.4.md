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

Não houve teste com microfone ou alto-falantes físicos nem audição entre duas pessoas. A conta Cloudflare foi acessada, mas a tela de ativação exige cadastrar pagamento e aceitar cobrança de excedente. Nenhum serviço pago foi ativado pelo agente; sem essa ativação e credenciais, o relay real permanece pendente. O teste local de RTP não comprova conectividade TURN entre redes distintas.

O instalador não contém chave permanente de TURN nem código de acesso da sala. Os JARs originais e seus avisos de licença são mantidos. O pacote é local; não foi publicada uma release binária no GitHub.

Instruções para o proprietário: [VOICE-SETUP.md](VOICE-SETUP.md). Instruções de chamada: [CHAMADAS-DE-VOZ.txt](CHAMADAS-DE-VOZ.txt).
