package com.maxlananas.fawebim.core.history;

/**
 * The recorded biome changes of one chunk section.
 *
 * <p>Minecraft stores biomes per 4x4x4 cell, so a section holds at most 64
 * entries. The cells are packed the same way the game packs them, which keeps an
 * undo of a big {@code //setbiome} run a few hundred bytes per section.</p>
 */
public final class BiomeChangeSet {

    private int[] cells = new int[16];
    private int[] before = new int[16];
    private int[] after = new int[16];
    private int size;
    private final int chunkX;
    private final int chunkZ;
    private final int sectionY;

    public BiomeChangeSet(int chunkX, int chunkZ, int sectionY) {
        // sectionY is the section index, as in ChangeSet; y() shifts it back up.
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int sectionY() {
        return sectionY;
    }

    public int size() {
        return size;
    }

    public int[] cells() {
        return cells;
    }

    public int[] before() {
        return before;
    }

    public int[] after() {
        return after;
    }

    public void add(int x, int y, int z, int previous, int current) {
        if (size == cells.length) {
            int length = cells.length * 2;
            cells = java.util.Arrays.copyOf(cells, length);
            before = java.util.Arrays.copyOf(before, length);
            after = java.util.Arrays.copyOf(after, length);
        }
        cells[size] = (((y >> 2) & 3) << 4) | (((z >> 2) & 3) << 2) | ((x >> 2) & 3);
        before[size] = previous;
        after[size] = current;
        size++;
    }

    /** Appends one cell while a snapshot is being read back. */
    public void restore(int cell, int biomeId) {
        if (size == cells.length) {
            int length = cells.length * 2;
            cells = java.util.Arrays.copyOf(cells, length);
            before = java.util.Arrays.copyOf(before, length);
            after = java.util.Arrays.copyOf(after, length);
        }
        cells[size] = cell;
        before[size] = biomeId;
        after[size] = biomeId;
        size++;
    }

    /** World X of the cell stored at that slot. */
    public int x(int slot) {
        return (chunkX << 4) + ((cells[slot] & 3) << 2);
    }

    /** World Y of the cell stored at that slot. */
    public int y(int slot) {
        return (sectionY << 4) + (((cells[slot] >> 4) & 3) << 2);
    }

    /** World Z of the cell stored at that slot. */
    public int z(int slot) {
        return (chunkZ << 4) + (((cells[slot] >> 2) & 3) << 2);
    }
}
