package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Actor;

/**
 * The single entry point the platform adapter talks to: it builds the registry,
 * registers every command and forwards command lines coming from Minecraft.
 */
public final class CommandManager {

    private static final CommandManager INSTANCE = new CommandManager();

    private final CommandRegistry registry = new CommandRegistry();
    private boolean initialised;

    private CommandManager() {
    }

    public static CommandManager get() {
        return INSTANCE;
    }

    public CommandRegistry registry() {
        return registry;
    }

    /** Idempotent: registers the whole command surface exactly once. */
    public synchronized void initialise() {
        if (initialised) {
            return;
        }
        initialised = true;
        new Commands(registry).registerAll();
        BrushCommands.setDispatcher(registry::dispatch);
    }

    public boolean dispatch(Actor actor, String line) {
        initialise();
        return registry.dispatch(actor, line);
    }

    /** Every literal the platform must register, in registration order. */
    public java.util.List<String> registrationNames() {
        initialise();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (CommandRegistry.Entry entry : registry.all()) {
            names.add(entry.registrationName());
        }
        return names;
    }

    /** Number of registered commands, used by {@code //version} and the docs. */
    public int size() {
        initialise();
        return registry.all().size();
    }
}
