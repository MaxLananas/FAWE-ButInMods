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
    /**
     * The last chunk a read found no buffer for. An edit that writes nothing
     * where it reads - a {@code //replace} over blocks that do not match - asks
     * the buffer about every block it visits, and without this every one of
     * those questions was a hash lookup that finds nothing.
     */
    private long missingChunkKey;
    private boolean missingChunkKnown;
    /** The world's height, read once: it does not change while an edit runs. */
    private final int minY;
    private final int maxY;

    private final com.maxlananas.fawebim.core.util.LongObjectMap<ChunkSet> chunks =
            new com.maxlananas.fawebim.core.util.LongObjectMap<>();
    private final Set<BlockVector2> dirtyChunks = new LinkedHashSet<>();

    private Mask mask;
    private Transform transform = Transform.identity();
    private final TimeLimiter limiter;
    private int changeLimit;
    private long blocksChanged;
    /** Writes buffered since the last flush, which is what queue.target-size bounds. */
    private long bufferedWrites;
    private long lastBlockCount;
    private boolean cancelled;
    private boolean closed;
    private boolean tracing;
    /** The first traced actions, the ones a report prints. */
    private final List<String> traceLog = new ArrayList<>();
    /** Every traced action, kept or not. */
    private long traceCount;
    /**
     * The side effects of this edit, resolved once when it opens: the session's
     * set, with lighting and the per-block notifications deferred when the
     * session runs in fast mode.
     */
    private final com.maxlananas.fawebim.core.session.SideEffectSet sideEffects;
    private boolean queueEnabled = true;
    /** When the session was opened, for the time a result line reports. */
    private final long openedAt = System.currentTimeMillis();
    /**
     * When the queue was last written out: queue.max-wait-ms is how long a
     * buffered change may wait, not how long the edit may run before every
     * later check writes the queue out a few thousand blocks at a time.
     */
    private long lastFlushAt = openedAt;

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
        this.minY = world.minY();
        this.maxY = world.maxY();
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
        if (record != null) {
            record.owner = session.ownerName();
        }
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
        for (String line : traceLog) {
            actor.message(com.maxlananas.fawebim.core.util.Msg.info("Trace: " + line));
        }
        if (traceCount > traceLog.size()) {
            actor.message(com.maxlananas.fawebim.core.util.Msg.info("Trace: "
                    + Msg.formatNumber(traceCount - traceLog.size()) + " more action(s)"));
        }
    }

    /**
     * How many traced actions are kept for the report; the rest are counted.
     * A traced edit of a million blocks used to keep a formatted line for each
     * of them, of which the report printed twenty.
     */
    private static final int TRACE_REPORT_LIMIT = 20;

    /** The traced actions a report prints: the first ones, at most twenty. */
    public List<String> getTraceLog() {
        return traceLog;
    }

    public long getBlocksChanged() {
        return blocksChanged;
    }

    public void setBlocksChanged(long value) {
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
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
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
        checkOpen();
        if (y < minY || y > maxY) {
            return false;
        }
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
        chunk.setBiome(x, y, z, biomeId, minY);
        return true;
    }

    /**
     * Re-applies a recorded run of biome changes, the block equivalent of
     * {@link #applyChangeSet}, and walked the same way: back to front for an
     * undo, so a cell changed twice ends on the value it had first.
     */
    public int applyBiomeChangeSet(com.maxlananas.fawebim.core.history.BiomeChangeSet set, boolean undo) {
        int size = set.size();
        if (undo) {
            int[] values = set.before();
            for (int i = size - 1; i >= 0; i--) {
                setBiome(set.x(i), set.y(i), set.z(i), values[i]);
            }
        } else {
            int[] values = set.after();
            for (int i = 0; i < size; i++) {
                setBiome(set.x(i), set.y(i), set.z(i), values[i]);
            }
        }
        return size;
    }

    @Override
    public boolean setBlock(int x, int y, int z, int stateId) {
        return setBlock(x, y, z, stateId, true);
    }

    /**
     * The write path; {@code recordChange} says whether the history records the write.
     *
     * <p>The state a write replaces is what the position holds for this edit:
     * the write still waiting in the buffer, else the world. A cell the edit
     * already wrote and has not flushed still holds its old state in the world;
     * comparing against that dropped a write that put the old state back as a
     * no-op - the pending write then reached the world anyway, which is how an
     * overlapping move left holes - and recorded a "before" in the history the
     * cell never had at that point.</p>
     */
    public boolean setBlock(int x, int y, int z, int stateId, boolean recordChange) {
        if (cancelled || stateId < 0) {
            return false;
        }
        if (y < minY || y > maxY) {
            // There is no block outside the world: counting one here would put a
            // change that never happened into the block count and the history.
            return false;
        }
        if (mask != null && !mask.isRegion() && !mask.test(x, y, z)) {
            return false;
        }
        // Nothing buffered means nothing to find: an edit that writes what the
        // world already holds skips the lookup entirely.
        ChunkSet chunk = chunks.isEmpty() ? null : chunkFor(x, z, false);
        int previous = chunk == null ? -1 : chunk.getBlock(x, y, z);
        if (previous == -1) {
            previous = world.getBlock(x, y, z);
        }
        if (previous == stateId) {
            return false;
        }
        return write(chunk, x, y, z, previous, stateId, recordChange);
    }

    /**
     * The same write for a caller that already knows what the position holds.
     *
     * <p>A copy-and-clear pass - {@code //cut} above all - reads the block it is
     * about to overwrite, so handing that value over saves a second trip into
     * the world for every position of the selection, and the common cell that is
     * already the value being written costs nothing at all.</p>
     *
     * @param previous the state the position holds, as the caller read it
     * @return whether the world changed
     */
    public boolean setBlockKnown(int x, int y, int z, int previous, int stateId, boolean recordChange) {
        if (cancelled || stateId < 0) {
            return false;
        }
        if (y < minY || y > maxY) {
            return false;
        }
        // The caller read the world; a write of this edit still in the buffer
        // is what the cell holds now. Asking the buffer costs a lookup in the
        // chunk the edit is already writing, not a trip into the world.
        ChunkSet chunk = chunkFor(x, z, false);
        if (chunk != null) {
            int buffered = chunk.getBlock(x, y, z);
            if (buffered != -1) {
                previous = buffered;
            }
        }
        if (previous == stateId) {
            return false;
        }
        if (mask != null && !mask.isRegion() && !mask.test(x, y, z)) {
            return false;
        }
        return write(chunk, x, y, z, previous, stateId, recordChange);
    }

    /** The back half of the write path, shared by both entry points. */
    /**
     * @param chunk the buffer of the position's chunk, or {@code null} when the
     *              caller found none and it has to be created
     */
    private boolean write(ChunkSet chunk, int x, int y, int z, int previous, int stateId, boolean recordChange) {
        checkOpen();
        if (changeLimit > 0 && blocksChanged >= changeLimit) {
            throw new MaxChangedBlocksException(changeLimit);
        }
        if (chunk == null) {
            chunk = chunkFor(x, z, true);
        }
        chunk.set(x, y, z, stateId);
        if (recordChange) {
            record(chunk, x, y, z, previous, stateId);
        }
        blocksChanged++;
        bufferedWrites++;
        limiter.count(1);
        if (tracing) {
            if (traceLog.size() < TRACE_REPORT_LIMIT) {
                traceLog.add("set " + x + "," + y + "," + z + " " + registry.describe(previous) + " -> "
                        + registry.describe(stateId));
            }
            traceCount++;
        }
        if (queueEnabled && (blocksChanged & 0xFFF) == 0) {
            flushChunksThatAreFull();
        }
        return true;
    }

    /** A write to a closed session would be applied by nobody and recorded nowhere. */
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Edit session '"
                    + (record == null ? "unrecorded" : record.description) + "' is closed");
        }
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
        if (!create && missingChunkKnown && missingChunkKey == key) {
            return null;
        }
        ChunkSet chunk = chunks.get(key);
        if (chunk == null) {
            if (!create) {
                missingChunkKey = key;
                missingChunkKnown = true;
                return null;
            }
            chunk = new ChunkSet(x >> 4, z >> 4, minY, maxY);
            chunks.put(key, chunk);
            if (missingChunkKnown && missingChunkKey == key) {
                missingChunkKnown = false;
            }
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
        if (bufferedWrites >= target || chunks.size() >= MAX_BUFFERED_CHUNKS
                || (maxWait > 0 && !chunks.isEmpty() && System.currentTimeMillis() - lastFlushAt >= maxWait)) {
            flushQueue();
        }
    }

    /** Chunks the queue holds before it is written out, whatever their size. */
    private static final int MAX_BUFFERED_CHUNKS = 64;

    /** Applies every buffered chunk to the world and relights what changed. */
    public void flushQueue() {
        bufferedWrites = 0;
        lastFlushAt = System.currentTimeMillis();
        if (chunks.isEmpty()) {
            return;
        }
        List<ChunkSet> pending = chunks.values();
        chunks.clear();
        currentChunk = null;
        for (ChunkSet chunk : pending) {
            if (!chunk.isEmpty()) {
                world.loadChunk(chunk.chunkX(), chunk.chunkZ());
                recordBlockEntities(chunk);
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

    /**
     * Records the block entities a buffered chunk is about to change, while the
     * world still holds them: the data of each one whose block the buffer
     * writes or whose data it replaces, with what the buffer gives it.
     *
     * <p>The world is asked for the block entities of the chunk, which are few,
     * rather than about every position the buffer writes.</p>
     */
    private void recordBlockEntities(ChunkSet chunk) {
        if (record == null) {
            return;
        }
        int minX = chunk.chunkX() << 4;
        int minZ = chunk.chunkZ() << 4;
        List<ChunkSet.BlockEntity> queued = chunk.blockEntities();
        com.maxlananas.fawebim.core.util.LongObjectMap<com.maxlananas.fawebim.core.util.NbtCompound> incoming =
                new com.maxlananas.fawebim.core.util.LongObjectMap<>(queued.size());
        for (ChunkSet.BlockEntity entity : queued) {
            // A position queued twice ends with the later data, as the world applies them in order.
            incoming.put(cellOf(entity.x, entity.y, entity.z), entity.nbt);
        }
        com.maxlananas.fawebim.core.util.LongSet recorded = new com.maxlananas.fawebim.core.util.LongSet();
        world.forEachBlockEntity(minX, minY, minZ, minX + 15, maxY, minZ + 15, (x, y, z) -> {
            long cell = cellOf(x, y, z);
            com.maxlananas.fawebim.core.util.NbtCompound after = incoming.get(cell);
            if (after != null || chunk.isSet(x, y, z)) {
                record.addBlockEntity(x, y, z, world.getBlockEntity(x, y, z), after);
                recorded.add(cell);
            }
        });
        for (long cell : incoming.keys()) {
            if (!recorded.contains(cell)) {
                record.addBlockEntity(minX + (int) (cell & 15), minY + (int) (cell >> 8), minZ + (int) ((cell >> 4) & 15),
                        null, incoming.get(cell));
            }
        }
    }

    /** A position inside its chunk column, as a key: the height above the bottom, then z, then x. */
    private long cellOf(int x, int y, int z) {
        return ((long) (y - minY) << 8) | ((z & 15) << 4) | (x & 15);
    }

    @Override
    public List<com.maxlananas.fawebim.core.world.EntityData> getEntities(Extent.Region3i box) {
        return world.getEntities(box);
    }

    /**
     * Spawns an entity with a new identity, recorded so an undo removes it.
     * A paste of entities goes through here.
     */
    @Override
    public void addEntity(com.maxlananas.fawebim.core.world.EntityData data) {
        checkOpen();
        if (!sideEffects.shouldApply(com.maxlananas.fawebim.core.session.SideEffect.ENTITY_EVENTS)) {
            return;
        }
        com.maxlananas.fawebim.core.world.EntityData created = world.spawnEntity(data, null);
        if (created != null && record != null) {
            record.addEntity(new History.EntityChange(data.type(), data.nbt(), data.position().x(),
                    data.position().y(), data.position().z(), false, created.uuid()));
        }
    }

    /**
     * Removes a live entity, recording its data first so an undo puts it back,
     * under the identity it had. {@code /butcher} and {@code /remove} go through
     * here.
     */
    @Override
    public void removeEntity(com.maxlananas.fawebim.core.world.EntityData data) {
        checkOpen();
        if (!sideEffects.shouldApply(com.maxlananas.fawebim.core.session.SideEffect.ENTITY_EVENTS)) {
            return;
        }
        if (record != null) {
            // Each entity comes back on its own: a vehicle's passengers are
            // recorded when they are removed themselves, and left out of its
            // data so an undo does not put them back twice.
            com.maxlananas.fawebim.core.util.NbtCompound nbt = data.nbt();
            if (nbt != null && nbt.contains("Passengers")) {
                nbt = nbt.clone();
                nbt.remove("Passengers");
            }
            record.addEntity(new History.EntityChange(data.type(), nbt, data.position().x(),
                    data.position().y(), data.position().z(), true, data.uuid()));
        }
        world.removeEntity(data);
    }

    /**
     * Replays the entity changes of a record: an undo puts back what the edit
     * removed and removes what it added, a redo the other way round. A
     * removed entity comes back under its own identity, so the redo finds it
     * again; one the game refuses (its identity is back in use) is left alone.
     */
    private void applyEntityChanges(History.Record record, boolean undo) {
        List<History.EntityChange> changes = record.entities();
        for (int i = 0; i < changes.size(); i++) {
            History.EntityChange change = changes.get(undo ? changes.size() - 1 - i : i);
            boolean put = undo == change.removed;
            if (put) {
                if (change.nbt != null) {
                    world.spawnEntity(new com.maxlananas.fawebim.core.world.EntityData(change.type,
                            change.nbt.clone(), new com.maxlananas.fawebim.core.math.Vector3(change.x, change.y,
                            change.z)), change.uuid);
                }
            } else if (change.uuid != null) {
                world.removeEntityById(change.uuid);
            }
        }
    }

    /**
     * Queues the data of a block entity, to be written when the blocks of its
     * chunk are: the platform applies both together, in that order. A position
     * the session's mask or the world's height keeps a block from is kept from
     * the data as well.
     */
    public void setBlockEntity(int x, int y, int z, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
        checkOpen();
        if (nbt == null || cancelled || y < minY || y > maxY
                || mask != null && !mask.isRegion() && !mask.test(x, y, z)) {
            return;
        }
        ChunkSet chunk = chunkFor(x, z, true);
        if (chunk == null) {
            return;
        }
        chunk.setBlockEntity(x, y, z, nbt);
    }

    /**
     * Applies a change set back to the world (undo/redo).
     *
     * <p>A cell can be recorded more than once in a set - written twice by the
     * same edit - so the rows are replayed in the order that ends on the right
     * state: back to front for an undo, which ends on the state the cell held
     * before the edit, front to back for a redo, which ends on the last write.</p>
     */
    public int applyChangeSet(ChangeSet set, boolean undo) {
        checkOpen();
        int baseX = set.chunkX() << 4;
        int baseY = set.sectionY() << 4;
        int baseZ = set.chunkZ() << 4;
        ChunkSet chunk = chunkFor(baseX, baseZ, true);
        int size = set.size();
        if (undo) {
            for (int row = size - 1; row >= 0; row--) {
                int cell = set.cellAt(row);
                chunk.set(baseX + (cell & 15), baseY + ((cell >> 8) & 15), baseZ + ((cell >> 4) & 15),
                        set.beforeAt(row));
            }
        } else {
            for (int row = 0; row < size; row++) {
                int cell = set.cellAt(row);
                chunk.set(baseX + (cell & 15), baseY + ((cell >> 8) & 15), baseZ + ((cell >> 4) & 15),
                        set.afterAt(row));
            }
        }
        return size;
    }

    /**
     * Replays a whole history record: the undo puts back what the edit found,
     * the redo writes what it wrote.
     *
     * <p>The sets of one chunk are replayed in the same order as their rows,
     * and a chunk is complete once its sets are, so the queue is written out
     * every {@value #MAX_BUFFERED_CHUNKS} chunks: the undo of a huge edit holds
     * a bounded part of it in memory instead of all of it.</p>
     *
     * @return the number of recorded block and biome changes replayed
     */
    public int applyRecord(History.Record record, boolean undo) {
        int changed = 0;
        for (List<ChangeSet> sets : record.changes().values()) {
            if (undo) {
                for (int index = sets.size() - 1; index >= 0; index--) {
                    changed += applyChangeSet(sets.get(index), true);
                }
            } else {
                for (ChangeSet set : sets) {
                    changed += applyChangeSet(set, false);
                }
            }
            if (queueEnabled && chunks.size() >= MAX_BUFFERED_CHUNKS) {
                flushQueue();
            }
        }
        for (List<com.maxlananas.fawebim.core.history.BiomeChangeSet> sets : record.biomeChanges().values()) {
            if (undo) {
                for (int index = sets.size() - 1; index >= 0; index--) {
                    changed += applyBiomeChangeSet(sets.get(index), true);
                }
            } else {
                for (com.maxlananas.fawebim.core.history.BiomeChangeSet set : sets) {
                    changed += applyBiomeChangeSet(set, false);
                }
            }
        }
        if (!record.entities().isEmpty()) {
            // A painting or an item frame needs its wall: the blocks go in first.
            flushQueue();
            applyEntityChanges(record, undo);
        }
        // The data goes back after the blocks, which the queue writes first: a
        // chest the undo puts back gets its items, a block the undo takes away
        // takes its block entity with it. Replayed in the order that ends on
        // the right data, as the block changes are.
        List<History.BlockEntityChange> blockEntities = record.blockEntities();
        for (int i = 0; i < blockEntities.size(); i++) {
            History.BlockEntityChange change = blockEntities.get(undo ? blockEntities.size() - 1 - i : i);
            com.maxlananas.fawebim.core.util.NbtCompound data = undo ? change.before() : change.after();
            if (data != null) {
                setBlockEntity(change.x(), change.y(), change.z(), data);
            }
        }
        return changed;
    }

    /**
     * Ends the edit: writes out what is still buffered and publishes the
     * history record, which seals it.
     *
     * <p>Every edit is closed by whoever opened it - the command dispatcher
     * closes the sessions of a command, a tool closes its own - including when
     * the edit failed half way: the part that was written stays written, and
     * the history holds exactly that part, so an undo takes it back. Writing to
     * a closed session throws. Closing twice does nothing.</p>
     */
    public void close() {
        if (closed) {
            return;
        }
        try {
            flushQueue();
        } finally {
            closed = true;
            if (record != null) {
                session.getHistory().publish(record);
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isWorld() {
        return false;
    }

    /** Statistics string used by command feedback. */
    public String statistics() {
        return Msg.formatNumber(blocksChanged) + " block(s) changed";
    }

    public TimeLimiter limiter() {
        return limiter;
    }

    /**
     * The checkpoint a long loop calls: stops the edit when {@code /cancel} was
     * used or the timeout passed. The cancel flag is left set, so every loop of
     * the command stops, not only the first one to see it; the dispatcher
     * clears it when the command is over. The clock is read every 64 calls,
     * since this runs per block in some loops and per column in others.
     */
    public void checkTimeout() {
        if (session.isCancelled()) {
            throw new CancelledException();
        }
        if ((++timeoutChecks & 0x3F) == 0 && session.isWatchdogEnabled() && limiter.isExpired()) {
            throw new TimeLimiter.OperationTimeoutException(limiter.elapsedMillis(), limiter.processed());
        }
    }

    private int timeoutChecks;

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

    /**
     * The one line a world-editing command answers with: the label of what it
     * did, how many blocks that was and how long it took.
     *
     * <p>The time is what tells a player whether a selection is one they can
     * work with: a million blocks is a second or a minute depending on the
     * machine, the size and the side effects they left on.</p>
     */
    public Msg result(String label, long changed, String unit) {
        return Msg.result(label, Msg.count(changed) + " " + unit + " affected in "
                + Msg.value(com.maxlananas.fawebim.core.util.Timer.phrase(elapsed())).raw());
    }

    /** Convenience for messages: format the operation summary. */
    public Msg summary() {
        return result("Operation completed", blocksChanged, "block(s)");
    }

    /** How long this session has been open, in seconds. */
    public double elapsed() {
        return (System.currentTimeMillis() - openedAt) / 1000.0;
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
