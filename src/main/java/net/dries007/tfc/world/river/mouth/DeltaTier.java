/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.world.region.RiverEdge;

/**
 * Size tier of a delta, selected by terminal river width. All dimension ranges are expressed in exact river-grid
 * units (1 grid = 128 blocks at stock scale), stored unscaled, and multiplied by the effective worldgen scale at use.
 * Values are from the locked design (RIVER_DELTA_DESIGN.md §2).
 */
public enum DeltaTier
{
    COMPACT(14, 0.65, 0.85, 0.40, 0.70, 0.25, 0.45, 2, 2),
    NORMAL(17, 0.80, 1.05, 0.70, 1.15, 0.35, 0.65, 2, 4),
    MAJOR(22, 1.00, 1.25, 1.10, 1.75, 0.55, 0.95, 3, 5);

    /**
     * The minimum terminal river width (in blocks, within {@link RiverEdge#MIN_WIDTH}..{@link RiverEdge#MAX_WIDTH})
     * for any delta to form. Rivers narrower than this never qualify. Tunable.
     */
    public static final int DELTA_MIN_WIDTH = COMPACT.minWidth;

    /**
     * @return The tier for a given terminal river width, or {@code null} if the river is too narrow for any delta.
     */
    @Nullable
    public static DeltaTier byWidth(int riverWidth)
    {
        if (riverWidth >= MAJOR.minWidth) return MAJOR;
        if (riverWidth >= NORMAL.minWidth) return NORMAL;
        if (riverWidth >= COMPACT.minWidth) return COMPACT;
        return null;
    }

    private final int minWidth;
    private final double minInlandReachGrid, maxInlandReachGrid;
    private final double minFanHalfWidthGrid, maxFanHalfWidthGrid;
    private final double minSeawardReachGrid, maxSeawardReachGrid;
    private final int minDistributaries, maxDistributaries;

    DeltaTier(int minWidth, double minInlandReachGrid, double maxInlandReachGrid, double minFanHalfWidthGrid, double maxFanHalfWidthGrid, double minSeawardReachGrid, double maxSeawardReachGrid, int minDistributaries, int maxDistributaries)
    {
        this.minWidth = minWidth;
        this.minInlandReachGrid = minInlandReachGrid;
        this.maxInlandReachGrid = maxInlandReachGrid;
        this.minFanHalfWidthGrid = minFanHalfWidthGrid;
        this.maxFanHalfWidthGrid = maxFanHalfWidthGrid;
        this.minSeawardReachGrid = minSeawardReachGrid;
        this.maxSeawardReachGrid = maxSeawardReachGrid;
        this.minDistributaries = minDistributaries;
        this.maxDistributaries = maxDistributaries;
    }

    public int minWidth()
    {
        return minWidth;
    }

    public double inlandReachGrid(double t)
    {
        return lerp(t, minInlandReachGrid, maxInlandReachGrid);
    }

    public double fanHalfWidthGrid(double t)
    {
        return lerp(t, minFanHalfWidthGrid, maxFanHalfWidthGrid);
    }

    public double seawardReachGrid(double t)
    {
        return lerp(t, minSeawardReachGrid, maxSeawardReachGrid);
    }

    public int minDistributaries()
    {
        return minDistributaries;
    }

    public int maxDistributaries()
    {
        return maxDistributaries;
    }

    private static double lerp(double t, double min, double max)
    {
        return min + t * (max - min);
    }
}
