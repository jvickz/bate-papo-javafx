# Validação 1.3.0 — 17/11/2026

Código da versão: `1314508`. Build `scripts/package-windows.ps1 -Installer` concluído com BUILD SUCCESS às 12:08:24 (America/Sao_Paulo). Distribuição em `dist/20261117-120824`.

## Testes automatizados

48 casos: **47 aprovados**, zero falhas/erros e 1 `OnlineSmokeTest` ignorado por ausência das variáveis de ambiente. Protocolo: 5; servidor: 18; conexão: 13; interface: 4; diagnóstico: 7. O smoke ignorado não foi contado como aprovado.

Regressões reproduzidas antes das correções: apelido inicial vazio; rascunho perdido ao trocar de canal; envio recusado na fila sem registro recuperável; callback de geração antiga; ausência de diagnóstico HTTP/TLS; rascunho de sessão encerrada restaurado em login explícito novo. Testes também verificam convite sem código, limite Unicode, recuperação sem sobrescrever rascunho, aviso de novas mensagens e animação.

Revisão independente encontrou o caso de rascunho entre sessões; corrigido, com teste RED/GREEN. Releitura focal não encontrou outro problema importante. Capturas 1080×760 e janela mínima 900×720 foram inspecionadas; botão Enviar recebeu largura mínima para manter o texto inteiro.

## Servidor público e executável empacotado

O executável portátil 1.3.0 abriu no Windows com runtime incluído. No servidor `https://bate-papo-java.onrender.com`, uma tentativa com apelido já ocupado foi recusada; **Gerar outro** forneceu um novo apelido e o campo de código permaneceu preenchido. A nova tentativa conectou sem redigitar o código.

A sessão entrou em `porto` e trocou mensagens nos dois sentidos com uma sessão 1.2 já conectada, confirmando compatibilidade com o servidor publicado e o cliente anterior. A janela 1.3 ficou aberta para continuar o teste. Nenhum servidor/protocolo foi alterado e não houve novo deploy ou plano pago.

Esse teste usou sessões no mesmo computador passando pelo servidor público. Não houve instalação do MSI nem teste em um segundo Windows/rede distinta nesta rodada. O diagnóstico HTTP foi coberto por testes locais; não é garantia de autenticação ou de WebSocket. O guia CONEXAO-ONLINE.txt descreve a verificação a fazer com a outra pessoa.

## Integridade

MSI e ZIP sincronizados ao workspace original, com SHA-256 conferido:

```
2693103614C5B6373953B03DF1C3415972E6F00A95C797275A818DFA541C80FB  BatePapo-1.3.0.msi
AFFA2BFEAA3F364AE69C37A25AA83DB2926C8F0D0395842E5E048480EA651E95  BatePapo-Windows-x64.zip
```
