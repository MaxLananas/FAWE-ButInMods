package com.maxlananas.fawebim.core.tool;

import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Extent;

import java.util.Arrays;
import java.util.BitSet;

/**
 * What one left click of WorldEdit's super pickaxes breaks.
 *
 * <ul>
 *   <li>single: the clicked block;</li>
 *   <li>area: every block of the clicked block's type in the cube of the range
 *       around it;</li>
 *   <li>recursive: the blocks of that type joined to it face to face, none
 *       further from it than the range.</li>
 * </ul>
 *
 * <p>Blocks of a type are the same whatever their properties, so an area pick
 * on a log takes the logs lying the other way too. A click on air breaks
 * nothing. The positions come in the order WorldEdit breaks them, so a change
 * limit reached halfway leaves the same blocks standing.</p>
 *
 * <p>Planning only reads the world: the caller breaks the blocks through its
 * edit session, which records them for undo, and can look at each block before
 * it goes - for the items it drops. WorldEdit's flood fill tool walks the way
 * the recursive pick does, and plans with it.</p>
 */
public final class SuperPickaxe {

    public static final int SINGLE = 0;
    public static final int AREA = 1;
    public static final int RECURSIVE = 2;

    /**
     * The largest range a configuration can allow. A recursive pick indexes
     * the cube around the click, and a click is answered on the server thread:
     * a cube of 513 blocks a side is already far beyond a pickaxe.
     */
    public static final int MAX_RANGE = 256;

    private SuperPickaxe() {
    }

    /** The largest range the configuration allows now: {@code limits.max-super-pickaxe-size}. */
    public static int ceiling() {
        return Math.max(0, Math.min(com.maxlananas.fawebim.core.platform.Config.get().maxSuperPickaxeSize, MAX_RANGE));
    }

    /** Refuses a range the configuration does not allow, as the command that sets it. */
    public static void checkRange(double range) {
        if (range < 0) {
            throw com.maxlananas.fawebim.core.command.CommandRegistry.error("The range cannot be negative");
        }
        int ceiling = ceiling();
        if (range > ceiling) {
            throw com.maxlananas.fawebim.core.command.CommandRegistry.error("The range is at most " + ceiling);
        }
    }

    /**
     * The blocks one click breaks, as {@code x, y, z} triples in breaking order.
     *
     * @param range the range of the area or recursive pick, ignored by the single one
     */
    public static int[] targets(Extent world, int mode, double range, int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return new int[0];
        }
        BlockStateRegistry registry = BlockState.registry();
        int clicked = world.getBlock(x, y, z);
        if (registry.isAirLike(clicked)) {
            return new int[0];
        }
        if (!(range >= 0) || range > MAX_RANGE) {
            throw new IllegalArgumentException("Super pickaxe range out of 0.." + MAX_RANGE + ": " + range);
        }
        SameType type = new SameType(registry, registry.name(clicked));
        return switch (mode) {
            case SINGLE -> new int[]{x, y, z};
            case AREA -> area(world, type, (int) range, x, y, z);
            case RECURSIVE -> recursive(world, type, range, x, y, z);
            default -> throw new IllegalArgumentException("Unknown super pickaxe mode " + mode);
        };
    }

    private static int[] area(Extent world, SameType type, int range, int ox, int oy, int oz) {
        Triples out = new Triples();
        int minY = Math.max(world.minY(), oy - range);
        int maxY = Math.min(world.maxY(), oy + range);
        for (int x = ox - range; x <= ox + range; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = oz - range; z <= oz + range; z++) {
                    if (type.test(world.getBlock(x, y, z))) {
                        out.add(x, y, z);
                    }
                }
            }
        }
        return out.toArray();
    }

    /**
     * A depth-first walk in the order of WorldEdit's recursion - east, west,
     * south, north, up, down - with its own stack instead of the call stack,
     * over a bit set of the cube the range fits in instead of a set of
     * positions.
     */
    private static int[] recursive(Extent world, SameType type, double range, int ox, int oy, int oz) {
        int radius = (int) Math.floor(range);
        int side = 2 * radius + 1;
        double reach = range * range;
        BitSet visited = new BitSet(side * side * side);
        Triples out = new Triples();
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = index(0, 0, 0, radius, side);
        while (top > 0) {
            int index = stack[--top];
            if (visited.get(index)) {
                continue;
            }
            visited.set(index);
            int dz = index % side - radius;
            int dy = index / side % side - radius;
            int dx = index / (side * side) - radius;
            int y = oy + dy;
            if (y < world.minY() || y > world.maxY() || !type.test(world.getBlock(ox + dx, y, oz + dz))) {
                continue;
            }
            out.add(ox + dx, y, oz + dz);
            if (top + 6 > stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            // Pushed in reverse so that east comes off the stack first.
            top = push(stack, top, dx, dy - 1, dz, radius, side, reach);
            top = push(stack, top, dx, dy + 1, dz, radius, side, reach);
            top = push(stack, top, dx, dy, dz - 1, radius, side, reach);
            top = push(stack, top, dx, dy, dz + 1, radius, side, reach);
            top = push(stack, top, dx - 1, dy, dz, radius, side, reach);
            top = push(stack, top, dx + 1, dy, dz, radius, side, reach);
        }
        return out.toArray();
    }

    /** Pushes a neighbour that is within the range; every such neighbour is inside the cube. */
    private static int push(int[] stack, int top, int dx, int dy, int dz, int radius, int side, double reach) {
        if ((double) dx * dx + (double) dy * dy + (double) dz * dz > reach) {
            return top;
        }
        stack[top] = index(dx, dy, dz, radius, side);
        return top + 1;
    }

    private static int index(int dx, int dy, int dz, int radius, int side) {
        return ((dx + radius) * side + dy + radius) * side + dz + radius;
    }

    /** Whether a state is of the clicked block's type, answered once per state. */
    private static final class SameType {
        private final BlockStateRegistry registry;
        private final String name;
        private final BitSet known = new BitSet();
        private final BitSet same = new BitSet();

        SameType(BlockStateRegistry registry, String name) {
            this.registry = registry;
            this.name = name;
        }

        boolean test(int state) {
            if (state < 0) {
                return false;
            }
            if (!known.get(state)) {
                known.set(state);
                if (name.equals(registry.name(state))) {
                    same.set(state);
                }
            }
            return same.get(state);
        }
    }

    /** A growing array of coordinate triples. */
    private static final class Triples {
        private int[] values = new int[48];
        private int size;

        void add(int x, int y, int z) {
            if (size + 3 > values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = x;
            values[size++] = y;
            values[size++] = z;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
