/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.test.world;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.TFCBiomes;
import net.dries007.tfc.world.river.mouth.DeltaTier;
import net.dries007.tfc.world.river.mouth.RiverMouthClassificationInput;
import net.dries007.tfc.world.river.mouth.RiverMouthClassifier;

import static org.junit.jupiter.api.Assertions.*;

public class RiverMouthClassifierTest
{
    @Test
    public void testQualifyingBaseline()
    {
        assertEquals(Optional.of(DeltaTier.NORMAL), classify(baseline()));
    }

    @Test
    public void testNonTerminalRejected()
    {
        assertTrue(classify(new Input(false, 18, TFCBiomes.SHORE, List.of(TFCBiomes.PLAINS), List.of(TFCBiomes.OCEAN), List.of(TFCBiomes.OCEAN))).isEmpty());
    }

    @Test
    public void testWidthThresholdsSelectTier()
    {
        assertTrue(classify(baseline().width(DeltaTier.DELTA_MIN_WIDTH - 1)).isEmpty());
        assertEquals(Optional.of(DeltaTier.COMPACT), classify(baseline().width(14)));
        assertEquals(Optional.of(DeltaTier.COMPACT), classify(baseline().width(16)));
        assertEquals(Optional.of(DeltaTier.NORMAL), classify(baseline().width(17)));
        assertEquals(Optional.of(DeltaTier.NORMAL), classify(baseline().width(21)));
        assertEquals(Optional.of(DeltaTier.MAJOR), classify(baseline().width(22)));
        assertEquals(Optional.of(DeltaTier.MAJOR), classify(baseline().width(24)));
    }

    @Test
    public void testEligibleShores()
    {
        assertTrue(classify(baseline().shore(TFCBiomes.SHORE)).isPresent());
        assertTrue(classify(baseline().shore(TFCBiomes.TIDAL_FLATS)).isPresent());
    }

    @Test
    public void testEmbaymentsRejected()
    {
        // Locked decision: embayments are reserved as prime estuary terrain
        assertTrue(classify(baseline().shore(TFCBiomes.EMBAYMENTS)).isEmpty());
        assertTrue(classify(baseline().shore(TFCBiomes.EMBAYMENTS).width(14)).isEmpty());
    }

    @Test
    public void testIneligibleShoresRejected()
    {
        for (BiomeExtension shore : List.of(
            TFCBiomes.ROCKY_SHORES, TFCBiomes.SEA_STACKS, TFCBiomes.SETBACK_CLIFFS,
            TFCBiomes.TERRACE_UPPER, TFCBiomes.TERRACE_LOWER,
            TFCBiomes.SHIELD_VOLCANO_SHORE, TFCBiomes.OLD_SHIELD_VOLCANO_SHORE,
            TFCBiomes.ICE_SHEET_SHORE, TFCBiomes.PLAINS, TFCBiomes.OCEAN))
        {
            assertTrue(classify(baseline().shore(shore)).isEmpty(), "Should reject shore: " + shore.key());
        }
    }

    @Test
    public void testCoastalDunesCompactTierOnly()
    {
        assertEquals(Optional.of(DeltaTier.COMPACT), classify(baseline().shore(TFCBiomes.COASTAL_DUNES).width(15)));
        assertTrue(classify(baseline().shore(TFCBiomes.COASTAL_DUNES).width(18)).isEmpty());
        assertTrue(classify(baseline().shore(TFCBiomes.COASTAL_DUNES).width(24)).isEmpty());
    }

    @Test
    public void testInlandFamily()
    {
        assertTrue(classify(baseline().inland(List.of(TFCBiomes.LOWLANDS, TFCBiomes.PLAINS, TFCBiomes.SALT_MARSH))).isPresent());

        // Any non-flat stable land behind the anchor rejects
        assertTrue(classify(baseline().inland(List.of(TFCBiomes.PLAINS, TFCBiomes.MOUNTAINS))).isEmpty());
        assertTrue(classify(baseline().inland(List.of(TFCBiomes.ROLLING_HILLS))).isEmpty());
        assertTrue(classify(baseline().inland(List.of(TFCBiomes.PLATEAU))).isEmpty());

        // Shore and water samples inland are ignored, but at least one stable flat land sample must exist
        assertTrue(classify(baseline().inland(List.of(TFCBiomes.SHORE, TFCBiomes.PLAINS))).isPresent());
        assertTrue(classify(baseline().inland(List.of(TFCBiomes.SHORE, TFCBiomes.OCEAN))).isEmpty());
    }

    @Test
    public void testOceanAhead()
    {
        assertTrue(classify(baseline().ahead(List.of(TFCBiomes.OCEAN, TFCBiomes.OCEAN_REEF))).isPresent());

        // Deep ocean ahead rejects, even alongside shallow
        assertTrue(classify(baseline().ahead(List.of(TFCBiomes.OCEAN, TFCBiomes.DEEP_OCEAN))).isEmpty());
        assertTrue(classify(baseline().ahead(List.of(TFCBiomes.DEEP_OCEAN))).isEmpty());
        assertTrue(classify(baseline().ahead(List.of(TFCBiomes.DEEP_OCEAN_TRENCH))).isEmpty());

        // No shallow ocean ahead (landlocked or all-shore front) rejects
        assertTrue(classify(baseline().ahead(List.of(TFCBiomes.SHORE, TFCBiomes.PLAINS))).isEmpty());
    }

    @Test
    public void testDeepOceanInConstructionAreaRejects()
    {
        assertTrue(classify(baseline().construction(List.of(TFCBiomes.OCEAN, TFCBiomes.DEEP_OCEAN))).isEmpty());
        assertTrue(classify(baseline().construction(List.of(TFCBiomes.OCEAN, TFCBiomes.OCEAN_RIDGE))).isEmpty());
        assertTrue(classify(baseline().construction(List.of(TFCBiomes.OCEAN, TFCBiomes.OCEANIC_VOLCANIC_ARC))).isEmpty());
        assertTrue(classify(baseline().construction(List.of(TFCBiomes.OCEAN, TFCBiomes.SHORE, TFCBiomes.PLAINS))).isPresent());
    }

    private Optional<DeltaTier> classify(Input input)
    {
        return RiverMouthClassifier.classifyDelta(new RiverMouthClassificationInput(
            input.terminal, input.width, input.shore, input.inland, input.ahead, input.construction));
    }

    private Input baseline()
    {
        return new Input(true, 18, TFCBiomes.SHORE,
            List.of(TFCBiomes.PLAINS, TFCBiomes.LOWLANDS),
            List.of(TFCBiomes.OCEAN),
            List.of(TFCBiomes.OCEAN, TFCBiomes.SHORE));
    }

    private record Input(boolean terminal, int width, BiomeExtension shore, List<BiomeExtension> inland, List<BiomeExtension> ahead, List<BiomeExtension> construction)
    {
        Input width(int width) { return new Input(terminal, width, shore, inland, ahead, construction); }
        Input shore(BiomeExtension shore) { return new Input(terminal, width, shore, inland, ahead, construction); }
        Input inland(List<BiomeExtension> inland) { return new Input(terminal, width, shore, inland, ahead, construction); }
        Input ahead(List<BiomeExtension> ahead) { return new Input(terminal, width, shore, inland, ahead, construction); }
        Input construction(List<BiomeExtension> construction) { return new Input(terminal, width, shore, inland, ahead, construction); }
    }
}
