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
    @Test void loginComposeAndReceiveThroughRealControls() throws Exception {
        String token = "ui-integration-access-code-123456";
        var events = new CopyOnWriteArrayList<Event>();
        try (var server = new ChatServer(token, 0).start(); var peer = new ChatConnection(events::add, state -> {})) {
            peer.connect("ws://localhost:" + server.port(), "Pessoa", token);
            var app = new ChatApp();
            Stage stage = fx(() -> { var window = new Stage(); app.start(window); return window; });
            try {
                fx(() -> {
                    assertNotNull(stage.getScene().lookup("#nickname"), "Campo de apelido deve existir");
                    ((TextField) stage.getScene().lookup("#nickname")).setText("Teste UI");
                    ((TextField) stage.getScene().lookup("#server")).setText("ws://localhost:" + server.port());
                    ((PasswordField) stage.getScene().lookup("#accessCode")).setText(token);
                    ((Button) stage.getScene().lookup("#join")).fire(); return null;
                });
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!fx(() -> stage.getScene().lookup("#send") instanceof Button b && !b.isDisabled()) && System.nanoTime() < end) Thread.sleep(50);
                fx(() -> {
                    var send = (Button) stage.getScene().lookup("#send");
                    assertNotNull(send); assertFalse(send.isDisabled());
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
            } finally { fx(() -> { app.stop(); stage.close(); return null; }); }
        }
    }
}
