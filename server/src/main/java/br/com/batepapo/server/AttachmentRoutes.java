package br.com.batepapo.server;

import br.com.batepapo.protocol.Attachment;
import io.javalin.http.Context;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Semaphore;

final class AttachmentRoutes {
    private final Room room;
    private final Semaphore uploads = new Semaphore(2);
    private final Semaphore downloads = new Semaphore(4);

    AttachmentRoutes(Room room) { this.room = room; }

    void upload(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        var permit = room.beginUpload(ctx.header(Attachment.SESSION_HEADER), ctx.header(Attachment.CHANNEL_HEADER));
        if (permit == null) { ctx.status(403); return; }

        String filename;
        Attachment type;
        try {
            String encoded = ctx.header(Attachment.FILENAME_HEADER);
            if (encoded == null || encoded.length() > 640) throw new IllegalArgumentException();
            filename = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            type = Attachment.forName(filename);
        } catch (IllegalArgumentException ex) { ctx.status(415); return; }

        if (ctx.req().getContentLengthLong() > type.limit()) { ctx.status(413); return; }
        if (!uploads.tryAcquire()) { ctx.status(429); return; }
        try {
            byte[] data;
            try (var input = ctx.bodyInputStream()) { data = input.readNBytes(type.limit() + 1); }
            if (data.length > type.limit()) { ctx.status(413); return; }
            if (!validContent(type, data)) { ctx.status(415); return; }
            ctx.status(room.publishAttachment(permit, filename, type, data) ? 201 : 409);
        } catch (IOException ex) {
            ctx.status(400);
        } finally {
            uploads.release();
        }
    }

    void download(Context ctx) throws IOException {
        ctx.header("Cache-Control", "no-store");
        String id = ctx.pathParam("id");
        if (!Attachment.validId(id)) { ctx.status(404); return; }
        var item = room.attachment(ctx.header(Attachment.SESSION_HEADER), id);
        if (item == null) { ctx.status(404); return; }
        if (!downloads.tryAcquire()) { ctx.status(429); return; }
        try {
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("Content-Disposition", "attachment");
            ctx.contentType(item.type().mime());
            ctx.res().setContentLength(item.data().length);
            ctx.res().getOutputStream().write(item.data());
            ctx.res().flushBuffer();
        } finally { downloads.release(); }
    }

    private static boolean validContent(Attachment type, byte[] data) {
        if (type == Attachment.MP4) {
            return data.length >= 16 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
        }
        // Read the header without expanding a large image on the server.
        try (var image = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) return false;
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(type.name())) return false;
                reader.setInput(image, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                return width > 0 && height > 0 && (long) width * height <= Attachment.MAX_IMAGE_PIXELS;
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException ex) { return false; }
    }
}
