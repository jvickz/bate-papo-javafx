package br.com.batepapo.server;

import br.com.batepapo.protocol.Attachment;
import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.Protocol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MessageDeletionTest {
    private final AtomicLong clock = new AtomicLong();
    private final AccountSessions accounts = new AccountSessions(clock::get);
    private final Room room = new Room(null, null, clock::get, accounts);

    @Test void verifiesAuthorAndChannelAndReconcilesCachedHistoryAfterReturning() {
        var ana = new Peer("Ana", true);
        var bia = new Peer("Bia", true);
        var old = new Peer("Antigo", false);
        var observer = new Peer("Mar", true);
        observer.change("create", "porto");
        ana.message("same-id", "Primeiro");
        String first = bia.last("message").session();
        assertNotNull(first, "O servidor deve identificar cada envio com uma referência própria.");
        assertNull(old.last("message").session());
        bia.message("same-id", "Segundo");
        String second = ana.last("message").session();
        assertNotEquals(first, second);
        bia.delete(first, "geral");
        assertNotNull(bia.last("deleteerror"));
        ana.delete(first, "porto");
        assertNotNull(ana.last("deleteerror"));
        assertEquals(0, bia.count("deleted"));
        bia.change("join", "porto");
        ana.delete(first, "geral");
        assertEquals(first, ana.last("deleted").id());
        assertEquals(0, old.count("deleted"));
        assertEquals(0, observer.count("deleted"));
        assertEquals(0, bia.count("deleted"));
        bia.change("join", "geral");
        assertEquals(List.of(second), bia.last("retained").users());
        room.remove(ana.id);
        var again = new Peer("Ana", true);
        again.message("same-id", "Novo envio");
        String latest = bia.last("message").session();
        assertNotEquals(first, latest);
        again.delete(first, "geral");
        assertEquals(0, bia.count("deleted"));
        again.delete(latest, "geral");
        assertEquals(latest, bia.last("deleted").id());
    }

    @Test void deletingAttachmentRevokesDownloadsAndPreservesOtherFiles() {
        var ana = new Peer("Ana", true);
        var bia = new Peer("Bia", true);
        String sender = ana.last("attachments").session(), reader = bia.last("attachments").session();
        assertTrue(room.publishAttachment(room.beginUpload(sender, "geral"), "foto.png", Attachment.PNG, new byte[]{1, 2}));
        Event photo = bia.last("attachment");
        assertNotNull(photo.session());
        assertNotNull(room.attachment(reader, photo.id()));
        assertTrue(room.publishAttachment(room.beginUpload(reader, "geral"), "outra.png", Attachment.PNG, new byte[]{3, 4}));
        Event other = bia.last("attachment");
        bia.delete(photo.session(), "geral");
        assertNotNull(room.attachment(reader, photo.id()));
        ana.delete(photo.session(), "geral");
        assertEquals(photo.session(), bia.last("deleted").id());
        assertNull(room.attachment(reader, photo.id()));
        assertNotNull(room.attachment(reader, other.id()));
    }

    @Test void aFullChannelInventoryFitsInOneFrameAndOnlyKeepsRecentPublications() {
        var ana = new Peer("Ana", true);
        ana.message("first", "Primeira");
        String first = ana.last("message").session();
        for (int index = 0; index < 500; index++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            ana.message("id-" + index, "Linha");
        }
        var viewer = new Peer("Mar", true);
        var retained = viewer.last("retained");
        assertEquals(500, retained.users().size());
        assertFalse(retained.users().contains(first));
        assertTrue(Protocol.encode(retained).length() < Protocol.MAX_BYTES);
        ana.delete(first, "geral");
        assertEquals(0, viewer.count("deleted"));
    }

    private final class Peer implements Room.Transport {
        final String id = UUID.randomUUID().toString();
        final List<Event> received = new ArrayList<>();
        Peer(String name, boolean deletion) {
            room.open(id, this);
            var features = deletion ? List.of("delete-v1", Attachment.CAPABILITY) : List.of(Attachment.CAPABILITY);
            command(new Event("hello", "channels-v1", name, null, accounts.issue(name), null, features, null, "geral"));
        }
        void message(String id, String text) { command(new Event("message", id, null, text, null, null, null, null, "geral")); }
        void delete(String ref, String channel) { command(new Event("delete", ref, "Ana", null, null, null, null, null, channel)); }
        void change(String type, String channel) { command(new Event(type, UUID.randomUUID().toString(), null, null, null, null, null, null, channel)); }
        void command(Event e) { room.receive(id, Protocol.encode(e)); }
        long count(String type) { return received.stream().filter(e -> e.type().equals(type)).count(); }
        Event last(String type) { return received.reversed().stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow(); }
        @Override public void send(String json) { received.add(Protocol.decode(json)); }
        @Override public void close() { room.remove(id); }
    }
}
