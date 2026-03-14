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

    public void multiply(long factor) {
        this.value *= factor;
    }

    public void divide(long divisor) {
        this.value /= divisor;
    }

    public void mod(long divisor) {
        this.value %= divisor;
    }

    /**
     * Compare and swap. Returns true if value was equal to expected and was set to newValue.
     */
    public boolean compareAndSwap(long expected, long newValue) {
        if (this.value == expected) {
            this.value = newValue;
            return true;
        }
        return false;
    }
}
