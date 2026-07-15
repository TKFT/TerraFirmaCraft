/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.surface;

import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.world.Seed;
import net.dries007.tfc.world.noise.Noise2D;
import net.dries007.tfc.world.noise.OpenSimplex2D;
import net.dries007.tfc.world.region.Units;
import net.dries007.tfc.world.river.mouth.RiverMouthChannelSample;
import net.dries007.tfc.world.river.mouth.RiverMouthSample;
import net.dries007.tfc.world.surface.builder.NormalSurfaceBuilder;
import net.dries007.tfc.world.surface.builder.SurfaceBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * Deterministic surface decisions for terminal river mouths. Bands are classified from the cached per-column
 * mouth sample plus the carved height - never a fresh terminal-edge search - so surface materials always agree
 * with the terrain the fan actually built. The band boundaries mirror the DELTA sampler's height profile: its
 * {@code distFac} bands for channel bed and levees, and its plain hypsometry for wet flats vs. raised islands.
 *
 * <p>All materials are climate-aware soil ladders (snow / arid transitions included), so cold or dry deltas stay
 * recognizable without anything hardcoded temperate.
 */
public final class RiverMouthSurface
{
    // == Phase 5 tunables ==
    /**
     * Normalized channel dist² band edges. The DELTA sampler works in {@code distFac = normDistSq * 0.8 + fuzz};
     * these are its bed (< 1) and levee (< 1.75) band edges mapped back to normDistSq, without the fuzz.
     */
    public static final double CHANNEL_NORM_DIST_SQ = 1 / 0.8;
    public static final double MARGIN_NORM_DIST_SQ = 1.75 / 0.8;
    /** Only channels at least this wide (in blocks) get gravel margins - "gravel on strong channel margins". */
    public static final double STRONG_CHANNEL_MIN_WIDTH_BLOCKS = 14;
    /** Plain columns more than this above sea level are raised islands: native soil and grass. */
    public static final double RAISED_ISLAND_MIN_HEIGHT_ABOVE_SEA = 1.25;
    /** Seaward of this fraction of the seaward reach, low columns become the sandy outer front. */
    public static final double OUTER_FRONT_NORMALIZED_ALONG = -0.55;
    /** The outer front only claims columns at most this far above sea level; higher ground stays island. */
    public static final double OUTER_FRONT_MAX_HEIGHT_ABOVE_SEA = 0.75;
    /**
     * Plain columns more than this below sea level are submerged shoal, not wet flat: pond bottoms and the
     * deep-water build cap ({@code min(plain, heightIn + 8)}) both bottom out well below the flat band.
     */
    public static final double WET_FLAT_MAX_DEPTH_BELOW_SEA = 2;

    private static final long FEATHER_SALT = 782634923418276L;

    /** Surface region of a column inside a mouth's fan mask, {@link #NONE} outside every mask. */
    public enum Band
    {
        NONE,
        /** Channel bed and waterline. */
        CHANNEL,
        /** Levee rise and fall beside a strong channel. */
        CHANNEL_MARGIN,
        /** The low wet plain between channels - silt, mud, and the clay band. */
        WET_FLAT,
        /** Submerged plain: pond bottoms and deep-water-capped shoal. Mud, but never the clay gate. */
        SHOAL,
        /** Distinct raised islands and the inland transition - native soil and grass. */
        RAISED_ISLAND,
        /** The sandy outer delta front facing the open sea. */
        OUTER_FRONT;

        public static final Band[] VALUES = values();
    }

    /**
     * Classifies the surface band for a column, from the mouth sample cached during the height pass and the
     * carved height of that column. Pure and deterministic.
     */
    public static Band classify(@Nullable RiverMouthSample mouth, double surfaceHeight, int seaLevel)
    {
        if (mouth == null || !mouth.inFan() || mouth.terrainWeight() <= 0)
        {
            return Band.NONE;
        }

        final RiverMouthChannelSample channel = mouth.channel();
        if (channel != null)
        {
            final double width = channel.widthGrid() * mouth.context().worldgenScale();
            if (width > 0)
            {
                final double normDistSq = (channel.distanceGrid() * channel.distanceGrid()) / (width * width);
                if (normDistSq < CHANNEL_NORM_DIST_SQ)
                {
                    return Band.CHANNEL;
                }
                if (normDistSq < MARGIN_NORM_DIST_SQ && channel.widthGrid() * Units.GRID_WIDTH_IN_BLOCK >= STRONG_CHANNEL_MIN_WIDTH_BLOCKS)
                {
                    return Band.CHANNEL_MARGIN;
                }
            }
        }

        if (mouth.normalizedAlong() < OUTER_FRONT_NORMALIZED_ALONG && surfaceHeight <= seaLevel + OUTER_FRONT_MAX_HEIGHT_ABOVE_SEA)
        {
            return Band.OUTER_FRONT;
        }
        if (surfaceHeight > seaLevel + RAISED_ISLAND_MIN_HEIGHT_ABOVE_SEA)
        {
            return Band.RAISED_ISLAND;
        }
        if (surfaceHeight < seaLevel - WET_FLAT_MAX_DEPTH_BELOW_SEA)
        {
            return Band.SHOAL;
        }
        return Band.WET_FLAT;
    }

    /** Patchwork mixing of mud and silty grass over the wet flats, and gravel blotches on margins. */
    private final Noise2D patchNoise;

    public RiverMouthSurface(Seed seed)
    {
        this.patchNoise = new OpenSimplex2D(seed.next()).octaves(2).spread(0.04f);
    }

    /**
     * Builds the surface of one column inside a mouth's fan mask, replacing the biome's own surface with the
     * band's delta materials. Through the feathered fan boundary ({@code weight < 1}) columns dither back to
     * {@code delegate}, mirroring how the terrain itself blends out.
     */
    public void buildSurface(SurfaceBuilderContext context, Band band, double weight, SurfaceBuilder delegate, int startY, int endY)
    {
        if (band == Band.NONE || (weight < 1 && ((Helpers.hash(FEATHER_SALT, context.pos()) & 1023) / 1023d) >= weight))
        {
            delegate.buildSurface(context, startY, endY);
            return;
        }

        final double patch = patchNoise.noise(context.pos().getX(), context.pos().getZ()) * 0.9 + context.random().nextDouble() * 0.1;
        switch (band)
        {
            case CHANNEL -> NormalSurfaceBuilder.INSTANCE.buildSurface(context, startY, endY,
                SurfaceStates.SILTY_GRASS, SurfaceStates.MID_DIRT_TO_GRAVEL, SurfaceStates.UNDER_GRAVEL,
                SurfaceStates.RIVER_SAND, SurfaceStates.RIVER_SAND);
            case CHANNEL_MARGIN -> NormalSurfaceBuilder.INSTANCE.buildSurface(context, startY, endY,
                patch < 0.4 ? SurfaceStates.GRAVEL : SurfaceStates.SILTY_GRASS, SurfaceStates.MID_DIRT_TO_GRAVEL, SurfaceStates.UNDER_GRAVEL,
                SurfaceStates.GRAVEL, SurfaceStates.GRAVEL);
            case WET_FLAT -> NormalSurfaceBuilder.INSTANCE.buildSurface(context, startY, endY,
                patch < 0 ? SurfaceStates.SILTY_GRASS : SurfaceStates.MUD, SurfaceStates.MUD, SurfaceStates.MID_DIRT_TO_GRAVEL,
                SurfaceStates.OCEAN_MUD, SurfaceStates.OCEAN_MUD);
            case SHOAL -> NormalSurfaceBuilder.INSTANCE.buildSurface(context, startY, endY,
                SurfaceStates.MUD, SurfaceStates.MUD, SurfaceStates.MID_DIRT_TO_GRAVEL,
                SurfaceStates.OCEAN_MUD, SurfaceStates.OCEAN_MUD);
            case RAISED_ISLAND -> NormalSurfaceBuilder.INSTANCE.buildSurface(context, startY, endY);
            case OUTER_FRONT -> NormalSurfaceBuilder.INSTANCE.buildSurface(context, startY, endY,
                SurfaceStates.SAND, SurfaceStates.SAND, SurfaceStates.UNDER_GRAVEL,
                SurfaceStates.SAND, SurfaceStates.SAND_AND_GRAVEL);
            default -> throw new IllegalStateException("Unhandled band " + band);
        }
    }
}
