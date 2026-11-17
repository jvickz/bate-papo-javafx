package br.com.batepapo.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Reject oversized bodies while receiving them, before allocating the whole response. */
final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
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
            if (remaining > 4096 - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(new IOException("Resposta do servidor excessiva."));
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
