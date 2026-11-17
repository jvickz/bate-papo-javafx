# SDD ledger — plan: docs/superpowers/plans/2026-11-17-bate-papo.md

Design aprovado em 17/11/2026. Execução direta, revisão independente ao final.
Pre-flight: Task 1 fornece Event/Protocol para Tasks 2 e 3; Task 2 fornece ChatServer para testes Task 3; Task 4 empacota os mesmos módulos. Interfaces consistentes.
Ruling: executar na pasta nova do usuário, em branch local própria — não existe checkout anterior a isolar — sem risco a código anterior.
Ruling: adaptar scripts de bookkeeping Unix a registro PowerShell/documento — ambiente Windows sem Bash confirmado — sem alteração do produto.
Task 1: complete — ProtocolTest RED (4 casos sem implementação), GREEN 4/4; JDK 21.0.13 e Maven 3.10.1 verificados por checksum. Ferramentas movidas ao cache local AppData para evitar lentidão do Google Drive.
Task 2: complete — ChatServerTest RED 7 casos sem servidor; GREEN 7/7 com WebSockets reais, mais ProtocolTest 4/4. Limites de transporte, autenticação e heartbeat implementados.
