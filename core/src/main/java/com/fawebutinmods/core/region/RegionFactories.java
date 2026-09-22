package com.fawebutinmods.core.region;

import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector2;

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
                    center.toCenter(), new com.fawebutinmods.core.math.Vector3(radius, radius, radius), minY, maxY);
            case "cyl", "cylinder" -> (center, radius) -> new CylinderRegion(
                    new Vector2(center.x() + 0.5, center.z() + 0.5), radius, radius, minY, maxY);
            case "cube", "cuboid", "box" -> (center, radius) -> {
                int r = (int) Math.floor(radius);
                return new CuboidRegion(center.add(-r, -r, -r), center.add(r, r, r));
            };
            case "fixedsphere", "fixed-sphere", "fixedheightsphere" -> (center, radius) -> new EllipsoidRegion(
                    center.toCenter(), new com.fawebutinmods.core.math.Vector3(radius, radius, radius));
            case "fixedcyl", "fixed-cylinder" -> (center, radius) -> new CylinderRegion(
                    new Vector2(center.x() + 0.5, center.z() + 0.5), radius, radius, minY, maxY);
            default -> null;
        };
    }

    public static final java.util.List<String> SHAPES = java.util.List.of(
            "sphere", "cyl", "cuboid", "cube", "fixedsphere", "fixedcyl");

}
