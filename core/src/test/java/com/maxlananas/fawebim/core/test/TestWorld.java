package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.ChunkSet;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.RegenOptions;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** A deterministic in-memory world used by the self-tests. */
public final class TestWorld implements World {

    private final Map<Long, Integer> blocks = new HashMap<>();
    /**
     * How many blocks of a section are not air, the way a real chunk section
     * counts them: {@link #isSectionEmpty} answers from this, so the double sees
     * the same one-check-per-section skips the game does.
     */
    private final Map<Long, Integer> sectionBlocks = new HashMap<>();
    private final Map<Long, Integer> biomes = new HashMap<>();
    /** The state a section holds, or -1 once it holds more than one. */
    private final Map<Long, Integer> sectionUniform = new HashMap<>();
    private final Map<Long, NbtCompound> blockEntities = new LinkedHashMap<>();
    private int blockEntityReads;
    private final List<EntityData> entities = new ArrayList<>();
    private final java.util.Set<Long> loadedChunks = new java.util.HashSet<>();
    private final java.util.Set<BlockVector2> relit = new java.util.LinkedHashSet<>();
    private final String name;
    private long seed = 1234L;
    private int setCount;
    /** How many times each chunk was handed to {@link #applyChunk}, by chunk key. */
    private final Map<Long, Integer> applyCounts = new HashMap<>();

    public TestWorld(String name) {
        this.name = name;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /** The section a position belongs to, as a key of {@link #sectionBlocks}. */
    private static long sectionKey(int x, int y, int z) {
        return ((long) (x >> 4) & 0x3FFFFFF) << 38 | ((long) (y >> 4) & 0xFFF) << 26
                | ((z >> 4) & 0x3FFFFFF);
    }

    /** Keeps the per-section count in step with a written block. */
    private void countSection(int x, int y, int z, boolean wasAir, boolean isAir) {
        if (wasAir == isAir) {
            return;
        }
        long key = sectionKey(x, y, z);
        sectionBlocks.merge(key, isAir ? -1 : 1, Integer::sum);
    }

    /**
     * Tracks the one state a section holds, the way a real chunk keeps a palette
     * of one entry for a section of solid ground: {@link #readSection} answers
     * such a section with a fill, and it may only do that when the section really
     * holds one state.
     */
    private void trackUniform(int x, int y, int z, int stateId) {
        long key = sectionKey(x, y, z);
        Integer uniform = sectionUniform.get(key);
        if (uniform == null) {
            // Nothing tracked for this section yet: the state it holds is this
            // cell's only when the section was empty before it was written.
            Integer count = sectionBlocks.get(key);
            sectionUniform.put(key, count == null || count == 0 ? stateId : -1);
            return;
        }
        if (uniform != stateId) {
            sectionUniform.put(key, -1);
        }
    }

    private boolean air(int stateId) {
        return stateId < 0 || BlockState.registry().isAirLike(stateId);
    }

    @Override
    public boolean isSectionEmpty(int chunkX, int sectionY, int chunkZ) {
        Integer count = sectionBlocks.get(sectionKey(chunkX << 4, sectionY << 4, chunkZ << 4));
        return count == null || count == 0;
    }

    @Override
    public boolean readSection(int chunkX, int sectionY, int chunkZ, int[] out,
                               int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int air = BlockState.registry().air();
        int baseX = chunkX << 4;
        int baseY = sectionY << 4;
        int baseZ = chunkZ << 4;
        // The double keeps one value per cell, so a section of solid ground is
        // filled from one lookup, the way the real world answers it.
        Integer count = sectionBlocks.get(sectionKey(baseX, baseY, baseZ));
        if (count == null || count == 0) {
            fill(out, air, fromX, fromY, fromZ, toX, toY, toZ, baseX, baseY, baseZ);
            return true;
        }
        Integer uniform = sectionUniform.get(sectionKey(baseX, baseY, baseZ));
        if (uniform != null && uniform >= 0) {
            fill(out, uniform, fromX, fromY, fromZ, toX, toY, toZ, baseX, baseY, baseZ);
            return true;
        }
        for (int y = fromY - baseY; y <= toY - baseY; y++) {
            for (int z = fromZ - baseZ; z <= toZ - baseZ; z++) {
                for (int x = fromX - baseX; x <= toX - baseX; x++) {
                    out[(y << 8) | (z << 4) | x] = getBlock(baseX + x, baseY + y, baseZ + z);
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

    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fawebim-test-worker");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public String name() {
        return name;
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int maxY() {
        return 319;
    }

    @Override
    public int getBlock(int x, int y, int z) {
        if (y < minY() || y > maxY()) {
            return BlockState.registry().air();
        }
        return blocks.getOrDefault(key(x, y, z), BlockState.registry().air());
    }

    @Override
    public boolean setBlock(int x, int y, int z, int stateId) {
        if (y < minY() || y > maxY()) {
            return false;
        }
        Integer previous = blocks.put(key(x, y, z), stateId);
        countSection(x, y, z, air(previous == null ? -1 : previous), air(stateId));
        trackUniform(x, y, z, stateId);
        setCount++;
        return true;
    }

    @Override
    public int getBiome(int x, int y, int z) {
        return biomes.getOrDefault(key(x, 0, z), 1);
    }

    @Override
    public boolean setBiome(int x, int y, int z, int biomeId) {
        biomes.put(key(x, 0, z), biomeId);
        return true;
    }

    @Override
    public int getHighestBlockY(int x, int z) {
        for (int y = maxY(); y >= minY(); y--) {
            if (!BlockState.registry().isAirLike(getBlock(x, y, z))) {
                return y;
            }
        }
        return minY() - 1;
    }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return loadedChunks.contains(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    @Override
    public void loadChunk(int chunkX, int chunkZ) {
        loadedChunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    @Override
    public int applyChunk(ChunkSet set) {
        applyCounts.merge(((long) set.chunkX() << 32) | (set.chunkZ() & 0xFFFFFFFFL), 1, Integer::sum);
        int applied = 0;
        int minY = set.minSection() << 4;
        for (int section = 0; section < set.sectionCount(); section++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldX = (set.chunkX() << 4) + x;
                        int worldY = minY + section * 16 + y;
                        int worldZ = (set.chunkZ() << 4) + z;
                        int state = set.getBlock(worldX, worldY, worldZ);
                        if (state == -1) {
                            continue;
                        }
                        int before = getBlock(worldX, worldY, worldZ);
                        Integer previous = blocks.put(key(worldX, worldY, worldZ), state);
                        countSection(worldX, worldY, worldZ, air(previous == null ? -1 : previous),
                                air(state));
                        trackUniform(worldX, worldY, worldZ, state);
                        keepBlockEntity(worldX, worldY, worldZ, before, state);
                        applied++;
                    }
                }
            }
        }
        if (set.hasBiomes()) {
            for (int section = 0; section < set.sectionCount(); section++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int worldX = (set.chunkX() << 4) + x;
                            int worldY = (minYSection(set) << 4) + section * 16 + y;
                            int worldZ = (set.chunkZ() << 4) + z;
                            int biome = set.getBiome(worldX, worldY, worldZ);
                            if (biome >= 0) {
                                biomes.put(key(worldX, 0, worldZ), biome);
                            }
                        }
                    }
                }
            }
        }
        for (ChunkSet.BlockEntity entity : set.blockEntities()) {
            applyBlockEntity(entity.x, entity.y, entity.z, entity.nbt);
        }
        setCount += applied;
        return applied;
    }

    /**
     * What the game does with the block entity of a block it sets, and what
     * {@link com.maxlananas.fawebim.core.world.World#applyChunk} promises: a
     * block without one loses it, a block of another type gets a new one, a
     * block that keeps its type keeps its data.
     */
    private void keepBlockEntity(int x, int y, int z, int before, int after) {
        String type = blockEntityType(after);
        long key = key(x, y, z);
        if (type == null) {
            blockEntities.remove(key);
        } else if (!type.equals(blockEntityType(before)) || !blockEntities.containsKey(key)) {
            blockEntities.put(key, new NbtCompound().putString("id", type));
        }
    }

    /** The blocks of the test world that hold a block entity, as a chest and a furnace do in the game. */
    static String blockEntityType(int state) {
        if (state < 0) {
            return null;
        }
        String name = BlockState.registry().name(state);
        return name.equals("minecraft:chest") || name.equals("minecraft:furnace") ? name : null;
    }

    /** Loads data into the block entity of a position, and does nothing where the block has none. */
    @Override
    public void applyBlockEntity(int x, int y, int z, NbtCompound nbt) {
        String type = blockEntityType(getBlock(x, y, z));
        if (type != null) {
            NbtCompound data = nbt.clone();
            data.putString("id", type);
            blockEntities.put(key(x, y, z), data);
        }
    }

    private static int minYSection(ChunkSet set) {
        return set.minSection();
    }

    @Override
    public void relight(Collection<BlockVector2> chunks) {
        relit.addAll(chunks);
    }

    @Override
    public boolean regenerateChunk(int chunkX, int chunkZ, RegenOptions options) {
        Random random = new Random(seed + chunkX * 3121L + chunkZ * 4021L);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int height = 64 + random.nextInt(8);
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                for (int y = minY(); y <= height; y++) {
                    int state = y == height
                            ? BlockState.registry().defaultState("minecraft:grass_block")
                            : y > height - 4
                            ? BlockState.registry().defaultState("minecraft:dirt")
                            : BlockState.registry().defaultState("minecraft:stone");
                    setBlock(worldX, y, worldZ, state);
                }
                for (int y = height + 1; y <= height + 6; y++) {
                    setBlock(worldX, y, worldZ, BlockState.registry().air());
                }
            }
        }
        if (options != null && options.shouldRegenBiomes()) {
            setBiome(chunkX << 4, 0, chunkZ << 4, 1);
        }
        return true;
    }

    @Override
    public boolean generateTree(BlockVector3 pos, String treeType, Random random) {
        int log = BlockState.registry().defaultState("minecraft:oak_log");
        int leaves = BlockState.registry().defaultState("minecraft:oak_leaves");
        if (log < 0 || leaves < 0) {
            return false;
        }
        int height = 5 + random.nextInt(3);
        for (int y = 0; y < height; y++) {
            setBlock(pos.x(), pos.y() + y, pos.z(), log);
        }
        for (int dy = height - 3; dy <= height; dy++) {
            int radius = dy >= height - 1 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius) {
                        continue;
                    }
                    setBlock(pos.x() + dx, pos.y() + dy, pos.z() + dz, leaves);
                }
            }
        }
        return true;
    }

    @Override
    public boolean generateFeature(BlockVector3 pos, String featureType, Random random) {
        // Features are placed by the platform; the test world uses its tree generator.
        return generateTree(pos, featureType, random);
    }

    @Override
    public List<EntityData> getEntities(com.maxlananas.fawebim.core.world.Extent.Region3i box) {
        List<EntityData> found = new ArrayList<>();
        for (EntityData entity : entities) {
            double x = entity.position().x();
            double y = entity.position().y();
            double z = entity.position().z();
            if (x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY()
                    && z >= box.minZ() && z <= box.maxZ()) {
                found.add(entity);
            }
        }
        return found;
    }

    /** Puts an entity into the world as it is, giving it an identity when it has none. */
    @Override
    public void addEntity(EntityData data) {
        if (data.uuid() == null) {
            data.setUuid(java.util.UUID.randomUUID().toString());
        }
        entities.add(data);
    }

    @Override
    public void removeEntity(EntityData data) {
        entities.remove(data);
    }

    /** As the game does: a new entity from the data, refused when the identity asked for is taken. */
    @Override
    public EntityData spawnEntity(EntityData data, String uuid) {
        if (uuid != null && entities.stream().anyMatch(entity -> uuid.equals(entity.uuid()))) {
            return null;
        }
        EntityData created = new EntityData(data.type(), data.nbt() == null ? new NbtCompound() : data.nbt().clone(),
                data.position());
        created.setUuid(uuid != null ? uuid : java.util.UUID.randomUUID().toString());
        entities.add(created);
        return created;
    }

    @Override
    public boolean removeEntityById(String uuid) {
        return entities.removeIf(entity -> uuid.equals(entity.uuid()));
    }

    @Override
    public NbtCompound getBlockEntity(int x, int y, int z) {
        blockEntityReads++;
        NbtCompound nbt = blockEntities.get(key(x, y, z));
        return nbt == null ? null : nbt.clone();
    }

    /** How many times the engine asked for the data of a position, to tell a per-block walk from a per-chunk one. */
    public int blockEntityReads() {
        return blockEntityReads;
    }

    @Override
    public void forEachBlockEntity(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                   BlockEntityVisitor visitor) {
        for (long key : new ArrayList<>(blockEntities.keySet())) {
            int x = (int) (key >> 38);
            int y = (int) (key << 26 >> 52);
            int z = (int) (key << 38 >> 38);
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                visitor.visit(x, y, z);
            }
        }
    }

    public java.util.Set<BlockVector2> relitChunks() {
        return relit;
    }

    /**
     * The worker pool of a real world. It is a single daemon thread so that a test
     * can wait for the writes handed to it with {@link #awaitExecutor()}.
     */
    @Override
    public java.util.concurrent.ExecutorService executor() {
        return executor;
    }

    /** Waits for the work already queued on {@link #executor()}. */
    public void awaitExecutor() throws Exception {
        executor.submit(() -> {
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    public int setCount() {
        return setCount;
    }

    /** How many flushes wrote into a chunk. */
    public int applyCount(int chunkX, int chunkZ) {
        return applyCounts.getOrDefault(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL), 0);
    }

    public List<EntityData> entityList() {
        return entities;
    }

    /** Fills a flat terrain so operations have something to work with. */
    public void fillFlat(int height) {
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        int grass = BlockState.registry().defaultState("minecraft:grass_block");
        for (int x = -16; x < 48; x++) {
            for (int z = -16; z < 48; z++) {
                for (int y = minY(); y < height - 3; y++) {
                    setBlock(x, y, z, stone);
                }
                setBlock(x, height - 3, z, stone);
                setBlock(x, height - 2, z, dirt);
                setBlock(x, height - 1, z, grass);
            }
        }
    }
}
