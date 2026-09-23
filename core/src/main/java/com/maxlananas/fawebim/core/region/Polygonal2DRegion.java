package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** A 2D polygon extruded over a Y range ({@code //sel poly}). */
public class Polygonal2DRegion implements Region {

    private final List<BlockVector2> points;
    private int minY;
    private int maxY;

    public Polygonal2DRegion(List<BlockVector2> points, int minY, int maxY) {
        this.points = new ArrayList<>(points);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public List<BlockVector2> getPoints() {
        return points;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public void setMinY(int y) {
        this.minY = y;
    }

    public void setMaxY(int y) {
        this.maxY = y;
    }

    public void addPoint(BlockVector2 point) {
        points.add(point);
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockVector2 p : points) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        if (points.isEmpty()) {
            return BlockVector3.ZERO;
        }
        return new BlockVector3(minX, minY, minZ);
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        if (points.isEmpty()) {
            return BlockVector3.ZERO;
        }
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockVector2 p : points) {
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        return new BlockVector3(maxX, maxY, maxZ);
    }

    @Override
    public long getVolume() {
        long area = 0;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            BlockVector2 a = points.get(i);
            BlockVector2 b = points.get((i + 1) % n);
            area += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        // Shoelace gives twice the area; add the boundary blocks Minecraft counts as inside.
        long polygonArea = Math.abs(area) / 2 + n / 2 + 1;
        return polygonArea * getHeight();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (y < minY || y > maxY || points.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = points.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            BlockVector2 pi = points.get(i);
            BlockVector2 pj = points.get(j);
            if (((pi.z() > z) != (pj.z() > z))
                    && (x < (double) (pj.x() - pi.x()) * (z - pi.z()) / (double) (pj.z() - pi.z()) + pi.x())) {
                inside = !inside;
            }
        }
        return inside;
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        if (amount.y() < 0) {
            minY += amount.y();
        } else if (amount.y() > 0) {
            maxY += amount.y();
        }
        if (amount.x() != 0 || amount.z() != 0) {
            // Scaling a polygon is done about its centroid, like WorldEdit does.
            BlockVector3 min = getMinimumPoint();
            BlockVector3 max = getMaximumPoint();
            double cx = (min.x() + max.x()) / 2.0;
            double cz = (min.z() + max.z()) / 2.0;
            List<BlockVector2> expanded = new ArrayList<>(points.size());
            for (BlockVector2 p : points) {
                double nx = p.x() + Math.signum(p.x() - cx) * amount.x();
                double nz = p.z() + Math.signum(p.z() - cz) * amount.z();
                expanded.add(new BlockVector2((int) Math.round(nx), (int) Math.round(nz)));
            }
            points.clear();
            points.addAll(expanded);
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
        if (points.size() >= 3) {
            BlockVector3 min = getMinimumPoint();
            BlockVector3 max = getMaximumPoint();
            for (int y = minY; y <= maxY; y++) {
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
        return "poly (" + points.size() + " points, " + getWidth() + "x" + getHeight() + "x" + getLength() + ")";
    }
}
