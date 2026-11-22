# TLS da Sala da Cachoeira no navegador

A página carrega o módulo WebAssembly da própria aplicação. Cada participante executa uma instância do Mbed TLS entre o navegador e a outra ponta da conversa. O WebSocket transporta os registros desse TLS interno. A identidade pública é `wavecove/secret/v1/{room}`, e a chave pré-compartilhada tem 32 bytes. O perfil usa TLS 1.3, PSK com X25519 efêmero e AES-128-GCM-SHA256, compatível com `SecretTunnel` do Wavecove Windows 1.13. A instância importa apenas `browser.random`, que usa `crypto.getRandomValues`.

## Recompilação

Use Python 3.12 ou posterior e a distribuição do Zig 0.14.1 com sua pasta `lib` e licença. Execute na raiz do projeto.

```sh
python tools/web-tls/build.py --zig /caminho/para/zig
```

O script verifica o SHA-256 do arquivo oficial, compila os fontes sem modificações com as configurações desta pasta e grava `secret-engine.wasm` e `secret-engine-license.txt` nos recursos web do servidor. O cache de download pode ser escolhido com `--cache`. O SHA-256 do módulo distribuído é `48b5e6b7a5190177c839591fd40756beb4f0490e0918e29287af8bb33d6151eb`. [Mbed TLS 3.6.8](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.8) e [Zig 0.14.1](https://ziglang.org/download/#release-0.14.1).

## Verificação com o aplicativo Windows

Com Node.js 24, JDK 21 e o aplicativo em `Wavecove/app`, execute na raiz do projeto. O verificador usa `JAVA_HOME` ou os executáveis Java no PATH.

```sh
node tools/web-tls/check-interop.mjs
```

O teste negocia as duas combinações de anfitrião e convidado contra a implementação dentro do JAR distribuído. Cada par troca mensagens nos dois sentidos, incluindo uma de 2.000 emojis. As chaves de teste são aleatórias e vivem apenas durante a execução.
