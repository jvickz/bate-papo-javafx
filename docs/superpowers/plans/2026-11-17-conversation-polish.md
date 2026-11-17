# Conversation Polish Implementation Plan

**Goal:** conversa remota mais clara, legível e resistente a perda de texto.
**Architecture:** cliente mantém estado efêmero por canal; diagnóstico HTTP independente e assíncrono; conexão mantém protocolo compatível.
**Tech Stack:** Java 21, JavaFX, Javalin existente, Render gratuito.
**Spec:** ../specs/2026-11-17-conversation-polish-design.md

## Tasks
- [ ] Conexão: teste RED/GREEN para rejeição de mensagem após enfileirar; pending antes de erro recuperável, estado e erros de rede úteis sem segredos. ChatConnection + seus testes.
- [ ] Diagnóstico: ServerCheck implements AutoCloseable; CompletableFuture<Result> check(String), Result(boolean reachable,String message), sem credenciais, timeout e validação health. Testes HTTP reais. Tema CSS com legibilidade e hierarquia preservando pixel art.
- [ ] Interface: testes RED/GREEN de rascunho/histórico ao voltar de canal, convite sem código e contador Unicode. Memória efêmera, verificar servidor, copiar convite, recuperar mensagem, acompanhamento manual de rolagem e layout 900×720.
- [ ] Revisão integrada e suíte; empacotar 1.3.0, conferir capturas e hashes, sincronizar workspace e publicar código aprovado pelas verificações.

## Constraints / Review Focus
- WSS fora de loopback, mesma autenticação, nenhum segredo em convite/disco/log.
- Callback de diagnóstico obsoleto não altera destino/estado novo; fechar libera recursos.
- Falha/troca/retorno preserva texto no canal correto; sair limpa memória.
- Contador por code points; botão recuperar não sobrescreve rascunho existente.
- Mensagem nova não arrasta quem está lendo acima; envio próprio acompanha fim.
