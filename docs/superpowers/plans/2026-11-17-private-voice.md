# Plano — chamadas privadas 1.4

Spec: `docs/superpowers/specs/2026-11-17-private-voice-design.md`

Baseline: 4e6ae4e; branch feat/private-voice no clone local isolado. Preservar chat 1.3, retro ocean e compatibilidade de texto. Não contratar serviços nem expor segredos. Trabalhos independentes possuem arquivos exclusivos; revisão final cobre integração.

- [x] T1: contrato VoiceConfig, sinalização Room, provedor TURN, configuração Main e testes de servidor.
- [x] T2: VoiceEngine/NativeVoiceEngine, dependência Maven e teste nativo headless.
- [x] T3: ChatConnection, controlador de chamada, painel JavaFX, testes de fluxo/consentimento/limpeza.
- [ ] T4: revisão independente, correções, testes completos, pacote Windows 1.4 e documentação de ativação/teste.
- [ ] T5: sincronizar fontes/artefatos com workspace, publicar código e atualizar servidor autorizado; relatar exatamente o estado de ativação TURN.

## Registro

2026-11-17: conta Cloudflare ausente. Implementação e testes locais prosseguem. Ruling: integração desativada explicitamente sem TURN, evitando prometer conectividade entre redes restritas. Ativação depende de credenciais inseridas fora da conversa; custo não autorizado.

12:55:44: reator completo passou (80 casos, 79 aprovados, um online opt-in ignorado). Revisão independente de código e especificação sem achados acionáveis. Conta Cloudflare acessada pelo usuário; ativação TURN exige pagamento e aceite de excedente. Decisão pendente; aplicação e testes locais concluídos, pacote em preparação.
