package br.com.batepapo.client;

import br.com.batepapo.protocol.*;
import javax.net.ssl.SSLException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.*;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One event loop owns connection state; generation + attempt invalidate late callbacks. */
public final class ChatConnection implements AutoCloseable {
    private final Consumer<Event> events;
    private final Consumer<String> states;
    private final ScheduledThreadPoolExecutor loop = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("chat-client").factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, ScheduledFuture<?>> pending = new LinkedHashMap<>();
    private volatile boolean connected, closed, switching;
    private volatile String channel = "geral";
    private String changeRequest;
    private ScheduledFuture<?> changeTimeout;
    private boolean wanted;
    private URI uri;
    private String name, token, resume;
    private volatile String attachmentSession;
    private WebSocket socket;
    private volatile long attempt;
    private long lastTraffic;
    private int retries;
    private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
    public ChatConnection(Consumer<Event> events, Consumer<String> states) {
        this.events = events; this.states = states;
        loop.setRemoveOnCancelPolicy(true);
        loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loop.scheduleAtFixedRate(() -> {
            if (connected && System.nanoTime() - lastTraffic > TimeUnit.SECONDS.toNanos(50))
                lost(generation.get(), attempt, new HttpTimeoutException("Heartbeat timeout"));
        }, 10, 10, TimeUnit.SECONDS);
    }
    public void connect(String address, String nickname, String sessionToken) {
        if (closed) throw new IllegalStateException("Aplicativo encerrado.");
        URI validated = Protocol.serverUri(address);
        String validatedName = Protocol.validateName(nickname);
        if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128)
            throw new IllegalArgumentException("Sessão inválida. Entre novamente na sua conta.");
        long g = generation.incrementAndGet(); connected = false;
        execute(() -> {
            stop(); uri = validated; name = validatedName; token = sessionToken; resume = null; retries = 0; channel = "geral"; wanted = true;
            if (generation.get() == g) { states.accept("connecting"); begin(g); }
        });
    }
    private void begin(long g) {
        if (!wanted || generation.get() != g) return;
        long a = ++attempt;
        sends = CompletableFuture.completedFuture(null);
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(uri, new Listener(g, a))
                .whenComplete((ws, error) -> { if (error != null) execute(() -> lost(g, a, error)); });
        loop.schedule(() -> {
            if (active(g, a) && !connected) lost(g, a, new HttpTimeoutException("Connection timeout"));
        }, 20, TimeUnit.SECONDS);
    }
    private boolean active(long g, long a) { return wanted && generation.get() == g && attempt == a; }
    private void write(Event event, long g, long a) {
        if (!active(g, a) || socket == null) return;
        WebSocket target = socket;
        sends = sends.thenCompose(ignored -> target.sendText(Protocol.encode(event), true));
        sends.whenComplete((ignored, error) -> { if (error != null) execute(() -> lost(g, a, error)); });
    }
    /**
     * A returned ID produces a local pending record before any queued rejection, so the UI can
     * recover the text in its original channel. Explicit connect/disconnect invalidates that
     * generation and suppresses queued callbacks; automatic reconnect remains in the same generation.
     */
    public String send(String text) {
        Protocol.validateText(text);
        if (!connected || closed || switching) throw new IllegalStateException("Aguarde a conexão e a confirmação do canal antes de enviar.");
        String id = UUID.randomUUID().toString(), targetChannel = channel, sender = name; long g = generation.get();
        execute(() -> {
            if (generation.get() != g) return;
            events.accept(new Event("pending", id, sender, text, null, null, null, null, targetChannel));
            if (generation.get() != g) return;
            if (!connected || switching || !channel.equals(targetChannel) || pending.size() >= 100) {
                events.accept(new Event("error", id, null, "Mensagem não enviada. Aguarde a conexão e recupere o texto para tentar novamente.", null, null, null, null, targetChannel)); return;
            }
            pending.put(id, loop.schedule(() -> {
                if (pending.remove(id) != null) uncertain(id);
            }, 15, TimeUnit.SECONDS));
            write(new Event("message", id, null, text, null, null, null, null, targetChannel), g, attempt);
        });
        return id;
    }
    public void createChannel(String name) { changeChannel("create", name); }
    public void joinChannel(String name) { changeChannel("join", name); }

    AttachmentTransfer attachmentTransfer(String expectedChannel) {
        String session = attachmentSession;
        if (!connected || switching || session == null || !channel.equals(expectedChannel))
            throw new IllegalStateException("Entre no canal para usar os anexos.");
        return new AttachmentTransfer(http, uri, session, expectedChannel);
    }
    /** Private signaling is never queued for replay after a reconnect or rendered as chat text. */
    public void voice(Event event) {
        if (event == null || !Set.of("callinvite", "callaccept", "callreject", "callend", "callsignal", "callconnected").contains(event.type()))
            throw new IllegalArgumentException("Comando de chamada inválido.");
        try {
            if (event.id() == null || !UUID.fromString(event.id()).toString().equals(event.id())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Identificador de chamada inválido."); }
        String target = event.type().equals("callinvite") ? Protocol.validateName(event.name()) : null;
        String signal = event.type().equals("callsignal") ? event.text() : null;
        if (event.type().equals("callsignal") && (signal == null || signal.isBlank() || signal.length() > 12000))
            throw new IllegalArgumentException("Sinal de chamada inválido.");
        var clean = new Event(event.type(), event.id(), target, signal, null, null, null, null);
        if (Protocol.encode(clean).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Protocol.MAX_BYTES)
            throw new IllegalArgumentException("Sinal de chamada muito grande.");
        boolean ending = event.type().equals("callend");
        if (!connected || closed || (switching && !ending)) throw new IllegalStateException("Aguarde a conexão para ligar.");
        long g = generation.get(), a = attempt;
        execute(() -> {
            if (generation.get() != g) return;
            if (!active(g, a) || !connected || (switching && !ending)) {
                events.accept(new Event("callerror", clean.id(), null, "A conexão mudou. Tente ligar novamente.", null, null, null, null));
                return;
            }
            write(clean, g, a);
        });
    }
    private synchronized void changeChannel(String type, String name) {
        String destination = Protocol.validateChannel(name);
        if (!connected || closed || switching) throw new IllegalStateException("Aguarde a confirmação da conexão ou do canal.");
        switching = true;
        long g = generation.get();
        execute(() -> {
            if (!connected || generation.get() != g) { switching = false; return; }
            clearPending();
            changeRequest = UUID.randomUUID().toString();
            String request = changeRequest; long a = attempt;
            states.accept("switching");
            changeTimeout = loop.schedule(() -> {
                if (active(g, a) && request.equals(changeRequest)) {
                    events.accept(new Event("error", request, null, "Troca de canal não confirmada. Reconectando para verificar o destino.", null, null, null, null));
                    lost(g, a);
                }
            }, 10, TimeUnit.SECONDS);
            write(new Event(type, request, null, null, null, null, null, null, destination), g, a);
        });
    }
    private void clearChange() {
        if (changeTimeout != null) changeTimeout.cancel(false);
        changeTimeout = null; changeRequest = null; switching = false;
    }
    private void receive(Event event, long g, long a) {
        if (!active(g, a)) return;
        lastTraffic = System.nanoTime();
        switch (event.type()) {
            case "welcome" -> {
                if (connected) return;
                channel = event.channel() == null ? "geral" : Protocol.validateChannel(event.channel());
                clearChange(); resume = event.session(); connected = true; retries = 0;
                events.accept(event); states.accept("connected");
            }
            case "joined" -> {
                if (!connected || changeRequest == null || !changeRequest.equals(event.id())) return;
                channel = Protocol.validateChannel(event.channel()); clearChange();
                events.accept(event); states.accept("connected");
            }
            case "channels" -> { if (connected) events.accept(event); }
            case "attachments" -> {
                if (connected && Attachment.validId(event.session())) {
                    attachmentSession = event.session();
                    events.accept(Event.of("attachments", null));
                }
            }
            case "attachment" -> {
                if (connected && channel.equals(event.channel()) && Attachment.validId(event.id())
                        && event.name() != null) {
                    try { Attachment.forName(event.text()); }
                    catch (IllegalArgumentException ignored) { return; }
                    events.accept(event);
                }
            }
            case "voice", "callringing", "callincoming", "callready", "callsignal", "callended", "callerror" -> {
                if (connected) events.accept(event);
            }
            case "ping" -> write(Event.of("pong", null), g, a);
            case "message" -> {
                if (!connected) return;
                if (name.equals(event.name())) confirm(event.id());
                if (event.channel() == null || channel.equals(event.channel())) events.accept(event);
            }
            case "error" -> {
                if (changeRequest != null && changeRequest.equals(event.id())) { clearChange(); states.accept("connected"); }
                confirm(event.id()); events.accept(event);
                if (Set.of("auth", "name", "full", "rate").contains(Objects.toString(event.id(), ""))) {
                    generation.incrementAndGet(); stop(); states.accept("disconnected");
                }
            }
            case "presence", "system" -> { if (connected && (event.channel() == null || channel.equals(event.channel()))) events.accept(event); }
            default -> { }
        }
    }
    private void confirm(String id) { var timeout = pending.remove(id); if (timeout != null) timeout.cancel(false); }
    private void uncertain(String id) {
        events.accept(new Event("uncertain", id, null, "Entrega não confirmada. A mensagem pode ter chegado.", null, null, null, null));
    }
    private void clearPending() {
        pending.forEach((id, timeout) -> { timeout.cancel(false); uncertain(id); }); pending.clear();
    }
    private void lost(long g, long a) { lost(g, a, null); }
    private void lost(long g, long a, Throwable failure) {
        if (!active(g, a)) return;
        attempt++; connected = false;
        attachmentSession = null;
        if (socket != null) { socket.abort(); socket = null; }
        clearPending(); clearChange(); states.accept("reconnecting");
        long delay = Math.min(30, 1L << Math.min(5, retries++));
        if (generation.get() != g || !wanted) return;
        events.accept(new Event("connection", "network", null,
                failureReason(failure) + " Tentativa " + retries + ". Nova tentativa em " + delay + " s.",
                null, null, null, null));
        loop.schedule(() -> begin(g), delay, TimeUnit.SECONDS);
    }
    /** Only fixed, actionable wording and an HTTP status may reach the UI; never exception messages. */
    private static String failureReason(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof UnknownHostException || failure instanceof UnresolvedAddressException)
                return "Não foi possível localizar o servidor. Confira o endereço e sua conexão com a internet.";
            if (failure instanceof SSLException)
                return "Não foi possível validar a conexão segura. Confira o endereço e a data e hora do Windows.";
            if (failure instanceof WebSocketHandshakeException handshake)
                return "O servidor respondeu HTTP " + handshake.getResponse().statusCode() + ". Confira o endereço ou aguarde o serviço iniciar.";
            if (failure instanceof HttpTimeoutException || failure instanceof TimeoutException)
                return "O servidor demorou para responder. Confira sua internet ou aguarde o serviço iniciar.";
            if (failure instanceof IllegalArgumentException)
                return "O servidor enviou uma resposta inválida. Confira o endereço do Wavecove.";
        }
        return "Não foi possível manter a conexão com o servidor. Confira sua internet ou aguarde o serviço iniciar.";
    }
    public void disconnect() {
        generation.incrementAndGet(); connected = false;
        execute(() -> { stop(); states.accept("disconnected"); });
    }
    private void stop() {
        wanted = false; connected = false; attachmentSession = null; attempt++;
        if (socket != null) { socket.abort(); socket = null; }
        clearPending(); clearChange(); token = null; resume = null;
    }
    private void execute(Runnable work) { try { loop.execute(work); } catch (RejectedExecutionException ignored) { } }
    @Override public void close() {
        if (closed) return;
        closed = true; disconnect(); loop.shutdown(); http.shutdownNow();
    }
    private final class Listener implements WebSocket.Listener {
        private final long g, a;
        private final StringBuilder fragments = new StringBuilder();
        Listener(long g, long a) { this.g = g; this.a = a; }
        @Override public void onOpen(WebSocket ws) {
            execute(() -> {
                if (!active(g, a)) { ws.abort(); return; }
                socket = ws; lastTraffic = System.nanoTime();
                write(new Event("hello", "channels-v1", name, "voice-v1", token, resume,
                        List.of(Attachment.CAPABILITY), null, channel), g, a);
                ws.request(1);
            });
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            if (fragments.length() + text.length() > Protocol.MAX_BYTES) {
                ws.abort(); execute(() -> lost(g, a)); return null;
            }
            fragments.append(text);
            if (last) {
                try { var e = Protocol.decode(fragments.toString()); execute(() -> receive(e, g, a)); }
                catch (IllegalArgumentException ex) { ws.abort(); execute(() -> lost(g, a, ex)); }
                fragments.setLength(0);
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { execute(() -> lost(g, a)); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { execute(() -> lost(g, a, error)); }
    }
}
