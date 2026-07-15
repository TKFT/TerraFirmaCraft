/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.placement;

import java.util.stream.Stream;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.region.Units;
import net.dries007.tfc.world.river.mouth.RiverMouthResolver;
import net.dries007.tfc.world.river.mouth.RiverMouthSample;
import net.dries007.tfc.world.surface.RiverMouthSurface;

/**
 * Restricts a feature to the wet-flat band of a terminal river mouth (delta). The position's band is classified
 * from the resolver's cached mouth sample and the generated surface height - the same classification the surface
 * stage used - so features gated by this modifier land exactly on the silt/mud plain between distributaries.
 *
 * <p>Used by the delta clay boost: deltas are a reliable early-game clay source, so clay discs are placed far more
 * densely here than the ordinary climate-gated distribution. The rate itself is the count in the placed feature
 * (see {@code delta_clay_disc_with_indicator}).
 */
public class RiverMouthWetFlatPlacement extends PlacementModifier
{
    public static final RiverMouthWetFlatPlacement INSTANCE = new RiverMouthWetFlatPlacement();
    public static final MapCodec<RiverMouthWetFlatPlacement> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos)
    {
        if (!(context.generator().getBiomeSource() instanceof BiomeSourceExtension source))
        {
            return Stream.empty();
        }
        final RiverMouthResolver resolver = source.riverMouthResolver();
        if (resolver == null)
        {
            return Stream.empty();
        }
        final RiverMouthSample sample = resolver.sample(
            source.getPartition(pos.getX(), pos.getZ()),
            Units.blockToGridExact(pos.getX()),
            Units.blockToGridExact(pos.getZ()));
        // The heightmap modifier ran before this one: pos is the first air block, so the surface is one below
        return RiverMouthSurface.classify(sample, pos.getY() - 1, context.generator().getSeaLevel()) == RiverMouthSurface.Band.WET_FLAT
            ? Stream.of(pos)
            : Stream.empty();
    }

    @Override
    public PlacementModifierType<?> type()
    {
        return TFCPlacements.RIVER_MOUTH_WET_FLAT.get();
    }
}
