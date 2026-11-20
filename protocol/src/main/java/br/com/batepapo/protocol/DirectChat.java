package br.com.batepapo.protocol;

/** Authenticated private messaging over HTTP. A page is ordered from oldest to newest. */
public final class DirectChat {
    public static final int PAGE_SIZE = 50;

    private DirectChat() {}

    public record Message(long id, String sender, String recipient, String text, String time) {}
    public record Thread(String peer, String preview, String time, boolean unread, long latestId) {}
    public record Send(String clientId, String text) {}
    public record Read(long through) {}
}
