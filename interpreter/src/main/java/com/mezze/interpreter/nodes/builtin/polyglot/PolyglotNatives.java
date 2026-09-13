package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeNull;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;

/**
 * Native operations backing {@code std::polyglot::core}.
 *
 * All operations go through {@link InteropLibrary#getUncached} — these are
 * boundary calls with a small fixed cost per invocation.  Hot dispatch on
 * Foreign values (member reads inside a tight loop) would want a cached
 * InteropLibrary specialization node; that's a follow-up optimisation.
 *
 * Every fallible operation returns a Mezze {@code Result Foreign
 * InteropError} — Ok wraps the returned Foreign, Err wraps an
 * InteropError variant.
 */
public final class PolyglotNatives {

    private PolyglotNatives() {}

    // ==================== Error construction ====================

    /**
     * Construct an InteropError variant. Constructor names must match the
     * stdlib {@code type InteropError = | NotFound { name: Str }, ...}
     * declaration exactly — tags are name-derived.
     */
    @TruffleBoundary
    private static MezzeVariant makeErrorVariant(String ctorName, String[] fieldNames, Object[] values) {
        MezzeRecord payload = MezzeRecord.fromFields(fieldNames, values);
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(ctorName);
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
    }

    @TruffleBoundary
    static MezzeVariant errorNotFound(String name) {
        return makeErrorVariant("NotFound", new String[]{"name"}, new Object[]{name});
    }

    @TruffleBoundary
    public static MezzeVariant errorTypeError(String message) {
        return makeErrorVariant("TypeError", new String[]{"message"}, new Object[]{message});
    }

    @TruffleBoundary
    static MezzeVariant errorGuestError(String message, String stack) {
        return makeErrorVariant("GuestError",
            new String[]{"message", "guest_stack"},
            new Object[]{message, stack == null ? "" : stack});
    }

    @TruffleBoundary
    static MezzeVariant errorArityMismatch(long expected, long actual) {
        return makeErrorVariant("ArityMismatch",
            new String[]{"expected", "actual"},
            new Object[]{expected, actual});
    }

    @TruffleBoundary
    static MezzeVariant errorUnsupported() {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("UnsupportedMessage");
        return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
    }

    /**
     * Translate a Truffle interop exception into an InteropError variant.
     * Public because JvmNatives and every out-of-tree language-native
     * plugin (e.g. `polyglot-python-natives`, `polyglot-js-natives`)
     * shares this error surface — one canonical mapping across every
     * guest language.
     */
    @TruffleBoundary
    public static MezzeVariant translateException(Throwable t) {
        if (t instanceof UnknownIdentifierException uie) {
            return errorNotFound(uie.getUnknownIdentifier());
        }
        if (t instanceof UnsupportedTypeException) {
            return errorTypeError(t.getMessage() == null ? "unsupported type" : t.getMessage());
        }
        if (t instanceof ArityException ae) {
            return errorArityMismatch(ae.getExpectedMinArity(), ae.getActualArity());
        }
        if (t instanceof UnsupportedMessageException) {
            return errorUnsupported();
        }
        if (t instanceof InvalidArrayIndexException iae) {
            return errorTypeError("Invalid array index: " + iae.getInvalidIndex());
        }
        // PolyglotException fallback — check by class name to avoid a hard
        // dep on org.graalvm.polyglot from this hot path.
        String cn = t.getClass().getName();
        String msg = t.getMessage() == null ? cn : t.getMessage();
        if (cn.equals("org.graalvm.polyglot.PolyglotException")) {
            return errorGuestError(msg, extractGuestStack(t));
        }
        return errorGuestError(msg, extractGuestStack(t));
    }

    @TruffleBoundary
    private static String extractGuestStack(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append(el.toString()).append('\n');
            if (sb.length() > 4096) break;  // keep it bounded
        }
        return sb.toString();
    }

    // ==================== Foreign helpers ====================

    public static MezzeForeign asForeign(MezzeRecord r) {
        Object val = getField(r, "val");
        if (val instanceof MezzeForeign f) return f;
        // Auto-wrap a raw value — makes it possible for tests to pass
        // e.g. MezzeSeq/MezzeRecord directly through the polyglot surface.
        return new MezzeForeign(val, "auto");
    }

    public static Object getField(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) {
            throw new NativeTypeMismatchException("Missing field '" + name + "'");
        }
        return r.getField(idx);
    }

    public static String getString(MezzeRecord r, String name) {
        Object v = getField(r, name);
        if (v instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + name + "'");
    }

    public static long getLong(MezzeRecord r, String name) {
        Object v = getField(r, name);
        if (v instanceof Long l) return l;
        throw new NativeTypeMismatchException("Expected Long for field '" + name + "'");
    }

    public static double getDouble(MezzeRecord r, String name) {
        Object v = getField(r, name);
        if (v instanceof Double d) return d;
        if (v instanceof Long l) return l.doubleValue();
        throw new NativeTypeMismatchException("Expected Double for field '" + name + "'");
    }

    static boolean getBool(MezzeRecord r, String name) {
        Object v = getField(r, name);
        if (v instanceof Boolean b) return b;
        throw new NativeTypeMismatchException("Expected Boolean for field '" + name + "'");
    }

    /**
     * Extract an Object[] from an argument that carries a sequence of
     * arguments (either MezzeSeq or Object[]). Returns unwrapped raw
     * values (MezzeForeign delegates unwrapped) so the guest side sees
     * the underlying values.
     */
    @TruffleBoundary
    static Object[] getArgArray(MezzeRecord r, String name) {
        Object v = getField(r, name);
        Object[] raw;
        if (v instanceof MezzeSeq seq) {
            raw = seq.toArray();
        } else if (v instanceof Object[] arr) {
            raw = arr;
        } else {
            throw new NativeTypeMismatchException(
                "Expected Seq for field '" + name + "', got: " +
                (v == null ? "null" : v.getClass().getSimpleName()));
        }
        // Unwrap MezzeForeign wrappers so guest langs see the delegate
        Object[] out = new Object[raw.length];
        for (int i = 0; i < raw.length; i++) {
            Object x = raw[i];
            out[i] = x instanceof MezzeForeign f ? f.getDelegate() : x;
        }
        return out;
    }

    // ==================== Type predicates & coercion ====================

    @TruffleBoundary
    public static boolean isNull(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isNull(f.getDelegate());
    }

    @TruffleBoundary
    public static boolean isString(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isString(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant asString(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().asString(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static boolean isNumber(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isNumber(f.getDelegate());
    }

    @TruffleBoundary
    public static boolean fitsInLong(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().fitsInLong(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant asLong(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().asLong(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant asDouble(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().asDouble(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant asBoolean(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().asBoolean(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    // ==================== Constructors ====================

    @TruffleBoundary
    public static MezzeForeign fromInt(MezzeRecord args) {
        return fromInt(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign fromInt(MezzeRecord args, String sourceLanguage) {
        return new MezzeForeign(getLong(args, "val"), sourceLanguage);
    }

    @TruffleBoundary
    public static MezzeForeign fromFloat(MezzeRecord args) {
        return fromFloat(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign fromFloat(MezzeRecord args, String sourceLanguage) {
        return new MezzeForeign(getDouble(args, "val"), sourceLanguage);
    }

    @TruffleBoundary
    public static MezzeForeign fromStr(MezzeRecord args) {
        return fromStr(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign fromStr(MezzeRecord args, String sourceLanguage) {
        return new MezzeForeign(getString(args, "val"), sourceLanguage);
    }

    @TruffleBoundary
    public static MezzeForeign fromBool(MezzeRecord args) {
        return fromBool(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign fromBool(MezzeRecord args, String sourceLanguage) {
        return new MezzeForeign(getBool(args, "val"), sourceLanguage);
    }

    @TruffleBoundary
    public static MezzeForeign nullVal(@SuppressWarnings("unused") MezzeRecord args) {
        return nullVal(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign nullVal(@SuppressWarnings("unused") MezzeRecord args, String sourceLanguage) {
        return new MezzeForeign(MezzeNull.INSTANCE, sourceLanguage);
    }

    // ==================== Members ====================

    @TruffleBoundary
    public static boolean hasMembers(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().hasMembers(f.getDelegate());
    }

    @TruffleBoundary
    public static boolean hasMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        String name = getString(args, "name");
        return InteropLibrary.getUncached().isMemberReadable(f.getDelegate(), name);
    }

    @TruffleBoundary
    public static MezzeVariant readMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        String name = getString(args, "name");
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            if (!lib.isMemberReadable(f.getDelegate(), name)) {
                return MezzeVariant.err(errorNotFound(name));
            }
            Object result = lib.readMember(f.getDelegate(), name);
            return MezzeVariant.ok(new MezzeForeign(result, f.getSourceLanguage()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant writeMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        String name = getString(args, "name");
        Object newVal = getField(args, "new_val");
        Object raw = newVal instanceof MezzeForeign mf ? mf.getDelegate() : newVal;
        try {
            InteropLibrary.getUncached().writeMember(f.getDelegate(), name, raw);
            return MezzeVariant.ok(MezzeRecord.empty());
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant invokeMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        String name = getString(args, "name");
        Object[] rawArgs = getArgArray(args, "args");
        try {
            Object result = InteropLibrary.getUncached().invokeMember(
                f.getDelegate(), name, rawArgs);
            return MezzeVariant.ok(new MezzeForeign(result, f.getSourceLanguage()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    // ==================== Arrays ====================

    @TruffleBoundary
    public static boolean hasArrayElements(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().hasArrayElements(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant arraySize(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().getArraySize(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant readArray(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        long index = getLong(args, "index");
        try {
            Object result = InteropLibrary.getUncached().readArrayElement(
                f.getDelegate(), index);
            return MezzeVariant.ok(new MezzeForeign(result, f.getSourceLanguage()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant writeArray(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        long index = getLong(args, "index");
        Object newVal = getField(args, "new_val");
        Object raw = newVal instanceof MezzeForeign mf ? mf.getDelegate() : newVal;
        try {
            InteropLibrary.getUncached().writeArrayElement(f.getDelegate(), index, raw);
            return MezzeVariant.ok(MezzeRecord.empty());
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    // ==================== Execution ====================

    @TruffleBoundary
    public static boolean isExecutable(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isExecutable(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant execute(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        Object[] rawArgs = getArgArray(args, "args");
        try {
            Object result = InteropLibrary.getUncached().execute(f.getDelegate(), rawArgs);
            return MezzeVariant.ok(new MezzeForeign(result, f.getSourceLanguage()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static boolean isInstantiable(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isInstantiable(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant instantiate(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        Object[] rawArgs = getArgArray(args, "args");
        try {
            Object result = InteropLibrary.getUncached().instantiate(f.getDelegate(), rawArgs);
            return MezzeVariant.ok(new MezzeForeign(result, f.getSourceLanguage()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    // ==================== Meta ====================

    @TruffleBoundary
    public static String typeName(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            if (lib.hasMetaObject(f.getDelegate())) {
                Object meta = lib.getMetaObject(f.getDelegate());
                InteropLibrary metaLib = InteropLibrary.getUncached(meta);
                if (metaLib.isMetaObject(meta)) {
                    return metaLib.getMetaQualifiedName(meta).toString();
                }
            }
            // Fallback — return the Java class name of the delegate.
            return f.getDelegate() == null
                ? "null"
                : f.getDelegate().getClass().getSimpleName();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
