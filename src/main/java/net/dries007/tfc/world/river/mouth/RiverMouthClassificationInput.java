/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import java.util.List;

import net.dries007.tfc.world.biome.BiomeExtension;

/**
 * Everything the classifier is allowed to see, sampled from the raw (no-river) biome field around the rendered
 * coastline anchor by {@link RiverMouthResolver}. Keeping this a plain value type keeps the classifier a pure,
 * unit-testable function.
 *
 * @param terminal Whether the edge is terminal ({@code edge.drainEdge() == null}).
 * @param riverWidth Terminal river width in blocks.
 * @param shoreAtAnchor The raw biome at the rendered coastline anchor.
 * @param inlandBiomes Raw biomes sampled behind the anchor (inland direction).
 * @param aheadBiomes Raw biomes sampled seaward of the anchor, just past the fan front.
 * @param constructionAreaBiomes Raw biomes sampled across the whole intended fan construction area.
 */
public record RiverMouthClassificationInput(
    boolean terminal,
    int riverWidth,
    BiomeExtension shoreAtAnchor,
    List<BiomeExtension> inlandBiomes,
    List<BiomeExtension> aheadBiomes,
    List<BiomeExtension> constructionAreaBiomes
) {}
