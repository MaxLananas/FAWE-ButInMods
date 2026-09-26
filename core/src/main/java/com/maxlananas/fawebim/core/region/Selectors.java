package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector2;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.ArrayList;
import java.util.List;

/**
 * All of FAWE's selection shapes, mirroring the seven choices of {@code //sel}:
 * cuboid, extend, poly, ellipsoid, sphere, cyl and convex.
 */
public final class Selectors {

    private Selectors() {
    }

    /** Shared behaviour: nothing to do for the plain selectors. */
    abstract static class BaseSelector implements RegionSelector {

        private final int minY;
        private final int maxY;

        BaseSelector(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }

        protected int minY() {
            return minY;
        }

        protected int maxY() {
            return maxY;
        }

        protected BlockVector3 clamp(BlockVector3 position) {
            return new BlockVector3(position.x(), Math.max(minY, Math.min(maxY, position.y())), position.z());
        }

        @Override
        public void explainPrimarySelection(Object actor, BlockVector3 position, boolean changed) {
        }
    }

    // ------------------------------------------------------------------ cuboid

    public static final class CuboidSelector extends BaseSelector {

        private BlockVector3 pos1;
        private BlockVector3 pos2;
        private final CuboidRegion region;

        public CuboidSelector(int minY, int maxY) {
            super(minY, maxY);
            this.region = new CuboidRegion(BlockVector3.ZERO, BlockVector3.ZERO);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(pos1)) {
                return false;
            }
            pos1 = clamped;
            rebuild();
            return true;
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return pos1 == null ? getRegion().getMinimumPoint() : pos1;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(pos2)) {
                return false;
            }
            pos2 = clamped;
            rebuild();
            return true;
        }

        private void rebuild() {
            if (pos1 != null && pos2 != null) {
                region.setCorners(pos1, pos2);
            }
        }

        public BlockVector3 getPos1() {
            return pos1;
        }

        public BlockVector3 getPos2() {
            return pos2;
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return "cuboid";
        }

        @Override
        public boolean isDefined() {
            return pos1 != null && pos2 != null;
        }

        @Override
        public void clear() {
            pos1 = null;
            pos2 = null;
            region.setCorners(BlockVector3.ZERO, BlockVector3.ZERO);
        }

        /**
         * Takes the corners back from the region a command changed. Each corner
         * keeps its side on every axis - the corner that was the lower one on X
         * takes the region's lower X - which is how WorldEdit's region moves the
         * corner on the side it grows. Without this the next click rebuilt the
         * box from the corners of before {@code //expand}, and the expansion was
         * gone.
         */
        @Override
        public void learnChanges() {
            if (pos1 == null || pos2 == null) {
                return;
            }
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            BlockVector3 first = new BlockVector3(pos1.x() <= pos2.x() ? min.x() : max.x(),
                    pos1.y() <= pos2.y() ? min.y() : max.y(), pos1.z() <= pos2.z() ? min.z() : max.z());
            BlockVector3 second = new BlockVector3(pos1.x() <= pos2.x() ? max.x() : min.x(),
                    pos1.y() <= pos2.y() ? max.y() : min.y(), pos1.z() <= pos2.z() ? max.z() : min.z());
            pos1 = first;
            pos2 = second;
        }

        @Override
        public String describe() {
            if (!isDefined()) {
                return "cuboid: not defined";
            }
            return "cuboid: " + region.getWidth() + "x" + region.getHeight() + "x" + region.getLength();
        }

        @Override
        public RegionSelector copy() {
            CuboidSelector copy = new CuboidSelector(minY(), maxY());
            copy.pos1 = pos1;
            copy.pos2 = pos2;
            copy.rebuild();
            return copy;
        }
    }

    // ------------------------------------------------------------------ extend

    /** Cuboid selection that grows with every left/right click, keeping the first corner. */
    public static final class ExtendingCuboidSelector extends BaseSelector {

        private BlockVector3 origin;
        private BlockVector3 pos1;
        private BlockVector3 pos2;
        private final CuboidRegion region = new CuboidRegion(BlockVector3.ZERO, BlockVector3.ZERO);

        public ExtendingCuboidSelector(int minY, int maxY) {
            super(minY, maxY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (origin == null || (pos1 != null && pos2 != null)) {
                origin = clamped;
                pos1 = clamped;
                pos2 = null;
                rebuild();
                return true;
            }
            pos1 = clamped;
            rebuild();
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (origin == null) {
                origin = clamped;
            }
            pos2 = clamped;
            rebuild();
            return true;
        }

        private void rebuild() {
            if (origin != null && pos1 != null && pos2 != null) {
                region.setCorners(origin.min(pos1).min(pos2), origin.max(pos1).max(pos2));
            }
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return "extend";
        }

        @Override
        public boolean isDefined() {
            return origin != null && pos1 != null && pos2 != null;
        }

        @Override
        public void clear() {
            origin = null;
            pos1 = null;
            pos2 = null;
            region.setCorners(BlockVector3.ZERO, BlockVector3.ZERO);
        }

        /** Takes the box back from the region a command changed; the next click extends it. */
        @Override
        public void learnChanges() {
            if (!isDefined()) {
                return;
            }
            origin = region.getMinimumPoint();
            pos1 = origin;
            pos2 = region.getMaximumPoint();
        }

        @Override
        public String describe() {
            return "extend: " + (isDefined()
                    ? region.getWidth() + "x" + region.getHeight() + "x" + region.getLength()
                    : "not defined");
        }

        @Override
        public RegionSelector copy() {
            ExtendingCuboidSelector copy = new ExtendingCuboidSelector(minY(), maxY());
            copy.origin = origin;
            copy.pos1 = pos1;
            copy.pos2 = pos2;
            copy.rebuild();
            return copy;
        }
    }

    // --------------------------------------------------------------------- poly

    /**
     * WorldEdit's polygon: the left click starts a new outline at the clicked
     * block, each right click adds a point to it, and the height runs from the
     * lowest to the highest block clicked.
     *
     * <p>The port used to append on the left click, move the last point on the
     * right one and span the whole height of the world, so a {@code //set} on a
     * polygon filled every layer from the bottom of the world to the top.</p>
     */
    public static final class Polygonal2DSelector extends BaseSelector {

        private final List<BlockVector2> points = new ArrayList<>();
        private final Polygonal2DRegion region;
        private BlockVector3 first;

        public Polygonal2DSelector(int minY, int maxY) {
            super(minY, maxY);
            this.region = new Polygonal2DRegion(List.of(), minY, minY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(first) && points.size() == 1) {
                return false;
            }
            first = clamped;
            points.clear();
            points.add(new BlockVector2(clamped.x(), clamped.z()));
            region.setMinY(clamped.y());
            region.setMaxY(clamped.y());
            region.setPoints(points);
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            BlockVector2 point = new BlockVector2(clamped.x(), clamped.z());
            if (!points.isEmpty()) {
                if (points.get(points.size() - 1).equals(point)) {
                    return false;
                }
                if (points.size() > limits.getPolygonVertexLimit()) {
                    return false;
                }
            } else {
                first = clamped;
                region.setMinY(clamped.y());
                region.setMaxY(clamped.y());
            }
            points.add(point);
            region.setPoints(points);
            region.expandY(clamped.y());
            return true;
        }

        /** Adds a point without a click, extending the height to it like a click does. */
        public boolean addVertex(BlockVector2 point) {
            if (!points.isEmpty() && points.get(points.size() - 1).equals(point)) {
                return false;
            }
            points.add(point);
            region.setPoints(points);
            return true;
        }

        public List<BlockVector2> getPoints() {
            return java.util.Collections.unmodifiableList(points);
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return first == null ? getRegion().getMinimumPoint() : first;
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return "poly";
        }

        @Override
        public boolean isDefined() {
            return points.size() >= 3;
        }

        @Override
        public void clear() {
            points.clear();
            first = null;
            region.setPoints(points);
        }

        /** Takes the outline back from the region a command moved or stretched. */
        @Override
        public void learnChanges() {
            points.clear();
            points.addAll(region.getPoints());
            if (!points.isEmpty()) {
                first = new BlockVector3(points.get(0).x(), region.getMinY(), points.get(0).z());
            }
        }

        @Override
        public int vertexCount() {
            return points.size();
        }

        @Override
        public String describe() {
            return "poly: " + points.size() + " points, y " + region.getMinY() + ".." + region.getMaxY();
        }

        @Override
        public RegionSelector copy() {
            Polygonal2DSelector copy = new Polygonal2DSelector(minY(), maxY());
            copy.points.addAll(points);
            copy.first = first;
            copy.region.setMinY(region.getMinY());
            copy.region.setMaxY(region.getMaxY());
            copy.region.setPoints(copy.points);
            return copy;
        }
    }

    // ---------------------------------------------------------- ellipsoid/sphere

    /**
     * WorldEdit's sphere and ellipsoid: the left click sets the centre and
     * starts from no radius; a right click sets a sphere's radius to its
     * distance from the centre, rounded up, and extends each radius of an
     * ellipsoid to reach the clicked block on that axis.
     */
    public static final class EllipsoidSelector extends BaseSelector {

        private BlockVector3 center;
        private Vector3 radii;
        private final EllipsoidRegion region;
        private final boolean sphere;

        public EllipsoidSelector(int minY, int maxY, boolean sphere) {
            super(minY, maxY);
            this.sphere = sphere;
            this.region = new EllipsoidRegion(Vector3.ZERO, Vector3.ZERO, minY, maxY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(center) && radii != null && radii.lengthSq() == 0) {
                return false;
            }
            center = clamped;
            radii = Vector3.ZERO;
            apply();
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (center == null) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            double dx = Math.abs((double) clamped.x() - center.x());
            double dy = Math.abs((double) clamped.y() - center.y());
            double dz = Math.abs((double) clamped.z() - center.z());
            if (sphere) {
                // The Euclidean distance, rounded up: the farthest of the three
                // axes gave a smaller ball than the one clicked on a diagonal.
                double r = Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
                radii = new Vector3(r, r, r);
            } else {
                radii = new Vector3(Math.max(radii.x(), dx), Math.max(radii.y(), dy), Math.max(radii.z(), dz));
            }
            apply();
            return true;
        }

        private void apply() {
            if (center != null && radii != null) {
                region.setCenter(center.toCenter());
                region.setRadii(radii);
            }
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return center == null ? getRegion().getMinimumPoint() : center;
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return sphere ? "sphere" : "ellipsoid";
        }

        @Override
        public boolean isDefined() {
            return center != null && radii != null && radii.lengthSq() > 0;
        }

        @Override
        public void clear() {
            center = null;
            radii = null;
        }

        /** Takes the centre and the radii back from the region a command changed. */
        @Override
        public void learnChanges() {
            if (center == null) {
                return;
            }
            Vector3 moved = region.getCenter();
            center = new BlockVector3((int) Math.floor(moved.x()), (int) Math.floor(moved.y()),
                    (int) Math.floor(moved.z()));
            radii = region.getRadii();
        }

        @Override
        public String describe() {
            return getTypeName() + (isDefined()
                    ? ": " + region.getRadiusX() + "x" + region.getRadiusY() + "x" + region.getRadiusZ()
                    : ": not defined");
        }

        @Override
        public RegionSelector copy() {
            EllipsoidSelector copy = new EllipsoidSelector(minY(), maxY(), sphere);
            copy.center = center;
            copy.radii = radii;
            copy.apply();
            return copy;
        }
    }

    // --------------------------------------------------------------------- cyl

    /**
     * WorldEdit's cylinder: the left click sets the centre, with no radius and
     * the height of that block; each right click extends the radii to reach the
     * clicked block and the height to include it.
     */
    public static final class CylinderSelector extends BaseSelector {

        private BlockVector3 center;
        private Vector2 radii;
        private int minY;
        private int maxY;
        private final CylinderRegion region;

        public CylinderSelector(int worldMinY, int worldMaxY) {
            super(worldMinY, worldMaxY);
            this.minY = worldMinY;
            this.maxY = worldMaxY;
            this.region = new CylinderRegion(Vector2.ZERO, 0, 0, worldMinY, worldMaxY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(center) && radii != null && radii.x() == 0 && radii.z() == 0) {
                return false;
            }
            center = clamped;
            radii = new Vector2(0, 0);
            minY = clamped.y();
            maxY = clamped.y();
            apply();
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (center == null) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            double dx = Math.abs((double) clamped.x() - center.x());
            double dz = Math.abs((double) clamped.z() - center.z());
            radii = new Vector2(Math.max(radii.x(), dx), Math.max(radii.z(), dz));
            minY = Math.min(minY, clamped.y());
            maxY = Math.max(maxY, clamped.y());
            apply();
            return true;
        }

        /** Used by {@code //cyl} style helpers and the cylinder brush. */
        public void setCenter(BlockVector3 center, double radiusX, double radiusZ, int minY, int maxY) {
            this.center = center;
            this.radii = new Vector2(radiusX, radiusZ);
            this.minY = minY;
            this.maxY = maxY;
            apply();
        }

        public void setYRange(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
            apply();
        }

        private void apply() {
            if (center != null && radii != null) {
                region.setCenter(new Vector2(center.x() + 0.5, center.z() + 0.5));
                region.setRadius(radii.x(), radii.z());
                region.setYRange(minY, maxY);
            }
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return center == null ? getRegion().getMinimumPoint() : center;
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return "cyl";
        }

        @Override
        public boolean isDefined() {
            return center != null && radii != null && (radii.x() > 0 || radii.z() > 0);
        }

        @Override
        public void clear() {
            center = null;
            radii = null;
        }

        /** Takes the centre, the radii and the height back from the region a command changed. */
        @Override
        public void learnChanges() {
            if (center == null) {
                return;
            }
            Vector2 moved = region.getCenter2D();
            center = new BlockVector3((int) Math.floor(moved.x()), center.y(), (int) Math.floor(moved.z()));
            radii = new Vector2(region.getRadiusX(), region.getRadiusZ());
            minY = region.getMinY();
            maxY = region.getMaxY();
        }

        @Override
        public String describe() {
            return "cyl" + (isDefined()
                    ? ": r=" + region.getRadiusX() + "," + region.getRadiusZ() + " y=" + minY + ".." + maxY
                    : ": not defined");
        }

        @Override
        public RegionSelector copy() {
            CylinderSelector copy = new CylinderSelector(minY(), maxY());
            copy.center = center;
            copy.radii = radii;
            copy.minY = minY;
            copy.maxY = maxY;
            copy.apply();
            return copy;
        }
    }

    // ------------------------------------------------------------------ convex

    /**
     * WorldEdit's convex selection: the left click starts a new hull at the
     * clicked block and each right click adds a vertex; the hull is defined as
     * soon as three vertices are not on a line.
     *
     * <p>The port used to add on the left click and remove on the right one, and
     * built a face from every three consecutive clicks, which is not a hull: the
     * selection held nothing until twelve vertices were clicked.</p>
     */
    public static final class ConvexSelector extends BaseSelector {

        private final ConvexPolyhedralRegion region = new ConvexPolyhedralRegion();
        private BlockVector3 first;

        public ConvexSelector(int minY, int maxY) {
            super(minY, maxY);
            region.setBounds(minY, maxY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            region.clear();
            first = clamped;
            return region.addVertex(clamped);
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (region.getVertices().size() > limits.getPolyhedronVertexLimit()) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            if (first == null) {
                first = clamped;
            }
            return region.addVertex(clamped);
        }

        public List<BlockVector3> getVertices() {
            return region.getVertices();
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return first == null ? getRegion().getMinimumPoint() : first;
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return "convex";
        }

        @Override
        public boolean isDefined() {
            return region.isDefined();
        }

        @Override
        public void clear() {
            first = null;
            region.clear();
        }

        /** The hull is the region's own; only the first vertex is taken back. */
        @Override
        public void learnChanges() {
            List<BlockVector3> vertices = region.getVertices();
            first = vertices.isEmpty() ? null : vertices.get(0);
        }

        @Override
        public int vertexCount() {
            return region.getVertices().size();
        }

        @Override
        public String describe() {
            return "convex: " + region.getVertices().size() + " vertices";
        }

        @Override
        public RegionSelector copy() {
            ConvexSelector copy = new ConvexSelector(minY(), maxY());
            copy.first = first;
            for (BlockVector3 vertex : region.getVertices()) {
                copy.region.addVertex(vertex);
            }
            return copy;
        }
    }
}
