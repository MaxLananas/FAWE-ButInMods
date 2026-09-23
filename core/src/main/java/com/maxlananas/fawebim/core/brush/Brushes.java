package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.function.HeightMaps;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.Extent;
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

        @Override
        public String describe() {
            return "radius=" + radius + (fill != null ? " fill=" + fill.getClass().getSimpleName() : "");
        }
    }

    // -------------------------------------------------------------- solid shapes

    /** {@code /brush sphere <pattern> [radius]}. */
    public static class SphereBrush extends BaseBrush {

        public SphereBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, hollow)) {
                if (place(session, target.x(), target.y(), target.z())) {
                    changed++;
                }
            }
            return changed;
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
            int changed = 0;
            for (int pass = 0; pass < iterations; pass++) {
                changed += smoothOnce(session, position);
            }
            return changed;
        }

        private int smoothOnce(EditSession session, BlockVector3 position) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                if (!test(target.x(), target.y(), target.z())) {
                    continue;
                }
                int average = 0;
                int count = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            average += session.getBlock(target.x() + dx, target.y() + dy, target.z() + dz);
                            count++;
                        }
                    }
                }
                int best = average / Math.max(1, count);
                if (registry.isAirLike(best)) {
                    continue;
                }
                if (session.setBlock(target.x(), target.y(), target.z(), best)) {
                    changed++;
                }
            }
            return changed;
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
            Mask combined = limit == null || limit == mask ? mask
                    : mask == null ? limit
                    : new com.maxlananas.fawebim.core.mask.Masks.IntersectionMask(List.of(mask, limit));
            return Operations.blendBall(session, position, (int) radius, combined, onlyAir, minFreqDiff);
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
     * {@code /brush height} and {@code /brush cliff}.
     *
     * <p>FAWE drives these two from a height map whose value is scaled by the
     * brush size: a cone for the height brush (it raises a rounded hill) and a
     * flat cylinder for the cliff brush (it raises a plateau, which is what makes
     * the sharp edge). The terrain is moved towards the target height, so the
     * brush both fills and clears.</p>
     */
    /**
     * {@code /brush height}, {@code /brush cliff} and {@code /brush flatten}: the
     * three brushes FAWE builds from one terrain shape.
     *
     * <p>The shape is a cone (height, flatten) or a cylinder (cliff); an image
     * replaces the shape with the heights of the image, rotated by the
     * {@code rotation} argument and by a random quarter turn per click when
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
            for (int y = target; y > session.getWorld().getHighestBlockY(x, z); y--) {
                if (place(session, x, y, z)) {
                    changed++;
                }
            }
            return changed;
        }

        private boolean isSnowLayer(BlockStateRegistry registry, int state) {
            return registry.describe(state).startsWith("minecraft:snow[");
        }
    }

    /** {@code /brush raise} and {@code /brush lower}. */
    public static final class RaiseLowerBrush extends BaseBrush {

        private final boolean lower;

        public RaiseLowerBrush(double radius, Pattern fill, boolean lower, Mask mask) {
            super(radius, fill, mask);
            this.lower = lower;
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
                    if (lower) {
                        if (session.setBlock(x0, highest, z0, registry.air())) {
                            changed++;
                        }
                    } else if (place(session, x0, highest + 1, z0)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush layer [radius]}. */
    public static final class LayerBrush extends BaseBrush {

        public LayerBrush(double radius, Pattern fill, Mask mask) {
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
                    for (int y = position.y(); y >= position.y() - (int) radius; y--) {
                        if (!registry.isAirLike(session.getBlock(position.x() + x, y, position.z() + z))) {
                            if (place(session, position.x() + x, y, position.z() + z)) {
                                changed++;
                            }
                            break;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush surface}. */
    public static final class SurfaceBrush extends BaseBrush {

        public SurfaceBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            for (int z = -(int) radius; z <= radius; z++) {
                for (int x = -(int) radius; x <= radius; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int highest = session.getWorld().getHighestBlockY(position.x() + x, position.z() + z);
                    if (place(session, position.x() + x, highest + 1, position.z() + z)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    // --------------------------------------------------------------- line brushes

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

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockVector3 from = start != null ? start : actor.session().getSelector(actor.world())
                    .getRegion().getMinimumPoint();
            if (start == null) {
                start = actor.position();
                from = start;
            }
            BlockVector3 end = flat ? position.withY(from.y()) : position;
            int changed = Operations.line(session, from, end, fill, 0, shell);
            if (select) {
                var selector = actor.session().getSelector(actor.world());
                var limits = com.maxlananas.fawebim.core.region.SelectorLimits.unlimited();
                selector.selectPrimary(from, limits);
                selector.selectSecondary(end, limits);
            }
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
            int changed = Operations.spline(session, List.of(points.get(points.size() - 2), target), fill, radius);
            if (points.size() > 64) {
                points.remove(0);
            }
            return changed;
        }
    }

    /** {@code /brush catenary} — a rope with sag between two points. */
    public static final class CatenaryBrush extends BaseBrush {

        private double lengthFactor = 1.1;
        private boolean shell;
        private boolean select;
        private boolean facingDirection;

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

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockVector3 from = actor.session().getSelector(actor.world()).getRegion().getMinimumPoint();
            // -d drops the line in the direction the player looks at instead of
            // straight down.
            BlockVector3 end = position;
            if (facingDirection) {
                var facing = actor.facing();
                end = position.add(facing.toVector().multiply(Math.max(1, (int) radius * 2)));
            }
            int changed = Operations.catenary(session, from, end, fill, lengthFactor, shell ? 0.5 : 0);
            if (select) {
                var selector = actor.session().getSelector(actor.world());
                var limits = com.maxlananas.fawebim.core.region.SelectorLimits.unlimited();
                selector.selectPrimary(from, limits);
                selector.selectSecondary(end, limits);
            }
            return changed;
        }
    }

    // ------------------------------------------------------------- scatter family

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

    /** {@code /brush shatter} — removes the surface like an explosion. */
    public static final class ShatterBrush extends BaseBrush {

        public ShatterBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                if (!test(target.x(), target.y(), target.z())) {
                    continue;
                }
                if (random.nextDouble() < 0.3
                        && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush splatter} — random blocks in a sphere. */
    public static final class SplatterBrush extends BaseBrush {

        private int points = 1;

        public SplatterBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /** How many clumps the brush throws; FAWE's {@code points} argument. */
        public void setPoints(int points) {
            this.points = Math.max(1, points);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int attempts = (int) (radius * radius * 4) * points;
            for (int i = 0; i < attempts; i++) {
                double dx = random.nextDouble() * 2 - 1;
                double dy = random.nextDouble() * 2 - 1;
                double dz = random.nextDouble() * 2 - 1;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length > 1) {
                    continue;
                }
                int x = position.x() + (int) (dx * radius);
                int y = position.y() + (int) (dy * radius);
                int z = position.z() + (int) (dz * radius);
                if (place(session, x, y, z)) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush rock} — random noise shaped like rock. */
    /**
     * {@code /brush rock}: a distorted sphere. {@code sphericity} is how close to
     * a perfect sphere the low frequency noise stays, {@code frequency} how fast
     * the surface wobbles and {@code amplitude} how far it wobbles.
     */
    public static final class RockBrush extends BaseBrush {

        private double sphericity = 100;
        private double frequency = 30;
        private double amplitude = 50;

        public RockBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        public void setShape(double sphericity, double frequency, double amplitude) {
            this.sphericity = sphericity;
            this.frequency = frequency;
            this.amplitude = amplitude;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            // The angular part of the position drives the noise, so the surface
            // wobbles per direction instead of per block.
            double noiseScale = frequency / 100.0;
            double wobble = amplitude / 100.0;
            double roundness = sphericity / 100.0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                double distance = target.distance(position);
                double x = target.x() - position.x();
                double y = target.y() - position.y();
                double z = target.z() - position.z();
                double noise = Math.sin(x * noiseScale) * Math.cos(y * noiseScale) * Math.sin(z * noiseScale + 1);
                double limit = radius * (roundness + noise * wobble * (1 - roundness));
                if (distance > limit) {
                    continue;
                }
                if (place(session, target.x(), target.y(), target.z())) {
                    changed++;
                }
            }
            return changed;
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
     * {@code /brush circle} — a disc facing the player, which is how FAWE builds
     * it: the plane normal is the vector from the player to the target.
     */
    public static final class CircleBrush extends BaseBrush {

        private final boolean filled;

        public CircleBrush(double radius, Pattern fill, Mask mask, boolean filled) {
            super(radius, fill, mask);
            this.filled = filled;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            com.maxlananas.fawebim.core.math.Vector3 normal = new com.maxlananas.fawebim.core.math.Vector3(
                    position.x() - actor.position().x(),
                    position.y() - actor.position().y(),
                    position.z() - actor.position().z());
            if (normal.lengthSq() == 0) {
                normal = actor.direction();
            }
            normal = normal.normalize();
            com.maxlananas.fawebim.core.math.Vector3 axisU = orthogonal(normal);
            com.maxlananas.fawebim.core.math.Vector3 axisV = normal.cross(axisU).normalize();
            int steps = Math.max(8, (int) (2 * Math.PI * radius));
            int changed = 0;
            for (int step = 0; step < steps; step++) {
                double angle = 2 * Math.PI * step / steps;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                int rings = filled ? (int) radius + 1 : 1;
                for (int ring = 0; ring < rings; ring++) {
                    double scale = filled ? ring : radius;
                    double px = position.x() + (axisU.x() * cos + axisV.x() * sin) * scale;
                    double py = position.y() + (axisU.y() * cos + axisV.y() * sin) * scale;
                    double pz = position.z() + (axisU.z() * cos + axisV.z() * sin) * scale;
                    int x = (int) Math.floor(px);
                    int y = (int) Math.floor(py);
                    int z = (int) Math.floor(pz);
                    if (place(session, x, y, z)) {
                        changed++;
                    }
                }
            }
            return changed;
        }

        /** Any unit vector perpendicular to the given one. */
        private static com.maxlananas.fawebim.core.math.Vector3 orthogonal(com.maxlananas.fawebim.core.math.Vector3 normal) {
            com.maxlananas.fawebim.core.math.Vector3 candidate =
                    Math.abs(normal.y()) < 0.9
                            ? new com.maxlananas.fawebim.core.math.Vector3(0, 1, 0)
                            : new com.maxlananas.fawebim.core.math.Vector3(1, 0, 0);
            return normal.cross(candidate).normalize();
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

    /** {@code /brush pull} — pulls terrain towards the player. */
    public static final class PullBrush extends BaseBrush {

        private int fillFaces = 1;

        public PullBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /** {@code erodefaces} / {@code fillFaces}: how many neighbours a block needs to be moved or filled. */
        public void setShape(int erodeFaces, int erodeRecursion, int fillFaces, int fillRecursion) {
            this.fillFaces = Math.max(1, fillFaces);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                if (!test(target.x(), target.y(), target.z())) {
                    continue;
                }
                int solidNeighbours = 0;
                for (var direction : com.maxlananas.fawebim.core.world.Direction.values()) {
                    BlockVector3 next = target.add(direction.toVector());
                    if (registry.isSolid(session.getBlock(next.x(), next.y(), next.z()))) {
                        solidNeighbours++;
                    }
                }
                if (solidNeighbours < fillFaces) {
                    continue;
                }
                int x0 = target.x() + Integer.signum(position.x() - target.x());
                int z0 = target.z() + Integer.signum(position.z() - target.z());
                int state = session.getBlock(target.x(), target.y(), target.z());
                if (registry.isAirLike(state)) {
                    continue;
                }
                if (session.setBlock(x0, target.y(), z0, state) && session.setBlock(
                        target.x(), target.y(), target.z(), registry.air())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush stencil} — draws a repeating pattern in a sphere. */
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
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, true)) {
                if ((target.x() + target.y() + target.z()) % 2 != 0) {
                    continue;
                }
                if (place(session, target.x(), target.y(), target.z())) {
                    changed++;
                }
            }
            return changed;
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
            BlockVector3 destination = pasteOnTop ? position : position.add(
                    -clipboard.getWidth() / 2, -clipboard.getHeight() / 2, -clipboard.getLength() / 2);
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
                actor.message(Msg.success("Copied " + Msg.formatNumber(copied.volume()) + " block(s)"));
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
        private boolean verbose;

        public ScatterCommandBrush(double radius, String command) {
            this(radius, command, false);
        }

        public ScatterCommandBrush(double radius, String command, boolean verbose) {
            super(radius, null, null);
            this.command = command == null ? "" : command;
            this.verbose = verbose;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (command.isEmpty()) {
                actor.message(Msg.error("No command set: /brush scattercommand <radius> <command>"));
                return 0;
            }
            int count = Math.max(1, (int) Math.round(radius));
            Actor target = verbose ? actor : new com.maxlananas.fawebim.core.actor.SilentActor(actor);
            int executed = 0;
            for (int i = 0; i < count; i++) {
                int x = position.x() + random.nextInt((int) radius * 2 + 1) - (int) radius;
                int y = position.y() + random.nextInt((int) radius * 2 + 1) - (int) radius;
                int z = position.z() + random.nextInt((int) radius * 2 + 1) - (int) radius;
                String parsed = command
                        .replace("%x%", String.valueOf(x))
                        .replace("%y%", String.valueOf(y))
                        .replace("%z%", String.valueOf(z));
                if (com.maxlananas.fawebim.core.command.BrushCommands.run(target, parsed)) {
                    executed++;
                }
            }
            return executed;
        }

        @Override
        public String describe() {
            return "scattercommand=" + command;
        }
    }

    /** {@code /brush biome} — paints biomes. */
    public static final class BiomeBrush extends BaseBrush {

        private int biomeId;
        private boolean fullColumn;

        public BiomeBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        public void setBiome(int biomeId) {
            this.biomeId = biomeId;
        }

        /** {@code -c}: change the whole column instead of the brush volume. */
        public void setFullColumn(boolean fullColumn) {
            this.fullColumn = fullColumn;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int r = (int) radius;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int minY = fullColumn ? session.minY() : position.y() - r;
                    int maxY = fullColumn ? session.maxY() : position.y() + r;
                    for (int y = minY; y <= maxY; y++) {
                        if (session.setBiome(position.x() + x, y, position.z() + z, biomeId)) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush butcher} — removes nearby entities. */
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

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int r = (int) radius;
            var box = Extent.Region3i.of(position.add(-r, -r, -r), position.add(r, r, r));
            List<EntityData> entities = session.getWorld().getEntities(box);
            int removed = 0;
            for (EntityData entity : entities) {
                if (entity.isSpawnable() && Creatures.matches(entity, categories)) {
                    session.getWorld().removeEntity(entity);
                    removed++;
                }
            }
            return removed;
        }
    }

    /** {@code /brush forest} — plants trees. */
    public static final class ForestBrush extends BaseBrush {

        private double density = 0.05;

        public ForestBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        public void setDensity(double density) {
            this.density = density;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            int changed = 0;
            int r = (int) radius;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.sqrt(x * x + z * z) > radius || random.nextDouble() > density) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int y = session.getWorld().getHighestBlockY(x0, z0);
                    if (session.getWorld().generateTree(new BlockVector3(x0, y + 1, z0), "tree", random)) {
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush command} — runs a command where the brush is used. */
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
            String parsed = command
                    .replace("%x%", String.valueOf(position.x()))
                    .replace("%y%", String.valueOf(position.y()))
                    .replace("%z%", String.valueOf(position.z()));
            // -h keeps the brush from printing what it ran and what the command
            // answered, which matters when it fires on every click.
            Actor target = quiet ? new com.maxlananas.fawebim.core.actor.SilentActor(actor) : actor;
            if (!quiet) {
                target.message(Msg.info("Brush command: /" + parsed));
            }
            return com.maxlananas.fawebim.core.command.BrushCommands.run(target, parsed) ? 1 : 0;
        }

        @Override
        public String describe() {
            return "command=" + command;
        }
    }

    /** {@code /brush populateschematic} — pastes a schematic at the click. */
    public static final class PopulateSchematicBrush extends BaseBrush {

        private String schematic;
        private boolean randomRotation;
        private int density = 50;

        public PopulateSchematicBrush(double radius) {
            super(radius, null, null);
        }

        public void setSchematic(String schematic) {
            this.schematic = schematic;
        }

        public void setRandomRotation(boolean randomRotation) {
            this.randomRotation = randomRotation;
        }

        /** How likely a spot of the surface receives a copy, in percent. */
        public void setDensity(int density) {
            this.density = Math.max(1, Math.min(100, density));
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (schematic == null) {
                actor.message(Msg.error("Set a schematic first: /brush populateschematic <name> <radius>"));
                return 0;
            }
            if (random.nextInt(100) >= density) {
                return 0;
            }
            var clipboard = com.maxlananas.fawebim.core.clipboard.Schematics.load(schematic);
            if (randomRotation) {
                int rotations = random.nextInt(4);
                var transform = com.maxlananas.fawebim.core.transform.Transforms.rotate(
                        clipboard.getOrigin(), rotations * 90);
                return com.maxlananas.fawebim.core.clipboard.Clipboards.paste(clipboard, position, session, transform,
                        false, false, false);
            }
            return com.maxlananas.fawebim.core.clipboard.Clipboards.paste(clipboard, position, session,
                    com.maxlananas.fawebim.core.transform.Transform.identity(), false, false, false);
        }
    }

    /** {@code /brush sweep} — sweeps the clipboard along the click path. */
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
            int changed = Operations.surfaceSpline(session, points, fill, tension, bias, continuity, quality);
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
            int changed = 0;
            for (BlockVector3 point : Operations.spherePositions(position, (int) radius, false)) {
                if (changed > 20000) {
                    break;
                }
                if (place(session, point.x(), point.y(), point.z())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /** {@code /brush deform} — applies an expression to the brush area. */
    public static final class DeformBrush extends BaseBrush {

        private final String expression;
        private boolean gameOrigin;
        private boolean placementOrigin;

        public DeformBrush(double radius, String expression) {
            super(radius, null, null);
            this.expression = expression == null ? "" : expression;
        }

        /** {@code -r}: evaluate the expression against the game's origin. */
        public void setGameOrigin(boolean gameOrigin) {
            this.gameOrigin = gameOrigin;
        }

        /** {@code -o}: evaluate the expression against the placement position. */
        public void setPlacementOrigin(boolean placementOrigin) {
            this.placementOrigin = placementOrigin;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            if (expression.isEmpty()) {
                return 0;
            }
            var region = com.maxlananas.fawebim.core.region.RegionFactories.parse("sphere",
                    session.minY(), session.maxY()).createCenteredAt(position, radius);
            if (placementOrigin && !gameOrigin) {
                return Operations.deform(session.getWorld(), session, region, expression,
                        position.x(), position.y(), position.z());
            }
            return Operations.deform(session.getWorld(), session, region, expression);
        }
    }

    /** {@code /brush erode}, {@code /brush dilate}, {@code /brush morph}. */
    public static final class ErodeDilateBrush extends BaseBrush {

        private final String mode;

        public ErodeDilateBrush(double radius, String mode, Mask mask) {
            super(radius, null, mask);
            this.mode = mode;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            List<BlockVector3> targets = Operations.spherePositions(position, (int) radius, false);
            for (BlockVector3 target : targets) {
                int solidNeighbours = 0;
                for (var direction : com.maxlananas.fawebim.core.world.Direction.values()) {
                    BlockVector3 next = target.add(direction.toVector());
                    if (registry.isSolid(session.getBlock(next.x(), next.y(), next.z()))) {
                        solidNeighbours++;
                    }
                }
                boolean solid = registry.isSolid(session.getBlock(target.x(), target.y(), target.z()));
                switch (mode) {
                    case "erode" -> {
                        if (solid && solidNeighbours < 3
                                && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                            changed++;
                        }
                    }
                    case "dilate" -> {
                        if (!solid && solidNeighbours >= 3 && fill != null
                                && place(session, target.x(), target.y(), target.z())) {
                            changed++;
                        }
                    }
                    default -> {
                        // morph: swap solid and air inside the brush
                        if (solid && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                            changed++;
                        } else if (!solid && fill != null && place(session, target.x(), target.y(), target.z())) {
                            changed++;
                        }
                    }
                }
            }
            return changed;
        }
    }

    /** {@code /brush extinguish} — removes fire and stops lava. */
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
            int changed = 0;
            for (BlockVector3 target : Operations.spherePositions(position, (int) radius, false)) {
                String name = registry.name(session.getBlock(target.x(), target.y(), target.z()));
                if ((name.contains("fire") || name.contains("lava"))
                        && session.setBlock(target.x(), target.y(), target.z(), registry.air())) {
                    changed++;
                }
            }
            return changed;
        }
    }

    /**
     * {@code /brush snow}: covers the surface with snow. {@code -s} stacks the
     * snow layers up instead of laying a single one.
     */
    public static final class SnowBrush extends BaseBrush {

        private boolean stack;
        private int layers = 1;

        public SnowBrush(double radius, Mask mask) {
            super(radius, null, mask);
        }

        public void setStack(boolean stack) {
            this.stack = stack;
        }

        public void setLayers(int layers) {
            this.layers = Math.max(1, layers);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            int r = (int) radius;
            for (int z = -r; z <= r; z++) {
                for (int x = -r; x <= r; x++) {
                    if (Math.sqrt(x * x + z * z) > radius) {
                        continue;
                    }
                    int x0 = position.x() + x;
                    int z0 = position.z() + z;
                    int y = session.getWorld().getHighestBlockY(x0, z0);
                    if (stack) {
                        // Stacked snow: one layer per block, up to a full snow
                        // block, which is what FAWE grows when you brush again.
                        for (int layer = 1; layer <= Math.min(8, layers * 8); layer++) {
                            int state = registry.parse("minecraft:snow[layers=" + layer + "]");
                            if (session.setBlock(x0, y + 1, z0, state)) {
                                changed++;
                            }
                            if (layer % 8 != 0) {
                                continue;
                            }
                            y++;
                        }
                    } else if (session.setBlock(x0, y + 1, z0, registry.parse("minecraft:snow[layers=1]"))) {
                        changed++;
                    }
                }
            }
            return changed;
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
            return HeightMaps.snowSmooth(session.getWorld(), session, region, iterations, snowBlockLayers, mask);
        }
    }

    /** {@code /brush recurse} — recursively applies the pattern to exposed blocks. */
    public static final class RecurseBrush extends BaseBrush {

        private boolean depthFirst;

        public RecurseBrush(double radius, Pattern fill, Mask mask) {
            super(radius, fill, mask);
        }

        /** {@code -d}: walk depth first instead of nearest first. */
        public void setDepthFirst(boolean depthFirst) {
            this.depthFirst = depthFirst;
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            BlockStateRegistry registry = BlockState.registry();
            int changed = 0;
            java.util.Set<BlockVector3> visited = new java.util.HashSet<>();
            java.util.Deque<BlockVector3> queue = new java.util.ArrayDeque<>();
            queue.add(position);
            while (!queue.isEmpty() && changed < 5000) {
                BlockVector3 current = depthFirst ? queue.pollLast() : queue.poll();
                if (!visited.add(current) || current.distance(position) > radius) {
                    continue;
                }
                if (registry.isAirLike(session.getBlock(current.x(), current.y(), current.z()))) {
                    continue;
                }
                if (place(session, current.x(), current.y(), current.z())) {
                    changed++;
                }
                for (var direction : com.maxlananas.fawebim.core.world.Direction.values()) {
                    queue.add(current.add(direction.toVector()));
                }
            }
            return changed;
        }
    }

    /** {@code /brush feature} and {@code /brush structure} — places a worldgen feature. */
    public static final class FeatureBrush extends BaseBrush {

        private final String kind;
        private String feature = "minecraft:oak_tree";
        private int density = 5;

        public FeatureBrush(double radius, String kind, Mask mask) {
            super(radius, null, mask);
            this.kind = kind;
        }

        public void setFeature(String feature) {
            this.feature = feature == null || feature.isEmpty() ? this.feature : feature;
        }

        /** How many features the brush tries to place on every click. */
        public void setDensity(int density) {
            this.density = Math.max(1, density);
        }

        @Override
        public int apply(EditSession session, BlockVector3 position, Actor actor) {
            World world = session.getWorld();
            if (kind.equals("feature") || kind.equals("set")) {
                boolean placed = world.generateFeature(position, feature, random);
                actor.message(placed ? Msg.success("Placed feature " + feature)
                        : Msg.error("Unknown feature " + feature));
                return placed ? 1 : 0;
            }
            int r = (int) Math.max(1, radius);
            int placed = 0;
            for (int attempt = 0; attempt < density; attempt++) {
                int x = position.x() + random.nextInt(r * 2 + 1) - r;
                int z = position.z() + random.nextInt(r * 2 + 1) - r;
                int y = world.getHighestBlockY(x, z);
                if (world.generateFeature(new BlockVector3(x, y, z), feature, random)) {
                    placed++;
                }
            }
            return placed;
        }
    }
}
