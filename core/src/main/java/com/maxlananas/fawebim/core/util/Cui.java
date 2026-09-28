package com.maxlananas.fawebim.core.util;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.region.ConvexPolyhedralRegion;
import com.maxlananas.fawebim.core.region.CylinderRegion;
import com.maxlananas.fawebim.core.region.EllipsoidRegion;
import com.maxlananas.fawebim.core.region.PolyhedralRegion;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.region.Selectors;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the selection preview shows: the outline of the selection's shape and
 * the line above the hotbar while it is dragged out.
 *
 * <p>WorldEdit hands the selection to a client mod and lets it draw. This mod
 * draws it itself, with particles, so the shape is worked out here - the edges
 * of a box, the outline of a polygon at its floor and its ceiling, the rings of
 * a sphere or a cylinder, the edges of a hull - where the engine tests cover
 * it, and the platform only has to put particles along straight segments.</p>
 */
public final class Cui {

    /** The segments a ring of a sphere or a cylinder is drawn with, at most. */
    private static final int MAX_RING_SEGMENTS = 64;

    private Cui() {
    }

    /** {@code FAWE » Cuboid: 12x70x12 (10,080 blocks)}: the shape, its size and its blocks. */
    public static Msg size(RegionSelector selector) {
        Region region = selector.getRegion();
        String type = selector.getTypeName();
        return Msg.result(Character.toUpperCase(type.charAt(0)) + type.substring(1),
                Msg.size(region.getWidth(), region.getHeight(), region.getLength()) + " ("
                        + Msg.blocks(region.getVolume()) + ")");
    }

    /**
     * The shape of a selection as straight segments, and the points its clicks
     * placed.
     *
     * @param segments six numbers per segment - the x, y and z of both ends -
     *                 in world coordinates, where a block spans from its
     *                 coordinate to the next: the edge of a box runs along the
     *                 outside of its blocks
     */
    public record Outline(double[] segments, List<BlockVector3> primary, List<BlockVector3> secondary) {

        public int segmentCount() {
            return segments.length / 6;
        }

        /** The length of every segment together, which is what the particles are spread over. */
        public double length() {
            double total = 0;
            for (int i = 0; i < segments.length; i += 6) {
                double dx = segments[i + 3] - segments[i];
                double dy = segments[i + 4] - segments[i + 1];
                double dz = segments[i + 5] - segments[i + 2];
                total += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            return total;
        }

        public boolean isEmpty() {
            return segments.length == 0 && primary.isEmpty() && secondary.isEmpty();
        }
    }

    /** The outline of what a selector holds, drawn for its shape. */
    public static Outline outline(RegionSelector selector) {
        Segments out = new Segments();
        Region region = selector.getRegion();
        if (selector instanceof Selectors.Polygonal2DSelector polygon) {
            polygon(out, polygon.getPoints(), region.getMinimumPoint().y(), region.getMaximumPoint().y());
        } else if (selector.isDefined()) {
            if (region instanceof EllipsoidRegion ellipsoid) {
                ellipsoid(out, ellipsoid);
            } else if (region instanceof CylinderRegion cylinder) {
                cylinder(out, cylinder);
            } else if (region instanceof ConvexPolyhedralRegion convex) {
                hull(out, convex.getTriangles());
            } else if (region instanceof PolyhedralRegion polyhedral) {
                hull(out, polyhedral.getTriangles());
            } else {
                box(out, region.getMinimumPoint(), region.getMaximumPoint());
            }
        }
        return new Outline(out.toArray(), selector.primaryPoints(), selector.secondaryPoints());
    }

    /** The twelve edges of the box around the blocks {@code min..max}. */
    private static void box(Segments out, BlockVector3 min, BlockVector3 max) {
        double x0 = min.x();
        double y0 = min.y();
        double z0 = min.z();
        double x1 = max.x() + 1.0;
        double y1 = max.y() + 1.0;
        double z1 = max.z() + 1.0;
        for (int corner = 0; corner < 4; corner++) {
            double y = (corner & 1) == 0 ? y0 : y1;
            double z = (corner & 2) == 0 ? z0 : z1;
            out.add(x0, y, z, x1, y, z);
            double x = (corner & 2) == 0 ? x0 : x1;
            out.add(x, y, z0, x, y, z1);
            double side = (corner & 1) == 0 ? x0 : x1;
            out.add(side, y0, z, side, y1, z);
        }
    }

    /**
     * A polygon at its floor and its ceiling, through the middle of the blocks
     * its points are, with an upright at each point. Before the third point it
     * is the path of the points clicked so far.
     */
    private static void polygon(Segments out, List<BlockVector2> points, int minY, int maxY) {
        double bottom = minY;
        double top = maxY + 1.0;
        int count = points.size();
        for (int i = 0; i < count; i++) {
            BlockVector2 point = points.get(i);
            double x = point.x() + 0.5;
            double z = point.z() + 0.5;
            out.add(x, bottom, z, x, top, z);
            if (i + 1 < count || count >= 3) {
                BlockVector2 next = points.get((i + 1) % count);
                double nx = next.x() + 0.5;
                double nz = next.z() + 0.5;
                out.add(x, bottom, z, nx, bottom, nz);
                out.add(x, top, z, nx, top, nz);
            }
        }
    }

    /** Three rings around the centre, one in each plane, along the surface the blocks fill. */
    private static void ellipsoid(Segments out, EllipsoidRegion ellipsoid) {
        Vector3 centre = ellipsoid.getCenter();
        Vector3 radii = ellipsoid.getRadii().add(0.5, 0.5, 0.5);
        int steps = ringSteps(Math.max(radii.x(), Math.max(radii.y(), radii.z())));
        for (int i = 0; i < steps; i++) {
            double from = 2 * Math.PI * i / steps;
            double to = 2 * Math.PI * (i + 1) / steps;
            out.add(centre.x() + radii.x() * Math.cos(from), centre.y(), centre.z() + radii.z() * Math.sin(from),
                    centre.x() + radii.x() * Math.cos(to), centre.y(), centre.z() + radii.z() * Math.sin(to));
            out.add(centre.x() + radii.x() * Math.cos(from), centre.y() + radii.y() * Math.sin(from), centre.z(),
                    centre.x() + radii.x() * Math.cos(to), centre.y() + radii.y() * Math.sin(to), centre.z());
            out.add(centre.x(), centre.y() + radii.y() * Math.cos(from), centre.z() + radii.z() * Math.sin(from),
                    centre.x(), centre.y() + radii.y() * Math.cos(to), centre.z() + radii.z() * Math.sin(to));
        }
    }

    /** The rings of the floor and the ceiling, joined by four uprights. */
    private static void cylinder(Segments out, CylinderRegion cylinder) {
        double cx = cylinder.getCenter2D().x();
        double cz = cylinder.getCenter2D().z();
        double rx = cylinder.getRadiusX() + 0.5;
        double rz = cylinder.getRadiusZ() + 0.5;
        double bottom = cylinder.getMinY();
        double top = cylinder.getMaxY() + 1.0;
        int steps = ringSteps(Math.max(rx, rz));
        for (int i = 0; i < steps; i++) {
            double from = 2 * Math.PI * i / steps;
            double to = 2 * Math.PI * (i + 1) / steps;
            double x0 = cx + rx * Math.cos(from);
            double z0 = cz + rz * Math.sin(from);
            double x1 = cx + rx * Math.cos(to);
            double z1 = cz + rz * Math.sin(to);
            out.add(x0, bottom, z0, x1, bottom, z1);
            out.add(x0, top, z0, x1, top, z1);
        }
        out.add(cx + rx, bottom, cz, cx + rx, top, cz);
        out.add(cx - rx, bottom, cz, cx - rx, top, cz);
        out.add(cx, bottom, cz + rz, cx, top, cz + rz);
        out.add(cx, bottom, cz - rz, cx, top, cz - rz);
    }

    /** Each edge of the hull's faces once, through the middle of the vertex blocks. */
    private static void hull(Segments out, List<BlockVector3[]> triangles) {
        Set<List<BlockVector3>> drawn = new HashSet<>();
        for (BlockVector3[] triangle : triangles) {
            for (int i = 0; i < 3; i++) {
                BlockVector3 a = triangle[i];
                BlockVector3 b = triangle[(i + 1) % 3];
                List<BlockVector3> edge = a.compareTo(b) <= 0 ? List.of(a, b) : List.of(b, a);
                if (drawn.add(edge)) {
                    out.add(a.x() + 0.5, a.y() + 0.5, a.z() + 0.5, b.x() + 0.5, b.y() + 0.5, b.z() + 0.5);
                }
            }
        }
    }

    /** About a segment every block and a half of a ring, between 16 and {@value #MAX_RING_SEGMENTS}. */
    private static int ringSteps(double radius) {
        return Math.max(16, Math.min(MAX_RING_SEGMENTS, (int) Math.ceil(2 * Math.PI * radius / 1.5)));
    }

    /** A growing list of segments, six numbers each. */
    private static final class Segments {

        private double[] values = new double[72];
        private int size;

        void add(double x0, double y0, double z0, double x1, double y1, double z1) {
            if (size + 6 > values.length) {
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = x0;
            values[size++] = y0;
            values[size++] = z0;
            values[size++] = x1;
            values[size++] = y1;
            values[size++] = z1;
        }

        double[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}
