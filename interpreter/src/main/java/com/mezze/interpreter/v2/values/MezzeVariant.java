package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Optimized variant representation for Mezze v2.
 *
 * Uses integer tags instead of string constructor names:
 * - tag: int identifying the constructor (0, 1, 2, ...)
 * - payload: Object[] containing the variant's payload fields
 *
 * The TagRegistry maps constructor names to tags at compile time.
 * Pattern matching uses switch(tag) instead of string comparison.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeVariant implements TruffleObject {

    // Shared empty payload to avoid allocation
    private static final Object[] EMPTY_PAYLOAD = new Object[0];

    // Cached member arrays for interop (avoid repeated allocations)
    private static final String[] MEMBERS_NO_PAYLOAD = new String[] { "constructor" };
    private static final String[] MEMBERS_SINGLE_PAYLOAD = new String[] { "constructor", "value" };

    // Cached field name arrays for common wrapper records
    private static final String[] VAL_FIELD = new String[] { "val" };
    private static final String[] ERROR_FIELD = new String[] { "error" };

    // Cached None instance - singleton since it has no payload
    private static volatile MezzeVariant CACHED_NONE = null;

    /**
     * TagRegistry defines the mapping from constructor names to integer tags.
     * Shared across all variants of the same type.
     *
     * OPTIMIZED: Uses both array (for tag→name) and HashMap (for name→tag)
     * to achieve O(1) lookups in both directions.
     */
    public static final class TagRegistry {
        // Global registry for dynamic tag allocation
        private static final TagRegistry GLOBAL = new TagRegistry();

        @CompilationFinal(dimensions = 1)
        private String[] constructorNames;

        @CompilationFinal
        private int tagCount;

        // Pre-computed HashMap for O(1) name→tag lookup
        private final java.util.concurrent.ConcurrentHashMap<String, Integer> nameToTagMap;

        // Dynamic list for global registry
        private final java.util.List<String> dynamicNames;

        public TagRegistry(String[] constructorNames) {
            this.constructorNames = constructorNames;
            this.tagCount = constructorNames.length;
            this.nameToTagMap = new java.util.concurrent.ConcurrentHashMap<>(tagCount);
            this.dynamicNames = null;

            // Pre-compute the name→tag map for O(1) lookups
            for (int i = 0; i < tagCount; i++) {
                nameToTagMap.put(constructorNames[i], i);
            }
        }

        // Private constructor for global registry
        private TagRegistry() {
            this.constructorNames = new String[0];
            this.tagCount = 0;
            this.nameToTagMap = new java.util.concurrent.ConcurrentHashMap<>();
            this.dynamicNames = new java.util.concurrent.CopyOnWriteArrayList<>();
        }

        /**
         * Get the global registry for dynamic tag allocation.
         */
        public static TagRegistry getGlobal() {
            return GLOBAL;
        }

        public int getTagCount() {
            return tagCount;
        }

        public String getConstructorName(int tag) {
            if (dynamicNames != null) {
                return dynamicNames.get(tag);
            }
            return constructorNames[tag];
        }

        /**
         * Find tag by constructor name - O(1) via HashMap.
         * Still @TruffleBoundary because HashMap.get() is not JIT-friendly,
         * but now O(1) instead of O(n) linear scan.
         */
        @TruffleBoundary
        public int findTag(String constructorName) {
            Integer tag = nameToTagMap.get(constructorName);
            return tag != null ? tag : -1;
        }

        /**
         * Get or create a tag for a constructor name.
         * Used for dynamic tag allocation in the global registry.
         */
        @TruffleBoundary
        public int getOrCreateTag(String constructorName) {
            Integer existing = nameToTagMap.get(constructorName);
            if (existing != null) {
                return existing;
            }

            // Only global registry supports dynamic allocation
            if (dynamicNames == null) {
                throw new IllegalStateException("Cannot dynamically allocate tags in non-global registry");
            }

            synchronized (this) {
                // Double-check under lock
                existing = nameToTagMap.get(constructorName);
                if (existing != null) {
                    return existing;
                }

                int newTag = dynamicNames.size();
                dynamicNames.add(constructorName);
                nameToTagMap.put(constructorName, newTag);
                tagCount = dynamicNames.size();
                return newTag;
            }
        }

        /**
         * Check if a constructor name exists - O(1).
         */
        @TruffleBoundary
        public boolean hasConstructor(String constructorName) {
            return nameToTagMap.containsKey(constructorName);
        }
    }

    private final TagRegistry registry;
    private final int tag;

    @CompilationFinal(dimensions = 1)
    private final Object[] payload;

    @CompilationFinal
    private final int payloadLength;

    public MezzeVariant(TagRegistry registry, int tag, Object[] payload) {
        this.registry = registry;
        this.tag = tag;
        this.payload = payload != null && payload.length > 0 ? payload : EMPTY_PAYLOAD;
        this.payloadLength = this.payload.length;
    }

    /**
     * Create a variant with no payload (e.g., None, Nil).
     */
    public static MezzeVariant noPayload(TagRegistry registry, int tag) {
        return new MezzeVariant(registry, tag, EMPTY_PAYLOAD);
    }

    /**
     * Create a variant with a single value payload (e.g., Some(x)).
     */
    public static MezzeVariant singlePayload(TagRegistry registry, int tag, Object value) {
        return new MezzeVariant(registry, tag, new Object[] { value });
    }

    // ==========================================
    // Option type helpers (Some/None)
    // Uses GLOBAL registry for consistency with pattern matching
    // ==========================================

    /**
     * Create a None variant.
     * Uses global registry for consistent tag assignment with pattern matching.
     * OPTIMIZED: Returns cached singleton since None has no payload.
     */
    public static MezzeVariant none() {
        MezzeVariant cached = CACHED_NONE;
        if (cached != null) {
            return cached;
        }
        // First call - create and cache
        int tag = TagRegistry.getGlobal().getOrCreateTag("None");
        cached = noPayload(TagRegistry.getGlobal(), tag);
        CACHED_NONE = cached;
        return cached;
    }

    /**
     * Create a Some variant with a value wrapped in { val = value }.
     * Uses global registry for consistent tag assignment with pattern matching.
     * OPTIMIZED: Uses cached field name array.
     */
    public static MezzeVariant some(Object value) {
        int tag = TagRegistry.getGlobal().getOrCreateTag("Some");
        // Use cached VAL_FIELD array instead of allocating new String[]
        MezzeRecord wrapped = MezzeRecord.fromFields(VAL_FIELD, new Object[]{value});
        return singlePayload(TagRegistry.getGlobal(), tag, wrapped);
    }

    /**
     * Check if this is a Some variant.
     */
    public boolean isSome() {
        return "Some".equals(getConstructorName());
    }

    /**
     * Check if this is a None variant.
     */
    public boolean isNone() {
        return "None".equals(getConstructorName());
    }

    // ==========================================
    // Result type helpers (Ok/Err)
    // Uses GLOBAL registry for consistency with pattern matching
    // ==========================================

    /**
     * Create an Ok variant.
     * Uses global registry for consistent tag assignment with pattern matching.
     * OPTIMIZED: Uses cached field name array.
     */
    public static MezzeVariant ok(Object value) {
        int tag = TagRegistry.getGlobal().getOrCreateTag("Ok");
        // Use cached VAL_FIELD array instead of allocating new String[]
        MezzeRecord wrapped = MezzeRecord.fromFields(VAL_FIELD, new Object[]{value});
        return singlePayload(TagRegistry.getGlobal(), tag, wrapped);
    }

    /**
     * Create an Err variant.
     * Uses global registry for consistent tag assignment with pattern matching.
     * OPTIMIZED: Uses cached field name array.
     */
    public static MezzeVariant err(Object error) {
        int tag = TagRegistry.getGlobal().getOrCreateTag("Err");
        // Use cached ERROR_FIELD array instead of allocating new String[]
        MezzeRecord wrapped = MezzeRecord.fromFields(ERROR_FIELD, new Object[]{error});
        return singlePayload(TagRegistry.getGlobal(), tag, wrapped);
    }

    // ==================== Ordering Variants ====================

    // Cached singletons for Ordering variants (no payload)
    private static volatile MezzeVariant CACHED_LT = null;
    private static volatile MezzeVariant CACHED_GT = null;
    private static volatile MezzeVariant CACHED_EQ = null;

    /**
     * Create an LT (less than) ordering variant.
     * OPTIMIZED: Returns cached singleton.
     */
    public static MezzeVariant lt() {
        MezzeVariant cached = CACHED_LT;
        if (cached != null) {
            return cached;
        }
        int tag = TagRegistry.getGlobal().getOrCreateTag("LT");
        MezzeVariant variant = noPayload(TagRegistry.getGlobal(), tag);
        CACHED_LT = variant;
        return variant;
    }

    /**
     * Create a GT (greater than) ordering variant.
     * OPTIMIZED: Returns cached singleton.
     */
    public static MezzeVariant gt() {
        MezzeVariant cached = CACHED_GT;
        if (cached != null) {
            return cached;
        }
        int tag = TagRegistry.getGlobal().getOrCreateTag("GT");
        MezzeVariant variant = noPayload(TagRegistry.getGlobal(), tag);
        CACHED_GT = variant;
        return variant;
    }

    /**
     * Create an EQ (equal) ordering variant.
     * OPTIMIZED: Returns cached singleton.
     */
    public static MezzeVariant eq() {
        MezzeVariant cached = CACHED_EQ;
        if (cached != null) {
            return cached;
        }
        int tag = TagRegistry.getGlobal().getOrCreateTag("EQ");
        MezzeVariant variant = noPayload(TagRegistry.getGlobal(), tag);
        CACHED_EQ = variant;
        return variant;
    }

    public TagRegistry getRegistry() {
        return registry;
    }

    public int getTag() {
        return tag;
    }

    public String getConstructorName() {
        return registry.getConstructorName(tag);
    }

    public Object[] getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public boolean hasPayload() {
        return payloadLength > 0;
    }

    public Object getPayloadValue() {
        return payloadLength > 0 ? payload[0] : null;
    }

    public Object getPayloadField(int index) {
        return payload[index];
    }

    // InteropLibrary exports
    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(boolean includeInternal) {
        if (payloadLength == 0) {
            return MEMBERS_NO_PAYLOAD;  // Cached singleton
        } else if (payloadLength == 1) {
            return MEMBERS_SINGLE_PAYLOAD;  // Cached singleton
        } else {
            // Multi-payload variants are rare, allocation is acceptable
            String[] members = new String[payloadLength + 1];
            members[0] = "constructor";
            for (int i = 0; i < payloadLength; i++) {
                members[i + 1] = "field" + i;
            }
            return members;
        }
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        if ("constructor".equals(member)) return true;
        if ("value".equals(member) && payloadLength == 1) return true;
        if (member.startsWith("field")) {
            try {
                int idx = Integer.parseInt(member.substring(5));
                return idx >= 0 && idx < payloadLength;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) {
        if ("constructor".equals(member)) return getConstructorName();
        if ("value".equals(member) && payloadLength == 1) return payload[0];
        if (member.startsWith("field")) {
            int idx = Integer.parseInt(member.substring(5));
            return payload[idx];
        }
        return null;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        String name = getConstructorName();
        if (payloadLength == 0) {
            return name;
        } else if (payloadLength == 1) {
            return name + "(" + payload[0] + ")";
        } else {
            // Pre-size: name + "(" + (payloadLength * ~15 chars) + ")"
            StringBuilder sb = new StringBuilder(Math.max(16, name.length() + 2 + payloadLength * 15));
            sb.append(name);
            sb.append("(");
            for (int i = 0; i < payloadLength; i++) {
                if (i > 0) sb.append(", ");
                sb.append(payload[i]);
            }
            sb.append(")");
            return sb.toString();
        }
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MezzeVariant that)) return false;

        if (tag != that.tag) return false;
        if (payloadLength != that.payloadLength) return false;

        for (int i = 0; i < payloadLength; i++) {
            if (!java.util.Objects.equals(payload[i], that.payload[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = tag;
        for (Object p : payload) {
            result = 31 * result + java.util.Objects.hashCode(p);
        }
        return result;
    }
}
