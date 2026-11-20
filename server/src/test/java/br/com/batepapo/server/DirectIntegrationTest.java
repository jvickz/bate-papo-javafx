package br.com.batepapo.server;

import br.com.batepapo.protocol.DirectChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DirectIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "Senha para testes de conversa";
    @TempDir Path directory;
    private final HttpClient http = HttpClient.newHttpClient();
    private ChatServer server;

    @BeforeEach void start() { server = ChatServer.localAccounts(directory.resolve("accounts"), 0).start(); }
    @AfterEach void close() { server.close(); http.close(); }

    private HttpRequest request(String path, String token, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.header("Content-Type", "application/json").POST(
                HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        return req.build();
    }

    private JsonNode run(int expected, String path, String token, Object body) throws Exception {
        var response = http.send(request(path, token, body), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected, response.statusCode(), () -> path + " " + new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        if (path.startsWith("/direct/")) assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        return response.body().length == 0 ? null : JSON.readTree(response.body());
    }

    private String account(String action, String name) throws Exception {
        return run(action.equals("register") ? 201 : 200, "/accounts/" + action, null,
                Map.of("username", name, "password", PASSWORD)).get("token").asText();
    }

    private DirectChat.Send send(String text) { return new DirectChat.Send(UUID.randomUUID().toString(), text); }

    private HttpResponse<String> poll(String token, String tag) throws Exception {
        var req = HttpRequest.newBuilder(request("/direct/threads", token, null), (key, value) -> true);
        if (tag != null) req.header("If-None-Match", tag);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void pollingOnlyReturnsTheInboxAfterItChangesAndValidatorsAreAccountScoped() throws Exception {
        String first = account("register", "Marina"), second = account("register", "Nico");
        var empty = poll(first, null);
        String emptyTag = empty.headers().firstValue("ETag").orElseThrow();
        assertEquals(200, empty.statusCode());
        assertEquals("private, no-store", empty.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(200, poll(second, emptyTag).statusCode());
        assertEquals(401, poll(null, "*").statusCode());
        var unchanged = poll(first, emptyTag);
        assertEquals(304, unchanged.statusCode());
        assertEquals("", unchanged.body());
        var sent = run(200, "/direct/messages/Marina", second, send("Cheguei"));
        var received = poll(first, emptyTag);
        assertEquals(200, received.statusCode());
        long id = JSON.readTree(received.body()).get(0).path("latestId").longValue();
        assertEquals(sent.get("id").longValue(), id);
        String unreadTag = received.headers().firstValue("ETag").orElseThrow();
        assertEquals(304, poll(first, "W/" + unreadTag).statusCode());
        run(204, "/direct/read/Nico", first, new DirectChat.Read(id));
        assertEquals(200, poll(first, unreadTag).statusCode());

        String text = "🌊".repeat(2000);
        var send = HttpRequest.newBuilder(request("/direct/messages/Marina", second, null), (k, v) -> true)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(send(text)))).build();
        var emoji = http.send(send, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, emoji.statusCode());
        assertEquals(text, JSON.readTree(emoji.body()).get("text").textValue());
        assertTrue(emoji.body().length < 16_384, "Mensagens de emoji também devem ser transferidas em UTF-8");
    }

    @Test void privateOfflineDeliveryAndUnreadSurviveServerRestart() throws Exception {
        assertTrue(run(200, "/health", null, null).path("directMessages").asBoolean());
        var marina = account("register", "Marina");
        var nico = account("register", "Nico");
        var other = account("register", "Luna");
        run(204, "/accounts/logout", nico, Map.of());
        run(401, "/direct/threads", nico, null);
        run(401, "/direct/messages/Marina", null, null);
        run(401, "/direct/messages/Nico", null, send("sem sessão"));
        run(400, "/direct/messages/MARINA", marina, send("mesma conta"));
        run(404, "/direct/messages/Fantasma", marina, send("indisponível"));
        var submitted = send("Até amanhã 🌊");
        var message = run(200, "/direct/messages/nIcO", marina, submitted);
        long id = message.path("id").longValue();
        assertEquals("Marina", message.path("sender").textValue());
        assertEquals("Nico", message.path("recipient").textValue());
        assertEquals(message, run(200, "/direct/messages/NICO", marina, submitted));
        run(409, "/direct/messages/Nico", marina, new DirectChat.Send(submitted.clientId(), "outra mensagem"));
        run(409, "/direct/messages/Luna", marina, submitted);
        assertTrue(run(200, "/direct/threads", other, null).isEmpty());
        assertTrue(run(200, "/direct/messages/Marina", other, null).isEmpty());
        run(400, "/direct/read/Marina", other, new DirectChat.Read(id));
        assertFalse(run(200, "/direct/threads", marina, null).get(0).path("unread").asBoolean());

        server.close();
        start();
        run(401, "/direct/messages/Nico", marina, null);
        nico = account("login", "nico");
        var summary = run(200, "/direct/threads", nico, null).get(0);
        assertTrue(summary.path("unread").asBoolean());
        assertEquals("Marina", summary.path("peer").asText());
        assertEquals(submitted.text(), summary.path("preview").asText());
        assertEquals(message, run(200, "/direct/messages/marina", nico, null).get(0));
        marina = account("login", "marina");
        long later = run(200, "/direct/messages/Nico", marina, send("Segunda mensagem")).path("id").longValue();
        run(204, "/direct/read/Marina", nico, new DirectChat.Read(id));
        assertTrue(run(200, "/direct/threads", nico, null).get(0).path("unread").asBoolean());
        run(204, "/direct/read/Marina", nico, new DirectChat.Read(later));
        run(204, "/direct/read/Marina", nico, new DirectChat.Read(id));
        assertFalse(run(200, "/direct/threads", nico, null).get(0).path("unread").asBoolean());
        run(200, "/direct/messages/Marina", nico, send("Recebi!"));
        assertTrue(run(200, "/direct/threads", marina, null).get(0).path("unread").asBoolean());
        assertEquals("Recebi!", run(200, "/direct/messages/Nico", marina, null).get(2).path("text").asText());
    }

    @Test void concurrentRetriesSaveOneCopyAndPagesRemainOrdered() throws Exception {
        var sender = account("register", "Marina");
        account("register", "Nico");
        var path = "/direct/messages/Nico";
        var original = request(path, sender, send("primeira"));
        var one = http.sendAsync(original, HttpResponse.BodyHandlers.ofString());
        var two = http.sendAsync(original, HttpResponse.BodyHandlers.ofString());
        var response = one.get(10, TimeUnit.SECONDS);
        var replay = two.get(10, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals(200, replay.statusCode());
        assertEquals(response.body(), replay.body());
        for (int i = 1; i <= DirectChat.PAGE_SIZE; i++) {
            var req = request(path, sender, send("mensagem " + i));
            var sent = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (sent.statusCode() == 429) {
                assertEquals("1", sent.headers().firstValue("Retry-After").orElseThrow());
                Thread.sleep(1000);
                sent = http.send(req, HttpResponse.BodyHandlers.discarding());
            }
            assertEquals(200, sent.statusCode());
        }
        var latest = run(200, path, sender, null);
        assertEquals(DirectChat.PAGE_SIZE, latest.size());
        for (int i = 0; i < latest.size(); i++) assertEquals("mensagem " + (i + 1), latest.get(i).path("text").textValue());
        var oldest = run(200, path + "?before=" + latest.get(0).path("id").longValue(), sender, null);
        assertEquals(1, oldest.size());
        assertEquals("primeira", oldest.get(0).path("text").textValue());
        var firstPage = run(200, path + "?after=0", sender, null);
        assertEquals(DirectChat.PAGE_SIZE, firstPage.size());
        assertEquals(oldest.get(0), firstPage.get(0));
        long lastSeen = firstPage.get(DirectChat.PAGE_SIZE - 1).get("id").longValue();
        var updates = run(200, path + "?after=" + lastSeen, sender, null);
        assertEquals(1, updates.size());
        assertEquals(latest.get(DirectChat.PAGE_SIZE - 1), updates.get(0));
        assertTrue(run(200, path + "?after=" + updates.get(0).get("id").longValue(), sender, null).isEmpty());
        run(400, path + "?before=2&after=1", sender, null);
        run(400, path + "?after=-1", sender, null);
        run(400, path + "?before=0", sender, null);
    }
}
