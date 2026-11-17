package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.VoiceConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owned by the UI thread. Engine callbacks are dispatched to that same thread. */
final class VoiceController {
    enum Phase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED }
    enum Sharing { OFF, CHOOSING, STARTING, ON, STOPPING }
    record View(Phase phase, boolean canInvite, boolean muted, String text, String callId, String peer,
                boolean canShare, Sharing sharing, boolean receiving) {}
    record ScreenChoice(String callId, String peer, List<VoiceEngine.Screen> screens) {}

    private static final String UNAVAILABLE = "Chamadas de voz indisponíveis neste servidor.";
    private static final String SEND_FAILED = "Não foi possível enviar o comando de voz.";
    private final Consumer<Event> send;
    private final Supplier<VoiceEngine> engines;
    private final Executor dispatch;
    private final Consumer<View> render;
    private final LinkedHashSet<String> retired = new LinkedHashSet<>();
    private boolean online;
    private boolean switching;
    private boolean available;
    private String availability = UNAVAILABLE;
    private String self;
    private String selected;
    private String notice;
    private Call call;

    VoiceController(Consumer<Event> send, Supplier<VoiceEngine> engines, Executor dispatch, Consumer<View> render) {
        this.send = send;
        this.engines = engines;
        this.dispatch = dispatch;
        this.render = render;
    }

    void connection(boolean online, boolean switching) {
        this.online = online;
        this.switching = switching;
        if (switching && call != null) {
            terminate("Chamada encerrada ao trocar de canal.", "callend");
        } else if (!online && !switching) {
            available = false;
            availability = UNAVAILABLE;
            if (call != null) terminate("Chamada encerrada. A conexão foi perdida.", null);
        }
        publish();
    }

    void participant(String self, String selected) {
        this.self = self;
        this.selected = selected;
        publish();
    }

    void invite() {
        if (!canInvite()) return;
        call = new Call(UUID.randomUUID().toString(), selected, true, Phase.OUTGOING);
        notice = null;
        if (!command("callinvite", call, null)) terminate(SEND_FAILED, null);
        publish();
    }

    void accept() {
        if (call == null || call.phase != Phase.INCOMING || !online) return;
        call.phase = Phase.CONNECTING;
        notice = null;
        if (!command("callaccept", call, null)) terminate(SEND_FAILED, null);
        publish();
    }

    void reject() {
        if (call != null && call.phase == Phase.INCOMING) {
            terminate("Chamada recusada.", "callreject");
            publish();
        }
    }

    void end() {
        if (call != null) {
            terminate(call.phase == Phase.OUTGOING ? "Chamada cancelada." : "Chamada encerrada.", "callend");
            publish();
        }
    }

    void toggleMute() {
        if (call == null || call.phase != Phase.CONNECTED) return;
        try {
            call.engine.setMuted(!call.muted);
            call.muted = !call.muted;
        } catch (RuntimeException | LinkageError failure) {
            terminate("Não foi possível controlar o microfone.", "callend");
        }
        publish();
    }

    void chooseScreen(Consumer<ScreenChoice> openPicker) {
        var current = call;
        if (current == null || current.phase != Phase.CONNECTED || !current.canShare
                || current.sharing != Sharing.OFF) return;
        current.sharing = Sharing.CHOOSING;
        notice = null;
        publish();
        try {
            current.engine.screens().whenComplete((screens, failure) -> dispatch.execute(() -> {
                if (call != current || current.sharing != Sharing.CHOOSING) return;
                if (failure != null || screens == null || screens.isEmpty()) {
                    screenError("Nenhuma tela disponível para compartilhar.");
                    return;
                }
                current.choices = List.copyOf(screens);
                openPicker.accept(new ScreenChoice(current.id, current.peer, current.choices));
            }));
        } catch (RuntimeException | LinkageError failure) {
            screenError("Não foi possível listar suas telas.");
        }
    }

    void confirmScreen(String callId, VoiceEngine.Screen screen) {
        var current = call;
        if (current == null || !current.id.equals(callId) || current.phase != Phase.CONNECTED
                || current.sharing != Sharing.CHOOSING) return;
        boolean start = screen != null && current.choices.contains(screen);
        current.choices = List.of();
        current.sharing = start ? Sharing.STARTING : Sharing.OFF;
        try {
            if (start) current.engine.startScreen(screen);
        } catch (RuntimeException | LinkageError failure) {
            screenError("Não foi possível iniciar o compartilhamento.");
            return;
        }
        publish();
    }

    void stopScreen() {
        if (call == null || (call.sharing != Sharing.ON && call.sharing != Sharing.STARTING)) return;
        call.sharing = Sharing.STOPPING;
        try {
            call.engine.stopScreen();
        } catch (RuntimeException | LinkageError failure) {
            // If capture cannot be stopped normally, close its owning media session.
            terminate("Chamada encerrada para interromper o compartilhamento.", "callend");
        }
        publish();
    }

    ScreenFrames openViewer(String callId) {
        if (call == null || !call.id.equals(callId) || !call.receiving || call.phase != Phase.CONNECTED) return null;
        if (call.frames == null) {
            call.frames = new ScreenFrames();
            call.engine.onScreenFrame(call.frames::offer);
        }
        return call.frames;
    }

    void closeViewer(ScreenFrames frames) {
        if (call != null && frames != null && call.frames == frames) detachViewer(call);
    }

    private void detachViewer(Call ended) {
        if (ended.frames == null) return;
        ended.frames.close();
        ended.frames = null;
        ended.engine.onScreenFrame(null);
    }

    private void screenError(String text) {
        call.sharing = Sharing.OFF;
        call.choices = List.of();
        notice = text;
        publish();
    }

    void receive(Event event) {
        if (!online && !(switching && "voice".equals(event.type()))) return;
        if ("voice".equals(event.type())) {
            updateCapability(event);
        } else if ("callincoming".equals(event.type())) {
            if (!available || call != null || !validCallId(event.id()) || retired.contains(event.id())
                    || event.name() == null || event.name().isBlank() || event.name().equals(self)) return;
            notice = null;
            call = new Call(event.id(), event.name(), false, Phase.INCOMING);
        } else {
            if (call == null || !call.id.equals(event.id())) return;
            if (!call.peer.equals(event.name()) && !("callerror".equals(event.type()) && event.name() == null)) return;
            switch (event.type()) {
                case "callringing" -> { if (call.phase == Phase.OUTGOING) call.ringing = true; }
                case "callready" -> ready(event);
                case "callsignal" -> forwardSignal(event.text());
                case "callended" -> terminate(message(event.text(), "Chamada encerrada."), null);
                case "callerror" -> handleError(event.text());
                default -> { return; }
            }
        }
        publish();
    }

    private void updateCapability(Event event) {
        available = "ready".equals(event.id());
        availability = available ? null : message(event.text(), UNAVAILABLE);
        if (!available && call != null) terminate(availability, "callend");
    }

    private void ready(Event ready) {
        var current = call;
        if (current.engine != null
                || (current.phase != Phase.OUTGOING && current.phase != Phase.CONNECTING)) return;
        try {
            var config = VoiceConfig.decode(ready.text());
            if (config.caller() != current.caller) {
                terminate("O servidor enviou uma configuração de voz inválida.", "callend");
                return;
            }
            current.phase = Phase.CONNECTING;
            current.engine = engines.get();
            current.canShare = ready.users() != null && ready.users().contains(VoiceConfig.SCREEN_CAPABILITY);
            current.engine.enableScreenSharing(current.canShare);
            current.engine.start(config,
                    signal -> dispatch.execute(() -> outgoingSignal(current, signal)),
                    state -> dispatch.execute(() -> engineState(current, state)));
        } catch (RuntimeException | LinkageError failure) {
            terminate("Não foi possível iniciar o áudio da chamada.", "callend");
        }
    }

    private void forwardSignal(String value) {
        if (call.engine == null || value == null) return;
        try {
            call.engine.signal(value);
        } catch (RuntimeException | LinkageError failure) {
            terminate("A conexão de áudio foi interrompida.", "callend");
        }
    }

    private void handleError(String text) {
        String reason = message(text, "O servidor recusou o comando de voz.");
        if (call.phase == Phase.OUTGOING && !call.ringing) {
            terminate(reason, null);
        } else if (call.phase == Phase.CONNECTING && call.engine == null) {
            terminate(reason, "callend");
        } else {
            notice = reason;
        }
    }

    private void outgoingSignal(Call origin, String signal) {
        if (call == origin && online && !command("callsignal", origin, signal)) {
            terminate(SEND_FAILED, "callend");
            publish();
        }
    }

    private void engineState(Call origin, String state) {
        if (call != origin || !online) return;
        switch (state) {
            case "connected" -> {
                if (origin.phase != Phase.CONNECTING) return;
                if (command("callconnected", origin, null)) {
                    origin.phase = Phase.CONNECTED;
                    notice = null;
                } else {
                    terminate(SEND_FAILED, "callend");
                }
            }
            case "disconnected" -> terminate("A conexão de áudio foi interrompida.", "callend");
            case "failed" -> terminate("Não foi possível conectar o áudio. Confira o microfone e a conexão.", "callend");
            case "screenstarted" -> {
                if (origin.sharing != Sharing.STARTING) return;
                origin.sharing = Sharing.ON;
            }
            case "screenstopped" -> { if (origin.sharing != Sharing.CHOOSING) origin.sharing = Sharing.OFF; }
            case "screenfailed" -> {
                origin.sharing = Sharing.OFF;
                notice = "Não foi possível compartilhar esta tela.";
            }
            case "remotescreenstarted" -> { if (origin.canShare) origin.receiving = true; }
            case "remotescreenstopped" -> {
                origin.receiving = false;
                detachViewer(origin);
            }
            default -> { return; }
        }
        publish();
    }

    private void terminate(String reason, String type) {
        var ended = call;
        call = null;
        notice = reason;
        retired.add(ended.id);
        if (retired.size() > 128) retired.removeFirst();
        if (ended.engine != null) {
            detachViewer(ended);
            try { ended.engine.close(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
        if (type != null) command(type, ended, null);
    }

    private boolean command(String type, Call current, String text) {
        try {
            send.accept(new Event(type, current.id, type.equals("callinvite") ? current.peer : null,
                    text, null, null, null, null));
            return true;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return false;
        }
    }

    private boolean canInvite() {
        return online && !switching && available && call == null && self != null
                && selected != null && !selected.equals(self);
    }

    private void publish() {
        if (call == null) {
            String text;
            if (switching) text = "Aguarde a confirmação do canal para ligar.";
            else if (!online) text = message(notice, "Conecte-se para ligar.");
            else if (!available) text = availability;
            else if (notice != null) text = notice;
            else if (canInvite()) text = "Ligar para " + selected + ".";
            else text = "Selecione outro participante para ligar.";
            render.accept(new View(Phase.IDLE, canInvite(), false, text, null, null, false, Sharing.OFF, false));
            return;
        }
        String text = switch (call.phase) {
            case OUTGOING -> "Chamando " + call.peer + "…";
            case INCOMING -> call.peer + " está ligando. O microfone só abre após aceitar.";
            case CONNECTING -> "Conectando áudio com " + call.peer + "…";
            case CONNECTED -> "Em chamada com " + call.peer;
            case IDLE -> throw new IllegalStateException();
        };
        if (notice != null) text += " · " + notice;
        render.accept(new View(call.phase, false, call.muted, text, call.id, call.peer,
                call.canShare, call.sharing, call.receiving));
    }

    private static boolean validCallId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException failure) { return false; }
    }

    private static String message(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static final class Call {
        final String id;
        final String peer;
        final boolean caller;
        Phase phase;
        VoiceEngine engine;
        boolean ringing;
        boolean muted;
        boolean canShare;
        Sharing sharing = Sharing.OFF;
        List<VoiceEngine.Screen> choices = List.of();
        boolean receiving;
        ScreenFrames frames;

        Call(String id, String peer, boolean caller, Phase phase) {
            this.id = id;
            this.peer = peer;
            this.caller = caller;
            this.phase = phase;
        }
    }
}
