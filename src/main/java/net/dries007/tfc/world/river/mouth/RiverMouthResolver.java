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
            buildChannels(edge, tier.get(), shapeSeed, anchorX, anchorZ, inlandX, inlandZ, inlandReachGrid, seawardReachGrid, fanHalfWidthGrid)
        );
        return Optional.of(new ResolvedMouth(context, new RiverMouthGeometry(context)));
    }

    /**
     * Builds the deterministic distributary network. The dominant channel (branch depth 0, index 0) is the trunk's
     * continuation: the terminal fractal's rendered course up to the bifurcation apex in the upper-middle fan, then
     * a gently bent path to just past the ocean-facing boundary, so a boat can always pass ocean → river. Secondary
     * distributaries (branch depth 1) split from staggered points along the dominant path with seeded jitter on
     * split positions, directions, and widths — narrower and less direct than the trunk, unequal to each other,
     * laterally spaced so readable islands survive between the channels. All endpoints reach past the seaward fan
     * boundary into (classifier-guaranteed) shallow ocean.
     */
    private List<RiverMouthChannel> buildChannels(RiverEdge edge, DeltaTier tier, long seed, double anchorX, double anchorZ, double inlandX, double inlandZ, double inlandReachGrid, double seawardReachGrid, double fanHalfWidthGrid)
    {
        final double trunkWidth = edge.width / (double) Units.GRID_WIDTH_IN_BLOCK;
        final double halfWidth = fanHalfWidthGrid * worldgenScale;
        final double seawardEnd = -(seawardReachGrid + RiverMouthGeometry.BOUNDARY_NOISE_GRID + RiverMouthGeometry.FEATHER_GRID) * worldgenScale;

        // --- The bifurcation apex: the point on the rendered trunk course nearest the target depth into the fan
        final double apexAlongTarget = (0.20 + 0.25 * RiverMouthRandom.hash01(seed, 23)) * inlandReachGrid * worldgenScale;
        final double[] points = edge.fractal().segments;
        int apexIndex = points.length - 2;
        for (int i = points.length - 2; i >= 0; i -= 2)
        {
            apexIndex = i;
            final double along = (points[i] - anchorX) * inlandX + (points[i + 1] - anchorZ) * inlandZ;
            if (along >= apexAlongTarget)
            {
                break;
            }
        }
        final double apexX = points[apexIndex], apexZ = points[apexIndex + 1];
        final double apexAlong = (apexX - anchorX) * inlandX + (apexZ - anchorZ) * inlandZ;
        final double apexAcross = (apexX - anchorX) * -inlandZ + (apexZ - anchorZ) * inlandX;

        // --- The dominant channel: trunk course upstream of the apex, then apex -> mid -> seaward end
        final List<RiverMouthChannelSegment> dominant = new ArrayList<>();
        for (int i = 0; i < apexIndex; i += 2)
        {
            dominant.add(segment(points[i], points[i + 1], points[i + 2], points[i + 3], trunkWidth, trunkWidth));
        }
        final double dominantEndAcross = apexAcross * 0.3 + RiverMouthRandom.hash11(seed, 22) * 0.15 * halfWidth;
        final double[] apex = {apexX, apexZ};
        final double[] dominantMid = position(anchorX, anchorZ, inlandX, inlandZ,
            0.5 * (apexAlong + seawardEnd),
            0.5 * (apexAcross + dominantEndAcross) + RiverMouthRandom.hash11(seed, 70) * 0.10 * halfWidth);
        final double[] dominantEnd = position(anchorX, anchorZ, inlandX, inlandZ, seawardEnd, dominantEndAcross);
        dominant.add(segment(apex[0], apex[1], dominantMid[0], dominantMid[1], trunkWidth, 0.92 * trunkWidth));
        dominant.add(segment(dominantMid[0], dominantMid[1], dominantEnd[0], dominantEnd[1], 0.92 * trunkWidth, 0.85 * trunkWidth));

        final List<RiverMouthChannel> channels = new ArrayList<>();
        channels.add(new RiverMouthChannel(List.copyOf(dominant), trunkWidth, 0.85 * trunkWidth, 0));

        // --- Secondary distributaries, splitting from staggered points on the dominant path
        final double[] slots = secondarySlots(distributaryCount(tier, seed) - 1, seed);
        for (int i = 0; i < slots.length; i++)
        {
            final double splitT = 0.15 + 0.45 * RiverMouthRandom.hash01(seed, 90 + i);
            final double[] split = pointOnPath(apex, dominantMid, dominantEnd, splitT);
            final double splitAlong = (split[0] - anchorX) * inlandX + (split[1] - anchorZ) * inlandZ;
            final double splitAcross = (split[0] - anchorX) * -inlandZ + (split[1] - anchorZ) * inlandX;

            final double endAcross = slots[i] * halfWidth;
            final double startWidth = Math.max(trunkWidth * (0.40 + 0.20 * RiverMouthRandom.hash01(seed, 50 + i)), 4.0 / Units.GRID_WIDTH_IN_BLOCK);
            final double endWidth = 0.6 * startWidth;

            final double[] mid = position(anchorX, anchorZ, inlandX, inlandZ,
                0.5 * (splitAlong + seawardEnd),
                0.5 * (splitAcross + endAcross) + RiverMouthRandom.hash11(seed, 71 + i) * 0.10 * halfWidth);
            final double[] end = position(anchorX, anchorZ, inlandX, inlandZ, seawardEnd, endAcross);

            channels.add(new RiverMouthChannel(List.of(
                segment(split[0], split[1], mid[0], mid[1], startWidth, 0.8 * startWidth),
                segment(mid[0], mid[1], end[0], end[1], 0.8 * startWidth, endWidth)
            ), startWidth, endWidth, 1));
        }

        return List.copyOf(channels);
    }

    /**
     * @return The total downstream channel count for the tier: compact always 2, normal 2–4, major 3–4 with a
     * rare fifth (locked dimensions table, RIVER_DELTA_DESIGN.md §2).
     */
    private static int distributaryCount(DeltaTier tier, long seed)
    {
        return switch (tier)
        {
            case COMPACT -> 2;
            case NORMAL -> 2 + (int) (3 * RiverMouthRandom.hash01(seed, 20));
            case MAJOR -> RiverMouthRandom.hash01(seed, 21) < 0.12 ? 5 : 3 + (int) (2 * RiverMouthRandom.hash01(seed, 20));
        };
    }

    /**
     * Lateral endpoint positions for the secondary distributaries, as signed fractions of the fan half-width.
     * Slots alternate sides at staggered magnitudes with bounded jitter, so minimum spacing (readable islands)
     * holds by construction; overall handedness flips per mouth.
     */
    private static double[] secondarySlots(int count, long seed)
    {
        final double flip = RiverMouthRandom.hash01(seed, 24) < 0.5 ? 1 : -1;
        final double[] slots = new double[count];
        for (int i = 0; i < count; i++)
        {
            // 1st: near one flank; 2nd: opposite flank; 3rd/4th: outer flanks. Magnitude bands are disjoint by
            // >= 0.23 of the half-width, so same-side channels keep readable islands between them.
            final double magnitude = switch (i)
            {
                case 0, 1 -> 0.42 + 0.15 * RiverMouthRandom.hash01(seed, 30 + i);
                default -> 0.80 + 0.15 * RiverMouthRandom.hash01(seed, 30 + i);
            };
            final double side = (i % 2 == 0 ? 1 : -1) * flip;
            slots[i] = side * magnitude;
        }
        return slots;
    }

    private static double[] position(double anchorX, double anchorZ, double inlandX, double inlandZ, double along, double across)
    {
        return new double[] {
            anchorX + inlandX * along - inlandZ * across,
            anchorZ + inlandZ * along + inlandX * across
        };
    }

    /**
     * @return The point at parameter {@code t} in [0, 1] along the two-segment path {@code start -> mid -> end}.
     */
    private static double[] pointOnPath(double[] start, double[] mid, double[] end, double t)
    {
        return t < 0.5
            ? new double[] {Mth.lerp(2 * t, start[0], mid[0]), Mth.lerp(2 * t, start[1], mid[1])}
            : new double[] {Mth.lerp(2 * t - 1, mid[0], end[0]), Mth.lerp(2 * t - 1, mid[1], end[1])};
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
