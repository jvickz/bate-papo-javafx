# Wavecove 1.7 para Windows

## Revisão de uso e design

O título repetia a identidade da pessoa e o canal que já aparecia na conversa. Na entrada, a faixa de 70 px mostrava “Desconectado” antes de a pessoa tentar entrar. O apelido descartável e o pedido de um código compartilhado tornavam difícil saber se o nome pertencia àquela pessoa.

O título passa a exibir Wavecove. O canal ocupa o cabeçalho da conversa e a conta conectada aparece na lateral. O erro ou progresso do login aparece junto ao formulário. A barra mantém as ações de janela do Windows e usa o azul-marinho da marca nos sistemas compatíveis. O formulário tem modos de entrada e cadastro, rótulos permanentes e acesso secundário às configurações do servidor.

O farol sinaliza a chegada ao Wavecove. No cadastro, o cenário ganha luz de amanhecer. O veleiro sobre o mar noturno acompanha as conversas. Os cenários respeitam a preferência de animação do Windows e permitem pausa manual.

## Implementação e verificação

1. Autenticar cadastro e login por HTTPS no servidor Java. Associar o nome canônico da conta ao WebSocket, revogar a sessão ao sair e validar reconexão, tentativas inválidas e concorrência.
2. Persistir os verificadores de senha em PostgreSQL, usando o schema privado `wavecove` no Supabase. No desenvolvimento local, usar um arquivo H2 fora da pasta do projeto. Verificar cadastro, saída, reinício e novo login.
3. Exercitar os fluxos reais do JavaFX em duas janelas, incluindo convite, troca de conta, mídia e controles de voz. Conferir as cenas de acesso e a cor efetiva da barra no Windows.
4. Empacotar o cliente Windows 1.7. Ativar o backend público após configurar a conexão privada de banco e testar duas contas usando o serviço publicado.

## Resultado em 17 de novembro de 2026

O servidor online do Render passou a usar o banco do projeto Wavecove na organização Free do Supabase. Duas contas temporárias fizeram cadastro e login pela internet. O teste confirmou mensagens nos dois sentidos, isolamento entre canais, transferência de uma foto PNG íntegra e rejeição das duas sessões após o logout. As duas contas de teste foram removidas.

A suíte Maven passou no Windows. O teste de interface cobriu a janela mínima com e sem capturas habilitadas. A distribuição MSI identifica o produto como Wavecove 1.7.0. O teste online usou dois clientes na mesma máquina.
