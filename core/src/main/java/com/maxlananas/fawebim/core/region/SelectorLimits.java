package com.maxlananas.fawebim.core.region;

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

    /**
     * The limits a player's clicks have, WorldEdit's defaults: a polygon or a
     * convex selection takes points while it has no more than 20.
     */
    public static SelectorLimits player() {
        return new SelectorLimits(PLAYER_VERTEX_LIMIT, PLAYER_VERTEX_LIMIT);
    }

    /** WorldEdit's {@code max-polygonal-points} and {@code max-polyhedron-points}. */
    public static final int PLAYER_VERTEX_LIMIT = 20;

    public int getPolygonVertexLimit() {
        return polygonVertexLimit;
    }

    public int getPolyhedronVertexLimit() {
        return polyhedronVertexLimit;
    }
}
