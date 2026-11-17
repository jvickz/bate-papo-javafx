package br.com.batepapo.client;

import br.com.batepapo.protocol.VoiceConfig;
import dev.onvoid.webrtc.client.LocalAudio;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.PeerState;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.WebRtc;

import java.util.Objects;
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
    private volatile Session active;

    public NativeVoiceEngine() {
        this(errors -> WebRtc.builder().errorHandler(errors).build(), rtc -> rtc.media().microphone());
    }

    NativeVoiceEngine(Supplier<WebRtc> openRuntime, Function<WebRtc, ? extends LocalAudio> openAudio) {
        this(errors -> openRuntime.get(), openAudio);
    }

    private NativeVoiceEngine(Function<Consumer<Throwable>, WebRtc> openRuntime,
                              Function<WebRtc, ? extends LocalAudio> openAudio) {
        this.openRuntime = openRuntime;
        this.openAudio = openAudio;
    }

    @Override
    public synchronized void start(VoiceConfig config, Consumer<String> signals, Consumer<String> states) {
        Objects.requireNonNull(config);
        var session = new Session(Objects.requireNonNull(signals), Objects.requireNonNull(states));
        if (active != null) throw new IllegalStateException("Já existe uma chamada de voz em andamento.");
        active = session;
        submit(session, () -> initialize(session, config));
    }

    @Override
    public void signal(String json) {
        var session = active;
        submit(session, () -> {
            var message = SignalMessage.fromJson(json);
            var description = message.description();
            if (description != null && description.sdp.lines()
                    .anyMatch(line -> line.startsWith("m=") && !line.startsWith("m=audio "))) {
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

    @Override
    public synchronized void close() {
        var session = active;
        if (session == null) return;
        active = null;
        worker.execute(session::release);
    }

    private void initialize(Session session, VoiceConfig config) {
        session.states.accept("connecting");
        if (active != session) return;

        var options = PeerOptions.builder().polite(!config.caller())
                .signaling(message -> submit(session, () -> session.signals.accept(message.toJson())));
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

        if (active != session) return;
        // The library's microphone track enables echo cancellation and noise suppression.
        session.track = openAudio.apply(session.runtime);
        if (active == session) session.peer.addTrack(session.track);
    }

    private void onState(Session session, PeerState state) {
        switch (state) {
            case CONNECTED -> session.states.accept("connected");
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

        private Session(Consumer<String> signals, Consumer<String> states) {
            this.signals = signals;
            this.states = states;
        }

        private void release() {
            // This runtime owns the peer, track, audio devices and native threads.
            if (runtime != null) runtime.close();
        }
    }
}
