package br.com.batepapo.server;

import br.com.batepapo.protocol.VoiceConfig;

import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
interface TurnCredentialsProvider extends AutoCloseable {
    CompletionStage<List<VoiceConfig.IceServer>> issue();

    @Override default void close() {}
}
