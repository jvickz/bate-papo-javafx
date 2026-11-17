package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.server.ChatServer;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ChatConnectionTest {
    private static final String TOKEN = "integration-test-access-code-123456";
    ChatServer server;
    final List<ChatConnection> clients = new ArrayList<>();
    @BeforeEach void start() { server = new ChatServer(TOKEN, 0).start(); }
    @AfterEach void stop() { clients.forEach(ChatConnection::close); server.close(); }
    String url() { return "ws://127.0.0.1:" + server.port(); }
    ChatConnection client(List<Event> events, List<String> states) {
        var c = new ChatConnection(events::add, states::add); clients.add(c); return c;
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(30);
        assertTrue(condition.getAsBoolean(), "Condição não atingida antes do timeout");
    }
    @Test void sendsReceivesAndConfirmsOnlyAfterServerEcho() throws Exception {
        var ea = new CopyOnWriteArrayList<Event>(); var eb = new CopyOnWriteArrayList<Event>();
        var sa = new CopyOnWriteArrayList<String>(); var sb = new CopyOnWriteArrayList<String>();
        var a = client(ea, sa); var b = client(eb, sb);
        a.connect(url(), "Ana", TOKEN); b.connect(url(), "João", TOKEN);
        await(() -> sa.contains("connected") && sb.contains("connected"));
        String id = a.send("Oi 👋\nTudo bem?");
        await(() -> eb.stream().anyMatch(e -> id.equals(e.id()) && "message".equals(e.type())));
        await(() -> ea.stream().anyMatch(e -> id.equals(e.id()) && "message".equals(e.type())));
        assertTrue(ea.stream().anyMatch(e -> id.equals(e.id()) && "pending".equals(e.type())));
        assertEquals("Ana", eb.stream().filter(e -> id.equals(e.id())).findFirst().orElseThrow().name());
        a.disconnect(); await(() -> sa.getLast().equals("disconnected"));
        assertThrows(IllegalStateException.class, () -> a.send("offline"));
    }
    @Test void invalidCredentialsStopInsteadOfRetrying() throws Exception {
        var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
        var c = client(events, states); c.connect(url(), "Ana", "wrong");
        await(() -> events.stream().anyMatch(e -> "error".equals(e.type()) && "auth".equals(e.id())));
        await(() -> states.getLast().equals("disconnected"));
        assertFalse(states.contains("connected")); assertFalse(states.contains("reconnecting"));
    }
    @Test void reconnectsAfterServerRestartAndCanCancelReconnect() throws Exception {
        var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
        var c = client(events, states); var address = url(); int port = server.port();
        c.connect(address, "Ana", TOKEN); await(() -> states.contains("connected"));
        server.close(); await(() -> states.contains("reconnecting"));
        server = new ChatServer(TOKEN, port).start();
        await(() -> Collections.frequency(states, "connected") >= 2);
        c.send("voltei"); await(() -> events.stream().anyMatch(e -> "message".equals(e.type()) && "voltei".equals(e.text())));
        server.close(); await(() -> states.getLast().equals("reconnecting"));
        c.disconnect(); await(() -> states.getLast().equals("disconnected"));
        server = new ChatServer(TOKEN, port).start();
        Thread.sleep(1800); assertEquals("disconnected", states.getLast());
    }
    @Test void immediateDisconnectInvalidatesInFlightConnection() throws Exception {
        var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
        var c = client(events, states); c.connect(url(), "Ana", TOKEN); c.disconnect();
        await(() -> !states.isEmpty() && states.getLast().equals("disconnected"));
        Thread.sleep(500); assertEquals("disconnected", states.getLast());
    }
    @Test void unacknowledgedMessageIsUncertainAndNeverAutomaticallyResent() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var silent = io.javalin.Javalin.create(cfg -> cfg.routes.ws("/chat", ws -> ws.onMessage(ctx -> {
            var incoming = br.com.batepapo.protocol.Protocol.decode(ctx.message());
            if (incoming.type().equals("hello")) ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("welcome", null, "Ana", null, null, "resume", null, null)));
            if (incoming.type().equals("message")) { count.incrementAndGet(); ctx.closeSession(); }
        }))).start(0);
        try {
            var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
            var c = client(events, states); c.connect("ws://localhost:" + silent.port(), "Ana", TOKEN);
            await(() -> states.contains("connected"));
            String id = c.send("não repetir");
            await(() -> events.stream().anyMatch(e -> "uncertain".equals(e.type()) && id.equals(e.id())));
            await(() -> Collections.frequency(states, "connected") > 1);
            assertEquals(1, count.get());
        } finally { silent.stop(); }
    }
}
