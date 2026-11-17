package br.com.batepapo.server;

import br.com.batepapo.protocol.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatServerTest {
    private static final String TOKEN = "integration-test-access-code-123456";
    private ChatServer server;
    private final List<Peer> peers = new ArrayList<>();
    @BeforeEach void start() { server = new ChatServer(TOKEN, 0).start(); }
    @AfterEach void stop() { peers.forEach(Peer::close); server.close(); }
    Peer peer() { var p = new Peer(server.port()); peers.add(p); return p; }
    @Test void authenticatesAndBroadcastsUnicodeWithoutLeakingSecrets() throws Exception {
        var ana = peer(); ana.login("Ana", TOKEN, null); var welcome = ana.next("welcome");
        assertNotNull(welcome.session());
        var joao = peer(); joao.login("João", TOKEN, null); joao.next("welcome");
        assertEquals(List.of("Ana", "João"), joao.next("presence").users());
        ana.send(new Event("message", "abc", "Impostor", "Olá 👋\nTudo bem?", null, null, null, null));
        var received = joao.next("message");
        assertEquals("Ana", received.name()); assertEquals("abc", received.id());
        assertEquals("Olá 👋\nTudo bem?", received.text()); assertNotNull(received.time());
        assertNull(received.token()); assertNull(received.session());
        assertEquals("abc", ana.next("message").id());
        joao.close();
        Event presence; do { presence = ana.next("presence"); } while (presence.users().size() != 1);
        assertEquals(List.of("Ana"), presence.users());
        try (var http = HttpClient.newHttpClient()) {
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void rejectsWrongAccessAndUnauthenticatedSend() throws Exception {
        var bad = peer(); bad.login("Ana", "wrong", null);
        assertEquals("auth", bad.next("error").id());
        var anonymous = peer(); anonymous.send(Event.of("message", "intruso"));
        assertEquals("auth", anonymous.next("error").id());
        assertTrue(bad.events.stream().noneMatch(e -> "presence".equals(e.type())));
    }
    @Test void duplicateNameCannotKickOwnerButResumeCanReplaceOwnConnection() throws Exception {
        var first = peer(); first.login("Ana", TOKEN, null); var session = first.next("welcome").session();
        var duplicate = peer(); duplicate.login("ana", TOKEN, null);
        assertEquals("name", duplicate.next("error").id());
        var resumed = peer(); resumed.login("Ana", TOKEN, session);
        assertEquals(session, resumed.next("welcome").session());
        assertEquals(List.of("Ana"), resumed.next("presence").users());
        resumed.send(new Event("message", "new", null, "voltei", null, null, null, null));
        assertEquals("voltei", resumed.next("message").text());
    }
    @Test void malformedAndOversizedTextDoNotBreakOtherPeers() throws Exception {
        var bad = peer(); bad.login("Bad", TOKEN, null); bad.next("welcome");
        bad.raw("{"); assertEquals("invalid", bad.next("error").id());
        bad.send(new Event("message", "large", null, "x".repeat(2001), null, null, null, null));
        assertEquals("large", bad.next("error").id());
        var good = peer(); good.login("Good", TOKEN, null); good.next("welcome");
        good.send(new Event("message", "ok", null, "funciona", null, null, null, null));
        assertEquals("funciona", good.next("message").text());
    }
    @Test void boundsFloodAndDeduplicatesIds() throws Exception {
        var p = peer(); p.login("Ana", TOKEN, null); p.next("welcome");
        for (int i = 0; i < 8; i++) p.send(new Event("message", "id-" + i, null, "texto", null, null, null, null));
        assertNotNull(p.next("error"));
    }
    @Test void expiresUnauthenticatedConnections() throws Exception {
        var p = peer(); assertTrue(p.closed.await(13, TimeUnit.SECONDS));
    }
    @Test void requiresStrongConfiguredSecret() {
        assertThrows(IllegalArgumentException.class, () -> new ChatServer("short", 0));
    }

    @Test void sharesARealPhotoOnlyWithAuthenticatedPeersInTheChannel() throws Exception {
        var ana = peer();
        var bia = peer();
        var carla = peer();
        String a = mediaLogin(ana, "Ana");
        String b = mediaLogin(bia, "Bia");
        String c = mediaLogin(carla, "Carla");
        carla.send(new Event("create", "create", null, null, null, null, null, null, "privado"));
        carla.next("joined");

        var out = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 3,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out));
        byte[] png = out.toByteArray();
        URI origin = URI.create("http://localhost:" + server.port() + Attachment.PATH);
        try (var http = HttpClient.newHttpClient()) {
            var unauthorized = HttpRequest.newBuilder(origin).POST(HttpRequest.BodyPublishers.ofByteArray(png)).build();
            assertEquals(403, http.send(unauthorized, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(201, http.send(upload(origin, a, "férias.png", png), HttpResponse.BodyHandlers.discarding()).statusCode());
            var event = bia.next("attachment");
            assertEquals("férias.png", event.text());
            assertEquals("Ana", event.name());
            assertEquals("geral", event.channel());
            assertNull(event.session());
            assertNull(event.token());
            URI file = URI.create(origin + "/" + event.id());
            var denied = HttpRequest.newBuilder(file).header(Attachment.SESSION_HEADER, c).build();
            assertEquals(404, http.send(denied, HttpResponse.BodyHandlers.discarding()).statusCode());
            var request = HttpRequest.newBuilder(file).header(Attachment.SESSION_HEADER, b).build();
            var downloaded = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, downloaded.statusCode());
            assertArrayEquals(png, downloaded.body());
            assertEquals("image/png", downloaded.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(carla.events.stream().noneMatch(item -> "attachment".equals(item.type())));
        }
    }

    @Test void rejectsDisguisedFilesAndOversizedPhotos() throws Exception {
        String ana = mediaLogin(peer(), "Ana"), bia = mediaLogin(peer(), "Bia");
        URI origin = URI.create("http://localhost:" + server.port() + Attachment.PATH);
        try (var http = HttpClient.newHttpClient()) {
            var fake = upload(origin, ana, "foto.png", new byte[]{1, 2, 3});
            assertEquals(415, http.send(fake, HttpResponse.BodyHandlers.discarding()).statusCode());
            var oversized = upload(origin, bia, "foto.png", new byte[Attachment.PNG.limit() + 1]);
            assertEquals(413, http.send(oversized, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    private static String mediaLogin(Peer peer, String name) throws InterruptedException {
        peer.send(new Event("hello", "channels-v1", name, null, TOKEN, null, List.of(Attachment.CAPABILITY), null, "geral"));
        return peer.next("attachments").session();
    }

    private static HttpRequest upload(URI origin, String session, String filename, byte[] data) {
        String encoded = Base64.getUrlEncoder().encodeToString(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(origin).timeout(java.time.Duration.ofSeconds(10))
                .header(Attachment.SESSION_HEADER, session).header(Attachment.CHANNEL_HEADER, "geral")
                .header(Attachment.FILENAME_HEADER, encoded).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();
    }

    static class Peer implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final StringBuilder text = new StringBuilder();
        final HttpClient http = HttpClient.newHttpClient();
        final WebSocket socket;
        Peer(int port) { socket = http.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + port + "/chat"), this).join(); }
        void login(String name, String token, String session) { send(new Event("hello", null, name, null, token, session, null, null)); }
        void send(Event e) { raw(Protocol.encode(e)); }
        void raw(String s) { socket.sendText(s, true).join(); }
        Event next(String type) throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                var e = events.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && e.type().equals(type)) return e;
            }
            fail("Evento não recebido: " + type); return null;
        }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence s, boolean last) {
            text.append(s); if (last) { events.add(Protocol.decode(text.toString())); text.setLength(0); }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { closed.countDown(); return null; }
        @Override public void onError(WebSocket ws, Throwable ex) { closed.countDown(); }
        @Override public void close() { socket.abort(); http.shutdownNow(); }
    }
}
