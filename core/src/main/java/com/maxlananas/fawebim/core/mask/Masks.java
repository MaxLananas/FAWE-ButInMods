package com.maxlananas.fawebim.core.mask;

import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.noise.Noise;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Extent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Every mask FAWE ships, in one place.
 *
 * <p>Ids ({@code #solid}, {@code #wall}, ...) and their aliases are identical to
 * the upstream parsers; the mask section of the README lists the families.</p>
 */
public final class Masks {

    private Masks() {
    }

    private static Extent resolve(Extent extent) {
        return extent == null ? ExtentHolder.get() : extent;
    }

    /** Holds the extent the current operation works on (FAWE's {@code Request} equivalent). */
    public static final class ExtentHolder {

        private static final ThreadLocal<Extent> CURRENT = new ThreadLocal<>();

        public static void set(Extent extent) {
            CURRENT.set(extent);
        }

        public static Extent get() {
            return CURRENT.get();
        }

        public static void clear() {
            CURRENT.remove();
        }
    }

    // -------------------------------------------------------------- block masks

    /** Matches any of the given blocks/states/tags/categories. */
    public static final class BlockMask implements Mask {

        private final Extent extent;
        private final com.maxlananas.fawebim.core.util.IntSet states = new com.maxlananas.fawebim.core.util.IntSet();
        private final Set<String> tags = new LinkedHashSet<>();
        private final Set<String> categories = new LinkedHashSet<>();
        private final Set<String> names = new LinkedHashSet<>();
        private final List<String> raw = new ArrayList<>();
        /**
         * The states this mask has rejected.
         *
         * <p>A mask is asked about every block of the edit, and the answer for a
         * given state never changes. Without this, a block that matches nothing
         * pays for a name lookup - a hash of a boxed id and a string comparison -
         * on every single block; a {@code //replace} over a region has a handful
         * of distinct states and millions of questions.</p>
         */
        private final com.maxlananas.fawebim.core.util.IntSet rejected =
                new com.maxlananas.fawebim.core.util.IntSet();

        public BlockMask(Extent extent, List<String> inputs) {
            this.extent = extent;
            BlockStateRegistry registry = BlockState.registry();
            for (String input : inputs) {
                raw.add(input);
                String key = input.trim();
                if (key.isEmpty()) {
                    continue;
                }
                if (key.startsWith("##")) {
                    categories.add(key.substring(2).toLowerCase(Locale.ROOT));
                } else if (key.startsWith("#")) {
                    tags.add(key.substring(1).toLowerCase(Locale.ROOT));
                } else {
                    int id = registry.parse(key);
                    if (id < 0) {
                        throw new IllegalArgumentException("Unknown block '" + key + "'");
                    }
                    states.add(id);
                    names.add(registry.name(id));
                }
            }
        }

        /** True when the mask is made of plain block names (used for optimisations). */
        public List<String> getInputs() {
            return raw;
        }

        /** The states this mask accepts without asking a tag or a name. */
        public com.maxlananas.fawebim.core.util.IntSet getStates() {
            return states;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int id = ext.getBlock(x, y, z);
            if (states.contains(id)) {
                return true;
            }
            if (rejected.contains(id)) {
                return false;
            }
            BlockStateRegistry registry = BlockState.registry();
            for (String tag : tags) {
                if (registry.hasTag(id, tag)) {
                    return true;
                }
            }
            for (String category : categories) {
                if (registry.matchesCategory(id, category)) {
                    return true;
                }
            }
            if (!names.isEmpty()) {
                String name = registry.name(id);
                if (names.contains(name)) {
                    return true;
                }
            }
            rejected.add(id);
            return false;
        }

        @Override
        public Extent extent() {
            return extent;
        }

        @Override
        public String toString() {
            return "BlockMask" + raw;
        }
    }

    public static final class AirMask implements Mask {

        private final Extent extent;
        private final boolean includeLiquid;

        public AirMask(Extent extent, boolean includeLiquid) {
            this.extent = extent;
            this.includeLiquid = includeLiquid;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int id = ext.getBlock(x, y, z);
            if (includeLiquid) {
                return BlockState.registry().isAirLike(id) || BlockState.registry().isLiquid(id);
            }
            return BlockState.registry().isAirLike(id);
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class ExistingMask implements Mask {

        private final Extent extent;
        private final boolean ignoreAir;

        public ExistingMask(Extent extent, boolean ignoreAir) {
            this.extent = extent;
            this.ignoreAir = ignoreAir;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int id = ext.getBlock(x, y, z);
            return ignoreAir ? !BlockState.registry().isAirLike(id) : true;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class SolidMask implements Mask {

        private final Extent extent;

        public SolidMask(Extent extent) {
            this.extent = extent;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            return ext != null && BlockState.registry().isSolid(ext.getBlock(x, y, z));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class LiquidMask implements Mask {

        private final Extent extent;

        public LiquidMask(Extent extent) {
            this.extent = extent;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            return ext != null && BlockState.registry().isLiquid(ext.getBlock(x, y, z));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class FullCubeMask implements Mask {

        private final Extent extent;

        public FullCubeMask(Extent extent) {
            this.extent = extent;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            return ext != null && BlockState.registry().isFullCube(ext.getBlock(x, y, z));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class BiomeMask implements Mask {

        private final Extent extent;
        private final int biomeId;

        public BiomeMask(Extent extent, int biomeId) {
            this.extent = extent;
            this.biomeId = biomeId;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            return ext != null && ext.getBiome(x, y, z) == biomeId;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    // ------------------------------------------------------------- geometry masks

    public static final class RegionMask implements Mask {

        private final Region region;

        public RegionMask(Region region) {
            this.region = region;
        }

        public Region getRegion() {
            return region;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return region.contains(x, y, z);
        }

        @Override
        public boolean isRegion() {
            return true;
        }
    }

    /** Region mask that re-reads the selection on every test ({@code #dregion}). */
    public static final class LazyRegionMask implements Mask {

        private final java.util.function.Supplier<Region> supplier;

        public LazyRegionMask(java.util.function.Supplier<Region> supplier) {
            this.supplier = supplier;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Region region = supplier.get();
            return region != null && region.contains(x, y, z);
        }

        @Override
        public boolean isRegion() {
            return true;
        }
    }

    public static final class OffsetMask implements Mask {

        private final Extent extent;
        private final Mask source;
        private final int dx;
        private final int dy;
        private final int dz;

        public OffsetMask(Extent extent, Mask source, int dx, int dy, int dz) {
            this.extent = extent;
            this.source = source;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return source.test(x + dx, y + dy, z + dz);
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class RadiusMask implements Mask {

        private final Extent extent;
        private final Mask source;
        private final int radius;

        public RadiusMask(Extent extent, Mask source, int radius) {
            this.extent = extent;
            this.source = source;
            this.radius = radius;
        }

        @Override
        public boolean test(int x, int y, int z) {
            if (source.test(x, y, z)) {
                return true;
            }
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (source.test(x + dx, y + dy, z + dz)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #wall}: matches blocks that have air on two opposite horizontal sides. */
    public static final class WallMask implements Mask {

        private final Mask source;
        private final int min;
        private final int max;

        public WallMask(Mask source, int min, int max) {
            this.source = source;
            this.min = min;
            this.max = Math.max(max, min);
        }

        @Override
        public boolean test(int x, int y, int z) {
            // The four horizontal neighbours, counted the way FAWE counts them,
            // including its short circuit: from a minimum of one the first match
            // decides as soon as the maximum reaches eight.
            int count = 0;
            if (source.test(x + 1, y, z) && ++count == min && max >= 8) {
                return true;
            }
            if (source.test(x - 1, y, z) && ++count == min && max >= 8) {
                return true;
            }
            if (source.test(x, y, z + 1) && ++count == min && max >= 8) {
                return true;
            }
            if (source.test(x, y, z - 1) && ++count == min && max >= 8) {
                return true;
            }
            return count >= min && count <= max;
        }
    }

    /**
     * {@code {[min][max]}}: a shell around the position the mask was first asked
     * about, which is where the operation started.
     */
    public static final class RadiusShellMask implements Mask {

        private final int minSquared;
        private final int maxSquared;
        private int[] origin;

        public RadiusShellMask(int min, int max) {
            int limit = Math.max(max, min);
            this.minSquared = min * min;
            this.maxSquared = limit * limit;
        }

        @Override
        public boolean test(int x, int y, int z) {
            if (origin == null) {
                origin = new int[]{x, y, z};
            }
            int dx = origin[0] - x;
            int distance = dx * dx;
            if (distance > maxSquared) {
                return false;
            }
            int dz = origin[2] - z;
            distance += dz * dz;
            if (distance > maxSquared) {
                return false;
            }
            int dy = origin[1] - y;
            distance += dy * dy;
            return distance >= minSquared && distance <= maxSquared;
        }

        @Override
        public boolean isRegion() {
            return true;
        }
    }

    /** {@code #surface}: a block whose top neighbour is air (or a specific y offset). */
    public static final class SurfaceMask implements Mask {

        private final Extent extent;
        private final int offset;
        private final boolean alwaysAir;

        public SurfaceMask(Extent extent, int offset, boolean alwaysAir) {
            this.extent = extent;
            this.offset = offset;
            this.alwaysAir = alwaysAir;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            BlockStateRegistry registry = BlockState.registry();
            for (int i = 1; i <= offset; i++) {
                if (!registry.isAirLike(ext.getBlock(x, y + i, z))) {
                    return false;
                }
            }
            if (alwaysAir) {
                return true;
            }
            return !registry.isAirLike(ext.getBlock(x, y - 1, z))
                    || registry.isSolid(ext.getBlock(x, y, z));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /**
     * {@code #angle}: keeps blocks whose slope is between {@code min} and
     * {@code max}.
     *
     * <p>FAWE's implementation, including its height cache: the column heights are
     * read once per 256-block window and reused, which is what makes the mask
     * affordable on large selections. The slope is the largest of the four
     * differences across the column, with the axial samples weighted by
     * {@code 0.5} and the diagonal ones by {@code 1/sqrt(8)}.</p>
     */
    public static class AngleMask implements Mask {

        protected static final double ADJACENT_MOD = 0.5;
        protected static final double DIAGONAL_MOD = 1 / Math.sqrt(8);

        private final Extent extent;
        protected final double min;
        protected final double max;
        protected final boolean overlay;
        protected final boolean checkFirst;
        protected final int maxY;
        protected final int minY;
        protected final int distance;
        private int cacheBotX = Integer.MIN_VALUE;
        private int cacheBotZ = Integer.MIN_VALUE;
        private short[] cacheHeights;
        protected int lastY;
        protected boolean lastValue;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;

        public AngleMask(Extent extent, double min, double max, boolean overlay, int distance) {
            this.extent = resolve(extent);
            this.min = min;
            this.max = max;
            this.overlay = overlay;
            this.distance = distance;
            // A maximum at or above 90 degrees covers every slope, so the mask can
            // stop at the first sample that reaches the minimum.
            this.checkFirst = max >= Math.tan(Math.PI / 2);
            this.maxY = this.extent.maxY();
            this.minY = this.extent.minY();
        }

        @Override
        public boolean test(int x, int y, int z) {
            if (lastX == x && lastZ == z) {
                int height = getHeight(extent, x, y, z);
                if (y <= height) {
                    return overlay ? lastValue && y == height : lastValue;
                }
            }
            lastX = x;
            lastZ = z;
            if (!BlockState.registry().isSolid(extent.getBlock(x, y, z))) {
                return lastValue = false;
            }

            if (overlay && y < maxY && !adjacentAir(x, y, z)) {
                return lastValue = false;
            }
            return testSlope(extent, x, y, z);
        }

        protected boolean testSlope(Extent extent, int x, int y, int z) {
            lastY = y;
            double slope = Math.abs(getHeight(extent, x + distance, y, z)
                    - getHeight(extent, x - distance, y, z)) * ADJACENT_MOD;
            if (checkFirst) {
                if (slope >= min) {
                    return lastValue = true;
                }
                slope = Math.max(slope, Math.abs(getHeight(extent, x, y, z + distance)
                        - getHeight(extent, x, y, z - distance)) * ADJACENT_MOD);
                slope = Math.max(slope, Math.abs(getHeight(extent, x + distance, y, z + distance)
                        - getHeight(extent, x - distance, y, z - distance)) * DIAGONAL_MOD);
                slope = Math.max(slope, Math.abs(getHeight(extent, x - distance, y, z + distance)
                        - getHeight(extent, x + distance, y, z - distance)) * DIAGONAL_MOD);
                return lastValue = slope >= min;
            }
            slope = Math.max(slope, Math.abs(getHeight(extent, x, y, z + distance)
                    - getHeight(extent, x, y, z - distance)) * ADJACENT_MOD);
            slope = Math.max(slope, Math.abs(getHeight(extent, x + distance, y, z + distance)
                    - getHeight(extent, x - distance, y, z - distance)) * DIAGONAL_MOD);
            slope = Math.max(slope, Math.abs(getHeight(extent, x - distance, y, z + distance)
                    - getHeight(extent, x + distance, y, z - distance)) * DIAGONAL_MOD);
            return lastValue = slope >= min && slope <= max;
        }

        /** Column height, cached over a 256x256 window like FAWE's {@code cacheHeights}. */
        protected int getHeight(Extent extent, int x, int y, int z) {
            int rx = x - cacheBotX + 16;
            int rz = z - cacheBotZ + 16;
            int index;
            if ((rx & 0xFF) != rx || (rz & 0xFF) != rz) {
                cacheBotX = x - 16;
                cacheBotZ = z - 16;
                rx = x - cacheBotX + 16;
                rz = z - cacheBotZ + 16;
                if (cacheHeights == null) {
                    cacheHeights = new short[65536];
                }
                java.util.Arrays.fill(cacheHeights, (short) minY);
            }
            index = rx + (rz << 8);
            int result = cacheHeights[index];
            if (y > result) {
                result = extent.getNearestSurfaceTerrainBlock(x, z, Math.max(lastY, y), minY, maxY);
                lastY = result;
                cacheHeights[index] = (short) result;
            }
            return result;
        }

        /** True when one of the six neighbours is air, which is what {@code -o} keeps. */
        private boolean adjacentAir(int x, int y, int z) {
            Extent ext = extent;
            if (y != maxY && !BlockState.registry().isSolid(ext.getBlock(x, y + 1, z))) {
                return true;
            }
            if (y != minY && !BlockState.registry().isSolid(ext.getBlock(x, y - 1, z))) {
                return true;
            }
            return !BlockState.registry().isSolid(ext.getBlock(x + 1, y, z))
                    || !BlockState.registry().isSolid(ext.getBlock(x - 1, y, z))
                    || !BlockState.registry().isSolid(ext.getBlock(x, y, z + 1))
                    || !BlockState.registry().isSolid(ext.getBlock(x, y, z - 1));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /**
     * {@code #surfaceangle}: keeps the surface blocks whose surrounding air points
     * away from straight up.
     *
     * <p>FAWE averages the direction of every air block in a cube around the
     * tested block and turns {@code 1 - average.y} into a fraction of 90 degrees,
     * which is why the mask's limits are given in degrees.</p>
     */
    public static final class SurfaceAngleMask implements Mask {

        private final Extent extent;
        private final double min;
        private final double max;
        private final int size;

        public SurfaceAngleMask(Extent extent, double min, double max, int size) {
            this.extent = extent;
            this.min = min;
            this.max = max;
            this.size = size;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null || BlockState.registry().isAirLike(ext.getBlock(x, y, z))
                    || !nextToAir(ext, x, y, z)) {
                return false;
            }
            double angle = 1 - averageAirDirection(ext, x, y, z);
            return angle >= min / 90.0 && angle <= max / 90.0;
        }

        /** The y component of the normalised average of the air directions. */
        private double averageAirDirection(Extent ext, int px, int py, int pz) {
            double x = 0;
            double y = 0;
            double z = 0;
            int air = 0;
            for (int dx = -size; dx <= size; dx++) {
                for (int dy = -size; dy <= size; dy++) {
                    for (int dz = -size; dz <= size; dz++) {
                        int bx = px + dx;
                        int by = Math.max(ext.minY(), Math.min(ext.maxY(), py + dy));
                        int bz = pz + dz;
                        if (BlockState.registry().isAirLike(ext.getBlock(bx, by, bz))) {
                            x += dx;
                            y += dy;
                            z += dz;
                            air++;
                        }
                    }
                }
            }
            if (air == 0) {
                return 1;
            }
            Vector3 average = new Vector3(x / air, y / air, z / air);
            if (average.x() == 0 && average.y() == 0 && average.z() == 0) {
                return 0;
            }
            return average.normalize().y();
        }

        private static boolean nextToAir(Extent ext, int x, int y, int z) {
            return BlockState.registry().isAirLike(ext.getBlock(x + 1, y, z))
                    || BlockState.registry().isAirLike(ext.getBlock(x - 1, y, z))
                    || BlockState.registry().isAirLike(ext.getBlock(x, y + 1, z))
                    || BlockState.registry().isAirLike(ext.getBlock(x, y - 1, z))
                    || BlockState.registry().isAirLike(ext.getBlock(x, y, z + 1))
                    || BlockState.registry().isAirLike(ext.getBlock(x, y, z - 1));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #beside}: true when at least one of the 4 horizontal neighbours matches. */
    public static final class BesideMask implements Mask {

        private final Extent extent;
        private final Mask source;

        public BesideMask(Extent extent, Mask source) {
            this.extent = extent;
            this.source = source;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return source.test(x - 1, y, z) || source.test(x + 1, y, z)
                    || source.test(x, y, z - 1) || source.test(x, y, z + 1);
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #extrema}: matches the top/bottom of a surface (used by /ore, /overlay). */
    public static final class ExtremaMask implements Mask {

        private final Extent extent;
        private final int minY;
        private final int maxY;

        public ExtremaMask(Extent extent, int minY, int maxY) {
            this.extent = extent;
            this.minY = minY;
            this.maxY = maxY;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            BlockStateRegistry registry = BlockState.registry();
            if (y < minY || y > maxY) {
                return false;
            }
            boolean above = !registry.isAirLike(ext.getBlock(x, y + 1, z));
            boolean below = !registry.isAirLike(ext.getBlock(x, y - 1, z));
            return above ^ below;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class AxisMask implements Mask {

        private final int axis;
        private final double radius;

        public AxisMask(int axis, double radius) {
            this.axis = axis;
            this.radius = radius;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return switch (axis) {
                case 0 -> z == 0 && Math.abs(y) <= radius;
                case 1 -> x == 0 && z == 0;
                default -> x == 0 && y == 0;
            };
        }

        @Override
        public boolean isRegion() {
            return true;
        }
    }

    /**
     * {@code %<percentage>}: passes on a fraction of the positions, each one
     * decided by its own random draw, which is WorldEdit's random noise filter.
     */
    public static final class RandomMask implements Mask {

        private final Noise noise = new Noise.RandomNoise(0);
        private final double density;

        public RandomMask(double density) {
            this.density = density;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return noise.unit(x, y, z) <= density;
        }

        @Override
        public boolean isRegion() {
            return true;
        }
    }

    /** {@code #true} / {@code #false}. */
    public static final class ConstantMask implements Mask {

        private final boolean value;

        public ConstantMask(boolean value) {
            this.value = value;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return value;
        }

        @Override
        public boolean isRegion() {
            return true;
        }
    }

    public static final class NoiseMask implements Mask {

        private final Noise noise;
        private final double density;
        private final boolean use3D;
        private final Extent extent;

        public NoiseMask(Noise noise, double density, boolean use3D, Extent extent) {
            this.noise = noise;
            this.density = density;
            this.use3D = use3D;
            this.extent = extent;
        }

        @Override
        public boolean test(int x, int y, int z) {
            double value = use3D
                    ? noise.noise(x, y, z)
                    : noise.noise(x, z);
            return value > density;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #simplex}: simplex noise threshold, defaults to 50%. */
    /**
     * FAWE's {@code #simplex[scale][min][max]}: the mask passes where the noise
     * sits inside the band the two percentages describe, {@code 50} being the
     * middle of the noise.
     */
    public static final class SimplexMask implements Mask {

        private final Noise noise = new Noise.Simplex(0);
        private final double min;
        private final double max;
        private final double scale;

        public SimplexMask(double scale, double min, double max) {
            this.scale = scale <= 0 ? 0.02 : scale;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean test(int x, int y, int z) {
            double value = noise.noise(x * scale, y * scale, z * scale);
            return value >= min && value <= max;
        }
    }

    /** {@code #roc}: angle based on the ROC (run/rise) of the surrounding terrain. */
    /**
     * {@code #roc}: like {@code #angle}, but keeps the sign of the slope, so a
     * mask can tell a rising cliff from a falling one. FAWE samples it over four
     * blocks.
     */
    public static final class ROCAngleMask extends AngleMask {

        public ROCAngleMask(Extent extent, double min, double max, boolean overlay, int distance) {
            super(extent, min, max, overlay, distance);
        }

        @Override
        protected boolean testSlope(Extent extent, int x, int y, int z) {
            lastY = y;
            int base = getHeight(extent, x, y, z);
            double slope = rise(base, getHeight(extent, x + distance, y, z),
                    getHeight(extent, x - distance, y, z)) * ADJACENT_MOD;
            double tmp = rise(base, getHeight(extent, x, y, z + distance),
                    getHeight(extent, x, y, z - distance)) * ADJACENT_MOD;
            if (Math.abs(tmp) > Math.abs(slope)) {
                slope = tmp;
            }
            tmp = rise(base, getHeight(extent, x + distance, y, z + distance),
                    getHeight(extent, x - distance, y, z - distance)) * DIAGONAL_MOD;
            if (Math.abs(tmp) > Math.abs(slope)) {
                slope = tmp;
            }
            tmp = rise(base, getHeight(extent, x - distance, y, z + distance),
                    getHeight(extent, x + distance, y, z - distance)) * DIAGONAL_MOD;
            if (Math.abs(tmp) > Math.abs(slope)) {
                slope = tmp;
            }
            return lastValue = slope >= min && slope <= max;
        }

        /** {@code (high - base) - (base - low)}: the curvature around the base. */
        private static double rise(int base, int high, int low) {
            return (high - base) - (base - low);
        }
    }

    /** {@code ~[mask][min][max]}: how many of the six faces match the sub-mask. */
    public static final class AdjacentMask implements Mask {

        private final Mask source;
        private final int min;
        private final int max;

        public AdjacentMask(Mask source, int min, int max) {
            this.source = source;
            this.min = min;
            this.max = Math.max(max, min);
        }

        @Override
        public boolean test(int x, int y, int z) {
            int count = 0;
            for (int[] face : FACES) {
                if (source.test(x + face[0], y + face[1], z + face[2]) && ++count > max) {
                    return false;
                }
            }
            return count >= min;
        }
    }

    /** {@code ~[mask]}: true as soon as one of the six faces matches. */
    public static final class AdjacentAnyMask implements Mask {

        private final Mask source;

        public AdjacentAnyMask(Mask source) {
            this.source = source;
        }

        @Override
        public boolean test(int x, int y, int z) {
            for (int[] face : FACES) {
                if (source.test(x + face[0], y + face[1], z + face[2])) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code ~2d[mask][min][max]}: the same count over the four horizontal sides. */
    public static final class Adjacent2DMask implements Mask {

        private final Mask source;
        private final int min;
        private final int max;

        public Adjacent2DMask(Mask source, int min, int max) {
            this.source = source;
            this.min = min;
            this.max = Math.max(max, min);
        }

        @Override
        public boolean test(int x, int y, int z) {
            int count = 0;
            for (int[] side : SIDES) {
                if (source.test(x + side[0], y, z + side[1]) && ++count > max) {
                    return false;
                }
            }
            return count >= min;
        }
    }

    /** {@code ~2d[mask]}: true as soon as one horizontal side matches. */
    public static final class AdjacentAny2DMask implements Mask {

        private final Mask source;

        public AdjacentAny2DMask(Mask source) {
            this.source = source;
        }

        @Override
        public boolean test(int x, int y, int z) {
            for (int[] side : SIDES) {
                if (source.test(x + side[0], y, z + side[1])) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final int[][] FACES =
            {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** {@code #exposed}: faces the sky (WorldEdit's "exposed to air" surface). */
    public static final class ExposedMask implements Mask {

        private final Extent extent;

        public ExposedMask(Extent extent) {
            this.extent = extent;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            // A block is exposed when at least one face touches air.
            BlockStateRegistry registry = BlockState.registry();
            return registry.isAirLike(ext.getBlock(x, y + 1, z))
                    || registry.isAirLike(ext.getBlock(x, y - 1, z))
                    || registry.isAirLike(ext.getBlock(x + 1, y, z))
                    || registry.isAirLike(ext.getBlock(x - 1, y, z))
                    || registry.isAirLike(ext.getBlock(x, y, z + 1))
                    || registry.isAirLike(ext.getBlock(x, y, z - 1));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class ExpressionMask implements Mask {

        private final com.maxlananas.fawebim.core.expression.Expression compiled;
        private final Extent extent;
        private final Random random;

        public ExpressionMask(String input, Extent extent, Random random) {
            this.extent = extent;
            this.random = random;
            this.compiled = Expression.compile(input);
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = resolve(extent);
            int blockId = ext == null ? 0 : ext.getBlock(x, y, z);
            com.maxlananas.fawebim.core.expression.Expression.Variables vars = new com.maxlananas.fawebim.core.expression
                    .Expression.Variables();
            vars.set("x", x).set("y", y).set("z", z);
            vars.set("bx", x & 15).set("by", y & 15).set("bz", z & 15);
            vars.set("block", blockId);
            vars.set("random", random.nextDouble());
            return compiled.evaluate(vars) != 0;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    // ------------------------------------------------------------------ combinators

    public static final class UnionMask implements Mask {

        private final List<Mask> masks;

        public UnionMask(List<Mask> masks) {
            this.masks = masks;
        }

        @Override
        public boolean test(int x, int y, int z) {
            for (Mask mask : masks) {
                if (mask.test(x, y, z)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isRegion() {
            return masks.stream().allMatch(Mask::isRegion);
        }
    }

    public static final class IntersectionMask implements Mask {

        private final List<Mask> masks;

        public IntersectionMask(List<Mask> masks) {
            this.masks = masks;
        }

        @Override
        public boolean test(int x, int y, int z) {
            for (Mask mask : masks) {
                if (!mask.test(x, y, z)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isRegion() {
            return masks.stream().allMatch(Mask::isRegion);
        }
    }

    public static final class NegateMask implements Mask {

        private final Mask source;

        public NegateMask(Mask source) {
            this.source = source;
        }

        public Mask getSource() {
            return source;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return !source.test(x, y, z);
        }

        @Override
        public boolean isRegion() {
            return source.isRegion();
        }
    }

    /** A mask that also matches everything inside a region. */
    public static final class RegionIntersectionMask implements Mask {

        private final Region region;
        private final Mask mask;

        public RegionIntersectionMask(Region region, Mask mask) {
            this.region = region;
            this.mask = mask;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return region.contains(x, y, z) && mask.test(x, y, z);
        }
    }

    /** Source blend: only blocks that differ from the source extent match. */
    public static final class DifferenceMask implements Mask {

        private final Extent source;
        private final Extent target;

        public DifferenceMask(Extent source, Extent target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public boolean test(int x, int y, int z) {
            return source.getBlock(x, y, z) != target.getBlock(x, y, z);
        }
    }

    /** Mask matching the blocks currently in the player's hotbar ({@code /tool mask}). */
    public static final class HotbarMask implements Mask {

        private final Set<Integer> blocks;

        public HotbarMask(Set<Integer> blocks) {
            this.blocks = blocks;
        }

        @Override
        public boolean test(int x, int y, int z) {
            Extent ext = ExtentHolder.get();
            return ext != null && blocks.contains(ext.getBlock(x, y, z));
        }
    }
}
