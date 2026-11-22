package br.com.batepapo.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A native peer for the cross-runtime check, using the unmodified packaged client. */
public final class InteropPeer {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        var b64 = Base64.getDecoder();
        var encode = Base64.getEncoder();
        var tls = new SecretTunnel(args[0].equals("host"), args[1], b64.decode(input.readLine()));
        boolean sent = false;
        for (String line; (line = input.readLine()) != null;) {
            if (!line.isEmpty()) tls.receive(b64.decode(line));
            StringBuilder received = new StringBuilder();
            if (tls.connected()) {
                tls.readMessages(received::append);
                if (!sent) { tls.write("Do Windows 🌿"); sent = true; }
            }
            System.out.println(encode.encodeToString(tls.output()) + "|" +
                    encode.encodeToString(received.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }
}
