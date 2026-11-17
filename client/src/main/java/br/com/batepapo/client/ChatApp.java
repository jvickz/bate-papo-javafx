package br.com.batepapo.client;

import br.com.batepapo.protocol.*;
import javafx.application.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ChatApp extends Application {
    private static final String APP_NAME = "Wavecove";
    private static final Image APP_ICON = new Image(Objects.requireNonNull(
            ChatApp.class.getResource("/wavecove.png")).toExternalForm());

    private final BorderPane content = new BorderPane();
    private final Label status = label("Desconectado", "pill");
    private final Label error = label("", "error");
    private final Label hint = label("", "error");
    private final TextField username = new TextField();
    private final TextField server = new TextField();
    private final PasswordField password = new PasswordField();
    private final AccountAccess accounts = new AccountAccess();
    private final ToggleButton loginTab = new ToggleButton("Entrar");
    private final ToggleButton registerTab = new ToggleButton("Criar conta");
    private final Label formTitle = label("", "form-title");
    private final Label passwordHelp = label("Use de 12 a 128 caracteres.", "field-help");
    private final Label identity = label("", "muted");
    private HBox roomHeader;
    private TitledPane advanced;
    private boolean registering;
    private String accountAddress;
    private AccountAccess.Session session;
    private CompletableFuture<AccountAccess.Session> pendingAccount;
    private CompletableFuture<Void> abandonedRequests = CompletableFuture.completedFuture(null);
    private final Button join = new Button("Entrar");
    private final Button cancel = new Button("Cancelar entrada");
    private final TextArea composer = new TextArea();
    private final Button send = new Button("Enviar  →");
    private final Button attach = new Button("Anexar");
    private boolean attachmentsReady;
    private boolean transferring;
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
    private VBox sidebar;


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

    record Line(String id, String name, String text, String time, String delivery, boolean attachment, String reference) {
        Line(String id, String name, String text, String time, String delivery) {
            this(id, name, text, time, delivery, false, null);
        }
    }

    @Override public void start(Stage stage) {
        this.stage = stage;
        try (var font = Objects.requireNonNull(getClass().getResourceAsStream("/fonts/VT323-Regular.ttf"))) {
            Font.loadFont(font, 22);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        username.setId("username"); username.setPromptText("Seu nome de usuário");
        username.setOnAction(e -> password.requestFocus());
        server.setId("server"); server.setPromptText("https://seu-servidor.onrender.com");
        server.setText(System.getenv().getOrDefault("BATEPAPO_SERVER", "https://bate-papo-java.onrender.com"));
        server.textProperty().addListener((o, old, next) -> { ++checkEpoch; checkServer.setDisable(false); checkResult.setText("Endereço alterado. Verifique novamente se precisar."); });
        checkServer.setId("checkServer"); checkServer.getStyleClass().add("secondary-button"); checkServer.setOnAction(e -> verifyServer());
        checkResult.setId("checkResult"); checkResult.setWrapText(true);
        copyInvite.setId("copyInvite"); copyInvite.setMaxWidth(Double.MAX_VALUE); copyInvite.setDisable(true);
        copyInvite.setOnAction(e -> copyInvite());
        counter.setId("messageCounter");
        composer.textProperty().addListener((o, old, next) -> refreshSend());
        password.setId("password"); password.setPromptText("Sua senha");
        error.setId("authError");
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        join.setId("join"); join.getStyleClass().add("primary"); join.setMaxWidth(Double.MAX_VALUE);
        join.setOnAction(e -> enter()); password.setOnAction(e -> enter());
        cancel.setId("cancelAuth"); cancel.getStyleClass().add("secondary-button");
        cancel.setOnAction(e -> {
            boolean mode = registering;
            String name = username.getText();
            leave();
            username.setText(name);
            setAccountMode(mode);
            if (mode) error.setText("Entrada cancelada. Se sua conta já foi criada, use Entrar.");
        });
        cancel.setVisible(false); cancel.setManaged(false);
        composer.setId("composer"); composer.setPromptText("Escreva uma mensagem…");
        composer.setWrapText(true); composer.setPrefRowCount(2);
        composer.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) { e.consume(); sendMessage(); } });
        send.setId("send"); send.getStyleClass().add("primary"); send.setOnAction(e -> sendMessage());
        send.setMinWidth(Region.USE_PREF_SIZE); composer.setMinWidth(0);
        send.setDisable(true);
        attach.setId("attach");
        attach.getStyleClass().add("secondary-button");
        attach.setTooltip(new Tooltip("Enviar foto JPG/PNG ou vídeo MP4 ao canal"));
        attach.setOnAction(e -> chooseAttachment());
        refreshAttachmentButton();
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

        sidebar = sidebar();
        var shell = new BorderPane(); shell.setLeft(sidebar); shell.setCenter(content);
        shell.getStyleClass().add("terminal-shell");
        var root = new StackPane(backdrop, shell);
        backdrop.setPaused(!WindowsTitleBar.animationsEnabled());
        channelHeading.setId("channelHeading"); channelHeading.setMaxWidth(300); channelHeading.setMinWidth(0);
        conversationInfo.setId("conversationInfo"); conversationInfo.setText("Entre e convide alguém para conversar");
        var heading = new VBox(4, channelHeading, conversationInfo); heading.setMinWidth(0);
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        roomHeader = new HBox(12, heading, spacer, status); roomHeader.setAlignment(Pos.CENTER_LEFT); roomHeader.getStyleClass().add("header");
        buildAccountScreen(); showLogin();
        var scene = new Scene(root, 1080, 760); scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/chat.css")).toExternalForm());
        stage.getIcons().add(APP_ICON);
        stage.setScene(scene); stage.setTitle(APP_NAME); stage.setMinWidth(900); stage.setMinHeight(720);
        stage.iconifiedProperty().addListener((o, before, after) -> refreshMotion());
        stage.showingProperty().addListener((o, before, after) -> refreshMotion());
        stage.setOnCloseRequest(e -> stop()); stage.show();
        WindowsTitleBar.apply(stage);
        username.requestFocus();
    }
    private static HBox brand(String style, int iconSize) {
        var icon = new ImageView(APP_ICON);
        icon.setFitWidth(iconSize);
        icon.setFitHeight(iconSize);
        icon.setSmooth(false);
        var row = new HBox(8, icon, label(APP_NAME, style));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void showRoomControls(boolean visible) {
        sidebar.setVisible(visible);
        sidebar.setManaged(visible);
        content.setTop(visible ? roomHeader : null);
    }

    private VBox sidebar() {
        identity.setId("accountIdentity"); identity.setWrapText(true);
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
        var another = new Button("Outra janela"); another.setId("openTestWindow"); another.getStyleClass().add("secondary-button"); another.setMaxWidth(Double.MAX_VALUE);
        another.setTooltip(new Tooltip("Abrir uma segunda sessão para testar uma conversa"));
        another.setOnAction(e -> { var other = new ChatApp(); other.start(new Stage()); other.server.setText(server.getText()); });
        var box = new VBox(12, brand("brand", 24), identity, new Separator(), label("CANAIS", "eyebrow"), channels, createRow,
                peopleCount, participants, spacer, copyInvite, another);
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
    }
    private ScrollPane accountScreen;

    private void buildAccountScreen() {
        loginTab.setId("loginTab");
        registerTab.setId("registerTab");
        var modes = new ToggleGroup();
        for (var tab : List.of(loginTab, registerTab)) {
            tab.setToggleGroup(modes);
            tab.getStyleClass().add("account-tab");
            tab.setPrefWidth(150);
            tab.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tab, Priority.ALWAYS);
            tab.setOnAction(e -> {
                setAccountMode(tab == registerTab);
                username.requestFocus();
            });
        }
        var tabs = new HBox(4, loginTab, registerTab);
        tabs.getStyleClass().add("account-tabs");
        var connectionSettings = new VBox(10, field("Endereço do servidor", server), checkServer, checkResult);
        advanced = new TitledPane("Configurações do servidor", connectionSettings);
        advanced.setId("serverOptions");
        advanced.setExpanded(false);
        advanced.setAnimated(false);
        error.setWrapText(true);
        passwordHelp.setWrapText(true);
        var passwordRow = field("Senha", password);
        passwordRow.getChildren().add(passwordHelp);
        var card = new VBox(14, brand("brand", 32), tabs, formTitle,
                field("Nome de usuário", username), passwordRow, error, join, cancel, advanced);
        card.getStyleClass().add("card");
        card.setMaxWidth(408);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setMinHeight(Region.USE_PREF_SIZE);
        var center = new StackPane(card);
        center.setAlignment(Pos.CENTER_LEFT);
        center.setPadding(new Insets(62, 32, 42, 54));
        center.setMinHeight(Region.USE_PREF_SIZE);
        accountScreen = new ScrollPane(center);
        accountScreen.setId("loginScroll");
        accountScreen.getStyleClass().add("login-scroll");
        accountScreen.setFitToWidth(true);
        accountScreen.setFitToHeight(true);
        accountScreen.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    }

    private void setAccountMode(boolean create) {
        if (registering != create) {
            password.clear();
            error.setText("");
        }
        registering = create;
        (create ? registerTab : loginTab).setSelected(true);
        formTitle.setText(create ? "Crie sua conta" : "Entre na sua conta");
        passwordHelp.setVisible(create);
        passwordHelp.setManaged(create);
        username.setPromptText(create ? "3–24 letras, números ou _" : "Seu nome de usuário");
        join.setText(create ? "Criar conta" : "Entrar");
        backdrop.setView(create ? PixelBackdrop.View.REGISTER : PixelBackdrop.View.LOGIN);
    }

    private void showLogin() {
        showRoomControls(false);
        content.setBottom(null);
        content.setCenter(accountScreen);
        setAccountMode(registering);
        setAuthBusy(false);
        refreshMotion();
        username.requestFocus();
    }

    private void setAuthBusy(boolean busy) {
        for (var control : List.of(join, username, password, advanced, loginTab, registerTab)) control.setDisable(busy);
        cancel.setManaged(busy);
        cancel.setVisible(busy);
        join.setText(busy ? (registering ? "Criando conta…" : "Entrando…") : (registering ? "Criar conta" : "Entrar"));
    }

    private void enter() {
        if (join.isDisabled()) return;
        String name = username.getText().strip();
        String secret = password.getText();
        if (!name.matches("[a-zA-Z0-9_]{3,24}")) {
            error.setText("Use de 3 a 24 letras sem acento, números ou _ no nome de usuário.");
            username.requestFocus();
            return;
        }
        int length = secret.codePointCount(0, secret.length());
        if (secret.isBlank() || length > 128 || (registering && length < 12)) {
            error.setText(registering ? "Escolha uma senha de 12 a 128 caracteres." : "Digite sua senha.");
            password.requestFocus();
            return;
        }
        String address = server.getText().strip();
        try {
            Protocol.serverUri(address);
            var request = accounts.authenticate(address, name, secret, registering);
            accountAddress = address;
            pendingAccount = request;
            error.setText("");
            password.clear();
            setAuthBusy(true);
            request.whenComplete((result, failure) -> Platform.runLater(() -> {
                if (request != pendingAccount) return;
                pendingAccount = null;
                if (failure != null) {
                    var cause = failure instanceof CompletionException ? failure.getCause() : failure;
                    error.setText(cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
                            ? cause.getMessage() : "Não foi possível acessar o servidor. Confira a conexão e tente novamente.");
                    setAuthBusy(false);
                    password.requestFocus();
                    return;
                }
                session = result;
                myName = result.username();
                identity.setText("@" + myName);
                long current = ++epoch;
                connection = new ChatConnection(e -> Platform.runLater(() -> { if (current == epoch) onEvent(e); }),
                        s -> Platform.runLater(() -> { if (current == epoch) onState(s); }));
                try {
                    connection.connect(address, myName, result.token());
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    leave();
                    error.setText(ex.getMessage());
                }
            }));
        } catch (IllegalArgumentException ex) {
            advanced.setExpanded(true);
            error.setText(ex.getMessage());
            server.requestFocus();
        }
    }

    private void revokeAccount() {
        if (pendingAccount != null) {
            // A successful HTTP response can arrive after cancellation, including during shutdown.
            String address = accountAddress;
            var cleanup = pendingAccount.handle((result, failure) -> {
                if (result != null) accounts.logout(address, result.token());
                return (Void) null;
            });
            abandonedRequests = CompletableFuture.allOf(abandonedRequests, cleanup);
            pendingAccount = null;
        }
        if (session != null) {
            accounts.logout(accountAddress, session.token());
            session = null;
        }
        accountAddress = null;
    }

    private void showChat() {
        inRoom = true; cancel.setVisible(false); cancel.setManaged(false);
        backdrop.setView(PixelBackdrop.View.CHAT);
        showRoomControls(true);
        refreshMotion();
        var feed = new BorderPane(messages); feed.setBottom(newMessages); BorderPane.setAlignment(newMessages, Pos.CENTER);
        content.setCenter(feed); Platform.runLater(this::watchScroll);
        var row = new HBox(12, attach, composer, send); row.setAlignment(Pos.CENTER); HBox.setHgrow(composer, Priority.ALWAYS);
        var leave = new Button("Sair da conta"); leave.setId("signOut"); leave.getStyleClass().add("secondary-button"); leave.setOnAction(e -> leave());
        var tip = label("Enter envia · Shift+Enter quebra a linha", "muted");
        var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        var footer = new HBox(10, tip, spacer, counter, leave); footer.setAlignment(Pos.CENTER_LEFT);
        hint.setWrapText(true);
        var bottom = new VBox(8, voicePanel, row, hint, footer); bottom.getStyleClass().add("composer"); content.setBottom(bottom);
        composer.requestFocus();
    }
    private void leave() {
        voice.connection(false, false);
        attachmentsReady = transferring = false;
        ++epoch; if (connection != null) { connection.close(); connection = null; }
        revokeAccount();
        inRoom = false; registering = false; myName = null;
        identity.setText(""); username.clear(); password.clear(); error.setText(""); hint.setText("");
        channelHistory.clear(); drafts.clear(); history.clear(); composer.clear(); channelName.clear();
        participants.getItems().clear();
        updatingChannels = true; channels.getItems().clear(); updatingChannels = false;
        currentChannel = "geral"; channelHeading.setText("#geral"); channelHeading.setTooltip(null);
        jumpToLatest();
        advanced.setExpanded(false);
        conversationInfo.setText("");
        onState("disconnected");
    }
    private void onState(String state) {
        online = state.equals("connected");
        if (!online && !state.equals("switching")) attachmentsReady = false;
        refreshAttachmentButton();
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
            case "attachments" -> { attachmentsReady = true; refreshAttachmentButton(); }
            case "retained" -> {
                if (currentChannel.equals(e.channel())) {
                    // Also remove withdrawals made while this client was offline or viewing another channel.
                    var retained = new HashSet<>(e.users());
                    history.removeIf(line -> line.reference() != null && !retained.contains(line.reference()));
                }
            }
            case "deleted" -> {
                if (currentChannel.equals(e.channel())) {
                    boolean own = history.stream().anyMatch(line -> e.id().equals(line.reference()) && Objects.equals(myName, line.name()));
                    history.removeIf(line -> e.id().equals(line.reference()));
                    if (own) hint.setText("Mensagem apagada.");
                }
            }
            case "deleteerror" -> hint.setText(e.text());
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
            case "attachment" -> {
                backdrop.pulse();
                append(new Line(e.id(), e.name(), e.text(), e.time(), "", true, e.session()));
            }
            case "pending", "message" -> {
                backdrop.pulse();
                String delivery = e.type().equals("pending") ? "Enviando…" : Objects.equals(e.name(), myName) ? "Confirmada pelo servidor" : "";
                var line = new Line(e.id(), e.name(), e.text(), e.time(), delivery, false, e.session());
                if (e.channel() != null && !currentChannel.equals(e.channel())) {
                    var saved = channelHistory.computeIfAbsent(e.channel(), key -> new ArrayList<>());
                    saved.add(line); if (saved.size() > Protocol.CHANNEL_HISTORY_LIMIT) saved.removeFirst(); break;
                }
                int existing = index(e.id(), e.name());
                if (e.type().equals("message") && existing >= 0
                        && Set.of("Enviando…", "Entrega não confirmada").contains(Objects.toString(history.get(existing).delivery(), "")))
                    history.set(existing, line);
                else append(line);
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
                    leave();
                    error.setText(e.text());
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
        history.add(line); if (history.size() > Protocol.CHANNEL_HISTORY_LIMIT) history.removeFirst();
        if (follow) jumpToLatest(); else { unread++; newMessages.setText(unread + " novas mensagens ↓"); newMessages.setManaged(true); newMessages.setVisible(true); }
    }
    private void sendMessage() {
        if (connection == null || send.isDisabled()) return;
        try { connection.send(composer.getText()); composer.clear(); hint.setText(""); composer.requestFocus(); }
        catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }

    private void confirmDeletion(Line line) {
        if (!online || connection == null) { hint.setText("Reconecte ao canal para apagar a mensagem."); return; }
        if (!Protocol.validMessageReference(line.reference())) {
            hint.setText("A exclusão exige uma mensagem enviada com o Wavecove 1.8 e um servidor compatível."); return;
        }
        long account = epoch;
        String channel = currentChannel;
        var erase = new ButtonType("Apagar", ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        var prompt = new Alert(Alert.AlertType.CONFIRMATION, line.attachment()
                ? "O arquivo ficará indisponível para novos downloads. Cópias já salvas continuam nos dispositivos."
                : "O Wavecove vai remover esta mensagem da conversa para quem usa a versão 1.8 ou superior.", cancel, erase);
        prompt.initOwner(stage);
        prompt.setTitle(APP_NAME);
        prompt.setHeaderText("Apagar " + (line.attachment() ? "o anexo" : "a mensagem") + " de #" + channel + "?");
        prompt.getDialogPane().getStylesheets().setAll(stage.getScene().getStylesheets());
        prompt.getDialogPane().getStyleClass().add("wavecove-dialog");
        ((Button) prompt.getDialogPane().lookupButton(erase)).setDefaultButton(false);
        ((Button) prompt.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        if (prompt.showAndWait().orElse(cancel) != erase || account != epoch || !channel.equals(currentChannel)
                || connection == null || !history.contains(line)) return;
        try { connection.deleteMessage(line.reference(), channel); }
        catch (IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }

    private void refreshAttachmentButton() {
        attach.setDisable(!online || !attachmentsReady || transferring);
        attach.setText(transferring ? "Aguarde…" : "Anexar");
        attach.getTooltip().setText(online && !attachmentsReady
                ? "Este servidor ainda não oferece anexos."
                : "Enviar foto JPG/PNG (5 MB) ou vídeo MP4 (15 MB) ao canal");
    }

    private void chooseAttachment() {
        if (attach.isDisabled()) return;
        long session = epoch;
        String channel = currentChannel;
        var chooser = new FileChooser();
        chooser.setTitle("Enviar anexo para #" + channel);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Fotos e vídeos", "*.jpg", "*.jpeg", "*.png", "*.mp4"));
        var file = chooser.showOpenDialog(stage);
        if (file == null || session != epoch || !channel.equals(currentChannel)) return;
        try {
            var transfer = connection.attachmentTransfer(channel);
            var upload = transfer.upload(file.toPath());
            beginTransfer("Enviando " + file.getName() + "…");
            upload.whenComplete((unused, failure) -> Platform.runLater(() ->
                    finishTransfer(session, channel, failure, "Anexo enviado.", "Não foi possível enviar o anexo.")));
        } catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }

    private void beginTransfer(String message) {
        transferring = true;
        refreshAttachmentButton();
        hint.setText(message);
    }

    private void finishTransfer(long session, String channel, Throwable failure, String success, String fallback) {
        if (epoch != session || stopped) return;
        transferring = false;
        refreshAttachmentButton();
        if (!channel.equals(currentChannel)) return;
        if (failure instanceof CompletionException) failure = failure.getCause();
        hint.setText(failure == null ? success
                : failure instanceof IllegalArgumentException ? failure.getMessage() : fallback);
    }

    private void receiveAttachment(Line line, ImageView preview) {
        if (!online || transferring || connection == null) return;
        long session = epoch;
        String channel = currentChannel;
        try {
            Attachment kind = Attachment.forName(line.text());
            var transfer = connection.attachmentTransfer(channel);
            if (preview == null) {
                var chooser = new FileChooser();
                chooser.setTitle("Salvar anexo");
                chooser.setInitialFileName(line.text());
                var destination = chooser.showSaveDialog(stage);
                if (destination == null || session != epoch || !channel.equals(currentChannel)) return;
                beginTransfer("Baixando " + line.text() + "…");
                transfer.download(line.id(), kind).thenAcceptAsync(bytes -> {
                    try { Files.write(destination.toPath(), bytes); }
                    catch (IOException ex) { throw new CompletionException(ex); }
                }).whenComplete((unused, failure) -> Platform.runLater(() -> finishTransfer(session, channel,
                        failure, "Arquivo salvo.", "Não foi possível salvar o anexo.")));
                return;
            }
            beginTransfer("Carregando foto…");
            transfer.download(line.id(), kind).thenApplyAsync(bytes -> {
                var photo = new Image(new ByteArrayInputStream(bytes), 380, 260, true, true);
                if (photo.isError()) throw new IllegalArgumentException("Não foi possível visualizar a foto.");
                return photo;
            }).whenComplete((photo, failure) -> Platform.runLater(() -> {
                if (failure == null && epoch == session && channel.equals(currentChannel)) {
                    preview.setImage(photo);
                    preview.setManaged(true);
                    preview.setVisible(true);
                }
                finishTransfer(session, channel, failure, "", "Não foi possível carregar a foto.");
            }));
        } catch (IllegalArgumentException | IllegalStateException ex) { hint.setText(ex.getMessage()); }
    }
    private static Label label(String text, String css) { var label = new Label(text); label.getStyleClass().add(css); return label; }
    private static VBox field(String title, Control field) {
        var label = label(title, "field-label"); label.setLabelFor(field); return new VBox(0, label, field);
    }
    private void refreshMotion() {
        backdrop.setActive(!stopped && (!inRoom || online) && stage != null && stage.isShowing() && !stage.isIconified());
    }
    @Override public void stop() {
        if (stopped) return;
        stopped = true;
        ++epoch;
        ++checkEpoch;
        voice.connection(false, false);
        serverCheck.close();
        backdrop.close();
        if (connection != null) connection.close();
        revokeAccount();
        abandonedRequests.whenComplete((unused, failure) -> accounts.close());
        channelHistory.clear(); drafts.clear(); history.clear(); password.clear();
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
            try { if (line.time() != null) time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(line.time()).atZone(ZoneId.systemDefault())); }
            catch (DateTimeException ignored) { }
            String delivery = Objects.toString(line.delivery(), "");
            String meta = time.isEmpty() || delivery.isEmpty() ? time + delivery : time + " · " + delivery;
            var sender = label(own ? "Você" : line.name(), "sender");
            var bubble = new VBox(5, sender, text);
            if (!meta.isEmpty()) bubble.getChildren().add(label(meta, "message-meta"));
            if (line.attachment()) {
                Attachment kind = Attachment.forName(line.text());
                var actions = new HBox(8);
                if (kind.photo()) {
                    var preview = new ImageView();
                    preview.setPreserveRatio(true);
                    preview.fitWidthProperty().bind(messages.widthProperty().subtract(160).multiply(0.8));
                    preview.setFitHeight(260);
                    preview.setVisible(false);
                    preview.setManaged(false);
                    var show = new Button("Ver foto");
                    show.getStyleClass().add("secondary-button");
                    show.setOnAction(e -> receiveAttachment(line, preview));
                    actions.getChildren().add(show);
                    bubble.getChildren().add(preview);
                }
                var save = new Button(kind.photo() ? "Salvar foto" : "Baixar vídeo");
                save.getStyleClass().add("secondary-button");
                save.setOnAction(e -> receiveAttachment(line, null));
                actions.getChildren().add(save);
                bubble.getChildren().add(actions);
            }
            var copy = new MenuItem("Copiar mensagem"); copy.setOnAction(e -> copyText(line.text()));
            var menu = new ContextMenu(copy);
            if (own && !line.attachment()) {
                var recover = new MenuItem("Editar novamente"); recover.setOnAction(e -> recover(line.text())); menu.getItems().add(recover);
                if (Set.of("Não enviada", "Entrega não confirmada").contains(Objects.toString(line.delivery(), ""))) {
                    var recoverButton = new Button("Recuperar texto"); recoverButton.getStyleClass().add("secondary-button");
                    recoverButton.setOnAction(e -> recover(line.text())); bubble.getChildren().add(recoverButton);
                }
            }
            if (own && (line.attachment() || "Confirmada pelo servidor".equals(line.delivery()))) {
                var erase = new MenuItem("Apagar mensagem…");
                erase.setOnAction(e -> confirmDeletion(line));
                menu.getItems().addAll(new SeparatorMenuItem(), erase);
                var actions = new Button("⋯");
                actions.getStyleClass().add("message-actions");
                actions.setAccessibleText("Ações da mensagem");
                actions.setTooltip(new Tooltip("Ações da mensagem"));
                actions.setOnAction(e -> menu.show(actions, Side.BOTTOM, 0, 0));
                var spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
                bubble.getChildren().remove(sender);
                var top = new HBox(8, sender, spacer, actions);
                top.setAlignment(Pos.CENTER_LEFT);
                bubble.getChildren().addFirst(top);
            }
            bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
            bubble.getStyleClass().add("bubble"); if (own) bubble.getStyleClass().add("own");
            var row = new HBox(bubble); row.setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

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
        copyText("Vamos conversar no " + APP_NAME + "!\nServidor: " + address + "\nCanal: " + currentChannel
                + "\nAbra o Wavecove e entre com sua conta, ou crie uma nesse servidor."
                + "\nSelecione #" + currentChannel + ".");
        hint.setText("Convite copiado com o servidor e o canal.");
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
