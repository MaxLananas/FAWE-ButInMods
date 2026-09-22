package com.fawebutinmods.core.region;

import com.fawebutinmods.core.math.BlockVector2;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector2;
import com.fawebutinmods.core.math.Vector3;

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
                region.setPos1(pos1.min(pos2));
                region.setPos2(pos1.max(pos2));
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
            rebuild();
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
                region.setPos1(origin.min(pos1).min(pos2));
                region.setPos2(origin.max(pos1).max(pos2));
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
            rebuild();
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

    public static final class Polygonal2DSelector extends BaseSelector {

        private final List<BlockVector2> points = new ArrayList<>();
        private final Polygonal2DRegion region;

        public Polygonal2DSelector(int minY, int maxY) {
            super(minY, maxY);
            this.region = new Polygonal2DRegion(List.of(), minY, maxY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            BlockVector2 point = new BlockVector2(clamped.x(), clamped.z());
            int vertexLimit = limits.getPolygonVertexLimit();
            if (points.size() >= vertexLimit + 1) {
                return false;
            }
            if (points.size() == 1 && points.get(0).equals(point)) {
                return false;
            }
            points.add(point);
            rebuild();
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (points.size() <= 1) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            BlockVector2 last = points.get(points.size() - 1);
            // The second click moves the last vertex before closing the polygon.
            points.set(points.size() - 1, new BlockVector2(clamped.x(), clamped.z()));
            if (!last.equals(points.get(points.size() - 1))) {
                rebuild();
                return true;
            }
            return false;
        }

        /** Adds a vertex programmatically (used for the closing point). */
        public boolean addVertex(BlockVector2 point) {
            points.add(point);
            rebuild();
            return true;
        }

        private void rebuild() {
            region.getPoints().clear();
            region.getPoints().addAll(points);
        }

        public List<BlockVector2> getPoints() {
            return points;
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
            rebuild();
        }

        @Override
        public int vertexCount() {
            return points.size();
        }

        @Override
        public String describe() {
            return "poly: " + points.size() + " points";
        }

        @Override
        public RegionSelector copy() {
            Polygonal2DSelector copy = new Polygonal2DSelector(minY(), maxY());
            copy.points.addAll(points);
            copy.rebuild();
            return copy;
        }
    }

    // ---------------------------------------------------------- ellipsoid/sphere

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
            boolean changed = !clamped.equals(center);
            center = clamped;
            if (radii == null) {
                radii = new Vector3(0, 0, 0);
            }
            apply();
            return changed;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (center == null) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            double dx = Math.abs(clamped.x() + 0.5 - center.x() - 0.5);
            double dy = Math.abs(clamped.y() + 0.5 - center.y() - 0.5);
            double dz = Math.abs(clamped.z() + 0.5 - center.z() - 0.5);
            if (sphere) {
                double r = Math.max(dx, Math.max(dy, dz));
                radii = new Vector3(Math.max(r, 1), Math.max(r, 1), Math.max(r, 1));
            } else {
                radii = new Vector3(Math.max(dx, 1), Math.max(dy, 1), Math.max(dz, 1));
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
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return sphere ? "sphere" : "ellipsoid";
        }

        @Override
        public boolean isDefined() {
            return center != null && radii != null && radii.x() > 0;
        }

        @Override
        public void clear() {
            center = null;
            radii = null;
            apply();
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
            center = clamp(position);
            radii = null;
            apply();
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (center == null) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            double dx = Math.abs(clamped.x() - center.x());
            double dz = Math.abs(clamped.z() - center.z());
            radii = new Vector2(Math.max(dx, 1), Math.max(dz, 1));
            minY = Math.min(center.y(), clamped.y());
            maxY = Math.max(center.y(), clamped.y());
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
                region.setY(minY, maxY);
            }
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
            return center != null && radii != null;
        }

        @Override
        public void clear() {
            center = null;
            radii = null;
            apply();
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

    public static final class ConvexSelector extends BaseSelector {

        private final ConvexPolyhedralRegion region = new ConvexPolyhedralRegion();
        private final List<BlockVector3> vertices = new ArrayList<>();

        public ConvexSelector(int minY, int maxY) {
            super(minY, maxY);
            region.setBounds(minY, maxY);
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            if (vertices.size() / 3 >= limits.getPolyhedronVertexLimit()) {
                return false;
            }
            BlockVector3 clamped = clamp(position);
            if (!region.addVertex(clamped)) {
                region.removeLastVertex();
                return false;
            }
            vertices.add(clamped);
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (vertices.isEmpty()) {
                return false;
            }
            // Right click removes the last vertex, as in WorldEdit.
            vertices.remove(vertices.size() - 1);
            region.removeLastVertex();
            return true;
        }

        public List<BlockVector3> getVertices() {
            return vertices;
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
            return region.getTriangleCount() >= 4;
        }

        @Override
        public void clear() {
            vertices.clear();
            ConvexPolyhedralRegion cleared = new ConvexPolyhedralRegion();
            cleared.setBounds(minY(), maxY());
            // ConvexPolyhedralRegion has no clear(); recreate through the selector contract.
            while (region.removeLastVertex()) {
                // keep removing until empty
            }
        }

        @Override
        public int vertexCount() {
            return vertices.size();
        }

        @Override
        public String describe() {
            return "convex: " + region.getTriangleCount() + " triangles";
        }

        @Override
        public RegionSelector copy() {
            ConvexSelector copy = new ConvexSelector(minY(), maxY());
            for (BlockVector3 vertex : vertices) {
                copy.selectPrimary(vertex, SelectorLimits.unlimited());
            }
            return copy;
        }
    }
}
