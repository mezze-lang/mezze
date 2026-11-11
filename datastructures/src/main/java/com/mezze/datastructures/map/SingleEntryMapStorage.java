package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.util.Objects;

/**
 * Storage for single-entry maps.
 * Optimal for maps that stay small.
 */
public final class SingleEntryMapStorage implements MapStorage {

    @CompilationFinal
    private final Object key;

    @CompilationFinal
    private final Object value;

    public SingleEntryMapStorage(Object key, Object value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public int size() {
        return 1;
    }

    /**
     * `Objects.equals` STAYS HERE. Comparing a long key as a long — the change
     * that was 2.04x on {@code SmallFlatMapStorage}, where the same call ran
     * once per entry — measured WORSE on the one-element storages:
     *
     * <pre>
     *   row            Objects.equals   type-tested
     *     map has           2 192 ps        2 425    0.90x
     *     map get           2 836           3 091    0.92x
     *     set has           2 180           2 403    0.91x
     *     seq get  (ctl)    2 922           2 866    1.02x
     *     seq size (ctl)      646             595    1.09x
     *     counted loop        225             234    0.96x
     * </pre>
     *
     * n=2e7, min of 8 per arm, interleaved, both arms built by the dev launcher
     * with the extracted class confirmed to differ. Three independent target
     * rows moved together to 0.90-0.92x while three controls sat at 0.96-1.09x,
     * so it is a regression and not noise.
     *
     * <p>WHY IT DIFFERS FROM THE EIGHT-ENTRY SCAN. {@code key} here is a single
     * {@code @CompilationFinal} field, so partial evaluation already knows its
     * TYPE and devirtualises the {@code equals} completely — there is nothing
     * megamorphic left to remove, and an {@code instanceof} plus two
     * {@code longValue()} calls on top is pure added work. The flat scan's
     * eight entries did not fold that way. **A megamorphic call is worth
     * removing; a call PE has already devirtualised is not.**
     */
    @Override
    public Object get(Object searchKey) {
        return Objects.equals(key, searchKey) ? value : null;
    }

    @Override
    public boolean containsKey(Object searchKey) {
        return Objects.equals(key, searchKey);
    }

    @Override
    public MapStorage put(Object newKey, Object newValue) {
        if (Objects.equals(key, newKey)) {
            // Update existing
            return new SingleEntryMapStorage(newKey, newValue);
        }
        // Transition to SmallFlatMapStorage
        return new SmallFlatMapStorage(
                new Object[]{key, newKey},
                new Object[]{value, newValue}
        );
    }

    @Override
    public MapStorage remove(Object removeKey) {
        if (Objects.equals(key, removeKey)) {
            return EmptyMapStorage.INSTANCE;
        }
        return this;
    }

    @Override
    public Object[] keys() {
        return new Object[]{key};
    }

    @Override
    public Object[] values() {
        return new Object[]{value};
    }

    @Override
    public byte getStrategyTag() {
        return TAG_SINGLE;
    }

    public Object getKey() {
        return key;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "SingleEntryMapStorage{" + key + "=" + value + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SingleEntryMapStorage other)) return false;
        return Objects.equals(key, other.key) && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
