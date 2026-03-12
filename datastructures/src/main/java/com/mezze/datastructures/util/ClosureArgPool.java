package com.mezze.datastructures.util;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Thread-local pool of reusable argument arrays for closure calls.
 *
 * Critical optimization: HOF operations like map/filter create an argument
 * array for each element. This pool eliminates that allocation by reusing
 * arrays for common closure+arg sizes.
 *
 * Thread-local ensures no synchronization overhead.
 * Pre-allocated for sizes 1-8 (covers 99% of real-world cases).
 *
 * Usage pattern:
 * <pre>
 *   Object[] args = ClosureArgPool.acquire(closureLen + 1);
 *   try {
 *       // populate args and call
 *       System.arraycopy(closureValues, 0, args, 0, closureLen);
 *       args[closureLen] = element;
 *       Object result = callNode.call(args);
 *   } finally {
 *       ClosureArgPool.release(args);
 *   }
 * </pre>
 */
public final class ClosureArgPool {

    // Maximum cached size
    private static final int MAX_CACHED_SIZE = 8;

    // Thread-local pools indexed by size
    private static final ThreadLocal<Object[][]> POOL = ThreadLocal.withInitial(() -> {
        Object[][] pool = new Object[MAX_CACHED_SIZE + 1][];
        for (int i = 0; i <= MAX_CACHED_SIZE; i++) {
            pool[i] = new Object[i];
        }
        return pool;
    });

    // Thread-local in-use flags (one per size)
    private static final ThreadLocal<boolean[]> IN_USE = ThreadLocal.withInitial(() -> new boolean[MAX_CACHED_SIZE + 1]);

    /**
     * Acquire an argument array of the given size.
     * Returns a pooled array if available, otherwise allocates.
     */
    public static Object[] acquire(int size) {
        if (size <= MAX_CACHED_SIZE) {
            boolean[] inUse = IN_USE.get();
            if (!inUse[size]) {
                inUse[size] = true;
                return POOL.get()[size];
            }
        }
        // Pool exhausted or size too large - allocate
        return new Object[size];
    }

    /**
     * Release an argument array back to the pool.
     * Clears the array to avoid memory leaks.
     */
    public static void release(Object[] args) {
        int size = args.length;
        if (size <= MAX_CACHED_SIZE) {
            boolean[] inUse = IN_USE.get();
            Object[][] pool = POOL.get();
            if (args == pool[size]) {
                // Clear to prevent memory leaks
                for (int i = 0; i < size; i++) {
                    args[i] = null;
                }
                inUse[size] = false;
            }
        }
        // Non-pooled arrays are just garbage collected
    }

    /**
     * Acquire an array pre-filled with closure values.
     * This is the common pattern: closure values + one element slot.
     */
    public static Object[] acquireForClosure(Object[] closureValues, int extraSlots) {
        int size = closureValues.length + extraSlots;
        Object[] args = acquire(size);
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        return args;
    }

    /**
     * Acquire array for zero-closure case (most common).
     */
    public static Object[] acquireSingleArg() {
        return acquire(1);
    }

    /**
     * Acquire array for two-arg case (common for binary operations).
     */
    public static Object[] acquireTwoArgs() {
        return acquire(2);
    }

    private ClosureArgPool() {
        // Utility class
    }
}
