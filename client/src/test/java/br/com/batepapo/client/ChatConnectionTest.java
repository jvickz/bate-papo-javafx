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
    @Test void createsJoinsAndIsolatesChannelMessages() throws Exception {
        var ea = new CopyOnWriteArrayList<Event>(); var eb = new CopyOnWriteArrayList<Event>();
        var sa = new CopyOnWriteArrayList<String>(); var sb = new CopyOnWriteArrayList<String>();
        var a = client(ea, sa); var b = client(eb, sb);
        a.connect(url(), "Ana", TOKEN); b.connect(url(), "João", TOKEN);
        await(() -> sa.contains("connected") && sb.contains("connected"));
        a.createChannel("viagem");
        await(() -> ea.stream().anyMatch(e -> "joined".equals(e.type()) && "viagem".equals(e.channel())));
        await(() -> eb.stream().anyMatch(e -> "channels".equals(e.type()) && e.users().contains("viagem")));
        String isolated = a.send("Só no canal viagem");
        await(() -> ea.stream().anyMatch(e -> isolated.equals(e.id()) && "message".equals(e.type())));
        assertFalse(eb.stream().anyMatch(e -> isolated.equals(e.id())));
        b.joinChannel("viagem");
        await(() -> eb.stream().anyMatch(e -> "joined".equals(e.type()) && "viagem".equals(e.channel())));
        assertFalse(eb.stream().anyMatch(e -> isolated.equals(e.id())), "Ack de troca é barreira ordenada para mensagens anteriores");
        String reply = b.send("Cheguei ao barco ⚓");
        await(() -> ea.stream().anyMatch(e -> reply.equals(e.id()) && "message".equals(e.type())));
        a.joinChannel("inexistente");
        await(() -> sa.getLast().equals("connected") && ea.stream().anyMatch(e -> "error".equals(e.type())));
        String stillThere = a.send("Continuo no canal viagem");
        await(() -> eb.stream().anyMatch(e -> stillThere.equals(e.id()) && "message".equals(e.type())));
    }
    @Test void restartFallsBackToGeneralIfChannelWasLost() throws Exception {
        var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
        var c = client(events, states); String address = url(); int port = server.port();
        c.connect(address, "Ana", TOKEN); await(() -> states.contains("connected"));
        c.createChannel("mar"); await(() -> events.stream().anyMatch(e -> "joined".equals(e.type())));
        server.close(); await(() -> states.getLast().equals("reconnecting"));
        server = new ChatServer(TOKEN, port).start();
        await(() -> events.stream().filter(e -> "welcome".equals(e.type())).count() >= 2 && states.getLast().equals("connected"));
        assertEquals("geral", events.stream().filter(e -> "welcome".equals(e.type())).toList().getLast().channel());
        String id = c.send("De volta ao geral");
        await(() -> events.stream().anyMatch(e -> id.equals(e.id()) && "message".equals(e.type()) && "geral".equals(e.channel())));
    }
    @Test void unconfirmedChannelSwitchBlocksSendingAndReconnects() throws Exception {
        var joinSeen = new java.util.concurrent.atomic.AtomicBoolean();
        var remote = io.javalin.Javalin.create(cfg -> cfg.routes.ws("/chat", ws -> ws.onMessage(ctx -> {
            var incoming = br.com.batepapo.protocol.Protocol.decode(ctx.message());
            if (incoming.type().equals("hello")) ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("welcome", null, "Ana", null, null, "resume", null, null, "geral")));
            if (incoming.type().equals("join")) joinSeen.set(true); // Lost confirmation, server channel may have changed.
        }))).start(0);
        try {
            var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
            var c = client(events, states); c.connect("ws://localhost:" + remote.port(), "Ana", TOKEN);
            await(() -> states.contains("connected")); c.joinChannel("mar");
            await(joinSeen::get);
            assertThrows(IllegalStateException.class, () -> c.send("Não enviar ao canal incerto"));
            assertThrows(IllegalStateException.class, () -> c.joinChannel("outro"));
            await(() -> states.contains("reconnecting"));
            await(() -> Collections.frequency(states, "connected") >= 2);
        } finally { remote.stop(); }
    }
    @Test void ignoresUnmatchedSwitchAcknowledgmentAndOldChannelTraffic() throws Exception {
        var remote = io.javalin.Javalin.create(cfg -> cfg.routes.ws("/chat", ws -> ws.onMessage(ctx -> {
            var incoming = br.com.batepapo.protocol.Protocol.decode(ctx.message());
            if (incoming.type().equals("hello")) ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("welcome", null, "Ana", null, null, "resume", null, null, "geral")));
            if (incoming.type().equals("join")) {
                ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("joined", incoming.id(), null, null, null, null, null, null, "mar")));
                ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("joined", "old-request", null, null, null, null, null, null, "geral")));
                ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("message", "old-message", "Pessoa", "Outro canal", null, null, null, null, "geral")));
                ctx.send(br.com.batepapo.protocol.Protocol.encode(new Event("message", "barrier", "Pessoa", "Canal atual", null, null, null, null, "mar")));
            }
        }))).start(0);
        try {
            var events = new CopyOnWriteArrayList<Event>(); var states = new CopyOnWriteArrayList<String>();
            var c = client(events, states); c.connect("ws://localhost:" + remote.port(), "Ana", TOKEN);
            await(() -> states.contains("connected")); c.joinChannel("mar");
            await(() -> events.stream().anyMatch(e -> "barrier".equals(e.id())));
            assertFalse(events.stream().anyMatch(e -> "old-request".equals(e.id()) || "old-message".equals(e.id())));
        } finally { remote.stop(); }
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
    @Test void queuedSendRejectedByChannelSwitchKeepsItsTextInTheOriginalChannel() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var connected = new CountDownLatch(1); var release = new CountDownLatch(1);
        var joined = new CompletableFuture<Event>();
        var c = new ChatConnection(event -> {
            events.add(event);
            if (event.type().equals("joined")) joined.complete(event);
        }, state -> {
            if (state.equals("connected") && connected.getCount() > 0) {
                connected.countDown(); waitForRelease(release);
            }
        });
        clients.add(c); c.connect(url(), "Ana", TOKEN);
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        String id;
        try {
            id = c.send("Texto que preciso recuperar 👋");
            c.createChannel("viagem");
        } finally { release.countDown(); }
        assertEquals("viagem", joined.get(5, TimeUnit.SECONDS).channel());
        var matching = events.stream().filter(e -> id.equals(e.id())).toList();
        assertEquals(List.of("pending", "error"), matching.stream().map(Event::type).toList());
        assertEquals("Texto que preciso recuperar 👋", matching.getFirst().text());
        assertEquals("Ana", matching.getFirst().name());
        assertEquals("geral", matching.getFirst().channel());
        assertEquals("geral", matching.getLast().channel());
    }
    @Test void queuedSendFromAnOldLoginCannotLeakIntoTheNewSession() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        var connected = new CountDownLatch(1); var release = new CountDownLatch(1);
        var newSession = new CompletableFuture<Event>();
        var c = new ChatConnection(event -> {
            events.add(event);
            if (event.type().equals("welcome") && "Bia".equals(event.name())) newSession.complete(event);
        }, state -> {
            if (state.equals("connected") && connected.getCount() > 0) {
                connected.countDown(); waitForRelease(release);
            }
        });
        clients.add(c); c.connect(url(), "Ana", TOKEN);
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        String id;
        try {
            id = c.send("Rascunho da sessão anterior");
            c.connect(url(), "Bia", TOKEN);
        } finally { release.countDown(); }
        assertEquals("Bia", newSession.get(5, TimeUnit.SECONDS).name());
        assertTrue(events.stream().noneMatch(e -> id.equals(e.id())), "Gerações antigas não podem emitir callbacks de envio na sessão nova");
    }
    @Test void handshakeFailureReportsSafeRetryDetailsAfterTheReconnectState() throws Exception {
        var order = new CopyOnWriteArrayList<Object>();
        var diagnostic = new CompletableFuture<Event>();
        String secretDetail = "private-upstream-detail-" + TOKEN;
        var remote = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/chat", exchange -> {
            byte[] body = secretDetail.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        remote.start();
        try {
            var c = new ChatConnection(event -> {
                order.add(event);
                if (event.type().equals("connection")) diagnostic.complete(event);
            }, order::add);
            clients.add(c); c.connect("ws://127.0.0.1:" + remote.getAddress().getPort(), "Ana", TOKEN);
            Event notice = assertDoesNotThrow(() -> diagnostic.get(5, TimeUnit.SECONDS), "A falha de conexão precisa gerar um diagnóstico local");
            assertEquals("network", notice.id());
            assertTrue(notice.text().contains("503"), notice::text);
            assertTrue(notice.text().toLowerCase(Locale.ROOT).contains("tentativa 1"));
            assertTrue(notice.text().contains("1 s"));
            assertFalse(notice.text().contains(TOKEN));
            assertFalse(notice.text().contains(secretDetail));
            assertNull(notice.token()); assertNull(notice.session());
            assertTrue(order.indexOf("reconnecting") >= 0);
            assertTrue(order.indexOf("reconnecting") < order.indexOf(notice), "O estado deve chegar antes para não apagar o diagnóstico");
        } finally { remote.stop(0); }
    }
    @Test void tlsFailureExplainsTheSecureConnectionWithoutExposingTheException() throws Exception {
        var diagnostic = new CompletableFuture<Event>();
        var c = new ChatConnection(event -> {
            if (event.type().equals("connection")) diagnostic.complete(event);
        }, state -> {});
        clients.add(c);
        // A TLS handshake against this plain HTTP endpoint fails locally without trusting a certificate.
        c.connect("wss://localhost:" + server.port(), "Ana", TOKEN);
        Event notice = assertDoesNotThrow(() -> diagnostic.get(5, TimeUnit.SECONDS), "TLS precisa de um diagnóstico local legível");
        assertTrue(notice.text().toLowerCase(Locale.ROOT).contains("conexão segura"));
        assertFalse(notice.text().contains("Exception"));
        assertFalse(notice.text().contains(TOKEN));
    }
    private static void waitForRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("O teste não liberou a fila de conexão");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new AssertionError(ex);
        }
    }
}
