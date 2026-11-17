package br.com.batepapo.server;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Socket authentication must not perform database or password-hash work under the Room lock. */
final class AccountSessions {
    private record Session(String username, long expires) {}
    private final LinkedHashMap<String, Session> active = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    static final long LIFETIME = Duration.ofHours(12).toNanos();

    AccountSessions(LongSupplier clock) { this.clock = clock; }

    synchronized String issue(String name) {
        long now = clock.getAsLong();
        active.values().removeIf(session -> now - session.expires >= 0);
        if (active.size() >= 4096) active.pollFirstEntry();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        active.put(token, new Session(name, now + LIFETIME));
        return token;
    }

    synchronized String owner(String token) {
        if (token == null || token.length() != 43) return null;
        var session = active.get(token);
        if (session == null) return null;
        if (clock.getAsLong() - session.expires < 0) return session.username;
        active.remove(token);
        return null;
    }

    synchronized void revoke(String token) { active.remove(token); }
}
