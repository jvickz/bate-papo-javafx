package br.com.batepapo.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoiceConfigTest {
    @Test void readsCloudflareIceServersAndPreservesRoleOnWire() {
        var config = VoiceConfig.decode("""
                {"iceServers":[{"urls":["stun:stun.example.net"]},
                               {"urls":["turns:turn.example.net:443"],"username":"temporary-user","credential":"temporary-password"}],
                 "caller":true}
                """);
        assertTrue(config.caller());
        assertEquals(List.of("stun:stun.example.net"), config.iceServers().getFirst().urls());
        assertNull(config.iceServers().getFirst().username());
        assertEquals("temporary-user", config.iceServers().getLast().username());
        assertEquals("temporary-password", config.iceServers().getLast().credential());
        assertEquals(config, VoiceConfig.decode(config.encode()));
        assertFalse(config.toString().contains("temporary-user"));
        assertFalse(config.iceServers().toString().contains("temporary-password"));
    }

    @Test void errorsNeverEchoReceivedCredentials() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> VoiceConfig.decode("""
                        {"iceServers":[],"caller":false} trailing-secret
                        """));
        assertFalse(e.getMessage().contains("trailing-secret"));
        assertNull(e.getCause());
    }
}
