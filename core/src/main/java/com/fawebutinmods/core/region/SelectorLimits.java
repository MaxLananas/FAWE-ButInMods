package com.fawebutinmods.core.region;

/** Per-actor limits on selector vertices (polygon/polyhedron). */
public final class SelectorLimits {

    private final int polygonVertexLimit;
    private final int polyhedronVertexLimit;

    public SelectorLimits(int polygonVertexLimit, int polyhedronVertexLimit) {
        this.polygonVertexLimit = polygonVertexLimit;
        this.polyhedronVertexLimit = polyhedronVertexLimit;
    }

    public static SelectorLimits unlimited() {
        return new SelectorLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public int getPolygonVertexLimit() {
        return polygonVertexLimit;
    }

    public int getPolyhedronVertexLimit() {
        return polyhedronVertexLimit;
    }
}
