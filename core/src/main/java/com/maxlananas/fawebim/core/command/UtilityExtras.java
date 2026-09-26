package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.history.EditLog;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.Placement;
import com.maxlananas.fawebim.core.session.PlacementType;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

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
        togglePlace();
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//calculate",
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//cancel", "/fcancel");
        if (entry == null) {
            return;
        }
        entry.description = "Cancel your current command";
        entry.group = "utility";
        entry.requiresPlayer = true;
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//confirm");
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//watchdog");
        if (entry == null) {
            return;
        }
        entry.description = "Changes watchdog hook state";
        entry.group = "utility";
        entry.arguments.add("[active|inactive]");
        entry.handler = ctx -> {
            LocalSession session = ctx.session();
            Boolean mode = ctx.args().isEmpty() ? null : Parsers.hookMode(ctx.arg(0));
            boolean active = session.isWatchdogEnabled();
            if (mode != null && mode == active) {
                ctx.actor().message(Msg.info(active
                        ? "Watchdog hook already active." : "Watchdog hook already inactive."));
                return;
            }
            active = mode != null ? mode : !active;
            session.setWatchdogEnabled(active);
            ctx.actor().message(Msg.result("Watchdog hook", active ? "active" : "inactive"));
        };
    }

    /**
     * {@code /placement} — where pastes and generators start from.
     *
     * <p>The type names the anchor - the world origin, the block the player
     * stands in, the first position of the selection or one of its corners - and
     * the offset moves it, multiplied by the number in between. {@code here}
     * becomes the world origin plus the player's coordinates, which is how
     * WorldEdit reads it.</p>
     */
    private void placement() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("placement", "/placement");
        if (entry == null) {
            return;
        }
        entry.description = "Select which placement to use";
        entry.group = "utility";
        entry.arguments.add("placementType");
        entry.arguments.add("[multiplier]");
        entry.arguments.add("[offset]");
        entry.handler = ctx -> {
            PlacementType type = PlacementType.parse(ctx.arg(0));
            if (type == null) {
                throw CommandRegistry.error("Placement must be one of " + PlacementType.names()
                        + ", got '" + ctx.arg(0) + "'");
            }
            int multiplier = ctx.intArg(1, 1);
            BlockVector3 offset = ctx.args().size() < 3 ? BlockVector3.ZERO : ctx.blockVector(2).multiply(multiplier);
            if (type == PlacementType.HERE) {
                if (!type.canBeUsedBy(ctx.actor())) {
                    throw CommandRegistry.error("Cannot toggle placing in this context.");
                }
                offset = offset.add(ctx.actor().position());
                type = PlacementType.WORLD;
            }
            Placement placement = new Placement(type, offset);
            if (!placement.canBeUsedBy(ctx.actor())) {
                throw CommandRegistry.error("Cannot toggle placing in this context.");
            }
            ctx.session().setPlacement(placement);
            ctx.actor().message(Msg.result("Placement", placement.message()));
        };
    }

    /**
     * {@code /toggleplace} — swaps between the block the player stands in and
     * the first position of the selection.
     */
    private void togglePlace() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("toggleplace", "/toggleplace");
        if (entry == null) {
            return;
        }
        entry.description = "Switch between your position and pos1 for placement";
        entry.group = "utility";
        entry.handler = ctx -> {
            PlacementType type = ctx.session().getPlacement().type() == PlacementType.POS1
                    ? PlacementType.PLAYER : PlacementType.POS1;
            Placement placement = new Placement(type, BlockVector3.ZERO);
            if (!placement.canBeUsedBy(ctx.actor())) {
                throw CommandRegistry.error("Cannot toggle placing in this context.");
            }
            ctx.session().setPlacement(placement);
            ctx.actor().message(Msg.result("Placement", placement.message()));
        };
    }

    /**
     * {@code //reorder} — the order the blocks of an edit are written in.
     *
     * <p>WorldEdit deprecated the setter of this mode, and FAWE's session keeps
     * answering {@code fast} whatever it was given, so an edit is written in the
     * order it was generated and the command only names the mode back; the names
     * it accepts are upstream's, spelled the way its converter spells them.</p>
     */
    private void reorder() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//reorder");
        if (entry == null) {
            return;
        }
        entry.description = "Sets the reorder mode of WorldEdit";
        entry.group = "utility";
        entry.arguments.add("[none|multi|fast]");
        entry.handler = ctx -> {
            if (ctx.args().isEmpty()) {
                ctx.actor().message(Msg.info("The reorder mode is " + LocalSession.REORDER_NAME));
            } else {
                requireReorderMode(ctx.arg(0));
                ctx.actor().message(Msg.success("The reorder mode is now " + LocalSession.REORDER_NAME));
            }
        };
    }

    /** The three names upstream accepts for {@code //reorder}. */
    private static void requireReorderMode(String word) {
        String mode = word.toLowerCase(Locale.ROOT);
        if (!mode.equals("none") && !mode.equals("multi") && !mode.equals("fast")) {
            throw CommandRegistry.error("Reorder mode must be none, multi or fast, got '" + word + "'");
        }
    }

    /** {@code //gsmask} — the mask applied to the blocks an operation reads. */
    private void sourceMask() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//gsmask");
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
     * {@code //heightmapinterface [-min <size>] [-max <size>]} — writes every
     * heightmap image of the heightmap folder at the two sizes the web interface
     * asks for, and the {@code config.js} listing them.
     *
     * <p>FAWE ships this so the HeightMap tool can be served from the game folder;
     * the scaled copies land in {@code web/heightmap/images/min} and
     * {@code web/heightmap/images/max}.</p>
     */
    private void heightmapInterface() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//heightmapinterface", "/hmi");
        if (entry == null) {
            return;
        }
        entry.description = "Generate the heightmap interface";
        entry.group = "infos";
        entry.arguments.add("[min]");
        entry.arguments.add("[max]");
        entry.handler = ctx -> {
            int min = ctx.intArg(0, 100);
            int max = ctx.intArg(1, 200);
            Path source = Config.get().resolveDirectory("heightmap");
            Path web = Config.get().resolveDirectory("web/heightmap");
            Path minImages = web.resolve("images/min");
            Path maxImages = web.resolve("images/max");
            ctx.actor().message(Msg.info("Please wait while we generate the minified heightmaps."));
            List<String> written = new ArrayList<>();
            try (java.util.stream.Stream<Path> files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
                    if (image == null) {
                        continue;
                    }
                    String name = source.relativize(file).toString().replace('\\', '/');
                    javax.imageio.ImageIO.write(scale(image, min, min), "png",
                            writeFile(minImages.resolve(name)).toFile());
                    javax.imageio.ImageIO.write(max == -1 ? image : scale(image, max, max), "png",
                            writeFile(maxImages.resolve(name)).toFile());
                    ctx.actor().message(Msg.info("Writing " + name));
                    written.add(name);
                }
            } catch (IOException e) {
                throw CommandRegistry.error("Could not generate the heightmap interface: "
                        + e.getMessage());
            }
            try {
                StringBuilder config = new StringBuilder();
                config.append("var images = [\n");
                for (String name : written) {
                    config.append('"').append(name).append("\",\n");
                }
                config.append("];\n");
                config.append("// The low res images (they should all be the same size)\n");
                config.append("var src_min = \"images/min/\";\n");
                config.append("// The max resolution images (use the same if there are none)\n");
                config.append("var src_max = \"images/max/\";\n");
                config.append("// The local source for the image (used in commands)\n");
                config.append("var src_local = \"file://\";\n");
                Files.createDirectories(web);
                Files.writeString(web.resolve("config.js"), config.toString());
            } catch (IOException e) {
                throw CommandRegistry.error("Could not write the heightmap config: "
                        + e.getMessage());
            }
            ctx.actor().message(Msg.success("Done! See: " + web));
        };
    }

    private static Path writeFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return file;
    }

    private static java.awt.image.BufferedImage scale(java.awt.image.BufferedImage image,
                                                      int width, int height) {
        java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * {@code //registry <registry> [-p <page>] [query]} — searches a registry the
     * way upstream does, with {@code *} and {@code ?} wildcards and one page of
     * results at a time.
     */
    private void registry() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//registry");
        if (entry == null) {
            return;
        }
        entry.description = "Search through the given registry";
        entry.group = "infos";
        entry.valueFlags.add("p");
        entry.arguments.add("registry");
        entry.arguments.add("[query]");
        entry.handler = ctx -> {
            String query = String.join("_", ctx.joined(1).isEmpty() ? List.of("*") : ctx.rawArgs(1));
            if (!query.matches("[a-z0-9_:?*/]+")) {
                throw CommandRegistry.error("Invalid registry key: " + query);
            }
            List<String> names = registryContents(ctx.arg(0));
            String glob = query.contains("*") || query.contains("?") ? query : "*" + query + "*";
            java.util.regex.Pattern matcher = java.util.regex.Pattern.compile(
                    globToRegex(glob), java.util.regex.Pattern.CASE_INSENSITIVE);
            List<String> matches = new ArrayList<>();
            for (String name : names) {
                if (matcher.matcher(name).matches()) {
                    matches.add(name);
                }
            }
            Page page = Page.of(ctx, matches.size());
            String title = query.isBlank() || query.equals("*") ? "Registry contents"
                    : "Search results for '" + query + "'";
            ctx.actor().message(Msg.info(page.header(title, matches.size())));
            ctx.actor().message(Msg.info(String.join(", ", matches.subList(page.from(), page.to()))));
        };
    }

    /** The names of one of the registries the search understands. */
    private static List<String> registryContents(String name) {
        BlockStateRegistry states = BlockState.registry();
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "block", "blocks" -> states.blockNames();
            case "item", "items" -> states.itemNames();
            case "biome", "biomes" -> states.biomeNames();
            case "blocktag", "blocktags", "tag", "tags" -> states.blockTags();
            case "blockcategory", "blockcategories", "category", "categories" ->
                    states.categories();
            default -> throw CommandRegistry.error("Unknown registry: " + name
                    + ". Try block, item, biome, blocktag or category");
        };
    }

    /** Turns a {@code *}/{@code ?} glob into a regular expression. */
    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder();
        for (char character : glob.toCharArray()) {
            switch (character) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                default -> out.append(java.util.regex.Pattern.quote(String.valueOf(character)));
            }
        }
        return out.toString();
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
        // The actions FAWE spreads over /history and its sub-commands: the
        // search ones, the rollback pair and the three the shortcuts cover.
        entry.arguments.add("list|info|summary|summarize|distr|distribution|find|inspect|search|near"
                + "|rollback|restore|rerun|import|clear|size"
                + "|undo|redo|clearhistory");
        entry.arguments.add("[-u <user>]");
        entry.arguments.add("[-t <time>]");
        entry.arguments.add("[-r <radius>]");
        entry.arguments.add("[-p <page>]");
        entry.handler = ctx -> {
            LocalSession session = ctx.session();
            String action = ctx.arg(0, "list").toLowerCase(Locale.ROOT);
            switch (action) {
                // /history undo and friends are the shortcuts under the same
                // container in FAWE, so they run the command that owns them.
                case "undo" -> CommandManager.get().dispatch(ctx.actor(), "//undo");
                case "redo" -> CommandManager.get().dispatch(ctx.actor(), "//redo");
                case "clearhistory" -> CommandManager.get().dispatch(ctx.actor(), "//clearhistory");
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
                case "size" -> {
                    if (ctx.args().size() < 2) {
                        ctx.actor().message(Msg.keyValue("History size",
                                ctx.session().getHistory().maxRecords()));
                        return;
                    }
                    int requested = ctx.intArg(1);
                    int maximum = Config.get().maxHistorySize;
                    if (maximum >= 0 && requested > maximum) {
                        throw CommandRegistry.error("History size must be at most " + maximum
                                + " (raise history.max-size in config/fawebim.yml)");
                    }
                    ctx.session().getHistory().setMaxRecords(requested);
                    ctx.actor().message(Msg.success("History size set to "
                            + ctx.session().getHistory().maxRecords()));
                }
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
                    counts.merge(BlockState.registry().name(set.beforeAt(i)), 1, Integer::sum);
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
        ctx.actor().message(Msg.result(undo ? "Rolled back" : "Restored", Msg.count(changed)
                + "\u00a77 block change(s) from " + Msg.count(matches.size()) + "\u00a77 edit(s)"));
    }

    /** Applies the {@code -u}, {@code -t} and {@code -r} filters of the command line. */
    private List<EditLog.Entry> matches(Ctx ctx) {
        String user = ctx.hasFlag("u") ? ctx.flagValue("u", "") : null;
        long since = ctx.hasFlag("t")
                ? System.currentTimeMillis() - Commands.parseDuration(ctx.flagValue("t", "")) : -1;
        double radius = ctx.hasFlag("r") ? ctx.flagDouble("r", -1) : -1;
        BlockVector3 origin = radius >= 0 ? ctx.placement() : null;
        return EditLog.find(user, ctx.world().name(), radius, since, origin);
    }

    private static String time(Ctx ctx, EditLog.Entry entry) {
        return java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(entry.time),
                        ctx.session().getTimezone())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

}
