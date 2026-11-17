package br.com.batepapo.client;

import br.com.batepapo.protocol.Attachment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

record AttachmentTransfer(HttpClient http, URI server, String session, String channel) {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    CompletableFuture<Void> upload(Path file) {
        String filename = file.getFileName().toString();
        Attachment kind = Attachment.forName(filename);
        return CompletableFuture.supplyAsync(() -> {
            try (var input = Files.newInputStream(file)) {
                if (Files.size(file) > kind.limit()) throw new IllegalArgumentException(sizeMessage(kind));
                byte[] bytes = input.readNBytes(kind.limit() + 1);
                if (bytes.length > kind.limit()) throw new IllegalArgumentException(sizeMessage(kind));
                return bytes;
            } catch (IOException ex) { throw new CompletionException(ex); }
        }).thenCompose(bytes -> {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(filename.getBytes(StandardCharsets.UTF_8));
            var request = request(null).header(Attachment.CHANNEL_HEADER, channel)
                    .header(Attachment.FILENAME_HEADER, encoded).header("Content-Type", kind.mime())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            return bounded(http.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
        }).thenAccept(response -> {
            if (response.statusCode() != 201) throw new IllegalArgumentException(switch (response.statusCode()) {
                case 403, 409 -> "O anexo não foi enviado. Confira o canal ou tente novamente em instantes.";
                case 413 -> sizeMessage(kind);
                case 415 -> "O arquivo não é uma foto JPG/PNG ou um vídeo MP4 compatível. As fotos têm limite de 8 megapixels.";
                case 429 -> "Há outros anexos sendo enviados. Aguarde e tente novamente.";
                default -> "Não foi possível enviar o anexo.";
            });
        });
    }

    CompletableFuture<byte[]> download(String id, Attachment kind) {
        var request = request(id).GET().build();
        return bounded(http.sendAsync(request, info -> {
            if (info.statusCode() != 200) return HttpResponse.BodySubscribers.replacing(new byte[0]);
            if (!info.headers().firstValue("Content-Type").orElse("").equalsIgnoreCase(kind.mime()))
                throw new IllegalArgumentException("Formato de anexo inesperado.");
            var bytes = new ByteArrayOutputStream();
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofByteArrayConsumer(chunk -> chunk.ifPresent(part -> {
                        if (part.length > kind.limit() - bytes.size())
                            throw new IllegalArgumentException(sizeMessage(kind));
                        bytes.writeBytes(part);
                    })), ignored -> bytes.toByteArray());
        })).thenApply(response -> {
            if (response.statusCode() == 429)
                throw new IllegalArgumentException("Há outros downloads em andamento. Tente novamente em instantes.");
            if (response.statusCode() != 200)
                throw new IllegalArgumentException("Anexo indisponível. Ele pode ter expirado ou pertencer a outro canal.");
            return response.body();
        });
    }

    private HttpRequest.Builder request(String id) {
        return HttpRequest.newBuilder(Attachment.uri(server, id)).timeout(TIMEOUT)
                .header(Attachment.SESSION_HEADER, session);
    }

    private static <T> CompletableFuture<T> bounded(CompletableFuture<T> request) {
        return request.copy().orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).whenComplete((result, error) -> {
            if (error != null) request.cancel(true);
        });
    }

    private static String sizeMessage(Attachment kind) {
        return "Arquivo grande demais. Limite de " + kind.limit() / 1_000_000 + " MB.";
    }

    @Override public String toString() { return "AttachmentTransfer[redacted]"; }
}
