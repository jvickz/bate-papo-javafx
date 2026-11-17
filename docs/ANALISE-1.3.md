# Análise e melhorias da versão 1.3

## Diagnóstico

A base de rede já usa WSS, autenticação com código compartilhado, reconexão e isolamento de canais. Não foi necessário trocar o servidor ou adicionar um serviço pago. Apelidos são únicos no servidor, inclusive quando diferem só por maiúsculas. Isso evita confundir participantes, mas o fluxo anterior apagava também o código ao recusar um apelido ocupado.

Foram identificados problemas na experiência: rascunhos apagados ao trocar de canal; mensagens recusadas na fila após o compositor ser limpo; rolagem forçada durante leitura; ausência de convite copiável e de diagnóstico do servidor; instruções e metadados pequenos demais. A revisão também examinou a separação de dados entre sessões explícitas e a largura dos controles.

## Melhorias

- Apelido válido sugerido automaticamente. **Gerar outro** resolve conflitos sem cadastro. O código digitado é preservado quando o apelido é recusado.
- Login simplificado com servidor padrão. Endereço e verificação ficam em uma seção expansível.
- **Verificar servidor** consulta `/health` sem código, com limite de resposta, timeout, cancelamento e validação JSON. Não comprova autenticação nem WebSocket; entrar e trocar mensagens faz essa verificação.
- Diagnósticos de conexão distinguem DNS, TLS, resposta HTTP e demora, mostrando nova tentativa sem expor informações sensíveis.
- **Convidar pessoa** copia servidor e canal. O código deve ser enviado separadamente à pessoa convidada.
- Rascunho e histórico em memória separados por canal, com limites. Novo login explícito, saída e fechamento limpam a sessão; reconexão automática preserva o contexto local.
- Mensagens recusadas mantêm texto recuperável. Menu de copiar/editar e botão de recuperação não reenviam automaticamente nem substituem um rascunho já escrito.
- Contador respeita caracteres Unicode. Envio vazio ou acima de 2000 caracteres fica desabilitado.
- A leitura acima no histórico é respeitada, com aviso para novas mensagens.
- Fontes mais legíveis em mensagens/instruções, metadados maiores, melhor contraste e ações principais destacadas. Barco, mar pixelado e títulos retrô foram preservados.

## Limites atuais

Não há contas individuais, histórico no servidor, mensagens offline, arquivos, chamadas ou criptografia de ponta a ponta. Os canais não são privados: quem tem o código pode entrar neles. Reiniciar o servidor elimina seus canais; o cliente retorna a geral. A confirmação exibida é do servidor, não de leitura pelo destinatário.

Para validar locais diferentes, uma segunda pessoa deve abrir o pacote no outro Windows, usar o mesmo servidor/código, escolher outro apelido e o mesmo canal, e confirmar mensagens nos dois sentidos. O teste com duas sessões no mesmo computador passando pelo servidor público não substitui essa etapa.
