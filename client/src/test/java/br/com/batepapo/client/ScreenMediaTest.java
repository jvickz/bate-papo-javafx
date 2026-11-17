package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.Protocol;
import br.com.batepapo.protocol.VoiceConfig;
import dev.onvoid.webrtc.client.*;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class ScreenMediaTest {
    private static final VoiceEngine.Screen MONITOR = new VoiceEngine.Screen(11, "Monitor de teste");

    private static class MonitorCapture implements ScreenCapture {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicReference<CustomVideo> capture = new AtomicReference<>();
        @Override public List<VoiceEngine.Screen> displays(WebRtc rtc) { return List.of(MONITOR); }
        @Override public LocalVideo open(WebRtc rtc, VoiceEngine.Screen selected) {
            assertEquals(MONITOR, selected);
            opened.incrementAndGet();
            var track = rtc.media().customVideo("screen-test");
            capture.set(track);
            return track;
        }
    }

    @Test @Timeout(55)
    void sendsOnlySelectedScreenAndReceivesDecodedColorEvenAfterRestartingTheShare() throws Exception {
        var source = new MonitorCapture();
        var reverseSource = new MonitorCapture();
        try (var sender = engine(source); var receiver = engine(reverseSource)) {
            var sendStates = new LinkedBlockingQueue<String>();
            var receiveStates = new LinkedBlockingQueue<String>();
            var sentSignals = new LinkedBlockingQueue<String>();
            var colors = new LinkedBlockingQueue<List<Integer>>();
            receiver.enableScreenSharing(true);
            sender.enableScreenSharing(true);
            receiver.start(new VoiceConfig(List.of(), false), signal -> {
                sentSignals.add(signal);
                sender.signal(signal);
            }, receiveStates::add);
            sender.start(new VoiceConfig(List.of(), true), signal -> {
                sentSignals.add(signal);
                receiver.signal(signal);
            }, sendStates::add);
            receiver.onScreenFrame(frame -> colors.offer(readColor(frame)));
            var reverseColors = new LinkedBlockingQueue<List<Integer>>();
            sender.onScreenFrame(frame -> reverseColors.offer(readColor(frame)));
            awaitState(sendStates, "connected");
            awaitState(receiveStates, "connected");
            assertEquals(List.of(MONITOR), sender.screens().get(5, SECONDS));
            assertEquals(0, source.opened.get());
            assertTrue(sentSignals.stream().map(ScreenSignal::unpack).noneMatch(s -> s.contains("m=video")));
            sender.startScreen(new VoiceEngine.Screen(77, "indisponível"));
            awaitState(sendStates, "screenfailed");
            assertEquals(0, source.opened.get());
            for (int round = 1; round <= 2; round++) {
                colors.clear();
                sender.startScreen(MONITOR);
                awaitState(sendStates, "screenstarted");
                awaitState(receiveStates, "remotescreenstarted");
                var track = source.capture.get();
                assertEquals(round, source.opened.get());
                assertReceivesRed(track, colors);
                if (round == 1) {
                    receiver.startScreen(MONITOR);
                    awaitState(receiveStates, "screenstarted");
                    awaitState(sendStates, "remotescreenstarted");
                    assertReceivesRed(reverseSource.capture.get(), reverseColors);
                    assertReceivesRed(track, colors);
                    receiver.stopScreen();
                    awaitState(receiveStates, "screenstopped");
                    awaitState(sendStates, "remotescreenstopped");
                    assertTrue(reverseSource.capture.get().isEnded());
                }
                sender.stopScreen();
                awaitState(sendStates, "screenstopped");
                awaitState(receiveStates, "remotescreenstopped");
                assertTrue(track.isEnded(), "Stopping screen sharing releases capture without ending the voice call");
            }
            int maxDescription = sentSignals.stream().mapToInt(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).max().orElseThrow();
            assertTrue(maxDescription <= 10 * 1024, "The largest signal must fit the chat server limit: " + maxDescription);
            String id = java.util.UUID.randomUUID().toString();
            int maxEnvelope = sentSignals.stream()
                    .map(s -> Protocol.encode(new Event("callsignal", id, "Contato", s, null, null, null, null, "geral")))
                    .mapToInt(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .max().orElseThrow();
            assertTrue(maxEnvelope <= Protocol.MAX_BYTES);
            System.out.printf("Screen signaling maxima: %d bytes, %d bytes with chat envelope.%n", maxDescription, maxEnvelope);
        }
    }

    private static List<Integer> readColor(VoiceEngine.Frame frame) {
        try (frame) {
            byte[] pixels = frame.bgra();
            int offset = ((frame.height() / 2) * frame.width() + frame.width() / 2) * 4;
            return List.of(pixels[offset] & 255, pixels[offset + 1] & 255,
                    pixels[offset + 2] & 255, pixels[offset + 3] & 255);
        }
    }

    private static void assertReceivesRed(CustomVideo track, BlockingQueue<List<Integer>> colors) throws Exception {
        var frame = redFrame(320, 180);
        try {
            List<Integer> bgra = null;
            for (int i = 0; i < 70 && bgra == null; i++) {
                track.push(frame);
                bgra = colors.poll(65, TimeUnit.MILLISECONDS);
            }
            assertNotNull(bgra, "Screen receiver must decode the RTP video stream");
            assertTrue(bgra.get(0) < 40 && bgra.get(1) < 40 && bgra.get(2) > 210 && bgra.get(3) == 255, bgra.toString());
        } finally { frame.release(); }
    }

    @Test @Timeout(45)
    void decodesHighResolutionScreenAndReleasesCaptureOnHangup() throws Exception {
        var source = new MonitorCapture();
        var sender = engine(source);
        try (sender; var receiver = engine(new MonitorCapture())) {
            var sendStates = new LinkedBlockingQueue<String>();
            var receiveStates = new LinkedBlockingQueue<String>();
            var sizes = new LinkedBlockingQueue<List<Integer>>();
            receiver.enableScreenSharing(true);
            sender.enableScreenSharing(true);
            receiver.onScreenFrame(frame -> {
                try (frame) { sizes.offer(List.of(frame.width(), frame.height())); }
            });
            receiver.start(new VoiceConfig(List.of(), false), sender::signal, receiveStates::add);
            sender.start(new VoiceConfig(List.of(), true), receiver::signal, sendStates::add);
            awaitState(sendStates, "connected");
            awaitState(receiveStates, "connected");
            sender.startScreen(MONITOR);
            awaitState(sendStates, "screenstarted");
            awaitState(receiveStates, "remotescreenstarted");
            var track = source.capture.get();
            var frame = redFrame(3840, 2160);
            int largestWidth = 0, largestHeight = 0;
            try {
                for (int i = 0; i < 110 && largestWidth < 3840; i++) {
                    track.push(frame);
                    var size = sizes.poll(75, TimeUnit.MILLISECONDS);
                    if (size != null && size.get(0) > largestWidth) {
                        largestWidth = size.get(0);
                        largestHeight = size.get(1);
                    }
                }
            } finally { frame.release(); }
            System.out.printf("3840x2160 synthetic screen: largest decoded frame was %dx%d.%n", largestWidth, largestHeight);
            assertTrue(largestWidth >= 1920 && largestHeight >= 1080);
            sender.close();
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (!track.isEnded() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(track.isEnded());
        }
    }

    @Test void rejectsExcessiveDescriptionsDuringPackingAndUnpacking() throws Exception {
        String text = "m=video meaningful description ".repeat(1024);
        assertEquals(text, ScreenSignal.unpack(ScreenSignal.pack(text)));
        byte[] input = new byte[64 * 1024 + 1];
        assertThrows(IllegalArgumentException.class, () -> ScreenSignal.pack("a".repeat(input.length)));
        var compressed = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.DeflaterOutputStream(compressed)) { z.write(input); }
        String data = java.util.Base64.getEncoder().encodeToString(compressed.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> ScreenSignal.unpack("{\"screenSdp\":\"" + data + "\"}"));
    }

    private static NativeVoiceEngine engine(ScreenCapture capture) {
        return new NativeVoiceEngine(() -> WebRtc.builder().audio(AudioMode.HEADLESS).build(),
                rtc -> rtc.media().customAudio("audio-test"), capture);
    }

    private static void awaitState(BlockingQueue<String> states, String expected) throws InterruptedException {
        long end = System.nanoTime() + SECONDS.toNanos(8);
        java.util.ArrayList<String> seen = new java.util.ArrayList<>();
        while (System.nanoTime() < end) {
            String state = states.poll(100, TimeUnit.MILLISECONDS);
            if (expected.equals(state)) return;
            if (state != null) seen.add(state);
        }
        fail("Missing state " + expected + "; observed " + seen);
    }

    private static VideoFrame redFrame(int width, int height) {
        var data = NativeI420Buffer.allocate(width, height);
        fill(data.getDataY(), 81);
        fill(data.getDataU(), 90);
        fill(data.getDataV(), 240);
        return new VideoFrame(data, 0);
    }

    private static void fill(ByteBuffer plane, int value) {
        while (plane.hasRemaining()) plane.put((byte)value);
    }
}
