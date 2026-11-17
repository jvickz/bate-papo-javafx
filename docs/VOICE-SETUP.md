# Ativar chamadas de voz

O cliente 1.4 usa WebRTC nativo, com sinalização privada pelo servidor WSS do chat. TURN permite estabelecer mídia em redes nas quais a conexão direta falha. A implantação precisa de **uma instância** do servidor; o estado das chamadas é mantido em memória.

Sem as variáveis abaixo, o servidor mantém o texto funcionando e informa que a voz está indisponível. Instalar a versão 1.4 sozinho não ativa o relay.

No servidor deste projeto, o proprietário concluiu a ativação da Cloudflare em 17/11/2026 e autorizou a criação da chave `bate-papo-javafx`. O ID e o segredo foram configurados somente nas variáveis do serviço Render. O diagnóstico com dois peers nativos confirmou relay TURN e áudio sintético nos dois sentidos; ainda é necessário testar microfones e audição entre duas pessoas em redes diferentes.

## Configuração Cloudflare

1. O proprietário cria uma [conta Cloudflare](https://dash.cloudflare.com/sign-up), confirma seu e-mail e acessa o painel.
2. Em **Realtime → TURN Server**, ativa o serviço e cria uma chave TURN. Na conta consultada, a Cloudflare exige cadastrar pagamento, aceitar os termos e autorizar cobrança mensal de excedentes: US$ 0 hoje, franquia de 1.000 GB/mês e US$ 0,05/GB adicional. O proprietário deve concluir pessoalmente esse aceite se decidir usar o serviço. Não é necessário comprar domínio nem mover o DNS do projeto.
3. No [serviço existente do Render](https://dashboard.render.com/web/srv-dbu51hrm8hqs73e50qgg), abra **Environment** e adicione `TURN_KEY_ID` e `TURN_API_TOKEN` com os valores fornecidos pela Cloudflare. O banco usado para as contas do Wavecove 1.7 é configurado separadamente em `DATABASE_URL`. O token é o segredo de geração de credenciais TURN, não uma chave de API global da conta.
4. Salve e publique o servidor atualizado. As publicações automáticas estão desativadas neste projeto. As duas pessoas devem reabrir/entrar no cliente 1.4.
5. Selecione um participante no mesmo canal, ligue e aceite na outra máquina. Teste em duas redes diferentes, falando e ouvindo em ambos os sentidos; teste silenciar, recusar, encerrar e queda de conexão.

As credenciais temporárias são solicitadas somente após aceite, por HTTPS, com validade de uma hora. A preparação permite até 12 segundos para o provedor e 15 segundos no estado da sala; a negociação de mídia tem limite separado de 45 segundos. A chamada termina em 55 minutos para evitar operar com credenciais expiradas. A chave permanente permanece no servidor; erros de provedor são exibidos sem respostas brutas ou segredos. Revogar a chave na Cloudflare e remover as variáveis do Render desativa novas chamadas; publique novamente para encerrar as sessões existentes.

A documentação consultada em 17/11/2026 informa franquia mensal de 1.000 GB compartilhada entre TURN e SFU, com excedente de US$ 0,05/GB. Isso não equivale a um teto de custo automático: confira o painel e monitore uso antes de habilitar cobrança. Esta implementação não contrata plano pago nem cadastra meio de pagamento. [Preços oficiais](https://developers.cloudflare.com/realtime/sfu/platform/pricing/).

## Diagnóstico

- **Voz indisponível:** verifique as duas variáveis e se o servidor atualizado foi publicado. Clientes antigos continuam usando texto.
- **Não foi possível preparar a chamada:** confira chave ativa, token específico de TURN, conta habilitada e saída HTTPS do servidor. Não copie o token para logs nem para issues.
- **Conectando sem completar:** verifique firewall e relay. TURN fornece UDP/TCP e TLS, mas redes corporativas podem bloquear o serviço; somente um teste real confirma a rede específica.
- **Conectada sem som:** selecione microfone/saída padrão do Windows e confira acesso de aplicativos desktop ao microfone, volume e botão Silenciar. Reinicie a chamada após trocar dispositivo.
- **Servidor reiniciou:** ambos reconectam no chat; é necessário iniciar e aceitar uma nova chamada.

### Diagnóstico local do relay TURN

Depois de configurar e publicar o servidor, execute o diagnóstico no Windows x64. O lançador de teste usa o servidor público padrão do Wavecove e fica separado do aplicativo distribuído. Na raiz do repositório, gere a imagem portátil com `./scripts/package-windows.ps1` se ainda não tiver uma. No trecho abaixo, ajuste a primeira linha para apontar para a pasta `Wavecove` da imagem gerada.

```powershell
$package = (Resolve-Path 'dist/<data-hora>/Wavecove').Path
$jdk = Join-Path $env:LOCALAPPDATA 'BatePapo/build-tools/jdk-21.0.13+9/bin'
$build = Join-Path $PWD ('.local/turn-diagnostic-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
$inputDir = Join-Path $build 'input'
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Path "$inputDir/lib",$classes -Force | Out-Null

$source = 'client/src/test/java/br/com/batepapo/client/TurnRelayDiagnostic.java'
& "$jdk/javac.exe" --release 21 -encoding UTF-8 -cp "$package/app/client-1.0.0.jar;$package/app/lib/*" -d $classes $source
if ($LASTEXITCODE -ne 0) { throw 'A compilação do diagnóstico falhou.' }

$manifest = Join-Path $build 'MANIFEST.MF'
@('Manifest-Version: 1.0','Class-Path: client-1.0.0.jar','') | Set-Content $manifest -Encoding ascii
& "$jdk/jar.exe" --create --file "$inputDir/diagnostic.jar" --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'A geração do JAR falhou.' }

Copy-Item -LiteralPath "$package/app/client-1.0.0.jar" -Destination $inputDir
Get-ChildItem -LiteralPath "$package/app/lib" -Filter '*.jar' -File | Copy-Item -Destination "$inputDir/lib"
& "$jdk/jpackage.exe" --type app-image --name BatePapoVozTeste --input $inputDir --dest $build `
    --main-jar diagnostic.jar --main-class br.com.batepapo.client.TurnRelayDiagnostic `
    --runtime-image "$package/runtime" --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'O empacotamento do diagnóstico falhou.' }
Join-Path $build 'BatePapoVozTeste/BatePapoVozTeste.exe'
```

Abra o executável indicado pelo Explorer. Digite o código de acesso do chat somente no campo mascarado e clique em **Testar TURN**. Mantenha o código fora de argumentos, variáveis de ambiente e logs. O diagnóstico abre duas sessões com apelidos temporários, troca convite e aceite e usa as credenciais efêmeras retornadas pelo servidor.

O resultado **OK** exige que os dois participantes usem `relayOnly`, selecionem um candidato TURN e recebam tanto pacotes RTP de áudio quanto áudio PCM sintético decodificado. O teste funciona sem microfone ou alto-falantes. A audição humana e as condições de redes diferentes devem ser verificadas com uma chamada entre duas máquinas. [Política de relay e estatísticas WebRTC](https://jrtc.dev/guide/client/statistics)

## Implementação e testes

O protocolo limita os sinais e encaminha SDP/ICE somente aos dois participantes de uma chamada aceita. O servidor não grava nem recebe o áudio. Identidade é baseada na sessão com código da sala; apelidos não são identidade verificada. O chat de texto continua sendo processado pelo servidor.

Os testes usam provedor TURN simulado e motores sem hardware. Validam consentimento, isolamento de sinalização, concorrência e ciclo de vida sem abrir microfone. A integração nativa com áudio sintético verifica WebRTC localmente. Essas verificações não substituem o teste de relay com credenciais reais nem a audição entre duas pessoas.

Referências: [credenciais TURN Cloudflare](https://developers.cloudflare.com/realtime/turn/generate-credentials/), [TURN e transportes](https://developers.cloudflare.com/realtime/turn/), [WebRTC Java](https://jrtc.dev/guide/get-started).
