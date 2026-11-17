package br.com.batepapo.client;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Decorative, bounded-cost CRT field. All calls belong to the JavaFX thread. */
final class PixelBackdrop extends Region implements AutoCloseable {
    private final Canvas canvas = new Canvas();
    private boolean active, paused, running, closed;
    private long lastFrame, frames;
    private double phase, signal;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (lastFrame != 0 && now - lastFrame < 50_000_000L) return;
            double elapsed = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
            lastFrame = now; phase += elapsed; signal = Math.max(0, signal - elapsed);
            frames++; draw();
        }
    };

    PixelBackdrop() {
        setId("pixelBackdrop"); setMouseTransparent(true); setFocusTraversable(false);
        getChildren().add(canvas);
    }

    void setActive(boolean value) { active = value; updateTimer(); }
    void setPaused(boolean value) { paused = value; updateTimer(); }
    void pulse() { if (active && !paused && !closed) signal = 1.4; }
    boolean isRunning() { return running; }
    long frameCount() { return frames; }

    private void updateTimer() {
        boolean next = active && !paused && !closed;
        if (next == running) return;
        running = next; lastFrame = 0;
        if (running) timer.start(); else { timer.stop(); signal = 0; }
    }

    @Override protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth()); canvas.setHeight(getHeight()); draw();
        }
    }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        var g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#060d0b")); g.fillRect(0, 0, w, h);
        g.setFill(Color.rgb(67, 148, 105, 0.055));
        for (int x = 0; x < w; x += 24) g.fillRect(x, 0, 1, h);
        for (int y = 0; y < h; y += 24) g.fillRect(0, y, w, 1);
        // A fixed number of packets keeps CPU cost independent of message count.
        for (int i = 0; i < 64; i++) {
            double x = Math.floor(((i * 0.61803398875) % 1) * w / 6) * 6;
            double y = Math.floor(((i * 79.0 + phase * (8 + i % 5 * 3)) % (h + 48) - 24) / 6) * 6;
            double glow = 0.08 + (i % 5) * 0.025 + (i % 7 == 0 ? signal * 0.08 : 0);
            g.setFill(Color.rgb(109, 238, 162, glow));
            g.fillRect(x, y, 6, 6);
            if (i % 3 == 0) { g.fillRect(x + 9, y, 3, 3); g.fillRect(x, y - 9, 3, 3); }
        }
        // Static scanlines: no strobing or full-screen brightness flashes.
        g.setFill(Color.rgb(0, 0, 0, 0.16));
        for (int y = 0; y < h; y += 4) g.fillRect(0, y, w, 1);
    }

    @Override public void close() { closed = true; updateTimer(); }
}
