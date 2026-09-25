package com.maxlananas.fawebim.core.extent;

import com.maxlananas.fawebim.core.history.ChangeSet;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector2;
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

    /** The buffer the previous write used, and the key it was found under. */
    private ChunkSet currentChunk;

    /**
     * True while the source mask is deciding a read. A mask such as
     * {@code //gsmask stone} reads a block to answer, and that read must come
     * from the world: letting it apply the mask again has no base case.
     */
    private boolean applyingSourceMask;
    private long currentChunkKey;

    private final com.maxlananas.fawebim.core.util.LongObjectMap<ChunkSet> chunks =
            new com.maxlananas.fawebim.core.util.LongObjectMap<>();
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
    /**
     * The side effects of this edit, resolved once when it opens: the session's
     * set, with lighting and the per-block notifications deferred when the
     * session runs in fast mode.
     */
    private final com.maxlananas.fawebim.core.session.SideEffectSet sideEffects;
    private boolean queueEnabled = true;
    /** When the session was opened, which is what queue.max-wait-ms measures. */
    private final long openedAt = System.currentTimeMillis();

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
        com.maxlananas.fawebim.core.session.SideEffectSet effects = session.getSideEffectSet();
        if (session.isFastMode()) {
            // Fast mode is the promise that an edit writes blocks and little
            // else: lighting waits for the flush and the per-block notifications
            // are skipped, which is what the command warns about.
            effects = effects
                    .with(com.maxlananas.fawebim.core.session.SideEffect.LIGHTING,
                            com.maxlananas.fawebim.core.session.SideEffect.State.DELAYED)
                    .with(com.maxlananas.fawebim.core.session.SideEffect.NEIGHBORS,
                            com.maxlananas.fawebim.core.session.SideEffect.State.OFF)
                    .with(com.maxlananas.fawebim.core.session.SideEffect.UPDATE,
                            com.maxlananas.fawebim.core.session.SideEffect.State.OFF);
        }
        this.sideEffects = effects;
        // With history turned off in the configuration, or with the history side
        // effect switched off in the session, the edit records nothing, so
        // //undo has nothing to undo and no record is kept.
        this.record = recordHistory && com.maxlananas.fawebim.core.platform.Config.get().historyEnabled
                && effects.shouldApply(com.maxlananas.fawebim.core.session.SideEffect.HISTORY)
                ? session.getHistory().newRecord(description, world.name())
                : null;
        this.tracing = session.isTracing();
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

    public boolean isTracing() {
        return tracing;
    }

    /** The side effects this edit applies, resolved when it opened. */
    public com.maxlananas.fawebim.core.session.SideEffectSet sideEffects() {
        return sideEffects;
    }

    /**
     * Prints what a traced edit did, which only the session flag turns on.
     * A large edit records a line per block, so the report stops after a screen
     * and counts the rest.
     */
    public void reportTrace(com.maxlananas.fawebim.core.actor.Actor actor) {
        if (!tracing) {
            return;
        }
        if (traceLog.isEmpty()) {
            actor.message(com.maxlananas.fawebim.core.util.Msg.info("Trace: no block was written"));
            return;
        }
        int shown = Math.min(traceLog.size(), TRACE_REPORT_LIMIT);
        for (int index = 0; index < shown; index++) {
            actor.message(com.maxlananas.fawebim.core.util.Msg.info("Trace: " + traceLog.get(index)));
        }
        if (traceLog.size() > shown) {
            actor.message(com.maxlananas.fawebim.core.util.Msg.info("Trace: "
                    + (traceLog.size() - shown) + " more action(s)"));
        }
    }

    /** How many traced actions a report prints before it counts the rest. */
    private static final int TRACE_REPORT_LIMIT = 20;

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
        chunk.set(x, y, z, stateId);
        if (recordChange) {
            record(chunk, x, y, z, previous, stateId);
        }
        blocksChanged++;
        limiter.count(1);
        if (tracing) {
            traceLog.add("set " + x + "," + y + "," + z + " " + registry.describe(previous) + " -> "
                    + registry.describe(stateId));
        }
        if (queueEnabled && (blocksChanged & 0xFFF) == 0) {
            flushChunksThatAreFull();
        }
        return true;
    }

    private void record(ChunkSet chunk, int x, int y, int z, int previous, int stateId) {
        if (record != null) {
            record.addChange(x, y, z, previous, stateId);
        }
    }

    /**
     * The buffer of the chunk a position belongs to.
     *
     * <p>The last one is remembered: a region is walked x first, so sixteen blocks
     * in a row belong to the same chunk, and without the cache every one of them
     * hashes its chunk key and searches the map for the buffer it is about to
     * append to.</p>
     */
    private ChunkSet chunkFor(int x, int z, boolean create) {
        long key = History.key(x >> 4, z >> 4);
        ChunkSet cached = currentChunk;
        if (cached != null && currentChunkKey == key) {
            return cached;
        }
        ChunkSet chunk = chunks.get(key);
        if (chunk == null) {
            if (!create) {
                return null;
            }
            chunk = new ChunkSet(x >> 4, z >> 4, world.minY(), world.maxY());
            chunks.put(key, chunk);
        }
        currentChunk = chunk;
        currentChunkKey = key;
        return chunk;
    }

    /**
     * Flushes the buffer once it is holding a lot of data, keeping memory
     * bounded. How much is "a lot" is {@code queue.target-size}; the wait is
     * {@code queue.max-wait-ms}, so a long single-chunk edit cannot sit in memory
     * for the whole operation either.
     */
    private void flushChunksThatAreFull() {
        long target = Math.max(4096, com.maxlananas.fawebim.core.platform.Config.get().queueTargetSize);
        long maxWait = Math.max(0, com.maxlananas.fawebim.core.platform.Config.get().queueMaxWait);
        if (blocksChanged >= target) {
            flushQueue();
            return;
        }
        if (chunks.size() >= 64
                || (maxWait > 0 && System.currentTimeMillis() - openedAt >= maxWait && !chunks.isEmpty())) {
            flushQueue();
        }
    }

    /** Applies every buffered chunk to the world and relights what changed. */
    public void flushQueue() {
        if (chunks.isEmpty()) {
            return;
        }
        List<ChunkSet> pending = chunks.values();
        chunks.clear();
        currentChunk = null;
        for (ChunkSet chunk : pending) {
            if (!chunk.isEmpty()) {
                world.loadChunk(chunk.chunkX(), chunk.chunkZ());
                world.applyChunk(chunk, sideEffects);
                dirtyChunks.add(new BlockVector2(chunk.chunkX(), chunk.chunkZ()));
            }
        }
        if (!dirtyChunks.isEmpty()) {
            if (sideEffects.shouldApply(com.maxlananas.fawebim.core.session.SideEffect.LIGHTING)) {
                world.relight(dirtyChunks);
            }
            dirtyChunks.clear();
        }
    }

    @Override
    public List<com.maxlananas.fawebim.core.world.EntityData> getEntities(Extent.Region3i box) {
        return world.getEntities(box);
    }

    @Override
    public void addEntity(com.maxlananas.fawebim.core.world.EntityData data) {
        if (!sideEffects.shouldApply(com.maxlananas.fawebim.core.session.SideEffect.ENTITY_EVENTS)) {
            return;
        }
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
        if (!sideEffects.shouldApply(com.maxlananas.fawebim.core.session.SideEffect.ENTITY_EVENTS)) {
            return;
        }
        recordEntity(data, true);
        world.removeEntity(data);
    }

    /**
     * Queues the data of a block entity, to be written when the blocks of its
     * chunk are: the platform applies both together, in that order.
     */
    public void setBlockEntity(int x, int y, int z, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
        if (nbt == null) {
            return;
        }
        ChunkSet chunk = chunkFor(x, z, true);
        if (chunk == null) {
            return;
        }
        chunk.setBlockEntity(x, y, z, nbt);
    }

    /** Applies a change set back to the world (undo/redo). */
    public int applyChangeSet(ChangeSet set, boolean undo) {
        int baseX = set.chunkX() << 4;
        int baseY = set.sectionY() << 4;
        int baseZ = set.chunkZ() << 4;
        ChunkSet chunk = chunkFor(baseX, baseZ, true);
        int size = set.size();
        for (int row = 0; row < size; row++) {
            int cell = set.cellAt(row);
            int x = baseX + (cell & 15);
            int y = baseY + ((cell >> 8) & 15);
            int z = baseZ + ((cell >> 4) & 15);
            chunk.set(x, y, z, undo ? set.beforeAt(row) : set.afterAt(row));
        }
        return size;
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
        if (source != null && !applyingSourceMask) {
            applyingSourceMask = true;
            boolean accepted;
            try {
                accepted = source.test(x, y, z);
            } finally {
                applyingSourceMask = false;
            }
            if (!accepted) {
                return com.maxlananas.fawebim.core.world.BlockState.registry().air();
            }
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
