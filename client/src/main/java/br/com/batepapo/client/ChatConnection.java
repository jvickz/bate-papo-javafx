package br.com.batepapo.client;

import br.com.batepapo.protocol.*;
import java.net.URI;
import java.net.http.*;
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
    private volatile boolean connected, closed;
    private boolean wanted;
    private URI uri;
    private String name, token, resume;
    private WebSocket socket;
    private long attempt, lastTraffic;
    private int retries;
    private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
    public ChatConnection(Consumer<Event> events, Consumer<String> states) {
        this.events = events; this.states = states;
        loop.setRemoveOnCancelPolicy(true);
        loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loop.scheduleAtFixedRate(() -> {
            if (connected && System.nanoTime() - lastTraffic > TimeUnit.SECONDS.toNanos(50)) lost(generation.get(), attempt);
        }, 10, 10, TimeUnit.SECONDS);
    }
    public void connect(String address, String nickname, String accessCode) {
        if (closed) throw new IllegalStateException("Aplicativo encerrado.");
        URI validated = Protocol.serverUri(address);
        String validatedName = Protocol.validateName(nickname);
        if (accessCode == null || accessCode.isBlank() || accessCode.length() > 128)
            throw new IllegalArgumentException("Informe o código de acesso da sala.");
        long g = generation.incrementAndGet(); connected = false;
        execute(() -> {
            stop(); uri = validated; name = validatedName; token = accessCode; resume = null; retries = 0; wanted = true;
            if (generation.get() == g) { states.accept("connecting"); begin(g); }
        });
    }
    private void begin(long g) {
        if (!wanted || generation.get() != g) return;
        long a = ++attempt;
        sends = CompletableFuture.completedFuture(null);
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(uri, new Listener(g, a))
                .whenComplete((ws, error) -> { if (error != null) execute(() -> lost(g, a)); });
        loop.schedule(() -> { if (active(g, a) && !connected) lost(g, a); }, 20, TimeUnit.SECONDS);
    }
    private boolean active(long g, long a) { return wanted && generation.get() == g && attempt == a; }
    private void write(Event event, long g, long a) {
        if (!active(g, a) || socket == null) return;
        WebSocket target = socket;
        sends = sends.thenCompose(ignored -> target.sendText(Protocol.encode(event), true));
        sends.whenComplete((ignored, error) -> { if (error != null) execute(() -> lost(g, a)); });
    }
    public String send(String text) {
        Protocol.validateText(text);
        if (!connected || closed) throw new IllegalStateException("Aguarde a conexão antes de enviar.");
        String id = UUID.randomUUID().toString(); long g = generation.get();
        execute(() -> {
            if (!connected || generation.get() != g || pending.size() >= 100) {
                events.accept(new Event("error", id, null, "Mensagem não enviada. Aguarde a conexão e tente novamente.", null, null, null, null)); return;
            }
            events.accept(new Event("pending", id, name, text, null, null, null, null));
            pending.put(id, loop.schedule(() -> {
                if (pending.remove(id) != null) uncertain(id);
            }, 15, TimeUnit.SECONDS));
            write(new Event("message", id, null, text, null, null, null, null), g, attempt);
        });
        return id;
    }
    private void receive(Event event, long g, long a) {
        if (!active(g, a)) return;
        lastTraffic = System.nanoTime();
        switch (event.type()) {
            case "welcome" -> {
                if (connected) return;
                resume = event.session(); connected = true; retries = 0;
                events.accept(event); states.accept("connected");
            }
            case "ping" -> write(Event.of("pong", null), g, a);
            case "message" -> {
                if (!connected) return;
                if (name.equals(event.name())) confirm(event.id());
                events.accept(event);
            }
            case "error" -> {
                confirm(event.id()); events.accept(event);
                if (Set.of("auth", "name", "full", "rate").contains(Objects.toString(event.id(), ""))) {
                    generation.incrementAndGet(); stop(); states.accept("disconnected");
                }
            }
            case "presence", "system" -> { if (connected) events.accept(event); }
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
    private void lost(long g, long a) {
        if (!active(g, a)) return;
        attempt++; connected = false;
        if (socket != null) { socket.abort(); socket = null; }
        clearPending(); states.accept("reconnecting");
        long delay = Math.min(30, 1L << Math.min(5, retries++));
        loop.schedule(() -> begin(g), delay, TimeUnit.SECONDS);
    }
    public void disconnect() {
        generation.incrementAndGet(); connected = false;
        execute(() -> { stop(); states.accept("disconnected"); });
    }
    private void stop() {
        wanted = false; connected = false; attempt++;
        if (socket != null) { socket.abort(); socket = null; }
        clearPending(); token = null; resume = null;
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
                write(new Event("hello", null, name, null, token, resume, null, null), g, a);
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
                catch (IllegalArgumentException ex) { ws.abort(); execute(() -> lost(g, a)); }
                fragments.setLength(0);
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { execute(() -> lost(g, a)); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { execute(() -> lost(g, a)); }
    }
}
