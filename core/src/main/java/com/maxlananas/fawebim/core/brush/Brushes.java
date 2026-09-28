package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.function.HeightMaps;
import com.maxlananas.fawebim.core.function.Morphology;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.pattern.MapColors;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.util.LongQueue;
import com.maxlananas.fawebim.core.util.LongSet;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.World;

import java.util.List;
import java.util.Random;


/**
 * Every brush FAWE ships. These are direct ports of the upstream behaviour:
 * same names, same settings and the same block results.
 */
public final class Brushes {

    /**
     * Applies a brush, honouring a source mask the tool was given with
     * {@code /tool smask -h}: that mask applies while this tool runs and the
     * session's own mask is put back afterwards.
     */
    public static int apply(Brush brush, com.maxlananas.fawebim.core.extent.EditSession session,
                            com.maxlananas.fawebim.core.math.BlockVector3 position,
                            com.maxlananas.fawebim.core.actor.Actor actor) {
        // /tool transform: what the brush places is transformed around where it hit.
        Transform transform = brush.settings().getTransform();
        if (transform != null && !transform.isIdentity()) {
            session.setTransform(transform, position);
        }
        com.maxlananas.fawebim.core.mask.Mask own = brush.settings().getSourceMask();
        if (own == null) {
            return brush.apply(session, position, actor);
        }
        com.maxlananas.fawebim.core.session.LocalSession local = session.getSession();
        com.maxlananas.fawebim.core.mask.Mask previous = local.getSourceMask();
        local.setSourceMask(own);
        try {
            return brush.apply(session, position, actor);
        } finally {
            local.setSourceMask(previous);
        }
    }

    private Brushes() {
    }

    /** Shared base: holds radius, fill, mask and the settings object. */
    public abstract static class BaseBrush implements Brush {

        protected double radius;
        protected Pattern fill;
        protected Mask mask;
        protected boolean hollow;
        protected final BrushSettings settings = new BrushSettings();
        protected final Random random = new Random();

        protected BaseBrush(double radius, Pattern fill, Mask mask) {
            this.radius = radius;
            this.fill = fill;
            this.mask = mask;
            this.settings.setFill(fill);
            this.settings.setSize((int) Math.round(radius));
        }

        @Override
        public double radius() {
            return radius;
        }

        @Override
        public void setRadius(double radius) {
            this.radius = radius;
            this.settings.setSize((int) Math.round(radius));
        }

        @Override
        public void setFill(Pattern fill) {
            this.fill = fill;
            this.settings.setFill(fill);
        }

        @Override
        public Mask mask() {
            return mask;
        }

        @Override
        public void setMask(Mask mask) {
            this.mask = mask;
        }

        @Override
        public boolean hollow() {
            return hollow;
        }

        @Override
        public void setHollow(boolean hollow) {
            this.hollow = hollow;
            settings.setHollow(hollow);
        }

        @Override
        public BrushSettings settings() {
            return settings;
        }

        protected boolean test(int x, int y, int z) {
            return mask == null || mask.test(x, y, z);
        }

        protected boolean place(EditSession session, int x, int y, int z) {
            if (!test(x, y, z)) {
                return false;
            }
            int state = fill == null ? BlockState.registry().air() : fill.apply(x, y, z);
            return session.setBlock(x, y, z, state);
        }

        /** The region a shape covers around the click, as FAWE's region factories build it. */
        protected static com.maxlananas.fawebim.core.region.Region region(String shape, EditSession session,
                                                                         BlockVector3 position, double radius) {
            return com.maxlananas.fawebim.core.region.RegionFactories.parse(shape, session.minY(), session.maxY())
                    .createCenteredAt(position, radius);
        }

        /**
         * Runs an operation that writes through the edit - a forest, a deform -
         * with the brush's mask added to the edit's for its duration, which is
         * how FAWE's brush tool masks every brush.
         */
        protected int masked(EditSession session, java.util.function.IntSupplier operation) {
            Mask previous = session.getMask();
            if (mask == null || mask == previous) {
                return operation.getAsInt();
            }
            session.setMask(previous == null ? mask : new Masks.IntersectionMask(List.of(previous, mask)));
            try {
                return operation.getAsInt();
            } finally {
                session.setMask(previous);
            }
        }

        @Override
        public String describe() {
            return "radius=" + radius + (fill != null ? " fill=" + fill.getClass().getSimpleName() : "");
        }
    }

    /**
     * {@code /brush sphere <pattern> [radius]}: WorldEdit's sphere, the one
     * {@code //sphere} builds, whose radius grows by half a block before it is
     * measured.
     */
    public static class SphereBrush extends BaseBrush {

        public SphereBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.forEachInEllipsoid(position, new double[]{radius, radius, radius}, hollow,
                    session.minY(), session.maxY(), (x, y, z) -> place(session, x, y, z));
        }
    }

    /**
     * {@code /brush set <shape> [radius] <pattern>}: the pattern in the shape the
     * command names, built around the clicked block the way FAWE's region
     * factories build it - a sphere, a disc one block high, a cube.
     */
    public static final class ShapeBrush extends BaseBrush {

        private final String shape;

        public ShapeBrush(double radius, Pattern fill, Mask mask, String shape) {
            super(radius, fill, mask);
            this.shape = shape;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return (int) region(shape, session, position, radius).forEachPosition((x, y, z) -> place(session, x, y, z));
        }

        @Override
        public String describe() {
            return "shape=" + shape + " " + super.describe();
        }
    }

    /**
     * {@code /brush sphere -f}: the same sphere, but every column is filled down
     * to the terrain below it, so the blocks land instead of floating.
     */
    public static final class FallingSphereBrush extends SphereBrush {

        public FallingSphereBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int size = (int) radius;
            for (int z = -size; z <= size; z++) {
                for (int x = -size; x <= size; x++) {
                    int remaining = size * size - z * z - x * x;
                    if (remaining < 0) {
                        continue;
                    }
                    int yRadius = (int) Math.sqrt(remaining);
                    int columnX = position.x() + x;
                    int columnZ = position.z() + z;
                    int startY = Math.max(session.getWorld().minY(), position.y() - yRadius);
                    int endY = Math.min(session.getWorld().maxY(), position.y() + yRadius);
                    int floorY = session.getWorld().getHighestBlockY(columnX, columnZ);
                    // The sphere drops until its lowest block rests on the ground.
                    if (floorY < startY) {
                        int drop = startY - floorY;
                        startY -= drop;
                        endY -= drop;
                    }
                    for (int y = startY; y <= Math.max(floorY, endY); y++) {
                        if (place(session, columnX, y, columnZ)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush cylinder <pattern> <radius> [height] [-h [thickness]]}. */
    public static final class CylinderBrush extends BaseBrush {

        private final int height;
        private final double thickness;

        public CylinderBrush(double radius, Pattern fill, Mask mask) {
            this(radius, fill, mask, (int) (radius * 2));
        }

        public CylinderBrush(double radius, Pattern fill, Mask mask, int height) {
            this(radius, fill, mask, height, 0);
        }

        public CylinderBrush(double radius, Pattern fill, Mask mask, int height, double thickness) {
            super(radius, fill, mask);
            this.height = height;
            this.thickness = thickness;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.cylinder(session, position, (int) radius, height, fill, hollow, thickness);
        }
    }

    /** {@code /brush smooth [radius] [cycles]}. */
    public static final class SmoothBrush extends BaseBrush {

        private int iterations = 4;

        public SmoothBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        /** How many smoothing passes the brush runs on every click. */
        public void setIterations(int iterations) {
            this.iterations = Math.max(1, iterations);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            // FAWE samples a box around the click that reaches ten blocks up, so
            // a hill inside the reach of the brush is smoothed, not just its foot.
            int size = (int) radius;
            CuboidRegion region = new CuboidRegion(
                    BlockVector3.at(position.x() - size, position.y() - size, position.z() - size),
                    BlockVector3.at(position.x() + size, position.y() + size + 10, position.z() + size));
            return HeightMaps.smooth(session.getWorld(), session, region, iterations, mask);
        }
    }

    /** {@code /brush blendball [radius] [minFreqDiff] [-a] [-m <mask>]}. */
    public static final class BlendBallBrush extends BaseBrush {

        private final boolean onlyAir;
        private final int minFreqDiff;
        private final Mask limit;

        public BlendBallBrush(double radius, Mask mask) {
            this(radius, mask, false, 1, null);
        }

        public BlendBallBrush(double radius, Mask mask, boolean onlyAir, int minFreqDiff, Mask limit) {
            super(radius, null, mask);
            this.onlyAir = onlyAir;
            this.minFreqDiff = minFreqDiff;
            this.limit = limit;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            // The two masks do different things: -m decides which blocks take
            // part in the blend, the brush's mask only which may be written.
            return Operations.blendBall(session, position, radius, mask, limit, onlyAir, minFreqDiff);
        }
    }

    /** {@code /brush flatten [radius] [height]}. */
    public static final class FlattenBrush extends BaseBrush {

        private final int height;

        public FlattenBrush(double radius, Pattern fill, Mask mask) {
            this(radius, fill, mask, -1);
        }

        public FlattenBrush(double radius, Pattern fill, Mask mask, int height) {
            super(radius, fill, mask);
            this.height = height;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int targetY = height >= 0 ? height : position.y();
                    int highest = session.getWorld().getHighestBlockY(position.x() + x, position.z() + z);
                    if (highest > targetY) {
                        for (int y = targetY + 1; y <= highest; y++) {
                            if (session.setBlock(position.x() + x, y, position.z() + z, registry.air())) {
                                changed++;
                            }
                        }
                    } else if (highest < targetY) {
                        for (int y = highest + 1; y <= targetY; y++) {
                            if (place(session, position.x() + x, y, position.z() + z)) {
                                changed++;
                            }
                        }
                    }
                }
            }
            return changed;
        }
    }

    /**
     * {@code /brush height}, {@code /brush cliff} and {@code /brush flatten}: the
     * three brushes FAWE builds from one terrain shape.
     *
     * <p>FAWE drives them from a height map whose value is scaled by the brush
     * size: a cone for the height and flatten brushes, which raises a rounded
     * hill, and a flat cylinder for the cliff brush, which raises a plateau - the
     * sharp edge. The terrain is moved towards the target height, so the brush
     * both fills and clears.</p>
     *
     * <p>An image replaces the shape with the heights of the image, rotated by
     * the {@code rotation} argument and by a random quarter turn per click when
     * {@code -r} is given. {@code -l} moves the snow layers along with the
     * terrain and the smoothing pass can be turned off with {@code -s}.</p>
     */
    public static final class HeightmapBrush extends BaseBrush {

        private final boolean cylinder;
        private final double yScale;
        private boolean flatten;
        private com.maxlananas.fawebim.core.util.Images.PixelSource image;
        private int rotation;
        private boolean randomRotation;
        private boolean layers;
        private boolean smooth = true;

        public HeightmapBrush(double radius, Pattern fill, Mask mask, boolean cylinder, double yScale) {
            super(radius, fill, mask);
            this.cylinder = cylinder;
            this.yScale = yScale;
        }

        /** Flatten towards the clicked point instead of raising a shape. */
        public void setFlatten(boolean flatten) {
            this.flatten = flatten;
        }

        public void setImage(com.maxlananas.fawebim.core.util.Images.PixelSource image) {
            this.image = image;
        }

        public void setRotation(int rotation) {
            this.rotation = Math.floorMod(rotation, 360);
        }

        public void setRandomRotation(boolean randomRotation) {
            this.randomRotation = randomRotation;
        }

        public void setLayers(boolean layers) {
            this.layers = layers;
        }

        public void setSmooth(boolean smooth) {
            this.smooth = smooth;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int r = (int) Math.ceil(radius);
            int span = 2 * r + 1;
            int[] targets = new int[span * span];
            java.util.Arrays.fill(targets, Integer.MIN_VALUE);
            int turn = randomRotation ? random.nextInt(4) : Math.floorMod(rotation / 90, 4);
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    double dx = x - r;
                    double dz = z - r;
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    if (distance > radius) {
                        continue;
                    }
                    targets[z * span + x] = position.y() + heightAt(dx, dz, distance, r, turn);
                }
            }
            if (smooth) {
                targets = smoothed(targets, span, r);
            }
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    int target = targets[z * span + x];
                    if (target == Integer.MIN_VALUE) {
                        continue;
                    }
                    int columnX = position.x() - r + x;
                    int columnZ = position.z() - r + z;
                    changed += column(session, registry, columnX, columnZ, target);
                }
            }
            return changed;
        }

        /** The height of one column of the shape, relative to the click. */
        private int heightAt(double dx, double dz, double distance, int r, int turn) {
            if (image != null) {
                double rotatedX = dx;
                double rotatedZ = dz;
                switch (turn) {
                    case 1 -> {
                        rotatedX = -dz;
                        rotatedZ = dx;
                    }
                    case 2 -> {
                        rotatedX = -dx;
                        rotatedZ = -dz;
                    }
                    case 3 -> {
                        rotatedX = dz;
                        rotatedZ = -dx;
                    }
                    default -> {
                    }
                }
                int u = (int) Math.floor((rotatedX + r) / (2.0 * r + 1) * Math.max(1, image.width() - 1));
                int v = (int) Math.floor((rotatedZ + r) / (2.0 * r + 1) * Math.max(1, image.height() - 1));
                return (int) Math.round(image.luminance(u, v) / 255.0 * radius * yScale);
            }
            if (flatten) {
                return 0;
            }
            double profile = cylinder ? 1 : Math.sqrt(Math.max(0, 1 - (distance / radius) * (distance / radius)));
            return (int) Math.round(profile * radius * yScale);
        }

        /** Averages every column height with its neighbours, the way FAWE smooths. */
        private int[] smoothed(int[] targets, int span, int r) {
            int[] result = targets.clone();
            for (int z = 1; z < span - 1; z++) {
                for (int x = 1; x < span - 1; x++) {
                    if (targets[z * span + x] == Integer.MIN_VALUE) {
                        continue;
                    }
                    int sum = 0;
                    int count = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int neighbour = targets[(z + dz) * span + (x + dx)];
                            if (neighbour == Integer.MIN_VALUE) {
                                continue;
                            }
                            sum += neighbour;
                            count++;
                        }
                    }
                    result[z * span + x] = Math.round((float) sum / count);
                }
            }
            return result;
        }

        /** Raises or lowers one column to the target height. */
        private int column(EditSession session, BlockStateRegistry registry, int x, int z, int target) {
            int changed = 0;
            int highest = session.getWorld().getHighestBlockY(x, z);
            for (int y = highest; y > target; y--) {
                int state = session.getBlock(x, y, z);
                if (registry.isAirLike(state)) {
                    continue;
                }
                if (!layers && isSnowLayer(registry, state)) {
                    // Without -l the snow lying on the ground stays where it is.
                    break;
                }
                if (session.setBlock(x, y, z, registry.air())) {
                    changed++;
                }
            }
            if (target <= highest) {
                return changed;
            }
            if (fill != null) {
                for (int y = target; y > highest; y--) {
                    if (place(session, x, y, z)) {
                        changed++;
                    }
                }
                return changed;
            }
            // Without a pattern the column grows as WorldEdit's heightmap grows
            // it: its top block goes up to the new height and the blocks under
            // it follow, so grass stays on dirt over stone. It placed air.
            int top = session.getBlock(x, highest, z);
            if (!registry.isSolid(top)) {
                return changed;
            }
            int carried = registry.air();
            for (int setY = target - 1, getY = highest - 1; setY >= highest; setY--, getY--) {
                int below = getY >= session.minY() ? session.getBlock(x, getY, z) : registry.air();
                if (!registry.isAirLike(below)) {
                    carried = below;
                }
                if (test(x, setY, z) && session.setBlock(x, setY, z, carried)) {
                    changed++;
                }
            }
            if (test(x, target, z) && session.setBlock(x, target, z, top)) {
                changed++;
            }
            return changed;
        }

        private boolean isSnowLayer(BlockStateRegistry registry, int state) {
            return registry.describe(state).startsWith("minecraft:snow[");
        }
    }

    /**
     * {@code /brush layer <radius> <patterns>}: FAWE's layered skin. The solid
     * blocks touching air that connect to the clicked one, diagonals included,
     * within the radius, take the first entry of the list; each entry after it
     * goes one block further in, where the layer before came straight in and
     * never next to air.
     *
     * <p>As in FAWE, each entry is the block its pattern gives at the origin,
     * taken once when the brush is bound.</p>
     */
    public static final class LayerBrush extends BaseBrush {

        /** The faces of a block, in the order FAWE's search walks them. */
        private static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};

        private final int[] layers;

        public LayerBrush(double radius, int[] layers, Mask mask) {
            super(radius, null, mask);
            this.layers = layers.clone();
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (layers.length == 0) {
                return 0;
            }
            BlockStateRegistry registry = BlockState.registry();
            long reach = (long) (int) radius * (int) radius;
            // The surface, connected to the click through all 26 neighbours.
            LongSet visited = new LongSet();
            LongQueue queue = new LongQueue();
            long start = BlockArrayClipboard.positionKey(position.x(), position.y(), position.z());
            visited.add(start);
            queue.add(start);
            java.util.List<Long> surface = new java.util.ArrayList<>();
            while (!queue.isEmpty()) {
                long current = queue.poll();
                surface.add(current);
                int x = BlockArrayClipboard.keyX(current);
                int y = BlockArrayClipboard.keyY(current);
                int z = BlockArrayClipboard.keyZ(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            int nz = z + dz;
                            if ((dx | dy | dz) == 0 || ny < session.minY() || ny > session.maxY()) {
                                continue;
                            }
                            long next = BlockArrayClipboard.positionKey(nx, ny, nz);
                            long ox = nx - position.x();
                            long oy = ny - position.y();
                            long oz = nz - position.z();
                            if (!visited.contains(next) && ox * ox + oy * oy + oz * oz <= reach
                                    && registry.isSolid(session.getBlock(nx, ny, nz))
                                    && touchesAir(session, registry, nx, ny, nz)) {
                                visited.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            // The layers, one step in per entry of the list.
            int changed = 0;
            java.util.List<Long> current = surface;
            for (int depth = 0; depth < layers.length && !current.isEmpty(); depth++) {
                java.util.List<Long> inner = new java.util.ArrayList<>();
                for (long key : current) {
                    int x = BlockArrayClipboard.keyX(key);
                    int y = BlockArrayClipboard.keyY(key);
                    int z = BlockArrayClipboard.keyZ(key);
                    if (test(x, y, z) && session.setBlock(x, y, z, layers[depth])) {
                        changed++;
                    }
                    if (depth + 1 == layers.length) {
                        continue;
                    }
                    for (int[] face : FACES) {
                        int nx = x + face[0];
                        int ny = y + face[1];
                        int nz = z + face[2];
                        if (ny < session.minY() || ny > session.maxY()) {
                            continue;
                        }
                        long next = BlockArrayClipboard.positionKey(nx, ny, nz);
                        if (!visited.contains(next) && goesIn(session, registry, visited, nx, ny, nz, depth + 1)) {
                            visited.add(next);
                            inner.add(next);
                        }
                    }
                }
                current = inner;
            }
            return changed;
        }

        /**
         * FAWE's layer mask: a cell takes a layer past the second when the cell
         * next to it holds the layer before and the one past that the layer
         * before that, along the first face whose neighbour holds it; and it
         * never touches air.
         */
        private boolean goesIn(EditSession session, BlockStateRegistry registry, LongSet visited,
                               int x, int y, int z, int depth) {
            if (depth > 1) {
                boolean found = false;
                for (int[] face : FACES) {
                    int ax = x + face[0];
                    int ay = y + face[1];
                    int az = z + face[2];
                    if (visited.contains(BlockArrayClipboard.positionKey(ax, ay, az))
                            && session.getBlock(ax, ay, az) == layers[depth - 1]) {
                        int bx = ax + face[0];
                        int by = ay + face[1];
                        int bz = az + face[2];
                        if (visited.contains(BlockArrayClipboard.positionKey(bx, by, bz))
                                && session.getBlock(bx, by, bz) == layers[depth - 2]) {
                            found = true;
                            break;
                        }
                        return false;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return !touchesAir(session, registry, x, y, z);
        }

        private static boolean touchesAir(EditSession session, BlockStateRegistry registry, int x, int y, int z) {
            for (int[] face : FACES) {
                if (registry.isAirLike(session.getBlock(x + face[0], y + face[1], z + face[2]))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return "radius=" + radius + " layers=" + layers.length;
        }
    }

    /** {@code /brush surface}. */
    public static final class SurfaceBrush extends BaseBrush {

        public SurfaceBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /**
         * FAWE's: paints the surface around the click, see
         * {@link Operations#surfaceSphere}. It laid the pattern on top of the
         * highest block of every column of a disc - the treetops, the roofs -
         * and could paint no wall.
         */
        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.surfaceSphere(session, position, radius, fill, mask);
        }
    }

    /** {@code /brush line [pattern] [radius] [thickness]} — needs pos1/pos2. */
    public static final class LineBrush extends BaseBrush {

        private BlockVector3 start;
        private boolean shell;
        private boolean select;
        private boolean flat;

        public LineBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        public void setStart(BlockVector3 start) {
            this.start = start;
        }

        public void setShell(boolean shell) {
            this.shell = shell;
        }

        public void setSelect(boolean select) {
            this.select = select;
        }

        public void setFlat(boolean flat) {
            this.flat = flat;
        }

        /**
         * FAWE's: the first click marks where the line starts, the second draws
         * it there, of the brush's radius - a ball around each block, or with
         * {@code -f} a disc at its height - solid unless {@code -h}, and with
         * {@code -s} the second click starts the next line. What each click did
         * is said over the hotbar, not in the chat.
         */
        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (start == null) {
                start = position;
                actor.status(Msg.info("Added point " + position + ", click another position to create the line"));
                return 0;
            }
            int changed = Operations.drawLine(session, List.of(start, position), radius, !shell, flat, fill);
            actor.status(Msg.info("Created the line"));
            start = select ? position : null;
            return changed;
        }
    }

    /** {@code /brush spline} and {@code /brush surfacespline}. */
    public static final class SplineBrush extends BaseBrush {

        private final boolean surface;
        private final java.util.List<BlockVector3> points = new java.util.ArrayList<>();

        public SplineBrush(double radius, Pattern fill, Mask mask, boolean surface) {
            super(radius, fill, mask);
            this.surface = surface;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockVector3 target = position;
            if (surface) {
                target = new BlockVector3(position.x(),
                        session.getWorld().getHighestBlockY(position.x(), position.z()), position.z());
            }
            points.add(target);
            if (points.size() < 2) {
                return 0;
            }
            int changed = Operations.drawSpline(session, List.of(points.get(points.size() - 2), target), 0, 0, 0,
                    10, radius, true, fill);
            if (points.size() > 64) {
                points.remove(0);
            }
            return changed;
        }
    }

    /** {@code /brush catenary} — a rope with sag between two points. */
    public static final class CatenaryBrush extends BaseBrush {

        private double lengthFactor = 1.2;
        private boolean shell;
        private boolean select;
        private boolean facingDirection;
        private BlockVector3 start;
        private BlockVector3 end;
        private BlockVector3 vertex;

        public CatenaryBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        public void setLengthFactor(double lengthFactor) {
            this.lengthFactor = lengthFactor;
        }

        public void setShell(boolean shell) {
            this.shell = shell;
        }

        public void setSelect(boolean select) {
            this.select = select;
        }

        public void setFacingDirection(boolean facingDirection) {
            this.facingDirection = facingDirection;
        }

        /**
         * FAWE's: the first click marks one end, the second hangs a wire of
         * {@code lengthFactor} times the distance from there - a spline through
         * the ends and the lowest point of the catenary between them, of the
         * brush's radius, hollow with {@code -h}. With {@code -d} a third click
         * turns the sag towards where the player looks. With {@code -s} the end
         * of a wire starts the next. It hung a sine from the corner of the
         * selection, as deep as the line was long times the factor.
         */
        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (start == null || position.equals(start)) {
                start = position;
                vertex = null;
                actor.status(Msg.info("Added point " + position + ", click another position to create the line"));
                return 0;
            }
            if (vertex == null) {
                end = position;
                vertex = Operations.catenaryVertex(start, end, lengthFactor);
                if (facingDirection) {
                    actor.status(Msg.info("Added point " + position
                            + ", click the direction you want to create the spline"));
                    return 0;
                }
            } else if (facingDirection) {
                // The sag keeps its depth and turns to the view.
                BlockVector3 middle = new BlockVector3((start.x() + end.x()) / 2, (start.y() + end.y()) / 2,
                        (start.z() + end.z()) / 2);
                double depth = Math.sqrt(middle.distanceSq(vertex));
                com.maxlananas.fawebim.core.math.Vector3 facing = actor.direction();
                vertex = new BlockVector3((int) Math.round(middle.x() + facing.x() * depth),
                        (int) Math.round(middle.y() + facing.y() * depth),
                        (int) Math.round(middle.z() + facing.z() * depth));
            }
            BlockVector3 last = facingDirection ? end : position;
            int changed = Operations.drawSpline(session, List.of(start, vertex, last), 0, 0, 0, 10, radius, !shell,
                    fill);
            actor.status(Msg.info("Created the line"));
            vertex = null;
            start = select ? last : null;
            return changed;
        }
    }

    /**
     * {@code /brush image <image> [radius] [yscale] [-a] [-f]}, FAWE's: the
     * image laid over the surface blocks around the click as the player sees
     * it - flat on the ground looking down, upright on a wall looking at it -
     * as wide as the brush, each block it covers becoming the block nearest the
     * colour of the pixels it covers. With {@code -a} the image's transparency
     * mixes it with the colours already there, a clear pixel leaving its block
     * alone; {@code yscale} makes the image that much more opaque or clear and
     * {@code -f} fades it out towards its edges, both with the transparency.
     * The image is read from the schematic folder, as the heightmap brushes'.
     *
     * <p>It was a sphere of the brush's pattern, which it had not, so of air.</p>
     */
    public static final class ImageBrush extends BaseBrush {

        /** Samples taken along a side of the pixels one block covers, at most. */
        private static final int SAMPLES = 16;

        private final com.maxlananas.fawebim.core.util.Images.PixelSource image;
        private final double yScale;
        private final boolean alpha;
        private final boolean fade;

        public ImageBrush(double radius, Mask mask, com.maxlananas.fawebim.core.util.Images.PixelSource image,
                          double yScale, boolean alpha, boolean fade) {
            super(radius, null, mask);
            this.image = image;
            this.yScale = yScale;
            this.fade = fade;
            this.alpha = alpha || fade || yScale != 1;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            double scale = Math.max(image.width(), image.height()) / Math.max(1, radius);
            // FAWE's view transform, inverted: the yaw turns the offset around
            // the vertical, then the pitch tilts the image plane up to the view.
            double yaw = Math.toRadians(actor.yaw());
            double tilt = Math.toRadians(90 - actor.pitch());
            double[] view = {Math.cos(yaw), Math.sin(yaw), Math.cos(tilt), Math.sin(tilt)};
            BlockStateRegistry registry = BlockState.registry();
            LongSet visited = new LongSet();
            LongQueue queue = new LongQueue();
            long start = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.positionKey(position.x(),
                    position.y(), position.z());
            visited.add(start);
            queue.add(start);
            int changed = 0;
            while (!queue.isEmpty()) {
                long node = queue.poll();
                int x = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.keyX(node);
                int y = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.keyY(node);
                int z = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.keyZ(node);
                int painted = paint(session, registry, position, x, y, z, scale, view);
                // The walk goes on over the surface the image covers, from the
                // click whatever it is.
                if (painted < 0 && node != start) {
                    continue;
                }
                changed += Math.max(0, painted);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int ny = y + dy;
                            if ((dx | dy | dz) == 0 || ny < session.minY() || ny > session.maxY()) {
                                continue;
                            }
                            long key = com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard.positionKey(x + dx,
                                    ny, z + dz);
                            if (visited.add(key)) {
                                queue.add(key);
                            }
                        }
                    }
                }
            }
            return changed;
        }

        private static boolean surface(EditSession session, BlockStateRegistry registry, int x, int y, int z) {
            if (!registry.isSolid(session.getBlock(x, y, z))) {
                return false;
            }
            return !registry.isSolid(session.getBlock(x + 1, y, z)) || !registry.isSolid(session.getBlock(x - 1, y, z))
                    || !registry.isSolid(session.getBlock(x, y, z + 1)) || !registry.isSolid(session.getBlock(x, y, z - 1))
                    || y < session.maxY() && !registry.isSolid(session.getBlock(x, y + 1, z))
                    || y > session.minY() && !registry.isSolid(session.getBlock(x, y - 1, z));
        }

        /**
         * Paints one surface block the image covers: 1 when it changed, 0 when
         * it did not, -1 when it is no surface block the image covers, where
         * the walk stops.
         */
        private int paint(EditSession session, BlockStateRegistry registry, BlockVector3 center, int x, int y,
                          int z, double scale, double[] view) {
            if (!surface(session, registry, x, y, z)) {
                return -1;
            }
            int dx = x - center.x();
            int dy = y - center.y();
            int dz = z - center.z();
            double[] low = project(dx - 0.5, dy - 0.5, dz - 0.5, view);
            double[] high = project(dx + 0.5, dy + 0.5, dz + 0.5, view);
            int x1 = (int) (low[0] * scale + image.width() / 2d);
            int z1 = (int) (low[1] * scale + image.height() / 2d);
            int x2 = (int) (high[0] * scale + image.width() / 2d);
            int z2 = (int) (high[1] * scale + image.height() / 2d);
            if (x2 < x1) {
                int swap = x1;
                x1 = x2;
                x2 = swap;
            }
            if (z2 < z1) {
                int swap = z1;
                z1 = z2;
                z2 = swap;
            }
            if (x1 >= image.width() || x2 < 0 || z1 >= image.height() || z2 < 0) {
                return -1;
            }
            int color = colour(session, registry, x, y, z, Math.max(0, x1), Math.max(0, z1),
                    Math.min(image.width() - 1, x2), Math.min(image.height() - 1, z2));
            if (color == -1 || mask != null && !mask.test(x, y, z)) {
                return 0;
            }
            int block = MapColors.palette(registry).closest(color);
            return block >= 0 && session.setBlock(x, y, z, block) ? 1 : 0;
        }

        /** An offset in the image's plane, as FAWE's inverted view transform takes it there. */
        private static double[] project(double dx, double dy, double dz, double[] view) {
            double ax = view[0] * dx + view[1] * dz;
            double az = -view[1] * dx + view[0] * dz;
            return new double[]{ax, view[3] * dy + view[2] * az};
        }

        /**
         * The colour of the pixels a block covers, or -1 where they are clear:
         * their average, mixed with the colour of the block by their average
         * transparency when the brush reads it.
         */
        private int colour(EditSession session, BlockStateRegistry registry, int x, int y, int z,
                           int x1, int z1, int x2, int z2) {
            int stepX = Math.max(1, (x2 - x1 + 1) / SAMPLES);
            int stepZ = Math.max(1, (z2 - z1 + 1) / SAMPLES);
            long red = 0;
            long green = 0;
            long blue = 0;
            long opacity = 0;
            int count = 0;
            for (int u = x1; u <= x2; u += stepX) {
                for (int v = z1; v <= z2; v += stepZ) {
                    int rgb = image.rgb(u, v);
                    red += (rgb >> 16) & 0xFF;
                    green += (rgb >> 8) & 0xFF;
                    blue += rgb & 0xFF;
                    opacity += alpha ? opacity(u, v) : 255;
                    count++;
                }
            }
            int r = (int) (red / count);
            int g = (int) (green / count);
            int b = (int) (blue / count);
            int a = (int) (opacity / count);
            if (a <= 0) {
                return -1;
            }
            if (a >= 255) {
                return (r << 16) | (g << 8) | b;
            }
            int existing = MapColors.colorOf(registry, session.getBlock(x, y, z));
            int er = (existing >> 16) & 0xFF;
            int eg = (existing >> 8) & 0xFF;
            int eb = existing & 0xFF;
            return ((r * a + er * (255 - a)) / 255 << 16) | ((g * a + eg * (255 - a)) / 255 << 8)
                    | (b * a + eb * (255 - a)) / 255;
        }

        /** The transparency of a pixel as the brush reads it: scaled by yscale, faded out towards the edges. */
        private int opacity(int u, int v) {
            double value = image.opacity(u, v) * yScale;
            if (fade) {
                double cx = image.width() / 2d;
                double cz = image.height() / 2d;
                double distance = Math.sqrt(Math.pow((u - cx) / cx, 2) + Math.pow((v - cz) / cz, 2));
                value *= Math.max(0, 1 - distance);
            }
            return (int) Math.max(0, Math.min(255, value));
        }
    }

    /** {@code /brush scatter}. */
    public static final class ScatterBrush extends BaseBrush {

        private final int points;
        private final int distance;
        private final boolean overlay;

        public ScatterBrush(double radius, Pattern fill, Mask mask) {
            this(radius, fill, mask, Math.max(1, (int) Math.round(radius)), 1, false);
        }

        public ScatterBrush(double radius, Pattern fill, Mask mask, int points, int distance, boolean overlay) {
            super(radius, fill, mask);
            this.points = points;
            this.distance = distance;
            this.overlay = overlay;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.scatter(session, position, points, distance, radius, fill, random, overlay, mask);
        }
    }

    /**
     * {@code /brush shatter <pattern> [radius] [count]}, FAWE's: the pattern
     * drawn along the cracks between patches grown over the surface from
     * {@code count} points, see {@link Operations#shatter}. It set random blocks
     * of a sphere to air whatever its pattern and count.
     */
    public static final class ShatterBrush extends BaseBrush {

        private final int count;

        public ShatterBrush(double radius, Pattern fill, Mask mask, int count) {
            super(radius, fill, mask);
            this.count = Math.max(1, count);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.shatter(session, position, radius, count, fill, mask, random);
        }
    }

    /**
     * {@code /brush splatter <pattern> [radius] [points] [recursion] [solid]},
     * FAWE's: splotches grown over the surface from points on it, see
     * {@link Operations#splatter}. It filled random blocks of a sphere, air
     * included, whatever its recursion and solid said.
     */
    public static final class SplatterBrush extends BaseBrush {

        private int points = 1;
        private int recursion = 5;
        private boolean solid = true;

        public SplatterBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /** How many splotches the brush throws; FAWE's {@code points} argument. */
        public void setPoints(int points) {
            this.points = Math.max(1, points);
        }

        /** How many levels a splotch grows; FAWE's {@code recursion} argument. */
        public void setRecursion(int recursion) {
            this.recursion = Math.max(0, recursion);
        }

        /** One block of the pattern per splotch, where false asks it for every block. */
        public void setSolid(boolean solid) {
            this.solid = solid;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.splatter(session, position, radius, points, recursion, solid, fill, mask, random);
        }
    }

    /**
     * {@code /brush rock <pattern> [radius] [roundness] [frequency] [amplitude]}:
     * FAWE's blob, a ball whose surface simplex noise pushes in and out, drawn
     * afresh on every click from a random corner of the noise.
     *
     * <p>The radius is one number or one per axis, {@code 10,5,10} for a flat
     * rock; the brush's size is the largest, and the others keep their share
     * of it when {@code /tool size} changes it. At a roundness under 100 the
     * ball is mixed with FAWE's "Manhattan" shape - the sum and the largest of
     * the axes, each stretched at random - and turned at random.</p>
     *
     * <p>This drew the same wobbled sphere, from sines of the offsets, on every
     * click, and read the radius as a single number.</p>
     */
    public static final class RockBrush extends BaseBrush {

        /** The radius of each axis divided by the largest, which is the size. */
        private Vector3 axes = new Vector3(1, 1, 1);
        private double sphericity = 1;
        private double frequency = 0.3;
        private double amplitude = 0.5;

        public RockBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /**
         * The shape as the command line gives it: the radius of each axis, and
         * the roundness, frequency and amplitude as percentages.
         */
        public void setShape(double[] radii, double sphericity, double frequency, double amplitude) {
            double largest = Math.max(radii[0], Math.max(radii[1], radii[2]));
            this.axes = new Vector3(radii[0] / largest, radii[1] / largest, radii[2] / largest);
            this.sphericity = sphericity / 100;
            this.frequency = frequency / 100;
            this.amplitude = amplitude / 100;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.makeBlob(position, radius, frequency, amplitude, axes, sphericity, random,
                    (x, y, z) -> place(session, x, y, z));
        }
    }

    /**
     * {@code /brush heightmap <image>} — raises or lowers the terrain to the
     * heightmap of an image: the brighter the pixel, the higher the terrain.
     */
    public static final class ImageHeightmapBrush extends BaseBrush {

        private final com.maxlananas.fawebim.core.util.Images.PixelSource image;
        private final double yScale;
        private int intensity = 5;
        private boolean flatten;
        private boolean randomize;

        public ImageHeightmapBrush(double radius, Pattern fill, Mask mask,
                                   com.maxlananas.fawebim.core.util.Images.PixelSource image, double yScale) {
            super(radius, fill, mask);
            this.image = image;
            this.yScale = yScale;
        }

        /** How many blocks of height the brightest pixel of the image means. */
        public void setIntensity(int intensity) {
            this.intensity = Math.max(1, intensity);
        }

        /** {@code -f}: only cut down to the height, never fill up to it. */
        public void setFlatten(boolean flatten) {
            this.flatten = flatten;
        }

        /** {@code -r}: move each column's height by a block or so. */
        public void setRandomize(boolean randomize) {
            this.randomize = randomize;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            com.maxlananas.fawebim.core.world.BlockStateRegistry registry =
                    com.maxlananas.fawebim.core.world.BlockState.registry();
            int r = (int) Math.ceil(radius);
            int changed = 0;
            int originX = position.x() - r;
            int originZ = position.z() - r;
            int span = 2 * r + 1;
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    double dx = x - r;
                    double dz = z - r;
                    if (Math.sqrt(dx * dx + dz * dz) > radius) {
                        continue;
                    }
                    int px = (int) Math.round((double) x / span * (image.width() - 1));
                    int pz = (int) Math.round((double) z / span * (image.height() - 1));
                    if (image.transparent(px, pz)) {
                        continue;
                    }
                    int height = (int) Math.round(image.luminance(px, pz) / 255.0 * intensity * yScale);
                    if (randomize) {
                        height += random.nextInt(3) - 1;
                    }
                    int columnX = originX + x;
                    int columnZ = originZ + z;
                    int target = position.y() + height;
                    for (int y = session.getWorld().getHighestBlockY(columnX, columnZ); y > target; y--) {
                        if (session.setBlock(columnX, y, columnZ, registry.air())) {
                            changed++;
                        }
                    }
                    if (flatten) {
                        continue;
                    }
                    for (int y = target; y > session.getWorld().getHighestBlockY(columnX, columnZ); y--) {
                        if (place(session, columnX, y, columnZ)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }

        @Override
        public String describe() {
            return "heightmap " + image.width() + "x" + image.height() + " (yscale " + yScale + ")";
        }
    }

    /**
     * {@code /brush circle <pattern> [radius] [filled]}: FAWE's circle, a disc
     * or a ring facing the player - the blocks of the ball, or of its shell,
     * within half a block of the plane square to the line from the player to
     * the clicked block.
     *
     * <p>This walked the circle by angles and floored each point, which left
     * holes on the diagonals and put the circle a block off towards negative
     * coordinates.</p>
     */
    public static final class CircleBrush extends BaseBrush {

        private final boolean filled;

        public CircleBrush(double radius, Pattern fill, Mask mask, boolean filled) {
            super(radius, fill, mask);
            this.filled = filled;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            // FAWE faces the circle along the line from the player's feet to
            // the clicked block, measured from its corner.
            Vector3 feet = actor.location();
            Vector3 normal = feet == null ? Vector3.ZERO
                    : new Vector3(position.x() - feet.x(), position.y() - feet.y(), position.z() - feet.z());
            if (normal.lengthSq() == 0) {
                normal = actor.direction();
            }
            return Operations.circle(position, radius, filled, normal.normalize(), (x, y, z) -> place(session, x, y, z));
        }
    }

    /** {@code /brush blob} — a smooth blob, the inverse of the smooth brush. */
    public static final class BlobBrush extends BaseBrush {

        public BlobBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int highest = session.getWorld().getHighestBlockY(x0, z0);
                    int target = position.y();
                    for (int y = highest; y > target; y--) {
                        if (session.setBlock(x0, y, z0, registry.air())) {
                            changed++;
                        }
                    }
                    for (int y = highest + 1; y <= target; y++) {
                        if (place(session, x0, y, z0)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /**
     * {@code /brush stencil <pattern> <radius> <image> [rotation] [yscale]} —
     * paints the image onto the surface around the click. The rotation is a
     * quarter turn per step, {@code -r} picks one at random for every click and
     * {@code -w} treats the whole image as solid (maximum saturation) instead of
     * skipping its transparent pixels.
     */
    public static final class StencilBrush extends BaseBrush {

        private final com.maxlananas.fawebim.core.util.Images.PixelSource image;
        private final int rotation;
        private final double yScale;
        private final boolean onlyWhite;
        private final boolean randomRotation;

        public StencilBrush(double radius, Pattern fill, Mask mask) {
            this(radius, fill, mask, null, 0, 1, false, false);
        }

        public StencilBrush(double radius, Pattern fill, Mask mask,
                            com.maxlananas.fawebim.core.util.Images.PixelSource image,
                            int rotation, double yScale, boolean onlyWhite, boolean randomRotation) {
            super(radius, fill, mask);
            this.image = image;
            this.rotation = rotation;
            this.yScale = yScale;
            this.onlyWhite = onlyWhite;
            this.randomRotation = randomRotation;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (image == null) {
                return paintedSphere(session, position);
            }
            int turn = randomRotation ? random.nextInt(4) : Math.floorMod(rotation / 90, 4);
            int width = image.width();
            int height = image.height();
            int size = (int) Math.max(1, radius);
            int changed = 0;
            for (int px = -size; px <= size; px++) {
                for (int pz = -size; pz <= size; pz++) {
                    if (px * px + pz * pz > size * size) {
                        continue;
                    }
                    int sampleX;
                    int sampleZ;
                    switch (turn) {
                        case 1 -> {
                            sampleX = -pz;
                            sampleZ = px;
                        }
                        case 2 -> {
                            sampleX = -px;
                            sampleZ = -pz;
                        }
                        case 3 -> {
                            sampleX = pz;
                            sampleZ = -px;
                        }
                        default -> {
                            sampleX = px;
                            sampleZ = pz;
                        }
                    }
                    int u = Math.floorMod(sampleX + size, Math.max(1, width));
                    int v = Math.floorMod(sampleZ + size, Math.max(1, height));
                    int opacity = image.opacity(u, v);
                    if (opacity == 0) {
                        continue;
                    }
                    if (onlyWhite && opacity < 128) {
                        continue;
                    }
                    int surfaceY = session.getWorld().getHighestBlockY(position.x() + px, position.z() + pz);
                    if (surfaceY <= session.getWorld().minY()) {
                        continue;
                    }
                    int drop = (int) Math.round(opacity / 255.0 * yScale * size);
                    int y = surfaceY + Math.max(0, drop);
                    if (place(session, position.x() + px, y, position.z() + pz)) {
                        changed++;
                    }
                }
            }
            return changed;
        }

        /** The plain form without an image still paints a stencil-like surface. */
        private int paintedSphere(EditSession session, BlockVector3 position) {
            return Operations.forEachInSphere(position, (int) radius, true,
                    (x, y, z) -> (x + y + z) % 2 == 0 && place(session, x, y, z));
        }
    }

    /** {@code /brush gravity} — drops blocks. */
    public static final class GravityBrush extends BaseBrush {

        /** {@code -h <height>}: WorldEdit's height, in place of the brush radius. */
        private Integer height;
        /** {@code -h}: FAWE's flag form, which scans down to the bottom of the world. */
        private boolean fullHeight;

        public GravityBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        /** {@code /brush gravity <radius> -h <height>} — WorldEdit's window offset. */
        public void setHeight(int height) {
            this.height = height;
        }

        /** {@code /brush gravity <radius> -h} — FAWE's full-height scan. */
        public void setFullHeight(boolean fullHeight) {
            this.fullHeight = fullHeight;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Operations.gravity(session.getWorld(), session, position, (int) radius, height, fullHeight);
        }
    }

    /** {@code /brush clipboard} — pastes the clipboard at the click. */
    public static final class ClipboardBrush extends BaseBrush {

        /** {@code -o}: place the clipboard's origin on the click instead of centring it. */
        private final boolean pasteOnTop;
        /** {@code -a}: leave the target blocks alone where the clipboard holds air. */
        private final boolean ignoreAir;
        /** {@code -v}: keep the target block where the clipboard holds a structure void. */
        private final boolean keepStructureVoid;
        /** {@code -e}: spawn the clipboard's entities. */
        private final boolean pasteEntities;
        /** {@code -b}: apply the clipboard's biomes. */
        private final boolean pasteBiomes;
        /** {@code -m}: only paste where this mask accepts the target block. */
        private final Mask sourceMask;
        /** {@code -r}: turn the paste by a random quarter turn. */
        private final boolean randomRotate;

        public ClipboardBrush(double radius, Mask mask, boolean pasteOnTop) {
            this(radius, mask, pasteOnTop, false, false, false, false, null, false);
        }

        public ClipboardBrush(double radius, Mask mask, boolean pasteOnTop, boolean ignoreAir,
                              boolean keepStructureVoid, boolean pasteEntities, boolean pasteBiomes,
                              Mask sourceMask, boolean randomRotate) {
            super(radius, null, mask);
            this.pasteOnTop = pasteOnTop;
            this.ignoreAir = ignoreAir;
            this.keepStructureVoid = keepStructureVoid;
            this.pasteEntities = pasteEntities;
            this.pasteBiomes = pasteBiomes;
            this.sourceMask = sourceMask;
            this.randomRotate = randomRotate;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (!actor.session().hasClipboard()) {
                actor.message(Msg.error("No clipboard: use //copy first"));
                return 0;
            }
            var holder = actor.session().getClipboard();
            var clipboard = holder.getClipboard();
            // WorldEdit's centring: the centre of the clipboard's box goes on
            // the click, wherever its origin is. Counting half the size from
            // the origin only centred a clipboard whose origin was its corner.
            var box = clipboard.getBox();
            BlockVector3 centre = new BlockVector3(Math.floorDiv(box.minX() + box.maxX(), 2),
                    Math.floorDiv(box.minY() + box.maxY(), 2), Math.floorDiv(box.minZ() + box.maxZ(), 2));
            BlockVector3 destination = pasteOnTop ? position : position.subtract(centre.subtract(clipboard.getOrigin()));
            // -r composes a quarter turn with whatever transform the clipboard
            // already carries, exactly like FAWE's brush does.
            Transform transform = holder.getTransform();
            if (randomRotate) {
                Transform turn = Transforms.rotate(clipboard.getOrigin(), random.nextInt(4) * 90.0);
                transform = transform == null ? turn : turn.combine(transform);
            }
            return com.maxlananas.fawebim.core.clipboard.Clipboards.paste(clipboard, destination, session,
                    transform, ignoreAir, sourceMask, pasteEntities, pasteBiomes, false, keepStructureVoid);
        }

        @Override
        public String describe() {
            return "clipboard (radius " + radius + ")" + (pasteOnTop ? " at the target" : "");
        }
    }

    /**
     * {@code /brush copypaste [-r] [-a] <radius>} — the first click copies the
     * connected blob under the cursor, the next ones paste it back with an
     * optional rotation, which is FAWE's {@code CopyPastaBrush}.
     */
    public static final class CopyPastaBrush extends BaseBrush {

        private final boolean randomRotate;
        private final boolean autoRotate;

        public CopyPastaBrush(double radius, boolean randomRotate, boolean autoRotate) {
            super(radius, null, null);
            this.randomRotate = randomRotate;
            this.autoRotate = autoRotate;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (!actor.session().hasClipboard()) {
                BlockArrayClipboard copied = com.maxlananas.fawebim.core.function.Operations.copyConnected(
                        session.getWorld(), session, position, (int) Math.ceil(radius), mask);
                if (copied.volume() == 0) {
                    actor.message(Msg.error("Nothing to copy at " + position));
                    return 0;
                }
                actor.session().setClipboard(copied);
                actor.message(Msg.success("Copied " + Msg.blocks(copied.volume())));
                return 0;
            }
            Transform transform = Transform.identity();
            if (randomRotate) {
                transform = Transforms.rotate(position, random.nextInt(4) * 90.0);
            }
            if (autoRotate) {
                // The blob follows the way the player looks, as FAWE's brush does:
                // the yaw turns it around Y, the pitch tilts it.
                transform = Transforms.rotate(position, com.maxlananas.fawebim.core.transform.Axis.Y, -actor.yaw())
                        .combine(transform);
                transform = Transforms.rotate(position, com.maxlananas.fawebim.core.transform.Axis.X,
                        actor.pitch() - 90).combine(transform);
            }
            return com.maxlananas.fawebim.core.clipboard.Clipboards.paste(actor.session().getClipboard().getClipboard(),
                    position.add(0, 1, 0), session, transform, true, null, false, false, false, false);
        }

        @Override
        public String describe() {
            return "copypaste (radius " + radius + (randomRotate ? ", random rotation" : "")
                    + (autoRotate ? ", view rotation" : "") + ")";
        }
    }

    /**
     * {@code /brush scattercommand} — runs a command at random positions inside
     * the brush, which is FAWE's brush for scattering the result of another
     * command over an area. The amount is what its setting holds, defaulting to
     * one command per block of radius.
     */
    public static final class ScatterCommandBrush extends BaseBrush {

        private final String command;
        private final int points;
        private final int distance;
        private boolean verbose;

        public ScatterCommandBrush(double radius, String command) {
            this(radius, 1, 1, command, false);
        }

        public ScatterCommandBrush(double radius, int points, int distance, String command, boolean verbose) {
            super(radius, null, null);
            this.command = command == null ? "" : command;
            this.points = Math.max(1, points);
            this.distance = Math.max(0, distance);
            this.verbose = verbose;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (command.isEmpty()) {
                actor.message(Msg.error("No command set: /brush scattercommand <radius> <command>"));
                return 0;
            }
            // FAWE's: the points of the scatter brush, and the commands run at
            // each with a selection as large as the distance between them.
            int executed = 0;
            for (BlockVector3 point : Operations.scatterPoints(session, position, radius, points, distance, null,
                    random)) {
                executed += runCommandsAt(actor, point, distance, command, !verbose);
            }
            return executed;
        }

        @Override
        public String describe() {
            return "scattercommand=" + command;
        }
    }

    /**
     * {@code /brush biome <shape> [radius] <biome> [-c]}: FAWE's biome brush,
     * the biome set in every block of the shape. With {@code -c} the shape
     * goes through the height of the world, a sphere or a cylinder as a
     * cylinder of the radius and a cuboid as the square of it.
     *
     * <p>It set the biome with the id 0 whatever biome it was bound with, in a
     * cylinder whatever the shape.</p>
     */
    public static final class BiomeBrush extends BaseBrush {

        private final String shape;
        private final int biome;
        private final boolean fullColumn;

        public BiomeBrush(double radius, Mask mask, String shape, int biome, boolean fullColumn) {
            super(radius, null, mask);
            this.shape = shape;
            this.biome = biome;
            this.fullColumn = fullColumn;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            com.maxlananas.fawebim.core.region.Region region = region(shape, session, position, radius);
            if (fullColumn) {
                BlockVector3 min = region.getMinimumPoint();
                BlockVector3 max = region.getMaximumPoint();
                region = region instanceof CuboidRegion
                        ? new CuboidRegion(new BlockVector3(min.x(), session.minY(), min.z()),
                                new BlockVector3(max.x(), session.maxY(), max.z()))
                        : new com.maxlananas.fawebim.core.region.CylinderRegion(
                                new com.maxlananas.fawebim.core.math.Vector2(position.x() + 0.5, position.z() + 0.5),
                                radius, radius, session.minY(), session.maxY());
            }
            com.maxlananas.fawebim.core.region.Region shaped = region;
            return masked(session, () -> (int) shaped.forEachPosition((x, y, z) -> session.setBiome(x, y, z, biome)));
        }

        @Override
        public String describe() {
            return "biome " + BlockState.registry().biomeName(biome) + " shape=" + shape + (fullColumn ? " -c" : "")
                    + " " + super.describe();
        }
    }

    /**
     * {@code /brush butcher} — kills the entities in the brush. Which categories
     * it may touch is the {@link Creatures.Category} set the flags built: without
     * any flag only hostile monsters are killed, exactly like FAWE.
     */
    public static final class ButcherBrush extends BaseBrush {

        private final java.util.Set<Creatures.Category> categories;

        public ButcherBrush(double radius) {
            this(radius, Creatures.hostile());
        }

        public ButcherBrush(double radius, java.util.Set<Creatures.Category> categories) {
            super(radius, null, null);
            this.categories = categories;
        }

        /**
         * FAWE's cylinder of the radius by the height of the world; a cube
         * of the radius missed the mobs above and below it, and took them from
         * its corners, half as far again.
         */
        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            List<EntityData> entities = session.getWorld().getEntitiesWithin(position, radius);
            int removed = 0;
            for (EntityData entity : entities) {
                if (entity.isSpawnable() && Creatures.matches(entity, categories)) {
                    // Through the edit session, so //undo brings the entity back.
                    session.removeEntity(entity);
                    removed++;
                }
            }
            return removed;
        }
    }

    /**
     * {@code /brush forest <shape> [radius] [density] <type>}: WorldEdit's
     * forest brush, a {@link Operations#paint} of trees over the shape - a tree
     * tried on {@code density} percent of its columns, grown as //forest grows
     * one.
     *
     * <p>It tried a fixed number of trees whatever the density, on the square
     * around the click, and asked the world for a feature named after the tree
     * type, which no world has: it planted nothing.</p>
     */
    public static final class ForestBrush extends BaseBrush {

        private final String shape;
        private final String treeType;
        private final double density;

        /** @param density in percent, as typed */
        public ForestBrush(double radius, Mask mask, String shape, String treeType, double density) {
            super(radius, null, mask);
            this.shape = shape;
            this.treeType = treeType;
            this.density = density;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            com.maxlananas.fawebim.core.region.Region region = region(shape, session, position, radius);
            return masked(session, () -> Operations.forest(session, region, treeType, density / 100));
        }

        @Override
        public String describe() {
            return "forest " + treeType + " shape=" + shape + " density=" + Msg.formatDouble(density) + " "
                    + super.describe();
        }
    }

    /**
     * Runs the commands of a command brush at a point, as FAWE's command
     * brushes run them: the selection becomes the cube of {@code size} around
     * the point, the placeholders {x}, {y}, {z}, {world} and {size} - and the
     * older %x%, %y%, %z% - are the point's, the line is split at ';', and each
     * command runs for the player standing at the point, so //set fills the
     * cube and //sphere is built there.
     *
     * @return how many of the commands ran
     */
    static int runCommandsAt(Actor actor, BlockVector3 point, int size, String commands, boolean quiet) {
        World world = actor.world();
        com.maxlananas.fawebim.core.region.RegionSelector cube =
                com.maxlananas.fawebim.core.region.Selectors.create("cuboid", world, null);
        com.maxlananas.fawebim.core.region.SelectorLimits limits =
                com.maxlananas.fawebim.core.region.SelectorLimits.unlimited();
        cube.selectPrimary(point.add(-size, -size, -size), limits);
        cube.selectSecondary(point.add(size, size, size), limits);
        actor.session().setSelector(cube);
        actor.updateSelectionOutline();
        String x = String.valueOf(point.x());
        String y = String.valueOf(point.y());
        String z = String.valueOf(point.z());
        String line = commands.replace("{x}", x).replace("{y}", y).replace("{z}", z)
                .replace("{world}", world.name()).replace("{size}", String.valueOf(size))
                .replace("%x%", x).replace("%y%", y).replace("%z%", z);
        Actor runner = new com.maxlananas.fawebim.core.actor.PositionedActor(actor, point);
        if (quiet) {
            runner = new com.maxlananas.fawebim.core.actor.SilentActor(runner);
        }
        int ran = 0;
        for (String command : line.split(";")) {
            if (!command.isBlank() && com.maxlananas.fawebim.core.command.BrushCommands.run(runner, command.trim())) {
                ran++;
            }
        }
        return ran;
    }

    /**
     * {@code /brush command <radius> <commands> [-h]}, FAWE's: the commands run
     * where the brush lands, with a selection of the radius around it, each
     * answering in chat unless {@code -h} hides it. It announced the command it
     * ran on every click and kept the player's own selection.
     */
    public static final class CommandBrush extends BaseBrush {

        private final String command;
        private final boolean quiet;
        private BlockVector3 lastPosition;

        public CommandBrush(double radius, String command) {
            this(radius, command, false);
        }

        public CommandBrush(double radius, String command, boolean quiet) {
            super(radius, null, null);
            this.command = command == null ? "" : command;
            this.quiet = quiet;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (command.isEmpty() || position.equals(lastPosition)) {
                return 0;
            }
            lastPosition = position;
            return runCommandsAt(actor, position, (int) radius, command, quiet);
        }

        @Override
        public String describe() {
            return "command=" + command;
        }
    }

    /**
     * {@code /brush populateschematic} — scatters copies of a schematic over the
     * surface around the click.
     *
     * <p>FAWE walks the chunks the brush covers, rolls the density once per chunk
     * and drops one copy at a random column of it. The column is found with the
     * mask, which defaults to any solid block when the command line leaves it
     * out.</p>
     */
    public static final class PopulateSchematicBrush extends BaseBrush {

        private String schematic;
        private boolean randomRotation;
        private int density = 50;

        public PopulateSchematicBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        public void setSchematic(String schematic) {
            this.schematic = schematic;
        }

        public void setRandomRotation(boolean randomRotation) {
            this.randomRotation = randomRotation;
        }

        /** How likely a chunk receives a copy, in percent. */
        public void setDensity(int density) {
            this.density = Math.max(1, Math.min(100, density));
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (schematic == null || schematic.isEmpty()) {
                actor.message(Msg.error("Set a schematic first: /brush populateschematic <name> <radius>"));
                return 0;
            }
            // A comma separated list is FAWE's "clipboard uri": one of them is
            // picked for every copy that is placed.
            String[] names = schematic.split(",");
            int size = (int) radius;
            int minY = session.getWorld().minY();
            int maxY = session.getWorld().maxY();
            int changed = 0;
            for (int chunkX = (position.x() - size) >> 4; chunkX <= (position.x() + size) >> 4; chunkX++) {
                for (int chunkZ = (position.z() - size) >> 4; chunkZ <= (position.z() + size) >> 4; chunkZ++) {
                    if (random.nextInt(100) > density) {
                        continue;
                    }
                    int x = (chunkX << 4) + random.nextInt(16);
                    int z = (chunkZ << 4) + random.nextInt(16);
                    int y = HeightMaps.highestTerrain(session.getWorld(), mask, x, z, minY, maxY);
                    if (mask != null && !mask.test(x, y, z)) {
                        continue;
                    }
                    String name = names[random.nextInt(names.length)].trim();
                    var clipboard = com.maxlananas.fawebim.core.clipboard.Schematics.load(name);
                    if (clipboard == null) {
                        actor.message(Msg.error("Could not load schematic '" + name + "'"));
                        return changed;
                    }
                    var transform = randomRotation
                            ? com.maxlananas.fawebim.core.transform.Transforms.rotate(clipboard.getOrigin(),
                                    random.nextInt(4) * 90)
                            : com.maxlananas.fawebim.core.transform.Transform.identity();
                    changed += com.maxlananas.fawebim.core.clipboard.Clipboards.paste(clipboard,
                            new BlockVector3(x, y, z), session, transform, false, false, false);
                }
            }
            return changed;
        }
    }

    /**
     * {@code /brush surfacespline}: the same path as the spline brush, drawn on
     * the surface, with the tension, bias, continuity and quality controls of a
     * Kochanek-Bartels spline.
     */
    public static final class SurfaceSplineBrush extends BaseBrush {

        private final java.util.List<BlockVector3> points = new java.util.ArrayList<>();
        private double tension;
        private double bias;
        private double continuity;
        private int quality = 10;

        public SurfaceSplineBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        public void setCurve(double tension, double bias, double continuity, int quality) {
            this.tension = tension;
            this.bias = bias;
            this.continuity = continuity;
            this.quality = Math.max(1, quality);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            points.add(new BlockVector3(position.x(),
                    session.getWorld().getHighestBlockY(position.x(), position.z()), position.z()));
            if (points.size() < 2) {
                return 0;
            }
            int changed = Operations.surfaceSpline(session, points, fill, tension, bias, continuity, quality,
                    radius);
            if (points.size() > 64) {
                points.remove(0);
            }
            return changed;
        }
    }

    public static final class SweepBrush extends BaseBrush {

        private final java.util.List<BlockVector3> path = new java.util.ArrayList<>();

        public SweepBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            path.add(position);
            if (path.size() > 128) {
                path.remove(0);
            }
            return Operations.forEachInSphere(position, (int) radius, false, 20000,
                    (x, y, z) -> place(session, x, y, z));
        }
    }

    /**
     * {@code /brush deform}: {@code //deform} on the shape around the click, as
     * WorldEdit's Deform brush. The expression works in the unit cube of the
     * shape, or with {@code -r} in the game's coordinates, or with {@code -o}
     * in blocks from the placement position the brush was bound with.
     *
     * <p>WorldEdit's raise and lower brushes are this brush with {@code y-=1}
     * and {@code y+=1} in the game's coordinates: every block of the shape
     * takes the one below it, or the one above it.</p>
     */
    public static final class DeformBrush extends BaseBrush {

        private final String expression;
        private final String shape;
        private boolean gameOrigin;
        private BlockVector3 placement;

        /** @param shape a name {@code RegionFactories} knows */
        public DeformBrush(double radius, String expression, String shape) {
            this(radius, expression, shape, null);
        }

        public DeformBrush(double radius, String expression, String shape, Mask mask) {
            super(radius, null, mask);
            this.expression = expression == null ? "" : expression;
            // Compiled here so a typo is reported when the brush is bound,
            // not on every click.
            com.maxlananas.fawebim.core.expression.Expression.compile(this.expression);
            this.shape = shape;
        }

        /** {@code -r}: the game's coordinates. */
        public void setGameOrigin(boolean gameOrigin) {
            this.gameOrigin = gameOrigin;
        }

        /** {@code -o}: blocks counted from this position; null for the unit cube. */
        public void setPlacement(BlockVector3 placement) {
            this.placement = placement;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (expression.isEmpty()) {
                return 0;
            }
            com.maxlananas.fawebim.core.region.Region region = region(shape, session, position, radius);
            Operations.DeformFrame frame = gameOrigin ? Operations.DeformFrame.RAW
                    : placement != null ? Operations.DeformFrame.offset(placement.toVector3())
                    : Operations.DeformFrame.unitCube(region);
            return masked(session, () -> Operations.deform(session.getWorld(), session, region, expression, frame));
        }
    }

    /**
     * {@code /brush morph}, {@code /brush dilate}, {@code /brush erode} and
     * {@code /brush pull}: erosion and filling in a ball, see
     * {@link Morphology}. Morph and dilate are WorldEdit's, erode and pull
     * FAWE's.
     *
     * <p>They used to be a single pass that read the blocks it had just
     * written, so the result depended on the order of the walk; their face and
     * iteration arguments were read and ignored, and pull moved blocks sideways
     * towards the click where FAWE fills the open faces around the terrain.</p>
     */
    public static final class MorphBrush extends BaseBrush {

        private final Morphology.Style style;
        private final Morphology.Passes passes;

        public MorphBrush(double radius, Morphology.Style style, Morphology.Passes passes, Mask mask) {
            super(radius, null, mask);
            this.style = style;
            this.passes = passes;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            return Morphology.apply(session, position, radius, style, passes, openStates(style), mask);
        }

        /** Which states the style counts as open, remembered per state for the walk of one click. */
        private static java.util.function.IntPredicate openStates(Morphology.Style style) {
            BlockStateRegistry registry = BlockState.registry();
            byte[] known = new byte[Math.max(1, registry.stateCount())];
            return state -> {
                if (state < 0 || state >= known.length) {
                    return isOpen(registry, state, style);
                }
                if (known[state] == 0) {
                    known[state] = (byte) (isOpen(registry, state, style) ? 1 : 2);
                }
                return known[state] == 1;
            };
        }

        private static boolean isOpen(BlockStateRegistry registry, int state, Morphology.Style style) {
            String name = registry.name(state);
            if (style == Morphology.Style.ERODE) {
                // FAWE asks whether the block stops movement: the game's solid
                // blocks but the cobweb and the bamboo sapling.
                return !registry.isSolid(state) || "minecraft:cobweb".equals(name)
                        || "minecraft:bamboo_sapling".equals(name);
            }
            // WorldEdit's liquids are the blocks the game calls liquid - water,
            // lava and the bubble column: a waterlogged block or a kelp plant
            // is a block.
            return registry.isAirLike(state) || "minecraft:water".equals(name) || "minecraft:lava".equals(name)
                    || "minecraft:bubble_column".equals(name);
        }
    }

    /**
     * {@code /brush item <item> [direction]}: uses the item the way a player
     * would, which for a block item means placing it on the surface below the
     * brush. The item to block mapping is the registry's, so an item that places
     * nothing is reported instead of silently doing nothing.
     */
    public static final class ItemBrush extends BaseBrush {

        private final int state;
        private final String item;
        private final String direction;

        public ItemBrush(double radius, String item, String direction) {
            super(radius, null, null);
            this.item = item == null ? "" : item;
            this.direction = direction == null ? "up" : direction.toLowerCase(java.util.Locale.ROOT);
            this.state = BlockState.registry().blockFromItem(this.item);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (state < 0) {
                actor.message(Msg.error("'" + item + "' does not place a block"));
                return 0;
            }
            int r = (int) Math.max(1, radius);
            int changed = 0;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (x * x + z * z > r * r) {
                        continue;
                    }
                    int columnX = position.x() + x;
                    int columnZ = position.z() + z;
                    int y = switch (direction) {
                        case "down" -> session.getWorld().getHighestBlockY(columnX, columnZ) - 1;
                        default -> session.getWorld().getHighestBlockY(columnX, columnZ) + 1;
                    };
                    if (session.setBlock(columnX, y, columnZ, state)) {
                        changed++;
                    }
                }
            }
            return changed;
        }

        @Override
        public String describe() {
            return "item " + item + " " + direction;
        }
    }

    public static final class ExtinguishBrush extends BaseBrush {

        public ExtinguishBrush(double radius) {
            super(radius, null, null);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            return Operations.forEachInSphere(position, (int) radius, false, (x, y, z) -> {
                String name = registry.name(session.getBlock(x, y, z));
                return (name.contains("fire") || name.contains("lava"))
                        && session.setBlock(x, y, z, registry.air());
            });
        }
    }

    /**
     * {@code /brush snow <shape> [radius] [-s]}: FAWE's snow brush, the snow
     * //snow lays - see {@link Operations#simulateSnow(World, EditSession,
     * com.maxlananas.fawebim.core.region.Region, boolean)} - over the shape. A
     * cylinder is as high as its radius there, where it is one block high for
     * the other brushes.
     *
     * <p>It put a layer on the highest block of every column of a disc,
     * whatever the shape and whatever that block was - a flower, flowing
     * water, a bottom slab - and {@code -s} went through every layer to a full
     * stack in one click.</p>
     */
    public static final class SnowBrush extends BaseBrush {

        private final String shape;
        private final boolean stack;

        public SnowBrush(double radius, Mask mask, String shape, boolean stack) {
            super(radius, null, mask);
            this.shape = shape;
            this.stack = stack;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            String key = shape.toLowerCase(java.util.Locale.ROOT);
            com.maxlananas.fawebim.core.region.Region region = key.equals("cyl") || key.equals("cylinder")
                    ? new com.maxlananas.fawebim.core.region.CylinderRegion(
                            new com.maxlananas.fawebim.core.math.Vector2(position.x() + 0.5, position.z() + 0.5),
                            radius, radius, position.y() - (int) (radius / 2), position.y() + (int) (radius / 2))
                    : region(shape, session, position, radius);
            return masked(session, () -> Operations.simulateSnow(session.getWorld(), session, region, stack));
        }

        @Override
        public String describe() {
            return "snow shape=" + shape + (stack ? " -s" : "") + " " + super.describe();
        }
    }

    /**
     * {@code /brush snowsmooth}: smooths the snow around the brush position.
     * FAWE samples a box that reaches ten blocks above the position, which is
     * where a drifted snow layer sits, and blurs it with a wider kernel than the
     * terrain smooth so a single click does not leave a spike.
     */
    public static final class SnowSmoothBrush extends BaseBrush {

        private final int iterations;
        private final int snowBlockLayers;

        public SnowSmoothBrush(double radius, int iterations, int snowBlockLayers, Mask mask) {
            super(radius, null, mask);
            this.iterations = Math.max(1, iterations);
            this.snowBlockLayers = Math.max(0, snowBlockLayers);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int size = (int) radius;
            CuboidRegion region = new CuboidRegion(
                    BlockVector3.at(position.x() - size, position.y() - size, position.z() - size),
                    BlockVector3.at(position.x() + size, position.y() + size + 10, position.z() + size));
            // FAWE blurs the snow of a brush with a wider kernel than the one
            // //snowsmooth uses, so one click evens out the whole drift.
            return HeightMaps.snowSmooth(session.getWorld(), session, region, iterations, snowBlockLayers, mask, 10);
        }
    }

    /**
     * {@code /brush recurse}: the blocks connected to the clicked one through
     * blocks the mask accepts are set to the pattern, as in FAWE's
     * RecurseBrush - breadth first up to {@code radius} steps from the click,
     * or with {@code -d} depth first within {@code radius} blocks of it.
     *
     * <p>The mask is the brush's, and without one the clicked block's type, as
     * FAWE binds the brush with an id mask that {@code /mask} replaces. The
     * session's mask narrows the walk and stays out of the writes, as FAWE
     * clears it for the brush. The walk used to go through every block that
     * was not air whatever the mask, stopped at 5000 changes, and queued a
     * position object for each of the six neighbours of every block it saw.</p>
     */
    public static final class RecurseBrush extends BaseBrush {

        private boolean depthFirst;

        public RecurseBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /** {@code -d}: walk depth first within the radius instead of breadth first. */
        public void setDepthFirst(boolean depthFirst) {
            this.depthFirst = depthFirst;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int clicked = session.getBlock(position.x(), position.y(), position.z());
            if (registry.isAirLike(clicked)) {
                return 0;
            }
            Mask previous = session.getMask();
            Mask own = mask != null ? mask
                    : new Masks.BlockMask(session, List.of(registry.name(clicked)));
            Mask walk = previous == null ? own
                    : new Masks.IntersectionMask(List.of(previous, own));
            session.setMask(null);
            try {
                return walk(session, position, walk);
            } finally {
                session.setMask(previous);
            }
        }

        private int walk(EditSession session, BlockVector3 start, Mask walk) {
            World world = session.getWorld();
            int steps = (int) radius;
            double reach = radius * radius;
            LongSet visited = new LongSet();
            LongQueue queue = new LongQueue();
            long first = BlockArrayClipboard.positionKey(start.x(), start.y(), start.z());
            visited.add(first);
            queue.add(first);
            int changed = 0;
            // Breadth first counts its steps a layer at a time.
            int leftInLayer = 1;
            int nextLayer = 0;
            int depth = 0;
            while (!queue.isEmpty()) {
                long current = depthFirst ? queue.pollLast() : queue.poll();
                int x = BlockArrayClipboard.keyX(current);
                int y = BlockArrayClipboard.keyY(current);
                int z = BlockArrayClipboard.keyZ(current);
                int state = fill == null ? BlockState.registry().air() : fill.apply(x, y, z);
                if (session.setBlock(x, y, z, state)) {
                    changed++;
                }
                if (depthFirst || depth < steps) {
                    for (Direction direction : DIRECTIONS) {
                        int nx = x + direction.x();
                        int ny = y + direction.y();
                        int nz = z + direction.z();
                        if (ny < world.minY() || ny > world.maxY()) {
                            continue;
                        }
                        if (depthFirst) {
                            double dx = nx - start.x();
                            double dy = ny - start.y();
                            double dz = nz - start.z();
                            if (dx * dx + dy * dy + dz * dz > reach) {
                                continue;
                            }
                        }
                        long next = BlockArrayClipboard.positionKey(nx, ny, nz);
                        if (walk.test(nx, ny, nz) && visited.add(next)) {
                            queue.add(next);
                            nextLayer++;
                        }
                    }
                }
                if (!depthFirst && --leftInLayer == 0) {
                    depth++;
                    leftInLayer = nextLayer;
                    nextLayer = 0;
                }
            }
            return changed;
        }

        private static final Direction[] DIRECTIONS = {
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
    }

    /**
     * {@code /brush feature} and {@code /brush structure <shape> [radius]
     * [density] <type>}: FAWE's Paint of a worldgen feature or structure over
     * the shape, placed on the ground of {@code density} percent of its
     * columns.
     *
     * <p>The feature brush placed one feature where it was clicked, whatever
     * its shape and density, and said so in chat on every click; the
     * structure brush asked the world for a feature named after the
     * structure.</p>
     */
    public static final class FeatureBrush extends BaseBrush {

        private final String shape;
        private final boolean structure;
        private final String type;
        private final double density;

        /** @param density in percent, as typed */
        public FeatureBrush(double radius, Mask mask, String shape, boolean structure, String type, double density) {
            super(radius, null, mask);
            this.shape = shape;
            this.structure = structure;
            this.type = type;
            this.density = density;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            World world = session.getWorld();
            com.maxlananas.fawebim.core.region.Region region = region(shape, session, position, radius);
            return masked(session, () -> Operations.paint(session, region, density / 100, random,
                    (x, y, z, ground) -> structure
                            ? world.generateStructure(session, type, new BlockVector3(x, y, z), random)
                            : world.generateFeature(session, new BlockVector3(x, y, z), type, random)));
        }

        @Override
        public String describe() {
            return (structure ? "structure " : "feature ") + type + " shape=" + shape + " density="
                    + Msg.formatDouble(density) + " " + super.describe();
        }
    }
}
