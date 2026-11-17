package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import br.com.batepapo.server.ChatServer;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class ChatAppTest {
    static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
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
    @Test void connectionNoticeIsReachableAtMinimumWindowSize() throws Exception {
        var app = new ChatApp();
        Stage stage = fx(() -> { var window = new Stage(); app.start(window); window.setWidth(900); window.setHeight(720); return window; });
        try {
            fx(() -> {
                ((TextField) stage.getScene().lookup("#nickname")).setText("Teste UI");
                ((TextField) ((TitledPane) stage.getScene().lookup("#serverOptions")).getContent().lookup("#server")).setText("ws://localhost:1");
                ((PasswordField) stage.getScene().lookup("#accessCode")).setText("test-only-invalid-endpoint-code");
                ((Button) stage.getScene().lookup("#join")).fire(); return null;
            });
            // Exercise the longest connection-state notice without depending on retry timing.
            fx(() -> {
                var error = (Label) stage.getScene().lookup(".error");
                error.setText("Tentando conectar. Confira o endereço ou aguarde o servidor iniciar.");
                snapshot(stage, "login-small-connecting");
                var card = (javafx.scene.layout.VBox) stage.getScene().lookup(".card");
                assertTrue(card.getHeight() >= card.prefHeight(card.getWidth()) - 1, "Cartão não deve comprimir/cortar os textos");
                var scroll = (ScrollPane) stage.getScene().lookup("#loginScroll");
                assertNotNull(scroll, "Entrada deve permitir rolagem quando houver avisos");
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
        String token = "ui-integration-access-code-123456";
        var events = new CopyOnWriteArrayList<Event>();
        try (var server = new ChatServer(token, 0).start(); var peer = new ChatConnection(events::add, state -> {})) {
            peer.connect("ws://localhost:" + server.port(), "Pessoa", token);
            var app = new ChatApp();
            Stage stage = fx(() -> { var window = new Stage(); app.start(window); return window; });
            try {
                fx(() -> {
                    assertNotNull(stage.getScene().lookup("#pixelBackdrop"), "Fundo retrô deve existir");
                    assertNotNull(stage.getScene().lookup("#motionToggle"), "Animação deve poder ser pausada");
                    assertNotNull(stage.getScene().lookup("#channels"), "Lista de canais deve existir");
                    assertTrue(((Button) stage.getScene().lookup("#createChannel")).isDisabled(), "Criar canal exige conexão");
                    assertFalse(((PixelBackdrop) stage.getScene().lookup("#pixelBackdrop")).isRunning());
                    assertTrue(javafx.scene.text.Font.getFamilies().contains("VT323"), "Fonte deve estar embutida");
                    snapshot(stage, "login");
                    assertNotNull(stage.getScene().lookup("#nickname"), "Campo de apelido deve existir");
                    ((TextField) stage.getScene().lookup("#nickname")).setText("Teste UI");
                    ((TextField) ((TitledPane) stage.getScene().lookup("#serverOptions")).getContent().lookup("#server")).setText("ws://localhost:" + server.port());
                    ((PasswordField) stage.getScene().lookup("#accessCode")).setText(token);
                    ((Button) stage.getScene().lookup("#join")).fire(); return null;
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
                    handler.invoke(app, new Event("pending", "recover-test", "Teste UI", "Texto recuperável", null, null, null, null, "porto"));
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
                        .filter(n -> n instanceof Button b && b.getText().equals("Sair da sala"))
                        .map(n -> (Button) n).findFirst().orElseThrow().fire();
                    assertFalse(backdrop.isRunning(), "Sair deve parar o timer");
                    app.stop(); backdrop.setActive(true);
                    assertFalse(backdrop.isRunning(), "Timer fechado não deve reiniciar"); return null;
                });
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }

    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(condition) && System.nanoTime() < deadline) Thread.sleep(30);
        assertTrue(fx(condition));
    }

    @Test void explicitLoginAfterFatalErrorDoesNotRestorePreviousChannelDraftOrHistory() throws Exception {
        String token = "fresh-session-access-code-123456";
        try (var host = new ChatServer(token, 0).start()) {
            var app = new ChatApp();
            Stage stage = fx(() -> { var window = new Stage(); app.start(window); return window; });
            try {
                fx(() -> {
                    ((TextField) stage.getScene().lookup("#nickname")).setText("Sessao Antiga");
                    ((TextField) ((TitledPane) stage.getScene().lookup("#serverOptions")).getContent().lookup("#server"))
                            .setText("ws://localhost:" + host.port());
                    ((PasswordField) stage.getScene().lookup("#accessCode")).setText(token);
                    ((Button) stage.getScene().lookup("#join")).fire();
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
                    ((TextField) stage.getScene().lookup("#nickname")).setText("Sessao Nova");
                    ((PasswordField) stage.getScene().lookup("#accessCode")).setText(token);
                    ((Button) stage.getScene().lookup("#join")).fire();
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

    @Test void duplicateNicknameCanRetryWithoutRetypingAccessCode() throws Exception {
        String token = "nickname-test-access-code-123456";
        var events = new CopyOnWriteArrayList<Event>();
        try (var host = new ChatServer(token, 0).start(); var peer = new ChatConnection(events::add, s -> {})) {
            peer.connect("ws://localhost:" + host.port(), "Marinheiro", token);
            ChatConnectionTest.await(() -> events.stream().anyMatch(e -> "welcome".equals(e.type())));
            var app = new ChatApp();
            Stage stage = fx(() -> { var w = new Stage(); app.start(w); return w; });
            try {
                fx(() -> {
                    assertFalse(((TextField) stage.getScene().lookup("#nickname")).getText().isBlank(), "Entrada deve sugerir apelido válido");
                    ((TextField) stage.getScene().lookup("#nickname")).setText("Marinheiro");
                    ((TextField) ((TitledPane) stage.getScene().lookup("#serverOptions")).getContent().lookup("#server")).setText("ws://localhost:" + host.port());
                    ((PasswordField) stage.getScene().lookup("#accessCode")).setText(token);
                    ((Button) stage.getScene().lookup("#join")).fire(); return null;
                });
                awaitFx(() -> stage.getScene().lookup("#join") instanceof Button b && !b.isDisabled());
                fx(() -> {
                    assertEquals(token, ((PasswordField) stage.getScene().lookup("#accessCode")).getText());
                    ((Button) stage.getScene().lookup("#suggestNickname")).fire();
                    assertNotEquals("Marinheiro", ((TextField) stage.getScene().lookup("#nickname")).getText());
                    ((Button) stage.getScene().lookup("#join")).fire(); return null;
                });
                awaitFx(() -> stage.getScene().lookup("#send") != null);
                fx(() -> {
                    ((Button) stage.getScene().lookup("#copyInvite")).fire();
                    String invite = javafx.scene.input.Clipboard.getSystemClipboard().getString();
                    assertFalse(invite.contains(token)); assertTrue(invite.contains("localhost:" + host.port()));
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
}
