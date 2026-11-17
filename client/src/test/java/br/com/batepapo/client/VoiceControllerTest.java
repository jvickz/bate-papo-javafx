package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.VoiceConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test void sharingRequiresNewClientsConnectedCallAndConfirmationOfAnEnumeratedDisplay() {
        var oldEngine = connectOutgoing();
        oldEngine.states.accept("connected");
        runCallbacks();
        controller.chooseScreen(choice -> fail("Cliente antigo deve manter apenas áudio"));
        assertFalse(oldEngine.screenEnabled);
        assertEquals(0, oldEngine.enumerations);
        controller.end();

        var engine = connectScreen();
        assertTrue(engine.screenEnabled);
        controller.chooseScreen(choice -> fail("Áudio ainda não conectou"));
        assertEquals(0, engine.enumerations);
        engine.states.accept("connected");
        runCallbacks();
        var selected = new AtomicReference<VoiceController.ScreenChoice>();
        controller.chooseScreen(selected::set);
        assertNull(engine.started);
        var monitor = new VoiceEngine.Screen(3, "Monitor 3");
        engine.availableScreens.complete(List.of(monitor));
        runCallbacks();
        assertEquals(List.of(monitor), selected.get().screens());
        controller.confirmScreen("chamada antiga", monitor);
        assertNull(engine.started);
        controller.confirmScreen(view.callId(), new VoiceEngine.Screen(4, "Outro"));
        assertNull(engine.started);
        controller.chooseScreen(selected::set);
        runCallbacks();
        controller.confirmScreen(view.callId(), monitor);
        assertEquals(monitor, engine.started);
        engine.states.accept("screenstarted");
        runCallbacks();
        assertEquals(VoiceController.Sharing.ON, view.sharing());
        controller.stopScreen();
        assertEquals(1, engine.stops);
        engine.states.accept("screenstopped");
        runCallbacks();
        assertEquals(VoiceController.Sharing.OFF, view.sharing());
        assertEquals(VoiceController.Phase.CONNECTED, view.phase());
    }

    @Test void lateScreenChoiceAndLateFramesCannotReachANewCall() {
        var engine = connectScreen();
        engine.states.accept("connected");
        engine.states.accept("remotescreenstarted");
        runCallbacks();
        assertTrue(view.receiving());
        var sink = controller.openViewer(view.callId());
        assertNotNull(sink);
        var callback = engine.frames;
        callback.accept(new VoiceEngine.Frame(2, 2, new byte[16]));
        assertNotNull(sink.take());
        var picked = new AtomicReference<VoiceController.ScreenChoice>();
        controller.chooseScreen(picked::set);
        controller.connection(false, true);
        engine.availableScreens.complete(List.of(new VoiceEngine.Screen(1, "Monitor 1")));
        engine.states.accept("remotescreenstarted");
        callback.accept(new VoiceEngine.Frame(2, 2, new byte[16]));
        runCallbacks();
        assertNull(sink.take());
        assertNull(picked.get());
        assertFalse(view.receiving());
        assertEquals(1, engine.closes);
    }

    private FakeEngine connectScreen() {
        controller.connection(true, false);
        controller.participant("Alice", "Beto");
        event("voice", "ready", null, null);
        controller.invite();
        var id = commands.getLast().id();
        controller.receive(new Event("callready", id, "Beto", config(true).encode(),
                null, null, List.of("screen-v1"), null, "geral"));
        return engines.getLast();
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
        private boolean screenEnabled;
        private int enumerations;
        private int stops;
        private Screen started;
        private Consumer<Frame> frames;
        private final CompletableFuture<List<Screen>> availableScreens = new CompletableFuture<>();

        @Override public void enableScreenSharing(boolean enabled) { screenEnabled = enabled; }
        @Override public CompletableFuture<List<Screen>> screens() { enumerations++; return availableScreens; }
        @Override public void startScreen(Screen screen) { started = screen; }
        @Override public void stopScreen() { stops++; }
        @Override public void onScreenFrame(Consumer<Frame> listener) { frames = listener; }

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
