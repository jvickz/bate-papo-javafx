package br.com.batepapo.client;

import br.com.batepapo.protocol.*;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class VoiceConnectionTest {
    @Test void explicitDisconnectDropsQueuedCallInsteadOfReplayingItInAnotherSession() throws Exception {
        var received = new CopyOnWriteArrayList<Event>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stateCount = new java.util.concurrent.atomic.AtomicInteger();
        String id = UUID.randomUUID().toString();
        var remote = Javalin.create(cfg -> cfg.routes.ws("/chat", ws -> ws.onMessage(ctx -> {
            Event event = Protocol.decode(ctx.message()); received.add(event);
            if (event.type().equals("hello")) ctx.send(Protocol.encode(new Event("welcome", null, event.name(), null, null, "resume", null, null, "geral")));
        }))).start(0);
        try (var connection = new ChatConnection(event -> {}, state -> {
            if (state.equals("connected") && stateCount.incrementAndGet() == 1) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        })) {
            String address = "ws://localhost:" + remote.port();
            connection.connect(address, "Ana", "test-access-code-123456789");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            connection.voice(new Event("callinvite", id, "Bia", null, null, null, null, null));
            connection.disconnect();
            connection.connect(address, "Outra Ana", "test-access-code-123456789");
            release.countDown();
            ChatConnectionTest.await(() -> stateCount.get() == 2);
            String barrier = connection.send("nova sessão");
            ChatConnectionTest.await(() -> received.stream().anyMatch(e -> barrier.equals(e.id())));
            assertFalse(received.stream().anyMatch(e -> id.equals(e.id())), "Uma chamada cancelada nunca deve ser reenviada");
        } finally { release.countDown(); remote.stop(); }
    }

    @Test void advertisesVoiceAndRoutesPrivateEventsWithoutChatEcho() throws Exception {
        var received = new CopyOnWriteArrayList<Event>();
        var events = new CopyOnWriteArrayList<Event>();
        var connected = new CountDownLatch(1);
        String id = UUID.randomUUID().toString();
        var remote = Javalin.create(cfg -> cfg.routes.ws("/chat", ws -> ws.onMessage(ctx -> {
            Event event = Protocol.decode(ctx.message()); received.add(event);
            if (event.type().equals("hello")) {
                ctx.send(Protocol.encode(new Event("welcome", null, "Ana", null, null, "resume", null, null, "geral")));
                ctx.send(Protocol.encode(new Event("voice", "ready", null, "Voz disponível", null, null, null, null)));
            }
            if (event.type().equals("callinvite")) {
                for (String type : List.of("callringing", "callready", "callsignal", "callended"))
                    ctx.send(Protocol.encode(new Event(type, id, "Bia", "{}", null, null, null, null)));
            }
        }))).start(0);
        try (var connection = new ChatConnection(events::add, state -> { if (state.equals("connected")) connected.countDown(); })) {
            connection.connect("ws://localhost:" + remote.port(), "Ana", "test-access-code-123456789");
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            assertEquals("voice-v1", received.getFirst().text());
            connection.voice(new Event("callinvite", id, "Bia", null, "must-not-send", "must-not-send", null, null));
            ChatConnectionTest.await(() -> events.stream().anyMatch(e -> e.type().equals("callended")));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("voice")));
            assertTrue(events.stream().anyMatch(e -> e.type().equals("callsignal")));
            Event invite = received.stream().filter(e -> e.type().equals("callinvite")).findFirst().orElseThrow();
            assertNull(invite.token()); assertNull(invite.session());
            assertFalse(events.stream().anyMatch(e -> e.type().equals("pending") || e.type().equals("message")));
            assertThrows(IllegalArgumentException.class, () -> connection.voice(Event.of("message", "wrong path")));
            assertThrows(IllegalArgumentException.class, () -> connection.voice(Event.of("callend", null)));
            assertThrows(IllegalArgumentException.class, () -> connection.voice(new Event("callsignal", id, null, "x".repeat(13000), null, null, null, null)));
            connection.disconnect();
            assertThrows(IllegalStateException.class, () -> connection.voice(new Event("callend", id, null, null, null, null, null, null)));
        } finally { remote.stop(); }
    }
}
