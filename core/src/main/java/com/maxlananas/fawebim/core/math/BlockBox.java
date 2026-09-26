package com.maxlananas.fawebim.core.math;

/**
 * A mutable axis-aligned box of block coordinates, used for chunk/region
 * iteration and clipboard bounds.
 */
public final class BlockBox {

    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    public BlockBox() {
        this(0, 0, 0, 0, 0, 0);
    }

    public BlockBox(BlockVector3 point) {
        this(point.x(), point.y(), point.z(), point.x(), point.y(), point.z());
    }

    public BlockBox(BlockVector3 a, BlockVector3 b) {
        this(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static BlockBox fromChunk(int chunkX, int chunkZ, int minY, int maxY) {
        return new BlockBox(chunkX << 4, minY, chunkZ << 4, (chunkX << 4) + 15, maxY, (chunkZ << 4) + 15);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public BlockVector3 min() {
        return new BlockVector3(minX, minY, minZ);
    }

    public BlockVector3 max() {
        return new BlockVector3(maxX, maxY, maxZ);
    }

    public BlockVector3 center() {
        return new BlockVector3((minX + maxX) >> 1, (minY + maxY) >> 1, (minZ + maxZ) >> 1);
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int length() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) width() * height() * length();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockVector3 v) {
        return contains(v.x(), v.y(), v.z());
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= (minX >> 4) && chunkX <= (maxX >> 4) && chunkZ >= (minZ >> 4) && chunkZ <= (maxZ >> 4);
    }

    public BlockBox expand(int dx, int dy, int dz) {
        return new BlockBox(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public BlockBox expand(int amount) {
        return expand(amount, amount, amount);
    }

    public BlockBox shift(int dx, int dy, int dz) {
        return new BlockBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public BlockBox union(BlockBox other) {
        return new BlockBox(
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public BlockBox intersect(BlockBox other) {
        return new BlockBox(
                Math.max(minX, other.minX), Math.max(minY, other.minY), Math.max(minZ, other.minZ),
                Math.min(maxX, other.maxX), Math.min(maxY, other.maxY), Math.min(maxZ, other.maxZ));
    }

    public boolean intersects(BlockBox other) {
        return maxX >= other.minX && minX <= other.maxX
                && maxY >= other.minY && minY <= other.maxY
                && maxZ >= other.minZ && minZ <= other.maxZ;
    }

    public void setMin(BlockVector3 v) {
        minX = v.x();
        minY = v.y();
        minZ = v.z();
    }

    public void setMax(BlockVector3 v) {
        maxX = v.x();
        maxY = v.y();
        maxZ = v.z();
    }

    public void set(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public BlockBox clampY(int min, int max) {
        return new BlockBox(minX, Math.max(minY, min), minZ, maxX, Math.min(maxY, max), maxZ);
    }

    public BlockBox copy() {
        return new BlockBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return "BlockBox(" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + ")";
    }
}
