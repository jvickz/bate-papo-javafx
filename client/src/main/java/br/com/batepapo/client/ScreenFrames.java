package br.com.batepapo.client;

/** One decoded frame crosses from the WebRTC decoder to the next JavaFX pulse. */
final class ScreenFrames implements AutoCloseable {
    private VoiceEngine.Frame next;
    private boolean closed;

    synchronized void offer(VoiceEngine.Frame frame) {
        if (closed) { frame.close(); return; }
        if (next != null) next.close();
        next = frame;
    }

    synchronized VoiceEngine.Frame take() {
        var result = next;
        next = null;
        return result;
    }

    @Override public synchronized void close() {
        closed = true;
        if (next != null) next.close();
        next = null;
    }
}
