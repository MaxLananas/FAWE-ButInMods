package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.LongSet;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * FAWE's polyhedral region: the skin of a convex hull. A block belongs to it
 * when its cube touches one of the hull's faces, which is FAWE's test - the
 * cube centred half a block from the block's corner, against each triangle of
 * the hull whose corners are the clicked blocks' corners.
 *
 * <p>The blocks are found face by face: each face is swept along the axis it
 * faces most, and only the blocks its plane crosses in a column are tested, so
 * the work follows the area of the skin rather than the volume of the box
 * around it. The set is kept until a vertex changes.</p>
 */
public final class PolyhedralRegion implements Region {

    private final ConvexPolyhedralRegion hull = new ConvexPolyhedralRegion();
    /** The skin, or {@code null} once the hull changed. */
    private LongSet skin;
    private long[] ordered;

    public PolyhedralRegion(int minY, int maxY) {
        hull.setBounds(minY, maxY);
    }

    public boolean addVertex(BlockVector3 vertex) {
        boolean changed = hull.addVertex(vertex);
        if (changed) {
            forget();
        }
        return changed;
    }

    public List<BlockVector3> getVertices() {
        return hull.getVertices();
    }

    /** The faces of the hull, each as its three vertices. */
    public List<BlockVector3[]> getTriangles() {
        return hull.getTriangles();
    }

    public boolean isDefined() {
        return hull.isDefined();
    }

    public void clear() {
        hull.clear();
        forget();
    }

    private void forget() {
        skin = null;
        ordered = null;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return hull.getMinimumPoint();
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return hull.getMaximumPoint();
    }

    @Override
    public long getVolume() {
        return isDefined() ? skin().size() : 0;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (!isDefined()) {
            return false;
        }
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        if (x < min.x() || y < min.y() || z < min.z() || x > max.x() || y > max.y() || z > max.z()) {
            return false;
        }
        return skin().contains(BlockKeys.key(x, y, z));
    }

    private LongSet skin() {
        if (skin == null) {
            LongSet found = new LongSet();
            BlockVector3 min = getMinimumPoint();
            BlockVector3 max = getMaximumPoint();
            for (BlockVector3[] triangle : hull.getTriangles()) {
                sweep(triangle[0], triangle[1], triangle[2], min, max, found);
            }
            skin = found;
        }
        return skin;
    }

    /**
     * Adds the blocks of the box {@code min..max} whose cube touches the
     * triangle. The triangle is walked in columns along the axis its normal
     * points along most; in each column its plane only crosses the few blocks
     * between its height at the column's four corners.
     */
    private static void sweep(BlockVector3 a, BlockVector3 b, BlockVector3 c, BlockVector3 min, BlockVector3 max,
                              LongSet out) {
        double[] v0 = {a.x(), a.y(), a.z()};
        double[] v1 = {b.x(), b.y(), b.z()};
        double[] v2 = {c.x(), c.y(), c.z()};
        double[] normal = cross(subtract(v1, v0), subtract(v2, v0));
        if (normal[0] == 0 && normal[1] == 0 && normal[2] == 0) {
            return;
        }
        int axis = Math.abs(normal[0]) >= Math.abs(normal[1]) && Math.abs(normal[0]) >= Math.abs(normal[2]) ? 0
                : Math.abs(normal[1]) >= Math.abs(normal[2]) ? 1 : 2;
        int first = axis == 0 ? 1 : 0;
        int second = axis == 2 ? 1 : 2;
        int[] low = {min.x(), min.y(), min.z()};
        int[] high = {max.x(), max.y(), max.z()};
        // A block touches a vertex from the block before it on each axis as well.
        int[] from = new int[3];
        int[] to = new int[3];
        for (int i = 0; i < 3; i++) {
            from[i] = Math.max(low[i], (int) Math.floor(Math.min(v0[i], Math.min(v1[i], v2[i]))) - 1);
            to[i] = Math.min(high[i], (int) Math.floor(Math.max(v0[i], Math.max(v1[i], v2[i]))));
        }
        double limit = normal[0] * v0[0] + normal[1] * v0[1] + normal[2] * v0[2];
        int[] cell = new int[3];
        for (int u = from[first]; u <= to[first]; u++) {
            for (int v = from[second]; v <= to[second]; v++) {
                // The plane's value along the axis at the corners of the column.
                double lowest = Double.POSITIVE_INFINITY;
                double highest = Double.NEGATIVE_INFINITY;
                for (int corner = 0; corner < 4; corner++) {
                    double cu = u + (corner & 1);
                    double cv = v + (corner >> 1);
                    double along = (limit - normal[first] * cu - normal[second] * cv) / normal[axis];
                    lowest = Math.min(lowest, along);
                    highest = Math.max(highest, along);
                }
                int start = Math.max(from[axis], (int) Math.ceil(lowest) - 1);
                int end = Math.min(to[axis], (int) Math.floor(highest));
                cell[first] = u;
                cell[second] = v;
                for (int w = start; w <= end; w++) {
                    cell[axis] = w;
                    if (touches(cell[0], cell[1], cell[2], v0, v1, v2)) {
                        out.add(BlockKeys.key(cell[0], cell[1], cell[2]));
                    }
                }
            }
        }
    }

    /**
     * Whether the cube of a block touches a triangle: the separating axis test
     * of Akenine-Möller, over the nine products of the cube's axes with the
     * triangle's edges, the cube's three axes and the triangle's normal. It
     * runs for every candidate block of a face, so it works on numbers only.
     */
    static boolean touches(int x, int y, int z, double[] a, double[] b, double[] c) {
        double cx = x + 0.5;
        double cy = y + 0.5;
        double cz = z + 0.5;
        double ax = a[0] - cx;
        double ay = a[1] - cy;
        double az = a[2] - cz;
        double bx = b[0] - cx;
        double by = b[1] - cy;
        double bz = b[2] - cz;
        double qx = c[0] - cx;
        double qy = c[1] - cy;
        double qz = c[2] - cz;
        if (separatedByEdge(bx - ax, by - ay, bz - az, ax, ay, az, bx, by, bz, qx, qy, qz)
                || separatedByEdge(qx - bx, qy - by, qz - bz, ax, ay, az, bx, by, bz, qx, qy, qz)
                || separatedByEdge(ax - qx, ay - qy, az - qz, ax, ay, az, bx, by, bz, qx, qy, qz)) {
            return false;
        }
        if (Math.min(ax, Math.min(bx, qx)) > HALF || Math.max(ax, Math.max(bx, qx)) < -HALF
                || Math.min(ay, Math.min(by, qy)) > HALF || Math.max(ay, Math.max(by, qy)) < -HALF
                || Math.min(az, Math.min(bz, qz)) > HALF || Math.max(az, Math.max(bz, qz)) < -HALF) {
            return false;
        }
        double ex = bx - ax;
        double ey = by - ay;
        double ez = bz - az;
        double fx = qx - bx;
        double fy = qy - by;
        double fz = qz - bz;
        double nx = ey * fz - ez * fy;
        double ny = ez * fx - ex * fz;
        double nz = ex * fy - ey * fx;
        double reach = HALF * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        return Math.abs(nx * ax + ny * ay + nz * az) <= reach;
    }

    /** Half the side of a block's cube. */
    private static final double HALF = 0.5;

    /**
     * Whether one of the three axes an edge {@code (ex, ey, ez)} makes with the
     * cube's axes separates the triangle from the cube.
     */
    private static boolean separatedByEdge(double ex, double ey, double ez, double ax, double ay, double az,
                                           double bx, double by, double bz, double cx, double cy, double cz) {
        // x cross the edge: (0, -ez, ey)
        if (separated(0, -ez, ey, ax, ay, az, bx, by, bz, cx, cy, cz)) {
            return true;
        }
        // y cross the edge: (ez, 0, -ex)
        if (separated(ez, 0, -ex, ax, ay, az, bx, by, bz, cx, cy, cz)) {
            return true;
        }
        // z cross the edge: (-ey, ex, 0)
        return separated(-ey, ex, 0, ax, ay, az, bx, by, bz, cx, cy, cz);
    }

    /** Whether the triangle's shadow on an axis misses the cube's. */
    private static boolean separated(double dx, double dy, double dz, double ax, double ay, double az,
                                     double bx, double by, double bz, double cx, double cy, double cz) {
        double reach = HALF * (Math.abs(dx) + Math.abs(dy) + Math.abs(dz));
        double pa = dx * ax + dy * ay + dz * az;
        double pb = dx * bx + dy * by + dz * bz;
        double pc = dx * cx + dy * cy + dz * cz;
        return Math.min(pa, Math.min(pb, pc)) > reach || Math.max(pa, Math.max(pb, pc)) < -reach;
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private long[] ordered() {
        if (ordered == null) {
            ordered = BlockKeys.chunkOrder(skin().toArray());
        }
        return ordered;
    }

    @Override
    public long forEachPosition(BlockVisitor visitor) {
        return isDefined() ? BlockKeys.forEach(ordered(), visitor) : 0;
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        return isDefined() ? BlockKeys.iterator(ordered()) : Collections.emptyIterator();
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        throw new InputException("A polyhedral selection cannot be expanded");
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        throw new InputException("A polyhedral selection cannot be contracted");
    }

    @Override
    public boolean shift(BlockVector3 amount) {
        boolean moved = hull.shift(amount);
        if (moved) {
            forget();
        }
        return moved;
    }

    @Override
    public PolyhedralRegion copy() {
        PolyhedralRegion copy = new PolyhedralRegion(hull.getMinY(), hull.getMaxY());
        for (BlockVector3 vertex : hull.getVertices()) {
            copy.addVertex(vertex);
        }
        return copy;
    }

    @Override
    public String describe() {
        return "polyhedral (" + getVertices().size() + " vertices)";
    }
}
