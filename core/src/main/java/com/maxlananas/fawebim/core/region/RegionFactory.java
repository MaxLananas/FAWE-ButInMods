package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;

/** A shape factory used where commands accept a region shape argument. */
@FunctionalInterface
public interface RegionFactory {

    Region createCenteredAt(BlockVector3 center, double radius);
}
