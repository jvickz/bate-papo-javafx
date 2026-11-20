package br.com.batepapo.server;

import io.javalin.http.Context;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

final class AvatarRoutes {
    private static final int SIZE = 96;
    private static final int MAX_BYTES = 64 * 1024;
    private final AccountStore store;
    private final AccountSessions sessions;
    private final Semaphore workers = new Semaphore(8);

    AvatarRoutes(AccountStore store, AccountSessions sessions) {
        this.store = store;
        this.sessions = sessions;
    }

    void get(Context ctx) {
        authenticated(ctx, owner -> {
            String name = ctx.pathParam("name");
            if (!name.matches("[A-Za-z0-9_]{3,24}")) { ctx.status(400); return; }
            byte[] photo = store.avatar(name);
            if (photo == null) { ctx.status(204); return; }
            String tag = tag(photo);
            ctx.header("ETag", tag);
            String condition = ctx.header("If-None-Match");
            if (condition != null) {
                for (String entry : condition.split(",")) {
                    String candidate = entry.strip();
                    if (candidate.equals(tag) || candidate.equals("W/" + tag) || candidate.equals("*")) {
                        ctx.status(304); return;
                    }
                }
            }
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.contentType("image/png").result(photo);
        });
    }

    void put(Context ctx) {
        authenticated(ctx, owner -> {
            String type = ctx.contentType();
            if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("image/png")) { ctx.status(415); return; }
            if (ctx.req().getContentLengthLong() > MAX_BYTES) { ctx.status(413); return; }
            byte[] body;
            try (var input = ctx.bodyInputStream()) { body = input.readNBytes(MAX_BYTES + 1); }
            if (body.length > MAX_BYTES) { ctx.status(413); return; }
            store.setAvatar(owner, normalizedPng(body));
            ctx.status(204);
        });
    }

    void remove(Context ctx) {
        authenticated(ctx, owner -> {
            store.setAvatar(owner, null);
            ctx.status(204);
        });
    }

    private static byte[] normalizedPng(byte[] body) throws IOException {
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        BufferedImage image;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(pixelChunks(body)))) {
            reader.setInput(input, true, true);
            if (reader.getWidth(0) != SIZE || reader.getHeight(0) != SIZE) throw new IOException("Tamanho inválido.");
            image = reader.read(0);
        } catch (RuntimeException ex) {
            throw new IOException("Foto inválida.", ex);
        } finally { reader.dispose(); }

        // Rebuild an 8-bit image from the pixels; uploaded metadata and trailing bytes stay out of the database.
        var pixels = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, SIZE, SIZE, image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE), 0, SIZE);
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(pixels, "png", output);
        }
        return bytes.toByteArray();
    }

    private static byte[] pixelChunks(byte[] file) throws IOException {
        var input = ByteBuffer.wrap(file);
        if (input.remaining() < 8 || input.getLong() != 0x89504e470d0a1a0aL) throw new IOException("PNG inválido.");
        var clean = new ByteArrayOutputStream(file.length);
        clean.write(file, 0, 8);
        var crc = new CRC32();
        while (input.remaining() >= 12) {
            int start = input.position();
            int length = input.getInt();
            String type = new String(file, input.position(), 4, StandardCharsets.US_ASCII);
            input.position(input.position() + 4);
            if (length < 0 || length > input.remaining() - 4) throw new IOException("Bloco PNG incompleto.");
            input.position(input.position() + length);
            crc.reset();
            crc.update(file, start + 4, length + 4);
            if (input.getInt() != (int) crc.getValue()) throw new IOException("Bloco PNG inválido.");
            // Only image data and palette transparency are needed. Skip text, profiles and other metadata.
            switch (type) {
                case "IHDR", "PLTE", "tRNS", "IDAT" -> clean.write(file, start, length + 12);
                case "IEND" -> {
                    if (length != 0) throw new IOException("Fim do PNG inválido.");
                    clean.write(file, start, 12);
                    return clean.toByteArray();
                }
                default -> {
                    if (!Character.isLowerCase(type.charAt(0))) throw new IOException("Bloco PNG obrigatório desconhecido.");
                }
            }
        }
        throw new IOException("PNG incompleto.");
    }

    private static String tag(byte[] png) {
        try { return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)) + "\""; }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    @FunctionalInterface
    private interface Action { void run(String owner) throws IOException, SQLException; }

    private void authenticated(Context ctx, Action action) {
        ctx.header("Cache-Control", "private, no-store");
        ctx.header("Vary", "Authorization");
        String bearer = ctx.header("Authorization");
        String owner = bearer != null && bearer.startsWith("Bearer ") ? sessions.owner(bearer.substring(7)) : null;
        if (owner == null) { ctx.status(401); return; }
        if (!workers.tryAcquire()) { ctx.header("Retry-After", "1"); ctx.status(503); return; }
        try { action.run(owner); }
        catch (IOException ex) { ctx.status(400); }
        catch (NoSuchElementException ex) { ctx.status(404); }
        catch (SQLException ex) { ctx.status(503); }
        finally { workers.release(); }
    }
}
