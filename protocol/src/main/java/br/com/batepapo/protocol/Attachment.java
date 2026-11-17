package br.com.batepapo.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;

public enum Attachment {
    JPEG("image/jpeg", 5_000_000), PNG("image/png", 5_000_000), MP4("video/mp4", 15_000_000);

    public static final String PATH = "/attachments";
    public static final String SESSION_HEADER = "X-Wavecove-Session";
    public static final String CHANNEL_HEADER = "X-Wavecove-Channel";
    public static final String FILENAME_HEADER = "X-Wavecove-Filename";
    public static final String CAPABILITY = "attachments-v1";
    public static final long MAX_IMAGE_PIXELS = 8_000_000;
    private final String mime;
    private final int limit;

    Attachment(String mime, int limit) { this.mime = mime; this.limit = limit; }
    public String mime() { return mime; }
    public int limit() { return limit; }
    public boolean photo() { return this != MP4; }

    public static Attachment forName(String name) {
        if (name == null || name.length() > 120 || name.isBlank()
                || name.matches(".*[\\\\/:*?\"<>|].*")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("Use um nome de arquivo válido de até 120 caracteres.");
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return JPEG;
        if (lower.endsWith(".png")) return PNG;
        if (lower.endsWith(".mp4")) return MP4;
        throw new IllegalArgumentException("Escolha uma foto JPG/PNG ou um vídeo MP4.");
    }

    public static boolean validId(String id) {
        try { return id != null && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException ex) { return false; }
    }

    public static URI uri(URI websocket, String id) {
        if (id != null && !validId(id)) throw new IllegalArgumentException("Anexo inválido.");
        try {
            return new URI("wss".equals(websocket.getScheme()) ? "https" : "http", null,
                    websocket.getHost(), websocket.getPort(), PATH + (id == null ? "" : "/" + id), null, null);
        } catch (URISyntaxException ex) { throw new IllegalArgumentException("Endereço do anexo inválido."); }
    }
}
