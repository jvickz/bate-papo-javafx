package br.com.batepapo.server;

import br.com.batepapo.protocol.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AccountIntegrationTest {
    private static final String PASSWORD = "uma frase de teste 🌊";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private HttpClient http;
    private ChatServer server;

    @BeforeEach void start() {
        http = HttpClient.newHttpClient();
        startServer();
    }

    private void startServer() { server = ChatServer.localAccounts(directory.resolve("accounts"), 0).start(); }

    @AfterEach void close() { server.close(); http.close(); }

    private HttpRequest.Builder route(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/accounts/" + path));
    }

    private HttpRequest credentials(String path, String name, String password) throws Exception {
        return route(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                JSON.writeValueAsString(Map.of("username", name, "password", password)))).build();
    }

    private HttpResponse<String> request(String path, String name, String password) throws Exception {
        return http.send(credentials(path, name, password), HttpResponse.BodyHandlers.ofString());
    }

    private String token(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var json = JSON.readTree(response.body());
        assertEquals(2, json.size());
        assertFalse(json.path("username").asText().isBlank());
        assertTrue(json.path("token").asText().length() >= 32);
        return json.path("token").asText();
    }

    @Test void returningUserKeepsCanonicalNameAndPasswordAcrossServerRestart() throws Exception {
        String original = token(request("register", "Marina", PASSWORD), 201);
        assertEquals(409, request("register", "MARINA", "outra senha suficientemente longa").statusCode());
        server.close();
        startServer();
        assertEquals(401, request("login", "marina", "senha incorreta longa").statusCode());
        var login = request("login", "marina", PASSWORD);
        String restored = token(login, 200);
        assertNotEquals(original, restored);
        assertEquals("Marina", JSON.readTree(login.body()).path("username").asText());
    }

    @Test void bindsWebSocketPresenceAndMessagesToTheAuthenticatedAccount() throws Exception {
        String marina = token(request("register", "Marina", PASSWORD), 201);
        String pedro = token(request("register", "Pedro", PASSWORD), 201);
        try (var fake = new ChatServerTest.Peer(server.port()); var first = new ChatServerTest.Peer(server.port());
             var second = new ChatServerTest.Peer(server.port()); var bypass = new ChatServerTest.Peer(server.port())) {
            fake.login("Pedro", marina, null);
            assertEquals("auth", fake.next("error").id());
            bypass.login("Marina", "legacy-code-that-was-on-render", null);
            assertEquals("auth", bypass.next("error").id());
            first.login("marina", marina, null);
            assertEquals("Marina", first.next("welcome").name());
            second.login("Pedro", pedro, null);
            second.next("welcome");
            assertEquals(List.of("Marina", "Pedro"), second.next("presence").users());
            first.send(new Event("message", "ok", "Pedro", "Oi!", null, null, null, null));
            assertEquals("Marina", second.next("message").name());
        }
    }

    @Test void logoutClosesOnlyThatSessionAndBlocksResume() throws Exception {
        String marina = token(request("register", "Marina", PASSWORD), 201);
        try (var first = new ChatServerTest.Peer(server.port()); var retry = new ChatServerTest.Peer(server.port())) {
            first.login("Marina", marina, null);
            String resume = first.next("welcome").session();
            var logout = route("logout").header("Authorization", "Bearer " + marina)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(204, http.send(logout, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertTrue(first.closed.await(3, TimeUnit.SECONDS));
            retry.login("Marina", marina, resume);
            assertEquals("auth", retry.next("error").id());
            token(request("login", "Marina", PASSWORD), 200);
        }
    }

    @Test void validatesLengthInCodePointsAndBoundsRequestBodies() throws Exception {
        assertEquals(400, request("register", "m aria", PASSWORD).statusCode());
        assertEquals(400, request("register", "Maria", "🌊".repeat(11)).statusCode());
        assertEquals(400, request("register", "Maria", "a".repeat(129)).statusCode());
        assertEquals(413, request("register", "Maria", "a".repeat(10000)).statusCode());
        token(request("register", "Maria", "🌊".repeat(128)), 201);
    }

    @Test void throttlesRepeatedCredentialsEvenWithDifferentForwardedHeaders() throws Exception {
        int status = 0;
        for (int i = 0; i < 15 && status != 429; i++) {
            var req = HttpRequest.newBuilder(credentials("login", "Marina", PASSWORD), (k, v) -> true)
                    .header("X-Forwarded-For", "192.0.2." + (i + 1)).build();
            status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            assertTrue(status == 401 || status == 429);
        }
        assertEquals(429, status);
    }
}
