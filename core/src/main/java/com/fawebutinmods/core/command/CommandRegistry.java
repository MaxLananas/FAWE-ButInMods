package com.fawebutinmods.core.command;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.util.Str;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The command dispatcher.
 *
 * <p>FAWE inherits WorldEdit's two command namespaces and we reproduce both:</p>
 * <ul>
 *   <li>the legacy {@code //} namespace: {@code //set}, {@code //copy} — stored
 *       here as {@code //set} and registered with Minecraft as a literal named
 *       {@code /set} (Minecraft strips the first slash),</li>
 *   <li>the modern {@code /} namespace: {@code /fast}, {@code /schem save},
 *       {@code /brush sphere}, {@code /tool ...}.</li>
 * </ul>
 *
 * <p>Argument parsing follows WorldEdit's rules: whitespace separated with
 * double-quote support, {@code -flag} switches and {@code -flag value} options,
 * and greedy string arguments.</p>
 */
public final class CommandRegistry {

    /** A command definition. */
    public static final class Entry {

        public final String name;
        public final Set<String> aliases = new LinkedHashSet<>();
        public String description = "";
        public String help;
        public String group = "general";
        public final Set<String> booleanFlags = new LinkedHashSet<>();
        public final Set<String> valueFlags = new LinkedHashSet<>();
        public final List<String> arguments = new ArrayList<>();
        public boolean requiresSelection;
        public boolean requiresPlayer;
        public boolean requiresWorld = true;
        public CommandHandler handler;
        public String status = "implemented";

        Entry(String name) {
            this.name = name;
        }

        /** The raw string the platform must register (see class docs). */
        public String registrationName() {
            return name.startsWith("//") ? name.substring(1) : name;
        }

        public String usage() {
            StringBuilder sb = new StringBuilder(name);
            for (String argument : arguments) {
                sb.append(' ').append(argument.startsWith("-") ? argument : '<' + argument + '>');
            }
            return sb.toString();
        }
    }

    /** A command handler. */
    @FunctionalInterface
    public interface CommandHandler {
        void run(Ctx context) throws Exception;
    }

    private final Map<String, Entry> commands = new LinkedHashMap<>();
    private final Map<String, Entry> byAlias = new LinkedHashMap<>();

    public Entry register(String name) {
        Entry entry = new Entry(name);
        commands.put(name.toLowerCase(Locale.ROOT), entry);
        byAlias.put(name.toLowerCase(Locale.ROOT), entry);
        return entry;
    }

    /** Convenience for the fluent style used in {@link Commands}. */
    public Entry register(String name, String... aliases) {
        Entry entry = register(name);
        entry.aliases.addAll(Arrays.asList(aliases));
        for (String alias : aliases) {
            byAlias.put(alias.toLowerCase(Locale.ROOT), entry);
        }
        return entry;
    }

    public Entry get(String name) {
        return byAlias.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Registers a command unless another entry already answers to the name, so
     * that a command class never duplicates behaviour another one implements.
     *
     * @return the new entry, or null when the name is already taken
     */
    public Entry registerUnlessPresent(String name, String... aliases) {
        if (contains(name)) {
            return null;
        }
        return register(name, aliases);
    }

    /**
     * True when any spelling of the name is already registered, in either
     * namespace ({@code //set}, {@code /set} or {@code set}).
     */
    public boolean contains(String name) {
        return lookup(name) != null;
    }

    /**
     * Looks a name up in every spelling the platform can hand us.
     *
     * <p>WorldEdit names carry a slash ({@code //set}, {@code /fast}), and
     * Minecraft strips one slash from what the player typed, so the same command
     * arrives as {@code //set}, {@code /set} or {@code set} depending on the
     * registration. All of them resolve here.</p>
     */
    private Entry lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry entry = byAlias.get(key);
        if (entry != null) {
            return entry;
        }
        if (key.startsWith("//")) {
            return byAlias.get(key.substring(1));
        }
        if (key.startsWith("/")) {
            return byAlias.get("/" + key);
        }
        entry = byAlias.get("/" + key);
        return entry != null ? entry : byAlias.get("//" + key);
    }

    public java.util.Collection<Entry> all() {
        return commands.values();
    }

    public Entry resolve(String input) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        Entry entry = lookup(parts[0]);
        if (entry == null) {
            return null;
        }
        // Container commands ("/brush sphere") resolve through their sub-command.
        if (parts.length > 1) {
            Entry sub = lookup(parts[0] + " " + parts[1]);
            if (sub != null) {
                return sub;
            }
        }
        return entry;
    }

    /** Suggestions for tab completion: the first token, then the declared arguments. */
    public List<String> suggest(String input) {
        String trimmed = input;
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace < 0) {
            List<String> out = new ArrayList<>();
            for (String name : byAlias.keySet()) {
                if (name.startsWith(trimmed.toLowerCase(Locale.ROOT))) {
                    out.add(name);
                }
            }
            return out;
        }
        Entry entry = resolve(trimmed.substring(0, lastSpace));
        if (entry == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String prefix = trimmed.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
        for (String argument : entry.arguments) {
            if (argument.startsWith("<")) {
                continue;
            }
            if (argument.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(argument);
            }
        }
        for (String flag : entry.booleanFlags) {
            if (("-" + flag).startsWith(prefix)) {
                out.add("-" + flag);
            }
        }
        return out;
    }

    /**
     * Parses and runs a command line.
     *
     * @return true when a command handled the input
     */
    public boolean dispatch(Actor actor, String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("/")) {
            // Accept "/fast", "//set" and "/we" through one entry point.
            while (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
                trimmed = trimmed.substring(1);
            }
        }
        List<String> tokens = Str.split(trimmed);
        if (tokens.isEmpty()) {
            return false;
        }
        Entry entry = lookup(tokens.get(0));
        if (tokens.size() > 1) {
            // Container commands ("/brush sphere", "//schem save") win over the
            // bare container ("/brush", "//schem").
            Entry sub = lookup(tokens.get(0) + " " + tokens.get(1));
            if (sub != null) {
                entry = sub;
                List<String> shifted = new ArrayList<>(tokens.subList(1, tokens.size()));
                shifted.set(0, sub.name);
                tokens = shifted;
            }
        }
        if (entry == null) {
            actor.message(Msg.error("Unknown command: " + trimmed));
            return false;
        }
        try {
            Ctx context = new Ctx(entry, actor, tokens);
            if (entry.handler == null) {
                actor.message(Msg.warn("Command '" + entry.name
                        + "' is registered but not implemented in this build (see docs/STATUS.md)."));
                return true;
            }
            entry.handler.run(context);
            return true;
        } catch (CommandException e) {
            actor.message(Msg.error(e.getMessage()));
            return true;
        } catch (com.fawebutinmods.core.extent.EditSession.MaxChangedBlocksException e) {
            actor.message(Msg.error("Max blocks changed in an operation: " + e.getLimit()));
            return true;
        } catch (com.fawebutinmods.core.util.TimeLimiter.OperationTimeoutException e) {
            actor.message(Msg.error("Operation timed out after " + e.elapsedMillis() + "ms"));
            return true;
        } catch (Exception e) {
            actor.message(Msg.error("Command failed: " + e.getMessage()));
            return true;
        }
    }

    /** Thrown by command implementations for user-facing failures. */
    public static final class CommandException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CommandException(String message) {
            super(message);
        }
    }

    public static CommandException error(String message) {
        return new CommandException(message);
    }
}
