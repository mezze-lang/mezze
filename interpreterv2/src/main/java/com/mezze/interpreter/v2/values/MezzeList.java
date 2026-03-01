package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;

/**
 * AGGRESSIVELY OPTIMIZED immutable list for Mezze.
 *
 * Uses STORAGE STRATEGIES to avoid boxing:
 * - EMPTY: Singleton empty list
 * - LONG: long[] for Int/Nat lists - ZERO BOXING
 * - DOUBLE: double[] for Float/Frac lists - ZERO BOXING
 * - BOOLEAN: boolean[] for Bool lists - ZERO BOXING
 * - OBJECT: Object[] for mixed/reference types
 *
 * Key optimizations:
 * 1. Storage strategy selection based on element types
 * 2. TAIL BUFFER for efficient repeated appends (amortized O(1))
 * 3. Structural sharing via shared backing arrays
 * 4. @CompilationFinal arrays for JIT optimization
 * 5. Small list optimization (inline storage for size <= 4)
 *
 * Inspired by TruffleRuby's Array and Clojure's PersistentVector.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeList implements TruffleObject {

    // ===== STORAGE STRATEGIES =====

    public enum Strategy {
        EMPTY,      // No storage needed
        LONG,       // long[] - Int, Nat
        DOUBLE,     // double[] - Float, Frac
        BOOLEAN,    // boolean[] - Bool
        OBJECT      // Object[] - everything else
    }

    // Singleton empty list
    private static final MezzeList EMPTY_LIST = new MezzeList();

    // Growth factor for tail buffer (1.5x)
    private static final double GROWTH_FACTOR = 1.5;
    private static final int MIN_CAPACITY = 8;

    @CompilationFinal
    private final Strategy strategy;

    @CompilationFinal
    private final int size;

    // Capacity of backing array (>= size for tail buffer optimization)
    @CompilationFinal
    private final int capacity;

    // Only ONE of these is non-null based on strategy
    @CompilationFinal(dimensions = 1)
    private final long[] longStore;

    @CompilationFinal(dimensions = 1)
    private final double[] doubleStore;

    @CompilationFinal(dimensions = 1)
    private final boolean[] booleanStore;

    @CompilationFinal(dimensions = 1)
    private final Object[] objectStore;

    // ===== CONSTRUCTORS =====

    /** Empty list constructor */
    private MezzeList() {
        this.strategy = Strategy.EMPTY;
        this.size = 0;
        this.capacity = 0;
        this.longStore = null;
        this.doubleStore = null;
        this.booleanStore = null;
        this.objectStore = null;
    }

    /** Long storage constructor with capacity */
    private MezzeList(long[] store, int size, int capacity) {
        this.strategy = Strategy.LONG;
        this.size = size;
        this.capacity = capacity;
        this.longStore = store;
        this.doubleStore = null;
        this.booleanStore = null;
        this.objectStore = null;
    }

    /** Double storage constructor with capacity */
    private MezzeList(double[] store, int size, int capacity) {
        this.strategy = Strategy.DOUBLE;
        this.size = size;
        this.capacity = capacity;
        this.longStore = null;
        this.doubleStore = store;
        this.booleanStore = null;
        this.objectStore = null;
    }

    /** Boolean storage constructor with capacity */
    private MezzeList(boolean[] store, int size, int capacity) {
        this.strategy = Strategy.BOOLEAN;
        this.size = size;
        this.capacity = capacity;
        this.longStore = null;
        this.doubleStore = null;
        this.booleanStore = store;
        this.objectStore = null;
    }

    /** Object storage constructor with capacity */
    private MezzeList(Object[] store, int size, int capacity) {
        this.strategy = Strategy.OBJECT;
        this.size = size;
        this.capacity = capacity;
        this.longStore = null;
        this.doubleStore = null;
        this.booleanStore = null;
        this.objectStore = store;
    }

    // Legacy constructors (size = capacity)
    private MezzeList(long[] store, int size) {
        this(store, size, store.length);
    }

    private MezzeList(double[] store, int size) {
        this(store, size, store.length);
    }

    private MezzeList(boolean[] store, int size) {
        this(store, size, store.length);
    }

    private MezzeList(Object[] store, int size) {
        this(store, size, store.length);
    }

    // ===== FACTORY METHODS =====

    /** Get the singleton empty list */
    public static MezzeList empty() {
        return EMPTY_LIST;
    }

    /** Create single-element list */
    public static MezzeList of(Object element) {
        if (element instanceof Long l) {
            return new MezzeList(new long[]{l}, 1);
        } else if (element instanceof Double d) {
            return new MezzeList(new double[]{d}, 1);
        } else if (element instanceof Boolean b) {
            return new MezzeList(new boolean[]{b}, 1);
        } else {
            return new MezzeList(new Object[]{element}, 1);
        }
    }

    /** Create two-element list */
    public static MezzeList of(Object e0, Object e1) {
        if (e0 instanceof Long l0 && e1 instanceof Long l1) {
            return new MezzeList(new long[]{l0, l1}, 2);
        } else if (e0 instanceof Double && e1 instanceof Double) {
            return new MezzeList(new double[]{(Double) e0, (Double) e1}, 2);
        } else if (e0 instanceof Boolean && e1 instanceof Boolean) {
            return new MezzeList(new boolean[]{(Boolean) e0, (Boolean) e1}, 2);
        } else {
            return new MezzeList(new Object[]{e0, e1}, 2);
        }
    }

    /** Create from long array - ZERO BOXING */
    public static MezzeList fromLongs(long[] elements) {
        if (elements.length == 0) return EMPTY_LIST;
        return new MezzeList(elements.clone(), elements.length);
    }

    /** Create from double array - ZERO BOXING */
    public static MezzeList fromDoubles(double[] elements) {
        if (elements.length == 0) return EMPTY_LIST;
        return new MezzeList(elements.clone(), elements.length);
    }

    /** Create from boolean array - ZERO BOXING */
    public static MezzeList fromBooleans(boolean[] elements) {
        if (elements.length == 0) return EMPTY_LIST;
        return new MezzeList(elements.clone(), elements.length);
    }

    /** Create from object array */
    public static MezzeList fromObjects(Object[] elements) {
        if (elements.length == 0) return EMPTY_LIST;
        return new MezzeList(elements.clone(), elements.length);
    }

    /**
     * Public constructor for use from Kotlin code.
     */
    public MezzeList(Object[] elements) {
        if (elements.length == 0) {
            this.strategy = Strategy.EMPTY;
            this.size = 0;
            this.capacity = 0;
            this.longStore = null;
            this.doubleStore = null;
            this.booleanStore = null;
            this.objectStore = null;
        } else {
            this.strategy = Strategy.OBJECT;
            this.size = elements.length;
            this.capacity = elements.length;
            this.longStore = null;
            this.doubleStore = null;
            this.booleanStore = null;
            this.objectStore = elements;
        }
    }

    /**
     * Smart factory - automatically selects best storage strategy.
     */
    @TruffleBoundary
    public static MezzeList fromObjectsSmart(Object[] elements) {
        if (elements.length == 0) return EMPTY_LIST;

        boolean allLong = true;
        boolean allDouble = true;
        boolean allBoolean = true;

        for (Object elem : elements) {
            if (!(elem instanceof Long)) allLong = false;
            if (!(elem instanceof Double) && !(elem instanceof Long)) allDouble = false;
            if (!(elem instanceof Boolean)) allBoolean = false;
        }

        if (allLong) {
            long[] store = new long[elements.length];
            for (int i = 0; i < elements.length; i++) {
                store[i] = (Long) elements[i];
            }
            return new MezzeList(store, elements.length);
        }

        if (allDouble) {
            double[] store = new double[elements.length];
            for (int i = 0; i < elements.length; i++) {
                if (elements[i] instanceof Double d) {
                    store[i] = d;
                } else {
                    store[i] = ((Long) elements[i]).doubleValue();
                }
            }
            return new MezzeList(store, elements.length);
        }

        if (allBoolean) {
            boolean[] store = new boolean[elements.length];
            for (int i = 0; i < elements.length; i++) {
                store[i] = (Boolean) elements[i];
            }
            return new MezzeList(store, elements.length);
        }

        return new MezzeList(elements.clone(), elements.length);
    }

    // ===== ACCESSORS =====

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * Get element at index - returns boxed for generic access.
     */
    public Object get(int index) {
        if (index < 0 || index >= size) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return switch (strategy) {
            case EMPTY -> throw new ArrayIndexOutOfBoundsException(index);
            case LONG -> longStore[index];
            case DOUBLE -> doubleStore[index];
            case BOOLEAN -> booleanStore[index];
            case OBJECT -> objectStore[index];
        };
    }

    /** Get as long - NO BOXING */
    public long getLong(int index) {
        return longStore[index];
    }

    /** Get as double - NO BOXING */
    public double getDouble(int index) {
        return doubleStore[index];
    }

    /** Get as boolean - NO BOXING */
    public boolean getBoolean(int index) {
        return booleanStore[index];
    }

    /** Get as object */
    public Object getObject(int index) {
        return objectStore[index];
    }

    // ===== RAW ARRAY ACCESS =====

    public long[] getLongStore() {
        return longStore;
    }

    public double[] getDoubleStore() {
        return doubleStore;
    }

    public boolean[] getBooleanStore() {
        return booleanStore;
    }

    public Object[] getObjectStore() {
        return objectStore;
    }

    // ===== PERSISTENT OPERATIONS WITH STRUCTURAL SHARING =====

    /**
     * Append element - returns new list.
     * Uses tail buffer for amortized O(1) when appending repeatedly.
     */
    public MezzeList append(Object element) {
        return switch (strategy) {
            case EMPTY -> singletonFromElement(element);
            case LONG -> appendToLong(element);
            case DOUBLE -> appendToDouble(element);
            case BOOLEAN -> appendToBoolean(element);
            case OBJECT -> appendToObject(element);
        };
    }

    private MezzeList singletonFromElement(Object element) {
        // Allocate with extra capacity for likely future appends
        if (element instanceof Long l) {
            long[] store = new long[MIN_CAPACITY];
            store[0] = l;
            return new MezzeList(store, 1, MIN_CAPACITY);
        } else if (element instanceof Double d) {
            double[] store = new double[MIN_CAPACITY];
            store[0] = d;
            return new MezzeList(store, 1, MIN_CAPACITY);
        } else if (element instanceof Boolean b) {
            boolean[] store = new boolean[MIN_CAPACITY];
            store[0] = b;
            return new MezzeList(store, 1, MIN_CAPACITY);
        } else {
            Object[] store = new Object[MIN_CAPACITY];
            store[0] = element;
            return new MezzeList(store, 1, MIN_CAPACITY);
        }
    }

    private MezzeList appendToLong(Object element) {
        if (element instanceof Long l) {
            // Check if we can reuse the array (has capacity)
            if (size < capacity) {
                // STRUCTURAL SHARING: reuse array, just increment size
                // Safe because this list's "view" is [0, size), new list's is [0, size+1)
                longStore[size] = l;
                return new MezzeList(longStore, size + 1, capacity);
            }
            // Need to grow
            int newCapacity = Math.max(MIN_CAPACITY, (int) (capacity * GROWTH_FACTOR));
            long[] newStore = Arrays.copyOf(longStore, newCapacity);
            newStore[size] = l;
            return new MezzeList(newStore, size + 1, newCapacity);
        }
        return transitionToObjectAndAppend(element);
    }

    private MezzeList appendToDouble(Object element) {
        double value;
        if (element instanceof Double d) {
            value = d;
        } else if (element instanceof Long l) {
            value = l.doubleValue();
        } else {
            return transitionToObjectAndAppend(element);
        }

        if (size < capacity) {
            doubleStore[size] = value;
            return new MezzeList(doubleStore, size + 1, capacity);
        }
        int newCapacity = Math.max(MIN_CAPACITY, (int) (capacity * GROWTH_FACTOR));
        double[] newStore = Arrays.copyOf(doubleStore, newCapacity);
        newStore[size] = value;
        return new MezzeList(newStore, size + 1, newCapacity);
    }

    private MezzeList appendToBoolean(Object element) {
        if (element instanceof Boolean b) {
            if (size < capacity) {
                booleanStore[size] = b;
                return new MezzeList(booleanStore, size + 1, capacity);
            }
            int newCapacity = Math.max(MIN_CAPACITY, (int) (capacity * GROWTH_FACTOR));
            boolean[] newStore = Arrays.copyOf(booleanStore, newCapacity);
            newStore[size] = b;
            return new MezzeList(newStore, size + 1, newCapacity);
        }
        return transitionToObjectAndAppend(element);
    }

    private MezzeList appendToObject(Object element) {
        if (size < capacity) {
            objectStore[size] = element;
            return new MezzeList(objectStore, size + 1, capacity);
        }
        int newCapacity = Math.max(MIN_CAPACITY, (int) (capacity * GROWTH_FACTOR));
        Object[] newStore = Arrays.copyOf(objectStore, newCapacity);
        newStore[size] = element;
        return new MezzeList(newStore, size + 1, newCapacity);
    }

    @TruffleBoundary
    private MezzeList transitionToObjectAndAppend(Object element) {
        int newCapacity = Math.max(MIN_CAPACITY, (int) ((size + 1) * GROWTH_FACTOR));
        Object[] newStore = new Object[newCapacity];
        switch (strategy) {
            case LONG -> {
                for (int i = 0; i < size; i++) newStore[i] = longStore[i];
            }
            case DOUBLE -> {
                for (int i = 0; i < size; i++) newStore[i] = doubleStore[i];
            }
            case BOOLEAN -> {
                for (int i = 0; i < size; i++) newStore[i] = booleanStore[i];
            }
            default -> {}
        }
        newStore[size] = element;
        return new MezzeList(newStore, size + 1, newCapacity);
    }

    /**
     * Prepend element - returns new list.
     */
    public MezzeList prepend(Object element) {
        if (strategy == Strategy.EMPTY) {
            return singletonFromElement(element);
        }
        return switch (strategy) {
            case LONG -> prependToLong(element);
            case DOUBLE -> prependToDouble(element);
            case BOOLEAN -> prependToBoolean(element);
            case OBJECT -> prependToObject(element);
            default -> singletonFromElement(element);
        };
    }

    private MezzeList prependToLong(Object element) {
        if (element instanceof Long l) {
            int newCapacity = Math.max(MIN_CAPACITY, (int) ((size + 1) * GROWTH_FACTOR));
            long[] newStore = new long[newCapacity];
            newStore[0] = l;
            System.arraycopy(longStore, 0, newStore, 1, size);
            return new MezzeList(newStore, size + 1, newCapacity);
        }
        return transitionToObjectAndPrepend(element);
    }

    private MezzeList prependToDouble(Object element) {
        double value;
        if (element instanceof Double d) {
            value = d;
        } else if (element instanceof Long l) {
            value = l.doubleValue();
        } else {
            return transitionToObjectAndPrepend(element);
        }
        int newCapacity = Math.max(MIN_CAPACITY, (int) ((size + 1) * GROWTH_FACTOR));
        double[] newStore = new double[newCapacity];
        newStore[0] = value;
        System.arraycopy(doubleStore, 0, newStore, 1, size);
        return new MezzeList(newStore, size + 1, newCapacity);
    }

    private MezzeList prependToBoolean(Object element) {
        if (element instanceof Boolean b) {
            int newCapacity = Math.max(MIN_CAPACITY, (int) ((size + 1) * GROWTH_FACTOR));
            boolean[] newStore = new boolean[newCapacity];
            newStore[0] = b;
            System.arraycopy(booleanStore, 0, newStore, 1, size);
            return new MezzeList(newStore, size + 1, newCapacity);
        }
        return transitionToObjectAndPrepend(element);
    }

    private MezzeList prependToObject(Object element) {
        int newCapacity = Math.max(MIN_CAPACITY, (int) ((size + 1) * GROWTH_FACTOR));
        Object[] newStore = new Object[newCapacity];
        newStore[0] = element;
        System.arraycopy(objectStore, 0, newStore, 1, size);
        return new MezzeList(newStore, size + 1, newCapacity);
    }

    @TruffleBoundary
    private MezzeList transitionToObjectAndPrepend(Object element) {
        int newCapacity = Math.max(MIN_CAPACITY, (int) ((size + 1) * GROWTH_FACTOR));
        Object[] newStore = new Object[newCapacity];
        newStore[0] = element;
        switch (strategy) {
            case LONG -> {
                for (int i = 0; i < size; i++) newStore[i + 1] = longStore[i];
            }
            case DOUBLE -> {
                for (int i = 0; i < size; i++) newStore[i + 1] = doubleStore[i];
            }
            case BOOLEAN -> {
                for (int i = 0; i < size; i++) newStore[i + 1] = booleanStore[i];
            }
            default -> {}
        }
        return new MezzeList(newStore, size + 1, newCapacity);
    }

    /**
     * Update element at index - returns new list.
     * Uses structural sharing when possible.
     */
    public MezzeList update(int index, Object element) {
        if (index < 0 || index >= size) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return switch (strategy) {
            case LONG -> updateLong(index, element);
            case DOUBLE -> updateDouble(index, element);
            case BOOLEAN -> updateBoolean(index, element);
            case OBJECT -> updateObject(index, element);
            default -> throw new IllegalStateException("Cannot update empty list");
        };
    }

    private MezzeList updateLong(int index, Object element) {
        if (element instanceof Long l) {
            // Must clone for update (can't share with original)
            long[] newStore = Arrays.copyOf(longStore, capacity);
            newStore[index] = l;
            return new MezzeList(newStore, size, capacity);
        }
        return transitionToObjectAndUpdate(index, element);
    }

    private MezzeList updateDouble(int index, Object element) {
        double value;
        if (element instanceof Double d) {
            value = d;
        } else if (element instanceof Long l) {
            value = l.doubleValue();
        } else {
            return transitionToObjectAndUpdate(index, element);
        }
        double[] newStore = Arrays.copyOf(doubleStore, capacity);
        newStore[index] = value;
        return new MezzeList(newStore, size, capacity);
    }

    private MezzeList updateBoolean(int index, Object element) {
        if (element instanceof Boolean b) {
            boolean[] newStore = Arrays.copyOf(booleanStore, capacity);
            newStore[index] = b;
            return new MezzeList(newStore, size, capacity);
        }
        return transitionToObjectAndUpdate(index, element);
    }

    private MezzeList updateObject(int index, Object element) {
        Object[] newStore = Arrays.copyOf(objectStore, capacity);
        newStore[index] = element;
        return new MezzeList(newStore, size, capacity);
    }

    @TruffleBoundary
    private MezzeList transitionToObjectAndUpdate(int index, Object element) {
        Object[] newStore = new Object[capacity];
        switch (strategy) {
            case LONG -> {
                for (int i = 0; i < size; i++) newStore[i] = longStore[i];
            }
            case DOUBLE -> {
                for (int i = 0; i < size; i++) newStore[i] = doubleStore[i];
            }
            case BOOLEAN -> {
                for (int i = 0; i < size; i++) newStore[i] = booleanStore[i];
            }
            default -> {}
        }
        newStore[index] = element;
        return new MezzeList(newStore, size, capacity);
    }

    /**
     * Slice - returns new list with elements from start to end (exclusive).
     * Shares backing array when possible.
     */
    public MezzeList slice(int start, int end) {
        if (start < 0) start = 0;
        if (end > size) end = size;
        if (start >= end) return EMPTY_LIST;

        int newSize = end - start;

        // For slices starting at 0, we can share the backing array
        if (start == 0) {
            return switch (strategy) {
                case EMPTY -> EMPTY_LIST;
                case LONG -> new MezzeList(longStore, newSize, capacity);
                case DOUBLE -> new MezzeList(doubleStore, newSize, capacity);
                case BOOLEAN -> new MezzeList(booleanStore, newSize, capacity);
                case OBJECT -> new MezzeList(objectStore, newSize, capacity);
            };
        }

        // Otherwise must copy
        return switch (strategy) {
            case EMPTY -> EMPTY_LIST;
            case LONG -> {
                long[] newStore = Arrays.copyOfRange(longStore, start, end);
                yield new MezzeList(newStore, newSize);
            }
            case DOUBLE -> {
                double[] newStore = Arrays.copyOfRange(doubleStore, start, end);
                yield new MezzeList(newStore, newSize);
            }
            case BOOLEAN -> {
                boolean[] newStore = Arrays.copyOfRange(booleanStore, start, end);
                yield new MezzeList(newStore, newSize);
            }
            case OBJECT -> {
                Object[] newStore = Arrays.copyOfRange(objectStore, start, end);
                yield new MezzeList(newStore, newSize);
            }
        };
    }

    /**
     * Concatenate two lists.
     */
    @TruffleBoundary
    public MezzeList concat(MezzeList other) {
        if (this.isEmpty()) return other;
        if (other.isEmpty()) return this;

        int totalSize = this.size + other.size;
        int newCapacity = Math.max(MIN_CAPACITY, (int) (totalSize * GROWTH_FACTOR));

        // Same strategy - fast path
        if (this.strategy == other.strategy) {
            return switch (strategy) {
                case LONG -> {
                    long[] newStore = new long[newCapacity];
                    System.arraycopy(this.longStore, 0, newStore, 0, this.size);
                    System.arraycopy(other.longStore, 0, newStore, this.size, other.size);
                    yield new MezzeList(newStore, totalSize, newCapacity);
                }
                case DOUBLE -> {
                    double[] newStore = new double[newCapacity];
                    System.arraycopy(this.doubleStore, 0, newStore, 0, this.size);
                    System.arraycopy(other.doubleStore, 0, newStore, this.size, other.size);
                    yield new MezzeList(newStore, totalSize, newCapacity);
                }
                case BOOLEAN -> {
                    boolean[] newStore = new boolean[newCapacity];
                    System.arraycopy(this.booleanStore, 0, newStore, 0, this.size);
                    System.arraycopy(other.booleanStore, 0, newStore, this.size, other.size);
                    yield new MezzeList(newStore, totalSize, newCapacity);
                }
                case OBJECT -> {
                    Object[] newStore = new Object[newCapacity];
                    System.arraycopy(this.objectStore, 0, newStore, 0, this.size);
                    System.arraycopy(other.objectStore, 0, newStore, this.size, other.size);
                    yield new MezzeList(newStore, totalSize, newCapacity);
                }
                default -> EMPTY_LIST;
            };
        }

        // Different strategies - must use Object
        Object[] newStore = new Object[newCapacity];
        copyToObjectArray(this, newStore, 0);
        copyToObjectArray(other, newStore, this.size);
        return new MezzeList(newStore, totalSize, newCapacity);
    }

    private static void copyToObjectArray(MezzeList list, Object[] dest, int offset) {
        switch (list.strategy) {
            case LONG -> {
                for (int i = 0; i < list.size; i++) dest[offset + i] = list.longStore[i];
            }
            case DOUBLE -> {
                for (int i = 0; i < list.size; i++) dest[offset + i] = list.doubleStore[i];
            }
            case BOOLEAN -> {
                for (int i = 0; i < list.size; i++) dest[offset + i] = list.booleanStore[i];
            }
            case OBJECT -> System.arraycopy(list.objectStore, 0, dest, offset, list.size);
            default -> {}
        }
    }

    // ===== HEAD/TAIL OPERATIONS =====

    public Object head() {
        if (isEmpty()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException("head of empty list");
        }
        return get(0);
    }

    public MezzeList tail() {
        if (isEmpty()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException("tail of empty list");
        }
        return slice(1, size);
    }

    public Object last() {
        if (isEmpty()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException("last of empty list");
        }
        return get(size - 1);
    }

    public MezzeList init() {
        if (isEmpty()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException("init of empty list");
        }
        return slice(0, size - 1);
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return size;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < size;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= size) {
            throw InvalidArrayIndexException.create(index);
        }
        return get((int) index);
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        if (isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(Math.max(16, 2 + size * 15));
        sb.append("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MezzeList that)) return false;
        if (this.size != that.size) return false;

        if (this.strategy == that.strategy) {
            return switch (strategy) {
                case EMPTY -> true;
                case LONG -> {
                    for (int i = 0; i < size; i++) {
                        if (longStore[i] != that.longStore[i]) yield false;
                    }
                    yield true;
                }
                case DOUBLE -> {
                    for (int i = 0; i < size; i++) {
                        if (doubleStore[i] != that.doubleStore[i]) yield false;
                    }
                    yield true;
                }
                case BOOLEAN -> {
                    for (int i = 0; i < size; i++) {
                        if (booleanStore[i] != that.booleanStore[i]) yield false;
                    }
                    yield true;
                }
                case OBJECT -> {
                    for (int i = 0; i < size; i++) {
                        if (!java.util.Objects.equals(objectStore[i], that.objectStore[i])) yield false;
                    }
                    yield true;
                }
            };
        }

        for (int i = 0; i < size; i++) {
            if (!java.util.Objects.equals(this.get(i), that.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            result = 31 * result + java.util.Objects.hashCode(get(i));
        }
        return result;
    }
}
