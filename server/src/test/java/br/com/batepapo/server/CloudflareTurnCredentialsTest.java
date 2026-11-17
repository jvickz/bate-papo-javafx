package br.com.batepapo.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareTurnCredentialsTest {
    private HttpServer server;

    @BeforeEach void setUp() throws Exception { server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
    @AfterEach void tearDown() { server.stop(0); }

    @Test void requestsOneHourCredentialsWithServerSideAuthorization() throws Exception {
        byte[] body = """
                {"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},
                               {"urls":["turns:turn.cloudflare.com:443?transport=tcp"],
                                "username":"temporary-user","credential":"temporary-password"}]}
                """.getBytes(StandardCharsets.UTF_8);
        var request = new AtomicReference<String>();
        server.createContext("/v1/turn/keys/key-id/credentials/generate-ice-servers", exchange -> {
            try (exchange) {
                request.set(exchange.getRequestMethod() + " "
                        + exchange.getRequestHeaders().getFirst("Authorization") + " "
                        + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                        + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (var turn = provider(server, Duration.ofSeconds(3))) {
            var servers = turn.issue().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("POST Bearer test-api-token application/json {\"ttl\":3600}", request.get());
            assertEquals(2, servers.size());
            assertEquals("temporary-user", servers.getLast().username());
            assertEquals("temporary-password", servers.getLast().credential());
        }
    }

    @Test void rejectsOversizedOrCredentiallessResponses() throws Exception {
        var content = new AtomicReference<>("{\"iceServers\":[{\"urls\":[\"stun:stun.cloudflare.com:3478\"]}]}");
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(201, 0);
                exchange.getResponseBody().write(content.get().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try (var turn = provider(server, Duration.ofSeconds(3))) {
            assertThrows(ExecutionException.class, () -> turn.issue().toCompletableFuture().get(5, TimeUnit.SECONDS));
            content.set(" ".repeat(8192) + "{\"iceServers\":[]}");
            assertThrows(ExecutionException.class, () -> turn.issue().toCompletableFuture().get(5, TimeUnit.SECONDS));
        }
    }

    @Test void endsARequestThatStallsAfterHeaders() throws Exception {
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(201, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (var turn = provider(server, Duration.ofMillis(250))) {
            assertThrows(ExecutionException.class, () -> turn.issue().toCompletableFuture().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_ERR)
    void diagnosesProviderRejectionAndInvalidIceWithoutLoggingTheirContents() throws Exception {
        var status = new AtomicInteger(401);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = """
                        {"iceServers":[{"credential":"provider-secret","urls":[]}],"detail":"private-response"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();

        var output = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (var logs = new PrintStream(output, true, StandardCharsets.UTF_8);
             var turn = provider(server, Duration.ofSeconds(3))) {
            System.setErr(logs);
            assertThrows(ExecutionException.class, () -> turn.issue().toCompletableFuture().get(5, TimeUnit.SECONDS));
            status.set(201);
            assertThrows(ExecutionException.class, () -> turn.issue().toCompletableFuture().get(5, TimeUnit.SECONDS));
        } finally {
            System.setErr(original);
        }
        String diagnostics = output.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("TURN credentials: http_status=401"));
        assertTrue(diagnostics.contains("TURN credentials: invalid_ice_response"));
        assertFalse(diagnostics.contains("private-response"));
        assertFalse(diagnostics.contains("provider-secret"));
        assertFalse(diagnostics.contains("test-api-token"));
        assertFalse(diagnostics.contains("key-id"));
    }

    @Test void invalidEnvironmentLeavesTurnDisabled() {
        assertNull(CloudflareTurnCredentials.configured(null, null));
        assertNull(CloudflareTurnCredentials.configured("key-id", " "));
        assertNull(CloudflareTurnCredentials.configured("../key-id", "test-api-token"));
        assertNull(CloudflareTurnCredentials.configured("key-id", "token\r\nheader"));
    }

    private static CloudflareTurnCredentials provider(HttpServer server, Duration timeout) {
        return new CloudflareTurnCredentials("key-id", "test-api-token",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), timeout);
    }
}
