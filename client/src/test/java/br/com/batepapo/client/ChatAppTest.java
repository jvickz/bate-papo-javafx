package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.protocol.VoiceConfig;
import br.com.batepapo.server.ChatServer;
import br.com.batepapo.server.VoiceTestServer;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class ChatAppTest {
    @TempDir Path temporary;
    private static final String PASSWORD = "uma-senha-de-teste-42";

    private static void fillAccount(Stage stage, String name, String password, int port, boolean register) {
        ((ToggleButton) stage.getScene().lookup(register ? "#registerTab" : "#loginTab")).fire();
        ((TextField) stage.getScene().lookup("#username")).setText(name);
        ((TextField) ((TitledPane) stage.getScene().lookup("#serverOptions")).getContent().lookup("#server"))
                .setText("ws://localhost:" + port);
        ((PasswordField) stage.getScene().lookup("#password")).setText(password);
        ((Button) stage.getScene().lookup("#join")).fire();
    }

    static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); Platform.runLater(task); return task.get(45, TimeUnit.SECONDS);
    }
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    private static void snapshot(Stage stage, String name) throws Exception {
        String directory = System.getProperty("batepapo.screenshotDir");
        if (directory == null) return;
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        var image = stage.getScene().snapshot(null);
        var raster = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < raster.getHeight(); y++) for (int x = 0; x < raster.getWidth(); x++) raster.setRGB(x, y, reader.getArgb(x, y));
        var folder = java.nio.file.Path.of(directory); java.nio.file.Files.createDirectories(folder);
        javax.imageio.ImageIO.write(raster, "png", folder.resolve(name + ".png").toFile());
    }
    @Test void voiceRemainsUnavailableWhenServerHasNoTurnConfiguration() throws Exception {
        try (var host = ChatServer.localAccounts(temporary.resolve("accounts.db"), 0).start()) {
            var app = new ChatApp();
            Stage stage = fx(() -> {
                var window = new Stage();
                app.start(window);
                window.setWidth(900);
                window.setHeight(720);
                fillAccount(window, "TesteVoz", PASSWORD, host.port(), true);
                return window;
            });
            try {
                awaitFx(() -> stage.getScene().lookup("#composer") instanceof TextArea composer && !composer.isDisabled());
                fx(() -> {
                    assertInstanceOf(Button.class, stage.getScene().lookup("#voiceCall"));
                    assertTrue(stage.getScene().lookup("#voiceCall").isDisabled());
                    assertFalse(assertInstanceOf(Label.class, stage.getScene().lookup("#voiceStatus")).getText().isBlank());
                    assertFalse(stage.getScene().lookup("#composer").isDisabled());
                    snapshot(stage, "voice-unavailable-small");
                    return null;
                });
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    @Test void voiceControlsRequireConsentAndCloseAudioOnChannelChange() throws Exception {
        var callers = new ArrayList<UiVoiceEngine>();
        var recipients = new ArrayList<UiVoiceEngine>();
        try (var host = VoiceTestServer.startAccounts(temporary.resolve("accounts.db"))) {
            var alice = new ChatApp(() -> new UiVoiceEngine(callers));
            var beto = new ChatApp(() -> new UiVoiceEngine(recipients));
            Stage caller = loginForVoice(alice, host.port(), "Alice");
            Stage recipient = loginForVoice(beto, host.port(), "Beto");
            try {
                awaitFx(() -> {
                    var list = (ListView<?>) caller.getScene().lookup("#participants");
                    return list != null && list.getItems().contains("Beto")
                            && caller.getScene().lookup("#composer") instanceof TextArea field && !field.isDisabled();
                });
                fx(() -> {
                    var list = (ListView<?>) caller.getScene().lookup("#participants");
                    list.getSelectionModel().select(list.getItems().indexOf("Alice"));
                    assertTrue(caller.getScene().lookup("#voiceCall").isDisabled(), "Você não pode ligar para si");
                    list.getSelectionModel().select(list.getItems().indexOf("Beto"));
                    return null;
                });
                awaitFx(() -> !caller.getScene().lookup("#voiceCall").isDisabled());
                fx(() -> {
                    ((Button) caller.getScene().lookup("#voiceCall")).fire();
                    assertEquals("Cancelar", ((Button) caller.getScene().lookup("#voiceEnd")).getText());
                    snapshot(caller, "voice-ringing-small");
                    return null;
                });
                awaitFx(() -> recipient.getScene().lookup("#voiceAccept") != null
                        && recipient.getScene().lookup("#voiceAccept").isVisible());
                fx(() -> {
                    assertTrue(callers.isEmpty());
                    assertTrue(recipients.isEmpty());
                    snapshot(recipient, "voice-incoming-small");
                    ((Button) recipient.getScene().lookup("#voiceReject")).fire();
                    return null;
                });

                awaitFx(() -> caller.getScene().lookup("#voiceCall").isVisible()
                        && !caller.getScene().lookup("#voiceCall").isDisabled());
                fx(() -> {
                    var list = (ListView<?>) recipient.getScene().lookup("#participants");
                    list.getSelectionModel().select(list.getItems().indexOf("Alice"));
                    var returnCall = (Button) recipient.getScene().lookup("#voiceCall");
                    assertFalse(returnCall.isDisabled());
                    returnCall.fire();
                    return null;
                });
                awaitFx(() -> caller.getScene().lookup("#voiceAccept").isVisible());
                fx(() -> {
                    assertTrue(callers.isEmpty());
                    assertTrue(recipients.isEmpty());
                    ((Button) caller.getScene().lookup("#voiceAccept")).fire();
                    return null;
                });
                awaitFx(() -> caller.getScene().lookup("#voiceDuration").isVisible()
                        && recipient.getScene().lookup("#voiceDuration").isVisible());
                fx(() -> {
                    assertEquals(1, callers.size());
                    assertEquals(1, recipients.size());
                    var mute = (ToggleButton) caller.getScene().lookup("#voiceMute");
                    snapshot(caller, "voice-connected-small");
                    mute.fire();
                    assertTrue(mute.isSelected());
                    assertTrue(callers.getFirst().muted);
                    assertTrue(((Label) caller.getScene().lookup("#voiceDuration")).getText().matches("\\d{2}:\\d{2}"));
                    snapshot(caller, "voice-muted-small");
                    ((TextArea) caller.getScene().lookup("#composer")).setText("Texto durante a chamada");
                    ((Button) caller.getScene().lookup("#send")).fire();
                    return null;
                });
                awaitFx(() -> ((ListView<?>) recipient.getScene().lookup("#messages")).getItems().toString()
                        .contains("Texto durante a chamada"));
                fx(() -> {
                    ((TextField) caller.getScene().lookup("#channelName")).setText("porto");
                    ((Button) caller.getScene().lookup("#createChannel")).fire();
                    assertTrue(callers.getFirst().closed, "Trocar de canal deve encerrar o áudio imediatamente");
                    return null;
                });
                awaitFx(() -> recipients.getFirst().closed && !recipient.getScene().lookup("#voiceDuration").isVisible());
            } finally {
                fx(() -> { alice.stop(); beto.stop(); caller.close(); recipient.close(); return null; });
            }
        }
    }

    private static Stage loginForVoice(ChatApp app, int port, String name) throws Exception {
        var stage = fx(() -> {
            var window = new Stage();
            app.start(window);
            window.setWidth(900);
            window.setHeight(720);
            fillAccount(window, name, PASSWORD, port, true);
            return window;
        });
        awaitFx(() -> {
            if (stage.getScene().lookup("#authError") instanceof Label error && !error.getText().isEmpty())
                fail("Falha ao entrar com " + name + " — " + error.getText());
            return stage.getScene().lookup("#composer") instanceof TextArea field && !field.isDisabled();
        });
        return stage;
    }

    private static final class UiVoiceEngine implements VoiceEngine {
        private boolean muted;
        private boolean closed;

        UiVoiceEngine(List<UiVoiceEngine> instances) { instances.add(this); }

        @Override public void start(VoiceConfig config, Consumer<String> signals,
                                    Consumer<String> states) { states.accept("connected"); }
        @Override public void signal(String payload) {}
        @Override public void setMuted(boolean muted) { this.muted = muted; }
        @Override public void close() { closed = true; }
    }

    @Test void connectionNoticeIsReachableAtMinimumWindowSize() throws Exception {
        var app = new ChatApp();
        Stage stage = fx(() -> { var window = new Stage(); app.start(window); window.setWidth(900); window.setHeight(720); return window; });
        try {
            var laidOut = new CompletableFuture<Void>();
            Runnable afterLayout = () -> laidOut.complete(null);
            fx(() -> {
                var scene = stage.getScene();
                ((TextField) scene.lookup("#username")).setText("TesteUI");
                ((PasswordField) scene.lookup("#password")).setText(PASSWORD);
                var options = (TitledPane) scene.lookup("#serverOptions");
                ((TextField) options.getContent().lookup("#server")).setText("endereço inválido");
                ((Button) scene.lookup("#join")).fire();
                assertTrue(options.isExpanded());
                assertFalse(((Label) scene.lookup("#authError")).getText().isBlank());
                scene.addPostLayoutPulseListener(afterLayout);
                Platform.requestNextPulse();
                return null;
            });
            laidOut.get(10, TimeUnit.SECONDS);
            fx(() -> {
                stage.getScene().removePostLayoutPulseListener(afterLayout);
                var card = (javafx.scene.layout.VBox) stage.getScene().lookup(".card");
                assertTrue(card.getHeight() >= card.prefHeight(card.getWidth()) - 1, "Cartão não deve comprimir/cortar os textos");
                var scroll = (ScrollPane) stage.getScene().lookup("#loginScroll");
                assertTrue(scroll.getContent().getLayoutBounds().getHeight() > scroll.getViewportBounds().getHeight(),
                        "A janela mínima deve exercitar rolagem com diagnóstico expandido");
                snapshot(stage, "login-small-connection-error");
                scroll.setVvalue(1); stage.getScene().getRoot().layout();
                var viewport = scroll.lookup(".viewport");
                var visible = viewport.localToScene(viewport.getLayoutBounds());
                var last = card.getChildren().getLast();
                var notice = last.localToScene(last.getLayoutBounds());
                assertTrue(notice.getMinY() >= visible.getMinY() && notice.getMaxY() <= visible.getMaxY() + 1, "Aviso final deve ser alcançável pela rolagem");
                snapshot(stage, "login-small-scrolled");
                return null;
            });
        } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
    }
    @Test void loginComposeAndReceiveThroughRealControls() throws Exception {
        var events = new CopyOnWriteArrayList<Event>();
        try (var server = ChatServer.localAccounts(temporary.resolve("accounts.db"), 0).start(); var peer = new ChatConnection(events::add, state -> {});
             var accounts = new AccountAccess()) {
            String address = "ws://localhost:" + server.port();
            var peerSession = accounts.authenticate(address, "Pessoa", PASSWORD, true).get(10, TimeUnit.SECONDS);
            peer.connect(address, peerSession.username(), peerSession.token());
            var app = new ChatApp();
            Stage stage = fx(() -> { var window = new Stage(); app.start(window); return window; });
            try {
                fx(() -> {
                    var scene = stage.getScene();
                    var background = (PixelBackdrop) scene.lookup("#pixelBackdrop");
                    assertEquals(PixelBackdrop.View.LOGIN, background.view());
                    var effects = (ToggleButton) scene.lookup("#authMotion");
                    if (effects.isSelected()) effects.fire();
                    assertTrue(background.isRunning());
                    snapshot(stage, "login");
                    ((ToggleButton) scene.lookup("#registerTab")).fire();
                    assertEquals(PixelBackdrop.View.REGISTER, background.view());
                    assertTrue(background.isRunning());
                    effects.fire();
                    assertFalse(background.isRunning());
                    effects.fire();
                    snapshot(stage, "registration");
                    fillAccount(stage, "TesteUI", PASSWORD, server.port(), true); return null;
                });
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!fx(() -> stage.getScene().lookup("#composer") instanceof TextArea c && !c.isDisabled()) && System.nanoTime() < end) Thread.sleep(50);
                fx(() -> {
                    var send = (Button) stage.getScene().lookup("#send");
                    assertNotNull(send); assertTrue(send.isDisabled());
                    ((TextArea) stage.getScene().lookup("#composer")).setText("Olá pela interface 👋");
                    send.fire(); return null;
                });
                ChatConnectionTest.await(() -> events.stream().anyMatch(e -> "message".equals(e.type()) && "Olá pela interface 👋".equals(e.text())));
                peer.send("Resposta recebida!");
                boolean received = false;
                while (!received && System.nanoTime() < end) {
                    received = fx(() -> ((ListView<?>) stage.getScene().lookup("#messages")).getItems().toString().contains("Resposta recebida!"));
                    Thread.sleep(30);
                }
                assertTrue(received);
                fx(() -> {
                    ((TextArea) stage.getScene().lookup("#composer")).setText("Rascunho no geral");
                    ((TextField) stage.getScene().lookup("#channelName")).setText("porto");
                    ((Button) stage.getScene().lookup("#createChannel")).fire(); return null;
                });
                long channelEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!fx(() -> "#porto".equals(((Label) stage.getScene().lookup("#channelHeading")).getText())) && System.nanoTime() < channelEnd) Thread.sleep(30);
                fx(() -> {
                    assertEquals("#porto", ((Label) stage.getScene().lookup("#channelHeading")).getText());
                    assertFalse(((ListView<?>) stage.getScene().lookup("#messages")).getItems().toString().contains("Resposta recebida!"), "Trocar canal limpa histórico anterior");
                    return null;
                });
                peer.joinChannel("porto");
                ChatConnectionTest.await(() -> events.stream().anyMatch(e -> "joined".equals(e.type()) && "porto".equals(e.channel())));
                peer.send("Bem-vindo a bordo. Vamos conversar? ⛵");
                boolean channelReceived = false;
                while (!channelReceived && System.nanoTime() < channelEnd) {
                    channelReceived = fx(() -> ((ListView<?>) stage.getScene().lookup("#messages")).getItems().toString().contains("Bem-vindo a bordo")); Thread.sleep(30);
                }
                assertTrue(channelReceived);
                fx(() -> { ((ListView<String>) stage.getScene().lookup("#channels")).getSelectionModel().select("geral"); return null; });
                awaitFx(() -> "#geral".equals(((Label) stage.getScene().lookup("#channelHeading")).getText()));
                fx(() -> {
                    assertEquals("Rascunho no geral", ((TextArea) stage.getScene().lookup("#composer")).getText());
                    assertTrue(((ListView<?>) stage.getScene().lookup("#messages")).getItems().toString().contains("Resposta recebida!"));
                    ((ListView<String>) stage.getScene().lookup("#channels")).getSelectionModel().select("porto"); return null;
                });
                awaitFx(() -> "#porto".equals(((Label) stage.getScene().lookup("#channelHeading")).getText()));
                fx(() -> { ((TextArea) stage.getScene().lookup("#composer")).setText("Tudo certo por aqui. Mar tranquilo! 🌊"); ((Button) stage.getScene().lookup("#send")).fire(); return null; });
                ChatConnectionTest.await(() -> events.stream().anyMatch(e -> "message".equals(e.type()) && "Tudo certo por aqui. Mar tranquilo! 🌊".equals(e.text())));
                fx(() -> { snapshot(stage, "conversation"); stage.setWidth(900); stage.setHeight(720); return null; });
                fx(() -> { snapshot(stage, "conversation-small");
                    var button = (Button) stage.getScene().lookup("#send");
                    assertTrue(button.getWidth() >= button.prefWidth(-1) - 1, "Enviar deve caber inteiro na largura mínima"); return null; });
                // Feed deterministic server events to exercise the real virtualized list and recovery controls.
                fx(() -> {
                    var handler = ChatApp.class.getDeclaredMethod("onEvent", Event.class); handler.setAccessible(true);
                    for (int i = 0; i < 40; i++) handler.invoke(app, new Event("message", "scroll-" + i, "Pessoa", "Mensagem de leitura " + i, null, null, null, null, "porto"));
                    return null;
                });
                fx(() -> {
                    var list = (ListView<?>) stage.getScene().lookup("#messages"); list.applyCss(); list.layout();
                    var bar = list.lookupAll(".scroll-bar").stream().filter(n -> n instanceof ScrollBar b && b.getOrientation() == javafx.geometry.Orientation.VERTICAL).map(n -> (ScrollBar) n).findFirst().orElseThrow();
                    bar.setValue(bar.getMin()); return null;
                });
                fx(() -> {
                    var handler = ChatApp.class.getDeclaredMethod("onEvent", Event.class); handler.setAccessible(true);
                    handler.invoke(app, new Event("message", "scroll-new", "Pessoa", "Mensagem nova enquanto leio acima", null, null, null, null, "porto"));
                    assertTrue(stage.getScene().lookup("#newMessages").isVisible(), "Avisar sem arrastar a leitura");
                    ((Button) stage.getScene().lookup("#newMessages")).fire();
                    handler.invoke(app, new Event("pending", "recover-test", "TesteUI", "Texto recuperável", null, null, null, null, "porto"));
                    handler.invoke(app, new Event("error", "recover-test", null, "Não enviada", null, null, null, null, "porto"));
                    return null;
                });
                fx(() -> {
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    ((TextArea) stage.getScene().lookup("#composer")).setText("Rascunho preservado");
                    var recover = stage.getScene().getRoot().lookupAll(".button").stream().filter(n -> n instanceof Button b && "Recuperar texto".equals(b.getText())).map(n -> (Button) n).findFirst().orElseThrow();
                    recover.fire();
                    assertEquals("Rascunho preservado", ((TextArea) stage.getScene().lookup("#composer")).getText());
                    assertEquals("Texto recuperável", javafx.scene.input.Clipboard.getSystemClipboard().getString());
                    return null;
                });
                var backdrop = fx(() -> (PixelBackdrop) stage.getScene().lookup("#pixelBackdrop"));
                assertEquals(PixelBackdrop.View.CHAT, fx(backdrop::view));
                assertTrue(fx(backdrop::isRunning));
                long before = fx(backdrop::frameCount);
                Thread.sleep(180);
                assertTrue(fx(backdrop::frameCount) > before, "Fundo deve animar na sala conectada");
                fx(() -> { ((ToggleButton) stage.getScene().lookup("#motionToggle")).fire(); return null; });
                assertFalse(fx(backdrop::isRunning));
                long frozen = fx(backdrop::frameCount);
                Thread.sleep(180);
                assertEquals(frozen, fx(backdrop::frameCount));
                fx(() -> { stage.setIconified(true); stage.setIconified(false); return null; });
                assertFalse(fx(backdrop::isRunning), "Restaurar janela deve preservar pausa do usuário");
                fx(() -> { ((ToggleButton) stage.getScene().lookup("#motionToggle")).fire(); return null; });
                assertTrue(fx(backdrop::isRunning));
                fx(() -> { stage.setIconified(true); return null; });
                assertFalse(fx(backdrop::isRunning), "Janela minimizada não deve renderizar frames");
                fx(() -> { stage.setIconified(false); return null; });
                assertTrue(fx(backdrop::isRunning));
                fx(() -> {
                    stage.getScene().getRoot().lookupAll(".button").stream()
                        .filter(n -> n instanceof Button b && b.getText().equals("Sair da conta"))
                        .map(n -> (Button) n).findFirst().orElseThrow().fire();
                    assertEquals(PixelBackdrop.View.LOGIN, backdrop.view());
                    assertTrue(backdrop.isRunning(), "Sair restaura a paisagem animada de abertura");
                    app.stop(); backdrop.setActive(true);
                    assertFalse(backdrop.isRunning(), "Timer fechado não deve reiniciar"); return null;
                });
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!fx(condition) && System.nanoTime() < deadline) Thread.sleep(30);
        assertTrue(fx(condition));
    }

    @Test void explicitLoginAfterFatalErrorDoesNotRestorePreviousChannelDraftOrHistory() throws Exception {
        try (var host = ChatServer.localAccounts(temporary.resolve("accounts.db"), 0).start()) {
            var app = new ChatApp();
            Stage stage = fx(() -> { var window = new Stage(); app.start(window); return window; });
            try {
                fx(() -> {
                    fillAccount(stage, "SessaoAntiga", PASSWORD, host.port(), true);
                    return null;
                });
                awaitFx(() -> stage.getScene().lookup("#composer") instanceof TextArea composer && !composer.isDisabled());
                fx(() -> {
                    ((TextField) stage.getScene().lookup("#channelName")).setText("porto");
                    ((Button) stage.getScene().lookup("#createChannel")).fire();
                    return null;
                });
                awaitFx(() -> "#porto".equals(((Label) stage.getScene().lookup("#channelHeading")).getText())
                        && !((TextArea) stage.getScene().lookup("#composer")).isDisabled());
                fx(() -> {
                    ((TextArea) stage.getScene().lookup("#composer")).setText("Mensagem apenas da sessão antiga");
                    ((Button) stage.getScene().lookup("#send")).fire();
                    return null;
                });
                awaitFx(() -> ((ListView<?>) stage.getScene().lookup("#messages")).getItems().toString()
                        .contains("Confirmada pelo servidor"));
                fx(() -> {
                    ((TextArea) stage.getScene().lookup("#composer")).setText("Rascunho privado da sessão antiga");
                    var handler = ChatApp.class.getDeclaredMethod("onEvent", Event.class);
                    handler.setAccessible(true);
                    handler.invoke(app, new Event("error", "rate", null, "Sessão encerrada pelo servidor", null, null, null, null, "porto"));
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    fillAccount(stage, "SessaoNova", PASSWORD, host.port(), true);
                    return null;
                });
                awaitFx(() -> stage.getScene().lookup("#composer") instanceof TextArea composer && !composer.isDisabled()
                        && "#geral".equals(((Label) stage.getScene().lookup("#channelHeading")).getText()));
                fx(() -> {
                    ((ListView<String>) stage.getScene().lookup("#channels")).getSelectionModel().select("porto");
                    return null;
                });
                awaitFx(() -> "#porto".equals(((Label) stage.getScene().lookup("#channelHeading")).getText())
                        && !((TextArea) stage.getScene().lookup("#composer")).isDisabled());
                fx(() -> {
                    assertEquals("", ((TextArea) stage.getScene().lookup("#composer")).getText(),
                            "Uma nova entrada explícita não deve restaurar rascunhos da sessão encerrada");
                    assertFalse(((ListView<?>) stage.getScene().lookup("#messages")).getItems().toString()
                            .contains("Mensagem apenas da sessão antiga"), "O histórico anterior não deve reaparecer");
                    return null;
                });
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    @Test void returningAccountCanRetryWrongPasswordAndSharePublicInvite() throws Exception {
        try (var host = ChatServer.localAccounts(temporary.resolve("accounts.db"), 0).start();
             var accounts = new AccountAccess()) {
            String address = "ws://localhost:" + host.port();
            var first = accounts.authenticate(address, "Marinheiro", PASSWORD, true).get(10, TimeUnit.SECONDS);
            accounts.logout(address, first.token());
            var app = new ChatApp();
            Stage stage = fx(() -> { var w = new Stage(); app.start(w); return w; });
            try {
                fx(() -> { fillAccount(stage, "marinheiro", "senha-incorreta", host.port(), false); return null; });
                awaitFx(() -> stage.getScene().lookup("#join") instanceof Button b && !b.isDisabled());
                fx(() -> {
                    assertFalse(((Label) stage.getScene().lookup("#authError")).getText().isBlank());
                    assertTrue(((PasswordField) stage.getScene().lookup("#password")).getText().isEmpty());
                    fillAccount(stage, "marinheiro", PASSWORD, host.port(), false);
                    return null;
                });
                awaitFx(() -> stage.getScene().lookup("#send") != null);
                fx(() -> {
                    ((Button) stage.getScene().lookup("#copyInvite")).fire();
                    String invite = javafx.scene.input.Clipboard.getSystemClipboard().getString();
                    assertFalse(invite.contains(PASSWORD)); assertFalse(invite.contains(first.token()));
                    assertEquals("@Marinheiro", ((Label) stage.getScene().lookup("#accountIdentity")).getText()); assertTrue(invite.contains("localhost:" + host.port()));
                    TextArea composer = (TextArea) stage.getScene().lookup("#composer");
                    assertTrue(((Button) stage.getScene().lookup("#send")).isDisabled());
                    composer.setText("🌊".repeat(2000));
                    assertFalse(((Button) stage.getScene().lookup("#send")).isDisabled());
                    composer.appendText("a"); assertTrue(((Button) stage.getScene().lookup("#send")).isDisabled());
                    return null;
                });
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    @Test void cancellingPendingSignupRevokesSessionThatArrivesLater() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        String token = "session-returned-after-cancel";
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        http.createContext("/accounts/register", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                received.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) return; }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                byte[] result = ("{\"username\":\"Marina\",\"token\":\"" + token + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, result.length);
                exchange.getResponseBody().write(result);
            }
        });
        http.createContext("/accounts/logout", exchange -> {
            try (exchange) {
                if (("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) revoked.countDown();
                exchange.sendResponseHeaders(204, -1);
            }
        });
        http.start();
        var app = new ChatApp();
        Stage stage = fx(() -> { var window = new Stage(); app.start(window); return window; });
        try {
            fx(() -> { fillAccount(stage, "Marina", PASSWORD, http.getAddress().getPort(), true); return null; });
            assertTrue(received.await(10, TimeUnit.SECONDS));
            fx(() -> {
                ((Button) stage.getScene().lookup("#cancelAuth")).fire();
                assertFalse(stage.getScene().lookup("#join").isDisabled());
                assertTrue(((PasswordField) stage.getScene().lookup("#password")).getText().isEmpty());
                return null;
            });
            release.countDown();
            assertTrue(revoked.await(10, TimeUnit.SECONDS));
            assertNull(fx(() -> stage.getScene().lookup("#composer")));
        } finally {
            release.countDown();
            fx(() -> { app.stop(); stage.close(); return null; });
            http.stop(0);
        }
    }
}
