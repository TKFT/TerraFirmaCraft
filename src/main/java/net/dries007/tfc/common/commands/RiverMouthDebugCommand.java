/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.dries007.tfc.world.ChunkGeneratorExtension;
import net.dries007.tfc.world.biome.BiomeExtension;
import net.dries007.tfc.world.biome.BiomeSourceExtension;
import net.dries007.tfc.world.region.RegionPartition;
import net.dries007.tfc.world.region.RiverEdge;
import net.dries007.tfc.world.region.Units;

/**
 * TEMPORARY (river delta prototype) — diagnostic dump of terminal river edges, their drains, and the
 * raw biome field along the drain axis. Used to verify coastline-anchor placement. Removed before PR.
 */
public class RiverMouthDebugCommand
{
    public static LiteralArgumentBuilder<CommandSourceStack> create()
    {
        return Commands.literal("riverMouthDebug")
            .requires(source -> source.hasPermission(2))
            .executes(cmd -> dump(cmd.getSource()));
    }

    private static int dump(CommandSourceStack source)
    {
        if (!(source.getLevel().getChunkSource().getGenerator() instanceof ChunkGeneratorExtension extension))
        {
            source.sendFailure(Component.literal("Not a TFC chunk generator"));
            return 0;
        }

        final BiomeSourceExtension biomeSource = (BiomeSourceExtension) extension.self().getBiomeSource();
        final BlockPos pos = BlockPos.containing(source.getPosition());
        final double gridX = Units.blockToGridExact(pos.getX());
        final double gridZ = Units.blockToGridExact(pos.getZ());

        final RegionPartition.Point point = biomeSource.getPartition(pos.getX(), pos.getZ());
        final List<RiverEdge> terminals = new ArrayList<>();
        int totalEdges = 0;
        for (RiverEdge edge : point.rivers())
        {
            totalEdges++;
            if (edge.drainEdge() == null)
            {
                terminals.add(edge);
            }
        }
        terminals.sort(Comparator.comparingDouble(edge -> distSq(edge.drain().x(), edge.drain().y(), gridX, gridZ)));

        source.sendSuccess(() -> Component.literal("Partition edges: %d, terminal: %d, at grid (%.3f, %.3f)".formatted(
            point.rivers().size(), terminals.size(), gridX, gridZ)), false);

        for (int i = 0; i < Math.min(4, terminals.size()); i++)
        {
            final RiverEdge edge = terminals.get(i);
            final double drainX = edge.drain().x(), drainZ = edge.drain().y();
            final double sourceX = edge.source().x(), sourceZ = edge.source().y();
            final String text = "Terminal[%d]: drain grid (%.2f, %.2f) block (%d, %d), source grid (%.2f, %.2f), width %d, dist %.2f grid".formatted(
                i, drainX, drainZ,
                (int) (drainX * Units.GRID_WIDTH_IN_BLOCK), (int) (drainZ * Units.GRID_WIDTH_IN_BLOCK),
                sourceX, sourceZ, edge.width, Math.sqrt(distSq(drainX, drainZ, gridX, gridZ)));
            source.sendSuccess(() -> Component.literal(text), false);
        }

        if (!terminals.isEmpty())
        {
            marchRawBiomes(source, biomeSource, terminals.get(0));
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Samples the raw (no-river) biome field along the drain axis of {@code edge}, from 1.5 grid inland
     * of the drain to 3.0 grid seaward, at 0.125 grid steps — the same axis and step the mouth resolver's
     * coastline-anchor march uses.
     */
    private static void marchRawBiomes(CommandSourceStack source, BiomeSourceExtension biomeSource, RiverEdge edge)
    {
        final double drainX = edge.drain().x(), drainZ = edge.drain().y();
        double seaX = drainX - edge.source().x(), seaZ = drainZ - edge.source().y();
        final double mag = Math.sqrt(seaX * seaX + seaZ * seaZ);
        if (mag < 1.0e-6)
        {
            source.sendSuccess(() -> Component.literal("Degenerate terminal edge (source == drain)"), false);
            return;
        }
        seaX /= mag;
        seaZ /= mag;

        final StringBuilder line = new StringBuilder("March (inland -> sea): ");
        BiomeExtension prev = null;
        for (double along = -1.5; along <= 3.0; along += 0.125)
        {
            final double x = drainX + seaX * along;
            final double z = drainZ + seaZ * along;
            final BiomeExtension biome = biomeSource.getBiomeExtensionNoRiver((int) Math.round(x * Units.GRID_WIDTH_IN_QUART), (int) Math.round(z * Units.GRID_WIDTH_IN_QUART));
            if (biome != prev)
            {
                line.append("[%+.3f] %s ".formatted(along, biome.key().location().getPath()));
                prev = biome;
            }
        }
        source.sendSuccess(() -> Component.literal(line.toString()), false);
    }

    private static double distSq(double x0, double z0, double x1, double z1)
    {
        final double dx = x0 - x1, dz = z0 - z1;
        return dx * dx + dz * dz;
    }
}
