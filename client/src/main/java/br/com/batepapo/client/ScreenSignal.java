package br.com.batepapo.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Audio-only peers use the original JSON. Cap both the signaling envelope and its expanded form. */
final class ScreenSignal {
    static final String START = "{\"screen\":\"started\"}";
    static final String STOP = "{\"screen\":\"stopped\"}";
    private static final String PREFIX = "{\"screenSdp\":\"";
    private static final String SUFFIX = "\"}";
    private static final int MAX_EXPANDED = 64 * 1024;

    static String pack(String description) {
        if (description.length() < 4096) return description;
        byte[] input = description.getBytes(StandardCharsets.UTF_8);
        if (input.length > MAX_EXPANDED) throw new IllegalArgumentException("Descrição de mídia excessiva.");
        try {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new DeflaterOutputStream(bytes)) { zip.write(input); }
            String result = PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray()) + SUFFIX;
            if (result.length() > 10 * 1024) throw new IllegalArgumentException("Descrição de mídia excessiva.");
            return result;
        } catch (IOException e) { throw new IllegalStateException("Falha ao preparar a configuração de mídia."); }
    }

    static String unpack(String message) {
        if (!message.startsWith(PREFIX)) return message;
        if (!message.endsWith(SUFFIX) || message.length() > 10 * 1024) throw invalid();
        byte[] encoded = Base64.getDecoder().decode(message.substring(PREFIX.length(), message.length() - SUFFIX.length()));
        try (var in = new InflaterInputStream(new ByteArrayInputStream(encoded))) {
            byte[] expanded = in.readNBytes(MAX_EXPANDED + 1);
            if (expanded.length > MAX_EXPANDED) throw invalid();
            return new String(expanded, StandardCharsets.UTF_8);
        } catch (IOException e) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Descrição de mídia inválida."); }

    private ScreenSignal() { }
}
