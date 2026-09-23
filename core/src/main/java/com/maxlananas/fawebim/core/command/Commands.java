package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Navigation;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.HeightMaps;
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
 * {@link Stubs}) and report their status, so the full command surface is always
 * present and {@code docs/COMMANDS.md} lists it completely.</p>
 */
public final class Commands {

    private final CommandRegistry registry;

    public Commands(CommandRegistry registry) {
        this.registry = registry;
    }

    // ------------------------------------------------------------------ helpers

    private void flush(Ctx ctx, EditSession session) {
        session.flushQueue();
        ctx.actor().message(session.summary());
    }

    private static int air() {
        return BlockState.registry().air();
    }

    /** Replaces every block in a region matching {@code mask} with {@code pattern}. */
    private int fill(EditSession session, Region region, Pattern pattern, Mask mask) {
        int changed = 0;
        for (BlockVector3 position : region) {
            if (mask != null && !mask.test(position)) {
                continue;
            }
            session.limiter().check(1);
            int state = pattern.apply(position.x(), position.y(), position.z());
            if (session.setBlock(position.x(), position.y(), position.z(), state)) {
                changed++;
            }
        }
        return changed;
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
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.actor().position() : ctx.blockVector(0);
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectPrimary(pos, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 1 set to ").append(Msg.value(pos)));
                };


        CommandRegistry.Entry e2 = registry.register("//pos2", "//p2");
        e2.description = "Set position 2 to your position or the given coordinates";
        e2.group = "selection";
        e2.arguments.add("[coordinates]");
        e2.handler = ctx -> {
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.actor().position() : ctx.blockVector(0);
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectSecondary(pos, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 2 set to ").append(Msg.value(pos)));
                };


        CommandRegistry.Entry e3 = registry.register("//hpos1");
        e3.description = "Set position 1 to the block you are looking at";
        e3.group = "selection";
        e3.requiresPlayer = true;
        e3.handler = ctx -> {
                    BlockVector3 target = ctx.world().getTargetBlock(ctx.actor(), 100);
                    ctx.session().getSelector(ctx.world()).selectPrimary(target,
                            com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 1 set to ").append(Msg.value(target)));
                };


        CommandRegistry.Entry e4 = registry.register("//hpos2");
        e4.description = "Set position 2 to the block you are looking at";
        e4.group = "selection";
        e4.requiresPlayer = true;
        e4.handler = ctx -> {
                    BlockVector3 target = ctx.world().getTargetBlock(ctx.actor(), 100);
                    ctx.session().getSelector(ctx.world()).selectSecondary(target,
                            com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 2 set to ").append(Msg.value(target)));
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
                    BlockVector3 primary = ctx.args().isEmpty() ? ctx.actor().position() : ctx.blockVector(0);
                    if (primary == null) {
                        throw CommandRegistry.error("Coordinates are required when the command is not run by a player");
                    }
                    BlockVector3 secondary = ctx.args().size() > 1 ? ctx.blockVector(1) : primary;
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectPrimary(primary, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    selector.selectSecondary(secondary, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 1 set to ").append(Msg.value(primary)));
                    ctx.actor().message(Msg.info("Position 2 set to ").append(Msg.value(secondary)));
                };


        CommandRegistry.Entry e6 = registry.register("//sel");
        e6.description = "Choose the selection type: cuboid, extend, poly, ellipsoid, sphere, cyl, convex";
        e6.group = "selection";
        e6.arguments.add("type");
        // -d remembers the selector as the default for new sessions.
        e6.booleanFlags.add("d");
        e6.handler = ctx -> {
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
                    ctx.actor().message(Msg.success("Selection type set to " + selector.getTypeName()));
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
                        ctx.actor().message(Msg.success((ctx.hasFlag("n") ? "Navigation wand given: " : "Wand given: ")
                                + item));
                    } else {
                        ctx.actor().message(Msg.error("Could not give you the wand"));
                    }
                };


        CommandRegistry.Entry e8 = registry.register("//toggleeditwand");
        e8.description = "Toggle the wand's function (only the commands remain active)";
        e8.group = "selection";
        e8.handler = ctx -> {
                    LocalSession session = ctx.session();
                    session.setFastMode(!session.isFastMode());
                    ctx.actor().message(Msg.info("Edit wand is now "
                            + (session.isFastMode() ? "enabled" : "disabled")));
                };


        CommandRegistry.Entry e9 = registry.register("//toggleplace");
        e9.description = "Switch between placing at position 1 or at your position";
        e9.group = "selection";
        e9.handler = ctx -> {
                    ctx.session().togglePlace();
                    ctx.actor().message(Msg.info("Placing at "
                            + (ctx.session().shouldPlaceAtPos1() ? "position 1" : "your position")));
                };


        CommandRegistry.Entry e10 = registry.register("//drawsel");
        e10.description = "Draw the selection outline (uses particles, no client mod needed)";
        e10.group = "selection";
        e10.handler = ctx -> {
                    LocalSession session = ctx.session();
                    session.setDrawSelection(!session.isDrawSelection());
                    ctx.actor().updateSelectionOutline();
                    ctx.actor().message(Msg.info("Selection drawing "
                            + (session.isDrawSelection() ? "enabled" : "disabled")));
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


        CommandRegistry.Entry e14 = registry.register("//expand");
        e14.description = "Expand the selection area";
        e14.group = "selection";
        e14.requiresSelection = true;
        e14.booleanFlags.add("v");
        e14.booleanFlags.add("h");
        e14.arguments.add("amount");
        e14.arguments.add("[direction]");
        e14.handler = ctx -> {
                    Region region = ctx.selection();
                    String amount = ctx.arg(0);
                    boolean vertical = amount.equalsIgnoreCase("vert") || amount.equalsIgnoreCase("vertical");
                    BlockVector3 change;
                    if (vertical) {
                        int minY = ctx.world().minY();
                        int maxY = ctx.world().maxY();
                        change = new BlockVector3(0, maxY - region.getMaximumY(), 0);
                        region.expand(change);
                        change = new BlockVector3(0, minY - region.getMinimumY(), 0);
                        region.expand(change);
                    } else {
                        int value = Integer.parseInt(amount);
                        String direction = ctx.arg(1, ctx.hasFlag("v") ? "up" : ctx.hasFlag("h") ? "me" : "me");
                        change = directionVector(ctx, direction, value);
                        region.expand(change);
                    }
                    ctx.actor().message(Msg.success("Region expanded: " + region.describe()));
                };


        CommandRegistry.Entry e15 = registry.register("//contract");
        e15.description = "Contract the selection area";
        e15.group = "selection";
        e15.requiresSelection = true;
        e15.arguments.add("amount");
        e15.arguments.add("[direction]");
        e15.handler = ctx -> {
                    Region region = ctx.selection();
                    int value = ctx.intArg(0);
                    String direction = ctx.arg(1, "me");
                    region.contract(directionVector(ctx, direction, value));
                    ctx.actor().message(Msg.success("Region contracted: " + region.describe()));
                };


        CommandRegistry.Entry e16 = registry.register("//shift");
        e16.description = "Shift the selection area";
        e16.group = "selection";
        e16.requiresSelection = true;
        e16.arguments.add("amount");
        e16.arguments.add("[direction]");
        e16.handler = ctx -> {
                    Region region = ctx.selection();
                    int value = ctx.intArg(0);
                    String direction = ctx.arg(1, "me");
                    region.shift(directionVector(ctx, direction, value));
                    ctx.actor().message(Msg.success("Region shifted: " + region.describe()));
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
                    flush(ctx, session);
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
                    flush(ctx, session);
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
                    flush(ctx, session);
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
                    for (int y = min.y(); y <= max.y(); y++) {
                        for (int x = min.x(); x <= max.x(); x++) {
                            session.setBlock(x, y, min.z(), pattern.apply(x, y, min.z()));
                            session.setBlock(x, y, max.z(), pattern.apply(x, y, max.z()));
                        }
                        for (int z = min.z(); z <= max.z(); z++) {
                            session.setBlock(min.x(), y, z, pattern.apply(min.x(), y, z));
                            session.setBlock(max.x(), y, z, pattern.apply(max.x(), y, z));
                        }
                    }
                    flush(ctx, session);
                };


        CommandRegistry.Entry e23 = registry.register("//faces");
        e23.description = "Build the faces of the selection";
        e23.group = "region";
        e23.requiresSelection = true;
        e23.arguments.add("pattern");
        e23.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Region region = ctx.selection();
                    BlockVector3 min = region.getMinimumPoint();
                    BlockVector3 max = region.getMaximumPoint();
                    for (int x = min.x(); x <= max.x(); x++) {
                        for (int z = min.z(); z <= max.z(); z++) {
                            session.setBlock(x, min.y(), z, pattern.apply(x, min.y(), z));
                            session.setBlock(x, max.y(), z, pattern.apply(x, max.y(), z));
                        }
                    }
                    for (int y = min.y(); y <= max.y(); y++) {
                        for (int x = min.x(); x <= max.x(); x++) {
                            session.setBlock(x, y, min.z(), pattern.apply(x, y, min.z()));
                            session.setBlock(x, y, max.z(), pattern.apply(x, y, max.z()));
                        }
                        for (int z = min.z(); z <= max.z(); z++) {
                            session.setBlock(min.x(), y, z, pattern.apply(min.x(), y, z));
                            session.setBlock(max.x(), y, z, pattern.apply(max.x(), y, z));
                        }
                    }
                    flush(ctx, session);
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
                    flush(ctx, session);
                };


        CommandRegistry.Entry e25 = registry.register("//hollow");
        e25.description = "Hollow out the selection";
        e25.group = "region";
        e25.requiresSelection = true;
        e25.booleanFlags.add("h");
        e25.booleanFlags.add("s");
        e25.valueFlags.add("m");
        e25.arguments.add("[thickness]");
        e25.arguments.add("[-m <mask>]");
        e25.arguments.add("[pattern]");
        e25.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int thickness = ctx.args().isEmpty() ? (ctx.hasFlag("h") ? 1 : 0) : ctx.intArg(0);
                    Pattern pattern = ctx.args().size() > 1 ? Parsers.pattern(ctx.joined(1), ctx) : null;
                    // -m hollows only the blocks the mask selects.
                    Mask hollowMask = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    Region region = ctx.selection();
                    int depth = Math.max(1, thickness);
                    for (BlockVector3 position : region) {
                        int distance = distanceToEdge(region, position);
                        if (distance >= depth) {
                            continue;
                        }
                        if (hollowMask != null && !hollowMask.test(position.x(), position.y(), position.z())) {
                            continue;
                        }
                        if (pattern != null) {
                            session.setBlock(position.x(), position.y(), position.z(),
                                    pattern.apply(position.x(), position.y(), position.z()));
                        } else {
                            session.setBlock(position.x(), position.y(), position.z(), air());
                        }
                    }
                    flush(ctx, session);
                };


        CommandRegistry.Entry e26 = registry.register("//outline", "//outline-remove");
        e26.description = "Build a hollow outline";
        e26.group = "region";
        e26.requiresSelection = true;
        e26.arguments.add("pattern");
        e26.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Region region = ctx.selection();
                    for (BlockVector3 position : region) {
                        if (distanceToEdge(region, position) < 1) {
                            session.setBlock(position.x(), position.y(), position.z(),
                                    pattern.apply(position.x(), position.y(), position.z()));
                        } else {
                            session.setBlock(position.x(), position.y(), position.z(), air());
                        }
                    }
                    flush(ctx, session);
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
                    ctx.actor().message(Msg.success("Smoothed " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
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
                    flush(ctx, session);
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
                    flush(ctx, session);
                };


        CommandRegistry.Entry e30 = registry.register("//fill", "//fillr");
        e30.description = "Fill a hole with a pattern (flood fill)";
        e30.group = "region";
        e30.requiresSelection = true;
        e30.booleanFlags.add("r");
        e30.booleanFlags.add("h");
        e30.arguments.add("pattern");
        e30.arguments.add("[radius]");
        e30.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int radius = ctx.intArg(1, ctx.world().maxY());
                    BlockVector3 start = ctx.actor().position() != null
                            ? ctx.actor().position()
                            : ctx.selection().getMinimumPoint();
                    int changed = com.maxlananas.fawebim.core.function.Operations.floodFill(ctx.world(), session,
                            start, pattern, radius, ctx.hasFlag("h"));
                    ctx.actor().message(Msg.success("Filled " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
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
                    BlockVector3 start = ctx.actor().position() != null
                            ? ctx.actor().position() : ctx.selection().getMinimumPoint();
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
                    ctx.actor().message(Msg.success("Drained " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
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
                    try {
                        for (BlockVector2 chunk : region.getChunks()) {
                            ctx.world().loadChunk(chunk.x(), chunk.z());
                            ctx.world().regenerateChunk(chunk.x(), chunk.z(),
                                    new com.maxlananas.fawebim.core.world.RegenOptions()
                                            .setRegenBiomes(ctx.hasFlag("b") || biomeId >= 0
                                                    || settings.regenerateBiomes)
                                            .setSeed(seed));
                        }
                    } finally {
                        ctx.session().setMask(previousMask);
                    }
                    if (biomeId >= 0) {
                        EditSession editSession = ctx.editSession();
                        for (BlockVector3 position : region) {
                            editSession.setBiome(position.x(), position.y(), position.z(), biomeId);
                        }
                        editSession.flushQueue();
                    }
                    ctx.actor().message(Msg.success("Regenerated " + region.getChunks().size() + " chunk(s)"));
                };


        CommandRegistry.Entry e33 = registry.register("//removeabove");
        e33.description = "Remove blocks above a height";
        e33.group = "region";
        e33.arguments.add("[size]");
        e33.arguments.add("[height]");
        e33.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockVector3 origin = ctx.actor().position() != null ? ctx.actor().position() : BlockVector3.ZERO;
                    int size = ctx.intArg(0, 0);
                    int height = ctx.args().size() > 1 ? ctx.intArg(1) : origin.y() + 1;
                    for (int x = origin.x() - size; x <= origin.x() + size; x++) {
                        for (int z = origin.z() - size; z <= origin.z() + size; z++) {
                            for (int y = height; y <= ctx.world().maxY(); y++) {
                                session.setBlock(x, y, z, air());
                            }
                        }
                    }
                    flush(ctx, session);
                };


        CommandRegistry.Entry e34 = registry.register("//removebelow");
        e34.description = "Remove blocks below a height";
        e34.group = "region";
        e34.arguments.add("[size]");
        e34.arguments.add("[height]");
        e34.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockVector3 origin = ctx.actor().position() != null ? ctx.actor().position() : BlockVector3.ZERO;
                    int size = ctx.intArg(0, 0);
                    int height = ctx.args().size() > 1 ? ctx.intArg(1) : origin.y() - 1;
                    for (int x = origin.x() - size; x <= origin.x() + size; x++) {
                        for (int z = origin.z() - size; z <= origin.z() + size; z++) {
                            for (int y = ctx.world().minY(); y <= height; y++) {
                                session.setBlock(x, y, z, air());
                            }
                        }
                    }
                    flush(ctx, session);
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
                    BlockVector3 origin = ctx.actor().position();
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
                    ctx.actor().message(Msg.success("Removed " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
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
                    BlockVector3 origin = ctx.actor().position();
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
                    ctx.actor().message(Msg.success("Replaced " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e37 = registry.register("//snow");
        e37.description = "Simulate snow on the terrain";
        e37.group = "region";
        e37.requiresSelection = true;
        // -s stacks a snow layer per click instead of laying a full block.
        e37.booleanFlags.add("s");
        e37.arguments.add("[pattern]");
        e37.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    boolean stack = ctx.hasFlag("s");
                    int snow = BlockState.registry().defaultState(
                            stack ? "minecraft:snow" : "minecraft:snow_block");
                    Region region = ctx.selection();
                    int changed = 0;
                    for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                        for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                            for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                                if (!BlockState.registry().isAirLike(ctx.world().getBlock(x, y, z))) {
                                    if (session.setBlock(x, y + 1, z, snow)) {
                                        changed++;
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    ctx.actor().message(Msg.success("Snowed " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e38 = registry.register("//thaw");
        e38.description = "Thaw snow and ice in the region";
        e38.group = "region";
        e38.requiresSelection = true;
        e38.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int changed = 0;
                    BlockStateRegistry blockRegistry = BlockState.registry();
                    for (BlockVector3 position : ctx.selection()) {
                        String name = blockRegistry.name(ctx.world().getBlock(position.x(), position.y(), position.z()));
                        if (name.contains("snow") || name.contains("ice")) {
                            if (session.setBlock(position.x(), position.y(), position.z(), air())) {
                                changed++;
                            }
                        }
                    }
                    ctx.actor().message(Msg.success("Thawed " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e39 = registry.register("//green");
        e39.description = "Turn dirt into grass";
        e39.group = "region";
        e39.requiresSelection = true;
        // -f also turns coarse dirt into grass, which FAWE keeps out by default.
        e39.booleanFlags.add("f");
        e39.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockStateRegistry blockRegistry = BlockState.registry();
                    Mask dirt = Parsers.mask(ctx.hasFlag("f") ? "minecraft:dirt,minecraft:coarse_dirt"
                            : "minecraft:dirt", ctx);
                    Pattern grass = new Patterns.Single(blockRegistry.defaultState("minecraft:grass_block"));
                    int changed = fill(session, ctx.selection(), grass, dirt);
                    ctx.actor().message(Msg.success("Greened " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e40 = registry.register("//extinguish", "//ex");
        e40.description = "Extinguish fires in the region";
        e40.group = "region";
        e40.requiresSelection = true;
        e40.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = 0;
                    for (BlockVector3 position : ctx.selection()) {
                        String name = BlockState.registry()
                                .name(ctx.world().getBlock(position.x(), position.y(), position.z()));
                        if (name.contains("fire") || name.contains("lava") || name.contains("magma")) {
                            if (session.setBlock(position.x(), position.y(), position.z(), air())) {
                                changed++;
                            }
                        }
                    }
                    ctx.actor().message(Msg.success("Extinguished " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
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
                    ctx.actor().message(Msg.success("Fixed " + Msg.formatNumber(changed) + " water block(s)"));
                    flush(ctx, session);
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
                    ctx.actor().message(Msg.success("Fixed " + Msg.formatNumber(changed) + " lava block(s)"));
                    flush(ctx, session);
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
                    for (BlockVector3 position : region) {
                        session.setBlock(position.x(), position.y(), position.z(), air(), false);
                    }
                    // Paste at the offset.
                    BlockVector3 origin = clipboard.getOrigin();
                    for (BlockVector3 position : clipboard.positions()) {
                        int state = clipboard.getBlock(position);
                        int x = position.x() - origin.x() + region.getMinimumPoint().x() + offset.x();
                        int y = position.y() - origin.y() + region.getMinimumPoint().y() + offset.y();
                        int z = position.z() - origin.z() + region.getMinimumPoint().z() + offset.z();
                        if (BlockState.registry().isAirLike(state)) {
                            if (ctx.hasFlag("a")) {
                                // -a keeps the blocks the copy would erase.
                                continue;
                            }
                            if (pattern != null) {
                                state = pattern.apply(x, y, z);
                            }
                        }
                        session.setBlock(x, y, z, state);
                    }
                    // -s moves the selection along with the blocks.
                    if (ctx.hasFlag("s")) {
                        region.shift(offset);
                    }
                    flush(ctx, session);
                    ctx.actor().message(Msg.success("Moved the selection by " + amount + " block(s) towards "
                            + direction.toLowerCase(Locale.ROOT)));
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
                    flush(ctx, session);
                };

    }

    /** The origin a rotation transform turns around. */
    private static BlockVector3 clipboardOriginOf(com.maxlananas.fawebim.core.session.ClipboardHolder holder) {
        return holder.getClipboard().getOrigin();
    }

    private int distanceToEdge(Region region, BlockVector3 position) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return Math.min(Math.min(position.x() - min.x(), max.x() - position.x()),
                Math.min(Math.min(position.y() - min.y(), max.y() - position.y()),
                        Math.min(position.z() - min.z(), max.z() - position.z())));
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
                    ctx.actor().message(Msg.success("Drew " + changed + " block(s)"));
                    flush(ctx, session);
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
                    ctx.actor().message(Msg.success("Drew " + changed + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e47 = registry.register("//generate", "//gen", "//g");
        e47.description = "Generate a shape from an expression or noise pattern";
        e47.group = "generation";
        e47.requiresSelection = true;
        e47.booleanFlags.add("h");
        e47.booleanFlags.add("o");
        e47.booleanFlags.add("r");
        // -c evaluates the expression around the centre of the selection.
        e47.booleanFlags.add("c");
        e47.arguments.add("pattern");
        e47.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    String input = ctx.joined(0);
                    Pattern pattern;
                    if (input.startsWith("=")) {
                        pattern = new Patterns.ExpressionPattern(input.substring(1));
                    } else {
                        pattern = Parsers.pattern(input, ctx);
                    }
                    int changed = 0;
                    BlockVector3 min = ctx.selection().getMinimumPoint();
                    BlockVector3 max = ctx.selection().getMaximumPoint();
                    for (int y = min.y(); y <= max.y(); y++) {
                        for (int z = min.z(); z <= max.z(); z++) {
                            for (int x = min.x(); x <= max.x(); x++) {
                                int state = pattern.apply(x, y, z);
                                if (!ctx.hasFlag("h") || !BlockState.registry().isAirLike(state)) {
                                    if (session.setBlock(x, y, z, state)) {
                                        changed++;
                                    }
                                }
                            }
                        }
                    }
                    ctx.actor().message(Msg.success("Generated " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
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
                        BlockVector3 placement = ctx.actor().position();
                        if (placement != null) {
                            originX = placement.x();
                            originZ = placement.z();
                        }
                    }
                    int changed = com.maxlananas.fawebim.core.function.Operations.deform(ctx.world(), session,
                            deformRegion, expression, originX, 0, originZ);
                    ctx.actor().message(Msg.success("Deformed " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e49 = registry.register("//flora", "//forest", "//forestgen");
        e49.description = "Generate flora/forest in the selection";
        e49.group = "generation";
        e49.requiresSelection = true;
        e49.booleanFlags.add("d");
        e49.booleanFlags.add("a");
        e49.booleanFlags.add("t");
        e49.arguments.add("[density]");
        e49.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    double density = ctx.doubleArg(0, 5) / 100.0;
                    int changed = com.maxlananas.fawebim.core.function.Operations.flora(ctx.world(), session,
                            ctx.selection(), density);
                    ctx.actor().message(Msg.success("Generated " + changed + " plant(s)"));
                    flush(ctx, session);
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
                    ctx.actor().message(Msg.success("Generated " + changed + " pumpkin(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e51 = registry.register("//tree");
        e51.description = "Create a tree at your position";
        e51.group = "generation";
        e51.requiresPlayer = true;
        e51.booleanFlags.add("t");
        e51.arguments.add("[type]");
        e51.handler = ctx -> {
                    String type = ctx.arg(0, "tree");
                    boolean ok = ctx.world().generateTree(ctx.actor().position(), type, new java.util.Random());
                    ctx.actor().message(ok ? Msg.success("Tree planted at ").append(Msg.value(ctx.actor().position()))
                            : Msg.error("Unknown tree type '" + type + "'"));
                };


        CommandRegistry.Entry e52 = registry.register("//deltree");
        e52.description = "Remove the tree you are looking at";
        e52.group = "generation";
        e52.requiresPlayer = true;
        e52.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockVector3 target = ctx.world().getTargetBlock(ctx.actor(), 100);
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeTree(ctx.world(), session, target);
                    ctx.actor().message(Msg.success("Removed " + changed + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e53 = registry.register("//ore", "//ores");
        e53.description = "Generate ores in the selection";
        e53.group = "generation";
        e53.requiresSelection = true;
        // -b and -d pick how the ores are written below the deepslate line.
        e53.booleanFlags.add("b");
        e53.booleanFlags.add("d");
        e53.arguments.add("pattern");
        e53.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern ore = Parsers.pattern(ctx.arg(0), ctx);
                    com.maxlananas.fawebim.core.function.Operations.OreDeepslate deepslate =
                            com.maxlananas.fawebim.core.function.Operations.OreDeepslate.of(
                                    ctx.hasFlag("b"), ctx.hasFlag("d"));
                    int changed = com.maxlananas.fawebim.core.function.Operations.ore(ctx.world(), session,
                            ctx.selection(), ore, new java.util.Random(), deepslate);
                    ctx.actor().message(Msg.success("Generated " + changed + " ore block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e54 = registry.register("//caves");
        e54.description = "Generate cave systems in the selection";
        e54.group = "generation";
        e54.requiresSelection = true;
        e54.arguments.add("[frequency]");
        e54.arguments.add("[rarity]");
        e54.arguments.add("[size]");
        e54.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = com.maxlananas.fawebim.core.function.Operations.caves(ctx.world(), session,
                            ctx.selection(), new java.util.Random(),
                            ctx.intArg(0, 40), ctx.doubleArg(1, 0.5), ctx.intArg(2, 8));
                    ctx.actor().message(Msg.success("Generated " + Msg.formatNumber(changed) + " cave block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e55 = registry.register("//fall");
        e55.description = "Make blocks fall";
        e55.group = "generation";
        e55.requiresSelection = true;
        // -m keeps the blocks inside the vertical bounds of the selection.
        e55.booleanFlags.add("m");
        e55.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = com.maxlananas.fawebim.core.function.Operations.fall(ctx.world(), session,
                            ctx.selection(), ctx.hasFlag("m"));
                    ctx.actor().message(Msg.success("Moved " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };

    }

    private void registerShapes() {
        // //sphere, //cyl, //pyramid, //cone and their hollow variants share one implementation.
        Object[][] shapes = {
                {"//sphere", "sphere", false},
                {"//hsphere", "sphere", true},
                {"//cyl", "cyl", false},
                {"//hcyl", "cyl", true},
        };
        for (Object[] shape : shapes) {
            boolean hollow = (boolean) shape[2];
            String kind = (String) shape[1];
                    CommandRegistry.Entry e56 = registry.register((String) shape[0]);
        e56.description = "Create a " + kind + " at your position";
        e56.group = "generation";
        e56.requiresPlayer = true;
        e56.booleanFlags.add("h");
        // -r raises the bottom of the sphere to the placement position.
        e56.booleanFlags.add("r");
        e56.arguments.add("pattern");
        e56.arguments.add("radius");
        e56.arguments.add("[height]");
        e56.handler = ctx -> {
                        EditSession session = ctx.editSession();
                        Masks.ExtentHolder.set(session);
                        Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                        double radius = ctx.doubleArg(1);
                        double height = ctx.doubleArg(2, radius * 2);
                        BlockVector3 origin = ctx.actor().position();
                        boolean hollowShape = hollow || ctx.hasFlag("h");
                        boolean raised = ctx.hasFlag("r");
                        int changed = kind.equals("sphere")
                                ? com.maxlananas.fawebim.core.function.Operations.sphere(session, origin, radius, pattern,
                                hollowShape, raised)
                                : com.maxlananas.fawebim.core.function.Operations.cylinder(session, origin,
                                (int) Math.floor(radius), (int) height, pattern, hollowShape);
                        ctx.actor().message(Msg.success("Created shape: " + changed + " block(s)"));
                        flush(ctx, session);
                    };

        }

        CommandRegistry.Entry e57 = registry.register("//pyramid", "//hpyramid");
        e57.description = "Create a pyramid at your position";
        e57.group = "generation";
        e57.requiresPlayer = true;
        e57.booleanFlags.add("h");
        e57.arguments.add("pattern");
        e57.arguments.add("size");
        e57.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int size = ctx.intArg(1);
                    boolean hollowShape = ctx.entry().name.equals("//hpyramid") || ctx.hasFlag("h");
                    int changed = com.maxlananas.fawebim.core.function.Operations.pyramid(session, ctx.actor().position(),
                            size, pattern, hollowShape);
                    ctx.actor().message(Msg.success("Created pyramid: " + changed + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e58 = registry.register("//cone");
        e58.description = "Create a cone at your position";
        e58.group = "generation";
        e58.requiresPlayer = true;
        e58.booleanFlags.add("h");
        e58.arguments.add("pattern");
        e58.arguments.add("radius");
        e58.arguments.add("[height]");
        e58.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double radius = ctx.doubleArg(1);
                    double height = ctx.doubleArg(2, radius * 2);
                    int changed = com.maxlananas.fawebim.core.function.Operations.cone(session, ctx.actor().position(),
                            radius, height, pattern, ctx.hasFlag("h"));
                    ctx.actor().message(Msg.success("Created cone: " + changed + " block(s)"));
                    flush(ctx, session);
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
                    ctx.actor().message(Msg.success("Copied " + Msg.formatNumber(clipboard.volume())
                            + " block(s) (" + clipboard.entities().size() + " entities"
                            + (clipboard.hasBiomes() ? ", biomes" : "") + ")"));
                };


        CommandRegistry.Entry e60 = registry.register("//cut");
        e60.description = "Cut the selection to your clipboard";
        e60.group = "clipboard";
        e60.requiresSelection = true;
        e60.booleanFlags.add("e");
        e60.booleanFlags.add("r");
        e60.booleanFlags.add("b");
        e60.valueFlags.add("m");
        e60.arguments.add("[-m <mask>]");
        e60.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Mask exclude = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    BlockArrayClipboard clipboard = com.maxlananas.fawebim.core.clipboard.Clipboards.copy(ctx.world(),
                            ctx.selection(), session, ctx.hasFlag("e"), ctx.hasFlag("b"), exclude, false);
                    ctx.session().setClipboard(clipboard);
                    for (BlockVector3 position : ctx.selection()) {
                        session.setBlock(position.x(), position.y(), position.z(), air());
                    }
                    ctx.actor().message(Msg.success("Cut " + Msg.formatNumber(clipboard.volume()) + " block(s)"));
                    flush(ctx, session);
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
                            ? (ctx.session().shouldPlaceAtPos1()
                            ? ctx.session().getSelector(ctx.world()).getRegion().getMinimumPoint()
                            : ctx.actor().position())
                            : ctx.blockVector(0);
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
                    ctx.actor().message(Msg.success("Pasted " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e62 = registry.register("//rotate");
        e62.description = "Rotate the clipboard";
        e62.group = "clipboard";
        e62.arguments.add("angle");
        e62.arguments.add("[direction]");
        e62.handler = ctx -> {
                    if (!ctx.session().hasClipboard()) {
                        throw CommandRegistry.error("No clipboard");
                    }
                    double angle = ctx.doubleArg(0);
                    var holder = ctx.session().getClipboard();
                    var origin = holder.getClipboard().getOrigin();
                    holder.setTransform(holder.getTransform().combine(
                            com.maxlananas.fawebim.core.transform.Transforms.rotate(origin, angle)));
                    ctx.actor().message(Msg.success("Clipboard rotated by " + angle + " degrees"));
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
                    ctx.actor().message(Msg.success("Clipboard flipped on " + axis));
                };


        CommandRegistry.Entry e64 = registry.register("//clearclipboard");
        e64.description = "Clear your clipboard";
        e64.group = "clipboard";
        e64.handler = ctx -> {
                    ctx.session().setClipboard(new BlockArrayClipboard(BlockVector3.ZERO));
                    ctx.actor().message(Msg.success("Clipboard cleared"));
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
                            ctx.actor().message(Msg.info("Schematics (" + names.size() + ", page " + page.number()
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
                            ctx.actor().message(Msg.success("Clipboard unloaded"));
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
                            ctx.actor().message(Msg.success("Clipboard cleared"));
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
        CommandRegistry.Entry e66 = registry.register("//undo", "//u");
        e66.description = "Undo your last operation";
        e66.group = "history";
        e66.arguments.add("[number]");
        e66.handler = ctx -> {
                    int steps = ctx.intArg(0, 1);
                    int undone = 0;
                    for (int i = 0; i < steps; i++) {
                        var record = ctx.session().getHistory().undo();
                        if (record == null) {
                            break;
                        }
                        EditSession session = new EditSession(ctx.world(), ctx.session(), "undo", false);
                        for (var sets : record.changes().values()) {
                            for (var set : sets) {
                                session.applyChangeSet(set, true);
                            }
                        }
                        for (var sets : record.biomeChanges().values()) {
                            for (var set : sets) {
                                session.applyBiomeChangeSet(set, true);
                            }
                        }
                        undone += record.changeCount() + record.biomeChangeCount();
                        flush(ctx, session);
                    }
                    if (undone == 0) {
                        ctx.actor().message(Msg.error("Nothing to undo"));
                    } else {
                        ctx.actor().message(Msg.success("Undid " + Msg.formatNumber(undone) + " block change(s)"));
                    }
                };


        CommandRegistry.Entry e67 = registry.register("//redo", "//r");
        e67.description = "Redo your last undone operation";
        e67.group = "history";
        e67.arguments.add("[number]");
        e67.handler = ctx -> {
                    int steps = ctx.intArg(0, 1);
                    int redone = 0;
                    for (int i = 0; i < steps; i++) {
                        var record = ctx.session().getHistory().redo();
                        if (record == null) {
                            break;
                        }
                        EditSession session = new EditSession(ctx.world(), ctx.session(), "redo", false);
                        for (var sets : record.changes().values()) {
                            for (var set : sets) {
                                session.applyChangeSet(set, false);
                            }
                        }
                        for (var sets : record.biomeChanges().values()) {
                            for (var set : sets) {
                                session.applyBiomeChangeSet(set, false);
                            }
                        }
                        redone += record.changeCount();
                        flush(ctx, session);
                    }
                    if (redone == 0) {
                        ctx.actor().message(Msg.error("Nothing to redo"));
                    } else {
                        ctx.actor().message(Msg.success("Redid " + Msg.formatNumber(redone) + " block change(s)"));
                    }
                };


        CommandRegistry.Entry e68 = registry.register("//clearhistory");
        e68.description = "Clear your history";
        e68.group = "history";
        e68.handler = ctx -> {
                    ctx.session().getHistory().clear();
                    ctx.actor().message(Msg.success("History cleared"));
                };

    }

    // -------------------------------------------------------------------- biome

    private void registerBiome() {
        CommandRegistry.Entry e69 = registry.register("//biome", "//setbiome");
        e69.description = "Set the biome in the selection";
        e69.group = "biome";
        e69.requiresSelection = true;
        // -p changes the biome of the block the player stands in only.
        e69.booleanFlags.add("p");
        e69.arguments.add("biome");
        e69.handler = ctx -> {
                    if (ctx.hasFlag("p")) {
                        int biomeId = Parsers.biome(ctx.arg(0));
                        BlockVector3 pos = ctx.actor().position();
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
        e71.requiresPlayer = true;
        // -t reads the biome of the block the player looks at, -p the one the
        // player stands in.
        e71.booleanFlags.add("t");
        e71.booleanFlags.add("p");
        e71.handler = ctx -> {
                    BlockVector3 pos = ctx.hasFlag("t")
                            ? ctx.world().getTargetBlock(ctx.actor(), 100) : ctx.actor().position();
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
                    BlockVector3 pos = ctx.actor().position();
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
                    ctx.actor().message(Msg.success("Deleted " + count + " chunk(s)"
                            + (skipped > 0 ? ", kept " + skipped + " recently changed" : "")));
                };


        CommandRegistry.Entry e75 = registry.register("//chunk");
        e75.description = "Select the chunk you are standing in";
        e75.group = "chunk";
        e75.requiresPlayer = true;
        // -c reads the argument as chunk coordinates, -s expands the current
        // selection to whole chunks instead of replacing it.
        e75.booleanFlags.add("c");
        e75.booleanFlags.add("s");
        e75.arguments.add("[coordinates]");
        e75.handler = ctx -> {
                    BlockVector3 pos = ctx.actor().position();
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
                            ? ctx.world().getTargetBlock(ctx.actor(), 300)
                            : ctx.parseBlockVector(ctx.joined(0));
                    if (target == null) {
                        throw CommandRegistry.error("No block in sight");
                    }
                    Navigation.setOnGround(ctx.actor(), target);
                    ctx.actor().message(Msg.success("Teleported to " + target));
                };


        CommandRegistry.Entry e77 = registry.register("//thru");
        e77.description = "Pass through walls";
        e77.group = "navigation";
        e77.requiresPlayer = true;
        e77.handler = ctx -> {
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
                    if (!Navigation.findFreePosition(ctx.actor())) {
                        throw CommandRegistry.error("Could not find a free spot");
                    }
                    ctx.actor().message(Msg.success("Moved you to a free spot"));
                };


        CommandRegistry.Entry e79 = registry.register("//ascend", "//asc");
        e79.description = "Go up a floor";
        e79.group = "navigation";
        e79.requiresPlayer = true;
        e79.arguments.add("[levels]");
        e79.handler = ctx -> {
                    int levels = ctx.args().isEmpty() ? 1 : Math.max(1, ctx.intArg(0));
                    int moved = 0;
                    while (moved < levels && Navigation.ascendLevel(ctx.actor())) {
                        ++moved;
                    }
                    if (moved == 0) {
                        throw CommandRegistry.error("You would hit something above you");
                    }
                    ctx.actor().message(Msg.success("Ascended " + moved + " level(s)"));
                };


        CommandRegistry.Entry e79b = registry.register("//descend", "//desc");
        e79b.description = "Go down a floor";
        e79b.group = "navigation";
        e79b.requiresPlayer = true;
        e79b.arguments.add("[levels]");
        e79b.handler = ctx -> {
                    int levels = ctx.args().isEmpty() ? 1 : Math.max(1, ctx.intArg(0));
                    int moved = 0;
                    while (moved < levels && Navigation.descendLevel(ctx.actor())) {
                        ++moved;
                    }
                    if (moved == 0) {
                        throw CommandRegistry.error("You would hit something below you");
                    }
                    ctx.actor().message(Msg.success("Descended " + moved + " level(s)"));
                };


        CommandRegistry.Entry e79c = registry.register("//ceil", "//ceiling");
        e79c.description = "Go to the ceiling";
        e79c.group = "navigation";
        e79c.requiresPlayer = true;
        e79c.arguments.add("[clearance]");
        e79c.booleanFlags.add("f");
        e79c.booleanFlags.add("g");
        e79c.handler = ctx -> {
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
        e81.description = "Toggle FAWE's fast mode for your session";
        e81.group = "utility";
        e81.handler = ctx -> {
                    LocalSession session = ctx.session();
                    session.setFastMode(!session.isFastMode());
                    ctx.actor().message(Msg.info("Fast mode "
                            + (session.isFastMode() ? "§aenabled" : "§cdisabled")));
                };


        CommandRegistry.Entry e82 = registry.register("/perf", "//perf");
        e82.description = "Show performance information";
        e82.group = "utility";
        e82.booleanFlags.add("h");
        e82.handler = ctx -> {
                    Runtime runtime = Runtime.getRuntime();
                    long used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
                    ctx.actor().message(Msg.keyValue("Threads", Thread.activeCount()));
                    ctx.actor().message(Msg.keyValue("Memory", used + " MB used of "
                            + runtime.totalMemory() / 1024 / 1024 + " MB"));
                    ctx.actor().message(Msg.keyValue("History", ctx.session().getHistory().size()
                            + " record(s), " + Msg.formatNumber(ctx.session().getHistory().totalChanges())
                            + " change(s)"));
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


        CommandRegistry.Entry e86 = registry.register("/gmask", "//gmask", "/smask", "//smask");
        e86.description = "Set the global mask (/smask = source mask on overwrite)";
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
        e89.handler = ctx -> ctx.actor().message(Msg.info( "Masks: #air #existing #solid #liquid #fullcube #wall #surface #angle #surfaceangle #roc #beside " + "#extrema #xaxis #yaxis #zaxis #true #false #exposed #biome #region #dregion #offset " + "#simplex #clipboard # =expr ! & ,"));


        CommandRegistry.Entry e90 = registry.register("//patterns");
        e90.description = "List the available patterns";
        e90.group = "utility";
        e90.handler = ctx -> ctx.actor().message(Msg.info( "Patterns: block, 25%block, #clipboard #copy #existing #biome #offset #spread #solidspread " + "#surfacespread #l/#linear #l3d #l2d #color #lighten #darken #saturate #desaturate " + "#swaptype #simplex ##tag =expr ^"));


        CommandRegistry.Entry e91 = registry.register("//transforms");
        e91.description = "List the available transforms";
        e91.group = "utility";
        e91.handler = ctx -> ctx.actor().message(Msg.info( "Transforms: rotate <angle> [axis], flip [direction], scale <factor>, offset <x> <y> <z>"));


        CommandRegistry.Entry e92 = registry.register("//brushes");
        e92.description = "List the available brushes";
        e92.group = "utility";
        e92.handler = ctx -> ctx.actor().message(Msg.info( "Brushes: sphere ball smooth blendball flatten height raise lower layer line spline catenary " + "scatter shatter splatter rock blob pull stencil gravity cylinder clipboard copypaste " + "biome butcher forest command populateschematic surface surfacespline sweep"));


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
                            ctx.session().setTracing(!ctx.session().isTracing());
                            ctx.actor().message(Msg.info("Tracing " + (ctx.session().isTracing() ? "on" : "off")));
                        }
                        default -> ctx.actor().message(Msg.info("Usage: /we version|reload|trace"));
                    }
                };


        CommandRegistry.Entry e95 = registry.register("//help", "/help");
        e95.description = "List the commands";
        e95.group = "utility";
        // -s lists the sub-commands of the given command, -p picks the page.
        e95.booleanFlags.add("s");
        e95.valueFlags.add("p");
        e95.arguments.add("[filter]");
        e95.arguments.add("[-p <page>]");
        e95.handler = ctx -> {
                    String filter = ctx.arg(0, "").toLowerCase(Locale.ROOT);
                    if (ctx.hasFlag("s") && !filter.isEmpty()) {
                        // Sub-commands are registered as "<container> <name>".
                        int shown = 0;
                        for (CommandRegistry.Entry entry : registry.all()) {
                            String name = entry.name.toLowerCase(Locale.ROOT);
                            if (!name.startsWith("/" + filter + " ") && !name.startsWith("//" + filter + " ")
                                    && !name.equals("/" + filter) && !name.equals("//" + filter)) {
                                continue;
                            }
                            ctx.actor().message(Msg.of("§b" + entry.usage() + " §7- §f" + entry.description));
                            if (++shown > 60) {
                                break;
                            }
                        }
                        if (shown == 0) {
                            ctx.actor().message(Msg.error("No sub-command found for '" + filter + "'"));
                        }
                        return;
                    }
                    List<CommandRegistry.Entry> matches = new ArrayList<>();
                    for (CommandRegistry.Entry entry : registry.all()) {
                        if (entry.status.equals("stub")) {
                            continue;
                        }
                        if (!filter.isEmpty() && !entry.name.toLowerCase(Locale.ROOT).contains(filter)
                                && !entry.description.toLowerCase(Locale.ROOT).contains(filter)) {
                            continue;
                        }
                        matches.add(entry);
                    }
                    if (matches.isEmpty()) {
                        ctx.actor().message(Msg.error("No command matches '" + filter + "'"));
                        return;
                    }
                    Page page = Page.of(ctx, matches.size());
                    for (CommandRegistry.Entry entry : matches.subList(page.from(), page.to())) {
                        ctx.actor().message(Msg.of("§b" + entry.usage() + " §7- §f" + entry.description));
                    }
                    ctx.actor().message(Msg.info(page.header("Commands matching '" + filter + "'", matches.size())));
                    page.hint(ctx, "//help");
                };


        CommandRegistry.Entry e96 = registry.register("//version");
        e96.description = "Show the mod version";
        e96.group = "utility";
        e96.handler = ctx -> ctx.actor().message(Msg.info("FAWE-BIM " + com.maxlananas.fawebim.core.platform.Config.VERSION
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
                        com.maxlananas.fawebim.core.tool.Tools.clear(ctx.session());
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
        none.handler = ctx -> {
            com.maxlananas.fawebim.core.brush.BrushFactory.unbind(ctx.session());
            ctx.session().getBindings().remove("brush-command");
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
