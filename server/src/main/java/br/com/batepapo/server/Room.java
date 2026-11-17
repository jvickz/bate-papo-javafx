package br.com.batepapo.server;

import br.com.batepapo.protocol.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** All membership changes and broadcasts are ordered by this room's monitor. */
final class Room {
    interface Transport { void send(String json); void close(); }
    private static final int MAX_CONNECTIONS = 64;
    private static final int MAX_CHANNELS = 20;
    private static final String DEFAULT_CHANNEL = "geral";
    private static final String CHANNELS_CAPABILITY = "channels-v1";

    private final byte[] accessCode;
    private final Set<String> channels = new LinkedHashSet<>(List.of(DEFAULT_CHANNEL));
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Deque<Event> broadcasts = new ArrayDeque<>();
    private boolean broadcasting;

    private static final class Member {
        final Transport transport;
        final long connected = System.nanoTime();
        long seen = connected, ping = connected, window = connected;
        int frames, messages;
        String name, resume, channel;
        boolean supportsChannels;
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        Member(Transport transport) { this.transport = transport; }
    }

    Room(String token) {
        if (token == null || token.length() < 24 || token.length() > 128 || token.isBlank())
            throw new IllegalArgumentException("CHAT_ACCESS_TOKEN deve ter entre 24 e 128 caracteres aleatórios.");
        accessCode = token.getBytes(StandardCharsets.UTF_8);
    }

    synchronized void open(String id, Transport transport) {
        if (members.size() >= MAX_CONNECTIONS) {
            transport.send(Protocol.encode(new Event("error", "full", null, "Sala cheia. Tente novamente em instantes.", null, null, null, null)));
            transport.close(); return;
        }
        members.put(id, new Member(transport));
    }

    synchronized void receive(String id, String json) {
        Member member = members.get(id);
        if (member == null) return;
        long now = System.nanoTime();
        if (now - member.window >= 1_000_000_000L) { member.window = now; member.messages = 0; member.frames = 0; }
        if (++member.frames > 30) { error(member, "rate", "Muitas solicitações. Conecte novamente."); kick(id); return; }
        Event event;
        try { event = Protocol.decode(json); }
        catch (IllegalArgumentException ex) { error(member, "invalid", ex.getMessage()); return; }
        member.seen = now;
        if (member.name == null) {
            authenticate(id, member, event); return;
        }
        switch (event.type()) {
            case "pong" -> { }
            case "message" -> message(member, event);
            case "create", "join" -> changeChannel(id, member, event);
            default -> error(member, "invalid", "Evento não permitido.");
        }
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
            members.remove(existing.get().getKey()); old.transport.close();
        } else member.resume = UUID.randomUUID().toString();

        member.supportsChannels = CHANNELS_CAPABILITY.equals(event.id());
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
        if (removed != null && removed.name != null)
            announce(removed.channel, removed.name + " saiu da sala.");
    }

    private void kick(String id) { var m = members.get(id); remove(id); if (m != null) m.transport.close(); }

    synchronized void tick() {
        long now = System.nanoTime();
        for (var entry : List.copyOf(members.entrySet())) {
            var m = entry.getValue();
            if ((m.name == null && now - m.connected > 10_000_000_000L) || now - m.seen > 45_000_000_000L) { kick(entry.getKey()); continue; }
            if (m.name != null && now - m.ping > 15_000_000_000L) {
                m.ping = now; send(m, Event.of("ping", null));
            }
        }
    }

    synchronized void shutdown() {
        var closing = List.copyOf(members.values());
        members.clear();
        broadcasts.clear();
        for (var m : closing) m.transport.close();
    }
}
