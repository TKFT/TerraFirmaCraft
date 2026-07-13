/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import java.util.Optional;

import net.dries007.tfc.world.biome.BiomeExtension;

/**
 * The strict, pure delta classifier (RIVER_DELTA_DESIGN.md §5). A terminal river qualifies only when every
 * condition agrees:
 * <ul>
 *     <li>The edge is terminal.</li>
 *     <li>The river is medium-wide or wider ({@link DeltaTier#DELTA_MIN_WIDTH}).</li>
 *     <li>The shore at the rendered anchor is depositional: {@code tidal_flats} or {@code shore};
 *     {@code coastal_dunes} accepts the compact tier only; {@code embayments} and everything else is rejected.</li>
 *     <li>Every stable-land sample behind the anchor belongs to the flat/low inland family, and at least one exists.</li>
 *     <li>Shallow ocean is present ahead of the mouth, and no deep or forbidden ocean appears anywhere in the
 *     construction area.</li>
 * </ul>
 * This is a pure function of its input: no noise, no caching, no world access.
 */
public final class RiverMouthClassifier
{
    public static Optional<DeltaTier> classifyDelta(RiverMouthClassificationInput input)
    {
        if (!input.terminal())
        {
            return Optional.empty();
        }

        final DeltaTier tier = DeltaTier.byWidth(input.riverWidth());
        if (tier == null)
        {
            return Optional.empty();
        }

        final BiomeExtension shore = input.shoreAtAnchor();
        if (!RiverMouthBiomes.isEligibleDeltaShore(shore) && !(RiverMouthBiomes.isDuneShore(shore) && tier == DeltaTier.COMPACT))
        {
            return Optional.empty();
        }

        // Inland: every stable-land sample must be flat/low family, and at least one must exist. Shore and water
        // samples behind the anchor are ignored - a wide shore band is fine, a mountain behind it is not.
        boolean anyFlatInland = false;
        for (BiomeExtension biome : input.inlandBiomes())
        {
            if (RiverMouthBiomes.isAnchorLand(biome))
            {
                if (!RiverMouthBiomes.isFlatInland(biome))
                {
                    return Optional.empty();
                }
                anyFlatInland = true;
            }
        }
        if (!anyFlatInland)
        {
            return Optional.empty();
        }

        // Ahead: the fan must face shallow ocean or shelf
        boolean anyShallowAhead = false;
        for (BiomeExtension biome : input.aheadBiomes())
        {
            if (RiverMouthBiomes.isDeepOrForbiddenOcean(biome))
            {
                return Optional.empty();
            }
            if (RiverMouthBiomes.isShallowOcean(biome))
            {
                anyShallowAhead = true;
            }
        }
        if (!anyShallowAhead)
        {
            return Optional.empty();
        }

        // Construction area: no deep ocean anywhere inside the intended fan
        for (BiomeExtension biome : input.constructionAreaBiomes())
        {
            if (RiverMouthBiomes.isDeepOrForbiddenOcean(biome))
            {
                return Optional.empty();
            }
        }

        return Optional.of(tier);
    }

    private RiverMouthClassifier() {}
}
