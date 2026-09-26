package com.maxlananas.fawebim.fabric;

/**
 * The bridge to the configuration screen.
 *
 * <p>The engine asks for the screen through {@code Actor}, the command layer
 * hands that question here, and the client entrypoint answers it: this class is
 * loaded on both sides, the screen itself only where a client exists. A
 * dedicated server therefore keeps the chat listing and never touches a client
 * class.</p>
 */
public final class ConfigurationScreens {

    /** Draws the screen on the client thread. */
    @FunctionalInterface
    public interface Opener {

        void open();
    }

    private static volatile Opener opener;

    private ConfigurationScreens() {
    }

    /** Called once by the client entrypoint. */
    public static void setOpener(Opener value) {
        opener = value;
    }

    /** True when a client took the request, false when there is no screen to show. */
    public static boolean open() {
        Opener current = opener;
        if (current == null) {
            return false;
        }
        current.open();
        return true;
    }
}
