package br.com.batepapo.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.net.URI;
import java.util.Properties;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {
    @TempDir Path directory;

    @Test void usesSupabaseRootAndFullHostnameVerificationOnlyForTheOfficialPooler() {
        var pooler = new Properties();
        var address = URI.create("postgresql://aws-1-us-east-1.pooler.supabase.com/postgres?sslmode=require"
                + "&sslfactory=unsafe&sslhostnameverifier=unsafe&sslrootcert=other&ApplicationName=Wave%20Test");
        assertEquals("", AccountStore.configurePostgresTls(address, pooler));
        assertEquals("verify-full", pooler.getProperty("sslmode"));
        assertEquals("org.postgresql.ssl.LibPQFactory", pooler.getProperty("sslfactory"));
        assertTrue(Path.of(pooler.getProperty("sslrootcert")).isAbsolute());
        assertNull(pooler.getProperty("sslhostnameverifier"));
        assertEquals("Wave Test", pooler.getProperty("ApplicationName"));

        var external = new Properties();
        assertEquals("?ApplicationName=Wave", AccountStore.configurePostgresTls(
                URI.create("postgresql://pooler.supabase.com.example.org/db?ApplicationName=Wave"), external));
        assertEquals("org.postgresql.ssl.DefaultJavaSSLFactory", external.getProperty("sslfactory"));
        assertFalse(external.containsKey("sslrootcert"));
    }

    @Test void simultaneousRegistrationsKeepJustOnePassword() throws Exception {
        var store = AccountStore.local(directory.resolve("accounts"));
        var start = new CountDownLatch(1);
        String first = "senha da primeira janela", second = "senha da segunda janela";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return store.register("Marina", first.toCharArray()); });
            var b = executor.submit(() -> { start.await(); return store.register("MARINA", second.toCharArray()); });
            start.countDown();
            boolean registeredFirst = a.get(15, TimeUnit.SECONDS);
            assertNotEquals(registeredFirst, b.get(15, TimeUnit.SECONDS));
            assertEquals(registeredFirst ? "Marina" : "MARINA", store.login("marina",
                    (registeredFirst ? first : second).toCharArray()));
            assertNull(store.login("marina", (registeredFirst ? second : first).toCharArray()));
        }
    }

    @Test void equalPasswordsHaveIndependentSaltsAndHashes() throws Exception {
        Path path = directory.resolve("accounts");
        var store = AccountStore.local(path);
        char[] password = "uma senha para testar".toCharArray();
        assertTrue(store.register("Marina", password));
        assertTrue(store.register("Carlos", password));
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + path);
             var query = connection.createStatement();
             var result = query.executeQuery("SELECT COUNT(DISTINCT password_salt), COUNT(DISTINCT password_hash) FROM wavecove.accounts")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
            assertEquals(2, result.getInt(2));
        }
    }

    @Test void ticketsExpireAndRevokingOneLeavesTheOthersValid() {
        var clock = new AtomicLong(42);
        var sessions = new AccountSessions(clock::get);
        String first = sessions.issue("Marina"), second = sessions.issue("Marina");
        sessions.revoke(first);
        assertNull(sessions.owner(first));
        assertEquals("Marina", sessions.owner(second));
        clock.addAndGet(AccountSessions.LIFETIME);
        assertNull(sessions.owner(second));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WAVECOVE_TEST_POSTGRES_URL", matches = ".+")
    void persistsUsingTheConfiguredDisposablePostgresSchema() throws Exception {
        String database = System.getenv("WAVECOVE_TEST_POSTGRES_URL");
        String name = "Test_" + UUID.randomUUID().toString().substring(0, 8);
        char[] password = UUID.randomUUID().toString().toCharArray();
        assertTrue(AccountStore.postgres(database).register(name, password));
        var reopened = AccountStore.postgres(database);
        assertEquals(name, reopened.login(name.toLowerCase(java.util.Locale.ROOT), password));
        assertFalse(reopened.register(name, password));
    }
}
