package com.mezze.interpreter.v2.values;

/**
 * Mutable int wrapper for safe local mutation.
 *
 * This is the runtime representation of MutInt - a mutable integer.
 * Useful for counters, accumulators, etc.
 */
public final class MutableInt {
    private long value;

    public MutableInt(long value) {
        this.value = value;
    }

    public long get() {
        return value;
    }

    public void set(long value) {
        this.value = value;
    }

    public void add(long delta) {
        this.value += delta;
    }

    public void subtract(long delta) {
        this.value -= delta;
    }

    public long getAndIncrement() {
        return value++;
    }

    public long incrementAndGet() {
        return ++value;
    }
}
