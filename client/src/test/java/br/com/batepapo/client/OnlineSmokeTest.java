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
            await(() -> ea.stream().anyMatch(e -> "channels".equals(e.type())));
            boolean channelExists = ea.stream().filter(e -> "channels".equals(e.type())).toList().getLast().users().contains("teste-rede");
            if (channelExists) a.joinChannel("teste-rede"); else a.createChannel("teste-rede");
            await(() -> ea.stream().anyMatch(e -> "joined".equals(e.type()) && "teste-rede".equals(e.channel())));
            String isolated = a.send("Mensagem isolada no canal de teste");
            await(() -> ea.stream().anyMatch(e -> isolated.equals(e.id()) && "message".equals(e.type())));
            assertTrue(eb.stream().noneMatch(e -> isolated.equals(e.id())), "Outro canal não deve receber a mensagem");
            b.joinChannel("teste-rede");
            await(() -> eb.stream().anyMatch(e -> "joined".equals(e.type()) && "teste-rede".equals(e.channel())));
            assertTrue(eb.stream().noneMatch(e -> isolated.equals(e.id())), "Nenhum vazamento antes da barreira de troca");
            String joinedReply = b.send("Canal online confirmado ⛵");
            await(() -> ea.stream().anyMatch(e -> joinedReply.equals(e.id()) && "message".equals(e.type()) && "teste-rede".equals(e.channel())));
            rejected.connect(url, "Sem acesso " + suffix, UUID.randomUUID().toString());
            await(() -> bad.stream().anyMatch(e -> "error".equals(e.type()) && "auth".equals(e.id())));
            assertTrue(bad.stream().noneMatch(e -> "presence".equals(e.type()) || "message".equals(e.type())));
        }
    }
}
