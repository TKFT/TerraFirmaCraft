/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import org.jetbrains.annotations.Nullable;

/**
 * The per-column result of a mouth query. Projections are in grid units (scaled space — the same space the
 * reaches occupy after multiplying by the worldgen scale).
 *
 * @param alongGrid Signed distance along the flow axis from the anchor. Positive = inland, negative = seaward.
 * @param acrossGrid Signed lateral distance from the flow axis.
 * @param normalizedAlong {@code alongGrid} normalized by the inland (positive) or seaward (negative) reach.
 * @param normalizedAcross {@code acrossGrid} normalized by the local fan half-width.
 * @param terrainWeight Fan mask weight in [0, 1] — 1 deep inside the fan, feathering to 0 at the noisy boundary.
 * @param channel The nearest mouth channel, or {@code null} if none is close enough to matter.
 */
public record RiverMouthSample(
    RiverMouthContext context,
    double alongGrid,
    double acrossGrid,
    double normalizedAlong,
    double normalizedAcross,
    double terrainWeight,
    boolean inFan,
    @Nullable RiverMouthChannelSample channel
) {}
