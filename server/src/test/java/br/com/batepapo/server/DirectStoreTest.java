package br.com.batepapo.server;

import br.com.batepapo.protocol.DirectChat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DirectStoreTest {
    @TempDir Path directory;

    private static void register(AccountStore store, String... names) throws Exception {
        for (String name : names) assertTrue(store.register(name, UUID.randomUUID().toString().toCharArray()));
    }

    private static DirectChat.Send send(String text) { return new DirectChat.Send(UUID.randomUUID().toString(), text); }

    @Test void limitsNewWritesPerSenderButAllowsRetriesAndOtherSenders() throws Exception {
        var accounts = AccountStore.local(directory.resolve("rate"));
        register(accounts, "Marina", "Nico", "Luna");
        var clock = Clock.fixed(Instant.parse("2025-01-01T12:00:00Z"), ZoneOffset.UTC);
        var store = new DirectStore(accounts, clock);
        var first = send("Oi");
        var saved = store.send("Marina", "Nico", first);
        for (int i = 1; i < 5; i++) store.send("Marina", "Nico", send("Outra " + i));
        var sixth = send("Oi, Luna");
        assertThrows(DirectStore.SendRateExceeded.class, () -> store.send("Marina", "Luna", sixth));
        assertEquals(saved, store.send("Marina", "Nico", first));
        store.send("Luna", "Marina", send("Olá!"));
        var nextSecond = new DirectStore(accounts, Clock.offset(clock, Duration.ofSeconds(1)));
        assertEquals(sixth.text(), nextSecond.send("Marina", "Luna", sixth).text());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WAVECOVE_TEST_POSTGRES_URL", matches = ".+")
    void postgresPersistsOfflineMessagesAndKeepsReadStateAndAccessScopedToParticipants() throws Exception {
        String url = System.getenv("WAVECOVE_TEST_POSTGRES_URL");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String author = "Marina_" + suffix, peer = "Nico_" + suffix, other = "Luna_" + suffix;
        var accounts = AccountStore.postgres(url);
        register(accounts, author, peer, other);
        var store = new DirectStore(accounts);
        var body = send("Depois conversamos 🌊\nÀs nove.");
        var message = store.send(author, peer.toLowerCase(Locale.ROOT), body);
        assertTrue(message.id() > 0);
        assertEquals(peer, message.recipient());
        assertEquals(message, store.send(author, peer, body));
        var reopened = new DirectStore(AccountStore.postgres(url));
        assertEquals(List.of(message), reopened.history(peer, author, Long.MAX_VALUE, false));
        assertEquals(body.text(), reopened.threads(peer).getFirst().preview());
        assertTrue(reopened.threads(peer).getFirst().unread());
        assertTrue(reopened.history(other, peer, Long.MAX_VALUE, false).isEmpty());
        assertTrue(reopened.threads(other).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reopened.markRead(other, peer, message.id()));
        reopened.markRead(peer, author, message.id());
        assertFalse(reopened.threads(peer).getFirst().unread());
        var answer = reopened.send(peer, author, send("Combinado!"));
        assertEquals(List.of(message), reopened.history(author, peer, answer.id(), false));
        assertEquals(List.of(answer), reopened.history(author, peer, message.id(), true));
        assertTrue(reopened.threads(author).getFirst().unread());
    }
}
