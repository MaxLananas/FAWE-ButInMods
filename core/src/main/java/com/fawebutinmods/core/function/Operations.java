package com.fawebutinmods.core.function;

import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.math.Vector3;
import com.fawebutinmods.core.pattern.Pattern;
import com.fawebutinmods.core.expression.Expression;
import com.fawebutinmods.core.region.Region;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;
import com.fawebutinmods.core.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The block operations behind the commands and brushes (FAWE's
 * {@code com.fastasyncworldedit.core.function} package).
 */
public final class Operations {

    private Operations() {
    }

    // ------------------------------------------------------------------ shapes

    /** {@code //sphere} and {@code /brush sphere}. */
    public static int sphere(EditSession session, BlockVector3 center, double radius, Pattern pattern, boolean hollow) {
        int changed = 0;
        int r = (int) Math.ceil(radius);
        double radiusSq = radius * radius;
        double innerSq = hollow ? (radius - 1) * (radius - 1) : -1;
        for (int y = -r; y <= r; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    double distanceSq = x * x + y * y + z * z;
                    if (distanceSq > radiusSq || (hollow && distanceSq < innerSq)) {
                        continue;
                    }
                    int bx = center.x() + x;
                    int by = center.y() + y;
                    int bz = center.z() + z;
                    if (session.setBlock(bx, by, bz, pattern.apply(bx, by, bz))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //cyl} and {@code /brush cylinder}. */
    public static int cylinder(EditSession session, BlockVector3 center, int radius, int height, Pattern pattern,
                               boolean hollow) {
        int changed = 0;
        int r = Math.max(0, radius);
        int minY = center.y() - height / 2;
        int maxY = minY + Math.max(1, height) - 1;
        double radiusSq = (double) r * r;
        double innerSq = hollow ? Math.max(0, r - 1) * (double) Math.max(0, r - 1) : -1;
        for (int y = minY; y <= maxY; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    double distanceSq = x * x + z * z;
                    if (distanceSq > radiusSq || (hollow && distanceSq < innerSq && y != minY && y != maxY)) {
                        continue;
                    }
                    int bx = center.x() + x;
                    int bz = center.z() + z;
                    if (session.setBlock(bx, y, bz, pattern.apply(bx, y, bz))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //pyramid}. */
    public static int pyramid(EditSession session, BlockVector3 center, int size, Pattern pattern, boolean hollow) {
        int changed = 0;
        int base = center.y() - size / 2;
        for (int layer = 0; layer <= size; layer++) {
            int y = base + layer;
            int r = size - layer;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    boolean edge = Math.abs(x) == r || Math.abs(z) == r;
                    if (hollow && !edge) {
                        continue;
                    }
                    int bx = center.x() + x;
                    int bz = center.z() + z;
                    if (session.setBlock(bx, y, bz, pattern.apply(bx, y, bz))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //cone}. */
    public static int cone(EditSession session, BlockVector3 center, double radius, double height, Pattern pattern,
                           boolean hollow) {
        int changed = 0;
        int heightInt = (int) Math.max(1, height);
        int base = center.y() - heightInt / 2;
        for (int layer = 0; layer < heightInt; layer++) {
            double r = radius * (1 - (double) layer / heightInt);
            int ri = (int) Math.ceil(r);
            int y = base + layer;
            for (int z = -ri; z <= ri; z++) {
                for (int x = -ri; x <= ri; x++) {
                    double distance = Math.sqrt(x * x + z * z);
                    if (distance > r || (hollow && distance < r - 1)) {
                        continue;
                    }
                    int bx = center.x() + x;
                    int bz = center.z() + z;
                    if (session.setBlock(bx, y, bz, pattern.apply(bx, y, bz))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------- lines/curves

    /** {@code //line}. */
    public static int line(EditSession session, BlockVector3 from, BlockVector3 to, Pattern pattern, double thickness,
                           boolean shell) {
        int changed = 0;
        Vector3 direction = new Vector3(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
        double length = Math.max(1, direction.length());
        Vector3 step = direction.divide(length);
        int extra = (int) Math.ceil(thickness);
        for (double t = 0; t <= length; t += 0.5) {
            double x = from.x() + step.x() * t;
            double y = from.y() + step.y() * t;
            double z = from.z() + step.z() * t;
            for (int dy = -extra; dy <= extra; dy++) {
                for (int dz = -extra; dz <= extra; dz++) {
                    for (int dx = -extra; dx <= extra; dx++) {
                        if (thickness > 0 && Math.sqrt(dx * dx + dy * dy + dz * dz) > thickness) {
                            continue;
                        }
                        if (thickness == 0 && (dx != 0 || dy != 0 || dz != 0)) {
                            continue;
                        }
                        int bx = (int) Math.floor(x) + dx;
                        int by = (int) Math.floor(y) + dy;
                        int bz = (int) Math.floor(z) + dz;
                        if (session.setBlock(bx, by, bz, pattern.apply(bx, by, bz))) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //curve} — Catmull-Rom spline through the given points. */
    public static int spline(EditSession session, List<BlockVector3> points, Pattern pattern, double thickness) {
        if (points.size() < 2) {
            return 0;
        }
        int changed = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            BlockVector3 p0 = points.get(Math.max(0, i - 1));
            BlockVector3 p1 = points.get(i);
            BlockVector3 p2 = points.get(i + 1);
            BlockVector3 p3 = points.get(Math.min(points.size() - 1, i + 2));
            for (double t = 0; t < 1; t += 0.02) {
                double t2 = t * t;
                double t3 = t2 * t;
                double x = 0.5 * ((2 * p1.x()) + (-p0.x() + p2.x()) * t
                        + (2 * p0.x() - 5 * p1.x() + 4 * p2.x() - p3.x()) * t2
                        + (-p0.x() + 3 * p1.x() - 3 * p2.x() + p3.x()) * t3);
                double y = 0.5 * ((2 * p1.y()) + (-p0.y() + p2.y()) * t
                        + (2 * p0.y() - 5 * p1.y() + 4 * p2.y() - p3.y()) * t2
                        + (-p0.y() + 3 * p1.y() - 3 * p2.y() + p3.y()) * t3);
                double z = 0.5 * ((2 * p1.z()) + (-p0.z() + p2.z()) * t
                        + (2 * p0.z() - 5 * p1.z() + 4 * p2.z() - p3.z()) * t2
                        + (-p0.z() + 3 * p1.z() - 3 * p2.z() + p3.z()) * t3);
                int bx = (int) Math.floor(x);
                int by = (int) Math.floor(y);
                int bz = (int) Math.floor(z);
                if (session.setBlock(bx, by, bz, pattern.apply(bx, by, bz))) {
                    changed++;
                }
                if (thickness >= 1) {
                    changed += sphere(session, new BlockVector3(bx, by, bz), thickness, pattern, false);
                }
            }
        }
        return changed;
    }

    /** {@code /brush catenary} — a sagging rope between two points. */
    public static int catenary(EditSession session, BlockVector3 from, BlockVector3 to, Pattern pattern,
                               double lengthFactor, double thickness) {
        int changed = 0;
        double distance = from.distance(to);
        double sag = distance * lengthFactor;
        int steps = (int) Math.max(2, distance * 2);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x() + (to.x() - from.x()) * t;
            double z = from.z() + (to.z() - from.z()) * t;
            double y = from.y() + (to.y() - from.y()) * t - sag * Math.sin(Math.PI * t);
            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            if (session.setBlock(bx, by, bz, pattern.apply(bx, by, bz))) {
                changed++;
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- flood fill

    /** {@code //fill}/{@code //drain} — 3D flood fill of the connected blocks. */
    public static int floodFill(World world, EditSession session, BlockVector3 start, Pattern pattern, int radius,
                                boolean hollow) {
        return floodFill(world, session, start, pattern, radius, hollow, null);
    }

    public static int floodFill(World world, EditSession session, BlockVector3 start, Pattern pattern, int radius,
                                boolean hollow, Mask replaceMask) {
        BlockStateRegistry registry = BlockState.registry();
        int targetState = world.getBlock(start.x(), start.y(), start.z());
        if (!registry.isAirLike(targetState) && replaceMask == null) {
            // Filling a solid block only replaces that same block type.
            replaceMask = new com.fawebutinmods.core.mask.Masks.BlockMask(session,
                    List.of(registry.describe(targetState)));
        }
        Deque<BlockVector3> queue = new ArrayDeque<>();
        java.util.Set<BlockVector3> visited = new java.util.HashSet<>();
        queue.add(start);
        int changed = 0;
        while (!queue.isEmpty()) {
            BlockVector3 current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.distance(start) > radius) {
                continue;
            }
            Mask reject = replaceMask;
            if (reject != null && !reject.test(current)) {
                continue;
            }
            boolean shouldPlace = !hollow || isEdge(world, current, replaceMask);
            if (shouldPlace && session.setBlock(current.x(), current.y(), current.z(),
                    pattern.apply(current.x(), current.y(), current.z()))) {
                changed++;
            }
            session.limiter().check(1);
            for (com.fawebutinmods.core.world.Direction direction : com.fawebutinmods.core.world.Direction.values()) {
                BlockVector3 next = current.add(direction.toVector());
                if (!visited.contains(next) && next.distance(start) <= radius) {
                    queue.add(next);
                }
            }
        }
        return changed;
    }

    private static boolean isEdge(World world, BlockVector3 position, Mask mask) {
        for (com.fawebutinmods.core.world.Direction direction : com.fawebutinmods.core.world.Direction.values()) {
            BlockVector3 next = position.add(direction.toVector());
            if (mask != null && !mask.test(next)) {
                return true;
            }
            if (mask == null && !BlockState.registry()
                    .isAirLike(world.getBlock(next.x(), next.y(), next.z()))) {
                return true;
            }
        }
        return false;
    }

    /** {@code //drain} — removes connected liquid. */
    public static int drain(World world, EditSession session, BlockVector3 start, Mask liquidMask, int radius) {
        Deque<BlockVector3> queue = new ArrayDeque<>();
        java.util.Set<BlockVector3> visited = new java.util.HashSet<>();
        queue.add(start);
        int changed = 0;
        int air = BlockState.registry().air();
        while (!queue.isEmpty()) {
            BlockVector3 current = queue.poll();
            if (!visited.add(current) || current.distance(start) > radius) {
                continue;
            }
            if (visited.size() > 1_000_000) {
                break;
            }
            if (!liquidMask.test(current)) {
                continue;
            }
            if (session.setBlock(current.x(), current.y(), current.z(), air)) {
                changed++;
            }
            session.limiter().check(1);
            for (com.fawebutinmods.core.world.Direction direction
                    : com.fawebutinmods.core.world.Direction.values()) {
                if (direction == com.fawebutinmods.core.world.Direction.UP) {
                    continue;
                }
                queue.add(current.add(direction.toVector()));
            }
        }
        return changed;
    }

    /** {@code //fixwater}, {@code //fixlava} — makes liquid flow to its neighbours. */
    public static int fixLiquid(World world, EditSession session, Region region, String liquid, int radius) {
        BlockStateRegistry registry = BlockState.registry();
        int source = registry.parse("minecraft:" + liquid);
        if (source < 0) {
            return 0;
        }
        int changed = 0;
        for (BlockVector3 position : region) {
            if (registry.name(world.getBlock(position.x(), position.y(), position.z())).equals("minecraft:" + liquid)
                    && session.setBlock(position.x(), position.y(), position.z(), source)) {
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------- smooth

    /** {@code //smooth} — heightmap smoothing, exactly the FAWE flood-fill variant. */
    public static int smooth(World world, EditSession session, Region region, int iterations) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        int air = registry.air();
        int stone = registry.defaultState("minecraft:stone");
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                    int height = 0;
                    int count = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            height += world.getHighestBlockY(x + dx, z + dz);
                            count++;
                        }
                    }
                    int average = height / Math.max(1, count);
                    int currentHeight = world.getHighestBlockY(x, z);
                    if (average > currentHeight) {
                        for (int y = currentHeight + 1; y <= average; y++) {
                            if (session.setBlock(x, y, z, stone)) {
                                changed++;
                            }
                        }
                    } else if (average < currentHeight) {
                        for (int y = average + 1; y <= currentHeight; y++) {
                            if (session.setBlock(x, y, z, air)) {
                                changed++;
                            }
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** {@code /brush blendball} — blends the brush area with its surroundings. */
    public static int blendBall(EditSession session, BlockVector3 center, int radius, Mask mask) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (int y = -radius; y <= radius; y++) {
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + y * y + z * z) > radius) {
                        continue;
                    }
                    int bx = center.x() + x;
                    int by = center.y() + y;
                    int bz = center.z() + z;
                    if (mask != null && !mask.test(bx, by, bz)) {
                        continue;
                    }
                    java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                counts.merge(session.getBlock(bx + dx, by + dy, bz + dz), 1, Integer::sum);
                            }
                        }
                    }
                    int best = session.getBlock(bx, by, bz);
                    int bestCount = 0;
                    for (var entry : counts.entrySet()) {
                        if (entry.getValue() > bestCount && !registry.isAirLike(entry.getKey())) {
                            bestCount = entry.getValue();
                            best = entry.getKey();
                        }
                    }
                    if (best != session.getBlock(bx, by, bz) && session.setBlock(bx, by, bz, best)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code /brush gravity} — drops blocks straight down. */
    public static int gravity(World world, EditSession session, BlockVector3 center, int radius) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (Math.sqrt(x * x + z * z) > radius) {
                    continue;
                }
                int bx = center.x() + x;
                int bz = center.z() + z;
                int radiusY = (int) Math.sqrt(Math.max(0, radius * radius - x * x - z * z));
                for (int y = center.y() + radiusY; y >= center.y() - radiusY; y--) {
                    int state = world.getBlock(bx, y, bz);
                    if (registry.isAirLike(state) || !registry.isFullCube(state)) {
                        continue;
                    }
                    int below = y - 1;
                    while (below > world.minY() && registry.isAirLike(world.getBlock(bx, below, bz))) {
                        below--;
                    }
                    if (below + 1 != y) {
                        session.setBlock(bx, y, bz, registry.air());
                        if (session.setBlock(bx, below + 1, bz, state)) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------- deform/generate

    /** {@code //deform} — moves blocks according to an expression. */
    public static int deform(World world, EditSession session, Region region, String expressionInput) {
        Expression expression = Expression.compile(expressionInput);
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;
        int[] source = new int[width * height * length];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    source[index++] = world.getBlock(min.x() + x, min.y() + y, min.z() + z);
                }
            }
        }
        BlockStateRegistry registry = BlockState.registry();
        for (BlockVector3 position : region) {
            session.setBlock(position.x(), position.y(), position.z(), registry.air());
        }
        int changed = 0;
        index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int state = source[index++];
                    if (registry.isAirLike(state)) {
                        continue;
                    }
                    Expression.Variables variables = new Expression.Variables();
                    variables.set("x", x).set("y", y).set("z", z);
                    variables.set("ox", min.x() + x).set("oy", min.y() + y).set("oz", min.z() + z);
                    variables.set("cx", width / 2.0).set("cy", height / 2.0).set("cz", length / 2.0);
                    expression.evaluate(variables);
                    if (true) {
                        // The expression stores the displacement in x/y/z.
                        int tx = (int) Math.floor(min.x() + variables.get("x"));
                        int ty = (int) Math.floor(min.y() + variables.get("y"));
                        int tz = (int) Math.floor(min.z() + variables.get("z"));
                        if (session.setBlock(tx, ty, tz, state)) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //flora}/{@code //forest} — plants trees and foliage. */
    public static int flora(World world, EditSession session, Region region, double density) {
        Random random = new Random();
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
            for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                if (random.nextDouble() > density) {
                    continue;
                }
                int y = world.getHighestBlockY(x, z);
                if (registry.isAirLike(world.getBlock(x, y + 1, z))) {
                    if (world.generateTree(new BlockVector3(x, y + 1, z), "tree", random)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //deltree} — removes a tree starting from its trunk. */
    public static int removeTree(World world, EditSession session, BlockVector3 start) {
        Deque<BlockVector3> queue = new ArrayDeque<>();
        java.util.Set<BlockVector3> visited = new java.util.HashSet<>();
        queue.add(start);
        int changed = 0;
        int air = BlockState.registry().air();
        while (!queue.isEmpty()) {
            BlockVector3 current = queue.poll();
            if (!visited.add(current) || visited.size() > 20000) {
                continue;
            }
            String name = BlockState.registry()
                    .name(world.getBlock(current.x(), current.y(), current.z()));
            if (!name.contains("log") && !name.contains("leaves") && !name.contains("wood")) {
                continue;
            }
            if (session.setBlock(current.x(), current.y(), current.z(), air)) {
                changed++;
            }
            for (com.fawebutinmods.core.world.Direction direction
                    : com.fawebutinmods.core.world.Direction.values()) {
                queue.add(current.add(direction.toVector()));
            }
        }
        return changed;
    }

    /**
     * {@code //ores} — scatters ore veins through the selection.
     *
     * @param mask      blocks a vein may replace
     * @param size      blocks per vein
     * @param frequency one vein per this many blocks
     * @param rarity    1 in {@code rarity} veins is actually placed
     */
    public static int ore(World world, EditSession session, Region region, Mask mask, Pattern ore, int size,
                          double frequency, double rarity, Random random) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int veins = (int) Math.max(1, region.getVolume() / Math.max(1, frequency));
        int changed = 0;
        for (int i = 0; i < veins; i++) {
            if (rarity > 1 && random.nextDouble() * rarity > 1) {
                continue;
            }
            session.checkTimeout();
            int x = min.x() + random.nextInt(Math.max(1, max.x() - min.x() + 1));
            int y = min.y() + random.nextInt(Math.max(1, max.y() - min.y() + 1));
            int z = min.z() + random.nextInt(Math.max(1, max.z() - min.z() + 1));
            for (int j = 0; j < size; j++) {
                int bx = x + random.nextInt(3) - 1;
                int by = y + random.nextInt(3) - 1;
                int bz = z + random.nextInt(3) - 1;
                if (!region.contains(bx, by, bz) || mask == null || !mask.test(bx, by, bz)) {
                    continue;
                }
                if (session.setBlock(bx, by, bz, ore.apply(bx, by, bz))) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** {@code //ore} with the defaults FAWE uses when only a pattern is given. */
    public static int ore(World world, EditSession session, Region region, Pattern ore, Random random) {
        return ore(world, session, region, null, ore, 6, 500, 1, random);
    }

    /**
     * {@code //caves} — carves caves/worms through the selection.
     *
     * @param frequency chance in percent that a cave is started per 1000 blocks
     * @param rarity    chance a started cave carves anything at all (0-1)
     * @param size      radius factor of the carved tunnel
     */
    public static int caves(World world, EditSession session, Region region, Random random, double frequency,
                            double rarity, int size) {
        int air = BlockState.registry().air();
        int changed = 0;
        int candidates = Math.max(1, (int) (region.getVolume() / 1000));
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        for (int i = 0; i < candidates; i++) {
            if (random.nextDouble() * 100 > frequency || random.nextDouble() > rarity) {
                continue;
            }
            session.checkTimeout();
            int x = min.x() + random.nextInt(Math.max(1, max.x() - min.x() + 1));
            int y = min.y() + random.nextInt(Math.max(1, max.y() - min.y() + 1));
            int z = min.z() + random.nextInt(Math.max(1, max.z() - min.z() + 1));
            int length = size + random.nextInt(size * 2);
            double dx = random.nextDouble() * 2 - 1;
            double dy = random.nextDouble() * 2 - 1;
            double dz = random.nextDouble() * 2 - 1;
            double norm = Math.max(0.0001, Math.sqrt(dx * dx + dy * dy + dz * dz));
            dx /= norm;
            dy /= norm;
            dz /= norm;
            for (int step = 0; step < length; step++) {
                if (!region.contains(x, y, z)) {
                    break;
                }
                changed += sphere(session, new BlockVector3(x, y, z),
                        1 + random.nextDouble() * size / 4.0, new FixedPattern(air), false);
                x += Math.round(dx * 2);
                y += Math.round(dy * 2);
                z += Math.round(dz * 2);
            }
        }
        return changed;
    }

    /** {@code //fall} — drops every block in the region to the ground. */
    public static int fall(World world, EditSession session, Region region) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (BlockVector3 position : region) {
            int state = world.getBlock(position.x(), position.y(), position.z());
            if (registry.isAirLike(state)) {
                continue;
            }
            int y = position.y();
            while (y > world.minY() && registry.isAirLike(world.getBlock(position.x(), y - 1, position.z()))) {
                y--;
            }
            if (y != position.y()) {
                session.setBlock(position.x(), position.y(), position.z(), registry.air());
                if (session.setBlock(position.x(), y, position.z(), state)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** {@code /brush scatter} — scatters a pattern over the surface. */
    public static int scatter(EditSession session, BlockVector3 center, int radius, Pattern pattern, Random random,
                              boolean overwrite) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (int i = 0; i < radius * radius * 4; i++) {
            int x = random.nextInt(radius * 2 + 1) - radius;
            int z = random.nextInt(radius * 2 + 1) - radius;
            if (Math.sqrt(x * x + z * z) > radius) {
                continue;
            }
            int y = radius;
            while (y > -radius && registry.isAirLike(session.getBlock(center.x() + x, center.y() + y, center.z() + z))) {
                y--;
            }
            int by = center.y() + y + 1;
            if (overwrite || registry.isAirLike(session.getBlock(center.x() + x, by, center.z() + z))) {
                if (session.setBlock(center.x() + x, by, center.z() + z,
                        pattern.apply(center.x() + x, by, center.z() + z))) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** A pattern that always returns the same state. */
    private record FixedPattern(int state) implements Pattern {

        @Override
        public int apply(BlockVector3 position) {
            return state;
        }
    }

    /** A pattern that reuses an expression's output. */
    public static Pattern expressionPattern(Expression expression) {
        return (position) -> {
            Expression.Variables variables = new Expression.Variables();
            variables.set("x", position.x()).set("y", position.y()).set("z", position.z());
            return (int) expression.evaluate(variables);
        };
    }

    /** Utility used by the brushes: the highest non-air block in a column. */
    public static int highestBlock(World world, int x, int z) {
        return world.getHighestBlockY(x, z);
    }

    /** Utility used by the brushes: a list of blocks in a radius, shell or solid. */
    public static List<BlockVector3> spherePositions(BlockVector3 center, int radius, boolean hollow) {
        List<BlockVector3> positions = new ArrayList<>();
        int r = Math.max(0, radius);
        double radiusSq = (double) r * r;
        double innerSq = hollow ? Math.max(0, r - 1) * (double) Math.max(0, r - 1) : -1;
        for (int y = -r; y <= r; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    double distanceSq = x * x + y * y + z * z;
                    if (distanceSq > radiusSq || (hollow && distanceSq < innerSq)) {
                        continue;
                    }
                    positions.add(center.add(x, y, z));
                }
            }
        }
        return positions;
    }
}
