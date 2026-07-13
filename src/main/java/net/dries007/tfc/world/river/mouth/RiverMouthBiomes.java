/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import java.util.Set;

import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.TFCBiomes;

/**
 * Centralized biome-group predicates used by the mouth classifier and the coastline-anchor march, in place of
 * repeating long biome identity checks (design doc §5/§6). These are intentionally strict for the delta prototype;
 * they grow into shared terrain-group metadata when more mouth types are added.
 */
public final class RiverMouthBiomes
{
    /**
     * Shores that accept a delta of any tier at the rendered anchor. {@code EMBAYMENTS} is deliberately absent —
     * it is reserved as prime estuary terrain (locked decision, RIVER_DELTA_DESIGN.md §1).
     */
    private static final Set<BiomeExtension> ELIGIBLE_DELTA_SHORES = Set.of(
        TFCBiomes.SHORE,
        TFCBiomes.TIDAL_FLATS
    );

    /**
     * Shallow / shelf-like ocean a delta may build into.
     */
    private static final Set<BiomeExtension> SHALLOW_OCEANS = Set.of(
        TFCBiomes.OCEAN,
        TFCBiomes.OCEAN_REEF,
        TFCBiomes.OCEAN_ATOLLS
    );

    /**
     * Ocean that forbids delta construction anywhere inside the fan area. Volcanic arcs are included per the
     * coastal compatibility matrix (volcanic coasts reject deltas).
     */
    private static final Set<BiomeExtension> DEEP_OR_FORBIDDEN_OCEANS = Set.of(
        TFCBiomes.DEEP_OCEAN,
        TFCBiomes.DEEP_OCEAN_TRENCH,
        TFCBiomes.DEEP_OCEAN_ATOLLS,
        TFCBiomes.OCEAN_RIDGE,
        TFCBiomes.OCEANIC_VOLCANIC_ARC
    );

    /**
     * The flat / low-relief inland families that can host a delta plain behind the anchor (design doc §5.1,
     * strict v1 subset).
     */
    private static final Set<BiomeExtension> FLAT_INLAND = Set.of(
        TFCBiomes.PLAINS,
        TFCBiomes.HILLS,
        TFCBiomes.LOWLANDS,
        TFCBiomes.SALT_MARSH,
        TFCBiomes.RIVER_VALLEY,
        TFCBiomes.LOW_CANYONS,
        TFCBiomes.MUD_FLATS,
        TFCBiomes.PATTERNED_GROUND,
        TFCBiomes.INVERTED_PATTERNED_GROUND,
        TFCBiomes.STONE_CIRCLES,
        TFCBiomes.KNOB_AND_KETTLE,
        TFCBiomes.DOLINE_PLAINS,
        TFCBiomes.CENOTE_PLAINS
    );

    public static boolean isEligibleDeltaShore(BiomeExtension biome)
    {
        return ELIGIBLE_DELTA_SHORES.contains(biome);
    }

    public static boolean isDuneShore(BiomeExtension biome)
    {
        return biome == TFCBiomes.COASTAL_DUNES;
    }

    public static boolean isShallowOcean(BiomeExtension biome)
    {
        return SHALLOW_OCEANS.contains(biome);
    }

    public static boolean isDeepOrForbiddenOcean(BiomeExtension biome)
    {
        return DEEP_OR_FORBIDDEN_OCEANS.contains(biome);
    }

    public static boolean isFlatInland(BiomeExtension biome)
    {
        return FLAT_INLAND.contains(biome);
    }

    /**
     * @return {@code true} if the raw biome reads as open water for the purpose of the coastline-anchor march.
     * Shore biomes are excluded — they are the transition band the march is looking for.
     */
    public static boolean isAnchorOcean(BiomeExtension biome)
    {
        return !biome.isShore() && (isShallowOcean(biome) || isDeepOrForbiddenOcean(biome));
    }

    /**
     * @return {@code true} if the raw biome reads as stable land (neither shore nor ocean) for the anchor march.
     */
    public static boolean isAnchorLand(BiomeExtension biome)
    {
        return !biome.isShore() && !isAnchorOcean(biome);
    }

    private RiverMouthBiomes() {}
}
