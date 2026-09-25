package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.world.Extent;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.util.Str;

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
        /**
         * Extra tab completions, when the arguments are not a fixed list: the
         * platform passes the text typed so far and shows what comes back. The
         * configuration commands are the ones that use it, to complete a setting
         * key the player has started to type.
         */
        public java.util.function.Function<String, List<String>> suggestions;

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
                sb.append(' ');
                // A flag, and an argument that already carries its own brackets
                // (the brushes declare their optional arguments that way), is
                // printed as it is; everything else is a required argument.
                if (argument.startsWith("-") || argument.startsWith("[") || argument.startsWith("<")) {
                    sb.append(argument);
                } else {
                    sb.append('<').append(argument).append('>');
                }
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
        String key = name.toLowerCase(Locale.ROOT);
        if (byAlias.containsKey(key)) {
            throw new IllegalStateException("Command already registered: " + name);
        }
        Entry entry = new Entry(name);
        commands.put(key, entry);
        byAlias.put(key, entry);
        return entry;
    }

    /**
     * Convenience for the fluent style used in {@link Commands}.
     *
     * <p>A spelling that is already taken is a bug in the caller, not something
     * to resolve silently: without this check a late registration replaces an
     * implemented command and the replacement is only visible at runtime.</p>
     */
    public Entry register(String name, String... aliases) {
        Entry entry = register(name);
        entry.aliases.addAll(Arrays.asList(aliases));
        for (String alias : aliases) {
            String key = alias.toLowerCase(Locale.ROOT);
            if (byAlias.containsKey(key)) {
                throw new IllegalStateException("Command alias already registered: " + alias);
            }
            byAlias.put(key, entry);
        }
        return entry;
    }

    public Entry get(String name) {
        return byAlias.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Registers a second spelling of a command: the new entry shares the
     * handler, the arguments and the flags of the one it copies.
     *
     * <p>Used for the names WorldEdit gives a tool at the top level, such as
     * {@code /mask} for {@code /tool mask}: both spellings have to accept the
     * same line, help text and completions.</p>
     */
    public Entry alias(String spelling, Entry target) {
        Entry entry = register(spelling);
        entry.description = target.description;
        entry.help = target.help;
        entry.group = target.group;
        entry.status = "alias";
        entry.arguments.addAll(target.arguments);
        entry.booleanFlags.addAll(target.booleanFlags);
        entry.valueFlags.addAll(target.valueFlags);
        entry.requiresSelection = target.requiresSelection;
        entry.requiresPlayer = target.requiresPlayer;
        entry.requiresWorld = target.requiresWorld;
        entry.suggestions = target.suggestions;
        entry.handler = target.handler;
        return entry;
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
        // The client sends "set" for both "/set" and "//set", and ";" for "//;":
        // every spelling of the same name is tried before the name is unknown.
        String bare = key;
        while (bare.startsWith("/")) {
            bare = bare.substring(1);
        }
        for (String candidate : new String[] { bare, "/" + bare, "//" + bare }) {
            entry = byAlias.get(candidate);
            if (entry != null) {
                return entry;
            }
        }
        return null;
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
    /**
     * Builds the context of a command line without running it: the same lookup and
     * token split {@link #dispatch} uses, for callers that parse arguments of a
     * command rather than run it.
     *
     * @return the context, or {@code null} when no command answers to that name
     */
    public Ctx context(Actor actor, String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("/")) {
            // Accept "/fast", "//set" and "/we" through one entry point.
            while (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
                trimmed = trimmed.substring(1);
            }
        }
        List<String> tokens = Str.split(trimmed);
        if (tokens.isEmpty()) {
            return null;
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
        return entry == null ? null : new Ctx(entry, actor, tokens);
    }

    public boolean dispatch(Actor actor, String line) {
        Ctx context = context(actor, line);
        if (context == null) {
            actor.message(Msg.error("Unknown command: " + line.trim()));
            return false;
        }
        Entry entry = context.entry();
        // A command binds the extent its masks read blocks through, and several of
        // them - //smooth, //replace, the brushes - leave it bound while they run.
        // Without this the binding of one command is still in place for the next
        // one, and a mask parsed there reads through a dead session.
        Extent previous = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
        try {
            // WorldEdit binds these commands to a Player parameter, so a source
            // without one - the server console, a command block, a function -
            // cannot reach them at all. The commands that only need somewhere to
            // build take an Actor and are answered below with the selection.
            if (entry.requiresPlayer && !actor.isPlayer()) {
                throw error("This command must be run by a player");
            }
            if (entry.handler == null) {
                actor.message(Msg.warn("Command '" + entry.name
                        + "' is registered but not implemented in this build."));
                return true;
            }
            entry.handler.run(context);
            context.reportTrace();
            return true;
        } catch (CommandException e) {
            actor.message(Msg.error(e.getMessage()));
            return true;
        } catch (com.maxlananas.fawebim.core.extent.EditSession.MaxChangedBlocksException e) {
            actor.message(Msg.error("Max blocks changed in an operation: " + e.getLimit()));
            return true;
        } catch (com.maxlananas.fawebim.core.util.TimeLimiter.OperationTimeoutException e) {
            actor.message(Msg.error("Operation timed out after " + e.elapsedMillis() + "ms"));
            return true;
        } catch (Exception e) {
            actor.message(Msg.error("Command failed: " + e.getMessage()));
            return true;
        } finally {
            if (previous == null) {
                com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.clear();
            } else {
                com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.set(previous);
            }
        }
    }

    /**
     * Extends a container's aliases to the commands inside it.
     *
     * <p>WorldEdit registers one spelling per sub-command ({@code /brush sphere})
     * and gives the container its aliases ({@code /br}); in game the alias covers
     * the whole subtree, so {@code //br sphere} has to be the sphere brush. The
     * routing is done here rather than at every registration so a container only
     * declares its own aliases once.</p>
     */
    public void expandContainerAliases() {
        for (Entry child : new ArrayList<>(commands.values())) {
            List<String> spellings = new ArrayList<>();
            spellings.add(child.name);
            spellings.addAll(child.aliases);
            for (String spelling : spellings) {
                int space = spelling.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                Entry parent = byAlias.get(spelling.substring(0, space).toLowerCase(Locale.ROOT));
                if (parent == null || parent == child) {
                    continue;
                }
                String rest = spelling.substring(space);
                for (String alias : new ArrayList<>(parent.aliases)) {
                    if (alias.indexOf(' ') >= 0) {
                        continue;
                    }
                    String derived = alias + rest;
                    if (byAlias.putIfAbsent(derived.toLowerCase(Locale.ROOT), child) == null) {
                        child.aliases.add(derived);
                    }
                }
            }
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
