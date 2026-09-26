package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.ChunkSet;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.PackedBlockArray;
import com.maxlananas.fawebim.core.world.RegenOptions;
import com.maxlananas.fawebim.core.world.World;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Random;

/**
 * The engine's view of a server world.
 *
 * <p>All writes go through the bulk path: whole sections are written straight
 * into the chunk's palette ({@link LevelChunkSection#setBlockState}) instead of
 * going block by block through vanilla's neighbour cascade; then only the
 * positions that actually changed are re-lit, told to their neighbours and sent
 * to the clients. That is FAWE's core trick and it is why a 100k block
 * {@code //set} does not lag the server.</p>
 */
public final class FabricWorld implements World {

    private static final int UPDATE_NEIGHBORS = 1;
    private static final int UPDATE_CLIENTS = 2;
    /**
     * Changed blocks in one chunk above which the chunk is re-lit as a whole
     * instead of queueing every position: the light engine keeps the positions it
     * is handed in a set, and a set with a million entries in it costs more time
     * and more memory than recomputing the chunk's light in one pass.
     */
    private static final int LIGHT_CHUNK_THRESHOLD = 512;

    private final ServerLevel level;
    /** The chunk of the previous read or write, and its position. */
    private LevelChunk cachedChunk;
    /**
     * The positions a flush changed, kept between flushes and grown on demand:
     * a large edit flushes one chunk after another and this is the only array a
     * flush needs, so handing it to the collector every time is pure garbage.
     */
    private int[] changedX = new int[0];
    private int[] changedY = new int[0];
    private int[] changedZ = new int[0];
    /**
     * The changes of a flush whose block entity has to follow the block: the
     * index of the change in {@link #changedX}, and the state it replaced.
     */
    private int[] blockEntitySlots = new int[16];
    private int[] blockEntityBefore = new int[16];
    private int blockEntityCount;
    /** One position object for a whole flush: nothing keeps what it is handed. */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;

    /**
     * Shared worker pool for the work the engine hands off the server thread.
     *
     * <p>Built on first use and sized by {@code threads}, which is the setting
     * that says how many workers the engine may use: a pool sized from the core
     * count instead would be a second answer to the same question. The size is
     * read once, like the setting describes, so a change to it takes effect on
     * the next start.</p>
     */
    private static volatile java.util.concurrent.ExecutorService executor;

    public static java.util.concurrent.ExecutorService workers() {
        java.util.concurrent.ExecutorService pool = executor;
        if (pool == null) {
            synchronized (FabricWorld.class) {
                pool = executor;
                if (pool == null) {
                    pool = java.util.concurrent.Executors.newFixedThreadPool(
                            Math.max(1, com.maxlananas.fawebim.core.platform.Config.get().threads),
                            runnable -> {
                                Thread thread = new Thread(runnable, "FAWE-BIM worker");
                                thread.setDaemon(true);
                                return thread;
                            });
                    executor = pool;
                }
            }
        }
        return pool;
    }

    public FabricWorld(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return level;
    }

    @Override
    public String name() {
        return level.dimension().location().toString();
    }

    @Override
    public int minY() {
        return level.getMinY();
    }

    @Override
    public int maxY() {
        return level.getMaxY();
    }

    @Override
    public int getBlock(int x, int y, int z) {
        int air = com.maxlananas.fawebim.core.world.BlockState.registry().air();
        if (y < minY() || y > maxY()) {
            return air;
        }
        LevelChunkSection section = sectionAt(x, y, z);
        if (section == null) {
            return air;
        }
        return Block.getId(section.getBlockState(x & 15, y & 15, z & 15));
    }

    @Override
    public boolean isSectionEmpty(int chunkX, int sectionY, int chunkZ) {
        int index = ((sectionY << 4) - minY()) >> 4;
        if (index < 0 || index >= level.getSectionsCount()) {
            // Outside the level there is nothing to read or to write.
            return true;
        }
        // A chunk that is not loaded must not be generated just to answer this;
        // an unknown section is walked like a full one.
        net.minecraft.world.level.chunk.LevelChunk chunk =
                level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk != null && chunk.getSection(index).hasOnlyAir();
    }

    @Override
    public boolean readSection(int chunkX, int sectionY, int chunkZ, int[] out,
                               int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int air = com.maxlananas.fawebim.core.world.BlockState.registry().air();
        int index = ((sectionY << 4) - minY()) >> 4;
        int baseX = chunkX << 4;
        int baseY = sectionY << 4;
        int baseZ = chunkZ << 4;
        if (index < 0 || index >= level.getSectionsCount()) {
            fill(out, air, fromX, fromY, fromZ, toX, toY, toZ, baseX, baseY, baseZ);
            return true;
        }
        // A chunk that is not loaded is not read here: the caller walks the
        // section then, and that walk loads it. Answering with air instead would
        // copy nothing and clear nothing, quietly.
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        LevelChunkSection section = chunk.getSection(index);
        if (section.hasOnlyAir()) {
            fill(out, air, fromX, fromY, fromZ, toX, toY, toZ, baseX, baseY, baseZ);
            return true;
        }
        PalettedContainer<BlockState> states = section.getStates();
        // One state in the whole section, which the chunk keeps as a palette of
        // one - a section of solid stone or of water. The read is a fill, and
        // the id is looked up once.
        if (states.bitsPerEntry() == 0) {
            fill(out, Block.getId(states.get(0, 0, 0)), fromX, fromY, fromZ, toX, toY, toZ,
                    baseX, baseY, baseZ);
            return true;
        }
        // The rest is read straight out of the section: no chunk lookup, no
        // bounds check and no position object per block, and the walk goes in
        // the same order the section stores its cells, so both the palette and
        // the bit storage are read front to back.
        for (int y = fromY & 15; y <= (toY & 15); y++) {
            int row = y << 8;
            for (int z = fromZ & 15; z <= (toZ & 15); z++) {
                int at = row | (z << 4);
                for (int x = fromX & 15; x <= (toX & 15); x++) {
                    out[at | x] = Block.getId(states.get(x, y, z));
                }
            }
        }
        return true;
    }

    /** Writes one state into the cells of a box, in the section's own layout. */
    private static void fill(int[] out, int state, int fromX, int fromY, int fromZ,
                             int toX, int toY, int toZ, int baseX, int baseY, int baseZ) {
        for (int y = fromY - baseY; y <= toY - baseY; y++) {
            int row = y << 8;
            for (int z = fromZ - baseZ; z <= toZ - baseZ; z++) {
                int at = row | (z << 4);
                java.util.Arrays.fill(out, at + (fromX - baseX), at + (toX - baseX) + 1, state);
            }
        }
    }

    /**
     * The section a position lives in, without the position object the game's
     * own accessor allocates and without its second chunk lookup.
     *
     * <p>Every read and every write of an edit goes through here. A region is
     * walked x first, so sixteen calls in a row ask for the same chunk, and the
     * chunk lookup behind that walk is a hash of the chunk position with a load
     * on a miss; the last chunk is remembered so the run pays for it once.</p>
     *
     * @return the section, or {@code null} when the position is outside the
     *         sections of the level
     */
    private LevelChunkSection sectionAt(int x, int y, int z) {
        int index = (y - minY()) >> 4;
        if (index < 0 || index >= level.getSectionsCount()) {
            return null;
        }
        return chunkAt(x >> 4, z >> 4).getSection(index);
    }

    /**
     * The chunk of a position, from the last one when it is the same.
     *
     * <p>FAWE's own world adapter keeps a chunk cache in front of the platform
     * for the same reason: an edit walks a row of one chunk at a time, and the
     * game's chunk map is asked once per block without it. Only the block reads
     * and writes of an edit go through here; the flush and the block-entity
     * paths look their chunk up themselves, once per chunk, so a stale entry
     * cannot decide what is written.</p>
     */
    private LevelChunk chunkAt(int chunkX, int chunkZ) {
        LevelChunk cached = cachedChunk;
        if (cached != null && cachedChunkX == chunkX && cachedChunkZ == chunkZ) {
            return cached;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        cachedChunk = chunk;
        cachedChunkX = chunkX;
        cachedChunkZ = chunkZ;
        return chunk;
    }

    @Override
    public boolean setBlock(int x, int y, int z, int stateId) {
        if (y < minY() || y > maxY()) {
            return false;
        }
        LevelChunkSection section = sectionAt(x, y, z);
        if (section == null) {
            return false;
        }
        if (Block.getId(section.getBlockState(x & 15, y & 15, z & 15)) == stateId) {
            return false;
        }
        // Through the level, so the neighbours, the light and the clients hear
        // about it: this is the path of a single write, not of a bulk edit.
        level.setBlock(new BlockPos(x, y, z), Block.stateById(stateId), Block.UPDATE_ALL);
        return true;
    }

    @Override
    public int getBiome(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, Math.max(minY(), Math.min(maxY(), y)), z);
        Registry<net.minecraft.world.level.biome.Biome> biomes =
                level.registryAccess().lookupOrThrow(Registries.BIOME);
        ResourceLocation key = biomes.getKey(level.getBiome(pos).value());
        return key == null ? -1 : FabricRegistries.biomeId(key.toString());
    }

    @Override
    public boolean setBiome(int x, int y, int z, int biomeId) {
        ResourceKey<net.minecraft.world.level.biome.Biome> biomeKey = FabricRegistries.biomeKey(biomeId);
        if (biomeKey == null || y < minY() || y > maxY()) {
            return false;
        }
        LevelChunk chunk = chunkAt(x >> 4, z >> 4);
        int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return false;
        }
        Registry<net.minecraft.world.level.biome.Biome> registry =
                level.registryAccess().lookupOrThrow(Registries.BIOME);
        // The biome palette is a 4x4x4 grid inside each section; sections are
        // 16 blocks tall, so the low bits of Y address the cell directly.
        // The game hands the biome grid out as a read-only view; it is the
        // writable palette container in every chunk that exists, which is how
        // WorldEdit's own Fabric adapter writes biomes on this version too.
        @SuppressWarnings("unchecked")
        PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>> container =
                (PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>>) chunk
                        .getSection(sectionIndex).getBiomes();
        container.getAndSetUnchecked(x & 3, y & 3, z & 3, registry.getOrThrow(biomeKey));
        chunk.markUnsaved();
        return true;
    }

    @Override
    public int getHighestBlockY(int x, int z) {
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            return minY() - 1;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        // The heightmap ignores some non-air blocks (leaves are included, but
        // e.g. blocks placed at build height by //set are not), so fall back to
        // a scan whenever it looks empty.
        if (y < minY() || com.maxlananas.fawebim.core.world.BlockState.registry()
                .isAirLike(getBlock(x, y, z))) {
            for (int candidate = maxY(); candidate >= minY(); candidate--) {
                if (!com.maxlananas.fawebim.core.world.BlockState.registry()
                        .isAirLike(getBlock(x, candidate, z))) {
                    return candidate;
                }
            }
            return minY() - 1;
        }
        return y;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    @Override
    public void loadChunk(int chunkX, int chunkZ) {
        level.getChunk(chunkX, chunkZ);
    }

    /**
     * Applies a prepared chunk buffer.
     *
     * <p>Every section that holds changes is written at once through the chunk's
     * palette, then only the positions that really changed are re-lit and sent to
     * the clients. Nothing goes through the slow per-block neighbour cascade.</p>
     */
    @Override
    public int applyChunk(ChunkSet set) {
        return applyChunk(set, com.maxlananas.fawebim.core.session.SideEffectSet.defaults());
    }

    @Override
    public int applyChunk(ChunkSet set, com.maxlananas.fawebim.core.session.SideEffectSet sideEffects) {
        boolean lighting = sideEffects.shouldApply(
                com.maxlananas.fawebim.core.session.SideEffect.LIGHTING);
        boolean notify = sideEffects.shouldApply(
                com.maxlananas.fawebim.core.session.SideEffect.UPDATE);
        boolean neighbors = sideEffects.shouldApply(
                com.maxlananas.fawebim.core.session.SideEffect.NEIGHBORS);
        boolean network = sideEffects.shouldApply(
                com.maxlananas.fawebim.core.session.SideEffect.NETWORK);
        // The flush looks its chunk up itself: it runs once per chunk, so the
        // cache is no help here, and the write it is about to make must not go
        // through an entry that another walk put there.
        LevelChunk chunk = level.getChunk(set.chunkX(), set.chunkZ());
        PackedBlockArray[] sections = set.sections();
        int applied = 0;
        int baseX = set.chunkX() << 4;
        int baseZ = set.chunkZ() << 4;
        int baseY = set.minSection() << 4;

        // 1. Remember the positions that changed, so they can be re-lit once the
        //    write is done. They stay in parallel int arrays rather than in a map
        //    keyed by a block vector: a large edit changes millions of them and
        //    this runs on every flush. The arrays are kept between flushes - a
        //    large edit flushes a chunk after another - and only grow. A flush
        //    that will re-send the chunk needs the positions and nothing else -
        //    the clients get the chunk, not a packet per block - so the states are
        //    only collected below that.
        int count = set.size();
        boolean resend = network && count >= Math.max(1, Config.get().chunkResendThreshold);
        if (changedX.length < count) {
            int size = Math.max(1024, count);
            changedX = new int[size];
            changedY = new int[size];
            changedZ = new int[size];
        }
        int[] changedXs = changedX;
        int[] changedYs = changedY;
        int[] changedZs = changedZ;
        int[] slot = {0};
        blockEntityCount = 0;
        boolean perBlockNotify = !resend && (notify || neighbors);
        BlockState[] before = perBlockNotify ? new BlockState[count] : null;
        BlockState[] after = perBlockNotify ? new BlockState[count] : null;
        boolean perBlockLight = lighting && count < LIGHT_CHUNK_THRESHOLD;
        var chunkSource = level.getChunkSource();
        var lightEngine = chunkSource.getLightEngine();
        boolean ticking = chunk.getFullStatus().isOrAfter(
                net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
        // The four heightmaps vanilla updates for every block it writes. Looking
        // them up once per chunk keeps the map out of the per-block path.
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        Heightmap motionBlockingNoLeaves =
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);

        // 2. Bulk section write: one palette update per section instead of one
        //    world.setBlock call (with its 6 neighbour updates) per block, plus
        //    the bookkeeping vanilla does for a written cell - the heightmaps of
        //    its column, the sky light source of its column, the light queue and,
        //    when the section stops being empty, the light engine's own view of
        //    it.
        for (int index = 0; index < sections.length; index++) {
            PackedBlockArray buffered = sections[index];
            if (buffered == null) {
                continue;
            }
            int sectionY = baseY + index * 16;
            int sectionIndex = chunk.getSectionIndex(sectionY);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(sectionIndex);
            boolean wasEmpty = section.hasOnlyAir();
            int written = buffered.forEachWritten(local -> {
                int localX = local & 15;
                int localY = (local >> 8) & 15;
                int localZ = (local >> 4) & 15;
                int y = sectionY + localY;
                int at = slot[0]++;
                changedXs[at] = baseX + localX;
                changedYs[at] = y;
                changedZs[at] = baseZ + localZ;
                BlockState now = Block.stateById(buffered.get(local));
                BlockState was = section.getBlockState(localX, localY, localZ);
                if (before != null) {
                    before[at] = was;
                    after[at] = now;
                }
                section.setBlockState(localX, localY, localZ, now, false);
                if (was != now && (was.hasBlockEntity() || now.hasBlockEntity())) {
                    rememberBlockEntity(at, was);
                }
                motionBlocking.update(localX, y, localZ, now);
                motionBlockingNoLeaves.update(localX, y, localZ, now);
                oceanFloor.update(localX, y, localZ, now);
                worldSurface.update(localX, y, localZ, now);
                if (perBlockLight && LightEngine.hasDifferentLightProperties(was, now)) {
                    chunk.getSkyLightSources().update(chunk, localX, y, localZ);
                    lightEngine.checkBlock(cursor.set(baseX + localX, y, baseZ + localZ));
                }
            });
            applied += written;
            if (written > 0) {
                chunk.markUnsaved();
                boolean isEmpty = section.hasOnlyAir();
                if (wasEmpty != isEmpty) {
                    net.minecraft.core.SectionPos sectionPos =
                            net.minecraft.core.SectionPos.of(set.chunkX(), sectionY >> 4, set.chunkZ());
                    lightEngine.updateSectionStatus(sectionPos, isEmpty);
                    chunkSource.onSectionEmptinessChanged(
                            set.chunkX(), sectionY >> 4, set.chunkZ(), isEmpty);
                }
            }
        }

        // 2b. The block entities of the blocks that changed, kept as the game
        //     keeps them when it sets a block: one whose block became another
        //     block goes, one whose block only changed state stays with its
        //     data, and a block that needs one gets a new one, registered with
        //     its ticker. The bulk write went past the chunk's own bookkeeping,
        //     which used to leave the old block entity in place - a chest's
        //     items under the stone that replaced it, saved with the chunk - and
        //     a new chest or furnace without one until something asked for it.
        //     Nothing is dropped: an edit does not spill what it replaces.
        for (int i = 0; i < blockEntityCount; i++) {
            int at = blockEntitySlots[i];
            BlockPos pos = new BlockPos(changedXs[at], changedYs[at], changedZs[at]);
            BlockState was = Block.stateById(blockEntityBefore[i]);
            BlockState now = chunk.getBlockState(pos);
            var existing = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (existing != null) {
                if (now.is(was.getBlock()) && existing.isValidBlockState(now)) {
                    existing.setBlockState(now);
                    chunk.updateBlockEntityTicker(existing);
                    continue;
                }
                chunk.removeBlockEntity(pos);
            }
            if (now.hasBlockEntity()) {
                chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            }
        }

        // 3. Biomes, written straight into the section's 4x4x4 palette.
        if (set.hasBiomes()) {
            applyBiomes(chunk, set);
        }

        // 3b. The data of the block entities the buffer put down, now that their
        //     blocks exist.
        for (ChunkSet.BlockEntity entity : set.blockEntities()) {
            applyBlockEntity(entity.x, entity.y, entity.z, entity.nbt);
        }

        // 4. Lighting, and the client sync for the changed blocks only. Vanilla
        //    would have queued 6 neighbour updates per block; the bulk write skips
        //    that and relies on the light engine plus the game's own block-change
        //    bookkeeping, the way WorldEdit's native access does it. A flush that
        //    changed a large part of a chunk is re-lit whole instead of position
        //    by position: the light engine keeps the positions it is handed in a
        //    set, and a set of a million entries costs more than recomputing the
        //    chunk's light.
        int changedCount = slot[0];
        for (int index = 0; perBlockNotify && index < changedCount; index++) {
            BlockState was = before[index];
            BlockState now = after[index];
            if (now == was) {
                continue;
            }
            // Vanilla's own notification: it hands the section to the game's
            // next broadcast and tells the mobs the ground moved. A neighbour
            // update can schedule a block tick, which keeps the position it was
            // given, so this path hands it one of its own.
            BlockPos at = new BlockPos(changedXs[index], changedYs[index], changedZs[index]);
            if (notify) {
                level.sendBlockUpdated(at, was, now, UPDATE_NEIGHBORS | UPDATE_CLIENTS);
            }
            if (neighbors) {
                level.updateNeighborsAt(at, now.getBlock());
            }
        }
        if (lighting && !perBlockLight) {
            lightEngine.lightChunk(chunk, false);
        }
        if (resend) {
            sendChunk(chunk, lightEngine);
        }
        return applied;
    }

    /** Remembers a change whose block entity has to follow its block, see step 2b of {@link #applyChunk}. */
    private void rememberBlockEntity(int slot, BlockState was) {
        if (blockEntityCount == blockEntitySlots.length) {
            blockEntitySlots = java.util.Arrays.copyOf(blockEntitySlots, blockEntityCount * 2);
            blockEntityBefore = java.util.Arrays.copyOf(blockEntityBefore, blockEntityCount * 2);
        }
        blockEntitySlots[blockEntityCount] = slot;
        blockEntityBefore[blockEntityCount] = Block.getId(was);
        blockEntityCount++;
    }

    /** Sends a whole chunk, with its light data, to everyone who can see it. */
    private void sendChunk(LevelChunk chunk, net.minecraft.world.level.lighting.LevelLightEngine lightEngine) {
        var packet = new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                chunk, lightEngine, null, null);
        ChunkPos chunkPos = chunk.getPos();
        for (var player : level.players()) {
            if (player.getChunkTrackingView().contains(chunkPos)) {
                player.connection.send(packet);
            }
        }
    }

    /** Writes the chunk buffer's biomes into the section palettes. */
    private void applyBiomes(LevelChunk chunk, ChunkSet set) {
        int baseX = set.chunkX() << 4;
        int baseZ = set.chunkZ() << 4;
        Registry<net.minecraft.world.level.biome.Biome> registry =
                level.registryAccess().lookupOrThrow(Registries.BIOME);
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null) {
                continue;
            }
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
            @SuppressWarnings("unchecked")
            PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>> container =
                    (PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>>) section.getBiomes();
            boolean touched = false;
            for (int y = 0; y < 16; y += 4) {
                for (int z = 0; z < 16; z += 4) {
                    for (int x = 0; x < 16; x += 4) {
                        int biomeId = set.getBiome(baseX + x, sectionY + y, baseZ + z);
                        if (biomeId < 0) {
                            continue;
                        }
                        ResourceKey<net.minecraft.world.level.biome.Biome> key =
                                FabricRegistries.biomeKey(biomeId);
                        if (key == null) {
                            continue;
                        }
                        container.getAndSetUnchecked(x >> 2, y >> 2, z >> 2, registry.getOrThrow(key));
                        touched = true;
                    }
                }
            }
            if (touched) {
                chunk.markUnsaved();
            }
        }
    }

    @Override
    public void resendChunks(Collection<BlockVector2> chunks) {
        var lightEngine = level.getChunkSource().getLightEngine();
        for (BlockVector2 chunk : chunks) {
            sendChunk(level.getChunk(chunk.x(), chunk.z()), lightEngine);
        }
    }

    @Override
    public void relight(Collection<BlockVector2> chunks) {
        for (BlockVector2 chunk : chunks) {
            level.getChunkSource().getLightEngine()
                    .setLightEnabled(new ChunkPos(chunk.x(), chunk.z()), true);
        }
    }

    @Override
    public void queueBlockUpdate(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
    }

    @Override
    public long chunkLastModified(int chunkX, int chunkZ) {
        // Vanilla keeps every chunk of a 32x32 area in one region file, whose
        // timestamp is the best signal available without reading the file's
        // header, which would mean loading the chunk from disk.
        Path folder = regionDirectory();
        if (folder == null) {
            return -1;
        }
        Path region = folder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        if (!Files.isRegularFile(region)) {
            return -1;
        }
        try {
            return Files.getLastModifiedTime(region).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public boolean regenerateChunk(int chunkX, int chunkZ, RegenOptions options) {
        return FabricWorldRegen.regenerate(level, chunkX, chunkZ, options);
    }

    @Override
    public boolean generateTree(BlockVector3 pos, String treeType, Random random) {
        String type = com.maxlananas.fawebim.core.world.TreeTypes.canonical(treeType);
        if (type == null) {
            // Not a WorldEdit tree type: a placed feature id plants what it names.
            String id = treeType == null || !treeType.contains(":") ? null
                    : treeType.toLowerCase(java.util.Locale.ROOT);
            return id != null && placeFeature(pos, id, random);
        }
        // A feature a Minecraft version does not have any more plants a plain oak
        // rather than failing the command that asked for a tree.
        return placeFeature(pos, com.maxlananas.fawebim.core.world.TreeTypes.feature(type, random), random)
                || placeFeature(pos, "minecraft:oak_checked", random);
    }

    @Override
    public boolean generateFeature(BlockVector3 pos, String featureType, Random random) {
        String id = featureType.contains(":") ? featureType : "minecraft:" + featureType;
        return placeFeature(pos, id, random);
    }

    private boolean placeFeature(BlockVector3 pos, String id, Random random) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return false;
        }
        net.minecraft.world.level.levelgen.placement.PlacedFeature placement =
                level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getValue(location);
        if (placement == null) {
            return false;
        }
        net.minecraft.util.RandomSource source = net.minecraft.util.RandomSource.create(random.nextLong());
        return placement.place(level, level.getChunkSource().getGenerator(), source,
                new BlockPos(pos.x(), pos.y(), pos.z()));
    }

    /**
     * The entities of a box, players excepted: no edit copies, removes or
     * restores a player. Their data is read when first asked for, the way the
     * game saves them, so a command that only filters by type does not
     * serialise every entity it looks at. Server thread only.
     */
    @Override
    public List<EntityData> getEntities(com.maxlananas.fawebim.core.world.Extent.Region3i box) {
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        List<EntityData> entities = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, aabb,
                candidate -> !(candidate instanceof net.minecraft.world.entity.player.Player))) {
            entities.add(describe(entity));
        }
        return entities;
    }

    private EntityData describe(Entity entity) {
        EntityData data = new EntityData(entity, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                () -> saveEntity(entity), new Vector3(entity.getX(), entity.getY(), entity.getZ()));
        data.setUuid(entity.getStringUUID());
        data.setPassenger(entity.isPassenger());
        return data;
    }

    /**
     * An entity's data as the game saves it, its type under {@code id} and
     * its passengers inside; {@code null} for one the game does not save.
     */
    private NbtCompound saveEntity(Entity entity) {
        try {
            net.minecraft.world.level.storage.TagValueOutput output =
                    net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                            ProblemReporter.DISCARDING, level.registryAccess());
            return entity.saveAsPassenger(output) ? fromTag(output.buildResult()) : null;
        } catch (IOException | RuntimeException exception) {
            FaweMod.LOGGER.warn("Could not save the data of entity {}", entity.getStringUUID(), exception);
            return null;
        }
    }

    /** Pastes and undo put entities through {@link #spawnEntity}. */
    @Override
    public void addEntity(EntityData data) {
        spawnEntity(data, null);
    }

    /**
     * Creates an entity from its data, the way WorldEdit's Fabric world does:
     * without the identity fields, at the position of the data, with its
     * passengers. Server thread only.
     */
    @Override
    public EntityData spawnEntity(EntityData data, String uuid) {
        if (net.minecraft.world.entity.EntityType.byString(data.type()).isEmpty()) {
            return null;
        }
        java.util.UUID identity = null;
        if (uuid != null) {
            try {
                identity = java.util.UUID.fromString(uuid);
            } catch (IllegalArgumentException invalid) {
                identity = null;
            }
            if (identity != null && level.getEntity(identity) != null) {
                return null;
            }
        }
        CompoundTag tag = data.nbt() == null ? new CompoundTag() : toTag(data.nbt());
        withoutIdentity(tag);
        tag.putString("id", data.type());
        Vector3 at = data.position();
        Entity created = net.minecraft.world.entity.EntityType.loadEntityRecursive(tag, level,
                net.minecraft.world.entity.EntitySpawnReason.COMMAND, loaded -> {
                    loaded.absSnapTo(at.x(), at.y(), at.z(), loaded.getYRot(), loaded.getXRot());
                    return loaded;
                });
        if (created == null) {
            return null;
        }
        if (identity != null) {
            created.setUUID(identity);
        }
        if (!level.tryAddFreshEntityWithPassengers(created)) {
            return null;
        }
        return describe(created);
    }

    /** The fields that name one entity: a copy with them would not be added, or would replace the original. */
    private static final List<String> IDENTITY_FIELDS = List.of("UUID", "UUIDMost", "UUIDLeast",
            "WorldUUIDMost", "WorldUUIDLeast", "PersistentIDMSB", "PersistentIDLSB");

    private static void withoutIdentity(CompoundTag tag) {
        for (String field : IDENTITY_FIELDS) {
            tag.remove(field);
        }
        tag.getList("Passengers").ifPresent(passengers -> {
            for (int i = 0; i < passengers.size(); i++) {
                withoutIdentity(passengers.getCompoundOrEmpty(i));
            }
        });
    }

    @Override
    public void removeEntity(EntityData data) {
        if (data.handle() instanceof Entity entity && !(entity instanceof net.minecraft.world.entity.player.Player)) {
            entity.discard();
        }
    }

    @Override
    public boolean removeEntityById(String uuid) {
        Entity entity;
        try {
            entity = level.getEntity(java.util.UUID.fromString(uuid));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (entity == null || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }
        entity.discard();
        return true;
    }

    /** Precision ray trace, used by {@code //jumpto}, {@code //thru} and the tools. */
    @Override
    public BlockVector3 getTargetBlock(com.maxlananas.fawebim.core.actor.Actor actor, int maxDistance) {
        BlockVector3 origin = actor.position();
        if (origin == null) {
            // Nothing to look from, and the caller reports that: answering with
            // the world origin made a console ray trace from 0,0,0.
            return null;
        }
        Vector3 direction = actor.direction().normalize();
        double eyeHeight = actor.isPlayer() ? 1.62 : 0.0;
        net.minecraft.world.phys.Vec3 from =
                new net.minecraft.world.phys.Vec3(origin.x() + 0.5, origin.y() + eyeHeight, origin.z() + 0.5);
        net.minecraft.world.phys.Vec3 to = from.add(direction.x() * maxDistance,
                direction.y() * maxDistance, direction.z() * maxDistance);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            return origin.add(direction.multiply(maxDistance).toBlockPoint());
        }
        BlockPos pos = hit.getBlockPos();
        return new BlockVector3(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void playEffect(int x, int y, int z, int effectId) {
        level.levelEvent(effectId, new BlockPos(x, y, z), 0);
    }

    @Override
    public void applyBlockEntity(int x, int y, int z, NbtCompound nbt) {
        LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
        BlockPos pos = new BlockPos(x, y, z);
        // The flush created the block entity of every block it wrote; IMMEDIATE
        // also covers data queued for a block the flush did not write.
        var blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        if (blockEntity == null) {
            FaweMod.LOGGER.debug("No block entity to hold the data at {},{},{}", x, y, z);
            return;
        }
        // The data of a chest is not loaded into the furnace that stands where
        // the chest was meant to go.
        String id = nbt.getString("id", null);
        ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        if (id != null && type != null && !type.equals(ResourceLocation.tryParse(id))) {
            FaweMod.LOGGER.debug("The data at {},{},{} is for {}, the block entity there is {}", x, y, z, id, type);
            return;
        }
        try {
            // The data is read through the game's own tag reader, which is what
            // knows how a compound of a saved world becomes a live block entity.
            blockEntity.loadWithComponents(TagValueInput.create(
                    ProblemReporter.DISCARDING, level.registryAccess(), toTag(nbt)));
            chunk.markUnsaved();
            level.sendBlockUpdated(pos, blockEntity.getBlockState(), blockEntity.getBlockState(),
                    Block.UPDATE_CLIENTS);
        } catch (Throwable throwable) {
            FaweMod.LOGGER.warn("Could not load the data of the block entity at {},{},{}", x, y, z, throwable);
        }
    }

    /**
     * The data of a block entity, saved the way the game saves it into a chunk.
     * Server thread only: the chunk's block entities are not safe to read from
     * another.
     */
    @Override
    public NbtCompound getBlockEntity(int x, int y, int z) {
        LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
        var blockEntity = chunk.getBlockEntity(new BlockPos(x, y, z), LevelChunk.EntityCreationType.CHECK);
        if (blockEntity == null) {
            return null;
        }
        try {
            net.minecraft.world.level.storage.TagValueOutput output =
                    net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                            ProblemReporter.DISCARDING, level.registryAccess());
            blockEntity.saveWithId(output);
            return fromTag(output.buildResult());
        } catch (IOException | RuntimeException exception) {
            FaweMod.LOGGER.warn("Could not save the data of the block entity at {},{},{}", x, y, z, exception);
            return null;
        }
    }

    /**
     * The positions of the block entities of a box, from the chunks' own maps.
     * Server thread only, like every chunk read.
     */
    @Override
    public void forEachBlockEntity(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                   BlockEntityVisitor visitor) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                // A copy of the positions, the pending ones included: reading a
                // pending block entity makes the chunk promote it.
                for (BlockPos pos : level.getChunk(chunkX, chunkZ).getBlockEntitiesPos()) {
                    int x = pos.getX();
                    int y = pos.getY();
                    int z = pos.getZ();
                    if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                        visitor.visit(x, y, z);
                    }
                }
            }
        }
    }

    /** The game's tag as the engine's compound, through the binary form both of them read and write. */
    private static NbtCompound fromTag(CompoundTag tag) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.write(tag, new java.io.DataOutputStream(bytes));
        return com.maxlananas.fawebim.core.util.NbtIo.read(bytes.toByteArray());
    }

    /** The engine's compound as the game's tag. */
    private static CompoundTag toTag(NbtCompound nbt) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, Object> entry : nbt.entries().entrySet()) {
            Tag value = toTag(entry.getValue());
            if (value != null) {
                tag.put(entry.getKey(), value);
            }
        }
        return tag;
    }

    private static Tag toTag(Object value) {
        if (value instanceof NbtCompound compound) {
            return toTag(compound);
        }
        if (value instanceof byte[] bytes) {
            return new net.minecraft.nbt.ByteArrayTag(bytes);
        }
        if (value instanceof int[] ints) {
            return new net.minecraft.nbt.IntArrayTag(ints);
        }
        if (value instanceof long[] longs) {
            return new net.minecraft.nbt.LongArrayTag(longs);
        }
        if (value instanceof List<?> list) {
            ListTag tags = new ListTag();
            for (Object element : list) {
                Tag tag = toTag(element);
                if (tag != null) {
                    tags.add(tag);
                }
            }
            return tags;
        }
        if (value instanceof Byte) {
            return net.minecraft.nbt.ByteTag.valueOf((Byte) value);
        }
        if (value instanceof Short) {
            return net.minecraft.nbt.ShortTag.valueOf((Short) value);
        }
        if (value instanceof Integer) {
            return net.minecraft.nbt.IntTag.valueOf((Integer) value);
        }
        if (value instanceof Long) {
            return net.minecraft.nbt.LongTag.valueOf((Long) value);
        }
        if (value instanceof Float) {
            return net.minecraft.nbt.FloatTag.valueOf((Float) value);
        }
        if (value instanceof Double) {
            return net.minecraft.nbt.DoubleTag.valueOf((Double) value);
        }
        if (value instanceof String text) {
            return net.minecraft.nbt.StringTag.valueOf(text);
        }
        if (value instanceof Boolean flag) {
            return net.minecraft.nbt.ByteTag.valueOf((byte) (flag ? 1 : 0));
        }
        return null;
    }

    @Override
    public void sync(Runnable task) {
        if (level.getServer().isSameThread()) {
            task.run();
        } else {
            level.getServer().execute(task);
        }
    }

    @Override
    public void async(Runnable task) {
        workers().execute(task);
    }

    @Override
    public java.util.concurrent.ExecutorService executor() {
        return workers();
    }
    /**
     * The dimension's {@code region} folder, so {@code /anvil} can inspect chunks
     * the server has not loaded. Vanilla keeps the overworld at the world root and
     * the other dimensions in their own folder.
     */
    @Override
    public java.nio.file.Path regionDirectory() {
        try {
            java.nio.file.Path root = level.getServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                return root.resolve("region");
            }
            if (level.dimension().equals(net.minecraft.world.level.Level.NETHER)) {
                return root.resolve("DIM-1").resolve("region");
            }
            if (level.dimension().equals(net.minecraft.world.level.Level.END)) {
                return root.resolve("DIM1").resolve("region");
            }
            net.minecraft.resources.ResourceLocation location = level.dimension().location();
            return root.resolve("dimensions").resolve(location.getNamespace())
                    .resolve(location.getPath()).resolve("region");
        } catch (RuntimeException e) {
            return null;
        }
    }

}
