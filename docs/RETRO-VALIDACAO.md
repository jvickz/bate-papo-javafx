# Cliente retrô 1.1 — 17/11/2026

Implementado: preto/verde-fósforo, fonte VT323 local, painéis retangulares, grid/scanlines e partículas em Canvas. Animação limitada a 20 fps, apenas na sala conectada com janela visível; reage discretamente às mensagens. Botão EFEITOS: ON/OFF pausa e retoma. Janela minimizada, saída e encerramento param o timer. Fonte e licença incluídas, sem download em tempo de execução.

## Evidência

- Teste UI inicialmente falhou por ausência do fundo; passou após implementação.
- Suíte final `scripts/maven.ps1 -B -ntp test`: BUILD SUCCESS, 23 casos, sendo 22 aprovados e 1 teste opt-in de servidor externo ignorado.
- Teste JavaFX com servidor real verifica envio/recebimento, carregamento da fonte, frames em movimento, congelamento ao pausar, pausa preservada após restaurar janela, minimização, saída e encerramento.
- Revisão independente apontou compressão do login no tamanho mínimo durante conexão. Reproduzido e corrigido com rolagem e altura preferida; teste verifica aviso final alcançável pela rolagem.
- Capturas inspecionadas: entrada, conversa em 1080×760, conversa em janela de 900×720 e entrada mínima com aviso/rolagem.
- Pacotes finais gerados em `dist/20261117-110024/`: MSI 1.1.0 e ZIP x64, ambos com runtime Java.

## Limites da verificação

O tema foi executado e inspecionado pela aplicação JavaFX real nos testes. A abertura do novo EXE via comando foi rejeitada pela revisão automática com “blocked by policy”, sem razão adicional. O lançador de desktop selecionou a instalação antiga 1.0; portanto isso não foi contado como validação do novo pacote. Instalação/atualização MSI não executada. Abra o novo ZIP ou instale o MSI para usar o tema.

O servidor e o protocolo não mudaram; não houve nova publicação do servidor nem teste WSS externo nesta alteração. O teste de conversa desta alteração usa conexões WebSocket reais locais.
