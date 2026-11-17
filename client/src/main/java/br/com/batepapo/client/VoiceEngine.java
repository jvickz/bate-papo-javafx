package br.com.batepapo.client;

import br.com.batepapo.protocol.VoiceConfig;

import java.util.function.Consumer;

public interface VoiceEngine extends AutoCloseable {
    /** Starts an accepted call. Callbacks run asynchronously and should return promptly. */
    void start(VoiceConfig config, Consumer<String> signals, Consumer<String> states);

    void signal(String json);

    void setMuted(boolean muted);

    /** Invalidates pending callbacks and requests asynchronous media cleanup. */
    @Override
    void close();
}
