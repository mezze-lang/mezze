package com.mezze.datastructures.concurrent;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Atomic reference with watches and optional validation.
 *
 * Simpler than TVar - no transactions required.
 * Supports:
 * - Lock-free reads (deref)
 * - Atomic updates (reset, swap, compareAndSet)
 * - Optional validators (reject invalid state transitions)
 * - Watches (callbacks on state changes)
 *
 * Thread-safe: Yes
 * Lock-free: Yes (for basic operations)
 */
public final class MezzeAtom<T> implements TruffleObject {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    @CompilationFinal
    private final long id;

    private final AtomicReference<T> value;
    private volatile Predicate<T> validator;
    private final ConcurrentHashMap<String, Consumer<WatchEvent<T>>> watches;

    /**
     * Watch event data.
     */
    public record WatchEvent<T>(String key, T oldValue, T newValue) {}

    /**
     * Creates a new atom with the given initial value and no validator.
     */
    public MezzeAtom(T initialValue) {
        this(initialValue, null);
    }

    /**
     * Creates a new atom with the given initial value and validator.
     */
    public MezzeAtom(T initialValue, Predicate<T> validator) {
        this.id = NEXT_ID.getAndIncrement();
        this.value = new AtomicReference<>(initialValue);
        this.validator = validator;
        this.watches = new ConcurrentHashMap<>();

        // Validate initial value if validator is set
        if (validator != null && !validator.test(initialValue)) {
            throw new IllegalArgumentException("Initial value rejected by validator");
        }
    }

    /**
     * Returns the unique ID of this atom.
     */
    public long getId() {
        return id;
    }

    /**
     * Reads the current value.
     */
    public T deref() {
        return value.get();
    }

    /**
     * Sets a new value, running validator and notifying watches.
     */
    @TruffleBoundary
    public void reset(T newValue) {
        validate(newValue);
        T oldValue = value.getAndSet(newValue);
        notifyWatches(oldValue, newValue);
    }

    /**
     * Atomically swaps the value, running validator and notifying watches.
     * Returns the old value.
     */
    @TruffleBoundary
    public T swap(T newValue) {
        validate(newValue);
        T oldValue = value.getAndSet(newValue);
        notifyWatches(oldValue, newValue);
        return oldValue;
    }

    /**
     * Atomically updates the value using a function.
     * Retries on contention.
     * Returns the new value.
     */
    @TruffleBoundary
    public T swap(Function<T, T> updateFn) {
        T oldValue;
        T newValue;
        do {
            oldValue = value.get();
            newValue = updateFn.apply(oldValue);
            validate(newValue);
        } while (!value.compareAndSet(oldValue, newValue));
        notifyWatches(oldValue, newValue);
        return newValue;
    }

    /**
     * Compare-and-set without validation or watches.
     * Low-level operation for advanced use cases.
     */
    public boolean compareAndSet(T expected, T newValue) {
        return value.compareAndSet(expected, newValue);
    }

    /**
     * Compare-and-set with validation and watches.
     */
    @TruffleBoundary
    public boolean compareAndSwap(T expected, T newValue) {
        validate(newValue);
        if (value.compareAndSet(expected, newValue)) {
            notifyWatches(expected, newValue);
            return true;
        }
        return false;
    }

    /**
     * Sets the validator function.
     * The validator should return true for valid values.
     */
    public void setValidator(Predicate<T> newValidator) {
        // Validate current value with new validator
        if (newValidator != null) {
            T current = value.get();
            if (!newValidator.test(current)) {
                throw new IllegalArgumentException("Current value rejected by new validator");
            }
        }
        this.validator = newValidator;
    }

    /**
     * Gets the current validator, or null if none.
     */
    public Predicate<T> getValidator() {
        return validator;
    }

    /**
     * Removes the validator.
     */
    public void removeValidator() {
        this.validator = null;
    }

    private void validate(T newValue) {
        Predicate<T> v = validator;
        if (v != null && !v.test(newValue)) {
            throw new IllegalArgumentException("Value rejected by validator");
        }
    }

    /**
     * Adds a watch with the given key.
     * The callback is invoked on every state change.
     */
    @TruffleBoundary
    public void addWatch(String key, Consumer<WatchEvent<T>> callback) {
        watches.put(key, callback);
    }

    /**
     * Removes a watch by key.
     */
    @TruffleBoundary
    public void removeWatch(String key) {
        watches.remove(key);
    }

    /**
     * Checks if a watch with the given key exists.
     */
    public boolean hasWatch(String key) {
        return watches.containsKey(key);
    }

    /**
     * Returns the number of active watches.
     */
    public int watchCount() {
        return watches.size();
    }

    @TruffleBoundary
    private void notifyWatches(T oldValue, T newValue) {
        for (var entry : watches.entrySet()) {
            String key = entry.getKey();
            Consumer<WatchEvent<T>> callback = entry.getValue();
            try {
                callback.accept(new WatchEvent<>(key, oldValue, newValue));
            } catch (Exception e) {
                // Log but don't propagate watch errors
                System.err.println("Watch '" + key + "' threw exception: " + e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "<atom:" + id + ">";
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeAtom<?> other)) return false;
        return this.id == other.id;
    }
}
