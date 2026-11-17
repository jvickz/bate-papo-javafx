package br.com.batepapo.server;

import br.com.batepapo.protocol.Protocol;
import io.javalin.Javalin;
import java.time.Duration;
import java.util.concurrent.*;

public final class ChatServer implements AutoCloseable {
    private final Javalin app;
    private final Room room;
    private final int port;
    private final TurnCredentialsProvider turn;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("room-heartbeat").factory());
    public ChatServer(String token, int port) { this(token, port, null); }

    ChatServer(String token, int port, TurnCredentialsProvider turn) {
        this.port = port;
        this.turn = turn;
        room = new Room(token, turn, System::nanoTime);
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.startup.showOldJavalinVersionWarning = false;
            config.jetty.host = "0.0.0.0";
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxTextMessageSize(Protocol.MAX_BYTES);
                factory.setMaxBinaryMessageSize(1);
                factory.setIdleTimeout(Duration.ofSeconds(60));
            });
            config.routes.get("/health", ctx -> ctx.contentType("application/json").result("{\"status\":\"ok\"}"));
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
        return this;
    }
    public int port() { return app.port(); }
    @Override public void close() {
        timer.shutdownNow();
        room.shutdown();
        if (turn != null) turn.close();
        app.stop();
    }
}
