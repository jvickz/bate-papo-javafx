# Certificado do pooler Supabase

O servidor usa a raiz pública Supabase Root 2021 CA exclusivamente para hosts em `.pooler.supabase.com`. O driver PostgreSQL verifica a cadeia e o nome do host com `verify-full`. A imagem Docker inclui o certificado em `/app/certs/supabase-prod-ca-2021.crt`. Para executar o JAR em outra pasta, defina `WAVECOVE_SUPABASE_CA` com o caminho absoluto do arquivo.

O painel do Supabase obtém a URL do certificado pela entrada `ssl:certificate_url` da configuração oficial. Para conferir ou renovar o arquivo, use o ambiente `prod` daquela URL. [Configuração do painel](https://github.com/supabase/supabase/blob/master/apps/studio/hooks/custom-content/custom-content.json) · [Certificado oficial](https://supabase-downloads.s3-ap-southeast-1.amazonaws.com/prod/ssl/prod-ca-2021.crt) · [Documentação de verificação do pooler](https://supabase.com/docs/guides/database/psql)

Para conferir a impressão digital SHA-256 e a validade, execute na raiz do projeto.

```shell
openssl x509 -in server/certs/supabase-prod-ca-2021.crt -noout -subject -dates -fingerprint -sha256
```

A impressão digital desse certificado é `80:70:25:AD:50:D4:ED:21:9D:2C:9C:7D:29:9C:00:4F:82:4E:B0:0C:F7:F6:5A:FE:F6:07:D0:7B:72:E6:CA:FA`. Ele expira em 26 de abril de 2031.
