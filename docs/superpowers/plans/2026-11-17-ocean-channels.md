# Ocean Channels Implementation Plan

> **For agentic workers:** use superpowers:subagent-driven-development for independent units and integration review.

**Goal:** cliente 1.2 com mar/barco pixelado, canais separados e projeto público.
**Architecture:** Room atua como hub com membros em canais; Event ganha campo channel opcional compatível; ChatConnection gerencia confirmação de troca; ChatApp mostra catálogo/criação. PixelBackdrop e CSS independentes.
**Tech Stack:** Java 21, JavaFX, Javalin, Maven, Render Docker existente.
**Spec:** ../specs/2026-11-17-ocean-channels-design.md

## Global Constraints
- Manter código de acesso; 64 conexões, 20 canais, nomes [a-z0-9][a-z0-9-]{1,23}; animação até 20 fps pausável.
- Clientes legados permanecem em geral, sem receber campo channel desconhecido.
- Não persistir histórico/segredos; fallback geral após reinício; nenhum gasto novo.

## Tasks
- [ ] Servidor/protocolo: testes RED/GREEN para criar/entrar/listar, isolamento de mensagem/presença, validação/limite, autenticação e legado. Implementar Event.channel, validateChannel e hub Room.
- [ ] Cena e tema: barco pixelado reconhecível no mar, cores azul/petróleo/turquesa/âmbar; conservar métodos/ciclo de vida PixelBackdrop e classes CSS.
- [ ] Cliente: changeChannel/createChannel com ack/timeout/cancelamento, catálogo e criação no sidebar, canal no cabeçalho, histórico separado por limpeza, testes reais de troca/isolamento e UI.
- [ ] Revisão independente e suíte completa; corrigir achados; conferir capturas e pacote Windows 1.2.0.
- [ ] Auditoria de arquivos/histórico, GitHub público, publicar servidor existente e teste WSS de canais.
- [ ] Abrir duas sessões para teste, sincronizar workspace e entregar pacotes/instruções.

## Review Focus
- Corridas entre enviar/trocar/reconectar, confirmação perdida, mensagens do canal antigo.
- Compatibilidade de JSON com clientes antigos, catálogo somente autenticado.
- Remoção reentrante de membros em broadcast/troca e shutdown.
- Limites de canais/conexões, criação repetida/nomes inválidos.
- Legibilidade da cena e acesso aos canais no mínimo 900×720; pausa/minimização/fechamento.
