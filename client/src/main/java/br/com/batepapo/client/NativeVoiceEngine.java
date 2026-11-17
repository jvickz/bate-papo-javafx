package br.com.batepapo.client;

import br.com.batepapo.protocol.VoiceConfig;
import dev.onvoid.webrtc.client.LocalAudio;
import dev.onvoid.webrtc.client.LocalVideo;
import dev.onvoid.webrtc.client.RemoteVideo;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.client.Encoding;
import dev.onvoid.webrtc.client.FrameDelivery;
import dev.onvoid.webrtc.client.Subscription;
import dev.onvoid.webrtc.media.video.VideoContentHint;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.PeerState;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.WebRtc;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NativeVoiceEngine implements VoiceEngine {
    // This worker serializes startup, signaling, media changes and cleanup, and exits when idle.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon().name("bate-papo-voice").factory());
    private final Function<Consumer<Throwable>, WebRtc> openRuntime;
    private final Function<WebRtc, ? extends LocalAudio> openAudio;
    private final ScreenCapture capture;
    private boolean screenCapability;
    private volatile Consumer<Frame> viewer;
    private volatile Session active;

    public NativeVoiceEngine() {
        this(errors -> WebRtc.builder().errorHandler(errors).build(), rtc -> rtc.media().microphone(), ScreenCapture.DESKTOP);
    }

    NativeVoiceEngine(Supplier<WebRtc> openRuntime, Function<WebRtc, ? extends LocalAudio> openAudio) {
        this(openRuntime, openAudio, ScreenCapture.DESKTOP);
    }

    NativeVoiceEngine(Supplier<WebRtc> openRuntime, Function<WebRtc, ? extends LocalAudio> openAudio, ScreenCapture capture) {
        this(errors -> openRuntime.get(), openAudio, capture);
    }

    private NativeVoiceEngine(Function<Consumer<Throwable>, WebRtc> openRuntime,
                              Function<WebRtc, ? extends LocalAudio> openAudio, ScreenCapture capture) {
        this.openRuntime = openRuntime;
        this.openAudio = openAudio;
        this.capture = capture;
    }

    @Override public synchronized void enableScreenSharing(boolean supported) {
        if (active != null) throw new IllegalStateException("A chamada já começou.");
        screenCapability = supported;
    }

    @Override
    public synchronized void start(VoiceConfig config, Consumer<String> signals, Consumer<String> states) {
        Objects.requireNonNull(config);
        var session = new Session(Objects.requireNonNull(signals), Objects.requireNonNull(states), screenCapability);
        if (active != null) throw new IllegalStateException("Já existe uma chamada de voz em andamento.");
        active = session;
        submit(session, () -> initialize(session, config));
    }

    @Override
    public void signal(String json) {
        var session = active;
        submit(session, () -> {
            if (session.screenCapable && (ScreenSignal.START.equals(json) || ScreenSignal.STOP.equals(json))) {
                remoteSharing(session, ScreenSignal.START.equals(json));
                return;
            }
            var message = SignalMessage.fromJson(session.screenCapable ? ScreenSignal.unpack(json) : json);
            var description = message.description();
            if (description != null && description.sdp.lines()
                    .anyMatch(line -> line.startsWith("m=") && !line.startsWith("m=audio ")
                            && !(session.screenCapable && line.startsWith("m=video ")))) {
                finish(session, "failed");
                return;
            }
            session.peer.signal(message);
        });
    }

    @Override
    public void setMuted(boolean muted) {
        var session = active;
        submit(session, () -> session.track.setEnabled(!muted));
    }

    @Override public void onScreenFrame(Consumer<Frame> viewer) { this.viewer = viewer; }

    @Override public CompletableFuture<List<Screen>> screens() {
        var session = active;
        var result = new CompletableFuture<List<Screen>>();
        worker.execute(() -> {
            if (!canShare(session)) {
                result.complete(List.of());
                return;
            }
            try { result.complete(capture.displays(session.runtime)); }
            catch (RuntimeException | LinkageError failure) {
                result.completeExceptionally(new IllegalStateException("Não foi possível listar os monitores."));
            }
        });
        return result;
    }

    private boolean canShare(Session session) {
        return session != null && session == active && session.screenCapable && session.connected;
    }

    @Override public void startScreen(Screen selected) {
        var session = active;
        submit(session, () -> {
            if (!canShare(session) || session.localScreen != null) return;
            try {
                if (selected == null || !capture.displays(session.runtime).contains(selected))
                    throw new IllegalArgumentException("Monitor indisponível.");
                if (session != active) return;
                LocalVideo screen = capture.open(session.runtime, selected);
                session.localScreen = screen;
                if (session != active) {
                    screen.close();
                    return;
                }
                screen.setContentHint(VideoContentHint.TEXT);
                CompletableFuture<Void> attached;
                if (session.videoSender == null) {
                    session.videoSender = session.peer.addTrack(screen, "wavecove-screen");
                    var encoding = Encoding.builder().maxBitrate(24_000_000).maxFrameRate(30).build();
                    attached = session.videoSender.configure(encoding);
                } else {
                    // Reuse the negotiated sender so toggling sharing never grows the SDP.
                    attached = session.videoSender.replaceTrack(screen);
                }
                attached.whenComplete((unused, failure) -> submit(session, () -> {
                    if (session.localScreen != screen) return;
                    if (failure == null) {
                        session.signals.accept(ScreenSignal.START);
                        session.states.accept("screenstarted");
                    } else endScreen(session, "screenfailed");
                }));
            } catch (RuntimeException | LinkageError failure) { endScreen(session, "screenfailed"); }
        });
    }

    @Override public void stopScreen() {
        var session = active;
        submit(session, () -> endScreen(session, "screenstopped"));
    }

    private void endScreen(Session session, String state) {
        var track = session.localScreen;
        var sender = session.videoSender;
        session.localScreen = null;
        if (track != null) {
            try {
                track.setEnabled(false);
                if (sender != null) sender.replaceTrack(null);
            } finally { track.close(); }
            session.signals.accept(ScreenSignal.STOP);
        }
        session.states.accept(state);
    }

    private void remoteSharing(Session session, boolean enabled) {
        if (enabled == session.remoteSharing) return;
        session.remoteSharing = enabled;
        session.states.accept(enabled ? "remotescreenstarted" : "remotescreenstopped");
    }

    private void remoteScreen(Session session, RemoteVideo track) {
        if (track.isEnded() || track == session.remoteScreen) return;
        if (session.frameSubscription != null) session.frameSubscription.close();
        session.remoteScreen = track;
        track.onEnded(() -> submit(session, () -> {
            if (session.remoteScreen == track) {
                session.remoteScreen = null;
                session.frameSubscription = null;
                remoteSharing(session, false);
            }
        }));
        session.frameSubscription = track.onFrame(FrameDelivery.LATEST, video -> remoteFrame(session, track, video));
    }

    private void remoteFrame(Session session, RemoteVideo track, VideoFrame video) {
        var recipient = viewer;
        if (recipient == null || active != session || session.remoteScreen != track || !session.remoteSharing) return;
        try {
            Frame frame = session.pixels.convert(video);
            if (frame == null) return;
            try {
                if (active == session && session.remoteScreen == track && session.remoteSharing && viewer == recipient) {
                    recipient.accept(frame);
                    return;
                }
            } catch (RuntimeException ignored) { }
            frame.close();
        } catch (Exception | LinkageError ignored) {
            // A bad frame may be dropped without disrupting the ongoing audio call.
        }
    }

    @Override
    public synchronized void close() {
        var session = active;
        if (session == null) return;
        active = null;
        viewer = null;
        screenCapability = false;
        worker.execute(session::release);
    }

    private void initialize(Session session, VoiceConfig config) {
        session.states.accept("connecting");
        if (active != session) return;

        var options = PeerOptions.builder().polite(!config.caller())
                .signaling(message -> submit(session, () -> {
                    String signal = message.toJson();
                    session.signals.accept(session.screenCapable && message.description() != null ? ScreenSignal.pack(signal) : signal);
                }));
        for (var server : config.iceServers()) {
            if (server.username() == null) {
                options.iceServer(server.urls().toArray(String[]::new));
            } else {
                for (String url : server.urls()) {
                    options.iceServer(url, server.username(), server.credential());
                }
            }
        }

        session.runtime = openRuntime.apply(error -> submit(session, () -> finish(session, "failed")));
        if (active != session) return;
        session.peer = session.runtime.createPeer(options.build());
        session.peer.onError(error -> submit(session, () -> finish(session, "failed")));
        session.peer.onStateChange(state -> submit(session, () -> onState(session, state)));

        if (session.screenCapable) session.peer.onTrack(track -> {
            if (track.isVideo()) submit(session, () -> remoteScreen(session, track.asVideo()));
        });

        if (active != session) return;
        // The library's microphone track enables echo cancellation and noise suppression.
        session.track = openAudio.apply(session.runtime);
        if (active == session) session.peer.addTrack(session.track);
    }

    private void onState(Session session, PeerState state) {
        switch (state) {
            case CONNECTED -> {
                session.connected = true;
                session.states.accept("connected");
            }
            case DISCONNECTED, CLOSED -> finish(session, "disconnected");
            case FAILED -> finish(session, "failed");
            case NEW, CONNECTING -> { } // Startup already delivered "connecting".
        }
    }

    private void submit(Session session, Runnable work) {
        if (session == null || active != session) return;
        worker.execute(() -> {
            if (active != session) return;
            try {
                work.run();
            } catch (RuntimeException | LinkageError failure) {
                finish(session, "failed");
            }
        });
    }

    private void finish(Session session, String state) {
        if (active != session) return;
        try {
            session.states.accept(state);
        } catch (RuntimeException ignored) {
            // An application callback must not interrupt native cleanup.
        } finally {
            synchronized (this) {
                if (active == session) close();
            }
        }
    }

    private static final class Session {
        private final Consumer<String> signals;
        private final Consumer<String> states;
        private WebRtc runtime;
        private Peer peer;
        private LocalAudio track;

        private final boolean screenCapable;
        private final ScreenPixels pixels = new ScreenPixels();
        private boolean connected;
        private LocalVideo localScreen;
        private Sender videoSender;
        private volatile RemoteVideo remoteScreen;
        private volatile boolean remoteSharing;
        private Subscription frameSubscription;

        private Session(Consumer<String> signals, Consumer<String> states, boolean screenCapable) {
            this.screenCapable = screenCapable;
            this.signals = signals;
            this.states = states;
        }

        private void release() {
            // This runtime owns the peer, track, audio devices and native threads.
            if (runtime != null) runtime.close();
        }
    }
}
