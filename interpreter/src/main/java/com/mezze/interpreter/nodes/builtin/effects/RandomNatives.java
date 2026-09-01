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
    @TruffleBoundary
    public static Object resetSeededRandom(MezzeRecord args) {
        long seed = getLong(args, "seed");
        SEEDED_RANDOMS.put(seed, new Random(seed));
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static double seededRandomFloat(MezzeRecord args) {
        long seed = getLong(args, "seed");
        Random random = SEEDED_RANDOMS.computeIfAbsent(seed, Random::new);
        return random.nextDouble();
    }

    @TruffleBoundary
    public static long seededRandomInt(MezzeRecord args) {
        long seed = getLong(args, "seed");
        long min = getLong(args, "min");
        long max = getLong(args, "max");
        Random random = SEEDED_RANDOMS.computeIfAbsent(seed, Random::new);
        // For longs, we need to handle the range ourselves
        return min + (long) (random.nextDouble() * (max - min + 1));
    }

    @TruffleBoundary
    public static long seededRandomNat(MezzeRecord args) {
        long seed = getLong(args, "seed");
        long max = getLong(args, "max");
        Random random = SEEDED_RANDOMS.computeIfAbsent(seed, Random::new);
        return (long) (random.nextDouble() * (max + 1));
    }

    @TruffleBoundary
    public static Object updateSeed(MezzeRecord args) {
        long oldSeed = getLong(args, "old_seed");
        long newSeed = getLong(args, "new_seed");
        SEEDED_RANDOMS.put(oldSeed, new Random(newSeed));
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
