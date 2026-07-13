/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.region.Units;

/**
 * Flow-aligned fan geometry for a single mouth context: along/across projection, the noisy outer boundary, and
 * nearest-channel lookup. All math is in exact river-grid units; every reach is multiplied by the context's
 * worldgen scale at use.
 * <p>
 * The outer boundary is a wedge that pinches to roughly valley width at the inland tip and opens to the full fan
 * half-width at the coastline, modulated by deterministic sector noise so it never reads as an analytic shape
 * (design requirement: never a smooth arc).
 */
public final class RiverMouthGeometry
{
    /** Amplitude of the sector-noise boundary modulation, in grid units (~15 blocks at stock scale). */
    public static final double BOUNDARY_NOISE_GRID = 0.12;
    /** Width of the feathered mask edge, in grid units (~14 blocks at stock scale). */
    public static final double FEATHER_GRID = 0.11;
    /** How far, in multiples of local channel width, a channel is considered for the nearest-channel sample. */
    public static final double CHANNEL_INFLUENCE_WIDTHS = 4;

    private static final int BASE_SECTORS = 12;

    private final RiverMouthContext context;
    private final double inlandReach, seawardReach, fanHalfWidth, tipHalfWidth;
    private final double boundaryNoise, feather;

    public RiverMouthGeometry(RiverMouthContext context)
    {
        this.context = context;
        this.inlandReach = context.inlandReachGrid() * context.worldgenScale();
        this.seawardReach = context.seawardReachGrid() * context.worldgenScale();
        this.fanHalfWidth = context.fanHalfWidthGrid() * context.worldgenScale();
        this.boundaryNoise = BOUNDARY_NOISE_GRID * context.worldgenScale();
        this.feather = FEATHER_GRID * context.worldgenScale();

        // The wedge pinches to ~2.5x the trunk half-width at the inland tip, so the fan hands over to the
        // ordinary river valley without a visible step
        final double trunkWidthGrid = Mth.lerp(context.normalizedRiverWidth(), RiverEdge.MIN_WIDTH, RiverEdge.MAX_WIDTH) / Units.GRID_WIDTH_IN_BLOCK;
        this.tipHalfWidth = 1.25 * trunkWidthGrid * context.worldgenScale();
    }

    /**
     * @return The mouth sample at an exact grid position, or {@code null} if the position is entirely outside the
     * fan mask (including its feather and worst-case boundary noise).
     */
    @Nullable
    public RiverMouthSample sample(double exactGridX, double exactGridZ)
    {
        final double relX = exactGridX - context.anchorGridX();
        final double relZ = exactGridZ - context.anchorGridZ();

        final double along = relX * context.inlandUnitX() + relZ * context.inlandUnitZ();
        final double across = relX * -context.inlandUnitZ() + relZ * context.inlandUnitX();

        // Cheap reject far outside the fan, before any noise math
        if (along > inlandReach + boundaryNoise + feather
            || along < -(seawardReach + boundaryNoise + feather)
            || Math.abs(across) > fanHalfWidth + boundaryNoise + feather)
        {
            return null;
        }

        final double halfWidth = halfWidthAt(along);
        final double boundary = Math.max(
            Math.max(along - inlandReach, -along - seawardReach),
            Math.abs(across) - halfWidth
        ) + sectorNoise(Math.atan2(across, along));

        final double terrainWeight = Mth.clampedMap(boundary, 0, -feather, 0, 1);
        if (terrainWeight <= 0)
        {
            return null;
        }

        final double normalizedAlong = along >= 0 ? along / inlandReach : along / seawardReach;
        final double normalizedAcross = across / halfWidth;

        return new RiverMouthSample(context, along, across, normalizedAlong, normalizedAcross, terrainWeight, true, sampleNearestChannel(exactGridX, exactGridZ));
    }

    /**
     * The lateral half-width of the fan wedge at a given signed along-axis position: the full fan half-width from
     * the coastline seaward, pinching smoothly to the tip width at the inland end.
     */
    public double halfWidthAt(double along)
    {
        final double t = Mth.clamp(1 - along / inlandReach, 0, 1);
        return Mth.lerp(t * t * (3 - 2 * t), tipHalfWidth, fanHalfWidth);
    }

    /**
     * @return The nearest mouth channel sample at the position, minimizing distance normalized by channel width,
     * or {@code null} when no channel is within {@link #CHANNEL_INFLUENCE_WIDTHS} of the position.
     */
    @Nullable
    public RiverMouthChannelSample sampleNearestChannel(double exactGridX, double exactGridZ)
    {
        RiverMouthChannelSample nearest = null;
        double minNormalized = Double.MAX_VALUE;
        for (RiverMouthChannel channel : context.channels())
        {
            final RiverMouthChannelSample sample = channel.sample(exactGridX, exactGridZ, CHANNEL_INFLUENCE_WIDTHS * channel.startWidthGrid() * context.worldgenScale());
            if (sample != null)
            {
                final double normalized = sample.distanceGrid() / sample.widthGrid();
                if (normalized < minNormalized)
                {
                    minNormalized = normalized;
                    nearest = sample;
                }
            }
        }
        return nearest;
    }

    /**
     * Deterministic sector noise around the anchor: two octaves of smoothly interpolated per-sector values, purely
     * a function of {@code (shapeSeed, angle)}. Returns a signed boundary offset in grid units.
     */
    public double sectorNoise(double angle)
    {
        return (0.7 * sectorValue(angle, BASE_SECTORS, context.shapeSeed())
            + 0.3 * sectorValue(angle, BASE_SECTORS * 2, context.shapeSeed() + 1)) * boundaryNoise;
    }

    private static double sectorValue(double angle, int sectors, long seed)
    {
        final double position = (angle + Math.PI) / (2 * Math.PI) * sectors;
        final int sector = Math.floorMod((int) Math.floor(position), sectors);
        final double t = position - Math.floor(position);
        final double v0 = RiverMouthRandom.hash11(seed, sector);
        final double v1 = RiverMouthRandom.hash11(seed, (sector + 1) % sectors);
        return Mth.lerp(t * t * (3 - 2 * t), v0, v1);
    }
}
