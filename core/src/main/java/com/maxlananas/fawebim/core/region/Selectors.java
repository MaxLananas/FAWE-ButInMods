package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector2;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The selection shapes of {@code //sel}: WorldEdit's cuboid, extend, poly,
 * ellipsoid, sphere, cyl and convex, and FAWE's polyhedral and fuzzy.
 *
 * <p>The wand and {@code //pos1}/{@code //pos2} talk to a selector: the primary
 * click is the left one, the secondary the right one, and each means for a
 * shape what WorldEdit makes it mean - the two corners of a cuboid, the centre
 * then the radius of a sphere, the first then the next points of a polygon.
 * Each click is answered with the line WorldEdit prints for it.</p>
 *
 * <p>Switching shapes keeps the selection where the new shape can hold it, as
 * {@code //sel} does: a cuboid becomes the polygon of its four corners, the
 * cylinder inside its box or the ellipsoid inside it.</p>
 */
public final class Selectors {

    /** The types {@code //sel} takes, in the order {@code //sel list} shows them. */
    public static final List<String> NAMES = List.of("cuboid", "extend", "poly", "ellipsoid", "sphere", "cyl",
            "convex", "polyhedral", "fuzzy");

    private Selectors() {
    }

    /** What a type selects, as {@code //sel list} says it: WorldEdit's words, and FAWE's for its two. */
    public static String description(String name) {
        return switch (canonicalName(name) == null ? "" : canonicalName(name)) {
            case "cuboid" -> "Select two corners of a cuboid";
            case "extend" -> "Fast cuboid selection mode";
            case "poly" -> "Select a 2D polygon with height";
            case "ellipsoid" -> "Select an ellipsoid";
            case "sphere" -> "Select a sphere";
            case "cyl" -> "Select a cylinder";
            case "convex" -> "Select a convex polyhedral";
            case "polyhedral" -> "Select a hollow polyhedral";
            case "fuzzy" -> "Select all connected blocks (magic wand)";
            default -> "";
        };
    }

    /**
     * The name of a type {@code //sel} takes, its aliases included - WorldEdit's
     * {@code cylinder}, {@code hull} and {@code polyhedron}, FAWE's {@code
     * magic} - or {@code null} for a name it does not know.
     */
    public static String canonicalName(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "cylinder" -> "cyl";
            case "hull", "polyhedron" -> "convex";
            case "magic" -> "fuzzy";
            default -> NAMES.contains(key) ? key : null;
        };
    }

    /**
     * A new selector of a type, or {@code null} for a name {@code //sel} does
     * not know.
     *
     * @param previous the selector in use, whose selection the new one takes
     *                 over where its shape can hold it; {@code null} for none
     */
    public static RegionSelector create(String name, World world, RegionSelector previous) {
        String type = canonicalName(name);
        if (type == null) {
            return null;
        }
        int minY = world == null ? -64 : world.minY();
        int maxY = world == null ? 319 : world.maxY();
        Region old = previous != null && previous.isDefined() ? previous.getRegion() : null;
        return switch (type) {
            case "cuboid" -> CuboidSelector.from(previous, old, minY, maxY);
            case "extend" -> ExtendingCuboidSelector.from(old, minY, maxY);
            case "poly" -> Polygonal2DSelector.from(previous, old, minY, maxY);
            case "ellipsoid" -> EllipsoidSelector.from(previous, old, minY, maxY, false);
            case "sphere" -> EllipsoidSelector.from(previous, old, minY, maxY, true);
            case "cyl" -> CylinderSelector.from(previous, old, minY, maxY);
            case "convex" -> ConvexSelector.from(previous, old, minY, maxY);
            case "polyhedral" -> new PolyhedralSelector(minY, maxY);
            default -> new FuzzySelector(world, minY, maxY);
        };
    }

    /**
     * The outline of a region on the ground, WorldEdit's {@code polygonize}: the
     * points of a polygon, a circle of points for a cylinder, and the four
     * corners of the box for every other shape.
     */
    static List<BlockVector2> polygonize(RegionSelector previous, Region region) {
        if (previous instanceof Polygonal2DSelector polygon) {
            return new ArrayList<>(polygon.getPoints());
        }
        if (previous instanceof CylinderSelector cylinder && cylinder.center != null) {
            return cylinderOutline(cylinder.center.toBlockVector2(), cylinder.radii);
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        List<BlockVector2> corners = new ArrayList<>(4);
        corners.add(new BlockVector2(min.x(), min.z()));
        corners.add(new BlockVector2(min.x(), max.z()));
        corners.add(new BlockVector2(max.x(), max.z()));
        corners.add(new BlockVector2(max.x(), min.z()));
        return corners;
    }

    /** WorldEdit's {@code polygonizeCylinder}: a point every block or so of the rim. */
    private static List<BlockVector2> cylinderOutline(BlockVector2 center, Vector2 radius) {
        int count = Math.max(3, (int) Math.ceil(Math.PI * Math.sqrt(radius.x() * radius.x()
                + radius.z() * radius.z())));
        List<BlockVector2> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = i * (2.0 * Math.PI) / count;
            BlockVector2 point = new BlockVector2((int) Math.floor(Math.cos(angle) * radius.x()) + center.x(),
                    (int) Math.floor(Math.sin(angle) * radius.z()) + center.z());
            if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
                points.add(point);
            }
        }
        return points;
    }

    /** The limits and the answers every shape shares. */
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

        /** A line of the wand's answer, with the size of the selection once it holds blocks. */
        protected Msg explained(String label, String detail) {
            return Msg.result(label, isDefined() ? detail + " (" + Msg.blocks(getRegion().getVolume()) + ")"
                    : detail);
        }
    }

    /**
     * WorldEdit's cuboid: the primary click sets one corner and the secondary
     * the other.
     */
    public static final class CuboidSelector extends BaseSelector {

        private BlockVector3 pos1;
        private BlockVector3 pos2;
        private final CuboidRegion region;

        public CuboidSelector(int minY, int maxY) {
            super(minY, maxY);
            this.region = new CuboidRegion(BlockVector3.ZERO, BlockVector3.ZERO);
        }

        static CuboidSelector from(RegionSelector previous, Region old, int minY, int maxY) {
            CuboidSelector selector = new CuboidSelector(minY, maxY);
            if (previous instanceof CuboidSelector cuboid) {
                selector.pos1 = cuboid.pos1;
                selector.pos2 = cuboid.pos2;
            } else if (old != null) {
                selector.pos1 = old.getMinimumPoint();
                selector.pos2 = old.getMaximumPoint();
            }
            selector.rebuild();
            return selector;
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
        public String usage() {
            return "Cuboid: left click for point 1, right click for point 2";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return explained("Position 1", "set to " + Msg.value(pos1).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            return explained("Position 2", "set to " + Msg.value(pos2).raw());
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return pos1 == null ? List.of() : List.of(pos1);
        }

        @Override
        public List<BlockVector3> secondaryPoints() {
            return pos2 == null ? List.of() : List.of(pos2);
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

    /**
     * WorldEdit's extending cuboid: the primary click starts a box of one
     * block, and each secondary click grows it to take the clicked block in.
     */
    public static final class ExtendingCuboidSelector extends BaseSelector {

        private BlockVector3 pos1;
        private BlockVector3 pos2;
        private final CuboidRegion region = new CuboidRegion(BlockVector3.ZERO, BlockVector3.ZERO);

        public ExtendingCuboidSelector(int minY, int maxY) {
            super(minY, maxY);
        }

        static ExtendingCuboidSelector from(Region old, int minY, int maxY) {
            ExtendingCuboidSelector selector = new ExtendingCuboidSelector(minY, maxY);
            if (old != null) {
                selector.pos1 = old.getMinimumPoint();
                selector.pos2 = old.getMaximumPoint();
                selector.region.setCorners(selector.pos1, selector.pos2);
            }
            return selector;
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(pos1) && clamped.equals(pos2)) {
                return false;
            }
            pos1 = clamped;
            pos2 = clamped;
            region.setCorners(pos1, pos2);
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            if (pos1 == null || pos2 == null) {
                return selectPrimary(position, limits);
            }
            BlockVector3 clamped = clamp(position);
            if (region.contains(clamped)) {
                return false;
            }
            pos1 = pos1.min(clamped);
            pos2 = pos2.max(clamped);
            region.setCorners(pos1, pos2);
            return true;
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return pos1 == null ? region.getMinimumPoint() : pos1;
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
        public String usage() {
            return "Cuboid: left click for a starting point, right click to extend";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return explained("Selection", "started at " + Msg.value(clamp(position)).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            return explained("Selection", "extended to " + Msg.value(clamp(position)).raw());
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return pos1 == null ? List.of() : List.of(pos1);
        }

        @Override
        public List<BlockVector3> secondaryPoints() {
            return pos2 == null ? List.of() : List.of(pos2);
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

        /** Takes the box back from the region a command changed; the next click extends it. */
        @Override
        public void learnChanges() {
            if (!isDefined()) {
                return;
            }
            pos1 = region.getMinimumPoint();
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
            copy.pos1 = pos1;
            copy.pos2 = pos2;
            if (pos1 != null && pos2 != null) {
                copy.region.setCorners(pos1, pos2);
            }
            return copy;
        }
    }

    /**
     * WorldEdit's polygon: the primary click starts a new outline at the
     * clicked block, each secondary click adds a point to it, and the height
     * runs from the lowest to the highest block clicked.
     */
    public static final class Polygonal2DSelector extends BaseSelector {

        private final List<BlockVector2> points = new ArrayList<>();
        private final Polygonal2DRegion region;
        private BlockVector3 first;

        public Polygonal2DSelector(int minY, int maxY) {
            super(minY, maxY);
            this.region = new Polygonal2DRegion(List.of(), minY, minY);
        }

        static Polygonal2DSelector from(RegionSelector previous, Region old, int minY, int maxY) {
            Polygonal2DSelector selector = new Polygonal2DSelector(minY, maxY);
            if (previous instanceof Polygonal2DSelector polygon) {
                selector.points.addAll(polygon.points);
                selector.first = polygon.first;
                selector.region.setYRange(polygon.region.getMinY(), polygon.region.getMaxY());
                selector.region.setPoints(selector.points);
            } else if (old != null) {
                selector.points.addAll(polygonize(previous, old));
                selector.region.setYRange(old.getMinimumPoint().y(), old.getMaximumPoint().y());
                selector.region.setPoints(selector.points);
                BlockVector2 start = selector.points.get(0);
                selector.first = new BlockVector3(start.x(), old.getMinimumPoint().y(), start.z());
            }
            return selector;
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(first)) {
                return false;
            }
            first = clamped;
            points.clear();
            points.add(new BlockVector2(clamped.x(), clamped.z()));
            region.setYRange(clamped.y(), clamped.y());
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
                region.setYRange(clamped.y(), clamped.y());
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
            return Collections.unmodifiableList(points);
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
        public String usage() {
            return "2D polygon selector: left click starts a new polygon, right click adds a point";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return Msg.result("Polygon", "started at " + Msg.value(clamp(position)).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            return Msg.result("Polygon", "point #" + points.size() + " added at "
                    + Msg.value(clamp(position)).raw()
                    + (isDefined() ? " (" + Msg.blocks(region.getVolume()) + ")" : ""));
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return points.isEmpty() ? List.of() : List.of(new BlockVector3(points.get(0).x(), region.getMinY(),
                    points.get(0).z()));
        }

        @Override
        public List<BlockVector3> secondaryPoints() {
            List<BlockVector3> others = new ArrayList<>(Math.max(0, points.size() - 1));
            for (int i = 1; i < points.size(); i++) {
                others.add(new BlockVector3(points.get(i).x(), region.getMinY(), points.get(i).z()));
            }
            return others;
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
            copy.region.setYRange(region.getMinY(), region.getMaxY());
            copy.region.setPoints(copy.points);
            return copy;
        }
    }

    /**
     * WorldEdit's sphere and ellipsoid: the primary click sets the centre and
     * starts from no radius; a secondary click sets a sphere's radius to its
     * distance from the centre, rounded up, and extends each radius of an
     * ellipsoid to reach the clicked block on that axis.
     */
    public static final class EllipsoidSelector extends BaseSelector {

        private BlockVector3 center;
        private Vector3 radii;
        private boolean radiusSet;
        private final EllipsoidRegion region;
        private final boolean sphere;

        public EllipsoidSelector(int minY, int maxY, boolean sphere) {
            super(minY, maxY);
            this.sphere = sphere;
            this.region = new EllipsoidRegion(Vector3.ZERO, Vector3.ZERO, minY, maxY);
        }

        static EllipsoidSelector from(RegionSelector previous, Region old, int minY, int maxY, boolean sphere) {
            EllipsoidSelector selector = new EllipsoidSelector(minY, maxY, sphere);
            if (previous instanceof EllipsoidSelector ellipsoid) {
                selector.center = ellipsoid.center;
                selector.radii = ellipsoid.radii;
                selector.radiusSet = ellipsoid.radiusSet;
            } else if (old != null) {
                BlockVector3 min = old.getMinimumPoint();
                BlockVector3 max = old.getMaximumPoint();
                selector.center = new BlockVector3(Math.floorDiv(min.x() + max.x(), 2),
                        Math.floorDiv(min.y() + max.y(), 2), Math.floorDiv(min.z() + max.z(), 2));
                selector.radii = max.subtract(selector.center).toVector3();
                selector.radiusSet = true;
            }
            if (sphere && selector.radiusSet && selector.radii != null) {
                double radius = Math.max(Math.max(selector.radii.x(), selector.radii.y()), selector.radii.z());
                selector.radii = new Vector3(radius, radius, radius);
            }
            selector.apply();
            return selector;
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(center) && radii != null && radii.lengthSq() == 0) {
                return false;
            }
            center = clamped;
            radii = Vector3.ZERO;
            radiusSet = false;
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
            radiusSet = true;
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

        /** The centre block, or {@code null} before the first click. */
        public BlockVector3 getCenter() {
            return center;
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
        public String usage() {
            return sphere ? "Sphere selector: left click for the center, right click to set the radius"
                    : "Ellipsoid selector: left click for the center, right click to extend";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return explained("Center", "set to " + Msg.value(center).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            String radius = sphere ? Msg.formatDouble(radii.x())
                    : Msg.formatDouble(radii.x()) + ", " + Msg.formatDouble(radii.y()) + ", "
                    + Msg.formatDouble(radii.z());
            return explained("Radius", "set to " + Msg.value(radius).raw());
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return center == null ? List.of() : List.of(center);
        }

        @Override
        public boolean isDefined() {
            return center != null && radiusSet;
        }

        @Override
        public void clear() {
            center = null;
            radii = null;
            radiusSet = false;
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
            copy.radiusSet = radiusSet;
            copy.apply();
            return copy;
        }
    }

    /**
     * WorldEdit's cylinder: the primary click sets the centre, with no radius
     * and the height of that block; each secondary click extends the radii to
     * reach the clicked block and the height to include it.
     */
    public static final class CylinderSelector extends BaseSelector {

        private BlockVector3 center;
        private Vector2 radii;
        private boolean radiusSet;
        private int minY;
        private int maxY;
        private final CylinderRegion region;

        public CylinderSelector(int worldMinY, int worldMaxY) {
            super(worldMinY, worldMaxY);
            this.minY = worldMinY;
            this.maxY = worldMaxY;
            this.region = new CylinderRegion(Vector2.ZERO, 0, 0, worldMinY, worldMaxY);
        }

        static CylinderSelector from(RegionSelector previous, Region old, int worldMinY, int worldMaxY) {
            CylinderSelector selector = new CylinderSelector(worldMinY, worldMaxY);
            if (previous instanceof CylinderSelector cylinder) {
                selector.center = cylinder.center;
                selector.radii = cylinder.radii;
                selector.radiusSet = cylinder.radiusSet;
                selector.minY = cylinder.minY;
                selector.maxY = cylinder.maxY;
            } else if (old != null) {
                BlockVector3 min = old.getMinimumPoint();
                BlockVector3 max = old.getMaximumPoint();
                selector.center = new BlockVector3(Math.floorDiv(min.x() + max.x(), 2),
                        Math.floorDiv(min.y() + max.y(), 2), Math.floorDiv(min.z() + max.z(), 2));
                selector.radii = new Vector2(max.x() - selector.center.x(), max.z() - selector.center.z());
                selector.minY = min.y();
                selector.maxY = max.y();
                selector.radiusSet = true;
            }
            selector.apply();
            return selector;
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            BlockVector3 clamped = clamp(position);
            if (clamped.equals(center) && radii != null && radii.x() == 0 && radii.z() == 0) {
                return false;
            }
            center = clamped;
            radii = new Vector2(0, 0);
            radiusSet = false;
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
            radiusSet = true;
            apply();
            return true;
        }

        /** Used by {@code //cyl} style helpers and the cylinder brush. */
        public void setCenter(BlockVector3 center, double radiusX, double radiusZ, int minY, int maxY) {
            this.center = center;
            this.radii = new Vector2(radiusX, radiusZ);
            this.minY = minY;
            this.maxY = maxY;
            this.radiusSet = true;
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

        /** The centre block, or {@code null} before the first click. */
        public BlockVector3 getCenter() {
            return center;
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
        public String usage() {
            return "Cylindrical selector: left click for the center, right click to extend";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return Msg.result("Cylinder", "started at " + Msg.value(center).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            if (center == null) {
                return Msg.error("Select the center with a left click before the radius");
            }
            return explained("Radius", "set to " + Msg.value(Msg.formatDouble(radii.x()) + "/"
                    + Msg.formatDouble(radii.z())).raw());
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return center == null ? List.of() : List.of(center);
        }

        @Override
        public boolean isDefined() {
            return center != null && radiusSet;
        }

        @Override
        public void clear() {
            center = null;
            radii = null;
            radiusSet = false;
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
            copy.radiusSet = radiusSet;
            copy.minY = minY;
            copy.maxY = maxY;
            copy.apply();
            return copy;
        }
    }

    /**
     * WorldEdit's convex selection: the primary click starts a new hull at the
     * clicked block and each secondary click adds a vertex; the hull is defined
     * as soon as three vertices are not on a line.
     */
    public static final class ConvexSelector extends BaseSelector {

        private final ConvexPolyhedralRegion region = new ConvexPolyhedralRegion();
        private BlockVector3 first;

        public ConvexSelector(int minY, int maxY) {
            super(minY, maxY);
            region.setBounds(minY, maxY);
        }

        static ConvexSelector from(RegionSelector previous, Region old, int minY, int maxY) {
            ConvexSelector selector = new ConvexSelector(minY, maxY);
            if (previous instanceof ConvexSelector convex) {
                selector.first = convex.first;
                for (BlockVector3 vertex : convex.region.getVertices()) {
                    selector.region.addVertex(vertex);
                }
            } else if (old != null) {
                int low = old.getMinimumPoint().y();
                int high = old.getMaximumPoint().y();
                for (BlockVector2 point : polygonize(previous, old)) {
                    selector.region.addVertex(new BlockVector3(point.x(), low, point.z()));
                    selector.region.addVertex(new BlockVector3(point.x(), high, point.z()));
                }
                selector.learnChanges();
            }
            return selector;
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
        public String usage() {
            return "Convex polyhedral selector: left click for the first vertex, right click to add more";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return Msg.result("Convex", "started with the vertex " + Msg.value(clamp(position)).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            return explained("Convex", "vertex " + Msg.value(clamp(position)).raw() + " added");
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return first == null ? List.of() : List.of(first);
        }

        @Override
        public List<BlockVector3> secondaryPoints() {
            List<BlockVector3> others = new ArrayList<>(region.getVertices());
            others.remove(first);
            return others;
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

    /**
     * FAWE's polyhedral selection: the vertices are clicked as for the convex
     * one, and the selection is the skin of the hull - the blocks its faces
     * pass through - rather than everything inside it.
     */
    public static final class PolyhedralSelector extends BaseSelector {

        private final PolyhedralRegion region;
        private BlockVector3 first;

        public PolyhedralSelector(int minY, int maxY) {
            super(minY, maxY);
            region = new PolyhedralRegion(minY, maxY);
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
            return "polyhedral";
        }

        @Override
        public String usage() {
            return "Polyhedral selector: left click for the first vertex, right click to add more;"
                    + " the selection is the skin of the shape";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return Msg.result("Polyhedral", "started with the vertex " + Msg.value(clamp(position)).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            return Msg.result("Polyhedral", "vertex " + Msg.value(clamp(position)).raw() + " added");
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return first == null ? List.of() : List.of(first);
        }

        @Override
        public List<BlockVector3> secondaryPoints() {
            List<BlockVector3> others = new ArrayList<>(region.getVertices());
            others.remove(first);
            return others;
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
            return "polyhedral: " + region.getVertices().size() + " vertices";
        }

        @Override
        public RegionSelector copy() {
            PolyhedralSelector copy = new PolyhedralSelector(minY(), maxY());
            copy.first = first;
            for (BlockVector3 vertex : region.getVertices()) {
                copy.region.addVertex(vertex);
            }
            return copy;
        }
    }

    /**
     * FAWE's fuzzy selection, its magic wand: a click selects the blocks joined
     * to the clicked one by faces that are of its type, up to 256 steps away;
     * the primary click starts over and the secondary adds to the selection.
     */
    public static final class FuzzySelector extends BaseSelector {

        private final World world;
        private final FuzzyRegion region;
        private final List<BlockVector3> clicks = new ArrayList<>();

        public FuzzySelector(World world, int minY, int maxY) {
            super(minY, maxY);
            this.world = world;
            this.region = new FuzzyRegion();
        }

        @Override
        public boolean selectPrimary(BlockVector3 position, SelectorLimits limits) {
            region.clear();
            clicks.clear();
            clicks.add(clamp(position));
            region.select(world, clamp(position), minY(), maxY());
            return true;
        }

        @Override
        public boolean selectSecondary(BlockVector3 position, SelectorLimits limits) {
            clicks.add(clamp(position));
            return region.select(world, clamp(position), minY(), maxY());
        }

        @Override
        public BlockVector3 getPrimaryPosition() {
            return clicks.isEmpty() ? region.getMinimumPoint() : clicks.get(0);
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public String getTypeName() {
            return "fuzzy";
        }

        @Override
        public String usage() {
            return "Fuzzy selector: left click selects the connected blocks of one type, right click adds more";
        }

        @Override
        public Msg explainPrimary(BlockVector3 position) {
            return explained("Fuzzy", "selected from " + Msg.value(clamp(position)).raw());
        }

        @Override
        public Msg explainSecondary(BlockVector3 position) {
            return explained("Fuzzy", "added from " + Msg.value(clamp(position)).raw());
        }

        @Override
        public List<BlockVector3> primaryPoints() {
            return clicks.isEmpty() ? List.of() : List.of(clicks.get(0));
        }

        @Override
        public List<BlockVector3> secondaryPoints() {
            return clicks.size() < 2 ? List.of() : List.copyOf(clicks.subList(1, clicks.size()));
        }

        @Override
        public boolean isDefined() {
            return region.getVolume() > 0;
        }

        @Override
        public void clear() {
            clicks.clear();
            region.clear();
        }

        @Override
        public String describe() {
            return "fuzzy: " + region.getVolume() + " blocks";
        }

        @Override
        public RegionSelector copy() {
            FuzzySelector copy = new FuzzySelector(world, minY(), maxY());
            copy.clicks.addAll(clicks);
            copy.region.addAll(region);
            return copy;
        }
    }
}
