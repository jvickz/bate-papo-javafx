package br.com.batepapo.client;

import br.com.batepapo.protocol.VoiceConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface VoiceEngine extends AutoCloseable {
    record Screen(long id, String name) {
        @Override public String toString() { return name; }
    }

    /** A tightly packed BGRA image. The recipient must close it after copying or discarding it. */
    final class Frame implements AutoCloseable {
        private final int width;
        private final int height;
        private final byte[] bgra;
        private Runnable recycle;

        public Frame(int width, int height, byte[] bgra) { this(width, height, bgra, null); }

        Frame(int width, int height, byte[] bgra, Runnable recycle) {
            this.width = width;
            this.height = height;
            this.bgra = bgra;
            this.recycle = recycle;
        }

        public int width() { return width; }
        public int height() { return height; }
        public byte[] bgra() { return bgra; }

        @Override public synchronized void close() {
            if (recycle != null) {
                recycle.run();
                recycle = null;
            }
        }
    }

    /** Negotiate video only for peers that both advertised this capability. Call before start. */
    default void enableScreenSharing(boolean supported) { }

    /** Starts an accepted call. Callbacks run asynchronously and should return promptly. */
    void start(VoiceConfig config, Consumer<String> signals, Consumer<String> states);

    void signal(String json);

    void setMuted(boolean muted);

    default CompletableFuture<List<Screen>> screens() { return CompletableFuture.completedFuture(List.of()); }

    default void startScreen(Screen selected) { }
    default void stopScreen() { }

    /** Set null when no viewer is open. An active callback can still finish after detaching. */
    default void onScreenFrame(Consumer<Frame> viewer) { }

    /** Invalidates pending callbacks and requests asynchronous media cleanup. */
    @Override
    void close();
}
