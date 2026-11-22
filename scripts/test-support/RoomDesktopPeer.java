package br.com.batepapo.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Test driver for the actual SecretSession classes in the distributed Windows JAR. */
public final class RoomDesktopPeer implements SecretSession.Listener {
    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

    public void waiting() { events.add("waiting"); }
    public void ready() { events.add("ready"); }
    public void message(String text) { events.add("text " + text); }
    public void ended(String reason) { events.add("ended"); }

    private void expect(String expected) throws InterruptedException {
        if (!expected.equals(events.poll(90, TimeUnit.SECONDS))) {
            throw new AssertionError("O cliente Windows não chegou à etapa esperada.");
        }
    }

    public static void main(String[] args) throws Exception {
        var listener = new RoomDesktopPeer();
        var input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        boolean host = args[1].equals("host");
        try (var session = host ? SecretSession.create(args[0], listener)
                                : SecretSession.join(args[0], input.readLine(), listener)) {
            if (host) {
                listener.expect("waiting");
                System.out.println(session.invite());
            }
            if (args[1].equals("reject")) {
                listener.expect("ended");
                System.out.println("REJECTED");
                return;
            }
            listener.expect("ready");
            session.send("Mensagem do Windows 🌊").get(15, TimeUnit.SECONDS);
            listener.expect("text Resposta do navegador 🌿");
            System.out.println("DELIVERED");
            listener.expect("ended");
            System.out.println("CLOSED");
        }
    }
}
