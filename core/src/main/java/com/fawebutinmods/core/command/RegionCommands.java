package com.fawebutinmods.core.command;

import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.function.Operations;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.mask.Masks;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.pattern.Patterns;
import com.fawebutinmods.core.region.Region;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.util.NbtCompound;
import com.fawebutinmods.core.util.noise.Noise;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.EntityData;
import com.fawebutinmods.core.world.Extent;
import com.fawebutinmods.core.world.World;

import java.util.List;
import java.util.Locale;

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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("air", "/0");
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
            Mask mask = ctx.mask(0, null);
            int changed = 0;
            for (BlockVector3 position : region) {
                session.checkTimeout();
                if (mask != null && !mask.test(position)) {
                    continue;
                }
                if (session.setBlock(position.x(), position.y(), position.z(), air.apply(position))) {
                    changed++;
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " block(s) set to air"));
        };
    }

    /** {@code //test} — the upstream diagnostic command that echoes its argument. */
    private void test() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("test");
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("fixlighting");
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("removelighting", "/removelight");
        if (entry == null) {
            return;
        }
        entry.description = "Remove lighting data from the selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.handler = ctx -> relight(ctx, "Lighting removed and recomputed");
    }

    private void setBlockLight() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("setblocklight");
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("setskylight");
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("nbtinfo");
        if (entry == null) {
            return;
        }
        entry.description = "Print the NBT of the block you are looking at";
        entry.group = "region";
        entry.requiresPlayer = true;
        entry.handler = ctx -> {
            BlockVector3 target = ctx.args().isEmpty()
                    ? ctx.world().getTargetBlock(ctx.actor(), 100)
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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("fixblocks", "/updateblocks", "/fixconnect");
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
            int changed = 0;
            for (BlockVector3 position : region) {
                session.checkTimeout();
                int state = world.getBlock(position.x(), position.y(), position.z());
                if (state == BlockState.registry().air()) {
                    continue;
                }
                // Writing the value that is already there only triggers the
                // neighbour updates that recompute the block's shape.
                if (session.setBlock(position.x(), position.y(), position.z(), state, false)) {
                    changed++;
                }
                world.queueBlockUpdate(position.x(), position.y(), position.z());
            }
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " block(s) updated"));
        };
    }

    private void worldEditAnywhere() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("wea", "/weanywhere", "/worldeditanywhere");
        if (entry == null) {
            return;
        }
        entry.description = "Bypass region restrictions (there are none in the standalone mod)";
        entry.group = "region";
        entry.handler = ctx -> ctx.actor().message(Msg.info(
                "Region restrictions are a plugin feature; a standalone mod has no regions to bypass"));
    }

    private void worldEditRegion() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("wer", "/worldeditregion");
        if (entry == null) {
            return;
        }
        entry.description = "Select your allowed region (there are none in the standalone mod)";
        entry.group = "region";
        entry.handler = ctx -> ctx.actor().message(Msg.info(
                "Region restrictions are a plugin feature; a standalone mod has no regions to select"));
    }

    /**
     * {@code //update} — applies the side effects of every block in the
     * selection, which is what WorldEdit's {@code /update} does after a paste
     * with side effects disabled.
     */
    private void update() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("update");
        if (entry == null) {
            return;
        }
        entry.description = "Apply side effects and neighbour updates to the selection";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.handler = ctx -> {
            World world = ctx.world();
            Region region = ctx.selection();
            long updated = 0;
            for (BlockVector3 position : region) {
                world.queueBlockUpdate(position.x(), position.y(), position.z());
                updated++;
            }
            ctx.actor().message(Msg.success(updated + " block update(s) queued"));
        };
    }

    /** {@code //snowsmooth} — smooths snow, upstream runs {@code //smooth} with snow. */
    private void snowSmooth() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("snowsmooth");
        if (entry == null) {
            return;
        }
        entry.description = "Smooth the terrain, only considering snow blocks";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.arguments.add("[iterations]");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            int iterations = Math.max(1, ctx.intArg(0, 1));
            EditSession session = ctx.editSession("snowsmooth");
            session.setMask(new Masks.BlockMask(session, List.of("minecraft:snow", "minecraft:snow_block")));
            int changed = Operations.smooth(ctx.world(), session, region, iterations);
            session.flushQueue();
            ctx.actor().message(Msg.success("Smoothed " + changed + " snow block(s)"));
        };
    }

    /**
     * {@code //trim} — shrinks the selection to the blocks that match the mask,
     * which is how WorldEdit "minimises" a selection around a build.
     */
    private void trim() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("trim");
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
            selector.selectPrimary(first, com.fawebutinmods.core.region.SelectorLimits.unlimited());
            selector.selectSecondary(second, com.fawebutinmods.core.region.SelectorLimits.unlimited());
            ctx.actor().updateSelectionOutline();
            ctx.actor().message(Msg.success("Selection trimmed to " + first + " - " + second));
        };
    }

    /**
     * {@code //remove} — removes matching blocks that have air above them, i.e.
     * the blocks that are "exposed" on the surface.
     */
    private void remove() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("remove", "rem", "rement");
        if (entry == null) {
            return;
        }
        entry.description = "Remove blocks above the ground level that match the mask";
        entry.group = "region";
        entry.requiresSelection = true;
        entry.arguments.add("mask");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            Mask mask = ctx.mask(0);
            World world = ctx.world();
            EditSession session = ctx.editSession("remove");
            int air = BlockState.registry().air();
            int changed = 0;
            for (BlockVector3 position : region) {
                session.checkTimeout();
                if (!mask.test(position)) {
                    continue;
                }
                if (!BlockState.registry().isAir(world.getBlock(position.x(), position.y() + 1, position.z()))) {
                    continue;
                }
                if (session.setBlock(position.x(), position.y(), position.z(), air)) {
                    changed++;
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " block(s) removed"));
        };
    }

    /**
     * {@code //butcher} — kills the entities in a radius, with WorldEdit's flag
     * set ({@code -p} pets, {@code -n} NPCs, {@code -f} friendly, {@code -g}
     * golems, {@code -a} animals, {@code -m} monsters, {@code -t} toggles for
     * named entities and {@code -l} leaves tamed animals alone).
     */
    private void butcher() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("butcher");
        if (entry == null) {
            return;
        }
        entry.description = "Kill all or matching entities within a radius";
        entry.group = "region";
        entry.arguments.add("[radius]");
        entry.booleanFlags.addAll(List.of("p", "n", "f", "g", "a", "m", "t", "l", "e"));
        entry.handler = ctx -> {
            int radius = ctx.args().isEmpty() || ctx.arg(0).startsWith("-")
                    ? com.fawebutinmods.core.platform.Config.get().butcherDefaultRadius
                    : ctx.intArg(0, com.fawebutinmods.core.platform.Config.get().butcherDefaultRadius);
            if (radius > com.fawebutinmods.core.platform.Config.get().butcherMaxRadius) {
                throw CommandRegistry.error("Maximum butcher radius is "
                        + com.fawebutinmods.core.platform.Config.get().butcherMaxRadius);
            }
            BlockVector3 origin = ctx.arg(0, "").contains(",")
                    ? ctx.blockVector(0) : ctx.actor().position();
            World world = ctx.world();
            Extent.Region3i box = new Extent.Region3i(
                    origin.x() - radius, world.minY(), origin.z() - radius,
                    origin.x() + radius, world.maxY(), origin.z() + radius);
            List<EntityData> entities = world.getEntities(box);
            int killed = 0;
            for (EntityData entity : entities) {
                if (!butcherMatches(entity, ctx)) {
                    continue;
                }
                world.removeEntity(entity);
                killed++;
            }
            ctx.actor().message(Msg.success(killed + " entit(y/ies) removed within " + radius + " block(s)"));
        };
    }

    /**
     * Entity filtering for {@code //butcher}. Without flags every entity is
     * removed, which is what FAWE does; with flags only the requested families
     * are touched.
     */
    private boolean butcherMatches(EntityData entity, Ctx ctx) {
        String type = entity.type() == null ? "" : entity.type().toLowerCase(Locale.ROOT);
        boolean anyFlag = ctx.hasFlag("p") || ctx.hasFlag("n") || ctx.hasFlag("f")
                || ctx.hasFlag("g") || ctx.hasFlag("a") || ctx.hasFlag("m") || ctx.hasFlag("e");
        if (!anyFlag) {
            return true;
        }
        boolean pet = type.contains("wolf") || type.contains("cat") || type.contains("parrot")
                || type.contains("horse") || type.contains("llama") || type.contains("fox")
                || type.contains("axolotl") || type.contains("camel") || type.contains("frog");
        boolean npc = type.contains("villager") || type.contains("wandering_trader");
        boolean friendly = type.contains("sheep") || type.contains("cow") || type.contains("pig")
                || type.contains("chicken") || type.contains("rabbit") || type.contains("bee");
        boolean golem = type.contains("golem");
        boolean animal = pet || friendly || type.contains("animal");
        boolean monster = type.contains("zombie") || type.contains("skeleton") || type.contains("creeper")
                || type.contains("spider") || type.contains("witch") || type.contains("slime")
                || type.contains("enderman") || type.contains("blaze") || type.contains("phantom")
                || type.contains("raider") || type.contains("pillager") || type.contains("illager");
        if (ctx.hasFlag("l") && entity.nbt() != null
                && entity.nbt().contains("owner")) {
            return false;
        }
        if (ctx.hasFlag("t") && !entity.nbt().contains("custom_name")) {
            return false;
        }
        return ctx.hasFlag("p") && pet
                || ctx.hasFlag("n") && npc
                || ctx.hasFlag("f") && friendly
                || ctx.hasFlag("g") && golem
                || ctx.hasFlag("a") && animal
                || ctx.hasFlag("m") && monster
                || ctx.hasFlag("e") && !(pet || npc || friendly || golem || animal || monster);
    }

    /**
     * {@code //blob} — builds a sphere whose radius is perturbed by Perlin noise,
     * which is FAWE's "distorted sphere" generator.
     */
    private void blob() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("blob");
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
            BlockVector3 origin = ctx.actor().position();
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
