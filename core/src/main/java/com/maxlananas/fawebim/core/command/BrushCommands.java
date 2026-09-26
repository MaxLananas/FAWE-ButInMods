package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Actor;

/**
 * Entry point used when a command has to be run from inside another operation:
 * {@code /brush command}, the {@code /tool} bindings, {@code //paste -c} and the
 * schematic macros.
 */
public final class BrushCommands {

    private static CommandDispatcher dispatcher;

    private BrushCommands() {
    }

    /**
     * Installed once by {@code CommandManager} while the registry is built, so
     * the engine can run a command line from inside an operation without the
     * brush code knowing about the registry.
     */
    public static void setDispatcher(CommandDispatcher value) {
        dispatcher = value;
    }

    public static boolean run(Actor actor, String line) {
        if (dispatcher == null) {
            actor.message(com.maxlananas.fawebim.core.util.Msg.error("Command dispatch is not available"));
            return false;
        }
        return dispatcher.dispatch(actor, line);
    }

    public interface CommandDispatcher {
        boolean dispatch(Actor actor, String line);
    }
}
