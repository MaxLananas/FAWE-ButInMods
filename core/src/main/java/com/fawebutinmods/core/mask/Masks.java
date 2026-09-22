package com.fawebutinmods.core.mask;

import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.expression.Expression;
import com.fawebutinmods.core.region.Region;
import com.fawebutinmods.core.util.noise.Noise;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;
import com.fawebutinmods.core.world.Extent;

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
 * the upstream parsers; see {@code docs/COMMANDS.md} and the mask section of the
 * README for the complete list.</p>
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
        private final Set<Integer> states = new LinkedHashSet<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private final Set<String> categories = new LinkedHashSet<>();
        private final Set<String> names = new LinkedHashSet<>();
        private final List<String> raw = new ArrayList<>();

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

        public Set<Integer> getStates() {
            return states;
        }

        @Override
        public boolean test(BlockVector3 position) {
            return test(position.x(), position.y(), position.z());
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int id = ext.getBlock(position.x(), position.y(), position.z());
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int id = ext.getBlock(position.x(), position.y(), position.z());
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            return ext != null && BlockState.registry().isSolid(ext.getBlock(position.x(), position.y(), position.z()));
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            return ext != null && BlockState.registry().isLiquid(ext.getBlock(position.x(), position.y(), position.z()));
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            return ext != null && BlockState.registry().isFullCube(ext.getBlock(position.x(), position.y(), position.z()));
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            return ext != null && ext.getBiome(position.x(), position.y(), position.z()) == biomeId;
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
        public boolean test(BlockVector3 position) {
            return region.contains(position);
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
        public boolean test(BlockVector3 position) {
            Region region = supplier.get();
            return region != null && region.contains(position);
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
        public boolean test(BlockVector3 position) {
            return source.test(position.add(dx, dy, dz));
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
        public boolean test(BlockVector3 position) {
            if (source.test(position)) {
                return true;
            }
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int x = -radius; x <= radius; x++) {
                        if (source.test(position.add(x, y, z))) {
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

        private final Extent extent;

        public WallMask(Extent extent) {
            this.extent = extent;
        }

        @Override
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            BlockStateRegistry registry = BlockState.registry();
            int x = position.x();
            int y = position.y();
            int z = position.z();
            boolean northSouth = !registry.isAirLike(ext.getBlock(x, y, z - 1))
                    && !registry.isAirLike(ext.getBlock(x, y, z + 1));
            boolean eastWest = !registry.isAirLike(ext.getBlock(x - 1, y, z))
                    && !registry.isAirLike(ext.getBlock(x + 1, y, z));
            return northSouth ^ eastWest;
        }

        @Override
        public Extent extent() {
            return extent;
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            BlockStateRegistry registry = BlockState.registry();
            for (int i = 1; i <= offset; i++) {
                if (!registry.isAirLike(ext.getBlock(position.x(), position.y() + i, position.z()))) {
                    return false;
                }
            }
            if (alwaysAir) {
                return true;
            }
            return !registry.isAirLike(ext.getBlock(position.x(), position.y() - 1, position.z()))
                    || registry.isSolid(ext.getBlock(position.x(), position.y(), position.z()));
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #angle}: slope angle test, in tangent or degree units. */
    public static final class AngleMask implements Mask {

        private final Extent extent;
        private final double min;
        private final double max;
        private final boolean overlay;

        public AngleMask(Extent extent, double min, double max, boolean overlay) {
            this.extent = extent;
            this.min = min;
            this.max = max;
            this.overlay = overlay;
        }

        @Override
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int x = position.x();
            int y = position.y();
            int z = position.z();
            if (overlay && !BlockState.registry().isAirLike(ext.getBlock(x, y + 1, z))) {
                return false;
            }
            double height = ext.getBlock(x, y, z);
            double north = sample(ext, x, y, z - 1);
            double south = sample(ext, x, y, z + 1);
            double east = sample(ext, x + 1, y, z);
            double west = sample(ext, x - 1, y, z);
            double dx = east - west;
            double dz = south - north;
            double angle = Math.atan(Math.sqrt(dx * dx + dz * dz) / 2.0);
            double tan = Math.tan(angle);
            return tan >= min && tan <= max;
        }

        private double sample(Extent extent, int x, int y, int z) {
            for (int dy = 0; dy < 8; dy++) {
                if (!BlockState.registry().isAirLike(extent.getBlock(x, y + dy, z))) {
                    return y + dy;
                }
            }
            return y;
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
        public boolean test(BlockVector3 position) {
            int x = position.x();
            int y = position.y();
            int z = position.z();
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
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            BlockStateRegistry registry = BlockState.registry();
            int y = position.y();
            if (y < minY || y > maxY) {
                return false;
            }
            boolean above = !registry.isAirLike(ext.getBlock(position.x(), y + 1, position.z()));
            boolean below = !registry.isAirLike(ext.getBlock(position.x(), y - 1, position.z()));
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
        public boolean test(BlockVector3 position) {
            return switch (axis) {
                case 0 -> position.z() == 0 && Math.abs(position.y()) <= radius;
                case 1 -> position.x() == 0 && position.z() == 0;
                default -> position.x() == 0 && position.y() == 0;
            };
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
        public boolean test(BlockVector3 position) {
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
        public boolean test(BlockVector3 position) {
            double value = use3D
                    ? noise.noise(position.x(), position.y(), position.z())
                    : noise.noise(position.x(), position.z());
            return value > density;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #simplex}: simplex noise threshold, defaults to 50%. */
    public static final class SimplexMask implements Mask {

        private final Noise noise = new Noise.Simplex(0);
        private final double threshold;
        private final double scale;

        public SimplexMask(double threshold, double scale) {
            this.threshold = threshold;
            this.scale = scale <= 0 ? 0.02 : scale;
        }

        @Override
        public boolean test(BlockVector3 position) {
            double value = noise.noise(position.x() * scale, position.y() * scale, position.z() * scale);
            return (value + 1) / 2.0 >= threshold;
        }
    }

    /** {@code #roc}: angle based on the ROC (run/rise) of the surrounding terrain. */
    public static final class ROCAngleMask implements Mask {

        private final Extent extent;
        private final double min;
        private final double max;
        private final boolean overlay;

        public ROCAngleMask(Extent extent, double min, double max, boolean overlay) {
            this.extent = extent;
            this.min = min;
            this.max = max;
            this.overlay = overlay;
        }

        @Override
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            int x = position.x();
            int y = position.y();
            int z = position.z();
            if (overlay && !BlockState.registry().isAirLike(ext.getBlock(x, y + 1, z))) {
                return false;
            }
            double rise = 1;
            double run = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int height = ext.getBlock(x + dx, y, z + dz);
                    run += Math.abs(height - ext.getBlock(x, y, z));
                }
            }
            double angle = Math.atan2(rise, run / 8.0);
            double degrees = Math.toDegrees(angle);
            return degrees >= min && degrees <= max;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    public static final class AdjacentMask implements Mask {

        private final Extent extent;
        private final Mask source;
        private final int min;
        private final int max;

        public AdjacentMask(Extent extent, Mask source, int min, int max) {
            this.extent = extent;
            this.source = source;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean test(BlockVector3 position) {
            int count = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        if (source.test(position.x() + dx, position.y() + dy, position.z() + dz)) {
                            count++;
                        }
                    }
                }
            }
            return count >= min && count <= max;
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #exposed}: faces the sky (WorldEdit's "exposed to air" surface). */
    public static final class ExposedMask implements Mask {

        private final Extent extent;

        public ExposedMask(Extent extent) {
            this.extent = extent;
        }

        @Override
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            if (ext == null) {
                return false;
            }
            // A block is exposed when at least one face touches air.
            BlockStateRegistry registry = BlockState.registry();
            int x = position.x();
            int y = position.y();
            int z = position.z();
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

        private final com.fawebutinmods.core.expression.Expression compiled;
        private final Extent extent;
        private final Random random;

        public ExpressionMask(String input, Extent extent, Random random) {
            this.extent = extent;
            this.random = random;
            this.compiled = Expression.compile(input);
        }

        @Override
        public boolean test(BlockVector3 position) {
            Extent ext = resolve(extent);
            int blockId = ext == null ? 0 : ext.getBlock(position.x(), position.y(), position.z());
            com.fawebutinmods.core.expression.Expression.Variables vars = new com.fawebutinmods.core.expression
                    .Expression.Variables();
            vars.set("x", position.x()).set("y", position.y()).set("z", position.z());
            vars.set("bx", position.x() & 15).set("by", position.y() & 15).set("bz", position.z() & 15);
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
        public boolean test(BlockVector3 position) {
            for (Mask mask : masks) {
                if (mask.test(position)) {
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
        public boolean test(BlockVector3 position) {
            for (Mask mask : masks) {
                if (!mask.test(position)) {
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
        public boolean test(BlockVector3 position) {
            return !source.test(position);
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
        public boolean test(BlockVector3 position) {
            return region.contains(position) && mask.test(position);
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
        public boolean test(BlockVector3 position) {
            int x = position.x();
            int y = position.y();
            int z = position.z();
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
        public boolean test(BlockVector3 position) {
            Extent ext = ExtentHolder.get();
            return ext != null && blocks.contains(ext.getBlock(position.x(), position.y(), position.z()));
        }
    }
}
