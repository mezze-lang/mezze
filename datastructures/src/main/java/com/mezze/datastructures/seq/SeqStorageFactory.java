package com.mezze.datastructures.seq;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Factory for creating optimal SeqStorage based on element types.
 * Automatically selects the most efficient storage strategy.
 */
public final class SeqStorageFactory {

    private SeqStorageFactory() {}

    /**
     * Create storage for a single element.
     */
    public static SeqStorage createSingle(Object value) {
        if (value instanceof Long l) {
            return new LongSeqStorage(new long[]{l});
        } else if (value instanceof Double d) {
            return new DoubleSeqStorage(new double[]{d});
        } else if (value instanceof Boolean b) {
            return BooleanSeqStorage.fromBooleans(new boolean[]{b});
        } else {
            return new ObjectSeqStorage(new Object[]{value});
        }
    }

    /**
     * Create storage from an array of objects.
     * Detects if all elements are the same primitive type and uses specialized storage.
     */
    public static SeqStorage fromArray(Object[] elements) {
        if (elements.length == 0) {
            return EmptySeqStorage.INSTANCE;
        }

        if (elements.length == 1) {
            return createSingle(elements[0]);
        }

        // Check element types
        boolean allLongs = true;
        boolean allDoubles = true;
        boolean allBooleans = true;

        for (Object elem : elements) {
            if (!(elem instanceof Long)) allLongs = false;
            if (!(elem instanceof Double)) allDoubles = false;
            if (!(elem instanceof Boolean)) allBooleans = false;

            // Early exit if all flags are false
            if (!allLongs && !allDoubles && !allBooleans) {
                break;
            }
        }

        if (allLongs) {
            long[] data = new long[elements.length];
            for (int i = 0; i < elements.length; i++) {
                data[i] = (Long) elements[i];
            }
            return new LongSeqStorage(data);
        }

        if (allDoubles) {
            double[] data = new double[elements.length];
            for (int i = 0; i < elements.length; i++) {
                data[i] = (Double) elements[i];
            }
            return new DoubleSeqStorage(data);
        }

        if (allBooleans) {
            boolean[] data = new boolean[elements.length];
            for (int i = 0; i < elements.length; i++) {
                data[i] = (Boolean) elements[i];
            }
            return BooleanSeqStorage.fromBooleans(data);
        }

        return new ObjectSeqStorage(elements.clone());
    }

    /**
     * Create storage from an array of objects WITHOUT cloning.
     * Caller must guarantee the array won't be modified.
     * This is faster than fromArray() but unsafe if the array is mutated.
     */
    public static SeqStorage fromArrayUnsafe(Object[] elements) {
        if (elements.length == 0) {
            return EmptySeqStorage.INSTANCE;
        }

        if (elements.length == 1) {
            return createSingle(elements[0]);
        }

        // Check element types with early exit
        Object first = elements[0];
        if (first instanceof Long) {
            boolean allLongs = true;
            for (int i = 1; i < elements.length; i++) {
                if (!(elements[i] instanceof Long)) {
                    allLongs = false;
                    break;
                }
            }
            if (allLongs) {
                long[] data = new long[elements.length];
                for (int i = 0; i < elements.length; i++) {
                    data[i] = (Long) elements[i];
                }
                return new LongSeqStorage(data);
            }
        } else if (first instanceof Double) {
            boolean allDoubles = true;
            for (int i = 1; i < elements.length; i++) {
                if (!(elements[i] instanceof Double)) {
                    allDoubles = false;
                    break;
                }
            }
            if (allDoubles) {
                double[] data = new double[elements.length];
                for (int i = 0; i < elements.length; i++) {
                    data[i] = (Double) elements[i];
                }
                return new DoubleSeqStorage(data);
            }
        } else if (first instanceof Boolean) {
            boolean allBooleans = true;
            for (int i = 1; i < elements.length; i++) {
                if (!(elements[i] instanceof Boolean)) {
                    allBooleans = false;
                    break;
                }
            }
            if (allBooleans) {
                boolean[] data = new boolean[elements.length];
                for (int i = 0; i < elements.length; i++) {
                    data[i] = (Boolean) elements[i];
                }
                return BooleanSeqStorage.fromBooleans(data);
            }
        }

        // No clone - caller owns the array
        return new ObjectSeqStorage(elements);
    }

    /**
     * Create storage from a long array (unboxed).
     */
    public static LongSeqStorage fromLongs(long[] elements) {
        if (elements.length == 0) {
            throw new IllegalArgumentException("Use EmptySeqStorage.INSTANCE for empty sequences");
        }
        return new LongSeqStorage(elements.clone());
    }

    /**
     * Create storage from a double array (unboxed).
     */
    public static DoubleSeqStorage fromDoubles(double[] elements) {
        if (elements.length == 0) {
            throw new IllegalArgumentException("Use EmptySeqStorage.INSTANCE for empty sequences");
        }
        return new DoubleSeqStorage(elements.clone());
    }

    /**
     * Create storage from a boolean array.
     */
    public static BooleanSeqStorage fromBooleans(boolean[] elements) {
        if (elements.length == 0) {
            throw new IllegalArgumentException("Use EmptySeqStorage.INSTANCE for empty sequences");
        }
        return BooleanSeqStorage.fromBooleans(elements);
    }

    /**
     * Create a range of longs [start, end).
     */
    public static LongSeqStorage range(long start, long end) {
        if (start >= end) {
            throw new IllegalArgumentException("Start must be less than end");
        }
        if (LAZY_RANGE) {
            return LongSeqStorage.lazyRange(start, end, 1L);
        }
        int size = (int) (end - start);
        long[] data = new long[size];
        for (int i = 0; i < size; i++) {
            data[i] = start + i;
        }
        return new LongSeqStorage(data);
    }

    // Env-gated: MEZZE_LAZY_RANGE=1 enables lazy RangeSeq for seq::range.
    private static final boolean LAZY_RANGE = "1".equals(System.getenv("MEZZE_LAZY_RANGE"));

    /**
     * Create a range with step.
     */
    public static LongSeqStorage range(long start, long end, long step) {
        if (step == 0) {
            throw new IllegalArgumentException("Step cannot be zero");
        }
        if ((step > 0 && start >= end) || (step < 0 && start <= end)) {
            throw new IllegalArgumentException("Invalid range");
        }
        if (LAZY_RANGE) {
            return LongSeqStorage.lazyRange(start, end, step);
        }

        int size = (int) ((end - start + step - Long.signum(step)) / step);
        long[] data = new long[size];
        long value = start;
        for (int i = 0; i < size; i++) {
            data[i] = value;
            value += step;
        }
        return new LongSeqStorage(data);
    }

    /**
     * Create a sequence filled with the same value.
     */
    public static SeqStorage fill(int count, Object value) {
        if (count <= 0) {
            return EmptySeqStorage.INSTANCE;
        }

        if (value instanceof Long l) {
            long[] data = new long[count];
            java.util.Arrays.fill(data, l);
            return new LongSeqStorage(data);
        } else if (value instanceof Double d) {
            double[] data = new double[count];
            java.util.Arrays.fill(data, d);
            return new DoubleSeqStorage(data);
        } else if (value instanceof Boolean b) {
            boolean[] data = new boolean[count];
            java.util.Arrays.fill(data, b);
            return BooleanSeqStorage.fromBooleans(data);
        } else {
            Object[] data = new Object[count];
            java.util.Arrays.fill(data, value);
            return new ObjectSeqStorage(data);
        }
    }

    /**
     * Create a sequence by applying a function to indices.
     */
    @TruffleBoundary
    public static SeqStorage tabulate(int count, java.util.function.IntFunction<Object> fn) {
        if (count <= 0) {
            return EmptySeqStorage.INSTANCE;
        }

        Object[] data = new Object[count];
        for (int i = 0; i < count; i++) {
            data[i] = fn.apply(i);
        }

        return fromArray(data);
    }

    /**
     * Create a sequence by applying a function to indices (long result).
     */
    public static LongSeqStorage tabulateLong(int count, java.util.function.IntToLongFunction fn) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        long[] data = new long[count];
        for (int i = 0; i < count; i++) {
            data[i] = fn.applyAsLong(i);
        }

        return new LongSeqStorage(data);
    }

    /**
     * Create a sequence by applying a function to indices (double result).
     */
    public static DoubleSeqStorage tabulateDouble(int count, java.util.function.IntToDoubleFunction fn) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }

        double[] data = new double[count];
        for (int i = 0; i < count; i++) {
            data[i] = fn.applyAsDouble(i);
        }

        return new DoubleSeqStorage(data);
    }
}
