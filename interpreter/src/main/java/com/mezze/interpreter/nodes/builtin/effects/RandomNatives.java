package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Native implementations for random number generation.
 *
 * Operations:
 * - random_float: Generate random float in [0, 1)
 * - random_int: Generate random int in [min, max]
 * - random_nat: Generate random nat in [0, max]
 * - random_seed: Set seed for thread-local random
 * - seeded_random_*: Deterministic random with explicit seed
 */
public final class RandomNatives {

    private RandomNatives() {}

    // Thread-local random for proper concurrency
    private static final ThreadLocal<Random> THREAD_LOCAL_RANDOM =
        ThreadLocal.withInitial(Random::new);

    // Seeded random generators for deterministic behavior
    private static final ConcurrentHashMap<Long, Random> SEEDED_RANDOMS =
        new ConcurrentHashMap<>();

    // ============== System Random Operations ==============

    @TruffleBoundary
    public static double randomFloat(MezzeRecord args) {
        return THREAD_LOCAL_RANDOM.get().nextDouble();
    }

    @TruffleBoundary
    public static long randomInt(MezzeRecord args) {
        long min = getLong(args, "min");
        long max = getLong(args, "max");
        // nextLong(origin, bound) is exclusive of bound, so +1
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    @TruffleBoundary
    public static long randomNat(MezzeRecord args) {
        long max = getLong(args, "max");
        return ThreadLocalRandom.current().nextLong(0, max + 1);
    }

    @TruffleBoundary
    public static Object randomSeed(MezzeRecord args) {
        long seed = getLong(args, "seed");
        THREAD_LOCAL_RANDOM.set(new Random(seed));
        return MezzeRecord.empty();
    }

    // ============== Seeded Random Operations ==============

    /**
     * Reset the seeded random generator to a fresh state.
     * This should be called at the start of each `perform SeededRandom` block
     * to ensure deterministic behavior across multiple perform blocks with the same seed.
     */

    // ============== Seeded-stream quality ==============

    /**
     * Decorrelate a user-chosen seed before it reaches {@link Random}.
     *
     * `java.util.Random(seed)` scrambles weakly, so nearby seeds produce nearby
     * FIRST outputs. Measured before this: across seeds, the first
     * `choose { bound = 100 }` clustered in roughly {19, 26, 63, 73}, and seeds 0 and
     * 1 returned the same value. That is not academic — `mez sample` mints a fresh
     * seed per run and a one-row sample takes exactly that first draw, so one-row
     * samples systematically missed whole branches of a generator. See S-5.
     *
     * This is SplitMix64's finalizer: a bijection, so distinct seeds stay distinct
     * and reproducibility is unaffected — the same seed still gives the same stream.
     * Only the mapping from seed to stream changes.
     */
    private static long scrambleSeed(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A `Random` for [seed], keyed by the seed the PROGRAM used, scrambled inside. */
    private static Random seededRandom(long seed) {
        return SEEDED_RANDOMS.computeIfAbsent(seed, s -> new Random(scrambleSeed(s)));
    }

    /**
     * A uniform long in [min, max], inclusive.
     *
     * The previous `min + (long) (nextDouble() * (max - min + 1))` was wrong twice
     * over. `nextDouble` carries 53 bits of mantissa, so scaling it across a 64-bit
     * span produced at most 2^53 distinct values spread coarsely — the low bits were
     * simply gone, which is why wide draws came out implausibly round. And
     * `max - min + 1` OVERFLOWS for a wide span, which since S-6 is a hard error
     * rather than a silently wrong modulus.
     *
     * `nextLong(origin, bound)` is uniform over the whole range and needs no
     * arithmetic on the span. The full-range case is special-cased because its
     * exclusive bound would be `Long.MAX_VALUE + 1`.
     */
    private static long nextInRange(Random random, long min, long max) {
        if (min >= max) return min;
        if (min == Long.MIN_VALUE && max == Long.MAX_VALUE) return random.nextLong();
        return random.nextLong(min, max + 1);
    }

    @TruffleBoundary
    public static Object resetSeededRandom(MezzeRecord args) {
        long seed = getLong(args, "seed");
        // Scrambled the same way as the lazy path, or a reset and a first use of the
        // same seed would hand back different streams.
        SEEDED_RANDOMS.put(seed, new Random(scrambleSeed(seed)));
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static double seededRandomFloat(MezzeRecord args) {
        long seed = getLong(args, "seed");
        return seededRandom(seed).nextDouble();
    }

    @TruffleBoundary
    public static long seededRandomInt(MezzeRecord args) {
        long seed = getLong(args, "seed");
        long min = getLong(args, "min");
        long max = getLong(args, "max");
        return nextInRange(seededRandom(seed), min, max);
    }

    @TruffleBoundary
    public static long seededRandomNat(MezzeRecord args) {
        long seed = getLong(args, "seed");
        long max = getLong(args, "max");
        return nextInRange(seededRandom(seed), 0, max);
    }

    @TruffleBoundary
    public static Object updateSeed(MezzeRecord args) {
        long oldSeed = getLong(args, "old_seed");
        long newSeed = getLong(args, "new_seed");
        SEEDED_RANDOMS.put(oldSeed, new Random(scrambleSeed(newSeed)));
        return MezzeRecord.empty();
    }

    // ============== Helpers ==============

    private static long getLong(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) {
            throw new NativeTypeMismatchException("Missing field '" + name + "'");
        }
        Object val = r.getField(index);
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Double d) return d.longValue();
        throw new NativeTypeMismatchException("Expected Long for field '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getSimpleName()));
    }
}
