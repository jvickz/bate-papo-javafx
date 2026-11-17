package br.com.batepapo.client;

import br.com.batepapo.protocol.Attachment;
import br.com.batepapo.protocol.Event;
import br.com.batepapo.server.ChatServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class OnlineSmokeTest {
    private static final String CHANNEL = "teste-rede";
    @TempDir Path files;

    /** Uses two existing, operator-owned accounts. All application messages are sent in #teste-rede. */
    @Test
    @EnabledIfEnvironmentVariable(named = "BATEPAPO_TEST_URL", matches = ".+")
    void twoExistingAccountsExchangeMessagesAndPhotoOnline() throws Exception {
        exercise(System.getenv("BATEPAPO_TEST_URL"), setting("WAVECOVE_TEST_USER_A"), setting("WAVECOVE_TEST_PASSWORD_A"),
                setting("WAVECOVE_TEST_USER_B"), setting("WAVECOVE_TEST_PASSWORD_B"));
    }

    @Test void twoAccountsExchangeMessagesAndPhotoOnLoopback() throws Exception {
        try (var server = ChatServer.localAccounts(files.resolve("accounts"), 0).start();
             var setup = new AccountAccess()) {
            String url = "http://127.0.0.1:" + server.port();
            String passwordA = UUID.randomUUID().toString(), passwordB = UUID.randomUUID().toString();
            var a = setup.authenticate(url, "Teste_A", passwordA, true).get(30, TimeUnit.SECONDS);
            var b = setup.authenticate(url, "Teste_B", passwordB, true).get(30, TimeUnit.SECONDS);
            setup.logout(url, a.token());
            setup.logout(url, b.token());
            exercise(url, a.username(), passwordA, b.username(), passwordB);
        }
    }

    private void exercise(String url, String nameA, String passwordA, String nameB, String passwordB) throws Exception {
        assertFalse(nameA.equalsIgnoreCase(nameB), "Use duas contas de teste distintas.");
        var eventsA = new Inbox();
        var eventsB = new Inbox();
        try (var accounts = new AccountAccess(); var a = new ChatConnection(eventsA::accept, state -> {});
             var b = new ChatConnection(eventsB::accept, state -> {})) {
            var userA = accounts.authenticate(url, nameA, passwordA, false).get(95, TimeUnit.SECONDS);
            try {
                var userB = accounts.authenticate(url, nameB, passwordB, false).get(95, TimeUnit.SECONDS);
                try {
                    a.connect(url, userA.username(), userA.token());
                    b.connect(url, userB.username(), userB.token());
                    var catalog = eventsA.await("channels", e -> true);
                    eventsB.await("channels", e -> true);
                    if (catalog.users().contains(CHANNEL)) a.joinChannel(CHANNEL);
                    else a.createChannel(CHANNEL);
                    eventsA.await("joined", e -> CHANNEL.equals(e.channel()));
                    var firstPresence = eventsA.await("presence", e -> CHANNEL.equals(e.channel()));
                    assertEquals(List.of(userA.username()), firstPresence.users(),
                            "Reserve #teste-rede para os dois participantes de teste.");
                    String isolated = a.send("Verificação de isolamento entre canais");
                    assertMessage(eventsA, isolated, userA.username(), "Verificação de isolamento entre canais");
                    b.joinChannel(CHANNEL);
                    eventsB.await("joined", e -> CHANNEL.equals(e.channel()));
                    assertFalse(eventsB.events.stream().anyMatch(e -> isolated.equals(e.id())));
                    var participants = eventsB.await("presence", e -> CHANNEL.equals(e.channel())
                            && e.users().containsAll(List.of(userA.username(), userB.username())));
                    assertEquals(2, participants.users().size(), "Reserve #teste-rede para os dois participantes de teste.");

                    String first = a.send("Verificação de entrega A → B");
                    Event firstDelivery = assertMessage(eventsB, first, userA.username(), "Verificação de entrega A → B");
                    String reply = b.send("Verificação de entrega B → A");
                    assertMessage(eventsA, reply, userB.username(), "Verificação de entrega B → A");

                    eventsA.await("attachments", e -> true);
                    eventsB.await("attachments", e -> true);
                    Path photo = files.resolve("teste-" + UUID.randomUUID() + ".png");
                    assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", photo.toFile()));
                    a.attachmentTransfer(CHANNEL).upload(photo).get(65, TimeUnit.SECONDS);
                    var media = eventsB.await("attachment", e -> userA.username().equals(e.name())
                            && CHANNEL.equals(e.channel()) && photo.getFileName().toString().equals(e.text()));
                    assertArrayEquals(Files.readAllBytes(photo),
                            b.attachmentTransfer(CHANNEL).download(media.id(), Attachment.PNG).get(65, TimeUnit.SECONDS));
                    a.deleteMessage(firstDelivery.session(), CHANNEL);
                    eventsB.await("deleted", e -> firstDelivery.session().equals(e.id()));
                    a.deleteMessage(media.session(), CHANNEL);
                    eventsB.await("deleted", e -> media.session().equals(e.id()));
                    var unavailable = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> b.attachmentTransfer(CHANNEL).download(media.id(), Attachment.PNG).get(65, TimeUnit.SECONDS));
                    assertTrue(unavailable.getCause().getMessage().contains("Anexo indisponível"));
                } finally {
                    accounts.logout(url, userB.token());
                }
            } finally {
                accounts.logout(url, userA.token());
            }
            // Both sockets attempt to reconnect after logout. The revoked tickets must be rejected.
            eventsA.await("error", e -> "auth".equals(e.id()));
            eventsB.await("error", e -> "auth".equals(e.id()));
        }
    }

    private static Event assertMessage(Inbox inbox, String id, String sender, String text) throws Exception {
        var message = inbox.await("message", e -> id.equals(e.id()));
        assertEquals(CHANNEL, message.channel());
        assertEquals(sender, message.name());
        assertEquals(text, message.text());
        return message;
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Defina " + name + " para o teste online.");
        return value;
    }

    private static final class Inbox {
        private final List<Event> events = new CopyOnWriteArrayList<>();

        void accept(Event event) {
            if (event.type().equals("channels") || event.type().equals("attachments") || event.type().equals("error")
                    || CHANNEL.equals(event.channel())) events.add(event);
        }

        Event await(String type, Predicate<Event> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            do {
                var match = events.stream().filter(e -> type.equals(e.type()) && condition.test(e)).findFirst();
                if (match.isPresent()) return match.get();
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Evento esperado em 60 segundos: " + type);
        }
    }
}
