package br.com.batepapo.client;

import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Displays the remote monitor without interrupting the call or blocking the chat window. */
final class ScreenWindow {
    private final Stage stage = new Stage();
    private final ImageView image = new ImageView();
    private final Label waiting = new Label("Aguardando imagem…");
    private final Label size = new Label();
    private final ToggleButton original = new ToggleButton("Tamanho original");
    private final StackPane picture = new StackPane(image, waiting);
    private final ScrollPane scroll = new ScrollPane(picture);
    private WritableImage surface;

    ScreenWindow(Window owner, String peer, ScreenFrames frames, Runnable onClose) {
        stage.initOwner(owner);
        stage.setTitle("Wavecove · Tela de " + peer);
        if (owner instanceof Stage parent) stage.getIcons().addAll(parent.getIcons());
        stage.setMinWidth(470);
        stage.setMinHeight(320);
        var full = new Button("Tela cheia");
        full.setId("screenFullscreen");
        original.setId("screenOriginal");
        original.setOnAction(e -> layoutImage());
        full.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));
        stage.fullScreenProperty().addListener((o, before, after) ->
                full.setText(after ? "Sair da tela cheia" : "Tela cheia"));
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var toolbar = new HBox(10, size, spacer, original, full);
        toolbar.getStyleClass().add("screen-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        picture.getStyleClass().add("screen-picture");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.viewportBoundsProperty().addListener((o, old, viewport) -> layoutImage());
        image.setPreserveRatio(true);
        image.setId("screenImage");
        image.setAccessibleText("Tela compartilhada por " + peer);
        var root = new BorderPane(scroll);
        root.setTop(toolbar);
        root.getStyleClass().add("screen-viewer");
        var scene = new Scene(root, Math.max(540, owner.getWidth() * 0.88), Math.max(360, owner.getHeight() * 0.84));
        scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F11) { stage.setFullScreen(!stage.isFullScreen()); e.consume(); }
        });
        stage.setScene(scene);
        var pulse = new AnimationTimer() {
            @Override public void handle(long now) {
                if (stage.isIconified()) return;
                try (var frame = frames.take()) {
                    if (frame != null) showFrame(frame);
                }
            }
        };
        stage.setOnShown(e -> pulse.start());
        stage.setOnHidden(e -> {
            pulse.stop();
            image.setImage(null);
            surface = null;
            onClose.run();
        });
    }

    void show() { stage.show(); WindowsTitleBar.apply(stage); }
    void focus() { stage.setIconified(false); stage.toFront(); stage.requestFocus(); }
    void close() { stage.close(); }

    private void showFrame(VoiceEngine.Frame frame) {
        int width = frame.width(), height = frame.height();
        if (surface == null || surface.getWidth() != width || surface.getHeight() != height) {
            surface = new WritableImage(width, height);
            image.setImage(surface);
            waiting.setVisible(false);
            size.setText(width + " × " + height);
            layoutImage();
        }
        surface.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getByteBgraPreInstance(),
                frame.bgra(), 0, width * 4);
    }

    private void layoutImage() {
        if (surface == null) return;
        boolean actual = original.isSelected();
        picture.setMinSize(actual ? surface.getWidth() : 0, actual ? surface.getHeight() : 0);
        image.setFitWidth(actual ? surface.getWidth() : Math.max(1, scroll.getViewportBounds().getWidth()));
        image.setFitHeight(actual ? surface.getHeight() : Math.max(1, scroll.getViewportBounds().getHeight()));
    }
}
