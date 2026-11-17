package br.com.batepapo.protocol;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void unicodeRoundTrip() {
        var event = new Event("message", "123", "João", "Olá 👋\nTudo bem?", null, null, List.of("Ana"), "2026-11-17T12:00:00Z");
        assertEquals(event, Protocol.decode(Protocol.encode(event)));
    }
    @Test void channelIsOptionalOnTheWire() {
        String frame = "{\"type\":\"presence\",\"users\":[\"Ana\"],\"channel\":\"tech\"}";
        assertEquals(frame, Protocol.encode(Protocol.decode(frame)));
        var legacy = new Event("presence", null, null, null, null, null, List.of("Ana"), null);
        assertEquals("{\"type\":\"presence\",\"users\":[\"Ana\"]}", Protocol.encode(legacy));
    }
    @Test void validatesNamesAndText() {
        assertEquals("João", Protocol.validateName(" João "));
        assertEquals("Olá\n👋", Protocol.validateText("Olá\n👋"));
        for (String name : List.of("", "a", "x".repeat(25), "Ana\nMaria", "A\u202Ena"))
            assertThrows(IllegalArgumentException.class, () -> Protocol.validateName(name));
        for (String text : List.of(" ", "x".repeat(2001), "a\u0000b"))
            assertThrows(IllegalArgumentException.class, () -> Protocol.validateText(text));
        assertDoesNotThrow(() -> Protocol.validateText("👋".repeat(2000)));
    }
    @Test void requiresSecureRemoteUrls() {
        assertEquals("wss://example.com/chat", Protocol.serverUri("https://example.com").toString());
        assertEquals("ws://127.0.0.1:8080/chat", Protocol.serverUri("ws://127.0.0.1:8080").toString());
        assertDoesNotThrow(() -> Protocol.serverUri("ws://[::1]:8080/chat"));
        for (String url : List.of("ws://example.com/chat", "http://192.168.1.2", "wss://name:secret@example.com", "wss://example.com?token=secret", "ftp://example.com", "wss://example.com/#x"))
            assertThrows(IllegalArgumentException.class, () -> Protocol.serverUri(url));
    }
    @Test void rejectsMalformedOversizeAndMissingType() {
        for (String json : List.of("{", "null", "{}", "[]", "{\"type\":\"message\",\"text\":\"" + "x".repeat(17000) + "\"}", "{\"type\":\"x\"} {}"))
            assertThrows(IllegalArgumentException.class, () -> Protocol.decode(json));
    }
}
