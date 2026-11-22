# Wavecove Web

O cliente web usa as contas, os canais e a caixa de mensagens privadas do Wavecove. Abra o site, entre ou crie uma conta. Escolha um canal na lateral ou use **Criar canal**. A lista de participantes mostra quem está naquele canal. No celular, toque em **Canais de texto** ou **Participantes** para abrir a lista correspondente. [Abrir o Wavecove](https://bate-papo-java.onrender.com/)

Em **Privadas**, digite o usuário da outra conta e abra a conversa. O destinatário pode ler depois de entrar. A Sala da Cachoeira com criptografia de ponta a ponta, as chamadas de voz, os anexos e o compartilhamento de tela ficam disponíveis pelo aplicativo para Windows.

## Executar em desenvolvimento

No Windows, execute o comando abaixo na pasta do projeto. O script atualiza o JAR e usa o banco de desenvolvimento do Wavecove quando não há um banco externo configurado.

```powershell
.\scripts\start-server.ps1
```

Abra **http://127.0.0.1:8080/**. O script usa a interface de loopback por padrão. Na hospedagem, sirva o mesmo processo via HTTPS, com suporte a WebSocket e uma instância. Os arquivos HTML, CSS, JavaScript, a arte e a fonte acompanham o servidor dentro do JAR.

## Análise do sistema

| Área | Comportamento e impacto |
| --- | --- |
| Arquitetura | Javalin entrega a página, a API HTTP e o WebSocket. Os clientes web e JavaFX usam o mesmo protocolo. Uma alteração no formato de eventos precisa ser compatível com ambos. |
| Contas | O servidor usa PBKDF2 com sal por conta e limita tentativas de acesso. A aba mantém o token em memória. Recarregar a página exige novo login. No navegador, a autenticação exige HTTPS ou HTTP local. |
| Canais | O servidor transmite mensagens aos participantes conectados. O navegador mantém até 500 mensagens recebidas por canal e os rascunhos durante a sessão. Uma pessoa que acaba de entrar não recebe uma transcrição anterior. |
| Conversas privadas | As mensagens entre contas ficam no banco. A entrega offline e o histórico dependem desse banco. Já a Sala da Cachoeira no desktop usa um canal criptografado entre duas pessoas e descarta a conversa ao encerrar. |
| Capacidade | O chat público admite até 64 conexões e 20 canais por instância. As salas anônimas usam um limite separado de 32 pares. Presença, canais e sessões ficam em memória. Reiniciar o processo interrompe as conexões, e duas réplicas independentes teriam listas diferentes. |
| Escala das privadas | O histórico é paginado de 50 em 50 mensagens. O servidor monta o catálogo completo de conversas de uma conta antes de comparar o ETag. O custo cresce com a quantidade de contatos. Paginar esse catálogo é a prioridade antes de expandir para contas com muitos contatos. |
| Acessibilidade e interface | O site usa rótulos de campos, navegação por teclado, aviso de conexão e layout compacto. Textos e mensagens usam fonte de leitura contínua. A arte pixelada fica fora do conteúdo lido por leitores de tela. |
| Operação | A rota de saúde informa que o processo atende HTTP. Login, escrita no banco e entrega via WebSocket exigem verificações funcionais próprias. O Render usa publicação manual neste serviço. |

As conclusões de capacidade acima vêm dos limites do código. A revisão cobre o servidor, o contrato do protocolo, os recursos de privacidade e a experiência web. Medições de carga sustentada, auditoria criptográfica independente e avaliações com usuários de tecnologias assistivas exigem estudos específicos.

## Verificação da versão

Em 21/11/2026, o build do protocolo e do servidor passou com 72 testes aprovados e dois testes opcionais de PostgreSQL ignorados. O teste HTTP conferiu a página inicial, os arquivos estáticos, a política de conteúdo e a exigência de autenticação na caixa de entrada.

Dois contextos independentes do Edge acessaram o JAR empacotado com um banco H2 temporário. O teste verificou cadastro, login, presença, criação e troca de canais, mensagens nos dois sentidos, isolamento entre canais, entrega privada a um destinatário offline, resposta após login e saída das contas. Foram verificadas larguras de 390 e 1320 pixels.

Para repetir após gerar o JAR, use Python com Playwright instalado e Edge ou Chromium disponível. O teste abre seu próprio servidor local e encerra os processos de teste ao terminar.

```powershell
python scripts/test-web.py --screenshots .local/web-previews
```
