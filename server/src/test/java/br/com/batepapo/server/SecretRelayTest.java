package br.com.batepapo.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SecretRelayTest {
    private static final String ROOM = "0123456789abcdef0123456789abcdef";
    private final ChatServer server = new ChatServer("integration-test-access-code-123456", 0).start();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<WebSocket> sockets = new ArrayList<>();

    @AfterEach void stop() {
        sockets.forEach(WebSocket::abort);
        server.close();
        client.shutdownNow();
    }

    @Test void onlyTwoSocketsCanExchangeFramesAndLeavingExpiresTheInvite() throws Exception {
        assertEquals(1008, connect(ROOM, "guest").closeCode());
        assertEquals(1008, connect("short", "host").closeCode());
        var host = connect(ROOM, "host");
        assertEquals("WAIT", host.next());
        assertEquals(1008, connect(ROOM, "host").closeCode());
        var guest = connect(ROOM, "guest");
        assertEquals("READY", guest.next());
        assertEquals("READY", host.next());
        assertEquals(1008, connect(ROOM, "guest").closeCode());

        String first = "DATA." + "a".repeat(48);
        String second = "DATA." + "b".repeat(40);
        guest.socket.sendText(first, true).join();
        assertEquals(first, host.next());
        host.socket.sendText(second, true).join();
        assertEquals(second, guest.next());
        guest.socket.sendClose(1000, "").join();
        assertEquals(1008, host.closeCode());
        assertEquals(1008, connect(ROOM, "guest").closeCode());
    }

    @Test void aParticipantCannotImpersonateRelayControlFrames() throws Exception {
        var host = connect(ROOM, "host");
        assertEquals("WAIT", host.next());
        var guest = connect(ROOM, "guest");
        assertEquals("READY", host.next());
        assertEquals("READY", guest.next());
        guest.socket.sendText("READY", true).join();
        assertEquals(1008, host.closeCode());
        assertTrue(host.frames.isEmpty());
    }

    @Test void boundsBurstSizeAndRefillsAtTheSustainedByteRate() {
        var small = new SecretRelay.FrameBudget(0);
        for (int i = 0; i < 32; i++) assertTrue(small.admit(32, 0));
        assertFalse(small.admit(32, 0));
        assertFalse(small.admit(32, 62_499_999));
        assertTrue(small.admit(32, 62_500_000));

        var large = new SecretRelay.FrameBudget(0);
        for (int i = 0; i < 4; i++) assertTrue(large.admit(16384, 0));
        assertFalse(large.admit(16384, 0));
        assertTrue(large.admit(16384, 500_000_000));
        assertFalse(large.admit(32, 500_000_000));
    }

    private Peer connect(String room, String slot) {
        var peer = new Peer();
        URI endpoint = URI.create("ws://localhost:" + server.port() + "/secret/" + room + "/" + slot);
        peer.socket = client.newWebSocketBuilder().buildAsync(endpoint, peer).join();
        sockets.add(peer.socket);
        return peer;
    }

    private static final class Peer implements WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket socket;
        String next() throws InterruptedException { return frames.poll(5, TimeUnit.SECONDS); }
        int closeCode() throws Exception { return closed.get(5, TimeUnit.SECONDS); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(code);
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) { closed.completeExceptionally(error); }
    }
}
