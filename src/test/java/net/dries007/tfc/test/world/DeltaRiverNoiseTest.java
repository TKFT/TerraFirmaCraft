/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.test.world;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import net.dries007.tfc.world.Seed;
import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.river.Flow;
import net.dries007.tfc.world.river.River;
import net.dries007.tfc.world.river.RiverInfo;
import net.dries007.tfc.world.river.RiverNoise;
import net.dries007.tfc.world.river.RiverNoiseSampler;

import static net.dries007.tfc.world.TFCChunkGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure sampler-level tests for {@link RiverNoise#delta} (DELTA_NOISE_SAMPLER.md §6) — no worldgen.
 */
public class DeltaRiverNoiseTest
{
    private static final double MAX_WIDTH_SQ = RiverEdge.MAX_WIDTH * RiverEdge.MAX_WIDTH;
    private static final double MID_WIDTH_SQ = 18 * 18;

    @Test
    public void testRegimeContinuity()
    {
        // Sweep normDistSq 0 -> 4 at fixed (x, z): height is continuous, no jumps > 1 block between steps
        final RiverNoiseSampler sampler = RiverNoise.delta(Seed.of(12345L));
        double previous = Double.NaN;
        for (double norm = 0; norm <= 4; norm += 0.005)
        {
            final double height = sampler.setColumnAndSampleHeight(info(norm, MID_WIDTH_SQ), 1000, 2000, 70, 1);
            if (!Double.isNaN(previous))
            {
                assertTrue(Math.abs(height - previous) < 1, "Jump of " + Math.abs(height - previous) + " at normDistSq " + norm);
            }
            previous = height;
        }
    }

    @Test
    public void testChannelFloorDepth()
    {
        // Channel floor <= SEA - 4 for max-width channels at the center, at any (x, z)
        final RiverNoiseSampler sampler = RiverNoise.delta(Seed.of(12345L));
        for (int i = 0; i < 20; i++)
        {
            final double height = sampler.setColumnAndSampleHeight(info(0, MAX_WIDTH_SQ), i * 137, i * 89, 70, 1);
            assertTrue(height <= SEA_LEVEL_Y - 4, "Channel floor too shallow: " + height);
        }
    }

    @Test
    public void testLeveeCrestAbovePlain()
    {
        // At the same (x, z), the levee peak (distFac = 1.25) rises above the surrounding wet-flat plain
        // (distFac = 3.0). Raised islands can locally exceed the levee, so probe deterministically for a
        // wet-flat position (plain below sea + 0.5). The dist noise offset is recovered by inverting the
        // channel-bed formula at normDistSq = 0.
        final RiverNoiseSampler sampler = RiverNoise.delta(Seed.of(12345L));
        final double depth = 6; // Max width depth, per the sampler's clampedMap

        boolean tested = false;
        for (int i = 0; i < 100 && !tested; i++)
        {
            final int x = 500 + i * 61, z = -900 + i * 47;

            final double bedHeight = sampler.setColumnAndSampleHeight(info(0, MAX_WIDTH_SQ), x, z, 70, 1);
            final double noiseOffset = (bedHeight - (SEA_LEVEL_Y - 1.5 - depth)) / depth; // distFac at normDistSq = 0

            final double plainHeight = sampler.setColumnAndSampleHeight(info((3.0 - noiseOffset) / 0.8, MAX_WIDTH_SQ), x, z, 70, 1);
            if (plainHeight > SEA_LEVEL_Y + 0.5)
            {
                continue; // A raised island - not the landform this test is about
            }

            final double leveeHeight = sampler.setColumnAndSampleHeight(info((1.25 - noiseOffset) / 0.8, MAX_WIDTH_SQ), x, z, 70, 1);
            assertTrue(leveeHeight > plainHeight, "Levee crest " + leveeHeight + " not above plain " + plainHeight + " at (" + x + ", " + z + ")");
            assertTrue(leveeHeight > SEA_LEVEL_Y, "Levee crest " + leveeHeight + " not above sea level");
            tested = true;
        }
        assertTrue(tested, "Never found a wet-flat position to test against");
    }

    @Test
    public void testWeightBlending()
    {
        final RiverNoiseSampler sampler = RiverNoise.delta(Seed.of(12345L));

        // thisWeight = 0 returns heightIn exactly
        assertEquals(87.5, sampler.setColumnAndSampleHeight(info(1.5, MID_WIDTH_SQ), 10, 20, 87.5, 0));

        // thisWeight = 1 is the pure delta profile, independent of heightIn (above the deep-water cap)
        final double first = sampler.setColumnAndSampleHeight(info(1.5, MID_WIDTH_SQ), 10, 20, 70, 1);
        final double second = sampler.setColumnAndSampleHeight(info(1.5, MID_WIDTH_SQ), 10, 20, 90, 1);
        assertEquals(first, second);
    }

    @Test
    public void testDeepWaterCap()
    {
        // A delta may shoal deep water but never build land into it: heightIn = SEA - 30 caps at heightIn + 8
        final RiverNoiseSampler sampler = RiverNoise.delta(Seed.of(12345L));
        for (double norm = 0; norm <= 4; norm += 0.25)
        {
            final double height = sampler.setColumnAndSampleHeight(info(norm, MID_WIDTH_SQ), 300, 400, SEA_LEVEL_Y - 30, 1);
            assertTrue(height <= SEA_LEVEL_Y - 22, "Deep-water cap breached: " + height);
        }
    }

    @Test
    public void testDeterminism()
    {
        // Two samplers from equal seeds produce identical output everywhere
        final RiverNoiseSampler first = RiverNoise.delta(Seed.of(9876L));
        final RiverNoiseSampler second = RiverNoise.delta(Seed.of(9876L));
        for (int i = 0; i < 200; i++)
        {
            final double norm = (i % 17) * 0.25;
            final int x = i * 31, z = i * -57;
            assertEquals(
                first.setColumnAndSampleHeight(info(norm, MID_WIDTH_SQ), x, z, 70, 1),
                second.setColumnAndSampleHeight(info(norm, MID_WIDTH_SQ), x, z, 70, 1));
            for (int y = 50; y < 70; y++)
            {
                assertEquals(first.noise(y, 0.5), second.noise(y, 0.5));
            }
        }
    }

    private RiverInfo info(double normDistSq, double widthSq)
    {
        // The doc sampler reads only normDistSq() and widthSq(); the edge is a placeholder
        final RiverEdge edge = new RiverEdge(new River.Edge(
            new River.Vertex(10, 0, 0, 0, 1),
            new River.Vertex(0, 0, 0, 0, 0)), new XoroshiroRandomSource(42));
        return new RiverInfo(edge, Flow.NONE, normDistSq * widthSq, widthSq);
    }
}
