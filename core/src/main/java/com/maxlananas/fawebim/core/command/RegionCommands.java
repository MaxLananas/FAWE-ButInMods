package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.brush.Creatures;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.EntityRemovers;
import com.maxlananas.fawebim.core.function.HeightMaps;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.session.SideEffect;
import com.maxlananas.fawebim.core.session.SideEffectSet;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.noise.Noise;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.Extent;
import com.maxlananas.fawebim.core.world.World;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Region commands, mirroring WorldEdit's {@code RegionCommands}.
 *
 * <p>These are the operations that act on the current selection: setting and
 * replacing blocks, walls, light repair, trimming, entity removal and the
 * commands that apply side effects afterwards ({@code //update},
 * {@code //fixblocks}).</p>
 */
final class RegionCommands {

    private final CommandRegistry registry;

    RegionCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        air();
        test();
        fixLighting();
        removeLighting();
        setBlockLight();
        setSkyLight();
        nbtInfo();
        fixBlocks();
        worldEditAnywhere();
        worldEditRegion();
        update();
        snowSmooth();
        trim();
        remove();
        butcher();
        blob();
    }

    /** {@code //air} — clears the selection, the fast path of {@code //set air}. */
    private void air() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//air", "/0");
        if (entry == null) {
            return;
        }
        entry.description = "Sets all the blocks in the region to air";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.handler = ctx -> {
            EditSession session = ctx.editSession();
            Region region = ctx.selection();
            Pattern air = new Patterns.Single(BlockState.registry().air());
            int changed = region.forEachPosition((x, y, z) -> {
                session.checkTimeout();
                return session.setBlock(x, y, z, air.apply(x, y, z));
            });
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " block(s) set to air"));
        };
    }

    /** {@code //test} — the upstream diagnostic command that echoes its argument. */
    private void test() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//test");
        if (entry == null) {
            return;
        }
        entry.description = "Prints the given value back, used to check command parsing";
        entry.group = "region";
        entry.arguments.add("value");
        entry.handler = ctx -> ctx.actor().message(Msg.info("test: " + ctx.arg(0)));
    }

    /** {@code //fixlighting} — relights the selection after a bulk edit. */
    private void fixLighting() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//fixlighting");
        if (entry == null) {
            return;
        }
        entry.description = "Propagate lighting through the selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.handler = ctx -> relight(ctx, "Lighting fixed");
    }

    /** {@code //removelighting} — drops the cached light of the selection. */
    private void removeLighting() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//removelighting", "/removelight");
        if (entry == null) {
            return;
        }
        entry.description = "Remove lighting data from the selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.handler = ctx -> relight(ctx, "Lighting removed and recomputed");
    }

    private void setBlockLight() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//setblocklight");
        if (entry == null) {
            return;
        }
        entry.description = "Set the block light level in the selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.arguments.add("level");
        entry.handler = ctx -> {
            int level = ctx.intArg(0, 15);
            if (level < 0 || level > 15) {
                throw CommandRegistry.error("Light level must be between 0 and 15");
            }
            relight(ctx, "Block light set to " + level);
        };
    }

    private void setSkyLight() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//setskylight");
        if (entry == null) {
            return;
        }
        entry.description = "Set the sky light level in the selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.arguments.add("level");
        entry.handler = ctx -> {
            int level = ctx.intArg(0, 15);
            if (level < 0 || level > 15) {
                throw CommandRegistry.error("Light level must be between 0 and 15");
            }
            relight(ctx, "Sky light set to " + level);
        };
    }

    /**
     * Lighting is computed by the engine since 1.18: a mod cannot pin an exact
     * level, so the light data of the affected chunks is invalidated and rebuilt,
     * which is the only operation that has a visible effect.
     */
    private void relight(Ctx ctx, String message) {
        Region region = ctx.selection();
        World world = ctx.world();
        for (var chunk : region.getChunks()) {
            world.relight(List.of(chunk));
        }
        for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x += 16) {
            for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z += 16) {
                world.queueBlockUpdate(x, region.getMinimumPoint().y(), z);
            }
        }
        ctx.actor().message(Msg.success(message + " (light is engine-managed since 1.18, chunks relit)"));
    }

    /** {@code //nbtinfo} — dumps the block entity of the targeted block. */
    private void nbtInfo() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//nbtinfo");
        if (entry == null) {
            return;
        }
        entry.description = "Print the NBT of the block you are looking at";
        entry.group = "region";
        entry.requiresPlayer = true;
        entry.handler = ctx -> {
            BlockVector3 target = ctx.args().isEmpty()
                    ? ctx.targetBlock(100)
                    : ctx.blockVector(0);
            NbtCompound nbt = ctx.world().getBlockEntity(target.x(), target.y(), target.z());
            if (nbt == null) {
                ctx.actor().message(Msg.info("No block entity at " + target));
                return;
            }
            ctx.actor().message(Msg.info("NBT at " + target + ":"));
            for (var value : nbt.entries().entrySet()) {
                ctx.actor().message(Msg.of("§7" + value.getKey() + "§r: §f" + value.getValue()));
            }
        };
    }

    /**
     * {@code //fixblocks} — re-applies the state of every block in the selection
     * so that shapes and connections (fences, stairs, glass panes) are rebuilt
     * against their neighbours.
     */
    private void fixBlocks() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//fixblocks", "/updateblocks", "/fixconnect");
        if (entry == null) {
            return;
        }
        entry.description = "Fixes all blocks in the region to the correct shape and connections";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.handler = ctx -> {
            World world = ctx.world();
            Region region = ctx.selection();
            EditSession session = ctx.editSession("fixblocks");
            int changed = region.forEachPosition((x, y, z) -> {
                session.checkTimeout();
                int state = world.getBlock(x, y, z);
                if (state == BlockState.registry().air()) {
                    return false;
                }
                // Writing the value that is already there only triggers the
                // neighbour updates that recompute the block's shape.
                boolean wrote = session.setBlock(x, y, z, state, false);
                world.queueBlockUpdate(x, y, z);
                return wrote;
            });
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " block(s) updated"));
        };
    }

    private void worldEditAnywhere() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//wea", "/weanywhere", "/worldeditanywhere");
        if (entry == null) {
            return;
        }
        entry.description = "Bypass region restrictions (there are none in the standalone mod)";
        entry.group = "region";
        entry.handler = ctx -> ctx.actor().message(Msg.info(
                "Region restrictions are a plugin feature; a standalone mod has no regions to bypass"));
    }

    private void worldEditRegion() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//wer", "/worldeditregion");
        if (entry == null) {
            return;
        }
        entry.description = "Select your allowed region (there are none in the standalone mod)";
        entry.group = "region";
        entry.requiresPlayer = true;
        entry.handler = ctx -> ctx.actor().message(Msg.info(
                "Region restrictions are a plugin feature; a standalone mod has no regions to select"));
    }

    /**
     * {@code //update} — applies side effects to the selection.
     *
     * <p>The blocks do not change; what runs is the work an edit would have done
     * around them: lighting the chunks, telling the neighbours of every position,
     * and sending what changed back to the clients. Which of those run is what
     * the side effect list says, and without one the default set decides, which
     * is the lighting pass.</p>
     */
    private void update() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//update");
        if (entry == null) {
            return;
        }
        entry.description = "Apply side effects to your selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.arguments.add("[sideEffectSet]");
        entry.handler = ctx -> {
            World world = ctx.world();
            Region region = ctx.selection();
            SideEffectSet sideEffects = ctx.args().isEmpty()
                    ? SideEffectSet.defaults() : Parsers.sideEffectSet(ctx.arg(0));
            if (sideEffects.shouldApply(SideEffect.NEIGHBORS)) {
                region.forEachPosition((x, y, z) -> {
                    world.queueBlockUpdate(x, y, z);
                    return true;
                });
            }
            boolean lighting = sideEffects.shouldApply(SideEffect.LIGHTING);
            if (lighting || sideEffects.shouldApply(SideEffect.NETWORK)) {
                Set<BlockVector2> chunks = new LinkedHashSet<>();
                region.forEachPosition((x, y, z) -> {
                    chunks.add(new BlockVector2(x >> 4, z >> 4));
                    return true;
                });
                if (lighting) {
                    world.relight(chunks);
                }
                if (sideEffects.shouldApply(SideEffect.NETWORK)) {
                    world.resendChunks(chunks);
                }
            }
            ctx.actor().message(Msg.success("Applied side effects to the selection."));
        };
    }

    /** {@code //snowsmooth} — smooths snow, upstream runs {@code //smooth} with snow. */
    private void snowSmooth() {
        // Upstream spells this one with a single slash; players type it with the
        // usual double slash next to //smooth, so both are accepted.
        CommandRegistry.Entry entry = registry.registerUnlessPresent("snowsmooth", "/snowsmooth", "//snowsmooth");
        if (entry == null) {
            return;
        }
        entry.description = "Smooth the elevation in the selection with snow layers";
        entry.group = "region";
        entry.requiresSelection = true;
        // -l is the snow height to place back, -m restricts the pass to a mask.
        entry.valueFlags.add("l");
        entry.valueFlags.add("m");
        entry.arguments.add("[iterations]");
        entry.arguments.add("[-l <snowBlockCount>]");
        entry.arguments.add("[-m <mask>]");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            int iterations = Math.max(1, ctx.intArg(0, 1));
            // -l is how many full snow blocks sit under the layer, -m filters the
            // blocks the height map is built from: snow-only terrain is the
            // caller's choice rather than the default.
            int layerBlocks = Math.max(0, ctx.flagInt("l", 1));
            Mask heightMask = ctx.hasFlag("m") ? Parsers.mask(ctx.flagValue("m", ""), ctx) : null;
            EditSession session = ctx.editSession("snowsmooth");
            int changed = HeightMaps.snowSmooth(ctx.world(), session, region, iterations, layerBlocks, heightMask);
            session.flushQueue();
            ctx.actor().message(Msg.success("Smoothed " + changed + " snow block(s)"));
        };
    }

    /**
     * {@code //trim} — shrinks the selection to the blocks that match the mask,
     * which is how WorldEdit "minimises" a selection around a build.
     */
    private void trim() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//trim");
        if (entry == null) {
            return;
        }
        entry.description = "Minimize the selection to encompass matching blocks";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.arguments.add("[mask]");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            Mask mask = ctx.mask(0, null);
            World world = ctx.world();
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockVector3 position : region) {
                boolean matches = mask == null
                        ? !BlockState.registry().isAir(world.getBlock(position.x(), position.y(), position.z()))
                        : mask.test(position);
                if (!matches) {
                    continue;
                }
                minX = Math.min(minX, position.x());
                minY = Math.min(minY, position.y());
                minZ = Math.min(minZ, position.z());
                maxX = Math.max(maxX, position.x());
                maxY = Math.max(maxY, position.y());
                maxZ = Math.max(maxZ, position.z());
            }
            if (minX == Integer.MAX_VALUE) {
                throw CommandRegistry.error("Nothing in the selection matches");
            }
            BlockVector3 first = new BlockVector3(minX, minY, minZ);
            BlockVector3 second = new BlockVector3(maxX, maxY, maxZ);
            var selector = ctx.session().getSelector(world);
            selector.selectPrimary(first, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
            selector.selectSecondary(second, com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
            ctx.actor().updateSelectionOutline();
            ctx.actor().message(Msg.success("Selection trimmed to " + first + " - " + second));
        };
    }

    /**
     * {@code /remove} — removes the entities of one type around the player, which
     * is WorldEdit's {@code EntityRemover}: a radius of {@code -1} reaches every
     * loaded chunk, and anything smaller is a cylinder of that radius by the full
     * height of the world.
     */
    private void remove() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("remove", "rem", "rement", "/remove", "/rem", "/rement");
        if (entry == null) {
            return;
        }
        entry.description = "Remove all entities of a type";
        entry.group = "region";
        entry.arguments.add("<type>");
        entry.arguments.add("[radius]");
        entry.handler = ctx -> {
            EntityRemovers.Type type = EntityRemovers.find(ctx.arg(0));
            if (type == null) {
                throw CommandRegistry.error("Acceptable types: " + EntityRemovers.keywords());
            }
            int radius = ctx.intArg(1, 5);
            if (radius < -1) {
                throw CommandRegistry.error("Use -1 to remove all entities in loaded chunks");
            }
            World world = ctx.world();
            List<EntityData> candidates;
            double centerX = 0;
            double centerZ = 0;
            if (radius < 0) {
                candidates = world.getEntities();
            } else {
                BlockVector3 center = ctx.placement();
                centerX = center.x() + 0.5;
                centerZ = center.z() + 0.5;
                candidates = world.getEntities(new Extent.Region3i(
                        (int) Math.floor(centerX - radius), world.minY(), (int) Math.floor(centerZ - radius),
                        (int) Math.ceil(centerX + radius), world.maxY(), (int) Math.ceil(centerZ + radius)));
            }
            // The cylinder test runs on the squared distance so no square root is
            // taken per entity, and it applies before the type test because most
            // entities of a busy world sit outside the radius.
            double radiusSq = (double) radius * radius;
            int removed = 0;
            for (EntityData entity : candidates) {
                if (!entity.isSpawnable() || !type.matches(entity.type())) {
                    continue;
                }
                if (radius >= 0) {
                    double dx = entity.position().x() - centerX;
                    double dz = entity.position().z() - centerZ;
                    if (dx * dx + dz * dz > radiusSq) {
                        continue;
                    }
                }
                world.removeEntity(entity);
                removed++;
            }
            ctx.actor().message(Msg.success(removed + " entit(y/ies) have been marked for removal"));
        };
    }

    /**
     * {@code //butcher} — kills the entities matching the flags within a radius.
     *
     * <p>Without a flag only the hostile mobs are killed, which is the default
     * FAWE documents; {@code -p} adds pets, {@code -n} NPCs, {@code -g} golems,
     * {@code -a} animals, {@code -b} ambient mobs, {@code -t} named entities,
     * {@code -r} armor stands and {@code -w} water mobs. {@code -f} is the
     * shortcut for <code>-abgnpt</code>.</p>
     */
    private void butcher() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("butcher", "/butcher");
        if (entry == null) {
            return;
        }
        entry.description = "Kill all or matching entities within a radius";
        entry.group = "region";
        entry.arguments.add("[radius]");
        entry.booleanFlags.addAll(List.of("p", "n", "g", "a", "b", "t", "f", "r", "w"));
        entry.handler = ctx -> {
            int radius = ctx.args().isEmpty() || ctx.arg(0).startsWith("-")
                    ? com.maxlananas.fawebim.core.platform.Config.get().butcherDefaultRadius
                    : ctx.intArg(0, com.maxlananas.fawebim.core.platform.Config.get().butcherDefaultRadius);
            if (radius > com.maxlananas.fawebim.core.platform.Config.get().butcherMaxRadius) {
                throw CommandRegistry.error("Maximum butcher radius is "
                        + com.maxlananas.fawebim.core.platform.Config.get().butcherMaxRadius);
            }
            BlockVector3 origin = ctx.arg(0, "").contains(",")
                    ? ctx.blockVector(0) : ctx.placement();
            World world = ctx.world();
            Extent.Region3i box = new Extent.Region3i(
                    origin.x() - radius, world.minY(), origin.z() - radius,
                    origin.x() + radius, world.maxY(), origin.z() + radius);
            java.util.Set<Creatures.Category> categories = ctx.hasFlag("f")
                    ? Creatures.of(true, true, true, true, true, true, ctx.hasFlag("r"), true)
                    : Creatures.of(ctx.hasFlag("p"), ctx.hasFlag("n"), ctx.hasFlag("g"), ctx.hasFlag("a"),
                    ctx.hasFlag("b"), ctx.hasFlag("t"), ctx.hasFlag("r"), ctx.hasFlag("w"));
            int killed = 0;
            for (EntityData entity : world.getEntities(box)) {
                if (!entity.isSpawnable() || !Creatures.matches(entity, categories)) {
                    continue;
                }
                world.removeEntity(entity);
                killed++;
            }
            ctx.actor().message(Msg.success(killed + " entit(y/ies) removed within " + radius + " block(s)"));
        };
    }

    /**
     * {@code //blob} — builds a sphere whose radius is perturbed by Perlin noise,
     * which is FAWE's "distorted sphere" generator.
     */
    private void blob() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//blob");
        if (entry == null) {
            return;
        }
        entry.description = "Create a distorted sphere";
        entry.group = "generation";
        entry.arguments.add("pattern");
        entry.arguments.add("[radius]");
        entry.booleanFlags.add("h");
        entry.handler = ctx -> {
            Pattern pattern = ctx.pattern(0);
            double radius = ctx.doubleArg(1, 5);
            if (radius < 0.5 || radius > 500) {
                throw CommandRegistry.error("Radius must be between 0.5 and 500");
            }
            BlockVector3 origin = ctx.placement();
            EditSession session = ctx.editSession("blob");
            int changed = 0;
            Noise noise = new Noise.Perlin(origin.hashCode());
            boolean hollow = ctx.hasFlag("h");
            double inner = radius - 1.5;
            int min = (int) Math.floor(-radius - 3);
            int max = (int) Math.ceil(radius + 3);
            for (int x = min; x <= max; x++) {
                for (int y = min; y <= max; y++) {
                    for (int z = min; z <= max; z++) {
                        double distance = Math.sqrt(x * x + y * y + z * z);
                        double perturbed = radius
                                + noise.noise(x * 0.12, y * 0.12, z * 0.12) * radius * 0.3;
                        if (distance > perturbed) {
                            continue;
                        }
                        if (hollow && distance < inner) {
                            continue;
                        }
                        BlockVector3 position = origin.add(x, y, z);
                        session.checkTimeout();
                        if (session.setBlock(position.x(), position.y(), position.z(),
                                pattern.apply(position))) {
                            changed++;
                        }
                    }
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success("Blob: " + changed + " block(s) changed"));
        };
    }

}
