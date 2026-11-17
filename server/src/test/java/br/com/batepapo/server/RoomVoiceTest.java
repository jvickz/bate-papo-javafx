package br.com.batepapo.server;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.Protocol;
import br.com.batepapo.protocol.VoiceConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RoomVoiceTest {
    private static final String TOKEN = "room-test-access-code-123456789";
    private static final List<VoiceConfig.IceServer> SERVERS = List.of(
            new VoiceConfig.IceServer(List.of("turns:turn.example.net:443"), "temporary-name", "temporary-secret"));
    private final AtomicLong now = new AtomicLong(1);
    private final List<CompletableFuture<List<VoiceConfig.IceServer>>> requests = new ArrayList<>();
    private final Room room = new Room(TOKEN, this::issueCredentials, now::get);

    @Test void announcesVoiceUnavailableWithoutInterruptingChat() {
        Room disabled = new Room(TOKEN);
        var ana = new Peer(disabled, "ana");
        var legacy = new Peer(disabled, "legacy");
        ana.login("Ana", "voice-v1", null);
        legacy.login("Bia", null, null);

        assertEquals("unavailable", ana.last("voice").id());
        assertFalse(legacy.received("voice"));
        ana.call("callinvite", id(), "Bia", null);
        assertTrue(ana.received("callerror"));
        ana.send(new Event("message", "message-id", null, "Oi", null, null, null, null, "geral"));
        assertEquals("Oi", legacy.last("message").text());
    }

    @Test void validatesParticipantsAndPhaseBeforeIssuingOrForwarding() {
        var ana = login("Ana");
        var bia = login("Bia");
        var carla = login("Carla");
        assertEquals("ready", ana.last("voice").id());
        String call = invite(ana, bia, "Bia");
        var anonymous = new Peer(room, "intruso");
        anonymous.call("callaccept", call, null, null);
        assertEquals("auth", anonymous.last("error").id());

        ana.call("callaccept", call, null, null);
        carla.call("callend", call, "Ana", null);
        carla.call("callsignal", call, "Bia", "{}");
        ana.call("callsignal", call, "Bia", "{}");
        assertEquals(call, ana.last("callerror").id());
        assertEquals(call, carla.last("callerror").id());
        assertTrue(requests.isEmpty());
        assertFalse(bia.received("callsignal"));

        bia.call("callaccept", call, null, null);
        assertFalse(ana.received("callready"));
        bia.call("callaccept", call, null, null);
        resolveCredentials();
        assertTrue(VoiceConfig.decode(ana.last("callready").text()).caller());
        assertFalse(VoiceConfig.decode(bia.last("callready").text()).caller());
        assertEquals("Bia", ana.last("callready").name());
        assertEquals("Ana", bia.last("callready").name());

        ana.call("callsignal", call, "Carla", "{}");
        assertFalse(bia.received("callsignal"));
        ana.call("callsignal", call, "Bia", "{\"type\":\"offer\"}");
        Event signal = bia.last("callsignal");
        assertEquals("Ana", signal.name());
        assertEquals("{\"type\":\"offer\"}", signal.text());
        assertNull(signal.token());
        assertNull(signal.session());
        assertFalse(carla.received("callsignal"));
        assertFalse(carla.received("callready"));
    }

    @Test void rejectsBusyTargetsAndReleasesBothOnDecline() {
        var ana = login("Ana");
        var bia = login("Bia");
        var carla = login("Carla");
        String call = invite(ana, bia, "Bia");
        String busy = id();
        carla.call("callinvite", busy, "Bia", null);
        assertEquals(busy, carla.last("callerror").id());
        ana.call("callreject", call, null, null);
        assertFalse(bia.received("callended"));
        bia.call("callreject", call, null, null);
        assertEnded(ana, bia, call);
        assertTrue(requests.isEmpty());
        advance(3);
        assertNotEquals(call, invite(carla, ana, "Ana"));
    }

    @Test void expiresAnUnansweredInvitationAndRejectsLateAcceptance() {
        var ana = login("Ana");
        var bia = login("Bia");
        String call = invite(ana, bia, "Bia");
        advance(30);

        assertEnded(ana, bia, call);
        bia.call("callaccept", call, null, null);
        assertFalse(bia.received("callready"));
        assertTrue(requests.isEmpty());
    }

    @Test void rejectsCallsAcrossChannelsToLegacyMembersAndWithInvalidIds() {
        var ana = login("Ana");
        var bia = login("Bia");
        var legacy = new Peer(room, "leo");
        legacy.login("Leo", null, null);
        bia.createChannel("equipe");
        ana.call("callinvite", "not a uuid", "Bia", null);
        assertEquals("invalid", ana.last("error").id());
        String remote = id();
        ana.call("callinvite", remote, "Bia", null);
        assertEquals(remote, ana.last("callerror").id());
        String incapable = id();
        ana.call("callinvite", incapable, "Leo", null);
        assertEquals(incapable, ana.last("callerror").id());
        assertFalse(legacy.received("callincoming"));
        assertFalse(bia.received("callincoming"));
    }

    @Test void dropsCredentialsAfterCallEndsDuringProvisioning() {
        var ana = login("Ana");
        var bia = login("Bia");
        String call = invite(ana, bia, "Bia");
        bia.call("callaccept", call, null, null);
        ana.call("callend", call, null, null);

        resolveCredentials();
        assertEnded(ana, bia, call);
        assertFalse(ana.received("callready"));
        assertFalse(bia.received("callready"));
    }

    @Test void waitsForSlowCredentialsAndStartsNegotiationAfterTheyArrive() {
        var ana = login("Ana");
        var bia = login("Bia");
        String call = invite(ana, bia, "Bia");
        bia.call("callaccept", call, null, null);

        advance(10);
        assertFalse(ana.received("callended"));
        assertFalse(bia.received("callended"));
        resolveCredentials();
        assertEquals(call, ana.last("callready").id());
        assertEquals(call, bia.last("callready").id());

        keepOnline(ana, bia);
        advance(4);
        assertFalse(ana.received("callended"));
        advance(1);
        assertEnded(ana, bia, call);
    }

    @Test void timesOutAnUnresponsiveCredentialProviderAndIgnoresLateResults() {
        var ana = login("Ana");
        var bia = login("Bia");
        String call = invite(ana, bia, "Bia");
        bia.call("callaccept", call, null, null);

        advance(14);
        assertFalse(ana.received("callended"));
        advance(1);
        assertEnded(ana, bia, call);
        resolveCredentials();
        assertFalse(ana.received("callready"));
        assertFalse(bia.received("callready"));
    }

    @Test void providerFailureEndsTheCallWithoutLeakingAnErrorDetail() {
        var ana = login("Ana");
        var bia = login("Bia");
        String call = invite(ana, bia, "Bia");
        bia.call("callaccept", call, null, null);
        requests.getFirst().completeExceptionally(new IllegalStateException("secret-provider-detail"));
        requests.getLast().complete(SERVERS);

        assertEnded(ana, bia, call);
        assertFalse(ana.last("callended").text().contains("secret-provider-detail"));
        assertFalse(bia.received("callready"));
    }

    @Test void movesChannelsAndReplacesSessionsWithoutKeepingACall() {
        var ana = login("Ana");
        var bia = login("Bia");
        String moved = invite(ana, bia, "Bia");
        ana.createChannel("equipe");
        assertEnded(ana, bia, moved);
        ana.send(new Event("join", "join-id", null, null, null, null, null, null, "geral"));

        advance(3);
        String replaced = invite(ana, bia, "Bia");
        bia.call("callaccept", replaced, null, null);
        var resumed = new Peer(room, "new-session");
        resumed.login("Bia", "voice-v1", bia.last("welcome").session());
        assertEquals(replaced, ana.last("callended").id());
        resolveCredentials();
        assertFalse(ana.received("callready"));
        assertFalse(resumed.received("callready"));
        advance(3);
        invite(ana, resumed, "Bia");
    }

    @Test void disconnectAndShutdownNotifyRemainingParticipants() {
        var ana = login("Ana");
        var bia = login("Bia");
        String disconnected = invite(ana, bia, "Bia");
        bia.close();
        assertEquals(disconnected, ana.last("callended").id());

        advance(3);
        bia = login("Bia");
        String closing = invite(ana, bia, "Bia");
        room.shutdown();
        assertEnded(ana, bia, closing);
    }

    @Test void requiresBothConnectedReportsAndCapsCallLifetime() {
        var ana = login("Ana");
        var bia = login("Bia");
        String unconnected = invite(ana, bia, "Bia");
        bia.call("callaccept", unconnected, null, null);
        resolveCredentials();
        ana.call("callconnected", unconnected, null, null);
        ana.call("callconnected", unconnected, null, null);
        keepOnline(ana, bia);
        advance(5);
        assertEnded(ana, bia, unconnected);

        String connected = invite(ana, bia, "Bia");
        bia.call("callaccept", connected, null, null);
        resolveCredentials();
        ana.call("callconnected", connected, null, null);
        bia.call("callconnected", connected, null, null);
        for (int i = 0; i < 82; i++) keepOnline(ana, bia);
        assertEquals(unconnected, ana.last("callended").id());
        advance(20);
        assertEnded(ana, bia, connected);
    }

    @Test void boundsVoiceSignalSizeAndRateWithoutClosingTextChat() {
        var ana = login("Ana");
        var bia = login("Bia");
        String call = invite(ana, bia, "Bia");
        bia.call("callaccept", call, null, null);
        resolveCredentials();
        ana.call("callsignal", call, null, "{\"sdp\":\"" + "x".repeat(11000) + "\"}");
        ana.call("callsignal", call, null, "{} {}");
        assertFalse(bia.received("callsignal"));
        for (int i = 0; i < 60; i++) ana.call("callsignal", call, null, "{}");
        long signals = bia.events.stream().filter(e -> e.type().equals("callsignal")).count();
        assertTrue(signals > 30, "ICE candidates can arrive in a burst.");
        assertTrue(signals < 60, "A sustained burst must be bounded.");
        ana.send(new Event("message", "text-id", null, "Oi", null, null, null, null, "geral"));
        assertEquals("Oi", bia.last("message").text());
    }

    private CompletableFuture<List<VoiceConfig.IceServer>> issueCredentials() {
        assertFalse(Thread.holdsLock(room));
        var request = new CompletableFuture<List<VoiceConfig.IceServer>>();
        requests.add(request);
        return request;
    }

    private void resolveCredentials() {
        assertEquals(2, requests.size());
        requests.forEach(request -> request.complete(SERVERS));
        requests.clear();
    }

    private void advance(int seconds) {
        now.addAndGet(Duration.ofSeconds(seconds).toNanos());
        room.tick();
    }

    private void keepOnline(Peer ana, Peer bia) {
        now.addAndGet(Duration.ofSeconds(40).toNanos());
        ana.send(Event.of("pong", null));
        bia.send(Event.of("pong", null));
        room.tick();
    }

    private static void assertEnded(Peer ana, Peer bia, String call) {
        assertEquals(call, ana.last("callended").id());
        assertEquals(call, bia.last("callended").id());
    }

    private Peer login(String name) {
        var peer = new Peer(room, name);
        peer.login(name, "voice-v1", null);
        return peer;
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static String invite(Peer caller, Peer callee, String name) {
        String call = id();
        caller.call("callinvite", call, name, null);
        assertEquals(call, caller.last("callringing").id());
        assertEquals(name, caller.last("callringing").name());
        assertEquals(call, callee.last("callincoming").id());
        return call;
    }

    private static final class Peer implements Room.Transport {
        private final Room room;
        private final String id;
        private final List<Event> events = new ArrayList<>();

        Peer(Room room, String id) {
            this.room = room;
            this.id = id;
            room.open(id, this);
        }

        void login(String name, String voice, String session) {
            send(new Event("hello", "channels-v1", name, voice, TOKEN, session, null, null));
        }

        void createChannel(String channel) {
            send(new Event("create", "create-id", null, null, null, null, null, null, channel));
        }

        void call(String type, String call, String name, String text) {
            send(new Event(type, call, name, text, null, null, null, null));
        }

        void send(Event event) { room.receive(id, Protocol.encode(event)); }

        boolean received(String type) { return events.stream().anyMatch(event -> type.equals(event.type())); }

        Event last(String type) {
            return events.reversed().stream().filter(event -> type.equals(event.type())).findFirst()
                    .orElseThrow(() -> new AssertionError("Evento ausente: " + type));
        }

        @Override public void send(String json) { events.add(Protocol.decode(json)); }
        @Override public void close() { room.remove(id); }
    }
}
