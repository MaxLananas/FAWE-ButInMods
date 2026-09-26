package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A convex polyhedron: the convex hull of the vertices a player clicked
 * ({@code //sel convex}).
 *
 * <p>The hull is built the way WorldEdit builds it, one vertex at a time: the
 * faces the new vertex can see are removed and the hole is closed with faces
 * from its rim to the vertex. A vertex inside the hull, or on the line of the
 * first two, waits in a backlog until the hull has a volume. A point is inside
 * when no face has it above its plane.</p>
 *
 * <p>The faces are kept as integer planes relative to the first vertex, so the
 * test is exact: a block on a face belongs to the selection, whatever the
 * rounding of a normalised normal would have said. The products involved fit in
 * a {@code long} while every vertex is within {@value #MAX_SPAN} blocks of the
 * first one, which is the limit of a convex selection.</p>
 */
public class ConvexPolyhedralRegion implements Region {

    /** How far a vertex may be from the first one on any axis. */
    public static final int MAX_SPAN = 500_000;

    private final List<BlockVector3> vertices = new ArrayList<>();
    private final Set<BlockVector3> backlog = new LinkedHashSet<>();
    private final List<Face> faces = new ArrayList<>();
    private BlockVector3 origin;
    private BlockVector3 minimum;
    private BlockVector3 maximum;
    private int minY = Integer.MIN_VALUE / 2;
    private int maxY = Integer.MAX_VALUE / 2;
    /** The face that last had a point above it, which usually has the next one too. */
    private Face lastOutside;

    /** Clips the region to a range of Y, the world's. */
    public void setBounds(int minY, int maxY) {
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public void setMaxY(int maxY) {
        this.maxY = maxY;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMinY() {
        return minY;
    }

    /** The vertices of the hull, then the ones still in the backlog. */
    public List<BlockVector3> getVertices() {
        if (backlog.isEmpty()) {
            return Collections.unmodifiableList(vertices);
        }
        List<BlockVector3> all = new ArrayList<>(vertices);
        all.addAll(backlog);
        return all;
    }

    public int getTriangleCount() {
        return faces.size();
    }

    /** True once the hull has faces, i.e. three vertices that are not on a line. */
    public boolean isDefined() {
        return !faces.isEmpty();
    }

    public void clear() {
        vertices.clear();
        backlog.clear();
        faces.clear();
        origin = null;
        minimum = null;
        maximum = null;
        lastOutside = null;
    }

    /**
     * Adds a vertex to the hull.
     *
     * @return whether the selection changed
     */
    public boolean addVertex(BlockVector3 vertex) {
        lastOutside = null;
        if (vertices.contains(vertex)) {
            return false;
        }
        if (origin != null && (Math.abs((long) vertex.x() - origin.x()) > MAX_SPAN
                || Math.abs((long) vertex.y() - origin.y()) > MAX_SPAN
                || Math.abs((long) vertex.z() - origin.z()) > MAX_SPAN)) {
            throw new com.maxlananas.fawebim.core.util.InputException("A convex selection spans at most "
                    + MAX_SPAN + " blocks from its first point");
        }
        if (vertices.size() == 3) {
            if (backlog.contains(vertex)) {
                return false;
            }
            if (insideHull(vertex)) {
                return backlog.add(vertex);
            }
        } else if (vertices.size() == 2 && collinear(vertices.get(0), vertices.get(1), vertex)) {
            return backlog.add(vertex);
        }

        if (origin == null) {
            origin = vertex;
        }
        vertices.add(vertex);
        minimum = minimum == null ? vertex : minimum.min(vertex);
        maximum = maximum == null ? vertex : maximum.max(vertex);

        if (vertices.size() < 3) {
            return true;
        }
        if (vertices.size() == 3) {
            // Two faces back to back: a flat hull that later vertices open up.
            BlockVector3 a = vertices.get(0);
            BlockVector3 b = vertices.get(1);
            BlockVector3 c = vertices.get(2);
            faces.add(face(a, b, c));
            faces.add(face(a, c, b));
            return true;
        }

        // The faces the vertex sees go; their edges that only one of them had
        // are the rim of the hole, closed with faces to the new vertex.
        Set<Edge> rim = new LinkedHashSet<>();
        for (Iterator<Face> it = faces.iterator(); it.hasNext(); ) {
            Face face = it.next();
            if (!face.above(vertex.x() - origin.x(), vertex.y() - origin.y(), vertex.z() - origin.z())) {
                continue;
            }
            it.remove();
            for (int i = 0; i < 3; i++) {
                Edge edge = face.edge(i);
                if (!rim.remove(edge)) {
                    rim.add(edge);
                }
            }
        }
        for (Edge edge : rim) {
            faces.add(face(edge.start, edge.end, vertex));
        }

        if (!backlog.isEmpty()) {
            // The hull has a volume now: the vertices that waited are added
            // again, before the new one, which is what WorldEdit does too.
            vertices.remove(vertices.size() - 1);
            List<BlockVector3> waiting = new ArrayList<>(backlog);
            backlog.clear();
            for (BlockVector3 each : waiting) {
                addVertex(each);
            }
            vertices.add(vertex);
        }
        return true;
    }

    private static boolean collinear(BlockVector3 a, BlockVector3 b, BlockVector3 c) {
        long abx = (long) b.x() - a.x();
        long aby = (long) b.y() - a.y();
        long abz = (long) b.z() - a.z();
        long acx = (long) c.x() - a.x();
        long acy = (long) c.y() - a.y();
        long acz = (long) c.z() - a.z();
        return aby * acz - abz * acy == 0 && abz * acx - abx * acz == 0 && abx * acy - aby * acx == 0;
    }

    private Face face(BlockVector3 a, BlockVector3 b, BlockVector3 c) {
        return new Face(a, b, c, origin);
    }

    /** Whether a vertex is inside the current hull, faces included. */
    private boolean insideHull(BlockVector3 vertex) {
        long x = (long) vertex.x() - origin.x();
        long y = (long) vertex.y() - origin.y();
        long z = (long) vertex.z() - origin.z();
        for (Face face : faces) {
            if (face.above(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    /** Removes the last vertex, rebuilding the hull from the others. */
    public boolean removeLastVertex() {
        List<BlockVector3> all = getVertices();
        if (all.isEmpty()) {
            return false;
        }
        List<BlockVector3> kept = new ArrayList<>(all.subList(0, all.size() - 1));
        clear();
        for (BlockVector3 vertex : kept) {
            addVertex(vertex);
        }
        return true;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        if (minimum == null) {
            return BlockVector3.ZERO;
        }
        return minimum.withY(Math.max(minimum.y(), minY));
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        if (maximum == null) {
            return BlockVector3.ZERO;
        }
        return maximum.withY(Math.min(maximum.y(), maxY));
    }

    /**
     * The number of blocks inside, exactly: the Y run of each column of the
     * bounding box, from the face planes. The bounding box's volume used to be
     * the answer, which a pyramid overstates three times.
     */
    @Override
    public long getVolume() {
        if (!isDefined()) {
            return 0;
        }
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        long total = 0;
        int[] range = new int[2];
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                if (columnRange(x, z, min.y(), max.y(), range)) {
                    total += (long) range[1] - range[0] + 1;
                }
            }
        }
        return total;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (!isDefined() || y < minY || y > maxY
                || x < minimum.x() || x > maximum.x() || y < minimum.y() || y > maximum.y()
                || z < minimum.z() || z > maximum.z()) {
            return false;
        }
        long px = (long) x - origin.x();
        long py = (long) y - origin.y();
        long pz = (long) z - origin.z();
        Face last = lastOutside;
        if (last != null && last.above(px, py, pz)) {
            return false;
        }
        for (Face face : faces) {
            if (face != last && face.above(px, py, pz)) {
                lastOutside = face;
                return false;
            }
        }
        return true;
    }

    /**
     * The Y run of one column, from the planes: each face bounds Y from above
     * or below in a column, or leaves the column out entirely when it is
     * vertical and the column is on its outer side.
     *
     * @return false when the column holds nothing
     */
    private boolean columnRange(int x, int z, int lowY, int highY, int[] out) {
        long px = (long) x - origin.x();
        long pz = (long) z - origin.z();
        long low = (long) lowY - origin.y();
        long high = (long) highY - origin.y();
        for (Face face : faces) {
            // normal . (px, y, pz) <= limit, so normalY * y <= rest
            long rest = face.limit - face.normalX * px - face.normalZ * pz;
            if (face.normalY > 0) {
                high = Math.min(high, Math.floorDiv(rest, face.normalY));
            } else if (face.normalY < 0) {
                low = Math.max(low, -Math.floorDiv(rest, -face.normalY));
            } else if (rest < 0) {
                return false;
            }
            if (low > high) {
                return false;
            }
        }
        out[0] = (int) (low + origin.y());
        out[1] = (int) (high + origin.y());
        return true;
    }

    private void fillColumns(int baseX, int baseZ, int[] lo, int[] hi) {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        int[] range = new int[2];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = z << 4 | x;
                if (columnRange(baseX + x, baseZ + z, min.y(), max.y(), range)) {
                    lo[column] = range[0];
                    hi[column] = range[1];
                } else {
                    lo[column] = 1;
                    hi[column] = 0;
                }
            }
        }
    }

    @Override
    public long forEachPosition(BlockVisitor visitor) {
        if (!isDefined()) {
            return 0;
        }
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return ColumnWalk.walk(min.x(), min.z(), max.x(), max.z(), this::fillColumns, visitor);
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        if (!isDefined()) {
            return Collections.emptyIterator();
        }
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return ColumnWalk.iterator(min.x(), min.z(), max.x(), max.z(), this::fillColumns);
    }

    /** A hull cannot grow by a number of blocks, as in WorldEdit. */
    @Override
    public boolean expand(BlockVector3 amount) {
        throw new com.maxlananas.fawebim.core.util.InputException("A convex selection cannot be expanded");
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        throw new com.maxlananas.fawebim.core.util.InputException("A convex selection cannot be contracted");
    }

    /** Moves every vertex; the planes are relative to the first one, so they move with it. */
    @Override
    public boolean shift(BlockVector3 amount) {
        if (amount.equals(BlockVector3.ZERO) || origin == null) {
            return false;
        }
        List<BlockVector3> moved = new ArrayList<>(vertices.size());
        for (BlockVector3 vertex : vertices) {
            moved.add(vertex.add(amount));
        }
        List<BlockVector3> waiting = new ArrayList<>(backlog.size());
        for (BlockVector3 vertex : backlog) {
            waiting.add(vertex.add(amount));
        }
        vertices.clear();
        vertices.addAll(moved);
        backlog.clear();
        backlog.addAll(waiting);
        List<Face> movedFaces = new ArrayList<>(faces.size());
        for (Face face : faces) {
            movedFaces.add(face.shift(amount));
        }
        faces.clear();
        faces.addAll(movedFaces);
        origin = origin.add(amount);
        minimum = minimum.add(amount);
        maximum = maximum.add(amount);
        lastOutside = null;
        return true;
    }

    @Override
    public String describe() {
        return "convex (" + getVertices().size() + " vertices, " + faces.size() + " faces)";
    }

    /** An undirected edge between two vertices. */
    private record Edge(BlockVector3 start, BlockVector3 end) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Edge edge && ((start.equals(edge.start) && end.equals(edge.end))
                    || (start.equals(edge.end) && end.equals(edge.start)));
        }

        @Override
        public int hashCode() {
            return start.hashCode() ^ end.hashCode();
        }
    }

    /**
     * A face: three vertices counter-clockwise and the plane through them,
     * {@code normal . p <= limit} on the inner side, in coordinates relative to
     * the region's first vertex.
     */
    private static final class Face {

        final BlockVector3 a;
        final BlockVector3 b;
        final BlockVector3 c;
        final long normalX;
        final long normalY;
        final long normalZ;
        final long limit;

        Face(BlockVector3 a, BlockVector3 b, BlockVector3 c, BlockVector3 origin) {
            this.a = a;
            this.b = b;
            this.c = c;
            long abx = (long) b.x() - a.x();
            long aby = (long) b.y() - a.y();
            long abz = (long) b.z() - a.z();
            long acx = (long) c.x() - a.x();
            long acy = (long) c.y() - a.y();
            long acz = (long) c.z() - a.z();
            this.normalX = aby * acz - abz * acy;
            this.normalY = abz * acx - abx * acz;
            this.normalZ = abx * acy - aby * acx;
            this.limit = normalX * ((long) a.x() - origin.x()) + normalY * ((long) a.y() - origin.y())
                    + normalZ * ((long) a.z() - origin.z());
        }

        private Face(BlockVector3 a, BlockVector3 b, BlockVector3 c, long normalX, long normalY, long normalZ,
                     long limit) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.normalX = normalX;
            this.normalY = normalY;
            this.normalZ = normalZ;
            this.limit = limit;
        }

        boolean above(long x, long y, long z) {
            return normalX * x + normalY * y + normalZ * z > limit;
        }

        Edge edge(int index) {
            return switch (index) {
                case 0 -> new Edge(a, b);
                case 1 -> new Edge(b, c);
                default -> new Edge(c, a);
            };
        }

        /** The same face moved by an amount, the origin moving with it. */
        Face shift(BlockVector3 amount) {
            return new Face(a.add(amount), b.add(amount), c.add(amount), normalX, normalY, normalZ, limit);
        }
    }
}
