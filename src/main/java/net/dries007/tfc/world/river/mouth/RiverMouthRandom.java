/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.world.river.mouth;

/**
 * Small, allocation-free deterministic hashing for mouth shape jitter. Every random-looking value in the mouth
 * system derives from a stable {@code shapeSeed} (level seed + quantized drain coordinates) through these — never
 * from identity hashes, iteration order, or per-run state.
 */
public final class RiverMouthRandom
{
    /**
     * SplitMix64 finalizer — a high-quality 64-bit bit mixer.
     */
    public static long mix(long value)
    {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * @return A uniform value in [0, 1), deterministic in {@code (seed, salt)}.
     */
    public static double hash01(long seed, int salt)
    {
        return (mix(seed + salt * 0xC2B2AE3D27D4EB4FL) >>> 11) / (double) (1L << 53);
    }

    /**
     * @return A uniform value in [-1, 1), deterministic in {@code (seed, salt)}.
     */
    public static double hash11(long seed, int salt)
    {
        return hash01(seed, salt) * 2 - 1;
    }

    private RiverMouthRandom() {}
}
