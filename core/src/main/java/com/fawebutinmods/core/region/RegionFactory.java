package com.fawebutinmods.core.region;

import com.fawebutinmods.core.math.BlockVector3;

/** A shape factory used where commands accept a region shape argument. */
@FunctionalInterface
public interface RegionFactory {

    Region createCenteredAt(BlockVector3 center, double radius);
}
