package br.com.batepapo.server;

import br.com.batepapo.protocol.Protocol;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** In-memory rendezvous for a single pair. The shared invite secret stays on the clients. */
final class SecretRelay {
    private static final Pattern ROOM_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern FRAME = Pattern.compile("DATA\\.[A-Za-z0-9_-]{22,}+");
    private static final int MAX_ROOMS = 32;
    private static final long WAIT_TIMEOUT = Duration.ofMinutes(10).toNanos();
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private final Map<String, Pair> rooms = new HashMap<>();
    private boolean stopping;

    // Charge tiny frames a minimum size to bound both bytes and dispatches per participant.
    static final class FrameBudget {
        private static final int BURST_BYTES = 64 * 1024;
        private static final int BYTES_PER_SECOND = 32 * 1024;
        private static final int MIN_CHARGE = 2048;
        private double credit = BURST_BYTES;
        private long updated;

        FrameBudget(long now) { updated = now; }

        boolean admit(int frameBytes, long now) {
            credit = Math.min(BURST_BYTES, credit + (now - updated) * (BYTES_PER_SECOND / 1_000_000_000.0));
            updated = now;
            int cost = Math.max(MIN_CHARGE, frameBytes);
            if (cost > credit) return false;
            credit -= cost;
            return true;
        }
    }

    private record Member(String sessionId, SocketPeer socket, FrameBudget budget) {
        Member(WsContext ctx) { this(ctx.sessionId, new SocketPeer(ctx.session), new FrameBudget(System.nanoTime())); }
    }

    private static final class Pair {
        final Member host;
        Member guest;
        long lastActivity = System.nanoTime();

        Pair(Member host) { this.host = host; }

        Member find(String sessionId) {
            if (host.sessionId.equals(sessionId)) return host;
            return guest != null && guest.sessionId.equals(sessionId) ? guest : null;
        }
    }

    void configure(WsConfig ws) {
        ws.onConnect(this::open);
        ws.onMessage(ctx -> receive(ctx, ctx.message()));
        ws.onBinaryMessage(ctx -> ctx.closeSession(1003, "Formato inválido."));
        ws.onClose(this::depart);
        ws.onError(this::depart);
    }

    private synchronized void open(WsContext ctx) {
        String id = ctx.pathParam("room");
        String slot = ctx.pathParam("slot");
        var pair = rooms.get(id);
        if (!stopping && ROOM_ID.matcher(id).matches()) {
            ctx.session.setIdleTimeout(IDLE_TIMEOUT);
            if ("host".equals(slot) && pair == null && rooms.size() < MAX_ROOMS) {
                var host = new Member(ctx);
                rooms.put(id, new Pair(host));
                host.socket.send("WAIT");
                return;
            }
            if ("guest".equals(slot) && pair != null && pair.guest == null) {
                pair.guest = new Member(ctx);
                pair.lastActivity = System.nanoTime();
                pair.host.socket.send("READY");
                pair.guest.socket.send("READY");
                return;
            }
        }
        ctx.closeSession(1008, "Sala indisponível.");
    }

    private synchronized void receive(WsContext ctx, String frame) {
        String id = ctx.pathParam("room");
        var pair = rooms.get(id);
        var sender = pair == null ? null : pair.find(ctx.sessionId);
        if (sender == null) return;
        long now = System.nanoTime();
        if (pair.guest == null || frame.length() > Protocol.MAX_BYTES ||
                !sender.budget.admit(frame.length(), now) || !FRAME.matcher(frame).matches()) {
            finish(id);
            return;
        }
        pair.lastActivity = now;
        var recipient = sender == pair.host ? pair.guest : pair.host;
        recipient.socket.send(frame);
    }

    private synchronized void depart(WsContext ctx) {
        String id = ctx.pathParam("room");
        var pair = rooms.get(id);
        // A late close from an old socket must not tear down a new pair.
        if (pair != null && pair.find(ctx.sessionId) != null) finish(id);
    }

    synchronized void expire() {
        long now = System.nanoTime();
        for (var id : List.copyOf(rooms.keySet())) {
            var pair = rooms.get(id);
            long timeout = pair.guest == null ? WAIT_TIMEOUT : IDLE_TIMEOUT.toNanos();
            if (now - pair.lastActivity >= timeout) finish(id);
        }
    }

    synchronized void shutdown() {
        stopping = true;
        for (var id : List.copyOf(rooms.keySet())) finish(id);
    }

    private void finish(String id) {
        var pair = rooms.remove(id);
        pair.host.socket.close();
        if (pair.guest != null) pair.guest.socket.close();
    }
}
