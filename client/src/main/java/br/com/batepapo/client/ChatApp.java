package br.com.batepapo.client;

import br.com.batepapo.protocol.*;
import javafx.application.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ChatApp extends Application {
    private final BorderPane content = new BorderPane();
    private final Label status = label("Desconectado", "pill");
    private final Label error = label("", "error");
    private final Label hint = label("", "error");
    private final TextField nickname = new TextField();
    private final TextField server = new TextField();
    private final PasswordField accessCode = new PasswordField();
    private final Button join = new Button("Entrar na conversa  →");
    private final Button cancel = new Button("Cancelar conexão");
    private final TextArea composer = new TextArea();
    private final Button send = new Button("Enviar  →");
    private final ObservableList<Line> history = FXCollections.observableArrayList();
    private final ListView<Line> messages = new ListView<>(history);
    private final ListView<String> participants = new ListView<>();
    private final ListView<String> channels = new ListView<>();
    private final TextField channelName = new TextField();
    private final Button createChannel = new Button("+");
    private final Label channelHeading = label("#geral", "title");
    private String currentChannel = "geral";
    private boolean updatingChannels;
    private final Label peopleCount = label("PARTICIPANTES", "eyebrow");
    private ChatConnection connection;
    private Stage stage;
    private String myName;
    private long epoch;
    private boolean inRoom;
    private boolean online;
    private final PixelBackdrop backdrop = new PixelBackdrop();
    private final ToggleButton motion = new ToggleButton("EFEITOS: ON");
    record Line(String id, String name, String text, String time, String delivery) {}

    @Override public void start(Stage stage) {
        this.stage = stage;
        Font.loadFont(Objects.requireNonNull(getClass().getResource("/fonts/VT323-Regular.ttf")).toExternalForm(), 22);
        nickname.setId("nickname"); nickname.setPromptText("Como você quer ser chamado?");
        server.setId("server"); server.setPromptText("https://seu-servidor.onrender.com");
        server.setText(System.getenv().getOrDefault("BATEPAPO_SERVER", "https://bate-papo-java.onrender.com"));
        accessCode.setId("accessCode"); accessCode.setPromptText("Código compartilhado com você");
        join.setId("join"); join.getStyleClass().add("primary"); join.setMaxWidth(Double.MAX_VALUE);
        join.setOnAction(e -> enter()); accessCode.setOnAction(e -> enter());
        cancel.setOnAction(e -> leave()); cancel.setVisible(false); cancel.setManaged(false);
        composer.setId("composer"); composer.setPromptText("Escreva uma mensagem…");
        composer.setWrapText(true); composer.setPrefRowCount(2);
        composer.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) { e.consume(); sendMessage(); } });
        send.setId("send"); send.getStyleClass().add("primary"); send.setOnAction(e -> sendMessage());
        send.setDisable(true);
        messages.setId("messages"); messages.getStyleClass().add("messages");
        messages.setCellFactory(view -> new MessageCell());
        messages.setPlaceholder(label("Sua próxima conversa começa com um oi.\nAs mensagens aparecem aqui.", "muted"));
        participants.setPlaceholder(label("Ninguém conectado", "muted"));

        var shell = new BorderPane(); shell.setLeft(sidebar()); shell.setCenter(content);
        shell.getStyleClass().add("terminal-shell");
        var root = new StackPane(backdrop, shell);
        motion.setId("motionToggle"); motion.getStyleClass().add("motion-toggle");
        motion.setAccessibleText("Pausar animação do fundo");
        motion.setOnAction(e -> {
            backdrop.setPaused(motion.isSelected());
            motion.setText(motion.isSelected() ? "EFEITOS: OFF" : "EFEITOS: ON");
            motion.setAccessibleText(motion.isSelected() ? "Retomar animação do fundo" : "Pausar animação do fundo");
        });
        channelHeading.setId("channelHeading"); channelHeading.setMaxWidth(300); channelHeading.setMinWidth(0);
        var heading = new VBox(4, channelHeading, label("UM MAR DE CONVERSAS", "eyebrow")); heading.setMinWidth(0);
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(12, heading, spacer, motion, status); header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("header");
        content.setTop(header); showLogin();
        var scene = new Scene(root, 1080, 760); scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/chat.css")).toExternalForm());
        stage.setScene(scene); stage.setTitle("Bate-papo"); stage.setMinWidth(900); stage.setMinHeight(720);
        stage.iconifiedProperty().addListener((o, before, after) -> refreshMotion());
        stage.showingProperty().addListener((o, before, after) -> refreshMotion());
        stage.setOnCloseRequest(e -> stop()); stage.show();
    }
    private VBox sidebar() {
        var subtitle = label("OUTRAS MARGENS.\nA MESMA CONVERSA.", "muted");
        channels.setId("channels"); channels.getStyleClass().add("channel-list"); channels.setFixedCellSize(28);
        channels.setPrefHeight(116); channels.setMinHeight(68); channels.setDisable(true);
        channels.setPlaceholder(label("Entre para ver canais", "muted"));
        channels.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(String value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : "# " + value); }
        });
        channels.getSelectionModel().selectedItemProperty().addListener((o, before, next) -> {
            if (!updatingChannels && next != null && !next.equals(currentChannel) && online) {
                try { connection.joinChannel(next); } catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); selectCurrentChannel(); }
            }
        });
        channelName.setId("channelName"); channelName.setPromptText("novo-canal"); channelName.getStyleClass().add("channel-name");
        channelName.setAccessibleText("Nome do novo canal"); channelName.setDisable(true);
        createChannel.setId("createChannel"); createChannel.getStyleClass().add("create-channel"); createChannel.setDisable(true);
        createChannel.setAccessibleText("Criar canal"); createChannel.setTooltip(new Tooltip("Criar canal e entrar. Use 2–24 letras, números ou hífens."));
        createChannel.setOnAction(e -> createChannel()); channelName.setOnAction(e -> createChannel());
        var createRow = new HBox(6, channelName, createChannel); createRow.getStyleClass().add("channel-create-row"); HBox.setHgrow(channelName, Priority.ALWAYS);
        var spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        participants.setPrefHeight(112); participants.setMinHeight(55);
        var another = new Button("OUTRA JANELA"); another.setId("openTestWindow"); another.getStyleClass().add("demo-button"); another.setMaxWidth(Double.MAX_VALUE);
        another.setTooltip(new Tooltip("Abrir uma segunda sessão para testar uma conversa"));
        another.setOnAction(e -> { var other = new ChatApp(); other.start(new Stage()); other.server.setText(server.getText()); other.nickname.setText("Teste 2"); });
        var box = new VBox(12, label("bate-papo_", "brand"), subtitle, new Separator(), label("[ CANAIS ]", "eyebrow"), channels, createRow,
                peopleCount, participants, spacer, another, label("WINDOWS / v1.2", "muted"));
        box.getStyleClass().add("sidebar"); box.setPrefWidth(224); return box;
    }
    private void createChannel() {
        if (connection == null || !online) return;
        try { connection.createChannel(channelName.getText()); hint.setText(""); }
        catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }
    private void selectCurrentChannel() {
        updatingChannels = true;
        try { channels.getSelectionModel().select(currentChannel); } finally { updatingChannels = false; }
    }
    private void confirmChannel(String channel) {
        String next = channel == null ? "geral" : channel;
        if (!next.equals(currentChannel)) { history.clear(); composer.clear(); participants.getItems().clear(); }
        currentChannel = next; channelHeading.setText("#" + next); channelHeading.setTooltip(new Tooltip("#" + next));
        selectCurrentChannel();
        if (inRoom) stage.setTitle("Bate-papo — " + myName + " · #" + currentChannel);
    }
    private void showLogin() {
        refreshMotion();
        content.setBottom(null);
        var explanation = label("Entre com seu apelido e o convite da sala.\nA outra pessoa usa o mesmo servidor e código.", "muted");
        error.setWrapText(true);
        var card = new VBox(14, label("[ INICIAR SESSÃO ]", "eyebrow"), label("Olá, visitante_", "hero"), explanation,
                field("SEU APELIDO", nickname), field("ENDEREÇO DO SERVIDOR", server), field("CÓDIGO DE ACESSO", accessCode),
                error, join, cancel, label("Quem tem o código pode entrar. Seu código não é salvo.", "muted"));
        card.getStyleClass().add("card"); card.setMaxWidth(510); card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setMinHeight(Region.USE_PREF_SIZE);
        var center = new StackPane(card); center.setPadding(new Insets(24)); center.setMinHeight(Region.USE_PREF_SIZE);
        var scroll = new ScrollPane(center); scroll.setId("loginScroll"); scroll.getStyleClass().add("login-scroll");
        scroll.setFitToWidth(true); scroll.setFitToHeight(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setCenter(scroll);
        join.setDisable(false); nickname.setDisable(false); server.setDisable(false); accessCode.setDisable(false);
        nickname.requestFocus();
    }
    private void enter() {
        if (join.isDisabled()) return;
        try {
            myName = Protocol.validateName(nickname.getText()); Protocol.serverUri(server.getText());
            if (accessCode.getText().isBlank()) throw new IllegalArgumentException("Informe o código de acesso da sala.");
            error.setText(""); hint.setText(""); history.clear();
            if (connection != null) connection.close();
            long current = ++epoch;
            connection = new ChatConnection(e -> Platform.runLater(() -> { if (current == epoch) onEvent(e); }),
                    s -> Platform.runLater(() -> { if (current == epoch) onState(s); }));
            connection.connect(server.getText(), myName, accessCode.getText());
            join.setDisable(true); nickname.setDisable(true); server.setDisable(true); accessCode.setDisable(true);
            cancel.setManaged(true); cancel.setVisible(true);
        } catch (IllegalArgumentException ex) { error.setText(ex.getMessage()); }
    }
    private void showChat() {
        inRoom = true; cancel.setVisible(false); cancel.setManaged(false); accessCode.clear();
        refreshMotion();
        content.setCenter(messages);
        var row = new HBox(12, composer, send); row.setAlignment(Pos.CENTER); HBox.setHgrow(composer, Priority.ALWAYS);
        var leave = new Button("Sair da sala"); leave.setOnAction(e -> leave());
        var tip = label("Enter envia · Shift+Enter quebra a linha\nAo trocar de canal, o histórico da tela é limpo", "muted");
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var footer = new HBox(10, tip, spacer, leave); footer.setAlignment(Pos.CENTER_LEFT);
        hint.setWrapText(true);
        var bottom = new VBox(8, row, hint, footer); bottom.getStyleClass().add("composer"); content.setBottom(bottom);
        stage.setTitle("Bate-papo — " + myName + " · #" + currentChannel); composer.requestFocus();
    }
    private void leave() {
        ++epoch; if (connection != null) { connection.close(); connection = null; }
        inRoom = false; history.clear(); participants.getItems().clear(); composer.clear(); accessCode.clear(); error.setText("");
        updatingChannels = true; channels.getItems().clear(); updatingChannels = false; confirmChannel("geral");
        cancel.setVisible(false); cancel.setManaged(false); onState("disconnected"); showLogin(); stage.setTitle("Bate-papo");
    }
    private void onState(String state) {
        online = state.equals("connected");
        refreshMotion();
        status.setText(switch (state) { case "connected" -> "●  Conectado"; case "connecting" -> "Conectando…"; case "reconnecting" -> "Reconectando…"; case "switching" -> "Trocando canal…"; default -> "Desconectado"; });
        status.getStyleClass().remove("online"); if (online) status.getStyleClass().add("online");
        send.setDisable(!online); composer.setDisable(!online);
        channels.setDisable(!online); channelName.setDisable(!online); createChannel.setDisable(!online);
        if (!online && !state.equals("switching")) { participants.getItems().clear(); peopleCount.setText("PARTICIPANTES"); }
        if (state.equals("reconnecting")) {
            String notice = "Tentando reconectar. Mensagens enviadas durante sua ausência não serão recuperadas.";
            if (inRoom) hint.setText(notice); else error.setText("Tentando conectar. Confira o endereço ou aguarde o servidor iniciar.");
        }
        if (online) { hint.setText(""); error.setText(""); selectCurrentChannel(); }
        if (state.equals("disconnected") && !inRoom) { cancel.setManaged(false); cancel.setVisible(false); showLogin(); }
    }
    private void onEvent(Event e) {
        switch (e.type()) {
            case "welcome" -> {
                boolean moved = e.channel() != null && !e.channel().equals(currentChannel);
                confirmChannel(e.channel()); if (!inRoom) showChat();
                if (moved && e.text() != null) append(new Line(null, null, e.text(), null, null));
            }
            case "joined" -> { confirmChannel(e.channel()); channelName.clear(); }
            case "channels" -> {
                if (e.users() != null) { updatingChannels = true; channels.getItems().setAll(e.users()); updatingChannels = false; selectCurrentChannel(); }
            }
            case "presence" -> {
                if (e.users() != null) { participants.getItems().setAll(e.users()); peopleCount.setText("PARTICIPANTES · " + e.users().size()); }
            }
            case "system" -> append(new Line(null, null, e.text(), null, null));
            case "pending", "message" -> {
                backdrop.pulse();
                String delivery = e.type().equals("pending") ? "Enviando…" : Objects.equals(e.name(), myName) ? "Confirmada pelo servidor" : "";
                var line = new Line(e.id(), e.name(), e.text(), e.time(), delivery);
                int existing = index(e.id(), e.name()); if (existing >= 0) history.set(existing, line); else append(line);
            }
            case "uncertain", "error" -> {
                int index = index(e.id(), myName);
                if (index >= 0) {
                    var old = history.get(index);
                    history.set(index, new Line(old.id(), old.name(), old.text(), old.time(), e.type().equals("uncertain") ? "Entrega não confirmada" : "Não enviada"));
                }
                if (inRoom) hint.setText(e.text()); else error.setText(e.text());
                if (Set.of("auth", "name", "full", "rate").contains(Objects.toString(e.id(), ""))) {
                    inRoom = false; error.setText(e.text()); accessCode.clear(); showLogin();
                }
            }
            default -> { }
        }
    }
    private int index(String id, String name) {
        if (id == null) return -1;
        for (int i = history.size() - 1; i >= 0; i--) if (id.equals(history.get(i).id()) && Objects.equals(name, history.get(i).name())) return i;
        return -1;
    }
    private void append(Line line) { history.add(line); if (history.size() > 500) history.removeFirst(); messages.scrollTo(history.size() - 1); }
    private void sendMessage() {
        if (connection == null || send.isDisabled()) return;
        try { connection.send(composer.getText()); composer.clear(); hint.setText(""); composer.requestFocus(); }
        catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }
    private static Label label(String text, String css) { var label = new Label(text); label.getStyleClass().add(css); return label; }
    private static VBox field(String title, Control field) {
        var label = label(title, "field-label"); label.setLabelFor(field); return new VBox(0, label, field);
    }
    private void refreshMotion() { backdrop.setActive(inRoom && online && stage != null && stage.isShowing() && !stage.isIconified()); }
    @Override public void stop() { ++epoch; backdrop.close(); if (connection != null) connection.close(); }
    private final class MessageCell extends ListCell<Line> {
        @Override protected void updateItem(Line line, boolean empty) {
            super.updateItem(line, empty); setText(null); setGraphic(null);
            if (empty || line == null) return;
            if (line.name() == null) { var info = label(line.text(), "system-message"); info.setWrapText(true); setGraphic(info); return; }
            boolean own = Objects.equals(line.name(), myName);
            var text = label(line.text(), "message-text"); text.setWrapText(true); text.setMinWidth(0);
            text.maxWidthProperty().bind(messages.widthProperty().subtract(140).multiply(0.9));
            String time = "";
            try { if (line.time() != null) time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(line.time()).atZone(ZoneId.systemDefault())) + " · "; }
            catch (DateTimeException ignored) { }
            var bubble = new VBox(5, label(own ? "Você" : line.name(), "sender"), text, label(time + Objects.toString(line.delivery(), ""), "message-meta"));
            bubble.getStyleClass().add("bubble"); if (own) bubble.getStyleClass().add("own");
            var row = new HBox(bubble); row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
