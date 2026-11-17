# Plano — chamadas privadas 1.4

Spec: `docs/superpowers/specs/2026-11-17-private-voice-design.md`

Baseline: 4e6ae4e; branch feat/private-voice no clone local isolado. Preservar chat 1.3, retro ocean e compatibilidade de texto. Não contratar serviços nem expor segredos. Trabalhos independentes possuem arquivos exclusivos; revisão final cobre integração.

- [x] T1: contrato VoiceConfig, sinalização Room, provedor TURN, configuração Main e testes de servidor.
- [x] T2: VoiceEngine/NativeVoiceEngine, dependência Maven e teste nativo headless.
- [x] T3: ChatConnection, controlador de chamada, painel JavaFX, testes de fluxo/consentimento/limpeza.
- [x] T4: revisão independente, correções, testes completos, pacote Windows 1.4 e documentação de ativação/teste.
- [x] T5: sincronizar fontes/artefatos com workspace, publicar código e atualizar servidor autorizado; relatar exatamente o estado de ativação TURN.

## Registro

2026-11-17: conta Cloudflare ausente. Implementação e testes locais prosseguem. Ruling: integração desativada explicitamente sem TURN, evitando prometer conectividade entre redes restritas. Ativação depende de credenciais inseridas fora da conversa; custo não autorizado.

12:55:44: reator completo passou (80 casos, 79 aprovados, um online opt-in ignorado). Revisão independente de código e especificação sem achados acionáveis. Conta Cloudflare acessada pelo usuário; ativação TURN exige pagamento e aceite de excedente. Decisão pendente; aplicação e testes locais concluídos, pacote em preparação.

13:03: pacote dist/20261117-125710 gerado e versão portátil aberta; MSI ProductVersion 1.4.0 conferida. Código e4f25fc publicado, deploy dep-dbu7isbl550s73a5rt9g live e /health HTTP200. Fonte sincronizada no workspace original sem conflitos. Ativação/pagamento TURN e teste humano entre redes permanecem pendentes, explicitamente separados da implementação validada.

13:24: proprietário concluiu ativação/pagamento na Cloudflare e aprovou criar chave e transferir ID/segredo para Render. Credenciais configuradas sem arquivos locais ou exposição em logs. Diagnóstico nativo com duas sessões WSS e relay obrigatório passou: PCM e RTP nos dois sentidos. A primeira tentativa excedeu o prazo HTTP de 5s; fase específica desconhecida. Prazo ampliado para 12s HTTP/15s preparação, preservando negociação de 45s. Regressões RED/GREEN e revisão independente concluídas; suíte protocolo/servidor com 45 testes passou às 13:29:39. Teste humano entre redes permanece pendente.

13:33: deploy final dep-dbu82bmk1f9s739niidg do commit 21aa592 confirmado live. Primeira tentativa do diagnóstico após deploy passou, com relay e áudio nos dois sentidos; /health HTTP200. Aplicativo 1.4 conectado ao servidor público. Chave permanente somente na Cloudflare e no Render. Fontes sincronizadas, MSI/ZIP inalterados. Integração concluída; teste humano de microfone e duas redes reservado aos participantes.
