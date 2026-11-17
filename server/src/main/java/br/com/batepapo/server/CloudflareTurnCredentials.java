package br.com.batepapo.server;

import br.com.batepapo.protocol.VoiceConfig;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
        if (!slots.tryAcquire()) return CompletableFuture.failedFuture(unavailable());
        try {
            var response = http.sendAsync(request, ignored -> limitedBody());
            // Also bound the time to receive the body after the headers arrive.
            return response.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .handle((result, error) -> {
                        if (error != null) {
                            response.cancel(true);
                            throw unavailable();
                        }
                        if (result.statusCode() != 201) throw unavailable();
                        var servers = VoiceConfig.decode(result.body()).iceServers();
                        if (servers.stream().noneMatch(CloudflareTurnCredentials::hasRelay)) throw unavailable();
                        return servers;
                    }).whenComplete((result, error) -> slots.release());
        } catch (RuntimeException ignored) {
            slots.release();
            return CompletableFuture.failedFuture(unavailable());
        }
    }

    private static HttpResponse.BodySubscriber<String> limitedBody() {
        var bytes = new ByteArrayOutputStream();
        return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk ->
                chunk.ifPresent(data -> {
                    if (data.length > MAX_RESPONSE_BYTES - bytes.size()) throw unavailable();
                    bytes.writeBytes(data);
                })), ignored -> bytes.toString(StandardCharsets.UTF_8));
    }

    private static boolean hasRelay(VoiceConfig.IceServer server) {
        return server.username() != null && !server.username().isBlank()
                && server.credential() != null && !server.credential().isBlank()
                && server.urls().stream().anyMatch(url -> url.startsWith("turn:") || url.startsWith("turns:"));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Credenciais de voz indisponíveis.");
    }

    @Override public void close() { http.shutdownNow(); }
}
