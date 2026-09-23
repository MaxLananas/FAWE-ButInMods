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
    private final Map<Long, Integer> biomes = new HashMap<>();
    private final Map<Long, NbtCompound> blockEntities = new LinkedHashMap<>();
    private final List<EntityData> entities = new ArrayList<>();
    private final java.util.Set<Long> loadedChunks = new java.util.HashSet<>();
    private final java.util.Set<BlockVector2> relit = new java.util.LinkedHashSet<>();
    private final String name;
    private long seed = 1234L;
    private int setCount;

    public TestWorld(String name) {
        this.name = name;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

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
        blocks.put(key(x, y, z), stateId);
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
    public int applyChunk(ChunkSet set, Collection<BlockVector3> changed) {
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
                        blocks.put(key(worldX, worldY, worldZ), state);
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
        setCount += applied;
        return applied;
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

    @Override
    public void addEntity(EntityData data) {
        entities.add(data);
    }

    @Override
    public void removeEntity(EntityData data) {
        entities.remove(data);
    }

    @Override
    public void setBlockEntity(int x, int y, int z, NbtCompound nbt) {
        blockEntities.put(key(x, y, z), nbt);
    }

    @Override
    public NbtCompound getBlockEntity(int x, int y, int z) {
        return blockEntities.get(key(x, y, z));
    }

    @Override
    public void removeBlockEntity(int x, int y, int z) {
        blockEntities.remove(key(x, y, z));
    }

    public java.util.Set<BlockVector2> relitChunks() {
        return relit;
    }

    public int setCount() {
        return setCount;
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
