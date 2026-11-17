# Ativar chamadas de voz

O cliente 1.4 usa WebRTC nativo, com sinalização privada pelo servidor WSS do chat. TURN permite estabelecer mídia em redes nas quais a conexão direta falha. A implantação precisa de **uma instância** do servidor; o estado das chamadas é mantido em memória.

Sem as variáveis abaixo, o servidor mantém o texto funcionando e informa que a voz está indisponível. Instalar a versão 1.4 sozinho não ativa o relay.

## Configuração Cloudflare

1. O proprietário cria uma [conta Cloudflare](https://dash.cloudflare.com/sign-up), confirma seu e-mail e acessa o painel.
2. Em **Realtime → TURN Server**, ativa o serviço e cria uma chave TURN. Na conta consultada, a Cloudflare exige cadastrar pagamento, aceitar os termos e autorizar cobrança mensal de excedentes: US$ 0 hoje, franquia de 1.000 GB/mês e US$ 0,05/GB adicional. O proprietário deve concluir pessoalmente esse aceite se decidir usar o serviço. Não é necessário comprar domínio nem mover o DNS do projeto.
3. No [serviço existente do Render](https://dashboard.render.com/web/srv-dbu51hrm8hqs73e50qgg), abra **Environment** e adicione `TURN_KEY_ID` e `TURN_API_TOKEN` com os valores fornecidos pela Cloudflare. Mantenha `CHAT_ACCESS_TOKEN` intacta. O token é o segredo de geração de credenciais TURN, não uma chave de API global da conta.
4. Salve e publique o servidor atualizado. As publicações automáticas estão desativadas neste projeto. As duas pessoas devem reabrir/entrar no cliente 1.4.
5. Selecione um participante no mesmo canal, ligue e aceite na outra máquina. Teste em duas redes diferentes, falando e ouvindo em ambos os sentidos; teste silenciar, recusar, encerrar e queda de conexão.

As credenciais temporárias são solicitadas somente após aceite, por HTTPS, com validade de uma hora. A chamada termina em 55 minutos para evitar operar com credenciais expiradas. A chave permanente permanece no servidor; erros de provedor são exibidos sem respostas brutas ou segredos. Revogar a chave na Cloudflare e remover as variáveis do Render desativa novas chamadas; publique novamente para encerrar as sessões existentes.

A documentação consultada em 17/11/2026 informa franquia mensal de 1.000 GB compartilhada entre TURN e SFU, com excedente de US$ 0,05/GB. Isso não equivale a um teto de custo automático: confira o painel e monitore uso antes de habilitar cobrança. Esta implementação não contrata plano pago nem cadastra meio de pagamento. [Preços oficiais](https://developers.cloudflare.com/realtime/sfu/platform/pricing/).

## Diagnóstico

- **Voz indisponível:** verifique as duas variáveis e se o servidor atualizado foi publicado. Clientes antigos continuam usando texto.
- **Não foi possível preparar a chamada:** confira chave ativa, token específico de TURN, conta habilitada e saída HTTPS do servidor. Não copie o token para logs nem para issues.
- **Conectando sem completar:** verifique firewall e relay. TURN fornece UDP/TCP e TLS, mas redes corporativas podem bloquear o serviço; somente um teste real confirma a rede específica.
- **Conectada sem som:** selecione microfone/saída padrão do Windows e confira acesso de aplicativos desktop ao microfone, volume e botão Silenciar. Reinicie a chamada após trocar dispositivo.
- **Servidor reiniciou:** ambos reconectam no chat; é necessário iniciar e aceitar uma nova chamada.

## Implementação e testes

O protocolo limita os sinais e encaminha SDP/ICE somente aos dois participantes de uma chamada aceita. O servidor não grava nem recebe o áudio. Identidade é baseada na sessão com código da sala; apelidos não são identidade verificada. O chat de texto continua sendo processado pelo servidor.

Os testes usam provedor TURN simulado e motores sem hardware. Validam consentimento, isolamento de sinalização, concorrência e ciclo de vida sem abrir microfone. A integração nativa com áudio sintético verifica WebRTC localmente. Essas verificações não substituem o teste de relay com credenciais reais nem a audição entre duas pessoas.

Referências: [credenciais TURN Cloudflare](https://developers.cloudflare.com/realtime/turn/generate-credentials/), [TURN e transportes](https://developers.cloudflare.com/realtime/turn/), [WebRTC Java](https://jrtc.dev/guide/get-started).
