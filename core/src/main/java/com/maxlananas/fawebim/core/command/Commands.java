package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Navigation;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.HeightMaps;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.SideEffect;
import com.maxlananas.fawebim.core.session.SideEffectSet;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The WorldEdit/FAWE commands implemented by this build.
 *
 * <p>Every entry keeps FAWE's name, aliases, flags and argument order. Commands
 * that exist upstream but are not implemented here are still registered (see
 * {@link Stubs}) and report their status, so the full command surface stays
 * present and the inventory audit keeps seeing every upstream name.</p>
 */
public final class Commands {

    private final CommandRegistry registry;

    public Commands(CommandRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Flushes the queued blocks and answers with one line naming what the
     * command did - a label, the count and the time - or with nothing at all
     * when the handler already reported the result itself.
     */
    private void flush(Ctx ctx, EditSession session, String label) {
        flush(ctx, session, label, session.getBlocksChanged(), "block(s)");
    }

    private void flush(Ctx ctx, EditSession session, String label, long changed, String unit) {
        session.flushQueue();
        if (label != null) {
            ctx.actor().message(session.result(label, changed, unit));
        }
    }

    private static int air() {
        return BlockState.registry().air();
    }

    /** Replaces every block in a region matching {@code mask} with {@code pattern}. */
    private int fill(EditSession session, Region region, Pattern pattern, Mask mask) {
        return region.forEachPosition((x, y, z) -> {
            if (mask != null && !mask.test(x, y, z)) {
                return false;
            }
            session.limiter().check(1);
            return session.setBlock(x, y, z, pattern.apply(x, y, z));
        });
    }

    public void registerAll() {
        registerSelection();
        registerRegion();
        registerGeneration();
        registerClipboard();
        registerHistory();
        registerBiome();
        registerChunk();
        registerNavigation();
        registerUtility();
        registerMasksAndPatterns();
        registerBrushes();
        registerTools();
        // The command classes below take over the names the previous groups do not
        // implement, so the port can grow a class at a time.
        new RegionCommands(registry).register();
        new GenerationCommands(registry).register();
        new ClipboardExtras(registry).register();
        new AnvilCommands(registry).register();
        new ScriptCommands(registry).register();
        new SnapshotCommands(registry).register();
        new UtilityExtras(registry).register();
        new ConfigCommands(registry).register();
        new ToolUtilCommands(registry).register();
        new WorldCommands(registry).register();
        Stubs.register(registry);
    }

    // ---------------------------------------------------------------- selection

    private void registerSelection() {
        CommandRegistry.Entry e1 = registry.register("//pos1", "//p1");
        e1.description = "Set position 1 to your position or the given coordinates";
        e1.group = "selection";
        e1.arguments.add("[coordinates]");
        e1.handler = ctx -> {
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectPrimary(pos, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.result("Position 1", "set to " + Msg.value(pos).raw()));
                };


        CommandRegistry.Entry e2 = registry.register("//pos2", "//p2");
        e2.description = "Set position 2 to your position or the given coordinates";
        e2.group = "selection";
        e2.arguments.add("[coordinates]");
        e2.handler = ctx -> {
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectSecondary(pos, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.result("Position 2", "set to " + Msg.value(pos).raw()));
                };


        CommandRegistry.Entry e3 = registry.register("//hpos1");
        e3.description = "Set position 1 to the block you are looking at";
        e3.group = "selection";
        e3.requiresPlayer = true;
        e3.handler = ctx -> {
                    BlockVector3 target = ctx.targetBlock(100);
                    ctx.session().getSelector(ctx.world()).selectPrimary(target,
                            com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.result("Position 1", "set to " + Msg.value(target).raw()));
                };


        CommandRegistry.Entry e4 = registry.register("//hpos2");
        e4.description = "Set position 2 to the block you are looking at";
        e4.group = "selection";
        e4.requiresPlayer = true;
        e4.handler = ctx -> {
                    BlockVector3 target = ctx.targetBlock(100);
                    ctx.session().getSelector(ctx.world()).selectSecondary(target,
                            com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.result("Position 2", "set to " + Msg.value(target).raw()));
                };


        CommandRegistry.Entry e5 = registry.register("//pos");
        e5.description = "Set positions";
        e5.group = "selection";
        // -s switches to the given selector before placing the positions.
        e5.valueFlags.add("s");
        e5.arguments.add("[coordinates]");
        e5.arguments.add("[secondary coordinates]");
        e5.arguments.add("[-s <selector>]");
        e5.handler = ctx -> {
                    if (ctx.hasFlag("s")) {
                        String type = ctx.flagValue("s", "").toLowerCase(Locale.ROOT);
                        RegionSelector chosen = LocalSession.newSelectors(ctx.world(), type);
                        if (chosen == null) {
                            throw CommandRegistry.error("Unknown selection type '" + type + "'");
                        }
                        ctx.session().setSelector(chosen);
                    }
                    // Without coordinates both positions land where the player
                    // stands; with them the first sets position 1 and the second
                    // position 2, as WorldEdit's /pos does.
                    BlockVector3 primary = ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
                    if (primary == null) {
                        throw CommandRegistry.error("Coordinates are required when the command is not run by a player");
                    }
                    BlockVector3 secondary = ctx.args().size() > 1 ? ctx.blockVector(1) : primary;
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectPrimary(primary, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    selector.selectSecondary(secondary, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.result("Position 1", "set to " + Msg.value(primary).raw()));
                    ctx.actor().message(Msg.result("Position 2", "set to " + Msg.value(secondary).raw()));
                };


        // ";" is the spelling WorldEdit gives this command: the client sends the
        // line without its leading slashes, so //; and /; reach it.
        CommandRegistry.Entry e6 = registry.register("//sel", ";");
        e6.description = "Choose the selection type: cuboid, extend, poly, ellipsoid, sphere, cyl, convex";
        e6.group = "selection";
        e6.arguments.add("[type]");
        // -d remembers the selector as the default for new sessions.
        e6.booleanFlags.add("d");
        e6.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.actor().message(Msg.info("Selection type: ").append(Msg.value(
                                ctx.session().getSelector(ctx.world()).getTypeName())));
                        ctx.actor().message(Msg.info("Types: cuboid, extend, poly, ellipsoid, sphere, cyl, convex"));
                        return;
                    }
                    String type = ctx.arg(0).toLowerCase(Locale.ROOT);
                    if (type.equals("none")) {
                        ctx.session().getSelector(ctx.world()).clear();
                        ctx.actor().message(Msg.info("Selection cleared"));
                        return;
                    }
                    RegionSelector selector = LocalSession.newSelectors(ctx.world(), type);
                    if (selector == null) {
                        throw CommandRegistry.error("Unknown selection type '" + type
                                + "'. Try cuboid, extend, poly, ellipsoid, sphere, cyl, convex.");
                    }
                    ctx.session().setSelector(selector);
                    if (ctx.hasFlag("d")) {
                        ctx.session().setDefaultSelectorType(selector.getTypeName());
                        ctx.actor().message(Msg.success("Default selection type set to " + selector.getTypeName()));
                        return;
                    }
                    ctx.actor().message(Msg.result("Selection type", "set to "
                            + Msg.value(selector.getTypeName()).raw()));
                };


        CommandRegistry.Entry e7 = registry.register("//wand");
        e7.description = "Give yourself the selection wand";
        e7.group = "selection";
        e7.requiresPlayer = true;
        // -n hands out the navigation wand instead of the selection wand.
        e7.booleanFlags.add("n");
        e7.handler = ctx -> {
                    String item = ctx.hasFlag("n") ? com.maxlananas.fawebim.core.platform.Config.get().navigationWandItem
                            : com.maxlananas.fawebim.core.platform.Config.get().wandItem;
                    if (ctx.actor().giveWand(item)) {
                        ctx.actor().message(Msg.result(
                                ctx.hasFlag("n") ? "Navigation wand" : "Wand",
                                Msg.value(item).raw() + "\u00a77 given"));
                    } else {
                        ctx.actor().message(Msg.error("Could not give you the wand"));
                    }
                };


        CommandRegistry.Entry e8 = registry.register("//toggleeditwand");
        e8.description = "Toggle the wand's function (only the commands remain active)";
        e8.requiresPlayer = true;
        e8.group = "selection";
        e8.handler = ctx -> {
                    LocalSession session = ctx.session();
                    session.setFastMode(!session.isFastMode());
                    ctx.actor().message(Msg.info("Edit wand is now "
                            + (session.isFastMode() ? "enabled" : "disabled")));
                };



        CommandRegistry.Entry e10 = registry.register("//drawsel");
        e10.description = "Draw the selection outline (uses particles, no client mod needed)";
        e10.requiresPlayer = true;
        e10.group = "selection";
        e10.arguments.add("[true|false]");
        e10.handler = ctx -> {
                    LocalSession session = ctx.session();
                    boolean enabled = ctx.args().isEmpty()
                            ? !session.isDrawSelection() : Parsers.booleanArg(ctx, 0, false);
                    if (enabled == session.isDrawSelection()) {
                        ctx.actor().message(Msg.info("Selection drawing already "
                                + (enabled ? "enabled" : "disabled")));
                        return;
                    }
                    session.setDrawSelection(enabled);
                    ctx.actor().updateSelectionOutline();
                    ctx.actor().message(Msg.success("Selection drawing "
                            + (enabled ? "enabled" : "disabled")));
                };


        CommandRegistry.Entry e11 = registry.register("//size");
        e11.description = "Show the size and dimensions of the selection";
        e11.group = "selection";
        e11.requiresSelection = true;
        // -c describes the clipboard instead of the selection.
        e11.booleanFlags.add("c");
        e11.handler = ctx -> {
                    if (ctx.hasFlag("c")) {
                        if (!ctx.session().hasClipboard()) {
                            throw CommandRegistry.error("No clipboard: use //copy first");
                        }
                        BlockArrayClipboard clip = ctx.session().getClipboard().getClipboard();
                        ctx.actor().message(Msg.keyValue("Clipboard",
                                clip.getWidth() + " x " + clip.getHeight() + " x " + clip.getLength()));
                        ctx.actor().message(Msg.keyValue("Volume", Msg.formatNumber(clip.volume())));
                        ctx.actor().message(Msg.keyValue("Origin", clip.getOrigin().toString()));
                        return;
                    }
                    Region region = ctx.selection();
                    ctx.actor().message(Msg.keyValue("Selection",
                            ctx.session().getSelector(ctx.world()).describe()));
                    ctx.actor().message(Msg.keyValue("Dimensions",
                            region.getWidth() + " x " + region.getHeight() + " x " + region.getLength()));
                    ctx.actor().message(Msg.keyValue("Volume", Msg.formatNumber(region.getVolume())));
                    ctx.actor().message(Msg.keyValue("Chunks", region.getChunks().size()));
                };


        CommandRegistry.Entry e12 = registry.register("//count");
        e12.description = "Count the number of blocks matching a mask";
        e12.group = "selection";
        e12.requiresSelection = true;
        e12.arguments.add("mask");
        e12.handler = ctx -> {
                    Mask mask = Parsers.mask(ctx.arg(0), ctx);
                    int count = 0;
                    for (BlockVector3 position : ctx.selection()) {
                        if (mask.test(position)) {
                            count++;
                        }
                    }
                    ctx.actor().message(Msg.keyValue("Count", Msg.formatNumber(count)));
                };


        CommandRegistry.Entry e13 = registry.register("//distr", "//distribution");
        e13.description = "Show the block distribution in the selection";
        e13.group = "selection";
        e13.requiresSelection = true;
        e13.booleanFlags.add("c");
        e13.booleanFlags.add("d");
        e13.valueFlags.add("p");
        e13.arguments.add("[-p <page>]");
        e13.requiresSelection = false;
        e13.handler = ctx -> {
                    java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
                    if (ctx.hasFlag("c")) {
                        if (!ctx.session().hasClipboard()) {
                            throw CommandRegistry.error("No clipboard: use //copy first");
                        }
                        BlockArrayClipboard clip = ctx.session().getClipboard().getClipboard();
                        for (BlockVector3 position : clip.positions()) {
                            int state = clip.getBlock(position);
                            if (BlockState.registry().isAirLike(state)) {
                                continue;
                            }
                            counts.merge(state, 1, Integer::sum);
                        }
                    } else {
                        for (BlockVector3 position : ctx.selection()) {
                            int state = ctx.world().getBlock(position.x(), position.y(), position.z());
                            counts.merge(state, 1, Integer::sum);
                        }
                    }
                    final long total = counts.values().stream().mapToLong(Integer::longValue).sum();
                    ctx.actor().message(Msg.info("Block distribution (" + Msg.formatNumber(total) + " blocks)"));
                    BlockStateRegistry blockRegistry = BlockState.registry();
                    // -d separates the states of a block, e.g. oak_log[axis=x].
                    boolean separate = ctx.hasFlag("d");
                    java.util.Map<String, Integer> named = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                        String name = separate ? blockRegistry.describe(entry.getKey())
                                : blockRegistry.name(entry.getKey());
                        named.merge(name, entry.getValue(), Integer::sum);
                    }
                    java.util.List<java.util.Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(named.entrySet());
                    sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    Page page = Page.of(ctx, sorted.size());
                    for (java.util.Map.Entry<String, Integer> entry : sorted.subList(page.from(), page.to())) {
                        ctx.actor().message(Msg.of("§7 - §f" + entry.getKey() + " §7= §b" + entry.getValue()
                                + " §7(" + String.format(Locale.ROOT, "%.2f",
                                entry.getValue() * 100.0 / Math.max(1, total)) + "%)"));
                    }
                    page.hint(ctx, "//distr");

                };


        // WorldEdit declares this one with a `vert` sub-command and a list of
        // directions: `//expand vert` takes the whole column, `//expand 10` grows
        // in the way the player looks, and `//expand 10 5 north,east` grows up to
        // ten and back to five in each of those directions.
        CommandRegistry.Entry e14 = registry.register("//expand", "/expand");
        e14.description = "Expand the selection area";
        e14.group = "selection";
        e14.requiresSelection = true;
        e14.arguments.add("amount");
        e14.arguments.add("[reverseAmount]");
        e14.arguments.add("[direction]");
        e14.handler = ctx -> {
                    Region region = ctx.selection();
                    if (ctx.arg(0).equalsIgnoreCase("vert") || ctx.arg(0).equalsIgnoreCase("vertical")) {
                        region.setY(ctx.world().minY(), ctx.world().maxY());
                        ctx.actor().message(Msg.result("Region expanded vertically",
                                Msg.value(region.describe()).raw()));
                        return;
                    }
                    int amount = ctx.intArg(0);
                    int reverse = ctx.intArg(1, 0);
                    List<BlockVector3> directions = expandDirections(ctx,
                            ctx.args().size() > 2 ? ctx.joined(2) : "me");
                    for (BlockVector3 direction : directions) {
                        region.expand(direction.multiply(amount));
                        if (reverse != 0) {
                            region.expand(direction.multiply(-reverse));
                        }
                    }
                    ctx.actor().message(Msg.result("Region expanded", Msg.value(region.describe()).raw()));
                };


        CommandRegistry.Entry e15 = registry.register("//contract");
        e15.description = "Contract the selection area";
        e15.group = "selection";
        e15.requiresSelection = true;
        e15.arguments.add("amount");
        e15.arguments.add("[reverseAmount]");
        e15.arguments.add("[direction]");
        e15.handler = ctx -> {
                    Region region = ctx.selection();
                    int amount = ctx.intArg(0);
                    int reverse = ctx.intArg(1, 0);
                    List<BlockVector3> directions = expandDirections(ctx,
                            ctx.args().size() > 2 ? ctx.joined(2) : "me");
                    for (BlockVector3 direction : directions) {
                        region.contract(direction.multiply(amount));
                        if (reverse != 0) {
                            region.contract(direction.multiply(-reverse));
                        }
                    }
                    ctx.actor().message(Msg.result("Region contracted", Msg.value(region.describe()).raw()));
                };


        CommandRegistry.Entry e16 = registry.register("//shift");
        e16.description = "Shift the selection area";
        e16.group = "selection";
        e16.requiresSelection = true;
        e16.arguments.add("amount");
        e16.arguments.add("[direction]");
        e16.handler = ctx -> {
                    Region region = ctx.selection();
                    int amount = ctx.intArg(0);
                    List<BlockVector3> directions = expandDirections(ctx,
                            ctx.args().size() > 1 ? ctx.joined(1) : "me");
                    for (BlockVector3 direction : directions) {
                        region.shift(direction.multiply(amount));
                    }
                    ctx.actor().message(Msg.result("Region shifted", Msg.value(region.describe()).raw()));
                };


        CommandRegistry.Entry e17 = registry.register("//outset", "//expand-out");
        e17.description = "Outset the selection area in every direction";
        e17.group = "selection";
        e17.requiresSelection = true;
        // -h and -v restrict the growth to one plane.
        e17.booleanFlags.add("h");
        e17.booleanFlags.add("v");
        e17.arguments.add("[amount]");
        e17.handler = ctx -> {
                    int amount = ctx.intArg(0, 1);
                    Region region = ctx.selection();
                    boolean horizontal = ctx.hasFlag("h");
                    boolean vertical = ctx.hasFlag("v");
                    if (horizontal && vertical) {
                        throw CommandRegistry.error("Specify either -h or -v, not both");
                    }
                    if (horizontal) {
                        region.expand(new BlockVector3(amount, 0, amount));
                    } else if (vertical) {
                        region.expand(new BlockVector3(0, amount, 0));
                    } else {
                        region.expand(new BlockVector3(amount, amount, amount));
                    }
                    ctx.actor().message(Msg.success("Region outset: " + region.describe()));
                };


        CommandRegistry.Entry e18 = registry.register("//inset");
        e18.description = "Inset the selection area";
        e18.group = "selection";
        e18.requiresSelection = true;
        e18.booleanFlags.add("h");
        e18.booleanFlags.add("v");
        e18.arguments.add("amount");
        e18.handler = ctx -> {
                    int amount = ctx.intArg(0);
                    Region region = ctx.selection();
                    boolean horizontal = ctx.hasFlag("h");
                    boolean vertical = ctx.hasFlag("v");
                    if (horizontal && vertical) {
                        throw CommandRegistry.error("Specify either -h or -v, not both");
                    }
                    if (horizontal) {
                        region.contract(new BlockVector3(amount, 0, amount));
                    } else if (vertical) {
                        region.contract(new BlockVector3(0, amount, 0));
                    } else {
                        region.contract(new BlockVector3(amount, amount, amount));
                    }
                    ctx.actor().message(Msg.success("Region inset: " + region.describe()));
                };

    }

    /**
     * Parses FAWE's duration syntax: {@code 30s}, {@code 5m}, {@code 2h},
     * {@code 1d} or a bare number of minutes. Returns milliseconds.
     */
    /** Reads a duration such as {@code 30m}; bare numbers count in minutes. */
    static long parseDuration(String input) {
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw CommandRegistry.error("Empty duration");
        }
        long multiplier = 60_000L;
        char unit = value.charAt(value.length() - 1);
        if (!Character.isDigit(unit)) {
            multiplier = switch (unit) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> throw CommandRegistry.error("Unknown time unit '" + unit + "'. Use s, m, h, d or w.");
            };
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(value) * multiplier);
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("'" + input + "' is not a valid duration");
        }
    }

    /**
     * The directions {@code //expand} grows in: a named direction, several of
     * them separated by commas, an explicit {@code x,y,z} vector, or {@code me}
     * for the way the player is looking. WorldEdit takes a list here where
     * {@code //contract} takes one direction.
     */
    private List<BlockVector3> expandDirections(Ctx ctx, String input) {
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            text = "me";
        }
        String[] parts = text.split(",");
        boolean vector = parts.length == 3;
        for (String part : parts) {
            try {
                Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                vector = false;
                break;
            }
        }
        if (vector) {
            return List.of(new BlockVector3(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
        }
        List<BlockVector3> out = new ArrayList<>();
        for (String part : parts) {
            out.add(directionVector(ctx, part.trim(), 1));
        }
        return out;
    }

    /**
     * The vertical reach of WorldEdit's utility commands: its
     * {@code default-vertical-height}, which is 128 blocks up and down when a
     * command does not name a height of its own.
     */
    private static int defaultVerticalHeight() {
        return 128;
    }

    private BlockVector3 directionVector(Ctx ctx, String direction, int amount) {
        String dir = direction.toLowerCase(Locale.ROOT);
        if (dir.equals("me")) {
            Direction facing = ctx.actor().facing();
            return facing.toVector().multiply(amount);
        }
        if (dir.equals("back")) {
            return ctx.actor().facing().opposite().toVector().multiply(amount);
        }
        if (dir.equals("north") || dir.equals("south") || dir.equals("east") || dir.equals("west")
                || dir.equals("up") || dir.equals("down")) {
            return Direction.parse(dir).toVector().multiply(amount);
        }
        if (dir.contains(",")) {
            String[] parts = dir.split(",");
            return new BlockVector3(Integer.parseInt(parts[0].trim()) * amount,
                    Integer.parseInt(parts[1].trim()) * amount,
                    Integer.parseInt(parts[2].trim()) * amount);
        }
        return new BlockVector3(0, amount, 0);
    }

    // ------------------------------------------------------------------- region

    private void registerRegion() {
        CommandRegistry.Entry e19 = registry.register("//set");
        e19.description = "Set all blocks inside a region to a pattern";
        e19.group = "region";
        e19.requiresSelection = true;
        e19.booleanFlags.add("n");
        e19.booleanFlags.add("e");
        e19.booleanFlags.add("m");
        e19.arguments.add("pattern");
        e19.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.joined(0), ctx);
                    Mask mask = null;
                    if (ctx.hasFlag("m")) {
                        mask = ctx.session().getMask();
                    }
                    fill(session, ctx.selection(), pattern, mask);
                    flush(ctx, session, "Set");
                };


        CommandRegistry.Entry e20 = registry.register("//replace", "//re");
        e20.description = "Replace all blocks matching a mask with a pattern inside a region";
        e20.group = "region";
        e20.requiresSelection = true;
        e20.booleanFlags.add("e");
        e20.arguments.add("mask");
        e20.arguments.add("pattern");
        e20.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask mask = Parsers.mask(ctx.arg(0), ctx);
                    Pattern pattern = Parsers.pattern(ctx.joined(1), ctx);
                    fill(session, ctx.selection(), pattern, mask);
                    flush(ctx, session, "Replaced");
                };


        CommandRegistry.Entry e21 = registry.register("//overlay");
        e21.description = "Overlay the top layer of blocks with a pattern";
        e21.group = "region";
        e21.requiresSelection = true;
        e21.arguments.add("pattern");
        e21.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Region region = ctx.selection();
                    // FAWE overlays the top block of every column: walk down from
                    // the selection's ceiling and stop at the first block.
                    for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                        for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                            for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                                if (!BlockState.registry().isAirLike(ctx.world().getBlock(x, y, z))) {
                                    session.setBlock(x, y, z, pattern.apply(x, y, z));
                                    break;
                                }
                            }
                        }
                    }
                    flush(ctx, session, "Overlaid");
                };


        CommandRegistry.Entry e22 = registry.register("//walls");
        e22.description = "Build the walls of the selection";
        e22.group = "region";
        e22.requiresSelection = true;
        e22.arguments.add("pattern");
        e22.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Region region = ctx.selection();
                    BlockVector3 min = region.getMinimumPoint();
                    BlockVector3 max = region.getMaximumPoint();
                    // One plane at a time. The two planes of a direction used to
                    // be written alternately, which left the chunk the previous
                    // write went into on every block; a plane walks sixteen
                    // blocks of one chunk before it moves to the next one.
                    for (int y = min.y(); y <= max.y(); y++) {
                        for (int x = min.x(); x <= max.x(); x++) {
                            session.setBlock(x, y, min.z(), pattern.apply(x, y, min.z()));
                        }
                        for (int x = min.x(); x <= max.x(); x++) {
                            session.setBlock(x, y, max.z(), pattern.apply(x, y, max.z()));
                        }
                        for (int z = min.z(); z <= max.z(); z++) {
                            session.setBlock(min.x(), y, z, pattern.apply(min.x(), y, z));
                        }
                        for (int z = min.z(); z <= max.z(); z++) {
                            session.setBlock(max.x(), y, z, pattern.apply(max.x(), y, z));
                        }
                    }
                    flush(ctx, session, "Walls");
                };


        CommandRegistry.Entry e23 = registry.register("//faces", "//outline");
        e23.description = "Build the faces of the selection";
        e23.group = "region";
        e23.requiresSelection = true;
        e23.arguments.add("pattern");
        e23.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Operations.faces(session, ctx.selection(), pattern);
                    flush(ctx, session, "Faces");
                };


        CommandRegistry.Entry e24 = registry.register("//center");
        e24.description = "Set the center block(s) of the selection";
        e24.group = "region";
        e24.requiresSelection = true;
        e24.arguments.add("pattern");
        e24.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Region region = ctx.selection();
                    Vector3 center = region.getCenter();
                    int minX = (int) Math.floor(center.x());
                    int minY = (int) Math.floor(center.y());
                    int minZ = (int) Math.floor(center.z());
                    int maxX = (int) Math.round(center.x());
                    int maxY = (int) Math.round(center.y());
                    int maxZ = (int) Math.round(center.z());
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                session.setBlock(x, y, z, pattern.apply(x, y, z));
                            }
                        }
                    }
                    flush(ctx, session, "Centered");
                };


        CommandRegistry.Entry e25 = registry.register("//hollow");
        e25.description = "Hollow out the selection";
        e25.group = "region";
        e25.requiresSelection = true;
        e25.valueFlags.add("m");
        e25.arguments.add("[thickness]");
        e25.arguments.add("[pattern]");
        e25.arguments.add("[-m <mask>]");
        e25.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int thickness = ctx.intArg(0, 0);
                    Pattern pattern = ctx.args().size() > 1 ? Parsers.pattern(ctx.joined(1), ctx) : null;
                    Mask hollowMask = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    Region region = ctx.selection();
                    // The flood is stopped by solid blocks unless -m names the
                    // cells it should be stopped by instead.
                    Mask barrier = hollowMask != null ? hollowMask : new Masks.SolidMask(null);
                    Pattern fill = pattern != null ? pattern : new Patterns.Single(air());
                    Operations.hollow(session, region, Math.max(1, thickness), fill, barrier);
                    flush(ctx, session, "Hollowed");
                };


        CommandRegistry.Entry e27 = registry.register("//smooth");
        e27.description = "Smooth the terrain in the selection";
        e27.group = "region";
        e27.requiresSelection = true;
        // WorldEdit takes the mask of blocks the height map is built from as its
        // second argument, not as a switch.
        e27.arguments.add("[iterations]");
        e27.arguments.add("[mask]");
        e27.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int iterations = Math.max(1, ctx.intArg(0, 1));
                    String maskInput = ctx.arg(1, "");
                    Mask smoothMask = maskInput.isEmpty() ? null : Parsers.mask(maskInput, ctx);
                    int changed = HeightMaps.smooth(ctx.world(), session, ctx.selection(), iterations, smoothMask);
                    flush(ctx, session, "Smoothed", changed, "block(s)");
                };


        CommandRegistry.Entry e28 = registry.register("//naturalize");
        e28.description = "Turn the terrain into grass over dirt over stone";
        e28.group = "region";
        e28.requiresSelection = true;
        e28.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockStateRegistry blockRegistry = BlockState.registry();
                    int grass = blockRegistry.defaultState("minecraft:grass_block");
                    int dirt = blockRegistry.defaultState("minecraft:dirt");
                    int stone = blockRegistry.defaultState("minecraft:stone");
                    Region region = ctx.selection();
                    for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                        for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                            int layer = 0;
                            for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                                if (blockRegistry.isAirLike(ctx.world().getBlock(x, y, z))) {
                                    continue;
                                }
                                session.setBlock(x, y, z, switch (layer) {
                                    case 0 -> grass;
                                    case 1, 2 -> dirt;
                                    default -> stone;
                                });
                                layer++;
                            }
                        }
                    }
                    flush(ctx, session, "Naturalized");
                };


        CommandRegistry.Entry e29 = registry.register("//lay");
        e29.description = "Lay a pattern on the ground, keeping natural layers below";
        e29.group = "region";
        e29.requiresSelection = true;
        e29.arguments.add("pattern");
        e29.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int layers = 1;
                    Region region = ctx.selection();
                    for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                        for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                            int placed = 0;
                            for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                                if (!BlockState.registry().isAirLike(ctx.world().getBlock(x, y, z))) {
                                    if (placed < layers) {
                                        session.setBlock(x, y, z, air());
                                        placed++;
                                    } else {
                                        session.setBlock(x, y + 1, z, pattern.apply(x, y + 1, z));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    flush(ctx, session, "Laid");
                };


        CommandRegistry.Entry e30 = registry.register("//fill");
        e30.description = "Fill a hole";
        e30.group = "region";
        e30.booleanFlags.add("r");
        e30.booleanFlags.add("h");
        e30.arguments.add("pattern");
        e30.arguments.add("radius");
        e30.arguments.add("[depth]");
        e30.arguments.add("[direction]");
        e30.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double radius = Math.max(1, ctx.doubleArg(1));
                    int depth = Math.max(1, ctx.intArg(2, 1));
                    BlockVector3 direction = ctx.args().size() < 4
                            ? new BlockVector3(0, -1, 0)
                            : expandDirections(ctx, ctx.joined(3)).get(0);
                    BlockVector3 start = ctx.placement();
                    int changed = com.maxlananas.fawebim.core.function.Operations.fillDirection(ctx.world(), session,
                            start, pattern, radius, depth, direction);
                    flush(ctx, session, "Filled", changed, "block(s)");
                };


        // /fillr is WorldEdit's recursive fill: it fills the connected space at
        // the placement position and follows it down, stopping at the depth it was
        // given, which is what keeps a hole from being followed to the bottom of
        // the world.
        CommandRegistry.Entry e30b = registry.register("//fillr", "/fillr");
        e30b.description = "Fill a hole recursively";
        e30b.group = "region";
        e30b.arguments.add("<pattern>");
        e30b.arguments.add("<radius>");
        e30b.arguments.add("[depth]");
        e30b.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double radius = Math.max(1, ctx.doubleArg(1, 1));
                    int depth = Math.max(1, ctx.intArg(2, Integer.MAX_VALUE));
                    BlockVector3 start = ctx.placement() != null
                            ? ctx.placement() : ctx.selection().getMinimumPoint();
                    // The fill follows the empty space, so only air is replaced:
                    // whatever the hole was dug through stays where it is.
                    int changed = com.maxlananas.fawebim.core.function.Operations.floodFill(ctx.world(), session,
                            start, pattern, (int) Math.ceil(radius), false,
                            new Masks.AirMask(session, false), depth);
                    flush(ctx, session, "Filled", changed, "block(s)");
                };


        CommandRegistry.Entry e31 = registry.register("//drain");
        e31.description = "Drain liquids in the selection";
        e31.group = "region";
        e31.requiresSelection = true;
        // -p removes the water plants, -w also un-waterlogs the blocks.
        e31.booleanFlags.add("p");
        e31.booleanFlags.add("w");
        e31.arguments.add("[radius]");
        e31.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    BlockVector3 start = ctx.placement() != null
                            ? ctx.placement() : ctx.selection().getMinimumPoint();
                    Mask drainMask = ctx.hasFlag("p")
                            ? Parsers.mask("minecraft:water,minecraft:lava,minecraft:kelp,minecraft:seagrass,"
                            + "minecraft:tall_seagrass,minecraft:lily_pad,minecraft:bubble_column", ctx)
                            : new Masks.LiquidMask(session);
                    int changed = com.maxlananas.fawebim.core.function.Operations.drain(ctx.world(), session, start,
                            drainMask, ctx.intArg(0, 256));
                    if (ctx.hasFlag("w")) {
                        changed += com.maxlananas.fawebim.core.function.Operations.drainWaterlogged(session,
                                ctx.selection());
                    }
                    flush(ctx, session, "Drained", changed, "block(s)");
                };


        CommandRegistry.Entry e32 = registry.register("//regen");
        e32.description = "Regenerate the selection from the world seed";
        e32.group = "region";
        e32.requiresSelection = true;
        e32.arguments.add("[seed]");
        e32.arguments.add("[biome]");
        e32.booleanFlags.add("b");
        e32.booleanFlags.add("r");
        e32.handler = ctx -> {
                    Region region = ctx.selection();
                    com.maxlananas.fawebim.core.platform.Config settings =
                            com.maxlananas.fawebim.core.platform.Config.get();
                    if (settings.maxRegenVolume > 0 && region.getVolume() > settings.maxRegenVolume) {
                        throw CommandRegistry.error("Selection is too large to regenerate ("
                                + region.getVolume() + " blocks, limit " + settings.maxRegenVolume
                                + "; raise regen.max-volume in config/fawebim.yml)");
                    }
                    Long seed = null;
                    if (ctx.hasFlag("r")) {
                        seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                    } else if (!ctx.args().isEmpty()) {
                        seed = Long.parseLong(ctx.arg(0));
                    }
                    if (seed != null && !ctx.world().supportsCustomRegenSeed()) {
                        ctx.actor().message(Msg.warn("This platform regenerates with the world seed;"
                                + " the seed you gave is ignored."));
                    }
                    String biome = ctx.args().size() > 1 ? ctx.arg(1) : null;
                    int biomeId = -1;
                    if (biome != null) {
                        biomeId = Parsers.biome(biome);
                    }
                    // FAWE clears the masks for the duration of the regeneration:
                    // a region the mask excludes must not survive a //regen.
                    Mask previousMask = ctx.session().getMask();
                    ctx.session().setMask(null);
                    // A seed is only set when the player asked for one: the
                    // options take a primitive, and asking them to use nothing
                    // is not the same as asking them to use the world's seed.
                    com.maxlananas.fawebim.core.world.RegenOptions options =
                            new com.maxlananas.fawebim.core.world.RegenOptions()
                                    .setRegenBiomes(ctx.hasFlag("b") || biomeId >= 0
                                            || settings.regenerateBiomes);
                    if (seed != null) {
                        options.setSeed(seed);
                    }
                    int regenerated = 0;
                    com.maxlananas.fawebim.core.util.Timer timer = new com.maxlananas.fawebim.core.util.Timer();
                    try {
                        for (BlockVector2 chunk : region.getChunks()) {
                            ctx.world().loadChunk(chunk.x(), chunk.z());
                            if (ctx.world().regenerateChunk(chunk.x(), chunk.z(), options)) {
                                regenerated++;
                            }
                        }
                    } finally {
                        ctx.session().setMask(previousMask);
                    }
                    if (biomeId >= 0) {
                        EditSession editSession = ctx.editSession();
                        int targetBiome = biomeId;
                        region.forEachPosition((x, y, z) -> {
                            editSession.setBiome(x, y, z, targetBiome);
                            return true;
                        });
                        editSession.flushQueue();
                    }
                    ctx.actor().message(Msg.result("Regenerated", Msg.count(regenerated)
                            + "\u00a77 of " + Msg.count(region.getChunks().size())
                            + "\u00a77 chunk(s) in \u00a7b" + timer.phrase()));
                };


        CommandRegistry.Entry e33 = registry.register("//removeabove");
        e33.description = "Remove blocks above a height";
        e33.group = "region";
        e33.arguments.add("[size]");
        e33.arguments.add("[height]");
        e33.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockVector3 origin = ctx.placement() != null ? ctx.placement() : BlockVector3.ZERO;
                    int size = ctx.intArg(0, 0);
                    int height = ctx.args().size() > 1 ? ctx.intArg(1) : origin.y() + 1;
                    for (int x = origin.x() - size; x <= origin.x() + size; x++) {
                        for (int z = origin.z() - size; z <= origin.z() + size; z++) {
                            for (int y = height; y <= ctx.world().maxY(); y++) {
                                session.setBlock(x, y, z, air());
                            }
                        }
                    }
                    flush(ctx, session, "Removed above");
                };


        CommandRegistry.Entry e34 = registry.register("//removebelow");
        e34.description = "Remove blocks below a height";
        e34.group = "region";
        e34.arguments.add("[size]");
        e34.arguments.add("[height]");
        e34.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockVector3 origin = ctx.placement() != null ? ctx.placement() : BlockVector3.ZERO;
                    int size = ctx.intArg(0, 0);
                    int height = ctx.args().size() > 1 ? ctx.intArg(1) : origin.y() - 1;
                    for (int x = origin.x() - size; x <= origin.x() + size; x++) {
                        for (int z = origin.z() - size; z <= origin.z() + size; z++) {
                            for (int y = ctx.world().minY(); y <= height; y++) {
                                session.setBlock(x, y, z, air());
                            }
                        }
                    }
                    flush(ctx, session, "Removed below");
                };


        CommandRegistry.Entry e35 = registry.register("//removenear");
        e35.description = "Remove blocks near you";
        e35.group = "region";
        e35.arguments.add("mask");
        e35.arguments.add("[size]");
        e35.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask mask = Parsers.mask(ctx.arg(0), ctx);
                    int size = ctx.intArg(1, 10);
                    BlockVector3 origin = ctx.placement();
                    int changed = 0;
                    for (int x = origin.x() - size; x <= origin.x() + size; x++) {
                        for (int y = origin.y() - size; y <= origin.y() + size; y++) {
                            for (int z = origin.z() - size; z <= origin.z() + size; z++) {
                                if (mask.test(x, y, z) && session.setBlock(x, y, z, air())) {
                                    changed++;
                                }
                            }
                        }
                    }
                    flush(ctx, session, "Removed", changed, "block(s)");
                };


        CommandRegistry.Entry e36 = registry.register("//replacenear");
        e36.description = "Replace blocks near you";
        e36.group = "region";
        e36.arguments.add("size");
        e36.arguments.add("mask");
        e36.arguments.add("pattern");
        e36.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int size = ctx.intArg(0);
                    Mask mask = Parsers.mask(ctx.arg(1), ctx);
                    Pattern pattern = Parsers.pattern(ctx.joined(2), ctx);
                    BlockVector3 origin = ctx.placement();
                    int changed = 0;
                    for (int x = origin.x() - size; x <= origin.x() + size; x++) {
                        for (int y = origin.y() - size; y <= origin.y() + size; y++) {
                            for (int z = origin.z() - size; z <= origin.z() + size; z++) {
                                if (mask.test(x, y, z)
                                        && session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                                    changed++;
                                }
                            }
                        }
                    }
                    flush(ctx, session, "Replaced", changed, "block(s)");
                };


        // WorldEdit runs these three around the place the source stands on: the
        // radius and height describe a cylinder, the placement position is its
        // centre, and the top block of each column decides what happens to it.
        CommandRegistry.Entry e37 = registry.register("//snow");
        e37.description = "Simulate snow on the terrain";
        e37.group = "region";
        // -s stacks a snow layer on the snow that is already there.
        e37.booleanFlags.add("s");
        e37.arguments.add("[size]");
        e37.arguments.add("[height]");
        e37.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    double size = Math.max(1, ctx.doubleArg(0, 10));
                    int height = Math.max(1, ctx.intArg(1, defaultVerticalHeight()));
                    int changed = com.maxlananas.fawebim.core.function.Operations.simulateSnow(
                            ctx.world(), session, ctx.placement(), size, height, ctx.hasFlag("s"));
                    flush(ctx, session, "Snowed", changed, "block(s)");
                };


        CommandRegistry.Entry e38 = registry.register("//thaw");
        e38.description = "Thaw snow and ice around you";
        e38.group = "region";
        e38.arguments.add("[size]");
        e38.arguments.add("[height]");
        e38.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    double size = Math.max(1, ctx.doubleArg(0, 10));
                    int height = Math.max(1, ctx.intArg(1, defaultVerticalHeight()));
                    int changed = com.maxlananas.fawebim.core.function.Operations.thaw(
                            ctx.world(), session, ctx.placement(), size, height);
                    flush(ctx, session, "Thawed", changed, "block(s)");
                };


        CommandRegistry.Entry e39 = registry.register("//green");
        e39.description = "Convert dirt to grass blocks around you";
        e39.group = "region";
        // -f also turns coarse dirt into grass, which WorldEdit keeps out by default.
        e39.booleanFlags.add("f");
        e39.arguments.add("[size]");
        e39.arguments.add("[height]");
        e39.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    double size = Math.max(1, ctx.doubleArg(0, 10));
                    int height = Math.max(1, ctx.intArg(1, defaultVerticalHeight()));
                    int changed = com.maxlananas.fawebim.core.function.Operations.green(ctx.world(), session,
                            ctx.placement(), size, height, !ctx.hasFlag("f"));
                    flush(ctx, session, "Greened", changed, "block(s)");
                };


        // WorldEdit removes the fire in a cube around the source and leaves the
        // lava alone, in a radius of forty blocks unless one is named.
        CommandRegistry.Entry e40 = registry.register("//extinguish", "//ex");
        e40.description = "Extinguish nearby fire";
        e40.group = "region";
        e40.arguments.add("[radius]");
        e40.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int radius = Math.max(1, ctx.intArg(0, 40));
                    Mask fire = Parsers.mask("minecraft:fire", ctx);
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeNear(
                            ctx.world(), session, ctx.placement(), radius, fire);
                    flush(ctx, session, "Extinguished", changed, "block(s)");
                };


        CommandRegistry.Entry e41 = registry.register("//fixwater");
        e41.description = "Fix water placement in the selection";
        e41.group = "region";
        e41.requiresSelection = true;
        e41.arguments.add("[radius]");
        e41.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = com.maxlananas.fawebim.core.function.Operations.fixLiquid(ctx.world(), session,
                            ctx.selection(), "water", ctx.intArg(0, 5));
                    flush(ctx, session, "Fixed water", changed, "water block(s)");
                };


        CommandRegistry.Entry e42 = registry.register("//fixlava");
        e42.description = "Fix lava placement in the selection";
        e42.group = "region";
        e42.requiresSelection = true;
        e42.arguments.add("[radius]");
        e42.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = com.maxlananas.fawebim.core.function.Operations.fixLiquid(ctx.world(), session,
                            ctx.selection(), "lava", ctx.intArg(0, 5));
                    flush(ctx, session, "Fixed lava", changed, "lava block(s)");
                };


        CommandRegistry.Entry e43 = registry.register("//move");
        e43.description = "Move the selection's contents in a direction";
        e43.group = "region";
        e43.requiresSelection = true;
        e43.booleanFlags.add("s");
        e43.booleanFlags.add("a");
        e43.booleanFlags.add("e");
        e43.booleanFlags.add("b");
        e43.valueFlags.add("m");
        e43.arguments.add("amount");
        e43.arguments.add("direction");
        e43.arguments.add("[pattern]");
        e43.arguments.add("[-m <mask>]");
        e43.handler = ctx -> {
                    Region region = ctx.selection();
                    int amount = ctx.intArg(0);
                    String direction = ctx.arg(1);
                    BlockVector3 offset = directionVector(ctx, direction, amount);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask include = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    BlockArrayClipboard clipboard = com.maxlananas.fawebim.core.clipboard.Clipboards.copy(ctx.world(),
                            region, session, ctx.hasFlag("e"), ctx.hasFlag("b"), include, false);
                    Pattern pattern = ctx.args().size() > 2 ? Parsers.pattern(ctx.joined(2), ctx) : null;
                    // Clear the source region.
                    int empty = air();
                    region.forEachPosition((x, y, z) -> {
                        session.setBlock(x, y, z, empty, false);
                        return false;
                    });
                    // Paste at the offset.
                    BlockVector3 origin = clipboard.getOrigin();
                    BlockVector3 target = region.getMinimumPoint();
                    boolean keepSource = ctx.hasFlag("a");
                    int targetX = target.x() + offset.x();
                    int targetY = target.y() + offset.y();
                    int targetZ = target.z() + offset.z();
                    clipboard.forEachPosition((x, y, z, state) -> {
                        int bx = x - origin.x() + targetX;
                        int by = y - origin.y() + targetY;
                        int bz = z - origin.z() + targetZ;
                        if (BlockState.registry().isAirLike(state)) {
                            if (keepSource) {
                                // -a keeps the blocks the copy would erase.
                                return false;
                            }
                            if (pattern != null) {
                                session.setBlock(bx, by, bz, pattern.apply(bx, by, bz));
                                return false;
                            }
                        }
                        session.setBlock(bx, by, bz, state);
                        return false;
                    });
                    // -s moves the selection along with the blocks.
                    if (ctx.hasFlag("s")) {
                        region.shift(offset);
                    }
                    flush(ctx, session, "Moved", session.getBlocksChanged(), "block(s)");
                    ctx.actor().message(Msg.result("Selection", "moved by " + Msg.count(amount)
                            + "\u00a77 block(s) towards \u00a7b" + direction.toLowerCase(Locale.ROOT)));
                };


        CommandRegistry.Entry e44 = registry.register("//stack");
        e44.description = "Stack the selection's contents";
        e44.group = "region";
        e44.requiresSelection = true;
        e44.booleanFlags.add("s");
        e44.booleanFlags.add("a");
        e44.booleanFlags.add("e");
        e44.booleanFlags.add("b");
        // -r counts the copies in blocks instead of selections.
        e44.booleanFlags.add("r");
        e44.valueFlags.add("m");
        e44.arguments.add("[count]");
        e44.arguments.add("[direction]");
        e44.arguments.add("[-m <mask>]");
        e44.handler = ctx -> {
                    Region region = ctx.selection();
                    int count = ctx.intArg(0, 1);
                    String dir = ctx.arg(1, "me");
                    Direction direction = dir.equalsIgnoreCase("me") ? ctx.actor().facing() : Direction.parse(dir);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask include = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    BlockArrayClipboard original = com.maxlananas.fawebim.core.clipboard.Clipboards.copy(ctx.world(), region,
                            session, ctx.hasFlag("e"), ctx.hasFlag("b"), include, false);
                    BlockVector3 origin = original.getOrigin();
                    BlockVector3 min = region.getMinimumPoint();
                    int step = ctx.hasFlag("r") ? 1 : region.getHeight();
                    int dx = direction.x() * step;
                    int dy = direction.y() * step;
                    int dz = direction.z() * step;
                    for (int i = 1; i <= count; i++) {
                        for (BlockVector3 position : original.positions()) {
                            int state = original.getBlock(position);
                            if (ctx.hasFlag("a") && BlockState.registry().isAirLike(state)) {
                                continue;
                            }
                            int x = position.x() - origin.x() + min.x() + dx * i;
                            int y = position.y() - origin.y() + min.y() + dy * i;
                            int z = position.z() - origin.z() + min.z() + dz * i;
                            session.setBlock(x, y, z, state);
                        }
                    }
                    // -s moves the selection onto the last copy.
                    if (ctx.hasFlag("s")) {
                        region.shift(new BlockVector3(dx * count, dy * count, dz * count));
                    }
                    flush(ctx, session, "Stacked");
                };

    }

    /** The origin a rotation transform turns around. */
    private static BlockVector3 clipboardOriginOf(com.maxlananas.fawebim.core.session.ClipboardHolder holder) {
        return holder.getClipboard().getOrigin();
    }

    // --------------------------------------------------------------- generation

    private void registerGeneration() {
        registerShapes();
        CommandRegistry.Entry e45 = registry.register("//line");
        e45.description = "Draw a line between selection corners";
        e45.group = "generation";
        e45.requiresSelection = true;
        e45.booleanFlags.add("h");
        e45.booleanFlags.add("s");
        e45.arguments.add("pattern");
        e45.arguments.add("[thickness]");
        e45.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double thickness = ctx.doubleArg(1, 0);
                    BlockVector3 min = ctx.selection().getMinimumPoint();
                    BlockVector3 max = ctx.selection().getMaximumPoint();
                    int changed = com.maxlananas.fawebim.core.function.Operations.line(session, min, max, pattern, thickness,
                            ctx.hasFlag("h"));
                    flush(ctx, session, "Drew", changed, "block(s)");
                };


        CommandRegistry.Entry e46 = registry.register("//curve");
        e46.description = "Draw a spline through the convex selection's vertices";
        e46.group = "generation";
        e46.requiresSelection = true;
        // -h draws the shell of the curve instead of the solid path.
        e46.booleanFlags.add("h");
        e46.arguments.add("pattern");
        e46.arguments.add("[thickness]");
        e46.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double thickness = ctx.doubleArg(1, 0);
                    Region region = ctx.selection();
                    List<BlockVector3> points = region instanceof com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion convex
                            ? convex.getVertices() : List.of(region.getMinimumPoint(), region.getMaximumPoint());
                    int changed = com.maxlananas.fawebim.core.function.Operations.spline(session, points, pattern,
                            ctx.hasFlag("h") ? Math.max(0, thickness - 1) : thickness);
                    if (ctx.hasFlag("h")) {
                        // A shell keeps the outer layer of the tube only.
                        changed = com.maxlananas.fawebim.core.function.Operations.splineShell(session, points, pattern,
                                thickness);
                    }
                    flush(ctx, session, "Drew", changed, "block(s)");
                };


        CommandRegistry.Entry e48 = registry.register("//deform");
        e48.description = "Deform blocks in the selection using an expression";
        e48.group = "generation";
        e48.requiresSelection = true;
        e48.booleanFlags.add("r");
        e48.booleanFlags.add("o");
        // -c evaluates the expression around the centre of the selection.
        e48.booleanFlags.add("c");
        e48.arguments.add("expression");
        e48.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    String expression = ctx.joined(0);
                    Region deformRegion = ctx.selection();
                    int originX = 0;
                    int originZ = 0;
                    if (ctx.hasFlag("c")) {
                        originX = (deformRegion.getMinimumPoint().x() + deformRegion.getMaximumPoint().x()) / 2;
                        originZ = (deformRegion.getMinimumPoint().z() + deformRegion.getMaximumPoint().z()) / 2;
                    } else if (ctx.hasFlag("o") && !ctx.hasFlag("r")) {
                        BlockVector3 placement = ctx.placement();
                        originX = placement.x();
                        originZ = placement.z();
                    }
                    int changed = com.maxlananas.fawebim.core.function.Operations.deform(ctx.world(), session,
                            deformRegion, expression, originX, 0, originZ);
                    flush(ctx, session, "Deformed", changed, "block(s)");
                };


        CommandRegistry.Entry e49 = registry.register("//flora");
        e49.description = "Make flora within the region";
        e49.group = "generation";
        e49.requiresSelection = true;
        e49.arguments.add("[density]");
        e49.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    double density = ctx.doubleArg(0, 5) / 100.0;
                    int changed = com.maxlananas.fawebim.core.function.Operations.flora(ctx.world(), session,
                            ctx.selection(), density);
                    flush(ctx, session, "Planted", changed, "plant(s)");
                };


        // //forest is WorldEdit's "Make a forest": trees of one type, scattered at
        // a density, while //forestgen generates a forest of a given size.
        CommandRegistry.Entry e49b = registry.register("//forest");
        e49b.description = "Make a forest within the region";
        e49b.group = "generation";
        e49b.requiresSelection = true;
        e49b.arguments.add("<tree-type>");
        e49b.arguments.add("[density]");
        e49b.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    // WorldEdit defaults the type to a regular tree and takes any
                    // name it declares for one, so the argument is optional.
                    String type = com.maxlananas.fawebim.core.world.TreeTypes
                            .canonical(ctx.arg(0, "tree"));
                    if (type == null) {
                        throw CommandRegistry.error("Unknown tree type '" + ctx.arg(0)
                                + "'. Try: " + com.maxlananas.fawebim.core.world.TreeTypes.names());
                    }
                    double density = ctx.doubleArg(1, 5) / 100.0;
                    int changed = com.maxlananas.fawebim.core.function.Operations.forest(ctx.world(), session,
                            ctx.selection(), type, density);
                    flush(ctx, session, "Planted", changed, "tree(s)");
                };


        CommandRegistry.Entry e50 = registry.register("//pumpkins");
        e50.description = "Generate a pumpkin patch";
        e50.group = "generation";
        e50.requiresSelection = true;
        e50.arguments.add("[density]");
        e50.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int pumpkin = BlockState.registry().defaultState("minecraft:pumpkin");
                    double density = ctx.doubleArg(0, 5) / 100.0;
                    java.util.Random random = new java.util.Random();
                    int changed = 0;
                    Region region = ctx.selection();
                    for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                        for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                            for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                                if (!BlockState.registry().isAirLike(ctx.world().getBlock(x, y, z))) {
                                    if (random.nextDouble() < density && session.setBlock(x, y + 1, z, pumpkin)) {
                                        changed++;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    flush(ctx, session, "Generated", changed, "pumpkin(s)");
                };


        CommandRegistry.Entry e51 = registry.register("//tree");
        e51.description = "Create a tree at your position";
        e51.group = "generation";
        e51.requiresPlayer = true;
        e51.booleanFlags.add("t");
        e51.arguments.add("[type]");
        e51.handler = ctx -> {
                    String type = com.maxlananas.fawebim.core.world.TreeTypes.canonical(ctx.arg(0, "tree"));
                    if (type == null) {
                        throw CommandRegistry.error("Unknown tree type '" + ctx.arg(0, "")
                                + "'. Try: " + com.maxlananas.fawebim.core.world.TreeTypes.names());
                    }
                    boolean ok = ctx.world().generateTree(ctx.placement(), type, new java.util.Random());
                    ctx.actor().message(ok ? Msg.success("Tree planted at ").append(Msg.value(ctx.placement()))
                            : Msg.error("The world cannot plant a " + type + " tree here"));
                };


        CommandRegistry.Entry e52 = registry.register("//deltree");
        e52.description = "Remove the tree you are looking at";
        e52.group = "generation";
        e52.requiresPlayer = true;
        e52.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockVector3 target = ctx.targetBlock(100);
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeTree(ctx.world(), session, target);
                    flush(ctx, session, "Removed", changed, "block(s)");
                };


        CommandRegistry.Entry e53 = registry.register("//ore", "/ore");
        e53.description = "Generates ores";
        e53.group = "generation";
        e53.requiresSelection = true;
        e53.arguments.add("mask");
        e53.arguments.add("material");
        e53.arguments.add("size");
        e53.arguments.add("[frequency]");
        e53.arguments.add("[rarity]");
        e53.arguments.add("[minY]");
        e53.arguments.add("[maxY]");
        e53.handler = ctx -> {
                    Mask mask = ctx.mask(0);
                    Pattern material = ctx.pattern(1);
                    int size = ctx.intArg(2);
                    int frequency = ctx.intArg(3, 10);
                    int rarity = ctx.intArg(4, 100);
                    int worldMinY = ctx.world().minY();
                    int worldMaxY = ctx.world().maxY();
                    int minY = ctx.intArg(5, 0);
                    int maxY = ctx.intArg(6, 63);
                    if (minY < worldMinY) {
                        throw CommandRegistry.error("Argument miny may not be less than " + worldMinY);
                    }
                    if (maxY > worldMaxY) {
                        throw CommandRegistry.error("Argument maxy may not be greater than " + worldMaxY);
                    }
                    if (minY >= maxY) {
                        throw CommandRegistry.error("Argument miny may not be greater than argument maxy");
                    }
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int changed = com.maxlananas.fawebim.core.function.Operations.ore(ctx.world(), session,
                            ctx.selection(), mask, material, size, frequency, rarity, minY, maxY, false,
                            com.maxlananas.fawebim.core.function.Operations.OreDeepslate.NONE,
                            java.util.concurrent.ThreadLocalRandom.current());
                    flush(ctx, session, "Generated", changed, "block(s)");
                };


        CommandRegistry.Entry e53b = registry.register("//ores", "/ores");
        e53b.description = "Generates ores";
        e53b.group = "generation";
        e53b.requiresSelection = true;
        // -b makes every ore below y=0 its deepslate form, -d only the ores that
        // land in deepslate, which are the two switches FAWE declares.
        e53b.booleanFlags.add("b");
        e53b.booleanFlags.add("d");
        e53b.arguments.add("mask");
        e53b.handler = ctx -> {
                    Mask mask = ctx.mask(0);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    com.maxlananas.fawebim.core.function.Operations.OreDeepslate deepslate =
                            com.maxlananas.fawebim.core.function.Operations.OreDeepslate.of(
                                    ctx.hasFlag("b"), ctx.hasFlag("d"));
                    int changed = com.maxlananas.fawebim.core.function.Operations.ores(ctx.world(), session,
                            ctx.selection(), mask, deepslate,
                            java.util.concurrent.ThreadLocalRandom.current());
                    flush(ctx, session, "Generated", changed, "block(s)");
                };


        CommandRegistry.Entry e55 = registry.register("//fall");
        e55.description = "Have the blocks in the selection fall";
        e55.group = "generation";
        e55.requiresSelection = true;
        e55.arguments.add("[replace]");
        // -m keeps the blocks inside the vertical bounds of the selection.
        e55.booleanFlags.add("m");
        e55.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int[] replace = ctx.args().isEmpty() ? null
                            : new int[]{ctx.pattern(0).apply(ctx.placement())};
                    int changed = com.maxlananas.fawebim.core.function.Operations.fall(ctx.world(), session,
                            ctx.selection(), ctx.hasFlag("m"), replace);
                    flush(ctx, session, "Generated", changed, "block(s)");
                };

    }

    private void registerShapes() {
        CommandRegistry.Entry sphere = registry.register("//sphere");
        sphere.description = "Create a sphere or an ellipsoid at your position";
        sphere.group = "generation";
        sphere.booleanFlags.add("r");
        sphere.booleanFlags.add("h");
        sphere.arguments.add("pattern");
        sphere.arguments.add("radii");
        sphere.handler = ctx -> {
            EditSession session = ctx.editSession();
            Masks.ExtentHolder.set(session);
            double[] radii = sphereRadii(ctx.arg(1));
            int changed = sphere(session, ctx, radii, Parsers.pattern(ctx.arg(0), ctx), ctx.hasFlag("h"));
            flush(ctx, session, "Created", changed, "block(s)");
        };

        CommandRegistry.Entry hollowSphere = registry.register("//hsphere");
        hollowSphere.description = "Create a hollow sphere or ellipsoid at your position";
        hollowSphere.group = "generation";
        hollowSphere.booleanFlags.add("r");
        hollowSphere.arguments.add("pattern");
        hollowSphere.arguments.add("radii");
        hollowSphere.handler = ctx -> {
            EditSession session = ctx.editSession();
            Masks.ExtentHolder.set(session);
            double[] radii = sphereRadii(ctx.arg(1));
            int changed = sphere(session, ctx, radii, Parsers.pattern(ctx.arg(0), ctx), true);
            flush(ctx, session, "Created", changed, "block(s)");
        };

        CommandRegistry.Entry cylinder = registry.register("//cyl");
        cylinder.description = "Create a cylinder at your position";
        cylinder.group = "generation";
        cylinder.booleanFlags.add("h");
        cylinder.arguments.add("pattern");
        cylinder.arguments.add("radii");
        cylinder.arguments.add("[height]");
        cylinder.handler = ctx -> {
            EditSession session = ctx.editSession();
            Masks.ExtentHolder.set(session);
            double[] radii = cylinderRadii(ctx.arg(1));
            int changed = com.maxlananas.fawebim.core.function.Operations.cylinder(session, ctx.placement(),
                    radii, ctx.intArg(2, 1), Parsers.pattern(ctx.arg(0), ctx), ctx.hasFlag("h"), 0);
            flush(ctx, session, "Created", changed, "block(s)");
        };

        CommandRegistry.Entry hollowCylinder = registry.register("//hcyl");
        hollowCylinder.description = "Create a hollow cylinder at your position";
        hollowCylinder.group = "generation";
        hollowCylinder.arguments.add("pattern");
        hollowCylinder.arguments.add("radii");
        hollowCylinder.arguments.add("[height]");
        hollowCylinder.arguments.add("[thickness]");
        hollowCylinder.handler = ctx -> {
            EditSession session = ctx.editSession();
            Masks.ExtentHolder.set(session);
            double[] radii = cylinderRadii(ctx.arg(1));
            double thickness = ctx.doubleArg(3, 0);
            if (thickness > radii[0] || thickness > radii[1]) {
                throw CommandRegistry.error("Thickness is larger than the radius");
            }
            int changed = com.maxlananas.fawebim.core.function.Operations.cylinder(session, ctx.placement(),
                    radii, ctx.intArg(2, 1), Parsers.pattern(ctx.arg(0), ctx), true, thickness);
            flush(ctx, session, "Created", changed, "block(s)");
        };

        registerPyramidAndCone();
    }

    /** {@code -r} lifts the centre by the vertical radius, as WorldEdit does. */
    private int sphere(EditSession session, Ctx ctx, double[] radii, Pattern pattern, boolean hollow) {
        BlockVector3 origin = ctx.hasFlag("r") ? ctx.placement().add(0, (int) radii[1], 0) : ctx.placement();
        return com.maxlananas.fawebim.core.function.Operations.sphere(session, origin, radii, pattern, hollow);
    }

    /** The one or three radii {@code //sphere} takes. */
    private static double[] sphereRadii(String input) {
        List<Double> radii = Parsers.radii(input);
        if (radii.size() == 1) {
            double radius = Math.max(0, radii.get(0));
            return new double[]{radius, radius, radius};
        }
        if (radii.size() == 3) {
            return new double[]{Math.max(0, radii.get(0)), Math.max(0, radii.get(1)),
                    Math.max(0, radii.get(2))};
        }
        throw CommandRegistry.error("You must either specify 1 or 3 radius values.");
    }

    /** The one or two radii {@code //cyl} takes: north/south then east/west. */
    private static double[] cylinderRadii(String input) {
        List<Double> radii = Parsers.radii(input);
        if (radii.size() == 1) {
            double radius = Math.max(1, radii.get(0));
            return new double[]{radius, radius};
        }
        if (radii.size() == 2) {
            return new double[]{Math.max(1, radii.get(0)), Math.max(1, radii.get(1))};
        }
        throw CommandRegistry.error("You must either specify 1 or 2 radius values.");
    }

    private void registerPyramidAndCone() {
        CommandRegistry.Entry e57 = registry.register("//pyramid");
        e57.description = "Create a pyramid at your position";
        e57.group = "generation";
        e57.booleanFlags.add("h");
        e57.arguments.add("pattern");
        e57.arguments.add("size");
        e57.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int size = ctx.intArg(1);
                    boolean hollowShape = ctx.hasFlag("h");
                    int changed = com.maxlananas.fawebim.core.function.Operations.pyramid(session, ctx.placement(),
                            size, pattern, hollowShape);
                    flush(ctx, session, "Created", changed, "block(s)");
                };


        CommandRegistry.Entry e57b = registry.register("//hpyramid", "/hpyramid");
        e57b.description = "Generate a hollow pyramid";
        e57b.group = "generation";
        e57b.arguments.add("pattern");
        e57b.arguments.add("size");
        e57b.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int size = ctx.intArg(1);
                    int changed = com.maxlananas.fawebim.core.function.Operations.pyramid(session, ctx.placement(),
                            size, pattern, true);
                    flush(ctx, session, "Created", changed, "block(s)");
                };


        CommandRegistry.Entry e58 = registry.register("//cone");
        e58.description = "Generate a cone";
        e58.group = "generation";
        e58.booleanFlags.add("h");
        e58.arguments.add("pattern");
        e58.arguments.add("radii");
        e58.arguments.add("[height]");
        e58.arguments.add("[thickness]");
        e58.handler = ctx -> {
                    Pattern pattern = ctx.pattern(0);
                    List<Double> radii = com.maxlananas.fawebim.core.command.Parsers.radii(ctx.arg(1));
                    double radiusX;
                    double radiusZ;
                    if (radii.size() == 1) {
                        radiusX = radiusZ = Math.max(1, radii.get(0));
                    } else if (radii.size() == 2) {
                        radiusX = Math.max(1, radii.get(0));
                        radiusZ = Math.max(1, radii.get(1));
                    } else {
                        throw CommandRegistry.error("Invalid radius: give one radius or two, N/S then E/W");
                    }
                    int height = ctx.intArg(2, 1);
                    double thickness = ctx.doubleArg(3, 1);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int changed = com.maxlananas.fawebim.core.function.Operations.cone(session, ctx.placement(),
                            pattern, radiusX, radiusZ, height, !ctx.hasFlag("h"), thickness);
                    flush(ctx, session, "Created", changed, "block(s)");
                };

    }

    // ---------------------------------------------------------------- clipboard

    private void registerClipboard() {
        CommandRegistry.Entry e59 = registry.register("//copy", "//cp");
        e59.description = "Copy the selection to your clipboard";
        e59.group = "clipboard";
        e59.requiresSelection = true;
        e59.booleanFlags.add("e");
        e59.booleanFlags.add("b");
        e59.booleanFlags.add("c");
        e59.valueFlags.add("m");
        e59.arguments.add("[-m <mask>]");
        e59.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Mask include = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    BlockArrayClipboard clipboard = com.maxlananas.fawebim.core.clipboard.Clipboards.copy(ctx.world(),
                            ctx.selection(), session, ctx.hasFlag("e"), ctx.hasFlag("b"), include, ctx.hasFlag("c"));
                    ctx.session().setClipboard(clipboard);
                    StringBuilder detail = new StringBuilder(Msg.count(clipboard.volume()))
                            .append("\u00a77 block(s) to your clipboard");
                    if (!clipboard.entities().isEmpty()) {
                        detail.append(", ").append(Msg.count(clipboard.entities().size()))
                                .append("\u00a77 entities");
                    }
                    if (clipboard.hasBiomes()) {
                        detail.append(", \u00a77biomes");
                    }
                    detail.append(" \u00a78(").append(clipboard.getWidth()).append('x')
                            .append(clipboard.getHeight()).append('x').append(clipboard.getLength())
                            .append(')');
                    ctx.actor().message(Msg.result("Copied", detail.toString()));
                };


        CommandRegistry.Entry e60 = registry.register("//cut");
        e60.description = "Cut the selection to your clipboard";
        e60.group = "clipboard";
        e60.requiresSelection = true;
        e60.booleanFlags.add("e");
        e60.booleanFlags.add("b");
        e60.valueFlags.add("m");
        // Upstream takes the pattern the selection is left as; its default is air.
        e60.arguments.add("[leavePattern]");
        e60.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask exclude = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    Region region = ctx.selection();
                    Pattern leave = ctx.args().isEmpty() ? Parsers.pattern("air", ctx)
                            : Parsers.pattern(ctx.arg(0), ctx);
                    // The copy and the replacing share one traversal, so the
                    // answer can say how long the whole cut took.
                    com.maxlananas.fawebim.core.util.Timer timer = new com.maxlananas.fawebim.core.util.Timer();
                    BlockArrayClipboard clipboard = com.maxlananas.fawebim.core.clipboard.Clipboards.cut(ctx.world(),
                            region, session, ctx.hasFlag("e"), ctx.hasFlag("b"), exclude, leave);
                    ctx.session().setClipboard(clipboard);
                    // The queue is applied before the answer is written, so the
                    // time the line reports is the time the cut really took.
                    session.flushQueue();
                    StringBuilder detail = new StringBuilder(Msg.count(clipboard.volume()))
                            .append("\u00a77 block(s) to your clipboard");
                    if (!clipboard.entities().isEmpty()) {
                        detail.append(", ").append(Msg.count(clipboard.entities().size()))
                                .append("\u00a77 entities");
                    }
                    if (clipboard.hasBiomes()) {
                        detail.append(", \u00a77biomes");
                    }
                    detail.append(" in \u00a7b").append(timer.phrase());
                    detail.append(" \u00a78(").append(clipboard.getWidth()).append('x')
                            .append(clipboard.getHeight()).append('x').append(clipboard.getLength())
                            .append(')');
                    ctx.actor().message(Msg.result("Cut", detail.toString()));
                };


        CommandRegistry.Entry e61 = registry.register("//paste", "//p");
        e61.description = "Paste your clipboard";
        e61.group = "clipboard";
        e61.booleanFlags.add("a");
        e61.booleanFlags.add("o");
        e61.booleanFlags.add("s");
        e61.booleanFlags.add("n");
        e61.booleanFlags.add("e");
        e61.booleanFlags.add("b");
        e61.booleanFlags.add("x");
        e61.booleanFlags.add("v");
        e61.valueFlags.add("m");
        e61.arguments.add("[destination]");
        e61.arguments.add("[-m <mask>]");
        e61.handler = ctx -> {
                    if (!ctx.session().hasClipboard()) {
                        throw CommandRegistry.error("No clipboard: use //copy first");
                    }
                    var holder = ctx.session().getClipboard();
                    java.util.List<BlockArrayClipboard> pool = ctx.session().getClipboardPool();
                    if (pool.size() > 1) {
                        // //schem loadall: a multi clipboard pastes a random member.
                        BlockArrayClipboard pick = pool.get(java.util.concurrent.ThreadLocalRandom.current()
                                .nextInt(pool.size()));
                        holder = new com.maxlananas.fawebim.core.session.ClipboardHolder(pick);
                        if (ctx.session().isClipboardPoolRandomRotation()) {
                            holder.setTransform(Transforms.rotate(pick.getOrigin(), java.util.concurrent.ThreadLocalRandom
                                    .current().nextInt(4) * 90.0));
                        }
                    } else if (ctx.session().isClipboardDynamicRotation() || ctx.session().isClipboardPoolDynamicRotation()) {
                        // //schem load -d: the rotation is re-rolled on every paste.
                        holder.setTransform(Transforms.rotate(clipboardOriginOf(holder),
                                java.util.concurrent.ThreadLocalRandom.current().nextInt(4) * 90.0));
                    }
                    BlockArrayClipboard clipboard = holder.getClipboard();
                    BlockVector3 destination = ctx.args().isEmpty()
                            ? ctx.placement() : ctx.blockVector(0);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask sourceMask = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    boolean onlySelect = ctx.hasFlag("n");
                    int changed = 0;
                    if (!onlySelect) {
                        changed = com.maxlananas.fawebim.core.clipboard.Clipboards.paste(clipboard, destination, session,
                                holder.getTransform(), !ctx.hasFlag("a"), sourceMask, ctx.hasFlag("e"),
                                ctx.hasFlag("b"), ctx.hasFlag("x"), ctx.hasFlag("v"));
                    }
                    if (ctx.hasFlag("s") || onlySelect) {
                        ctx.session().getSelector(ctx.world()).selectPrimary(destination,
                                com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                        ctx.session().getSelector(ctx.world()).selectSecondary(
                                destination.add(clipboard.getWidth(), clipboard.getHeight(), clipboard.getLength()),
                                com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    }
                    flush(ctx, session, "Pasted", changed, "block(s)");
                };


        CommandRegistry.Entry e62 = registry.register("//rotate");
        e62.description = "Rotate the contents of the clipboard";
        e62.group = "clipboard";
        e62.arguments.add("rotateY");
        e62.arguments.add("[rotateX]");
        e62.arguments.add("[rotateZ]");
        e62.handler = ctx -> {
                    if (!ctx.session().hasClipboard()) {
                        throw CommandRegistry.error("No clipboard");
                    }
                    double rotateY = ctx.doubleArg(0);
                    double rotateX = ctx.doubleArg(1, 0);
                    double rotateZ = ctx.doubleArg(2, 0);
                    var holder = ctx.session().getClipboard();
                    var origin = holder.getClipboard().getOrigin();
                    // Upstream rotates around the clipboard origin, one axis at a
                    // time and in this order, so a line asking for two of them
                    // gives the same result as the command run twice.
                    com.maxlananas.fawebim.core.transform.Transform transform = holder.getTransform();
                    if (rotateY != 0) {
                        transform = transform.combine(Transforms.rotate(origin,
                                com.maxlananas.fawebim.core.transform.Axis.Y, -rotateY));
                    }
                    if (rotateX != 0) {
                        transform = transform.combine(Transforms.rotate(origin,
                                com.maxlananas.fawebim.core.transform.Axis.X, -rotateX));
                    }
                    if (rotateZ != 0) {
                        transform = transform.combine(Transforms.rotate(origin,
                                com.maxlananas.fawebim.core.transform.Axis.Z, -rotateZ));
                    }
                    holder.setTransform(transform);
                    ctx.actor().message(Msg.result("Clipboard", "rotated"));
                };


        CommandRegistry.Entry e63 = registry.register("//flip");
        e63.description = "Flip the clipboard";
        e63.group = "clipboard";
        e63.arguments.add("[direction]");
        e63.handler = ctx -> {
                    if (!ctx.session().hasClipboard()) {
                        throw CommandRegistry.error("No clipboard");
                    }
                    String direction = ctx.arg(0, "me");
                    com.maxlananas.fawebim.core.transform.Axis axis = direction.equalsIgnoreCase("me")
                            ? switch (ctx.actor().facing()) {
                        case NORTH, SOUTH -> com.maxlananas.fawebim.core.transform.Axis.Z;
                        case EAST, WEST -> com.maxlananas.fawebim.core.transform.Axis.X;
                        default -> com.maxlananas.fawebim.core.transform.Axis.Y;
                    } : com.maxlananas.fawebim.core.transform.Axis.parse(direction);
                    var holder = ctx.session().getClipboard();
                    holder.setTransform(holder.getTransform().combine(
                            com.maxlananas.fawebim.core.transform.Transforms.flip(holder.getClipboard().getOrigin(), axis)));
                    ctx.actor().message(Msg.result("Clipboard", "flipped on " + Msg.value(axis).raw()));
                };


        CommandRegistry.Entry e64 = registry.register("//clearclipboard");
        e64.description = "Clear your clipboard";
        e64.group = "clipboard";
        e64.handler = ctx -> {
                    ctx.session().setClipboard(new BlockArrayClipboard(BlockVector3.ZERO));
                    ctx.actor().message(Msg.result("Clipboard", "cleared"));
                };


        CommandRegistry.Entry e65 = registry.register("//schem", "//schematic");
        e65.description = "Save/load/list schematics";
        e65.group = "schematic";
        e65.booleanFlags.add("o");
        e65.booleanFlags.add("r");
        e65.booleanFlags.add("d");
        // list: -p <page>, -d oldest first, -n newest first, -f <format>.
        e65.booleanFlags.add("n");
        e65.valueFlags.add("f");
        e65.valueFlags.add("p");
        e65.arguments.add("list|ls|all|save|load|loadall|delete|d|formats|listformats|f|move|m|share|clear|unload");
        e65.arguments.add("[name]");
        e65.arguments.add("[format]");
        e65.arguments.add("[-p <page>]");
        e65.handler = ctx -> {
                    String action = ctx.arg(0).toLowerCase(Locale.ROOT);
                    if (action.equals("ls") || action.equals("all")) {
                        action = "list";
                    }
                    switch (action) {
                        case "list" -> {
                            // //schem list [filter] overrides the filter /list set.
                            com.maxlananas.fawebim.core.clipboard.ListFilter filter =
                                    com.maxlananas.fawebim.core.clipboard.ListFilter.parse(ctx.arg(1, ""));
                            if (filter == null) {
                                filter = ctx.session().getListFilter();
                            }
                            List<String> names = Schematics.list(filter,
                                    ctx.actor().isPlayer() ? ctx.actor().name() : null);
                            // -f <format> keeps one format, -d and -n sort by
                            // write time instead of by name.
                            String format = ctx.hasFlag("f")
                                    ? ctx.flagValue("f", "").toLowerCase(Locale.ROOT) : null;
                            if (format != null) {
                                List<String> kept = new ArrayList<>();
                                for (String name : names) {
                                    if (Schematics.formatOf(name).toLowerCase(Locale.ROOT).contains(format)) {
                                        kept.add(name);
                                    }
                                }
                                names = kept;
                            }
                            if (ctx.hasFlag("d") || ctx.hasFlag("n")) {
                                boolean oldestFirst = ctx.hasFlag("d");
                                names.sort((a, b) -> oldestFirst
                                        ? Long.compare(Schematics.timeOf(a), Schematics.timeOf(b))
                                        : Long.compare(Schematics.timeOf(b), Schematics.timeOf(a)));
                            }
                            Page page = Page.of(ctx, names.size());
                            ctx.actor().message(Msg.info(Msg.title("Schematics") + "§7 (" + names.size() + ", page " + page.number()
                                    + "/" + page.pages() + ", " + filter.describe() + "):"));
                            for (String name : names.subList(page.from(), page.to())) {
                                ctx.actor().message(Msg.of("§7 - §f" + name + " §7("
                                        + Schematics.formatOf(name) + ")"));
                            }
                            page.hint(ctx, "//schem list");
                        }
                        case "save" -> {
                            if (!ctx.session().hasClipboard()) {
                                throw CommandRegistry.error("No clipboard: copy something first");
                            }
                            String name = ctx.arg(1);
                            String format = ctx.arg(2, "sponge.3");
                            // -f overwrites an existing file; without it a name
                            // that is already taken is refused.
                            if (!ctx.hasFlag("f") && Schematics.exists(name, format)) {
                                throw CommandRegistry.error("A schematic named '" + name + "' already exists."
                                        + " Use //schem save -f to overwrite it.");
                            }
                            // A large save goes to the worker pool, so the tick
                            // loop is not held up while the file is written.
                            BlockArrayClipboard saving = ctx.session().getClipboard().getClipboard();
                            if (format.toLowerCase(java.util.Locale.ROOT).startsWith("mcedit")
                                    || format.toLowerCase(java.util.Locale.ROOT).startsWith("legacy")) {
                                int lost = Schematics.legacyLosses(saving);
                                if (lost > 0) {
                                    ctx.actor().message(Msg.error(lost + " block(s) have no legacy id and are"
                                            + " saved as air; use sponge.3 to keep them"));
                                }
                            }
                            if (saving.volume() >= Schematics.ASYNC_SAVE_THRESHOLD) {
                                ctx.actor().message(Msg.success("Saving schematic '" + name + "' in the background"));
                                // The write runs on a worker, so its outcome comes
                                // back to the main thread; a failure would otherwise
                                // be lost with the worker.
                                Schematics.saveAsync(saving, name, format, ctx.world().executor())
                                        .whenComplete((file, error) -> ctx.world().sync(() -> {
                                            if (error != null) {
                                                ctx.actor().message(Msg.error("Could not save schematic '"
                                                        + name + "': " + error.getCause()));
                                            } else {
                                                ctx.actor().message(Msg.success("Saved schematic '"
                                                        + file.getFileName() + "'"));
                                            }
                                        }));
                            } else {
                                Schematics.save(saving, name, format);
                                ctx.actor().message(Msg.success("Saved schematic '" + name + "'"));
                            }
                        }
                        case "load" -> {
                            String name = ctx.arg(1);
                            BlockArrayClipboard clipboard = Schematics.load(name);
                            // -r applies a random rotation, -d re-rolls it on
                            // every paste instead of freezing it.
                            ctx.session().setClipboard(clipboard);
                            if (ctx.hasFlag("r")) {
                                ctx.session().setClipboardRandomRotation(true);
                                ctx.session().setClipboardDynamicRotation(ctx.hasFlag("d"));
                                ctx.session().getClipboard().setTransform(Transforms.rotate(clipboard.getOrigin(),
                                        java.util.concurrent.ThreadLocalRandom.current().nextInt(4) * 90.0));
                            } else {
                                ctx.session().setClipboardRandomRotation(false);
                                ctx.session().setClipboardDynamicRotation(false);
                            }
                            ctx.actor().message(Msg.success("Loaded schematic '" + name + "' ("
                                    + Msg.formatNumber(clipboard.volume()) + " blocks)"
                                    + (ctx.hasFlag("r") ? " with a random rotation" : "")));
                        }
                        case "delete", "d" -> {
                            Schematics.delete(ctx.arg(1));
                            ctx.actor().message(Msg.success("Deleted schematic '" + ctx.arg(1) + "'"));
                        }
                        case "unload" -> {
                            ctx.session().setClipboard(null);
                            ctx.actor().message(Msg.result("Clipboard", "unloaded"));
                        }
                        case "move", "m" -> {
                            String name = ctx.arg(1);
                            String format = ctx.arg(2, com.maxlananas.fawebim.core.platform.Config.get().defaultSchematicFormat);
                            com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard converted = Schematics.load(name);
                            Schematics.delete(name);
                            Schematics.save(converted, name, format);
                            ctx.actor().message(Msg.success("Schematic '" + name + "' converted to " + format));
                        }
                        case "share" -> {
                            if (!ctx.session().hasClipboard()) {
                                throw CommandRegistry.error("No clipboard: copy something first");
                            }
                            String name = ctx.arg(1, "shared-" + System.currentTimeMillis());
                            Schematics.save(ctx.session().getClipboard().getClipboard(), name,
                                    com.maxlananas.fawebim.core.platform.Config.get().defaultSchematicFormat);
                            ctx.actor().message(Msg.success("Schematic shared as '" + name
                                    + "' in " + Schematics.directory()
                                    + " (uploading needs a web service, which the mod does not ship)"));
                        }
                        case "clear" -> {
                            ctx.session().setClipboard(null);
                            ctx.session().clearClipboardPool();
                            ctx.actor().message(Msg.result("Clipboard", "cleared"));
                        }
                        case "loadall" -> {
                            String format = ctx.arg(1, com.maxlananas.fawebim.core.platform.Config.get()
                                    .defaultSchematicFormat);
                            String filter = ctx.arg(2, "*");
                            java.util.List<com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard> loaded =
                                    Schematics.loadAll(format, filter);
                            if (loaded.isEmpty()) {
                                throw CommandRegistry.error("No schematic matched '" + filter + "'");
                            }
                            if (ctx.hasFlag("o")) {
                                ctx.session().setClipboardPool(loaded);
                            } else {
                                ctx.session().addToClipboardPool(loaded);
                            }
                            ctx.session().setClipboardPoolRandomRotation(ctx.hasFlag("r") || ctx.hasFlag("d"));
                            ctx.session().setClipboardPoolDynamicRotation(ctx.hasFlag("d"));
                            ctx.session().setClipboard(loaded.get(0));
                            ctx.actor().message(Msg.success("Loaded " + loaded.size() + " clipboard(s); "
                                    + "//paste picks one at random"));
                        }
                        case "formats", "listformats", "f" -> {
                            ctx.actor().message(Msg.info("Formats: " + String.join(", ", Schematics.formats())
                                    + " (default: " + com.maxlananas.fawebim.core.platform.Config.get().defaultSchematicFormat + ")"));
                        }
                        default -> throw CommandRegistry.error("Usage: //schem list|save <name>|load <name>|delete <name>");
                    }
                };

    }

    // ------------------------------------------------------------------ history

    private void registerHistory() {
        CommandRegistry.Entry e66 = registry.register("//undo", "/undo", "//u");
        e66.description = "Undoes the last action (from history)";
        e66.group = "history";
        e66.arguments.add("[times]");
        e66.arguments.add("[player]");
        e66.handler = ctx -> {
                    int steps = undoCount(ctx.argument(0));
                    com.maxlananas.fawebim.core.session.LocalSession target = historyTarget(ctx);
                    int undone = historySteps(ctx, target, steps, true);
                    String who = target == ctx.session() ? "" : " for " + target.ownerName();
                    if (undone == 0) {
                        ctx.actor().message(Msg.error("Nothing to undo" + who));
                    } else {
                        ctx.actor().message(Msg.result("Undid", Msg.count(undone) + "\u00a77 block change(s)" + who));
                    }
                };


        CommandRegistry.Entry e67 = registry.register("//redo", "/redo", "//r");
        e67.description = "Redoes the last action (from history)";
        e67.group = "history";
        e67.arguments.add("[times]");
        e67.arguments.add("[player]");
        e67.handler = ctx -> {
                    int steps = undoCount(ctx.argument(0));
                    com.maxlananas.fawebim.core.session.LocalSession target = historyTarget(ctx);
                    int redone = historySteps(ctx, target, steps, false);
                    String who = target == ctx.session() ? "" : " for " + target.ownerName();
                    if (redone == 0) {
                        ctx.actor().message(Msg.error("Nothing to redo" + who));
                    } else {
                        ctx.actor().message(Msg.result("Redid", Msg.count(redone) + "\u00a77 block change(s)" + who));
                    }
                };


        CommandRegistry.Entry e68 = registry.register("//clearhistory");
        e68.description = "Clear your history";
        e68.group = "history";
        e68.handler = ctx -> {
                    ctx.session().getHistory().clear();
                    ctx.actor().message(Msg.result("History", "cleared"));
                };

    }

    /**
     * The session {@code //undo} and {@code //redo} act on: the caller's own, or
     * the one belonging to the player named behind the count.
     *
     * <p>The count and the name share the optional tail, so the argument that is
     * a number is the count and the one that is not is the name.</p>
     */
    private static com.maxlananas.fawebim.core.session.LocalSession historyTarget(Ctx ctx) {
        Ctx.Argument named = ctx.argument(1);
        if (named == null || named.isNumber()) {
            // A name in the count's place, for a line that names a player and
            // leaves the count at one.
            named = ctx.argument(0);
        }
        if (named == null || named.isNumber()) {
            return ctx.session();
        }
        return otherSession(ctx, named.value());
    }

    private static com.maxlananas.fawebim.core.session.LocalSession otherSession(Ctx ctx, String name) {
        com.maxlananas.fawebim.core.session.LocalSession other =
                com.maxlananas.fawebim.core.session.SessionManager.get().byName(name);
        if (other == null) {
            throw CommandRegistry.error("Unable to find session for " + name);
        }
        return other;
    }

    /** The count {@code //undo} and {@code //redo} take: one unless a number was given. */
    private static int undoCount(Ctx.Argument first) {
        return first == null || !first.isNumber() ? 1 : Math.max(1, (int) Math.round(
                Double.parseDouble(first.value())));
    }

    /**
     * Runs {@code steps} edits of a session's history in one direction, putting
     * back what an undo takes off or replaying what a redo returns. The changes
     * are written without being recorded again, which is what keeps an undo out
     * of the history it is walking.
     */
    private int historySteps(Ctx ctx, com.maxlananas.fawebim.core.session.LocalSession session,
                             int steps, boolean undo) {
        int changed = 0;
        for (int i = 0; i < steps; i++) {
            var record = undo ? session.getHistory().undo() : session.getHistory().redo();
            if (record == null) {
                break;
            }
            EditSession edit = new EditSession(ctx.world(), session, undo ? "undo" : "redo", false);
            for (var sets : record.changes().values()) {
                for (var set : sets) {
                    edit.applyChangeSet(set, undo);
                }
            }
            for (var sets : record.biomeChanges().values()) {
                for (var set : sets) {
                    edit.applyBiomeChangeSet(set, undo);
                }
            }
            changed += record.changeCount() + record.biomeChangeCount();
            edit.flushQueue();
        }
        return changed;
    }

    // -------------------------------------------------------------------- biome

    private void registerBiome() {
        CommandRegistry.Entry e69 = registry.register("/setbiome", "//setbiome", "//biome");
        e69.description = "Set the biome in the selection, or at your position with -p";
        e69.group = "biome";
        e69.requiresSelection = true;
        // -p changes the biome of the block the player stands in only.
        e69.booleanFlags.add("p");
        e69.arguments.add("biome");
        e69.handler = ctx -> {
                    if (ctx.hasFlag("p")) {
                        int biomeId = Parsers.biome(ctx.arg(0));
                        BlockVector3 pos = ctx.placement();
                        EditSession session = ctx.editSession();
                        session.setBiome(pos.x(), pos.y(), pos.z(), biomeId);
                        session.flushQueue();
                        ctx.actor().message(Msg.success("Changed biome at " + pos + " to " + ctx.arg(0)));
                        return;
                    }
                    int biomeId = Parsers.biome(ctx.arg(0));
                    EditSession session = ctx.editSession();
                    // A biome cell covers 4x4x4 blocks, so each column is asked
                    // once per cell instead of once per block.
                    int changed = 0;
                    for (int x = ctx.selection().getMinimumPoint().x(); x <= ctx.selection().getMaximumPoint().x(); x++) {
                        for (int z = ctx.selection().getMinimumPoint().z();
                                z <= ctx.selection().getMaximumPoint().z(); z++) {
                            for (int y = ctx.world().minY(); y < ctx.world().maxY(); y += 4) {
                                if (session.setBiome(x, y, z, biomeId)) {
                                    changed++;
                                }
                            }
                        }
                    }
                    session.flushQueue();
                    ctx.actor().message(Msg.success("Changed biome of " + Msg.formatNumber(changed)
                            + " biome cell(s)"));
                };


        CommandRegistry.Entry e70 = registry.register("//biomelist");
        e70.description = "List available biomes";
        e70.group = "biome";
        e70.valueFlags.add("p");
        e70.arguments.add("[-p <page>]");
        e70.handler = ctx -> {
                    List<String> biomes = BlockState.registry().biomeNames();
                    Page page = Page.of(ctx, biomes.size());
                    ctx.actor().message(Msg.info(page.header("Biomes", biomes.size()) + " "
                            + Str.limit(String.join(", ", biomes.subList(page.from(), page.to())), 2000)));
                };


        CommandRegistry.Entry e71 = registry.register("//biomeinfo", "//biomeinfo -p");
        e71.description = "Show the biome you are standing in";
        e71.group = "biome";
        // -t reads the biome of the block the player looks at, -p the one the
        // player stands in.
        e71.booleanFlags.add("t");
        e71.booleanFlags.add("p");
        e71.handler = ctx -> {
                    BlockVector3 pos = ctx.hasFlag("t")
                            ? ctx.targetBlock(100) : ctx.placement();
                    int biomeId = ctx.world().getBiome(pos.x(), pos.y(), pos.z());
                    ctx.actor().message(Msg.keyValue("Biome", BlockState.registry().biomeName(biomeId)));
                };

    }

    // -------------------------------------------------------------------- chunk

    private void registerChunk() {
        CommandRegistry.Entry e72 = registry.register("//chunkinfo");
        e72.description = "Show information about the current chunk";
        e72.group = "chunk";
        e72.requiresPlayer = true;
        e72.handler = ctx -> {
                    BlockVector3 pos = ctx.placement();
                    int cx = pos.x() >> 4;
                    int cz = pos.z() >> 4;
                    ctx.actor().message(Msg.keyValue("Chunk", cx + ", " + cz));
                    ctx.actor().message(Msg.keyValue("Loaded", ctx.world().isChunkLoaded(cx, cz)));
                    ctx.actor().message(Msg.keyValue("Highest block",
                            ctx.world().getHighestBlockY(pos.x(), pos.z())));
                };


        CommandRegistry.Entry e73 = registry.register("//listchunks");
        e73.description = "List the chunks in the selection";
        e73.group = "chunk";
        e73.requiresSelection = true;
        e73.valueFlags.add("p");
        e73.arguments.add("[-p <page>]");
        e73.handler = ctx -> {
                    List<BlockVector2> chunks = ctx.selection().getChunks();
                    Page page = Page.of(ctx, chunks.size(), 40);
                    StringBuilder sb = new StringBuilder(page.header("Chunks", chunks.size()) + " ");
                    for (int i = page.from(); i < page.to(); i++) {
                        sb.append(chunks.get(i).x()).append(',').append(chunks.get(i).z()).append(' ');
                    }
                    ctx.actor().message(Msg.info(sb.toString()));
                };


        CommandRegistry.Entry e74 = registry.register("//delchunks");
        e74.description = "Delete the chunks in the selection (regenerates them)";
        e74.group = "chunk";
        e74.requiresSelection = true;
        // -o only deletes the chunks that were untouched for that long.
        e74.valueFlags.add("o");
        e74.arguments.add("[-o <time>]");
        e74.handler = ctx -> {
                    long before = ctx.hasFlag("o") ? parseDuration(ctx.flagValue("o", "")) : 0;
                    long threshold = before == 0 ? 0 : System.currentTimeMillis() - before;
                    int count = 0;
                    int skipped = 0;
                    for (BlockVector2 chunk : ctx.selection().getChunks()) {
                        if (threshold > 0 && ctx.world().chunkLastModified(chunk.x(), chunk.z()) > threshold) {
                            skipped++;
                            continue;
                        }
                        if (ctx.world().regenerateChunk(chunk.x(), chunk.z(),
                                new com.maxlananas.fawebim.core.world.RegenOptions())) {
                            count++;
                        }
                    }
                    ctx.actor().message(Msg.result("Deleted", Msg.count(count) + "\u00a77 chunk(s)"
                            + (skipped > 0 ? ", kept " + Msg.count(skipped) + "\u00a77 recently changed"
                                    : "")));
                };


        CommandRegistry.Entry e75 = registry.register("//chunk");
        e75.description = "Select the chunk you are standing in";
        e75.group = "chunk";
        // -c reads the argument as chunk coordinates, -s expands the current
        // selection to whole chunks instead of replacing it.
        e75.booleanFlags.add("c");
        e75.booleanFlags.add("s");
        e75.arguments.add("[coordinates]");
        e75.handler = ctx -> {
                    BlockVector3 pos = ctx.placement();
                    if (ctx.hasFlag("s")) {
                        Region region = ctx.selection();
                        BlockVector3 min = region.getMinimumPoint();
                        BlockVector3 max = region.getMaximumPoint();
                        RegionSelector selector = ctx.session().getSelector(ctx.world());
                        var limits = com.maxlananas.fawebim.core.region.SelectorLimits.unlimited();
                        selector.selectPrimary(new BlockVector3(min.x() >> 4 << 4, min.y(), min.z() >> 4 << 4), limits);
                        selector.selectSecondary(new BlockVector3(((max.x() >> 4) << 4) + 15, max.y(),
                                ((max.z() >> 4) << 4) + 15), limits);
                        ctx.actor().message(Msg.success("Selection expanded to whole chunks"));
                        return;
                    }
                    int cx;
                    int cz;
                    if (ctx.args().size() >= 2) {
                        cx = ctx.intArg(0);
                        cz = ctx.intArg(1);
                    } else {
                        String coordinate = ctx.arg(0, null);
                        if (coordinate != null && coordinate.contains(",")) {
                            String[] parts = coordinate.split(",", 2);
                            cx = (int) Double.parseDouble(parts[0].trim());
                            cz = (int) Double.parseDouble(parts[1].trim());
                        } else if (coordinate != null) {
                            cx = ctx.intArg(0);
                            cz = 0;
                        } else {
                            cx = pos.x() >> 4;
                            cz = pos.z() >> 4;
                        }
                    }
                    if (!ctx.hasFlag("c")) {
                        cx = pos.x() >> 4;
                        cz = pos.z() >> 4;
                    }
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectPrimary(new BlockVector3(cx << 4, ctx.world().minY(), cz << 4),
                            com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    selector.selectSecondary(new BlockVector3((cx << 4) + 15, ctx.world().maxY(), (cz << 4) + 15),
                            com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.success("Selected chunk " + cx + ", " + cz));
                };

    }

    // --------------------------------------------------------------- navigation

    private void registerNavigation() {
        CommandRegistry.Entry e76 = registry.register("//jumpto", "//j");
        e76.description = "Teleport to a location";
        e76.group = "navigation";
        e76.requiresPlayer = true;
        e76.arguments.add("[location]");
        e76.booleanFlags.add("f");
        e76.handler = ctx -> {
                    BlockVector3 target = ctx.args().isEmpty()
                            ? ctx.targetBlock(300)
                            : ctx.parseBlockVector(ctx.joined(0));
                    if (target == null) {
                        throw CommandRegistry.error("No block in sight");
                    }
                    ctx.requirePosition();
                    Navigation.setOnGround(ctx.actor(), target);
                    ctx.actor().message(Msg.result("Jumped to", Msg.value(target).raw()));
                };


        CommandRegistry.Entry e77 = registry.register("//thru");
        e77.description = "Pass through walls";
        e77.group = "navigation";
        e77.requiresPlayer = true;
        e77.handler = ctx -> {
                    ctx.requirePosition();
                    if (!Navigation.passThroughForwardWall(ctx.actor(), 6)) {
                        throw CommandRegistry.error("No wall in front of you");
                    }
                    ctx.actor().message(Msg.success("Went through the wall"));
                };


        CommandRegistry.Entry e78 = registry.register("//unstuck");
        e78.description = "Escape from being stuck inside a block";
        e78.group = "navigation";
        e78.requiresPlayer = true;
        e78.handler = ctx -> {
                    ctx.requirePosition();
                    if (!Navigation.findFreePosition(ctx.actor())) {
                        throw CommandRegistry.error("Could not find a free spot");
                    }
                    ctx.actor().message(Msg.result("Unstuck", "moved you to a free spot"));
                };


        CommandRegistry.Entry e79 = registry.register("//ascend", "//asc");
        e79.description = "Go up a floor";
        e79.group = "navigation";
        e79.requiresPlayer = true;
        e79.arguments.add("[levels]");
        e79.handler = ctx -> {
                    ctx.requirePosition();
                    int levels = ctx.args().isEmpty() ? 1 : Math.max(1, ctx.intArg(0));
                    int moved = 0;
                    while (moved < levels && Navigation.ascendLevel(ctx.actor())) {
                        ++moved;
                    }
                    if (moved == 0) {
                        throw CommandRegistry.error("You would hit something above you");
                    }
                    ctx.actor().message(Msg.result("Ascended", Msg.count(moved) + "\u00a77 level(s)"));
                };


        CommandRegistry.Entry e79b = registry.register("//descend", "//desc");
        e79b.description = "Go down a floor";
        e79b.group = "navigation";
        e79b.requiresPlayer = true;
        e79b.arguments.add("[levels]");
        e79b.handler = ctx -> {
                    ctx.requirePosition();
                    int levels = ctx.args().isEmpty() ? 1 : Math.max(1, ctx.intArg(0));
                    int moved = 0;
                    while (moved < levels && Navigation.descendLevel(ctx.actor())) {
                        ++moved;
                    }
                    if (moved == 0) {
                        throw CommandRegistry.error("You would hit something below you");
                    }
                    ctx.actor().message(Msg.result("Descended", Msg.count(moved) + "\u00a77 level(s)"));
                };


        CommandRegistry.Entry e79c = registry.register("//ceil", "//ceiling");
        e79c.description = "Go to the ceiling";
        e79c.group = "navigation";
        e79c.requiresPlayer = true;
        e79c.arguments.add("[clearance]");
        e79c.booleanFlags.add("f");
        e79c.booleanFlags.add("g");
        e79c.handler = ctx -> {
                    ctx.requirePosition();
                    int clearance = Math.max(0, ctx.args().isEmpty() ? 0 : ctx.intArg(0));
                    if (!Navigation.ascendToCeiling(ctx.actor(), clearance, alwaysGlass(ctx))) {
                        throw CommandRegistry.error("You would hit something above you");
                    }
                    ctx.actor().message(Msg.success("Moved to the ceiling"));
                };


        CommandRegistry.Entry e79d = registry.register("//up");
        e79d.description = "Go upwards some distance";
        e79d.group = "navigation";
        e79d.requiresPlayer = true;
        e79d.arguments.add("distance");
        e79d.booleanFlags.add("f");
        e79d.booleanFlags.add("g");
        e79d.handler = ctx -> {
                    ctx.requirePosition();
                    int distance = ctx.intArg(0);
                    if (distance < 1) {
                        throw CommandRegistry.error("Distance must be positive");
                    }
                    if (!Navigation.ascendUpwards(ctx.actor(), distance, alwaysGlass(ctx))) {
                        throw CommandRegistry.error("You are obstructed above");
                    }
                    ctx.actor().message(Msg.success("Moved up " + distance + " block(s)"));
                };


        CommandRegistry.Entry e80 = registry.register("//world");
        e80.description = "Show or change the world override";
        e80.group = "navigation";
        e80.arguments.add("[world]");
        e80.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.actor().message(Msg.keyValue("World", ctx.world().name()));
                    } else {
                        ctx.actor().message(Msg.keyValue("World", ctx.arg(0)));
                    }
                };

    }

    /**
     * WorldEdit's {@code getAlwaysGlass}: {@code -g} forces a glass platform,
     * {@code -f} forces flight instead, and with neither a player who is not
     * already flying gets a platform.
     */
    private static boolean alwaysGlass(Ctx ctx) {
        if (ctx.hasFlag("g")) {
            return true;
        }
        if (ctx.hasFlag("f")) {
            return false;
        }
        return !ctx.actor().isFlying();
    }

    // ------------------------------------------------------------------ utility

    private void registerUtility() {
        CommandRegistry.Entry e81 = registry.register("/fast");
        e81.description = "Toggle fast mode";
        e81.group = "utility";
        e81.arguments.add("[true|false]");
        e81.handler = ctx -> {
                    LocalSession session = ctx.session();
                    boolean enabled = ctx.args().isEmpty()
                            ? !session.isFastMode() : Parsers.booleanArg(ctx, 0, false);
                    if (enabled == session.isFastMode()) {
                        ctx.actor().message(Msg.info("Fast mode already "
                                + (enabled ? "enabled" : "disabled") + "."));
                        return;
                    }
                    session.setFastMode(enabled);
                    ctx.actor().message(Msg.result("Fast mode", enabled
                            ? "on \u00a77- lighting in the affected chunks may be wrong and/or you"
                                    + " may need to rejoin to see changes"
                            : "off"));
                };


        CommandRegistry.Entry e82 = registry.register("/perf", "//perf");
        e82.description = "Toggle side effects for performance";
        e82.group = "utility";
        e82.arguments.add("[sideEffect]");
        e82.arguments.add("[newState]");
        e82.booleanFlags.add("h");
        e82.handler = ctx -> {
                    LocalSession session = ctx.session();
                    SideEffect effect = null;
                    SideEffect.State newState = null;
                    if (!ctx.args().isEmpty()) {
                        effect = SideEffect.parse(ctx.arg(0));
                        if (effect == null) {
                            // A state on its own applies to every side effect.
                            newState = SideEffect.State.parse(ctx.arg(0));
                            if (newState == null) {
                                throw CommandRegistry.error("Unknown side effect '" + ctx.arg(0)
                                        + "'; try one of " + SideEffect.names());
                            }
                        }
                    }
                    if (effect != null && ctx.args().size() > 1) {
                        newState = SideEffect.State.parse(ctx.arg(1));
                        if (newState == null) {
                            throw CommandRegistry.error("A side effect state must be on, off or delayed: '"
                                    + ctx.arg(1) + "'");
                        }
                    }
                    // -h prints the box instead of the one-line answer, the way
                    // upstream's SideEffectBox does.
                    boolean box = ctx.hasFlag("h");
                    if (effect != null) {
                        SideEffect.State current = session.getSideEffectSet().getState(effect);
                        if (newState != null && newState == current) {
                            if (!box) {
                                ctx.actor().message(Msg.info("Side effect \"" + effect.getDisplayName()
                                        + "\" is already " + newState.getDisplayName()));
                            }
                            return;
                        }
                        if (newState != null) {
                            session.setSideEffectSet(session.getSideEffectSet().with(effect, newState));
                            if (!box) {
                                ctx.actor().message(Msg.success("Side effect \"" + effect.getDisplayName()
                                        + "\" set to " + newState.getDisplayName()));
                            }
                        } else {
                            ctx.actor().message(Msg.info("Side effect \"" + effect.getDisplayName()
                                    + "\" is set to " + current.getDisplayName()));
                        }
                    } else if (newState != null) {
                        SideEffectSet set = session.getSideEffectSet();
                        for (SideEffect each : SideEffect.values()) {
                            set = set.with(each, newState);
                        }
                        session.setSideEffectSet(set);
                        if (!box) {
                            ctx.actor().message(Msg.success("All side effects set to "
                                    + newState.getDisplayName()));
                        }
                    }
                    if (effect == null || box) {
                        for (SideEffect each : SideEffect.values()) {
                            ctx.actor().message(Msg.keyValue(each.getDisplayName(),
                                    session.getSideEffectSet().getState(each).getDisplayName()));
                        }
                    }
                };


        CommandRegistry.Entry e84 = registry.register("/timeout");
        e84.description = "Set your operation timeout in seconds";
        e84.group = "utility";
        e84.arguments.add("[seconds]");
        e84.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.actor().message(Msg.keyValue("Timeout", ctx.session().getTimeout() + "s"));
                    } else {
                        ctx.session().setTimeout(ctx.intArg(0));
                        ctx.actor().message(Msg.success("Timeout set to " + ctx.intArg(0) + "s"));
                    }
                };


        CommandRegistry.Entry e85 = registry.register("/limit", "//limit");
        e85.description = "Set the maximum number of blocks you can change";
        e85.group = "utility";
        e85.arguments.add("[limit]");
        e85.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.actor().message(Msg.keyValue("Limit", ctx.session().getMaxBlocksChanged() < 0 ? "none"
                                : String.valueOf(ctx.session().getMaxBlocksChanged())));
                    } else {
                        // The configured maximum caps what a player may give
                        // themselves, the way FAWE limits it.
                        int maximum = com.maxlananas.fawebim.core.platform.Config.get().maxChangeLimit;
                        int requested = ctx.intArg(0);
                        if (maximum >= 0 && requested >= 0 && requested > maximum) {
                            throw CommandRegistry.error("Limit must be at most " + maximum
                                    + " (raise limits.max-blocks-changed.maximum in config/fawebim.yml)");
                        }
                        ctx.session().setMaxBlocksChanged(requested);
                        ctx.actor().message(Msg.success("Limit set to " + requested));
                    }
                };


        CommandRegistry.Entry e86 = registry.register("/gmask", "//gmask");
        e86.description = "Set the global mask";
        e86.group = "utility";
        e86.arguments.add("[mask]");
        e86.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.session().setMask(null);
                        ctx.actor().message(Msg.success("Global mask cleared"));
                        return;
                    }
                    Mask mask = Parsers.mask(ctx.joined(0), ctx);
                    ctx.session().setMask(mask);
                    ctx.actor().message(Msg.success("Global mask set to " + ctx.joined(0)));
                };


        // The source mask is a different question from the global mask: it says
        // which blocks a brush may read from, while the global mask says which it
        // may write. It belongs to the brush, so it is kept on the brush when one
        // is equipped and on the session otherwise.
        CommandRegistry.Entry e86b = registry.register("/smask", "//smask", "/sourcemask");
        e86b.description = "Set the brush source mask";
        e86b.group = "brush";
        e86b.requiresPlayer = true;
        e86b.arguments.add("[mask]");
        e86b.handler = ctx -> {
                    com.maxlananas.fawebim.core.brush.Brush brush =
                            com.maxlananas.fawebim.core.brush.BrushFactory.current(ctx.session());
                    if (ctx.args().isEmpty()) {
                        ctx.session().setSourceMask(null);
                        if (brush != null) {
                            brush.settings().setSourceMask(null);
                        }
                        ctx.actor().message(Msg.success("Brush source mask cleared"));
                        return;
                    }
                    Mask mask = Parsers.mask(ctx.joined(0), ctx);
                    ctx.session().setSourceMask(mask);
                    if (brush != null) {
                        brush.settings().setSourceMask(mask);
                    }
                    ctx.actor().message(Msg.success("Brush source mask set to " + ctx.joined(0)));
                };


        CommandRegistry.Entry e87 = registry.register("/gtexture", "//gtexture", "/material", "//material");
        e87.description = "Set the global pattern";
        e87.group = "utility";
        e87.arguments.add("[pattern]");
        e87.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.session().setPattern(null);
                        ctx.actor().message(Msg.success("Global pattern cleared"));
                        return;
                    }
                    ctx.session().setPattern(Parsers.pattern(ctx.joined(0), ctx));
                    ctx.actor().message(Msg.success("Global pattern set to " + ctx.joined(0)));
                };


        CommandRegistry.Entry e88 = registry.register("/gtransform", "//gtransform");
        e88.description = "Apply a transform to every edit you make";
        e88.group = "utility";
        e88.arguments.add("[transform]");
        e88.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.session().getTransformSet().clear();
                        ctx.actor().message(Msg.success("Global transform cleared"));
                        return;
                    }
                    com.maxlananas.fawebim.core.transform.Transforms.Set set =
                            new com.maxlananas.fawebim.core.transform.Transforms.Set();
                    set.add(com.maxlananas.fawebim.core.transform.Transforms.rotate(BlockVector3.ZERO,
                            Double.parseDouble(ctx.arg(0))));
                    ctx.session().getTransformSet().setTransforms(set);
                    ctx.actor().message(Msg.success("Global transform set"));
                };


        CommandRegistry.Entry e89 = registry.register("//masks");
        e89.description = "List the available masks";
        e89.group = "utility";
        e89.handler = ctx -> ctx.actor().message(Msg.info( Msg.title("Masks") + "§7: #air #existing #solid #liquid #fullcube #wall #surface #angle #surfaceangle #roc #beside " + "#extrema #xaxis #yaxis #zaxis #true #false #exposed #biome #region #dregion #offset " + "#simplex #clipboard # =expr ! & ,"));


        CommandRegistry.Entry e90 = registry.register("//patterns");
        e90.description = "List the available patterns";
        e90.group = "utility";
        e90.handler = ctx -> ctx.actor().message(Msg.info( Msg.title("Patterns") + "§7: block, 25%block, #clipboard #copy #existing #biome #offset #spread #solidspread " + "#surfacespread #l/#linear #l3d #l2d #color #lighten #darken #saturate #desaturate " + "#swaptype #simplex ##tag =expr ^"));


        CommandRegistry.Entry e91 = registry.register("//transforms");
        e91.description = "List the available transforms";
        e91.group = "utility";
        e91.handler = ctx -> ctx.actor().message(Msg.info( Msg.title("Transforms") + "§7: rotate <angle> [axis], flip [direction], scale <factor>, offset <x> <y> <z>"));


        CommandRegistry.Entry e92 = registry.register("//brushes");
        e92.description = "List the available brushes";
        e92.group = "utility";
        e92.handler = ctx -> ctx.actor().message(Msg.info( Msg.title("Brushes") + "§7: sphere ball smooth blendball flatten height raise lower layer line spline catenary " + "scatter shatter splatter rock blob pull stencil gravity cylinder clipboard copypaste " + "biome butcher forest command populateschematic surface surfacespline sweep"));


        CommandRegistry.Entry e93 = registry.register("//desel", "//deselect");
        e93.description = "Clear your selection";
        e93.group = "selection";
        e93.handler = ctx -> {
                    ctx.session().getSelector(ctx.world()).clear();
                    ctx.actor().message(Msg.success("Selection cleared"));
                };


        CommandRegistry.Entry e94 = registry.register("//we", "/we", "/worldedit");
        e94.description = "WorldEdit/FAWE information";
        e94.group = "utility";
        e94.arguments.add("[version|reload|trace|help]");
        e94.handler = ctx -> {
                    String action = ctx.arg(0, "version").toLowerCase(Locale.ROOT);
                    switch (action) {
                        case "version" -> ctx.actor().message(Msg.info("FAWE-BIM "
                                + com.maxlananas.fawebim.core.platform.Config.VERSION
                                + " for Minecraft " + com.maxlananas.fawebim.core.platform.Config.MINECRAFT_VERSION
                                + " (WorldEdit/FAWE command surface 7.3.17)"));
                        case "reload" -> {
                            com.maxlananas.fawebim.core.platform.Config.get().reload();
                            ctx.actor().message(Msg.success("Configuration reloaded"));
                        }
                        case "trace" -> {
                            // The same switch as the /we trace command, for a
                            // line that reached /we itself.
                            boolean tracing = !ctx.session().isTracing();
                            ctx.session().setTracing(tracing);
                            ctx.actor().message(Msg.result("Trace mode", tracing ? "active" : "inactive"));
                        }
                        default -> ctx.actor().message(Msg.info("Usage: /we version|reload|trace"));
                    }
                };


        CommandRegistry.Entry e95 = registry.register("//help", "/help");
        e95.description = "List the commands, page by page";
        e95.group = "utility";
        // -s lists the sub-commands of the given command, -p picks the page.
        e95.booleanFlags.add("s");
        e95.valueFlags.add("p");
        e95.arguments.add("[filter]");
        e95.arguments.add("[-p <page>]");
        e95.arguments.add("[-s]");
        e95.handler = ctx -> {
                    String filter = ctx.arg(0, "").trim().toLowerCase(Locale.ROOT);
                    if (filter.isEmpty()) {
                        Help.list(ctx, registry);
                    } else if (ctx.hasFlag("s")) {
                        Help.subCommands(ctx, registry, filter);
                    } else {
                        Help.search(ctx, registry, filter);
                    }
                };


        CommandRegistry.Entry e96 = registry.register("//version");
        e96.description = "Show the mod version";
        e96.group = "utility";
        e96.handler = ctx -> ctx.actor().message(Msg.info(Msg.title("FAWE-BIM") + "§7 " + com.maxlananas.fawebim.core.platform.Config.VERSION
                + " \u2014 " + registry.all().size() + " commands registered"));

    }

    private void registerMasksAndPatterns() {
        CommandRegistry.Entry e97 = registry.register("/masks-list");
        e97.description = "List every available mask";
        e97.group = "utility";
        e97.status = "alias";
        e97.handler = ctx -> registry.dispatch(ctx.actor(), "//masks");

    }

    private void registerBrushes() {
        // One entry per brush of the generated signature table, so every brush
        // FAWE declares is reachable with the arguments and the switches it has
        // upstream.
        for (String[] row : BrushTable.BRUSHES) {
            List<String> aliases = new ArrayList<>();
            for (String alias : com.maxlananas.fawebim.core.brush.BrushParameters.aliases(row)) {
                if (!alias.equals(row[0])) {
                    aliases.add("/brush " + alias);
                }
            }
            CommandRegistry.Entry entry = registry.registerUnlessPresent("/brush " + row[0],
                    aliases.toArray(new String[0]));
            if (entry == null) {
                continue;
            }
            entry.description = row[5].isEmpty() ? "Brush: " + row[0] : row[5];
            entry.group = "brush";
            entry.requiresPlayer = true;
            for (String argument : com.maxlananas.fawebim.core.brush.BrushParameters.arguments(row)) {
                String name = argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
                boolean optional = argument.contains("=");
                entry.arguments.add(optional ? "[" + name + "]" : "<" + name + ">");
            }
            // A flag upstream declares both as a switch and as a value flag is
            // listed once, in the form that takes a value.
            for (String flag : com.maxlananas.fawebim.core.brush.BrushParameters.valueFlags(row)) {
                String name = flag.contains(":") ? flag.substring(0, flag.indexOf(':')) : flag;
                String switchName = flag.contains(":") ? flag.substring(flag.indexOf(':') + 1) : flag;
                entry.valueFlags.add(switchName);
                entry.arguments.add("[-" + switchName + " <" + name + ">]");
            }
            for (String flag : com.maxlananas.fawebim.core.brush.BrushParameters.switches(row)) {
                entry.booleanFlags.add(flag);
                if (!entry.valueFlags.contains(flag)) {
                    entry.arguments.add("[-" + flag + "]");
                }
            }
            entry.handler = ctx -> bindBrush(ctx, row);
        }

        registerBrushPresets();
        registerBrushNone();

        CommandRegistry.Entry e99 = registry.register("/brush", "//brush", "/br");
        e99.description = "Show the current brush";
        e99.group = "brush";
        e99.handler = ctx -> {
                    var brush = com.maxlananas.fawebim.core.brush.BrushFactory.current(ctx.session());
                    if (brush == null) {
                        ctx.actor().message(Msg.info("No brush bound. Use /brush sphere 5 stone for example."));
                    } else {
                        ctx.actor().message(Msg.keyValue("Brush", brush.describe()));
                    }
                };

    }

    private void registerTools() {
        CommandRegistry.Entry e100 = registry.register("/tool", "//tool");
        e100.description = "Bind a tool to an item: none, tree, repl, cycler, flood-fill, brush, info, farwand, "
                        + "navwand, lrbuild, stacker, deltree";
        e100.group = "tool";
        e100.arguments.add("[" + String.join("|", com.maxlananas.fawebim.core.tool.Tools.NAMES) + "]");
        e100.arguments.add("[target]");
        e100.handler = ctx -> {
                    String type = ctx.arg(0, "none").toLowerCase(Locale.ROOT);
                    if (type.equals("none")) {
                        LocalSession session = ctx.session();
                        String held = ctx.actor().heldItem();
                        com.maxlananas.fawebim.core.tool.Tools.clear(session);
                        // Upstream a brush is a tool bound to an item, so unbinding
                        // clears the brush equipped with the held item as well.
                        if (held != null && held.equals(session.getBindings().get("brush-item"))) {
                            com.maxlananas.fawebim.core.brush.BrushFactory.unbind(session);
                            session.getBindings().remove("brush-command");
                        }
                        if (held != null && held.equals(session.getBindings().get("secondary-brush-item"))) {
                            com.maxlananas.fawebim.core.brush.BrushFactory.unbindSecondary(session);
                        }
                        ctx.actor().message(Msg.success("Tool unbound"));
                        return;
                    }
                    com.maxlananas.fawebim.core.tool.Tool tool = com.maxlananas.fawebim.core.tool.Tools.create(type, ctx);
                    if (tool == null) {
                        throw CommandRegistry.error("Unknown tool '" + type + "'");
                    }
                    com.maxlananas.fawebim.core.tool.Tools.bind(ctx.session(), tool, ctx.actor(), ctx.arg(1, ""));
                    ctx.actor().message(Msg.success("Tool '" + type + "' bound to your held item"));
                };


        CommandRegistry.Entry e101 = registry.register("/superpickaxe", "/sp", "//sp");
        e101.description = "Super-pickaxe: single, area <radius>, recursive";
        e101.group = "tool";
        e101.arguments.add("[single|area|recursive|recur|off]");
        e101.arguments.add("[radius]");
        e101.handler = ctx -> {
                    String mode = ctx.arg(0, "area").toLowerCase(Locale.ROOT);
                    LocalSession session = ctx.session();
                    switch (mode) {
                        case "single" -> {
                            session.setSuperPickaxeEnabled(true);
                            session.setSuperPickaxeMode(0);
                        }
                        case "recursive", "recur" -> {
                            session.setSuperPickaxeEnabled(true);
                            session.setSuperPickaxeMode(2);
                        }
                        case "area" -> {
                            session.setSuperPickaxeEnabled(true);
                            session.setSuperPickaxeMode(1);
                            session.setSuperPickaxeRadius(ctx.intArg(1, 1));
                        }
                        case "off" -> session.setSuperPickaxeEnabled(false);
                        default -> throw CommandRegistry.error("Usage: /sp single|area <radius>|recursive|off");
                    }
                    ctx.actor().message(Msg.success("Super-pickaxe mode: " + mode));
                };


    }

    /** {@code /brush none} — unbinds the brush from the held item. */
    private void registerBrushNone() {
        CommandRegistry.Entry none = registry.registerUnlessPresent("/brush none", "/brush unbind");
        if (none == null) {
            return;
        }
        none.description = "Unbind the brush from your current item";
        none.group = "brush";
        none.requiresPlayer = true;
        none.handler = ctx -> {
            LocalSession session = ctx.session();
            com.maxlananas.fawebim.core.brush.BrushFactory.unbind(session);
            session.getBindings().remove("brush-command");
            String held = ctx.actor().heldItem();
            if (held != null && held.equals(session.getBindings().get("secondary-brush-item"))) {
                com.maxlananas.fawebim.core.brush.BrushFactory.unbindSecondary(session);
            }
            ctx.actor().message(Msg.success("Brush unbound"));
        };
    }

    /**
     * {@code /brush savebrush}, {@code /brush loadbrush} and {@code /brush
     * listbrush}: the presets that reload a brush without retyping its settings.
     */
    private void registerBrushPresets() {
        CommandRegistry.Entry save = registry.registerUnlessPresent("/brush savebrush", "/brush save");
        if (save != null) {
            save.description = "Save the current brush as a preset";
            save.group = "brush";
        save.requiresPlayer = true;
            save.arguments.add("name");
            // -g saves into the shared preset folder instead of the player's.
            save.booleanFlags.add("g");
            save.handler = ctx -> {
                java.nio.file.Path file;
                try {
                    file = com.maxlananas.fawebim.core.brush.BrushPresets.save(ctx.session(), ctx.arg(0));
                } catch (java.io.IOException e) {
                    throw CommandRegistry.error("Could not save the preset: " + e.getMessage());
                }
                if (file == null) {
                    throw CommandRegistry.error("No brush bound: use /brush <type> first");
                }
                ctx.actor().message(Msg.success("Brush preset saved as " + file.getFileName()));
            };
        }

        CommandRegistry.Entry load = registry.registerUnlessPresent("/brush loadbrush", "/brush load");
        if (load != null) {
            load.description = "Load a saved brush preset";
            load.group = "brush";
            load.arguments.add("name");
            load.handler = ctx -> {
                String line;
                try {
                    line = com.maxlananas.fawebim.core.brush.BrushPresets.load(ctx.arg(0));
                } catch (java.io.IOException e) {
                    throw CommandRegistry.error("Could not read the preset: " + e.getMessage());
                }
                if (line == null) {
                    throw CommandRegistry.error("No brush preset named '" + ctx.arg(0) + "'");
                }
                registry.dispatch(ctx.actor(), line);
            };
        }

        CommandRegistry.Entry list = registry.registerUnlessPresent("/brush listbrush", "/brush list");
        if (list != null) {
            list.description = "List the saved brush presets";
            list.group = "brush";
        list.requiresPlayer = true;
            list.valueFlags.add("p");
            list.arguments.add("[-p <page>]");
            list.handler = ctx -> {
                java.util.List<String> presets = com.maxlananas.fawebim.core.brush.BrushPresets.list();
                if (presets.isEmpty()) {
                    ctx.actor().message(Msg.info("No brush preset saved yet"));
                    return;
                }
                Page page = Page.of(ctx, presets.size(), 15);
                ctx.actor().message(Msg.info(page.header("Brush presets", presets.size())));
                for (String preset : presets.subList(page.from(), page.to())) {
                    ctx.actor().message(Msg.of("\u00a77 - \u00a7f" + preset));
                }
            };
        }
    }

    /**
     * Binds the brush a {@code /brush <name>} sub-command built, with the
     * arguments and the switches of its generated signature.
     */
    private static void bindBrush(Ctx ctx, String[] row) {
        LocalSession session = ctx.session();
        com.maxlananas.fawebim.core.brush.BrushParameters parameters =
                com.maxlananas.fawebim.core.brush.BrushParameters.bind(ctx,
                        row, com.maxlananas.fawebim.core.brush.BrushOptions.of(ctx));
        double radius = parameters.radius();
        if (radius < 0) {
            throw CommandRegistry.error("The brush radius must not be negative");
        }
        if (radius > session.getMaxBrushRadius()) {
            throw CommandRegistry.error("Maximum brush radius is " + session.getMaxBrushRadius());
        }
        com.maxlananas.fawebim.core.brush.Brush built =
                com.maxlananas.fawebim.core.brush.BrushFactory.create(parameters);
        if (built == null) {
            throw CommandRegistry.error("Brush '" + row[0] + "' could not be created");
        }
        com.maxlananas.fawebim.core.brush.BrushFactory.bind(session, built, ctx.actor());
        // Remembered so the preset commands can save and reload it.
        session.getBindings().put("brush-command", buildBrushLine(ctx));
        ctx.actor().message(Msg.success("Brush '" + row[0] + "' equipped (radius " + radius + ")"));
    }

    private static String buildBrushLine(Ctx ctx) {
        StringBuilder line = new StringBuilder("brush ").append(ctx.arg(0));
        for (int i = 1; i < ctx.args().size(); i++) {
            line.append(' ').append(ctx.arg(i));
        }
        return line.toString();
    }
}
