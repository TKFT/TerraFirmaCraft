/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.test.world;

import java.util.ArrayList;
import java.util.EnumMap;
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
import net.dries007.tfc.world.river.RiverBlendType;
import net.dries007.tfc.world.river.RiverNoiseSampler;
import net.dries007.tfc.world.river.mouth.RiverMouthResolver;
import net.dries007.tfc.world.river.mouth.RiverMouthSample;
import net.dries007.tfc.world.settings.Settings;
import net.dries007.tfc.world.shore.ShoreBlendType;
import net.dries007.tfc.world.shore.ShoreNoiseSampler;
import net.dries007.tfc.world.volcano.CenteredFeatureBlendType;
import net.dries007.tfc.world.volcano.CenteredFeatureNoiseSampler;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless worldgen gates for the terminal-local delta dispatch (Phase 2):
 * <ul>
 *     <li>A qualifying mouth exists on a fixed seed and shows a real terrain change inside its fan mask.</li>
 *     <li>Every column outside the fan mask — including the rest of a wide shore biome and every nonqualifying
 *     river — generates a bit-identical height with the resolver enabled vs disabled.</li>
 * </ul>
 * Heights are compared through two independent {@link ChunkHeightFiller} stacks that differ only in whether the
 * biome source exposes a {@link RiverMouthResolver}.
 */
public class DeltaWorldgenTest implements TestSetup
{
    /** Fixed seeds to scan, in order, until one produces a qualifying delta. Deterministic - no random retries. */
    private static final long[] CANDIDATE_SEEDS = {1000L, 2000L, 3000L, 4000L, 5000L};
    /** Grid radius around the origin to scan for terminal edges. */
    private static final int SCAN_RADIUS_GRID = 300;

    @Test
    public void testDeltaTerrainChangeAndLocality()
    {
        for (long seed : CANDIDATE_SEEDS)
        {
            final Stack stack = new Stack(seed);
            final RiverMouthResolver.ResolvedMouth mouth = findQualifyingMouth(stack);
            if (mouth == null)
            {
                continue;
            }

            assertTerrainChangeAndLocality(stack, mouth);
            return;
        }
        fail("No qualifying delta found on any candidate seed within " + SCAN_RADIUS_GRID + " grid of the origin - widen the scan or add seeds");
    }

    private void assertTerrainChangeAndLocality(Stack stack, RiverMouthResolver.ResolvedMouth mouth)
    {
        final int anchorBlockX = (int) Math.round(mouth.context().anchorGridX() * Units.GRID_WIDTH_IN_BLOCK);
        final int anchorBlockZ = (int) Math.round(mouth.context().anchorGridZ() * Units.GRID_WIDTH_IN_BLOCK);
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
                final ChunkHeightFiller onFiller = stack.createHeightFiller(pos, true);
                final ChunkHeightFiller offFiller = stack.createHeightFiller(pos, false);

                for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x += step)
                {
                    for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z += step)
                    {
                        final double on = onFiller.sampleHeight(x, z);
                        final double off = offFiller.sampleHeight(x, z);

                        final RiverMouthSample sample = stack.resolver.sample(
                            stack.partition(x, z), Units.blockToGridExact(x), Units.blockToGridExact(z));
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

    @Nullable
    private RiverMouthResolver.ResolvedMouth findQualifyingMouth(Stack stack)
    {
        final Set<RiverEdge> seen = new HashSet<>();
        // Scan outward in rings so the found mouth is deterministic for the seed regardless of scan bounds
        for (int radius = 0; radius <= SCAN_RADIUS_GRID; radius += Units.PARTITION_WIDTH_IN_GRID)
        {
            final List<RiverMouthResolver.ResolvedMouth> found = new ArrayList<>();
            for (int gridX = -radius; gridX <= radius; gridX += Units.PARTITION_WIDTH_IN_GRID)
            {
                for (int gridZ = -radius; gridZ <= radius; gridZ += Units.PARTITION_WIDTH_IN_GRID)
                {
                    if (Math.max(Math.abs(gridX), Math.abs(gridZ)) + Units.PARTITION_WIDTH_IN_GRID <= radius)
                    {
                        continue; // Interior, already scanned on a previous ring
                    }
                    for (RiverEdge edge : stack.regionGenerator.getOrCreatePartitionPoint(gridX, gridZ).rivers())
                    {
                        if (edge.drainEdge() == null && seen.add(edge))
                        {
                            stack.resolver.resolve(edge).ifPresent(found::add);
                        }
                    }
                }
            }
            if (!found.isEmpty())
            {
                return found.get(0);
            }
        }
        return null;
    }

    /**
     * A headless equivalent of the {@code TFCChunkGenerator} height pipeline: region generator, raw biome layer,
     * and per-chunk height fillers, with the mouth resolver toggleable per filler.
     */
    private static final class Stack
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

        ChunkHeightFiller createHeightFiller(ChunkPos pos, boolean withResolver)
        {
            final BiomeSourceExtension source = new TestBiomeSource(this, withResolver ? resolver : null);
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
}
