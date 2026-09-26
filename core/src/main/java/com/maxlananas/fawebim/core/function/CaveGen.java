package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

import java.util.Random;

/**
 * FAWE's {@code CavesGen} and the {@code GenBase} it extends: the cave network
 * vanilla carves, walked chunk by chunk.
 *
 * <p>A cave is a chain of ellipsoid nodes: each one moves a little along a
 * direction, widens towards the middle of the chain and thins out at both ends,
 * and the blocks it covers that are neither air, water, lava nor bedrock become
 * cave air - or lava in the first ten layers. A chunk may start several chains,
 * and a node can split into two, which is what makes the network branch.</p>
 */
public final class CaveGen {

    private static final BlockStateRegistry REGISTRY = BlockState.registry();

    private final int checkAreaSize;
    private final int caveFrequency;
    private final int caveRarity;
    private final int caveMinAltitude;
    private final int caveMaxAltitude;
    private final int caveSystemFrequency;
    private final int individualCaveRarity;
    private final int caveSystemPocketChance;
    private final int caveSystemPocketMinSize;
    private final int caveSystemPocketMaxSize;
    private final Random random;
    private Region region;

    public CaveGen(int checkAreaSize, int caveFrequency, int caveRarity, int caveMinAltitude,
                   int caveMaxAltitude, int caveSystemFrequency, int individualCaveRarity,
                   int caveSystemPocketChance, int caveSystemPocketMinSize, int caveSystemPocketMaxSize,
                   Random random) {
        this.checkAreaSize = checkAreaSize;
        this.caveFrequency = Math.max(1, caveFrequency);
        this.caveRarity = caveRarity;
        this.caveMinAltitude = caveMinAltitude;
        this.caveMaxAltitude = caveMaxAltitude;
        this.caveSystemFrequency = caveSystemFrequency;
        this.individualCaveRarity = individualCaveRarity;
        this.caveSystemPocketChance = caveSystemPocketChance;
        this.caveSystemPocketMinSize = caveSystemPocketMinSize;
        this.caveSystemPocketMaxSize = caveSystemPocketMaxSize;
        this.random = random;
    }

    /** Carves the cave network through every chunk the region touches. */
    public int generate(World world, EditSession session, Region region) {
        this.region = region;
        int changed = 0;
        int minChunkX = region.getMinimumPoint().x() >> 4;
        int maxChunkX = region.getMaximumPoint().x() >> 4;
        int minChunkZ = region.getMinimumPoint().z() >> 4;
        int maxChunkZ = region.getMaximumPoint().z() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                session.checkTimeout();
                // The nodes are seeded from the chunks around the one being
                // written, so a cave that starts outside still reaches in.
                for (int x = chunkX - checkAreaSize; x <= chunkX + checkAreaSize; x++) {
                    for (int z = chunkZ - checkAreaSize; z <= chunkZ + checkAreaSize; z++) {
                        changed += generateChunk(world, session, x, z, chunkX, chunkZ);
                    }
                }
            }
        }
        return changed;
    }

    /** The caves one chunk seeds, upstream's {@code CavesGen.generateChunk}. */
    private int generateChunk(World world, EditSession session, int chunkX, int chunkZ,
                              int originChunkX, int originChunkZ) {
        int caves = random.nextInt(random.nextInt(random.nextInt(caveFrequency) + 1) + 1);
        if (random.nextInt(100) >= caveRarity) {
            caves = 0;
        }
        int changed = 0;
        for (int i = 0; i < caves; i++) {
            double x = (chunkX << 4) + random.nextInt(16);
            double y = random.nextInt(random.nextInt(caveMaxAltitude - caveMinAltitude + 1) + 1)
                    + caveMinAltitude;
            double z = (chunkZ << 4) + random.nextInt(16);
            int count = caveSystemFrequency;
            boolean largeCaveSpawned = false;
            if (random.nextInt(100) <= individualCaveRarity) {
                changed += generateNode(world, session, originChunkX, originChunkZ, x, y, z,
                        1.0 + random.nextDouble() * 6.0, 0.0, 0.0, -1, -1, 0.5);
                largeCaveSpawned = true;
            }
            if (largeCaveSpawned || random.nextInt(100) <= caveSystemPocketChance - 1) {
                count += random.nextInt(Math.max(1, caveSystemPocketMaxSize - caveSystemPocketMinSize))
                        + caveSystemPocketMinSize;
            }
            while (count > 0) {
                count--;
                double angle = random.nextDouble() * Math.PI * 2.0;
                double vertical = (random.nextDouble() - 0.5) * 2.0 / 8.0;
                double width = random.nextDouble() * 2.0 + random.nextDouble();
                changed += generateNode(world, session, originChunkX, originChunkZ, x, y, z,
                        width, angle, vertical, 0, 0, 1.0);
            }
        }
        return changed;
    }

    /**
     * One cave chain, upstream's {@code generateCaveNode}: it walks from
     * {@code angle} to {@code maxAngle}, growing and shrinking the node it carves.
     */
    private int generateNode(World world, EditSession session, int chunkX, int chunkZ,
                             double x, double y, double z, double width, double yaw, double pitch,
                             int angle, int maxAngle, double thickness) {
        int bx = chunkX << 4;
        int bz = chunkZ << 4;
        double realX = bx + 7;
        double realZ = bz + 7;
        double speedY = 0.0;
        double speedAngle = 0.0;
        if (maxAngle <= 0) {
            int checkArea = checkAreaSize * 16 - 16;
            maxAngle = checkArea - random.nextInt(checkArea / 4);
        }
        boolean isLargeCave = false;
        if (angle == -1) {
            angle = maxAngle / 2;
            isLargeCave = true;
        }
        int split = random.nextInt(maxAngle / 2) + maxAngle / 4;
        int thin = random.nextInt(6) == 0 ? 1 : 0;
        int changed = 0;
        for (; angle < maxAngle; angle++) {
            double horizontal = 1.5 + Math.sin(angle * Math.PI / maxAngle) * width * 1.0;
            double vertical = horizontal * thickness;
            double cosPitch = Math.cos(pitch);
            double sinPitch = Math.sin(pitch);
            x += Math.cos(yaw) * cosPitch;
            y += sinPitch;
            z += Math.sin(yaw) * cosPitch;
            pitch = thin != 0 ? pitch * 0.92 : pitch * 0.7;
            pitch += speedAngle * 0.1;
            yaw += speedY * 0.1;
            speedAngle *= 0.9;
            speedY *= 0.75;
            speedAngle += (random.nextDouble() - random.nextDouble()) * random.nextDouble() * 2.0;
            speedY += (random.nextDouble() - random.nextDouble()) * random.nextDouble() * 4.0;
            if (!isLargeCave && angle == split && width > 1.0 && maxAngle > 0) {
                // The chain forks here, which is what makes a cave network.
                generateNode(world, session, chunkX, chunkZ, x, y, z,
                        random.nextDouble() * 0.5 + 0.5, yaw - (Math.PI / 2), pitch / 3.0,
                        angle, maxAngle, 1.0);
                generateNode(world, session, chunkX, chunkZ, x, y, z,
                        random.nextDouble() * 0.5 + 0.5, yaw + (Math.PI / 2), pitch / 3.0,
                        angle, maxAngle, 1.0);
                return changed;
            }
            if (!isLargeCave && random.nextInt(4) == 0) {
                continue;
            }
            double dx = x - realX;
            double dz = z - realZ;
            double remaining = maxAngle - angle;
            double reach = width + 18.0;
            if (dx * dx + dz * dz - remaining * remaining > reach * reach) {
                return changed;
            }
            if (x < realX - 16.0 - horizontal * 2.0 || z < realZ - 16.0 - horizontal * 2.0
                    || x > realX + 16.0 + horizontal * 2.0 || z > realZ + 16.0 + horizontal * 2.0) {
                continue;
            }
            int fromX = (int) (x - horizontal) - bx - 1;
            int toX = (int) (x + horizontal) - bx + 1;
            int fromY = (int) (y - vertical) - 1;
            int toY = (int) (y + vertical) + 1;
            int fromZ = (int) (z - horizontal) - bz - 1;
            int toZ = (int) (z + horizontal) - bz + 1;
            fromX = Math.max(fromX, 0);
            toX = Math.min(toX, 16);
            fromY = Math.max(fromY, world.minY() + 1);
            toY = Math.min(toY, world.maxY() - 8);
            fromZ = Math.max(fromZ, 0);
            toZ = Math.min(toZ, 16);
            if (toX - fromX <= 0 || toZ - fromZ <= 0 || toY - fromY <= 0) {
                continue;
            }
            // A cave never runs into water: a node whose box holds any is left
            // alone, which is what keeps the sea floor closed.
            boolean waterFound = false;
            for (int localX = fromX; !waterFound && localX < toX; localX++) {
                for (int localZ = fromZ; !waterFound && localZ < toZ; localZ++) {
                    for (int localY = toY + 1; !waterFound && localY >= fromY - 1; localY--) {
                        if (localY < world.maxY()
                                && REGISTRY.name(world.getBlock(bx + localX, localY, bz + localZ))
                                .equals("minecraft:water")) {
                            waterFound = true;
                        }
                    }
                }
            }
            if (waterFound) {
                continue;
            }
            for (int localX = fromX; localX < toX; localX++) {
                double d9 = (localX + bx + 0.5 - x) / horizontal;
                for (int localZ = fromZ; localZ < toZ; localZ++) {
                    double d10 = (localZ + bz + 0.5 - z) / horizontal;
                    if (d9 * d9 + d10 * d10 >= 1.0) {
                        continue;
                    }
                    boolean grassFound = false;
                    for (int localY = toY; localY > fromY; localY--) {
                        double d11 = (localY - 1 + 0.5 - y) / vertical;
                        if (d11 <= -0.7 || d9 * d9 + d11 * d11 + d10 * d10 >= 1.0) {
                            continue;
                        }
                        int material = world.getBlock(bx + localX, localY, bz + localZ);
                        String name = REGISTRY.name(material);
                        if (name.equals("minecraft:mycelium") || name.equals("minecraft:grass_block")) {
                            grassFound = true;
                        }
                        if (!suitable(name)) {
                            continue;
                        }
                        if (localY - 1 < 10) {
                            changed += carve(session, bx + localX, localY, bz + localZ, "minecraft:lava");
                        } else {
                            changed += carve(session, bx + localX, localY, bz + localZ,
                                    "minecraft:cave_air");
                            if (grassFound && REGISTRY.name(world.getBlock(bx + localX, localY - 1,
                                    bz + localZ)).equals("minecraft:dirt")) {
                                // The grass above the cave is gone, so the dirt
                                // under it becomes stone and nothing floats.
                                changed += carve(session, bx + localX, localY - 1, bz + localZ,
                                        "minecraft:stone");
                            }
                        }
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Writes one block of a cave, counting the cells that really changed. Nodes
     * reach past the selection, so anything outside it is dropped.
     */
    private int carve(EditSession session, int x, int y, int z, String block) {
        return region.contains(x, y, z) && session.setBlock(x, y, z, REGISTRY.defaultState(block))
                ? 1 : 0;
    }

    /** The blocks a cave may cut through, upstream's {@code isSuitableBlock}. */
    private static boolean suitable(String name) {
        return switch (name) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water",
                 "minecraft:lava", "minecraft:bedrock" -> false;
            default -> true;
        };
    }
}
