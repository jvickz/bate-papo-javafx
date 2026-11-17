package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.VoiceConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class VoiceControllerTest {
    private static final String INCOMING = "a5525440-f523-4db9-8ba8-b170f68169e0";
    private static final String STALE = "b5525440-f523-4db9-8ba8-b170f68169e0";
    private final List<Event> commands = new ArrayList<>();
    private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
    private final List<FakeEngine> engines = new ArrayList<>();
    private VoiceController.View view;
    private final VoiceController controller = new VoiceController(commands::add, () -> {
        var engine = new FakeEngine();
        engines.add(engine);
        return engine;
    }, callbacks::add, next -> view = next);

    @Test void capabilityAndAnotherParticipantAreRequiredToCall() {
        controller.connection(true, false);
        controller.participant("Alice", "Beto");
        assertFalse(view.canInvite());
        controller.invite();
        assertTrue(commands.isEmpty());

        event("voice", "ready", null, null);
        controller.participant("Alice", "Alice");
        assertFalse(view.canInvite());
        controller.invite();
        assertTrue(commands.isEmpty());

        controller.participant("Alice", "Beto");
        assertTrue(view.canInvite());
        controller.invite();
        assertEquals(VoiceController.Phase.OUTGOING, view.phase());
        assertEquals("callinvite", commands.getFirst().type());
        assertEquals("Beto", commands.getFirst().name());
        assertTrue(engines.isEmpty());
    }

    @Test void calleeStartsAudioOnlyAfterLocalAcceptanceAndMatchingReadyEvent() {
        available();
        var receiverConfig = config(false);
        event("callincoming", INCOMING, "Beto", null);
        event("callready", INCOMING, "Beto", receiverConfig.encode());
        assertEquals(VoiceController.Phase.INCOMING, view.phase());
        assertTrue(engines.isEmpty());

        controller.accept();
        assertEquals("callaccept", commands.getLast().type());
        assertTrue(engines.isEmpty());
        event("callready", STALE, "Beto", receiverConfig.encode());
        event("callready", INCOMING, "Carla", receiverConfig.encode());
        assertTrue(engines.isEmpty());
        event("callready", INCOMING, "Beto", receiverConfig.encode());
        event("callready", INCOMING, "Beto", receiverConfig.encode());
        assertEquals(1, engines.size());
        assertEquals(receiverConfig, engines.getFirst().config);
    }

    @Test void declinedInvitationDoesNotReappearOrOpenAudio() {
        available();
        event("callincoming", INCOMING, "Beto", null);
        controller.reject();
        assertEquals("callreject", commands.getLast().type());
        assertEquals(INCOMING, commands.getLast().id());
        event("callincoming", INCOMING, "Beto", null);
        event("callready", INCOMING, "Beto", config(false).encode());
        assertTrue(engines.isEmpty());
        assertEquals(VoiceController.Phase.IDLE, view.phase());
    }

    @Test void canceledOutgoingInvitationIgnoresLateReady() {
        available();
        controller.invite();
        String id = commands.getLast().id();
        event("callringing", id, "Beto", null);
        controller.end();
        event("callready", id, "Beto", config(true).encode());
        assertTrue(engines.isEmpty());
        assertEquals("callend", commands.getLast().type());
        assertEquals(id, commands.getLast().id());
        assertEquals(VoiceController.Phase.IDLE, view.phase());
    }

    @Test void connectedCallSignalsOnceMutesAndEnds() {
        var engine = connectOutgoing();
        String id = commands.getFirst().id();
        engine.states.accept("connected");
        engine.states.accept("connected");
        engine.signals.accept("{\"type\":\"offer\"}");
        runCallbacks();

        assertEquals(VoiceController.Phase.CONNECTED, view.phase());
        assertEquals(1, commands.stream().filter(e -> e.type().equals("callconnected")).count());
        assertEquals("callsignal", commands.getLast().type());
        assertEquals(id, commands.getLast().id());
        event("callsignal", STALE, "Beto", "stale");
        event("callsignal", id, "Beto", "{\"type\":\"answer\"}");
        assertEquals(List.of("{\"type\":\"answer\"}"), engine.received);

        controller.toggleMute();
        assertTrue(view.muted());
        controller.toggleMute();
        assertFalse(view.muted());
        assertEquals(List.of(true, false), engine.mutes);
        event("callended", id, "Beto", "A outra pessoa encerrou a chamada.");
        assertEquals(1, engine.closes);
        assertEquals(VoiceController.Phase.IDLE, view.phase());
    }

    @Test void channelSwitchClosesAudioAndDropsAlreadyQueuedEngineCallbacks() {
        var engine = connectOutgoing();
        var id = commands.getFirst().id();
        engine.states.accept("connected");
        engine.signals.accept("sinal antigo");
        controller.connection(false, true);
        assertEquals(1, engine.closes);
        assertEquals("callend", commands.getLast().type());
        assertFalse(view.canInvite());

        controller.connection(true, false);
        controller.invite();
        int before = commands.size();
        event("callready", id, "Beto", config(true).encode());
        runCallbacks();
        assertEquals(before, commands.size());
        assertEquals(1, engines.size());
        assertEquals(VoiceController.Phase.OUTGOING, view.phase());
    }

    @Test void disconnectClosesAudioAndRequiresFreshCapability() {
        var engine = connectOutgoing();
        controller.connection(false, false);
        assertEquals(1, engine.closes);
        assertEquals(VoiceController.Phase.IDLE, view.phase());
        controller.connection(true, false);
        assertFalse(view.canInvite());
        event("voice", "ready", null, null);
        assertTrue(view.canInvite());
    }

    @Test void nativeFailureEndsTheCallWithoutExposingExceptionDetails() {
        var engine = connectOutgoing();
        engine.states.accept("failed");
        runCallbacks();
        assertEquals("callend", commands.getLast().type());
        assertEquals(1, engine.closes);
        assertEquals(VoiceController.Phase.IDLE, view.phase());
        assertTrue(view.text().contains("áudio"));
    }

    private void available() {
        controller.connection(true, false);
        controller.participant("Alice", "Beto");
        event("voice", "ready", null, null);
    }

    private FakeEngine connectOutgoing() {
        available();
        controller.invite();
        var id = commands.getLast().id();
        event("callringing", id, "Beto", null);
        event("callready", id, "Beto", config(true).encode());
        return engines.getFirst();
    }

    private static VoiceConfig config(boolean caller) {
        var turn = new VoiceConfig.IceServer(List.of("turn:example.test"), "temporario", "somente-teste");
        return new VoiceConfig(List.of(turn), caller);
    }

    private void event(String type, String id, String peer, String text) {
        controller.receive(new Event(type, id, peer, text, null, null, null, null, "geral"));
    }

    private void runCallbacks() {
        while (!callbacks.isEmpty()) callbacks.removeFirst().run();
    }

    private static final class FakeEngine implements VoiceEngine {
        private final List<String> received = new ArrayList<>();
        private final List<Boolean> mutes = new ArrayList<>();
        private VoiceConfig config;
        private Consumer<String> signals;
        private Consumer<String> states;
        private int closes;

        @Override public void start(VoiceConfig config, Consumer<String> signals, Consumer<String> states) {
            this.config = config;
            this.signals = signals;
            this.states = states;
        }

        @Override public void signal(String json) { received.add(json); }
        @Override public void setMuted(boolean muted) { mutes.add(muted); }
        @Override public void close() { closes++; }
    }
}
