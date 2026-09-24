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

    private final ServerLevel level;

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

    /**
     * The section a position lives in, without the position object the game's
     * own accessor allocates and without its second chunk lookup.
     *
     * <p>Every read and every write of an edit goes through here. A region is
     * walked x first, so sixteen calls in a row ask for the same chunk.</p>
     *
     * @return the section, or {@code null} when the position is outside the
     *         sections of the level
     */
    private LevelChunkSection sectionAt(int x, int y, int z) {
        int index = (y - minY()) >> 4;
        if (index < 0 || index >= level.getSectionsCount()) {
            return null;
        }
        return level.getChunk(x >> 4, z >> 4).getSection(index);
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
        LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
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
        LevelChunk chunk = level.getChunk(set.chunkX(), set.chunkZ());
        PackedBlockArray[] sections = set.sections();
        int applied = 0;
        int baseX = set.chunkX() << 4;
        int baseZ = set.chunkZ() << 4;
        int baseY = set.minSection() << 4;

        // 1. Remember the positions that changed, so they can be re-lit once the
        //    write is done. They stay in parallel int arrays rather than in a map
        //    keyed by a block vector: a large edit changes millions of them and
        //    this runs on every flush. A flush that will re-send the chunk needs
        //    the positions and nothing else - the clients get the chunk, not a
        //    packet per block - so the states are only collected below that.
        int count = set.size();
        boolean resend = count >= Math.max(1, Config.get().chunkResendThreshold);
        int[] changedXs = new int[count];
        int[] changedYs = new int[count];
        int[] changedZs = new int[count];
        BlockState[] before = resend ? null : new BlockState[count];
        BlockState[] after = resend ? null : new BlockState[count];
        int[] slot = {0};
        var chunkSource = level.getChunkSource();
        boolean ticking = chunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);

        // 2. Bulk section write: one palette update per section instead of one
        //    world.setBlock call (with its 6 neighbour updates) per block. A cell
        //    that is about to be written has its old state read out of the section
        //    in the same walk, which is what the client sync below hands back.
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
            // Only the cells this buffer holds are written, in one palette update
            // per section instead of one world.setBlock call per block.
            int written = buffered.forEachWritten(local -> {
                int localX = local & 15;
                int localY = (local >> 8) & 15;
                int localZ = (local >> 4) & 15;
                int at = slot[0]++;
                changedXs[at] = baseX + localX;
                changedYs[at] = sectionY + localY;
                changedZs[at] = baseZ + localZ;
                BlockState now = Block.stateById(buffered.get(local));
                if (before != null) {
                    before[at] = section.getBlockState(localX, localY, localZ);
                    after[at] = now;
                }
                section.setBlockState(localX, localY, localZ, now, false);
            });
            applied += written;
            if (written > 0) {
                chunk.markUnsaved();
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
        //    bookkeeping, the way WorldEdit's native access does it.
        int changedCount = slot[0];
        for (int index = 0; index < changedCount; index++) {
            BlockPos pos = new BlockPos(changedXs[index], changedYs[index], changedZs[index]);
            chunkSource.getLightEngine().checkBlock(pos);
            if (resend) {
                // The whole chunk is about to be sent, so the section the cell
                // belongs to does not also have to be marked for a broadcast.
                if (ticking && chunkSource instanceof net.minecraft.server.level.ServerChunkCache cache) {
                    cache.blockChanged(pos);
                }
            } else {
                BlockState was = before[index];
                BlockState now = after[index];
                if (now != was) {
                    // Vanilla's own notification: it hands the section to the
                    // game's next broadcast and tells the mobs the ground moved.
                    level.sendBlockUpdated(pos, was, now, UPDATE_NEIGHBORS | UPDATE_CLIENTS);
                }
            }
        }
        if (resend) {
            sendChunk(chunk, chunkSource.getLightEngine());
        }
        return applied;
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
        String key = treeType == null || treeType.isEmpty()
                ? "tree" : treeType.toLowerCase(java.util.Locale.ROOT);
        String id = switch (key) {
            case "tree", "oak" -> "minecraft:oak_checked";
            case "bigtree", "big_oak" -> "minecraft:fancy_oak_checked";
            case "dark_oak" -> "minecraft:dark_oak_checked";
            case "redwood", "spruce" -> "minecraft:spruce_checked";
            case "mega_spruce", "tallredwood" -> "minecraft:mega_spruce_checked";
            case "birch", "tallbirch" -> "minecraft:birch_checked";
            case "jungle" -> "minecraft:jungle_tree";
            case "acacia" -> "minecraft:acacia_checked";
            case "mangrove" -> "minecraft:mangrove_checked";
            case "cherry" -> "minecraft:cherry_checked";
            case "azalea" -> "minecraft:azalea_tree";
            default -> key.contains(":") ? key : "minecraft:" + key;
        };
        return placeFeature(pos, id, random);
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

    @Override
    public List<EntityData> getEntities(com.maxlananas.fawebim.core.world.Extent.Region3i box) {
        AABB aabb = new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        List<EntityData> entities = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, aabb, candidate -> true)) {
            EntityData data = new EntityData(
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    new NbtCompound().putString("uuid", entity.getUUID().toString()),
                    new Vector3(entity.getX(), entity.getY(), entity.getZ()));
            data.setHandle(entity);
            entities.add(data);
        }
        return entities;
    }

    @Override
    public void addEntity(EntityData data) {
        if (data.handle() instanceof Entity entity && !entity.isAlive()) {
            level.addFreshEntity(entity);
        }
    }

    @Override
    public void removeEntity(EntityData data) {
        if (data.handle() instanceof Entity entity) {
            entity.discard();
        }
    }

    /** Precision ray trace, used by {@code //jumpto}, {@code //thru} and the tools. */
    @Override
    public BlockVector3 getTargetBlock(com.maxlananas.fawebim.core.actor.Actor actor, int maxDistance) {
        BlockVector3 origin = actor.position();
        if (origin == null) {
            return BlockVector3.ZERO;
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
        // A chest that was just written does not have its block entity yet: the
        // game creates it when the block itself changes, which the bulk write did
        // without going through the level. Asking for it here is what creates it.
        var blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        if (blockEntity == null) {
            FaweMod.LOGGER.debug("No block entity to hold the data at {},{},{}", x, y, z);
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
