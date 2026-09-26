package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.Extent;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * A dense block array clipboard: this is what {@code //copy} produces and what
 * {@code //paste}, {@code /brush clipboard} and the schematic writers consume.
 *
 * <p>Blocks are stored in a palette-packed structure per section, which is the
 * same layout FAWE uses so that large copies stay small in memory.</p>
 */
public final class BlockArrayClipboard implements Extent {

    private final BlockBox box = new BlockBox();
    private BlockVector3 origin;
    private final List<EntityData> entities = new ArrayList<>();
    private String name = "";
    private final com.maxlananas.fawebim.core.util.LongObjectMap<int[]> sections =
            new com.maxlananas.fawebim.core.util.LongObjectMap<>();
    private final java.util.Map<BlockVector3, com.maxlananas.fawebim.core.util.NbtCompound> blockEntities =
            new java.util.LinkedHashMap<>();
    private int minY = Integer.MAX_VALUE;
    /** The section of the last block read or written: a walk stays in one for
     *  thousands of positions, and looking it up again costs a hash each time. */
    private long lastSectionKey = Long.MIN_VALUE;
    private int[] lastSection;
    /** The runs a partly read section holds, kept beside the full ones. */
    private final java.util.Map<Long, Partial> partial = new java.util.HashMap<>();
    private Partial lastPartial;
    private int maxY = Integer.MIN_VALUE;
    private World lazyWorld;

    public BlockArrayClipboard(BlockVector3 origin) {
        this.origin = origin;
        box.set(origin.x(), origin.y(), origin.z(), origin.x(), origin.y(), origin.z());
    }

    /**
     * A clipboard that keeps the region instead of its blocks, like FAWE's
     * {@code //lazycopy}: nothing is read until the clipboard is pasted, so
     * copying a huge selection costs no memory.
     */
    public static BlockArrayClipboard lazy(World world, com.maxlananas.fawebim.core.region.Region region, String name) {
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region.getMinimumPoint());
        clipboard.box.set(region.getMinimumPoint().x(), region.getMinimumPoint().y(), region.getMinimumPoint().z(),
                region.getMaximumPoint().x(), region.getMaximumPoint().y(), region.getMaximumPoint().z());
        clipboard.minY = region.getMinimumPoint().y();
        clipboard.maxY = region.getMaximumPoint().y();
        clipboard.lazyWorld = world;
        clipboard.name = name;
        return clipboard;
    }

    /** True when the blocks are still in the world rather than in this clipboard. */
    public boolean isLazy() {
        return lazyWorld != null;
    }

    public BlockVector3 getOrigin() {
        return origin;
    }

    public void setOrigin(BlockVector3 origin) {
        this.origin = origin;
    }

    public BlockBox getBox() {
        return box;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<EntityData> getEntitiesCopy() {
        List<EntityData> copy = new ArrayList<>(entities.size());
        for (EntityData entity : entities) {
            copy.add(entity.clone());
        }
        return copy;
    }

    public List<EntityData> entities() {
        return entities;
    }

    public void addEntity(EntityData data) {
        entities.add(data);
    }

    /** Block entity (chest contents, signs, ...) copied along with the blocks. */
    public void addBlockEntity(BlockVector3 position, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
        blockEntities.put(position, nbt);
    }

    public com.maxlananas.fawebim.core.util.NbtCompound getBlockEntity(BlockVector3 position) {
        return blockEntities.get(position);
    }

    public java.util.Map<BlockVector3, com.maxlananas.fawebim.core.util.NbtCompound> blockEntities() {
        return blockEntities;
    }

    // ------------------------------------------------------------------ biomes

    private final java.util.Map<Long, Integer> biomes = new java.util.HashMap<>();

    /** Stores the biome of a position, filled by {@code //copy -b}. */
    @Override
    public boolean setBiome(int x, int y, int z, int biomeId) {
        biomes.put(positionKey(x, y, z), biomeId);
        return true;
    }

    /** The stored biome of a position, or {@code -1} when there is none. */
    public int getBiome(int x, int y, int z) {
        return biomes.getOrDefault(positionKey(x, y, z), -1);
    }

    public boolean hasBiomes() {
        return !biomes.isEmpty();
    }

    /** Every stored biome, keyed by {@link #positionKey(int, int, int)}. */
    public java.util.Set<java.util.Map.Entry<Long, Integer>> biomeEntries() {
        return biomes.entrySet();
    }

    /** Packs the coordinates of a biome cell the way Minecraft packs a block position. */
    public static long positionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    /** Unpacks the X coordinate of a {@link #positionKey(int, int, int)}. */
    public static int keyX(long key) {
        return (int) (key << 0 >> 38);
    }

    /** Unpacks the Y coordinate of a {@link #positionKey(int, int, int)}. */
    public static int keyY(long key) {
        return (int) (key << 26 >> 52);
    }

    /** Unpacks the Z coordinate of a {@link #positionKey(int, int, int)}. */
    public static int keyZ(long key) {
        return (int) (key << 38 >> 38);
    }

    /**
     * Keys the section a block belongs to, by packing its chunk coordinates the
     * way {@link #positionKey(int, int, int)} packs a block position.
     *
     * <p>The fields have to be masked on the way in. Packing them with a plain
     * shift let the sign of a negative chunk - the west side of a build, or a
     * section below y 0 - spill into the field above it, so a section key read
     * back gave the coordinates of a different section: a paste of a clipboard
     * copied west of the origin wrote its blocks somewhere else as well.</p>
     */
    public static long sectionKey(int x, int y, int z) {
        return positionKey(x >> 4, y >> 4, z >> 4);
    }

    /** The block x of a key made by {@link #sectionKey(int, int, int)}. */
    public static int sectionKeyX(long key) {
        return keyX(key) << 4;
    }

    /** The block y of a key made by {@link #sectionKey(int, int, int)}. */
    public static int sectionKeyY(long key) {
        return keyY(key) << 4;
    }

    /** The block z of a key made by {@link #sectionKey(int, int, int)}. */
    public static int sectionKeyZ(long key) {
        return keyZ(key) << 4;
    }

    @Override
    public int getBlock(int x, int y, int z) {
        if (!box.contains(x, y, z)) {
            return 0;
        }
        if (lazyWorld != null) {
            return lazyWorld.getBlock(x, y, z);
        }
        int[] section = sectionFor(sectionKey(x, y, z));
        if (section != null) {
            return section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }
        Partial part = lastPartial;
        if (part == null) {
            return 0;
        }
        int localX = (x & 15) - part.startX();
        int localZ = (z & 15) - part.startZ();
        int localY = (y & 15) - part.startY();
        if (localX < 0 || localZ < 0 || localY < 0 || localX >= part.stride()
                || localZ >= part.stride() || localY > part.endY() - part.startY()) {
            return 0;
        }
        int[] row = part.rows()[localY * part.stride() + localZ];
        return row == null ? 0 : row[localX];
    }

    public int getBlock(BlockVector3 position) {
        return getBlock(position.x(), position.y(), position.z());
    }

    private int[] sectionFor(long key) {
        if (key == lastSectionKey) {
            return lastSection;
        }
        int[] section = sections.get(key);
        lastSectionKey = key;
        lastSection = section;
        lastPartial = partial.get(key);
        return section;
    }

    @Override
    public boolean setBlock(int x, int y, int z, int stateId) {
        long key = sectionKey(x, y, z);
        int[] section = sectionFor(key);
        if (section == null) {
            Partial part = lastPartial;
            lastPartial = null;
            partial.remove(key);
            section = new int[4096];
            // The runs the section was read as are laid back into the array, so
            // a cell written on top of them sees the blocks around it.
            if (part != null) {
                for (int row = 0; row < part.rows().length; row++) {
                    int[] run = part.rows()[row];
                    if (run == null) {
                        continue;
                    }
                    int localY = part.startY() + row / part.stride();
                    int localZ = part.startZ() + row % part.stride();
                    System.arraycopy(run, 0, section,
                            (localY << 8) | (localZ << 4) | part.startX(), run.length);
                }
            }
            sections.put(key, section);
            lastSectionKey = key;
            lastSection = section;
        }
        section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = stateId;
        box.set(
                Math.min(box.minX(), x), Math.min(box.minY(), y), Math.min(box.minZ(), z),
                Math.max(box.maxX(), x), Math.max(box.maxY(), y), Math.max(box.maxZ(), z));
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        return true;
    }

    /**
     * Takes a whole section of blocks the caller has already read.
     *
     * <p>{@code data} is indexed the way {@link
     * com.maxlananas.fawebim.core.world.World#readSection} fills it, which is
     * also the way this clipboard stores a section, so the array is handed over
     * as it is: copying a full section of a large selection costs one hash and
     * one box update instead of 4096 of each.</p>
     */
    public void adoptSection(int sectionX, int sectionY, int sectionZ, int[] data) {
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;
        long key = sectionKey(baseX, baseY, baseZ);
        sections.put(key, data);
        lastSectionKey = key;
        lastSection = data;
        // A section is 16 blocks along each axis, so the extent it adds to the
        // box is known without walking it.
        box.set(Math.min(box.minX(), baseX), Math.min(box.minY(), baseY),
                Math.min(box.minZ(), baseZ),
                Math.max(box.maxX(), baseX + 15), Math.max(box.maxY(), baseY + 15),
                Math.max(box.maxZ(), baseZ + 15));
        minY = Math.min(minY, baseY);
        maxY = Math.max(maxY, baseY + 15);
    }

    /**
     * Takes the part of a section a caller has already read, the way {@link
     * #adoptSection} takes the whole of it.
     *
     * <p>{@code data} holds the box at the section's own indices - the layout
     * {@link com.maxlananas.fawebim.core.world.World#readSection} fills - so the
     * rows of the box are recorded as the runs they are, and the cells outside
     * the box are left as air. A section the selection covers only partly is
     * then one small object per row instead of one per block.</p>
     */
    public void adoptBox(int chunkX, int sectionY, int chunkZ, int[] data,
                         int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int baseY = sectionY << 4;
        int startX = fromX & 15;
        int endX = toX & 15;
        int startZ = fromZ & 15;
        int endZ = toZ & 15;
        int startY = fromY - baseY;
        int endY = toY - baseY;
        int stride = endX - startX + 1;
        int rows = stride * (endY - startY + 1);
        int[][] recorded = new int[rows][];
        int row = 0;
        for (int y = startY; y <= endY; y++) {
            int rowBase = y << 8;
            for (int z = startZ; z <= endZ; z++) {
                int at = rowBase | (z << 4) | startX;
                int run = 0;
                while (run < stride && data[at + run] == 0) {
                    run++;
                }
                if (run == stride) {
                    recorded[row++] = null;
                    continue;
                }
                int first = run;
                run = stride;
                while (run > first && data[at + run - 1] == 0) {
                    run--;
                }
                recorded[row++] = java.util.Arrays.copyOfRange(data, at + first, at + run);
            }
        }
        long key = sectionKey(chunkX << 4, baseY, chunkZ << 4);
        partial.put(key, new Partial(recorded, chunkX << 4, baseY, chunkZ << 4,
                startX, startZ, startY, endY, stride));
        sections.remove(key);
        lastSectionKey = key;
        lastSection = null;
        lastPartial = partial.get(key);
        box.set(Math.min(box.minX(), (chunkX << 4) + startX),
                Math.min(box.minY(), baseY + startY),
                Math.min(box.minZ(), (chunkZ << 4) + startZ),
                Math.max(box.maxX(), (chunkX << 4) + endX),
                Math.max(box.maxY(), baseY + endY),
                Math.max(box.maxZ(), (chunkZ << 4) + endZ));
        minY = Math.min(minY, baseY + startY);
        maxY = Math.max(maxY, baseY + endY);
    }

    /** The runs a partly read section holds, and where they start. */
    private record Partial(int[][] rows, int baseX, int baseY, int baseZ, int startX, int startZ,
                           int startY, int endY, int stride) {
    }

    @Override
    public int minY() {
        return minY == Integer.MAX_VALUE ? 0 : minY;
    }

    @Override
    public int maxY() {
        return maxY == Integer.MIN_VALUE ? 255 : maxY;
    }

    public int getWidth() {
        return box.width();
    }

    public int getHeight() {
        return box.height();
    }

    public int getLength() {
        return box.length();
    }

    public long volume() {
        return box.volume();
    }

    /** Iterates the clipboard in the region's natural order. */
    public Iterable<BlockVector3> positions() {
        List<BlockVector3> positions = new ArrayList<>();
        forEachPosition((x, y, z, state) -> {
            positions.add(new BlockVector3(x, y, z));
            return false;
        });
        return positions;
    }

    /** Receives one clipboard cell: its position and the state stored there. */
    @FunctionalInterface
    public interface CellVisitor {

        boolean visit(int x, int y, int z, int state);
    }

    /**
     * Walks every cell of the clipboard without building a position object for
     * it: {@code //paste} walks a clipboard once per paste, and the list of
     * vectors used to be the largest allocation of the command.
     *
     * @return how many visits reported true
     */
    public int forEachPosition(CellVisitor visitor) {
        int visited = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (visitor.visit(x, y, z, getBlock(x, y, z))) {
                        visited++;
                    }
                }
            }
        }
        return visited;
    }

    /**
     * How many cells the clipboard holds a block in.
     *
     * <p>The number of blocks a copy really stored, which is what the command
     * answers with: a selection of air stores nothing, however large it is.</p>
     */
    public int filled(com.maxlananas.fawebim.core.world.BlockStateRegistry registry) {
        int filled = 0;
        for (int[] section : sections.values()) {
            for (int state : section) {
                if (state != 0 && !registry.isAirLike(state)) {
                    filled++;
                }
            }
        }
        for (Partial part : partial.values()) {
            for (int[] run : part.rows()) {
                if (run == null) {
                    continue;
                }
                for (int state : run) {
                    if (state != 0 && !registry.isAirLike(state)) {
                        filled++;
                    }
                }
            }
        }
        return filled;
    }

    /**
     * Visits every cell the clipboard holds a block in, and only those: a
     * {@code //paste} of a large clipboard used to walk the whole box - air
     * included - and look each cell up through the bbox check and the section
     * cache, which is work per cell of nothing.
     *
     * <p>The cells come in the order the clipboard stores them, section by
     * section, which is also the order the paste writes them in: a region is
     * walked x first, so the chunk the last cell went to is usually the chunk the
     * next one belongs to.</p>
     */
    public int forEachStored(int air, CellVisitor visitor) {
        int visited = 0;
        if (lazyWorld != null) {
            return forEachPosition(visitor);
        }
        long[] keys = sections.keys();
        for (long key : keys) {
            int[] section = sections.get(key);
            int baseX = sectionKeyX(key);
            int baseY = sectionKeyY(key);
            int baseZ = sectionKeyZ(key);
            for (int cell = 0; cell < 4096; cell++) {
                int state = section[cell];
                if (state == 0 || state == air) {
                    continue;
                }
                if (visitor.visit(baseX + (cell & 15), baseY + ((cell >> 8) & 15),
                        baseZ + ((cell >> 4) & 15), state)) {
                    visited++;
                }
            }
        }
        for (Partial part : partial.values()) {
            // The runs are stored per row, and the row's own coordinates say
            // which world row it is: no need to look them up per cell.
            for (int row = 0; row < part.rows().length; row++) {
                int[] run = part.rows()[row];
                if (run == null) {
                    continue;
                }
                int y = part.baseY() + part.startY() + row / part.stride();
                int z = part.baseZ() + part.startZ() + row % part.stride();
                for (int index = 0; index < run.length; index++) {
                    int state = run[index];
                    if (state == 0 || state == air) {
                        continue;
                    }
                    if (visitor.visit(part.baseX() + part.startX() + index, y, z, state)) {
                        visited++;
                    }
                }
            }
        }
        return visited;
    }

    /** True when the clipboard contains no non-air blocks (nothing to paste). */
    public boolean isEmpty(BlockStateRegistry registry) {
        for (int[] section : sections.values()) {
            for (int state : section) {
                if (!registry.isAirLike(state)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Shifts the clipboard so that its origin becomes the minimum corner. */
    public void normalize() {
        origin = new BlockVector3(box.minX(), box.minY(), box.minZ());
    }
}
