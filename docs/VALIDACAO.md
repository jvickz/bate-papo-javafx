# Validação — 17/11/2026

## Resultado local

- `scripts/maven.ps1 -B -ntp test`: **BUILD SUCCESS**, 21 testes executados com sucesso; 1 teste de serviço externo ignorado por não haver URL configurada nesse comando.
- Protocolo: 4 testes. Servidor: 11 testes. Transporte do cliente: 5 testes. Interface JavaFX real: 1 teste.
- Os testes usam servidores e clientes WebSocket reais, além de transportes controlados para reproduzir callbacks reentrantes.
- Foram verificados Unicode/emojis, autenticação, presença, apelido duplicado, retomada, reconexão após reinício, cancelamento de conexão, entrega incerta sem reenvio, deduplicação, observador não autenticado, limites de conexões e JSON inválido/excessivo.
- Uma falha intermitente de encerramento foi reproduzida deterministicamente em dois testes (encerramento e broadcast). A correção passou na suíte completa.
- Dois executáveis Windows empacotados foram abertos, autenticados como pessoas diferentes e trocaram mensagens nos dois sentidos. Conferidos tela de entrada, participantes, mensagens e confirmação pelo servidor.
- Instalador MSI e ZIP portátil gerados com jpackage/WiX 3.14.1 e runtime incluído. **A instalação/desinstalação do MSI não foi executada**; foi verificado o executável que ele contém.
- `render.yaml` validado contra o JSON Schema oficial do Render.
- Imagem Docker Linux construída com sucesso após a correção; os testes do servidor também passaram dentro do build Linux.

## Distribuição

Pasta `dist/20261117-094237/`:

- `BatePapo-1.0.0.msi` — 38.330.355 bytes.
- `BatePapo-Windows-x64.zip` — 37.433.050 bytes.
- `BatePapo/BatePapo.exe` — executável já extraído.
- `SHA256SUMS.txt` — hashes dos pacotes.

A correção posterior afeta apenas o servidor. O cliente e as bibliotecas incluídos no instalador/ZIP permanecem correspondentes ao código verificado.

## Revisão independente

Revisor separado leu protocolo, servidor, cliente, testes, scripts e configuração. Não confirmou achados de produto. Após essa revisão, a suíte integrada encontrou a falha de reentrância, que foi reproduzida e corrigida com testes.

Limites de cobertura remanescentes: sem simulação dedicada de receptor lento, timeout de confirmação sem queda de conexão ou saturação visual de 500 itens. O heartbeat permaneceu ativo durante a troca manual, mas não há teste dedicado de todas as suas falhas.

Decisões: quando outra pessoa já ocupou um apelido cuja sessão foi removida, a reconexão exige outro apelido; não expulsa a pessoa atual. Contas verificadas, entrega offline, histórico persistente e defesa contra ataques distribuídos ficam fora desta primeira versão.

## Publicação

Repositório privado criado: https://github.com/jvickz/bate-papo-javafx

Serviço publicado em **https://bate-papo-java.onrender.com**, no workspace **My Workspace**, plano **free**, região Virginia, uma instância e publicação automática desativada.

- Serviço: `srv-dbu51hrm8hqs73e50qgg`; deploy: `dep-dbu51i3m8hqs73e50r90`, estado **live** em 17/11/2026 às 13:05:14 UTC.
- Commit publicado: `b29d964f9baa71d2dae4bb77b64a848d308dc788`. Alterações seguintes são apenas documentação.
- HTTPS `/health` retornou `{"status":"ok"}`. O serviço criado via conector usa o health check TCP padrão do Render; o Blueprint alternativo configura `/health`.
- `OnlineSmokeTest`: **1 teste passou**, sem falhas/erros/ignorados, contra o endereço público, às 10:06:36 (America/Sao_Paulo). Dois clientes trocaram mensagens Unicode nos dois sentidos via WSS; um terceiro com código incorreto foi recusado sem receber presença/mensagens.
- O mesmo teste passou previamente contra o contêiner Docker Linux local.
- O segredo foi configurado no ambiente do Render e injetado no processo de teste, sem inclusão no código, instalador ou logs. Copie `CHAT_ACCESS_TOKEN` em Environment no painel do serviço para usar os aplicativos.
- Os hashes SHA-256 do MSI e ZIP foram recalculados e conferem com `SHA256SUMS.txt`.

O teste online usou dois clientes nesta máquina conectados ao servidor público. O teste com dois computadores em redes físicas distintas depende de participação do usuário e da outra pessoa.
