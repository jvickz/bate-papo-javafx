package br.com.batepapo.server;

import br.com.batepapo.protocol.DirectChat;
import br.com.batepapo.protocol.Protocol;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

final class DirectRoutes {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AccountSessions sessions;
    private final DirectStore store;
    private final Semaphore workers = new Semaphore(8);

    DirectRoutes(AccountSessions sessions, DirectStore store) {
        this.sessions = sessions;
        this.store = store;
    }

    void threads(Context ctx) throws IOException {
        authenticated(ctx, owner -> {
            byte[] contents = JSON.writeValueAsString(store.threads(owner)).getBytes(StandardCharsets.UTF_8);
            String etag = threadsTag(owner, contents);
            ctx.header("Cache-Control", "private, no-store");
            ctx.header("Vary", "Authorization");
            ctx.header("ETag", etag);
            String condition = ctx.header("If-None-Match");
            if (condition != null) {
                for (String token : condition.split(",")) {
                    String validator = token.strip();
                    if (validator.equals(etag) || validator.equals("W/" + etag) || validator.equals("*")) {
                        ctx.status(304);
                        return;
                    }
                }
            }
            ctx.contentType("application/json").result(contents);
        });
    }

    private static String threadsTag(String owner, byte[] contents) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(owner.getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            return "\"" + HexFormat.of().formatHex(hash.digest(contents)) + "\"";
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    void messages(Context ctx) throws IOException {
        authenticated(ctx, owner -> {
            String before = ctx.queryParam("before");
            String after = ctx.queryParam("after");
            if (before != null && after != null)
                throw new IllegalArgumentException("Use apenas uma direção para buscar mensagens.");
            boolean newer = after != null;
            String position = newer ? after : before;
            long cursor = position == null ? Long.MAX_VALUE : cursor(position, newer ? 0 : 1);
            respond(ctx, store.history(owner, peer(ctx), cursor, newer));
        });
    }

    void send(Context ctx) throws IOException {
        authenticated(ctx, owner -> {
            var data = body(ctx);
            String id = data.path("clientId").textValue();
            if (id == null || id.length() != 36) throw new IllegalArgumentException("Identificador de envio inválido.");
            try {
                if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Identificador de envio inválido.");
            }
            String text = Protocol.validateText(data.path("text").textValue());
            respond(ctx, store.send(owner, peer(ctx), new DirectChat.Send(id.toLowerCase(java.util.Locale.ROOT), text)));
        });
    }

    void read(Context ctx) throws IOException {
        authenticated(ctx, owner -> {
            var through = body(ctx).path("through");
            if (!through.isIntegralNumber() || !through.canConvertToLong() || through.longValue() <= 0)
                throw new IllegalArgumentException("Mensagem de leitura inválida.");
            store.markRead(owner, peer(ctx), through.longValue());
            ctx.status(204);
        });
    }

    private static String peer(Context ctx) {
        String peer = ctx.pathParam("peer");
        if (!peer.matches("[A-Za-z0-9_]{3,24}")) throw new IllegalArgumentException("Nome de usuário inválido.");
        return peer;
    }

    private static long cursor(String value, long minimum) {
        try {
            long id = Long.parseLong(value);
            if (id >= minimum) return id;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Posição no histórico inválida.");
    }

    private static JsonNode body(Context ctx) throws IOException {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
            throw new RequestError(415, "Envie os dados em JSON.");
        if (ctx.req().getContentLengthLong() > Protocol.MAX_BYTES) throw new RequestError(413, "Mensagem muito grande.");
        try (var input = ctx.bodyInputStream()) {
            byte[] bytes = input.readNBytes(Protocol.MAX_BYTES + 1);
            if (bytes.length > Protocol.MAX_BYTES) throw new RequestError(413, "Mensagem muito grande.");
            try {
                var json = JSON.readTree(bytes);
                if (json != null && json.isObject()) return json;
            } catch (IOException ignored) { }
            throw new IllegalArgumentException("Dados da conversa inválidos.");
        }
    }

    private interface Action { void run(String owner) throws SQLException, IOException; }
    private static final class RequestError extends RuntimeException {
        final int status;
        RequestError(int status, String message) { super(message); this.status = status; }
    }

    private void authenticated(Context ctx, Action action) throws IOException {
        ctx.header("Cache-Control", "no-store");
        String bearer = ctx.header("Authorization");
        String owner = bearer != null && bearer.startsWith("Bearer ") ? sessions.owner(bearer.substring(7)) : null;
        if (owner == null) { error(ctx, 401, "Entre na sua conta para abrir as conversas privadas."); return; }
        if (!workers.tryAcquire()) { error(ctx, 503, "Servidor ocupado. Tente novamente."); return; }
        try {
            action.run(owner);
        } catch (RequestError e) { error(ctx, e.status, e.getMessage()); }
        catch (DirectStore.SendRateExceeded e) {
            ctx.header("Retry-After", "1");
            error(ctx, 429, "Aguarde um segundo antes de enviar mais mensagens.");
        }
        catch (NoSuchElementException e) { error(ctx, 404, "Usuário não encontrado."); }
        catch (IllegalArgumentException e) { error(ctx, 400, e.getMessage()); }
        catch (IllegalStateException e) { error(ctx, 409, e.getMessage()); }
        catch (SQLException e) { error(ctx, 503, "Histórico privado indisponível. Tente novamente."); }
        finally { workers.release(); }
    }

    private static void error(Context ctx, int status, String message) throws IOException {
        ctx.status(status).contentType("application/json").result(JSON.writeValueAsBytes(Map.of("error", message)));
    }

    private static void respond(Context ctx, Object data) throws IOException {
        ctx.contentType("application/json").result(JSON.writeValueAsString(data));
    }
}
