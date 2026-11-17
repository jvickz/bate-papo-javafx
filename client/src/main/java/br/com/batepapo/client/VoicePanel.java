package br.com.batepapo.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class VoicePanel extends HBox {
    private final Button invite = button("voiceCall", "Ligar");
    private final Button accept = button("voiceAccept", "Aceitar");
    private final Button reject = button("voiceReject", "Recusar");
    private final Button end = button("voiceEnd", "Encerrar");
    private final ToggleButton mute = new ToggleButton("Silenciar");
    private final Label status = new Label("Conecte-se para ligar.");
    private final Label duration = new Label();
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateDuration()));
    private long connectedAt;
    private boolean connected;

    VoicePanel() {
        super(8);
        setId("voicePanel");
        getStyleClass().add("voice-panel");
        setAlignment(Pos.CENTER_LEFT);
        status.setId("voiceStatus");
        status.getStyleClass().add("voice-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        duration.setId("voiceDuration");
        duration.getStyleClass().add("voice-duration");
        duration.setMinWidth(Region.USE_PREF_SIZE);
        mute.setId("voiceMute");
        mute.getStyleClass().add("voice-action");
        mute.setMinWidth(Region.USE_PREF_SIZE);
        accept.getStyleClass().add("voice-accept");
        getChildren().addAll(invite, status, duration, accept, reject, mute, end);
        clock.setCycleCount(Timeline.INDEFINITE);
        render(new VoiceController.View(VoiceController.Phase.IDLE, false, false, status.getText()));
    }

    void bind(VoiceController controller) {
        invite.setOnAction(e -> controller.invite());
        accept.setOnAction(e -> controller.accept());
        reject.setOnAction(e -> controller.reject());
        end.setOnAction(e -> controller.end());
        mute.setOnAction(e -> controller.toggleMute());
    }

    void render(VoiceController.View view) {
        var phase = view.phase();
        boolean idle = phase == VoiceController.Phase.IDLE;
        boolean incoming = phase == VoiceController.Phase.INCOMING;
        boolean outgoing = phase == VoiceController.Phase.OUTGOING;
        boolean talking = phase == VoiceController.Phase.CONNECTED;
        if (!status.getText().equals(view.text())) {
            status.setText(view.text());
            status.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
        }
        show(invite, idle);
        invite.setDisable(!view.canInvite());
        show(accept, incoming);
        show(reject, incoming);
        show(mute, talking);
        mute.setSelected(view.muted());
        mute.setText(view.muted() ? "Reativar microfone" : "Silenciar");
        mute.setAccessibleText(view.muted() ? "Reativar microfone" : "Silenciar microfone");
        show(end, !idle && !incoming);
        end.setText(outgoing ? "Cancelar" : "Encerrar");
        end.setAccessibleText(outgoing ? "Cancelar chamada" : "Encerrar chamada");
        show(duration, talking);
        if (talking && !connected) {
            connectedAt = System.nanoTime();
            updateDuration();
            clock.playFromStart();
        } else if (!talking && connected) {
            clock.stop();
        }
        connected = talking;
    }

    private void updateDuration() {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - connectedAt);
        String text = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        duration.setText(text);
        duration.setAccessibleText("Duração da chamada " + text);
    }

    private static Button button(String id, String label) {
        var button = new Button(label);
        button.setId(id);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.getStyleClass().add("voice-action");
        return button;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
