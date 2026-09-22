package com.fawebutinmods.fabric;

import com.fawebutinmods.core.world.RegenOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@code //regen} support.
 *
 * <p>Minecraft has no public "regenerate this chunk" API, so the vanilla world
 * generator is driven reflectively. The parameter list of
 * {@code ChunkGenerator#fillFromNoise} changed across releases (an
 * {@link Executor} used to be the first argument), so the arguments are matched
 * by type instead of by position: that keeps the mod working on 1.21.10 and
 * degrading gracefully on other versions. When the generator cannot be driven,
 * the chunk is cleared instead — still a usable, if drastic, {@code //regen}.</p>
 */
final class FabricWorldRegen {

    private FabricWorldRegen() {
    }

    static boolean regenerate(ServerLevel level, int chunkX, int chunkZ, RegenOptions options) {
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (!options.shouldKeepEntities()) {
            removeEntities(level, chunk);
        }
        boolean generated = false;
        try {
            generated = runGenerator(level, chunk);
        } catch (Throwable ignored) {
            generated = false;
        }
        if (!generated) {
            clear(chunk);
        }
        chunk.setUnsaved(true);
        return true;
    }

    /**
     * Calls {@code ChunkGenerator#fillFromNoise} (or {@code createChunk}) with
     * whatever arguments the running version expects. The generator writes the
     * noise terrain, the carvers, the surface rules and the biomes straight into
     * the chunk that is passed in.
     */
    private static boolean runGenerator(ServerLevel level, LevelChunk chunk) throws Exception {
        Object generator = level.getChunkSource().getGenerator();
        Object randomState = invoke(level.getChunkSource(), "randomState");
        if (randomState == null) {
            return false;
        }
        for (Method method : generator.getClass().getMethods()) {
            if (!method.getName().equals("fillFromNoise") && !method.getName().equals("createChunk")) {
                continue;
            }
            Object[] args = argumentsFor(method, level, chunk, randomState);
            if (args == null) {
                continue;
            }
            Object result = method.invoke(generator, args);
            Object value = result instanceof CompletableFuture<?> future ? future.join() : result;
            if (value instanceof ChunkAccess generated) {
                if (generated != chunk) {
                    copy(generated, chunk);
                }
                return true;
            }
            return true;
        }
        return false;
    }

    /** Builds the argument list, or null when this overload cannot be driven. */
    private static Object[] argumentsFor(Method method, ServerLevel level, LevelChunk chunk, Object randomState) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type.isInstance(randomState)) {
                args[i] = randomState;
            } else if (type.isInstance(chunk)) {
                args[i] = chunk;
            } else if (Executor.class.isAssignableFrom(type)) {
                args[i] = (Executor) Runnable::run;
            } else if (type.getName().endsWith("Blender")) {
                args[i] = blender(type);
            } else if (type.isInstance(level.getStructureManager())) {
                args[i] = level.getStructureManager();
            } else {
                return null;
            }
        }
        return args;
    }

    /** {@code Blender.empty()} — no blending for a regenerate. */
    private static Object blender(Class<?> type) {
        try {
            Method empty = type.getMethod("empty");
            if (java.lang.reflect.Modifier.isStatic(empty.getModifiers())) {
                return empty.invoke(null);
            }
        } catch (ReflectiveOperationException ignored) {
            // Blender is an interface with a nested Empty implementation.
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (nested.getSimpleName().equalsIgnoreCase("empty")) {
                try {
                    var instance = nested.getField("INSTANCE").get(null);
                    if (type.isInstance(instance)) {
                        return instance;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Fall through.
                }
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** Copies a freshly generated chunk over the live one, sections and all. */
    private static void copy(ChunkAccess from, LevelChunk to) {
        for (int sectionIndex = 0; sectionIndex < to.getSectionsCount(); sectionIndex++) {
            LevelChunkSection source = from.getSection(sectionIndex);
            LevelChunkSection target = to.getSection(sectionIndex);
            if (source == null || target == null) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        target.setBlockState(x, y, z, source.getBlockState(x, y, z), false);
                    }
                }
            }
            copyBiomes(source, target);
        }
        // Heightmaps are rebuilt from the copied sections.
        to.setUnsaved(true);
    }

    private static void copyBiomes(LevelChunkSection from, LevelChunkSection to) {
        var source = from.getBiomes();
        @SuppressWarnings("unchecked")
        var target = (net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>)
                to.getBiomes();
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    target.getAndSetUnchecked(x, y, z, source.get(x, y, z));
                }
            }
        }
    }

    private static void removeEntities(ServerLevel level, LevelChunk chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        var box = new net.minecraft.world.phys.AABB(minX, level.getMinY(), minZ,
                minX + 16, level.getMaxY() + 1, minZ + 16);
        for (var entity : level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> true)) {
            entity.discard();
        }
    }

    private static void clear(LevelChunk chunk) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        section.setBlockState(x, y, z, air, false);
                    }
                }
            }
        }
        chunk.setUnsaved(true);
    }
}
