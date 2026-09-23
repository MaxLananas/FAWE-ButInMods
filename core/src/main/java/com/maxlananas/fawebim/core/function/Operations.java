package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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
        return sphere(session, center, radius, pattern, hollow, false);
    }

    /**
     * A sphere, optionally raised: {@code //sphere -r} keeps the bottom half of
     * the sphere at the placement height instead of the centre, which is how
     * FAWE builds a dome.
     */
    public static int sphere(EditSession session, BlockVector3 center, double radius, Pattern pattern, boolean hollow,
                             boolean raised) {
        int changed = 0;
        int r = (int) Math.ceil(radius);
        double radiusSq = radius * radius;
        double innerSq = hollow ? (radius - 1) * (radius - 1) : -1;
        for (int y = -r; y <= r; y++) {
            if (raised && y < 0) {
                // Raised: the sphere grows upwards from the placement position.
                continue;
            }
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
        return cylinder(session, center, radius, height, pattern, hollow, 0);
    }

    /**
     * A cylinder, hollow or solid. The wall thickness only applies to the hollow
     * form: FAWE leaves the floor and the roof in place and keeps the requested
     * number of blocks behind the shell, so a thickness of one is the plain
     * hollow cylinder.
     */
    public static int cylinder(EditSession session, BlockVector3 center, int radius, int height, Pattern pattern,
                               boolean hollow, double thickness) {
        int changed = 0;
        int r = Math.max(0, radius);
        int minY = center.y() - height / 2;
        int maxY = minY + Math.max(1, height) - 1;
        double radiusSq = (double) r * r;
        double wall = hollow ? Math.max(0, thickness) : 0;
        double inner = hollow ? Math.max(0, r - 1 - Math.floor(wall)) : 0;
        double innerSq = hollow ? inner * inner : -1;
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

    /**
     * A cubic Hermite spline with the tension, bias and continuity controls
     * {@code /brush surfacespline} exposes, drawn on the surface below the path.
     */
    public static int surfaceSpline(EditSession session, List<BlockVector3> points, Pattern pattern,
                                    double tension, double bias, double continuity, int quality) {
        if (points.size() < 2) {
            return 0;
        }
        int steps = Math.max(1, quality);
        int changed = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            BlockVector3 p0 = points.get(Math.max(0, i - 1));
            BlockVector3 p1 = points.get(i);
            BlockVector3 p2 = points.get(i + 1);
            BlockVector3 p3 = points.get(Math.min(points.size() - 1, i + 2));
            for (int step = 0; step < steps; step++) {
                double t = (double) step / steps;
                // Kochanek-Bartels: the tangent leaving p1 and the one entering
                // p2 are scaled by tension, tilted by bias and mixed by
                // continuity.
                double mx1 = (1 - tension) * (1 + bias) * (1 + continuity) / 2 * (p2.x() - p1.x())
                        + (1 - tension) * (1 - bias) * (1 - continuity) / 2 * (p1.x() - p0.x());
                double mz1 = (1 - tension) * (1 + bias) * (1 + continuity) / 2 * (p2.z() - p1.z())
                        + (1 - tension) * (1 - bias) * (1 - continuity) / 2 * (p1.z() - p0.z());
                double mx2 = (1 - tension) * (1 + bias) * (1 - continuity) / 2 * (p2.x() - p1.x())
                        + (1 - tension) * (1 - bias) * (1 + continuity) / 2 * (p3.x() - p2.x());
                double mz2 = (1 - tension) * (1 + bias) * (1 - continuity) / 2 * (p2.z() - p1.z())
                        + (1 - tension) * (1 - bias) * (1 + continuity) / 2 * (p3.z() - p2.z());
                double t2 = t * t;
                double t3 = t2 * t;
                double h1 = 2 * t3 - 3 * t2 + 1;
                double h2 = -2 * t3 + 3 * t2;
                double h3 = t3 - 2 * t2 + t;
                double h4 = t3 - t2;
                int x = (int) Math.floor(h1 * p1.x() + h2 * p2.x() + h3 * mx1 + h4 * mx2);
                int z = (int) Math.floor(h1 * p1.z() + h2 * p2.z() + h3 * mz1 + h4 * mz2);
                int y = session.getWorld().getHighestBlockY(x, z);
                if (session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * {@code //curve -h}: only the outer layer of the tube the path would fill is
     * written, which is FAWE's shell mode.
     */
    public static int splineShell(EditSession session, List<BlockVector3> points, Pattern pattern, double thickness) {
        if (points.size() < 2 || thickness < 1) {
            return spline(session, points, pattern, thickness);
        }
        int shell = 0;
        List<BlockVector3> positions = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            BlockVector3 from = points.get(i);
            BlockVector3 to = points.get(i + 1);
            int steps = (int) Math.max(1, from.distance(to));
            for (int step = 0; step <= steps; step++) {
                double t = (double) step / steps;
                positions.add(new BlockVector3(
                        (int) Math.floor(from.x() + (to.x() - from.x()) * t),
                        (int) Math.floor(from.y() + (to.y() - from.y()) * t),
                        (int) Math.floor(from.z() + (to.z() - from.z()) * t)));
            }
        }
        java.util.Set<BlockVector3> written = new java.util.HashSet<>();
        for (BlockVector3 position : positions) {
            for (BlockVector3 target : spherePositions(position, (int) Math.ceil(thickness), false)) {
                if (!written.add(target)) {
                    continue;
                }
                if (session.setBlock(target.x(), target.y(), target.z(),
                        pattern.apply(target.x(), target.y(), target.z()))) {
                    shell++;
                }
            }
        }
        return shell;
    }

    /** {@code //curve} — Catmull-Rom spline through the given points. */
    public static int spline(EditSession session, List<BlockVector3> points, Pattern pattern, double thickness) {
        return spline(session, points, pattern, thickness, 8);
    }

    /**
     * A Catmull-Rom spline through the given points. {@code subdivisions} is the
     * number of blocks drawn between two control points, so FAWE's
     * {@code quality} argument maps straight onto it.
     */
    public static int spline(EditSession session, List<BlockVector3> points, Pattern pattern, double thickness,
                             int subdivisions) {
        if (points.size() < 2) {
            return 0;
        }
        int steps = Math.max(1, subdivisions);
        int changed = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            BlockVector3 p0 = points.get(Math.max(0, i - 1));
            BlockVector3 p1 = points.get(i);
            BlockVector3 p2 = points.get(i + 1);
            BlockVector3 p3 = points.get(Math.min(points.size() - 1, i + 2));
            for (double t = 0; t < 1; t += 1.0 / steps) {
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
            replaceMask = new com.maxlananas.fawebim.core.mask.Masks.BlockMask(session,
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
            for (com.maxlananas.fawebim.core.world.Direction direction : com.maxlananas.fawebim.core.world.Direction.values()) {
                BlockVector3 next = current.add(direction.toVector());
                if (!visited.contains(next) && next.distance(start) <= radius) {
                    queue.add(next);
                }
            }
        }
        return changed;
    }

    /**
     * Collects the connected non-air blocks under a position into a clipboard,
     * which is what the first click of {@code /brush copypaste} does: FAWE walks
     * the blob with a recursive visitor limited to the brush radius and to the
     * height of the click, so the copy never grows downwards into the ground.
     *
     * @param limit the brush radius, which also caps how far the walk goes
     * @param mask  the brush mask, applied on top of "the block is not air"
     */
    public static com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard copyConnected(
            World world, EditSession session, BlockVector3 start, int limit, Mask mask) {
        BlockStateRegistry registry = BlockState.registry();
        com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard clipboard =
                new com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard(start);
        if (registry.isAirLike(world.getBlock(start.x(), start.y(), start.z()))) {
            return clipboard;
        }
        Deque<BlockVector3> queue = new ArrayDeque<>();
        java.util.Set<BlockVector3> visited = new java.util.HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockVector3 current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            if (current.y() < start.y() || current.distance(start) > limit) {
                continue;
            }
            int state = world.getBlock(current.x(), current.y(), current.z());
            if (registry.isAirLike(state) || mask != null && !mask.test(current.x(), current.y(), current.z())) {
                continue;
            }
            clipboard.setBlock(current.x(), current.y(), current.z(), state);
            com.maxlananas.fawebim.core.util.NbtCompound nbt = world.getBlockEntity(current.x(), current.y(), current.z());
            if (nbt != null) {
                clipboard.addBlockEntity(current, nbt);
            }
            session.limiter().check(1);
            for (com.maxlananas.fawebim.core.world.Direction direction
                    : com.maxlananas.fawebim.core.world.Direction.values()) {
                BlockVector3 next = current.add(direction.toVector());
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return clipboard;
    }

    private static boolean isEdge(World world, BlockVector3 position, Mask mask) {
        for (com.maxlananas.fawebim.core.world.Direction direction : com.maxlananas.fawebim.core.world.Direction.values()) {
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
            for (com.maxlananas.fawebim.core.world.Direction direction
                    : com.maxlananas.fawebim.core.world.Direction.values()) {
                if (direction == com.maxlananas.fawebim.core.world.Direction.UP) {
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

    /** {@code /brush blendball} — blends the brush area with its surroundings. */
    public static int blendBall(EditSession session, BlockVector3 center, int radius, Mask mask) {
        return blendBall(session, center, radius, mask, false, 1);
    }

    /**
     * FAWE's blend ball. With {@code onlyAir} the comparison only looks at
     * whether a position is air or not, which evens out cliffs without touching
     * the material mix; otherwise a block changes when its neighbours agree on
     * another block at least {@code minFreqDiff} times more often than on the
     * current one.
     */
    public static int blendBall(EditSession session, BlockVector3 center, int radius, Mask mask,
                                boolean onlyAir, int minFreqDiff) {
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
                    int current = session.getBlock(bx, by, bz);
                    java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int state = session.getBlock(bx + dx, by + dy, bz + dz);
                                if (onlyAir) {
                                    state = registry.isAirLike(state) ? registry.air() : current;
                                }
                                counts.merge(state, 1, Integer::sum);
                            }
                        }
                    }
                    int best = current;
                    int bestCount = 0;
                    for (var entry : counts.entrySet()) {
                        if (entry.getValue() > bestCount && !registry.isAirLike(entry.getKey())) {
                            bestCount = entry.getValue();
                            best = entry.getKey();
                        }
                    }
                    int currentCount = counts.getOrDefault(current, 0);
                    if (best != current && bestCount - currentCount >= minFreqDiff
                            && session.setBlock(bx, by, bz, best)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code /brush gravity} — drops blocks straight down. */
    public static int gravity(World world, EditSession session, BlockVector3 center, int radius) {
        return gravity(world, session, center, radius, null, false);
    }

    /**
     * Drops the blocks of every column of the brush onto the first gap below
     * them, which is what WorldEdit and FAWE both do: each column keeps its
     * blocks in order and compacts them into the lowest air cells of the scan
     * window.
     *
     * <p>The window spans the brush radius above and below the clicked block.
     * {@code -h <height>} replaces that offset with the given height in
     * WorldEdit; FAWE turns the same switch into a flag with no value and scans
     * down to the bottom of the world instead, which is why the two are passed
     * separately here and why a height wins when both are present.</p>
     */
    public static int gravity(World world, EditSession session, BlockVector3 center, int radius,
                              Integer heightOffset, boolean scanToWorldFloor) {
        BlockStateRegistry registry = BlockState.registry();
        int offset = heightOffset == null ? radius : heightOffset;
        int top = Math.min(center.y() + offset, world.maxY());
        int floor = scanToWorldFloor && heightOffset == null
                ? world.minY()
                : Math.max(center.y() - offset, world.minY());
        int changed = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = center.x() + x;
                int bz = center.z() + z;
                // The lowest air cell of the window that nothing has fallen into
                // yet; null until the column opens up, because a block sitting on
                // the bottom of its window has nowhere to go.
                Integer lowestAir = null;
                for (int y = floor; y <= top; y++) {
                    int state = world.getBlock(bx, y, bz);
                    if (registry.isAirLike(state)) {
                        if (lowestAir == null) {
                            lowestAir = y;
                        }
                        continue;
                    }
                    if (lowestAir == null) {
                        continue;
                    }
                    int target = lowestAir;
                    lowestAir = target + 1;
                    if (target == y) {
                        continue;
                    }
                    session.setBlock(bx, y, bz, registry.air());
                    if (session.setBlock(bx, target, bz, state)) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------- deform/generate

    /** {@code //deform} — moves blocks according to an expression. */
    public static int deform(World world, EditSession session, Region region, String expressionInput) {
        return deform(world, session, region, expressionInput, 0, 0, 0);
    }

    /**
     * Deforms the region with an expression evaluated with {@code ox}, {@code oy}
     * and {@code oz} as the origin, which is what the deform brush's {@code -o}
     * switch asks for; the plain form uses the world origin.
     */
    public static int deform(World world, EditSession session, Region region, String expressionInput,
                             int ox, int oy, int oz) {
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
                    variables.set("ox", min.x() + x - ox).set("oy", min.y() + y - oy).set("oz", min.z() + z - oz);
                    variables.set("cx", width / 2.0).set("cy", height / 2.0).set("cz", length / 2.0);
                    // The expression stores the displacement in x/y/z.
                    expression.evaluate(variables);
                    int tx = (int) Math.floor(min.x() + variables.get("x"));
                    int ty = (int) Math.floor(min.y() + variables.get("y"));
                    int tz = (int) Math.floor(min.z() + variables.get("z"));
                    if (session.setBlock(tx, ty, tz, state)) {
                        changed++;
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
            for (com.maxlananas.fawebim.core.world.Direction direction
                    : com.maxlananas.fawebim.core.world.Direction.values()) {
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
        return ore(world, session, region, ore, random, OreDeepslate.NONE);
    }

    /** How {@code //ores} rewrites the ore of the deepslate layers. */
    public enum OreDeepslate {
        /** Leave the ores exactly as the pattern wrote them. */
        NONE,
        /** {@code -b}: everything below y=0 becomes the deepslate variant. */
        BELOW_ZERO,
        /** {@code -d}: only the ores that replace deepslate become deepslate variants. */
        WHERE_DEEPSLATE;

        public static OreDeepslate of(boolean belowZero, boolean whereDeepslate) {
            if (whereDeepslate) {
                return WHERE_DEEPSLATE;
            }
            return belowZero ? BELOW_ZERO : NONE;
        }
    }

    /**
     * {@code //ore} / {@code //ores}: scatters the pattern through the selection
     * the way FAWE's ore generator does. When a deepslate mode is set, the block
     * that ends up below the deepslate line is replaced by its deepslate variant,
     * so a stone ore never shows up in the deepslate layers.
     */
    public static int ore(World world, EditSession session, Region region, Pattern ore, Random random,
                          OreDeepslate deepslate) {
        int changed = 0;
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        BlockStateRegistry registry = BlockState.registry();
        int attempts = Math.max(1, (int) (region.getVolume() / 500));
        for (int i = 0; i < attempts; i++) {
            session.checkTimeout();
            int size = 2 + random.nextInt(5);
            int x = min.x() + random.nextInt(Math.max(1, max.x() - min.x() + 1));
            int y = min.y() + random.nextInt(Math.max(1, max.y() - min.y() + 1));
            int z = min.z() + random.nextInt(Math.max(1, max.z() - min.z() + 1));
            for (int block = 0; block < size; block++) {
                int bx = x + random.nextInt(3) - 1;
                int by = y + random.nextInt(3) - 1;
                int bz = z + random.nextInt(3) - 1;
                if (!region.contains(bx, by, bz)) {
                    continue;
                }
                int target = world.getBlock(bx, by, bz);
                if (deepslate == OreDeepslate.WHERE_DEEPSLATE
                        && !"minecraft:deepslate".equals(registry.name(target))) {
                    continue;
                }
                int state = ore.apply(bx, by, bz);
                if (deepslate != OreDeepslate.NONE && by < 0) {
                    state = deepslateVariant(registry, state);
                }
                if (session.setBlock(bx, by, bz, state)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** The deepslate form of an ore, or the state itself when there is none. */
    private static int deepslateVariant(BlockStateRegistry registry, int state) {
        String name = registry.name(state);
        if (name.contains("deepslate") || !name.startsWith("minecraft:")) {
            return state;
        }
        String deepslateName = "minecraft:deepslate_" + name.substring("minecraft:".length());
        int variant = registry.defaultState(deepslateName);
        return variant < 0 ? state : variant;
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
        return fall(world, session, region, false);
    }

    /**
     * {@code //fall}, with {@code -m} keeping the blocks inside the vertical
     * bounds of the selection instead of letting them out of the bottom.
     */
    public static int fall(World world, EditSession session, Region region, boolean withinSelection) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (BlockVector3 position : region) {
            int state = world.getBlock(position.x(), position.y(), position.z());
            if (registry.isAirLike(state)) {
                continue;
            }
            int y = position.y();
            int floor = withinSelection ? region.getMinimumPoint().y() : world.minY();
            while (y > floor && registry.isAirLike(world.getBlock(position.x(), y - 1, position.z()))) {
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
        return scatter(session, center, radius, 1, radius, pattern, random, overwrite, null);
    }

    /**
     * {@code /brush scatter <pattern> [radius] [points] [distance] [-o]}: drops a
     * number of points on the surface around the click, each of them somewhere
     * within {@code distance} blocks of where it was aimed. With {@code overlay}
     * the block is placed on top of the surface, otherwise a point that is not
     * already air is skipped.
     */
    public static int scatter(EditSession session, BlockVector3 center, int points, int distance, double radius,
                              Pattern pattern, Random random, boolean overlay, Mask mask) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        int spread = (int) Math.max(1, radius);
        for (int i = 0; i < Math.max(1, points); i++) {
            int x = random.nextInt(spread * 2 + 1) - spread;
            int z = random.nextInt(spread * 2 + 1) - spread;
            if (Math.sqrt(x * x + z * z) > spread) {
                continue;
            }
            int y = spread;
            while (y > -spread
                    && registry.isAirLike(session.getBlock(center.x() + x, center.y() + y, center.z() + z))) {
                y--;
            }
            int jitter = Math.max(0, distance - 1);
            int by = center.y() + y + 1 + (jitter == 0 ? 0 : random.nextInt(jitter * 2 + 1) - jitter);
            int bx = center.x() + x;
            int bz = center.z() + z;
            if (mask != null && !mask.test(bx, by, bz)) {
                continue;
            }
            if (!overlay && !registry.isAirLike(session.getBlock(bx, by, bz))) {
                continue;
            }
            if (session.setBlock(bx, by, bz, pattern.apply(bx, by, bz))) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Removes the water of every waterlogged block of the region, {@code //drain -w}.
     * The block itself stays, only its {@code waterlogged} property is cleared.
     */
    public static int drainWaterlogged(EditSession session, Region region) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (BlockVector3 position : region) {
            int state = session.getBlock(position.x(), position.y(), position.z());
            if (state == registry.air()) {
                continue;
            }
            Map<String, String> properties = registry.properties(state);
            if (!"true".equals(properties.get("waterlogged"))) {
                continue;
            }
            int cleared = registry.withProperty(state, "waterlogged", "false");
            if (cleared >= 0 && session.setBlock(position.x(), position.y(), position.z(), cleared)) {
                changed++;
            }
        }
        return changed;
    }

    /** A pattern that always returns the same state. */
    private record FixedPattern(int state) implements Pattern {

        @Override
        public int apply(int x, int y, int z) {
            return state;
        }
    }

    /** A pattern that reuses an expression's output. */
    public static Pattern expressionPattern(Expression expression) {
        return (x, y, z) -> {
            Expression.Variables variables = new Expression.Variables();
            variables.set("x", x).set("y", y).set("z", z);
            return (int) expression.evaluate(variables);
        };
    }

    /** Utility used by the brushes: the highest non-air block in a column. */
    public static int highestBlock(World world, int x, int z) {
        return world.getHighestBlockY(x, z);
    }

    /**
     * Receives one block of a shape. Returning true counts it as a change, the
     * same value the shape commands return.
     */
    @FunctionalInterface
    public interface BlockVisitor {

        boolean visit(int x, int y, int z);
    }

    /**
     * Walks the blocks of a sphere, solid or hollow, and returns how many of
     * them the visitor counted as a change. A brush runs on every click of a
     * drag, so the coordinates reach the visitor as they are computed instead of
     * going through a list of {@link BlockVector3}, which at a large radius was
     * the biggest allocation of a stroke.
     *
     * @param maxChanges stopped after this many changes; 0 for no limit
     */
    public static int forEachInSphere(BlockVector3 center, int radius, boolean hollow, int maxChanges,
                                      BlockVisitor visitor) {
        int changed = 0;
        int r = Math.max(0, radius);
        double radiusSq = (double) r * r;
        double innerSq = hollow ? Math.max(0, r - 1) * (double) Math.max(0, r - 1) : -1;
        int cx = center.x();
        int cy = center.y();
        int cz = center.z();
        for (int y = -r; y <= r; y++) {
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    double distanceSq = x * x + y * y + z * z;
                    if (distanceSq > radiusSq || (hollow && distanceSq < innerSq)) {
                        continue;
                    }
                    if (visitor.visit(cx + x, cy + y, cz + z)) {
                        changed++;
                        if (maxChanges > 0 && changed >= maxChanges) {
                            return changed;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** {@link #forEachInSphere(BlockVector3, int, boolean, int, BlockVisitor)} without a limit. */
    public static int forEachInSphere(BlockVector3 center, int radius, boolean hollow, BlockVisitor visitor) {
        return forEachInSphere(center, radius, hollow, 0, visitor);
    }

    /** Utility used by the tools: a list of blocks in a radius, shell or solid. */
    public static List<BlockVector3> spherePositions(BlockVector3 center, int radius, boolean hollow) {
        List<BlockVector3> positions = new ArrayList<>();
        forEachInSphere(center, radius, hollow, (x, y, z) -> {
            positions.add(new BlockVector3(x, y, z));
            return false;
        });
        return positions;
    }
}
