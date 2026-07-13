/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import com.google.common.collect.MapMaker;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.region.RegionPartition;
import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.region.Units;
import net.dries007.tfc.world.river.Flow;
import net.dries007.tfc.world.river.RiverHelpers;

/**
 * Resolves terminal river edges into cached {@link RiverMouthContext}s: terminal-edge detection, the
 * coastline-anchor march over the raw biome field, classifier input sampling, deterministic scale-aware
 * dimensions, and the strongest mouth sample for a column.
 * <p>
 * Both qualifying and nonqualifying terminal edges are cached, so failed classification and anchor searches run
 * once per edge, never per column. All results are pure functions of the level seed and the edge's stable
 * coordinates — cache hits or misses can never change generated terrain.
 */
public final class RiverMouthResolver
{
    /** Step of the coastline-anchor march, in grid units (16 blocks at stock scale). */
    public static final double ANCHOR_STEP_GRID = 0.125;
    /** Cap on the anchor march distance from the graph drain, in grid units (384 blocks at stock scale). */
    public static final double MAX_ANCHOR_DISTANCE_GRID = 3.0;

    /**
     * Raw (no-river, no-mouth) biome field access, at quart resolution. The anchor march and classifier must only
     * ever see this field, never any overlay.
     */
    @FunctionalInterface
    public interface RawBiomeSampler
    {
        BiomeExtension get(int quartX, int quartZ);
    }

    private final long levelSeed;
    private final RawBiomeSampler rawBiomes;
    private final double worldgenScale;

    // Weak keys: RiverEdge instances are owned by the evicting region cache. Identical replacement edges resolve
    // to identical contexts (all inputs are stable coordinates), so eviction cannot affect worldgen output.
    private final ConcurrentMap<RiverEdge, Optional<ResolvedMouth>> cache = new MapMaker().weakKeys().makeMap();

    public RiverMouthResolver(long levelSeed, RawBiomeSampler rawBiomes, double worldgenScale)
    {
        this.levelSeed = levelSeed;
        this.rawBiomes = rawBiomes;
        this.worldgenScale = worldgenScale;
    }

    /**
     * @return The strongest mouth sample affecting the given exact grid position, considering every terminal edge
     * known to the partition point, or {@code null} if no qualifying mouth reaches it.
     */
    @Nullable
    public RiverMouthSample sample(RegionPartition.Point point, double exactGridX, double exactGridZ)
    {
        RiverMouthSample best = null;
        for (RiverEdge edge : point.rivers())
        {
            if (edge.drainEdge() != null)
            {
                continue;
            }
            // Cheap pre-resolve reject: the anchor may wander up to the march cap from the drain, plus the fan's
            // own worst-case extent. Everything farther cannot possibly be affected.
            final double maxExtent = (MAX_ANCHOR_DISTANCE_GRID + 2.0) * worldgenScale;
            final double dx = exactGridX - edge.drain().x(), dz = exactGridZ - edge.drain().y();
            if (dx * dx + dz * dz > maxExtent * maxExtent)
            {
                continue;
            }

            final Optional<ResolvedMouth> mouth = resolve(edge);
            if (mouth.isPresent())
            {
                final RiverMouthSample sample = mouth.get().geometry().sample(exactGridX, exactGridZ);
                if (sample != null && (best == null || sample.terrainWeight() > best.terrainWeight()))
                {
                    best = sample;
                }
            }
        }
        return best;
    }

    /**
     * @return The cached mouth for a terminal edge, resolving it on first access. Empty for nonterminal edges and
     * for terminal edges that do not classify as a delta.
     */
    public Optional<ResolvedMouth> resolve(RiverEdge edge)
    {
        return cache.computeIfAbsent(edge, this::resolveUncached);
    }

    private Optional<ResolvedMouth> resolveUncached(RiverEdge edge)
    {
        if (edge.drainEdge() != null)
        {
            return Optional.empty();
        }

        final DeltaTier tierByWidth = DeltaTier.byWidth(edge.width);
        if (tierByWidth == null)
        {
            return Optional.empty(); // Too narrow for any delta - skip the anchor march entirely
        }

        final double drainX = edge.drain().x(), drainZ = edge.drain().y();
        double inlandX = edge.source().x() - drainX, inlandZ = edge.source().y() - drainZ;
        final double magnitude = Math.sqrt(inlandX * inlandX + inlandZ * inlandZ);
        if (magnitude < 1.0e-6)
        {
            return Optional.empty(); // Degenerate edge
        }
        inlandX /= magnitude;
        inlandZ /= magnitude;

        // All shape jitter derives from the level seed and the quantized drain position - never instance identity
        final long shapeSeed = RiverMouthRandom.mix(levelSeed ^ RiverMouthRandom.mix(RiverHelpers.pack(
            (int) Math.round(drainX * 8), (int) Math.round(drainZ * 8))));

        final double[] anchor = findCoastlineAnchor(drainX, drainZ, inlandX, inlandZ);
        final double anchorX = anchor[0], anchorZ = anchor[1];

        // Deterministic dimension jitter within the tier's locked ranges, unscaled grid units
        final double inlandReachGrid = tierByWidth.inlandReachGrid(RiverMouthRandom.hash01(shapeSeed, 11));
        final double seawardReachGrid = tierByWidth.seawardReachGrid(RiverMouthRandom.hash01(shapeSeed, 12));
        final double fanHalfWidthGrid = tierByWidth.fanHalfWidthGrid(RiverMouthRandom.hash01(shapeSeed, 13));

        final Optional<DeltaTier> tier = RiverMouthClassifier.classifyDelta(new RiverMouthClassificationInput(
            true,
            edge.width,
            rawBiomeAt(anchorX, anchorZ),
            sampleInlandBiomes(anchorX, anchorZ, inlandX, inlandZ, inlandReachGrid),
            sampleAheadBiomes(anchorX, anchorZ, inlandX, inlandZ, seawardReachGrid, fanHalfWidthGrid),
            sampleConstructionAreaBiomes(anchorX, anchorZ, inlandX, inlandZ, seawardReachGrid, fanHalfWidthGrid)
        ));
        if (tier.isEmpty())
        {
            return Optional.empty();
        }

        final double normalizedRiverWidth = (edge.width - RiverEdge.MIN_WIDTH) / (double) (RiverEdge.MAX_WIDTH - RiverEdge.MIN_WIDTH);
        final RiverMouthContext context = new RiverMouthContext(
            edge, tier.get(),
            anchorX, anchorZ,
            inlandX, inlandZ,
            normalizedRiverWidth,
            inlandReachGrid, seawardReachGrid, fanHalfWidthGrid,
            worldgenScale, shapeSeed,
            buildTrunkChannel(edge, anchorX, anchorZ, inlandX, inlandZ, seawardReachGrid)
        );
        return Optional.of(new ResolvedMouth(context, new RiverMouthGeometry(context)));
    }

    /**
     * DIAGNOSTIC (one-channel milestone): the mouth network is only the terminal trunk — the fractal's rendered
     * course, plus a seaward extension from the graph drain to just past the fan's ocean-facing boundary, so the
     * channel always reaches open water. The real distributary network replaces the single-channel list in the
     * next phase; the trunk continuation stays branch depth 0.
     */
    private List<RiverMouthChannel> buildTrunkChannel(RiverEdge edge, double anchorX, double anchorZ, double inlandX, double inlandZ, double seawardReachGrid)
    {
        final double widthGrid = edge.width / (double) Units.GRID_WIDTH_IN_BLOCK;
        final List<RiverMouthChannelSegment> segments = new ArrayList<>();

        // The fractal runs source -> drain, which is already upstream -> seaward order
        final double[] points = edge.fractal().segments;
        for (int i = 0; i < points.length - 2; i += 2)
        {
            segments.add(segment(points[i], points[i + 1], points[i + 2], points[i + 3], widthGrid, widthGrid));
        }

        // Continue past the drain, parallel to the mouth axis, until just beyond the seaward fan boundary
        final double drainX = edge.drain().x(), drainZ = edge.drain().y();
        final double alongDrain = (drainX - anchorX) * inlandX + (drainZ - anchorZ) * inlandZ;
        final double seawardEnd = -(seawardReachGrid + RiverMouthGeometry.BOUNDARY_NOISE_GRID + RiverMouthGeometry.FEATHER_GRID) * worldgenScale;
        if (alongDrain > seawardEnd)
        {
            final double reach = alongDrain - seawardEnd;
            segments.add(segment(drainX, drainZ, drainX - inlandX * reach, drainZ - inlandZ * reach, widthGrid, widthGrid));
        }

        return List.of(new RiverMouthChannel(List.copyOf(segments), widthGrid, widthGrid, 0));
    }

    private static RiverMouthChannelSegment segment(double startX, double startZ, double endX, double endZ, double startWidth, double endWidth)
    {
        // Same flow-angle convention as MidpointFractal.calculateFlow: grid +z is negated for the polar angle
        final Flow flow = Flow.fromAngle(Mth.atan2(-(endZ - startZ), endX - startX));
        return new RiverMouthChannelSegment(startX, startZ, endX, endZ, startWidth, endWidth, flow);
    }

    /**
     * Marches the raw biome field along the drain axis to find the rendered coastline (impl plan §6). The graph
     * drain sits on a shore region point, but the quart-scale rendered biome border drifts from region vertices —
     * anchoring to the rendered transition prevents mouths stranded offshore or entirely inland.
     * <p>
     * The anchor is placed at the transition between stable inland terrain and the shore/ocean band: marching
     * inland if the drain renders as water or shore, seaward if it renders as land. Falls back to the graph drain
     * if no transition is found within {@link #MAX_ANCHOR_DISTANCE_GRID}.
     *
     * @return {@code [anchorGridX, anchorGridZ]}
     */
    private double[] findCoastlineAnchor(double drainX, double drainZ, double inlandX, double inlandZ)
    {
        final BiomeExtension atDrain = rawBiomeAt(drainX, drainZ);
        final int steps = (int) (MAX_ANCHOR_DISTANCE_GRID / ANCHOR_STEP_GRID);

        if (RiverMouthBiomes.isAnchorLand(atDrain))
        {
            // March seaward until the first shore or ocean sample, and anchor at the straddling midpoint
            for (int i = 1; i <= steps; i++)
            {
                final double along = -i * ANCHOR_STEP_GRID;
                if (!RiverMouthBiomes.isAnchorLand(rawBiomeAt(drainX + inlandX * along, drainZ + inlandZ * along)))
                {
                    final double mid = along + 0.5 * ANCHOR_STEP_GRID;
                    return new double[] {drainX + inlandX * mid, drainZ + inlandZ * mid};
                }
            }
        }
        else
        {
            // March inland until stable land (two consecutive land samples), and anchor at the transition
            for (int i = 1; i <= steps; i++)
            {
                final double along = i * ANCHOR_STEP_GRID;
                if (RiverMouthBiomes.isAnchorLand(rawBiomeAt(drainX + inlandX * along, drainZ + inlandZ * along))
                    && RiverMouthBiomes.isAnchorLand(rawBiomeAt(drainX + inlandX * (along + ANCHOR_STEP_GRID), drainZ + inlandZ * (along + ANCHOR_STEP_GRID))))
                {
                    final double mid = along - 0.5 * ANCHOR_STEP_GRID;
                    return new double[] {drainX + inlandX * mid, drainZ + inlandZ * mid};
                }
            }
        }
        return new double[] {drainX, drainZ}; // Fallback: the graph drain
    }

    private List<BiomeExtension> sampleInlandBiomes(double anchorX, double anchorZ, double inlandX, double inlandZ, double inlandReachGrid)
    {
        final List<BiomeExtension> samples = new ArrayList<>();
        final double reach = (inlandReachGrid + 0.4) * worldgenScale; // Sample slightly past the fan tip
        for (int i = 1; i <= 4; i++)
        {
            final double along = reach * i / 4;
            samples.add(rawBiomeAt(anchorX + inlandX * along, anchorZ + inlandZ * along));
        }
        // Two lateral samples at half reach, catching valley walls beside the trunk
        final double half = reach * 0.5, lateral = 0.5 * worldgenScale;
        samples.add(rawBiomeAt(anchorX + inlandX * half - inlandZ * lateral, anchorZ + inlandZ * half + inlandX * lateral));
        samples.add(rawBiomeAt(anchorX + inlandX * half + inlandZ * lateral, anchorZ + inlandZ * half - inlandX * lateral));
        return samples;
    }

    private List<BiomeExtension> sampleAheadBiomes(double anchorX, double anchorZ, double inlandX, double inlandZ, double seawardReachGrid, double fanHalfWidthGrid)
    {
        // Sample from just past the fan front out to ~0.9 grid beyond it: the rendered shore band can be wider
        // than the seaward reach, so open water may sit well past the fan front and still be "ahead" of the mouth
        final List<BiomeExtension> samples = new ArrayList<>();
        for (final double beyond : new double[] {0.125, 0.5, 0.875})
        {
            final double along = -(seawardReachGrid + beyond) * worldgenScale;
            for (final double side : new double[] {-0.5, 0, 0.5})
            {
                final double across = side * fanHalfWidthGrid * worldgenScale;
                samples.add(rawBiomeAt(
                    anchorX + inlandX * along - inlandZ * across,
                    anchorZ + inlandZ * along + inlandX * across));
            }
        }
        return samples;
    }

    private List<BiomeExtension> sampleConstructionAreaBiomes(double anchorX, double anchorZ, double inlandX, double inlandZ, double seawardReachGrid, double fanHalfWidthGrid)
    {
        // The seaward half of the fan is where deep-ocean construction could occur; the inland half is covered by
        // the inland family samples
        final List<BiomeExtension> samples = new ArrayList<>();
        for (int i = 0; i <= 4; i++)
        {
            final double along = -seawardReachGrid * worldgenScale * i / 4;
            for (final double side : new double[] {-1, -0.5, 0, 0.5, 1})
            {
                final double across = side * fanHalfWidthGrid * worldgenScale;
                samples.add(rawBiomeAt(
                    anchorX + inlandX * along - inlandZ * across,
                    anchorZ + inlandZ * along + inlandX * across));
            }
        }
        return samples;
    }

    private BiomeExtension rawBiomeAt(double exactGridX, double exactGridZ)
    {
        return rawBiomes.get(
            RiverHelpers.floor(exactGridX * Units.GRID_WIDTH_IN_QUART),
            RiverHelpers.floor(exactGridZ * Units.GRID_WIDTH_IN_QUART));
    }

    /**
     * A resolved, qualifying mouth: the immutable context plus its derived geometry.
     */
    public record ResolvedMouth(RiverMouthContext context, RiverMouthGeometry geometry) {}
}
