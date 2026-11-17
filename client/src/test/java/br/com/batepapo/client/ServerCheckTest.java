package br.com.batepapo.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerCheckTest {
    private HttpServer server;
    private final ServerCheck check = new ServerCheck();

    @AfterEach void stop() {
        check.close();
        if (server != null) server.stop(0);
    }

    @Test void checksHealthAtTheSameOriginWithoutCredentials() throws Exception {
        var request = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var cookie = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] bytes = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();

        var result = check.check(address() + "/nested/chat").get(5, TimeUnit.SECONDS);

        assertTrue(result.reachable(), result.message());
        assertEquals("GET /health", request.get());
        assertNull(authorization.get());
        assertNull(cookie.get());
        assertFalse(result.message().isBlank());
    }

    @Test void rejectsNonSuccessStatusEvenWithHealthyJson() throws Exception {
        respond(404, "{\"status\":\"ok\"}");
        var result = check.check(address()).get(5, TimeUnit.SECONDS);
        assertFalse(result.reachable());
        assertTrue(result.message().contains("404"), result.message());
    }

    @Test void rejectsHtmlAndMalformedHealthPayloads() throws Exception {
        var body = new AtomicReference<>("<html><body>status ok</body></html>");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        for (String invalid : new String[]{"<html>status ok</html>", "{\"status\":\"down\"}",
                "{\"status\":\"ok\"} trailing", "[]", "null", "{\"status\":true}"}) {
            body.set(invalid);
            assertFalse(check.check(address()).get(5, TimeUnit.SECONDS).reachable(), invalid);
        }
    }

    @Test void rejectsAnOversizedResponseInsteadOfAcceptingItsHealthyPrefix() throws Exception {
        respond(200, "{\"status\":\"ok\",\"padding\":\"" + "x".repeat(65_536) + "\"}");
        assertFalse(check.check(address()).get(5, TimeUnit.SECONDS).reachable());
    }

    @Test void doesNotFollowRedirects() throws Exception {
        var followed = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/destination", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        assertFalse(check.check(address()).get(5, TimeUnit.SECONDS).reachable());
        assertEquals(0, followed.get());
    }

    @Test void rejectsInsecureRemoteAddressesAndEmbeddedCredentials() throws Exception {
        for (String invalid : new String[]{"ws://example.com/chat", "http://example.com",
                "https://user:secret@example.com", "https://example.com?code=secret"}) {
            var result = check.check(invalid).get(1, TimeUnit.SECONDS);
            assertFalse(result.reachable(), invalid);
            assertFalse(result.message().contains("secret"));
        }
    }

    @Test void closeFinishesAnInFlightCheckAndRefusesNewChecks() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/health", exchange -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            CompletableFuture<ServerCheck.Result> pending = check.check(address());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                check.close();
                assertFalse(pending.get(2, TimeUnit.SECONDS).reachable());
                assertFalse(check.check(address()).get(1, TimeUnit.SECONDS).reachable());
            } finally { release.countDown(); }
        }
    }

    private String address() { return "ws://127.0.0.1:" + server.getAddress().getPort(); }

    private void respond(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }
}
