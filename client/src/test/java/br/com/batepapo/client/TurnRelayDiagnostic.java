package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.VoiceConfig;
import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.PcmFormat;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.PeerState;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.WebRtc;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Manual local diagnostic. Its entry point is separate from the distributable application. */
public final class TurnRelayDiagnostic {
    private static final String SERVER = "https://bate-papo-java.onrender.com";
    private static final int SAMPLE_RATE = 48_000;

    public static void main(String[] args) {
        Application.launch(Window.class);
    }

    public static final class Window extends Application {
        private Thread worker;

        @Override
        public void start(Stage stage) {
            var password = new PasswordField();
            var prompt = new Label("Código de acesso do Bate-papo");
            prompt.setLabelFor(password);
            var run = new Button("Testar TURN");
            run.setDefaultButton(true);
            var status = new Label("Aguardando início. O teste usa áudio sintético.");
            status.setWrapText(true);
            run.setOnAction(event -> {
                String accessCode = password.getText();
                password.clear();
                if (accessCode.isBlank() || accessCode.length() > 128) {
                    status.setText("Informe o código de acesso do chat.");
                    return;
                }
                password.setDisable(true);
                run.setDisable(true);
                worker = Thread.ofPlatform().daemon().name("turn-diagnostic").start(() -> {
                    String result = new Probe(text -> Platform.runLater(() -> status.setText(text))).run(accessCode);
                    Platform.runLater(() -> {
                        status.setText(result);
                        password.setDisable(false);
                        run.setDisable(false);
                    });
                });
            });
            var body = new VBox(10, prompt, password, run, status);
            body.setPadding(new Insets(16));
            stage.setTitle("Bate-papo — Diagnóstico TURN");
            stage.setScene(new Scene(body, 510, 165));
            stage.show();
        }

        @Override
        public void stop() {
            if (worker != null) worker.interrupt();
        }
    }

    private static final class Probe {
        private final String callId = UUID.randomUUID().toString();
        private final ArrayBlockingQueue<Runnable> inbox = new ArrayBlockingQueue<>(512);
        private final Consumer<String> progress;
        private String stage;

        private Probe(Consumer<String> progress) {
            this.progress = progress;
        }

        String run(String accessCode) {
            try (var a = new Endpoint(true); var b = new Endpoint(false)) {
                a.other = b;
                b.other = a;
                stage("Conectando duas sessões ao chat");
                a.chat.connect(SERVER, a.name, accessCode);
                b.chat.connect(SERVER, b.name, accessCode);
                await(() -> a.ready && b.ready, a, b, 65);

                stage("Obtendo credenciais temporárias após o aceite");
                a.send("callinvite", b.name, null);
                await(() -> a.config != null && b.config != null, a, b, 20);

                stage("Negociando conexão somente por TURN");
                a.createPeer();
                b.createPeer();
                a.peer.addTrack(a.audio);
                b.peer.addTrack(b.audio);
                await(() -> a.connected() && b.connected(), a, b, 35);
                a.send("callconnected", null, null);
                b.send("callconnected", null, null);

                stage("Verificando áudio recebido nos dois sentidos");
                a.audio.push(tone(440), PcmFormat.of(SAMPLE_RATE, 1));
                b.audio.push(tone(660), PcmFormat.of(SAMPLE_RATE, 1));
                await(() -> a.decoded && b.decoded, a, b, 7);
                if (!a.relayAndRtp() || !b.relayAndRtp()) return "Falha: relay ou RTP de áudio ausente.";
                return "OK — relay TURN selecionado nos dois peers. Áudio PCM e RTP recebidos nos dois sentidos.";
            } catch (VoiceUnavailable unavailable) {
                return "Falha: chamadas de voz indisponíveis no servidor.";
            } catch (Exception | LinkageError failure) {
                return "Falha na etapa: " + stage + ".";
            }
        }

        private void stage(String text) {
            stage = text;
            progress.accept(text + "…");
        }

        private void await(BooleanSupplier done, Endpoint a, Endpoint b, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!done.getAsBoolean()) {
                if (a.failed || b.failed || System.nanoTime() >= deadline)
                    throw new IllegalStateException();
                Runnable next = inbox.poll(150, TimeUnit.MILLISECONDS);
                if (next != null) next.run();
            }
        }

        private final class Endpoint implements AutoCloseable {
            private final boolean caller;
            private final String name = "Turn-" + UUID.randomUUID().toString().substring(0, 8);
            private volatile boolean ready, failed, decoded;
            private final ChatConnection chat = new ChatConnection(event -> {
                switch (event.type()) {
                    case "voice", "callincoming", "callready", "callsignal", "callerror", "callended", "error" -> {
                        if (!inbox.offer(() -> receive(event))) failed = true;
                    }
                    default -> { }
                }
            }, state -> {
                if (ready && ("disconnected".equals(state) || "reconnecting".equals(state))) failed = true;
            });
            private Endpoint other;
            private VoiceConfig config;
            private WebRtc rtc;
            private Peer peer;
            private CustomAudio audio;

            private Endpoint(boolean caller) {
                this.caller = caller;
            }

            private void receive(Event event) {
                if ("voice".equals(event.type())) {
                    if (!"ready".equals(event.id())) throw new VoiceUnavailable();
                    ready = true;
                    return;
                }
                if ("error".equals(event.type())) throw new IllegalStateException();
                if (!callId.equals(event.id())) return;
                if ("callerror".equals(event.type()) || "callended".equals(event.type())) throw new IllegalStateException();
                if (!other.name.equals(event.name())) return;
                switch (event.type()) {
                    case "callincoming" -> {
                        if (!caller) send("callaccept", null, null);
                    }
                    case "callready" -> {
                        config = VoiceConfig.decode(event.text());
                        if (config.caller() != caller) throw new IllegalStateException();
                    }
                    case "callsignal" -> peer.signal(SignalMessage.fromJson(event.text()));
                    default -> { }
                }
            }

            private void createPeer() {
                var options = PeerOptions.builder().relayOnly(true).polite(!caller)
                        .signaling(message -> send("callsignal", null, message.toJson()));
                for (var server : config.iceServers()) {
                    for (String url : server.urls()) {
                        if (url.startsWith("turn:") || url.startsWith("turns:"))
                            options.iceServer(url, server.username(), server.credential());
                    }
                }
                config = null;
                rtc = WebRtc.builder().audio(AudioMode.HEADLESS)
                        .operationTimeout(Duration.ofSeconds(10)).closeTimeout(Duration.ofSeconds(3))
                        .errorHandler(failure -> failed = true).build();
                peer = rtc.createPeer(options.build());
                peer.onError(failure -> failed = true);
                peer.onStateChange(state -> {
                    if (state == PeerState.FAILED || state == PeerState.DISCONNECTED) failed = true;
                });
                peer.onTrack(track -> track.asAudio().onAudio(chunk -> {
                    var samples = ByteBuffer.wrap(chunk.samples()).order(ByteOrder.LITTLE_ENDIAN);
                    while (samples.remaining() >= Short.BYTES) {
                        if (Math.abs((int) samples.getShort()) > 500) {
                            decoded = true;
                            break;
                        }
                    }
                }));
                audio = rtc.media().customAudio("turn-diagnostic");
            }

            private boolean connected() {
                return peer.state() == PeerState.CONNECTED;
            }

            private boolean relayAndRtp() throws Exception {
                var stats = peer.stats().get(3, TimeUnit.SECONDS);
                return "relay".equals(stats.candidateType()) && stats.inboundAudio().stream()
                        .anyMatch(stream -> stream.packetsReceived() != null && stream.packetsReceived() > 0);
            }

            private void send(String type, String target, String text) {
                chat.voice(new Event(type, callId, target, text, null, null, null, null));
            }

            @Override
            public void close() {
                chat.close();
                if (rtc != null) rtc.close();
            }
        }
    }

    private static final class VoiceUnavailable extends RuntimeException { }

    private static byte[] tone(int frequency) {
        var bytes = ByteBuffer.allocate(2 * SAMPLE_RATE * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 2 * SAMPLE_RATE; i++) {
            bytes.putShort((short) (12_000 * Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE)));
        }
        return bytes.array();
    }
}
