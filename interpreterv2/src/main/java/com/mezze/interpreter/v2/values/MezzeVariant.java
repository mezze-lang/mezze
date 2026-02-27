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

    /**
     * TagRegistry defines the mapping from constructor names to integer tags.
     * Shared across all variants of the same type.
     */
    public static final class TagRegistry {
        @CompilationFinal(dimensions = 1)
        private final String[] constructorNames;

        public TagRegistry(String[] constructorNames) {
            this.constructorNames = constructorNames;
        }

        public int getTagCount() {
            return constructorNames.length;
        }

        public String getConstructorName(int tag) {
            return constructorNames[tag];
        }

        @TruffleBoundary
        public int findTag(String constructorName) {
            for (int i = 0; i < constructorNames.length; i++) {
                if (constructorNames[i].equals(constructorName)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private final TagRegistry registry;
    private final int tag;

    @CompilationFinal(dimensions = 1)
    private final Object[] payload;

    public MezzeVariant(TagRegistry registry, int tag, Object[] payload) {
        this.registry = registry;
        this.tag = tag;
        this.payload = payload != null ? payload : new Object[0];
    }

    /**
     * Create a variant with no payload (e.g., None, Nil).
     */
    public static MezzeVariant noPayload(TagRegistry registry, int tag) {
        return new MezzeVariant(registry, tag, new Object[0]);
    }

    /**
     * Create a variant with a single value payload (e.g., Some(x)).
     */
    public static MezzeVariant singlePayload(TagRegistry registry, int tag, Object value) {
        return new MezzeVariant(registry, tag, new Object[] { value });
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

    public boolean hasPayload() {
        return payload.length > 0;
    }

    public Object getPayloadValue() {
        return payload.length > 0 ? payload[0] : null;
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
        if (payload.length == 0) {
            return new String[] { "constructor" };
        } else if (payload.length == 1) {
            return new String[] { "constructor", "value" };
        } else {
            String[] members = new String[payload.length + 1];
            members[0] = "constructor";
            for (int i = 0; i < payload.length; i++) {
                members[i + 1] = "field" + i;
            }
            return members;
        }
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        if ("constructor".equals(member)) return true;
        if ("value".equals(member) && payload.length == 1) return true;
        if (member.startsWith("field")) {
            try {
                int idx = Integer.parseInt(member.substring(5));
                return idx >= 0 && idx < payload.length;
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
        if ("value".equals(member) && payload.length == 1) return payload[0];
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
        if (payload.length == 0) {
            return name;
        } else if (payload.length == 1) {
            return name + "(" + payload[0] + ")";
        } else {
            StringBuilder sb = new StringBuilder(name);
            sb.append("(");
            for (int i = 0; i < payload.length; i++) {
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
        if (payload.length != that.payload.length) return false;

        for (int i = 0; i < payload.length; i++) {
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
