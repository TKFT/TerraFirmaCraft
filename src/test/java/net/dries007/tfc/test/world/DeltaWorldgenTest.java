/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.test.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import net.dries007.tfc.data.providers.BuiltinWorldPreset;
import net.dries007.tfc.test.TestSetup;
import net.dries007.tfc.world.BiomeNoiseSampler;
import net.dries007.tfc.world.ChunkBiomeSampler;
import net.dries007.tfc.world.ChunkHeightFiller;
import net.dries007.tfc.world.Seed;
import net.dries007.tfc.world.TFCChunkGenerator;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeNoise;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.biome.TFCBiomes;
import net.dries007.tfc.world.layer.TFCLayers;
import net.dries007.tfc.world.layer.framework.ConcurrentArea;
import net.dries007.tfc.world.noise.Noise2D;
import net.dries007.tfc.world.region.RegionGenerator;
import net.dries007.tfc.world.region.RegionPartition;
import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.region.Units;
import net.dries007.tfc.world.river.Flow;
import net.dries007.tfc.world.river.RiverBlendType;
import net.dries007.tfc.world.river.RiverNoiseSampler;
import net.dries007.tfc.world.river.mouth.RiverMouthBiomes;
import net.dries007.tfc.world.river.mouth.RiverMouthChannel;
import net.dries007.tfc.world.river.mouth.RiverMouthChannelSegment;
import net.dries007.tfc.world.river.mouth.RiverMouthContext;
import net.dries007.tfc.world.river.mouth.RiverMouthResolver;
import net.dries007.tfc.world.river.mouth.RiverMouthSample;
import net.dries007.tfc.world.settings.Settings;
import net.dries007.tfc.world.shore.ShoreBlendType;
import net.dries007.tfc.world.shore.ShoreNoiseSampler;
import net.dries007.tfc.world.volcano.CenteredFeatureBlendType;
import net.dries007.tfc.world.volcano.CenteredFeatureNoiseSampler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless worldgen gates for the terminal-local delta (Phases 2 + 3), running the real height pipeline through
 * two {@link ChunkHeightFiller} stacks that differ only in whether the biome source exposes a
 * {@link RiverMouthResolver}:
 * <ul>
 *     <li>Phase 2: a qualifying mouth exists on a fixed seed, terrain changes inside its fan mask, and every
 *     column outside the mask — the rest of the wide shore biome and all nonqualifying rivers included — is
 *     bit-identical.</li>
 *     <li>Phase 3: the diagnostic trunk channel reaches the sea, the channel floor is carved and submerged along
 *     its whole in-fan length (the headless stand-in for the boat test), the river biome overlay follows the
 *     channel through {@code .noRivers()} shore columns without leaking sideways, and anchors sit on the
 *     rendered coastline.</li>
 * </ul>
 */
public class DeltaWorldgenTest implements TestSetup
{
    /** Fixed seeds to scan, in order, until one produces a qualifying delta. Deterministic - no random retries. */
    private static final long[] CANDIDATE_SEEDS = {1000L, 2000L, 3000L, 4000L, 5000L};
    /** Grid radius around the origin to scan for terminal edges. */
    private static final int SCAN_RADIUS_GRID = 300;

    private static @Nullable Fixture fixture;

    @Test
    public void testDeltaTerrainChangeAndLocality()
    {
        final Fixture fixture = fixture();
        final RiverMouthContext context = fixture.mouths.get(0).context();

        final int anchorBlockX = (int) Math.round(context.anchorGridX() * Units.GRID_WIDTH_IN_BLOCK);
        final int anchorBlockZ = (int) Math.round(context.anchorGridZ() * Units.GRID_WIDTH_IN_BLOCK);
        final int radiusBlocks = 224, step = 4;

        double maxInFanDifference = 0;
        int inFanColumns = 0, outsideColumns = 0;

        final int minChunkX = (anchorBlockX - radiusBlocks) >> 4, maxChunkX = (anchorBlockX + radiusBlocks) >> 4;
        final int minChunkZ = (anchorBlockZ - radiusBlocks) >> 4, maxChunkZ = (anchorBlockZ + radiusBlocks) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
        {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
            {
                final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                final ChunkHeightFiller onFiller = fixture.stack.createHeightFiller(pos, true);
                final ChunkHeightFiller offFiller = fixture.stack.createHeightFiller(pos, false);

                for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x += step)
                {
                    for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z += step)
                    {
                        final double on = onFiller.sampleHeight(x, z);
                        final double off = offFiller.sampleHeight(x, z);

                        final RiverMouthSample sample = fixture.stack.sampleMouth(x, z);
                        if (sample == null)
                        {
                            // Outside every fan mask - including the rest of the wide shore biome hosting this
                            // mouth, and every other (qualifying or not) river - heights are bit-identical
                            assertEquals(off, on, "Height changed outside the fan mask at (" + x + ", " + z + ")");
                            outsideColumns++;
                        }
                        else
                        {
                            inFanColumns++;
                            maxInFanDifference = Math.max(maxInFanDifference, Math.abs(on - off));
                        }
                    }
                }
            }
        }

        assertTrue(inFanColumns > 50, "Fan mask covered too few sampled columns: " + inFanColumns);
        assertTrue(outsideColumns > 500, "Too few outside columns sampled for the locality check: " + outsideColumns);
        assertTrue(maxInFanDifference >= 2, "Delta did not visibly change terrain inside the fan (max difference " + maxInFanDifference + ")");
    }

    @Test
    public void testTrunkChannelStructure()
    {
        for (RiverMouthResolver.ResolvedMouth mouth : fixture().mouths)
        {
            final RiverMouthContext context = mouth.context();
            assertEquals(1, context.channels().size(), "Diagnostic phase: the network is exactly the trunk");

            final RiverMouthChannel trunk = context.channels().get(0);
            assertEquals(0, trunk.branchDepth());
            assertFalse(trunk.segments().isEmpty());

            // The channel's seaward endpoint reaches past the ocean-facing fan boundary
            final RiverMouthChannelSegment last = trunk.segments().get(trunk.segments().size() - 1);
            final double alongEnd = (last.endGridX() - context.anchorGridX()) * context.inlandUnitX()
                + (last.endGridZ() - context.anchorGridZ()) * context.inlandUnitZ();
            assertTrue(alongEnd <= -context.seawardReachGrid() * context.worldgenScale(),
                "Trunk ends at along " + alongEnd + ", before the seaward boundary " + -context.seawardReachGrid());

            // Segments are connected, upstream to seaward, and every segment has downstream flow
            for (int i = 0; i < trunk.segments().size(); i++)
            {
                final RiverMouthChannelSegment segment = trunk.segments().get(i);
                assertNotEquals(Flow.NONE, segment.flow());
                if (i > 0)
                {
                    final RiverMouthChannelSegment previous = trunk.segments().get(i - 1);
                    assertEquals(previous.endGridX(), segment.startGridX(), 1.0e-9);
                    assertEquals(previous.endGridZ(), segment.startGridZ(), 1.0e-9);
                }
            }
        }
    }

    @Test
    public void testTrunkCarvesContinuouslyToSea()
    {
        // The headless boat test: walk the trunk centerline through the fan; wherever the fan mask is strong, the
        // channel floor must be carved well below sea level, all the way through the .noRivers() shore into the sea
        final Fixture fixture = fixture();
        final RiverMouthResolver.ResolvedMouth mouth = fixture.mouths.get(0);
        final Map<ChunkPos, ChunkHeightFiller> fillers = new HashMap<>();

        int carvedPoints = 0;
        for (RiverMouthChannelSegment segment : mouth.context().channels().get(0).segments())
        {
            final double lengthGrid = Math.sqrt(RiverMouthResolverTestSupport.distSq(segment.startGridX(), segment.startGridZ(), segment.endGridX(), segment.endGridZ()));
            final int steps = Math.max(1, (int) (lengthGrid * Units.GRID_WIDTH_IN_BLOCK / 4));
            for (int i = 0; i <= steps; i++)
            {
                final double gridX = segment.startGridX() + (segment.endGridX() - segment.startGridX()) * i / steps;
                final double gridZ = segment.startGridZ() + (segment.endGridZ() - segment.startGridZ()) * i / steps;
                final RiverMouthSample sample = mouth.geometry().sample(gridX, gridZ);
                if (sample == null || sample.terrainWeight() < 0.9)
                {
                    continue; // Outside or on the feathered fan edge - the ordinary river carve owns these columns
                }

                final int blockX = (int) Math.round(gridX * Units.GRID_WIDTH_IN_BLOCK);
                final int blockZ = (int) Math.round(gridZ * Units.GRID_WIDTH_IN_BLOCK);
                final ChunkHeightFiller filler = fillers.computeIfAbsent(new ChunkPos(blockX >> 4, blockZ >> 4), pos -> fixture.stack.createHeightFiller(pos, true));
                final double height = filler.sampleHeight(blockX, blockZ);

                assertTrue(height <= TFCChunkGenerator.SEA_LEVEL_Y - 2,
                    "Trunk centerline not carved at (" + blockX + ", " + blockZ + "): height " + height);
                carvedPoints++;
            }
        }
        assertTrue(carvedPoints > 20, "Too few centerline points inside the fan: " + carvedPoints);
    }

    @Test
    public void testRiverBiomeOverlayFollowsChannelThroughShore()
    {
        // The biome overlay must continue through .noRivers() shore/ocean columns along the channel, and must NOT
        // leak sideways across the shore band
        final Fixture fixture = fixture();
        final RiverMouthResolver.ResolvedMouth mouth = fixture.mouths.get(0);
        final RiverMouthContext context = mouth.context();
        final BiomeSourceExtension source = fixture.stack.biomeSource(true);

        int overlaidShoreQuarts = 0;
        for (RiverMouthChannelSegment segment : context.channels().get(0).segments())
        {
            final double lengthGrid = Math.sqrt(RiverMouthResolverTestSupport.distSq(segment.startGridX(), segment.startGridZ(), segment.endGridX(), segment.endGridZ()));
            final int steps = Math.max(1, (int) (lengthGrid * Units.GRID_WIDTH_IN_QUART));
            for (int i = 0; i <= steps; i++)
            {
                final double gridX = segment.startGridX() + (segment.endGridX() - segment.startGridX()) * i / steps;
                final double gridZ = segment.startGridZ() + (segment.endGridZ() - segment.startGridZ()) * i / steps;
                final RiverMouthSample sample = mouth.geometry().sample(gridX, gridZ);
                if (sample == null || sample.terrainWeight() < 0.9)
                {
                    continue;
                }

                final int quartX = (int) Math.round(gridX * Units.GRID_WIDTH_IN_QUART);
                final int quartZ = (int) Math.round(gridZ * Units.GRID_WIDTH_IN_QUART);
                final BiomeExtension raw = source.getBiomeExtensionNoRiver(quartX, quartZ);
                if (!raw.hasRivers())
                {
                    // On the channel centerline in a .noRivers() biome: the overlay applies
                    assertEquals(TFCBiomes.RIVER, source.getBiomeExtension(quartX, quartZ),
                        "No river overlay on the channel at quart (" + quartX + ", " + quartZ + ") in " + raw.key());
                    overlaidShoreQuarts++;

                    // Laterally offset from the channel (well beyond its width), still in the same shore band:
                    // the overlay must NOT apply from this mouth's channel
                    final double lateral = 2.5 * context.channels().get(0).startWidthGrid() * context.worldgenScale();
                    final int offQuartX = (int) Math.round((gridX - context.inlandUnitZ() * lateral) * Units.GRID_WIDTH_IN_QUART);
                    final int offQuartZ = (int) Math.round((gridZ + context.inlandUnitX() * lateral) * Units.GRID_WIDTH_IN_QUART);
                    final BiomeExtension offRaw = source.getBiomeExtensionNoRiver(offQuartX, offQuartZ);
                    if (!offRaw.hasRivers())
                    {
                        assertNotEquals(TFCBiomes.RIVER, source.getBiomeExtension(offQuartX, offQuartZ),
                            "River overlay leaked sideways at quart (" + offQuartX + ", " + offQuartZ + ")");
                    }
                }
            }
        }
        assertTrue(overlaidShoreQuarts > 3, "Channel never crossed a .noRivers() biome: " + overlaidShoreQuarts);
    }

    @Test
    public void testAnchorsSitOnRenderedCoastline()
    {
        final Fixture fixture = fixture();
        for (RiverMouthResolver.ResolvedMouth mouth : fixture.mouths)
        {
            final RiverMouthContext context = mouth.context();
            // The anchor march straddles a transition at half-step (0.0625 grid) offsets: land inland of the
            // anchor, shore/ocean seaward. Also probe full steps, tolerant of jagged rendered borders.
            final boolean landInland = isAnchorLand(fixture, context, 0.0625) || isAnchorLand(fixture, context, 0.125) || isAnchorLand(fixture, context, 0.25);
            final boolean waterSeaward = !isAnchorLand(fixture, context, -0.0625) || !isAnchorLand(fixture, context, -0.125) || !isAnchorLand(fixture, context, -0.25);
            assertTrue(landInland, "No land just inland of the anchor at grid (" + context.anchorGridX() + ", " + context.anchorGridZ() + ")");
            assertTrue(waterSeaward, "No shore/ocean just seaward of the anchor at grid (" + context.anchorGridX() + ", " + context.anchorGridZ() + ")");
        }
    }

    private boolean isAnchorLand(Fixture fixture, RiverMouthContext context, double along)
    {
        final double gridX = context.anchorGridX() + context.inlandUnitX() * along;
        final double gridZ = context.anchorGridZ() + context.inlandUnitZ() * along;
        return RiverMouthBiomes.isAnchorLand(fixture.stack.biomeLayer.get(
            (int) Math.floor(gridX * Units.GRID_WIDTH_IN_QUART),
            (int) Math.floor(gridZ * Units.GRID_WIDTH_IN_QUART)));
    }

    private static synchronized Fixture fixture()
    {
        if (fixture == null)
        {
            for (long seed : CANDIDATE_SEEDS)
            {
                final Stack stack = new Stack(seed);
                final List<RiverMouthResolver.ResolvedMouth> mouths = findQualifyingMouths(stack);
                if (!mouths.isEmpty())
                {
                    fixture = new Fixture(stack, mouths);
                    return fixture;
                }
            }
            fail("No qualifying delta found on any candidate seed within " + SCAN_RADIUS_GRID + " grid of the origin - widen the scan or add seeds");
        }
        return fixture;
    }

    private static List<RiverMouthResolver.ResolvedMouth> findQualifyingMouths(Stack stack)
    {
        final List<RiverMouthResolver.ResolvedMouth> found = new ArrayList<>();
        final Set<RiverEdge> seen = new HashSet<>();
        for (int gridX = -SCAN_RADIUS_GRID; gridX <= SCAN_RADIUS_GRID; gridX += Units.PARTITION_WIDTH_IN_GRID)
        {
            for (int gridZ = -SCAN_RADIUS_GRID; gridZ <= SCAN_RADIUS_GRID; gridZ += Units.PARTITION_WIDTH_IN_GRID)
            {
                for (RiverEdge edge : stack.regionGenerator.getOrCreatePartitionPoint(gridX, gridZ).rivers())
                {
                    if (edge.drainEdge() == null && seen.add(edge))
                    {
                        stack.resolver.resolve(edge).ifPresent(found::add);
                    }
                }
            }
        }
        return found;
    }

    private record Fixture(Stack stack, List<RiverMouthResolver.ResolvedMouth> mouths) {}

    /**
     * A headless equivalent of the {@code TFCChunkGenerator} height pipeline: region generator, raw biome layer,
     * and per-chunk height fillers, with the mouth resolver toggleable per filler.
     */
    static final class Stack
    {
        final long seed;
        final RegionGenerator regionGenerator;
        final ConcurrentArea<BiomeExtension> biomeLayer;
        final RiverMouthResolver resolver;
        final Noise2D tideHeightNoise;

        Stack(long seed)
        {
            final Settings settings = BuiltinWorldPreset.defaultSettings();
            this.seed = seed;
            this.regionGenerator = new RegionGenerator(settings, Seed.of(seed));
            this.biomeLayer = new ConcurrentArea<>(TFCLayers.createRegionBiomeLayer(regionGenerator, Seed.of(seed)), TFCLayers::getFromLayerId);
            this.resolver = new RiverMouthResolver(seed, biomeLayer::get, 1.0);
            this.tideHeightNoise = BiomeNoise.shoreTideLevelNoise(Seed.of(seed));
        }

        RegionPartition.Point partition(int blockX, int blockZ)
        {
            return regionGenerator.getOrCreatePartitionPoint(Units.blockToGrid(blockX), Units.blockToGrid(blockZ));
        }

        @Nullable
        RiverMouthSample sampleMouth(int blockX, int blockZ)
        {
            return resolver.sample(partition(blockX, blockZ), Units.blockToGridExact(blockX), Units.blockToGridExact(blockZ));
        }

        BiomeSourceExtension biomeSource(boolean withResolver)
        {
            return new TestBiomeSource(this, withResolver ? resolver : null);
        }

        ChunkHeightFiller createHeightFiller(ChunkPos pos, boolean withResolver)
        {
            final BiomeSourceExtension source = biomeSource(withResolver);
            final Object2DoubleMap<BiomeExtension>[] biomeWeights = ChunkBiomeSampler.sampleBiomes(
                pos,
                (x, z) -> source.getBiomeExtensionNoRiver(QuartPos.fromBlock(x), QuartPos.fromBlock(z)),
                BiomeExtension::biomeBlendType);
            return new ChunkHeightFiller(biomeWeights, source, biomeSamplers(), riverSamplers(), shoreSamplers(), volcanoSamplers(), 63, tideHeightNoise);
        }

        Map<BiomeExtension, BiomeNoiseSampler> biomeSamplers()
        {
            // Mirrors TFCChunkGenerator.createBiomeSamplersForChunk: a stable fork per construction
            final Seed samplerSeed = Seed.of(seed);
            final ImmutableMap.Builder<BiomeExtension, BiomeNoiseSampler> builder = ImmutableMap.builder();
            for (BiomeExtension extension : TFCBiomes.REGISTRY)
            {
                final BiomeNoiseSampler sampler = extension.createNoiseSampler(samplerSeed);
                if (sampler != null)
                {
                    builder.put(extension, sampler);
                }
            }
            return builder.build();
        }

        Map<RiverBlendType, RiverNoiseSampler> riverSamplers()
        {
            final Seed samplerSeed = Seed.of(seed);
            final EnumMap<RiverBlendType, RiverNoiseSampler> map = new EnumMap<>(RiverBlendType.class);
            for (RiverBlendType type : RiverBlendType.ALL)
            {
                map.put(type, type.createNoiseSampler(samplerSeed));
            }
            return map;
        }

        Map<ShoreBlendType, ShoreNoiseSampler> shoreSamplers()
        {
            final Seed samplerSeed = Seed.of(seed);
            final EnumMap<ShoreBlendType, ShoreNoiseSampler> map = new EnumMap<>(ShoreBlendType.class);
            for (ShoreBlendType type : ShoreBlendType.ALL)
            {
                map.put(type, type.createNoiseSampler(samplerSeed));
            }
            return map;
        }

        Map<CenteredFeatureBlendType, CenteredFeatureNoiseSampler> volcanoSamplers()
        {
            final Seed samplerSeed = Seed.of(seed);
            final EnumMap<CenteredFeatureBlendType, CenteredFeatureNoiseSampler> map = new EnumMap<>(CenteredFeatureBlendType.class);
            for (CenteredFeatureBlendType type : CenteredFeatureBlendType.ALL)
            {
                map.put(type, type.createNoiseSampler(samplerSeed));
            }
            return map;
        }
    }

    private record TestBiomeSource(Stack stack, @Nullable RiverMouthResolver resolver) implements BiomeSourceExtension
    {
        @Override
        public BiomeExtension getBiomeExtensionNoRiver(int quartX, int quartZ)
        {
            return stack.biomeLayer.get(quartX, quartZ);
        }

        @Override
        public Holder<Biome> getBiomeFromExtension(BiomeExtension extension)
        {
            throw new UnsupportedOperationException("Not required for height sampling");
        }

        @Override
        public RegionPartition.Point getPartition(int blockX, int blockZ)
        {
            return stack.partition(blockX, blockZ);
        }

        @Override
        @Nullable
        public RiverMouthResolver riverMouthResolver()
        {
            return resolver;
        }
    }

    /** Tiny shared math for the tests. */
    static final class RiverMouthResolverTestSupport
    {
        static double distSq(double x0, double z0, double x1, double z1)
        {
            final double dx = x0 - x1, dz = z0 - z1;
            return dx * dx + dz * dz;
        }
    }
}
