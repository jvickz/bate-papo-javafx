package br.com.batepapo.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class VoicePanel extends VBox {
    private final Button invite = button("voiceCall", "Ligar");
    private final Button accept = button("voiceAccept", "Aceitar");
    private final Button reject = button("voiceReject", "Recusar");
    private final Button end = button("voiceEnd", "Encerrar");
    private final ToggleButton mute = new ToggleButton("Silenciar");
    private final Label status = new Label("Conecte-se para ligar.");
    private final Label duration = new Label();
    private final Button share = button("screenShare", "Compartilhar tela");
    private final Button watch = button("screenWatch", "Ver tela");
    private final HBox screenActions = new HBox(8, share, watch);
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateDuration()));
    private long connectedAt;
    private boolean connected;
    private VoiceController controller;
    private VoiceController.View current;
    private ChoiceDialog<VoiceEngine.Screen> chooser;
    private ScreenWindow viewer;

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
        var callActions = new HBox(8, invite, status, duration, accept, reject, mute, end);
        callActions.setAlignment(Pos.CENTER_LEFT);
        screenActions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(callActions, screenActions);
        clock.setCycleCount(Timeline.INDEFINITE);
        render(new VoiceController.View(VoiceController.Phase.IDLE, false, false, status.getText(),
                null, null, false, VoiceController.Sharing.OFF, false));
    }

    void bind(VoiceController controller) {
        this.controller = controller;
        invite.setOnAction(e -> controller.invite());
        accept.setOnAction(e -> controller.accept());
        reject.setOnAction(e -> controller.reject());
        end.setOnAction(e -> controller.end());
        mute.setOnAction(e -> controller.toggleMute());
        share.setOnAction(e -> {
            if (current.sharing() == VoiceController.Sharing.OFF) controller.chooseScreen(this::chooseDisplay);
            else controller.stopScreen();
        });
        watch.setOnAction(e -> showViewer());
    }

    void render(VoiceController.View view) {
        boolean sameCall = current != null && java.util.Objects.equals(view.callId(), current.callId());
        current = view;
        if (chooser != null && (!sameCall || view.sharing() != VoiceController.Sharing.CHOOSING)) chooser.close();
        if (viewer != null && (!sameCall || !view.receiving())) viewer.close();
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
        show(screenActions, talking && view.canShare());
        show(watch, talking && view.receiving());
        watch.setText("Ver tela de " + view.peer());
        share.setDisable(view.sharing() == VoiceController.Sharing.CHOOSING || view.sharing() == VoiceController.Sharing.STOPPING);
        share.setText(switch (view.sharing()) {
            case OFF -> "Compartilhar tela";
            case CHOOSING -> "Escolhendo tela…";
            case STARTING -> "Cancelar compartilhamento";
            case ON -> "Parar compartilhamento";
            case STOPPING -> "Parando…";
        });
        if (talking && !connected) {
            connectedAt = System.nanoTime();
            updateDuration();
            clock.playFromStart();
        } else if (!talking && connected) {
            clock.stop();
        }
        connected = talking;
    }

    private void chooseDisplay(VoiceController.ScreenChoice choice) {
        var screens = choice.screens();
        var dialog = new ChoiceDialog<>(screens.getFirst(), screens);
        chooser = dialog;
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Wavecove");
        dialog.setHeaderText("Compartilhar tela com " + choice.peer());
        dialog.setContentText("Monitor");
        dialog.getDialogPane().setId("screenPicker");
        dialog.getDialogPane().setAccessibleText("Selecione o monitor que ficará visível durante a chamada.");
        dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("screen-picker");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Compartilhar");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Cancelar");
        dialog.setOnHidden(e -> {
            chooser = null;
            controller.confirmScreen(choice.callId(), dialog.getResult());
        });
        dialog.show();
    }

    private void showViewer() {
        if (viewer != null) { viewer.focus(); return; }
        var frames = controller.openViewer(current.callId());
        if (frames == null) return;
        viewer = new ScreenWindow(getScene().getWindow(), current.peer(), frames, () -> {
            viewer = null;
            controller.closeViewer(frames);
        });
        viewer.show();
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
