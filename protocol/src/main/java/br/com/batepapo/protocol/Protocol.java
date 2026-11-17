package br.com.batepapo.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class Protocol {
    public static final int MAX_BYTES = 16384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(12000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Protocol() {}
    public static String encode(Event event) {
        try { return JSON.writeValueAsString(event); }
        catch (Exception e) { throw new IllegalArgumentException("Não foi possível preparar a mensagem."); }
    }
    public static Event decode(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Mensagem muito grande.");
        try {
            var e = JSON.readValue(json, Event.class);
            if (e == null || e.type() == null || !e.type().matches("[a-z]{1,24}")) throw new IllegalArgumentException();
            return e;
        } catch (Exception e) { throw new IllegalArgumentException("Mensagem inválida."); }
    }
    public static String validateName(String name) {
        String clean = name == null ? "" : name.strip();
        int length = clean.codePointCount(0, clean.length());
        if (length < 2 || length > 24 || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("Use um apelido de 2 a 24 caracteres, sem quebras de linha.");
        return clean;
    }
    public static String validateText(String text) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 2000 ||
                text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Escreva uma mensagem de 1 a 2000 caracteres.");
        return text;
    }
    public static URI serverUri(String value) {
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            scheme = switch (scheme) { case "https" -> "wss"; case "http" -> "ws"; default -> scheme; };
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    uri.getPort() == 0 || uri.getPort() > 65535 ||
                    !(scheme.equals("wss") || (scheme.equals("ws") && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host.toLowerCase(Locale.ROOT)))))
                throw new IllegalArgumentException();
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) path = "/chat";
            return new URI(scheme, null, host, uri.getPort(), path, null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Use o endereço HTTPS/WSS do servidor. WS é permitido apenas em localhost.");
        }
    }
}
