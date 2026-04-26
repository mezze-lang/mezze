package com.mezze.interpreter.values;

/**
 * Mutable bool wrapper for safe local mutation.
 *
 * This is the runtime representation of MutBool - a mutable boolean.
 * Useful for flags, seen markers, etc.
 */
public final class MutableBool {
    private boolean value;

    public MutableBool(boolean value) {
        this.value = value;
    }

    public boolean get() {
        return value;
    }

    public void set(boolean value) {
        this.value = value;
    }

    public void toggle() {
        this.value = !this.value;
    }

    public boolean getAndSet(boolean newValue) {
        boolean old = this.value;
        this.value = newValue;
        return old;
    }
}
