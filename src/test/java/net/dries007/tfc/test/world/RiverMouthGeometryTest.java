/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.test.world;

import java.util.List;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.river.River;
import net.dries007.tfc.world.river.mouth.DeltaTier;
import net.dries007.tfc.world.river.mouth.RiverMouthContext;
import net.dries007.tfc.world.river.mouth.RiverMouthGeometry;
import net.dries007.tfc.world.river.mouth.RiverMouthSample;

import static org.junit.jupiter.api.Assertions.*;

public class RiverMouthGeometryTest
{
    private static final double ANCHOR_X = 100, ANCHOR_Z = 50;
    private static final double INLAND_REACH = 0.9, SEAWARD_REACH = 0.5, FAN_HALF_WIDTH = 0.9;

    @Test
    public void testProjectionAlongInlandAxis()
    {
        final RiverMouthGeometry geometry = geometry(12345L);
        final RiverMouthSample sample = geometry.sample(ANCHOR_X + 0.4, ANCHOR_Z);

        assertNotNull(sample);
        assertEquals(0.4, sample.alongGrid(), 1.0e-9);
        assertEquals(0, sample.acrossGrid(), 1.0e-9);
        assertEquals(0.4 / INLAND_REACH, sample.normalizedAlong(), 1.0e-9);
    }

    @Test
    public void testProjectionAcross()
    {
        final RiverMouthGeometry geometry = geometry(12345L);
        final RiverMouthSample sample = geometry.sample(ANCHOR_X - 0.2, ANCHOR_Z + 0.3);

        assertNotNull(sample);
        assertEquals(-0.2, sample.alongGrid(), 1.0e-9);
        assertEquals(0.3, sample.acrossGrid(), 1.0e-9);
        assertEquals(-0.2 / SEAWARD_REACH, sample.normalizedAlong(), 1.0e-9);
    }

    @Test
    public void testDeepInsideFanHasFullWeight()
    {
        final RiverMouthGeometry geometry = geometry(12345L);
        final RiverMouthSample sample = geometry.sample(ANCHOR_X, ANCHOR_Z);

        assertNotNull(sample);
        assertTrue(sample.inFan());
        assertEquals(1, sample.terrainWeight(), 1.0e-9);
    }

    @Test
    public void testFarOutsideFanIsNull()
    {
        final RiverMouthGeometry geometry = geometry(12345L);

        assertNull(geometry.sample(ANCHOR_X + INLAND_REACH + 0.5, ANCHOR_Z)); // Far inland
        assertNull(geometry.sample(ANCHOR_X - SEAWARD_REACH - 0.5, ANCHOR_Z)); // Far out to sea
        assertNull(geometry.sample(ANCHOR_X, ANCHOR_Z + FAN_HALF_WIDTH + 0.5)); // Far lateral
        assertNull(geometry.sample(ANCHOR_X + 5, ANCHOR_Z + 5)); // Far everything
    }

    @Test
    public void testFanPinchesInland()
    {
        final RiverMouthGeometry geometry = geometry(12345L);

        // At the coastline the wedge is at full width; near the inland tip it pinches toward valley width
        assertEquals(FAN_HALF_WIDTH, geometry.halfWidthAt(0), 1.0e-9);
        assertTrue(geometry.halfWidthAt(INLAND_REACH) < 0.35 * FAN_HALF_WIDTH);
        assertEquals(FAN_HALF_WIDTH, geometry.halfWidthAt(-SEAWARD_REACH), 1.0e-9); // Full width seaward
    }

    @Test
    public void testBoundaryIsNeverASmoothArc()
    {
        final RiverMouthGeometry geometry = geometry(12345L);

        // The sector noise must actually modulate the boundary: across many angles, offsets differ
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < 64; i++)
        {
            final double noise = geometry.sectorNoise(-Math.PI + 2 * Math.PI * i / 64);
            min = Math.min(min, noise);
            max = Math.max(max, noise);
        }
        assertTrue(max - min > 0.02 , "Sector noise too flat: [" + min + ", " + max + "]");
        assertTrue(max <= RiverMouthGeometry.BOUNDARY_NOISE_GRID + 1.0e-9);
        assertTrue(min >= -RiverMouthGeometry.BOUNDARY_NOISE_GRID - 1.0e-9);
    }

    @Test
    public void testDeterminism()
    {
        // Two geometries built from independently constructed (but value-identical) contexts must sample
        // identically - RiverEdge has identity equality, so compare sample values, not records
        final RiverMouthGeometry first = geometry(999L);
        final RiverMouthGeometry second = geometry(999L);

        for (double x = -1.5; x <= 1.5; x += 0.05)
        {
            for (double z = -1.5; z <= 1.5; z += 0.05)
            {
                final RiverMouthSample firstSample = first.sample(ANCHOR_X + x, ANCHOR_Z + z);
                final RiverMouthSample secondSample = second.sample(ANCHOR_X + x, ANCHOR_Z + z);
                if (firstSample == null || secondSample == null)
                {
                    assertEquals(firstSample, secondSample);
                    continue;
                }
                assertEquals(firstSample.alongGrid(), secondSample.alongGrid());
                assertEquals(firstSample.acrossGrid(), secondSample.acrossGrid());
                assertEquals(firstSample.normalizedAlong(), secondSample.normalizedAlong());
                assertEquals(firstSample.normalizedAcross(), secondSample.normalizedAcross());
                assertEquals(firstSample.terrainWeight(), secondSample.terrainWeight());
                assertEquals(firstSample.inFan(), secondSample.inFan());
            }
        }
    }

    @Test
    public void testDifferentSeedsDifferentBoundaries()
    {
        final RiverMouthGeometry first = geometry(1L);
        final RiverMouthGeometry second = geometry(2L);

        boolean anyDifferent = false;
        for (int i = 0; i < 32 && !anyDifferent; i++)
        {
            final double angle = -Math.PI + 2 * Math.PI * i / 32;
            anyDifferent = Math.abs(first.sectorNoise(angle) - second.sectorNoise(angle)) > 1.0e-6;
        }
        assertTrue(anyDifferent);
    }

    private RiverMouthGeometry geometry(long shapeSeed)
    {
        return new RiverMouthGeometry(context(shapeSeed));
    }

    private RiverMouthContext context(long shapeSeed)
    {
        // Inland axis is +x; the fan opens toward -x
        final RiverEdge edge = new RiverEdge(new River.Edge(
            new River.Vertex(ANCHOR_X + 2.5, ANCHOR_Z, 0, 0, 1),
            new River.Vertex(ANCHOR_X - 0.1, ANCHOR_Z, 0, 0, 0)), new XoroshiroRandomSource(42));
        edge.width = 20;
        return new RiverMouthContext(edge, DeltaTier.NORMAL, ANCHOR_X, ANCHOR_Z, 1, 0, 0.75,
            INLAND_REACH, SEAWARD_REACH, FAN_HALF_WIDTH, 1.0, shapeSeed, List.of());
    }
}
