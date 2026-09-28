package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.Buffers;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Erosion and filling of the blocks in a ball: WorldEdit's {@code /brush
 * morph} and {@code /brush dilate}, and FAWE's {@code /brush erode} and
 * {@code /brush pull}, which differ only in their settings.
 *
 * <p>Erosion turns a closed block with at least {@code erodeFaces} open
 * neighbours into the most common of those neighbours; filling turns an open
 * block with at least {@code fillFaces} closed neighbours into the most common
 * of those. The neighbours are met in the order north, east, south, west, up,
 * down, and each style settles on the most common one its own way, see
 * {@link Style}. The erosion passes run first, then the filling ones. Each
 * pass reads one buffer and writes the other, so what a pass changes is seen
 * by the next pass only and the result does not depend on the order the
 * blocks are visited in.</p>
 */
public final class Morphology {

    /** Which blocks are open, which cells belong to the ball, and how the most common neighbour is found. */
    public enum Style {
        /**
         * WorldEdit's morph: air and liquids are open, and the ball holds the
         * cells at most the radius away from the centre. Neighbours are counted
         * by state, and the state that reaches the highest count first wins.
         */
        MORPH,
        /**
         * FAWE's erode: what does not block movement is open, and the ball holds
         * the cells closer to the centre than the radius, the radius squared cut
         * down to a whole number. Neighbours are counted by block type, starting
         * from one: erosion needs a type met twice and keeps the first to reach
         * the highest count, filling takes a single neighbour and keeps the last.
         *
         * <p>FAWE's code reads only the four horizontal neighbours: its port to
         * 1.13 took them from {@code Direction.valuesOf(CARDINAL)}, which left
         * the filling of its erode preset (five faces of four) out of reach and
         * pull, VoxelSniper's fill preset, unable to raise flat ground. The six
         * faces the brushes were written with, as VoxelSniper's, are kept.</p>
         */
        ERODE
    }

    /** How many open or closed neighbours each kind of pass needs, and how many passes run. */
    public record Passes(int erodeFaces, int erodeIterations, int fillFaces, int fillIterations) {
    }

    /** North, east, south, west, up and down: WorldEdit's order, and that of its directions. */
    private static final int[][] FACES = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    private Morphology() {
    }

    /**
     * Runs the passes on the ball around {@code center} and writes the blocks
     * that changed through the session, where {@code mask} accepts them.
     *
     * @param open which states count as open
     * @return how many blocks changed
     */
    public static int apply(EditSession session, BlockVector3 center, double radius, Style style, Passes passes,
                            IntPredicate open, Mask mask) {
        IntUnaryOperator typeOf = style == Style.ERODE
                ? Operations.rememberedType(com.maxlananas.fawebim.core.world.BlockState.registry())
                : IntUnaryOperator.identity();
        int reach = reach(radius);
        int side = 2 * reach + 3;
        Buffers.checkInts((long) side * side * side, 2, "A brush of radius " + radius);
        int[] states = new int[side * side * side];
        int offset = reach + 1;
        int index = 0;
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    states[index++] = session.getBlock(center.x() + x - offset, center.y() + y - offset,
                            center.z() + z - offset);
                }
            }
        }
        int[] result = run(states, side, radius, style, passes, open, typeOf);
        int changed = 0;
        for (int y = 1; y < side - 1; y++) {
            for (int z = 1; z < side - 1; z++) {
                for (int x = 1; x < side - 1; x++) {
                    int cell = (y * side + z) * side + x;
                    if (!inside(x - offset, y - offset, z - offset, radius, style)) {
                        continue;
                    }
                    int wx = center.x() + x - offset;
                    int wy = center.y() + y - offset;
                    int wz = center.z() + z - offset;
                    if ((mask == null || mask.test(wx, wy, wz)) && session.setBlock(wx, wy, wz, result[cell])) {
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Runs the passes on a cube of states, {@code side} cells a side and
     * indexed {@code (y * side + z) * side + x}, whose centre cell is the centre
     * of the ball. The outermost layer is read and never written, and the ball
     * must fit inside it: {@code side} is at least {@code 2 * reach(radius) + 3}.
     *
     * @return the states after the passes; {@code states} may be reused for it
     */
    public static int[] run(int[] states, int side, double radius, Style style, Passes passes, IntPredicate open) {
        return run(states, side, radius, style, passes, open, IntUnaryOperator.identity());
    }

    /**
     * The same, with the block type of each state for the style that counts
     * neighbours by type.
     */
    public static int[] run(int[] states, int side, double radius, Style style, Passes passes, IntPredicate open,
                            IntUnaryOperator typeOf) {
        if (side < 2 * reach(radius) + 3) {
            throw new IllegalArgumentException("A cube of side " + side + " cannot hold a ball of radius " + radius);
        }
        int[] current = states;
        int[] next = states.clone();
        for (int i = 0; i < passes.erodeIterations(); i++) {
            if (!pass(current, next, side, radius, style, open, typeOf, true, passes.erodeFaces())) {
                // A pass that changed nothing leaves the input for the next
                // one as it was, which would change nothing either.
                break;
            }
            int[] swap = current;
            current = next;
            next = swap;
        }
        for (int i = 0; i < passes.fillIterations(); i++) {
            if (!pass(current, next, side, radius, style, open, typeOf, false, passes.fillFaces())) {
                break;
            }
            int[] swap = current;
            current = next;
            next = swap;
        }
        return current;
    }

    /** How far from the centre the ball reaches, in whole blocks. */
    private static int reach(double radius) {
        return (int) Math.ceil(Math.max(0, radius));
    }

    private static boolean inside(int dx, int dy, int dz, double radius, Style style) {
        long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
        return style == Style.MORPH ? distance <= radius * radius : distance < (long) (radius * radius);
    }

    /**
     * One pass from {@code from} into {@code to}: {@code erode} turns closed
     * cells open, otherwise open cells are filled.
     *
     * @return whether any cell changed
     */
    private static boolean pass(int[] from, int[] to, int side, double radius, Style style, IntPredicate open,
                                IntUnaryOperator typeOf, boolean erode, int faces) {
        System.arraycopy(from, 0, to, 0, from.length);
        int offset = side / 2;
        int[] keys = new int[FACES.length];
        int[] counts = new int[FACES.length];
        // WorldEdit's count starts from none and a state must beat the best;
        // FAWE's starts from one, and its filling lets a later type equal it.
        int start = style == Style.MORPH ? 0 : 1;
        boolean laterWinsTies = style == Style.ERODE && !erode;
        boolean changed = false;
        for (int y = 1; y < side - 1; y++) {
            for (int z = 1; z < side - 1; z++) {
                for (int x = 1; x < side - 1; x++) {
                    if (!inside(x - offset, y - offset, z - offset, radius, style)) {
                        continue;
                    }
                    int cell = (y * side + z) * side + x;
                    // Erosion works on closed cells and counts open faces; filling
                    // the other way round.
                    if (open.test(from[cell]) == erode) {
                        continue;
                    }
                    int total = 0;
                    int distinct = 0;
                    int highest = start;
                    int chosen = from[cell];
                    for (int[] face : FACES) {
                        int neighbour = from[((y + face[1]) * side + z + face[2]) * side + x + face[0]];
                        if (open.test(neighbour) != erode) {
                            continue;
                        }
                        total++;
                        int key = typeOf.applyAsInt(neighbour);
                        int slot = 0;
                        while (slot < distinct && keys[slot] != key) {
                            slot++;
                        }
                        if (slot == distinct) {
                            keys[distinct] = key;
                            counts[distinct++] = 0;
                        }
                        int count = ++counts[slot];
                        if (count > highest || (laterWinsTies && count == highest)) {
                            highest = count;
                            chosen = neighbour;
                        }
                    }
                    if (total >= faces && chosen != from[cell]) {
                        to[cell] = chosen;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
}
