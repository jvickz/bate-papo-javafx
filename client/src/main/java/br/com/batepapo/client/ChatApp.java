package br.com.batepapo.client;

import br.com.batepapo.protocol.*;
import javafx.application.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

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
    private final ServerCheck serverCheck = new ServerCheck();
    private final Label checkResult = label("Verifique o servidor antes de entrar, se precisar.", "connection-note");
    private final Button checkServer = new Button("Verificar servidor");
    private final Button copyInvite = new Button("Convidar pessoa");
    private final Label conversationInfo = label("", "conversation-info");
    private final Label counter = label("0 / 2000", "message-counter");
    private final Button newMessages = new Button("Novas mensagens ↓");
    private final Map<String, List<Line>> channelHistory = new LinkedHashMap<>();
    private final Map<String, String> drafts = new HashMap<>();
    private boolean followTail = true;
    private int unread;
    private long checkEpoch;
    private boolean stopped;
    private final VoicePanel voicePanel = new VoicePanel();
    private final VoiceController voice;

    public ChatApp() {
        this(new Supplier<>() {
            private VoiceEngine engine;
            @Override public VoiceEngine get() {
                if (engine == null) engine = new NativeVoiceEngine();
                return engine;
            }
        });
    }

    ChatApp(Supplier<VoiceEngine> engines) {
        voice = new VoiceController(event -> {
            if (connection == null) throw new IllegalStateException();
            connection.voice(event);
        }, engines, Platform::runLater, voicePanel::render);
        voicePanel.bind(voice);
    }

    record Line(String id, String name, String text, String time, String delivery) {}

    @Override public void start(Stage stage) {
        this.stage = stage;
        Font.loadFont(Objects.requireNonNull(getClass().getResource("/fonts/VT323-Regular.ttf")).toExternalForm(), 22);
        nickname.setId("nickname"); nickname.setPromptText("Como você quer ser chamado?");
        nickname.setText(suggestNickname());
        server.setId("server"); server.setPromptText("https://seu-servidor.onrender.com");
        server.setText(System.getenv().getOrDefault("BATEPAPO_SERVER", "https://bate-papo-java.onrender.com"));
        server.textProperty().addListener((o, old, next) -> { ++checkEpoch; checkServer.setDisable(false); checkResult.setText("Endereço alterado. Verifique novamente se precisar."); });
        checkServer.setId("checkServer"); checkServer.getStyleClass().add("secondary-button"); checkServer.setOnAction(e -> verifyServer());
        checkResult.setId("checkResult"); checkResult.setWrapText(true);
        copyInvite.setId("copyInvite"); copyInvite.setMaxWidth(Double.MAX_VALUE); copyInvite.setDisable(true);
        copyInvite.setOnAction(e -> copyInvite());
        counter.setId("messageCounter");
        composer.textProperty().addListener((o, old, next) -> refreshSend());
        accessCode.setId("accessCode"); accessCode.setPromptText("Código compartilhado com você");
        join.setId("join"); join.getStyleClass().add("primary"); join.setMaxWidth(Double.MAX_VALUE);
        join.setOnAction(e -> enter()); accessCode.setOnAction(e -> enter());
        cancel.setOnAction(e -> leave()); cancel.setVisible(false); cancel.setManaged(false);
        composer.setId("composer"); composer.setPromptText("Escreva uma mensagem…");
        composer.setWrapText(true); composer.setPrefRowCount(2);
        composer.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) { e.consume(); sendMessage(); } });
        send.setId("send"); send.getStyleClass().add("primary"); send.setOnAction(e -> sendMessage());
        send.setMinWidth(Region.USE_PREF_SIZE); composer.setMinWidth(0);
        send.setDisable(true);
        messages.setId("messages"); messages.getStyleClass().add("messages");
        messages.setCellFactory(view -> new MessageCell());
        messages.setPlaceholder(label("Sua próxima conversa começa com um oi.\nAs mensagens aparecem aqui.", "muted"));
        messages.skinProperty().addListener((o, old, skin) -> Platform.runLater(this::watchScroll));
        newMessages.setId("newMessages"); newMessages.getStyleClass().add("new-messages");
        newMessages.setVisible(false); newMessages.setManaged(false); newMessages.setOnAction(e -> jumpToLatest());
        participants.setId("participants");
        participants.setAccessibleText("Participantes do canal");
        participants.setPlaceholder(label("Ninguém conectado", "muted"));
        participants.getSelectionModel().selectedItemProperty().addListener((o, before, next) ->
                voice.participant(myName, next));

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
        conversationInfo.setId("conversationInfo"); conversationInfo.setText("Entre e convide alguém para conversar");
        var heading = new VBox(4, channelHeading, conversationInfo); heading.setMinWidth(0);
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
                try {
                    connection.joinChannel(next);
                    voice.connection(false, true);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    hint.setText(ex.getMessage());
                    selectCurrentChannel();
                }
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
        var another = new Button("OUTRA JANELA"); another.setId("openTestWindow"); another.getStyleClass().add("secondary-button"); another.setMaxWidth(Double.MAX_VALUE);
        another.setTooltip(new Tooltip("Abrir uma segunda sessão para testar uma conversa"));
        another.setOnAction(e -> { var other = new ChatApp(); other.start(new Stage()); other.server.setText(server.getText()); });
        var box = new VBox(12, label("bate-papo_", "brand"), subtitle, new Separator(), label("[ CANAIS ]", "eyebrow"), channels, createRow,
                peopleCount, participants, spacer, copyInvite, another, label("WINDOWS / v1.4", "muted"));
        box.getStyleClass().add("sidebar"); box.setPrefWidth(224); return box;
    }
    private void createChannel() {
        if (connection == null || !online) return;
        try {
            connection.createChannel(channelName.getText());
            voice.connection(false, true);
            hint.setText("");
        }
        catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }
    private void selectCurrentChannel() {
        updatingChannels = true;
        try { channels.getSelectionModel().select(currentChannel); } finally { updatingChannels = false; }
    }
    private void confirmChannel(String channel) {
        String next = channel == null ? "geral" : channel;
        if (!next.equals(currentChannel)) {
            channelHistory.put(currentChannel, new ArrayList<>(history)); drafts.put(currentChannel, composer.getText());
            var saved = channelHistory.remove(next); String draft = drafts.remove(next);
            history.setAll(saved == null ? List.of() : saved); composer.setText(draft == null ? "" : draft);
            while (channelHistory.size() > 19) { String oldest = channelHistory.keySet().iterator().next(); channelHistory.remove(oldest); drafts.remove(oldest); }
            participants.getItems().clear(); jumpToLatest();
        }
        currentChannel = next; channelHeading.setText("#" + next); channelHeading.setTooltip(new Tooltip("#" + next));
        selectCurrentChannel();
        if (inRoom) stage.setTitle("Bate-papo — " + myName + " · #" + currentChannel);
    }
    private void showLogin() {
        refreshMotion();
        content.setBottom(null);
        var explanation = label("Seu apelido já está pronto. Cole o código recebido e entre.\nA outra pessoa usa o mesmo servidor e código.", "muted");
        explanation.setWrapText(true);
        var suggest = new Button("Gerar outro"); suggest.setId("suggestNickname"); suggest.getStyleClass().add("secondary-button");
        suggest.disableProperty().bind(nickname.disabledProperty());
        suggest.setOnAction(e -> { nickname.setText(suggestNickname()); error.setText(""); nickname.requestFocus(); });
        var nicknameRow = new HBox(8, nickname, suggest); HBox.setHgrow(nickname, Priority.ALWAYS); nickname.setMinWidth(0);
        var nicknameLabel = label("SEU APELIDO", "field-label"); nicknameLabel.setLabelFor(nickname);
        var serverOptions = new VBox(10, field("ENDEREÇO DO SERVIDOR", server), checkServer, checkResult);
        var advanced = new TitledPane("Servidor e diagnóstico de conexão", serverOptions); advanced.setId("serverOptions");
        advanced.setExpanded(false); advanced.setAnimated(false);
        error.setWrapText(true);
        var card = new VBox(14, label("[ INICIAR SESSÃO ]", "eyebrow"), label("Olá, visitante_", "hero"), explanation,
                new VBox(nicknameLabel, nicknameRow), field("CÓDIGO DE ACESSO", accessCode),
                error, join, cancel, advanced, label("Quem tem o código pode entrar. Seu código não é salvo.", "muted"));
        card.getStyleClass().add("card"); card.setMaxWidth(560); card.setMaxHeight(Region.USE_PREF_SIZE);
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
            error.setText(""); hint.setText(""); history.clear(); channelHistory.clear(); drafts.clear();
            composer.clear(); currentChannel = "geral"; channelHeading.setText("#geral");
            participants.getItems().clear(); updatingChannels = true; channels.getItems().clear(); updatingChannels = false;
            jumpToLatest();
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
        var feed = new BorderPane(messages); feed.setBottom(newMessages); BorderPane.setAlignment(newMessages, Pos.CENTER);
        content.setCenter(feed); Platform.runLater(this::watchScroll);
        var row = new HBox(12, composer, send); row.setAlignment(Pos.CENTER); HBox.setHgrow(composer, Priority.ALWAYS);
        var leave = new Button("Sair da sala"); leave.setOnAction(e -> leave());
        var tip = label("Enter envia · Shift+Enter quebra a linha", "muted");
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var footer = new HBox(10, tip, spacer, counter, leave); footer.setAlignment(Pos.CENTER_LEFT);
        hint.setWrapText(true);
        var bottom = new VBox(8, voicePanel, row, hint, footer); bottom.getStyleClass().add("composer"); content.setBottom(bottom);
        stage.setTitle("Bate-papo — " + myName + " · #" + currentChannel); composer.requestFocus();
    }
    private void leave() {
        voice.connection(false, false);
        ++epoch; if (connection != null) { connection.close(); connection = null; }
        inRoom = false; history.clear(); participants.getItems().clear(); composer.clear(); accessCode.clear(); error.setText("");
        updatingChannels = true; channels.getItems().clear(); updatingChannels = false; confirmChannel("geral");
        channelHistory.clear(); drafts.clear(); history.clear(); composer.clear(); conversationInfo.setText("Entre e convide alguém para conversar");
        cancel.setVisible(false); cancel.setManaged(false); onState("disconnected"); showLogin(); stage.setTitle("Bate-papo");
    }
    private void onState(String state) {
        online = state.equals("connected");
        voice.connection(online, state.equals("switching"));
        refreshMotion();
        status.setText(switch (state) { case "connected" -> "●  Conectado"; case "connecting" -> "Conectando…"; case "reconnecting" -> "Reconectando…"; case "switching" -> "Trocando canal…"; default -> "Desconectado"; });
        status.getStyleClass().remove("online"); if (online) status.getStyleClass().add("online");
        refreshSend(); composer.setDisable(!online); copyInvite.setDisable(!inRoom || !online);
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
                if (e.users() != null) {
                    String selected = participants.getSelectionModel().getSelectedItem();
                    participants.getItems().setAll(e.users());
                    if (selected != null && e.users().contains(selected)) participants.getSelectionModel().select(selected);
                    voice.participant(myName, participants.getSelectionModel().getSelectedItem());
                    peopleCount.setText("PARTICIPANTES · " + e.users().size());
                    conversationInfo.setText(e.users().size() == 1 ? "Só você por aqui · convide alguém" : e.users().size() + " pessoas neste canal · conexão ativa");
                }
            }
            case "voice", "callincoming", "callringing", "callready", "callsignal", "callended", "callerror" -> {
                if (inRoom && ("voice".equals(e.type()) || e.channel() == null || currentChannel.equals(e.channel()))) voice.receive(e);
            }
            case "connection" -> { if (inRoom) hint.setText(e.text()); else error.setText(e.text()); }
            case "system" -> append(new Line(null, null, e.text(), null, null));
            case "pending", "message" -> {
                backdrop.pulse();
                String delivery = e.type().equals("pending") ? "Enviando…" : Objects.equals(e.name(), myName) ? "Confirmada pelo servidor" : "";
                var line = new Line(e.id(), e.name(), e.text(), e.time(), delivery);
                if (e.channel() != null && !currentChannel.equals(e.channel())) {
                    var saved = channelHistory.computeIfAbsent(e.channel(), key -> new ArrayList<>());
                    saved.add(line); if (saved.size() > 500) saved.removeFirst(); break;
                }
                int existing = index(e.id(), e.name()); if (existing >= 0) history.set(existing, line); else append(line);
            }
            case "uncertain", "error" -> {
                int index = index(e.id(), myName);
                if (index >= 0) {
                    var old = history.get(index);
                    history.set(index, new Line(old.id(), old.name(), old.text(), old.time(), e.type().equals("uncertain") ? "Entrega não confirmada" : "Não enviada"));
                } else if (e.id() != null) {
                    for (var saved : channelHistory.values()) for (int i = 0; i < saved.size(); i++) {
                        var old = saved.get(i);
                        if (e.id().equals(old.id()) && Objects.equals(myName, old.name())) saved.set(i,
                            new Line(old.id(), old.name(), old.text(), old.time(), e.type().equals("uncertain") ? "Entrega não confirmada" : "Não enviada"));
                    }
                }
                if (inRoom) hint.setText(e.text()); else error.setText(e.text());
                if (Set.of("auth", "name", "full", "rate").contains(Objects.toString(e.id(), ""))) {
                    voice.connection(false, false);
                    inRoom = false; error.setText(e.text());
                    if (!"name".equals(e.id())) accessCode.clear();
                    else error.setText(e.text() + " Clique em Gerar outro e tente novamente; o código foi mantido.");
                    showLogin();
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
    private void append(Line line) {
        boolean follow = followTail || Objects.equals(line.name(), myName);
        history.add(line); if (history.size() > 500) history.removeFirst();
        if (follow) jumpToLatest(); else { unread++; newMessages.setText(unread + " novas mensagens ↓"); newMessages.setManaged(true); newMessages.setVisible(true); }
    }
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
    @Override public void stop() {
        stopped = true;
        ++epoch;
        ++checkEpoch;
        voice.connection(false, false);
        serverCheck.close();
        backdrop.close();
        if (connection != null) connection.close();
        channelHistory.clear(); drafts.clear(); history.clear(); accessCode.clear();
    }
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
            var copy = new MenuItem("Copiar mensagem"); copy.setOnAction(e -> copyText(line.text()));
            var menu = new ContextMenu(copy);
            if (own) {
                var recover = new MenuItem("Editar novamente"); recover.setOnAction(e -> recover(line.text())); menu.getItems().add(recover);
                if (Set.of("Não enviada", "Entrega não confirmada").contains(Objects.toString(line.delivery(), ""))) {
                    var recoverButton = new Button("Recuperar texto"); recoverButton.getStyleClass().add("secondary-button");
                    recoverButton.setOnAction(e -> recover(line.text())); bubble.getChildren().add(recoverButton);
                }
            }
            bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
            bubble.getStyleClass().add("bubble"); if (own) bubble.getStyleClass().add("own");
            var row = new HBox(bubble); row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

    private static String suggestNickname() { return "Marujo-" + UUID.randomUUID().toString().substring(0, 6); }
    private void refreshSend() {
        String text = composer.getText(); int count = text.codePointCount(0, text.length());
        counter.setText(count + " / 2000"); counter.getStyleClass().remove("invalid");
        boolean valid;
        try { Protocol.validateText(text); valid = true; } catch (IllegalArgumentException ex) { valid = false; }
        if (count > 2000) counter.getStyleClass().add("invalid"); send.setDisable(!online || !valid);
    }
    private void verifyServer() {
        long check = ++checkEpoch; checkServer.setDisable(true); checkResult.setText("Verificando… O servidor gratuito pode estar iniciando.");
        serverCheck.check(server.getText()).thenAccept(result -> Platform.runLater(() -> {
            if (stopped || check != checkEpoch) return;
            checkServer.setDisable(false); checkResult.setText(result.message());
        }));
    }
    private void copyInvite() {
        String address = Protocol.serverUri(server.getText()).toString();
        copyText("Vamos conversar no Bate-papo!\nServidor: " + address + "\nCanal: " + currentChannel
                + "\nUse um apelido diferente. O código de acesso deve ser compartilhado separadamente."
                + "\nAbra o aplicativo, entre e selecione #" + currentChannel + ".");
        hint.setText("Convite copiado com servidor e canal. Compartilhe o código de acesso em particular.");
    }
    private static void copyText(String text) { var content = new ClipboardContent(); content.putString(text); Clipboard.getSystemClipboard().setContent(content); }
    private void recover(String text) {
        if (!composer.getText().isBlank()) { copyText(text); hint.setText("Texto copiado. Seu rascunho atual foi preservado; cole onde desejar."); return; }
        composer.setText(text); composer.requestFocus(); hint.setText("Revise antes de enviar. Uma entrega não confirmada pode ter chegado.");
    }
    private void jumpToLatest() {
        followTail = true; unread = 0; newMessages.setManaged(false); newMessages.setVisible(false);
        messages.scrollTo(Math.max(0, history.size() - 1));
    }
    private void watchScroll() {
        messages.applyCss();
        for (Node node : messages.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL && !bar.getProperties().containsKey("watched")) {
                bar.getProperties().put("watched", true);
                bar.valueProperty().addListener((o, old, value) -> {
                    followTail = value.doubleValue() >= bar.getMax() - 0.01;
                    if (followTail) { unread = 0; newMessages.setManaged(false); newMessages.setVisible(false); }
                });
            }
        }
    }
}
