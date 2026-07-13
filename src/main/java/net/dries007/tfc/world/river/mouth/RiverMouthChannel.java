/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A single connected channel of a river mouth network — the terminal trunk continuation, or one distributary.
 * Segments are ordered upstream to seaward. Widths are in exact river-grid units, stored unscaled.
 *
 * @param branchDepth 0 for the trunk continuation, incrementing by one per bifurcation.
 */
public record RiverMouthChannel(
    List<RiverMouthChannelSegment> segments,
    double startWidthGrid,
    double endWidthGrid,
    int branchDepth
)
{
    /**
     * @return The nearest-segment sample for the given position, or {@code null} if no segment is within
     * {@code maxDistanceGrid} of the point.
     */
    @Nullable
    public RiverMouthChannelSample sample(double gridX, double gridZ, double maxDistanceGrid)
    {
        RiverMouthChannelSegment nearest = null;
        double minDistSq = maxDistanceGrid * maxDistanceGrid;
        for (RiverMouthChannelSegment segment : segments)
        {
            final double distSq = segment.distanceSqGrid(gridX, gridZ);
            if (distSq < minDistSq)
            {
                minDistSq = distSq;
                nearest = segment;
            }
        }
        if (nearest == null)
        {
            return null;
        }
        return new RiverMouthChannelSample(this, nearest, Math.sqrt(minDistSq), nearest.widthGridAt(gridX, gridZ), nearest.flow());
    }
}
