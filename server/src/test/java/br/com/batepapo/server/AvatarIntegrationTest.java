package br.com.batepapo.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AvatarIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WRITE = "/profile/avatar";
    private static final String MARINA = "/profiles/mArInA/avatar";
    @TempDir Path directory;

    @Test void peopleCanSeeReplaceAndRemoveAnAccountPhotoAcrossLogins() throws Exception {
        var database = directory.resolve("accounts");
        try (var http = HttpClient.newHttpClient()) {
            String expiredToken;
            byte[] firstPhoto;
            try (var server = ChatServer.localAccounts(database, 0).start()) {
                var client = new Api(http, server.port());
                var health = client.send("GET", "/health", null, null, null);
                assertTrue(JSON.readTree(health.body()).path("profileAvatars").asBoolean());
                expiredToken = client.account("register", "Marina");
                var nico = client.account("register", "Nico");
                assertEquals(204, client.send("GET", MARINA, nico, null, null).statusCode());
                byte[] png = image(96, 0xff1170aa);
                byte[] withTrailer = Arrays.copyOf(png, png.length + 90);
                assertEquals(204, client.send("PUT", WRITE, expiredToken, "image/png", withTrailer).statusCode());
                var photo = client.send("GET", MARINA, nico, null, null);
                assertEquals(200, photo.statusCode());
                assertEquals("image/png", photo.headers().firstValue("Content-Type").orElseThrow());
                var validated = HttpRequest.newBuilder(photo.request(), (key, value) -> true)
                        .header("If-None-Match", photo.headers().firstValue("ETag").orElseThrow()).build();
                assertEquals(304, http.send(validated, HttpResponse.BodyHandlers.discarding()).statusCode());
                firstPhoto = photo.body();
                assertTrue(firstPhoto.length < withTrailer.length);
                assertEquals(0xff1170aa, ImageIO.read(new ByteArrayInputStream(firstPhoto)).getRGB(0, 0));
                assertEquals(204, client.send("DELETE", WRITE, nico, null, null).statusCode());
                assertArrayEquals(firstPhoto, client.send("GET", MARINA, nico, null, null).body());
                assertEquals(401, client.send("GET", MARINA, null, null, null).statusCode());
                assertEquals(401, client.send("PUT", WRITE, null, "image/png", png).statusCode());
                assertEquals(401, client.send("DELETE", WRITE, null, null, null).statusCode());
                assertEquals(404, client.send("GET", "/profiles/Desconhecido/avatar", nico, null, null).statusCode());
            }
            try (var server = ChatServer.localAccounts(database, 0).start()) {
                var client = new Api(http, server.port());
                assertEquals(401, client.send("GET", MARINA, expiredToken, null, null).statusCode());
                var marina = client.account("login", "marina");
                assertArrayEquals(firstPhoto, client.send("GET", MARINA, marina, null, null).body());
                assertEquals(204, client.send("PUT", WRITE, marina, "image/png", image(96, 0xff123456)).statusCode());
                assertEquals(0xff123456, ImageIO.read(new ByteArrayInputStream(client.send("GET", MARINA, marina, null, null).body())).getRGB(0, 0));
                assertEquals(204, client.send("DELETE", WRITE, marina, null, null).statusCode());
                assertEquals(204, client.send("GET", MARINA, marina, null, null).statusCode());
            }
        }
    }

    @Test void rejectsUnsupportedFilesAndPreservesTheExistingPhoto() throws Exception {
        try (var http = HttpClient.newHttpClient(); var server = ChatServer.localAccounts(directory.resolve("accounts"), 0).start()) {
            var client = new Api(http, server.port());
            var marina = client.account("register", "Marina");
            byte[] png = image(96, 0xff556677);
            assertEquals(204, client.send("PUT", WRITE, marina, "image/png", png).statusCode());
            byte[] saved = client.send("GET", MARINA, marina, null, null).body();
            assertEquals(415, client.send("PUT", WRITE, marina, "image/jpeg", png).statusCode());
            assertEquals(400, client.send("PUT", WRITE, marina, "image/png", new byte[12]).statusCode());
            assertEquals(400, client.send("PUT", WRITE, marina, "image/png", image(32, 0)).statusCode());
            assertEquals(400, client.send("PUT", WRITE, marina, "image/png", Arrays.copyOf(png, png.length - 1)).statusCode());
            assertEquals(413, client.send("PUT", WRITE, marina, "image/png", new byte[65_537]).statusCode());
            assertArrayEquals(saved, client.send("GET", MARINA, marina, null, null).body());
        }
    }

    @Test void accountsFromBeforeProfilePhotosKeepTheirCredentials() throws Exception {
        var database = directory.resolve("legacy");
        var previous = AccountStore.local(database);
        assertTrue(previous.register("Marina", "Senha segura para testes".toCharArray()));
        try (var db = previous.open(); var sql = db.createStatement()) {
            sql.executeUpdate("ALTER TABLE wavecove.accounts DROP COLUMN avatar_png");
        }
        try (var http = HttpClient.newHttpClient(); var server = ChatServer.localAccounts(database, 0).start()) {
            var client = new Api(http, server.port());
            var marina = client.account("login", "marina");
            assertEquals(204, client.send("GET", MARINA, marina, null, null).statusCode());
            assertEquals(204, client.send("PUT", WRITE, marina, "image/png", image(96, 0xff8adecc)).statusCode());
            assertEquals(200, client.send("GET", MARINA, marina, null, null).statusCode());
        }
    }

    private static byte[] image(int size, int color) throws Exception {
        var pixels = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, color);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(pixels, "png", bytes);
        return bytes.toByteArray();
    }

    private record Api(HttpClient http, int port) {
        HttpResponse<byte[]> send(String method, String path, String token, String type, byte[] body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (type != null) request.header("Content-Type", type);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        String account(String action, String name) throws Exception {
            var body = JSON.writeValueAsBytes(Map.of("username", name, "password", "Senha segura para testes"));
            var response = send("POST", "/accounts/" + action, null, "application/json", body);
            assertEquals(action.equals("register") ? 201 : 200, response.statusCode());
            return JSON.readTree(response.body()).get("token").textValue();
        }
    }
}
