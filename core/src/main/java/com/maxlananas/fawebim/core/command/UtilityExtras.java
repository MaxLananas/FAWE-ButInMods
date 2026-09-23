package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.history.EditLog;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The utility commands that are not tied to a selection: expression evaluation,
 * macros, the history helpers shown as {@code //history}, the session settings
 * ({@code //watchdog}, {@code //tips}, {@code //placement}, {@code //reorder}),
 * {@code //cancel} / {@code //confirm} and the item search commands.
 */
final class UtilityExtras {

    private final CommandRegistry registry;

    UtilityExtras(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        calculate();
        cancel();
        confirm();
        macro();
        tips();
        watchdog();
        placement();
        reorder();
        sourceMask();
        heightmapInterface();
        registry();
        searchItem();
        listFilters();
        historySearch();
    }

    /**
     * {@code //calculate} — evaluates an expression and prints the result, the
     * command players use to check a formula before feeding it to
     * {@code //generate}.
     */
    private void calculate() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("calculate",
                "/calc", "/evaluate", "/eval", "/solve", "/!");
        if (entry == null) {
            return;
        }
        entry.description = "Evaluate a mathematical expression";
        entry.group = "utility";
        entry.arguments.add("expression");
        entry.handler = ctx -> {
            Expression expression = Expression.compile(ctx.joined(0));
            Expression.Variables variables = new Expression.Variables();
            variables.set("pi", Math.PI);
            variables.set("e", Math.E);
            variables.set("true", 1);
            variables.set("false", 0);
            for (int i = 0; i < ctx.args().size(); i++) {
                String argument = ctx.arg(i);
                int equals = argument.indexOf('=');
                if (equals > 0) {
                    variables.set(argument.substring(0, equals),
                            Double.parseDouble(argument.substring(equals + 1)));
                }
            }
            double value = expression.evaluate(variables);
            ctx.actor().message(Msg.of("§b= §f" + format(value)));
        };
    }

    private static String format(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    /** {@code //cancel} — aborts the next edit that reaches a timeout check. */
    private void cancel() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("cancel", "/fcancel");
        if (entry == null) {
            return;
        }
        entry.description = "Cancel your current command";
        entry.group = "utility";
        entry.handler = ctx -> {
            ctx.session().cancel();
            ctx.actor().message(Msg.success("The running edit will stop at the next checkpoint"));
        };
    }

    /**
     * {@code //confirm} — runs the command that asked for confirmation. Commands
     * that destroy a lot of data register themselves through
     * {@link LocalSession#setPendingCommand}.
     */
    private void confirm() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("confirm");
        if (entry == null) {
            return;
        }
        entry.description = "Confirm a command";
        entry.group = "utility";
        entry.handler = ctx -> {
            if (ctx.session().hasPendingCommand()) {
                String description = ctx.session().pendingDescription();
                ctx.session().confirmPending();
                ctx.actor().message(Msg.success("Confirmed: " + description));
                return;
            }
            ctx.actor().message(Msg.info("Nothing to confirm"));
        };
    }

    /**
     * {@code /macro} — runs a saved macro file, one command per line, with
     * {@code {arg}} placeholders replaced by the arguments given on the command
     * line. Macros live in the server's {@code macros} directory.
     */
    private void macro() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/macro", "/m");
        if (entry == null) {
            return;
        }
        entry.description = "Run a macro from the macros directory";
        entry.group = "utility";
        entry.arguments.add("file");
        entry.arguments.add("[args...]");
        entry.handler = ctx -> {
            Path folder = Config.get().resolveDirectory(Config.get().macroDirectory);
            Path file = folder.resolve(ctx.arg(0));
            if (!Files.isRegularFile(file)) {
                throw CommandRegistry.error("No macro named '" + ctx.arg(0) + "' in " + folder);
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                throw CommandRegistry.error("Could not read macro: " + e.getMessage());
            }
            List<String> arguments = new ArrayList<>();
            for (int i = 1; i < ctx.args().size(); i++) {
                arguments.add(ctx.arg(i));
            }
            int executed = 0;
            for (String line : lines) {
                String command = line.trim();
                if (command.isEmpty() || command.startsWith("#")) {
                    continue;
                }
                if (!arguments.isEmpty()) {
                    for (int i = 0; i < arguments.size(); i++) {
                        command = command.replace("{" + i + "}", arguments.get(i))
                                .replace("{" + (i + 1) + "}", arguments.get(i));
                    }
                }
                // Macros run in the same session, so selections and history stay
                // consistent with what the player would have typed.
                registry.dispatch(ctx.actor(), command);
                executed++;
            }
            ctx.actor().message(Msg.success("Ran " + executed + " command(s) from macro " + ctx.arg(0)));
        };
    }

    private void tips() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/tips");
        if (entry == null) {
            return;
        }
        entry.description = "Toggle FAWE's tips on and off";
        entry.group = "utility";
        entry.handler = ctx -> {
            boolean enabled = !ctx.session().isTips();
            ctx.session().setTips(enabled);
            ctx.actor().message(Msg.success("Tips " + (enabled ? "enabled" : "disabled")));
        };
    }

    /** {@code //watchdog} — whether long operations are stopped by the limiter. */
    private void watchdog() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("watchdog");
        if (entry == null) {
            return;
        }
        entry.description = "Changes watchdog hook state";
        entry.group = "utility";
        entry.handler = ctx -> {
            boolean enabled = !ctx.session().isWatchdogEnabled();
            ctx.session().setWatchdogEnabled(enabled);
            ctx.actor().message(Msg.success("Watchdog hook " + (enabled ? "enabled" : "disabled")
                    + (enabled ? "" : "; long edits will run to completion")));
        };
    }

    /**
     * {@code //placement} — where a pasted schematic lands relative to the
     * player: at the first block, the last block or the origin.
     */
    private void placement() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("placement", "/toggleplace");
        if (entry == null) {
            return;
        }
        entry.description = "Select which placement position to use for schematics";
        entry.group = "utility";
        entry.handler = ctx -> {
            LocalSession session = ctx.session();
            if (!ctx.args().isEmpty()) {
                session.setPlacementMode(parsePlacement(ctx.arg(0)));
            } else {
                session.setPlacementMode((session.getPlacementMode() + 1) % 3);
            }
            ctx.actor().message(Msg.success("Placement mode: " + session.placementModeName()));
        };
    }

    private static int parsePlacement(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "last" -> LocalSession.PLACEMENT_LAST;
            case "origin" -> LocalSession.PLACEMENT_ORIGIN;
            case "first" -> LocalSession.PLACEMENT_FIRST;
            default -> throw CommandRegistry.error("Placement must be first, last or origin");
        };
    }

    /**
     * {@code //reorder} — whether blocks are re-ordered while an edit runs, which
     * lets a pattern that reads the world (a clipboard brush, an ore vein) see a
     * consistent state. {@code multi} reorders one edit at a time, {@code full}
     * also reorders within the operation.
     */
    private void reorder() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("reorder");
        if (entry == null) {
            return;
        }
        entry.description = "Reorder the blocks of edits as they run";
        entry.group = "utility";
        entry.handler = ctx -> {
            LocalSession session = ctx.session();
            if (!ctx.args().isEmpty()) {
                session.setReorderMode(switch (ctx.arg(0).toLowerCase(Locale.ROOT)) {
                    case "none" -> LocalSession.REORDER_NONE;
                    case "multi" -> LocalSession.REORDER_MULTI;
                    case "full" -> LocalSession.REORDER_FULL;
                    default -> throw CommandRegistry.error("Reorder must be none, multi or full");
                });
            } else {
                session.setReorderMode((session.getReorderMode() + 1) % 3);
            }
            ctx.actor().message(Msg.success("Reorder mode: " + session.reorderModeName()));
        };
    }

    /** {@code //gsmask} — the mask applied to the blocks an operation reads. */
    private void sourceMask() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("gsmask", "/sourcemask",
                "/targetmask", "/tarmask");
        if (entry == null) {
            return;
        }
        entry.description = "Set the global source mask";
        entry.group = "mask";
        entry.arguments.add("[mask]");
        entry.handler = ctx -> {
            if (ctx.args().isEmpty()) {
                ctx.session().setSourceMask(null);
                ctx.actor().message(Msg.success("Source mask cleared"));
                return;
            }
            ctx.session().setSourceMask(Parsers.mask(ctx.joined(0), ctx));
            ctx.actor().message(Msg.success("Source mask set to " + ctx.joined(0)));
        };
    }

    /**
     * {@code //heightmapinterface} — reports the heightmap the current world uses
     * and shows the world height limits, which is what FAWE's interface command
     * exposes to players debugging terrain edits.
     */
    private void heightmapInterface() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("heightmapinterface", "/hmi");
        if (entry == null) {
            return;
        }
        entry.description = "Show the world's height limits and heightmap information";
        entry.group = "infos";
        entry.handler = ctx -> {
            World world = ctx.world();
            ctx.actor().message(Msg.info("World: " + world.name()
                    + " | min Y: " + world.minY() + " | max Y: " + world.maxY()));
            ctx.actor().message(Msg.info("Heightmap is generated by the server; use //removeabove or //upto to act on it"));
        };
    }

    /** {@code //registry} — counts the registered block states and biomes. */
    private void registry() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("registry");
        if (entry == null) {
            return;
        }
        entry.description = "Show the size of the block and biome registries";
        entry.group = "infos";
        entry.valueFlags.add("p");
        entry.arguments.add("[-p <page>]");
        entry.handler = ctx -> {
            BlockStateRegistry states = BlockState.registry();
            ctx.actor().message(Msg.info("Block states: " + states.stateCount()
                    + " | biomes: " + states.biomeNames().size()
                    + " | block tags: " + states.blockTags().size()));
            // -p walks the block tags a page at a time, which is what FAWE uses
            // the page argument for once the summary above is printed.
            List<String> tags = states.blockTags();
            if (tags.isEmpty()) {
                return;
            }
            Page page = Page.of(ctx, tags.size());
            ctx.actor().message(Msg.info(page.header("Block tags", tags.size()) + " "
                    + String.join(", ", tags.subList(page.from(), page.to()))));
        };
    }

    /**
     * {@code /searchitem [-b] [-i] [-p page] <query>} — finds blocks by name.
     *
     * <p>FAWE searches its item and block registries; the engine's registry is the
     * block one, so that is what the query runs against, twenty hits per page.</p>
     */
    private void searchItem() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/searchitem", "/search", "/l");
        if (entry == null) {
            return;
        }
        entry.description = "Search for an item";
        entry.group = "infos";
        entry.booleanFlags.add("b");
        entry.booleanFlags.add("i");
        entry.valueFlags.add("p");
        entry.arguments.add("query");
        entry.handler = ctx -> {
            String query = ctx.joined(0).toLowerCase(Locale.ROOT);
            if (query.length() <= 2) {
                throw CommandRegistry.error("The search query must be longer than two characters");
            }
            if (ctx.hasFlag("b") && ctx.hasFlag("i")) {
                throw CommandRegistry.error("Use either -b or -i, not both");
            }
            java.util.List<String> matches = BlockState.registry().blockNames().stream()
                    .filter(name -> name.contains(query))
                    .sorted()
                    .toList();
            if (matches.isEmpty()) {
                ctx.actor().message(Msg.info("No block matches '" + query + "'"));
                return;
            }
            int page = Math.max(1, ctx.flagInt("p", 1));
            int pages = (matches.size() + 19) / 20;
            ctx.actor().message(Msg.info("Blocks matching '" + query + "' (page " + page + "/" + pages + "):"));
            for (int i = (page - 1) * 20; i < Math.min(matches.size(), page * 20); i++) {
                ctx.actor().message(Msg.of("§7 - §f" + matches.get(i)));
            }
        };
    }

    /**
     * {@code /list <filter>} — which schematics {@code //schem list} shows, the
     * FAWE list filters: {@code all}, {@code global}/{@code public} and
     * {@code local}/{@code private}/{@code me}/{@code mine}.
     */
    private void listFilters() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/list",
                "/list all", "/list global", "/list local", "/list me", "/list mine", "/list private", "/list public");
        if (entry == null) {
            return;
        }
        entry.description = "Choose which schematics //schem list shows";
        entry.group = "schematic";
        entry.arguments.add("[filter]");
        entry.handler = ctx -> {
            String requested = ctx.args().isEmpty() ? "all" : ctx.arg(ctx.args().size() - 1);
            com.maxlananas.fawebim.core.clipboard.ListFilter filter =
                    com.maxlananas.fawebim.core.clipboard.ListFilter.parse(requested);
            if (filter == null) {
                throw CommandRegistry.error("Unknown filter '" + requested
                        + "'. Try all, global, public, local, private, me or mine");
            }
            ctx.session().setListFilter(filter);
            ctx.actor().message(Msg.success("//schem list now shows " + filter.describe()));
        };
    }

    /** {@code //history} — the edits of this server and what they changed. */
    private void historySearch() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/history");
        if (entry == null) {
            return;
        }
        entry.description = "Inspect the edits of this server";
        entry.group = "history";
        entry.valueFlags.add("p");
        entry.valueFlags.add("u");
        entry.valueFlags.add("t");
        entry.valueFlags.add("r");
        // -f restores instead of rolling back, exactly as FAWE's flag does.
        entry.booleanFlags.add("f");
        entry.arguments.add("list|info|summary|summarize|distr|distribution|find|inspect|search|near"
                + "|rollback|restore|rerun|import|clear");
        entry.arguments.add("[-u <user>]");
        entry.arguments.add("[-t <time>]");
        entry.arguments.add("[-r <radius>]");
        entry.arguments.add("[-p <page>]");
        entry.handler = ctx -> {
            LocalSession session = ctx.session();
            String action = ctx.arg(0, "list").toLowerCase(Locale.ROOT);
            switch (action) {
                case "list" -> list(ctx);
                case "info", "summary", "summarize" -> {
                    History.Record current = session.getHistory().getCurrent();
                    ctx.actor().message(current == null ? Msg.info("No edit recorded yet")
                            : Msg.info("Last edit: " + current.description
                            + " (" + current.changeCount() + " block(s))"));
                }
                case "distr", "distribution" -> distribution(ctx);
                case "find", "inspect", "search", "near" -> find(ctx);
                case "rollback" -> applyMatches(ctx, !ctx.hasFlag("f"));
                case "restore", "rerun" -> applyMatches(ctx, false);
                case "import" -> ctx.actor().message(Msg.info(
                        "Importing a database history needs a database, which the standalone mod does not ship"));
                case "clear" -> {
                    session.getHistory().clear();
                    EditLog.clear();
                    ctx.actor().message(Msg.success("History cleared"));
                }
                default -> throw CommandRegistry.error(
                        "Usage: //history list|info|distr|find|rollback|restore|clear");
            }
        };
    }

    /** {@code //history list [-p <page>]} — the edits of this server, newest first. */
    private void list(Ctx ctx) {
        List<EditLog.Entry> entries = EditLog.entries();
        if (entries.isEmpty()) {
            ctx.actor().message(Msg.info("No edit recorded yet"));
            return;
        }
        Page page = Page.of(ctx, entries.size());
        ctx.actor().message(Msg.info(page.header("Edits", entries.size())));
        for (EditLog.Entry entry : entries.subList(page.from(), page.to())) {
            ctx.actor().message(Msg.of("§7 - §f" + entry.actor + "§7 " + entry.record.description
                    + " §7(" + Msg.formatNumber(entry.record.changeCount()) + " block(s), " + time(ctx, entry) + ")"));
        }
        page.hint(ctx, "//history list");
    }

    /** {@code //history distr [-p <page>]} — what the last edit changed, by block. */
    private void distribution(Ctx ctx) {
        History.Record current = ctx.session().getHistory().getCurrent();
        if (current == null) {
            throw CommandRegistry.error("No edit recorded yet");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var sets : current.changes().values()) {
            for (var set : sets) {
                for (int i = 0; i < set.size(); i++) {
                    counts.merge(BlockState.registry().name(set.before()[i]), 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Page page = Page.of(ctx, sorted.size());
        ctx.actor().message(Msg.info(page.header("Blocks changed by the last edit (before state)",
                sorted.size())));
        for (Map.Entry<String, Integer> counted : sorted.subList(page.from(), page.to())) {
            ctx.actor().message(Msg.of("§7" + counted.getKey() + "§r: §f" + counted.getValue()));
        }
        page.hint(ctx, "//history distr");
    }

    /** {@code //history find [-u <user>] [-t <time>] [-r <radius>] [-p <page>]}. */
    private void find(Ctx ctx) {
        List<EditLog.Entry> matches = matches(ctx);
        if (matches.isEmpty()) {
            ctx.actor().message(Msg.info("No edit matches those filters"));
            return;
        }
        Page page = Page.of(ctx, matches.size());
        ctx.actor().message(Msg.info(page.header("Matching edits", matches.size())));
        for (EditLog.Entry entry : matches.subList(page.from(), page.to())) {
            ctx.actor().message(Msg.of("§7 - §f" + entry.actor + "§7 " + entry.record.description
                    + " §7(" + Msg.formatNumber(entry.record.changeCount()) + " block(s), " + time(ctx, entry) + ")"));
        }
        page.hint(ctx, "//history find");
    }

    /**
     * {@code //history rollback [-u] [-t] [-r] [-f]} and {@code //history restore}:
     * every matching edit is applied again, either as its before state (rollback)
     * or as its after state (restore, which is what {@code -f} asks for).
     */
    private void applyMatches(Ctx ctx, boolean undo) {
        List<EditLog.Entry> matches = matches(ctx);
        if (matches.isEmpty()) {
            ctx.actor().message(Msg.error("No edit matches those filters"));
            return;
        }
        EditSession session = new EditSession(ctx.world(), ctx.session(), "history", false);
        int changed = 0;
        for (EditLog.Entry entry : matches) {
            for (var sets : entry.record.changes().values()) {
                for (var set : sets) {
                    changed += session.applyChangeSet(set, undo);
                }
            }
        }
        session.flushQueue();
        ctx.actor().message(Msg.success((undo ? "Rolled back " : "Restored ") + Msg.formatNumber(changed)
                + " block change(s) from " + matches.size() + " edit(s)"));
    }

    /** Applies the {@code -u}, {@code -t} and {@code -r} filters of the command line. */
    private List<EditLog.Entry> matches(Ctx ctx) {
        String user = ctx.hasFlag("u") ? ctx.flagValue("u", "") : null;
        long since = ctx.hasFlag("t")
                ? System.currentTimeMillis() - Commands.parseDuration(ctx.flagValue("t", "")) : -1;
        double radius = ctx.hasFlag("r") ? ctx.flagDouble("r", -1) : -1;
        BlockVector3 origin = radius >= 0 ? ctx.actor().position() : null;
        return EditLog.find(user, ctx.world().name(), radius, since, origin);
    }

    private static String time(Ctx ctx, EditLog.Entry entry) {
        return java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(entry.time),
                        ctx.session().getTimezone())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

}
