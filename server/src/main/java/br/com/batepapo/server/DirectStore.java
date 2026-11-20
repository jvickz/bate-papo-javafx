package br.com.batepapo.server;

import br.com.batepapo.protocol.DirectChat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

final class DirectStore {
    private final AccountStore accounts;
    private final Clock clock;
    static final class SendRateExceeded extends RuntimeException {}

    DirectStore(AccountStore accounts) {
        this(accounts, Clock.systemUTC());
    }

    DirectStore(AccountStore accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
        try (var connection = accounts.open(); var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wavecove.direct_threads (
                      account_a VARCHAR(24) NOT NULL REFERENCES wavecove.accounts(username_key),
                      account_b VARCHAR(24) NOT NULL REFERENCES wavecove.accounts(username_key),
                      latest_id BIGINT NOT NULL DEFAULT 0,
                      a_incoming BIGINT NOT NULL DEFAULT 0, b_incoming BIGINT NOT NULL DEFAULT 0,
                      a_read BIGINT NOT NULL DEFAULT 0, b_read BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (account_a, account_b)
                    )
                    """);
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS direct_threads_b ON wavecove.direct_threads(account_b)");
            sql.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wavecove.direct_messages (
                      id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                      account_a VARCHAR(24) NOT NULL, account_b VARCHAR(24) NOT NULL,
                      sender_key VARCHAR(24) NOT NULL REFERENCES wavecove.accounts(username_key),
                      client_id VARCHAR(36) NOT NULL, body TEXT NOT NULL, sent_at VARCHAR(40) NOT NULL,
                      FOREIGN KEY (account_a, account_b) REFERENCES wavecove.direct_threads(account_a, account_b),
                      UNIQUE (sender_key, client_id)
                    )
                    """);
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS direct_messages_pair ON wavecove.direct_messages(account_a, account_b, id)");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS direct_messages_sender_id ON wavecove.direct_messages(sender_key, id)");
        } catch (SQLException e) {
            throw new IllegalArgumentException("Não foi possível preparar as tabelas de conversas privadas.");
        }
    }

    private record Pair(String a, String b) {
        static Pair of(String owner, String peer) {
            String me = owner.toLowerCase(Locale.ROOT);
            String other = peer.toLowerCase(Locale.ROOT);
            if (me.equals(other)) throw new IllegalArgumentException("Escolha outra pessoa para conversar.");
            return me.compareTo(other) < 0 ? new Pair(me, other) : new Pair(other, me);
        }
    }

    private static String peerName(Connection db, String name) throws SQLException {
        try (var sql = db.prepareStatement("SELECT display_name FROM wavecove.accounts WHERE username_key = ?")) {
            sql.setString(1, name.toLowerCase(Locale.ROOT));
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) throw new NoSuchElementException();
                return rows.getString(1);
            }
        }
    }

    DirectChat.Message send(String owner, String peer, DirectChat.Send send) throws SQLException {
        var pair = Pair.of(owner, peer);
        String senderKey = owner.toLowerCase(Locale.ROOT);
        try (var db = accounts.open()) {
            db.setAutoCommit(false);
            try {
                // Acquire participant locks in a consistent order. This also serializes retries across peers
                // and ensures committed IDs within a conversation always follow delivery order.
                try (var lock = db.prepareStatement("""
                        SELECT username_key FROM wavecove.accounts WHERE username_key IN (?, ?)
                        ORDER BY username_key FOR UPDATE
                        """)) {
                    lock.setString(1, pair.a);
                    lock.setString(2, pair.b);
                    try (var rows = lock.executeQuery()) {
                        if (!rows.next() || !rows.next()) throw new NoSuchElementException();
                    }
                }
                String recipient = peerName(db, peer);
                try (var existing = db.prepareStatement("""
                        SELECT id, account_a, account_b, body, sent_at FROM wavecove.direct_messages
                        WHERE sender_key = ? AND client_id = ?
                        """)) {
                    existing.setString(1, senderKey);
                    existing.setString(2, send.clientId());
                    try (var rows = existing.executeQuery()) {
                        if (rows.next()) {
                            if (!pair.a.equals(rows.getString(2)) || !pair.b.equals(rows.getString(3))
                                    || !send.text().equals(rows.getString(4))) {
                                throw new IllegalStateException("Este envio já foi usado em outra mensagem.");
                            }
                            var saved = new DirectChat.Message(rows.getLong(1), owner, recipient, send.text(), rows.getString(5));
                            db.commit();
                            return saved;
                        }
                    }
                }
                Instant now = clock.instant();
                // The sender row is already locked, so concurrent sends use the same five-message quota.
                // A retry of an acknowledged write returns its original ID before reaching this check.
                try (var limit = db.prepareStatement("""
                        SELECT sent_at FROM wavecove.direct_messages WHERE sender_key = ?
                        ORDER BY id DESC LIMIT 1 OFFSET 4
                        """)) {
                    limit.setString(1, senderKey);
                    try (var rows = limit.executeQuery()) {
                        if (rows.next() && Instant.parse(rows.getString(1)).isAfter(now.minusSeconds(1)))
                            throw new SendRateExceeded();
                    }
                }
                try (var thread = db.prepareStatement("""
                        INSERT INTO wavecove.direct_threads(account_a, account_b)
                        SELECT ?, ? WHERE NOT EXISTS (
                          SELECT 1 FROM wavecove.direct_threads WHERE account_a = ? AND account_b = ?)
                        """)) {
                    thread.setString(1, pair.a);
                    thread.setString(2, pair.b);
                    thread.setString(3, pair.a);
                    thread.setString(4, pair.b);
                    thread.executeUpdate();
                }
                String time = now.toString();
                long id;
                try (var insert = db.prepareStatement("""
                        INSERT INTO wavecove.direct_messages(account_a, account_b, sender_key, client_id, body, sent_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, pair.a);
                    insert.setString(2, pair.b);
                    insert.setString(3, senderKey);
                    insert.setString(4, send.clientId());
                    insert.setString(5, send.text());
                    insert.setString(6, time);
                    insert.executeUpdate();
                    try (var keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("ID da mensagem indisponível.");
                        id = keys.getLong(1);
                    }
                }
                String incoming = pair.a.equals(senderKey) ? "b_incoming" : "a_incoming";
                try (var update = db.prepareStatement("UPDATE wavecove.direct_threads SET latest_id = ?, "
                        + incoming + " = ? WHERE account_a = ? AND account_b = ?")) {
                    update.setLong(1, id);
                    update.setLong(2, id);
                    update.setString(3, pair.a);
                    update.setString(4, pair.b);
                    update.executeUpdate();
                }
                db.commit();
                return new DirectChat.Message(id, owner, recipient, send.text(), time);
            } catch (RuntimeException | SQLException e) {
                db.rollback();
                throw e;
            }
        }
    }

    List<DirectChat.Thread> threads(String owner) throws SQLException {
        String me = owner.toLowerCase(Locale.ROOT);
        try (var db = accounts.open(); var sql = db.prepareStatement("""
                SELECT peer.display_name, m.body, m.sent_at,
                       CASE WHEN t.account_a = ? THEN t.a_incoming > t.a_read ELSE t.b_incoming > t.b_read END,
                       t.latest_id
                FROM wavecove.direct_threads t
                JOIN wavecove.direct_messages m ON m.id = t.latest_id
                JOIN wavecove.accounts peer ON peer.username_key = CASE WHEN t.account_a = ? THEN t.account_b ELSE t.account_a END
                WHERE t.account_a = ? OR t.account_b = ?
                ORDER BY t.latest_id DESC
                """)) {
            for (int i = 1; i <= 4; i++) sql.setString(i, me);
            try (var rows = sql.executeQuery()) {
                var result = new ArrayList<DirectChat.Thread>();
                while (rows.next()) {
                    String text = rows.getString(2);
                    int length = text.codePointCount(0, text.length());
                    String preview = length > 100 ? text.substring(0, text.offsetByCodePoints(0, 99)) + "…" : text;
                    result.add(new DirectChat.Thread(rows.getString(1), preview, rows.getString(3), rows.getBoolean(4),
                            rows.getLong(5)));
                }
                return result;
            }
        }
    }

    List<DirectChat.Message> history(String owner, String peer, long cursor, boolean newer) throws SQLException {
        var pair = Pair.of(owner, peer);
        String page = newer ? "id > ? ORDER BY id ASC LIMIT ?" : "id < ? ORDER BY id DESC LIMIT ?";
        try (var db = accounts.open(); var sql = db.prepareStatement("""
                SELECT id, sender_key, body, sent_at FROM wavecove.direct_messages
                WHERE account_a = ? AND account_b = ? AND
                """ + page)) {
            String canonical = peerName(db, peer);
            sql.setString(1, pair.a);
            sql.setString(2, pair.b);
            sql.setLong(3, cursor);
            sql.setInt(4, DirectChat.PAGE_SIZE);
            var messages = new ArrayList<DirectChat.Message>();
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    boolean own = owner.equalsIgnoreCase(rows.getString(2));
                    messages.add(new DirectChat.Message(rows.getLong(1), own ? owner : canonical,
                            own ? canonical : owner, rows.getString(3), rows.getString(4)));
                }
            }
            if (!newer) Collections.reverse(messages);
            return messages;
        }
    }

    void markRead(String owner, String peer, long through) throws SQLException {
        var pair = Pair.of(owner, peer);
        String read = pair.a.equalsIgnoreCase(owner) ? "a_read" : "b_read";
        try (var db = accounts.open(); var sql = db.prepareStatement("UPDATE wavecove.direct_threads SET "
                + read + " = GREATEST(" + read + ", ?) WHERE account_a = ? AND account_b = ?"
                + " AND EXISTS (SELECT 1 FROM wavecove.direct_messages WHERE account_a = ? AND account_b = ? AND id = ?)")) {
            peerName(db, peer);
            sql.setLong(1, through);
            sql.setString(2, pair.a);
            sql.setString(3, pair.b);
            sql.setString(4, pair.a);
            sql.setString(5, pair.b);
            sql.setLong(6, through);
            if (sql.executeUpdate() == 0) throw new IllegalArgumentException("Mensagem indisponível nesta conversa.");
        }
    }
}
