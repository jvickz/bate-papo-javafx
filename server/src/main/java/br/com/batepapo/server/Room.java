package br.com.batepapo.server;

import br.com.batepapo.protocol.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** All membership changes and broadcasts are ordered by this room's monitor. */
final class Room {
    interface Transport { void send(String json); void close(); }
    private static final int MAX_CONNECTIONS = 64;
    private static final int MAX_CHANNELS = 20;
    private static final String DEFAULT_CHANNEL = "geral";
    private static final String CHANNELS_CAPABILITY = "channels-v1";
    private static final String VOICE_CAPABILITY = "voice-v1";
    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long INVITE_TIMEOUT = TimeUnit.SECONDS.toNanos(30);
    private static final long INVITE_INTERVAL = TimeUnit.SECONDS.toNanos(3);
    private static final long PROVISION_TIMEOUT = TimeUnit.SECONDS.toNanos(8);
    private static final long NEGOTIATION_TIMEOUT = TimeUnit.SECONDS.toNanos(45);
    private static final long CREDENTIAL_LIFETIME = TimeUnit.MINUTES.toNanos(55);
    private static final int MAX_SIGNAL_BYTES = 10 * 1024;
    private static final int SIGNAL_BYTES_PER_SECOND = 64 * 1024;
    private static final int FRAMES_PER_SECOND = 30;
    private static final int VOICE_FRAMES_PER_SECOND = 128;
    private static final int SIGNALS_PER_SECOND = 48;
    private static final int RECENT_CALL_IDS = 1024;
    private static final ObjectMapper SIGNAL_JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(MAX_SIGNAL_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final byte[] accessCode;
    private final TurnCredentialsProvider turn;
    private final LongSupplier clock;
    private final Map<UUID, Call> calls = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> recentCalls = new LinkedHashSet<>();
    private final Set<String> channels = new LinkedHashSet<>(List.of(DEFAULT_CHANNEL));
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Deque<Event> broadcasts = new ArrayDeque<>();
    private boolean broadcasting;
    private boolean shuttingDown;

    private static final class Member {
        final Transport transport;
        final long connected;
        long seen, ping, window, nextInvite;
        int frames, commands, messages, signals, signalBytes;
        String name, resume, channel;
        boolean supportsChannels, supportsVoice;
        Call call;
        final LinkedHashSet<String> ids = new LinkedHashSet<>();

        Member(Transport transport, long now) {
            this.transport = transport;
            connected = seen = ping = window = nextInvite = now;
        }
    }

    private enum CallPhase { RINGING, PROVISIONING, NEGOTIATING, CONNECTED }

    private static final class Call {
        final UUID id;
        final Member caller, callee;
        final String channel;
        CallPhase phase = CallPhase.RINGING;
        long deadline, credentialDeadline;
        boolean callerConnected, calleeConnected;

        Call(UUID id, Member caller, Member callee, long now) {
            this.id = id;
            this.caller = caller;
            this.callee = callee;
            channel = caller.channel;
            deadline = now + INVITE_TIMEOUT;
        }

        Member other(Member member) { return member == caller ? callee : caller; }
    }

    Room(String token) { this(token, null, System::nanoTime); }

    Room(String token, TurnCredentialsProvider turn, LongSupplier clock) {
        if (token == null || token.length() < 24 || token.length() > 128 || token.isBlank())
            throw new IllegalArgumentException("CHAT_ACCESS_TOKEN deve ter entre 24 e 128 caracteres aleatórios.");
        accessCode = token.getBytes(StandardCharsets.UTF_8);
        this.turn = turn;
        this.clock = clock;
    }

    synchronized void open(String id, Transport transport) {
        if (shuttingDown) { transport.close(); return; }
        if (members.size() >= MAX_CONNECTIONS) {
            transport.send(Protocol.encode(new Event("error", "full", null, "Sala cheia. Tente novamente em instantes.", null, null, null, null)));
            transport.close(); return;
        }
        members.put(id, new Member(transport, clock.getAsLong()));
    }

    void receive(String id, String json) {
        Call accepted;
        synchronized (this) {
            accepted = receiveLocked(id, json);
        }
        if (accepted != null) prepareCall(accepted);
    }

    private Call receiveLocked(String id, String json) {
        Member member = members.get(id);
        if (member == null || shuttingDown) return null;
        long now = clock.getAsLong();
        if (now - member.window >= ONE_SECOND) {
            member.window = now;
            member.frames = member.commands = member.messages = member.signals = member.signalBytes = 0;
        }
        int frameLimit = member.supportsVoice ? VOICE_FRAMES_PER_SECOND : FRAMES_PER_SECOND;
        if (++member.frames > frameLimit) {
            frameLimitReached(id, member);
            return null;
        }
        Event event;
        try { event = Protocol.decode(json); }
        catch (IllegalArgumentException ex) {
            if (allowCommand(id, member)) error(member, "invalid", ex.getMessage());
            return null;
        }
        if (!event.type().equals("callsignal") && !allowCommand(id, member)) return null;
        member.seen = now;
        if (member.name == null) {
            authenticate(id, member, event);
            return null;
        }
        switch (event.type()) {
            case "pong" -> { }
            case "message" -> message(member, event);
            case "create", "join" -> changeChannel(id, member, event);
            case "callinvite", "callaccept", "callreject", "callend", "callsignal", "callconnected" -> {
                return voiceCommand(member, event, now);
            }
            default -> error(member, "invalid", "Evento não permitido.");
        }
        return null;
    }

    private boolean allowCommand(String id, Member member) {
        if (!member.supportsVoice || ++member.commands <= FRAMES_PER_SECOND) return true;
        frameLimitReached(id, member);
        return false;
    }

    private void frameLimitReached(String id, Member member) {
        error(member, "rate", "Muitas solicitações. Conecte novamente.");
        kick(id);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_-]{1,64}");
    }

    private void message(Member member, Event event) {
        String messageId = event.id();
        if (!validId(messageId)) { error(member, "invalid", "Identificador de mensagem inválido."); return; }
        if (++member.messages > 5) { error(member, messageId, "Aguarde um segundo antes de enviar mais mensagens."); return; }
        if (member.supportsChannels && !member.channel.equals(event.channel())) {
            error(member, messageId, "O canal da mensagem não corresponde ao canal atual."); return;
        }
        try { Protocol.validateText(event.text()); }
        catch (IllegalArgumentException ex) { error(member, messageId, ex.getMessage()); return; }
        if (!member.ids.add(messageId)) { error(member, messageId, "Mensagem repetida; não foi reenviada."); return; }
        if (member.ids.size() > 256) member.ids.removeFirst();
        broadcast(new Event("message", messageId, member.name, event.text(), null, null, null, Instant.now().toString(), member.channel));
    }

    private void authenticate(String id, Member member, Event event) {
        if (!event.type().equals("hello") || event.token() == null || event.token().length() > 128 ||
                !MessageDigest.isEqual(accessCode, event.token().getBytes(StandardCharsets.UTF_8))) {
            error(member, "auth", "Código de acesso incorreto."); kick(id); return;
        }
        String name;
        try { name = Protocol.validateName(event.name()); }
        catch (IllegalArgumentException ex) { error(member, "name", ex.getMessage()); kick(id); return; }
        String previousChannel = null;
        var existing = members.entrySet().stream().filter(e -> name.equalsIgnoreCase(e.getValue().name)).findFirst();
        if (existing.isPresent()) {
            Member old = existing.get().getValue();
            if (event.session() == null || !old.resume.equals(event.session())) {
                error(member, "name", "Este apelido já está em uso. Escolha outro."); kick(id); return;
            }
            previousChannel = old.channel;
            member.resume = old.resume;
            member.nextInvite = old.nextInvite;
            members.remove(existing.get().getKey());
            endCall(old.call, "A sessão de um participante foi substituída.", old);
            old.transport.close();
        } else member.resume = UUID.randomUUID().toString();

        member.supportsChannels = CHANNELS_CAPABILITY.equals(event.id());
        member.supportsVoice = member.supportsChannels && VOICE_CAPABILITY.equals(event.text());
        member.channel = DEFAULT_CHANNEL;
        String welcomeText = "Você entrou na sala.";
        if (member.supportsChannels && event.channel() != null) {
            String requested = requestedChannel(event.channel());
            if (channels.contains(requested)) member.channel = requested;
            else welcomeText = "O canal solicitado não está disponível. Você entrou em geral.";
        }
        member.name = name;
        send(member, new Event("welcome", null, name, welcomeText, null, member.resume, null, Instant.now().toString(),
                member.supportsChannels ? member.channel : null));
        if (previousChannel != null && !previousChannel.equals(member.channel))
            announce(previousChannel, name + " saiu da sala.");
        if (members.get(id) != member) return;
        if (member.supportsChannels) send(member, catalog());
        if (members.get(id) == member && member.supportsVoice) {
            send(member, new Event("voice", turn == null ? "unavailable" : "ready", null,
                    turn == null ? "O servidor ainda não está configurado para chamadas de voz." : "Chamadas de voz disponíveis.",
                    null, null, null, null));
        }
        if (members.get(id) == member) announce(member.channel, name + " entrou na sala.");
    }

    private static String requestedChannel(String value) {
        try { return Protocol.validateChannel(value); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private void changeChannel(String id, Member member, Event event) {
        if (!member.supportsChannels) { error(member, "invalid", "Evento não permitido."); return; }
        String requestId = event.id();
        if (!validId(requestId)) { error(member, "invalid", "Identificador de solicitação inválido."); return; }
        String channel;
        try { channel = Protocol.validateChannel(event.channel()); }
        catch (IllegalArgumentException ex) { error(member, requestId, ex.getMessage()); return; }

        boolean create = event.type().equals("create");
        if (create) {
            if (channels.contains(channel)) { error(member, requestId, "Este canal já existe."); return; }
            if (channels.size() >= MAX_CHANNELS) { error(member, requestId, "O servidor atingiu o limite de canais."); return; }
            channels.add(channel);
        } else if (!channels.contains(channel)) {
            error(member, requestId, "Este canal não existe."); return;
        }

        String previousChannel = member.channel;
        member.channel = channel;
        if (!previousChannel.equals(channel))
            endCall(member.call, "Um participante mudou de canal.", null);
        if (members.get(id) != member) return;
        send(member, new Event("joined", requestId, null, null, null, null, null, null, channel));
        if (previousChannel.equals(channel)) {
            presence(channel);
            return;
        }
        announce(previousChannel, member.name + " saiu da sala.");
        if (members.get(id) == member && channel.equals(member.channel))
            announce(channel, member.name + " entrou na sala.");
        if (create) broadcast(catalog());
    }

    private Call voiceCommand(Member member, Event event, long now) {
        if (!member.supportsVoice) {
            error(member, "invalid", "Evento não permitido.");
            return null;
        }
        UUID id = callId(event.id());
        if (id == null) {
            error(member, "invalid", "Identificador de chamada inválido.");
            return null;
        }
        if (event.channel() != null && !member.channel.equals(event.channel())) {
            callError(member, id, null, "A chamada não corresponde ao canal atual.");
            return null;
        }
        if (event.type().equals("callinvite")) {
            invite(member, id, event.name(), now);
            return null;
        }

        Call call = member.call;
        if (call == null || !call.id.equals(id)) {
            callError(member, id, null, "Esta chamada não está ativa.");
            return null;
        }
        if (expireCall(call, now)) return null;
        Member other = call.other(member);
        if (!call.channel.equals(member.channel) || !call.channel.equals(other.channel)
                || (event.name() != null && !other.name.equalsIgnoreCase(event.name()))) {
            callError(member, id, other.name, "Destinatário da chamada inválido.");
            return null;
        }

        switch (event.type()) {
            case "callaccept", "callreject" -> {
                if (member != call.callee || call.phase != CallPhase.RINGING) {
                    callError(member, id, other.name, "O convite já não está aguardando resposta.");
                } else if (event.type().equals("callreject")) {
                    endCall(call, "Convite recusado.", null);
                } else {
                    call.phase = CallPhase.PROVISIONING;
                    call.deadline = now + PROVISION_TIMEOUT;
                    call.credentialDeadline = now + CREDENTIAL_LIFETIME;
                    return call;
                }
            }
            case "callend" -> endCall(call, "Chamada encerrada.", null);
            case "callsignal" -> forwardSignal(call, member, event.text());
            case "callconnected" -> {
                if (call.phase == CallPhase.NEGOTIATING) {
                    if (member == call.caller) call.callerConnected = true;
                    else call.calleeConnected = true;
                    if (call.callerConnected && call.calleeConnected) {
                        call.phase = CallPhase.CONNECTED;
                        call.deadline = call.credentialDeadline;
                    }
                } else if (call.phase != CallPhase.CONNECTED) {
                    callError(member, id, other.name, "A chamada ainda não está em conexão.");
                }
            }
        }
        return null;
    }

    private static UUID callId(String text) {
        if (text == null || text.length() != 36) return null;
        try {
            var id = UUID.fromString(text);
            return id.toString().equalsIgnoreCase(text) ? id : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void invite(Member caller, UUID id, String target, long now) {
        String name;
        try { name = Protocol.validateName(target); }
        catch (IllegalArgumentException ex) {
            callError(caller, id, null, "Selecione alguém do canal para ligar.");
            return;
        }
        if (turn == null) {
            callError(caller, id, name, "Chamadas de voz indisponíveis neste servidor.");
            return;
        }

        Member callee = members.values().stream()
                .filter(member -> caller.channel.equals(member.channel) && name.equalsIgnoreCase(member.name))
                .findFirst().orElse(null);
        if (callee == null || callee == caller || !callee.supportsVoice) {
            callError(caller, id, name, "Este participante não está disponível para chamadas de voz.");
            return;
        }
        if (caller.call != null || callee.call != null) {
            callError(caller, id, callee.name, "Um dos participantes já está em chamada.");
            return;
        }
        if (now - caller.nextInvite < 0) {
            callError(caller, id, callee.name, "Aguarde um instante antes de ligar novamente.");
            return;
        }
        if (calls.containsKey(id) || !recentCalls.add(id)) {
            callError(caller, id, callee.name, "Identificador de chamada já usado.");
            return;
        }
        if (recentCalls.size() > RECENT_CALL_IDS) recentCalls.removeFirst();

        caller.nextInvite = now + INVITE_INTERVAL;
        var call = new Call(id, caller, callee, now);
        calls.put(id, call);
        caller.call = callee.call = call;
        sendCall(caller, call, "callringing", null);
        if (calls.get(id) == call) sendCall(callee, call, "callincoming", null);
    }

    private void prepareCall(Call call) {
        try {
            // Use a distinct short-lived credential for each peer. No provider I/O under the room monitor.
            turn.issue().thenCombine(turn.issue(), Credentials::new)
                    .whenComplete((credentials, failure) -> finishPreparation(call, credentials, failure));
        } catch (RuntimeException failure) {
            finishPreparation(call, null, failure);
        }
    }

    private record Credentials(List<VoiceConfig.IceServer> caller, List<VoiceConfig.IceServer> callee) {}

    private synchronized void finishPreparation(Call call, Credentials credentials, Throwable failure) {
        if (calls.get(call.id) != call || call.phase != CallPhase.PROVISIONING) return;
        long now = clock.getAsLong();
        if (expireCall(call, now)) return;

        String callerConfig, calleeConfig;
        try {
            if (failure != null || credentials.caller().isEmpty() || credentials.callee().isEmpty())
                throw new IllegalArgumentException();
            callerConfig = new VoiceConfig(credentials.caller(), true).encode();
            calleeConfig = new VoiceConfig(credentials.callee(), false).encode();
        } catch (RuntimeException ignored) {
            endCall(call, "Serviço de voz indisponível. Tente novamente mais tarde.", null);
            return;
        }
        call.phase = CallPhase.NEGOTIATING;
        call.deadline = now + NEGOTIATION_TIMEOUT;
        sendCall(call.callee, call, "callready", calleeConfig);
        if (calls.get(call.id) == call) sendCall(call.caller, call, "callready", callerConfig);
    }

    private void forwardSignal(Call call, Member member, String text) {
        Member other = call.other(member);
        if (call.phase != CallPhase.NEGOTIATING && call.phase != CallPhase.CONNECTED) {
            callError(member, call.id, other.name, "A chamada ainda não está em conexão.");
            return;
        }
        if (++member.signals > SIGNALS_PER_SECOND) {
            callError(member, call.id, other.name, "Muitos dados de voz. Aguarde um instante.");
            return;
        }
        int bytes = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SIGNAL_BYTES || !validSignal(text)) {
            callError(member, call.id, other.name, "Dados de conexão inválidos.");
            return;
        }
        if (bytes > SIGNAL_BYTES_PER_SECOND - member.signalBytes) {
            callError(member, call.id, other.name, "Muitos dados de voz. Aguarde um instante.");
            return;
        }
        member.signalBytes += bytes;
        sendCall(other, call, "callsignal", text);
    }

    private static boolean validSignal(String text) {
        if (text == null || text.isBlank()) return false;
        try { return SIGNAL_JSON.readTree(text).isObject(); }
        catch (Exception ignored) { return false; }
    }

    private boolean expireCall(Call call, long now) {
        if (now - call.deadline < 0) return false;
        String reason = switch (call.phase) {
            case RINGING -> "O convite não foi atendido.";
            case PROVISIONING -> "Serviço de voz indisponível. Tente novamente mais tarde.";
            case NEGOTIATING -> "A conexão de voz demorou demais.";
            case CONNECTED -> "A chamada atingiu o limite de 55 minutos.";
        };
        endCall(call, reason, null);
        return true;
    }

    private void endCall(Call call, String reason, Member disconnected) {
        if (call == null || !calls.remove(call.id, call)) return;
        call.caller.call = call.callee.call = null;
        if (call.caller != disconnected) sendCall(call.caller, call, "callended", reason);
        if (call.callee != disconnected) sendCall(call.callee, call, "callended", reason);
    }

    private void sendCall(Member recipient, Call call, String type, String text) {
        send(recipient, new Event(type, call.id.toString(), call.other(recipient).name, text,
                null, null, null, null, call.channel));
    }

    private void callError(Member member, UUID id, String peer, String text) {
        send(member, new Event("callerror", id.toString(), peer, text, null, null, null, null, member.channel));
    }

    private Event catalog() {
        return new Event("channels", null, null, null, null, null, List.copyOf(channels), null);
    }

    private void send(Member member, Event event) {
        member.transport.send(Protocol.encode(event));
    }

    private void error(Member member, String id, String text) {
        send(member, new Event("error", id, null, text, null, null, null, null));
    }

    private void broadcast(Event event) {
        broadcasts.addLast(event);
        if (broadcasting) return;

        // A write can trigger a synchronous disconnect. Finish this broadcast before its updates.
        broadcasting = true;
        try {
            while (!broadcasts.isEmpty()) deliver(broadcasts.removeFirst());
        } finally {
            broadcasting = false;
        }
    }

    private void deliver(Event event) {
        String channel = event.channel();
        String currentJson = Protocol.encode(event);
        String legacyJson = DEFAULT_CHANNEL.equals(channel)
                ? Protocol.encode(new Event(event.type(), event.id(), event.name(), event.text(),
                        event.token(), event.session(), event.users(), event.time()))
                : null;
        for (var entry : List.copyOf(members.entrySet())) {
            Member member = entry.getValue();
            if (member.name == null || members.get(entry.getKey()) != member) continue;
            if (channel != null && !channel.equals(member.channel)) continue;
            if (member.supportsChannels) member.transport.send(currentJson);
            else if (legacyJson != null) member.transport.send(legacyJson);
        }
    }

    private void presence(String channel) {
        var names = members.values().stream().filter(member -> channel.equals(member.channel))
                .map(member -> member.name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        broadcast(new Event("presence", null, null, null, null, null, names, null, channel));
    }

    private void announce(String channel, String text) {
        broadcast(new Event("system", null, null, text, null, null, null, null, channel));
        presence(channel);
    }

    synchronized void remove(String id) {
        var removed = members.remove(id);
        if (removed != null && removed.name != null) {
            endCall(removed.call, "Um participante perdeu a conexão.", removed);
            if (!shuttingDown) announce(removed.channel, removed.name + " saiu da sala.");
        }
    }

    private void kick(String id) { var m = members.get(id); remove(id); if (m != null) m.transport.close(); }

    synchronized void tick() {
        long now = clock.getAsLong();
        for (Call call : List.copyOf(calls.values())) expireCall(call, now);
        for (var entry : List.copyOf(members.entrySet())) {
            var m = entry.getValue();
            if ((m.name == null && now - m.connected > 10_000_000_000L) || now - m.seen > 45_000_000_000L) { kick(entry.getKey()); continue; }
            if (m.name != null && now - m.ping > 15_000_000_000L) {
                m.ping = now; send(m, Event.of("ping", null));
            }
        }
    }

    synchronized void shutdown() {
        shuttingDown = true;
        var closing = List.copyOf(members.values());
        members.clear();
        broadcasts.clear();
        for (Call call : List.copyOf(calls.values())) endCall(call, "Servidor encerrando.", null);
        for (var m : closing) m.transport.close();
    }
}
