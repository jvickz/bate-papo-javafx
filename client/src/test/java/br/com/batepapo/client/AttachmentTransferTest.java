package br.com.batepapo.client;

import br.com.batepapo.protocol.Attachment;
import br.com.batepapo.protocol.Event;
import br.com.batepapo.server.ChatServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentTransferTest {
    @TempDir Path files;

    @Test void transfersPhotoAndVideoThroughRealChatClients() throws Exception {
        String code = "attachment-test-access-code-123456";
        var senderReady = new CompletableFuture<Void>();
        var receiverReady = new CompletableFuture<Void>();
        var received = new java.util.concurrent.LinkedBlockingQueue<Event>();
        var movieReady = new CompletableFuture<Void>();
        try (var server = new ChatServer(code, 0).start();
             var sender = new ChatConnection(event -> {
                 if (event.type().equals("attachments")) senderReady.complete(null);
             }, state -> {});
             var movieSender = new ChatConnection(event -> {
                 if (event.type().equals("attachments")) movieReady.complete(null);
             }, state -> {});
             var receiver = new ChatConnection(event -> {
                 if (event.type().equals("attachments")) receiverReady.complete(null);
                 if (event.type().equals("attachment")) received.add(event);
             }, state -> {})) {
            String address = "ws://localhost:" + server.port();
            sender.connect(address, "Ana", code);
            movieSender.connect(address, "Caio", code);
            receiver.connect(address, "Bia", code);
            CompletableFuture.allOf(senderReady, receiverReady, movieReady).get(10, TimeUnit.SECONDS);

            Path photo = files.resolve("Férias no mar.jpg");
            assertTrue(ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "jpeg", photo.toFile()));
            sender.attachmentTransfer("geral").upload(photo).get(10, TimeUnit.SECONDS);
            Event message = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(message);
            assertEquals(photo.getFileName().toString(), message.text());
            assertArrayEquals(Files.readAllBytes(photo), receiver.attachmentTransfer("geral")
                    .download(message.id(), Attachment.JPEG).get(10, TimeUnit.SECONDS));

            // The attachment transport preserves MP4 bytes; playback is handled by the OS.
            byte[] container = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                    0, 0, 0, 0, 'i', 's', 'o', 'm'};
            Path movie = Files.write(files.resolve("Passeio.mp4"), container);
            movieSender.attachmentTransfer("geral").upload(movie).get(10, TimeUnit.SECONDS);
            Event video = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(video);
            assertEquals(movie.getFileName().toString(), video.text());
            assertArrayEquals(container, receiver.attachmentTransfer("geral")
                    .download(video.id(), Attachment.MP4).get(10, TimeUnit.SECONDS));
        }
    }
}
