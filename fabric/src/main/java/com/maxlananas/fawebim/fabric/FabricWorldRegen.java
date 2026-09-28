package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.RegenOptions;
import com.maxlananas.fawebim.core.world.World;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * {@code //regen} on Fabric, the way WorldEdit's Fabric world does it.
 *
 * <p>The chunks are generated in a temporary level that has the live level's
 * dimension and generator - and the seed of the options when there is one -
 * with its storage in a temporary folder, up to the features, so the terrain,
 * its caves, its surface, its ores, trees and structures are all there. The
 * engine then copies the blocks it wants out of it through an edit session.
 * The live level is never written here; generating into its chunks in place,
 * as this class used to, rewrote whole chunks past the selection with raw
 * noise, left the blocks the noise did not cover, and could not be undone.</p>
 *
 * <p>Server thread only: the temporary level's chunk system is driven from
 * it, while the game's workers generate.</p>
 */
final class FabricWorldRegen {

    private FabricWorldRegen() {
    }

    static World.GeneratedTerrain generate(ServerLevel level, Collection<BlockVector2> chunks, RegenOptions options) {
        MinecraftServer server = level.getServer();
        PrimaryLevelData data = primary(level.getLevelData());
        WorldOptions original = data.worldGenOptions();
        long seed = options.shouldUseSeed() ? options.getSeed() : level.getSeed();
        Path folder = null;
        LevelStorageSource.LevelStorageAccess access = null;
        ServerLevel generated = null;
        try {
            folder = Files.createTempDirectory("fawebim-regen");
            access = LevelStorageSource.createDefault(folder).createAccess("fawebim-regen");
            if (options.shouldUseSeed()) {
                // What the structures are placed from; the noise takes the seed below.
                data.worldOptions = original.withSeed(OptionalLong.of(seed));
            }
            generated = new ServerLevel(server, Util.backgroundExecutor(), access,
                    (ServerLevelData) level.getLevelData(), level.dimension(),
                    new LevelStem(level.dimensionTypeRegistration(), level.getChunkSource().getGenerator()),
                    level.isDebug(), seed, List.of(), false, level.getRandomSequences());
            Map<Long, ChunkAccess> made = generateChunks(generated, chunks);
            return new Terrain(level, generated, access, folder, made);
        } catch (Exception | LinkageError failure) {
            close(server, generated, access, folder);
            throw new IllegalStateException("Could not generate the terrain to regenerate from", failure);
        } finally {
            data.worldOptions = original;
        }
    }

    /** The chunks, generated up to their features, keyed by {@link ChunkPos#asLong}. */
    private static Map<Long, ChunkAccess> generateChunks(ServerLevel generated, Collection<BlockVector2> chunks) {
        List<CompletableFuture<ChunkAccess>> futures = new ArrayList<>();
        for (BlockVector2 chunk : chunks) {
            futures.add(generated.getChunkSource().getChunkFuture(chunk.x(), chunk.z(), ChunkStatus.FEATURES, true)
                    .thenApply(result -> result.orElse(null)));
        }
        // The level's own tasks run on this thread while the workers generate.
        generated.getChunkSource().mainThreadProcessor.managedBlock(() -> {
            for (CompletableFuture<ChunkAccess> future : futures) {
                if (!future.isDone()) {
                    return false;
                }
            }
            return true;
        });
        Map<Long, ChunkAccess> made = new HashMap<>();
        for (CompletableFuture<ChunkAccess> future : futures) {
            ChunkAccess chunk = future.isCompletedExceptionally() ? null : future.getNow(null);
            if (chunk == null) {
                throw new IllegalStateException("A chunk could not be generated");
            }
            made.put(chunk.getPos().toLong(), chunk);
        }
        return made;
    }

    private static PrimaryLevelData primary(LevelData data) {
        if (data instanceof DerivedLevelData derived) {
            return primary(derived.wrapped);
        }
        if (data instanceof PrimaryLevelData primary) {
            return primary;
        }
        throw new IllegalStateException("Unknown level data " + data.getClass().getName());
    }

    /** Frees the temporary level, after the server ran what it left behind, and deletes its folder. */
    private static void close(MinecraftServer server, ServerLevel generated, LevelStorageSource.LevelStorageAccess access,
                              Path folder) {
        while (server.pollTask()) {
            Thread.yield();
        }
        try {
            if (generated != null) {
                generated.close();
            }
        } catch (IOException | RuntimeException exception) {
            FaweMod.LOGGER.warn("Could not close the level generated for //regen", exception);
        }
        try {
            if (access != null) {
                access.close();
            }
        } catch (IOException | RuntimeException exception) {
            FaweMod.LOGGER.warn("Could not close the storage generated for //regen", exception);
        }
        if (folder != null) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException exception) {
                FaweMod.LOGGER.warn("Could not delete {}, the folder generated for //regen", folder, exception);
            }
        }
    }

    /** The generated chunks, read by the engine while it copies from them. */
    private static final class Terrain implements World.GeneratedTerrain {

        private final ServerLevel live;
        private final ServerLevel generated;
        private final LevelStorageSource.LevelStorageAccess access;
        private final Path folder;
        private final Map<Long, ChunkAccess> chunks;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final int air = Block.getId(Blocks.AIR.defaultBlockState());

        Terrain(ServerLevel live, ServerLevel generated, LevelStorageSource.LevelStorageAccess access, Path folder,
                Map<Long, ChunkAccess> chunks) {
            this.live = live;
            this.generated = generated;
            this.access = access;
            this.folder = folder;
            this.chunks = chunks;
        }

        private ChunkAccess chunk(int x, int z) {
            return chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        }

        @Override
        public int getBlock(int x, int y, int z) {
            ChunkAccess chunk = chunk(x, z);
            return chunk == null ? air : Block.getId(chunk.getBlockState(cursor.set(x, y, z)));
        }

        @Override
        public int getBiome(int x, int y, int z) {
            ChunkAccess chunk = chunk(x, z);
            if (chunk == null) {
                return -1;
            }
            ResourceLocation key = live.registryAccess().lookupOrThrow(Registries.BIOME)
                    .getKey(chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).value());
            return key == null ? -1 : FabricRegistries.biomeId(key.toString());
        }

        @Override
        public NbtCompound getBlockEntity(int x, int y, int z) {
            ChunkAccess chunk = chunk(x, z);
            var blockEntity = chunk == null ? null : chunk.getBlockEntity(cursor.set(x, y, z));
            if (blockEntity == null) {
                return null;
            }
            try {
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                        live.registryAccess());
                blockEntity.saveWithId(output);
                return FabricWorld.fromTag(output.buildResult());
            } catch (IOException | RuntimeException exception) {
                FaweMod.LOGGER.warn("Could not save a generated block entity at {},{},{}", x, y, z, exception);
                return null;
            }
        }

        @Override
        public void forEachBlockEntity(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                       World.BlockEntityVisitor visitor) {
            for (ChunkAccess chunk : chunks.values()) {
                ChunkPos pos = chunk.getPos();
                if (pos.getMaxBlockX() < minX || pos.getMinBlockX() > maxX
                        || pos.getMaxBlockZ() < minZ || pos.getMinBlockZ() > maxZ) {
                    continue;
                }
                for (BlockPos at : chunk.getBlockEntitiesPos()) {
                    if (at.getX() >= minX && at.getX() <= maxX && at.getY() >= minY && at.getY() <= maxY
                            && at.getZ() >= minZ && at.getZ() <= maxZ) {
                        visitor.visit(at.getX(), at.getY(), at.getZ());
                    }
                }
            }
        }

        @Override
        public void close() {
            FabricWorldRegen.close(live.getServer(), generated, access, folder);
        }
    }
}
