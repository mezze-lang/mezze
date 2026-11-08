package com.mezze.datastructures.sorted;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Immutable sorted map backed by sorted arrays.
 * Uses binary search for O(log n) lookups.
 *
 * @param <K> Key type (must be Comparable)
 * @param <V> Value type
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedMap<K, V> implements TruffleObject, Iterable<MezzeSortedMap.Entry<K, V>> {

    @CompilationFinal(dimensions = 1)
    private final Object[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    @CompilationFinal
    private final int length;  // logical size (may be < keys.length with spare capacity)

    @CompilationFinal
    private final Comparator<Object> comparator;

    /** COW ownership token for O(1) amortized insert chains. */
    private final Object[] ownerToken;

    /**
     * Key-value entry.
     */
    public record Entry<K, V>(K key, V value) {}

    /**
     * Raw key-value entry for interpreter use.
     */
    public record RawEntry(Object key, Object value) {}

    @SuppressWarnings("unchecked")
    private MezzeSortedMap(Object[] keys, Object[] values, Comparator<?> comparator) {
        this.keys = keys;
        this.values = values;
        this.length = keys.length;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
        this.ownerToken = null;
    }

    @SuppressWarnings("unchecked")
    private MezzeSortedMap(Object[] keys, Object[] values, int length,
                           Comparator<?> comparator, Object[] ownerToken) {
        this.keys = keys;
        this.values = values;
        this.length = length;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
        this.ownerToken = ownerToken;
    }

    /** Latched flag: once anything has READ this structure it may never be
     *  mutated in place again, however the ownership token stands. (The
     *  rationale used to live on HashMapStorage, which is gone — its whole
     *  family was replaced by persistent tries after the in-place writes turned
     *  out to be observable in the structure derived FROM; see known-bugs
     *  BUG 38.) */
    @CompilationFinal
    private boolean ownershipRevoked;

    private boolean isOwner() {
        return !ownershipRevoked && ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownershipRevoked) return;
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
        ownershipRevoked = true;
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty sorted map.
     */
    public static <K, V> MezzeSortedMap<K, V> empty() {
        return new MezzeSortedMap<>(new Object[0], new Object[0], null);
    }

    /**
     * Returns an empty sorted map with custom comparator.
     */
    public static <K, V> MezzeSortedMap<K, V> empty(Comparator<? super K> comparator) {
        return new MezzeSortedMap<>(new Object[0], new Object[0], comparator);
    }

    /**
     * Creates a sorted map with a single entry.
     */
    public static <K, V> MezzeSortedMap<K, V> of(K key, V value) {
        return new MezzeSortedMap<>(new Object[]{key}, new Object[]{value}, null);
    }

    /**
     * Creates a sorted map from entries.
     */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static <K, V> MezzeSortedMap<K, V> of(Entry<K, V>... entries) {
        if (entries.length == 0) return empty();
        if (entries.length == 1) return of(entries[0].key, entries[0].value);

        // Sort by key
        Entry<K, V>[] sorted = entries.clone();
        // K is unbounded (see the note on MezzeSortedSet): this varargs builder is a
        // Java-side convenience used only with naturally-comparable keys, so it
        // compares through Comparable explicitly rather than requiring the bound.
        Arrays.sort(sorted, (x, y) -> ((Comparable<Object>) x.key).compareTo(y.key));

        // Build arrays, handling duplicates (last wins)
        Object[] keys = new Object[sorted.length];
        Object[] values = new Object[sorted.length];
        int size = 0;

        for (Entry<K, V> entry : sorted) {
            if (size > 0 && Objects.equals(keys[size - 1], entry.key)) {
                values[size - 1] = entry.value;  // Update
            } else {
                keys[size] = entry.key;
                values[size] = entry.value;
                size++;
            }
        }

        if (size < sorted.length) {
            keys = Arrays.copyOf(keys, size);
            values = Arrays.copyOf(values, size);
        }

        return new MezzeSortedMap<>(keys, values, null);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of entries.
     */
    public int size() {
        return length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * Gets the value for a key, or null if not present.
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        revokeOwnership();
        int idx = binarySearch(key);
        return idx >= 0 ? (V) values[idx] : null;
    }

    /**
     * Gets the value for a key (raw type), or null if not present.
     * For interpreter use with dynamic types.
     */
    @SuppressWarnings("unchecked")
    public Object getObject(Object key) {
        revokeOwnership();
        int idx = binarySearchObject(key);
        return idx >= 0 ? values[idx] : null;
    }

    /**
     * Gets the value for a key, or default if not present.
     */
    public V getOrElse(K key, V defaultValue) {
        V value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns true if the key is present.
     */
    /** This map's key ordering — see the note on MezzeSortedSet.comparator(). */
    public Comparator<Object> comparator() {
        return comparator;
    }

    /** The same entries ordered by [cmp]; a field swap, not a re-sort (see
     *  MezzeSortedSet.withComparator). */
    public MezzeSortedMap<K, V> withComparator(Comparator<? super K> cmp) {
        if (cmp == null || cmp == this.comparator) return this;
        // Hands BOTH arrays over, and the result can escape sharing them:
        // `removeObject` of an absent key returns `this`. Sound for the same
        // reason the replace above is — the only in-place write lands outside
        // the window either map reads.
        return new MezzeSortedMap<>(keys, values, length, cmp, null);
    }

    /**
     * The key at a position in sort order, for a search driven from OUTSIDE
     * this class.
     *
     * <p>WHY THIS EXISTS. A lookup used to run entirely in here, which meant
     * the comparison ran in HOST code and therefore called back into guest
     * code once per comparison — two allocations and an uncached
     * {@code CallTarget.call} each time, measured at ~30 ns a comparison and
     * ~300 ns a lookup. Nothing about how that callback is made can fix it:
     * host code is past the boundary, so Graal can never inline a guest
     * function into it. Driving the binary search from guest code instead
     * makes the comparison an ordinary guest call that DOES inline, and
     * reduces this class's job to an array read.
     *
     * <p>Ownership is revoked exactly as {@code getObject} does, and for the
     * same reason: the caller has now observed these contents, so a later
     * insert must copy rather than mutate underneath them. The flag makes it
     * idempotent, so the repeated probes of one search pay it once.
     */
    public Object keyAt(int i) {
        revokeOwnership();
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("sorted map key index " + i + " of " + length);
        }
        return keys[i];
    }

    /** The value at a position in sort order. See {@link #keyAt}. */
    public Object valueAt(int i) {
        revokeOwnership();
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("sorted map value index " + i + " of " + length);
        }
        return values[i];
    }

    public boolean containsKey(K key) {
        revokeOwnership();
        return binarySearch(key) >= 0;
    }

    /**
     * Returns true if the key is present (raw type).
     * For interpreter use with dynamic types.
     */
    public boolean containsKeyObject(Object key) {
        revokeOwnership();
        return binarySearchObject(key) >= 0;
    }

    /**
     * Binary search for key.
     */
    private int binarySearch(K key) {
        return binarySearchObject(key);
    }

    /**
     * Binary search for key (raw type).
     */
    private int binarySearchObject(Object key) {
        int low = 0;
        int high = length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            @SuppressWarnings("unchecked")
            int cmp = comparator.compare(keys[mid], key);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return -(low + 1);
    }

    /**
     * Returns the minimum key, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public K minKey() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (K) keys[0];
    }

    /**
     * Returns the maximum key, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public K maxKey() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (K) keys[length - 1];
    }

    /**
     * Returns the value for the minimum key.
     */
    @SuppressWarnings("unchecked")
    public V minValue() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (V) values[0];
    }

    /**
     * Returns the value for the maximum key.
     */
    @SuppressWarnings("unchecked")
    public V maxValue() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (V) values[length - 1];
    }

    // ===== UPDATE =====

    /**
     * Returns a new sorted map with the entry added/updated.
     */
    public MezzeSortedMap<K, V> put(K key, V value) {
        return putObject(key, value);
    }

    /** Whether {@link #appendInPlace} may run. Both arrays are written, so
     *  both capacities are checked. */
    private boolean canAppendInPlace() {
        return isOwner() && length < keys.length && length < values.length;
    }

    /**
     * Append at index {@code length} without copying, and hand the token to
     * the result.
     *
     * <p>The ONLY in-place write in this class, and sound by ADDRESSABILITY
     * rather than by care: every map holding these arrays reads
     * {@code [0, length)}, so none of them can address the slot. The map
     * derived here carries {@code length + 1} and is the only one that can
     * see it.
     *
     * <p>There used to be a sibling taking the same token for a MIDDLE insert,
     * shifting {@code [insertPos, length)} up by one. That range IS
     * addressable, by the base itself, so inserting into a map changed the
     * map — `base.put(5, _)` on 0,2,4,6,… left `base` reading 5 at index 3 and
     * 20 gone. It is deleted rather than guarded (BUG 40): a fast path that
     * mutates inside its own read window is not a fast path. Anything added
     * beside this one has to make the addressability argument first.
     */
    @SuppressWarnings("unchecked")
    private MezzeSortedMap<K, V> appendInPlace(Object key, Object value) {
        keys[length] = key;
        values[length] = value;
        Object[] token = ownerToken;
        MezzeSortedMap<K, V> result =
                new MezzeSortedMap<>(keys, values, length + 1, comparator, token);
        token[0] = result;
        return result;
    }

    /**
     * Replace the value at a position in sort order, for a caller that has
     * ALREADY located the key — the guest-side binary search in
     * {@code std::collections::sorted_map}. Makes no comparison and so needs
     * no comparator: the native search cost ~30 ns per comparison and ~300 ns
     * a lookup, against ~15 ns for the whole guest search.
     *
     * <p>Shares the keys array, exactly as {@link #putObject}'s replace does.
     */
    public MezzeSortedMap<K, V> setAt(int i, Object value) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("sorted map value index " + i + " of " + length);
        }
        Object[] newValues = Arrays.copyOf(values, length);
        newValues[i] = value;
        return new MezzeSortedMap<>(keys, newValues, length, comparator, null);
    }

    /**
     * Insert a key and value at a position in sort order. See {@link #setAt}
     * for why this takes a position rather than searching for one.
     *
     * <p>THE CALLER OWNS THE ORDERING. {@code insertPos} must be the index
     * that keeps the array sorted — what the guest search returns encoded as
     * {@code -(insertPos + 1)} when the key is absent. A wrong position
     * silently produces an unsorted array, which every later binary search
     * then reads as a missing key, so this is deliberately not public API: the
     * only caller is the native beside the search that computes it.
     */
    public MezzeSortedMap<K, V> insertAt(int insertPos, Object key, Object value) {
        if (insertPos < 0 || insertPos > length) {
            throw new IndexOutOfBoundsException(
                    "sorted map insert position " + insertPos + " of " + length);
        }
        if (insertPos == length && canAppendInPlace()) return appendInPlace(key, value);

        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        Object[] newKeys = new Object[newCapacity];
        Object[] newValues = new Object[newCapacity];

        System.arraycopy(keys, 0, newKeys, 0, insertPos);
        newKeys[insertPos] = key;
        System.arraycopy(keys, insertPos, newKeys, insertPos + 1, length - insertPos);

        System.arraycopy(values, 0, newValues, 0, insertPos);
        newValues[insertPos] = value;
        System.arraycopy(values, insertPos, newValues, insertPos + 1, length - insertPos);

        Object[] token = new Object[1];
        @SuppressWarnings("unchecked")
        MezzeSortedMap<K, V> result =
                new MezzeSortedMap<>(newKeys, newValues, length + 1, comparator, token);
        token[0] = result;
        return result;
    }

    /**
     * Returns a new sorted map with the entry added/updated (raw type).
     * For interpreter use with dynamic types.
     */
    @SuppressWarnings("unchecked")
    public MezzeSortedMap<K, V> putObject(Object key, Object value) {
        int idx = binarySearchObject(key);

        if (idx >= 0) {
            // A replace changes no KEY, so the keys array is shared rather than
            // copied. That halves the allocation, and this operation is
            // allocation bound rather than compute bound: a chain of replaces
            // ran 264 young collections where the `size` row over the same
            // process shape ran 3.
            //
            // The share needs nothing revoked: the only in-place write left
            // in this class lands at index `length`, which no map reading
            // `[0, length)` can address. See appendInPlace.
            //
            // This map's values array is exactly `length` while the keys array
            // it shares may be longer. Nothing reads `values.length`, so that
            // is safe, and canAppendInPlace checks BOTH capacities because the
            // write it guards touches both arrays.
            Object[] newValues = Arrays.copyOf(values, length);
            newValues[idx] = value;
            return new MezzeSortedMap<>(keys, newValues, length, comparator, null);
        }

        // Insert new
        int insertPos = -(idx + 1);

        if (insertPos == length && canAppendInPlace()) return appendInPlace(key, value);

        // Allocate with spare capacity
        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        Object[] newKeys = new Object[newCapacity];
        Object[] newValues = new Object[newCapacity];

        System.arraycopy(keys, 0, newKeys, 0, insertPos);
        newKeys[insertPos] = key;
        System.arraycopy(keys, insertPos, newKeys, insertPos + 1, length - insertPos);

        System.arraycopy(values, 0, newValues, 0, insertPos);
        newValues[insertPos] = value;
        System.arraycopy(values, insertPos, newValues, insertPos + 1, length - insertPos);

        Object[] token = new Object[1];
        @SuppressWarnings("unchecked")
        MezzeSortedMap<K, V> result = new MezzeSortedMap<>(newKeys, newValues, length + 1, comparator, token);
        token[0] = result;
        return result;
    }

    /**
     * Returns a new sorted map with the key removed.
     */
    public MezzeSortedMap<K, V> remove(K key) {
        return removeObject(key);
    }

    /**
     * Returns a new sorted map with the key removed (raw type).
     * For interpreter use with dynamic types.
     */
    @SuppressWarnings("unchecked")
    public MezzeSortedMap<K, V> removeObject(Object key) {
        revokeOwnership();
        int idx = binarySearchObject(key);
        if (idx < 0) return this;

        if (length == 1) return empty();

        Object[] newKeys = new Object[length - 1];
        Object[] newValues = new Object[length - 1];

        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, length - idx - 1);

        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, length - idx - 1);

        return new MezzeSortedMap<>(newKeys, newValues, comparator);
    }

    // ===== RANGE OPERATIONS =====

    /**
     * Returns entries with keys in range [fromKey, toKey].
     */
    public MezzeSortedMap<K, V> range(K fromKey, K toKey) {
        int fromIdx = binarySearch(fromKey);
        if (fromIdx < 0) fromIdx = -(fromIdx + 1);

        int toIdx = binarySearch(toKey);
        if (toIdx < 0) {
            toIdx = -(toIdx + 1);
        } else {
            toIdx++;
        }

        if (fromIdx >= toIdx) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, fromIdx, toIdx),
                Arrays.copyOfRange(values, fromIdx, toIdx),
                comparator
        );
    }

    /**
     * Returns entries with keys >= fromKey.
     */
    public MezzeSortedMap<K, V> rangeFrom(K fromKey) {
        int idx = binarySearch(fromKey);
        if (idx < 0) idx = -(idx + 1);

        if (idx >= length) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, idx, length),
                Arrays.copyOfRange(values, idx, length),
                comparator
        );
    }

    /**
     * Returns entries with keys <= toKey.
     */
    public MezzeSortedMap<K, V> rangeTo(K toKey) {
        int idx = binarySearch(toKey);
        if (idx < 0) {
            idx = -(idx + 1);
        } else {
            idx++;
        }

        if (idx <= 0) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, 0, idx),
                Arrays.copyOfRange(values, 0, idx),
                comparator
        );
    }

    // ===== NAVIGATION =====

    /**
     * Returns entry with greatest key <= given, or null if none.
     */
    @SuppressWarnings("unchecked")
    public Entry<K, V> floorEntry(K key) {
        int idx = binarySearch(key);
        if (idx >= 0) {
            return new Entry<>((K) keys[idx], (V) values[idx]);
        }
        int insertPos = -(idx + 1);
        if (insertPos == 0) return null;
        return new Entry<>((K) keys[insertPos - 1], (V) values[insertPos - 1]);
    }

    /**
     * Returns entry with smallest key >= given, or null if none.
     */
    @SuppressWarnings("unchecked")
    public Entry<K, V> ceilingEntry(K key) {
        int idx = binarySearch(key);
        if (idx >= 0) {
            return new Entry<>((K) keys[idx], (V) values[idx]);
        }
        int insertPos = -(idx + 1);
        if (insertPos >= length) return null;
        return new Entry<>((K) keys[insertPos], (V) values[insertPos]);
    }

    // ===== VIEWS =====

    /**
     * Returns all keys.
     */
    public Object[] keys() {
        return Arrays.copyOf(keys, length);
    }

    /**
     * Returns all values.
     */
    public Object[] values() {
        return Arrays.copyOf(values, length);
    }

    // ===== ITERATION =====

    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Entry<K, V> next() {
                if (!hasNext()) throw new NoSuchElementException();
                Entry<K, V> entry = new Entry<>((K) keys[index], (V) values[index]);
                index++;
                return entry;
            }
        };
    }

    /**
     * Returns a raw entry iterator for interpreter use with dynamic types.
     */
    public Iterable<RawEntry> rawEntries() {
        return () -> new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < length;
            }

            @Override
            public RawEntry next() {
                if (!hasNext()) throw new NoSuchElementException();
                RawEntry entry = new RawEntry(keys[index], values[index]);
                index++;
                return entry;
            }
        };
    }

    // ===== BACKING ARRAYS =====

    /**
     * The backing keys array, for a caller that walks entries in sort order
     * without allocating a copy of each one — {@code SortedMapNatives}
     * compares two maps with it.
     *
     * <p>READ ONLY, and bounded by {@link #size()} rather than by the array's
     * own length: the array carries spare capacity, and a replace SHARES it
     * with the map it derived from, so the slots past {@code size()} belong to
     * nobody and writing any slot at all would be visible in another map.
     */
    public Object[] getKeysInternal() {
        return keys;
    }

    /** The backing values array. See {@link #getKeysInternal}. */
    public Object[] getValuesInternal() {
        return values;
    }

    // ===== OBJECT METHODS =====

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedMap(");
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append(" -> ").append(values[i]);
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedMap<?, ?> other)) return false;
        return Arrays.equals(keys(), other.keys()) && Arrays.equals(values(), other.values());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keys()) ^ Arrays.hashCode(values());
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    long getHashSize() {
        return length;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return containsKeyObject(key);
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    @SuppressWarnings("unchecked")
    Object readHashValue(Object key) throws UnknownKeyException {
        try {
            V value = get((K) key);
            if (value == null && !containsKey((K) key)) {
                throw UnknownKeyException.create(key);
            }
            return value;
        } catch (ClassCastException e) {
            throw UnknownKeyException.create(key);
        }
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isHashEntryModifiable(@SuppressWarnings("unused") Object key) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isHashEntryInsertable(@SuppressWarnings("unused") Object key) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    void writeHashEntry(@SuppressWarnings("unused") Object key, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIterator() {
        return new SortedMapIterator(iterator());
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getHashEntriesIterator() {
        return new SortedMapIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SortedMapIterator implements TruffleObject {
        private final Iterator<?> iterator;

        SortedMapIterator(Iterator<?> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean isIterator() {
            return true;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }
}
