/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import net.dries007.tfc.world.river.Flow;

/**
 * The nearest-channel result for a single column. Distances and widths are in grid units, unscaled.
 */
public record RiverMouthChannelSample(
    RiverMouthChannel channel,
    RiverMouthChannelSegment segment,
    double distanceGrid,
    double widthGrid,
    Flow flow
) {}
