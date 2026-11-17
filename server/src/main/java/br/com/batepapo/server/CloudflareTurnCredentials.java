package br.com.batepapo.server;

import br.com.batepapo.protocol.VoiceConfig;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CloudflareTurnCredentials implements TurnCredentialsProvider {
    private static final URI CLOUDFLARE = URI.create("https://rtc.live.cloudflare.com");
    private static final int MAX_RESPONSE_BYTES = 8192;
    private final HttpClient http;
    private final HttpRequest request;
    private final Duration timeout;
    private final Semaphore slots = new Semaphore(8);

    static CloudflareTurnCredentials configured(String keyId, String apiToken) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,128}")
                || apiToken == null || !apiToken.matches("[A-Za-z0-9_-]{1,256}")) return null;
        return new CloudflareTurnCredentials(keyId, apiToken, CLOUDFLARE, Duration.ofSeconds(5));
    }

    CloudflareTurnCredentials(String keyId, String apiToken, URI origin, Duration timeout) {
        this.timeout = timeout;
        request = HttpRequest.newBuilder(origin.resolve("/v1/turn/keys/" + keyId + "/credentials/generate-ice-servers"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ttl\":3600}"))
                .build();
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public CompletionStage<List<VoiceConfig.IceServer>> issue() {
        if (!slots.tryAcquire()) return CompletableFuture.failedFuture(unavailable("capacity_limit"));
        try {
            var response = http.sendAsync(request, ignored -> limitedBody());
            // Also bound the time to receive the body after the headers arrive.
            return response.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .handle((result, error) -> {
                        if (error != null) {
                            response.cancel(true);
                            throw unavailable(requestFailure(error));
                        }
                        if (result.statusCode() != 201) throw unavailable("http_status=" + result.statusCode());
                        List<VoiceConfig.IceServer> servers;
                        try {
                            servers = VoiceConfig.decode(result.body()).iceServers();
                        } catch (IllegalArgumentException ignored) {
                            throw unavailable("invalid_ice_response");
                        }
                        if (servers.stream().noneMatch(CloudflareTurnCredentials::hasRelay))
                            throw unavailable("invalid_ice_response");
                        return servers;
                    }).whenComplete((result, error) -> slots.release());
        } catch (RuntimeException ignored) {
            slots.release();
            return CompletableFuture.failedFuture(unavailable("network_error"));
        }
    }

    private static HttpResponse.BodySubscriber<String> limitedBody() {
        var bytes = new ByteArrayOutputStream();
        return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk ->
                chunk.ifPresent(data -> {
                    if (data.length > MAX_RESPONSE_BYTES - bytes.size()) throw new ResponseTooLarge();
                    bytes.writeBytes(data);
                })), ignored -> bytes.toString(StandardCharsets.UTF_8));
    }

    private static boolean hasRelay(VoiceConfig.IceServer server) {
        return server.username() != null && !server.username().isBlank()
                && server.credential() != null && !server.credential().isBlank()
                && server.urls().stream().anyMatch(url -> url.startsWith("turn:") || url.startsWith("turns:"));
    }

    private static final class ResponseTooLarge extends RuntimeException {}

    private static String requestFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) return "timeout";
            if (cause instanceof ResponseTooLarge) return "response_too_large";
        }
        return "network_error";
    }

    private static IllegalStateException unavailable(String code) {
        // Log only controlled categories and the numeric HTTP status. Provider errors can contain secrets.
        System.err.println("TURN credentials: " + code);
        return new IllegalStateException("Credenciais de voz indisponíveis.");
    }

    @Override public void close() { http.shutdownNow(); }
}
