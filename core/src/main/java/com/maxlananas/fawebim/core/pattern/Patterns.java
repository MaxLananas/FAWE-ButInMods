package com.maxlananas.fawebim.core.pattern;

import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.RandomCollection;
import com.maxlananas.fawebim.core.util.noise.Noise;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Extent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** All pattern implementations FAWE exposes ({@code //set <pattern>}). */
public final class Patterns {

    private Patterns() {
    }

    /** A single fixed state. */
    public static final class Single implements Pattern {

        private final int stateId;

        public Single(int stateId) {
            this.stateId = stateId;
        }

        public int stateId() {
            return stateId;
        }

        @Override
        public int apply(int x, int y, int z) {
            return stateId;
        }
    }

    /** {@code 25%stone,75%dirt} — weighted random choice. */
    public static final class Weighted implements Pattern {

        private final RandomCollection<Pattern> children = new RandomCollection<>();
        private final Random random = new Random();

        public void add(double weight, Pattern pattern) {
            children.add(weight, pattern);
        }

        public boolean isEmpty() {
            return children.isEmpty();
        }

        @Override
        public int apply(int x, int y, int z) {
            Pattern chosen = children.next(random);
            return chosen == null ? 0 : chosen.apply(x, y, z);
        }

        /** The block a value in {@code [0, 1)} picks, used by the noise patterns. */
        public int applyAt(int x, int y, int z, double value) {
            Pattern chosen = children.next(value);
            return chosen == null ? 0 : chosen.apply(x, y, z);
        }

        /** How many blocks the choice is spread over. */
        public int size() {
            return children.size();
        }

        /** The pattern of an entry, in the order the list was written. */
        public Pattern get(int index) {
            return children.get(index);
        }

        /** The weight of an entry: 50 for the 50%stone of 50%stone,50%dirt, 1 when none is written. */
        public double weight(int index) {
            return children.getWeight(index);
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /**
     * FAWE's noise pattern, {@code #perlin[scale][dirt,stone]}: a noise generator
     * decides which of the inner blocks a position gets, so the same position
     * always answers the same way and the blocks come out in patches.
     */
    public static final class NoiseChoice implements Pattern {

        private final com.maxlananas.fawebim.core.util.noise.Noise generator;
        private final Weighted choices;
        private final double scale;

        public NoiseChoice(com.maxlananas.fawebim.core.util.noise.Noise generator,
                           Weighted choices, double scale) {
            this.generator = generator;
            this.choices = choices;
            this.scale = scale;
        }

        @Override
        public int apply(int x, int y, int z) {
            // FAWE's noise generators hand the value on as a float, and its
            // noise random keeps a value that rounds to one just under it.
            float value = (float) generator.unit(x * scale, y * scale, z * scale);
            return choices.applyAt(x, y, z, value >= 1 ? 0x1.fffffffffffffp-1 : value);
        }

        @Override
        public boolean isDeterministic() {
            return true;
        }
    }

    /**
     * WorldEdit's {@code #clipboard}: the clipboard repeated across the world
     * from its lowest corner, the block of a position being the clipboard's at
     * the position modulo its size, shifted by the offset of
     * {@code #clipboard@[x,y,z]}. It read the clipboard at its corner plus the
     * coordinates of the world, outside the clipboard and so air everywhere but
     * near the middle of the world; only {@code #fullcopy} repeated it.
     */
    public static final class ClipboardPattern implements Pattern {

        private final Extent clipboard;
        private final BlockVector3 min;
        private final BlockVector3 offset;
        private final int width;
        private final int height;
        private final int length;

        public ClipboardPattern(Extent clipboard, com.maxlananas.fawebim.core.math.BlockBox box, BlockVector3 offset) {
            this.clipboard = clipboard;
            this.min = box.min();
            this.offset = offset;
            this.width = Math.max(1, box.width());
            this.height = Math.max(1, box.height());
            this.length = Math.max(1, box.length());
        }

        @Override
        public int apply(int x, int y, int z) {
            return clipboard.getBlock(Math.floorMod(x + offset.x(), width) + min.x(),
                    Math.floorMod(y + offset.y(), height) + min.y(),
                    Math.floorMod(z + offset.z(), length) + min.z());
        }

        @Override
        public Extent extent() {
            return clipboard;
        }
    }

    /** {@code #existing} — keeps whatever block is already there. */
    /**
     * {@code #nx}/{@code #ny}/{@code #nz}: the inner pattern is asked about the
     * point without that coordinate, so noise and random patterns come out as
     * stripes along that axis, exactly as FAWE's {@code NoXPattern} does.
     */
    public static final class NoAxis implements Pattern {

        private final Pattern inner;
        private final int axis;

        public NoAxis(Pattern inner, int axis) {
            this.inner = inner;
            this.axis = axis;
        }

        @Override
        public int apply(int x, int y, int z) {
            return switch (axis) {
                case 0 -> inner.apply(0, y, z);
                case 1 -> inner.apply(x, 0, z);
                default -> inner.apply(x, y, 0);
            };
        }
    }

    /** {@code #mask[mask][pattern][pattern]}: one pattern where the mask matches. */
    public static final class Masked implements Pattern {

        private final com.maxlananas.fawebim.core.mask.Mask mask;
        private final Pattern matched;
        private final Pattern otherwise;

        public Masked(com.maxlananas.fawebim.core.mask.Mask mask, Pattern matched, Pattern otherwise) {
            this.mask = mask;
            this.matched = matched;
            this.otherwise = otherwise;
        }

        @Override
        public int apply(int x, int y, int z) {
            return mask.test(x, y, z) ? matched.apply(x, y, z) : otherwise.apply(x, y, z);
        }
    }

    /**
     * {@code #buffer[pattern][size]}: remembers the last results, so a pattern that
     * draws randomly gives neighbouring blocks the same result, FAWE's buffered
     * patterns. {@code #buffer2d} keys the cache on the two horizontal axes only.
     */
    /**
     * Answers a position from a cache, so a random inner pattern gives the same
     * block back for it.
     *
     * <p>The cache is a table indexed by the position itself rather than a map:
     * the pattern is asked about every block of an edit, and a map would box a
     * key and a value for each of the millions of positions a large one covers.
     * A position that collides with another takes the slot, which is the same
     * trade the map made when it dropped its eldest entry.</p>
     */
    public static final class Buffered implements Pattern {

        private final Pattern inner;
        private final boolean twoDimensional;
        private final long[] keys;
        private final int[] values;
        private final int mask;

        public Buffered(Pattern inner, int size, boolean twoDimensional) {
            this.inner = inner;
            this.twoDimensional = twoDimensional;
            int slots = Math.max(1, Integer.highestOneBit(Math.max(1, size) - 1) << 1);
            this.keys = new long[slots];
            this.values = new int[slots];
            this.mask = slots - 1;
        }

        @Override
        public int apply(int x, int y, int z) {
            long key = twoDimensional
                    ? ((long) (x & 0x3FFFFFF) << 26) | (z & 0x3FFFFFF)
                    : ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
            // The sign bit marks a filled slot, so a position at the origin is
            // not mistaken for an empty one.
            long stored = key | Long.MIN_VALUE;
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
            if (keys[slot] == stored) {
                return values[slot];
            }
            int state = inner.apply(x, y, z);
            keys[slot] = stored;
            values[slot] = state;
            return state;
        }
    }

    public static final class Existing implements Pattern {

        private final Extent extent;

        public Existing(Extent extent) {
            this.extent = extent;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent ext = extent != null ? extent : com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            return ext == null ? 0 : ext.getBlock(x, y, z);
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #biome[<biome>]} — places the biome's surface pattern. */
    public static final class Biome implements Pattern {

        private final int biomeId;
        private final Extent extent;

        public Biome(int biomeId, Extent extent) {
            this.biomeId = biomeId;
            this.extent = extent;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent ext = extent != null ? extent : com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            if (ext == null) {
                return 0;
            }
            // FAWE paints the biome's own surface: grass-like biomes get grass,
            // dry ones sand, cold ones snow, everything else keeps the terrain.
            String biome = BlockState.registry().biomeName(biomeId);
            boolean exposed = BlockState.registry().isAirLike(
                    ext.getBlock(x, y + 1, z));
            if (!exposed) {
                return BlockState.registry().defaultState("minecraft:stone");
            }
            if (biome == null) {
                return BlockState.registry().defaultState("minecraft:grass_block");
            }
            if (biome.contains("desert") || biome.contains("beach") || biome.contains("badlands")) {
                return BlockState.registry().defaultState("minecraft:sand");
            }
            if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) {
                return BlockState.registry().defaultState("minecraft:snow_block");
            }
            return BlockState.registry().defaultState("minecraft:grass_block");
        }
    }

    /** {@code #offset[x][y][z]} — shifts the pattern's own coordinates. */
    public static final class Offset implements Pattern {

        private final Pattern delegate;
        private final BlockVector3 offset;

        public Offset(Pattern delegate, BlockVector3 offset) {
            this.delegate = delegate;
            this.offset = offset;
        }

        @Override
        public int apply(int x, int y, int z) {
            return delegate.apply(x + offset.x(), y + offset.y(), z + offset.z());
        }

        @Override
        public BlockVector3 offset() {
            return offset;
        }
    }

    /**
     * FAWE's {@code #spread[pattern][x][y][z]} and {@code #solidspread}: the
     * pattern as it is at a random position up to the distances away, so a
     * pattern that depends on where it is - a clipboard, a noise - comes out
     * jittered.
     */
    public static final class RandomOffset implements Pattern {

        private final Pattern delegate;
        private final int dx;
        private final int dy;
        private final int dz;
        private final boolean solid;
        private final Random random = new Random();

        public RandomOffset(Pattern delegate, int dx, int dy, int dz, boolean solid) {
            this.delegate = delegate;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.solid = solid;
        }

        @Override
        public int apply(int x, int y, int z) {
            int ox = dx == 0 ? 0 : random.nextInt(dx * 2 + 1) - dx;
            int oy = dy == 0 ? 0 : random.nextInt(dy * 2 + 1) - dy;
            int oz = dz == 0 ? 0 : random.nextInt(dz * 2 + 1) - dz;
            int moved = delegate.apply(x + ox, y + oy, z + oz);
            // FAWE's #solidspread: the block of the offset position when it is
            // solid, else the block of the position itself.
            if (solid && !BlockState.registry().isSolid(moved)) {
                return delegate.apply(x, y, z);
            }
            return moved;
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /**
     * FAWE's {@code #relative[pattern]}: the pattern as it is at the position
     * less the first position the pattern is asked about, so a clipboard or a
     * noise starts where the edit does. It added the placement position to
     * every position instead.
     */
    public static final class Relative implements Pattern {

        private final Pattern delegate;
        private BlockVector3 origin;

        public Relative(Pattern delegate) {
            this.delegate = delegate;
        }

        @Override
        public int apply(int x, int y, int z) {
            BlockVector3 first = origin;
            if (first == null) {
                first = new BlockVector3(x, y, z);
                origin = first;
            }
            return delegate.apply(x - first.x(), y - first.y(), z - first.z());
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /**
     * FAWE's {@code #typeswap[input][output]}: the block a position holds with
     * {@code input} replaced by {@code output} in its id, and the properties it
     * had that the new block has - {@code #typeswap[spruce][oak]} makes spruce
     * planks oak planks and spruce stairs oak stairs. An input may list several,
     * {@code a,b} or {@code a|b}. A block whose id does not change, or changes
     * to no block, stays. It swapped a fixed list of blocks, whatever it was
     * given.
     */
    public static final class TypeSwap implements Pattern {

        private final String[] inputs;
        private final String output;

        public TypeSwap(String input, String output) {
            this.inputs = input.split("[|,]");
            this.output = output;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            BlockStateRegistry registry = BlockState.registry();
            if (extent == null) {
                return registry.air();
            }
            int id = extent.getBlock(x, y, z);
            String name = registry.name(id);
            String swapped = name;
            for (String input : inputs) {
                if (!input.isEmpty()) {
                    swapped = swapped.replace(input, output);
                }
            }
            if (swapped.equals(name)) {
                return id;
            }
            int target = registry.defaultState(swapped);
            if (target < 0) {
                return id;
            }
            int result = target;
            for (var entry : registry.properties(id).entrySet()) {
                int updated = registry.withProperty(result, entry.getKey(), entry.getValue());
                if (updated >= 0) {
                    result = updated;
                }
            }
            return result;
        }
    }

    /**
     * FAWE's {@code #linear[pattern]}: the entries of a list one after the other,
     * a block each, in the order the edit visits them.
     */
    public static final class LinearCycle implements Pattern {

        private final Pattern[] entries;
        private int index;

        public LinearCycle(Pattern[] entries) {
            this.entries = entries.clone();
        }

        @Override
        public int apply(int x, int y, int z) {
            index = (index + 1) % entries.length;
            return entries[index].apply(x, y, z);
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /**
     * FAWE's {@code #linear2d[pattern][xscale][zscale]} and
     * {@code #linear3d[pattern][xscale][yscale][zscale]}: the entry of a list a
     * position gets is a stripe of the sum of its coordinates, each divided by
     * its scale, so the entries run across the edit in diagonal bands, as wide
     * as their weights. A {@code yScale} of 0 leaves the height out.
     */
    public static final class LinearChoice implements Pattern {

        /** The entries, each repeated as its weight is to the others', as FAWE lays them out. */
        private final Pattern[] stripes;
        /** The entries and their running weights, for weights that do not lay out so. */
        private final Weighted weighted;
        private final double[] cumulative;
        private final double total;
        private final int xScale;
        private final int yScale;
        private final int zScale;

        public LinearChoice(Weighted choices, int xScale, int yScale, int zScale) {
            if (xScale == 0 || zScale == 0) {
                throw new IllegalArgumentException("a scale of 0");
            }
            this.xScale = xScale;
            this.yScale = yScale;
            this.zScale = zScale;
            this.stripes = stripes(choices);
            this.weighted = choices;
            this.cumulative = new double[choices.size()];
            double running = 0;
            for (int i = 0; i < cumulative.length; i++) {
                running += choices.weight(i);
                cumulative[i] = running;
            }
            this.total = running;
        }

        /**
         * FAWE's layout: every weight a hundredth at most, the entries repeated
         * weight over the greatest common divisor times; null when a weight has
         * finer parts or the stripes would be more than a hundred thousand.
         */
        private static Pattern[] stripes(Weighted choices) {
            int[] counts = new int[choices.size()];
            int gcd = 0;
            int max = 0;
            for (int i = 0; i < counts.length; i++) {
                double hundredths = choices.weight(i) * 100;
                counts[i] = (int) hundredths;
                if (counts[i] != hundredths) {
                    return null;
                }
                gcd = gcd(gcd, counts[i]);
                max = Math.max(max, counts[i]);
            }
            if (gcd == 0 || max / gcd > 100_000) {
                return null;
            }
            List<Pattern> out = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                for (int copy = 0; copy < counts[i] / gcd; copy++) {
                    out.add(choices.get(i));
                }
            }
            return out.toArray(new Pattern[0]);
        }

        private static int gcd(int a, int b) {
            return b == 0 ? a : gcd(b, a % b);
        }

        @Override
        public int apply(int x, int y, int z) {
            if (stripes != null) {
                int sum = Math.floorDiv(x, xScale) + Math.floorDiv(z, zScale)
                        + (yScale == 0 ? 0 : Math.floorDiv(y, yScale));
                return stripes[Math.floorMod(sum, stripes.length)].apply(x, y, z);
            }
            double value = Math.nextUp((double) x) / xScale + Math.nextUp((double) z) / zScale
                    + (yScale == 0 ? 0 : Math.nextUp((double) y) / yScale);
            value %= total;
            if (value < 0) {
                value += total;
            }
            for (int i = 0; i < cumulative.length; i++) {
                if (cumulative[i] >= value) {
                    return weighted.get(i).apply(x, y, z);
                }
            }
            return weighted.get(cumulative.length - 1).apply(x, y, z);
        }
    }

    /**
     * FAWE's {@code #surfacespread[pattern][distance]}: from each position, up to
     * {@code distance} random steps to a neighbour - diagonals included - that
     * the pattern makes solid and that has a side the pattern leaves open, so the
     * walk stays on the surface of what the pattern draws, and the pattern as it
     * is where the walk ends. With {@code #existing} it walks the terrain itself.
     */
    public static final class SurfaceSpread implements Pattern {

        private static final int[][] NEIGHBOURS = neighbours();

        private final Pattern delegate;
        private final int moves;
        private final int minY;
        private final int maxY;
        private final Random random = new Random();

        public SurfaceSpread(Pattern delegate, int distance, int minY, int maxY) {
            this.delegate = delegate;
            this.moves = Math.min(255, distance);
            this.minY = minY;
            this.maxY = maxY;
        }

        private static int[][] neighbours() {
            List<int[]> out = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) {
                            out.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
            return out.toArray(new int[0][]);
        }

        @Override
        public int apply(int x, int y, int z) {
            int[] allowed = new int[NEIGHBOURS.length];
            for (int move = 0; move < moves; move++) {
                int count = 0;
                for (int i = 0; i < NEIGHBOURS.length; i++) {
                    int[] step = NEIGHBOURS[i];
                    if (allowed(x + step[0], y + step[1], z + step[2])) {
                        allowed[count++] = i;
                    }
                }
                if (count == 0) {
                    break;
                }
                int[] step = NEIGHBOURS[allowed[random.nextInt(count)]];
                x += step[0];
                y += step[1];
                z += step[2];
            }
            return delegate.apply(x, y, z);
        }

        private boolean allowed(int x, int y, int z) {
            if (!BlockState.registry().isSolid(delegate.apply(x, y, z))) {
                return false;
            }
            return y < maxY && open(x, y + 1, z) || y > minY && open(x, y - 1, z)
                    || open(x + 1, y, z) || open(x - 1, y, z) || open(x, y, z + 1) || open(x, y, z - 1);
        }

        private boolean open(int x, int y, int z) {
            return !BlockState.registry().isSolid(delegate.apply(x, y, z));
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /** {@code =expr} — expression driven pattern. */
    public static final class ExpressionPattern implements Pattern {

        private final Expression expression;
        private final String input;
        /**
         * One thread's variables, reused from block to block with x, y and z
         * in the first three slots; what the expression assigns is forgotten
         * before the next block, as with a new set.
         */
        private final ThreadLocal<Expression.Variables> variables = ThreadLocal.withInitial(() -> {
            Expression.Variables vars = new Expression.Variables();
            vars.slot("x");
            vars.slot("y");
            vars.slot("z");
            return vars;
        });

        public ExpressionPattern(String input) {
            this.input = input;
            this.expression = Expression.compile(input);
        }

        @Override
        public int apply(int x, int y, int z) {
            Expression.Variables vars = variables.get();
            vars.keepFirst(3);
            vars.set(0, x);
            vars.set(1, y);
            vars.set(2, z);
            return (int) Math.floor(expression.evaluate(vars));
        }

        @Override
        public String describe() {
            return "=" + input;
        }
    }

    /** {@code ##tag} — pattern that follows the surface of the terrain. */
    public static final class Surface implements Pattern {

        @Override
        public int apply(int x, int y, int z) {
            return 0;
        }
    }

    /** {@code 33%stone,67%dirt} with per-block randomness but stable seeds. */
    public static final class RandomState implements Pattern {

        private final Random random = new Random();
        private final int[] states;

        public RandomState(int[] states) {
            this.states = states;
        }

        public RandomState(List<Integer> states) {
            this(states.stream().mapToInt(Integer::intValue).toArray());
        }

        @Override
        public int apply(int x, int y, int z) {
            return states[random.nextInt(states.length)];
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /**
     * WorldEdit's {@code ^} pattern. {@code ^oak_log} makes each block an oak
     * log with those properties of the block it replaces that an oak log has -
     * the axis of a log it replaces is kept - and {@code ^[facing=north]} keeps
     * each block and gives it the properties named that it has;
     * {@code ^oak_stairs[half=top]} does both. It copied the properties of the
     * block below instead.
     */
    public static final class TypeOrStateApplying implements Pattern {

        /** The default state of the type to apply, or -1 to keep each block's. */
        private final int type;
        private final Map<String, String> states;

        public TypeOrStateApplying(int type, Map<String, String> states) {
            this.type = type;
            this.states = Map.copyOf(states);
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            BlockStateRegistry registry = BlockState.registry();
            int old = extent == null ? registry.air() : extent.getBlock(x, y, z);
            int result = old;
            if (type >= 0) {
                result = type;
                for (Map.Entry<String, String> property : registry.properties(old).entrySet()) {
                    result = with(registry, result, property.getKey(), property.getValue());
                }
            }
            for (Map.Entry<String, String> property : states.entrySet()) {
                result = with(registry, result, property.getKey(), property.getValue());
            }
            return result;
        }

        /** The state with a property set, or unchanged when its block has no such property or value. */
        private static int with(BlockStateRegistry registry, int state, String property, String value) {
            int updated = registry.withProperty(state, property, value);
            return updated >= 0 ? updated : state;
        }
    }

    /**
     * {@code #color}: the block nearest a colour. The answer is the same for
     * every block, and it was searched for again at every one, through every
     * full block of the game and a boxed map lookup for each.
     */
    public static final class Color implements Pattern {

        private final int block;

        public Color(int rgb) {
            BlockStateRegistry registry = BlockState.registry();
            int closest = MapColors.palette(registry).closest(rgb & 0xFFFFFF);
            this.block = closest < 0 ? registry.air() : closest;
        }

        @Override
        public int apply(int x, int y, int z) {
            return block;
        }

        public int closest() {
            return block;
        }
    }

    /**
     * The block nearest in colour to the colour of the block a position holds,
     * changed: {@code #lighten} and {@code #darken} move its brightness,
     * {@code #desaturate[percent]} moves it that far towards its grey as FAWE
     * does, {@code #averagecolor[r][g][b][a]} averages it with a colour and
     * {@code #saturate[r][g][b][a]} multiplies it by one, as FAWE's do.
     */
    public static final class ColorAdjust implements Pattern {

        public enum Mode { LIGHTEN, DARKEN, SATURATE, DESATURATE, AVERAGE, MULTIPLY }

        private final Extent extent;
        private final Mode mode;
        private final double amount;
        /** The colour {@link Mode#AVERAGE} and {@link Mode#MULTIPLY} mix in, {@code 0xRRGGBB}. */
        private final int color;
        /**
         * The answer for every state met so far, plus one so that 0 means not
         * yet: it depends on the state only, and working it out went through
         * the whole palette, which was copied for every block.
         */
        private int[] answers = new int[0];

        public ColorAdjust(Extent extent, Mode mode, double amount) {
            this(extent, mode, amount, 0);
        }

        public ColorAdjust(Extent extent, Mode mode, double amount, int color) {
            this.extent = extent;
            this.mode = mode;
            this.amount = amount;
            this.color = color & 0xFFFFFF;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent ext = extent != null ? extent : com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            if (ext == null) {
                return 0;
            }
            int current = ext.getBlock(x, y, z);
            if (current < 0) {
                return adjust(current);
            }
            int[] known = answers;
            if (current >= known.length) {
                known = java.util.Arrays.copyOf(known, Math.max(current + 1, BlockState.registry().stateCount()));
                answers = known;
            }
            if (known[current] == 0) {
                known[current] = adjust(current) + 1;
            }
            return known[current] - 1;
        }

        private int adjust(int state) {
            BlockStateRegistry registry = BlockState.registry();
            int current = MapColors.colorOf(registry, state);
            int r = (current >> 16) & 0xFF;
            int g = (current >> 8) & 0xFF;
            int b = current & 0xFF;
            int rgb;
            switch (mode) {
                case DESATURATE -> {
                    // FAWE's: each channel that far towards the luminance.
                    double value = Math.max(0, Math.min(1, amount));
                    double luminance = 0.3f * r + 0.6f * g + 0.1f * b;
                    rgb = ((int) (r + value * (luminance - r)) << 16) | ((int) (g + value * (luminance - g)) << 8)
                            | (int) (b + value * (luminance - b));
                }
                case AVERAGE -> rgb = (((r + ((color >> 16) & 0xFF)) >> 1) << 16)
                        | (((g + ((color >> 8) & 0xFF)) >> 1) << 8) | ((b + (color & 0xFF)) >> 1);
                case MULTIPLY -> rgb = ((r * ((color >> 16) & 0xFF) / 255) << 16)
                        | ((g * ((color >> 8) & 0xFF) / 255) << 8) | (b * (color & 0xFF) / 255);
                default -> {
                    float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
                    switch (mode) {
                        case LIGHTEN -> hsb[2] = (float) Math.min(1, hsb[2] + amount);
                        case DARKEN -> hsb[2] = (float) Math.max(0, hsb[2] - amount);
                        default -> hsb[1] = (float) Math.min(1, hsb[1] + amount);
                    }
                    rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF;
                }
            }
            int closest = MapColors.palette(registry).closest(rgb);
            return closest < 0 ? state : closest;
        }
    }

    /**
     * FAWE's {@code #anglecolor[distance]}: the block nearest in colour to the
     * one a position holds, darkened by the slope of the terrain around it,
     * measured from the surface that far away on each side. A block that is
     * not solid stays, and so does one whose colour is black.
     */
    public static final class AngleColor implements Pattern {

        private final int distance;
        private final int minY;
        private final int maxY;
        private final double factor;

        public AngleColor(int distance, int minY, int maxY) {
            this.distance = distance;
            this.minY = minY;
            this.maxY = maxY;
            this.factor = (1d / distance) * (1d / maxY);
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            if (extent == null) {
                return BlockState.registry().air();
            }
            BlockStateRegistry registry = BlockState.registry();
            int block = extent.getBlock(x, y, z);
            if (!registry.isSolid(block)) {
                return block;
            }
            int color = MapColors.colorOf(registry, block);
            if (color == 0) {
                return block;
            }
            long slope = slope(extent, x, y, z);
            if (slope == 0) {
                return block;
            }
            double darken = 1 - Math.min(1, slope * factor);
            int rgb = ((int) (((color >> 16) & 0xFF) * darken) << 16) | ((int) (((color >> 8) & 0xFF) * darken) << 8)
                    | (int) ((color & 0xFF) * darken);
            int closest = MapColors.palette(registry).closest(rgb);
            return closest < 0 ? block : closest;
        }

        private long slope(Extent extent, int x, int y, int z) {
            int height = surface(extent, x, z, y);
            if (height > minY && !BlockState.registry().isSolid(extent.getBlock(x, height - 1, z))) {
                return Integer.MAX_VALUE;
            }
            int d = distance;
            return Math.abs(surface(extent, x + d, z, y) - surface(extent, x - d, z, y)) * 7L
                    + Math.abs(surface(extent, x, z + d, y) - surface(extent, x, z - d, y)) * 7L
                    + Math.abs(surface(extent, x + d, z + d, y) - surface(extent, x - d, z - d, y)) * 5L
                    + Math.abs(surface(extent, x - d, z + d, y) - surface(extent, x + d, z - d, y)) * 5L;
        }

        /**
         * The surface of a column nearest a height, as FAWE finds it: the first
         * change between solid and open met going up and down from there, the
         * solid block's height.
         */
        private int surface(Extent extent, int x, int z, int y) {
            BlockStateRegistry registry = BlockState.registry();
            y = Math.max(minY, Math.min(maxY, y));
            boolean open = !registry.isSolid(extent.getBlock(x, y, z));
            int offset = open ? 0 : 1;
            int clearanceAbove = maxY - y;
            int clearanceBelow = y - minY;
            int clearance = Math.min(clearanceAbove, clearanceBelow);
            for (int d = 0; d <= clearance; d++) {
                if (!registry.isSolid(extent.getBlock(x, y + d, z)) != open) {
                    return y + d - offset;
                }
                if (!registry.isSolid(extent.getBlock(x, y - d, z)) != open) {
                    return y - d + offset;
                }
            }
            if (clearanceAbove < clearanceBelow) {
                for (int layer = y - clearance - 1; layer >= minY; layer--) {
                    if (!registry.isSolid(extent.getBlock(x, layer, z)) != open) {
                        return layer + offset;
                    }
                }
            } else if (clearanceAbove > clearanceBelow) {
                for (int layer = y + clearance + 1; layer <= maxY; layer++) {
                    if (!registry.isSolid(extent.getBlock(x, layer, z)) != open) {
                        return layer - offset;
                    }
                }
            }
            return open ? minY : maxY;
        }
    }

    /** Noise driven pattern used by {@code #simplex}/{@code #perlin}. */
    public static final class NoisePattern implements Pattern {

        private final Noise noise;
        private final double scale;
        private final Pattern low;
        private final Pattern high;

        public NoisePattern(Noise noise, double scale, Pattern low, Pattern high) {
            this.noise = noise;
            this.scale = scale;
            this.low = low;
            this.high = high;
        }

        @Override
        public int apply(int x, int y, int z) {
            double value = noise.noise(x * scale, y * scale, z * scale);
            return value > 0 ? high.apply(x, y, z) : low.apply(x, y, z);
        }
    }
}
