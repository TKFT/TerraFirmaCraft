/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.surface;

import java.util.Map;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import net.dries007.tfc.world.Seed;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.TFCBiomes;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.dries007.tfc.world.settings.RockLayerSettings;
import net.dries007.tfc.world.surface.builder.SurfaceBuilder;

public final class SurfaceManager
{
    private static Map<BiomeExtension, SurfaceBuilder> collectSurfaceBuilders(Seed seed)
    {
        final ImmutableMap.Builder<BiomeExtension, SurfaceBuilder> builder = ImmutableMap.builder();
        for (BiomeExtension variant : TFCBiomes.REGISTRY)
        {
            builder.put(variant, variant.createSurfaceBuilder(seed));
        }
        return builder.build();
    }

    private final Map<BiomeExtension, SurfaceBuilder> builders;
    private final RiverMouthSurface mouthSurface;

    public SurfaceManager(Seed seed)
    {
        // Order matters: the mouth surface consumes its seeds after every biome builder, keeping their noise
        // seeds - and so every surface outside a mouth's fan mask - identical to before
        this.builders = collectSurfaceBuilders(seed);
        this.mouthSurface = new RiverMouthSurface(seed);
    }

    public void buildSurface(LevelAccessor world, ChunkAccess chunk, RockLayerSettings rockLayerSettings, ChunkData chunkData, BiomeExtension[] accurateChunkBiomes, BiomeExtension[] accurateChunkBiomesNoRivers, double[] accurateChunkBiomeWeights, RiverMouthSurface.Band[] mouthSurfaceBands, double[] mouthSurfaceWeights, double[] slopeMap, int[] preVolcanicHeights, RandomSource random, int seaLevel, int minY, BiomeExtension cinderConeBiome, BiomeExtension tuffRingBiome, BiomeExtension tuyaBiome, BiomeExtension atollBiome, BiomeExtension stratovolcanoBiome)
    {
        final boolean debugSlope = false;

        final ChunkPos chunkPos = chunk.getPos();
        final int blockX = chunkPos.getMinBlockX(), blockZ = chunkPos.getMinBlockZ();

        if (debugSlope)
        {
            slopeVisualization(chunk, slopeMap, blockX, blockZ);
        }

        final SurfaceBuilderContext context = new SurfaceBuilderContext(world, chunk, chunkData, random, rockLayerSettings, seaLevel, minY, cinderConeBiome, tuffRingBiome, tuyaBiome, atollBiome, stratovolcanoBiome);
        for (int x = 0; x < 16; ++x)
        {
            for (int z = 0; z < 16; ++z)
            {
                final int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                final double slope = sampleSlope(slopeMap, x, z);

                final BiomeExtension biome = accurateChunkBiomes[x + 16 * z];
                final BiomeExtension originalBiome = accurateChunkBiomesNoRivers[x + 16 * z];
                final double weight = accurateChunkBiomeWeights[x + 16 * z];
                final SurfaceBuilder builder = builders.get(biome);

                final int preVolcanicHeight = preVolcanicHeights[x + 16 * z];

                // Inside a terminal mouth's fan mask, the mouth-aware wrapper replaces the biome's surface with
                // the band's delta materials, feathering back to the biome builder through the mask boundary
                final RiverMouthSurface.Band band = mouthSurfaceBands[x + 16 * z];
                final double mouthWeight = mouthSurfaceWeights[x + 16 * z];
                final SurfaceBuilder actualBuilder = band == RiverMouthSurface.Band.NONE
                    ? builder
                    : (ctx, startY, endY) -> mouthSurface.buildSurface(ctx, band, mouthWeight, builder, startY, endY);

                context.buildSurface(biome, originalBiome, weight, biome.isSalty(), actualBuilder, blockX + x, y, blockZ + z, slope, preVolcanicHeight);
            }
        }
    }

    /**
     * Samples the 'slope' value for a given coordinate within the chunk
     * Expected values are in [0, 13] but are practically unbounded above
     */
    @SuppressWarnings("PointlessArithmeticExpression")
    private double sampleSlope(double[] slopeMap, int x, int z)
    {
        // compute slope contribution from lerp of corners
        final int offsetX = x + 2, offsetZ = z + 2;
        final int cellX = offsetX >> 2, cellZ = offsetZ >> 2;
        final double deltaX = ((double) offsetX - (cellX << 2)) * 0.25, deltaZ = ((double) offsetZ - (cellZ << 2)) * 0.25;

        double slope = 0;
        slope += slopeMap[(cellX + 0) + 6 * (cellZ + 0)] * (1 - deltaX) * (1 - deltaZ);
        slope += slopeMap[(cellX + 1) + 6 * (cellZ + 0)] * (deltaX) * (1 - deltaZ);
        slope += slopeMap[(cellX + 0) + 6 * (cellZ + 1)] * (1 - deltaX) * (deltaZ);
        slope += slopeMap[(cellX + 1) + 6 * (cellZ + 1)] * (deltaX) * (deltaZ);

        slope *= 0.8f;
        return slope;
    }

    private void slopeVisualization(ChunkAccess chunk, double[] slopeMap, int chunkX, int chunkZ)
    {
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        final Block[] meter = new Block[] {
            Blocks.WHITE_STAINED_GLASS,
            Blocks.LIGHT_GRAY_STAINED_GLASS,
            Blocks.LIGHT_BLUE_STAINED_GLASS,
            Blocks.BLUE_STAINED_GLASS,
            Blocks.CYAN_STAINED_GLASS,
            Blocks.GREEN_STAINED_GLASS,
            Blocks.LIME_STAINED_GLASS,
            Blocks.YELLOW_STAINED_GLASS,
            Blocks.ORANGE_STAINED_GLASS,
            Blocks.RED_STAINED_GLASS,
            Blocks.MAGENTA_STAINED_GLASS,
            Blocks.PINK_STAINED_GLASS
        };

        for (int x = 0; x < 16; x++)
        {
            for (int z = 0; z < 16; z++)
            {
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                mutablePos.set(chunkX + x, y, chunkZ + z);
                double slope = sampleSlope(slopeMap, x, z);
                int slopeIndex = Mth.clamp((int) slope, 0, meter.length - 1);
                chunk.setBlockState(mutablePos, meter[slopeIndex].defaultBlockState(), false);
            }
        }
    }
}
