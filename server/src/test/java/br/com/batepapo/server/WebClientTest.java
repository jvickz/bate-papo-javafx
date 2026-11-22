package br.com.batepapo.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WebClientTest {
    @Test void servesBrowserClientAndPreservesApiRouting(@TempDir Path directory) throws Exception {
        try (var server = ChatServer.localAccounts(directory.resolve("accounts"), 0).start();
             var http = HttpClient.newHttpClient()) {
            URI origin = URI.create("http://localhost:" + server.port());
            var page = http.send(HttpRequest.newBuilder(origin.resolve("/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Wavecove"));
            assertTrue(page.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
            for (String asset : List.of("styles.css", "app.js", "direct.css", "direct.js", "waterfall.png",
                    "vt323.ttf", "favicon.png", "registration.css", "harbor.js", "room.css", "room.js",
                    "waterfall-atlas.png", "secret-engine.js", "secret-engine.wasm", "secret-engine-license.txt")) {
                var response = http.send(HttpRequest.newBuilder(origin.resolve("/" + asset)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode(), asset);
                assertTrue(response.body().length > 0, asset);
            }
            var inbox = http.send(HttpRequest.newBuilder(origin.resolve("/direct/threads")).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(401, inbox.statusCode());
            var missing = http.send(HttpRequest.newBuilder(origin.resolve("/pagina-inexistente")).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(404, missing.statusCode());
        }
    }
}
