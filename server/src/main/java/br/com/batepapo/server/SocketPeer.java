package br.com.batepapo.server;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import java.util.ArrayDeque;

/** Serializes asynchronous writes and bounds memory when a receiver is slow. */
final class SocketPeer implements Room.Transport {
    private final Session session;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private boolean writing, closing, failed;
    SocketPeer(Session session) { this.session = session; }
    @Override public synchronized void send(String json) {
        if (closing || failed) return;
        if (queue.size() >= 64) { fail(); return; }
        queue.addLast(json);
        drain();
    }
    private synchronized void drain() {
        if (writing || failed) return;
        if (queue.isEmpty()) {
            if (closing) session.close(1008, "Conexão encerrada.", Callback.NOOP);
            return;
        }
        writing = true;
        try {
            session.sendText(queue.removeFirst(), new Callback() {
                @Override public void succeed() { synchronized (SocketPeer.this) { writing = false; drain(); } }
                @Override public void fail(Throwable cause) { SocketPeer.this.fail(); }
            });
        } catch (Exception ignored) { fail(); }
    }
    private synchronized void fail() { failed = true; queue.clear(); session.disconnect(); }
    @Override public synchronized void close() { closing = true; drain(); }
}
