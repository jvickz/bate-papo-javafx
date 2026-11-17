# Visual de terminal retrô

Pedido: interface oldschool com letras digitalizadas e fundo pixelado animado durante a conversa, com atmosfera de suspense tecnológico inspirada em Dark Web.

Escopo direto: trocar a apresentação do cliente JavaFX existente, conservar conversa online e gerar novo pacote Windows. A interpretação visual comunicada é preto, verde-fósforo, tipografia pixelada e bordas retas. Não reproduzir imagens ou marcas do filme.

Decisões de implementação: fonte VT323 embutida e licenciada OFL para títulos/controles; Consolas nas mensagens para leitura de português e Unicode. Canvas desenhado atrás do conteúdo: grade, scanlines discretas e partículas quadradas; movimento lento a 20 fps apenas na sala conectada, quando a janela está visível e não minimizada. Breve realce localizado a cada mensagem, sem flashes de tela. Botão acessível para pausar animação. Fundo não recebe cliques. Parar o timer ao sair, desconectar ou fechar.

Abordagens consideradas: apenas CSS não atende à animação; vídeo/GIF adiciona peso e dificulta pausar/redimensionar; Canvas nativo foi escolhido pelo implementador para manter distribuição sem dependências novas. Não alterar servidor, autenticação ou protocolo.

Aceite: campos e mensagens legíveis em 900x720 e 1080x760; troca real continua funcionando; pausa/retomada e ciclo de vida verificados; novo ZIP e MSI 1.1.0 entregues, sem fechar a sessão atual do usuário.
