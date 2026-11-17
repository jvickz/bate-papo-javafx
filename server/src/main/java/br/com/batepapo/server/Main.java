package br.com.batepapo.server;

public final class Main {
    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("PORT inválida.");
            var server = new ChatServer(System.getenv("CHAT_ACCESS_TOKEN"), port).start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown"));
            System.out.println("Bate-papo disponível na porta " + server.port() + ". Saúde: /health");
        } catch (IllegalArgumentException ex) {
            System.err.println("Não foi possível iniciar: " + ex.getMessage());
            System.exit(1);
        }
    }
}
