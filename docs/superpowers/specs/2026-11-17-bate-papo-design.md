# Bate-papo online para Windows — proposta de design

Data: 17/11/2026
Estado: proposta para aprovação; implementação ainda não iniciada.

## Intenção e escolhas confirmadas

Permitir que duas pessoas, em lugares e redes diferentes, instalem um aplicativo no Windows e conversem pela internet. O projeto também serve para aprender comunicação em rede, concorrência e programação orientada a eventos.

O usuário escolheu JavaFX para computadores Windows e um servidor online com WebSocket seguro. Pediu o uso do Superpowers para apoiar o desenvolvimento.

## Primeira versão proposta

- Interface em português, com apelido, endereço do servidor e código de acesso.
- Uma sala privada compartilhada por quem conhece o código; atende à conversa entre duas pessoas e permite pequenos grupos.
- Envio e recebimento de texto em tempo real, com horário e identificação do remetente.
- Lista de participantes conectados e avisos de entrada e saída.
- Estados claros de conexão, tentativa de reconexão e falha de envio.
- Reconexão automática com espera crescente e opção de desconectar.
- Aplicativo Windows distribuído com runtime Java incluído.
- Servidor preparado para hospedagem e teste real pela internet, conforme disponibilidade de conta e hospedagem.

Esta definição de sala, acesso e retenção é uma proposta do implementador, não uma exigência já expressa pelo usuário.

## Fora desta primeira versão

Compartilhamento de arquivos, áudio, vídeo, cadastro de contas, contatos, várias salas e mensagens privadas separadas. Não haverá entrega para pessoas offline nem histórico persistido no servidor. As mensagens recebidas permanecem apenas na sessão aberta do aplicativo, com limite de memória. Reiniciar o aplicativo limpa esse histórico.

Não apresentar uma demonstração local como se fosse uma publicação online concluída.

## Abordagens consideradas

1. **JavaFX com WebSocket seguro e servidor online — escolhida pelo usuário.** Facilita o uso de hospedagem web com TLS e preserva os conceitos de comunicação cliente-servidor, eventos e concorrência. Requer um servidor disponível e tratamento de desconexões.
2. **JavaFX com sockets TCP diretos.** Aproxima-se do tutorial fornecido, mas exige hospedagem que aceite a porta TCP utilizada e configuração própria de transporte seguro. Não foi escolhida.

## Arquitetura proposta

Um projeto Maven com módulos de protocolo compartilhado, servidor Java e cliente JavaFX. Versões exatas e biblioteca HTTP/WebSocket do servidor serão selecionadas durante o planejamento e verificadas na documentação oficial. Preferência inicial: JDK 21 e versão compatível do JavaFX.

Fluxo: aplicativo A → conexão WSS → servidor → conexão WSS → aplicativo B. Ambos iniciam conexões de saída. A arquitetura não exige abrir portas no roteador de cada participante.

O servidor mantém uma única sala em memória, valida acesso e distribui eventos para clientes autenticados. Deve operar em uma única instância nesta versão; múltiplas instâncias exigiriam coordenação adicional. Um endpoint de saúde permite verificar se o serviço iniciou.

O cliente usa a API WebSocket do Java e processamento assíncrono. Mudanças visuais são encaminhadas à thread do JavaFX; nenhuma conexão de rede bloqueia a interface. O fechamento da janela encerra conexões e tarefas pendentes.

## Acesso e transporte

O administrador configura um código aleatório de acesso ao servidor, por variável de ambiente, e o compartilha com a outra pessoa por um canal de sua escolha. O código não aparece em URLs nem em logs e não é salvo em texto puro nas preferências do cliente. A tela explica que qualquer pessoa com esse código pode entrar na sala; apelidos não são identidades verificadas.

Uma conexão só recebe mensagens e participantes após autenticar. Apelidos simultaneamente repetidos são recusados com explicação. Conexões sem autenticação expiram. O servidor valida tamanho de campos e mensagens, limita frequência de envios e quantidade de conexões, e rejeita eventos inválidos sem interromper outras sessões.

O cliente exige WSS para servidores remotos e aceita WS somente no endereço de loopback para desenvolvimento local. Certificados TLS são verificados normalmente. A conexão é protegida entre aplicativo e servidor; não há criptografia de ponta a ponta, e o servidor processa o texto das mensagens.

## Mensagens e reconexão

Mensagens de protocolo em JSON, com tipo, campos validados e identificador de mensagem. O servidor atribui identidade da sessão e horário, sem confiar em remetente informado pelo cliente.

O cliente distingue envio pendente, confirmado pelo servidor e falha. Confirmação significa recebimento pelo servidor, não leitura pelo destinatário. Mensagens pendentes não são reenviadas automaticamente após desconexão, evitando duplicações e falsas garantias de entrega. A reconexão tenta autenticar novamente e atualiza a lista de participantes; mensagens enviadas por outras pessoas durante a ausência não são recuperadas.

O servidor detecta conexões abandonadas e remove participantes desconectados. O cliente cancela tentativas antigas ao desconectar ou iniciar uma nova sessão.

## Experiência proposta

Tela inicial com apelido, endereço e código de acesso; botão Entrar e feedback de erro próximo aos campos. Tela de conversa com nome da sala, estado de conexão, lista de participantes, mensagens e campo de composição. Enter envia; Shift+Enter insere quebra de linha. Envio vazio é recusado. A interface permite sair e ajustar a conexão.

Estética, nome visual do aplicativo, dimensões e cores ficam a cargo do implementador. Priorizar legibilidade, navegação por teclado e mensagens de erro em português.

## Distribuição e hospedagem

Preparar scripts reproduzíveis para compilar, executar localmente e empacotar no Windows. Gerar aplicativo com executável e runtime incluído usando jpackage; produzir instalador se os pré-requisitos de empacotamento estiverem disponíveis. Identificar claramente a diferença entre instalador e pacote portátil. A assinatura digital não está incluída nesta proposta.

Não foi localizado Java, Maven ou Gradle no PATH nesta inspeção inicial; será preciso provisionar as ferramentas de desenvolvimento antes da compilação. Preferir ferramentas locais, sem modificar configurações globais desnecessariamente.

Preparar Dockerfile e instruções/configuração para um provedor que suporte WebSocket e TLS, tendo Render como candidato. Não foi confirmada conta, repositório remoto nem serviço de hospedagem. A escolha de plano deve explicitar eventuais custos e suspensão por inatividade; não contratar recursos pagos sem autorização.

A implementação e os testes locais podem avançar independentemente da hospedagem. Se uma conexão de conta ou escolha de recurso impedir a publicação, apresentar o aplicativo pronto e a etapa exata pendente. Concluir a validação online quando o acesso necessário estiver disponível.

## Verificação e critérios de aceite

1. Compilação reproduzível dos módulos e geração de pacote Windows.
2. Dois clientes autenticados enviam e recebem texto, acentos e emojis por um servidor real.
3. Cliente sem código correto não recebe mensagens nem participantes; entradas inválidas e excesso de envios são tratados.
4. Entrada, saída e perda de conexão atualizam presença; reconexão funciona sem duplicar sessões ou congelar a interface.
5. Falha de envio aparece como falha ou entrega não confirmada, nunca como sucesso indevido.
6. Verificação visual e funcional da interface JavaFX, além de testes automatizados do protocolo, servidor e comportamento de conexão.
7. Executável empacotado abre com o runtime distribuído.
8. Após publicação, dois clientes conectam à URL pública WSS, trocam mensagens e têm o isolamento de acesso validado. Teste a partir desta máquina não substitui a confirmação final em duas redes físicas distintas; declarar exatamente o que foi testado.

## Referências consultadas

- Tutorial fornecido: https://luisgcenci.medium.com/building-a-group-chat-app-with-javafx-multithread-socket-programming-in-java-c8c11fd8c927
- JavaFX: https://openjfx.io/
- Cliente WebSocket Java: https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/WebSocket.html
- Empacotamento: https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html
- Hospedagem WebSocket: https://render.com/docs/websocket

A imagem de exemplo não pôde ser aberta pela ferramenta de navegação nesta sessão; não foi usada como referência visual verificada.
