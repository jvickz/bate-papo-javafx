package br.com.batepapo.server;

import br.com.batepapo.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomReentrancyTest {
    private static final String TOKEN = "room-test-access-code-123456789";
    static class Transport implements Room.Transport {
        final List<Event> events = new ArrayList<>();
        Runnable onClose = () -> {}, onMessage = () -> {};
        boolean closed;
        public void send(String json) { var e = Protocol.decode(json); events.add(e); if (e.type().equals("message")) onMessage.run(); }
        public void close() { closed = true; onClose.run(); }
    }
    void login(Room room, String id, String name, Transport transport) {
        room.open(id, transport);
        room.receive(id, Protocol.encode(new Event("hello", null, name, null, TOKEN, null, null, null)));
    }
    @Test void shutdownSurvivesSynchronousCloseCallbacks() {
        var room = new Room(TOKEN); var a = new Transport(); var b = new Transport();
        login(room, "a", "Ana", a); login(room, "b", "Bia", b);
        a.onClose = () -> room.remove("a"); b.onClose = () -> room.remove("b");
        assertDoesNotThrow(room::shutdown);
        assertTrue(a.closed); assertTrue(b.closed);
    }
    @Test void broadcastSurvivesReceiverDisconnectingDuringWrite() {
        var room = new Room(TOKEN); var a = new Transport(); var b = new Transport();
        login(room, "a", "Ana", a); login(room, "b", "Bia", b);
        a.onMessage = () -> room.remove("a");
        assertDoesNotThrow(() -> room.receive("b", Protocol.encode(new Event("message", "one", null, "Olá", null, null, null, null))));
        assertEquals(1, b.events.stream().filter(e -> e.type().equals("message")).count());
    }
    @Test void deduplicatesIdsAndKeepsUnauthenticatedObserversOut() {
        var room = new Room(TOKEN); var a = new Transport(); var observer = new Transport();
        room.open("observer", observer); login(room, "a", "Ana", a);
        String message = Protocol.encode(new Event("message", "same-id", null, "Oi", null, null, null, null));
        room.receive("a", message); room.receive("a", message);
        assertEquals(1, a.events.stream().filter(e -> e.type().equals("message")).count());
        assertTrue(observer.events.isEmpty());
    }
    @Test void boundsConnectionCountAndRejectsOversizedJson() {
        var room = new Room(TOKEN);
        for (int i = 0; i < 64; i++) room.open("id" + i, new Transport());
        var extra = new Transport(); room.open("extra", extra);
        assertTrue(extra.closed); assertEquals("full", extra.events.getFirst().id());
        var separate = new Room(TOKEN); var a = new Transport(); login(separate, "a", "Ana", a);
        separate.receive("a", "x".repeat(Protocol.MAX_BYTES + 1));
        assertEquals("invalid", a.events.getLast().id());
    }
}
