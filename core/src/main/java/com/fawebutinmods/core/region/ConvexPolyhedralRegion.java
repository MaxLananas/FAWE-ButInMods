package com.fawebutinmods.core.region;

import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A convex polyhedron defined by triangles ({@code //sel convex}); vertices are
 * added one by one and a new triangle is created with every third vertex, the
 * same rule WorldEdit uses.
 */
public class ConvexPolyhedralRegion implements Region {

    private final List<BlockVector3> vertices = new ArrayList<>();
    private final List<Triangle> triangles = new ArrayList<>();
    private int minY;
    private int maxY;

    public void setBounds(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
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

    public List<BlockVector3> getVertices() {
        return vertices;
    }

    public int getTriangleCount() {
        return triangles.size();
    }

    public boolean canAddVertexIndex(int index) {
        return true;
    }

    public boolean addVertex(BlockVector3 vertex) {
        if (!vertices.isEmpty() && vertices.get(vertices.size() - 1).equals(vertex)) {
            return false;
        }
        vertices.add(vertex);
        if (vertices.size() >= 3 && vertices.size() % 3 == 0) {
            Triangle t = new Triangle(
                    vertices.get(vertices.size() - 3),
                    vertices.get(vertices.size() - 2),
                    vertices.get(vertices.size() - 1));
            if (t.isValid()) {
                triangles.add(t);
            } else {
                return false;
            }
        }
        return true;
    }

    public boolean removeLastVertex() {
        if (vertices.isEmpty()) {
            return false;
        }
        vertices.remove(vertices.size() - 1);
        if (!triangles.isEmpty() && vertices.size() % 3 == 2) {
            triangles.remove(triangles.size() - 1);
        }
        return true;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int lowestY = Integer.MAX_VALUE;
        for (BlockVector3 v : vertices) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            lowestY = Math.min(lowestY, v.y());
        }
        if (vertices.isEmpty()) {
            return BlockVector3.ZERO;
        }
        return new BlockVector3(minX, Math.min(lowestY, minY), minZ);
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        if (vertices.isEmpty()) {
            return BlockVector3.ZERO;
        }
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int highestY = Integer.MIN_VALUE;
        for (BlockVector3 v : vertices) {
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
            highestY = Math.max(highestY, v.y());
        }
        return new BlockVector3(maxX, Math.max(highestY, maxY), maxZ);
    }

    @Override
    public long getVolume() {
        return (long) getWidth() * getHeight() * getLength();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (triangles.size() < 4 || y < minY || y > maxY) {
            return false;
        }
        // A point is inside when it is on the inner side of every face plane.
        for (Plane plane : planes()) {
            if (plane.distance(x + 0.5, y + 0.5, z + 0.5) > 0) {
                return false;
            }
        }
        return true;
    }

    private List<Plane> planes() {
        List<Plane> planes = new ArrayList<>();
        // Faces of the hull: every triangle with its outward normal.
        for (Triangle t : triangles) {
            Vector3 normal = t.normal();
            Vector3 center = new Vector3(0, 0, 0);
            for (BlockVector3 v : vertices) {
                center = center.add(v.toVector3());
            }
            if (vertices.isEmpty()) {
                continue;
            }
            center = center.divide(vertices.size());
            Plane plane = new Plane(normal, t.a.toVector3());
            if (plane.distance(center.x(), center.y(), center.z()) > 0) {
                plane = plane.flip();
            }
            planes.add(plane);
        }
        return planes;
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        if (amount.y() != 0) {
            int dy = amount.y();
            List<BlockVector3> moved = new ArrayList<>(vertices.size());
            for (BlockVector3 v : vertices) {
                moved.add(v.withY(Math.min(maxY, Math.max(minY, v.y() + dy))));
            }
            vertices.clear();
            vertices.addAll(moved);
            triangles.clear();
            for (int i = 0; i + 2 < vertices.size(); i += 3) {
                Triangle t = new Triangle(vertices.get(i), vertices.get(i + 1), vertices.get(i + 2));
                if (t.isValid()) {
                    triangles.add(t);
                }
            }
        }
        return true;
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        return expand(amount.multiply(-1));
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        List<BlockVector3> blocks = new ArrayList<>();
        if (triangles.size() >= 4) {
            BlockVector3 min = getMinimumPoint();
            BlockVector3 max = getMaximumPoint();
            for (int y = Math.max(min.y(), minY); y <= Math.min(max.y(), maxY); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    for (int x = min.x(); x <= max.x(); x++) {
                        if (contains(x, y, z)) {
                            blocks.add(new BlockVector3(x, y, z));
                        }
                    }
                }
            }
        }
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < blocks.size();
            }

            @Override
            public BlockVector3 next() {
                if (index >= blocks.size()) {
                    throw new NoSuchElementException();
                }
                return blocks.get(index++);
            }
        };
    }

    @Override
    public String describe() {
        return "convex (" + (vertices.size() / 3) + " triangles)";
    }

    private record Triangle(BlockVector3 a, BlockVector3 b, BlockVector3 c) {

        boolean isValid() {
            return normal().lengthSq() > 1e-9;
        }

        Vector3 normal() {
            return b.subtract(a).toVector3().cross(c.subtract(a).toVector3()).normalize();
        }
    }

    private record Plane(Vector3 normal, Vector3 point) {

        double distance(double x, double y, double z) {
            return normal.dot(new Vector3(x, y, z).subtract(point));
        }

        Plane flip() {
            return new Plane(normal.multiply(-1), point);
        }
    }
}
