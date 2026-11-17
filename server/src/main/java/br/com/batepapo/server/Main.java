package br.com.batepapo.server;

public final class Main {
    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("PORT inválida.");
            var turn = CloudflareTurnCredentials.configured(System.getenv("TURN_KEY_ID"), System.getenv("TURN_API_TOKEN"));
            String database = System.getenv("DATABASE_URL");
            String localFile = System.getenv("WAVECOVE_ACCOUNT_FILE");
            AccountStore accounts;
            if (database != null && !database.isBlank()) accounts = AccountStore.postgres(database);
            else if (localFile != null && !localFile.isBlank() && System.getenv("RENDER") == null)
                accounts = AccountStore.local(java.nio.file.Path.of(localFile));
            else throw new IllegalArgumentException("Configure DATABASE_URL ou, para uso local, WAVECOVE_ACCOUNT_FILE.");
            var server = new ChatServer(null, port, turn, accounts).start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown"));
            System.out.println("Bate-papo disponível na porta " + server.port() + ". Saúde: /health");
        } catch (IllegalArgumentException ex) {
            System.err.println("Não foi possível iniciar: " + ex.getMessage());
            System.exit(1);
        }
    }
}
