/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.test.world;

import java.util.Optional;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import net.dries007.tfc.world.biome.TFCBiomes;
import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.region.Units;
import net.dries007.tfc.world.river.River;
import net.dries007.tfc.world.river.mouth.DeltaTier;
import net.dries007.tfc.world.river.mouth.RiverMouthContext;
import net.dries007.tfc.world.river.mouth.RiverMouthResolver;
import net.dries007.tfc.world.river.mouth.RiverMouthSample;

import static org.junit.jupiter.api.Assertions.*;

public class RiverMouthResolverTest
{
    /**
     * Synthetic coast along the x axis: open ocean below grid x = 99.5, a shore band in [99.5, 100.5), and flat
     * land (plains) beyond. The rendered coastline the anchor march should find is the land|shore transition at
     * grid x = 100.5.
     */
    private static final double SHORE_MIN_GRID = 99.5, LAND_MIN_GRID = 100.5;

    private static final RiverMouthResolver.RawBiomeSampler COAST = (quartX, quartZ) -> {
        final double gridX = (double) quartX / Units.GRID_WIDTH_IN_QUART;
        if (gridX >= LAND_MIN_GRID) return TFCBiomes.PLAINS;
        if (gridX >= SHORE_MIN_GRID) return TFCBiomes.SHORE;
        return TFCBiomes.OCEAN;
    };

    @Test
    public void testAnchorFindsRenderedCoastline()
    {
        // Drain inside the shore band; the march heads inland and anchors at the land|shore transition
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, COAST, 1.0);
        final RiverMouthContext context = assertQualifies(resolver, terminalEdge(99.8, 50, 103, 50, 20));

        assertEquals(LAND_MIN_GRID, context.anchorGridX(), 2 * RiverMouthResolver.ANCHOR_STEP_GRID);
        assertEquals(50, context.anchorGridZ(), 1.0e-6);
        assertEquals(DeltaTier.NORMAL, context.tier());
        assertEquals(1, context.inlandUnitX(), 1.0e-9);
        assertEquals(0, context.inlandUnitZ(), 1.0e-9);
    }

    @Test
    public void testAnchorFromStrandedInlandDrain()
    {
        // Drain rendered on land (biome border drifted seaward of the graph drain): march seaward instead
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, COAST, 1.0);
        final RiverMouthContext context = assertQualifies(resolver, terminalEdge(101.5, 50, 104.5, 50, 20));

        assertEquals(LAND_MIN_GRID, context.anchorGridX(), 2 * RiverMouthResolver.ANCHOR_STEP_GRID);
    }

    @Test
    public void testAnchorFromStrandedOffshoreDrain()
    {
        // Drain rendered in open ocean: march inland across the whole shore band
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, COAST, 1.0);
        final RiverMouthContext context = assertQualifies(resolver, terminalEdge(98.9, 50, 102.5, 50, 20));

        assertEquals(LAND_MIN_GRID, context.anchorGridX(), 2 * RiverMouthResolver.ANCHOR_STEP_GRID);
    }

    @Test
    public void testNonTerminalEdgeNeverResolves()
    {
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, COAST, 1.0);
        final RiverEdge edge = terminalEdge(99.8, 50, 103, 50, 20);
        final RiverEdge upstream = terminalEdge(103, 50, 106, 50, 18);
        upstream.linkToDrain(edge);

        assertTrue(resolver.resolve(upstream).isEmpty());
        assertTrue(resolver.resolve(edge).isPresent()); // The true terminal edge still qualifies
    }

    @Test
    public void testNarrowRiverNeverResolves()
    {
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, COAST, 1.0);
        assertTrue(resolver.resolve(terminalEdge(99.8, 50, 103, 50, DeltaTier.DELTA_MIN_WIDTH - 1)).isEmpty());
    }

    @Test
    public void testDeepOceanAheadNeverResolves()
    {
        final RiverMouthResolver.RawBiomeSampler deepCoast = (quartX, quartZ) -> {
            final double gridX = (double) quartX / Units.GRID_WIDTH_IN_QUART;
            if (gridX >= LAND_MIN_GRID) return TFCBiomes.PLAINS;
            if (gridX >= SHORE_MIN_GRID) return TFCBiomes.SHORE;
            return TFCBiomes.DEEP_OCEAN;
        };
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, deepCoast, 1.0);
        assertTrue(resolver.resolve(terminalEdge(99.8, 50, 103, 50, 20)).isEmpty());
    }

    @Test
    public void testMountainsBehindNeverResolve()
    {
        final RiverMouthResolver.RawBiomeSampler mountainCoast = (quartX, quartZ) -> {
            final double gridX = (double) quartX / Units.GRID_WIDTH_IN_QUART;
            if (gridX >= LAND_MIN_GRID) return TFCBiomes.MOUNTAINS;
            if (gridX >= SHORE_MIN_GRID) return TFCBiomes.SHORE;
            return TFCBiomes.OCEAN;
        };
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, mountainCoast, 1.0);
        assertTrue(resolver.resolve(terminalEdge(99.8, 50, 103, 50, 20)).isEmpty());
    }

    @Test
    public void testDeterminismAcrossResolverInstances()
    {
        final RiverEdge edge = terminalEdge(99.8, 50, 103, 50, 20);
        final RiverMouthResolver first = new RiverMouthResolver(777L, COAST, 1.0);
        final RiverMouthResolver second = new RiverMouthResolver(777L, COAST, 1.0);

        final RiverMouthContext firstContext = assertQualifies(first, edge);
        final RiverMouthContext secondContext = assertQualifies(second, edge);
        assertEquals(firstContext, secondContext);

        // Repeated resolution through the cache is identical to the first
        assertEquals(Optional.of(firstContext), first.resolve(edge).map(RiverMouthResolver.ResolvedMouth::context));
    }

    @Test
    public void testShapeSeedFollowsLevelSeed()
    {
        final RiverEdge edge = terminalEdge(99.8, 50, 103, 50, 20);
        final RiverMouthContext first = assertQualifies(new RiverMouthResolver(1L, COAST, 1.0), edge);
        final RiverMouthContext second = assertQualifies(new RiverMouthResolver(2L, COAST, 1.0), edge);

        assertNotEquals(first.shapeSeed(), second.shapeSeed());
    }

    @Test
    public void testEquivalentEdgeInstancesResolveIdentically()
    {
        // The cache is weak-keyed on edge instances; a regenerated (equal-coordinate) edge must produce an
        // identical context, so cache eviction can never affect worldgen
        final RiverMouthResolver resolver = new RiverMouthResolver(555L, COAST, 1.0);
        final RiverMouthContext first = assertQualifies(resolver, terminalEdge(99.8, 50, 103, 50, 20));
        final RiverMouthContext second = assertQualifies(resolver, terminalEdge(99.8, 50, 103, 50, 20));

        assertEquals(first.shapeSeed(), second.shapeSeed());
        assertEquals(first.anchorGridX(), second.anchorGridX());
        assertEquals(first.anchorGridZ(), second.anchorGridZ());
        assertEquals(first.inlandReachGrid(), second.inlandReachGrid());
        assertEquals(first.seawardReachGrid(), second.seawardReachGrid());
        assertEquals(first.fanHalfWidthGrid(), second.fanHalfWidthGrid());
    }

    @Test
    public void testDimensionsWithinTierRanges()
    {
        for (long seed = 0; seed < 50; seed++)
        {
            final RiverMouthContext context = assertQualifies(new RiverMouthResolver(seed, COAST, 1.0), terminalEdge(99.8, 50, 103, 50, 24));
            assertEquals(DeltaTier.MAJOR, context.tier());
            assertTrue(context.inlandReachGrid() >= 1.00 && context.inlandReachGrid() <= 1.25, "inland " + context.inlandReachGrid());
            assertTrue(context.fanHalfWidthGrid() >= 1.10 && context.fanHalfWidthGrid() <= 1.75, "halfWidth " + context.fanHalfWidthGrid());
            assertTrue(context.seawardReachGrid() >= 0.55 && context.seawardReachGrid() <= 0.95, "seaward " + context.seawardReachGrid());
        }
    }

    @Test
    public void testSampleInsideFan()
    {
        final RiverMouthResolver resolver = new RiverMouthResolver(12345L, COAST, 1.0);
        final RiverEdge edge = terminalEdge(99.8, 50, 103, 50, 20);
        final RiverMouthContext context = assertQualifies(resolver, edge);

        final RiverMouthSample sample = resolver.resolve(edge).orElseThrow().geometry().sample(context.anchorGridX(), context.anchorGridZ());
        assertNotNull(sample);
        assertEquals(1, sample.terrainWeight(), 1.0e-9);
        // The diagnostic trunk channel passes near the anchor
        assertNotNull(sample.channel());
        assertEquals(0, sample.channel().channel().branchDepth());
    }

    private RiverMouthContext assertQualifies(RiverMouthResolver resolver, RiverEdge edge)
    {
        final Optional<RiverMouthResolver.ResolvedMouth> mouth = resolver.resolve(edge);
        assertTrue(mouth.isPresent(), "Expected the edge to qualify as a delta");
        return mouth.orElseThrow().context();
    }

    private RiverEdge terminalEdge(double drainX, double drainZ, double sourceX, double sourceZ, int width)
    {
        final RiverEdge edge = new RiverEdge(new River.Edge(
            new River.Vertex(sourceX, sourceZ, 0, 0, 1),
            new River.Vertex(drainX, drainZ, 0, 0, 0)), new XoroshiroRandomSource(42));
        edge.width = width;
        return edge;
    }
}
