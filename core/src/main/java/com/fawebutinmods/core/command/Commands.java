package com.fawebutinmods.core.command;

import com.fawebutinmods.core.actor.Navigation;
import com.fawebutinmods.core.clipboard.BlockArrayClipboard;
import com.fawebutinmods.core.clipboard.Schematics;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.mask.Masks;
import com.fawebutinmods.core.math.BlockVector2;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector3;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.pattern.Patterns;
import com.fawebutinmods.core.region.Region;
import com.fawebutinmods.core.region.RegionSelector;
import com.fawebutinmods.core.session.LocalSession;
import com.fawebutinmods.core.transform.Transforms;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.util.Str;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;
import com.fawebutinmods.core.world.Direction;

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
                    selector.selectPrimary(pos, com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 1 set to ").append(Msg.value(pos)));
                };


        CommandRegistry.Entry e2 = registry.register("//pos2", "//p2");
        e2.description = "Set position 2 to your position or the given coordinates";
        e2.group = "selection";
        e2.arguments.add("[coordinates]");
        e2.handler = ctx -> {
                    BlockVector3 pos = ctx.args().isEmpty() ? ctx.actor().position() : ctx.blockVector(0);
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectSecondary(pos, com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 2 set to ").append(Msg.value(pos)));
                };


        CommandRegistry.Entry e3 = registry.register("//hpos1");
        e3.description = "Set position 1 to the block you are looking at";
        e3.group = "selection";
        e3.requiresPlayer = true;
        e3.handler = ctx -> {
                    BlockVector3 target = ctx.world().getTargetBlock(ctx.actor(), 100);
                    ctx.session().getSelector(ctx.world()).selectPrimary(target,
                            com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 1 set to ").append(Msg.value(target)));
                };


        CommandRegistry.Entry e4 = registry.register("//hpos2");
        e4.description = "Set position 2 to the block you are looking at";
        e4.group = "selection";
        e4.requiresPlayer = true;
        e4.handler = ctx -> {
                    BlockVector3 target = ctx.world().getTargetBlock(ctx.actor(), 100);
                    ctx.session().getSelector(ctx.world()).selectSecondary(target,
                            com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Position 2 set to ").append(Msg.value(target)));
                };


        CommandRegistry.Entry e5 = registry.register("//pos");
        e5.description = "Set both positions to your position";
        e5.group = "selection";
        e5.requiresPlayer = true;
        e5.handler = ctx -> {
                    BlockVector3 pos = ctx.actor().position();
                    ctx.session().getSelector(ctx.world()).selectPrimary(pos,
                            com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    ctx.session().getSelector(ctx.world()).selectSecondary(pos,
                            com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    ctx.actor().message(Msg.info("Both positions set to ").append(Msg.value(pos)));
                };


        CommandRegistry.Entry e6 = registry.register("//sel");
        e6.description = "Choose the selection type: cuboid, extend, poly, ellipsoid, sphere, cyl, convex";
        e6.group = "selection";
        e6.arguments.add("type");
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
                    ctx.actor().message(Msg.success("Selection type set to " + selector.getTypeName()));
                };


        CommandRegistry.Entry e7 = registry.register("//wand");
        e7.description = "Give yourself the selection wand";
        e7.group = "selection";
        e7.requiresPlayer = true;
        e7.handler = ctx -> {
                    String item = com.fawebutinmods.core.platform.Config.get().wandItem;
                    if (ctx.actor().giveWand(item)) {
                        ctx.actor().message(Msg.success("Wand given: " + item));
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
        e11.handler = ctx -> {
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
        e13.handler = ctx -> {
                    java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
                    for (BlockVector3 position : ctx.selection()) {
                        int state = ctx.world().getBlock(position.x(), position.y(), position.z());
                        counts.merge(state, 1, Integer::sum);
                    }
                    final long total = counts.values().stream().mapToLong(Integer::longValue).sum();
                    ctx.actor().message(Msg.info("Block distribution (" + Msg.formatNumber(total) + " blocks)"));
                    BlockStateRegistry blockRegistry = BlockState.registry();
                    counts.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                            .limit(20)
                            .forEach(entry -> ctx.actor().message(Msg.of("§7 - §f"
                                    + blockRegistry.describe(entry.getKey()) + " §7= §b" + entry.getValue()
                                    + " §7(" + String.format(Locale.ROOT, "%.2f",
                                    entry.getValue() * 100.0 / Math.max(1, total)) + "%)")));
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
        e17.arguments.add("[amount]");
        e17.handler = ctx -> {
                    int amount = ctx.intArg(0, 1);
                    Region region = ctx.selection();
                    region.expand(new BlockVector3(amount, 0, amount));
                    region.expand(new BlockVector3(0, amount, 0));
                    ctx.actor().message(Msg.success("Region outset: " + region.describe()));
                };


        CommandRegistry.Entry e18 = registry.register("//inset");
        e18.description = "Inset the selection area";
        e18.group = "selection";
        e18.requiresSelection = true;
        e18.arguments.add("amount");
        e18.handler = ctx -> {
                    int amount = ctx.intArg(0);
                    Region region = ctx.selection();
                    region.contract(new BlockVector3(amount, 0, amount));
                    ctx.actor().message(Msg.success("Region inset: " + region.describe()));
                };

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
        e25.arguments.add("[thickness]");
        e25.arguments.add("[pattern]");
        e25.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int thickness = ctx.args().isEmpty() ? (ctx.hasFlag("h") ? 1 : 0) : ctx.intArg(0);
                    Pattern pattern = ctx.args().size() > 1 ? Parsers.pattern(ctx.joined(1), ctx) : null;
                    Region region = ctx.selection();
                    int depth = Math.max(1, thickness);
                    for (BlockVector3 position : region) {
                        int distance = distanceToEdge(region, position);
                        if (distance < depth) {
                            if (pattern != null) {
                                session.setBlock(position.x(), position.y(), position.z(),
                                        pattern.apply(position.x(), position.y(), position.z()));
                            } else {
                                session.setBlock(position.x(), position.y(), position.z(), air());
                            }
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
        e27.booleanFlags.add("m");
        e27.booleanFlags.add("l");
        e27.arguments.add("[iterations]");
        e27.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int iterations = ctx.intArg(0, 1);
                    int changed = com.fawebutinmods.core.function.Operations.smooth(ctx.world(), session,
                            ctx.selection(), iterations);
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
                    int changed = com.fawebutinmods.core.function.Operations.floodFill(ctx.world(), session,
                            start, pattern, radius, ctx.hasFlag("h"));
                    ctx.actor().message(Msg.success("Filled " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e31 = registry.register("//drain");
        e31.description = "Drain liquids in the selection";
        e31.group = "region";
        e31.requiresSelection = true;
        e31.arguments.add("[radius]");
        e31.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    BlockVector3 start = ctx.actor().position() != null
                            ? ctx.actor().position() : ctx.selection().getMinimumPoint();
                    int changed = com.fawebutinmods.core.function.Operations.drain(ctx.world(), session, start,
                            new Masks.LiquidMask(session), ctx.intArg(0, 256));
                    ctx.actor().message(Msg.success("Drained " + Msg.formatNumber(changed) + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e32 = registry.register("//regen");
        e32.description = "Regenerate the selection from the world seed";
        e32.group = "region";
        e32.requiresSelection = true;
        e32.booleanFlags.add("b");
        e32.booleanFlags.add("s");
        e32.handler = ctx -> {
                    Region region = ctx.selection();
                    for (BlockVector2 chunk : region.getChunks()) {
                        ctx.world().loadChunk(chunk.x(), chunk.z());
                        ctx.world().regenerateChunk(chunk.x(), chunk.z(), new com.fawebutinmods.core.world.RegenOptions()
                                .setRegenBiomes(ctx.hasFlag("b")));
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
        e37.arguments.add("[pattern]");
        e37.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int snow = BlockState.registry().defaultState("minecraft:snow_block");
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
        e39.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockStateRegistry blockRegistry = BlockState.registry();
                    Mask dirt = Parsers.mask("minecraft:dirt,minecraft:coarse_dirt", ctx);
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
                    int changed = com.fawebutinmods.core.function.Operations.fixLiquid(ctx.world(), session,
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
                    int changed = com.fawebutinmods.core.function.Operations.fixLiquid(ctx.world(), session,
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
        e43.arguments.add("amount");
        e43.arguments.add("direction");
        e43.arguments.add("[pattern]");
        e43.handler = ctx -> {
                    Region region = ctx.selection();
                    int amount = ctx.intArg(0);
                    BlockVector3 offset = directionVector(ctx, ctx.arg(1), amount);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    BlockArrayClipboard clipboard = com.fawebutinmods.core.clipboard.Clipboards.copy(ctx.world(),
                            region, session);
                    Pattern pattern = ctx.args().size() > 2 ? Parsers.pattern(ctx.joined(2), ctx) : null;
                    // Clear the source region.
                    for (BlockVector3 position : region) {
                        session.setBlock(position.x(), position.y(), position.z(), air(), false);
                    }
                    // Paste at the offset.
                    for (BlockVector3 position : clipboard.positions()) {
                        int state = clipboard.getBlock(position);
                        int x = position.x() + offset.x();
                        int y = position.y() + offset.y();
                        int z = position.z() + offset.z();
                        if (BlockState.registry().isAirLike(state) && pattern != null) {
                            state = pattern.apply(x, y, z);
                        }
                        session.setBlock(x, y, z, state);
                    }
                    flush(ctx, session);
                };


        CommandRegistry.Entry e44 = registry.register("//stack");
        e44.description = "Stack the selection's contents";
        e44.group = "region";
        e44.requiresSelection = true;
        e44.booleanFlags.add("s");
        e44.booleanFlags.add("a");
        e44.arguments.add("[count]");
        e44.arguments.add("[direction]");
        e44.handler = ctx -> {
                    Region region = ctx.selection();
                    int count = ctx.intArg(0, 1);
                    String dir = ctx.arg(1, "me");
                    Direction direction = dir.equalsIgnoreCase("me") ? ctx.actor().facing() : Direction.parse(dir);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    BlockArrayClipboard original = com.fawebutinmods.core.clipboard.Clipboards.copy(ctx.world(), region,
                            session);
                    for (int i = 1; i <= count; i++) {
                        int dx = direction.x() * region.getHeight() * i;
                        int dy = direction.y() * region.getHeight() * i;
                        int dz = direction.z() * region.getHeight() * i;
                        for (BlockVector3 position : original.positions()) {
                            session.setBlock(position.x() + dx, position.y() + dy, position.z() + dz,
                                    original.getBlock(position));
                        }
                    }
                    flush(ctx, session);
                };

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
                    int changed = com.fawebutinmods.core.function.Operations.line(session, min, max, pattern, thickness,
                            ctx.hasFlag("h"));
                    ctx.actor().message(Msg.success("Drew " + changed + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e46 = registry.register("//curve");
        e46.description = "Draw a spline through the convex selection's vertices";
        e46.group = "generation";
        e46.requiresSelection = true;
        e46.arguments.add("pattern");
        e46.arguments.add("[thickness]");
        e46.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern pattern = Parsers.pattern(ctx.arg(0), ctx);
                    double thickness = ctx.doubleArg(1, 0);
                    Region region = ctx.selection();
                    List<BlockVector3> points = region instanceof com.fawebutinmods.core.region.ConvexPolyhedralRegion convex
                            ? convex.getVertices() : List.of(region.getMinimumPoint(), region.getMaximumPoint());
                    int changed = com.fawebutinmods.core.function.Operations.spline(session, points, pattern, thickness);
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
        e48.arguments.add("expression");
        e48.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    String expression = ctx.joined(0);
                    int changed = com.fawebutinmods.core.function.Operations.deform(ctx.world(), session,
                            ctx.selection(), expression);
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
                    int changed = com.fawebutinmods.core.function.Operations.flora(ctx.world(), session,
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
                    int changed = com.fawebutinmods.core.function.Operations.removeTree(ctx.world(), session, target);
                    ctx.actor().message(Msg.success("Removed " + changed + " block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e53 = registry.register("//ore", "//ores");
        e53.description = "Generate ores in the selection";
        e53.group = "generation";
        e53.requiresSelection = true;
        e53.arguments.add("pattern");
        e53.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    Pattern ore = Parsers.pattern(ctx.arg(0), ctx);
                    int changed = com.fawebutinmods.core.function.Operations.ore(ctx.world(), session,
                            ctx.selection(), ore, new java.util.Random());
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
                    int changed = com.fawebutinmods.core.function.Operations.caves(ctx.world(), session,
                            ctx.selection(), new java.util.Random(),
                            ctx.intArg(0, 40), ctx.doubleArg(1, 0.5), ctx.intArg(2, 8));
                    ctx.actor().message(Msg.success("Generated " + Msg.formatNumber(changed) + " cave block(s)"));
                    flush(ctx, session);
                };


        CommandRegistry.Entry e55 = registry.register("//fall");
        e55.description = "Make blocks fall";
        e55.group = "generation";
        e55.requiresSelection = true;
        e55.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    int changed = com.fawebutinmods.core.function.Operations.fall(ctx.world(), session, ctx.selection());
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
                        int changed = kind.equals("sphere")
                                ? com.fawebutinmods.core.function.Operations.sphere(session, origin, radius, pattern,
                                hollowShape)
                                : com.fawebutinmods.core.function.Operations.cylinder(session, origin,
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
                    int changed = com.fawebutinmods.core.function.Operations.pyramid(session, ctx.actor().position(),
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
                    int changed = com.fawebutinmods.core.function.Operations.cone(session, ctx.actor().position(),
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
        e59.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockArrayClipboard clipboard = com.fawebutinmods.core.clipboard.Clipboards.copy(ctx.world(),
                            ctx.selection(), session, ctx.hasFlag("e"));
                    ctx.session().setClipboard(clipboard);
                    ctx.actor().message(Msg.success("Copied " + Msg.formatNumber(clipboard.volume())
                            + " block(s) (" + clipboard.entities().size() + " entities)"));
                };


        CommandRegistry.Entry e60 = registry.register("//cut");
        e60.description = "Cut the selection to your clipboard";
        e60.group = "clipboard";
        e60.requiresSelection = true;
        e60.booleanFlags.add("e");
        e60.booleanFlags.add("r");
        e60.handler = ctx -> {
                    EditSession session = ctx.editSession();
                    BlockArrayClipboard clipboard = com.fawebutinmods.core.clipboard.Clipboards.copy(ctx.world(),
                            ctx.selection(), session, ctx.hasFlag("e"));
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
        e61.arguments.add("[destination]");
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
                        holder = new com.fawebutinmods.core.session.ClipboardHolder(pick);
                        if (ctx.session().isClipboardPoolRandomRotation()) {
                            holder.setTransform(Transforms.rotate(pick.getOrigin(), java.util.concurrent.ThreadLocalRandom
                                    .current().nextInt(4) * 90.0));
                        }
                    }
                    BlockArrayClipboard clipboard = holder.getClipboard();
                    BlockVector3 destination = ctx.args().isEmpty()
                            ? (ctx.session().shouldPlaceAtPos1()
                            ? ctx.session().getSelector(ctx.world()).getRegion().getMinimumPoint()
                            : ctx.actor().position())
                            : ctx.blockVector(0);
                    EditSession session = ctx.editSession();
                    Masks.ExtentHolder.set(session);
                    int changed = com.fawebutinmods.core.clipboard.Clipboards.paste(clipboard, destination, session,
                            holder.getTransform(), ctx.hasFlag("a"), ctx.hasFlag("o"), ctx.hasFlag("s"));
                    if (ctx.hasFlag("s")) {
                        ctx.session().getSelector(ctx.world()).selectPrimary(destination,
                                com.fawebutinmods.core.region.SelectorLimits.unlimited());
                        ctx.session().getSelector(ctx.world()).selectSecondary(
                                destination.add(clipboard.getWidth(), clipboard.getHeight(), clipboard.getLength()),
                                com.fawebutinmods.core.region.SelectorLimits.unlimited());
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
                            com.fawebutinmods.core.transform.Transforms.rotate(origin, angle)));
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
                    com.fawebutinmods.core.transform.Axis axis = direction.equalsIgnoreCase("me")
                            ? switch (ctx.actor().facing()) {
                        case NORTH, SOUTH -> com.fawebutinmods.core.transform.Axis.Z;
                        case EAST, WEST -> com.fawebutinmods.core.transform.Axis.X;
                        default -> com.fawebutinmods.core.transform.Axis.Y;
                    } : com.fawebutinmods.core.transform.Axis.parse(direction);
                    var holder = ctx.session().getClipboard();
                    holder.setTransform(holder.getTransform().combine(
                            com.fawebutinmods.core.transform.Transforms.flip(holder.getClipboard().getOrigin(), axis)));
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
        e65.arguments.add("list|ls|all|save|load|loadall|delete|d|formats|listformats|f|move|m|share|clear|unload");
        e65.arguments.add("[name]");
        e65.arguments.add("[format]");
        e65.handler = ctx -> {
                    String action = ctx.arg(0).toLowerCase(Locale.ROOT);
                    if (action.equals("ls") || action.equals("all")) {
                        action = "list";
                    }
                    switch (action) {
                        case "list" -> {
                            List<String> names = Schematics.list(ctx.session().getListFilter(),
                                    ctx.actor().isPlayer() ? ctx.actor().name() : null);
                            ctx.actor().message(Msg.info("Schematics (" + names.size() + ", "
                                    + ctx.session().getListFilter().describe() + "):"));
                            for (String name : names) {
                                ctx.actor().message(Msg.of("§7 - §f" + name));
                            }
                        }
                        case "save" -> {
                            if (!ctx.session().hasClipboard()) {
                                throw CommandRegistry.error("No clipboard: copy something first");
                            }
                            String name = ctx.arg(1);
                            String format = ctx.arg(2, "sponge.3");
                            Schematics.save(ctx.session().getClipboard().getClipboard(), name, format);
                            ctx.actor().message(Msg.success("Saved schematic '" + name + "'"));
                        }
                        case "load" -> {
                            String name = ctx.arg(1);
                            BlockArrayClipboard clipboard = Schematics.load(name);
                            ctx.session().setClipboard(clipboard);
                            ctx.actor().message(Msg.success("Loaded schematic '" + name + "' ("
                                    + Msg.formatNumber(clipboard.volume()) + " blocks)"));
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
                            String format = ctx.arg(2, com.fawebutinmods.core.platform.Config.get().defaultSchematicFormat);
                            com.fawebutinmods.core.clipboard.BlockArrayClipboard converted = Schematics.load(name);
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
                                    com.fawebutinmods.core.platform.Config.get().defaultSchematicFormat);
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
                            String format = ctx.arg(1, com.fawebutinmods.core.platform.Config.get()
                                    .defaultSchematicFormat);
                            String filter = ctx.arg(2, "*");
                            java.util.List<com.fawebutinmods.core.clipboard.BlockArrayClipboard> loaded =
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
                                    + " (default: " + com.fawebutinmods.core.platform.Config.get().defaultSchematicFormat + ")"));
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
                        undone += record.changeCount();
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
        e69.arguments.add("biome");
        e69.handler = ctx -> {
                    int biomeId = BlockState.registry().biome(ctx.arg(0));
                    if (biomeId < 0) {
                        throw CommandRegistry.error("Unknown biome '" + ctx.arg(0) + "'");
                    }
                    EditSession session = ctx.editSession();
                    int changed = 0;
                    for (BlockVector3 position : ctx.selection()) {
                        if (session.setBiome(position.x(), position.y(), position.z(), biomeId)) {
                            changed++;
                        }
                    }
                    session.flushQueue();
                    ctx.actor().message(Msg.success("Changed biome of " + Msg.formatNumber(changed) + " column(s)"));
                };


        CommandRegistry.Entry e70 = registry.register("//biomelist");
        e70.description = "List available biomes";
        e70.group = "biome";
        e70.handler = ctx -> {
                    List<String> biomes = BlockState.registry().biomeNames();
                    ctx.actor().message(Msg.info("Biomes (" + biomes.size() + "): " + Str.limit(String.join(", ", biomes), 2000)));
                };


        CommandRegistry.Entry e71 = registry.register("//biomeinfo", "//biomeinfo -p");
        e71.description = "Show the biome you are standing in";
        e71.group = "biome";
        e71.requiresPlayer = true;
        e71.handler = ctx -> {
                    BlockVector3 pos = ctx.actor().position();
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
        e73.handler = ctx -> {
                    List<BlockVector2> chunks = ctx.selection().getChunks();
                    StringBuilder sb = new StringBuilder("Chunks (" + chunks.size() + "): ");
                    for (int i = 0; i < Math.min(chunks.size(), 40); i++) {
                        sb.append(chunks.get(i).x()).append(',').append(chunks.get(i).z()).append(' ');
                    }
                    ctx.actor().message(Msg.info(sb.toString()));
                };


        CommandRegistry.Entry e74 = registry.register("//delchunks");
        e74.description = "Delete the chunks in the selection (regenerates them)";
        e74.group = "chunk";
        e74.requiresSelection = true;
        e74.handler = ctx -> {
                    int count = 0;
                    for (BlockVector2 chunk : ctx.selection().getChunks()) {
                        if (ctx.world().regenerateChunk(chunk.x(), chunk.z(),
                                new com.fawebutinmods.core.world.RegenOptions())) {
                            count++;
                        }
                    }
                    ctx.actor().message(Msg.success("Deleted " + count + " chunk(s)"));
                };


        CommandRegistry.Entry e75 = registry.register("//chunk");
        e75.description = "Select the chunk you are standing in";
        e75.group = "chunk";
        e75.requiresPlayer = true;
        e75.handler = ctx -> {
                    BlockVector3 pos = ctx.actor().position();
                    int cx = pos.x() >> 4;
                    int cz = pos.z() >> 4;
                    RegionSelector selector = ctx.session().getSelector(ctx.world());
                    selector.selectPrimary(new BlockVector3(cx << 4, ctx.world().minY(), cz << 4),
                            com.fawebutinmods.core.region.SelectorLimits.unlimited());
                    selector.selectSecondary(new BlockVector3((cx << 4) + 15, ctx.world().maxY(), (cz << 4) + 15),
                            com.fawebutinmods.core.region.SelectorLimits.unlimited());
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


        CommandRegistry.Entry e83 = registry.register("/threads");
        e83.description = "Show the worker thread pool size";
        e83.group = "utility";
        e83.arguments.add("[count]");
        e83.handler = ctx -> {
                    if (ctx.args().isEmpty()) {
                        ctx.actor().message(Msg.keyValue("Threads",
                                com.fawebutinmods.core.platform.Config.get().threads));
                    } else {
                        com.fawebutinmods.core.platform.Config.get().threads = ctx.intArg(0);
                        ctx.actor().message(Msg.success("Threads set to " + ctx.intArg(0)));
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
                        ctx.session().setMaxBlocksChanged(ctx.intArg(0));
                        ctx.actor().message(Msg.success("Limit set to " + ctx.intArg(0)));
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
                    com.fawebutinmods.core.transform.Transforms.Set set =
                            new com.fawebutinmods.core.transform.Transforms.Set();
                    set.add(com.fawebutinmods.core.transform.Transforms.rotate(BlockVector3.ZERO,
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
                                + com.fawebutinmods.core.platform.Config.VERSION
                                + " for Minecraft " + com.fawebutinmods.core.platform.Config.MINECRAFT_VERSION
                                + " (WorldEdit/FAWE command surface 7.3.17)"));
                        case "reload" -> {
                            com.fawebutinmods.core.platform.Config.get().reload();
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
        e95.arguments.add("[filter]");
        e95.handler = ctx -> {
                    String filter = ctx.arg(0, "").toLowerCase(Locale.ROOT);
                    int shown = 0;
                    for (CommandRegistry.Entry entry : registry.all()) {
                        if (!filter.isEmpty() && !entry.name.toLowerCase(Locale.ROOT).contains(filter)
                                && !entry.description.toLowerCase(Locale.ROOT).contains(filter)) {
                            continue;
                        }
                        if (entry.status.equals("stub")) {
                            continue;
                        }
                        ctx.actor().message(Msg.of("§b" + entry.usage() + " §7- §f" + entry.description));
                        shown++;
                        if (shown > 60) {
                            ctx.actor().message(Msg.info("... and more, see docs/COMMANDS.md"));
                            break;
                        }
                    }
                };


        CommandRegistry.Entry e96 = registry.register("//version");
        e96.description = "Show the mod version";
        e96.group = "utility";
        e96.handler = ctx -> ctx.actor().message(Msg.info("FAWE-BIM " + com.fawebutinmods.core.platform.Config.VERSION
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
        // The brush list lives next to the factory that builds the brushes, so a
        // brush is registered as soon as it can be created.
        for (String[] brush : com.fawebutinmods.core.brush.BrushFactory.COMMANDS) {
            CommandRegistry.Entry entry = registry.registerUnlessPresent("/brush " + brush[0], "//brush " + brush[0]);
            if (entry == null) {
                continue;
            }
            entry.description = "Brush: " + brush[0];
            entry.group = "brush";
            entry.requiresPlayer = true;
            entry.arguments.add("radius");
            entry.arguments.add("[pattern]");
            if (brush[0].equals("heightmap")) {
                entry.arguments.add("image");
                entry.arguments.add("[yscale]");
            }
            entry.handler = ctx -> bindBrush(ctx, brush);
        }

        registerBrushPresets();
        registerBrushNone();

        CommandRegistry.Entry e99 = registry.register("/brush", "//brush", "/br");
        e99.description = "Show the current brush";
        e99.group = "brush";
        e99.handler = ctx -> {
                    var brush = com.fawebutinmods.core.brush.BrushFactory.current(ctx.session());
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
        e100.arguments.add("[" + String.join("|", com.fawebutinmods.core.tool.Tools.NAMES) + "]");
        e100.arguments.add("[target]");
        e100.handler = ctx -> {
                    String type = ctx.arg(0, "none").toLowerCase(Locale.ROOT);
                    if (type.equals("none")) {
                        com.fawebutinmods.core.tool.Tools.clear(ctx.session());
                        ctx.actor().message(Msg.success("Tool unbound"));
                        return;
                    }
                    com.fawebutinmods.core.tool.Tool tool = com.fawebutinmods.core.tool.Tools.create(type, ctx);
                    if (tool == null) {
                        throw CommandRegistry.error("Unknown tool '" + type + "'");
                    }
                    com.fawebutinmods.core.tool.Tools.bind(ctx.session(), tool, ctx.actor(), ctx.arg(1, ""));
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


        CommandRegistry.Entry e102 = registry.register("/tool mask", "/tool material", "/tool range", "/tool size", "/tool tracemask");
        e102.description = "Tool configuration sub-commands";
        e102.group = "tool";
        e102.arguments.add("[arguments]");
        e102.handler = ctx -> ctx.actor().message(Msg.info("Tool configuration: " + ctx.entry().name));

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
            com.fawebutinmods.core.brush.BrushFactory.unbind(ctx.session());
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
            save.handler = ctx -> {
                java.nio.file.Path file;
                try {
                    file = com.fawebutinmods.core.brush.BrushPresets.save(ctx.session(), ctx.arg(0));
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
                    line = com.fawebutinmods.core.brush.BrushPresets.load(ctx.arg(0));
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
            list.handler = ctx -> {
                java.util.List<String> presets = com.fawebutinmods.core.brush.BrushPresets.list();
                if (presets.isEmpty()) {
                    ctx.actor().message(Msg.info("No brush preset saved yet"));
                    return;
                }
                ctx.actor().message(Msg.info("Brush presets (" + presets.size() + "):"));
                for (String preset : presets) {
                    ctx.actor().message(Msg.of("\u00a77 - \u00a7f" + preset));
                }
            };
        }
    }

    /**
     * Binds the brush a {@code /brush <name>} sub-command built, with the radius
     * and pattern the player gave.
     */
    private static void bindBrush(Ctx ctx, String[] brush) {
        LocalSession session = ctx.session();
        double radius = ctx.doubleArg(0, 5);
        if (radius > session.getMaxBrushRadius()) {
            throw CommandRegistry.error("Maximum brush radius is " + session.getMaxBrushRadius());
        }
        String patternArg = ctx.arg(1, "#clipboard");
        Pattern pattern = patternArg.startsWith("#clipboard") ? null : Parsers.pattern(patternArg, ctx);
        com.fawebutinmods.core.brush.Brush built =
                com.fawebutinmods.core.brush.BrushFactory.create(brush[1], radius, pattern, ctx);
        if (built == null) {
            throw CommandRegistry.error("Brush '" + brush[0] + "' could not be created");
        }
        com.fawebutinmods.core.brush.BrushFactory.bind(session, built, ctx.actor());
        // Remembered so the preset commands can save and reload it.
        session.getBindings().put("brush-command", buildBrushLine(ctx));
        ctx.actor().message(Msg.success("Brush '" + brush[0] + "' equipped (radius " + radius + ")"));
    }

    private static String buildBrushLine(Ctx ctx) {
        StringBuilder line = new StringBuilder("brush ").append(ctx.arg(0));
        for (int i = 1; i < ctx.args().size(); i++) {
            line.append(' ').append(ctx.arg(i));
        }
        return line.toString();
    }
}
