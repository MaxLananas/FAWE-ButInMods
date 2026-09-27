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
import com.maxlananas.fawebim.core.world.World;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

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

    /**
     * Flushes the queued blocks and answers with one line naming what the
     * command did - a label, the count and the time - or with nothing at all
     * when the handler already reported the result itself.
     */
    private void flush(Ctx ctx, EditSession session, String label) {
        flush(ctx, session, label, session.getBlocksChanged(), "block");
    }

    private void flush(Ctx ctx, EditSession session, String label, long changed, String unit) {
        session.flushQueue();
        if (label != null) {
            ctx.actor().message(session.result(label, changed, unit));
        }
    }

    /** Minecraft's world border: no selection holds anything past it. */
    private static final int WORLD_BORDER = 30_000_000;

    private static int air() {
        return BlockState.registry().air();
    }

    /** WorldEdit's MathUtils.roundHalfUp: a half rounds away from zero. */
    private static int roundHalfUp(double value) {
        return (int) (Math.signum(value) * Math.round(Math.abs(value)));
    }

    /**
     * The levels {@code //removeabove} and {@code //removebelow} clear, counted
     * as FAWE counts them: the player's own and as many more as typed, never
     * more than the world is high, which is also what no height means.
     */
    private static int removalHeight(Ctx ctx, EditSession session) {
        int world = session.maxY() - session.minY() + 1;
        if (ctx.args().size() < 2) {
            return world;
        }
        long height = Math.min(world, ctx.intArg(1) + 1L);
        if (height < 1) {
            throw CommandRegistry.error("The height must be at least 0");
        }
        return (int) height;
    }

    /** Replaces every block in a region matching {@code mask} with {@code pattern}. */
    private long fill(EditSession session, Region region, Pattern pattern, Mask mask) {
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

    private void registerSelection() {
        CommandRegistry.Entry e1 = registry.register("//pos1", "//p1");
        e1.description = "Set position 1 to your position or the given coordinates";
        e1.group = "selection";
        e1.arguments.add("[coordinates]");
        e1.handler = ctx -> {
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
                    com.maxlananas.fawebim.core.tool.Tools.select(ctx.actor(), pos, true, true);
                };


        CommandRegistry.Entry e2 = registry.register("//pos2", "//p2");
        e2.description = "Set position 2 to your position or the given coordinates";
        e2.group = "selection";
        e2.arguments.add("[coordinates]");
        e2.handler = ctx -> {
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
                    com.maxlananas.fawebim.core.tool.Tools.select(ctx.actor(), pos, false, true);
                };


        CommandRegistry.Entry e3 = registry.register("//hpos1");
        e3.description = "Set position 1 to the block you are looking at";
        e3.group = "selection";
        e3.requiresPlayer = true;
        e3.handler = ctx -> {
                    BlockVector3 target = ctx.targetBlock(100);
                    com.maxlananas.fawebim.core.tool.Tools.select(ctx.actor(), target, true, true);
                };


        CommandRegistry.Entry e4 = registry.register("//hpos2");
        e4.description = "Set position 2 to the block you are looking at";
        e4.group = "selection";
        e4.requiresPlayer = true;
        e4.handler = ctx -> {
                    BlockVector3 target = ctx.targetBlock(100);
                    com.maxlananas.fawebim.core.tool.Tools.select(ctx.actor(), target, false, true);
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
        e6.description = "Choose a region selector";
        e6.group = "selection";
        e6.arguments.add("[selector]");
        // -d remembers the selector as the default for new sessions.
        e6.booleanFlags.add("d");
        e6.handler = ctx -> {
                    RegionSelector current = ctx.session().getSelector(ctx.world());
                    // Nothing after //sel clears the selection, as in WorldEdit.
                    if (ctx.args().isEmpty() || ctx.arg(0).equalsIgnoreCase("none")) {
                        current.clear();
                        ctx.actor().updateSelectionOutline();
                        ctx.actor().message(Msg.result("Selection", "cleared"));
                        return;
                    }
                    String type = ctx.arg(0);
                    if (type.equalsIgnoreCase("list")) {
                        listSelectors(ctx, current);
                        return;
                    }
                    RegionSelector selector = com.maxlananas.fawebim.core.region.Selectors.create(type, ctx.world(),
                            current);
                    if (selector == null) {
                        throw CommandRegistry.error("Unknown selection type '" + type + "'. Try "
                                + String.join(", ", com.maxlananas.fawebim.core.region.Selectors.NAMES)
                                + ", or //sel list");
                    }
                    ctx.session().setSelector(selector);
                    if (ctx.hasFlag("d")) {
                        ctx.session().setDefaultSelectorType(selector.getTypeName());
                        ctx.actor().message(Msg.success("Default selection type set to " + selector.getTypeName()));
                    }
                    ctx.actor().message(Msg.result("Selection type", "set to "
                            + Msg.value(selector.getTypeName()).raw()));
                    ctx.actor().message(Msg.hint(selector.usage() + (limitsVertices(selector)
                            ? " (" + (com.maxlananas.fawebim.core.region.SelectorLimits.PLAYER_VERTEX_LIMIT + 1)
                            + " points at most)" : "")));
                    ctx.actor().updateSelectionOutline();
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
                                Msg.value(item).raw() + " given"));
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
                    boolean enabled = !session.isSelectionWandEnabled();
                    session.setSelectionWandEnabled(enabled);
                    if (enabled) {
                        ctx.actor().message(Msg.success("The selection wand selects again"));
                    } else {
                        ctx.actor().message(Msg.success("The selection wand is off: it is an item again"));
                        ctx.actor().message(Msg.hint("//pos1 and //pos2 still select, //toggleeditwand turns it back on"));
                    }
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
                    ctx.actor().message(Msg.keyValue("Chunks", Msg.formatNumber(region.getChunkCount())));
                };


        CommandRegistry.Entry e12 = registry.register("//count");
        e12.description = "Count the number of blocks matching a mask";
        e12.group = "selection";
        e12.requiresSelection = true;
        e12.arguments.add("mask");
        e12.handler = ctx -> {
                    Mask mask = Parsers.mask(ctx.arg(0), ctx);
                    long count = ctx.selection().forEachPosition(mask::test);
                    ctx.actor().message(Msg.result("Counted", Msg.count(count)));
                };


        CommandRegistry.Entry e13 = registry.register("//distr", "//distribution");
        e13.description = "Show the block distribution in the selection";
        e13.group = "selection";
        e13.booleanFlags.add("c");
        e13.booleanFlags.add("d");
        e13.valueFlags.add("p");
        e13.arguments.add("[-p <page>]");
        // -c reads the clipboard, which needs no selection: the selection is
        // asked for below when it is the one read.
        e13.requiresSelection = false;
        e13.handler = ctx -> {
                    // -p pages the distribution the last //distr counted, as in
                    // FAWE: counting the selection again for every page made
                    // each click through a large one as slow as the first, and
                    // a page could belong to a different count than the one
                    // before it.
                    com.maxlananas.fawebim.core.session.LocalSession.Distribution distribution;
                    if (ctx.hasFlag("p")) {
                        distribution = ctx.session().getLastDistribution();
                        if (distribution == null) {
                            throw CommandRegistry.error("No previous distribution: run //distr first");
                        }
                    } else {
                        distribution = distribution(ctx);
                        ctx.session().setLastDistribution(distribution);
                    }
                    long total = distribution.total();
                    java.util.List<java.util.Map.Entry<String, Long>> sorted = distribution.entries();
                    ctx.actor().message(Msg.title("Block distribution (" + Msg.blocks(total) + ")"));
                    Page page = Page.of(ctx, sorted.size());
                    for (java.util.Map.Entry<String, Long> entry : sorted.subList(page.from(), page.to())) {
                        ctx.actor().message(Msg.item(entry.getKey(), Msg.formatNumber(entry.getValue()) + " ("
                                + String.format(Locale.ROOT, "%.2f", entry.getValue() * 100.0 / Math.max(1, total))
                                + "%)"));
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
                        region.expandToY(ctx.world().minY(), ctx.world().maxY());
                        ctx.actor().message(Msg.result("Region expanded vertically",
                                Msg.value(region.describe()).raw()));
                        return;
                    }
                    int amount = ctx.intArg(0);
                    int reverse = reverseAmount(ctx);
                    List<BlockVector3> directions = expandDirections(ctx, directionArgument(ctx));
                    // Both amounts of a direction go to the region together, as
                    // WorldEdit hands them: a sphere takes 3 and a reverse 3 as
                    // three more on each side, which neither half is on its own.
                    for (BlockVector3 direction : directions) {
                        BlockVector3 opposite = direction.multiply(-1);
                        int forward = capped(region, amount,
                                roomToGrow(region, direction.multiply(Integer.signum(amount)), ctx.world()));
                        int back = capped(region, reverse,
                                roomToGrow(region, opposite.multiply(Integer.signum(reverse)), ctx.world()));
                        if (back == 0) {
                            region.expand(direction.multiply(forward));
                        } else {
                            region.expand(direction.multiply(forward), opposite.multiply(back));
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
                    int reverse = reverseAmount(ctx);
                    List<BlockVector3> directions = expandDirections(ctx, directionArgument(ctx));
                    for (BlockVector3 direction : directions) {
                        // The reverse amount shrinks the same axis from the other
                        // side, so it gets what the first one leaves.
                        int room = roomToShrink(region, direction);
                        int forward = capped(region, amount, room);
                        int back = capped(region, reverse, room - Math.abs(forward));
                        if (back == 0) {
                            region.contract(direction.multiply(forward));
                        } else {
                            region.contract(direction.multiply(forward), direction.multiply(-back));
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
                        region.shift(direction.multiply(capped(region, amount,
                                roomToGrow(region, direction.multiply(Integer.signum(amount)), ctx.world()))));
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
                    List<BlockVector3> sides = sides(horizontal, vertical);
                    int room = Integer.MAX_VALUE;
                    for (BlockVector3 side : sides) {
                        room = Math.min(room, roomToGrow(region, side, ctx.world()));
                    }
                    region.expand(each(sides, capped(region, amount, room)));
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
                    // Both sides of an axis move in, so it keeps at least one
                    // block when each takes half of what is there.
                    List<BlockVector3> sides = sides(horizontal, vertical);
                    int room = Integer.MAX_VALUE;
                    for (BlockVector3 side : sides) {
                        room = Math.min(room, roomToShrink(region, side) / 2);
                    }
                    region.contract(each(sides, capped(region, amount, room)));
                    ctx.actor().message(Msg.success("Region inset: " + region.describe()));
                };

    }

    /**
     * How far the selection can grow in one direction before it leaves the world.
     *
     * <p>The selection commands hand a region an amount straight from the command
     * line, and an amount in the billions overflows the coordinates: the region
     * comes back with its minimum point past its maximum - {@code //expand
     * 2147483647} on a five block selection answers a size of -2,147,483,644 -
     * and every command after it walks that as pure noise. The room the selection
     * has left is what the amount is capped with.</p>
     */
    private static int roomToGrow(Region region, BlockVector3 direction, World world) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int room = Integer.MAX_VALUE;
        if (direction.x() > 0) {
            room = Math.min(room, WORLD_BORDER - max.x());
        } else if (direction.x() < 0) {
            room = Math.min(room, min.x() + WORLD_BORDER);
        }
        if (direction.y() > 0) {
            room = Math.min(room, world.maxY() - max.y());
        } else if (direction.y() < 0) {
            room = Math.min(room, min.y() - world.minY());
        }
        if (direction.z() > 0) {
            room = Math.min(room, WORLD_BORDER - max.z());
        } else if (direction.z() < 0) {
            room = Math.min(room, min.z() + WORLD_BORDER);
        }
        return Math.max(0, room);
    }

    /** How far the selection can shrink before one of its sides passes the other. */
    private static int roomToShrink(Region region, BlockVector3 direction) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int room = Integer.MAX_VALUE;
        if (direction.x() != 0) {
            room = Math.min(room, max.x() - min.x());
        }
        if (direction.y() != 0) {
            room = Math.min(room, max.y() - min.y());
        }
        if (direction.z() != 0) {
            room = Math.min(room, max.z() - min.z());
        }
        return Math.max(0, room);
    }

    /**
     * The sides {@code //outset} and {@code //inset} move, in WorldEdit's
     * order: both of every axis the switches leave, so the selection grows or
     * shrinks around where it is. Moving only the positive side of each axis
     * shifted the selection by the amount as it grew.
     */
    private static List<BlockVector3> sides(boolean horizontal, boolean vertical) {
        List<BlockVector3> sides = new java.util.ArrayList<>(6);
        if (!horizontal) {
            sides.add(new BlockVector3(0, 1, 0));
            sides.add(new BlockVector3(0, -1, 0));
        }
        if (!vertical) {
            sides.add(new BlockVector3(1, 0, 0));
            sides.add(new BlockVector3(-1, 0, 0));
            sides.add(new BlockVector3(0, 0, 1));
            sides.add(new BlockVector3(0, 0, -1));
        }
        return sides;
    }

    private static BlockVector3[] each(List<BlockVector3> sides, int amount) {
        BlockVector3[] amounts = new BlockVector3[sides.size()];
        for (int i = 0; i < amounts.length; i++) {
            amounts[i] = sides.get(i).multiply(amount);
        }
        return amounts;
    }

    /** An amount in the direction it will actually be applied in, capped by its room. */
    private static int capped(Region region, int amount, int room) {
        return amount > 0 ? Math.min(amount, room) : Math.max(amount, -room);
    }

    /**
     * The directions {@code //expand}, {@code //contract} and {@code //shift}
     * work in: WorldEdit's list of directions separated by commas, each a name
     * or a word relative to where the player looks, without diagonals. An
     * explicit {@code x,y,z} vector is read as the one direction it is.
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
            out.add(Directions.parse(ctx.actor(), part, false));
        }
        return out;
    }

    /**
     * The optional reverse amount of {@code //expand} and {@code //contract}.
     * WorldEdit reads a word in its place as the start of the directions, so
     * {@code //expand 10 up} grows ten blocks up; the port refused it as a
     * number it could not read.
     */
    private static int reverseAmount(Ctx ctx) {
        Ctx.Argument second = ctx.argument(1);
        return second != null && second.isNumber() ? ctx.intArg(1) : 0;
    }

    /** The directions of {@code //expand} and {@code //contract}, after the optional reverse amount. */
    private static String directionArgument(Ctx ctx) {
        Ctx.Argument second = ctx.argument(1);
        int from = second != null && second.isNumber() ? 2 : 1;
        return ctx.args().size() > from ? ctx.joined(from) : "me";
    }

    /**
     * The vertical reach of the utility commands that do not name a height,
     * up and down: {@code limits.vertical-height.default}, 256 unless
     * configured, as in FAWE.
     */
    private static int defaultVerticalHeight() {
        return com.maxlananas.fawebim.core.platform.Config.get().defaultVerticalHeight;
    }


    private void registerRegion() {
        CommandRegistry.Entry e19 = registry.register("//set");
        e19.description = "Set all blocks inside a region to a pattern";
        e19.group = "region";
        e19.confirmRegion = true;
        e19.requiresSelection = true;
        e19.booleanFlags.add("n");
        e19.booleanFlags.add("e");
        e19.booleanFlags.add("m");
        e19.arguments.add("pattern");
        e19.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.requiredJoined(0), ctx);
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
        e20.confirmRegion = true;
        e20.requiresSelection = true;
        e20.booleanFlags.add("e");
        e20.arguments.add("[mask]");
        e20.arguments.add("pattern");
        e20.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    // As in WorldEdit, a line with one argument names the pattern
                    // and replaces every block that is not air.
                    boolean masked = ctx.args().size() > 1;
                    Mask mask = masked ? Parsers.mask(ctx.arg(0), ctx) : new Masks.ExistingMask(session, true);
                    Pattern pattern = Parsers.pattern(ctx.requiredJoined(masked ? 1 : 0), ctx);
                    fill(session, ctx.selection(), pattern, mask);
                    flush(ctx, session, "Replaced");
                };


        CommandRegistry.Entry e21 = registry.register("//overlay");
        e21.description = "Set a block on top of blocks in the region";
        e21.group = "region";
        e21.confirmRegion = true;
        e21.requiresSelection = true;
        e21.arguments.add("pattern");
        e21.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int changed = com.maxlananas.fawebim.core.function.Layers.overlay(session, ctx.selection(),
                            pattern);
                    flush(ctx, session, "Overlaid", changed, "block");
                };


        CommandRegistry.Entry e22 = registry.register("//walls");
        e22.description = "Build the walls of the selection";
        e22.group = "region";
        e22.confirmRegion = true;
        e22.requiresSelection = true;
        e22.arguments.add("pattern");
        e22.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Operations.walls(session, ctx.selection(), pattern);
                    flush(ctx, session, "Walls");
                };


        CommandRegistry.Entry e23 = registry.register("//faces", "//outline");
        e23.description = "Build the faces of the selection";
        e23.group = "region";
        e23.confirmRegion = true;
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
        e24.confirmRegion = true;
        e24.requiresSelection = true;
        e24.arguments.add("pattern");
        e24.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    Region region = ctx.selection();
                    Vector3 center = region.getCenter();
                    // FAWE's box: from the centre cut towards zero to the centre
                    // rounded half away from zero, whichever way round that is.
                    int[] from = {(int) center.x(), (int) center.y(), (int) center.z()};
                    int[] to = {roundHalfUp(center.x()), roundHalfUp(center.y()), roundHalfUp(center.z())};
                    for (int x = Math.min(from[0], to[0]); x <= Math.max(from[0], to[0]); x++) {
                        for (int y = Math.min(from[1], to[1]); y <= Math.max(from[1], to[1]); y++) {
                            for (int z = Math.min(from[2], to[2]); z <= Math.max(from[2], to[2]); z++) {
                                session.setBlock(x, y, z, pattern.apply(x, y, z));
                            }
                        }
                    }
                    flush(ctx, session, "Centered");
                };


        CommandRegistry.Entry e25 = registry.register("//hollow");
        e25.description = "Hollow out the selection";
        e25.group = "region";
        e25.confirmRegion = true;
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
        e27.confirmRegion = true;
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
                    flush(ctx, session, "Smoothed", changed, "block");
                };


        CommandRegistry.Entry e28 = registry.register("//naturalize");
        e28.description = "3 layers of dirt on top then rock below";
        e28.group = "region";
        e28.confirmRegion = true;
        e28.requiresSelection = true;
        e28.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = com.maxlananas.fawebim.core.function.Layers.naturalize(session, ctx.selection());
                    flush(ctx, session, "Naturalized", changed, "block");
                };


        CommandRegistry.Entry e29 = registry.register("//lay");
        e29.description = "Set the top block in the region";
        e29.group = "region";
        e29.confirmRegion = true;
        e29.requiresSelection = true;
        e29.arguments.add("pattern");
        e29.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int columns = com.maxlananas.fawebim.core.function.Layers.lay(session, ctx.selection(), pattern);
                    flush(ctx, session, "Laid", columns, "block");
                };


        CommandRegistry.Entry e30 = registry.register("//fill");
        e30.description = "Fill a hole";
        e30.group = "region";
        e30.arguments.add("pattern");
        e30.arguments.add("radius");
        e30.arguments.add("[depth]");
        e30.arguments.add("[direction]");
        e30.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double radius = Math.max(1, ctx.radiusArg(1));
                    int depth = Math.max(1, ctx.sizeArg(2, 1));
                    BlockVector3 direction = ctx.args().size() < 4
                            ? new BlockVector3(0, -1, 0)
                            : expandDirections(ctx, ctx.joined(3)).get(0);
                    int changed = com.maxlananas.fawebim.core.function.Operations.fillDirection(session,
                            ctx.placementInWorld(), pattern, radius, depth, direction);
                    flush(ctx, session, "Filled", changed, "block");
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
                    double radius = Math.max(1, ctx.radiusArg(1));
                    int depth = Math.max(1, ctx.intArg(2, Integer.MAX_VALUE));
                    int changed = com.maxlananas.fawebim.core.function.Operations.fillXz(session,
                            ctx.placementInWorld(), pattern, radius, depth, true);
                    flush(ctx, session, "Filled", changed, "block");
                };


        CommandRegistry.Entry e31 = registry.register("//drain");
        e31.description = "Drain a pool";
        e31.group = "region";
        // -p removes the water plants, -w also un-waterlogs the blocks.
        e31.booleanFlags.add("p");
        e31.booleanFlags.add("w");
        e31.arguments.add("<radius>");
        e31.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    double radius = Math.max(0, ctx.radiusArg(0));
                    int changed = com.maxlananas.fawebim.core.function.Operations.drain(session,
                            ctx.placementInWorld(), radius, ctx.hasFlag("w"), ctx.hasFlag("p"));
                    flush(ctx, session, "Drained", changed, "block");
                };


        CommandRegistry.Entry e32 = registry.register("//regen");
        e32.description = "Regenerate the selection from the world seed";
        e32.group = "region";
        e32.confirmRegion = true;
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
                        seed = Parsers.longArg(ctx.arg(0), "seed");
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
                    long regenerated;
                    com.maxlananas.fawebim.core.util.Timer timer = new com.maxlananas.fawebim.core.util.Timer();
                    try (com.maxlananas.fawebim.core.world.World.GeneratedTerrain terrain =
                                 ctx.world().generate(region.getChunks(), options)) {
                        if (terrain == null) {
                            throw CommandRegistry.error("This platform cannot generate terrain");
                        }
                        // The edit session is opened without the mask, which
                        // was cleared above.
                        EditSession editSession = ctx.editSession();
                        regenerated = com.maxlananas.fawebim.core.function.Regeneration.copy(terrain, region,
                                editSession, options.shouldRegenBiomes());
                        if (biomeId >= 0) {
                            int targetBiome = biomeId;
                            region.forEachPosition((x, y, z) -> editSession.setBiome(x, y, z, targetBiome));
                        }
                        editSession.flushQueue();
                    } finally {
                        ctx.session().setMask(previousMask);
                    }
                    ctx.actor().message(Msg.result("Regenerated", Msg.blocks(regenerated)
                            + " in " + timer.phrase()));
                };


        // FAWE's //removeabove and //removebelow: the size is the apothem of a
        // square around the player - 1 is the player's column - and the height
        // counts levels from the player's own, the whole height of the world when
        // none is given.
        CommandRegistry.Entry e33 = registry.register("//removeabove");
        e33.description = "Remove blocks above your head.";
        e33.group = "region";
        e33.arguments.add("[size]");
        e33.arguments.add("[height]");
        e33.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int apothem = Math.max(1, ctx.sizeArg(0, 1));
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeAbove(session,
                            ctx.placement(), apothem, removalHeight(ctx, session));
                    flush(ctx, session, "Removed", changed, "block");
                };


        CommandRegistry.Entry e34 = registry.register("//removebelow");
        e34.description = "Remove blocks below you.";
        e34.group = "region";
        e34.arguments.add("[size]");
        e34.arguments.add("[height]");
        e34.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int apothem = Math.max(1, ctx.sizeArg(0, 1));
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeBelow(session,
                            ctx.placement(), apothem, removalHeight(ctx, session));
                    flush(ctx, session, "Removed", changed, "block");
                };


        // FAWE's //removenear: what the mask matches in the cube of the apothem
        // around the player, fifty unless one is given.
        CommandRegistry.Entry e35 = registry.register("//removenear");
        e35.description = "Remove blocks near you.";
        e35.group = "region";
        e35.arguments.add("mask");
        e35.arguments.add("[radius]");
        e35.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask mask = Parsers.mask(ctx.arg(0), ctx);
                    int apothem = Math.max(1, ctx.sizeArg(1, 50));
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeNear(session,
                            ctx.placement(), apothem, mask);
                    flush(ctx, session, "Removed", changed, "block");
                };


        CommandRegistry.Entry e36 = registry.register("//replacenear");
        e36.description = "Replace blocks near you";
        e36.group = "region";
        e36.arguments.add("size");
        e36.arguments.add("[mask]");
        e36.arguments.add("pattern");
        e36.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int size = Math.max(1, ctx.sizeArg(0));
                    boolean masked = ctx.args().size() > 2;
                    Mask mask = masked ? Parsers.mask(ctx.arg(1), ctx) : new Masks.ExistingMask(session, true);
                    Pattern pattern = Parsers.pattern(ctx.requiredJoined(masked ? 2 : 1), ctx);
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
                    flush(ctx, session, "Replaced", changed, "block");
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
                    double size = Math.max(1, ctx.radiusArg(0, 10));
                    int height = Math.max(1, ctx.intArg(1, defaultVerticalHeight()));
                    int changed = com.maxlananas.fawebim.core.function.Operations.simulateSnow(
                            ctx.world(), session, ctx.placement(), size, height, ctx.hasFlag("s"));
                    flush(ctx, session, "Snowed", changed, "block");
                };


        CommandRegistry.Entry e38 = registry.register("//thaw");
        e38.description = "Thaw snow and ice around you";
        e38.group = "region";
        e38.arguments.add("[size]");
        e38.arguments.add("[height]");
        e38.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    double size = Math.max(1, ctx.radiusArg(0, 10));
                    int height = Math.max(1, ctx.intArg(1, defaultVerticalHeight()));
                    int changed = com.maxlananas.fawebim.core.function.Operations.thaw(
                            session, ctx.placement(), size, height);
                    flush(ctx, session, "Thawed", changed, "block");
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
                    double size = Math.max(1, ctx.radiusArg(0, 10));
                    int height = Math.max(1, ctx.intArg(1, defaultVerticalHeight()));
                    int changed = com.maxlananas.fawebim.core.function.Operations.green(session,
                            ctx.placement(), size, height, !ctx.hasFlag("f"));
                    flush(ctx, session, "Greened", changed, "block");
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
                    int ceiling = com.maxlananas.fawebim.core.platform.Config.get().maxRadius;
                    int radius = Math.max(1, ctx.sizeArg(0, ceiling > 0 ? Math.min(40, ceiling) : 40));
                    Mask fire = Parsers.mask("minecraft:fire", ctx);
                    int changed = com.maxlananas.fawebim.core.function.Operations.removeNear(
                            session, ctx.placement(), radius, fire);
                    flush(ctx, session, "Extinguished", changed, "block");
                };


        CommandRegistry.Entry e41 = registry.register("//fixwater");
        e41.description = "Fix water to be stationary";
        e41.group = "region";
        e41.arguments.add("<radius>");
        e41.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    double radius = Math.max(0, ctx.radiusArg(0));
                    int changed = com.maxlananas.fawebim.core.function.Operations.fixLiquid(session,
                            ctx.placementInWorld(), radius, "minecraft:water");
                    flush(ctx, session, "Fixed water", changed, "water block");
                };


        CommandRegistry.Entry e42 = registry.register("//fixlava");
        e42.description = "Fix lava to be stationary";
        e42.group = "region";
        e42.arguments.add("<radius>");
        e42.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    double radius = Math.max(0, ctx.radiusArg(0));
                    int changed = com.maxlananas.fawebim.core.function.Operations.fixLiquid(session,
                            ctx.placementInWorld(), radius, "minecraft:lava");
                    flush(ctx, session, "Fixed lava", changed, "lava block");
                };


        CommandRegistry.Entry e43 = registry.register("//move");
        e43.description = "Move the contents of the selection";
        e43.group = "region";
        e43.confirmRegion = true;
        e43.requiresSelection = true;
        e43.booleanFlags.add("s");
        e43.booleanFlags.add("a");
        e43.booleanFlags.add("e");
        e43.booleanFlags.add("b");
        e43.valueFlags.add("m");
        e43.arguments.add("[multiplier]");
        e43.arguments.add("[offset]");
        e43.arguments.add("[replace]");
        e43.arguments.add("[-m <mask>]");
        e43.handler = ctx -> {
                    Region region = ctx.selection();
                    int next = leadingCount(ctx);
                    int multiplier = next == 0 ? 1 : ctx.intArg(0);
                    if (multiplier < 1) {
                        throw CommandRegistry.error("The multiplier must be at least 1");
                    }
                    BlockVector3 offset = Directions.offset(ctx.actor(), ctx.arg(next, "forward"));
                    long reach = Math.max(Math.max(Math.abs((long) offset.x()), Math.abs((long) offset.y())),
                            Math.abs((long) offset.z())) * multiplier;
                    if (reach == 0) {
                        throw CommandRegistry.error("The offset " + Msg.value(offset).raw() + " moves nothing");
                    }
                    if (reach > 2L * WORLD_BORDER) {
                        throw CommandRegistry.error("That moves the selection out of the world");
                    }
                    offset = offset.multiply(multiplier);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern leave = ctx.args().size() > next + 1 ? Parsers.pattern(ctx.joined(next + 1), ctx)
                            : new com.maxlananas.fawebim.core.pattern.Patterns.Single(air());
                    Mask mask = sourceMask(ctx, session);
                    long moved = com.maxlananas.fawebim.core.function.RegionCopies.move(session, region, offset,
                            mask, leave, ctx.hasFlag("e"), ctx.hasFlag("b"));
                    // -s moves the selection along with the blocks.
                    if (ctx.hasFlag("s")) {
                        region.shift(offset);
                    }
                    flush(ctx, session, "Moved", moved, "block");
                };


        CommandRegistry.Entry e44 = registry.register("//stack");
        e44.description = "Repeat the contents of the selection";
        e44.group = "region";
        e44.requiresSelection = true;
        e44.booleanFlags.add("s");
        e44.booleanFlags.add("a");
        e44.booleanFlags.add("e");
        e44.booleanFlags.add("b");
        // -r steps by the offset itself instead of by the size of the selection.
        e44.booleanFlags.add("r");
        e44.valueFlags.add("m");
        e44.arguments.add("[count]");
        e44.arguments.add("[offset]");
        e44.arguments.add("[-m <mask>]");
        e44.handler = ctx -> {
                    Region region = ctx.selection();
                    int next = leadingCount(ctx);
                    int count = next == 0 ? 1 : ctx.intArg(0);
                    if (count < 1) {
                        throw CommandRegistry.error("The count must be at least 1");
                    }
                    // FAWE weighs the selection by the copies asked for.
                    ctx.confirmRegion(region, count);
                    BlockVector3 offset = Directions.offset(ctx.actor(), ctx.arg(next, "forward"));
                    // Each copy steps by the size of the selection along the
                    // offset, as in WorldEdit, or by the offset itself with -r.
                    BlockVector3 step = ctx.hasFlag("r") ? offset : new BlockVector3(
                            offset.x() * region.getWidth(), offset.y() * region.getHeight(),
                            offset.z() * region.getLength());
                    if (com.maxlananas.fawebim.core.function.RegionCopies.overlaps(region, step)) {
                        throw CommandRegistry.error("A step of " + Msg.value(step).raw()
                                + " puts the copies inside the selection: step at least its size along one axis");
                    }
                    // The copies past the edge of the world write nothing; walking
                    // them was two billion passes over the selection, and their
                    // offsets overflowed into copies on the far side of the world.
                    count = copiesInWorld(region, ctx.world(), step.x(), step.y(), step.z(), count);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask mask = sourceMask(ctx, session);
                    long changed = count == 0 ? 0 : com.maxlananas.fawebim.core.function.RegionCopies.stack(session,
                            region, step, count, mask, ctx.hasFlag("e"), ctx.hasFlag("b"));
                    // -s moves the selection onto the last copy.
                    if (ctx.hasFlag("s") && count > 0) {
                        region.shift(step.multiply(count));
                    }
                    flush(ctx, session, "Stacked", changed, "block");
                };

    }

    /**
     * Counts the blocks of the selection, or of the clipboard with {@code -c},
     * by block - by state with {@code -d}, e.g. oak_log[axis=x] - most first.
     */
    private static com.maxlananas.fawebim.core.session.LocalSession.Distribution distribution(Ctx ctx) {
        BlockStateRegistry blockRegistry = BlockState.registry();
        com.maxlananas.fawebim.core.util.StateCounts counts =
                new com.maxlananas.fawebim.core.util.StateCounts(blockRegistry.stateCount());
        if (ctx.hasFlag("c")) {
            if (!ctx.session().hasClipboard()) {
                throw CommandRegistry.error("No clipboard: use //copy first");
            }
            // Every cell of the copy, air included, as FAWE counts the
            // clipboard's region and as the selection is counted below: -c
            // left the air out, so the same blocks read differently copied.
            BlockArrayClipboard clip = ctx.session().getClipboard().getClipboard();
            clip.forEachPosition((x, y, z, state) -> {
                counts.add(state);
                return false;
            });
        } else {
            World world = ctx.world();
            ctx.selection().forEachPosition((x, y, z) -> {
                counts.add(world.getBlock(x, y, z));
                return false;
            });
        }
        java.util.Map<String, Long> named = counts.byName(ctx.hasFlag("d")
                ? blockRegistry::describe : blockRegistry::name);
        java.util.List<java.util.Map.Entry<String, Long>> sorted = new java.util.ArrayList<>(named.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return new com.maxlananas.fawebim.core.session.LocalSession.Distribution(java.util.List.copyOf(sorted),
                counts.total());
    }

    /** The sub-commands of /we, each by the name it was registered under. */
    private java.util.Set<String> weSubCommands() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (CommandRegistry.Entry entry : registry.all()) {
            java.util.stream.Stream.concat(java.util.stream.Stream.of(entry.name), entry.aliases.stream())
                    .filter(name -> name.startsWith("/we "))
                    .findFirst()
                    .ifPresent(name -> names.add(name.substring(4)));
        }
        return names;
    }

    /**
     * Tab completion of //schem: the schematics and folders after load,
     * delete and loadall, the folders after list, each from the folder the
     * word typed so far points into.
     */
    private static List<String> schematicCompletions(String remaining) {
        String[] words = remaining.stripLeading().split(" ", -1);
        if (words.length != 2) {
            return List.of();
        }
        String action = words[0].toLowerCase(Locale.ROOT);
        boolean foldersOnly = action.equals("list") || action.equals("ls") || action.equals("all");
        if (!foldersOnly && !java.util.Set.of("load", "delete", "d", "loadall").contains(action)) {
            return List.of();
        }
        String typed = words[1];
        int slash = typed.lastIndexOf('/');
        List<String> completions = new ArrayList<>();
        try {
            for (String entry : Schematics.entries(slash < 0 ? "" : typed.substring(0, slash))) {
                if ((!foldersOnly || entry.endsWith("/"))
                        && entry.regionMatches(true, 0, typed, 0, typed.length())) {
                    completions.add(entry);
                    if (completions.size() == 80) {
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            // A folder that is not there completes nothing.
        }
        return completions;
    }

    /**
     * A sub-command of //schem, with its arguments and FAWE's description of
     * it: what a bare //schem lists, and the usage the sub-command answers
     * with when its argument is missing.
     */
    private record SchematicSubCommand(String name, String arguments, boolean needsArgument, String description) {

        private static final List<SchematicSubCommand> ALL = List.of(
                new SchematicSubCommand("list", "[folder/] [filter] [-p <page>] [-d|-n] [-f <format>]", false,
                        "List saved schematics"),
                new SchematicSubCommand("load", "<name> [-r] [-d]", true, "Load a schematic into your clipboard"),
                new SchematicSubCommand("save", "<name> [format] [-f]", true,
                        "Save your clipboard into a schematic file"),
                new SchematicSubCommand("loadall", "[format] <name> [-o] [-r] [-d]", true,
                        "Load multiple clipboards (paste will randomly choose one)"),
                new SchematicSubCommand("unload", "[name]", false, "Remove a clipboard from your multi-clipboard"),
                new SchematicSubCommand("move", "<folder>", true,
                        "Move your loaded schematic; <name> <format> converts one"),
                new SchematicSubCommand("delete", "<name|*>", true, "Delete a saved schematic"),
                new SchematicSubCommand("formats", "", false, "List available formats"),
                new SchematicSubCommand("share", "[name]", false, "Save your clipboard under a name to share"),
                new SchematicSubCommand("clear", "", false, "Clear your clipboard"));

        static SchematicSubCommand named(String name) {
            for (SchematicSubCommand sub : ALL) {
                if (sub.name.equals(name)) {
                    return sub;
                }
            }
            return null;
        }

        static String names() {
            return String.join("|", ALL.stream().map(SchematicSubCommand::name).toList());
        }
    }

    /** A bare //schem: every sub-command, each a click away from the chat box. */
    private static void schematicHelp(Ctx ctx) {
        ctx.actor().message(Msg.title("Schematic commands (" + SchematicSubCommand.ALL.size() + ")"));
        for (SchematicSubCommand sub : SchematicSubCommand.ALL) {
            String command = "//schem " + sub.name();
            ctx.actor().suggestLink(Msg.usage(command, sub.arguments(), sub.description()).raw(), command + " ",
                    "Put " + command + " in the chat box");
        }
        ctx.actor().message(Msg.hint("A name may go through folders: //schem save trees/oak"));
    }

    /**
     * The clipboards //schem load and loadall put in the session, which //schem
     * move, unload and delete * go by: the loadall pool, else the clipboard.
     */
    private static List<BlockArrayClipboard> loadedClipboards(com.maxlananas.fawebim.core.session.LocalSession session) {
        if (!session.getClipboardPool().isEmpty()) {
            return List.copyOf(session.getClipboardPool());
        }
        return session.hasClipboard() ? List.of(session.getClipboard().getClipboard()) : List.of();
    }

    /** The files the loaded clipboards were read from that are still there, each once. */
    private static List<java.nio.file.Path> loadedFiles(com.maxlananas.fawebim.core.session.LocalSession session) {
        java.util.Set<java.nio.file.Path> files = new java.util.LinkedHashSet<>();
        for (BlockArrayClipboard clipboard : loadedClipboards(session)) {
            if (clipboard.getSource() != null && java.nio.file.Files.isRegularFile(clipboard.getSource())) {
                files.add(clipboard.getSource());
            }
        }
        return new ArrayList<>(files);
    }

    /**
     * FAWE's //schem move: the files the clipboard was loaded from go into a
     * folder of the schematic folder, and the clipboards follow them, so a
     * second move or an unload still finds them. A file that cannot go - one of
     * the same name is there - is reported and the others still move.
     */
    private static void moveSchematics(Ctx ctx, String folder) {
        List<java.nio.file.Path> files = loadedFiles(ctx.session());
        if (files.isEmpty()) {
            throw CommandRegistry.error("No schematic file to move: //schem move moves the files"
                    + " //schem load read into your clipboard");
        }
        for (java.nio.file.Path file : files) {
            String before = Schematics.displayName(file);
            java.nio.file.Path moved;
            try {
                moved = Schematics.move(file, folder);
            } catch (com.maxlananas.fawebim.core.util.InputException refused) {
                ctx.actor().message(Msg.warn(refused.getMessage()));
                continue;
            }
            for (BlockArrayClipboard clipboard : loadedClipboards(ctx.session())) {
                if (file.equals(clipboard.getSource())) {
                    clipboard.setSource(moved.toAbsolutePath().normalize());
                }
            }
            ctx.actor().message(Msg.success("Moved '" + before + "' to '" + Schematics.displayName(moved) + "'"));
        }
    }

    /**
     * FAWE's //schem unload <file>: one schematic leaves the clipboards
     * //schem loadall gathered, or the clipboard it was loaded into is
     * cleared. It used to clear the clipboard whatever the name.
     */
    private static void unloadSchematic(Ctx ctx, String name) {
        com.maxlananas.fawebim.core.session.LocalSession session = ctx.session();
        BlockArrayClipboard match = null;
        for (BlockArrayClipboard clipboard : loadedClipboards(session)) {
            if (clipboard.getSource() != null && Schematics.names(clipboard.getSource(), name)) {
                match = clipboard;
                break;
            }
        }
        if (match == null) {
            throw CommandRegistry.error("You do not have '" + name + "' loaded");
        }
        String shown = Schematics.displayName(match.getSource());
        List<BlockArrayClipboard> rest = new ArrayList<>(session.getClipboardPool());
        rest.remove(match);
        if (rest.isEmpty()) {
            session.setClipboard(null);
            ctx.actor().message(Msg.success("Unloaded '" + shown + "': your clipboard is empty"));
            return;
        }
        boolean current = session.getClipboard().getClipboard() == match;
        session.setClipboardPool(rest);
        if (current) {
            session.setClipboardFromPool(rest.get(0));
        }
        ctx.actor().message(Msg.success("Unloaded '" + shown + "': "
                + Msg.count(rest.size(), "clipboard", "clipboards") + " left"));
    }

    /**
     * The origin of a copy or a cut: where the player stands, or pos1 under
     * {@code //toggleplace}, as in WorldEdit and FAWE, so that a paste puts the
     * build where it was from the player. A source with no position - the
     * console, rcon - keeps the lowest corner the clipboard starts with, so a
     * paste at coordinates puts that corner there.
     */
    static BlockVector3 copyOrigin(Ctx ctx, BlockArrayClipboard clipboard) {
        return ctx.placementOr(clipboard.getOrigin());
    }

    /**
     * Cuts the selection into the clipboard and leaves a pattern behind: the
     * work of {@code //cut}, and of {@code //lazycut}, which differs from it by
     * its flags only.
     *
     * @param exclude blocks that fail it stay where they are, or {@code null}
     */
    static void cutSelection(Ctx ctx, boolean withEntities, boolean withBiomes, Mask exclude, Pattern leave) {
        EditSession session = ctx.editSession();
        Region region = ctx.selection();
        // The copy and the replacing share one traversal, so the answer can say
        // how long the whole cut took.
        com.maxlananas.fawebim.core.util.Timer timer = new com.maxlananas.fawebim.core.util.Timer();
        BlockArrayClipboard clipboard = com.maxlananas.fawebim.core.clipboard.Clipboards.cut(ctx.world(),
                region, session, withEntities, withBiomes, exclude, leave);
        clipboard.setOrigin(copyOrigin(ctx, clipboard));
        ctx.session().setClipboard(clipboard);
        // The queue is applied before the answer is written, so the time the
        // line reports is the time the cut really took.
        session.flushQueue();
        StringBuilder detail = new StringBuilder(Msg.blocks(
                        clipboard.filled(com.maxlananas.fawebim.core.world.BlockState.registry())))
                .append(" to your clipboard");
        if (!clipboard.entities().isEmpty()) {
            detail.append(", ").append(Msg.count(clipboard.entities().size(), "entity", "entities"));
        }
        if (clipboard.hasBiomes()) {
            detail.append(", biomes");
        }
        detail.append(" in ").append(timer.phrase());
        detail.append(" (").append(Msg.size(region.getWidth(), region.getHeight(), region.getLength()))
                .append(')');
        ctx.actor().message(Msg.result("Cut", detail.toString()));
    }

    /**
     * How many leading arguments of {@code //move} and {@code //stack} are the
     * count: none when the first is not a number, so {@code //stack up} makes
     * one copy upwards the way {@code //stack 1 up} does.
     */
    private static int leadingCount(Ctx ctx) {
        Ctx.Argument first = ctx.argument(0);
        return first != null && first.isNumber() ? 1 : 0;
    }

    /**
     * The positions {@code //move} and {@code //stack} copy: the {@code -m}
     * mask, and with {@code -a} only the ones holding a block, both tested on
     * the source as in WorldEdit.
     */
    private static Mask sourceMask(Ctx ctx, EditSession session) {
        Mask include = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
        if (!ctx.hasFlag("a")) {
            return include;
        }
        Mask existing = new com.maxlananas.fawebim.core.mask.Masks.ExistingMask(session, true);
        return include == null ? existing
                : new com.maxlananas.fawebim.core.mask.Masks.IntersectionMask(List.of(include, existing));
    }

    /**
     * How many copies of a stack, each {@code (dx, dy, dz)} further than the
     * one before, still reach into the world: between the world's floor and
     * ceiling, and inside the border horizontally.
     */
    static int copiesInWorld(Region region, World world, int dx, int dy, int dz, int count) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        long border = Parsers.MAX_COORDINATE;
        long fit = count;
        fit = Math.min(fit, copiesAlong(min.x(), max.x(), dx, -border, border));
        fit = Math.min(fit, copiesAlong(min.y(), max.y(), dy, world.minY(), world.maxY()));
        fit = Math.min(fit, copiesAlong(min.z(), max.z(), dz, -border, border));
        return (int) fit;
    }

    /** The copies along one axis whose span still meets {@code [low, high]}. */
    private static long copiesAlong(int min, int max, int step, long low, long high) {
        if (step > 0) {
            return Math.max(0, (high - min) / step);
        }
        if (step < 0) {
            return Math.max(0, (max - low) / -step);
        }
        return Long.MAX_VALUE;
    }

    /** {@code //sel list}: every selection type, each a line that switches to it when clicked. */
    private static void listSelectors(Ctx ctx, RegionSelector current) {
        ctx.actor().message(Msg.title("Selection types"));
        for (String name : com.maxlananas.fawebim.core.region.Selectors.NAMES) {
            String description = com.maxlananas.fawebim.core.region.Selectors.description(name);
            Msg line = name.equals(current.getTypeName()) ? Msg.item(name + " (in use)", description)
                    : Msg.item(name, description);
            ctx.actor().commandLink(line.raw(), "//sel " + name, "Switch to " + name);
        }
    }

    /** Whether the shape counts its clicks against WorldEdit's vertex limit. */
    private static boolean limitsVertices(RegionSelector selector) {
        return selector instanceof com.maxlananas.fawebim.core.region.Selectors.Polygonal2DSelector
                || selector instanceof com.maxlananas.fawebim.core.region.Selectors.ConvexSelector
                || selector instanceof com.maxlananas.fawebim.core.region.Selectors.PolyhedralSelector;
    }

    /**
     * The axis {@code //flip} mirrors along: that of a direction, as in
     * WorldEdit, looking up or down included, or one named by its letter.
     */
    private static com.maxlananas.fawebim.core.transform.Axis flipAxis(Ctx ctx, String input) {
        String word = input.trim().toLowerCase(Locale.ROOT);
        if (word.equals("x") || word.equals("y") || word.equals("z")) {
            return com.maxlananas.fawebim.core.transform.Axis.parse(word);
        }
        BlockVector3 direction = Directions.parse(ctx.actor(), word, false);
        return direction.x() != 0 ? com.maxlananas.fawebim.core.transform.Axis.X
                : direction.y() != 0 ? com.maxlananas.fawebim.core.transform.Axis.Y
                : com.maxlananas.fawebim.core.transform.Axis.Z;
    }

    /**
     * What {@code //line} joins, as in WorldEdit: the vertices of a convex
     * selection, or the two corners of a cuboid one in the order they were
     * set, so the line runs between the corners the player clicked rather than
     * always from the lowest to the highest.
     */
    private static List<BlockVector3> lineEnds(Ctx ctx) {
        Region region = ctx.selection();
        if (region instanceof com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion convex) {
            return convex.getVertices();
        }
        if (!(region instanceof com.maxlananas.fawebim.core.region.CuboidRegion)) {
            throw CommandRegistry.error("//line only works with cuboid selections or convex polyhedral selections");
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        if (ctx.session().getSelector(ctx.world()) instanceof com.maxlananas.fawebim.core.region.Selectors.CuboidSelector cuboid
                && cuboid.isDefined() && cuboid.getPos1().min(cuboid.getPos2()).equals(min)
                && cuboid.getPos1().max(cuboid.getPos2()).equals(max)) {
            return List.of(cuboid.getPos1(), cuboid.getPos2());
        }
        return List.of(min, max);
    }

    private static double lineThickness(Ctx ctx) {
        double thickness = ctx.radiusArg(1, 0);
        if (thickness < 0) {
            throw CommandRegistry.error("Thickness must be >= 0");
        }
        return thickness;
    }

    /** The origin a rotation transform turns around. */
    private static BlockVector3 clipboardOriginOf(com.maxlananas.fawebim.core.session.ClipboardHolder holder) {
        return holder.getClipboard().getOrigin();
    }

    private void registerGeneration() {
        registerShapes();
        CommandRegistry.Entry e45 = registry.register("//line");
        e45.description = "Draw a line between selection corners";
        e45.group = "generation";
        e45.confirmRegion = true;
        e45.requiresSelection = true;
        e45.booleanFlags.add("h");
        e45.arguments.add("pattern");
        e45.arguments.add("[thickness]");
        e45.handler = ctx -> {
                    List<BlockVector3> points = lineEnds(ctx);
                    double thickness = lineThickness(ctx);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    int changed = Operations.drawLine(session, points, thickness, !ctx.hasFlag("h"), pattern);
                    flush(ctx, session, "Drew", changed, "block");
                };


        CommandRegistry.Entry e46 = registry.register("//curve");
        e46.description = "Draw a spline through the convex selection's vertices";
        e46.group = "generation";
        e46.confirmRegion = true;
        e46.requiresSelection = true;
        // -h draws the shell of the curve instead of the solid path.
        e46.booleanFlags.add("h");
        e46.arguments.add("pattern");
        e46.arguments.add("[thickness]");
        e46.handler = ctx -> {
                    if (!(ctx.selection() instanceof com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion convex)) {
                        throw CommandRegistry.error("//curve only works with convex polyhedral selections");
                    }
                    double thickness = lineThickness(ctx);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    // WorldEdit's curve: a Catmull-Rom spline walked ten times per block.
                    int changed = Operations.drawSpline(session, convex.getVertices(), 0, 0, 0, 10, thickness,
                            !ctx.hasFlag("h"), pattern);
                    flush(ctx, session, "Drew", changed, "block");
                };


        CommandRegistry.Entry e48 = registry.register("//deform");
        e48.description = "Deform blocks in the selection using an expression";
        e48.group = "generation";
        e48.confirmRegion = true;
        e48.requiresSelection = true;
        e48.booleanFlags.add("r");
        e48.booleanFlags.add("o");
        // -c evaluates the expression around the centre of the selection.
        e48.booleanFlags.add("c");
        e48.arguments.add("expression");
        e48.handler = ctx -> {
                    String expression = ctx.requiredJoined(0);
                    Region deformRegion = ctx.selection();
                    // WorldEdit's order: -r, then -o, then -c, else the unit cube.
                    Operations.DeformFrame frame;
                    if (ctx.hasFlag("r")) {
                        frame = Operations.DeformFrame.RAW;
                    } else if (ctx.hasFlag("o")) {
                        frame = Operations.DeformFrame.offset(ctx.placement().toVector3());
                    } else if (ctx.hasFlag("c")) {
                        frame = Operations.DeformFrame.offset(deformRegion.getMinimumPoint().toVector3()
                                .add(deformRegion.getMaximumPoint().toVector3()).multiply(0.5));
                    } else {
                        frame = Operations.DeformFrame.unitCube(deformRegion);
                    }
                    EditSession session = ctx.editSession();
                    int changed = Operations.deform(ctx.world(), session, deformRegion, expression, frame);
                    flush(ctx, session, "Deformed", changed, "block");
                };


        CommandRegistry.Entry e49 = registry.register("//flora");
        e49.description = "Make flora within the region";
        e49.group = "generation";
        e49.confirmRegion = true;
        e49.requiresSelection = true;
        e49.arguments.add("[density]");
        e49.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    double density = ctx.doubleArg(0, 5) / 100.0;
                    int changed = com.maxlananas.fawebim.core.function.Operations.flora(ctx.world(), session,
                            ctx.selection(), density);
                    flush(ctx, session, "Planted", changed, "plant");
                };


        // //forest is WorldEdit's "Make a forest": trees of one type, scattered at
        // a density, while //forestgen generates a forest of a given size.
        CommandRegistry.Entry e49b = registry.register("//forest");
        e49b.description = "Make a forest within the region";
        e49b.group = "generation";
        e49b.confirmRegion = true;
        e49b.requiresSelection = true;
        e49b.arguments.add("<tree-type>");
        e49b.arguments.add("[density]");
        e49b.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    // WorldEdit defaults the type to a regular tree and takes any
                    // name it declares for one, so the argument is optional.
                    String type = Parsers.treeType(ctx.arg(0, "tree"));
                    double density = ctx.doubleArg(1, 5) / 100.0;
                    int changed = com.maxlananas.fawebim.core.function.Operations.forest(session,
                            ctx.selection(), type, density);
                    flush(ctx, session, "Planted", changed, "tree");
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
                                if (!region.contains(x, y, z)
                                        || BlockState.registry().isAirLike(ctx.world().getBlock(x, y, z))) {
                                    continue;
                                }
                                if (random.nextDouble() < density && region.contains(x, y + 1, z)
                                        && session.setBlock(x, y + 1, z, pumpkin)) {
                                    changed++;
                                }
                                break;
                            }
                        }
                    }
                    flush(ctx, session, "Generated", changed, "pumpkin");
                };


        CommandRegistry.Entry e53 = registry.register("//ore", "/ore");
        e53.description = "Generates ores";
        e53.group = "generation";
        e53.confirmRegion = true;
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
                    flush(ctx, session, "Generated", changed, "block");
                };


        CommandRegistry.Entry e53b = registry.register("//ores", "/ores");
        e53b.description = "Generates ores";
        e53b.group = "generation";
        e53b.confirmRegion = true;
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
                    flush(ctx, session, "Generated", changed, "block");
                };


        CommandRegistry.Entry e55 = registry.register("//fall");
        e55.description = "Have the blocks in the selection fall";
        e55.group = "generation";
        e55.confirmRegion = true;
        e55.requiresSelection = true;
        e55.arguments.add("[replace]");
        // -m keeps the blocks inside the vertical bounds of the selection.
        e55.booleanFlags.add("m");
        e55.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int[] replace = ctx.args().isEmpty() ? null
                            : new int[]{ctx.pattern(0).apply(ctx.placement())};
                    long changed = com.maxlananas.fawebim.core.function.Operations.fall(ctx.world(), session,
                            ctx.selection(), ctx.hasFlag("m"), replace);
                    flush(ctx, session, "Generated", changed, "block");
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
            flush(ctx, session, "Created", changed, "block");
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
            flush(ctx, session, "Created", changed, "block");
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
            flush(ctx, session, "Created", changed, "block");
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
            flush(ctx, session, "Created", changed, "block");
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
                    int size = ctx.sizeArg(1);
                    boolean hollowShape = ctx.hasFlag("h");
                    int changed = com.maxlananas.fawebim.core.function.Operations.pyramid(session, ctx.placement(),
                            size, pattern, hollowShape);
                    flush(ctx, session, "Created", changed, "block");
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
                    int size = ctx.sizeArg(1);
                    int changed = com.maxlananas.fawebim.core.function.Operations.pyramid(session, ctx.placement(),
                            size, pattern, true);
                    flush(ctx, session, "Created", changed, "block");
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
                    flush(ctx, session, "Created", changed, "block");
                };

    }

    private void registerClipboard() {
        CommandRegistry.Entry e59 = registry.register("//copy", "//cp");
        e59.description = "Copy the selection to your clipboard";
        e59.group = "clipboard";
        e59.confirmRegion = true;
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
                    if (!ctx.hasFlag("c")) {
                        clipboard.setOrigin(copyOrigin(ctx, clipboard));
                    }
                    ctx.session().setClipboard(clipboard);
                    // What the clipboard holds, not how big the selection was:
                    // a copy of an empty region stores nothing, and the size in
                    // brackets is the selection the player made either way.
                    StringBuilder detail = new StringBuilder(Msg.blocks(
                                    clipboard.filled(com.maxlananas.fawebim.core.world.BlockState.registry())))
                            .append(" to your clipboard");
                    if (!clipboard.entities().isEmpty()) {
                        detail.append(", ").append(Msg.count(clipboard.entities().size(), "entity", "entities"));
                    }
                    if (clipboard.hasBiomes()) {
                        detail.append(", biomes");
                    }
                    detail.append(" (").append(Msg.size(ctx.selection().getWidth(), ctx.selection().getHeight(),
                            ctx.selection().getLength())).append(')');
                    ctx.actor().message(Msg.result("Copied", detail.toString()));
                };


        CommandRegistry.Entry e60 = registry.register("//cut");
        e60.description = "Cut the selection to your clipboard";
        e60.group = "clipboard";
        e60.confirmRegion = true;
        e60.requiresSelection = true;
        e60.booleanFlags.add("e");
        e60.booleanFlags.add("b");
        e60.valueFlags.add("m");
        // Upstream takes the pattern the selection is left as; its default is air.
        e60.arguments.add("[leavePattern]");
        e60.handler = ctx -> {
                    Masks.ExtentHolder.set(ctx.editSession());
                    Mask exclude = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    Pattern leave = ctx.args().isEmpty() ? Parsers.pattern("air", ctx)
                            : Parsers.pattern(ctx.arg(0), ctx);
                    cutSelection(ctx, ctx.hasFlag("e"), ctx.hasFlag("b"), exclude, leave);
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
                    // -o, as in WorldEdit: the origin goes back where it was
                    // in the world, which puts the build back where it stood.
                    BlockVector3 destination = ctx.hasFlag("o") ? clipboard.worldOrigin()
                            : ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Mask sourceMask = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
                    boolean onlySelect = ctx.hasFlag("n");
                    int changed = 0;
                    if (!onlySelect) {
                        changed = com.maxlananas.fawebim.core.clipboard.Clipboards.paste(clipboard, destination, session,
                                holder.getTransform(), ctx.hasFlag("a"), sourceMask, ctx.hasFlag("e"),
                                ctx.hasFlag("b"), ctx.hasFlag("x"), ctx.hasFlag("v"));
                    }
                    if (ctx.hasFlag("s") || onlySelect) {
                        // The blocks the paste covers: the clipboard's box around
                        // the destination as its origin, turned with the paste.
                        BlockVector3[] bounds = com.maxlananas.fawebim.core.clipboard.Clipboards.pastedBounds(
                                clipboard, destination, holder.getTransform());
                        ctx.session().getSelector(ctx.world()).selectPrimary(bounds[0],
                                com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                        ctx.session().getSelector(ctx.world()).selectSecondary(bounds[1],
                                com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
                    }
                    flush(ctx, session, "Pasted", changed, "block");
                };


        CommandRegistry.Entry e62 = registry.register("//rotate");
        e62.description = "Rotate the contents of the clipboard";
        e62.group = "clipboard";
        e62.arguments.add("rotateY");
        e62.arguments.add("[rotateX]");
        e62.arguments.add("[rotateZ]");
        e62.handler = ctx -> {
                    if (!ctx.session().hasClipboard()) {
                        throw CommandRegistry.error("No clipboard: use //copy first");
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
                    // WorldEdit's note: a block is turned by a quarter at a
                    // time, so any other angle lands between two of them.
                    if (Math.abs(rotateY % 90) > 0.001 || Math.abs(rotateX % 90) > 0.001
                            || Math.abs(rotateZ % 90) > 0.001) {
                        ctx.actor().message(Msg.warn("Interpolation is not supported: angles that are"
                                + " multiples of 90 are recommended"));
                    }
                };


        CommandRegistry.Entry e63 = registry.register("//flip");
        e63.description = "Flip the clipboard";
        e63.group = "clipboard";
        e63.arguments.add("[direction]");
        e63.handler = ctx -> {
                    if (!ctx.session().hasClipboard()) {
                        throw CommandRegistry.error("No clipboard");
                    }
                    com.maxlananas.fawebim.core.transform.Axis axis = flipAxis(ctx, ctx.arg(0, "me"));
                    var holder = ctx.session().getClipboard();
                    holder.setTransform(holder.getTransform().combine(
                            com.maxlananas.fawebim.core.transform.Transforms.flip(holder.getClipboard().getOrigin(), axis)));
                    ctx.actor().message(Msg.result("Clipboard", "flipped on " + Msg.value(axis).raw()));
                };


        CommandRegistry.Entry e64 = registry.register("//clearclipboard");
        e64.description = "Clear your clipboard";
        e64.group = "clipboard";
        e64.handler = ctx -> {
                    // Empty, as FAWE leaves it: a clipboard of one air block
                    // still pasted, and saved as a schematic.
                    ctx.session().setClipboard(null);
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
        e65.switchesUnder.put("save", java.util.Set.of("f"));
        e65.arguments.add("list|ls|all|save|load|loadall|delete|d|formats|listformats|f|move|m|share|clear|unload");
        e65.arguments.add("[name]");
        e65.arguments.add("[format]");
        e65.arguments.add("[-p <page>]");
        e65.suggestions = Commands::schematicCompletions;
        e65.handler = ctx -> {
                    // A bare //schem lists the sub-commands, as FAWE's help for the
                    // container does, where it answered with a missing argument.
                    if (ctx.args().isEmpty() || ctx.arg(0).equalsIgnoreCase("help")) {
                        schematicHelp(ctx);
                        return;
                    }
                    String action = switch (ctx.arg(0).toLowerCase(Locale.ROOT)) {
                        case "ls", "all" -> "list";
                        case "d" -> "delete";
                        case "m" -> "move";
                        case "f", "listformats" -> "formats";
                        default -> ctx.arg(0).toLowerCase(Locale.ROOT);
                    };
                    SchematicSubCommand sub = SchematicSubCommand.named(action);
                    if (sub == null) {
                        throw CommandRegistry.error("Unknown sub-command '" + ctx.arg(0) + "': //schem "
                                + SchematicSubCommand.names());
                    }
                    // The usage of the sub-command, not of every one of them.
                    if (sub.needsArgument() && ctx.args().size() < 2) {
                        throw CommandRegistry.error("Missing argument 1 for //schem " + sub.name() + " "
                                + sub.arguments());
                    }
                    switch (action) {
                        case "list" -> {
                            // FAWE's list: a word ending in a slash is a folder to
                            // look in - trees/ - and another word keeps the names it
                            // starts. A filter name picks between the shared folder
                            // and the player's own, which only exists with FAWE's
                            // per-player schematics: every schematic is shared here.
                            StringBuilder path = new StringBuilder();
                            String word = "";
                            for (String argument : ctx.args().subList(1, ctx.args().size())) {
                                if (argument.endsWith("/")) {
                                    path.append(argument);
                                } else if (com.maxlananas.fawebim.core.clipboard.ListFilter.parse(argument) == null) {
                                    word = argument;
                                }
                            }
                            String folder = path.length() == 0 ? "" : path.substring(0, path.length() - 1);
                            if (!folder.isEmpty() && !Schematics.isFolder(folder)) {
                                throw CommandRegistry.error("No folder named '" + folder + "' among the schematics");
                            }
                            List<String> names = Schematics.entries(folder);
                            if (!word.isEmpty()) {
                                names = Schematics.matching(names, word);
                            }
                            // -f <format> keeps one format, as FAWE's does, which
                            // leaves the folders out; -d and -n sort the files by
                            // write time instead of by name, under the folders.
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
                                names = new ArrayList<>(names);
                                names.sort((a, b) -> {
                                    if (a.endsWith("/") != b.endsWith("/")) {
                                        return a.endsWith("/") ? -1 : 1;
                                    }
                                    return oldestFirst ? Long.compare(Schematics.timeOf(a), Schematics.timeOf(b))
                                            : Long.compare(Schematics.timeOf(b), Schematics.timeOf(a));
                                });
                            }
                            Page page = Page.of(ctx, names.size());
                            ctx.actor().message(Msg.title((folder.isEmpty() ? "Schematics" : "Schematics in " + folder + "/")
                                    + " (" + names.size() + (page.pages() > 1 ? ", page " + page.number() + "/" + page.pages() : "")
                                    + ")"));
                            if (names.isEmpty()) {
                                ctx.actor().message(Msg.hint("None yet: //schem save <name> writes your clipboard"));
                            }
                            // A folder lists itself when clicked, a schematic puts its
                            // load command in the chat box, as in FAWE.
                            for (String name : names.subList(page.from(), page.to())) {
                                if (name.endsWith("/")) {
                                    ctx.actor().commandLink(Msg.item(name, "folder").raw(), "//schem list " + name,
                                            "List " + name);
                                } else {
                                    ctx.actor().suggestLink(Msg.item(name, Schematics.formatOf(name)).raw(),
                                            "//schem load " + name, "Load " + name);
                                }
                            }
                            page.hint(ctx, "//schem list" + (folder.isEmpty() ? "" : " " + folder + "/"));
                        }
                        case "save" -> {
                            if (!ctx.session().hasClipboard()) {
                                throw CommandRegistry.error("No clipboard: copy something first");
                            }
                            String name = ctx.arg(1);
                            // Without a format the save writes the one saving.format
                            // names, which it used to ignore for sponge.3.
                            String format = com.maxlananas.fawebim.core.clipboard.SchematicFormat.of(ctx.arg(2,
                                    com.maxlananas.fawebim.core.platform.Config.get().defaultSchematicFormat)).id();
                            // -f overwrites an existing file; without it a name
                            // that is already taken is refused.
                            if (!ctx.hasFlag("f") && Schematics.exists(name, format)) {
                                throw CommandRegistry.error("A schematic named '" + name + "' already exists."
                                        + " Use //schem save -f to overwrite it.");
                            }
                            // A large save goes to the worker pool, so the tick
                            // loop is not held up while the file is written.
                            BlockArrayClipboard saving = ctx.session().getClipboard().getClipboard();
                            if (format.equals(com.maxlananas.fawebim.core.clipboard.SchematicFormat.MCEDIT.id())) {
                                int lost = Schematics.legacyLosses(saving);
                                if (lost > 0) {
                                    ctx.actor().message(Msg.error(Msg.blocks(lost) + " have no legacy id and are"
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
                                                Throwable cause = error.getCause() != null ? error.getCause() : error;
                                                ctx.actor().message(CommandRegistry.failureMessage(
                                                        cause instanceof Exception exception ? exception
                                                                : new RuntimeException(cause),
                                                        ctx.actor(), "//schem save"));
                                            } else {
                                                ctx.actor().message(Msg.success("Saved schematic '"
                                                        + Schematics.displayName(file) + "'"));
                                            }
                                        }));
                            } else {
                                java.nio.file.Path file = Schematics.save(saving, name, format);
                                ctx.actor().message(Msg.success("Saved schematic '" + Schematics.displayName(file) + "'"));
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
                            // The file that was read, which the name may leave open -
                            // oak is oak.schem or oak.schematic - and FAWE's pointer to
                            // what comes next.
                            com.maxlananas.fawebim.core.math.BlockBox box = clipboard.getBox();
                            ctx.actor().message(Msg.success("Loaded schematic '"
                                    + Schematics.displayName(clipboard.getSource()) + "' ("
                                    + Msg.size(box.width(), box.height(), box.length()) + ")"
                                    + (ctx.hasFlag("r") ? " with a random rotation" : "")
                                    + ": paste it with //paste"));
                        }
                        case "delete", "d" -> {
                            String name = ctx.arg(1);
                            if (!name.equals("*")) {
                                Schematics.delete(name);
                                ctx.actor().message(Msg.success("Deleted schematic '" + name + "'"));
                                return;
                            }
                            // FAWE's //schem delete *: the files the clipboard was loaded from.
                            List<java.nio.file.Path> files = loadedFiles(ctx.session());
                            if (files.isEmpty()) {
                                throw CommandRegistry.error("No schematic file to delete: //schem delete * deletes"
                                        + " the files //schem load read into your clipboard");
                            }
                            for (java.nio.file.Path file : files) {
                                String shown = Schematics.displayName(file);
                                Schematics.delete(shown);
                                ctx.actor().message(Msg.success("Deleted schematic '" + shown + "'"));
                            }
                        }
                        case "unload" -> {
                            if (ctx.args().size() < 2) {
                                ctx.session().setClipboard(null);
                                ctx.actor().message(Msg.result("Clipboard", "unloaded"));
                                return;
                            }
                            unloadSchematic(ctx, ctx.arg(1));
                        }
                        case "move", "m" -> {
                            if (ctx.args().size() > 2) {
                                // Two words: the conversion this command did before it
                                // moved files as FAWE's does.
                                String name = ctx.arg(1);
                                java.nio.file.Path written = Schematics.convert(name, ctx.arg(2));
                                ctx.actor().message(Msg.success("Converted '" + name + "' to "
                                        + com.maxlananas.fawebim.core.clipboard.SchematicFormat.of(ctx.arg(2)).id()
                                        + ": '" + Schematics.displayName(written) + "'"));
                                return;
                            }
                            moveSchematics(ctx, ctx.arg(1));
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
                            ctx.actor().message(Msg.result("Clipboard", "cleared"));
                        }
                        case "loadall" -> {
                            // FAWE's //schem loadall [format] <filename>: one word is the
                            // file, and the format only comes first when both are given.
                            // The format is read from each file anyway.
                            String filter = ctx.arg(ctx.args().size() > 2 ? 2 : 1);
                            java.util.List<com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard> loaded =
                                    Schematics.loadAll(filter);
                            if (loaded.isEmpty()) {
                                throw CommandRegistry.error("No schematic matched '" + filter + "'");
                            }
                            // Without -o they join the clipboards the session
                            // holds, a single one included, as FAWE's addClipboard.
                            java.util.List<BlockArrayClipboard> pool = new java.util.ArrayList<>();
                            if (!ctx.hasFlag("o")) {
                                pool.addAll(ctx.session().getClipboardPool());
                                if (pool.isEmpty() && ctx.session().hasClipboard()) {
                                    pool.add(ctx.session().getClipboard().getClipboard());
                                }
                            }
                            pool.addAll(loaded);
                            ctx.session().setClipboard(loaded.get(0));
                            ctx.session().setClipboardPool(pool);
                            ctx.session().setClipboardPoolRandomRotation(ctx.hasFlag("r") || ctx.hasFlag("d"));
                            ctx.session().setClipboardPoolDynamicRotation(ctx.hasFlag("d"));
                            ctx.actor().message(Msg.success("Loaded " + Msg.count(loaded.size(), "clipboard", "clipboards") + "; "
                                    + "//paste picks one at random"));
                        }
                        case "formats", "listformats", "f" -> {
                            // FAWE's listing: every format with the names it is looked up by.
                            com.maxlananas.fawebim.core.clipboard.SchematicFormat[] all =
                                    com.maxlananas.fawebim.core.clipboard.SchematicFormat.values();
                            ctx.actor().message(Msg.title("Schematic formats (" + all.length + ", default "
                                    + com.maxlananas.fawebim.core.platform.Config.get().defaultSchematicFormat + ")"));
                            for (com.maxlananas.fawebim.core.clipboard.SchematicFormat format : all) {
                                ctx.actor().message(Msg.item(format.id(), format.suffix() + (format.aliases().isEmpty()
                                        ? "" : " - also " + String.join(", ", format.aliases()))));
                            }
                        }
                        default -> throw CommandRegistry.error("Unknown sub-command '" + ctx.arg(0) + "': //schem "
                                + SchematicSubCommand.names());
                    }
                };

    }

    private void registerHistory() {
        CommandRegistry.Entry e66 = registry.register("//undo", "/undo", "//u");
        e66.description = "Undoes the last action (from history)";
        e66.group = "history";
        e66.arguments.add("[times]");
        e66.arguments.add("[player]");
        e66.handler = ctx -> {
                    int steps = undoCount(ctx.argument(0));
                    ctx.confirmCount(steps);
                    com.maxlananas.fawebim.core.session.LocalSession target = historyTarget(ctx);
                    int undone = historySteps(ctx, target, steps, true);
                    String who = target == ctx.session() ? "" : " for " + target.ownerName();
                    if (undone == 0) {
                        ctx.actor().message(Msg.error("Nothing to undo" + who));
                    } else {
                        ctx.actor().message(Msg.result("Undid", Msg.count(undone, "block change", "block changes") + who));
                    }
                };


        CommandRegistry.Entry e67 = registry.register("//redo", "/redo", "//r");
        e67.description = "Redoes the last action (from history)";
        e67.group = "history";
        e67.arguments.add("[times]");
        e67.arguments.add("[player]");
        e67.handler = ctx -> {
                    int steps = undoCount(ctx.argument(0));
                    ctx.confirmCount(steps);
                    com.maxlananas.fawebim.core.session.LocalSession target = historyTarget(ctx);
                    int redone = historySteps(ctx, target, steps, false);
                    String who = target == ctx.session() ? "" : " for " + target.ownerName();
                    if (redone == 0) {
                        ctx.actor().message(Msg.error("Nothing to redo" + who));
                    } else {
                        ctx.actor().message(Msg.result("Redid", Msg.count(redone, "block change", "block changes") + who));
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
            try {
                edit.applyRecord(record, undo);
            } finally {
                edit.close();
            }
            changed += record.changeCount() + record.biomeChangeCount();
        }
        return changed;
    }

    private void registerBiome() {
        CommandRegistry.Entry e69 = registry.register("/setbiome", "//setbiome", "//biome");
        e69.description = "Set the biome in the selection, or at your position with -p";
        e69.group = "biome";
        e69.confirmRegion = true;
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
                    BlockVector3 min = ctx.selection().getMinimumPoint();
                    BlockVector3 max = ctx.selection().getMaximumPoint();
                    for (int x = min.x(); x <= max.x(); x++) {
                        for (int z = min.z(); z <= max.z(); z++) {
                            for (int y = min.y(); y <= max.y(); y += 4) {
                                if (!ctx.selection().contains(x, y, z)) {
                                    continue;
                                }
                                if (session.setBiome(x - Math.floorMod(x, 4), y - Math.floorMod(y, 4),
                                        z - Math.floorMod(z, 4), biomeId)) {
                                    changed++;
                                }
                            }
                        }
                    }
                    session.flushQueue();
                    ctx.actor().message(Msg.success("Changed biome of "
                            + Msg.count(changed, "biome cell", "biome cells")));
                };


        CommandRegistry.Entry e70 = registry.register("//biomelist");
        e70.description = "List available biomes";
        e70.group = "biome";
        e70.valueFlags.add("p");
        e70.arguments.add("[-p <page>]");
        e70.handler = ctx -> {
                    List<String> biomes = BlockState.registry().biomeNames();
                    Page page = Page.of(ctx, biomes.size());
                    ctx.actor().message(page.header("Biomes", biomes.size()));
                    ctx.actor().message(Msg.hint(Str.limit(String.join(", ",
                            biomes.subList(page.from(), page.to())), 2000)));
                    page.hint(ctx, "//biomelist");
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
                    ctx.actor().message(page.header("Chunks", chunks.size()));
                    StringBuilder sb = new StringBuilder();
                    for (int i = page.from(); i < page.to(); i++) {
                        sb.append(chunks.get(i).x()).append(',').append(chunks.get(i).z()).append(' ');
                    }
                    ctx.actor().message(Msg.hint(sb.toString().trim()));
                    page.hint(ctx, "//listchunks");
                };


        CommandRegistry.Entry e74 = registry.register("//delchunks");
        e74.description = "Delete the chunks in the selection (regenerates them)";
        e74.group = "chunk";
        e74.requiresSelection = true;
        // -o only deletes the chunks that were untouched for that long.
        e74.valueFlags.add("o");
        e74.arguments.add("[-o <time>]");
        e74.handler = ctx -> {
                    long before = ctx.hasFlag("o") ? Str.parseDuration(ctx.flagValue("o", "")) : 0;
                    long threshold = before == 0 ? 0 : System.currentTimeMillis() - before;
                    java.util.List<BlockVector2> chunks = new java.util.ArrayList<>();
                    int skipped = 0;
                    for (BlockVector2 chunk : ctx.selection().getChunks()) {
                        if (threshold > 0 && ctx.world().chunkLastModified(chunk.x(), chunk.z()) > threshold) {
                            skipped++;
                            continue;
                        }
                        chunks.add(chunk);
                    }
                    // A deleted chunk is one the generator makes again: its whole
                    // columns are written as freshly generated, through the edit
                    // session, so //undo brings them back.
                    if (!chunks.isEmpty()) {
                        try (com.maxlananas.fawebim.core.world.World.GeneratedTerrain terrain = ctx.world().generate(
                                chunks, new com.maxlananas.fawebim.core.world.RegenOptions().setRegenBiomes(true))) {
                            if (terrain == null) {
                                throw CommandRegistry.error("This platform cannot generate terrain");
                            }
                            EditSession editSession = ctx.editSession();
                            for (BlockVector2 chunk : chunks) {
                                com.maxlananas.fawebim.core.function.Regeneration.copy(terrain,
                                        new com.maxlananas.fawebim.core.region.CuboidRegion(
                                                new BlockVector3(chunk.x() << 4, ctx.world().minY(), chunk.z() << 4),
                                                new BlockVector3((chunk.x() << 4) + 15, ctx.world().maxY(),
                                                        (chunk.z() << 4) + 15)),
                                        editSession, true);
                            }
                            editSession.flushQueue();
                        }
                    }
                    ctx.actor().message(Msg.result("Deleted", Msg.count(chunks.size(), "chunk", "chunks")
                            + (skipped > 0 ? ", kept " + Msg.count(skipped) + " recently changed"
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
                    // WorldEdit's /jumpto: the first free space at or above the
                    // target, or the target itself with -f.
                    if (ctx.hasFlag("f")) {
                        ctx.actor().teleport(target.x() + 0.5, target.y(), target.z() + 0.5);
                    } else if (!Navigation.findFreePosition(ctx.actor(), target)) {
                        throw CommandRegistry.error("No free space above " + Msg.value(target).raw());
                    }
                    ctx.actor().message(Msg.result("Jumped to", Msg.value(ctx.actor().position()).raw()));
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
                    ctx.actor().message(Msg.result("Ascended", Msg.count(moved, "level", "levels")));
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
                    ctx.actor().message(Msg.result("Descended", Msg.count(moved, "level", "levels")));
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
                    ctx.actor().message(Msg.success("Moved up " + Msg.blocks(distance)));
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
                            ? "on - lighting in the affected chunks may be wrong and/or you"
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
                            com.maxlananas.fawebim.core.brush.BrushFactory.current(ctx.actor());
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
                    set.add(ToolUtilCommands.parseTransform(ctx));
                    ctx.session().getTransformSet().setTransforms(set);
                    ctx.actor().message(Msg.success("Global transform set"));
                };


        CommandRegistry.Entry e89 = registry.register("//masks");
        e89.description = "List the available masks";
        e89.group = "utility";
        e89.handler = ctx -> ctx.actor().message(Msg.result("Masks", "#air #existing #solid #liquid #fullcube #wall #surface #angle #surfaceangle #roc #beside " + "#extrema #xaxis #yaxis #zaxis #true #false #exposed #biome #region #dregion #offset " + "#simplex #clipboard # =expr ! & ,"));


        CommandRegistry.Entry e90 = registry.register("//patterns");
        e90.description = "List the available patterns";
        e90.group = "utility";
        e90.handler = ctx -> ctx.actor().message(Msg.result("Patterns", "block, 25%block, #clipboard #copy #existing #biome #offset #spread #solidspread " + "#surfacespread #l/#linear #l3d #l2d #color #lighten #darken #saturate #desaturate " + "#swaptype #simplex ##tag =expr ^"));


        CommandRegistry.Entry e91 = registry.register("//transforms");
        e91.description = "List the available transforms";
        e91.group = "utility";
        e91.handler = ctx -> ctx.actor().message(Msg.result("Transforms", "rotate <angle> [axis], flip [direction], scale <factor>, offset <x> <y> <z>"));


        CommandRegistry.Entry e92 = registry.register("//brushes");
        e92.description = "List the available brushes";
        e92.group = "utility";
        e92.handler = ctx -> ctx.actor().message(Msg.result("Brushes", "sphere ball smooth blendball flatten height raise lower layer line spline catenary " + "scatter shatter splatter rock blob pull stencil gravity cylinder clipboard copypaste " + "biome butcher forest command populateschematic surface surfacespline sweep"));


        CommandRegistry.Entry e93 = registry.register("//desel", "//deselect");
        e93.description = "Clear your selection";
        e93.group = "selection";
        e93.handler = ctx -> {
                    ctx.session().getSelector(ctx.world()).clear();
                    ctx.actor().message(Msg.success("Selection cleared"));
                };


        CommandRegistry.Entry e94 = registry.register("//we", "/we", "/worldedit", "/fawe", "/fastasyncworldedit");
        e94.description = "WorldEdit/FAWE information";
        e94.group = "utility";
        e94.arguments.add("[version|reload|trace|help]");
        e94.handler = ctx -> {
                    // FAWE's /worldedit container, which answers to /we, /fawe
                    // and /fastasyncworldedit too. Its sub-commands are
                    // registered as "/we <name>": a line that came in through
                    // another spelling is sent on to them, and a bare /we lists
                    // them, as FAWE's container does.
                    if (ctx.args().isEmpty()) {
                        Help.subCommands(ctx, registry, "we");
                        return;
                    }
                    String sub = "/we " + ctx.arg(0).toLowerCase(Locale.ROOT);
                    if (!registry.contains(sub)) {
                        throw CommandRegistry.error("Unknown sub-command '" + ctx.arg(0) + "': /we "
                                + String.join("|", weSubCommands()));
                    }
                    registry.dispatch(ctx.actor(), "/we " + ctx.tail());
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
        e96.handler = ctx -> ctx.actor().message(Msg.result("FAWE-BIM "
                + com.maxlananas.fawebim.core.platform.Config.VERSION, registry.all().size() + " commands registered"));

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
                    var brush = com.maxlananas.fawebim.core.brush.BrushFactory.current(ctx.actor());
                    if (brush == null) {
                        ctx.actor().message(Msg.info("No brush bound. Use /brush sphere 5 stone for example."));
                    } else {
                        ctx.actor().message(Msg.keyValue("Brush", brush.describe()));
                    }
                };

    }

    /**
     * Binds the tool a name stands for to the held item, or to the item named
     * right after the tool's arguments, and says so as FAWE does.
     *
     * @param first where the tool's arguments start among the command's
     */
    private void bindTool(Ctx ctx, String name, int first) {
        com.maxlananas.fawebim.core.tool.Tool tool = com.maxlananas.fawebim.core.tool.Tools.create(name, ctx, first);
        if (tool == null) {
            throw CommandRegistry.error("Unknown tool '" + name + "'");
        }
        // The tool's own name, whichever spelling built it: /tool replace is the replacer.
        String item = com.maxlananas.fawebim.core.tool.Tools.bind(ctx.session(), tool, ctx.actor(),
                ctx.arg(first + com.maxlananas.fawebim.core.tool.Tools.argumentCount(tool.name()), ""));
        ctx.actor().message(Msg.success(com.maxlananas.fawebim.core.tool.Tools.boundLine(tool.name(), item)));
    }

    /**
     * Takes whatever is bound to the held item off it, its tool or its
     * brushes, as FAWE's /tool none and /brush none both do; FAWE's line says
     * which of the two it was.
     */
    private void unbindTool(Ctx ctx) {
        com.maxlananas.fawebim.core.session.ItemBinding old = ctx.session().unbind(ctx.actor().heldItem());
        boolean brush = old != null && old.hasBrush();
        ctx.actor().message(Msg.success((brush ? "Brush" : "Tool") + " unbound from your current item"));
    }

    private void registerTools() {
        CommandRegistry.Entry e100 = registry.register("/tool", "//tool");
        e100.description = "Binds a tool to the item in your hand";
        e100.group = "tool";
        // A tool is bound to the item in a hand, as every one of WorldEdit's
        // tool commands takes a player: a console has no hand to bind it to.
        e100.requiresPlayer = true;
        e100.arguments.add("[" + String.join("|", com.maxlananas.fawebim.core.tool.Tools.NAMES) + "]");
        e100.arguments.add("[target]");
        e100.handler = ctx -> {
                    String type = ctx.arg(0, "none").toLowerCase(Locale.ROOT);
                    if (type.equals("none")) {
                        unbindTool(ctx);
                        return;
                    }
                    bindTool(ctx, type, 1);
                };

        // Each tool is a sub-command of its own, as in FAWE, with the arguments
        // and the description FAWE gives it: //help, the usage of a line short
        // of an argument and tab completion show what the tool takes.
        CommandRegistry.Entry none = registry.registerUnlessPresent("/tool none", "/tool unbind");
        if (none != null) {
            none.description = "Unbind a bound tool from your current item";
            none.group = "tool";
            none.requiresPlayer = true;
            none.handler = this::unbindTool;
        }
        for (com.maxlananas.fawebim.core.tool.Tools.Kind kind : com.maxlananas.fawebim.core.tool.Tools.KINDS) {
            String[] aliases = kind.aliases().stream().map(alias -> "/tool " + alias).toArray(String[]::new);
            CommandRegistry.Entry entry = registry.registerUnlessPresent("/tool " + kind.name(), aliases);
            if (entry == null) {
                continue;
            }
            entry.description = kind.description();
            entry.group = "tool";
            entry.requiresPlayer = true;
            entry.arguments.addAll(kind.arguments());
            entry.handler = ctx -> bindTool(ctx, kind.name(), 0);
        }


        CommandRegistry.Entry e101 = registry.register("/superpickaxe", "/sp", "//sp");
        e101.description = "Super-pickaxe: single, area <range>, recursive <range>";
        e101.group = "tool";
        e101.arguments.add("[single|area|recursive|recur|off]");
        e101.arguments.add("[range]");
        e101.handler = ctx -> {
                    String mode = ctx.arg(0, "area").toLowerCase(Locale.ROOT);
                    LocalSession session = ctx.session();
                    switch (mode) {
                        case "single" -> {
                            session.setSuperPickaxeMode(com.maxlananas.fawebim.core.tool.SuperPickaxe.SINGLE);
                            session.setSuperPickaxeEnabled(true);
                            ctx.actor().message(Msg.success("Super pickaxe: single block"));
                        }
                        case "area" -> {
                            int range = ctx.intArg(1, 1);
                            com.maxlananas.fawebim.core.tool.SuperPickaxe.checkRange(range);
                            session.setSuperPickaxeMode(com.maxlananas.fawebim.core.tool.SuperPickaxe.AREA);
                            session.setSuperPickaxeRange(range);
                            session.setSuperPickaxeEnabled(true);
                            ctx.actor().message(Msg.success("Super pickaxe: area of range " + Msg.value(range).raw()));
                        }
                        case "recursive", "recur" -> {
                            double range = ctx.doubleArg(1, 1);
                            com.maxlananas.fawebim.core.tool.SuperPickaxe.checkRange(range);
                            session.setSuperPickaxeMode(com.maxlananas.fawebim.core.tool.SuperPickaxe.RECURSIVE);
                            session.setSuperPickaxeRange(range);
                            session.setSuperPickaxeEnabled(true);
                            ctx.actor().message(Msg.success("Super pickaxe: recursive, range "
                                    + Msg.value(Msg.formatDouble(range)).raw()));
                        }
                        case "off" -> {
                            session.setSuperPickaxeEnabled(false);
                            ctx.actor().message(Msg.success("Super pickaxe disabled"));
                        }
                        default -> throw CommandRegistry.error(
                                "Usage: /sp single|area <range>|recursive <range>|off");
                    }
                };


    }

    /** {@code /brush none} — unbinds the brush from the held item. */
    private void registerBrushNone() {
        CommandRegistry.Entry none = registry.registerUnlessPresent("/brush none", "/brush unbind");
        if (none == null) {
            return;
        }
        none.description = "Unbind a bound brush from your current item";
        none.group = "brush";
        none.requiresPlayer = true;
        none.handler = this::unbindTool;
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
                    file = com.maxlananas.fawebim.core.brush.BrushPresets.save(ctx.session(), ctx.actor().heldItem(),
                            ctx.arg(0));
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
                ctx.actor().message(page.header("Brush presets", presets.size()));
                for (String preset : presets.subList(page.from(), page.to())) {
                    ctx.actor().message(Msg.item(preset));
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
        // With the line that built it, which the preset commands save and reload.
        com.maxlananas.fawebim.core.brush.BrushFactory.bind(session, built, ctx.actor(), buildBrushLine(ctx));
        ctx.actor().message(Msg.success("Brush '" + row[0] + "' equipped (radius " + Msg.formatDouble(radius) + ")"));
    }

    /**
     * The line that binds the brush again, for a preset: the brush's own
     * command and every word typed after it, switches included. It was
     * "brush" and the arguments alone - no brush name, no switches - so a
     * preset loaded nothing, and a brush bound without an argument failed
     * after it was bound.
     */
    private static String buildBrushLine(Ctx ctx) {
        String tail = ctx.tail();
        return ctx.entry().name + (tail.isEmpty() ? "" : " " + tail);
    }
}
