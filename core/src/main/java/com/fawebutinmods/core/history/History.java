package com.fawebutinmods.core.history;

import com.fawebutinmods.core.util.NbtCompound;
import com.fawebutinmods.core.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player undo/redo history. Edits are grouped into {@link Record}s (one per
 * command), and each record holds the changes per chunk section plus the
 * removed entity data, mirroring FAWE's sharded history.
 */
public final class History {

    private final int maxRecords;
    private final List<Record> records = new ArrayList<>();
    private int currentIndex = -1;

    public History(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
    }

    /** A single undo step. */
    public static final class Record {

        public final String description;
        final Map<Long, List<ChangeSet>> changes = new LinkedHashMap<>();
        final List<EntityChange> entities = new ArrayList<>();
        int changeCount;

        public Record(String description) {
            this.description = description;
        }

        public void add(ChangeSet set) {
            changes.computeIfAbsent(key(set.chunkX(), set.chunkZ()), k -> new ArrayList<>()).add(set);
            changeCount += set.size();
        }

        /** Records one block change, creating the section's change set on demand. */
        public void addChange(int x, int y, int z, int previous, int current) {
            List<ChangeSet> sets = changes.computeIfAbsent(key(x >> 4, z >> 4), k -> new ArrayList<>());
            int sectionY = y >> 4;
            ChangeSet set = null;
            for (ChangeSet candidate : sets) {
                if (candidate.sectionY() == sectionY) {
                    set = candidate;
                    break;
                }
            }
            if (set == null) {
                set = new ChangeSet(x >> 4, z >> 4, sectionY);
                sets.add(set);
            }
            set.add(x, y, z, previous, current);
            changeCount++;
        }

        public void addEntity(EntityChange change) {
            entities.add(change);
        }

        public int changeCount() {
            return changeCount;
        }

        public Map<Long, List<ChangeSet>> changes() {
            return changes;
        }

        public List<EntityChange> entities() {
            return entities;
        }

        public boolean isEmpty() {
            return changeCount == 0 && entities.isEmpty();
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

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Starts a new record and drops any redo history, like FAWE does. */
    public Record newRecord(String description) {
        while (records.size() > currentIndex + 1) {
            records.remove(records.size() - 1);
        }
        Record record = new Record(description);
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
    public int totalChanges() {
        int total = 0;
        for (Record record : records) {
            total += record.changeCount();
        }
        return total;
    }

    /** Undo/redo application helper used by the session. */
    public static int apply(World world, Record record, boolean undo, WorldApplier applier) {
        int count = 0;
        for (List<ChangeSet> sets : record.changes.values()) {
            for (ChangeSet set : sets) {
                count += applier.apply(set, undo);
            }
        }
        return count;
    }

    /** Callback the platform uses to write history data back into the world. */
    public interface WorldApplier {

        int apply(ChangeSet set, boolean undo);
    }
}
