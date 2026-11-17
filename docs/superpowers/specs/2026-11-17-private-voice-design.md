# Chamadas privadas — 1.4

O usuário aprovou chamadas privadas com aceite/recusa, WebRTC e TURN, para Windows/JavaFX. Ainda não possui conta Cloudflare. A implementação não depende da criação da conta; a ativação e validação do relay em produção dependem das credenciais e de eventual aceite de cobrança pelo usuário.

## Comportamento

Selecionar alguém na lista do canal e ligar. O destinatário pode aceitar ou recusar. Uma chamada por pessoa; até 30 segundos para atender. Só abrir microfone após o aceite. Mostrar conexão, duração, silenciar e encerrar. Sair, trocar de canal ou perder a conexão encerra a chamada e libera áudio. Nenhuma gravação. O texto continua funcionando durante chamadas e quando TURN não está configurado.

## Arquitetura e contrato

- JavaFX com motor nativo `dev.onvoid.webrtc:webrtc-java:1.0.1`; áudio somente, codec negociado pelo WebRTC, sem navegador embutido. Não adicionar dependência nativa ao servidor.
- Sinalização privada no WSS autenticado existente; mídia WebRTC direta ou via TURN. Nunca transmitir áudio pelo WebSocket.
- `hello.id=channels-v1` permanece; `hello.text=voice-v1` anuncia suporte a voz.
- Eventos novos sempre usam `id` como UUID da chamada e `name` como o outro participante; nenhum token de autenticação deve constar desses eventos.
- Cliente envia `callinvite` (id UUID novo, name destino), `callaccept`, `callreject`, `callend`, `callsignal` (text JSON da biblioteca), `callconnected`.
- Servidor envia `voice` (id `ready` ou `unavailable`, text explicação), `callringing` ao autor, `callincoming` ao destino, `callready` aos dois (text VoiceConfig JSON), `callsignal` exclusivamente ao par, `callended` (text motivo amigável), `callerror` (text amigável). `callready.name` identifica o par.
- `VoiceConfig` no módulo protocol: record `VoiceConfig(List<IceServer> iceServers, boolean caller)` e record aninhado `IceServer(List<String> urls, String username, String credential)`. Métodos `encode()` e estático `decode(String)`; toString redigido. `caller=true` para autor; destinatário polite=true. As credenciais temporárias não são logs.
- `VoiceEngine` no client: `void start(VoiceConfig config, Consumer<String> signals, Consumer<String> states)`, `void signal(String json)`, `void setMuted(boolean muted)`, `void close()`. Estados: `connecting`, `connected`, `disconnected`, `failed`. Implementação `NativeVoiceEngine` assíncrona e callbacks seguros; fechar deve invalidar callbacks antigos, ser idempotente e liberar recursos. Nunca inicializar hardware no construtor.
- Servidor valida autenticação, capacidade, canal, participante/role/estado da chamada e tamanho/rate dos sinais. Terceiros não podem atender, encerrar ou sinalizar chamadas alheias. Chamada acaba em disconnect, substituição de sessão, troca de canal, timeout de convite/negociação, shutdown.
- TURN Cloudflare: `TURN_KEY_ID` e `TURN_API_TOKEN` apenas no ambiente do servidor. Credenciais temporárias obtidas após aceite, fora do monitor da sala, timeout e resposta limitados; resultados de chamadas encerradas descartados. TTL 3600s; encerrar sessão antes de expirar (55 minutos). Ausência ou erro de configuração não impede chat; informa indisponibilidade de voz. Provedor injetável permite testes sem cobrança. Não oferecer STUN público como garantia de funcionamento entre redes.
- Negociação tem limite de 45 segundos; ambos enviam `callconnected` ao conectar para cancelar esse prazo. Credenciais não renovadas silenciosamente nesta versão.

## Validação

Testes de sinalização com participantes reais e provedor fake; recusa, ocupado, timeout, isolamento, cancelamento durante credenciais, desconexão/canal e legado. Testes do controlador com motor fake e consentimento explícito. Integração nativa entre dois peers headless sem hardware; empacotamento Windows com biblioteca nativa. Teste de relay real e áudio humano só pode ser declarado após credenciais e participação disponíveis.
