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
    private final byte[] accessCode;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private static final class Member {
        final Transport transport;
        final long connected = System.nanoTime();
        long seen = connected, ping = connected, window = connected;
        int frames, messages;
        String name, resume;
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
        if (event.type().equals("pong")) return;
        if (!event.type().equals("message")) { error(member, "invalid", "Evento não permitido."); return; }
        String messageId = event.id();
        if (messageId == null || !messageId.matches("[a-zA-Z0-9_-]{1,64}")) { error(member, "invalid", "Identificador de mensagem inválido."); return; }
        if (++member.messages > 5) { error(member, messageId, "Aguarde um segundo antes de enviar mais mensagens."); return; }
        try { Protocol.validateText(event.text()); }
        catch (IllegalArgumentException ex) { error(member, messageId, ex.getMessage()); return; }
        if (!member.ids.add(messageId)) { error(member, messageId, "Mensagem repetida; não foi reenviada."); return; }
        if (member.ids.size() > 256) member.ids.removeFirst();
        broadcast(new Event("message", messageId, member.name, event.text(), null, null, null, Instant.now().toString()));
    }
    private void authenticate(String id, Member member, Event event) {
        if (!event.type().equals("hello") || event.token() == null || event.token().length() > 128 ||
                !MessageDigest.isEqual(accessCode, event.token().getBytes(StandardCharsets.UTF_8))) {
            error(member, "auth", "Código de acesso incorreto."); kick(id); return;
        }
        String name;
        try { name = Protocol.validateName(event.name()); }
        catch (IllegalArgumentException ex) { error(member, "name", ex.getMessage()); kick(id); return; }
        var existing = members.entrySet().stream().filter(e -> e.getValue().name != null && e.getValue().name.equalsIgnoreCase(name)).findFirst();
        if (existing.isPresent()) {
            Member old = existing.get().getValue();
            if (event.session() == null || !old.resume.equals(event.session())) {
                error(member, "name", "Este apelido já está em uso. Escolha outro."); kick(id); return;
            }
            member.resume = old.resume;
            members.remove(existing.get().getKey()); old.transport.close();
        } else member.resume = UUID.randomUUID().toString();
        member.name = name;
        member.transport.send(Protocol.encode(new Event("welcome", null, name, "Você entrou na sala.", null, member.resume, null, Instant.now().toString())));
        broadcast(Event.of("system", name + " entrou na sala."));
        presence();
    }
    private void error(Member member, String id, String text) {
        member.transport.send(Protocol.encode(new Event("error", id, null, text, null, null, null, null)));
    }
    private void broadcast(Event e) {
        String json = Protocol.encode(e);
        members.values().stream().filter(m -> m.name != null).forEach(m -> m.transport.send(json));
    }
    private void presence() {
        var names = members.values().stream().map(m -> m.name).filter(Objects::nonNull).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        broadcast(new Event("presence", null, null, null, null, null, names, null));
    }
    synchronized void remove(String id) {
        var removed = members.remove(id);
        if (removed != null && removed.name != null) {
            broadcast(Event.of("system", removed.name + " saiu da sala.")); presence();
        }
    }
    private void kick(String id) { var m = members.get(id); remove(id); if (m != null) m.transport.close(); }
    synchronized void tick() {
        long now = System.nanoTime();
        for (var entry : List.copyOf(members.entrySet())) {
            var m = entry.getValue();
            if ((m.name == null && now - m.connected > 10_000_000_000L) || now - m.seen > 45_000_000_000L) { kick(entry.getKey()); continue; }
            if (m.name != null && now - m.ping > 15_000_000_000L) {
                m.ping = now; m.transport.send(Protocol.encode(Event.of("ping", null)));
            }
        }
    }
    synchronized void shutdown() { for (var m : members.values()) m.transport.close(); members.clear(); }
}
