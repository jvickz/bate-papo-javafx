package br.com.batepapo.client;

import br.com.batepapo.protocol.VoiceConfig;
import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.PcmFormat;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.PeerState;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.WebRtc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeVoiceEngineTest {
    @Test
    @Timeout(25)
    void sendsSyntheticAudioThroughNativePeerAndReleasesTrackOnClose() throws Exception {
        var runtime = new CompletableFuture<WebRtc>();
        var track = new CompletableFuture<CustomAudio>();
        var trackEnded = new CountDownLatch(1);
        try (var remote = headless();
             var engine = new NativeVoiceEngine(NativeVoiceEngineTest::headless, rtc -> {
                 runtime.complete(rtc);
                 var audio = rtc.media().customAudio("test-tone");
                 audio.onEnded(trackEnded::countDown);
                 track.complete(audio);
                 return audio;
             })) {
            var peer = remote.createPeer(PeerOptions.builder().polite(true)
                    .signaling(message -> engine.signal(message.toJson())).build());
            var decodedTone = new CountDownLatch(1);
            peer.onTrack(remoteTrack -> remoteTrack.asAudio().onAudio(chunk -> {
                ByteBuffer samples = ByteBuffer.wrap(chunk.samples()).order(ByteOrder.LITTLE_ENDIAN);
                while (samples.remaining() >= Short.BYTES) {
                    if (Math.abs((int) samples.getShort()) > 500) {
                        decodedTone.countDown();
                        return;
                    }
                }
            }));

            var state = new CompletableFuture<String>();
            engine.start(new VoiceConfig(List.of(), true),
                    json -> peer.signal(SignalMessage.fromJson(json)),
                    value -> { if (!"connecting".equals(value)) state.complete(value); });

            assertEquals("connected", state.get(10, TimeUnit.SECONDS));
            peer.whenConnected().get(5, TimeUnit.SECONDS);
            var localPeer = runtime.get(1, TimeUnit.SECONDS).peers().getFirst();
            var audio = track.get(1, TimeUnit.SECONDS);
            audio.push(tone(), PcmFormat.of(48000, 1));
            assertTrue(decodedTone.await(4, TimeUnit.SECONDS), "The peer must receive non-silent PCM");
            assertTrue(peer.stats().get(2, TimeUnit.SECONDS).inboundAudio().stream()
                    .anyMatch(stream -> stream.packetsReceived() != null && stream.packetsReceived() > 0),
                    "The peer must receive audio RTP packets");

            engine.setMuted(true);
            awaitEnabled(audio, false);
            engine.setMuted(false);
            awaitEnabled(audio, true);

            engine.close();
            engine.close();
            localPeer.whenClosed().get(4, TimeUnit.SECONDS);
            assertEquals(PeerState.CLOSED, localPeer.state());
            assertTrue(trackEnded.await(4, TimeUnit.SECONDS), "Close must release the native sender track");
        }
    }

    @Test
    @Timeout(8)
    void closeDuringStartupSkipsMediaAndAllowsAnotherSession() throws Exception {
        var attempted = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var engine = new NativeVoiceEngine(() -> {
            attempted.set(true);
            throw new IllegalStateException("simulated runtime failure");
        }, rtc -> rtc.media().customAudio("unused"))) {
            engine.close();
            assertFalse(attempted.get(), "Creating and closing an unused engine must not initialize media");

            var config = new VoiceConfig(List.of(), false);
            var staleCallback = new AtomicBoolean();
            var nextState = new CompletableFuture<String>();
            engine.start(config, signal -> staleCallback.set(true), state -> {
                if ("connecting".equals(state)) {
                    entered.countDown();
                    try {
                        resume.await(4, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    staleCallback.set(true);
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                engine.close();
                engine.start(config, signal -> { }, state -> {
                    if (!"connecting".equals(state)) nextState.complete(state);
                    else assertFalse(attempted.get(), "The closed session must skip native startup");
                });
            } finally {
                resume.countDown();
            }
            assertEquals("failed", nextState.get(2, TimeUnit.SECONDS));
            assertTrue(attempted.get());
            assertFalse(staleCallback.get(), "A closed session must suppress obsolete callbacks");
        }
    }

    private static WebRtc headless() {
        return WebRtc.builder().audio(AudioMode.HEADLESS).build();
    }

    private static byte[] tone() {
        ByteBuffer data = ByteBuffer.allocate(48000 * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 48000; i++) {
            data.putShort((short) (12000 * Math.sin(2 * Math.PI * 440 * i / 48000)));
        }
        return data.array();
    }

    private static void awaitEnabled(CustomAudio audio, boolean enabled) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (audio.isEnabled() != enabled && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(enabled, audio.isEnabled());
    }
}
