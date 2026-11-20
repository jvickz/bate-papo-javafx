package br.com.batepapo.server;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;

/** The normalized primary key makes concurrent registrations atomic on both supported databases. */
final class AccountStore {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DUMMY_SALT = new byte[16];
    private final String url;
    private final Properties properties;

    private AccountStore(String url, Properties properties) {
        this.url = url;
        this.properties = properties;
        try (var connection = open(); var statement = connection.createStatement()) {
            if (url.startsWith("jdbc:h2:")) statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS wavecove");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wavecove.accounts (
                      username_key VARCHAR(24) PRIMARY KEY,
                      display_name VARCHAR(24) NOT NULL,
                      password_salt VARCHAR(64) NOT NULL,
                      password_hash VARCHAR(64) NOT NULL,
                      iterations INTEGER NOT NULL
                    )
                    """);
        } catch (SQLException ex) {
            // Driver messages can contain connection details. Keep those out of startup logs.
            throw new IllegalArgumentException("Banco de contas indisponível. Confira a conexão e as permissões.");
        }
    }

    static AccountStore local(Path file) {
        String path = file.toAbsolutePath().normalize().toString();
        if (path.contains(";")) throw new IllegalArgumentException("Caminho local do banco inválido.");
        return new AccountStore("jdbc:h2:file:" + path, new Properties());
    }

    static AccountStore postgres(String address) {
        final String jdbc;
        var properties = new Properties();
        try {
            URI uri = URI.create(address);
            if (!("postgres".equalsIgnoreCase(uri.getScheme()) || "postgresql".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getFragment() != null || uri.getRawPath() == null
                    || !uri.getRawPath().matches("/[^/]+") || uri.getRawUserInfo() == null) throw new IllegalArgumentException();
            String[] userInfo = uri.getRawUserInfo().split(":", 2);
            if (userInfo.length != 2) throw new IllegalArgumentException();
            properties.setProperty("user", decode(userInfo[0]));
            properties.setProperty("password", decode(userInfo[1]));
            properties.setProperty("connectTimeout", "10");
            properties.setProperty("socketTimeout", "15");
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            String query = configurePostgresTls(uri, properties);
            jdbc = "jdbc:postgresql://" + uri.getHost() + port + uri.getRawPath() + query;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("DATABASE_URL deve ser uma URL PostgreSQL com usuário, senha e banco.");
        }
        return new AccountStore(jdbc, properties);
    }

    static String configurePostgresTls(URI uri, Properties properties) {
        if (!uri.getHost().toLowerCase(Locale.ROOT).endsWith(".pooler.supabase.com")) {
            boolean privateHost = uri.getHost().startsWith("dpg-") && !uri.getHost().contains(".");
            properties.setProperty("sslmode", privateHost ? "require" : "verify-full");
            if (!privateHost) properties.setProperty("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
            return uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        }
        // URL options override Properties in pgJDBC. Move application options into Properties and
        // keep certificate and hostname verification even for provider URLs with sslmode=require.
        if (uri.getRawQuery() != null) {
            for (String option : uri.getRawQuery().split("&")) {
                String[] entry = option.split("=", 2);
                if (entry.length == 2) properties.setProperty(URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
            }
        }
        properties.remove("sslhostnameverifier");
        properties.setProperty("sslmode", "verify-full");
        properties.setProperty("sslfactory", "org.postgresql.ssl.LibPQFactory");
        String certificate = System.getenv().getOrDefault("WAVECOVE_SUPABASE_CA",
                Path.of("server", "certs", "supabase-prod-ca-2021.crt").toString());
        properties.setProperty("sslrootcert", Path.of(certificate).toAbsolutePath().toString());
        return "";
    }

    private static String decode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    Connection open() throws SQLException { return DriverManager.getConnection(url, properties); }

    boolean register(String username, char[] password) throws SQLException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        try (var connection = open(); var statement = connection.prepareStatement("""
                INSERT INTO wavecove.accounts(username_key, display_name, password_salt, password_hash, iterations)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, username.toLowerCase(Locale.ROOT));
            statement.setString(2, username);
            statement.setString(3, Base64.getEncoder().encodeToString(salt));
            statement.setString(4, Base64.getEncoder().encodeToString(hash));
            statement.setInt(5, ITERATIONS);
            statement.executeUpdate();
            return true;
        } catch (SQLException ex) {
            if ("23505".equals(ex.getSQLState())) return false;
            throw ex;
        } finally { Arrays.fill(hash, (byte) 0); }
    }

    String login(String username, char[] password) throws SQLException {
        String displayName = null;
        byte[] salt = DUMMY_SALT, expected = new byte[32];
        int cost = ITERATIONS;
        try (var connection = open(); var statement = connection.prepareStatement("""
                SELECT display_name, password_salt, password_hash, iterations
                FROM wavecove.accounts WHERE username_key = ?
                """)) {
            statement.setString(1, username.toLowerCase(Locale.ROOT));
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    displayName = result.getString(1);
                    salt = Base64.getDecoder().decode(result.getString(2));
                    expected = Base64.getDecoder().decode(result.getString(3));
                    cost = result.getInt(4);
                }
            }
        }
        byte[] actual = derive(password, salt, cost);
        try { return MessageDigest.isEqual(actual, expected) ? displayName : null; }
        finally { Arrays.fill(actual, (byte) 0); }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException("PBKDF2 indisponível.", ex); }
        finally { spec.clearPassword(); }
    }
}
