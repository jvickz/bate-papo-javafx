# Validação da versão 1.2.0 — 17/11/2026

## Build e testes

`scripts/package-windows.ps1 -Installer` concluiu com BUILD SUCCESS. Foram executados 35 casos: 34 aprovados, nenhum erro ou falha e 1 smoke externo ignorado por não receber as variáveis de execução. O teste externo automatizado não é contabilizado como aprovado.

Cobertura: protocolo (5), servidor (18), conexão cliente (9) e interface JavaFX (2). Inclui isolamento de mensagens/presença, compatibilidade com cliente legado, criação e troca de canais, confirmação atrasada, timeout de troca e recuperação após reinício. Revisão independente não encontrou defeitos confirmados impeditivos.

As capturas normal e mínima (900 × 720) foram inspecionadas. A cena contém barco pixelado, mar animado, lua e reflexos; a animação pausa quando a janela está desconectada, minimizada ou fechada.

## Publicação e teste real

- Repositório confirmado público: https://github.com/jvickz/bate-papo-javafx.
- Commit de código: `e3597b167f7d9bc9e9c2bd5fcbf7ed570d5fe10e`.
- Deploy Render: `dep-dbu6c28ae00c73efuav0`, estado `live` em 17/11/2026 às 14:35:48 UTC, no serviço gratuito existente.
- Duas janelas do executável empacotado 1.2.0 conectaram a `https://bate-papo-java.onrender.com` por WSS, com apelidos João e Teste 2.
- A segunda sessão criou `porto`; catálogo e presença atualizaram. Uma mensagem em `porto`, confirmada pelo servidor, não apareceu na primeira sessão enquanto ela estava em `geral`.
- A primeira sessão entrou em `porto`; ambas exibiram dois participantes. Uma mensagem de João chegou à segunda janela e a resposta de Teste 2 chegou à primeira. As duas sessões ficaram abertas para continuação manual.

Esse teste usou duas sessões no mesmo computador, passando pelo servidor público; não substitui a verificação entre dois computadores em redes distintas. Uma sondagem HTTP via PowerShell recebeu uma página de desafio do intermediário, portanto não foi considerada validação de `/health`. O deploy live e a conversa WSS foram confirmados separadamente.

## Distribuição e limites

Pacote: `dist/20261117-113233`. MSI e ZIP foram copiados para o workspace original; seus hashes SHA-256 conferem com `SHA256SUMS.txt`. O executável portátil foi aberto, sem instalar o MSI nesta validação.

Os canais são temporários e desaparecem ao reiniciar o servidor. Trocar de canal limpa o histórico exibido. Todos os canais usam o mesmo código de acesso; canais separam conversas, mas não oferecem permissões privadas por canal.
