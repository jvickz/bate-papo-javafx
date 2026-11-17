package br.com.batepapo.server;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.Protocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoomChannelsTest {
    private static final String TOKEN = "room-test-access-code-123456789";
    private final Room room = new Room(TOKEN);

    @Test void catalogIsLimitedToAuthenticatedChannelClients() {
        var observer = new Peer(room, "observer");
        var legacy = new Peer(room, "legacy");
        legacy.send(new Event("hello", null, "Lia", null, TOKEN, null, null, null));
        var ana = login("ana", "Ana", null, null);

        ana.expectChannel("welcome", "geral");
        assertEquals(List.of("geral"), ana.last("channels").users());
        ana.command("create", "tech");

        assertEquals(List.of("geral", "tech"), ana.last("channels").users());
        assertNull(ana.last("channels").session());
        assertNull(ana.last("channels").token());
        assertTrue(observer.frames.isEmpty());
        assertTrue(legacy.frames.stream().noneMatch(json -> json.contains("\"channel\":") || json.contains("\"type\":\"channels\"")));
        assertEquals(List.of("Lia"), legacy.last("presence").users());
        legacy.send(new Event("message", "legacy-message", null, "Oi", null, null, null, null));
        assertNull(legacy.last("message").channel());

        ana.command("join", "geral");
        ana.expectChannel("presence", "geral");
        ana.send(new Event("message", "new-message", null, "Olá", null, null, null, null, "geral"));
        assertEquals("Ana", legacy.last("message").name());
        assertNull(legacy.last("message").channel());

        observer.command("join", "tech");
        assertEquals("auth", observer.last("error").id());
        assertTrue(observer.closed);
    }

    @Test void createsAndJoinsBeforeAnnouncingChannelMembership() {
        var ana = login("ana", "Ana", null, null);
        var bia = login("bia", "Bia", null, null);

        ana.frames.clear();
        String createId = ana.command("create", "  TeCh-2 ");
        assertEquals("joined", Protocol.decode(ana.frames.getFirst()).type());
        assertEquals(createId, ana.last("joined").id());
        ana.expectChannel("joined", "tech-2");
        ana.expectChannel("system", "tech-2");
        assertEquals(List.of("Ana"), ana.last("presence").users());
        assertEquals(List.of("Bia"), bia.last("presence").users());
        assertEquals(List.of("geral", "tech-2"), bia.last("channels").users());

        String joinId = bia.command("join", "TECH-2");
        assertEquals(joinId, bia.last("joined").id());
        bia.expectChannel("presence", "tech-2");
        assertEquals(List.of("Ana", "Bia"), ana.last("presence").users());

        var leo = login("leo", "Leo", "tech-2", null);
        leo.expectChannel("welcome", "tech-2");
        assertEquals(List.of("Ana", "Bia", "Leo"), leo.last("presence").users());
    }

    @Test void rejectsStaleMessageChannelAndScopesPresenceAndNotices() {
        var ana = login("ana", "Ana", null, null);
        var bia = login("bia", "Bia", null, null);
        var leo = login("leo", "Leo", null, null);
        ana.command("create", "tech");
        bia.command("join", "tech");
        leo.frames.clear();

        ana.send(new Event("message", "hello", null, "Oi", null, null, null, null, "geral"));
        assertEquals("hello", ana.last("error").id());
        assertTrue(bia.frames.stream().map(Protocol::decode).noneMatch(e -> e.type().equals("message")));
        ana.send(new Event("message", "current", null, "Oi", null, null, null, null, "tech"));
        assertEquals("Oi", bia.last("message").text());
        bia.expectChannel("message", "tech");
        room.remove("ana");
        assertEquals(List.of("Bia"), bia.last("presence").users());
        bia.expectChannel("system", "tech");
        assertTrue(leo.frames.isEmpty());
    }

    @Test void rejectsInvalidUnavailableAndDuplicateChannelsWithRequestId() {
        var ana = login("ana", "Ana", null, null);
        for (String invalid : List.of("", "a", "-tech", "maçã", "with spaces", "x".repeat(25))) {
            assertEquals(ana.command("create", invalid), ana.last("error").id());
        }
        assertEquals(ana.command("join", "missing"), ana.last("error").id());
        assertEquals(ana.command("create", "GERAL"), ana.last("error").id());
        ana.send(new Event("create", "invalid id", null, null, null, null, null, null, "tech"));
        assertEquals("invalid", ana.last("error").id());
        assertEquals(List.of("geral"), ana.last("channels").users());
        assertFalse(ana.closed);
    }

    @Test void countsTheDefaultChannelTowardCapacityAndKeepsFullRequestsConnected() {
        var ana = login("ana", "Ana", null, null);
        for (int i = 1; i <= 19; i++) {
            assertEquals(ana.command("create", "room-" + i), ana.last("joined").id());
        }
        assertEquals(20, ana.last("channels").users().size());
        assertEquals(ana.command("create", "overflow"), ana.last("error").id());
        assertFalse(ana.closed);
        ana.send(new Event("message", "after-error", null, "Oi", null, null, null, null, "room-19"));
        ana.expectChannel("message", "room-19");
    }

    @Test void resumesAnExistingChannelAndFallsBackWhenItNoLongerExists() {
        var ana = login("ana", "Ana", null, null);
        ana.command("create", "tech");
        String session = ana.last("welcome").session();
        var duplicate = login("duplicate", "ana", "geral", null);
        assertEquals("name", duplicate.last("error").id());
        assertTrue(duplicate.closed);

        var resumed = login("resumed", "Ana", "tech", session);
        assertTrue(ana.closed);
        assertEquals(session, resumed.last("welcome").session());
        resumed.expectChannel("welcome", "tech");

        var restarted = new Peer(new Room(TOKEN), "restarted");
        restarted.login("Ana", "tech", session);
        restarted.expectChannel("welcome", "geral");
        assertEquals(List.of("geral"), restarted.last("channels").users());
    }

    @Test void staysConsistentWhenARecipientDisconnectsDuringAPresenceUpdate() {
        var ana = login("ana", "Ana", null, null);
        ana.command("create", "tech");
        var bia = login("bia", "Bia", "tech", null);
        ana.onPresence = () -> room.remove("ana");

        var leo = login("leo", "Leo", "tech", null);
        assertEquals(List.of("Bia", "Leo"), bia.last("presence").users());
        assertEquals(List.of("Bia", "Leo"), leo.last("presence").users());
    }

    private Peer login(String id, String name, String channel, String session) {
        var peer = new Peer(room, id);
        peer.login(name, channel, session);
        return peer;
    }

    private static final class Peer implements Room.Transport {
        final Room room;
        final String id;
        final List<String> frames = new ArrayList<>();
        Runnable onPresence = () -> {};
        boolean closed;

        Peer(Room room, String id) {
            this.room = room;
            this.id = id;
            room.open(id, this);
        }

        void login(String name, String channel, String session) {
            send(new Event("hello", "channels-v1", name, null, TOKEN, session, null, null, channel));
        }

        String command(String command, String channel) {
            String requestId = UUID.randomUUID().toString();
            send(new Event(command, requestId, null, null, null, null, null, null, channel));
            return requestId;
        }

        void send(Event event) { room.receive(id, Protocol.encode(event)); }

        Event last(String type) {
            return frames.reversed().stream().map(Protocol::decode).filter(e -> e.type().equals(type))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing " + type));
        }

        void expectChannel(String type, String channel) {
            assertEquals(channel, last(type).channel());
        }

        @Override public void send(String json) {
            frames.add(json);
            if (Protocol.decode(json).type().equals("presence")) onPresence.run();
        }

        @Override public void close() { closed = true; room.remove(id); }
    }
}
