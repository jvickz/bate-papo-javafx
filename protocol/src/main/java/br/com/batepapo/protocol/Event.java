package br.com.batepapo.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(String type, String id, String name, String text, String token,
                    String session, List<String> users, String time, String channel) {
    public Event(String type, String id, String name, String text, String token,
                 String session, List<String> users, String time) {
        this(type, id, name, text, token, session, users, time, null);
    }
    public static Event of(String type, String text) {
        return new Event(type, null, null, text, null, null, null, null);
    }
    // Avoid leaking authentication/session secrets in diagnostics.
    @Override public String toString() { return "Event[type=" + type + ", id=" + id + "]"; }
}
