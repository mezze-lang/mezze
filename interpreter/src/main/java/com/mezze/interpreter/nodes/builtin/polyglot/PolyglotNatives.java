package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeNull;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
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

    // ==================== Guest evaluation ====================

    /**
     * Evaluate {@code source} in guest language {@code lang} against the ambient
     * Truffle Context, tagging the resulting Foreign with {@code lang}.
     *
     * This is the ONE guest-entry native, generic over the language id. It
     * replaces the per-provider {@code __native_python_eval} /
     * {@code __native_js_eval} handlers the plugin jars used to install: a
     * provider package now performs {@code Polyglot.eval_raw { lang, source }}
     * through the {@code Runtime} handler instead of shipping its own native, so
     * a third-party language (Ruby, R, …) needs no Java at all — only that the
     * launcher's Context has that language enabled. All import/require sugar is
     * built on top of this in the provider's own Mezze (it constructs the guest
     * expression and calls {@code eval_raw}).
     */
    @TruffleBoundary
    public static MezzeForeign evalRaw(MezzeRecord args, Node currentNode) {
        String lang = getString(args, "lang");
        String source = getString(args, "source");
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object result = support.eval(lang, source);
            return new MezzeForeign(result, lang);
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), lang);
        }
    }

    /**
     * Compile a guest module from a raw byte buffer (a wasm binary, …) against
     * the ambient Context, tagging the result with {@code lang}. The binary
     * counterpart of {@link #evalRaw}: a source-string {@code eval} cannot express
     * a wasm module, so a provider performs {@code Polyglot.eval_bytes} and then
     * composes {@code instantiate} / {@code read_member "exports"} in pure Mezze.
     */
    @TruffleBoundary
    public static MezzeForeign evalBytes(MezzeRecord args, Node currentNode) {
        String lang = getString(args, "lang");
        String name = getString(args, "name");
        byte[] data = getBytes(args, "bytes");
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object result = support.evalBytes(lang, data, name);
            return new MezzeForeign(result, lang);
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), lang);
        }
    }

    /**
     * Read {@code length} bytes from a guest byte-buffer Foreign (a wasm linear
     * memory, …) starting at {@code offset}, in ONE crossing → Mezze Bytes. Bulk,
     * generic over any array-shaped Foreign whose elements coerce to bytes — the
     * per-byte loop stays inside this native rather than costing a Mezze-level
     * `read_array` per byte.
     */
    @TruffleBoundary
    public static MezzeVariant readBytes(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        long offset = getLong(args, "offset");
        long length = getLong(args, "length");
        if (offset < 0 || length < 0) {
            return MezzeVariant.err(errorTypeError(
                "read_bytes: negative offset or length (offset=" + offset
                    + ", length=" + length + ")"));
        }
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            long size = lib.getArraySize(f.getDelegate());
            if (offset + length > size) {
                return MezzeVariant.err(errorTypeError(
                    "read_bytes: out of range (offset=" + offset + ", length="
                        + length + ", size=" + size + ")"));
            }
            byte[] out = new byte[(int) length];
            for (int i = 0; i < length; i++) {
                Object byteVal = lib.readArrayElement(f.getDelegate(), offset + i);
                out[i] = (byte) InteropLibrary.getUncached(byteVal).asInt(byteVal);
            }
            return MezzeVariant.ok(new BytesNatives.MezzeBytes(out));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    /**
     * Write a Mezze Bytes buffer into a guest byte-buffer Foreign at {@code offset},
     * in ONE crossing. Mirror of {@link #readBytes}; Ok is the empty record.
     */
    @TruffleBoundary
    public static MezzeForeign writeBytes(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        long offset = getLong(args, "offset");
        byte[] data = getBytes(args, "bytes");
        if (offset < 0) {
            return MezzeForeign.errored(errorTypeError(
                "write_bytes: negative offset (offset=" + offset + ")"), f.getSourceLanguage());
        }
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            long size = lib.getArraySize(f.getDelegate());
            if (offset + data.length > size) {
                return MezzeForeign.errored(errorTypeError(
                    "write_bytes: out of range (offset=" + offset + ", length="
                        + data.length + ", size=" + size + ")"), f.getSourceLanguage());
            }
            for (int i = 0; i < data.length; i++) {
                lib.writeArrayElement(f.getDelegate(), offset + i, (int) (data[i] & 0xFF));
            }
            return f;  // fluent
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    /**
     * Lift a Mezze Int into a 32-bit-typed (i32) Foreign — stored as a Java
     * {@code Integer} so it is directly acceptable to an i32-typed wasm export
     * (a Java {@code Long} would raise UnsupportedTypeException). The i64 form is
     * the generic {@link #fromInt}. Kept generic (a `lang` tag) so any guest with a
     * 32-bit numeric ABI can use it.
     */
    @TruffleBoundary
    public static MezzeForeign boxI32(MezzeRecord args, String sourceLanguage) {
        return new MezzeForeign(Integer.valueOf((int) getLong(args, "val")), sourceLanguage);
    }

    private static byte[] getBytes(MezzeRecord r, String name) {
        Object v = getField(r, name);
        if (v instanceof BytesNatives.MezzeBytes b) return b.getData();
        throw new NativeTypeMismatchException("Expected Bytes for field '" + name + "'");
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

    /**
     * The first errored foreign among the elements of the `name` arg-seq, or null
     * if none is errored (or the field is absent / not a seq). Propagates an errored
     * ARGUMENT through a call — `a.method { args = [errored] }` short-circuits to that
     * error rather than handing the guest a dead (null) delegate.
     */
    @TruffleBoundary
    static MezzeForeign firstErroredArg(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) return null;
        Object v = r.getField(idx);
        Object[] raw;
        if (v instanceof MezzeSeq seq) raw = seq.toArray();
        else if (v instanceof Object[] arr) raw = arr;
        else return null;
        for (Object x : raw) {
            if (x instanceof MezzeForeign mf && mf.isErrored()) return mf;
        }
        return null;
    }

    /** The carried error of an errored foreign, as an `Err` Result — the coercion
     *  boundary where an error-carrying foreign finally becomes a Mezze `Result`. */
    static MezzeVariant carriedErr(MezzeForeign f) {
        return MezzeVariant.err((MezzeVariant) f.getCarriedError());
    }

    /**
     * Surface an errored foreign as a `Result {}` WITHOUT extracting a value —
     * `Ok {}` if live, `Err` if it carries an error. Backs `.ok{}`: the explicit
     * check for a fire-and-forget call, and the presence probe a codec uses to
     * tell a missing field / absent payload from a real one.
     */
    @TruffleBoundary
    public static MezzeVariant checkOk(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        return MezzeVariant.ok(MezzeRecord.empty());
    }

    // ==================== Type predicates & coercion ====================

    @TruffleBoundary
    public static MezzeVariant isNull(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        return MezzeVariant.ok(InteropLibrary.getUncached().isNull(f.getDelegate()));
    }

    @TruffleBoundary
    public static boolean isString(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isString(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant asString(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
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
        if (f.isErrored()) return carriedErr(f);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().asLong(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant asDouble(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().asDouble(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant asBoolean(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
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

    /**
     * BUG 2 — build a guest list from already-encoded elements.
     *
     * The polyglot surface could READ an existing guest array but never build
     * one, so a codec's `encode_list` had nothing to call: it is handed
     * `elems: [<Lang>Foreign]`, each element already encoded, and has to produce
     * one list-shaped Foreign. Without this, `[Float].to_py {}` has no chain and
     * the blanket `impl ToPy for 'a where { … with Encode PythonC 'a }` does not
     * apply — the user sees `to_py` reported as a field access on a non-record.
     *
     * Language-agnostic by construction: the result is an interop view the guest
     * reads on demand, not a copy built through any one language's list builder,
     * so the same native serves Python, JS and WASM.
     */
    @TruffleBoundary
    public static MezzeForeign makeList(MezzeRecord args) {
        return makeList(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign makeList(MezzeRecord args, String sourceLanguage) {
        // getArgArray already unwraps MezzeForeign to its delegate, which is
        // what the guest must see — a list of wrappers would be opaque to it.
        return new MezzeForeign(
            new ForeignContainers.ForeignList(getArgArray(args, "elems")), sourceLanguage);
    }

    /**
     * BUG 2 — build a guest mapping from already-encoded keys and values.
     * Companion to {@link #makeList}, backing a codec's `encode_map` /
     * `encode_record`. Insertion order is preserved so a record's fields reach
     * the guest in declaration order rather than an arbitrary one.
     */
    @TruffleBoundary
    public static MezzeForeign makeDict(MezzeRecord args) {
        return makeDict(args, "mezze");
    }

    @TruffleBoundary
    public static MezzeForeign makeDict(MezzeRecord args, String sourceLanguage) {
        Object[] keys = getArgArray(args, "keys");
        Object[] values = getArgArray(args, "vals");
        if (keys.length != values.length) {
            throw new NativeTypeMismatchException(
                "make_dict needs one value per key, got " + keys.length
                    + " keys and " + values.length + " values");
        }
        java.util.LinkedHashMap<Object, Object> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            entries.put(keys[i], values[i]);
        }
        return new MezzeForeign(new ForeignContainers.ForeignDict(entries), sourceLanguage);
    }

    // ==================== Members ====================

    @TruffleBoundary
    public static boolean hasMembers(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().hasMembers(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant hasMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        String name = getString(args, "name");
        return MezzeVariant.ok(InteropLibrary.getUncached().isMemberReadable(f.getDelegate(), name));
    }

    /**
     * Read a named member. ONE interop message on the happy path.
     *
     * This used to ask {@code isMemberReadable} first and only then
     * {@code readMember} — two messages per read, on the hottest polyglot
     * operation there is. The check is not needed to get the answer: a member
     * that is not there makes {@code readMember} throw
     * UnknownIdentifierException, which {@link #translateException} already
     * turns into the same {@code NotFound{name}}. It only distinguished one
     * case — a member that EXISTS but is not readable, which the check called
     * NotFound and a bare read would call Unsupported. So the probe moved to
     * {@link #readFailure}, where it runs only if the read actually failed and
     * costs a successful read nothing.
     */
    @TruffleBoundary
    public static MezzeForeign readMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        String name = getString(args, "name");
        Object recv = f.getDelegate();
        try {
            Object result = InteropLibrary.getUncached(recv).readMember(recv, name);
            return new MezzeForeign(result, f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(readFailure(recv, name, t), f.getSourceLanguage());
        }
    }

    /**
     * Which InteropError a FAILED member read means. Only reached on failure,
     * so the extra probe here is off the hot path — it preserves the
     * "exists but unreadable reads as NotFound" answer the old pre-check gave.
     */
    @TruffleBoundary
    private static MezzeVariant readFailure(Object recv, String name, Throwable t) {
        if (t instanceof UnsupportedMessageException
                && !InteropLibrary.getUncached(recv).isMemberReadable(recv, name)) {
            return errorNotFound(name);
        }
        return translateException(t);
    }

    @TruffleBoundary
    public static MezzeForeign writeMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        Object newVal = getField(args, "new_val");
        if (newVal instanceof MezzeForeign mvErr && mvErr.isErrored()) return mvErr;
        String name = getString(args, "name");
        Object raw = newVal instanceof MezzeForeign mf ? mf.getDelegate() : newVal;
        try {
            InteropLibrary.getUncached().writeMember(f.getDelegate(), name, raw);
            return f;  // fluent: the receiver, so a write can be chained
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    @TruffleBoundary
    public static MezzeForeign invokeMember(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        MezzeForeign badArg = firstErroredArg(args, "args");
        if (badArg != null) return badArg;
        String name = getString(args, "name");
        Object[] rawArgs = getArgArray(args, "args");
        try {
            Object result = InteropLibrary.getUncached().invokeMember(
                f.getDelegate(), name, rawArgs);
            return new MezzeForeign(result, f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
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
    public static MezzeForeign readArray(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        long index = getLong(args, "index");
        try {
            Object result = InteropLibrary.getUncached().readArrayElement(
                f.getDelegate(), index);
            return new MezzeForeign(result, f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    @TruffleBoundary
    public static MezzeForeign writeArray(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        Object newVal = getField(args, "new_val");
        if (newVal instanceof MezzeForeign mvErr && mvErr.isErrored()) return mvErr;
        long index = getLong(args, "index");
        Object raw = newVal instanceof MezzeForeign mf ? mf.getDelegate() : newVal;
        try {
            InteropLibrary.getUncached().writeArrayElement(f.getDelegate(), index, raw);
            return f;  // fluent
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    // ==================== Hashes ====================
    //
    // The READ side of {@link #makeDict}. `makeDict` builds a
    // {@link ForeignContainers.ForeignDict}, which is HASH-shaped — it exports
    // hasHashEntries / readHashValue / getHashEntriesIterator, not members — so
    // none of the member or array natives above can read one back. Without
    // these four, a codec could encode a Mezze Map or record into a guest dict
    // and then had no way to decode it: the write side shipped with no mirror.
    //
    // A guest dict from Python or JavaScript is hash-shaped too, so the same
    // four natives read a guest-built mapping and a Mezze-built one alike.

    @TruffleBoundary
    public static boolean hasHashEntries(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().hasHashEntries(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeVariant hashSize(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        try {
            return MezzeVariant.ok(InteropLibrary.getUncached().getHashSize(f.getDelegate()));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    /**
     * Read one entry by key. The key is unwrapped exactly like a call argument:
     * a caller holding a `JvmForeign` key passes the Foreign, and the guest must
     * see the delegate. A bare Mezze value (a Str field name) is passed through
     * as-is — ForeignDict normalizes guest strings to Java Strings on lookup.
     */
    @TruffleBoundary
    public static MezzeForeign readHash(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        Object key = getField(args, "key");
        if (key instanceof MezzeForeign kErr && kErr.isErrored()) return kErr;
        Object rawKey = key instanceof MezzeForeign mf ? mf.getDelegate() : key;
        try {
            Object result = InteropLibrary.getUncached().readHashValue(f.getDelegate(), rawKey);
            return new MezzeForeign(result, f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    /**
     * All entries, as a list-shaped Foreign whose elements are two-element
     * [key, value] lists.
     *
     * Interop hands hash entries back as an ITERATOR, which Mezze has no way to
     * consume — there is no interop-iterator native and adding one would expose a
     * mutable cursor to pure code. Draining it here into the same
     * {@link ForeignContainers.ForeignList} view the rest of the surface already
     * reads keeps `decode_map` to the array natives it already uses for lists.
     */
    @TruffleBoundary
    public static MezzeForeign hashEntries(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        InteropLibrary interop = InteropLibrary.getUncached();
        try {
            Object iterator = interop.getHashEntriesIterator(f.getDelegate());
            java.util.ArrayList<Object> pairs = new java.util.ArrayList<>();
            while (interop.hasIteratorNextElement(iterator)) {
                pairs.add(interop.getIteratorNextElement(iterator));
            }
            return new MezzeForeign(
                new ForeignContainers.ForeignList(pairs.toArray()), f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    /**
     * A whole guest array as a Mezze seq of Foreigns, in ONE crossing.
     *
     * The read side was per-element: a codec's `decode_list` asked for the size
     * and then called `read_array` once per index, so pulling an N-element guest
     * array into Mezze cost N native calls, N interop messages and N `Result`
     * allocations, plus a Mezze-level recursion to accumulate them. Building a
     * seq the OTHER way is already O(1) — `makeList` hands the guest a view — so
     * the boundary was asymmetric for no reason other than a missing native.
     *
     * The loop belongs on this side of the boundary: it is one
     * {@code @TruffleBoundary} either way, and doing it here pays that cost once
     * instead of N times. Elements stay wrapped as Foreigns, so the caller
     * decodes them exactly as it did before.
     */
    @TruffleBoundary
    public static MezzeVariant arrayToSeq(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        InteropLibrary interop = InteropLibrary.getUncached();
        try {
            long size = interop.getArraySize(f.getDelegate());
            if (size > Integer.MAX_VALUE) {
                throw new NativeTypeMismatchException(
                    "Guest array of " + size + " elements is too large to read into a seq");
            }
            Object[] elements = new Object[(int) size];
            for (int i = 0; i < elements.length; i++) {
                elements[i] = new MezzeForeign(
                    interop.readArrayElement(f.getDelegate(), i), f.getSourceLanguage());
            }
            return MezzeVariant.ok(MezzeSeq.fromArray(elements));
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    /**
     * Write one entry into a hash-shaped Foreign — the mirror of {@link #readHash}
     * and the write side {@code hash_entries}/{@code read_hash} never had. Both key
     * and value are unwrapped like call arguments: a Foreign passes its delegate, a
     * bare Mezze value passes through as-is.
     */
    @TruffleBoundary
    public static MezzeForeign writeHash(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        Object key = getField(args, "key");
        if (key instanceof MezzeForeign kErr && kErr.isErrored()) return kErr;
        Object newVal = getField(args, "new_val");
        if (newVal instanceof MezzeForeign vErr && vErr.isErrored()) return vErr;
        Object rawKey = key instanceof MezzeForeign mf ? mf.getDelegate() : key;
        Object rawVal = newVal instanceof MezzeForeign mv ? mv.getDelegate() : newVal;
        try {
            InteropLibrary.getUncached().writeHashEntry(f.getDelegate(), rawKey, rawVal);
            return f;  // fluent
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    /**
     * Number of elements in an array-shaped Foreign OR entries in a hash-shaped one
     * — the guest's {@code len()}. Unifies {@code array_size}/{@code hash_size} into
     * one verb so the surface has a single `size`, matching how a guest exposes one
     * `len`. Array is checked first (the common case); a mapping falls through to
     * its hash size; anything else is Unsupported.
     */
    @TruffleBoundary
    public static MezzeVariant size(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        InteropLibrary interop = InteropLibrary.getUncached();
        Object recv = f.getDelegate();
        try {
            if (interop.hasArrayElements(recv)) {
                return MezzeVariant.ok(interop.getArraySize(recv));
            }
            if (interop.hasHashEntries(recv)) {
                return MezzeVariant.ok(interop.getHashSize(recv));
            }
            return MezzeVariant.err(errorUnsupported());
        } catch (Throwable t) {
            return MezzeVariant.err(translateException(t));
        }
    }

    /**
     * A whole iterable Foreign as a Mezze seq of Foreigns, in ONE crossing —
     * generalizing {@link #arrayToSeq} to anything the guest can iterate: a Python
     * generator / range / map object, a JS iterator, a custom {@code __iter__}.
     *
     * An array-shaped Foreign is drained by INDEX (fast path, no iterator). Anything
     * else that {@code hasIterator} is drained through the iterator protocol —
     * getIterator then hasIteratorNextElement/getIteratorNextElement — with the
     * cursor kept entirely inside this native, so no mutable iterator ever reaches
     * pure Mezze code (the same reason {@code hash_entries} drains here rather than
     * exposing {@code iter}/{@code next}).
     */
    @TruffleBoundary
    public static MezzeVariant toSeq(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        InteropLibrary interop = InteropLibrary.getUncached();
        Object recv = f.getDelegate();
        String lang = f.getSourceLanguage();
        try {
            if (interop.hasArrayElements(recv)) {
                long size = interop.getArraySize(recv);
                if (size > Integer.MAX_VALUE) {
                    throw new NativeTypeMismatchException(
                        "Guest array of " + size + " elements is too large to read into a seq");
                }
                Object[] elements = new Object[(int) size];
                for (int i = 0; i < elements.length; i++) {
                    elements[i] = new MezzeForeign(interop.readArrayElement(recv, i), lang);
                }
                return MezzeVariant.ok(MezzeSeq.fromArray(elements));
            }
            if (interop.hasIterator(recv)) {
                Object iterator = interop.getIterator(recv);
                java.util.ArrayList<Object> elems = new java.util.ArrayList<>();
                while (interop.hasIteratorNextElement(iterator)) {
                    elems.add(new MezzeForeign(interop.getIteratorNextElement(iterator), lang));
                }
                return MezzeVariant.ok(MezzeSeq.fromArray(elems.toArray()));
            }
            return MezzeVariant.err(errorUnsupported());
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
    public static MezzeForeign execute(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        MezzeForeign badArg = firstErroredArg(args, "args");
        if (badArg != null) return badArg;
        Object[] rawArgs = getArgArray(args, "args");
        try {
            Object result = InteropLibrary.getUncached().execute(f.getDelegate(), rawArgs);
            return new MezzeForeign(result, f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    @TruffleBoundary
    public static boolean isInstantiable(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        return InteropLibrary.getUncached().isInstantiable(f.getDelegate());
    }

    @TruffleBoundary
    public static MezzeForeign instantiate(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return f;
        MezzeForeign badArg = firstErroredArg(args, "args");
        if (badArg != null) return badArg;
        Object[] rawArgs = getArgArray(args, "args");
        try {
            Object result = InteropLibrary.getUncached().instantiate(f.getDelegate(), rawArgs);
            return new MezzeForeign(result, f.getSourceLanguage());
        } catch (Throwable t) {
            return MezzeForeign.errored(translateException(t), f.getSourceLanguage());
        }
    }

    // ==================== Meta ====================

    @TruffleBoundary
    public static MezzeVariant typeName(MezzeRecord args) {
        MezzeForeign f = asForeign(args);
        if (f.isErrored()) return carriedErr(f);
        try {
            InteropLibrary lib = InteropLibrary.getUncached(f.getDelegate());
            if (lib.hasMetaObject(f.getDelegate())) {
                Object meta = lib.getMetaObject(f.getDelegate());
                InteropLibrary metaLib = InteropLibrary.getUncached(meta);
                if (metaLib.isMetaObject(meta)) {
                    return MezzeVariant.ok(metaLib.getMetaQualifiedName(meta).toString());
                }
            }
            // Fallback — return the Java class name of the delegate.
            return MezzeVariant.ok(f.getDelegate() == null
                ? "null"
                : f.getDelegate().getClass().getSimpleName());
        } catch (Throwable t) {
            return MezzeVariant.ok("unknown");
        }
    }
}
