package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable set with automatic storage strategy selection.
 *
 * @param <A> Element type
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSet<A> implements TruffleObject, Iterable<A> {

    @CompilationFinal
    private final SetStorage storage;

    private MezzeSet(SetStorage storage) {
        this.storage = storage;
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty set.
     */
    public static <A> MezzeSet<A> empty() {
        return new MezzeSet<>(EmptySetStorage.INSTANCE);
    }

    /**
     * Creates a single-element set.
     */
    public static <A> MezzeSet<A> of(A element) {
        return new MezzeSet<>(new SingleElemSetStorage(element));
    }

    /**
     * Creates a set from varargs.
     */
    @SafeVarargs
    public static <A> MezzeSet<A> of(A... elements) {
        if (elements.length == 0) {
            return empty();
        }
        if (elements.length == 1) {
            return of(elements[0]);
        }

        return fromElements(elements, elements.length);
    }

    /**
     * BUILDS A SET FROM ELEMENTS ALREADY IN HAND, in one pass.
     *
     * <p>WHY. Every path that rebuilds a whole set — `filter`, `map`,
     * `filter_map`, `insert_all`, `symmetric_difference`, `intersection`,
     * `difference`, `from_list`, `of` — wrote {@code result = result.add(e)}
     * per element, and so did {@code TrieSetStorage.fromCollection}, which the
     * two {@code fromMutableSet} helpers called while their comments claimed a
     * one-pass build. A persistent add allocates a node at every level of the
     * path it touches: right for an update whose base survives, wasted for a
     * rebuild that discards the base on the next element. The map measured the
     * same change at 3.2x.
     *
     * <p>THE STRATEGY LADDER IS PRESERVED, which is why this lives on
     * `MezzeSet` and not on a storage. A set's storage is chosen by size and
     * element type — single, then {@link SmallFlatSetStorage} to
     * {@link SmallFlatSetStorage#MAX_SIZE}, {@link LongSetStorage} to
     * {@link LongSetStorage#MAX_SIZE} for all-Long elements, then the long
     * trie or the general one. Below the widest flat tier this adds one at a
     * time, which IS the ladder's answer and is cheap at that size; above it a
     * builder takes over and the tier is the one the adds would have reached.
     *
     * <p>Repeats are allowed and collapse, exactly as the adds did.
     * {@code count} says how much of the array to read, so a caller that
     * filtered may pass an array sized for its input.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static <A> MezzeSet<A> fromElements(Object[] elems, int count) {
        if (count <= 0) {
            return empty();
        }
        // Above BOTH flat maxima, so the tier below is always the ladder's.
        if (count <= BULK_FLOOR) {
            SetStorage out = EmptySetStorage.INSTANCE;
            for (int i = 0; i < count; i++) {
                out = out.add(elems[i]);
            }
            return new MezzeSet<>(out);
        }
        if (allLongElements(elems, count)) {
            var b = new LongTrieSetStorage.Builder();
            for (int i = 0; i < count; i++) {
                b.add((Long) elems[i]);
            }
            return new MezzeSet<>(b.build());
        }
        var b = new TrieSetStorage.Builder();
        for (int i = 0; i < count; i++) {
            b.add(elems[i]);
        }
        return new MezzeSet<>(b.build());
    }

    /**
     * The size below which adding one at a time IS the ladder's answer. The
     * flat tiers are the fastest storage at their own sizes and a builder
     * would only take the set off them, so the floor sits above the wider of
     * the two — {@link LongSetStorage#MAX_SIZE} for all-Long elements and
     * {@link SmallFlatSetStorage#MAX_SIZE} otherwise.
     */
    private static final int BULK_FLOOR =
        Math.max(LongSetStorage.MAX_SIZE, SmallFlatSetStorage.MAX_SIZE);

    /** The flat storage's own condition for promoting to the long-keyed trie. */
    private static boolean allLongElements(Object[] elems, int count) {
        for (int i = 0; i < count; i++) {
            if (!(elems[i] instanceof Long)) return false;
        }
        return true;
    }

    /**
     * ONE WALK, NO ARRAY, for the whole-set operations — the Set's twin of
     * {@code MezzeMap.walkEntries}. MEASURED there at 3.6x on the walk itself.
     */
    public void walkElems(java.util.function.Consumer<Object> fn) {
        storage.forEach(fn);
    }

    /** {@link #walkElems} that stops, for `any`, `all`, `none` and `find`. */
    public boolean walkElemsUntil(java.util.function.Predicate<Object> p) {
        return storage.forEachUntil(p);
    }

    /**
     * Creates a set of longs (unboxed).
     */
    public static MezzeSet<Long> ofLongs(long... elements) {
        if (elements.length == 0) {
            return empty();
        }
        if (elements.length == 1) {
            return new MezzeSet<>(new SingleElemSetStorage(elements[0]));
        }

        // Deduplicate
        long[] unique = new long[elements.length];
        int uniqueCount = 0;
        outer:
        for (long elem : elements) {
            for (int i = 0; i < uniqueCount; i++) {
                if (unique[i] == elem) continue outer;
            }
            unique[uniqueCount++] = elem;
        }

        if (uniqueCount == 1) {
            return new MezzeSet<>(new SingleElemSetStorage(unique[0]));
        }

        long[] finalArray = new long[uniqueCount];
        System.arraycopy(unique, 0, finalArray, 0, uniqueCount);
        return new MezzeSet<>(new LongSetStorage(finalArray));
    }

    /**
     * Creates from an existing storage.
     */
    /** The storage strategy backing this set. Callers use it to ask whether the set
     *  already buckets by an element instance (see EvidenceSetStorage) before
     *  rebuilding it under one. */
    public SetStorage storage() {
        return storage;
    }

    public static <A> MezzeSet<A> fromStorage(SetStorage storage) {
        return new MezzeSet<>(storage);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of elements.
     */
    public int size() {
        return storage.size();
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    /**
     * Returns true if not empty.
     */
    public boolean nonEmpty() {
        return !storage.isEmpty();
    }

    /**
     * True when a probe may run INSIDE compiled code — everything except
     * {@link EvidenceSetStorage}, which dispatches through guest closures. The
     * set counterpart of {@code MezzeMap.hasDirectProbe}.
     */
    public boolean hasDirectProbe() {
        return storage instanceof SingleElemSetStorage
                || storage instanceof EmptySetStorage;
    }

    /**
     * {@link #contains} with the storage type NAMED, so each branch has a
     * concrete receiver. See {@code MezzeMap.getDirect} for the measurement
     * that says both halves are needed.
     */
    public boolean containsDirect(Object elem) {
        if (storage instanceof SingleElemSetStorage s) return s.contains(elem);
        return storage.contains(elem);
    }

    /**
     * {@link #containsDirect} for a Long key, without a virtual {@code equals}
     * or {@code hashCode}: only the storages {@link #hasDirectProbe} names are
     * probed, and {@link SingleElemSetStorage#holds(long)} compares concretely.
     * Safe inside compiled code (see there); the caller routes every other
     * key type and storage through the {@code @TruffleBoundary} native.
     */
    public boolean containsDirectLong(long x) {
        if (storage instanceof SingleElemSetStorage s) return s.holds(x);
        return false; // EmptySetStorage is the only other direct-probe storage
    }

    /** The String twin of {@link #containsDirectLong}. */
    public boolean containsDirectString(String x) {
        if (storage instanceof SingleElemSetStorage s) return s.holds(x);
        return false;
    }

    /**
     * Returns true if the element is present.
     */
    public boolean contains(A elem) {
        return storage.contains(elem);
    }

    /**
     * Unboxed contains for long sets.
     *
     * <p>DEAD, AND THE TRIE IS DELIBERATELY ABSENT FROM IT. Nothing calls this
     * method: a long-keyed set reaches its unboxed probe through
     * {@code LongTrieSetStorage.containsLong} from {@code storage.contains},
     * never through here, so the missing branch cost nothing. It was found
     * while testing whether an interpreter fast path could use this method, and
     * that fast path measured NULL — see {@code InlineMapContainsKey} — so
     * closing the hole would have meant fixing code with no callers. Named
     * here as what it is instead.
     */
    public boolean containsLong(long elem) {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.containsLong(elem);
        }
        return storage.contains(elem);
    }

    // ===== UPDATE =====

    /**
     * Returns a new set with the element added.
     */
    public MezzeSet<A> add(A elem) {
        return new MezzeSet<>(storage.add(elem));
    }

    /**
     * Returns a new set with the element added (raw type).
     * For interpreter use with dynamic types.
     */
    @SuppressWarnings("unchecked")
    public MezzeSet<A> addObject(Object elem) {
        return new MezzeSet<>(storage.add(elem));
    }

    /**
     * Returns a new set with all elements from other set added.
     * Optimized: uses mutable HashSet for batch operations.
     */
    @SuppressWarnings("unchecked")
    public MezzeSet<A> addAll(MezzeSet<A> other) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;

        // THE TWO ARRAYS STAY, measured: see `MezzeMap.putAll`. A walk saves
        // the allocation and gives up `System.arraycopy`, and on `set::union`
        // that read 0.99x.
        Object[] mine = toArray();
        Object[] theirs = other.toArray();
        Object[] out = new Object[mine.length + theirs.length];
        System.arraycopy(mine, 0, out, 0, mine.length);
        System.arraycopy(theirs, 0, out, mine.length, theirs.length);
        // Shared elements appear twice and collapse, as the adds collapsed them.
        //
        // COLLAPSE BY WHOSE EQUALITY, THOUGH. The static `fromElements` buckets
        // STRUCTURALLY, so two elements a hand-written `Eq` calls equal stayed
        // apart and `set::union` answered 4 where every other operation agreed
        // the answer was 3. The element instance lives on the storage — see
        // `EvidenceSetStorage`, whose own comment says a set with evidence keeps
        // it — and `storage()` exists to be asked this exact question before a
        // rebuild. It was not being asked here.
        //
        // The defect was invisible from the result, because `contains` takes its
        // evidence from the CALL SITE: the union result still recognised a
        // differently-spelled member, so only the SIZE gave it away.
        ElementHashing hashing = hashingOf(this);
        if (hashing == null) hashing = hashingOf(other);
        if (hashing != null) {
            return fromStorage(EvidenceSetStorage.fromElements(out, hashing));
        }
        return (MezzeSet<A>) fromElements(out, out.length);
    }

    /** The element instance this set buckets by, or null when it buckets
     *  structurally. */
    private static ElementHashing hashingOf(MezzeSet<?> s) {
        return s.storage instanceof EvidenceSetStorage es ? es.hashing() : null;
    }

    /**
     * Returns a new set with the element removed.
     */
    public MezzeSet<A> remove(A elem) {
        return new MezzeSet<>(storage.remove(elem));
    }

    // ===== SET OPERATIONS =====

    /**
     * Union of two sets.
     */
    public MezzeSet<A> union(MezzeSet<A> other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Optimized path for LongSetStorage
        if (storage instanceof LongSetStorage ls && other.storage instanceof LongSetStorage otherLs) {
            return new MezzeSet<>(ls.unionLong(otherLs));
        }

        // AND THE TRIE, which is where a thousand-element Int set actually
        // lives: `LongSetStorage` caps at 32, so the sorted two-pointer merge
        // above never sees one. Without this, union fell through to `addAll`,
        // which collects BOTH sides into arrays and calls `fromElements` —
        // rehashing every element and discarding two perfectly good tries.
        //
        // MEASURED on the real structure, 1000 elements a side: that rebuild is
        // 84% of `addAll`'s work for disjoint inputs and 72% for identical
        // ones, against 6 790 ps/element merely to walk both sides. The
        // structural merge shares whole subtrees by reference and hashes
        // nothing — see `LongTrieSetStorage.unionWith`.
        if (storage instanceof LongTrieSetStorage lt
                && other.storage instanceof LongTrieSetStorage otherLt) {
            return new MezzeSet<>(LongTrieSetStorage.unionWith(lt, otherLt));
        }

        return addAll(other);
    }

    /**
     * Intersection of two sets.
     */
    public MezzeSet<A> intersect(MezzeSet<A> other) {
        if (isEmpty() || other.isEmpty()) return empty();

        // Optimized path for LongSetStorage
        if (storage instanceof LongSetStorage ls && other.storage instanceof LongSetStorage otherLs) {
            LongSetStorage result = ls.intersectLong(otherLs);
            if (result.size() == 0) return empty();
            return new MezzeSet<>(result);
        }

        MezzeSet<A> smaller = size() <= other.size() ? this : other;
        MezzeSet<A> larger = size() > other.size() ? this : other;

        int size = smaller.size();
        Object[] out = new Object[size];
        int[] n = {0};
        smaller.storage.forEach(e -> {
            if (larger.contains((A) e)) {
                out[n[0]++] = e;
            }
        });
        if (n[0] == size) return smaller;
        return (MezzeSet<A>) fromElements(out, n[0]);
    }

    /**
     * Difference (this - other).
     */
    public MezzeSet<A> difference(MezzeSet<A> other) {
        if (isEmpty()) return this;
        if (other.isEmpty()) return this;

        // Optimized path for LongSetStorage
        if (storage instanceof LongSetStorage ls && other.storage instanceof LongSetStorage otherLs) {
            LongSetStorage result = ls.differenceLong(otherLs);
            if (result.size() == 0) return empty();
            return new MezzeSet<>(result);
        }

        int size = size();
        Object[] out = new Object[size];
        int[] n = {0};
        storage.forEach(e -> {
            if (!other.contains((A) e)) {
                out[n[0]++] = e;
            }
        });
        if (n[0] == size) return this;
        return (MezzeSet<A>) fromElements(out, n[0]);
    }

    /**
     * Symmetric difference (elements in either but not both).
     */
    public MezzeSet<A> symmetricDifference(MezzeSet<A> other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // THE TRIE CANCELS STRUCTURALLY. The composition below walks the sets
        // THREE times — a union, an intersection, then a difference of the two
        // — and each of those rebuilds. `symmetricDifferenceWith` does it in
        // one pass, sharing every subtree only one side has and rehashing
        // nothing. At 26 464 686 ps for two half-overlapping thousand-element
        // sets this was the most expensive set row left.
        if (storage instanceof LongTrieSetStorage lt
                && other.storage instanceof LongTrieSetStorage otherLt) {
            LongTrieSetStorage out =
                LongTrieSetStorage.symmetricDifferenceWith(lt, otherLt);
            if (out.size() == 0) return empty();
            return new MezzeSet<>(out);
        }

        return union(other).difference(intersect(other));
    }

    /**
     * Returns true if this is a subset of other.
     */
    public boolean isSubsetOf(MezzeSet<A> other) {
        if (size() > other.size()) return false;

        for (A elem : this) {
            if (!other.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if this is a superset of other.
     */
    public boolean isSupersetOf(MezzeSet<A> other) {
        return other.isSubsetOf(this);
    }

    /**
     * Returns true if sets have no common elements.
     */
    public boolean isDisjoint(MezzeSet<A> other) {
        MezzeSet<A> smaller = size() <= other.size() ? this : other;
        MezzeSet<A> larger = size() > other.size() ? this : other;

        for (A elem : smaller) {
            if (larger.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    // ===== TRANSFORM =====

    /**
     * Maps over elements.
     *
     * <p>The {@code HashSet} this used to fill was not a bulk build: it was an
     * extra hash table, and the trie behind it was still filled one persistent
     * add at a time.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public <B> MezzeSet<B> map(Function<A, B> fn) {
        if (isEmpty()) return empty();

        int size = size();
        Object[] out = new Object[size];
        int[] n = {0};
        storage.forEach(e -> out[n[0]++] = fn.apply((A) e));
        // Two elements may map to one: they collapse, as the adds did.
        return (MezzeSet<B>) fromElements(out, size);
    }

    /**
     * Flat maps over elements.
     */
    @TruffleBoundary
    public <B> MezzeSet<B> flatMap(Function<A, MezzeSet<B>> fn) {
        if (isEmpty()) return empty();

        MezzeSet<B> result = empty();
        for (A elem : this) {
            result = result.union(fn.apply(elem));
        }
        return result;
    }

    /**
     * Filters elements by predicate.
     *
     * <p>Keeping everything still returns {@code this}: an immutable set that
     * would be rebuilt element for element is already the answer.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSet<A> filter(Predicate<A> p) {
        if (isEmpty()) return this;

        int size = size();
        Object[] out = new Object[size];
        int[] n = {0};
        storage.forEach(e -> {
            if (p.test((A) e)) {
                out[n[0]++] = e;
            }
        });
        if (n[0] == size) return this;

        return (MezzeSet<A>) fromElements(out, n[0]);
    }

    // ===== FOLD =====

    /**
     * Folds over elements.
     */
    @TruffleBoundary
    public <B> B fold(B init, java.util.function.BiFunction<B, A, B> fn) {
        B acc = init;
        for (A elem : this) {
            acc = fn.apply(acc, elem);
        }
        return acc;
    }

    // ===== SEARCH =====

    /**
     * Find first element matching predicate.
     */
    @TruffleBoundary
    public A find(Predicate<A> p) {
        for (A elem : this) {
            if (p.test(elem)) {
                return elem;
            }
        }
        return null;
    }

    /**
     * Check if any element matches predicate.
     */
    @TruffleBoundary
    public boolean exists(Predicate<A> p) {
        return find(p) != null;
    }

    /**
     * Check if all elements match predicate.
     */
    @TruffleBoundary
    public boolean forall(Predicate<A> p) {
        for (A elem : this) {
            if (!p.test(elem)) {
                return false;
            }
        }
        return true;
    }

    // ===== NUMERIC (for Long sets) =====

    /**
     * Sum of all elements (requires Long elements).
     */
    public long sumLong() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.sum();
        }
        throw new UnsupportedOperationException("sumLong requires Long elements");
    }

    /**
     * Minimum element (requires Long elements).
     */
    public long minLong() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.min();
        }
        throw new UnsupportedOperationException("minLong requires Long elements");
    }

    /**
     * Maximum element (requires Long elements).
     */
    public long maxLong() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.max();
        }
        throw new UnsupportedOperationException("maxLong requires Long elements");
    }

    // ===== ITERATION =====

    @Override
    public Iterator<A> iterator() {
        Object[] elements = storage.toArray();

        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < elements.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public A next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return (A) elements[index++];
            }
        };
    }

    /**
     * Applies function to each element for side effects.
     */
    @TruffleBoundary
    public void foreach(java.util.function.Consumer<A> fn) {
        for (A elem : this) {
            fn.accept(elem);
        }
    }

    // ===== CONVERSION =====

    /**
     * Returns all elements as array.
     */
    @SuppressWarnings("unchecked")
    public A[] toArray() {
        return (A[]) storage.toArray();
    }

    /**
     * Returns all elements as long array (for Long sets).
     */
    public long[] toLongArray() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.toLongArray();
        }
        throw new UnsupportedOperationException("toLongArray requires Long elements");
    }

    // ===== INTERNAL =====

    /**
     * Returns the storage (for internal use).
     */
    public SetStorage getStorage() {
        return storage;
    }

    /**
     * Returns the storage strategy tag.
     */
    public byte getStrategyTag() {
        return storage.getStrategyTag();
    }

    /**
     * Returns true if using long storage.
     */
    public boolean isLongStorage() {
        return storage instanceof LongSetStorage;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    long getArraySize() {
        return storage.size();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < storage.size();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readArrayElement(long index) {
        return storage.toArray()[(int) index];
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    void writeArrayElement(@SuppressWarnings("unused") long index, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
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
        return new SetIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SetIterator implements TruffleObject {
        private final Iterator<?> iterator;

        SetIterator(Iterator<?> iterator) {
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

    // ===== OBJECT METHODS =====

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (A elem : this) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(elem);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSet<?> other)) return false;
        if (size() != other.size()) return false;

        MezzeSet<A> otherCast = (MezzeSet<A>) other;
        for (A elem : this) {
            if (!otherCast.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (A elem : this) {
            result += Objects.hashCode(elem);
        }
        return result;
    }
}
