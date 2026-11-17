package br.com.batepapo.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

final class AccountRoutes {
    private static final int MAX_BODY = 2048;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,24}");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(MAX_BODY).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final AccountStore store;
    private final AccountSessions sessions;
    private final Room room;
    private final Semaphore workers = new Semaphore(2);
    private final Map<String, Integer> attempts = new HashMap<>();
    private long window = System.nanoTime();
    private int total;

    AccountRoutes(AccountStore store, AccountSessions sessions, Room room) {
        this.store = store;
        this.sessions = sessions;
        this.room = room;
    }

    void authenticate(Context ctx, boolean register) throws IOException {
        ctx.header("Cache-Control", "no-store");
        String contentType = ctx.contentType();
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            error(ctx, 415, "Envie as credenciais em JSON."); return;
        }
        if (ctx.req().getContentLengthLong() > MAX_BODY) { error(ctx, 413, "Dados de acesso muito longos."); return; }
        if (!workers.tryAcquire()) { busy(ctx); return; }
        try {
            byte[] bytes;
            try (var input = ctx.bodyInputStream()) { bytes = input.readNBytes(MAX_BODY + 1); }
            if (bytes.length > MAX_BODY) { error(ctx, 413, "Dados de acesso muito longos."); return; }
            final String username;
            final char[] password;
            try {
                var json = JSON.readTree(bytes);
                if (json == null || !json.isObject() || !json.path("username").isTextual()
                        || !json.path("password").isTextual()) { error(ctx, 400, "Informe usuário e senha."); return; }
                username = json.get("username").textValue();
                if (!NAME.matcher(username).matches()) {
                    error(ctx, 400, "Use de 3 a 24 letras sem acento, números ou sublinhado no usuário."); return;
                }
                String value = json.get("password").textValue();
                int length = value.codePointCount(0, value.length());
                if (length < 12 || length > 128 || value.isBlank()) {
                    error(ctx, 400, "Use uma senha de 12 a 128 caracteres."); return;
                }
                password = value.toCharArray();
            } catch (IOException ex) { error(ctx, 400, "Dados de acesso inválidos."); return; }
            finally { Arrays.fill(bytes, (byte) 0); }

            try {
                if (!allow(ctx.req().getRemoteAddr(), username)) { busy(ctx); return; }
                String canonical = username;
                if (register) {
                    if (!store.register(username, password)) { error(ctx, 409, "Esse usuário já está cadastrado."); return; }
                } else {
                    canonical = store.login(username, password);
                    if (canonical == null) { error(ctx, 401, "Usuário ou senha incorretos."); return; }
                }
                respond(ctx, register ? 201 : 200, Map.of("username", canonical, "token", sessions.issue(canonical)));
            } catch (SQLException ex) { error(ctx, 503, "Banco de contas indisponível. Tente novamente."); }
            finally { Arrays.fill(password, '\0'); }
        } finally { workers.release(); }
    }

    private synchronized boolean allow(String address, String name) {
        long now = System.nanoTime();
        if (now - window >= Duration.ofMinutes(1).toNanos()) {
            window = now;
            total = 0;
            attempts.clear();
        }
        // Bound the entire map even on shared or rotating proxy addresses. Never trust a raw forwarded header.
        if (total >= 100) return false;
        total++;
        return attempts.merge("address/" + address, 1, Integer::sum) <= 40
                && attempts.merge("name/" + name.toLowerCase(Locale.ROOT), 1, Integer::sum) <= 10;
    }

    void logout(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        String bearer = ctx.header("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() != 50) {
            error(ctx, 401, "Sessão inválida."); return;
        }
        room.revokeAccountSession(bearer.substring(7));
        ctx.status(204);
    }

    private static void busy(Context ctx) {
        ctx.header("Retry-After", "60");
        error(ctx, 429, "Muitas tentativas. Aguarde um minuto e tente novamente.");
    }

    private static void error(Context ctx, int status, String message) { respond(ctx, status, Map.of("error", message)); }

    private static void respond(Context ctx, int status, Map<String, String> result) {
        try { ctx.status(status).contentType("application/json").result(JSON.writeValueAsBytes(result)); }
        catch (IOException ex) { throw new IllegalStateException("Falha ao preparar resposta de acesso."); }
    }
}
