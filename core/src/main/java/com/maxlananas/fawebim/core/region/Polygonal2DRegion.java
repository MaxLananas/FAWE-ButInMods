package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A 2D polygon extruded over a Y range ({@code //sel poly}).
 *
 * <p>A column is inside when it lies on the outline or inside it, which is
 * WorldEdit's test: the vertices and the edges belong to the selection. The
 * test is made in exact integer arithmetic. The previous one cast a ray in
 * doubles with half-open bounds on both axes, and a square from 0,0 to 10,10
 * left its x = 10 and z = 10 sides out.</p>
 *
 * <p>Walking the region computes, for each column of X, the runs of Z inside
 * the outline from the edges that cross it, so a large polygon is walked as a
 * handful of runs per column instead of a test per block, and nothing is
 * materialised.</p>
 */
public class Polygonal2DRegion implements Region {

    /**
     * The largest coordinate a vertex may have. Every product the exact test
     * forms then stays inside a {@code long}; Minecraft's world stops at 30
     * million, far below it.
     */
    private static final int MAX_COORDINATE = 1 << 30;

    private final List<BlockVector2> points = new ArrayList<>();
    private int minY;
    private int maxY;
    /** Cached exact volume, or -1 once the outline or the height changed. */
    private long volume = -1;
    private int minX;
    private int minZ;
    private int maxX;
    private int maxZ;

    public Polygonal2DRegion(List<BlockVector2> points, int minY, int maxY) {
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        setPoints(points);
    }

    /** The outline, as a read-only view: {@link #setPoints} is how it changes. */
    public List<BlockVector2> getPoints() {
        return Collections.unmodifiableList(points);
    }

    public void setPoints(List<BlockVector2> outline) {
        for (BlockVector2 point : outline) {
            check(point);
        }
        points.clear();
        points.addAll(outline);
        recalculate();
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public void setMinY(int y) {
        this.minY = y;
        normalizeY();
    }

    public void setMaxY(int y) {
        this.maxY = y;
        normalizeY();
    }

    /** Grows the Y range so it holds {@code y}, the way a click extends it. */
    public void expandY(int y) {
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        volume = -1;
    }

    private void normalizeY() {
        if (minY > maxY) {
            int swap = minY;
            minY = maxY;
            maxY = swap;
        }
        volume = -1;
    }

    public void addPoint(BlockVector2 point) {
        check(point);
        points.add(point);
        recalculate();
    }

    private static void check(BlockVector2 point) {
        if (Math.abs((long) point.x()) > MAX_COORDINATE || Math.abs((long) point.z()) > MAX_COORDINATE) {
            throw new com.maxlananas.fawebim.core.util.InputException("The polygon point " + point
                    + " is outside the world");
        }
    }

    private void recalculate() {
        volume = -1;
        minX = Integer.MAX_VALUE;
        minZ = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        maxZ = Integer.MIN_VALUE;
        for (BlockVector2 point : points) {
            minX = Math.min(minX, point.x());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxZ = Math.max(maxZ, point.z());
        }
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return points.isEmpty() ? BlockVector3.ZERO : new BlockVector3(minX, minY, minZ);
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return points.isEmpty() ? BlockVector3.ZERO : new BlockVector3(maxX, maxY, maxZ);
    }

    /**
     * The number of blocks inside, exactly: the columns inside the outline times
     * the height. The count used to be the polygon's area plus half its vertex
     * count, which is neither WorldEdit's area nor the blocks that get edited.
     * Counted once per outline and kept.
     */
    @Override
    public long getVolume() {
        if (points.size() < 3) {
            return 0;
        }
        if (volume < 0) {
            long columns = 0;
            for (int x = minX; x <= maxX; x++) {
                int count = spans(x);
                for (int i = 0; i < count; i += 2) {
                    columns += (long) runs[i + 1] - runs[i] + 1;
                }
            }
            volume = columns * ((long) maxY - minY + 1);
        }
        return volume;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return y >= minY && y <= maxY && containsColumn(x, z);
    }

    /**
     * WorldEdit's test, in exact arithmetic: a vertex or a point of an edge is
     * inside; any other point is inside when an odd number of edges pass above
     * it in its column, counting an edge on the half-open range of X it spans.
     */
    public boolean containsColumn(int x, int z) {
        int n = points.size();
        if (n < 3) {
            return false;
        }
        boolean inside = false;
        BlockVector2 previous = points.get(n - 1);
        for (BlockVector2 current : points) {
            if (current.x() == x && current.z() == z) {
                return true;
            }
            int x1;
            int z1;
            int x2;
            int z2;
            if (current.x() > previous.x()) {
                x1 = previous.x();
                z1 = previous.z();
                x2 = current.x();
                z2 = current.z();
            } else {
                x1 = current.x();
                z1 = current.z();
                x2 = previous.x();
                z2 = previous.z();
            }
            if (x1 <= x && x <= x2) {
                long cross = ((long) z - z1) * ((long) x2 - x1) - ((long) z2 - z1) * ((long) x - x1);
                if (cross == 0) {
                    if ((z1 <= z) == (z <= z2)) {
                        return true;
                    }
                } else if (cross < 0 && x1 != x) {
                    inside = !inside;
                }
            }
            previous = current;
        }
        return inside;
    }

    /*
     * Buffers of the column runs, reused from column to column. A region is used
     * by one thread at a time, like the rest of the engine's shapes.
     */
    private long[] whole = new long[0];
    private long[] remainder = new long[0];
    private long[] denominator = new long[0];
    private int[] order = new int[0];
    private long[] runFrom = new long[0];
    private long[] runTo = new long[0];
    /** The runs {@link #spans} found, as {@code from, to} pairs. */
    private int[] runs = new int[0];

    /**
     * The runs of Z inside the outline in one column, merged and in increasing
     * order, written to {@link #runs} as {@code from, to} pairs.
     *
     * <p>An edge crossing the column on its half-open X range crosses it at a
     * rational Z. Between two crossings in increasing order the count of edges
     * above a point changes parity, so the inside of the column is the runs
     * from each odd crossing, rounded up, to the next one, rounded down; the
     * rounding also takes in a crossing that falls on a block, which is on the
     * outline. The vertices and the vertical edges in the column are the rest of
     * the outline and are added as runs of their own.</p>
     *
     * @return the number of ints written: twice the number of runs
     */
    private int spans(int x) {
        int n = points.size();
        if (whole.length < n) {
            whole = new long[n];
            remainder = new long[n];
            denominator = new long[n];
            order = new int[n];
            // Every edge adds at most one crossing or one extra run, and every
            // vertex one extra run.
            runFrom = new long[2 * n];
            runTo = new long[2 * n];
            runs = new int[4 * n];
        }
        int crossings = 0;
        int count = 0;
        BlockVector2 previous = points.get(n - 1);
        for (BlockVector2 current : points) {
            int x1;
            int z1;
            int x2;
            int z2;
            if (current.x() > previous.x()) {
                x1 = previous.x();
                z1 = previous.z();
                x2 = current.x();
                z2 = current.z();
            } else {
                x1 = current.x();
                z1 = current.z();
                x2 = previous.x();
                z2 = previous.z();
            }
            if (x1 == x2) {
                if (x1 == x) {
                    runFrom[count] = Math.min(z1, z2);
                    runTo[count] = Math.max(z1, z2);
                    count++;
                }
            } else if (x1 < x && x <= x2) {
                long den = (long) x2 - x1;
                long num = (long) z1 * den + ((long) z2 - z1) * ((long) x - x1);
                whole[crossings] = Math.floorDiv(num, den);
                remainder[crossings] = Math.floorMod(num, den);
                denominator[crossings] = den;
                order[crossings] = crossings;
                crossings++;
            }
            if (current.x() == x) {
                runFrom[count] = current.z();
                runTo[count] = current.z();
                count++;
            }
            previous = current;
        }
        sortCrossings(crossings);
        for (int i = 0; i + 1 < crossings; i += 2) {
            int low = order[i];
            int high = order[i + 1];
            long start = whole[low] + (remainder[low] != 0 ? 1 : 0);
            long end = whole[high];
            if (start <= end) {
                runFrom[count] = start;
                runTo[count] = end;
                count++;
            }
        }
        return merge(count);
    }

    /**
     * Orders the crossings by their exact value, whole + remainder / denominator:
     * the remainders are below their denominators, so their cross products fit
     * in a {@code long}. A column is crossed by a few edges and is sorted in
     * place; an outline with many edges over the same columns takes the general
     * sort.
     */
    private void sortCrossings(int count) {
        if (count > 32) {
            Integer[] boxed = new Integer[count];
            for (int i = 0; i < count; i++) {
                boxed[i] = order[i];
            }
            Arrays.sort(boxed, this::compareCrossings);
            for (int i = 0; i < count; i++) {
                order[i] = boxed[i];
            }
            return;
        }
        for (int i = 1; i < count; i++) {
            int item = order[i];
            int j = i - 1;
            while (j >= 0 && compareCrossings(order[j], item) > 0) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = item;
        }
    }

    private int compareCrossings(int a, int b) {
        int byWhole = Long.compare(whole[a], whole[b]);
        return byWhole != 0 ? byWhole
                : Long.compare(remainder[a] * denominator[b], remainder[b] * denominator[a]);
    }

    /** Sorts the runs by their start, merges the ones that touch, and writes them out. */
    private int merge(int count) {
        if (count > 32) {
            Integer[] boxed = new Integer[count];
            for (int i = 0; i < count; i++) {
                boxed[i] = i;
            }
            Arrays.sort(boxed, (a, b) -> Long.compare(runFrom[a], runFrom[b]));
            long[] from = new long[count];
            long[] to = new long[count];
            for (int i = 0; i < count; i++) {
                from[i] = runFrom[boxed[i]];
                to[i] = runTo[boxed[i]];
            }
            System.arraycopy(from, 0, runFrom, 0, count);
            System.arraycopy(to, 0, runTo, 0, count);
        }
        for (int i = 1; i < count; i++) {
            long from = runFrom[i];
            long to = runTo[i];
            int j = i - 1;
            while (j >= 0 && runFrom[j] > from) {
                runFrom[j + 1] = runFrom[j];
                runTo[j + 1] = runTo[j];
                j--;
            }
            runFrom[j + 1] = from;
            runTo[j + 1] = to;
        }
        int written = 0;
        for (int i = 0; i < count; i++) {
            if (written > 0 && runFrom[i] <= (long) runs[written - 1] + 1) {
                runs[written - 1] = (int) Math.max(runs[written - 1], runTo[i]);
            } else {
                runs[written++] = (int) runFrom[i];
                runs[written++] = (int) runTo[i];
            }
        }
        return written;
    }

    /**
     * Only the height of a polygon grows or shrinks, as in WorldEdit: there is
     * no single way to push an outline out by a number of blocks.
     */
    @Override
    public boolean expand(BlockVector3 amount) {
        if (amount.x() != 0 || amount.z() != 0) {
            throw new com.maxlananas.fawebim.core.util.InputException(
                    "A polygon selection can only be expanded vertically");
        }
        if (amount.y() > 0) {
            maxY += amount.y();
        } else {
            minY += amount.y();
        }
        volume = -1;
        return amount.y() != 0;
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        if (amount.x() != 0 || amount.z() != 0) {
            throw new com.maxlananas.fawebim.core.util.InputException(
                    "A polygon selection can only be contracted vertically");
        }
        int height = maxY - minY;
        if (amount.y() > 0) {
            minY += Math.min(height, amount.y());
        } else {
            maxY += Math.max(-height, amount.y());
        }
        volume = -1;
        return amount.y() != 0;
    }

    @Override
    public boolean shift(BlockVector3 amount) {
        List<BlockVector2> moved = new ArrayList<>(points.size());
        for (BlockVector2 point : points) {
            moved.add(new BlockVector2(point.x() + amount.x(), point.z() + amount.z()));
        }
        setPoints(moved);
        minY += amount.y();
        maxY += amount.y();
        return !amount.equals(BlockVector3.ZERO);
    }

    /** The columns of a chunk: the full height where the outline holds the column. */
    private void fillColumns(int baseX, int baseZ, int[] lo, int[] hi) {
        Arrays.fill(lo, 1);
        Arrays.fill(hi, 0);
        if (points.size() < 3) {
            return;
        }
        for (int x = 0; x < 16; x++) {
            int count = spans(baseX + x);
            for (int i = 0; i < count; i += 2) {
                int from = Math.max(runs[i], baseZ);
                int to = Math.min(runs[i + 1], baseZ + 15);
                for (int z = from; z <= to; z++) {
                    int column = (z - baseZ) << 4 | x;
                    lo[column] = minY;
                    hi[column] = maxY;
                }
            }
        }
    }

    @Override
    public long forEachPosition(BlockVisitor visitor) {
        if (points.size() < 3) {
            return 0;
        }
        return ColumnWalk.walk(minX, minZ, maxX, maxZ, this::fillColumns, visitor);
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        if (points.size() < 3) {
            return Collections.emptyIterator();
        }
        return ColumnWalk.iterator(minX, minZ, maxX, maxZ, this::fillColumns);
    }

    @Override
    public String describe() {
        return "poly (" + points.size() + " points, y " + minY + ".." + maxY + ")";
    }
}
