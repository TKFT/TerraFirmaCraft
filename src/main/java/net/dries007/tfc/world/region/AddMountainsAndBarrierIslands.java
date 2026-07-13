/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.region;

import java.util.BitSet;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

public enum AddMountainsAndBarrierIslands implements RegionTask
{
    INSTANCE;

    @Override
    public void apply(RegionGenerator.Context context)
    {
        final Region region = context.region;
        final RandomSource random = context.random;

        // Add collisional ranges first
        for (Region.Point point : region.points())
        {
            if (point.land() && point.divergence < 0 && point.distanceToEdge < 1.5 * context.generator().continentNoise.noise(point.x, point.z) - 5.2)
            {
                point.setMountain();
            }
        }

        // Then ranges following contours
        for (int attempt = 0, mtnsPlaced = 0, islesPlaced = 0; attempt < 40 && !(mtnsPlaced >= 3 && islesPlaced >= 6); attempt++)
        {
            final @Nullable Region.Point origin = region.random(random);
            if (origin != null)
            {
                // Attempt to construct a mountain range
                // We do this with a bit of a DFS / BFS hybrid - intentionally imprecise and random - across a contour of the base land height
                // Ranges at low altitudes (near ocean) get marked as oceanic ranges, where mid-high altitude ranges get marked as high altitude mountains.
                if (mtnsPlaced < 3 && (origin.land() && origin.baseLandHeight <= 1 || (origin.baseLandHeight >= 4 && origin.baseLandHeight <= 11)))
                {
                    final IntSet range = placeRange(region, random, origin.index);
                    if (range.size() > 45)
                    {
                        range.forEach(index -> {
                            final Region.Point point = region.atIndex(index);

                            point.setMountain();
                            if (origin.divergence < 0 && origin.baseLandHeight < 8)
                            {
                                // Mark mountain chain as volcanic if we are in a subduction zone and not too far inland
                                point.setVolcanic();
                            }
                            if (origin.baseLandHeight <= 2)
                            {
                                point.setCoastalMountain();
                            }
                        });
                        mtnsPlaced++;
                    }
                }
                // Attempt to construct a volcanic arc on the continental shelf in subduction zones
                // Works similarly to mountain ranges, but based on distance to edge of continental shelf
                else if (islesPlaced < 6 && !origin.land() && origin.oceanDepth == 2 && origin.divergence < 0 && origin.distanceToDeepOcean <= 3 && origin.distanceToLand > 2)
                {
                    final VolcanicArc arc = placeVolcanicArc(region, random, origin.index);
                    if (arc.spineSize() >= MIN_VOLCANIC_ARC_SPINE_SIZE)
                    {
                        arc.shelf().forEach(index -> {
                            final Region.Point point = region.atIndex(index);
                            point.setVolcanic();
                            point.oceanDepth = 1;
                        });

                        arc.islands().forEach(index -> region.atIndex(index).setBarrierIsland());
                        islesPlaced += 2;
                    }
                }
                // Attempt to construct a barrier island chain
                // Works similarly to mountain ranges, but based on distance to land and are thinner
                else if (islesPlaced < 6 && !origin.land() && origin.oceanDepth == 2 && origin.divergence > 0 && origin.distanceToLand > 2 && origin.distanceToLand < 6)
                {
                    final IntSet range = placeBarrier(region, random, origin.index);
                    final byte startContour = (byte) Math.max(1, origin.distanceToLand);
                    if (range.size() > 45)
                    {
                        range.forEach(index -> {
                            final Region.Point point = region.atIndex(index);

                            if (point.distanceToLand == startContour)
                            {
                                point.setBarrierIsland();
                            }
                            point.oceanDepth = 1;
                        });
                        islesPlaced++;
                    }
                }
            }
        }
    }

    private IntSet placeRange(Region region, RandomSource random, int originIndex)
    {
        final BitSet explored = new BitSet(region.size());
        final IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        final IntSet range = new IntOpenHashSet();

        queue.enqueue(originIndex);
        explored.set(originIndex);
        range.add(originIndex);

        // So that low altitude ranges don't start at 0 altitude, now they can follow the [0, 1] contour
        final int originBaseLandHeight = Math.max(1, region.atIndex(originIndex).baseLandHeight);
        final int maxSize = 70 + random.nextInt(40);

        while (!queue.isEmpty())
        {
            final int last = queue.dequeueInt();
            final Region.Point lastPoint = region.atIndex(last);
            if (range.size() > maxSize)
            {
                break;
            }

            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    final @Nullable Region.Point point = region.atOffset(last, dx, dz);

                    // Only explore the contour within [-1, 1] of the origin
                    // The baseLandHeight > 2 || distanceToOcean < 3 is to avoid what should be coastal mountains diverting inland due to
                    // the presence of a cell edge causing an artificial low point.
                    if (point != null &&
                        point.land() &&
                        point.baseLandHeight >= originBaseLandHeight - 1 &&
                        point.baseLandHeight <= originBaseLandHeight + 1 &&
                        (point.baseLandHeight > 2 || point.distanceToOcean < 3) &&
                        !explored.get(point.index))
                    {
                        if (lastPoint.baseLandHeight != point.baseLandHeight)
                        {
                            queue.enqueue(point.index);
                        }
                        else
                        {
                            queue.enqueueFirst(point.index);
                        }
                        range.add(point.index);
                        explored.set(point.index);
                    }
                }
            }
        }

        return range;
    }

    private static final int MIN_VOLCANIC_ARC_SPINE_SIZE = 36;
    private static final int MIN_VOLCANIC_ARC_TARGET_SIZE = 48;
    private static final int VOLCANIC_ARC_TARGET_SIZE_VARIANCE = 20;

    /**
     * Constructs a volcanic island arc as two related shapes:
     *
     * <ul>
     *     <li>{@code islands}: the visible volcanic island chain.</li>
     *     <li>{@code shelf}: the islands plus the surrounding shallow volcanic shelf.</li>
     * </ul>
     *
     * The previous implementation flood-filled a wide distance-to-deep-ocean contour. Since
     * that contour is calculated on the square region grid, the fill could follow a closed
     * contour into a square ring. This implementation instead grows a narrow, ordered spine
     * from both sides of the origin and widens it only after the spine has been accepted.
     */
    private VolcanicArc placeVolcanicArc(Region region, RandomSource random, int originIndex)
    {
        final Region.Point origin = region.atIndex(originIndex);
        final int originContour = Math.max(1, origin.distanceToDeepOcean);
        final int targetSpineSize = MIN_VOLCANIC_ARC_TARGET_SIZE + random.nextInt(VOLCANIC_ARC_TARGET_SIZE_VARIANCE);

        // Start approximately tangent to the local shelf contour. The random fallback also
        // chooses which of the two equivalent tangent directions is considered "forward".
        final double fallbackAngle = random.nextDouble() * Math.PI * 2d;
        final ArcDirection fallback = new ArcDirection(Math.cos(fallbackAngle), Math.sin(fallbackAngle));
        final ArcDirection initialDirection = volcanicArcTangent(region, originIndex, fallback);

        final IntSet occupied = new IntOpenHashSet();
        occupied.add(originIndex);

        final int forwardTarget = (targetSpineSize - 1) / 2;
        final int backwardTarget = targetSpineSize - 1 - forwardTarget;

        final IntArrayList forward = growVolcanicArcEnd(
            region,
            random,
            originIndex,
            initialDirection,
            originContour,
            forwardTarget,
            occupied
        );
        final IntArrayList backward = growVolcanicArcEnd(
            region,
            random,
            originIndex,
            initialDirection.opposite(),
            originContour,
            backwardTarget,
            occupied
        );

        // Reassemble the two outward-growing paths into one ordered end-to-end spine.
        final IntArrayList spine = new IntArrayList();
        for (int i = backward.size() - 1; i >= 0; i--)
        {
            spine.add(backward.getInt(i));
        }
        spine.add(originIndex);
        for (int i = 0; i < forward.size(); i++)
        {
            spine.add(forward.getInt(i));
        }

        if (spine.size() < MIN_VOLCANIC_ARC_SPINE_SIZE)
        {
            return VolcanicArc.EMPTY;
        }

        return widenVolcanicArc(region, random, spine, originContour);
    }

    /**
     * Grows one end of a volcanic arc using immediate eight-neighbor movement.
     *
     * Direction is controlled by three related signals:
     *
     * <ol>
     *     <li>A smoothed current heading, preventing abrupt 90-degree turns.</li>
     *     <li>The local tangent of the deep-ocean distance field.</li>
     *     <li>The original heading from the arc origin, preventing the path from wrapping
     *     all the way around a closed contour.</li>
     * </ol>
     */
    private IntArrayList growVolcanicArcEnd(
        Region region,
        RandomSource random,
        int originIndex,
        ArcDirection initialDirection,
        int originContour,
        int targetLength,
        IntSet occupied
    )
    {
        final IntArrayList path = new IntArrayList();
        final Region.Point origin = region.atIndex(originIndex);

        final ArcDirection initial = normalize(initialDirection);
        ArcDirection heading = initial;

        int previousIndex = -1;
        int currentIndex = originIndex;
        double furthestProjection = 0d;

        while (path.size() < targetLength)
        {
            final Region.Point current = region.atIndex(currentIndex);
            final ArcDirection tangent = volcanicArcTangent(region, currentIndex, heading);
            final double currentProjection = projectionFromOrigin(origin, current, initial);

            int bestIndex = -1;
            int bestDx = 0;
            int bestDz = 0;
            double bestProjection = 0d;
            double bestScore = Double.NEGATIVE_INFINITY;

            // Match barrier-island adjacency: no two-cell jumps and no 5x5 expansion front.
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    if (dx == 0 && dz == 0)
                    {
                        continue;
                    }

                    final @Nullable Region.Point candidate = region.atOffset(currentIndex, dx, dz);
                    if (candidate == null ||
                        occupied.contains(candidate.index) ||
                        !isValidVolcanicArcPoint(candidate, originContour) ||
                        touchesOlderVolcanicArc(region, occupied, candidate.index, currentIndex, previousIndex))
                    {
                        continue;
                    }

                    final ArcDirection step = normalize(new ArcDirection(dx, dz));
                    final double headingAlignment = dot(step, heading);
                    final double tangentAlignment = dot(step, tangent);
                    final double initialAlignment = dot(step, initial);
                    final double candidateProjection = projectionFromOrigin(origin, candidate, initial);

                    /*
                     * After the path is established, do not allow it to reverse around the
                     * back side of the contour. Slight sideways or backward stair-steps are
                     * still permitted so curves do not become brittle.
                     */
                    if (path.size() > 8 && initialAlignment < -0.10d)
                    {
                        continue;
                    }
                    if (path.size() > 8 && candidateProjection < furthestProjection - 1.75d)
                    {
                        continue;
                    }

                    final int contourDifference = Math.abs(candidate.distanceToDeepOcean - originContour);
                    double score = random.nextInt(12);

                    // Smooth local movement without forcing long axis-aligned runs.
                    score += 12d * headingAlignment;
                    score += 10d * tangentAlignment;

                    // Maintain a broad global direction so a closed contour cannot become a ring.
                    score += 5d * initialAlignment;
                    score += 4d * (candidateProjection - currentProjection);

                    // Treat the distance field as a corridor rather than a literal track.
                    score -= 5d * contourDifference;

                    // Strongly discourage a sudden local reversal, even during the first steps.
                    if (headingAlignment < -0.25d)
                    {
                        score -= 24d;
                    }

                    if (score > bestScore)
                    {
                        bestScore = score;
                        bestIndex = candidate.index;
                        bestDx = dx;
                        bestDz = dz;
                        bestProjection = candidateProjection;
                    }
                }
            }

            if (bestIndex == -1)
            {
                break;
            }

            previousIndex = currentIndex;
            currentIndex = bestIndex;
            occupied.add(currentIndex);
            path.add(currentIndex);

            // Blend toward the selected grid step. This yields gradual sequences such as
            // east -> east -> northeast -> northeast -> north instead of a hard right angle.
            final ArcDirection step = normalize(new ArcDirection(bestDx, bestDz));
            heading = normalize(new ArcDirection(
                0.76d * heading.x() + 0.24d * step.x(),
                0.76d * heading.z() + 0.24d * step.z()
            ));
            furthestProjection = Math.max(furthestProjection, bestProjection);
        }

        return path;
    }

    /**
     * Uses the completed ordered spine to create the actual islands and surrounding shelf.
     * Nothing added here can recursively add more cells, so widening cannot escape around a
     * closed distance contour.
     */
    private VolcanicArc widenVolcanicArc(Region region, RandomSource random, IntArrayList spine, int originContour)
    {
        final IntSet islands = new IntOpenHashSet();
        final IntSet shelf = new IntOpenHashSet();

        for (int i = 0; i < spine.size(); i++)
        {
            final int centerIndex = spine.getInt(i);
            islands.add(centerIndex);
            shelf.add(centerIndex);

            // A one-cell shelf around the spine produces a broad volcanic platform while the
            // visible island geometry remains tied to the smoother ordered centerline.
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    final @Nullable Region.Point point = region.atOffset(centerIndex, dx, dz);
                    if (point != null && isValidVolcanicArcPoint(point, originContour))
                    {
                        shelf.add(point.index);
                    }
                }
            }

            final int beforeIndex = spine.getInt(i > 0 ? i - 1 : i);
            final int afterIndex = spine.getInt(i + 1 < spine.size() ? i + 1 : i);
            final Region.Point before = region.atIndex(beforeIndex);
            final Region.Point after = region.atIndex(afterIndex);

            final int tangentX = Integer.signum(after.x - before.x);
            final int tangentZ = Integer.signum(after.z - before.z);
            if (tangentX == 0 && tangentZ == 0)
            {
                continue;
            }

            final int sideX = -tangentZ;
            final int sideZ = tangentX;

            // Occasional non-recursive island shoulders create wider island clusters without
            // turning the entire chain into two parallel contour bands.
            if (random.nextFloat() < 0.22f)
            {
                addVolcanicArcIslandShoulder(region, islands, shelf, centerIndex, sideX, sideZ, originContour);
            }
            if (random.nextFloat() < 0.22f)
            {
                addVolcanicArcIslandShoulder(region, islands, shelf, centerIndex, -sideX, -sideZ, originContour);
            }

            // Rare second-width shelf cells break up the otherwise uniform three-cell-wide
            // platform. These are shelf only, never visible island cells.
            if (random.nextFloat() < 0.30f)
            {
                addVolcanicArcShelfPoint(region, shelf, centerIndex, 2 * sideX, 2 * sideZ, originContour);
            }
            if (random.nextFloat() < 0.30f)
            {
                addVolcanicArcShelfPoint(region, shelf, centerIndex, -2 * sideX, -2 * sideZ, originContour);
            }
        }

        return new VolcanicArc(islands, shelf, spine.size());
    }

    private void addVolcanicArcIslandShoulder(
        Region region,
        IntSet islands,
        IntSet shelf,
        int centerIndex,
        int dx,
        int dz,
        int originContour
    )
    {
        final @Nullable Region.Point point = region.atOffset(centerIndex, dx, dz);
        if (point != null && isValidVolcanicArcPoint(point, originContour))
        {
            islands.add(point.index);
            shelf.add(point.index);
        }
    }

    private void addVolcanicArcShelfPoint(
        Region region,
        IntSet shelf,
        int centerIndex,
        int dx,
        int dz,
        int originContour
    )
    {
        final @Nullable Region.Point point = region.atOffset(centerIndex, dx, dz);
        if (point != null && isValidVolcanicArcPoint(point, originContour))
        {
            shelf.add(point.index);
        }
    }

    private boolean isValidVolcanicArcPoint(Region.Point point, int originContour)
    {
        return !point.land() &&
            point.oceanDepth == 2 &&
            point.divergence < 0 &&
            point.distanceToDeepOcean >= originContour - 1 &&
            point.distanceToDeepOcean <= originContour + 2;
    }

    /**
     * Prevents a new endpoint from touching an older part of either half of the spine.
     * The current and immediately previous cells are excluded so normal diagonal and
     * right-angle grid transitions remain possible.
     */
    private boolean touchesOlderVolcanicArc(
        Region region,
        IntSet occupied,
        int candidateIndex,
        int currentIndex,
        int previousIndex
    )
    {
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dz = -1; dz <= 1; dz++)
            {
                if (dx == 0 && dz == 0)
                {
                    continue;
                }

                final @Nullable Region.Point neighbor = region.atOffset(candidateIndex, dx, dz);
                if (neighbor != null &&
                    neighbor.index != currentIndex &&
                    neighbor.index != previousIndex &&
                    occupied.contains(neighbor.index))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Estimates a broad local tangent to the distance-to-deep-ocean field. Sampling across a
     * five-cell window smooths the integer distance field enough that corners are approached
     * gradually instead of as a single hard 90-degree turn.
     */
    private ArcDirection volcanicArcTangent(Region region, int index, ArcDirection fallback)
    {
        double gradientX = 0d;
        double gradientZ = 0d;

        for (int offset = -2; offset <= 2; offset++)
        {
            final @Nullable Region.Point negativeX = region.atOffset(index, -2, offset);
            final @Nullable Region.Point positiveX = region.atOffset(index, 2, offset);
            if (negativeX != null && positiveX != null)
            {
                gradientX += positiveX.distanceToDeepOcean - negativeX.distanceToDeepOcean;
            }

            final @Nullable Region.Point negativeZ = region.atOffset(index, offset, -2);
            final @Nullable Region.Point positiveZ = region.atOffset(index, offset, 2);
            if (negativeZ != null && positiveZ != null)
            {
                gradientZ += positiveZ.distanceToDeepOcean - negativeZ.distanceToDeepOcean;
            }
        }

        if (Math.abs(gradientX) + Math.abs(gradientZ) < 1e-5d)
        {
            return normalize(fallback);
        }

        ArcDirection tangent = normalize(new ArcDirection(-gradientZ, gradientX));
        if (dot(tangent, fallback) < 0d)
        {
            tangent = tangent.opposite();
        }
        return tangent;
    }

    private double projectionFromOrigin(Region.Point origin, Region.Point point, ArcDirection direction)
    {
        return (point.x - origin.x) * direction.x() + (point.z - origin.z) * direction.z();
    }

    private double dot(ArcDirection first, ArcDirection second)
    {
        return first.x() * second.x() + first.z() * second.z();
    }

    private ArcDirection normalize(ArcDirection direction)
    {
        final double length = Math.sqrt(direction.x() * direction.x() + direction.z() * direction.z());
        return length < 1e-5d
            ? new ArcDirection(1d, 0d)
            : new ArcDirection(direction.x() / length, direction.z() / length);
    }

    private record VolcanicArc(IntSet islands, IntSet shelf, int spineSize)
    {
        private static final VolcanicArc EMPTY = new VolcanicArc(new IntOpenHashSet(), new IntOpenHashSet(), 0);
    }

    private record ArcDirection(double x, double z)
    {
        private ArcDirection opposite()
        {
            return new ArcDirection(-x, -z);
        }
    }

    private IntSet placeBarrier(Region region, RandomSource random, int originIndex)
    {
        final BitSet explored = new BitSet(region.size());
        final IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        final IntSet barrier = new IntOpenHashSet();

        queue.enqueue(originIndex);
        explored.set(originIndex);
        barrier.add(originIndex);

        // So that near-ocean barriers don't start at 0 distance, now they can follow the [0, 1] contour
        final int originDistanceToLand = Math.max(1, region.atIndex(originIndex).distanceToLand);
        final int maxSize = 70 + random.nextInt(40);

        while (!queue.isEmpty())
        {
            final int last = queue.dequeueInt();
            final Region.Point lastPoint = region.atIndex(last);
            if (barrier.size() > maxSize)
            {
                break;
            }

            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dz = -1; dz <= 1; dz++)
                {
                    final @Nullable Region.Point point = region.atOffset(last, dx, dz);

                    // Only explore the contour within [-1, 1] of the origin
                    if (point != null &&
                        point.oceanDepth == 2 &&
                        point.distanceToLand >= originDistanceToLand - 1 &&
                        point.distanceToLand <= originDistanceToLand + 1 &&
                        !explored.get(point.index))
                    {
                        if (lastPoint.distanceToLand != point.distanceToLand)
                        {
                            queue.enqueue(point.index);
                        }
                        else
                        {
                            queue.enqueueFirst(point.index);
                        }
                        barrier.add(point.index);
                        explored.set(point.index);
                    }
                }
            }
        }

        return barrier;
    }
}
