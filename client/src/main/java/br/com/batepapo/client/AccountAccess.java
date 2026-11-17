package br.com.batepapo.client;

import br.com.batepapo.protocol.Protocol;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class AccountAccess implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Session(String username, String token) {
        @Override public String toString() { return "Wavecove session"; }
    }

    CompletableFuture<Session> authenticate(String address, String username, String password, boolean register) {
        try {
            String user = username == null ? "" : username.strip();
            if (!user.matches("[a-zA-Z0-9_]{3,24}"))
                throw new IllegalArgumentException("Use de 3 a 24 letras sem acento, números ou sublinhado no usuário.");
            if (password == null || password.isBlank()) throw new IllegalArgumentException("Digite sua senha.");
            int length = password.codePointCount(0, password.length());
            if (length > 128 || (register && length < 12))
                throw new IllegalArgumentException("A senha deve ter entre 12 e 128 caracteres.");
            var request = HttpRequest.newBuilder(endpoint(address, register ? "register" : "login"))
                    .timeout(TIMEOUT).header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of("username", user, "password", password)))).build();
            var pending = http.sendAsync(request, ignored -> new LimitedBody());
            return pending.copy().orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).handle((response, failure) -> {
                if (failure != null) {
                    pending.cancel(true);
                    throw new IllegalStateException("Não foi possível acessar o servidor. Confira sua conexão e tente novamente.", failure);
                }
                if (response.statusCode() != (register ? 201 : 200)) throw responseError(response.statusCode(), register);
                try {
                    var json = JSON.readTree(response.body());
                    if (json == null || !json.isObject()) throw new IOException();
                    String name = json.path("username").textValue();
                    String token = json.path("token").textValue();
                    if (name != null && name.equalsIgnoreCase(user) && name.matches("[A-Za-z0-9_]{3,24}")
                            && token != null && token.matches("[A-Za-z0-9_-]{20,128}")) return new Session(name, token);
                } catch (IOException ignored) { }
                throw new IllegalStateException("O servidor respondeu sem uma sessão válida. Tente entrar novamente.");
            });
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(ex);
        } catch (IOException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException("Não foi possível preparar o acesso à conta."));
        }
    }

    private static IllegalArgumentException responseError(int status, boolean register) {
        return new IllegalArgumentException(switch (status) {
            case 400 -> "Confira o formato do usuário e da senha.";
            case 401, 403 -> "Usuário ou senha incorretos.";
            case 404, 405 -> "Este servidor ainda não oferece contas do Wavecove. Confira o endereço.";
            case 409 -> register ? "Esse usuário já foi cadastrado. Escolha outro ou entre na sua conta." : "Essa conta já está conectada.";
            case 429 -> "Muitas tentativas. Aguarde um pouco antes de tentar novamente.";
            default -> "O acesso às contas está indisponível. Tente novamente em instantes.";
        });
    }

    void logout(String address, String token) {
        var request = HttpRequest.newBuilder(endpoint(address, "logout")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    private static URI endpoint(String address, String action) {
        URI ws = Protocol.serverUri(address);
        try {
            return new URI(ws.getScheme().equals("wss") ? "https" : "http", null, ws.getHost(), ws.getPort(),
                    "/accounts/" + action, null, null);
        } catch (URISyntaxException ex) { throw new IllegalArgumentException("Endereço do servidor inválido."); }
    }

    @Override public void close() { http.shutdown(); }
}
