package com.maxlananas.fawebim.core.extent;

import com.maxlananas.fawebim.core.history.ChangeSet;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.util.TimeLimiter;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.ChunkSet;
import com.maxlananas.fawebim.core.world.Extent;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The editing extent.
 *
 * <p>This is where FAWE's architecture lives:</p>
 * <ul>
 *   <li>writes go into per-chunk buffers ({@link ChunkSet}) instead of touching
 *       the world block by block,</li>
 *   <li>each write is recorded in the history as a change set,</li>
 *   <li>the session's global mask, change limit and transform set are applied,</li>
 *   <li>side effects (lighting/neighbour updates) are deferred and only applied
 *       to the positions that really changed, and</li>
 *   <li>the buffers are flushed to the world in batches.</li>
 * </ul>
 */
public final class EditSession implements Extent {

    private final World world;
    private final LocalSession session;
    private final History.Record record;
    private final BlockStateRegistry registry;

    private final java.util.Map<Long, ChunkSet> chunks = new java.util.LinkedHashMap<>();
    private final Set<BlockVector2> dirtyChunks = new LinkedHashSet<>();

    private Mask mask;
    private Transform transform = Transform.identity();
    private final TimeLimiter limiter;
    private int changeLimit;
    private int blocksChanged;
    private long lastBlockCount;
    private boolean cancelled;
    private boolean tracing;
    private final List<String> traceLog = new ArrayList<>();
    private boolean queueEnabled = true;

    public EditSession(World world, LocalSession session, String description) {
        this(world, session, description, true);
    }

    /**
     * @param recordHistory false for the undo/redo passes and read-only work,
     *                      which must not create a history entry of their own
     */
    public EditSession(World world, LocalSession session, String description, boolean recordHistory) {
        this.world = world;
        this.session = session;
        session.setLastWorldName(world.name());
        this.registry = BlockStateRegistryHolder.registry();
        this.record = recordHistory ? session.getHistory().newRecord(description, world.name()) : null;
        this.limiter = new TimeLimiter(session.getTimeout() * 1000L);
        this.changeLimit = session.hasBlockChangeLimit() ? session.getMaxBlocksChanged() : -1;
        this.mask = session.getMask();
        if (session.getTransformSet() != null && !session.getTransformSet().getTransforms().isEmpty()) {
            this.transform = session.getTransformSet().getTransforms();
        }
    }

    /** Simple holder so the engine never has to pass a registry explicitly. */
    public static final class BlockStateRegistryHolder {

        private static BlockStateRegistry registry;

        public static void set(BlockStateRegistry value) {
            registry = value;
        }

        public static BlockStateRegistry registry() {
            if (registry == null) {
                throw new IllegalStateException("BlockStateRegistry not installed");
            }
            return registry;
        }
    }

    public World getWorld() {
        return world;
    }

    public LocalSession getSession() {
        return session;
    }

    public History.Record getRecord() {
        return record;
    }

    public void setMask(Mask mask) {
        this.mask = mask;
    }

    public Mask getMask() {
        return mask;
    }

    public void setTransform(Transform transform) {
        this.transform = transform == null ? Transform.identity() : transform;
    }

    public Transform getTransform() {
        return transform;
    }

    public void setChangeLimit(int limit) {
        this.changeLimit = limit;
    }

    public int getChangeLimit() {
        return changeLimit;
    }

    public void setQueueEnabled(boolean enabled) {
        this.queueEnabled = enabled;
    }

    public void setTracing(boolean tracing) {
        this.tracing = tracing;
    }

    public List<String> getTraceLog() {
        return traceLog;
    }

    public int getBlocksChanged() {
        return blocksChanged;
    }

    public void setBlocksChanged(int value) {
        this.blocksChanged = value;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
    }

    public long getVolume() {
        return lastBlockCount;
    }

    public void setVolume(long volume) {
        this.lastBlockCount = volume;
    }

    @Override
    public int minY() {
        return world.minY();
    }

    @Override
    public int maxY() {
        return world.maxY();
    }

    @Override
    public int getBlock(int x, int y, int z) {
        ChunkSet chunk = chunkFor(x, z, false);
        if (chunk != null) {
            int state = chunk.getBlock(x, y, z);
            if (state != -1) {
                return state;
            }
        }
        return readMasked(x, y, z);
    }

    /** Reads straight from the world, ignoring queued changes. */
    public int getOriginalBlock(int x, int y, int z) {
        return world.getBlock(x, y, z);
    }

    @Override
    public int getBiome(int x, int y, int z) {
        return world.getBiome(x, y, z);
    }

    @Override
    public boolean setBiome(int x, int y, int z, int biomeId) {
        ChunkSet chunk = chunkFor(x, z, true);
        // A biome cell holds 4x4x4 blocks and commands address blocks, so the
        // buffer is asked first: without this the world would be read 64 times
        // per cell and every call after the first would record a no-op change.
        int previous = chunk.getBiome(x, y, z);
        if (previous == biomeId) {
            return false;
        }
        // The previous value has to be known before the buffer takes the new
        // one, or /snapshot restore -b would restore what the edit just wrote.
        if (previous < 0) {
            previous = world.getBiome(x, y, z);
            if (previous == biomeId) {
                return false;
            }
        }
        if (record != null) {
            record.addBiome(x, y, z, previous, biomeId);
        }
        chunk.setBiome(x, y, z, biomeId, world.minY());
        return true;
    }

    /** Re-applies a recorded run of biome changes, the block equivalent of {@link #applyChangeSet}. */
    public int applyBiomeChangeSet(com.maxlananas.fawebim.core.history.BiomeChangeSet set, boolean undo) {
        int[] values = undo ? set.before() : set.after();
        for (int i = 0; i < set.size(); i++) {
            setBiome(set.x(i), set.y(i), set.z(i), values[i]);
        }
        return set.size();
    }

    @Override
    public boolean setBlock(int x, int y, int z, int stateId) {
        return setBlock(x, y, z, stateId, true);
    }

    /** Core write path; {@code record} controls whether history is recorded. */
    public boolean setBlock(int x, int y, int z, int stateId, boolean recordChange) {
        if (cancelled) {
            return false;
        }
        if (stateId < 0) {
            return false;
        }
        if (mask != null && !mask.isRegion() && !mask.test(x, y, z)) {
            return false;
        }
        int previous = world.getBlock(x, y, z);
        if (previous == stateId) {
            return false;
        }
        if (changeLimit > 0 && blocksChanged >= changeLimit) {
            throw new MaxChangedBlocksException(changeLimit);
        }
        ChunkSet chunk = chunkFor(x, z, true);
        chunk.set(x, y, z, stateId, registry.air());
        if (recordChange) {
            record(chunk, x, y, z, previous, stateId);
        }
        blocksChanged++;
        limiter.count(1);
        if (tracing) {
            traceLog.add("set " + x + "," + y + "," + z + " " + registry.describe(previous) + " -> "
                    + registry.describe(stateId));
        }
        if (queueEnabled && blocksChanged % 4096 == 0) {
            flushChunksThatAreFull();
        }
        return true;
    }

    private void record(ChunkSet chunk, int x, int y, int z, int previous, int stateId) {
        if (record != null) {
            record.addChange(x, y, z, previous, stateId);
        }
    }

    private ChunkSet chunkFor(int x, int z, boolean create) {
        long key = History.key(x >> 4, z >> 4);
        ChunkSet chunk = chunks.get(key);
        if (chunk == null && create) {
            chunk = new ChunkSet(x >> 4, z >> 4, world.minY(), world.maxY());
            chunks.put(key, chunk);
        }
        return chunk;
    }

    /** Flushes chunks once the buffer is holding a lot of data, keeping memory bounded. */
    private void flushChunksThatAreFull() {
        if (chunks.size() >= 64) {
            flushQueue();
        }
    }

    /** Applies every buffered chunk to the world and relights what changed. */
    public void flushQueue() {
        if (chunks.isEmpty()) {
            return;
        }
        List<ChunkSet> pending = new ArrayList<>(chunks.values());
        chunks.clear();
        for (ChunkSet chunk : pending) {
            if (!chunk.isEmpty()) {
                world.loadChunk(chunk.chunkX(), chunk.chunkZ());
                world.applyChunk(chunk, chunk.changed() == null ? List.of() : toList(chunk));
                dirtyChunks.add(new BlockVector2(chunk.chunkX(), chunk.chunkZ()));
            }
        }
        if (!dirtyChunks.isEmpty()) {
            world.relight(dirtyChunks);
            dirtyChunks.clear();
        }
    }

    private static List<BlockVector3> toList(ChunkSet chunk) {
        List<BlockVector3> list = new ArrayList<>(chunk.size());
        chunk.changed().forEach(list::add);
        return list;
    }

    @Override
    public List<com.maxlananas.fawebim.core.world.EntityData> getEntities(Extent.Region3i box) {
        return world.getEntities(box);
    }

    @Override
    public void addEntity(com.maxlananas.fawebim.core.world.EntityData data) {
        recordEntity(data, false);
        world.addEntity(data);
    }

    /** Remembers an entity change so undo and {@code /snapshot restore -e} can replay it. */
    private void recordEntity(com.maxlananas.fawebim.core.world.EntityData data, boolean removed) {
        if (record == null) {
            return;
        }
        record.addEntity(new History.EntityChange(data.type(), data.nbt(), data.position().x(),
                data.position().y(), data.position().z(), removed));
    }

    @Override
    public void removeEntity(com.maxlananas.fawebim.core.world.EntityData data) {
        recordEntity(data, true);
        world.removeEntity(data);
    }

    public void setBlockEntity(int x, int y, int z, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
        world.sync(() -> world.setBlockEntity(x, y, z, nbt));
    }

    /** Applies a change set back to the world (undo/redo). */
    public int applyChangeSet(ChangeSet set, boolean undo) {
        int[] indices = set.indices();
        int[] values = undo ? set.before() : set.after();
        int baseX = set.chunkX() << 4;
        int baseY = set.sectionY() << 4;
        int baseZ = set.chunkZ() << 4;
        ChunkSet chunk = chunkFor(baseX, baseZ, true);
        int air = registry.air();
        for (int i = 0; i < set.size(); i++) {
            int index = indices[i];
            int x = baseX + (index & 15);
            int y = baseY + ((index >> 8) & 15);
            int z = baseZ + ((index >> 4) & 15);
            chunk.set(x, y, z, values[i], air);
        }
        return set.size();
    }

    @Override
    public boolean isWorld() {
        return false;
    }

    /** Statistics string used by command feedback. */
    public String statistics() {
        return blocksChanged + " block(s) changed";
    }

    public TimeLimiter limiter() {
        return limiter;
    }

    public void checkTimeout() {
        if (session.isCancelled()) {
            session.clearCancel();
            throw new CancelledException();
        }
        if (session.isWatchdogEnabled() && limiter.isExpired()) {
            throw new TimeLimiter.OperationTimeoutException(limiter.elapsedMillis(), limiter.processed());
        }
    }

    /**
     * The session's source mask, applied to reads: an operation only sees the
     * blocks the mask accepts, which is what {@code //gsmask} is for.
     */
    public Mask sourceMask() {
        return session.getSourceMask();
    }

    private int readMasked(int x, int y, int z) {
        Mask source = session.getSourceMask();
        if (source != null && !source.test(x, y, z)) {
            return com.maxlananas.fawebim.core.world.BlockState.registry().air();
        }
        return world.getBlock(x, y, z);
    }

    /** Thrown when {@code /cancel} is used while an edit is running. */
    public static final class CancelledException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Operation cancelled");
        }
    }

    /** Convenience for messages: format the operation summary. */
    public Msg summary() {
        return Msg.success("Operation completed: " + blocksChanged + " block(s) affected");
    }

    /** Thrown when the session's block change limit is hit. */
    public static final class MaxChangedBlocksException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int limit;

        public MaxChangedBlocksException(int limit) {
            super("Max blocks changed in an operation: " + limit);
            this.limit = limit;
        }

        public int getLimit() {
            return limit;
        }
    }

    public Collection<ChunkSet> pendingChunks() {
        return chunks.values();
    }
}
