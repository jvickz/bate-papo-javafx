package br.com.batepapo.server;

import br.com.batepapo.protocol.VoiceConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Shares the server package to inject fake TURN credentials through the test seam. */
public final class VoiceTestServer {
    private VoiceTestServer() {}

    public static ChatServer start(String token) {
        return new ChatServer(token, 0, () -> CompletableFuture.completedFuture(List.of(
                new VoiceConfig.IceServer(List.of("turn:example.test"), "temporary-user", "temporary-password")))).start();
    }
}
