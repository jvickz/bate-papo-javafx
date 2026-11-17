package br.com.batepapo.server;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.VoiceConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChatServerVoiceTest {
    @Test void relaysPrivateSignalsAndDisconnectEventsAcrossRealSockets() throws Exception {
        String token = "integration-test-access-code-123456";
        var credentialNumber = new AtomicInteger();
        try (var server = new ChatServer(token, 0, () -> CompletableFuture.completedFuture(List.of(
                new VoiceConfig.IceServer(List.of("turn:example.net"), "user-" + credentialNumber.incrementAndGet(),
                        "temporary-password")))).start();
             var ana = new ChatServerTest.Peer(server.port());
             var bia = new ChatServerTest.Peer(server.port());
             var observer = new ChatServerTest.Peer(server.port())) {
            ana.send(new Event("hello", "channels-v1", "Ana", "voice-v1", token, null, null, null));
            bia.send(new Event("hello", "channels-v1", "Bia", "voice-v1", token, null, null, null));
            observer.login("Leo", token, null);
            assertEquals("ready", ana.next("voice").id());
            assertEquals("ready", bia.next("voice").id());
            observer.next("welcome");

            String id = UUID.randomUUID().toString();
            ana.send(new Event("callinvite", id, "Bia", null, null, null, null, null));
            assertEquals(id, ana.next("callringing").id());
            assertEquals("Ana", bia.next("callincoming").name());
            bia.send(new Event("callaccept", id, null, null, null, null, null, null));
            var caller = VoiceConfig.decode(ana.next("callready").text());
            var callee = VoiceConfig.decode(bia.next("callready").text());
            assertTrue(caller.caller());
            assertFalse(callee.caller());
            assertNotEquals(caller.iceServers().getFirst().username(), callee.iceServers().getFirst().username());

            ana.send(new Event("callsignal", id, null, "{\"candidate\":\"c1\"}", "untrusted-token", null, null, null));
            Event signal = bia.next("callsignal");
            assertEquals(id, signal.id());
            assertEquals("Ana", signal.name());
            assertEquals("{\"candidate\":\"c1\"}", signal.text());
            assertNull(signal.token());
            bia.socket.abort();
            assertEquals(id, ana.next("callended").id());
            assertTrue(observer.events.stream().noneMatch(event -> event.type().startsWith("call") || event.type().equals("voice")));
        }
    }
}
