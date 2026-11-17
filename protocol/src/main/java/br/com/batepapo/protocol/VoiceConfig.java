package br.com.batepapo.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record VoiceConfig(List<IceServer> iceServers, boolean caller) {
    public static final String SCREEN_CAPABILITY = "screen-v1";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(2048).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IceServer(List<String> urls, String username, String credential) {
        public IceServer { urls = List.copyOf(urls); }
        @Override public String toString() { return "IceServer[redacted]"; }
    }

    public VoiceConfig { iceServers = List.copyOf(iceServers); }

    public String encode() {
        try { return JSON.writeValueAsString(this); }
        catch (Exception ignored) { throw new IllegalArgumentException("Não foi possível preparar a configuração de voz."); }
    }

    public static VoiceConfig decode(String json) {
        if (json != null && json.length() <= Protocol.MAX_BYTES
                && json.getBytes(StandardCharsets.UTF_8).length <= Protocol.MAX_BYTES) {
            try {
                var config = JSON.readValue(json, VoiceConfig.class);
                if (config != null) return config;
            } catch (Exception ignored) {
                // The response may contain credentials. Do not attach the parser exception.
            }
        }
        throw new IllegalArgumentException("Configuração de voz inválida.");
    }

    @Override public String toString() { return "VoiceConfig[caller=" + caller + ", iceServers=redacted]"; }
}
