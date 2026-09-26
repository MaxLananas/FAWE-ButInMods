package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.transform.Axis;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.util.LongQueue;
import com.maxlananas.fawebim.core.util.LongSet;
import com.maxlananas.fawebim.core.util.noise.Noise;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
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

    /**
     * {@code //faces} and {@code //outline}: the pattern on every cell of the
     * region that has a neighbour outside it.
     *
     * <p>For a cuboid that is its six faces. For any other selection it is the
     * surface of the shape rather than the faces of the box around it, which is
     * what FAWE's own {@code RegionShape} pass builds - a sphere's shell, not the
     * six caps of its bounding box.</p>
     */
    public static int faces(EditSession session, Region region, Pattern pattern) {
        int[] changed = {0};
        region.forEachPosition((x, y, z) -> {
            for (int side = 0; side < NEIGHBOURS.length; side += 3) {
                if (!region.contains(x + NEIGHBOURS[side], y + NEIGHBOURS[side + 1],
                        z + NEIGHBOURS[side + 2])) {
                    if (session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                        changed[0]++;
                    }
                    break;
                }
            }
            return false;
        });
        return changed[0];
    }

    /** The six neighbours of a cell, as x/y/z offsets. */
    private static final int[] NEIGHBOURS = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};

    /**
     * {@code //thaw}: melts the snow and ice of a cylinder around a position.
     *
     * <p>WorldEdit's own algorithm: each column of the disc is walked downwards
     * and the first block that is not air decides the column - ice becomes
     * water, a snow layer is removed, and anything else is left alone.</p>
     */
    public static int thaw(World world, EditSession session, BlockVector3 center, double radius, int height) {
        com.maxlananas.fawebim.core.world.BlockStateRegistry registry = BlockState.registry();
        int ice = registry.defaultState("minecraft:ice");
        int snow = registry.defaultState("minecraft:snow");
        int water = registry.defaultState("minecraft:water");
        int air = registry.air();
        int affected = 0;
        double radiusSq = radius * radius;
        int ceilRadius = (int) Math.ceil(radius);
        int minY = Math.max(world.minY(), center.y() - height);
        int maxY = Math.min(world.maxY(), center.y() + height);
        for (int x = center.x() - ceilRadius; x <= center.x() + ceilRadius; x++) {
            for (int z = center.z() - ceilRadius; z <= center.z() + ceilRadius; z++) {
                int dx = x - center.x();
                int dz = z - center.z();
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                for (int y = maxY; y > minY; y--) {
                    int state = session.getBlock(x, y, z);
                    if (state == ice) {
                        if (session.setBlock(x, y, z, water)) {
                            affected++;
                        }
                    } else if (state == snow) {
                        if (session.setBlock(x, y, z, air)) {
                            affected++;
                        }
                    } else if (registry.isAirLike(state)) {
                        continue;
                    }
                    break;
                }
            }
        }
        return affected;
    }

    /**
     * {@code //green}: turns the dirt of a cylinder around a position into grass.
     *
     * <p>WorldEdit's own algorithm: the topmost block of each column is looked
     * at, dirt (and coarse dirt with {@code -f}) becomes grass, and water, lava
     * and anything solid stop the column.</p>
     */
    public static int green(World world, EditSession session, BlockVector3 center, double radius, int height,
                            boolean onlyNormalDirt) {
        com.maxlananas.fawebim.core.world.BlockStateRegistry registry = BlockState.registry();
        int dirt = registry.defaultState("minecraft:dirt");
        int coarseDirt = registry.defaultState("minecraft:coarse_dirt");
        int grass = registry.defaultState("minecraft:grass_block");
        int water = registry.defaultState("minecraft:water");
        int lava = registry.defaultState("minecraft:lava");
        int affected = 0;
        double radiusSq = radius * radius;
        int ceilRadius = (int) Math.ceil(radius);
        int minY = Math.max(world.minY(), center.y() - height);
        int maxY = Math.min(world.maxY(), center.y() + height);
        for (int x = center.x() - ceilRadius; x <= center.x() + ceilRadius; x++) {
            for (int z = center.z() - ceilRadius; z <= center.z() + ceilRadius; z++) {
                int dx = x - center.x();
                int dz = z - center.z();
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                for (int y = maxY; y > minY; y--) {
                    int state = session.getBlock(x, y, z);
                    if (state == dirt || (!onlyNormalDirt && state == coarseDirt)) {
                        if (session.setBlock(x, y, z, grass)) {
                            affected++;
                        }
                        break;
                    }
                    if (state == water || state == lava || registry.isSolid(state)) {
                        break;
                    }
                }
            }
        }
        return affected;
    }

    /**
     * {@code //snow}: covers a cylinder around a position in snow the way the
     * weather would.
     *
     * <p>WorldEdit's {@code SnowSimulator}: a water block at sea level freezes,
     * and everything else the column holds gets a snow layer on top of it. With
     * {@code -s} the layers pile up - a full layer becomes a snow block - instead
     * of a single layer per run.</p>
     */
    public static int simulateSnow(World world, EditSession session, BlockVector3 center, double radius, int height,
                                   boolean stack) {
        com.maxlananas.fawebim.core.world.BlockStateRegistry registry = BlockState.registry();
        int snow = registry.defaultState("minecraft:snow");
        int snowBlock = registry.defaultState("minecraft:snow_block");
        int ice = registry.defaultState("minecraft:ice");
        int water = registry.defaultState("minecraft:water");
        int affected = 0;
        double radiusSq = radius * radius;
        int ceilRadius = (int) Math.ceil(radius);
        int minY = Math.max(world.minY(), center.y() - height);
        int maxY = Math.min(world.maxY(), center.y() + height);
        for (int x = center.x() - ceilRadius; x <= center.x() + ceilRadius; x++) {
            for (int z = center.z() - ceilRadius; z <= center.z() + ceilRadius; z++) {
                int dx = x - center.x();
                int dz = z - center.z();
                if (dx * dx + dz * dz > radiusSq) {
                    continue;
                }
                for (int y = maxY; y > minY; y--) {
                    int state = session.getBlock(x, y, z);
                    // The ground of a column is the first block that is not air,
                    // and not an existing layer when they are being stacked.
                    if (registry.isAirLike(state) || (stack && state == snow)) {
                        continue;
                    }
                    if (state == water) {
                        if ("0".equals(registry.properties(state).get("level"))) {
                            if (session.setBlock(x, y, z, ice)) {
                                affected++;
                            }
                        }
                        break;
                    }
                    // A layer is placed on the block above the ground, at most one
                    // block below the top of the region.
                    if (y == maxY) {
                        break;
                    }
                    int above = session.getBlock(x, y + 1, z);
                    boolean aboveAir = registry.isAirLike(above);
                    boolean aboveSnow = above == snow;
                    if (!aboveAir && !(stack && aboveSnow)) {
                        break;
                    }
                    if (stack && aboveSnow) {
                        int layers = Integer.parseInt(registry.properties(above).getOrDefault("layers", "1"));
                        int next = layers + 1;
                        int placed = next >= 8 ? snowBlock
                                : registry.withProperty(snow, "layers", Integer.toString(next));
                        if (placed >= 0 && session.setBlock(x, y + 1, z, placed)) {
                            affected++;
                        }
                    } else if (session.setBlock(x, y + 1, z, snow)) {
                        affected++;
                    }
                    break;
                }
            }
        }
        return affected;
    }

    /**
     * {@code //extinguish}: removes every block a mask accepts inside a cube
     * around a position, which is WorldEdit's {@code removeNear}.
     */
    public static int removeNear(World world, EditSession session, BlockVector3 center, int apothem,
                                 Mask mask) {
        int affected = 0;
        for (int y = center.y() - apothem; y <= center.y() + apothem; y++) {
            if (y < world.minY() || y > world.maxY()) {
                continue;
            }
            for (int z = center.z() - apothem; z <= center.z() + apothem; z++) {
                for (int x = center.x() - apothem; x <= center.x() + apothem; x++) {
                    if (!mask.test(x, y, z)) {
                        continue;
                    }
                    if (session.setBlock(x, y, z, BlockState.registry().air())) {
                        affected++;
                    }
                }
            }
        }
        return affected;
    }

    /** A hollow allocates a bit per cell of the selection; past this it refuses. */
    private static final long MAX_HOLLOW_CELLS = 64L * 1024 * 1024;

    /**
     * {@code //hollow}: replaces the inside of what the selection holds with the
     * pattern, leaving a shell of {@code thickness} blocks.
     *
     * <p>This is FAWE's own algorithm rather than a test on the shape. The space
     * that opens onto the selection's faces is flooded through the cells the mask
     * leaves open - cells that are not solid, by default - and the flood never
     * runs on from outside the region. Every cell the flood does not reach, and
     * every cell a thicker shell keeps, is left alone; the rest is replaced. The
     * surface of the object therefore survives and its inside does not, whatever
     * shape it has. The selection does need room around the object: a selection
     * that hugs a solid object leaves the flood nothing to start from and is
     * emptied whole, which is how FAWE behaves as well.</p>
     *
     * @param mask the cells that stop the flood, e.g. solid blocks
     * @return how many blocks changed
     */
    public static int hollow(EditSession session, Region region, int thickness, Pattern pattern, Mask mask) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;
        long volume = (long) width * height * length;
        if (volume <= 0 || volume > MAX_HOLLOW_CELLS) {
            throw new com.maxlananas.fawebim.core.util.InputException("A selection of " + volume + " blocks is too large to hollow");
        }
        // A shell thicker than the selection is the selection: every layer of the
        // growth is a layer further in, so once the growth has crossed the region
        // there is nothing left for the next one to reach. Clamping here is what
        // keeps //hollow with a thickness of a billion from looping a billion
        // times over a selection of a few blocks.
        thickness = Math.min(thickness, Math.max(width, Math.max(height, length)));
        // One bit per cell of the region padded by a block, so the neighbours of
        // the region's own cells have an index even when they are outside it.
        int padWidth = width + 2;
        int padLength = length + 2;
        int cellCount = padWidth * (height + 2) * padLength;
        BitSet open = new BitSet(cellCount);
        long[] queue = new long[Math.min(1 << 16, Math.max(64, (int) volume))];
        int queued = 0;

        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask, x, y, min.z());
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask, x, y, max.z());
            }
        }
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask, min.x(), y, z);
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask, max.x(), y, z);
            }
        }
        for (int z = min.z(); z <= max.z(); z++) {
            for (int x = min.x(); x <= max.x(); x++) {
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask, x, min.y(), z);
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask, x, max.y(), z);
            }
        }

        while (queued > 0) {
            long packed = queue[--queued];
            int x = BlockArrayClipboard.keyX(packed);
            int y = BlockArrayClipboard.keyY(packed);
            int z = BlockArrayClipboard.keyZ(packed);
            for (int side = 0; side < NEIGHBOURS.length; side += 3) {
                if (!region.contains(x + NEIGHBOURS[side], y + NEIGHBOURS[side + 1], z + NEIGHBOURS[side + 2])) {
                    continue;
                }
                queued = visit(queue, queued, open, region, min, padWidth, padLength, mask,
                        x + NEIGHBOURS[side], y + NEIGHBOURS[side + 1], z + NEIGHBOURS[side + 2]);
            }
        }

        // A shell thicker than one block is the flood grown inward, a layer at a
        // time: every cell of the region next to a reached cell counts as reached.
        for (int layer = 1; layer < thickness; layer++) {
            BitSet grown = (BitSet) open.clone();
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    for (int x = 0; x < width; x++) {
                        int index = padded(x, y, z, padWidth, padLength);
                        if (open.get(index) || !region.contains(min.x() + x, min.y() + y, min.z() + z)) {
                            continue;
                        }
                        for (int side = 0; side < NEIGHBOURS.length; side += 3) {
                            if (open.get(padded(x + NEIGHBOURS[side], y + NEIGHBOURS[side + 1],
                                    z + NEIGHBOURS[side + 2], padWidth, padLength))) {
                                grown.set(index);
                                break;
                            }
                        }
                    }
                }
            }
            open = grown;
        }

        int changed = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    boolean keeps = false;
                    for (int side = 0; side < NEIGHBOURS.length; side += 3) {
                        if (open.get(padded(x + NEIGHBOURS[side], y + NEIGHBOURS[side + 1],
                                z + NEIGHBOURS[side + 2], padWidth, padLength))) {
                            keeps = true;
                            break;
                        }
                    }
                    if (keeps) {
                        continue;
                    }
                    int worldX = min.x() + x;
                    int worldY = min.y() + y;
                    int worldZ = min.z() + z;
                    if (!region.contains(worldX, worldY, worldZ)) {
                        continue;
                    }
                    if (session.setBlock(worldX, worldY, worldZ, pattern.apply(worldX, worldY, worldZ))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** Adds a cell to the flood and queues it when it is inside the region. */
    private static int visit(long[] queue, int queued, BitSet open, Region region, BlockVector3 min,
                             int padWidth, int padLength, Mask mask, int x, int y, int z) {
        int index = padded(x - min.x(), y - min.y(), z - min.z(), padWidth, padLength);
        if (open.get(index)) {
            return queued;
        }
        if (mask.test(x, y, z)) {
            return queued;
        }
        open.set(index);
        if (queued == queue.length) {
            queue = java.util.Arrays.copyOf(queue, queue.length * 2);
        }
        queue[queued++] = BlockArrayClipboard.positionKey(x, y, z);
        return queued;
    }

    private static int padded(int x, int y, int z, int padWidth, int padLength) {
        return ((y + 1) * padLength + (z + 1)) * padWidth + (x + 1);
    }

    // ------------------------------------------------------------------ shapes

    /** {@code //sphere} and {@code /brush sphere}. */
    public static int sphere(EditSession session, BlockVector3 center, double radius, Pattern pattern, boolean hollow) {
        return sphere(session, center, new double[]{radius, radius, radius}, pattern, hollow);
    }

    /**
     * A sphere or an ellipsoid, solid or hollow, using WorldEdit's geometry: a
     * radius grows by half a block before the shape is measured, a cell belongs
     * to it when the squared sum of its axes over the radii is at most one, and
     * the hollow form keeps the cells whose outward neighbour on some axis has
     * left the shape.
     */
    public static int sphere(EditSession session, BlockVector3 center, double[] radii, Pattern pattern,
                             boolean hollow) {
        double radiusX = radii[0] + 0.5;
        double radiusY = radii[1] + 0.5;
        double radiusZ = radii[2] + 0.5;
        double invX = 1 / radiusX;
        double invY = 1 / radiusY;
        double invZ = 1 / radiusZ;
        int ceilX = (int) Math.ceil(radiusX);
        int ceilY = (int) Math.ceil(radiusY);
        int ceilZ = (int) Math.ceil(radiusZ);
        int minY = session.minY();
        int maxY = session.maxY();
        int changed = 0;
        for (int x = -ceilX; x <= ceilX; x++) {
            double dx = square(x * invX);
            if (dx > 1) {
                continue;
            }
            for (int z = -ceilZ; z <= ceilZ; z++) {
                double dxz = dx + square(z * invZ);
                if (dxz > 1) {
                    continue;
                }
                for (int y = -ceilY; y <= ceilY; y++) {
                    double distance = dxz + square(y * invY);
                    if (distance > 1) {
                        continue;
                    }
                    int blockY = center.y() + y;
                    if (blockY < minY || blockY > maxY) {
                        continue;
                    }
                    if (hollow && square((Math.abs(x) + 1) * invX) + square(y * invY) + square(z * invZ) <= 1
                            && dx + square((Math.abs(y) + 1) * invY) + square(z * invZ) <= 1
                            && dx + square(y * invY) + square((Math.abs(z) + 1) * invZ) <= 1) {
                        continue;
                    }
                    int blockX = center.x() + x;
                    int blockZ = center.z() + z;
                    if (session.setBlock(blockX, blockY, blockZ, pattern.apply(blockX, blockY, blockZ))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    private static double square(double value) {
        return value * value;
    }

    /** {@code //cyl} and {@code /brush cylinder}. */
    public static int cylinder(EditSession session, BlockVector3 center, int radius, int height, Pattern pattern,
                               boolean hollow) {
        return cylinder(session, center, radius, height, pattern, hollow, 0);
    }

    public static int cylinder(EditSession session, BlockVector3 center, int radius, int height, Pattern pattern,
                               boolean hollow, double thickness) {
        return cylinder(session, center, new double[]{radius, radius}, height, pattern, hollow, thickness);
    }

    /**
     * A cylinder or an elliptic cylinder, solid or hollow. The two radii are the
     * north/south and the east/west extent, the height grows upward from the
     * placement position as in WorldEdit and downward when it is negative, and a
     * hollow cylinder is its wall: the ends stay open and {@code thickness}
     * widens the wall inwards.
     */
    public static int cylinder(EditSession session, BlockVector3 center, double[] radii, int height, Pattern pattern,
                               boolean hollow, double thickness) {
        if (height == 0) {
            return 0;
        }
        double radiusX = radii[0] + 0.5;
        double radiusZ = radii[1] + 0.5;
        double invX = 1 / radiusX;
        double invZ = 1 / radiusZ;
        int ceilX = (int) Math.ceil(radiusX);
        int ceilZ = (int) Math.ceil(radiusZ);
        int bottom = height < 0 ? center.y() - Math.abs(height) : center.y();
        int top = height < 0 ? center.y() - 1 : center.y() + height - 1;
        int minY = session.minY();
        int maxY = session.maxY();
        double wall = hollow ? Math.max(0, thickness) : 0;
        double innerInvX = hollow && radiusX > wall ? 1 / (radiusX - wall) : 0;
        double innerInvZ = hollow && radiusZ > wall ? 1 / (radiusZ - wall) : 0;
        int changed = 0;
        for (int x = -ceilX; x <= ceilX; x++) {
            double dx = square(x * invX);
            if (dx > 1) {
                continue;
            }
            for (int z = -ceilZ; z <= ceilZ; z++) {
                if (dx + square(z * invZ) > 1) {
                    continue;
                }
                if (hollow) {
                    // A plain hollow cylinder is the ring whose outward neighbour
                    // on an axis has left the shape; with a thickness the ring is
                    // measured against the inner cylinder instead, which is what
                    // WorldEdit widens the wall with.
                    boolean keep = wall > 0 && innerInvX > 0 && innerInvZ > 0
                            ? square((Math.abs(x) + 1) * innerInvX) + square(z * innerInvZ) > 1
                                    || square(x * innerInvX) + square((Math.abs(z) + 1) * innerInvZ) > 1
                            : square((Math.abs(x) + 1) * invX) + square(z * invZ) > 1
                                    || dx + square((Math.abs(z) + 1) * invZ) > 1;
                    if (!keep) {
                        continue;
                    }
                }
                int blockX = center.x() + x;
                int blockZ = center.z() + z;
                for (int y = bottom; y <= top; y++) {
                    if (y < minY || y > maxY) {
                        continue;
                    }
                    if (session.setBlock(blockX, y, blockZ, pattern.apply(blockX, y, blockZ))) {
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
        // The layers are walked in long arithmetic and only where the world is:
        // a size near the int range would otherwise wrap the layer counter.
        long base = (long) center.y() - size / 2;
        long from = Math.max(base, session.minY());
        long to = Math.min(base + size, session.maxY());
        for (long level = from; level <= to; level++) {
            int y = (int) level;
            int r = (int) (size - (level - base));
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
        // The tube of a path overlaps itself where the path bends, and a block
        // that two spheres share must be written once. The positions live in a
        // primitive set keyed by the world position, not in a set of objects.
        com.maxlananas.fawebim.core.util.LongObjectMap<Boolean> written =
                new com.maxlananas.fawebim.core.util.LongObjectMap<>();
        int radius = (int) Math.ceil(thickness);
        int[] shellCounter = {0};
        for (BlockVector3 position : positions) {
            forEachInSphere(position, radius, false, (x, y, z) -> {
                long key = (((long) x & 0x3FFFFFF) << 38) | (((long) y & 0xFFF) << 26) | ((long) z & 0x3FFFFFF);
                if (written.get(key) != null) {
                    return false;
                }
                written.put(key, Boolean.TRUE);
                if (session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                    shellCounter[0]++;
                }
                return false;
            });
        }
        return shellCounter[0];
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
    /**
     * A flood fill that, given a depth, is WorldEdit's {@code /fillr}: it fills
     * no higher than the start and stops {@code depth} blocks below it, which
     * keeps a recursive fill from following a hole to the bottom of the world.
     *
     * @param depth       how many blocks below the start may be filled, 0 for a
     *                    fill in every direction
     * @param replaceMask what the fill may replace; {@code null} for what the
     *                    start holds
     */
    public static int floodFill(World world, EditSession session, BlockVector3 start, Pattern pattern, int radius,
                                boolean hollow, Mask replaceMask, int depth) {
        BlockStateRegistry registry = BlockState.registry();
        int targetState = world.getBlock(start.x(), start.y(), start.z());
        if (replaceMask == null) {
            // Without a mask the fill replaces what its start holds: the same
            // block, or the air joined to it - never everything in its radius.
            replaceMask = registry.isAirLike(targetState)
                    ? new com.maxlananas.fawebim.core.mask.Masks.AirMask(session, false)
                    : new com.maxlananas.fawebim.core.mask.Masks.BlockMask(session,
                            List.of(registry.describe(targetState)));
        }
        // The walk stays inside the world, as WorldEdit's fills do: one that left
        // it through the sky came back down into holes it could not reach. With
        // a depth it is /fillr, which fills down from its start and no higher.
        // The lowest level is found in long arithmetic: the default depth is the
        // int range, and below y 0 the level wrapped round to the top of it.
        int lowest = (int) Math.max(depth > 0 ? (long) start.y() - depth + 1 : Long.MIN_VALUE, world.minY());
        int highest = depth > 0 ? Math.min(world.maxY(), start.y()) : world.maxY();
        long reach = (long) radius * radius;
        LongQueue queue = new LongQueue();
        LongSet visited = new LongSet();
        queue.add(BlockArrayClipboard.positionKey(start.x(), start.y(), start.z()));
        int changed = 0;
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            int x = BlockArrayClipboard.keyX(current);
            int y = BlockArrayClipboard.keyY(current);
            int z = BlockArrayClipboard.keyZ(current);
            if (distanceSq(x, y, z, start) > reach || y < lowest || y > highest) {
                continue;
            }
            Mask reject = replaceMask;
            if (reject != null && !reject.test(x, y, z)) {
                continue;
            }
            boolean shouldPlace = !hollow || isEdge(world, x, y, z, replaceMask);
            if (shouldPlace && session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                changed++;
            }
            session.limiter().check(1);
            for (com.maxlananas.fawebim.core.world.Direction direction : DIRECTIONS) {
                int nx = x + direction.x();
                int ny = y + direction.y();
                int nz = z + direction.z();
                long next = BlockArrayClipboard.positionKey(nx, ny, nz);
                if (!visited.contains(next) && distanceSq(nx, ny, nz, start) <= reach
                        && ny >= lowest && ny <= highest) {
                    queue.add(next);
                }
            }
        }
        return changed;
    }

    /** The six directions in their declared order, read without copying the enum's array per block. */
    private static final com.maxlananas.fawebim.core.world.Direction[] DIRECTIONS =
            com.maxlananas.fawebim.core.world.Direction.values();

    private static long distanceSq(int x, int y, int z, BlockVector3 origin) {
        long dx = (long) x - origin.x();
        long dy = (long) y - origin.y();
        long dz = (long) z - origin.z();
        return dx * dx + dy * dy + dz * dz;
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
        LongQueue queue = new LongQueue();
        LongSet visited = new LongSet();
        queue.add(BlockArrayClipboard.positionKey(start.x(), start.y(), start.z()));
        long reach = (long) limit * limit;
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            int x = BlockArrayClipboard.keyX(current);
            int y = BlockArrayClipboard.keyY(current);
            int z = BlockArrayClipboard.keyZ(current);
            if (y < start.y() || distanceSq(x, y, z, start) > reach) {
                continue;
            }
            int state = world.getBlock(x, y, z);
            if (registry.isAirLike(state) || mask != null && !mask.test(x, y, z)) {
                continue;
            }
            clipboard.setBlock(x, y, z, state);
            session.limiter().check(1);
            for (com.maxlananas.fawebim.core.world.Direction direction : DIRECTIONS) {
                long next = BlockArrayClipboard.positionKey(x + direction.x(), y + direction.y(), z + direction.z());
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        com.maxlananas.fawebim.core.clipboard.Clipboards.copyBlockEntities(world, clipboard);
        return clipboard;
    }

    private static boolean isEdge(World world, int x, int y, int z, Mask mask) {
        for (com.maxlananas.fawebim.core.world.Direction direction : DIRECTIONS) {
            int nx = x + direction.x();
            int ny = y + direction.y();
            int nz = z + direction.z();
            if (mask != null && !mask.test(nx, ny, nz)) {
                return true;
            }
            if (mask == null && !BlockState.registry().isAirLike(world.getBlock(nx, ny, nz))) {
                return true;
            }
        }
        return false;
    }

    /** {@code //drain} — removes connected liquid. */
    public static int drain(World world, EditSession session, BlockVector3 start, Mask liquidMask, int radius) {
        LongQueue queue = new LongQueue();
        LongSet visited = new LongSet();
        queue.add(BlockArrayClipboard.positionKey(start.x(), start.y(), start.z()));
        long reach = (long) radius * radius;
        int changed = 0;
        int air = BlockState.registry().air();
        while (!queue.isEmpty()) {
            long current = queue.poll();
            int x = BlockArrayClipboard.keyX(current);
            int y = BlockArrayClipboard.keyY(current);
            int z = BlockArrayClipboard.keyZ(current);
            if (!visited.add(current) || distanceSq(x, y, z, start) > reach) {
                continue;
            }
            if (visited.size() > 1_000_000) {
                break;
            }
            if (!liquidMask.test(x, y, z)) {
                continue;
            }
            if (session.setBlock(x, y, z, air)) {
                changed++;
            }
            session.limiter().check(1);
            for (com.maxlananas.fawebim.core.world.Direction direction : DIRECTIONS) {
                if (direction == com.maxlananas.fawebim.core.world.Direction.UP) {
                    continue;
                }
                queue.add(BlockArrayClipboard.positionKey(x + direction.x(), y + direction.y(), z + direction.z()));
            }
        }
        return changed;
    }

    /** {@code //fixwater}, {@code //fixlava} — makes liquid flow to its neighbours. */
    public static long fixLiquid(World world, EditSession session, Region region, String liquid, int radius) {
        BlockStateRegistry registry = BlockState.registry();
        int source = registry.parse("minecraft:" + liquid);
        if (source < 0) {
            return 0;
        }
        String name = "minecraft:" + liquid;
        return region.forEachPosition((x, y, z) -> registry.name(world.getBlock(x, y, z)).equals(name)
                && session.setBlock(x, y, z, source));
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
        // The 27 cells around a block hold at most 27 states: a tally in two
        // small arrays reused for every block, instead of a map per block.
        int[] states = new int[27];
        int[] counts = new int[27];
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
                    int distinct = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int state = session.getBlock(bx + dx, by + dy, bz + dz);
                                if (onlyAir) {
                                    state = registry.isAirLike(state) ? registry.air() : current;
                                }
                                int slot = 0;
                                while (slot < distinct && states[slot] != state) {
                                    slot++;
                                }
                                if (slot == distinct) {
                                    states[distinct] = state;
                                    counts[distinct++] = 0;
                                }
                                counts[slot]++;
                            }
                        }
                    }
                    // Ties go to the state seen first, as the map's order used to
                    // decide them is not one anybody chose.
                    int best = current;
                    int bestCount = 0;
                    int currentCount = 0;
                    for (int slot = 0; slot < distinct; slot++) {
                        if (counts[slot] > bestCount && !registry.isAirLike(states[slot])) {
                            bestCount = counts[slot];
                            best = states[slot];
                        }
                        if (states[slot] == current) {
                            currentCount = counts[slot];
                        }
                    }
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

    /**
     * The plants {@code //flora} scatters, with the ground each one wants. The
     * names are the ones the block registry knows, so a plant the world does not
     * have is skipped rather than placed as something else.
     */
    private static final String[] PLANTS = {
        "short_grass", "tall_grass", "fern", "dandelion", "poppy", "cornflower", "oxeye_daisy",
        "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "allium",
        "brown_mushroom", "red_mushroom", "sweet_berry_bush",
    };

    /** The plants that grow on sand rather than on grass. */
    private static final String[] DESERT_PLANTS = {"dead_bush", "cactus"};

    /**
     * {@code //flora} — scatters plants over the surface of the region.
     *
     * <p>WorldEdit walks the region and, at the density it was given, puts the
     * vegetation of the biome on the surface it finds. Plants are the small ones:
     * grass, flowers, mushrooms and bushes. The surface a plant sits on decides
     * what may grow there, so the ones that want sand are only used on sand.</p>
     */
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
                if (y < region.getMinimumPoint().y() || y + 1 > region.getMaximumPoint().y()) {
                    continue;
                }
                if (!registry.isAirLike(world.getBlock(x, y + 1, z))) {
                    continue;
                }
                String ground = registry.name(world.getBlock(x, y, z));
                String[] choices = ground.contains("sand") ? DESERT_PLANTS : PLANTS;
                String plant = choices[random.nextInt(choices.length)];
                int state = plantState(plant);
                if (state < 0) {
                    continue;
                }
                if (session.setBlock(x, y + 1, z, state)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** The state of a plant, or -1 when the world's registry does not know it. */
    private static int plantState(String plant) {
        return BlockState.registry().defaultState(plant);
    }

    /**
     * {@code //forest} — plants trees over the region.
     *
     * <p>WorldEdit picks a column of the region at the density it was given, finds
     * the surface and plants the tree type it was asked for; a tree that does not
     * fit (a spot too small, a plant the world does not know) is skipped.</p>
     */
    public static int forest(World world, EditSession session, Region region, String treeType, double density) {
        Random random = new Random();
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
            for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                if (random.nextDouble() > density) {
                    continue;
                }
                int y = world.getHighestBlockY(x, z);
                if (y < region.getMinimumPoint().y() || y + 1 > region.getMaximumPoint().y()) {
                    continue;
                }
                if (registry.isAirLike(world.getBlock(x, y + 1, z))
                        && world.generateTree(new BlockVector3(x, y + 1, z), treeType, random)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** {@code //deltree} — removes a tree starting from its trunk. */
    public static int removeTree(World world, EditSession session, BlockVector3 start) {
        LongQueue queue = new LongQueue();
        LongSet visited = new LongSet();
        queue.add(BlockArrayClipboard.positionKey(start.x(), start.y(), start.z()));
        int changed = 0;
        int air = BlockState.registry().air();
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (!visited.add(current) || visited.size() > 20000) {
                continue;
            }
            int x = BlockArrayClipboard.keyX(current);
            int y = BlockArrayClipboard.keyY(current);
            int z = BlockArrayClipboard.keyZ(current);
            String name = BlockState.registry().name(world.getBlock(x, y, z));
            if (!name.contains("log") && !name.contains("leaves") && !name.contains("wood")) {
                continue;
            }
            if (session.setBlock(x, y, z, air)) {
                changed++;
            }
            for (com.maxlananas.fawebim.core.world.Direction direction : DIRECTIONS) {
                queue.add(BlockArrayClipboard.positionKey(x + direction.x(), y + direction.y(), z + direction.z()));
            }
        }
        return changed;
    }

    /**
     * FAWE's {@code addOre}: scatters veins of the pattern through the selection.
     *
     * <p>The deposits are seeded per chunk - {@code frequency} attempts each, of
     * which {@code rarity} percent run - and each one is a vein {@code size}
     * blocks long. That is {@code Extent.spawnResource} and {@code OreGen.spawn}
     * of the upstream sources, down to the sine the vein is bent with and the
     * larger tail it grows towards the end.</p>
     */
    public static int ore(World world, EditSession session, Region region, Mask mask, Pattern material,
                          int size, int frequency, int rarity, int minY, int maxY, boolean triangular,
                          OreDeepslate deepslate, Random random) {
        int changed = 0;
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        for (int chunkX = min.x() >> 4; chunkX <= max.x() >> 4; chunkX++) {
            for (int chunkZ = min.z() >> 4; chunkZ <= max.z() >> 4; chunkZ++) {
                for (int attempt = 0; attempt < frequency; attempt++) {
                    session.checkTimeout();
                    if (random.nextInt(100) > rarity) {
                        continue;
                    }
                    int x = (chunkX << 4) + random.nextInt(16);
                    int z = (chunkZ << 4) + random.nextInt(16);
                    changed += spawnVein(world, session, mask, material, size, minY, maxY, triangular,
                            deepslate, random, x, z);
                }
            }
        }
        return changed;
    }

    /** One deposit of {@link #ore}, upstream's {@code OreGen.spawn}. */
    private static int spawnVein(World world, EditSession session, Mask mask, Pattern material, int size,
                                 int minY, int maxY, boolean triangular, OreDeepslate deepslate,
                                 Random random, int x, int z) {
        int y;
        if (triangular) {
            // A triangular distribution puts most of the veins near the middle
            // of the band, which is how the vanilla ore bands are shaped.
            int range = maxY - minY;
            int mid = range / 2;
            y = minY + random.nextInt(Math.max(1, mid)) + random.nextInt(Math.max(1, range - mid));
        } else {
            y = minY + random.nextInt(Math.max(1, maxY - minY));
        }
        if (!mask.test(x, y, z)) {
            return 0;
        }
        double angle = random.nextDouble() * Math.PI;
        double eighth = size * 0.125;
        double sin = Math.sin(angle) * eighth;
        double cos = Math.cos(angle) * eighth;
        double d1 = x + sin;
        double d2 = x - sin;
        double d3 = z + cos;
        double d4 = z - cos;
        double d5 = y + random.nextInt(3) - 2;
        double d6 = y + random.nextInt(3) - 2;
        double xd = d2 - d1;
        double yd = d6 - d5;
        double zd = d4 - d3;
        double sixteenth = size * 0.0625;
        double sizeInverse = 1.0 / size;
        int changed = 0;
        double factor = 0;
        for (int i = 0; i < size; i++, factor += sizeInverse) {
            double centreX = d1 + xd * factor;
            double centreY = d5 + yd * factor;
            double centreZ = d3 + zd * factor;
            double radius = (Math.sin(Math.PI * factor) + 1.0) * (random.nextDouble() * sixteenth) + 1.0;
            double half = radius * 0.5;
            int fromX = floorZero(centreX - half);
            int fromY = Math.max(minY + 1, floorZero(centreY - half));
            int fromZ = floorZero(centreZ - half);
            int toX = floorZero(centreX + half);
            int toY = Math.min(maxY, floorZero(centreY + half));
            int toZ = floorZero(centreZ + half);
            // The inverse of the radius is taken once per slice, so the ellipse
            // test is three multiplications per block rather than a division.
            double inverse = 1.0 / half;
            for (int xx = fromX; xx <= toX; xx++) {
                double dx = (xx + 0.5 - centreX) * inverse;
                double dx2 = dx * dx;
                if (dx2 >= 1) {
                    continue;
                }
                for (int yy = fromY; yy <= toY; yy++) {
                    double dy = (yy + 0.5 - centreY) * inverse;
                    double dxy2 = dx2 + dy * dy;
                    if (dxy2 >= 1) {
                        continue;
                    }
                    for (int zz = fromZ; zz <= toZ; zz++) {
                        double dz = (zz + 0.5 - centreZ) * inverse;
                        if (dxy2 + dz * dz >= 1 || !mask.test(xx, yy, zz)) {
                            continue;
                        }
                        int state = material.apply(xx, yy, zz);
                        state = deepslateState(world, deepslate, state, xx, yy, zz);
                        if (session.setBlock(xx, yy, zz, state)) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** The state of a cell for {@code //ore}/{@code //ores}: the deepslate form where FAWE uses it. */
    private static int deepslateState(World world, OreDeepslate deepslate, int state, int x, int y, int z) {
        if (deepslate == OreDeepslate.NONE) {
            return state;
        }
        if (deepslate == OreDeepslate.BELOW_ZERO) {
            return y < 0 ? deepslateVariant(BlockState.registry(), state) : state;
        }
        String target = BlockState.registry().name(world.getBlock(x, y, z));
        if (!target.contains("deepslate") && !target.contains("tuff")) {
            return state;
        }
        return deepslateVariant(BlockState.registry(), state);
    }

    /** {@code MathMan.floorZero}: a floor that keeps zero where it is. */
    private static int floorZero(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /**
     * {@code //ores}: the vanilla shaped ore distribution, which is FAWE's
     * {@code addOres} - the same bands, vein sizes, frequencies and rarities.
     *
     * @param deepslate what happens to an ore that lands in the deepslate layers
     */
    public static int ores(World world, EditSession session, Region region, Mask mask, OreDeepslate deepslate,
                           Random random) {
        int changed = 0;
        int minY = world.minY();
        int maxY = world.maxY();
        // The stone band.
        changed += ore(world, session, region, mask, stone("minecraft:gravel"), 33, 14, 100, minY, maxY,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:andesite"), 33, 2, 100, 0, 60,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:andesite"), 33, 1, 17, 64, 128,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:diorite"), 33, 2, 100, 0, 60,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:diorite"), 33, 1, 17, 64, 128,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:granite"), 33, 2, 100, 0, 60,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:granite"), 33, 1, 17, 64, 128,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:tuff"), 33, 2, 100, minY, 0,
                false, deepslate, random);
        changed += ore(world, session, region, mask, stone("minecraft:dirt"), 33, 7, 100, 0, 160,
                false, deepslate, random);

        // Coal.
        changed += ore(world, session, region, mask, ore("coal_ore"), 17, 20, 100, 0, 192, true, deepslate, random);
        changed += ore(world, session, region, mask, ore("coal_ore"), 17, 30, 100, 136, maxY, false, deepslate, random);
        // Copper.
        changed += ore(world, session, region, mask, ore("copper_ore"), 13, 16, 100, -16, 112, true, deepslate, random);
        // Iron.
        changed += ore(world, session, region, mask, ore("iron_ore"), 9, 10, 100, minY, 72, false, deepslate, random);
        changed += ore(world, session, region, mask, ore("iron_ore"), 9, 10, 100, -24, 56, true, deepslate, random);
        changed += ore(world, session, region, mask, ore("iron_ore"), 9, 90, 100, 80, 384, true, deepslate, random);
        // Gold.
        changed += ore(world, session, region, mask, ore("gold_ore"), 9, 1, 50, -64, -48, false, deepslate, random);
        changed += ore(world, session, region, mask, ore("gold_ore"), 9, 4, 100, -64, 32, true, deepslate, random);
        // Redstone.
        changed += ore(world, session, region, mask, ore("redstone_ore"), 8, 8, 100, -32, 32, true, deepslate, random);
        changed += ore(world, session, region, mask, ore("redstone_ore"), 8, 4, 100, minY, 15, false, deepslate, random);
        // Diamond, in the four bands vanilla uses.
        int diamondMin = minY - 80;
        int diamondMax = minY + 80;
        changed += ore(world, session, region, mask, ore("diamond_ore"), 5, 7, 100, diamondMin, diamondMax,
                true, deepslate, random);
        changed += ore(world, session, region, mask, ore("diamond_ore"), 8, 2, 100, -64, -4, false, deepslate, random);
        changed += ore(world, session, region, mask, ore("diamond_ore"), 23, 1, 11, diamondMin, diamondMax,
                true, deepslate, random);
        changed += ore(world, session, region, mask, ore("diamond_ore"), 10, 4, 100, diamondMin, diamondMax,
                true, deepslate, random);
        // Lapis and emerald.
        changed += ore(world, session, region, mask, ore("lapis_ore"), 7, 2, 100, -32, 32, true, deepslate, random);
        changed += ore(world, session, region, mask, ore("lapis_ore"), 7, 4, 100, minY, 64, false, deepslate, random);
        changed += ore(world, session, region, mask, ore("emerald_ore"), 5, 100, 100, -16, 480,
                true, deepslate, random);
        return changed;
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

    /** A pattern of one block, for the ore bands. */
    private static Pattern stone(String name) {
        int state = BlockState.registry().defaultState(name);
        return new FixedPattern(state);
    }

    /** An ore pattern, which is the same state with a deepslate form beside it. */
    private static Pattern ore(String name) {
        return stone("minecraft:" + name);
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
     * FAWE's {@code EditSession.makeBlob}: a sphere of the given size whose
     * surface is pushed in and out by simplex noise, which is its "distorted
     * sphere" generator.
     *
     * <p>At full sphericity the noise scales the whole radius; below it the
     * radius is a blend of the round distance and a squashed box distance, so
     * the blob may come out lumpy and angular. Above the size the caller reads
     * the radii as the size of the box the blob has to fit in.</p>
     */
    public static int makeBlob(World world, EditSession session, BlockVector3 position,
                               Pattern pattern, double size, double frequency, double amplitude,
                               Vector3 radius, double sphericity) {
        Random random = new Random();
        double seedX = random.nextDouble();
        double seedY = random.nextDouble();
        double seedZ = random.nextDouble();
        int px = position.x();
        int py = position.y();
        int pz = position.z();
        double distort = frequency / size;
        double modX = 1d / radius.x();
        double modY = 1d / radius.y();
        double modZ = 1d / radius.z();
        int r = (int) size;
        int radiusSqr = (int) (size * size);
        int sizeInt = (int) size * 2;
        // Upstream reads a shared simplex noise, so the shape only depends on
        // the size, the frequency and the amplitude the caller passed.
        Noise noiseGen = new Noise.Simplex(0);
        int changed = 0;
        if (sphericity == 1) {
            for (int x = -sizeInt; x <= sizeInt; x++) {
                double nx = seedX + x * distort;
                double d1 = x * x * modX;
                int xx = px + x;
                for (int y = -sizeInt; y <= sizeInt; y++) {
                    double d2 = d1 + y * y * modY;
                    double ny = seedY + y * distort;
                    int yy = py + y;
                    for (int z = -sizeInt; z <= sizeInt; z++) {
                        double nz = seedZ + z * distort;
                        double distance = d2 + z * z * modZ;
                        double noise = amplitude * noiseGen.noise(nx, ny, nz);
                        int zz = pz + z;
                        if (distance + distance * noise < radiusSqr
                                && session.setBlock(xx, yy, zz, pattern.apply(
                                        new BlockVector3(xx, yy, zz)))) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
        // FAWE turns the sample point with three random rotations before it
        // measures it, which is what makes the low-sphericity blob asymmetric.
        Transform spin = Transforms.rotate(BlockVector3.ZERO, Axis.X, random.nextInt(360))
                .combine(Transforms.rotate(BlockVector3.ZERO, Axis.Y, random.nextInt(360)))
                .combine(Transforms.rotate(BlockVector3.ZERO, Axis.Z, random.nextInt(360)));
        double manScaleX = 1.25 + seedX * 0.5;
        double manScaleY = 1.25 + seedY * 0.5;
        double manScaleZ = 1.25 + seedZ * 0.5;
        double roughness = 1 - sphericity;
        for (int xr = -sizeInt; xr <= sizeInt; xr++) {
            int xx = px + xr;
            for (int yr = -sizeInt; yr <= sizeInt; yr++) {
                int yy = py + yr;
                for (int zr = -sizeInt; zr <= sizeInt; zr++) {
                    int zz = pz + zr;
                    Vector3 point = spin.apply(new Vector3(xr, yr, zr));
                    int x = (int) Math.round(point.x());
                    int y = (int) Math.round(point.y());
                    int z = (int) Math.round(point.z());
                    double xScaled = Math.abs(x) * modX;
                    double yScaled = Math.abs(y) * modY;
                    double zScaled = Math.abs(z) * modZ;
                    double manDist = xScaled + yScaled + zScaled;
                    double distSqr = x * x * modX + z * z * modZ + y * y * modY;
                    double distance = Math.sqrt(distSqr) * sphericity
                            + Math.max(manDist, Math.max(xScaled * manScaleX,
                                    Math.max(yScaled * manScaleY, zScaled * manScaleZ))) * roughness;
                    double noise = amplitude * noiseGen.noise(seedX + x * distort,
                            seedZ + z * distort, seedZ + z * distort);
                    if (distance + distance * noise < r
                            && session.setBlock(xx, yy, zz, pattern.apply(new BlockVector3(xx, yy, zz)))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** {@code //fall} — drops every block in the region to the ground. */
    public static long fall(World world, EditSession session, Region region) {
        return fall(world, session, region, false, null);
    }

    /**
     * WorldEdit's {@code EditSession.fall}: every block of the selection drops
     * onto the first block below it, and the cell it left takes the replace
     * block - air unless the line named another one.
     *
     * @param withinSelection {@code -m}: stop at the floor of the selection
     *                        rather than the floor of the world
     * @param replace         the block left behind, or {@code null} for air
     */
    public static long fall(World world, EditSession session, Region region, boolean withinSelection,
                            int[] replace) {
        BlockStateRegistry registry = BlockState.registry();
        int left = replace == null ? registry.air() : replace[0];
        int floor = withinSelection ? region.getMinimumPoint().y() : world.minY();
        return region.forEachPosition((x, y, z) -> {
            int state = world.getBlock(x, y, z);
            if (registry.isAirLike(state)) {
                return false;
            }
            int landing = y;
            while (landing > floor && registry.isAirLike(world.getBlock(x, landing - 1, z))) {
                landing--;
            }
            if (landing == y) {
                return false;
            }
            session.setBlock(x, y, z, left);
            return session.setBlock(x, landing, z, state);
        });
    }

    /**
     * WorldEdit's {@code EditSession.fillXZ}: fills the air of a sphere around
     * the placement, down to {@code depth} blocks, walking from the origin
     * downwards so a cell is only written once.
     */
    public static int fillXz(World world, EditSession session, BlockVector3 origin, Pattern pattern,
                             double radius, int depth) {
        BlockStateRegistry registry = BlockState.registry();
        int fromY = Math.max(world.minY(), origin.y() - depth + 1);
        int toY = Math.min(world.maxY(), origin.y());
        int spread = (int) Math.ceil(radius);
        int radiusSq = (int) (radius * radius);
        int changed = 0;
        for (int y = toY; y >= fromY; y--) {
            session.checkTimeout();
            int dy = y - origin.y();
            int dy2 = dy * dy;
            for (int x = origin.x() - spread; x <= origin.x() + spread; x++) {
                int dx = x - origin.x();
                int dx2 = dx * dx;
                if (dx2 + dy2 > radiusSq) {
                    continue;
                }
                for (int z = origin.z() - spread; z <= origin.z() + spread; z++) {
                    int dz = z - origin.z();
                    if (dx2 + dy2 + dz * dz > radiusSq) {
                        continue;
                    }
                    if (!registry.isAirLike(world.getBlock(x, y, z))) {
                        continue;
                    }
                    if (session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * FAWE's {@code EditSession.fillDirection}: the same fill, moved along the
     * direction it was given. Downwards is the common case and takes the walk
     * above; any other direction follows the sphere from the origin outwards.
     */
    public static int fillDirection(World world, EditSession session, BlockVector3 origin, Pattern pattern,
                                    double radius, int depth, BlockVector3 direction) {
        if (direction.y() < 0 && direction.x() == 0 && direction.z() == 0) {
            return fillXz(world, session, origin, pattern, radius, depth);
        }
        BlockStateRegistry registry = BlockState.registry();
        int spread = (int) Math.ceil(radius);
        int radiusSq = (int) (radius * radius);
        int changed = 0;
        for (int step = 0; step < Math.max(1, (int) (radius * 2 + 1)); step++) {
            session.checkTimeout();
            BlockVector3 centre = origin.add(direction.multiply(step));
            for (int x = centre.x() - spread; x <= centre.x() + spread; x++) {
                int dx = x - origin.x();
                int dx2 = dx * dx;
                if (dx2 > radiusSq) {
                    continue;
                }
                for (int y = centre.y() - spread; y <= centre.y() + spread; y++) {
                    int dy = y - origin.y();
                    int dxy2 = dx2 + dy * dy;
                    if (dxy2 > radiusSq) {
                        continue;
                    }
                    for (int z = centre.z() - spread; z <= centre.z() + spread; z++) {
                        int dz = z - origin.z();
                        if (dxy2 + dz * dz > radiusSq) {
                            continue;
                        }
                        if (!registry.isAirLike(world.getBlock(x, y, z))) {
                            continue;
                        }
                        if (session.setBlock(x, y, z, pattern.apply(x, y, z))) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /**
     * WorldEdit's {@code EditSession.makeCone}: a cone of the two radii and the
     * height, hollow when asked, with a shell of the given thickness.
     */
    public static int cone(EditSession session, BlockVector3 origin, Pattern pattern, double radiusX,
                           double radiusZ, int height, boolean filled, double thickness) {
        double radiusXPow = radiusX * radiusX;
        double radiusZPow = radiusZ * radiusZ;
        double heightPow = (double) height * height;
        int layers = Math.abs(height);
        int ceilX = (int) Math.ceil(radiusX);
        int ceilZ = (int) Math.ceil(radiusZ);
        int changed = 0;
        for (int y = 0; y < layers; y++) {
            double yTerm = (double) (y - layers) * (y - layers) / heightPow;
            for (int x = 0; x <= ceilX; x++) {
                double xTerm = (double) x * x / radiusXPow;
                for (int z = 0; z <= ceilZ; z++) {
                    double zTerm = (double) z * z / radiusZPow;
                    double distance = xTerm + zTerm - yTerm;
                    if (distance > 1) {
                        if (z == 0) {
                            break;
                        }
                        continue;
                    }
                    if (!filled) {
                        double xNext = (double) (x + thickness) * (x + thickness) / radiusXPow + zTerm - yTerm;
                        double yNext = xTerm + zTerm
                                - (double) (y + (int) thickness - layers) * (y + (int) thickness - layers) / heightPow;
                        double zNext = xTerm + (double) (z + thickness) * (z + thickness) / radiusZPow - yTerm;
                        if (xNext <= 0 && zNext <= 0 && yNext <= 0 && y + thickness != layers) {
                            continue;
                        }
                    }
                    if (distance > 0) {
                        continue;
                    }
                    int yOffset = height < 0 ? -y : y;
                    // The four quadrants of the layer, with the axes written once.
                    changed += place(session, pattern, origin.x() + x, origin.y() + yOffset, origin.z() + z);
                    changed += place(session, pattern, origin.x() - x, origin.y() + yOffset, origin.z() + z);
                    changed += place(session, pattern, origin.x() + x, origin.y() + yOffset, origin.z() - z);
                    changed += place(session, pattern, origin.x() - x, origin.y() + yOffset, origin.z() - z);
                }
            }
        }
        return changed;
    }

    /** Writes one cell of a generator, counting the ones that really changed. */
    private static int place(EditSession session, Pattern pattern, int x, int y, int z) {
        return session.setBlock(x, y, z, pattern.apply(x, y, z)) ? 1 : 0;
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
    public static long drainWaterlogged(EditSession session, Region region) {
        BlockStateRegistry registry = BlockState.registry();
        return region.forEachPosition((x, y, z) -> {
            int state = session.getBlock(x, y, z);
            if (state == registry.air()) {
                return false;
            }
            Map<String, String> properties = registry.properties(state);
            if (!"true".equals(properties.get("waterlogged"))) {
                return false;
            }
            int cleared = registry.withProperty(state, "waterlogged", "false");
            return cleared >= 0 && session.setBlock(x, y, z, cleared);
        });
    }

    /** A pattern that always returns the same state. */
    private record FixedPattern(int state) implements Pattern {

        @Override
        public int apply(int x, int y, int z) {
            return state;
        }
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
