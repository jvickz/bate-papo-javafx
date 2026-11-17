# Mar pixelado e canais — versão 1.2

Pedido direto: tornar projeto público, melhorar visual/cores, barco pixelado no mar como fundo, criar diferentes canais e preparar conversa de teste.

Direção visual escolhida pelo implementador: azul-noite/petróleo, água turquesa, luz âmbar da embarcação, tipografia VT323 nos títulos e monoespaçada legível nas mensagens. Cena Canvas pixelada própria, com barco reconhecível, estrelas, ondas/reflexos e balanço lento; animação pausável, até 20 fps, ciclo de vida existente preservado. Painéis translúcidos escuros mantêm leitura.

Canais: todos os usuários autenticados com o código compartilhado podem listar, criar e entrar. Nomes normalizados em minúsculas, 2–24 caracteres [a-z0-9-], iniciados por letra/dígito. Canal geral existe sempre; no máximo 20 canais e 64 conexões no total. Mensagens, presença e avisos de entrada/saída restritos ao canal. Lista de canais visível somente após autenticar. Apelidos continuam únicos no servidor. Canais em memória, sem histórico offline; reiniciar perde canais criados e reconecta em geral quando canal anterior não existe.

Compatibilidade: cliente novo anuncia capacidade hello.id=channels-v1 e usa campo JSON opcional channel. Cliente antigo continua em geral e recebe eventos sem esse campo. Não expor token/sessão no catálogo nem em logs.

Troca de canal: cliente bloqueia envio até confirmação joined; mensagens confirmadas de outro canal não aparecem no atual. Reconectar retoma canal confirmado anterior, com fallback geral comunicado. Criar já entra no canal. Histórico visual limpa ao trocar e não recupera mensagens antigas.

Publicação: revisar arquivos/histórico antes de mudar visibilidade do GitHub, manter credenciais fora. Atualizar main e servidor Render gratuito existente após testes. Distribuição Windows 1.2.0 com Java embutido. Preparar duas sessões de teste identificadas, sem enviar mensagens a destinatários externos. Não contratar plano pago.
