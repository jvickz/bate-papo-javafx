package br.com.batepapo.client;

/** Separate launcher also permits classpath execution with bundled JavaFX native libraries. */
public final class Launcher {
    public static void main(String[] args) { javafx.application.Application.launch(ChatApp.class, args); }
}
