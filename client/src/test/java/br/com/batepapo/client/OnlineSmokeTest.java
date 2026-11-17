package br.com.batepapo.client;

import br.com.batepapo.protocol.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in check of a deployed service; credentials remain in the process environment. */
@EnabledIfEnvironmentVariable(named = "BATEPAPO_TEST_URL", matches = ".+")
class OnlineSmokeTest {
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "Servidor não respondeu como esperado em 60 segundos");
    }
    @Test void twoClientsExchangeMessagesAndWrongCodeIsRejected() throws Exception {
        String url = System.getenv("BATEPAPO_TEST_URL"), token = System.getenv("CHAT_ACCESS_TOKEN");
        assertNotNull(token, "Defina CHAT_ACCESS_TOKEN no ambiente de teste");
        var ea = new CopyOnWriteArrayList<Event>(); var eb = new CopyOnWriteArrayList<Event>(); var bad = new CopyOnWriteArrayList<Event>();
        var sa = new CopyOnWriteArrayList<String>(); var sb = new CopyOnWriteArrayList<String>();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        try (var a = new ChatConnection(ea::add, sa::add); var b = new ChatConnection(eb::add, sb::add); var rejected = new ChatConnection(bad::add, state -> {})) {
            a.connect(url, "Teste A " + suffix, token); b.connect(url, "Teste B " + suffix, token);
            await(() -> sa.contains("connected") && sb.contains("connected"));
            String first = a.send("Olá pela rede 👋");
            await(() -> eb.stream().anyMatch(e -> first.equals(e.id()) && "message".equals(e.type())));
            String reply = b.send("Resposta recebida ✓");
            await(() -> ea.stream().anyMatch(e -> reply.equals(e.id()) && "message".equals(e.type())));
            rejected.connect(url, "Sem acesso " + suffix, UUID.randomUUID().toString());
            await(() -> bad.stream().anyMatch(e -> "error".equals(e.type()) && "auth".equals(e.id())));
            assertTrue(bad.stream().noneMatch(e -> "presence".equals(e.type()) || "message".equals(e.type())));
        }
    }
}
