package br.com.batepapo.client;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.Random;

/** Decorative night seascape drawn on a fixed pixel grid. All calls belong to the JavaFX thread. */
final class PixelBackdrop extends Region implements AutoCloseable {
    private static final int PIXEL = 4;
    private static final long FRAME_INTERVAL = 50_000_000L;

    private static final Color[] SKY = {
            Color.web("#091125"), Color.web("#0b152a"), Color.web("#0e192f"),
            Color.web("#101e35"), Color.web("#13243c"), Color.web("#172b43"),
            Color.web("#1a324a"), Color.web("#1d3c53")
    };
    private static final Color[] SEA = {
            Color.web("#15394d"), Color.web("#123345"), Color.web("#102e41"),
            Color.web("#0e293c"), Color.web("#0b2538"), Color.web("#0b2132"),
            Color.web("#0a1d2f"), Color.web("#0a192a")
    };
    private static final Color[] WAVES = {
            Color.web("#194255"), Color.web("#1c4e60"), Color.web("#215969"),
            Color.web("#296572"), Color.web("#307581")
    };
    private static final Color[] REFLECTION = {
            Color.web("#245569"), Color.web("#32717c"), Color.web("#43838a")
    };
    private static final Color STAR = Color.web("#70849c");
    private static final Color STAR_LIGHT = Color.web("#c3d6d7");
    private static final Color HALO_OUTER = Color.rgb(78, 139, 151, 0.09);
    private static final Color HALO_INNER = Color.rgb(109, 182, 189, 0.12);
    private static final Color MOON_EDGE = Color.web("#9bbbbd");
    private static final Color MOON = Color.web("#e0e6d4");
    private static final Color MOON_CRATER = Color.web("#bdcfc9");
    private static final Color CLOUD = Color.web("#263b54");
    private static final Color CLOUD_SHADOW = Color.web("#20354e");
    private static final Color HORIZON = Color.web("#315b6c");
    private static final Color WAKE_SHADOW = Color.web("#103040");
    private static final Color WAKE = Color.web("#346f78");
    private static final Color LANTERN_REFLECTION = Color.web("#a78364");

    private static final Map<Character, Color> BOAT_PALETTE = Map.ofEntries(
            Map.entry('a', Color.web("#edb66c")),
            Map.entry('l', Color.web("#ffdda1")),
            Map.entry('m', Color.web("#ab8667")),
            Map.entry('n', Color.web("#695958")),
            Map.entry('t', Color.web("#f4ebce")),
            Map.entry('c', Color.web("#e8dec1")),
            Map.entry('s', Color.web("#d8d0b6")),
            Map.entry('i', Color.web("#b7bbae")),
            Map.entry('v', Color.web("#849f9e")),
            Map.entry('w', Color.web("#b4c5b6")),
            Map.entry('x', Color.web("#66888c")),
            Map.entry('z', Color.web("#9ab9b0")),
            Map.entry('o', Color.web("#ac7859")),
            Map.entry('d', Color.web("#764e45")),
            Map.entry('r', Color.web("#593d3e")),
            Map.entry('h', Color.web("#322d38"))
    );
    private static final String[] SAILBOAT = """
            .....................aaaa
            .....................aaaaaaaa
            .....................lllllll
            .....................llll
            .....................mn
            ....................tmn
            ....................tmn
            ...................tsmn
            ..................tssmn
            .................tss.mn
            ................tsss.mn.z
            ................tccs.mn.z
            ...............tccss.mn.vz
            ..............tccsss.mn.vvz
            .............tccssss.mn.vvvz
            ............tccsssss.mn.vvvvz
            ...........tccssssss.mn.wwwwvz
            ...........tccsiiiii.mn.wwwwvvz
            ..........tccsiiiiii.mn.wwwwvvvz
            .........tccsiiiiiii.mn.wwwwvvvvz
            ........tccsiiiiiiii.mn.wwwwvvvvvz
            .......tccsiiiiiiiii.mn.wwwwvvvvvvz
            ......tccsiiiiiiiiii.mn.wwwwxxxxxxxz
            ......tccsiiiiiiiiii.mn.wwwwxxxxxxxxz
            .....ttttttttttttttt.mn.wwwwxxxxxxxxxz
            ...................ooon.zzzzzzzzzzzzzzz
            .................ooooooooooooooo
            .m.......nnnnnnnnnndaadlllldaaddnnnnn.....mm
            .mmmmmmmmmm.........dddaaaadddd.......nnnnn
            oolllllllllllllllllllllllllllllllllllllllooo
            ...ddoooooooooooooooooooooooooooooooooodd
            ....ddrrrrrrrdddddddddrrrrrrrrrrrddddddd
            .....rddddddddddrrrrrrrrrrrrrrrrrrrrrr
            .......rrhhhhhhhhhhhhhhhhhhhhhhhhhrr
            ..........hhhhhhhhhhhhhhhhhhhhhhh
            .............hhhhhhhhhhhhhhhh
            """.lines().toArray(String[]::new);

    private record Ripple(double x, double depth, int length, int shade, double phase) {}

    private static final Ripple[] RIPPLES = createRipples();

    private final Canvas canvas = new Canvas();
    private boolean active;
    private boolean paused;
    private boolean running;
    private boolean closed;
    private long lastFrame;
    private long frames;
    private double phase;
    private double signal;
    private double pulseTime;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (lastFrame != 0 && now - lastFrame < FRAME_INTERVAL) return;
            double elapsed = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
            lastFrame = now;
            phase += elapsed;
            pulseTime = Math.max(0, pulseTime - elapsed);
            double signalTarget = pulseTime > 0 ? 1 : 0;
            signal += (signalTarget - signal) * elapsed * 3;
            frames++;
            draw();
        }
    };

    PixelBackdrop() {
        setId("pixelBackdrop");
        setMouseTransparent(true);
        setFocusTraversable(false);
        getChildren().add(canvas);
    }

    void setActive(boolean value) {
        active = value;
        updateTimer();
    }

    void setPaused(boolean value) {
        paused = value;
        updateTimer();
    }

    void pulse() {
        if (active && !paused && !closed) pulseTime = 0.7;
    }

    boolean isRunning() {
        return running;
    }

    long frameCount() {
        return frames;
    }

    private void updateTimer() {
        boolean next = active && !paused && !closed;
        if (next == running) return;
        running = next;
        lastFrame = 0;
        if (running) {
            timer.start();
        } else {
            timer.stop();
            signal = 0;
            pulseTime = 0;
        }
    }

    @Override
    protected void layoutChildren() {
        if (canvas.getWidth() != getWidth() || canvas.getHeight() != getHeight()) {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }
    }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;

        var g = canvas.getGraphicsContext2D();
        double horizon = snap(height * 0.43, PIXEL);
        double moonX = snap(width * 0.72, PIXEL);
        drawBands(g, SKY, width, 0, horizon);
        drawSky(g, width, horizon, moonX, snap(height * 0.235, PIXEL));
        drawBands(g, SEA, width, horizon, Math.ceil(height));
        drawWater(g, width, height, horizon, moonX);
        drawSailboat(g, width, height);
    }

    private static void drawBands(GraphicsContext g, Color[] colors, double width, double from, double to) {
        double top = from;
        for (int i = 0; i < colors.length; i++) {
            double fraction = (i + 1.0) / colors.length;
            double bottom = i == colors.length - 1 ? to : snap(from + (to - from) * fraction, PIXEL);
            block(g, colors[i], 0, top, width, bottom - top);
            top = bottom;
        }
    }

    private static void drawSky(GraphicsContext g, double width, double horizon, double moonX, double moonY) {
        for (int i = 0; i < 64; i++) {
            double x = snap(((i * 0.61803398875 + 0.13) % 1.0) * width, PIXEL);
            double y = snap(((i * 0.37157008664 + 0.19) % 1.0) * horizon * 0.92, PIXEL);
            if (i % 13 == 0) {
                block(g, STAR, x - PIXEL, y, PIXEL * 3, PIXEL);
                block(g, STAR, x, y - PIXEL, PIXEL, PIXEL * 3);
                block(g, STAR_LIGHT, x, y, PIXEL, PIXEL);
            } else {
                int size = i % 5 == 0 ? PIXEL : PIXEL / 2;
                block(g, STAR, x, y, size, size);
            }
        }
        disc(g, HALO_OUTER, moonX, moonY, 14);
        disc(g, HALO_INNER, moonX, moonY, 11);
        disc(g, MOON_EDGE, moonX, moonY, 8);
        disc(g, MOON, moonX - PIXEL, moonY - PIXEL, 7);
        block(g, MOON_CRATER, moonX - 20, moonY - 12, 12, 8);
        block(g, MOON_CRATER, moonX - 16, moonY - 16, 8, 4);
        block(g, MOON_CRATER, moonX + 8, moonY + 4, 12, 8);
        block(g, MOON_CRATER, moonX - 8, moonY + 12, 8, 4);

        drawCloud(g, snap(width * 0.31, PIXEL), horizon - 36, 32);
        drawCloud(g, snap(width * 0.58, PIXEL), horizon - 20, 25);
        drawCloud(g, snap(width * 0.84, PIXEL), horizon - 44, 23);
    }

    private static void disc(GraphicsContext g, Color color, double x, double y, int radius) {
        for (int row = -radius; row <= radius; row++) {
            int halfWidth = (int) Math.sqrt(radius * radius - row * row);
            block(g, color, x - halfWidth * PIXEL, y + row * PIXEL, (2 * halfWidth + 1) * PIXEL, PIXEL);
        }
    }

    private static void drawCloud(GraphicsContext g, double x, double y, int length) {
        block(g, CLOUD_SHADOW, x, y, length * PIXEL, PIXEL);
        block(g, CLOUD, x + 12, y - 4, (length - 8) * PIXEL, PIXEL);
        block(g, CLOUD, x + 28, y - 8, (length - 18) * PIXEL, PIXEL);
        block(g, CLOUD_SHADOW, x - 20, y + 4, (length - 9) * PIXEL, PIXEL);
        block(g, CLOUD_SHADOW, x + length * PIXEL + 8, y + 4, 20, PIXEL);
    }

    private void drawWater(GraphicsContext g, double width, double height, double horizon, double moonX) {
        block(g, HORIZON, 0, horizon, width, 2);

        for (Ripple ripple : RIPPLES) {
            double depth = ripple.depth();
            double x = snap(ripple.x() * width + Math.sin(phase * 0.60 + ripple.phase()) * (8 + depth * 18), PIXEL);
            double y = snap(horizon + PIXEL + depth * (height - horizon - PIXEL), PIXEL);
            double length = (ripple.length() + Math.floor(depth * 13)) * PIXEL;
            block(g, WAVES[ripple.shade()], x, y, length, depth > 0.4 ? PIXEL : PIXEL / 2);
            if (ripple.shade() >= 3) {
                block(g, WAVES[ripple.shade() - 1], x - 12, y + PIXEL, length * 0.5, PIXEL / 2);
            }
        }

        for (int i = 0; i < 23; i++) {
            double depth = i / 23.0;
            double y = snap(horizon + 8 + (height - horizon - 8) * depth, PIXEL);
            double length = (4 + (i * 11) % 9 + i / 3) * PIXEL;
            double offset = Math.sin(i * 2.18 + phase * 0.72) * (9 + depth * 42);
            double x = snap(moonX - length / 2 + offset, PIXEL);
            block(g, REFLECTION[i % REFLECTION.length], x, y, length, PIXEL);
            if (i % 3 == 0) block(g, REFLECTION[0], x + length + 12, y + PIXEL, (5 + i % 4) * PIXEL, PIXEL / 2);
        }
    }

    private void drawSailboat(GraphicsContext g, double width, double height) {
        int scale = Math.max(3, Math.min(7, (int) Math.round(Math.min(width / 216, height / 150))));
        double boatX = snap(width * 0.812 - scale * 22 + Math.sin(phase * 0.28) * 7, scale);
        double waterline = snap(height * 0.685, scale);
        double hullY = waterline + Math.round(Math.sin(phase * 0.9) * 2) * scale;
        double boatY = hullY - (SAILBOAT.length - 1) * scale;

        block(g, WAKE_SHADOW, boatX - 10 * scale, waterline + scale, 65 * scale, 2 * scale);
        block(g, WAKE, boatX - 7 * scale, waterline, 17 * scale, scale);
        block(g, WAKE, boatX + 40 * scale, waterline + scale, 11 * scale, scale);
        block(g, REFLECTION[0], boatX - 3 * scale, waterline + 3 * scale, 23 * scale, scale);
        block(g, REFLECTION[1], boatX + 26 * scale, waterline + 4 * scale, 21 * scale, scale);
        for (int row = 0; row < SAILBOAT.length; row++) {
            String pixels = SAILBOAT[row];
            for (int start = 0; start < pixels.length();) {
                char pixel = pixels.charAt(start);
                int end = start + 1;
                while (end < pixels.length() && pixels.charAt(end) == pixel) end++;
                Color color = BOAT_PALETTE.get(pixel);
                if (color != null) {
                    block(g, color, boatX + start * scale, boatY + row * scale, (end - start) * scale, scale);
                }
                start = end;
            }
        }

        g.setGlobalAlpha(0.52 + 0.17 * signal);
        block(g, LANTERN_REFLECTION, boatX + 18 * scale, waterline + 6 * scale, 11 * scale, scale);
        block(g, LANTERN_REFLECTION, boatX + 23 * scale, waterline + 8 * scale, 6 * scale, scale);
        block(g, LANTERN_REFLECTION, boatX + 25 * scale, waterline + 10 * scale, 3 * scale, scale);
        g.setGlobalAlpha(1);
    }

    private static Ripple[] createRipples() {
        var random = new Random(117);
        var ripples = new Ripple[128];
        for (int i = 0; i < ripples.length; i++) {
            double x = 1.06 * random.nextDouble() - 0.06;
            double depth = random.nextDouble();
            ripples[i] = new Ripple(x, depth * depth, 2 + random.nextInt(13), random.nextInt(WAVES.length), random.nextDouble() * Math.PI * 2);
        }
        return ripples;
    }

    private static double snap(double coordinate, int grid) {
        return Math.round(coordinate / grid) * grid;
    }

    private static void block(GraphicsContext g, Color color, double x, double y, double width, double height) {
        g.setFill(color);
        g.fillRect(x, y, width, height);
    }

    @Override
    public void close() {
        closed = true;
        updateTimer();
    }
}
