# Conversa remota 1.3

Pedido: analisar e melhorar o aplicativo Windows JavaFX, visual, verificações e conversa entre locais diferentes. Mantemos o estilo retrô, barco e servidor WSS existente. A execução das melhorias está autorizada pelo pedido; escolhas abaixo são decisões de implementação.

Abordagens: uma nova plataforma com contas e banco exigiria migração e outro escopo; apenas mudar cores não resolveria perda de texto e entrada confusa. Evoluir o cliente existente entrega os benefícios com compatibilidade e sem novo custo.

Entregas: rascunho e histórico separados por canal durante a sessão (até 500 itens/canal e no máximo 20 canais); envio recusado pela fila mantém uma bolha recuperável; botão de recuperar texto sem reenvio automático; verificar servidor sem enviar credenciais; convite copiável com servidor/canal e instrução para código separado; mensagem vazia/longa desabilita envio e contador Unicode; rolagem manual preservada com botão de novas mensagens; textos de conexão claros, melhor hierarquia/contraste e fonte legível para instruções. Dados de conversa e senha não vão ao disco.

Não afirmar recebimento/leitura da outra pessoa apenas por eco do servidor. Não introduzir contas, histórico offline, canais privados, chamadas ou serviços pagos. Duas sessões locais via servidor público comprovam transporte externo, mas teste entre redes diferentes exige a segunda pessoa.

Verificação: testes reais de fila/queda, diagnósticos HTTP, JavaFX com troca e retorno de canal, limites Unicode e convite sem segredo. Build MSI/ZIP 1.3.0, capturas normal/mínima, revisão independente e publicação do código. Só atualizar servidor se houver mudança de servidor/protocolo.
