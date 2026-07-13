/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import net.dries007.tfc.world.river.Flow;
import net.dries007.tfc.world.river.RiverHelpers;

/**
 * One straight segment of a mouth channel. Endpoints and widths are in exact river-grid units, stored unscaled.
 * Flow points downstream (start = upstream end, end = seaward end).
 */
public record RiverMouthChannelSegment(
    double startGridX,
    double startGridZ,
    double endGridX,
    double endGridZ,
    double startWidthGrid,
    double endWidthGrid,
    Flow flow
)
{
    /**
     * @return The square distance, in grid units, from the point to this segment.
     */
    public double distanceSqGrid(double gridX, double gridZ)
    {
        return RiverHelpers.distancePointToLineSq(startGridX, startGridZ, endGridX, endGridZ, gridX, gridZ);
    }

    /**
     * @return The tapered channel width, in grid units, at the projection of the point onto this segment.
     */
    public double widthGridAt(double gridX, double gridZ)
    {
        final double t = RiverHelpers.projectAlongLine(startGridX, startGridZ, endGridX, endGridZ, gridX, gridZ);
        return startWidthGrid + t * (endWidthGrid - startWidthGrid);
    }
}
