package br.com.batepapo.client;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Pixel-art headland at twilight (login) and first light (registration). */
final class HarborBackdrop {
    private record Palette(Color sky, Color horizon, Color sea, Color foam, Color sun, Color ridge) {}
    private static final Palette DUSK = new Palette(Color.web("#23334d"), Color.web("#b795a4"),
            Color.web("#304f63"), Color.web("#94b9b4"), Color.web("#f2ce9b"), Color.web("#586278"));
    private static final Palette DAWN = new Palette(Color.web("#608d9b"), Color.web("#ffd09e"),
            Color.web("#397179"), Color.web("#c1d9be"), Color.web("#fff0bf"), Color.web("#899993"));
    private static final Color ROCK = Color.web("#354657"), SHADE = Color.web("#223442");
    private static final Color GRASS = Color.web("#657d70"), GRASS_LIGHT = Color.web("#9baf87");
    private static final Color BRICK = Color.web("#b65e50"), ROOF = Color.web("#78434a");
    private static final Color WALL = Color.web("#e6d4ba"), WALL_SHADE = Color.web("#aeb0a5");
    private static final Color IRON = Color.web("#344453"), LIGHT = Color.web("#fff3bc");

    void draw(GraphicsContext g, double width, double height, double time, boolean dawn) {
        Palette p = dawn ? DAWN : DUSK;
        double pixel = Math.max(2, Math.round(Math.min(width / 270, height / 170)));
        int w = (int) Math.ceil(width / pixel), h = (int) Math.ceil(height / pixel);
        int horizon = (int) Math.round(h * .55);
        g.save();
        g.scale(pixel, pixel);
        for (int y = 0; y < horizon; y += 3) {
            rect(g, p.sky.interpolate(p.horizon, (double) y / horizon), 0, y, w, 3);
        }
        int sunX = (int) (w * .66);
        int sunY = horizon - (dawn ? 9 : 22);
        for (int dy = -10; dy <= 10; dy++) {
            int radius = (int) Math.sqrt(100 - dy * dy);
            rect(g, p.sun, sunX - radius, sunY + dy, radius * 2 + 1, 1);
        }
        for (int i = 0; i < 7; i++) {
            int x = (int) Math.floorMod((long) (i * 59 + time * .5), w + 36) - 30;
            int y = 14 + (i * 17) % Math.max(16, horizon - 28);
            rect(g, p.horizon.interpolate(p.sky, .42), x, y, 20 + i, 1);
            rect(g, p.horizon.interpolate(p.sky, .32), x + 6, y - 1, 9 + i, 1);
        }
        for (int x = 0; x < w; x += 2) {
            int ridge = (int) (5 + 3 * Math.sin(x * .071) + 2 * Math.cos(x * .18));
            rect(g, p.ridge, x, horizon - ridge, 2, ridge + 1);
        }
        for (int y = horizon; y < h; y += 3) {
            double depth = (double) (y - horizon) / (h - horizon);
            rect(g, p.sea.interpolate(SHADE, depth * .85), 0, y, w, 3);
        }
        for (int i = 0; i < 82; i++) {
            int y = horizon + 2 + i * 23 % Math.max(1, h - horizon - 2);
            int x = (int) (i * 67L % w + 2 * Math.sin(time + i));
            rect(g, p.sea.interpolate(p.foam, .20 + i % 4 * .08), x, y, 2 + i % 7, 1);
        }
        for (int i = 0; i < 13; i++) {
            int x = sunX + (int) (Math.sin(i * 2.5 + time * .55) * (3 + i));
            rect(g, p.sea.interpolate(p.sun, .58 - i * .025), x - 3, horizon + i * 2 + 1, 4 + i % 7, 1);
        }
        int towerX = (int) Math.round(w * .83), base = horizon + 4;
        int shore = towerX - 13;
        for (int x = shore - 15; x < w; x += 2) {
            int dx = x - shore;
            int top = base + (dx < 0 ? -dx / 2 + 2 : (int) (2 + 1.5 * Math.sin(dx * .12)));
            int bottom = base + 10 + Math.min(8, (dx + 15) / 5) + (int) (1.7 * Math.sin(dx * .6));
            rect(g, SHADE, x, top, 2, Math.max(1, bottom - top));
            rect(g, ROCK, x, top, 2, Math.max(1, (bottom - top) / 2));
            rect(g, x % 8 < 4 ? GRASS : GRASS_LIGHT, x, top, 2, 1);
        }
        lighthouse(g, towerX, base, time);
        for (int i = 0; i < 3; i++) {
            int x = (int) (w * .64) + i * 7 + (int) (4 * Math.sin(time * .23));
            int y = horizon - 38 + (i % 2) * 3;
            rect(g, ROCK, x, y, 2, 1);
            rect(g, ROCK, x + 2, y + 1, 1, 1);
            rect(g, ROCK, x + 3, y, 2, 1);
        }
        g.restore();
    }

    private void lighthouse(GraphicsContext g, int x, int base, double time) {
        int lantern = base - 39;
        double sweep = Math.sin(time * .65);
        g.setGlobalAlpha(.12 + .10 * Math.abs(sweep));
        g.setFill(LIGHT);
        double reach = 52 * sweep;
        g.fillPolygon(new double[]{x, x + reach, x + reach}, new double[]{lantern, lantern - 4, lantern + 3}, 3);
        g.setGlobalAlpha(1);
        for (int row = 0; row < 34; row++) {
            int half = 3 + row / 12;
            boolean stripe = row >= 8 && row < 14 || row >= 23 && row < 29;
            rect(g, stripe ? BRICK : WALL, x - half, base - 34 + row, half * 2 + 1, 1);
            rect(g, stripe ? ROOF : WALL_SHADE, x + half - 1, base - 34 + row, 2, 1);
        }
        rect(g, IRON, x - 5, base - 35, 11, 2);
        rect(g, IRON, x - 3, lantern - 2, 7, 6);
        rect(g, LIGHT, x - 2, lantern - 1, 2, 4);
        rect(g, LIGHT, x + 1, lantern - 1, 2, 4);
        rect(g, ROOF, x - 5, lantern - 3, 11, 1);
        rect(g, BRICK, x - 3, lantern - 5, 7, 2);
        rect(g, ROOF, x - 1, lantern - 6, 3, 1);
        rect(g, IRON, x, lantern - 8, 1, 2);
        rect(g, IRON, x - 1, base - 4, 3, 4);
        rect(g, IRON, x, base - 17, 2, 3);
        rect(g, WALL_SHADE, x + 10, base - 5, 10, 6);
        rect(g, ROOF, x + 8, base - 6, 14, 2);
        rect(g, BRICK, x + 10, base - 8, 10, 2);
        rect(g, IRON, x + 15, base - 3, 2, 4);
    }

    private static void rect(GraphicsContext g, Color color, int x, int y, int w, int h) {
        g.setFill(color);
        g.fillRect(x, y, w, h);
    }
}
