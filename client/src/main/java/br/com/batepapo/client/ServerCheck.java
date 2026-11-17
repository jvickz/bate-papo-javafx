package br.com.batepapo.client;

import br.com.batepapo.protocol.Protocol;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

public final class ServerCheck implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 4096;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Result CLOSED = new Result(false, "Verificação encerrada.");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                    .maxStringLength(MAX_RESPONSE_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "bate-papo-server-check");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<CompletableFuture<Result>, CompletableFuture<?>> pending = new ConcurrentHashMap<>();
    private boolean closed;

    public record Result(boolean reachable, String message) {}

    public synchronized CompletableFuture<Result> check(String address) {
        if (closed) return CompletableFuture.completedFuture(CLOSED);
        final URI health;
        try {
            URI server = Protocol.serverUri(address);
            health = new URI(server.getScheme().equals("wss") ? "https" : "http", null,
                    server.getHost(), server.getPort(), "/health", null, null);
        } catch (IllegalArgumentException | URISyntaxException ex) {
            return CompletableFuture.completedFuture(new Result(false,
                    "Use o endereço HTTPS/WSS do servidor. WS é permitido apenas em localhost."));
        }

        var request = HttpRequest.newBuilder(health).timeout(TIMEOUT)
                .header("Accept", "application/json").GET().build();
        var result = new CompletableFuture<Result>();
        var network = client.sendAsync(request, ignored -> new LimitedBody());
        pending.put(result, network);
        var deadline = timer.schedule(() -> {
            if (result.complete(unavailable())) network.cancel(true);
        }, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        result.whenComplete((value, failure) -> {
            deadline.cancel(false);
            pending.remove(result);
            if (result.isCancelled()) network.cancel(true);
        });
        network.whenComplete((response, failure) -> result.complete(failure == null
                ? inspect(response) : failureResult(failure)));
        return result;
    }

    private static Result inspect(HttpResponse<byte[]> response) {
        if (response.statusCode() != 200) {
            return new Result(false, "O servidor respondeu HTTP " + response.statusCode()
                    + ". Confira o endereço. Se o serviço gratuito estiver iniciando, aguarde e tente novamente.");
        }
        try {
            var payload = JSON.readTree(response.body());
            if (payload != null && payload.isObject() && payload.path("status").isTextual()
                    && payload.path("status").textValue().equals("ok")) {
                return new Result(true, "Servidor disponível. Use o mesmo endereço e código nos dois computadores.");
            }
        } catch (IOException ignored) { }
        return new Result(false, "Esse endereço respondeu, mas não foi identificado como servidor do Wavecove. Confira o endereço e tente novamente.");
    }

    private static Result failureResult(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return new Result(false, "Não foi possível validar a conexão segura. Confira o endereço e a data e hora do Windows.");
            }
        }
        return unavailable();
    }

    private static Result unavailable() {
        return new Result(false, "Não foi possível alcançar o servidor. Confira sua conexão e o endereço. Se o serviço gratuito estiver iniciando, aguarde um minuto e tente novamente.");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.forEach((result, network) -> {
            result.complete(CLOSED);
            network.cancel(true);
        });
        pending.clear();
        timer.shutdownNow();
        client.shutdownNow();
    }

    /** Reject oversized bodies while receiving them, before allocating the whole response. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                if (remaining > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Resposta de saúde excessiva."));
                    return;
                }
                byte[] chunk = new byte[remaining];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
