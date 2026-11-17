package br.com.batepapo.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountAccessTest {
    @Test void recognizesMissingAccountServerAndRejectsRedirectsWithoutSendingCredentialsAgain() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var forwarded = new AtomicInteger();
        server.createContext("/accounts/login", exchange -> {
            exchange.getResponseHeaders().add("Location", "/forward");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/forward", exchange -> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String address = "ws://127.0.0.1:" + server.getAddress().getPort() + "/chat";
        try (var access = new AccountAccess()) {
            var error = assertThrows(ExecutionException.class, () -> access.authenticate(address, "Marina", "senha de teste", true).get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("contas"));
            assertThrows(ExecutionException.class, () -> access.authenticate(address, "Marina", "senha de teste", false).get(5, TimeUnit.SECONDS));
            assertEquals(0, forwarded.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsUnrelatedIdentityAndMasksTheSessionToken() throws Exception {
        String token = "a".repeat(43);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/accounts/login", exchange -> {
            var bytes = ("{\"username\":\"Marina\",\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var access = new AccountAccess()) {
            var session = access.authenticate(address, "marina", "senha de teste", false).get(5, TimeUnit.SECONDS);
            assertEquals("Marina", session.username());
            assertFalse(session.toString().contains(token));
            assertThrows(ExecutionException.class, () -> access.authenticate(address, "Pedro", "senha de teste", false).get(5, TimeUnit.SECONDS));
        } finally { server.stop(0); }
    }
}
