/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import java.util.List;

import net.dries007.tfc.world.region.RiverEdge;

/**
 * The cached, per-terminal-edge context of a qualifying river mouth. Created once per terminal edge by
 * {@link RiverMouthResolver} and reused by terrain generation, flow placement, biome overlay, and surfaces.
 * <p>
 * All spatial values are in exact river-grid units (1 grid = 128 blocks at stock scale), stored unscaled;
 * {@code worldgenScale} multiplies every reach at use. During the {@code RiverBlendType.DELTA} prototype every
 * context is a delta; extraction to {@code RiverMouthBlendType} adds a type field without changing coordinates.
 *
 * @param anchorGridX The rendered-coastline anchor (the mouth origin), in grid coordinates.
 * @param inlandUnitX Unit vector from the anchor pointing inland (toward {@code edge.source()}).
 * @param normalizedRiverWidth Terminal river width normalized over [{@link RiverEdge#MIN_WIDTH}, {@link RiverEdge#MAX_WIDTH}].
 * @param shapeSeed Deterministic seed for all shape jitter, derived from the level seed and quantized drain coordinates.
 */
public record RiverMouthContext(
    RiverEdge edge,
    DeltaTier tier,
    double anchorGridX,
    double anchorGridZ,
    double inlandUnitX,
    double inlandUnitZ,
    double normalizedRiverWidth,
    double inlandReachGrid,
    double seawardReachGrid,
    double fanHalfWidthGrid,
    double worldgenScale,
    long shapeSeed,
    List<RiverMouthChannel> channels
) {}
