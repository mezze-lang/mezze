package com.mezze.interpreter.v2.values;

/**
 * Mutable float wrapper for safe local mutation.
 *
 * This is the runtime representation of MutFloat - a mutable double.
 * Useful for accumulators, running averages, etc.
 */
public final class MutableFloat {
    private double value;

    public MutableFloat(double value) {
        this.value = value;
    }

    public double get() {
        return value;
    }

    public void set(double value) {
        this.value = value;
    }

    public void add(double delta) {
        this.value += delta;
    }

    public void subtract(double delta) {
        this.value -= delta;
    }

    public void multiply(double factor) {
        this.value *= factor;
    }

    public void divide(double divisor) {
        this.value /= divisor;
    }
}
