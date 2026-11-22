package br.com.batepapo.server;

import br.com.batepapo.protocol.Protocol;
import br.com.batepapo.protocol.Attachment;
import io.javalin.Javalin;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

public final class ChatServer implements AutoCloseable {
    private final Javalin app;
    private final Room room;
    private final SecretRelay secretRelay = new SecretRelay();
    private final int port;
    private final TurnCredentialsProvider turn;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("room-heartbeat").factory());
    public ChatServer(String token, int port) { this(token, port, null); }

    public static ChatServer localAccounts(Path file, int port) {
        return localAccounts(file, port, null);
    }

    static ChatServer localAccounts(Path file, int port, TurnCredentialsProvider turn) {
        return new ChatServer(null, port, turn, AccountStore.local(file));
    }

    ChatServer(String token, int port, TurnCredentialsProvider turn) {
        this(token, port, turn, null);
    }

    ChatServer(String token, int port, TurnCredentialsProvider turn, AccountStore store) {
        this.port = port;
        this.turn = turn;
        var sessions = store == null ? null : new AccountSessions(System::nanoTime);
        room = new Room(token, turn, System::nanoTime, sessions);
        var attachments = new AttachmentRoutes(room);
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.startup.showOldJavalinVersionWarning = false;
            config.jetty.host = System.getenv().getOrDefault("WAVECOVE_BIND_ADDRESS", "0.0.0.0");
            if (store != null) {
                config.staticFiles.add(files -> {
                    files.directory = "/web";
                    files.headers = java.util.Map.of(
                            "Cache-Control", "no-cache",
                            "Content-Security-Policy", "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
                            "Referrer-Policy", "no-referrer",
                            "X-Content-Type-Options", "nosniff");
                });
            }
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxTextMessageSize(Protocol.MAX_BYTES);
                factory.setMaxBinaryMessageSize(1);
                factory.setIdleTimeout(Duration.ofSeconds(60));
            });
            config.routes.get("/health", ctx -> ctx.contentType("application/json").result(store == null
                    ? "{\"status\":\"ok\"}" : "{\"status\":\"ok\",\"directMessages\":true,\"profileAvatars\":true}"));
            if (store != null) {
                var direct = new DirectRoutes(sessions, new DirectStore(store));
                config.routes.get("/direct/threads", direct::threads);
                config.routes.get("/direct/messages/{peer}", direct::messages);
                config.routes.post("/direct/messages/{peer}", direct::send);
                config.routes.post("/direct/read/{peer}", direct::read);
                var accounts = new AccountRoutes(store, sessions, room);
                config.routes.post("/accounts/register", ctx -> accounts.authenticate(ctx, true));
                config.routes.post("/accounts/login", ctx -> accounts.authenticate(ctx, false));
                config.routes.post("/accounts/logout", accounts::logout);
                var avatars = new AvatarRoutes(store, sessions);
                config.routes.get("/profiles/{name}/avatar", avatars::get);
                config.routes.put("/profile/avatar", avatars::put);
                config.routes.delete("/profile/avatar", avatars::remove);
            }
            config.routes.post(Attachment.PATH, attachments::upload);
            config.routes.get(Attachment.PATH + "/{id}", attachments::download);
            config.routes.ws("/secret/{room}/{slot}", secretRelay::configure);
            config.routes.ws("/chat", ws -> {
                ws.onConnect(ctx -> room.open(ctx.sessionId, new SocketPeer(ctx.session)));
                ws.onMessage(ctx -> room.receive(ctx.sessionId, ctx.message()));
                ws.onBinaryMessage(ctx -> ctx.closeSession(1003, "Envie apenas texto."));
                ws.onClose(ctx -> room.remove(ctx.sessionId));
                ws.onError(ctx -> room.remove(ctx.sessionId));
            });
        });
    }
    public ChatServer start() {
        app.start(port);
        timer.scheduleAtFixedRate(room::tick, 1, 1, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(secretRelay::expire, 15, 15, TimeUnit.SECONDS);
        return this;
    }
    public int port() { return app.port(); }
    @Override public void close() {
        timer.shutdownNow();
        room.shutdown();
        secretRelay.shutdown();
        if (turn != null) turn.close();
        app.stop();
    }
}
