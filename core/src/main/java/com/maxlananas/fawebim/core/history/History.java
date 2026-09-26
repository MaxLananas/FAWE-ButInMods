package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.util.NbtCompound;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player undo/redo history. Edits are grouped into {@link Record}s (one per
 * command), and each record holds the changes per chunk section plus the
 * removed entity data, mirroring FAWE's sharded history.
 */
public final class History {

    private int maxRecords;
    private final List<Record> records = new ArrayList<>();
    private int currentIndex = -1;
    private java.util.function.Consumer<Record> recordListener;

    public History(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
    }

    /** How many changes can be undone, as {@code /history size} sets it. */
    public int maxRecords() {
        return maxRecords;
    }

    public void setMaxRecords(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
        while (records.size() > this.maxRecords) {
            records.remove(0);
            currentIndex--;
        }
        if (currentIndex < -1) {
            currentIndex = -1;
        }
    }

    /**
     * A single undo step.
     *
     * <p>A record is written by the one edit that owns it, on the thread running
     * that edit. Once the edit is over the record is {@linkplain #seal() sealed}:
     * from then on it is only read - by an undo, by the edit log, by the snapshot
     * writer on its own thread - and a write to it is a bug that fails loudly
     * instead of changing a history someone else is serialising.</p>
     */
    public static final class Record {

        public final String description;
        /** The world the record belongs to, so the edit log can filter by it. */
        public String world;
        /**
         * Who made the edit. The history of a session can be shared with every
         * other session, so the name travels with the record rather than with
         * whichever session installed the history's listener.
         */
        public String owner;
        private boolean sealed;
        /** Set once the history listener has been told about the record. */
        private boolean published;
        /**
         * The change sets of a record, by packed chunk key. A history map used to
         * hold boxed keys, and an edit that fills thousands of chunks built a
         * tree inside it; the primitive key keeps both away from the hot path.
         */
        final com.maxlananas.fawebim.core.util.LongObjectMap<List<ChangeSet>> changes =
                new com.maxlananas.fawebim.core.util.LongObjectMap<>();
        final com.maxlananas.fawebim.core.util.LongObjectMap<List<BiomeChangeSet>> biomes =
                new com.maxlananas.fawebim.core.util.LongObjectMap<>();
        final List<EntityChange> entities = new ArrayList<>();
        int changeCount;
        int biomeChangeCount;
        /**
         * The section written by the previous call. An edit walks the blocks in
         * order, so this is almost always the section the next block belongs to
         * as well; without it every single block boxes a chunk key and searches
         * the map for the section it is about to append to.
         */
        private ChangeSet currentSection;
        private BiomeChangeSet currentBiomeSection;

        public Record(String description) {
            this.description = description;
        }

        /** Ends the record: every later write to it throws. */
        public void seal() {
            sealed = true;
            currentSection = null;
            currentBiomeSection = null;
        }

        public boolean isSealed() {
            return sealed;
        }

        private void checkOpen() {
            if (sealed) {
                throw new IllegalStateException("History record '" + description + "' is sealed");
            }
        }

        public void add(ChangeSet set) {
            checkOpen();
            sectionsOf(changes, key(set.chunkX(), set.chunkZ())).add(set);
            changeCount += set.size();
        }

        /** Records one block change, creating the section's change set on demand. */
        public void addChange(int x, int y, int z, int previous, int current) {
            checkOpen();
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            int sectionY = y >> 4;
            ChangeSet cached = currentSection;
            if (cached != null && cached.chunkX() == chunkX && cached.chunkZ() == chunkZ
                    && cached.sectionY() == sectionY) {
                cached.add(x, y, z, previous, current);
                changeCount++;
                return;
            }
            List<ChangeSet> sets = sectionsOf(changes, key(chunkX, chunkZ));
            ChangeSet set = null;
            for (ChangeSet candidate : sets) {
                if (candidate.sectionY() == sectionY) {
                    set = candidate;
                    break;
                }
            }
            if (set == null) {
                set = new ChangeSet(chunkX, chunkZ, sectionY);
                sets.add(set);
            }
            currentSection = set;
            set.add(x, y, z, previous, current);
            changeCount++;
        }

        public void addEntity(EntityChange change) {
            checkOpen();
            entities.add(change);
        }

        /** Records one biome change, creating the section's set on demand. */
        public void addBiome(int x, int y, int z, int previous, int current) {
            checkOpen();
            if (previous == current) {
                return;
            }
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            int sectionY = y >> 4;
            BiomeChangeSet cached = currentBiomeSection;
            if (cached != null && cached.chunkX() == chunkX && cached.chunkZ() == chunkZ
                    && cached.sectionY() == sectionY) {
                cached.add(x, y, z, previous, current);
                biomeChangeCount++;
                return;
            }
            List<BiomeChangeSet> sets = sectionsOf(biomes, key(chunkX, chunkZ));
            BiomeChangeSet set = null;
            for (BiomeChangeSet candidate : sets) {
                if (candidate.sectionY() == sectionY) {
                    set = candidate;
                    break;
                }
            }
            if (set == null) {
                set = new BiomeChangeSet(chunkX, chunkZ, sectionY);
                sets.add(set);
            }
            currentBiomeSection = set;
            set.add(x, y, z, previous, current);
            biomeChangeCount++;
        }

        public com.maxlananas.fawebim.core.util.LongObjectMap<List<BiomeChangeSet>> biomeChanges() {
            return biomes;
        }

        public int biomeChangeCount() {
            return biomeChangeCount;
        }

        public int changeCount() {
            return changeCount;
        }

        public com.maxlananas.fawebim.core.util.LongObjectMap<List<ChangeSet>> changes() {
            return changes;
        }

        public List<EntityChange> entities() {
            return entities;
        }

        public boolean isEmpty() {
            return changeCount == 0 && biomeChangeCount == 0 && entities.isEmpty();
        }

        /**
         * The box the edit touched, as {@code {x1, y1, z1, x2, y2, z2}}, or null
         * when it recorded no block. Used by the history filters.
         */
        public int[] bounds() {
            int[] box = null;
            for (List<ChangeSet> sets : changes.values()) {
                for (ChangeSet set : sets) {
                    if (set.size() == 0) {
                        continue;
                    }
                    int minX = set.chunkX() << 4;
                    int minZ = set.chunkZ() << 4;
                    int[] yRange = set.yRange();
                    int minY = yRange[0];
                    int maxY = yRange[1];
                    int maxX = minX + 15;
                    int maxZ = minZ + 15;
                    if (box == null) {
                        box = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
                        continue;
                    }
                    box[0] = Math.min(box[0], minX);
                    box[1] = Math.min(box[1], minY);
                    box[2] = Math.min(box[2], minZ);
                    box[3] = Math.max(box[3], maxX);
                    box[4] = Math.max(box[4], maxY);
                    box[5] = Math.max(box[5], maxZ);
                }
            }
            return box;
        }
    }

    /** A removed (undo) or added (redo) entity. */
    public static final class EntityChange {

        public final String type;
        public final NbtCompound nbt;
        public final double x;
        public final double y;
        public final double z;
        public final boolean removed;

        public EntityChange(String type, NbtCompound nbt, double x, double y, double z, boolean removed) {
            this.type = type;
            this.nbt = nbt;
            this.x = x;
            this.y = y;
            this.z = z;
            this.removed = removed;
        }
    }

    /** The section list of a chunk, created on first use. */
    private static <T> List<T> sectionsOf(
            com.maxlananas.fawebim.core.util.LongObjectMap<List<T>> map, long chunkKey) {
        List<T> sets = map.get(chunkKey);
        if (sets == null) {
            sets = new ArrayList<>(2);
            map.put(chunkKey, sets);
        }
        return sets;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Called once with every record that changed something, when the edit that
     * produced it is over. Used to log the edit and to write its snapshot.
     */
    public void setRecordListener(java.util.function.Consumer<Record> listener) {
        this.recordListener = listener;
    }

    /**
     * Ends a record: seals it and hands it to the listener, once.
     *
     * <p>An edit publishes its record when it closes. A record whose edit never
     * closed is published when the next record starts, so a caller that forgets
     * to close still gets its edit logged, only later.</p>
     */
    public void publish(Record record) {
        if (record == null || record.published) {
            return;
        }
        record.published = true;
        record.seal();
        if (recordListener != null && !record.isEmpty()) {
            recordListener.accept(record);
        }
    }

    /**
     * Publishes the newest record if its edit never closed, for a session that
     * is going away: the player left or the server is stopping.
     */
    public void publishPending() {
        if (!records.isEmpty()) {
            publish(records.get(records.size() - 1));
        }
    }

    /** Starts a new record and drops any redo history, like FAWE does. */
    public Record newRecord(String description) {
        return newRecord(description, null);
    }

    /**
     * Starts a new record in a known world.
     *
     * @param world the world the edit happens in, or null when unknown
     */
    public Record newRecord(String description, String world) {
        // Only the newest record can still be open: every earlier one was
        // published when the record after it started.
        if (!records.isEmpty()) {
            publish(records.get(records.size() - 1));
        }
        while (records.size() > currentIndex + 1) {
            records.remove(records.size() - 1);
        }
        Record record = new Record(description);
        record.world = world;
        records.add(record);
        currentIndex = records.size() - 1;
        trim();
        return record;
    }

    private void trim() {
        while (records.size() > maxRecords) {
            records.remove(0);
            currentIndex--;
        }
        if (currentIndex < 0) {
            currentIndex = -1;
        }
    }

    public boolean canUndo() {
        return currentIndex >= 0 && currentIndex < records.size() && !records.get(currentIndex).isEmpty();
    }

    public boolean canRedo() {
        return currentIndex + 1 < records.size();
    }

    public Record getCurrent() {
        return currentIndex >= 0 && currentIndex < records.size() ? records.get(currentIndex) : null;
    }

    /** Steps back one record and returns it, or null when nothing to undo. */
    public Record undo() {
        if (!canUndo()) {
            // Drop empty records and try again, exactly like WorldEdit's behaviour.
            while (currentIndex >= 0 && records.get(currentIndex).isEmpty()) {
                records.remove(currentIndex);
                currentIndex--;
            }
            if (currentIndex < 0) {
                return null;
            }
        }
        Record record = records.get(currentIndex);
        currentIndex--;
        return record;
    }

    public Record redo() {
        if (!canRedo() && records.isEmpty()) {
            return null;
        }
        if (currentIndex + 1 >= records.size()) {
            return null;
        }
        currentIndex++;
        return records.get(currentIndex);
    }

    public void clear() {
        records.clear();
        currentIndex = -1;
    }

    public int size() {
        return records.size();
    }

    public int currentIndex() {
        return currentIndex;
    }

    /** Total recorded changes across all records, for {@code /history}. */
    public long totalChanges() {
        long total = 0;
        for (Record record : records) {
            total += record.changeCount();
        }
        return total;
    }
}
