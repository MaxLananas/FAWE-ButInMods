package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.Vector2;

/**
 * The region shapes FAWE accepts where a shape argument is expected
 * ({@code /brush set <shape>}, {@code //forest <shape>}, ...).
 */
public final class RegionFactories {

    private RegionFactories() {
    }

    /** Parses shapes such as {@code sphere}, {@code cyl}, {@code cuboid}, {@code cube}. */
    public static RegionFactory parse(String input, int minY, int maxY) {
        String key = input.toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "sphere", "ball" -> (center, radius) -> new EllipsoidRegion(
                    center.toCenter(), new com.maxlananas.fawebim.core.math.Vector3(radius, radius, radius), minY, maxY);
            // WorldEdit's cylinder shape is a disc one block high at the centre;
            // the world's height is what fixedcyl is for. Spanning the world here
            // put a cylinder brush through every layer from bedrock to the sky.
            case "cyl", "cylinder" -> (center, radius) -> new CylinderRegion(
                    new Vector2(center.x() + 0.5, center.z() + 0.5), radius, radius,
                    Math.max(minY, Math.min(maxY, center.y())), Math.max(minY, Math.min(maxY, center.y())));
            case "cube", "cuboid", "box" -> (center, radius) -> {
                int r = (int) Math.floor(radius);
                return new CuboidRegion(center.add(-r, -r, -r), center.add(r, r, r));
            };
            case "fixedsphere", "fixed-sphere", "fixedheightsphere" -> (center, radius) -> new EllipsoidRegion(
                    center.toCenter(), new com.maxlananas.fawebim.core.math.Vector3(radius, radius, radius));
            case "fixedcyl", "fixed-cylinder" -> (center, radius) -> new CylinderRegion(
                    new Vector2(center.x() + 0.5, center.z() + 0.5), radius, radius, minY, maxY);
            default -> null;
        };
    }

    public static final java.util.List<String> SHAPES = java.util.List.of(
            "sphere", "cyl", "cuboid", "cube", "fixedsphere", "fixedcyl");

}
