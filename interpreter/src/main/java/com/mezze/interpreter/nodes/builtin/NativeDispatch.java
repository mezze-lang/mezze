package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.bytes.MezzeBytes;
import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.builtin.collections.*;
import com.mezze.interpreter.nodes.builtin.concurrency.*;
import com.mezze.interpreter.nodes.builtin.effects.*;
import com.mezze.interpreter.nodes.builtin.io.*;
import com.mezze.interpreter.nodes.builtin.polyglot.*;
import com.mezze.interpreter.nodes.builtin.primitives.*;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive Native function dispatch - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. Cached handler lookup - O(1) after first call, no hash computation
 * 2. @TruffleBoundary only for complex handlers (HOFs, etc.)
 * 3. Direct handler invocation via cached reference
 *
 * This dispatcher handles ALL native functions by delegating to specialized
 * native modules.
 */
@NodeInfo(shortName = "native-dispatch-v2")
public final class NativeDispatch extends MezzeNode {

    @CompilationFinal
    private final String functionName;

    @Child
    private MezzeNode argumentNode;

    @Child
    private IndirectCallNode callNode;

    // Cached handler - looked up once, then direct call
    @CompilationFinal
    private NativeHandler cachedHandler;

    @FunctionalInterface
    public interface NativeHandler {
        Object handle(MezzeRecord args, IndirectCallNode callNode);
    }

    /**
     * Replace a native handler after static init. Used by non-JVM builds (the
     * browser/WASM build) to swap host-touching natives — filesystem, stdin,
     * process, HTTP — for browser equivalents or for a clear "unsupported"
     * throw. Returns the previous handler, or null if the name was unbound.
     *
     * Call before evaluating user code; handlers are cached per node once hit.
     */
    public static NativeHandler overrideHandler(String name, NativeHandler handler) {
        NativeHandler previous = HANDLERS.get(name);
        HANDLERS.put(name, handler);
        return previous;
    }

    /** Every registered native name — lets a build enumerate what to override. */
    public static java.util.Set<String> registeredNativeNames() {
        return java.util.Collections.unmodifiableSet(HANDLERS.keySet());
    }

    // Static dispatch table - initialized once
    private static final Map<String, NativeHandler> HANDLERS = new HashMap<>();

    static {
        initHandlers();
    }

    public NativeDispatch(String functionName, MezzeNode argumentNode) {
        this.functionName = functionName;
        this.argumentNode = argumentNode;
        this.callNode = IndirectCallNode.create();
        // Pre-cache the handler at construction time
        this.cachedHandler = HANDLERS.get(functionName);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new NativeTypeMismatchException(functionName + " expects record argument, got: " +
                (arg == null ? "null" : arg.getClass().getName()));
        }

        // FAST PATH: Direct handler invocation via cached reference
        // No hash lookup, no @TruffleBoundary overhead for simple handlers
        NativeHandler handler = cachedHandler;
        if (handler != null) {
            return handler.handle(record, callNode);
        }

        // Slow path: unknown function
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new NativeTypeMismatchException("Unknown native function: " + functionName);
    }

    public static NativeDispatch create(String functionName, MezzeNode argumentNode) {
        return new NativeDispatch(functionName, argumentNode);
    }

    /**
     * Check if a function name is a known native function.
     * Called at parse time, not in hot path.
     * All native functions use the __native_ prefix.
     */
    public static boolean isNativeFunction(String name) {
        return HANDLERS.containsKey(name);
    }

    /**
     * Resolve a record-ABI native handler at emit time.
     * Returns null if the native is unknown.
     * #350: lets BDSL bake the handler as a ConstantOperand — no runtime
     * HashMap lookup, no string in the hot path.
     */
    public static NativeHandler recordHandlerOf(String name) {
        return HANDLERS.get(name);
    }

    // ==================== Handler Registration ====================

    private static void initHandlers() {
        registerAllHandlers();
        loadPluginsViaServiceLoader();
    }

    /**
     * Discover third-party native-handler packs via
     * {@link java.util.ServiceLoader}. Each {@code NativePlugin} registers
     * its handlers by calling {@link #overrideHandler}. Runs once, after all
     * built-in handlers are in place. Failures are logged and swallowed so a
     * broken plugin cannot brick the interpreter.
     */
    private static void loadPluginsViaServiceLoader() {
        var iter = java.util.ServiceLoader.load(com.mezze.interpreter.spi.NativePlugin.class);
        for (var plugin : iter) {
            try {
                plugin.register();
                // BUG 4: FINE, not INFO. Which plugins loaded is a debugging
                // question; a clean run has no reason to narrate its own
                // startup, and the default handler put these on the terminal
                // ahead of the program's first line of output.
                java.util.logging.Logger.getLogger("NativeDispatch")
                    .fine("Registered native plugin: " + plugin.name());
            } catch (Throwable t) {
                // A plugin that failed to register STAYS visible: its natives
                // are missing, so the program is about to fail somewhere far
                // from the cause. Say which plugin and what to do about it.
                java.util.logging.Logger.getLogger("NativeDispatch")
                    .warning("Native plugin '" + plugin.name() + "' failed to register: " + t
                        + " — calls into it will fail at runtime. Reinstall or remove the"
                        + " provider package that ships it.");
            }
        }
    }

    private static void registerAllHandlers() {
        // Core primitive operations (Int, Float, String, Bool, Console)
        registerIntHandlers();
        registerFloatBasicHandlers();
        registerStringBasicHandlers();
        registerBoolHandlers();
        // Seq operations (new, MezzeSeq-backed)
        registerSeqHandlers();
        // Iter operations (new, lazy MezzeIter-backed)
        registerIterHandlers();
        // List operations
        registerListHandlers();
        // Set operations
        registerSetHandlers();
        // SortedSet operations
        registerSortedSetHandlers();
        // Map operations
        registerMapHandlers();
        // SortedMap operations
        registerSortedMapHandlers();
        // Deque operations
        registerDequeHandlers();
        // NonEmptyList operations
        registerNonEmptyListHandlers();
        // Nat operations
        registerNatHandlers();
        // Char operations
        registerCharHandlers();
        // Frac operations
        registerFracHandlers();
        // Bytes operations
        registerBytesHandlers();
        // DateTime + Time operations (#72)
        registerDateTimeHandlers();
        // String case conversion operations
        registerStringCaseHandlers();
        // Float additional operations
        registerFloatHandlers();
        // Log operations
        registerLogHandlers();
        // Inspect operations
        registerInspectHandlers();
        // Random operations
        registerRandomHandlers();
        // Filesystem operations
        registerFilesystemHandlers();
        // Memory filesystem operations
        registerMemoryFilesystemHandlers();
        // Environment operations
        registerEnvironmentHandlers();
        // Stdin operations
        registerStdinHandlers();
        // Async operations (Loom, OS threads, Coop)
        registerAsyncHandlers();
        // Concurrency primitives (TVar, Atom, Mutex, etc.)
        registerConcurrencyHandlers();
        // Buffer, JSON, HTTP Mock
        registerIOHandlers();
        // Crash function - terminates program with error message
        registerCrashHandler();
        // Mutation primitives (safe local mutation)
        registerMutationHandlers();
        // Polyglot interop (Foreign, JVM host lookup, Context lifecycle)
        registerPolyglotHandlers();
        // Note: Comptime is handled at conversion time, no runtime handler needed
    }

    // ==================== Crash ====================

    private static void registerCrashHandler() {
        HANDLERS.put("crash", (r, c) -> {
            String msg = getString(r, "msg");
            // Deliberate guest-level panic primitive (std `crash`/unhandled
            // exception, "impossible" branches). Guest-visible fatal —
            // renders as a clean guest error, never a Java stack trace.
            throw new MezzeGuestException("crash: " + msg);
        });
    }

    // Note: Comptime is handled at conversion time by ASTToTruffleConverterV2.
    // It either executes immediately or creates a ComptimePlaceholderNode.
    // No runtime handler is needed.

    // ==================== Int Operations ====================
    // Uses PrimitiveNatives which leverages optimized Truffle nodes for overflow handling

    private static void registerIntHandlers() {
        HANDLERS.put("__native_int_add", (r, c) ->
            PrimitiveNatives.intAdd(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_subtract", (r, c) ->
            PrimitiveNatives.intSubtract(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_multiply", (r, c) ->
            PrimitiveNatives.intMultiply(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_divide", (r, c) ->
            PrimitiveNatives.intDivide(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_modulo", (r, c) ->
            PrimitiveNatives.intModulo(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_structural_hash", (r, c) ->
            PrimitiveNatives.structuralHash(getField(r, "it")));
        HANDLERS.put("__native_int_to_str", (r, c) ->
            PrimitiveNatives.intToString(getLong(r, "n")));
        HANDLERS.put("__native_int_abs", (r, c) ->
            PrimitiveNatives.intAbs(getLong(r, "n")));
        HANDLERS.put("__native_int_min", (r, c) ->
            PrimitiveNatives.intMin(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_max", (r, c) ->
            PrimitiveNatives.intMax(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_clamp", (r, c) ->
            PrimitiveNatives.intClamp(getLong(r, "n"), getLong(r, "min"), getLong(r, "max")));
        HANDLERS.put("__native_int_sign", (r, c) ->
            PrimitiveNatives.intSign(getLong(r, "n")));
        HANDLERS.put("__native_int_neg", (r, c) ->
            PrimitiveNatives.intNegate(getLong(r, "n")));
        HANDLERS.put("__native_int_eq", (r, c) ->
            PrimitiveNatives.intEq(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_neq", (r, c) ->
            PrimitiveNatives.intNeq(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_lt", (r, c) ->
            PrimitiveNatives.intLt(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_le", (r, c) ->
            PrimitiveNatives.intLte(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_gt", (r, c) ->
            PrimitiveNatives.intGt(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_ge", (r, c) ->
            PrimitiveNatives.intGte(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_from_string", (r, c) ->
            intFromString(getString(r, "s")));
        HANDLERS.put("__native_int_from_nat", (r, c) ->
            getLong(r, "n"));
        HANDLERS.put("__native_int_to_float", (r, c) ->
            PrimitiveNatives.intToFloat(getLong(r, "n")));
        HANDLERS.put("__native_int_to_nat", (r, c) -> {
            long n = getLong(r, "n");
            return n >= 0 ? MezzeVariant.some(n) : MezzeVariant.none();
        });
        HANDLERS.put("__native_int_pow", (r, c) ->
            intPow(getLong(r, "base"), getLong(r, "exp")));
        HANDLERS.put("__native_int_gcd", (r, c) ->
            intGcd(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_lcm", (r, c) ->
            intLcm(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_int_is_even", (r, c) ->
            getLong(r, "n") % 2 == 0);
        HANDLERS.put("__native_int_is_odd", (r, c) ->
            getLong(r, "n") % 2 != 0);
    }

    @TruffleBoundary
    private static MezzeVariant intFromString(String s) {
        try {
            long value = Long.parseLong(s.trim());
            return MezzeVariant.some(value);
        } catch (NumberFormatException e) {
            return MezzeVariant.none();
        }
    }

    private static long intPow(long base, long exp) {
        if (exp < 0) return 0;
        long result = 1;
        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = Math.multiplyExact(result, base);
            }
            base = Math.multiplyExact(base, base);
            exp >>= 1;
        }
        return result;
    }

    private static long intGcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    private static long intLcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a / intGcd(a, b) * b);
    }

    // ==================== Float Basic Operations ====================

    private static void registerFloatBasicHandlers() {
        HANDLERS.put("__native_float_to_str", (r, c) ->
            Double.toString(getDouble(r, "n")));
        HANDLERS.put("__native_float_from_int", (r, c) ->
            (double) getLong(r, "n"));
        HANDLERS.put("__native_float_from_string", (r, c) ->
            floatFromString(getString(r, "s")));
        HANDLERS.put("__native_float_abs", (r, c) ->
            PrimitiveNatives.floatAbs(getDouble(r, "n")));
        HANDLERS.put("__native_float_min", (r, c) ->
            PrimitiveNatives.floatMin(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_max", (r, c) ->
            PrimitiveNatives.floatMax(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_clamp", (r, c) ->
            PrimitiveNatives.floatClamp(getDouble(r, "n"), getDouble(r, "min"), getDouble(r, "max")));
        HANDLERS.put("__native_float_sign", (r, c) ->
            PrimitiveNatives.floatSignum(getDouble(r, "n")));
        HANDLERS.put("__native_float_to_int", (r, c) ->
            PrimitiveNatives.floatToInt(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_nan", (r, c) ->
            PrimitiveNatives.floatIsNaN(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_infinite", (r, c) ->
            PrimitiveNatives.floatIsInfinite(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_finite", (r, c) ->
            PrimitiveNatives.floatIsFinite(getDouble(r, "n")));
        HANDLERS.put("__native_float_floor", (r, c) ->
            PrimitiveNatives.floatFloor(getDouble(r, "n")));
        HANDLERS.put("__native_float_ceil", (r, c) ->
            PrimitiveNatives.floatCeil(getDouble(r, "n")));
        HANDLERS.put("__native_float_round", (r, c) ->
            (double) PrimitiveNatives.floatRound(getDouble(r, "n")));
        HANDLERS.put("__native_float_sqrt", (r, c) ->
            PrimitiveNatives.floatSqrt(getDouble(r, "n")));
        HANDLERS.put("__native_float_pow", (r, c) ->
            PrimitiveNatives.floatPow(getDouble(r, "base"), getDouble(r, "exp")));
        HANDLERS.put("__native_float_ln", (r, c) ->
            PrimitiveNatives.floatLog(getDouble(r, "n")));
        HANDLERS.put("__native_float_log10", (r, c) ->
            PrimitiveNatives.floatLog10(getDouble(r, "n")));
        HANDLERS.put("__native_float_exp", (r, c) ->
            PrimitiveNatives.floatExp(getDouble(r, "n")));
        // Trig operations - use PrimitiveNatives for consistency
        HANDLERS.put("__native_float_sin", (r, c) ->
            PrimitiveNatives.floatSin(getDouble(r, "n")));
        HANDLERS.put("__native_float_cos", (r, c) ->
            PrimitiveNatives.floatCos(getDouble(r, "n")));
        HANDLERS.put("__native_float_tan", (r, c) ->
            PrimitiveNatives.floatTan(getDouble(r, "n")));
        HANDLERS.put("__native_float_asin", (r, c) ->
            PrimitiveNatives.floatAsin(getDouble(r, "n")));
        HANDLERS.put("__native_float_acos", (r, c) ->
            PrimitiveNatives.floatAcos(getDouble(r, "n")));
        HANDLERS.put("__native_float_atan", (r, c) ->
            PrimitiveNatives.floatAtan(getDouble(r, "n")));
        HANDLERS.put("__native_float_atan2", (r, c) ->
            PrimitiveNatives.floatAtan2(getDouble(r, "y"), getDouble(r, "x")));
        // Float arithmetic
        HANDLERS.put("__native_float_add", (r, c) ->
            PrimitiveNatives.floatAdd(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_subtract", (r, c) ->
            PrimitiveNatives.floatSubtract(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_multiply", (r, c) ->
            PrimitiveNatives.floatMultiply(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_divide", (r, c) ->
            PrimitiveNatives.floatDivide(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_mod", (r, c) ->
            getDouble(r, "l") % getDouble(r, "r"));
        HANDLERS.put("__native_float_neg", (r, c) ->
            PrimitiveNatives.floatNegate(getDouble(r, "n")));
        // Float comparisons
        HANDLERS.put("__native_float_eq", (r, c) ->
            PrimitiveNatives.floatEq(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_neq", (r, c) ->
            PrimitiveNatives.floatNeq(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_lt", (r, c) ->
            PrimitiveNatives.floatLt(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_le", (r, c) ->
            PrimitiveNatives.floatLte(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_gt", (r, c) ->
            PrimitiveNatives.floatGt(getDouble(r, "l"), getDouble(r, "r")));
        HANDLERS.put("__native_float_ge", (r, c) ->
            PrimitiveNatives.floatGte(getDouble(r, "l"), getDouble(r, "r")));
    }

    @TruffleBoundary
    private static MezzeVariant floatFromString(String s) {
        try {
            double value = Double.parseDouble(s.trim());
            return MezzeVariant.some(value);
        } catch (NumberFormatException e) {
            return MezzeVariant.none();
        }
    }

    // ==================== String Basic Operations ====================

    private static void registerStringBasicHandlers() {
        // String operations using PrimitiveNatives
        HANDLERS.put("__native_string_concat", (r, c) ->
            PrimitiveNatives.stringConcat(getString(r, "l"), getString(r, "r")));
        HANDLERS.put("__native_string_size", (r, c) ->
            (long) PrimitiveNatives.stringLength(getString(r, "s")));
        HANDLERS.put("__native_string_to_upper", (r, c) ->
            PrimitiveNatives.stringToUpperCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_lower", (r, c) ->
            PrimitiveNatives.stringToLowerCase(getString(r, "s")));
        HANDLERS.put("__native_string_trim", (r, c) ->
            PrimitiveNatives.stringTrim(getString(r, "s")));
        HANDLERS.put("__native_string_substring", (r, c) ->
            stringSubstring(getString(r, "s"), getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_string_char_at", (r, c) ->
            stringCharAt(getString(r, "s"), getLong(r, "i")));
        HANDLERS.put("__native_string_index_of", (r, c) ->
            stringIndexOf(getString(r, "s"), getString(r, "sub")));
        HANDLERS.put("__native_string_contains", (r, c) ->
            getString(r, "s").contains(getString(r, "substring")));
        HANDLERS.put("__native_string_starts_with", (r, c) ->
            getString(r, "s").startsWith(getString(r, "prefix")));
        HANDLERS.put("__native_string_ends_with", (r, c) ->
            getString(r, "s").endsWith(getString(r, "suffix")));
        HANDLERS.put("__native_string_replace", (r, c) ->
            getString(r, "s").replaceFirst(java.util.regex.Pattern.quote(getString(r, "old")), getString(r, "new")));
        HANDLERS.put("__native_string_replace_all", (r, c) ->
            getString(r, "s").replace(getString(r, "old"), getString(r, "new")));
        HANDLERS.put("__native_string_split", (r, c) ->
            stringSplit(getString(r, "s"), getString(r, "delimiter")));
        HANDLERS.put("__native_string_join", (r, c) ->
            stringJoin(getSeq(r, "strings"), getString(r, "separator")));
        HANDLERS.put("__native_string_reverse", (r, c) ->
            new StringBuilder(getString(r, "s")).reverse().toString());
        HANDLERS.put("__native_string_repeat", (r, c) ->
            getString(r, "s").repeat((int) getLong(r, "n")));
        HANDLERS.put("__native_string_trim_start", (r, c) ->
            getString(r, "s").stripLeading());
        HANDLERS.put("__native_string_trim_end", (r, c) ->
            getString(r, "s").stripTrailing());
        // Percent-encode a single URL component (path segment, query value, etc).
        // Matches JS encodeURIComponent / RFC 3986 unreserved characters.
        HANDLERS.put("__native_url_encode_component", (r, c) ->
            urlEncodeComponent(getString(r, "s")));
        HANDLERS.put("__native_url_decode_component", (r, c) ->
            urlDecodeComponent(getString(r, "s")));
        HANDLERS.put("__native_url_parse", (r, c) -> UrlNatives.urlParse(r));
        HANDLERS.put("__native_url_render", (r, c) -> UrlNatives.urlRender(r));
        // String comparisons
        HANDLERS.put("__native_string_eq", (r, c) ->
            PrimitiveNatives.strEq(getString(r, "l"), getString(r, "r")));
        HANDLERS.put("__native_string_neq", (r, c) ->
            PrimitiveNatives.strNeq(getString(r, "l"), getString(r, "r")));
        HANDLERS.put("__native_string_lt", (r, c) ->
            PrimitiveNatives.strLt(getString(r, "l"), getString(r, "r")));
        HANDLERS.put("__native_string_le", (r, c) ->
            PrimitiveNatives.strLte(getString(r, "l"), getString(r, "r")));
        HANDLERS.put("__native_string_gt", (r, c) ->
            PrimitiveNatives.strGt(getString(r, "l"), getString(r, "r")));
        HANDLERS.put("__native_string_ge", (r, c) ->
            PrimitiveNatives.strGte(getString(r, "l"), getString(r, "r")));
    }

    @TruffleBoundary
    private static String stringSubstring(String s, long start, long end) {
        int startIdx = (int) Math.max(0, Math.min(start, s.length()));
        int endIdx = (int) Math.max(0, Math.min(end, s.length()));
        if (startIdx >= endIdx) return "";
        return s.substring(startIdx, endIdx);
    }

    @TruffleBoundary
    private static MezzeVariant stringCharAt(String s, long i) {
        if (i < 0 || i >= s.length()) return MezzeVariant.none();
        return MezzeVariant.some(String.valueOf(s.charAt((int) i)));
    }

    @TruffleBoundary
    private static MezzeVariant stringIndexOf(String s, String sub) {
        int idx = s.indexOf(sub);
        return idx >= 0 ? MezzeVariant.some((long) idx) : MezzeVariant.none();
    }

    @TruffleBoundary
    private static MezzeSeq stringSplit(String s, String delimiter) {
        String[] parts = s.split(java.util.regex.Pattern.quote(delimiter));
        Object[] result = new Object[parts.length];
        System.arraycopy(parts, 0, result, 0, parts.length);
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    private static String stringJoin(MezzeSeq strings, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append((String) strings.get(i));
        }
        return sb.toString();
    }

    /**
     * Percent-encode a single URL component. Matches `encodeURIComponent`
     * (RFC 3986 unreserved set: A-Z a-z 0-9 - _ . ~). Spaces become "%20",
     * NOT "+" — the latter is form-encoding, not URL component encoding.
     */
    @TruffleBoundary
    private static String urlEncodeComponent(String s) {
        // java.net.URLEncoder uses application/x-www-form-urlencoded which
        // encodes spaces as "+" and uppercases hex digits — close to what we
        // want but we need RFC 3986 component semantics: " " -> "%20" and
        // unreserved characters (~_-.) left as-is.
        try {
            String formEncoded = java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
            return formEncoded
                .replace("+", "%20")
                .replace("%7E", "~")
                .replace("*", "%2A");  // URLEncoder leaves * alone; RFC says encode
        } catch (Exception e) {
            return s;
        }
    }

    @TruffleBoundary
    private static String urlDecodeComponent(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // ==================== Bool Operations ====================
    // Uses PrimitiveNatives with optimized Truffle nodes

    private static void registerBoolHandlers() {
        HANDLERS.put("__native_bool_to_str", (r, c) ->
            getBool(r, "b") ? "True" : "False");
        HANDLERS.put("__native_bool_and", (r, c) ->
            PrimitiveNatives.boolAnd(getBool(r, "l"), getBool(r, "r")));
        HANDLERS.put("__native_bool_or", (r, c) ->
            PrimitiveNatives.boolOr(getBool(r, "l"), getBool(r, "r")));
        HANDLERS.put("__native_bool_not", (r, c) ->
            PrimitiveNatives.boolNot(getBool(r, "b")));
        HANDLERS.put("__native_bool_xor", (r, c) ->
            PrimitiveNatives.boolXor(getBool(r, "l"), getBool(r, "r")));
        // Bool comparisons
        HANDLERS.put("__native_bool_eq", (r, c) ->
            PrimitiveNatives.boolEq(getBool(r, "l"), getBool(r, "r")));
        HANDLERS.put("__native_bool_neq", (r, c) ->
            PrimitiveNatives.boolNeq(getBool(r, "l"), getBool(r, "r")));
    }

    // ==================== Seq (new, MezzeSeq-backed) ====================

    private static void registerSeqHandlers() {
        HANDLERS.put("__native_seq_empty", (r, c) -> SeqNatives.empty());
        HANDLERS.put("__native_seq_size", (r, c) ->
            SeqNatives.length(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_is_empty", (r, c) ->
            SeqNatives.isEmpty(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_get", (r, c) ->
            SeqNatives.get(getSeq(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_seq_get_unchecked", (r, c) ->
            SeqNatives.getUnchecked(getSeq(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_seq_set", (r, c) ->
            SeqNatives.set(getSeq(r, "xs"), getLong(r, "i"), getField(r, "x")));
        HANDLERS.put("__native_seq_add", (r, c) ->
            SeqNatives.add(getSeq(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_seq_push", (r, c) ->
            SeqNatives.push(getSeq(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_seq_push_front", (r, c) ->
            SeqNatives.pushFront(getSeq(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_seq_remove", (r, c) ->
            SeqNatives.remove(getSeq(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_seq_concat", (r, c) ->
            SeqNatives.concat(getSeq(r, "xs"), getSeq(r, "ys")));
        HANDLERS.put("__native_seq_reverse", (r, c) ->
            SeqNatives.reverse(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_slice", (r, c) ->
            SeqNatives.slice(getSeq(r, "xs"), getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_seq_take", (r, c) ->
            SeqNatives.take(getSeq(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_seq_drop", (r, c) ->
            SeqNatives.drop(getSeq(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_seq_head", (r, c) ->
            SeqNatives.head(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_first", (r, c) ->
            SeqNatives.first(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_last", (r, c) ->
            SeqNatives.last(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_tail", (r, c) ->
            SeqNatives.tail(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_init", (r, c) ->
            SeqNatives.init(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_contains", (r, c) ->
            SeqNatives.contains(getSeq(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_seq_index_of", (r, c) ->
            SeqNatives.indexOf(getSeq(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_seq_sort", (r, c) ->
            SeqNatives.sort(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_unique", (r, c) ->
            SeqNatives.unique(getSeq(r, "xs"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_distinct", (r, c) ->
            SeqNatives.distinct(getSeq(r, "xs"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_flatten", (r, c) ->
            SeqNatives.flatten(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_equals", (r, c) ->
            SeqNatives.equals(getSeq(r, "l"), getSeq(r, "r"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_intersperse", (r, c) ->
            SeqNatives.intersperse(getSeq(r, "xs"), getField(r, "sep")));
        HANDLERS.put("__native_seq_chunk", (r, c) ->
            SeqNatives.chunk(getSeq(r, "xs"), getLong(r, "size")));
        HANDLERS.put("__native_seq_sliding", (r, c) ->
            SeqNatives.sliding(getSeq(r, "xs"), getLong(r, "size")));
        HANDLERS.put("__native_seq_range", (r, c) ->
            SeqNatives.range(getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_seq_range_step", (r, c) ->
            SeqNatives.rangeStep(getLong(r, "start"), getLong(r, "end"), getLong(r, "step")));
        HANDLERS.put("__native_seq_zip_with_index", (r, c) ->
            SeqNatives.zipWithIndex(getSeq(r, "xs")));

        // HOF operations — dispatched through HANDLERS (behind
        // @TruffleBoundary in CallNativeRecord) to prevent SVM
        // blocklist violations in native-image builds with
        // --macro:truffle-svm. FLP fusion bypasses handler dispatch
        // for the hot HOF chains, so this has no JVM perf impact.
        HANDLERS.put("__native_seq_map", (r, c) ->
            SeqNatives.map(getSeq(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_seq_filter", (r, c) ->
            SeqNatives.filter(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_flat_map", (r, c) ->
            SeqNatives.flatMap(getSeq(r, "xs"), getFunction(r, "f"), c));
        // Result-short-circuiting map: `[T], T -> Result U E → Result [U] E`.
        // Emitted by CodecGenerator for `[T]` decode (each element decode is
        // fallible; a single failure fails the whole list).
        HANDLERS.put("__native_seq_try_map", (r, c) ->
            SeqNatives.tryMap(getSeq(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_seq_fold", (r, c) ->
            SeqNatives.fold(getSeq(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_seq_reduce", (r, c) ->
            SeqNatives.reduce(getSeq(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_seq_find", (r, c) ->
            SeqNatives.find(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_find_index", (r, c) ->
            SeqNatives.findIndex(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_any", (r, c) ->
            SeqNatives.any(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_all", (r, c) ->
            SeqNatives.all(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_none", (r, c) ->
            SeqNatives.none(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_count", (r, c) ->
            SeqNatives.count(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_partition", (r, c) ->
            SeqNatives.partition(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_sort_by", (r, c) ->
            SeqNatives.sortBy(getSeq(r, "xs"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_take_while", (r, c) ->
            SeqNatives.takeWhile(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_drop_while", (r, c) ->
            SeqNatives.dropWhile(getSeq(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_seq_zip", (r, c) ->
            SeqNatives.zip(getSeq(r, "xs"), getSeq(r, "ys")));
        HANDLERS.put("__native_seq_unzip", (r, c) ->
            SeqNatives.unzip(getSeq(r, "xs")));

        // Min/Max/Sum/Product
        HANDLERS.put("__native_seq_min_by", (r, c) ->
            SeqNatives.minBy(getSeq(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_max_by", (r, c) ->
            SeqNatives.maxBy(getSeq(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_sum", (r, c) ->
            SeqNatives.sum(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_sum_float", (r, c) ->
            SeqNatives.sumFloat(getSeq(r, "xs")));

        // #417: SIMD-accelerated fold with primitive op.  Emitted by the
        // fold-fusion classifier in BytecodeCompiler when it proves the
        // folder collapses to a supported InlineOp on a materialized Seq.
        HANDLERS.put("__native_seq_fold_reduce_long", (r, c) ->
            SeqNatives.foldReduceLong(getSeq(r, "xs"), (int) getLong(r, "op"), getLong(r, "init")));
        HANDLERS.put("__native_seq_fold_reduce_double", (r, c) ->
            SeqNatives.foldReduceDouble(getSeq(r, "xs"), (int) getLong(r, "op"), getDouble(r, "init")));
        HANDLERS.put("__native_seq_product", (r, c) ->
            SeqNatives.product(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_product_float", (r, c) ->
            SeqNatives.productFloat(getSeq(r, "xs")));

        // Group By — HOF, PE-visible
        HANDLERS.put("__native_seq_group_by", (r, c) ->
            SeqNatives.groupBy(getSeq(r, "xs"), getFunction(r, "f"), c));

        // Enumerate/ForEach/Scan
        HANDLERS.put("__native_seq_enumerate", (r, c) ->
            SeqNatives.enumerate(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_for_each", (r, c) ->
            SeqNatives.forEach(getSeq(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_seq_scan", (r, c) ->
            SeqNatives.scan(getSeq(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));

        // Split Operations
        HANDLERS.put("__native_seq_split_at", (r, c) ->
            SeqNatives.splitAt(getSeq(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_seq_span", (r, c) ->
            SeqNatives.span(getSeq(r, "xs"), getFunction(r, "p"), c));

        // Dedup
        HANDLERS.put("__native_seq_dedup", (r, c) ->
            SeqNatives.dedup(getSeq(r, "xs"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_dedup_by", (r, c) ->
            SeqNatives.dedupBy(getSeq(r, "xs"), getFunction(r, "f"), getFunction(r, "eq"), c));

        // Singleton/Nth/Fill
        HANDLERS.put("__native_seq_singleton", (r, c) ->
            SeqNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_seq_nth", (r, c) ->
            SeqNatives.nth(getSeq(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_seq_fill", (r, c) ->
            SeqNatives.fill(getLong(r, "n"), getField(r, "x")));
        HANDLERS.put("__native_seq_fill_with", (r, c) ->
            SeqNatives.fillWith(getLong(r, "n"), getFunction(r, "f"), c));

        // Group Consecutive
        HANDLERS.put("__native_seq_group_consecutive", (r, c) ->
            SeqNatives.groupConsecutive(getSeq(r, "xs"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_group_consecutive_by", (r, c) ->
            SeqNatives.groupConsecutiveBy(getSeq(r, "xs"), getFunction(r, "f"), getFunction(r, "eq"), c));

        // Windows/Chunks/Rotate
        HANDLERS.put("__native_seq_windows", (r, c) ->
            SeqNatives.windows(getSeq(r, "xs"), getLong(r, "size")));
        HANDLERS.put("__native_seq_chunks_exact", (r, c) ->
            SeqNatives.chunksExact(getSeq(r, "xs"), getLong(r, "size")));
        HANDLERS.put("__native_seq_rotate_left", (r, c) ->
            SeqNatives.rotateLeft(getSeq(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_seq_rotate_right", (r, c) ->
            SeqNatives.rotateRight(getSeq(r, "xs"), getLong(r, "n")));

        // Binary Search
        HANDLERS.put("__native_seq_binary_search_by", (r, c) ->
            SeqNatives.binarySearchBy(getSeq(r, "xs"), getField(r, "x"), getFunction(r, "cmp"), c));

        // Sort By Key
        HANDLERS.put("__native_seq_sort_by_key", (r, c) ->
            SeqNatives.sortByKey(getSeq(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));

        // Additional Search/Replace Operations
        HANDLERS.put("__native_seq_last_index_of", (r, c) ->
            SeqNatives.lastIndexOf(getSeq(r, "xs"), getField(r, "x"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_replace", (r, c) ->
            SeqNatives.replace(getSeq(r, "xs"), getField(r, "old"), getField(r, "new_val"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_replace_all", (r, c) ->
            SeqNatives.replaceAll(getSeq(r, "xs"), getField(r, "old"), getField(r, "new_val"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_insert_at", (r, c) ->
            SeqNatives.insertAt(getSeq(r, "xs"), getLong(r, "i"), getField(r, "x")));
        HANDLERS.put("__native_seq_swap", (r, c) ->
            SeqNatives.swap(getSeq(r, "xs"), getLong(r, "i"), getLong(r, "j")));
        HANDLERS.put("__native_seq_starts_with", (r, c) ->
            SeqNatives.startsWith(getSeq(r, "xs"), getSeq(r, "prefix"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_ends_with", (r, c) ->
            SeqNatives.endsWith(getSeq(r, "xs"), getSeq(r, "suffix"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_seq_join", (r, c) ->
            SeqNatives.join(getSeq(r, "xs"), getString(r, "sep")));

        // Advanced Zip Operations
        HANDLERS.put("__native_seq_zip_with", (r, c) ->
            SeqNatives.zipWith(getSeq(r, "xs"), getSeq(r, "ys"), getFunction(r, "f")));
        HANDLERS.put("__native_seq_zip3", (r, c) ->
            SeqNatives.zip3(getSeq(r, "xs"), getSeq(r, "ys"), getSeq(r, "zs")));
        HANDLERS.put("__native_seq_zip4", (r, c) ->
            SeqNatives.zip4(getSeq(r, "xs"), getSeq(r, "ys"), getSeq(r, "zs"), getSeq(r, "ws")));
        HANDLERS.put("__native_seq_cartesian_product", (r, c) ->
            SeqNatives.cartesianProduct(getSeq(r, "xs"), getSeq(r, "ys")));

        // Combinatorics
        HANDLERS.put("__native_seq_permutations", (r, c) ->
            SeqNatives.permutations(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_combinations", (r, c) ->
            SeqNatives.combinations(getSeq(r, "xs"), getLong(r, "k")));
        HANDLERS.put("__native_seq_subsequences", (r, c) ->
            SeqNatives.subsequences(getSeq(r, "xs")));

        // Statistical Operations
        HANDLERS.put("__native_seq_average", (r, c) ->
            SeqNatives.average(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_variance", (r, c) ->
            SeqNatives.variance(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_stddev", (r, c) ->
            SeqNatives.stddev(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_median", (r, c) ->
            SeqNatives.median(getSeq(r, "xs")));

        // Sorting Checks
        HANDLERS.put("__native_seq_is_sorted", (r, c) ->
            SeqNatives.isSorted(getSeq(r, "xs"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_is_sorted_by", (r, c) ->
            SeqNatives.isSortedBy(getSeq(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_is_sorted_descending", (r, c) ->
            SeqNatives.isSortedDescending(getSeq(r, "xs"), getFunction(r, "cmp"), c));

        // Counting

        // Update Operations
        HANDLERS.put("__native_seq_update", (r, c) ->
            SeqNatives.updateAt(getSeq(r, "xs"), getLong(r, "i"), getFunction(r, "f")));
        HANDLERS.put("__native_seq_remove_at", (r, c) ->
            SeqNatives.removeAt(getSeq(r, "xs"), getLong(r, "i")));

        // Split Operations
        HANDLERS.put("__native_seq_split", (r, c) ->
            SeqNatives.split(getSeq(r, "xs"), getField(r, "sep"), getFunction(r, "eq"), c));

        // Float-specific Operations
        HANDLERS.put("__native_seq_min_float", (r, c) ->
            SeqNatives.minFloat(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_max_float", (r, c) ->
            SeqNatives.maxFloat(getSeq(r, "xs")));

        // Cross-Collection Conversions
        HANDLERS.put("__native_seq_to_list", (r, c) ->
            SeqNatives.toList(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_to_set", (r, c) ->
            SeqNatives.toSet(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_to_deque", (r, c) ->
            SeqNatives.toDeque(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_to_sorted_set", (r, c) ->
            SeqNatives.toSortedSet(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_to_map", (r, c) ->
            SeqNatives.toMap(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_to_sorted_map", (r, c) ->
            SeqNatives.toSortedMap(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_from_list", (r, c) ->
            SeqNatives.fromList(getList(r, "xs")));
        HANDLERS.put("__native_seq_from_set", (r, c) ->
            SeqNatives.fromSet(getSet(r, "s")));
        HANDLERS.put("__native_seq_from_deque", (r, c) ->
            SeqNatives.fromDeque(getDeque(r, "d")));
        HANDLERS.put("__native_seq_from_sorted_set", (r, c) ->
            SeqNatives.fromSortedSet(getSortedSet(r, "s")));
        HANDLERS.put("__native_seq_from_map", (r, c) ->
            SeqNatives.fromMap(getMap(r, "m")));
        HANDLERS.put("__native_seq_from_sorted_map", (r, c) ->
            SeqNatives.fromSortedMap(getSortedMap(r, "m")));

        // Binary search operations
        HANDLERS.put("__native_seq_binary_search_insert_point", (r, c) ->
            SeqNatives.binarySearchInsertPoint(getSeq(r, "xs"), getField(r, "x"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_lower_bound", (r, c) ->
            SeqNatives.lowerBound(getSeq(r, "xs"), getField(r, "x"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_upper_bound", (r, c) ->
            SeqNatives.upperBound(getSeq(r, "xs"), getField(r, "x"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_seq_equal_range", (r, c) ->
            SeqNatives.equalRange(getSeq(r, "xs"), getField(r, "x"), getFunction(r, "cmp"), c));

        // Lexicographic comparison (returns -1, 0, 1)
        HANDLERS.put("__native_seq_compare", (r, c) ->
            SeqNatives.compareSeqs(getSeq(r, "xs"), getSeq(r, "ys"), getFunction(r, "cmp"), c));

        // Additional combinatorics
        HANDLERS.put("__native_seq_subsequences_non_empty", (r, c) ->
            SeqNatives.subsequencesNonEmpty(getSeq(r, "xs")));
        HANDLERS.put("__native_seq_contiguous_subsequences", (r, c) ->
            SeqNatives.contiguousSubsequences(getSeq(r, "xs")));
    }

    // ==================== Iter (lazy, MezzeIter-backed) ====================

    private static com.mezze.datastructures.iter.MezzeIter getIter(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof com.mezze.datastructures.iter.MezzeIter it) return it;
        throw new NativeTypeMismatchException("Expected MezzeIter for field '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getSimpleName()));
    }

    private static void registerIterHandlers() {
        HANDLERS.put("__native_iter_range", (r, c) ->
            IterNatives.range(getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_iter_range_step", (r, c) ->
            IterNatives.rangeStep(getLong(r, "start"), getLong(r, "end"), getLong(r, "step")));
        HANDLERS.put("__native_iter_from_seq", (r, c) ->
            IterNatives.fromSeq(getSeq(r, "xs")));
        HANDLERS.put("__native_iter_map", (r, c) ->
            IterNatives.map(getIter(r, "it"), getFunction(r, "f")));
        HANDLERS.put("__native_iter_filter", (r, c) ->
            IterNatives.filter(getIter(r, "it"), getFunction(r, "p")));
        HANDLERS.put("__native_iter_fold", (r, c) ->
            IterNatives.fold(getIter(r, "it"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_iter_take", (r, c) ->
            IterNatives.take(getIter(r, "it"), getLong(r, "n")));
        HANDLERS.put("__native_iter_drop", (r, c) ->
            IterNatives.drop(getIter(r, "it"), getLong(r, "n")));
        HANDLERS.put("__native_iter_sum", (r, c) ->
            IterNatives.sum(getIter(r, "it")));
        HANDLERS.put("__native_iter_for_each", (r, c) ->
            IterNatives.forEach(getIter(r, "it"), getFunction(r, "f"), c));
        HANDLERS.put("__native_iter_count", (r, c) ->
            IterNatives.count(getIter(r, "it"), getFunction(r, "p"), c));
        HANDLERS.put("__native_iter_any", (r, c) ->
            IterNatives.any(getIter(r, "it"), getFunction(r, "p"), c));
        HANDLERS.put("__native_iter_all", (r, c) ->
            IterNatives.all(getIter(r, "it"), getFunction(r, "p"), c));
        HANDLERS.put("__native_iter_find", (r, c) ->
            IterNatives.find(getIter(r, "it"), getFunction(r, "p"), c));
        HANDLERS.put("__native_iter_reduce", (r, c) ->
            IterNatives.reduce(getIter(r, "it"), getFunction(r, "f"), c));
        HANDLERS.put("__native_iter_to_seq", (r, c) ->
            IterNatives.toSeq(getIter(r, "it"), c));
        HANDLERS.put("__native_iter_to_list", (r, c) ->
            IterNatives.toList(getIter(r, "it"), c));

        // New constructors
        HANDLERS.put("__native_iter_from_list", (r, c) ->
            IterNatives.fromList(getList(r, "xs")));
        HANDLERS.put("__native_iter_unfold", (r, c) ->
            IterNatives.unfold(getField(r, "state"), getFunction(r, "step")));
        HANDLERS.put("__native_iter_once", (r, c) ->
            IterNatives.once(getField(r, "val")));
        HANDLERS.put("__native_iter_empty", (r, c) ->
            IterNatives.empty());

        // New stages
        HANDLERS.put("__native_iter_take_while", (r, c) ->
            IterNatives.takeWhile(getIter(r, "it"), getFunction(r, "p")));
        HANDLERS.put("__native_iter_drop_while", (r, c) ->
            IterNatives.dropWhile(getIter(r, "it"), getFunction(r, "p")));
        HANDLERS.put("__native_iter_enumerate", (r, c) ->
            IterNatives.enumerate(getIter(r, "it")));
        HANDLERS.put("__native_iter_chain", (r, c) ->
            IterNatives.chain(getIter(r, "a"), getIter(r, "b")));
        HANDLERS.put("__native_iter_zip", (r, c) ->
            IterNatives.zip(getIter(r, "a"), getIter(r, "b")));
        HANDLERS.put("__native_iter_flat_map", (r, c) ->
            IterNatives.flatMap(getIter(r, "it"), getFunction(r, "f")));

        // New sinks
        HANDLERS.put("__native_iter_count_all", (r, c) ->
            IterNatives.countAll(getIter(r, "it"), c));
        HANDLERS.put("__native_iter_first", (r, c) ->
            IterNatives.first(getIter(r, "it"), c));
        HANDLERS.put("__native_iter_last", (r, c) ->
            IterNatives.last(getIter(r, "it"), c));
    }

    // ==================== List ====================

    private static void registerListHandlers() {
        HANDLERS.put("__native_list_empty", (r, c) -> ListNatives.empty());
        HANDLERS.put("__native_list_size", (r, c) ->
            ListNatives.length(getList(r, "xs")));
        HANDLERS.put("__native_list_is_empty", (r, c) ->
            ListNatives.isEmpty(getList(r, "xs")));
        HANDLERS.put("__native_list_head", (r, c) ->
            ListNatives.head(getList(r, "xs")));
        HANDLERS.put("__native_list_tail", (r, c) ->
            ListNatives.tail(getList(r, "xs")));
        HANDLERS.put("__native_list_last", (r, c) ->
            ListNatives.last(getList(r, "xs")));
        HANDLERS.put("__native_list_init", (r, c) ->
            ListNatives.init(getList(r, "xs")));
        HANDLERS.put("__native_list_cons", (r, c) ->
            ListNatives.cons(getField(r, "head"), getList(r, "tail")));
        HANDLERS.put("__native_list_append", (r, c) ->
            ListNatives.concat2(getList(r, "xs"), getList(r, "ys")));
        HANDLERS.put("__native_list_uncons", (r, c) ->
            ListNatives.uncons(getList(r, "xs")));
        HANDLERS.put("__native_list_get", (r, c) ->
            ListNatives.get(getList(r, "xs"), getLong(r, "index")));
        HANDLERS.put("__native_list_concat", (r, c) ->
            ListNatives.concat(getList(r, "xss")));
        HANDLERS.put("__native_list_take", (r, c) ->
            ListNatives.take(getList(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_list_drop", (r, c) ->
            ListNatives.drop(getList(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_list_reverse", (r, c) ->
            ListNatives.reverse(getList(r, "xs")));
        HANDLERS.put("__native_list_sort", (r, c) ->
            ListNatives.sort(getList(r, "xs")));
        HANDLERS.put("__native_list_range", (r, c) ->
            ListNatives.range(getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_list_repeat", (r, c) ->
            ListNatives.repeat(getField(r, "x"), getLong(r, "n")));
        HANDLERS.put("__native_list_intersperse", (r, c) ->
            ListNatives.intersperse(getList(r, "xs"), getField(r, "sep")));
        HANDLERS.put("__native_list_to_seq", (r, c) ->
            ListNatives.toSeq(getList(r, "xs")));
        HANDLERS.put("__native_list_zip", (r, c) ->
            ListNatives.zip(getList(r, "xs"), getList(r, "ys")));
        HANDLERS.put("__native_list_unzip", (r, c) ->
            ListNatives.unzip(getList(r, "xs")));

        // HOF operations — dispatched through HANDLERS (behind
        // @TruffleBoundary in CallNativeRecord) to prevent SVM
        // blocklist violations in native-image builds with
        // --macro:truffle-svm. FLP fusion bypasses handler dispatch
        // for the hot HOF chains, so this has no JVM perf impact.
        HANDLERS.put("__native_list_map", (r, c) ->
            ListNatives.map(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_filter", (r, c) ->
            ListNatives.filter(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_fold_left", (r, c) ->
            ListNatives.foldLeft(getList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_fold_right", (r, c) ->
            ListNatives.foldRight(getList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_reduce", (r, c) ->
            ListNatives.reduce(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_find", (r, c) ->
            ListNatives.find(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_find_index", (r, c) ->
            ListNatives.findIndex(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_any", (r, c) ->
            ListNatives.any(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_all", (r, c) ->
            ListNatives.all(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_partition", (r, c) ->
            ListNatives.partition(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_sort_by", (r, c) ->
            ListNatives.sortBy(getList(r, "xs"), getFunction(r, "cmp"), c));

        // Min/Max/Sum/Product
        HANDLERS.put("__native_list_min_by", (r, c) ->
            ListNatives.minBy(getList(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_max_by", (r, c) ->
            ListNatives.maxBy(getList(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_sum", (r, c) ->
            ListNatives.sum(getList(r, "xs")));
        HANDLERS.put("__native_list_sum_float", (r, c) ->
            ListNatives.sumFloat(getList(r, "xs")));
        HANDLERS.put("__native_list_product", (r, c) ->
            ListNatives.product(getList(r, "xs")));
        HANDLERS.put("__native_list_product_float", (r, c) ->
            ListNatives.productFloat(getList(r, "xs")));

        // Group By
        HANDLERS.put("__native_list_group_by", (r, c) ->
            ListNatives.groupBy(getList(r, "xs"), getFunction(r, "f"), c));

        // Enumerate/ForEach/Scan/ZipWithIndex
        HANDLERS.put("__native_list_enumerate", (r, c) ->
            ListNatives.enumerate(getList(r, "xs")));
        HANDLERS.put("__native_list_zip_with_index", (r, c) ->
            ListNatives.zipWithIndex(getList(r, "xs")));
        HANDLERS.put("__native_list_for_each", (r, c) ->
            ListNatives.forEach(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_scan", (r, c) ->
            ListNatives.scan(getList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));

        // Split Operations
        HANDLERS.put("__native_list_split_at", (r, c) ->
            ListNatives.splitAt(getList(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_list_span", (r, c) ->
            ListNatives.span(getList(r, "xs"), getFunction(r, "p"), c));

        // Dedup
        HANDLERS.put("__native_list_dedup", (r, c) ->
            ListNatives.dedup(getList(r, "xs"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_list_dedup_by", (r, c) ->
            ListNatives.dedupBy(getList(r, "xs"), getFunction(r, "f"), getFunction(r, "eq"), c));

        // Singleton/Nth/Fill
        HANDLERS.put("__native_list_singleton", (r, c) ->
            ListNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_list_nth", (r, c) ->
            ListNatives.nth(getList(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_list_fill", (r, c) ->
            ListNatives.fill(getLong(r, "n"), getField(r, "x")));
        HANDLERS.put("__native_list_fill_with", (r, c) ->
            ListNatives.fillWith(getLong(r, "n"), getFunction(r, "f"), c));

        // Group Consecutive
        HANDLERS.put("__native_list_group_consecutive", (r, c) ->
            ListNatives.groupConsecutive(getList(r, "xs"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_list_group_consecutive_by", (r, c) ->
            ListNatives.groupConsecutiveBy(getList(r, "xs"), getFunction(r, "f"), getFunction(r, "eq"), c));

        // FlatMap/TakeWhile/DropWhile
        HANDLERS.put("__native_list_flat_map", (r, c) ->
            ListNatives.flatMap(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_take_while", (r, c) ->
            ListNatives.takeWhile(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_drop_while", (r, c) ->
            ListNatives.dropWhile(getList(r, "xs"), getFunction(r, "p"), c));

        // None/Count
        HANDLERS.put("__native_list_none", (r, c) ->
            ListNatives.none(getList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_list_count", (r, c) ->
            ListNatives.count(getList(r, "xs"), getFunction(r, "p"), c));

        // Unique/Distinct/Flatten
        HANDLERS.put("__native_list_unique", (r, c) ->
            ListNatives.unique(getList(r, "xs")));
        HANDLERS.put("__native_list_distinct", (r, c) ->
            ListNatives.distinct(getList(r, "xs")));
        HANDLERS.put("__native_list_flatten", (r, c) ->
            ListNatives.flatten(getList(r, "xss")));

        // Task #17: Additional operations
        HANDLERS.put("__native_list_equals", (r, c) ->
            ListNatives.equals(getList(r, "xs"), getList(r, "ys"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_list_last_index_of", (r, c) ->
            ListNatives.lastIndexOf(getList(r, "xs"), getField(r, "x"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_list_slice", (r, c) ->
            ListNatives.slice(getList(r, "xs"), getLong(r, "start"), getLong(r, "end")));

        // Task #18: Windowing operations
        HANDLERS.put("__native_list_chunk", (r, c) ->
            ListNatives.chunk(getList(r, "xs"), getLong(r, "size")));
        HANDLERS.put("__native_list_windows", (r, c) ->
            ListNatives.windows(getList(r, "xs"), getLong(r, "size")));
        HANDLERS.put("__native_list_sliding", (r, c) ->
            ListNatives.sliding(getList(r, "xs"), getLong(r, "size"), getLong(r, "step")));
        HANDLERS.put("__native_list_chunks_exact", (r, c) ->
            ListNatives.chunksExact(getList(r, "xs"), getLong(r, "size")));

        // Task #19: Transformation operations
        HANDLERS.put("__native_list_sort_by_key", (r, c) ->
            ListNatives.sortByKey(getList(r, "xs"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_rotate_left", (r, c) ->
            ListNatives.rotateLeft(getList(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_list_rotate_right", (r, c) ->
            ListNatives.rotateRight(getList(r, "xs"), getLong(r, "n")));

        // Task #27: Cross-collection conversions (List to other types)
        HANDLERS.put("__native_list_to_seq", (r, c) ->
            ListNatives.toSeq(getList(r, "xs")));
        HANDLERS.put("__native_list_to_set", (r, c) ->
            ListNatives.toSet(getList(r, "xs")));
        HANDLERS.put("__native_list_to_deque", (r, c) ->
            ListNatives.toDeque(getList(r, "xs")));
        HANDLERS.put("__native_list_to_sorted_set", (r, c) ->
            ListNatives.toSortedSet(getList(r, "xs"), getFunction(r, "cmp")));
        HANDLERS.put("__native_list_to_map", (r, c) ->
            ListNatives.toMap(getList(r, "xs")));
        HANDLERS.put("__native_list_to_sorted_map", (r, c) ->
            ListNatives.toSortedMap(getList(r, "xs"), getFunction(r, "cmp")));
        HANDLERS.put("__native_list_from_seq", (r, c) ->
            ListNatives.fromSeq(getSeq(r, "xs")));
        HANDLERS.put("__native_list_from_set", (r, c) ->
            ListNatives.fromSet(getSet(r, "xs")));
        HANDLERS.put("__native_list_from_deque", (r, c) ->
            ListNatives.fromDeque(getDeque(r, "xs")));
        HANDLERS.put("__native_list_from_sorted_set", (r, c) ->
            ListNatives.fromSortedSet(getSortedSet(r, "xs")));
        HANDLERS.put("__native_list_from_map", (r, c) ->
            ListNatives.fromMap(getMap(r, "m")));
        HANDLERS.put("__native_list_from_sorted_map", (r, c) ->
            ListNatives.fromSortedMap(getSortedMap(r, "m")));

        // Task #32: Numeric operations
        HANDLERS.put("__native_list_average", (r, c) ->
            ListNatives.average(getList(r, "xs")));
        HANDLERS.put("__native_list_variance", (r, c) ->
            ListNatives.variance(getList(r, "xs")));
        HANDLERS.put("__native_list_stddev", (r, c) ->
            ListNatives.stddev(getList(r, "xs")));
        HANDLERS.put("__native_list_median", (r, c) ->
            ListNatives.median(getList(r, "xs")));
        HANDLERS.put("__native_list_sum_float", (r, c) ->
            ListNatives.sumFloat(getList(r, "xs")));
        HANDLERS.put("__native_list_product_float", (r, c) ->
            ListNatives.productFloat(getList(r, "xs")));

        // Task #36: Comparison operations
        HANDLERS.put("__native_list_lexicographic_compare", (r, c) ->
            ListNatives.lexicographicCompare(getList(r, "xs"), getList(r, "ys"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_is_sorted_by", (r, c) ->
            ListNatives.isSortedBy(getList(r, "xs"), getFunction(r, "cmp"), c));

        // Task #33: Binary search operations
        HANDLERS.put("__native_list_binary_search", (r, c) ->
            ListNatives.binarySearch(getList(r, "xs"), getField(r, "target"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_binary_search_insert_point", (r, c) ->
            ListNatives.binarySearchInsertPoint(getList(r, "xs"), getField(r, "target"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_lower_bound", (r, c) ->
            ListNatives.lowerBound(getList(r, "xs"), getField(r, "target"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_upper_bound", (r, c) ->
            ListNatives.upperBound(getList(r, "xs"), getField(r, "target"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_equal_range", (r, c) ->
            ListNatives.equalRange(getList(r, "xs"), getField(r, "target"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_list_binary_search_by", (r, c) ->
            ListNatives.binarySearchBy(getList(r, "xs"), getField(r, "target"), getFunction(r, "cmp"), c));
    }

    // ==================== Set ====================

    private static void registerSetHandlers() {
        HANDLERS.put("__native_set_empty", (r, c) -> SetNatives.empty());
        HANDLERS.put("__native_set_size", (r, c) ->
            SetNatives.size(getSet(r, "s")));
        HANDLERS.put("__native_set_is_empty", (r, c) ->
            SetNatives.isEmpty(getSet(r, "s")));
        HANDLERS.put("__native_set_contains", (r, c) ->
            SetNatives.contains(getSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_set_insert", (r, c) ->
            SetNatives.insert(getSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_set_remove", (r, c) ->
            SetNatives.remove(getSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_set_insert_all", (r, c) ->
            SetNatives.insertAll(getSet(r, "s"), getArray(r, "xs")));
        HANDLERS.put("__native_set_remove_all", (r, c) ->
            SetNatives.removeAll(getSet(r, "s"), getArray(r, "xs")));
        HANDLERS.put("__native_set_union", (r, c) ->
            SetNatives.union(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_intersection", (r, c) ->
            SetNatives.intersection(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_difference", (r, c) ->
            SetNatives.difference(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_is_subset", (r, c) ->
            SetNatives.isSubset(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_is_superset", (r, c) ->
            SetNatives.isSuperset(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_is_disjoint", (r, c) ->
            SetNatives.isDisjoint(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_equals", (r, c) ->
            SetNatives.equals(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_for_each", (r, c) ->
            SetNatives.forEach(getSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_to_seq", (r, c) ->
            SetNatives.toArray(getSet(r, "s")));
        HANDLERS.put("__native_set_to_list", (r, c) ->
            SetNatives.toList(getSet(r, "s")));
        HANDLERS.put("__native_set_from_seq", (r, c) ->
            SetNatives.fromArray(getArray(r, "xs")));
        HANDLERS.put("__native_set_from_list", (r, c) ->
            SetNatives.fromList(getList(r, "xs")));

        // HOF operations
        HANDLERS.put("__native_set_filter", (r, c) ->
            SetNatives.filter(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_map", (r, c) ->
            SetNatives.map(getSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_fold", (r, c) ->
            SetNatives.fold(getSet(r, "s"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_any", (r, c) ->
            SetNatives.any(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_all", (r, c) ->
            SetNatives.all(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_none", (r, c) ->
            SetNatives.none(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_find", (r, c) ->
            SetNatives.find(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_count", (r, c) ->
            SetNatives.count(getSet(r, "s"), getFunction(r, "p"), c));

        // Min/Max/Sum/Product
        HANDLERS.put("__native_set_sum", (r, c) ->
            SetNatives.sum(getSet(r, "s")));
        HANDLERS.put("__native_set_product", (r, c) ->
            SetNatives.product(getSet(r, "s")));

        // Conversion/Factory
        HANDLERS.put("__native_set_to_seq", (r, c) ->
            SetNatives.toSeq(getSet(r, "s")));
        HANDLERS.put("__native_set_singleton", (r, c) ->
            SetNatives.singleton(getField(r, "x")));

        // Additional operations
        HANDLERS.put("__native_set_reduce", (r, c) ->
            SetNatives.reduce(getSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_min_by", (r, c) ->
            SetNatives.minBy(getSet(r, "s"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_set_max_by", (r, c) ->
            SetNatives.maxBy(getSet(r, "s"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_set_partition", (r, c) ->
            SetNatives.partition(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_sum_float", (r, c) ->
            SetNatives.sumFloat(getSet(r, "s")));
        HANDLERS.put("__native_set_product_float", (r, c) ->
            SetNatives.productFloat(getSet(r, "s")));

        // Task #20: Additional operations
        HANDLERS.put("__native_set_flat_map", (r, c) ->
            SetNatives.flatMap(getSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_group_by", (r, c) ->
            SetNatives.groupBy(getSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_symmetric_difference", (r, c) ->
            SetNatives.symmetricDifference(getSet(r, "s1"), getSet(r, "s2")));

        // Task #30: Collect operations
        HANDLERS.put("__native_set_filter_map", (r, c) ->
            SetNatives.filterMap(getSet(r, "set"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_collect_seq", (r, c) ->
            SetNatives.collectArray(getSet(r, "set"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_collect_list", (r, c) ->
            SetNatives.collectList(getSet(r, "set"), getFunction(r, "f"), c));

        // SortedSet conversions
        HANDLERS.put("__native_set_to_sorted_set", (r, c) ->
            SetNatives.toSortedSet(getSet(r, "s")));
        HANDLERS.put("__native_set_from_sorted_set", (r, c) ->
            SetNatives.fromSortedSet(getSortedSet(r, "s")));
    }

    // ==================== SortedSet ====================

    private static void registerSortedSetHandlers() {
        HANDLERS.put("__native_sorted_set_empty", (r, c) -> SortedSetNatives.empty());
        HANDLERS.put("__native_sorted_set_singleton", (r, c) ->
            SortedSetNatives.singleton(getField(r, "x"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_set_size", (r, c) ->
            SortedSetNatives.size(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_is_empty", (r, c) ->
            SortedSetNatives.isEmpty(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_contains", (r, c) ->
            SortedSetNatives.contains(getSortedSet(r, "s"), getField(r, "x"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_set_add", (r, c) ->
            SortedSetNatives.add(getSortedSet(r, "s"), getField(r, "x"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_set_remove", (r, c) ->
            SortedSetNatives.remove(getSortedSet(r, "s"), getField(r, "x"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_set_min", (r, c) ->
            SortedSetNatives.min(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_max", (r, c) ->
            SortedSetNatives.max(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_union", (r, c) ->
            SortedSetNatives.union(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_intersect", (r, c) ->
            SortedSetNatives.intersect(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_difference", (r, c) ->
            SortedSetNatives.difference(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_equals", (r, c) ->
            SortedSetNatives.equals(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_compare", (r, c) ->
            SortedSetNatives.compare(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_to_list", (r, c) ->
            SortedSetNatives.toList(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_to_set", (r, c) ->
            SortedSetNatives.toSet(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_from_list", (r, c) ->
            SortedSetNatives.fromList(getList(r, "xs"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_set_from_set", (r, c) ->
            SortedSetNatives.fromSet(getSet(r, "s")));

        // HOF operations
        HANDLERS.put("__native_sorted_set_filter", (r, c) ->
            SortedSetNatives.filter(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_fold", (r, c) ->
            SortedSetNatives.fold(getSortedSet(r, "s"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_set_map", (r, c) ->
            SortedSetNatives.map(getSortedSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_set_any", (r, c) ->
            SortedSetNatives.any(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_all", (r, c) ->
            SortedSetNatives.all(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_none", (r, c) ->
            SortedSetNatives.none(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_find", (r, c) ->
            SortedSetNatives.find(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_count", (r, c) ->
            SortedSetNatives.count(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_reduce", (r, c) ->
            SortedSetNatives.reduce(getSortedSet(r, "s"), getFunction(r, "f"), c));

        // Aggregation
        HANDLERS.put("__native_sorted_set_sum", (r, c) ->
            SortedSetNatives.sum(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_sum_float", (r, c) ->
            SortedSetNatives.sumFloat(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_product", (r, c) ->
            SortedSetNatives.product(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_product_float", (r, c) ->
            SortedSetNatives.productFloat(getSortedSet(r, "s")));

        // Conversion
        HANDLERS.put("__native_sorted_set_to_seq", (r, c) ->
            SortedSetNatives.toSeq(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_from_seq", (r, c) ->
            SortedSetNatives.fromArray(getArray(r, "xs"), getFunction(r, "cmp")));

        // ForEach
        HANDLERS.put("__native_sorted_set_for_each", (r, c) ->
            SortedSetNatives.forEach(getSortedSet(r, "s"), getFunction(r, "f"), c));

        // Task #13: Additional operations
        HANDLERS.put("__native_sorted_set_partition", (r, c) ->
            SortedSetNatives.partition(getSortedSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_set_min_by", (r, c) ->
            SortedSetNatives.minBy(getSortedSet(r, "s"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_sorted_set_max_by", (r, c) ->
            SortedSetNatives.maxBy(getSortedSet(r, "s"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_sorted_set_take", (r, c) ->
            SortedSetNatives.take(getSortedSet(r, "s"), getLong(r, "n")));
        HANDLERS.put("__native_sorted_set_drop", (r, c) ->
            SortedSetNatives.drop(getSortedSet(r, "s"), getLong(r, "n")));
        HANDLERS.put("__native_sorted_set_range", (r, c) ->
            SortedSetNatives.range(getSortedSet(r, "s"), getField(r, "lo"), getField(r, "hi")));

        // Task #14: Advanced operations
        HANDLERS.put("__native_sorted_set_symmetric_difference", (r, c) ->
            SortedSetNatives.symmetricDifference(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_head_set", (r, c) ->
            SortedSetNatives.headSet(getSortedSet(r, "s"), getField(r, "to")));
        HANDLERS.put("__native_sorted_set_tail_set", (r, c) ->
            SortedSetNatives.tailSet(getSortedSet(r, "s"), getField(r, "from")));

        // Task #30: Collect operations
        HANDLERS.put("__native_sorted_set_filter_map", (r, c) ->
            SortedSetNatives.filterMap(getSortedSet(r, "set"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_set_collect_seq", (r, c) ->
            SortedSetNatives.collectArray(getSortedSet(r, "set"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_set_collect_list", (r, c) ->
            SortedSetNatives.collectList(getSortedSet(r, "set"), getFunction(r, "f"), c));
    }

    // ==================== Map ====================

    private static void registerMapHandlers() {
        HANDLERS.put("__native_map_empty", (r, c) -> MapNatives.empty());
        HANDLERS.put("__native_map_size", (r, c) ->
            MapNatives.size(getMap(r, "m")));
        HANDLERS.put("__native_map_is_empty", (r, c) ->
            MapNatives.isEmpty(getMap(r, "m")));
        HANDLERS.put("__native_map_contains_key", (r, c) ->
            MapNatives.containsKey(getMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_map_get", (r, c) ->
            MapNatives.get(getMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_map_insert", (r, c) ->
            MapNatives.insert(getMap(r, "m"), getField(r, "k"), getField(r, "v")));
        HANDLERS.put("__native_map_remove", (r, c) ->
            MapNatives.remove(getMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_map_remove_all", (r, c) ->
            MapNatives.removeAll(getMap(r, "m"), listToObjectArray(getList(r, "ks"))));
        HANDLERS.put("__native_map_keys", (r, c) ->
            MapNatives.keys(getMap(r, "m")));
        HANDLERS.put("__native_map_values", (r, c) ->
            MapNatives.values(getMap(r, "m")));
        HANDLERS.put("__native_map_entries", (r, c) ->
            MapNatives.entries(getMap(r, "m")));
        HANDLERS.put("__native_map_merge", (r, c) ->
            MapNatives.merge(getMap(r, "m1"), getMap(r, "m2")));
        HANDLERS.put("__native_map_equals", (r, c) ->
            MapNatives.equals(getMap(r, "m1"), getMap(r, "m2")));

        // HOF operations
        HANDLERS.put("__native_map_filter", (r, c) ->
            MapNatives.filter(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_map_values", (r, c) ->
            MapNatives.mapValues(getMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_fold", (r, c) ->
            MapNatives.fold(getMap(r, "m"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_merge_with", (r, c) ->
            MapNatives.mergeWith(getMap(r, "m1"), getMap(r, "m2"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_from_keys", (r, c) ->
            MapNatives.fromKeys(listToObjectArray(getList(r, "ks")), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_any", (r, c) ->
            MapNatives.any(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_all", (r, c) ->
            MapNatives.all(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_none", (r, c) ->
            MapNatives.none(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_find", (r, c) ->
            MapNatives.find(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_count", (r, c) ->
            MapNatives.count(getMap(r, "m"), getFunction(r, "p"), c));

        // Additional operations
        HANDLERS.put("__native_map_contains_value", (r, c) ->
            MapNatives.containsValue(getMap(r, "m"), getField(r, "v"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_map_update", (r, c) ->
            MapNatives.update(getMap(r, "m"), getField(r, "k"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_get_or_default", (r, c) ->
            MapNatives.getOrDefault(getMap(r, "m"), getField(r, "k"), getField(r, "default")));
        HANDLERS.put("__native_map_to_seq", (r, c) ->
            MapNatives.toSeq(getMap(r, "m")));
        HANDLERS.put("__native_map_keys_seq", (r, c) ->
            MapNatives.keysSeq(getMap(r, "m")));
        HANDLERS.put("__native_map_values_seq", (r, c) ->
            MapNatives.valuesSeq(getMap(r, "m")));
        HANDLERS.put("__native_map_singleton", (r, c) ->
            MapNatives.singleton(getField(r, "k"), getField(r, "v")));
        HANDLERS.put("__native_map_partition", (r, c) ->
            MapNatives.partition(getMap(r, "m"), getFunction(r, "p"), c));

        // Task #21: Min/Max operations
        HANDLERS.put("__native_map_min_key", (r, c) ->
            MapNatives.minKey(getMap(r, "m"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_map_max_key", (r, c) ->
            MapNatives.maxKey(getMap(r, "m"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_map_min_value", (r, c) ->
            MapNatives.minValue(getMap(r, "m"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_map_max_value", (r, c) ->
            MapNatives.maxValue(getMap(r, "m"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_map_invert", (r, c) ->
            MapNatives.invert(getMap(r, "m")));

        // Task #22: Key/Value operations
        HANDLERS.put("__native_map_select_keys", (r, c) ->
            MapNatives.selectKeys(getMap(r, "m"), getArray(r, "ks")));
        HANDLERS.put("__native_map_reject_keys", (r, c) ->
            MapNatives.rejectKeys(getMap(r, "m"), getArray(r, "ks")));
        HANDLERS.put("__native_map_reduce", (r, c) ->
            MapNatives.reduce(getMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_group_by_value", (r, c) ->
            MapNatives.groupByValue(getMap(r, "m")));

        // Task #29: Key operations
        HANDLERS.put("__native_map_map_keys", (r, c) ->
            MapNatives.mapKeys(getMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_filter_keys", (r, c) ->
            MapNatives.filterKeys(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_filter_values", (r, c) ->
            MapNatives.filterValues(getMap(r, "m"), getFunction(r, "p"), c));

        // Task #37: Multi-map operations
        HANDLERS.put("__native_map_zip_maps", (r, c) ->
            MapNatives.zipMaps(getMap(r, "m1"), getMap(r, "m2")));
        HANDLERS.put("__native_map_diff_maps", (r, c) ->
            MapNatives.diffMaps(getMap(r, "m1"), getMap(r, "m2")));
        HANDLERS.put("__native_map_intersect_maps", (r, c) ->
            MapNatives.intersectMaps(getMap(r, "m1"), getMap(r, "m2")));
        HANDLERS.put("__native_map_symmetric_diff_maps", (r, c) ->
            MapNatives.symmetricDiffMaps(getMap(r, "m1"), getMap(r, "m2")));

        // List and SortedMap conversions
        HANDLERS.put("__native_map_to_list", (r, c) ->
            MapNatives.toList(getMap(r, "m")));
        HANDLERS.put("__native_map_from_list", (r, c) ->
            MapNatives.fromList(getList(r, "xs")));
        HANDLERS.put("__native_map_flat_map", (r, c) ->
            MapNatives.flatMap(getMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_to_sorted_map", (r, c) ->
            MapNatives.toSortedMap(getMap(r, "m"), getFunction(r, "cmp")));
    }

    // ==================== SortedMap ====================

    private static void registerSortedMapHandlers() {
        HANDLERS.put("__native_sorted_map_empty", (r, c) -> SortedMapNatives.empty());
        HANDLERS.put("__native_sorted_map_size", (r, c) ->
            SortedMapNatives.size(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_is_empty", (r, c) ->
            SortedMapNatives.isEmpty(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_contains_key", (r, c) ->
            SortedMapNatives.containsKey(getSortedMap(r, "m"), getField(r, "k"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_map_get", (r, c) ->
            SortedMapNatives.get(getSortedMap(r, "m"), getField(r, "k"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_map_insert", (r, c) ->
            SortedMapNatives.insert(getSortedMap(r, "m"), getField(r, "k"), getField(r, "v"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_map_remove", (r, c) ->
            SortedMapNatives.remove(getSortedMap(r, "m"), getField(r, "k"), getFunction(r, "cmp")));
        HANDLERS.put("__native_sorted_map_keys", (r, c) ->
            SortedMapNatives.keys(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_values", (r, c) ->
            SortedMapNatives.values(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_entries", (r, c) ->
            SortedMapNatives.entries(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_equals", (r, c) ->
            SortedMapNatives.equals(getSortedMap(r, "m1"), getSortedMap(r, "m2")));
        HANDLERS.put("__native_sorted_map_compare", (r, c) ->
            SortedMapNatives.compareMaps(getSortedMap(r, "m1"), getSortedMap(r, "m2")));
        HANDLERS.put("__native_sorted_map_min_entry", (r, c) ->
            SortedMapNatives.minEntry(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_max_entry", (r, c) ->
            SortedMapNatives.maxEntry(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_to_map", (r, c) ->
            SortedMapNatives.toMap(getSortedMap(r, "m")));

        // HOF operations
        HANDLERS.put("__native_sorted_map_filter", (r, c) ->
            SortedMapNatives.filter(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_fold", (r, c) ->
            SortedMapNatives.fold(getSortedMap(r, "m"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_map_map_values", (r, c) ->
            SortedMapNatives.mapValues(getSortedMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_map_any", (r, c) ->
            SortedMapNatives.any(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_all", (r, c) ->
            SortedMapNatives.all(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_none", (r, c) ->
            SortedMapNatives.none(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_find", (r, c) ->
            SortedMapNatives.find(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_count", (r, c) ->
            SortedMapNatives.count(getSortedMap(r, "m"), getFunction(r, "p"), c));

        // Additional operations
        HANDLERS.put("__native_sorted_map_get_or_default", (r, c) ->
            SortedMapNatives.getOrDefault(getSortedMap(r, "m"), getField(r, "k"), getField(r, "default")));
        HANDLERS.put("__native_sorted_map_update", (r, c) ->
            SortedMapNatives.update(getSortedMap(r, "m"), getField(r, "k"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_map_merge", (r, c) ->
            SortedMapNatives.merge(getSortedMap(r, "m1"), getSortedMap(r, "m2")));
        HANDLERS.put("__native_sorted_map_merge_with", (r, c) ->
            SortedMapNatives.mergeWith(getSortedMap(r, "m1"), getSortedMap(r, "m2"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_map_singleton", (r, c) ->
            SortedMapNatives.singleton(getField(r, "k"), getField(r, "v"), getFunction(r, "cmp")));

        // Conversion
        HANDLERS.put("__native_sorted_map_to_seq", (r, c) ->
            SortedMapNatives.toSeq(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_keys_seq", (r, c) ->
            SortedMapNatives.keysSeq(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_values_seq", (r, c) ->
            SortedMapNatives.valuesSeq(getSortedMap(r, "m")));

        // Task #15: Additional operations
        HANDLERS.put("__native_sorted_map_partition", (r, c) ->
            SortedMapNatives.partition(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_range", (r, c) ->
            SortedMapNatives.range(getSortedMap(r, "m"), getField(r, "lo"), getField(r, "hi")));
        HANDLERS.put("__native_sorted_map_floor_key", (r, c) ->
            SortedMapNatives.floorKey(getSortedMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_sorted_map_ceiling_key", (r, c) ->
            SortedMapNatives.ceilingKey(getSortedMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_sorted_map_floor_entry", (r, c) ->
            SortedMapNatives.floorEntry(getSortedMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_sorted_map_ceiling_entry", (r, c) ->
            SortedMapNatives.ceilingEntry(getSortedMap(r, "m"), getField(r, "k")));

        // Task #16: Advanced operations
        HANDLERS.put("__native_sorted_map_head_map", (r, c) ->
            SortedMapNatives.headMap(getSortedMap(r, "m"), getField(r, "to")));
        HANDLERS.put("__native_sorted_map_tail_map", (r, c) ->
            SortedMapNatives.tailMap(getSortedMap(r, "m"), getField(r, "from")));
        HANDLERS.put("__native_sorted_map_sub_map", (r, c) ->
            SortedMapNatives.subMap(getSortedMap(r, "m"), getField(r, "lo"), getField(r, "hi")));
        HANDLERS.put("__native_sorted_map_take", (r, c) ->
            SortedMapNatives.take(getSortedMap(r, "m"), getLong(r, "n")));
        HANDLERS.put("__native_sorted_map_drop", (r, c) ->
            SortedMapNatives.drop(getSortedMap(r, "m"), getLong(r, "n")));

        // Additional higher-order functions
        HANDLERS.put("__native_sorted_map_map_keys", (r, c) ->
            SortedMapNatives.mapKeys(getSortedMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_sorted_map_filter_keys", (r, c) ->
            SortedMapNatives.filterKeys(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_filter_values", (r, c) ->
            SortedMapNatives.filterValues(getSortedMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_sorted_map_invert", (r, c) ->
            SortedMapNatives.invert(getSortedMap(r, "m")));

        // List conversion
        HANDLERS.put("__native_sorted_map_to_list", (r, c) ->
            SortedMapNatives.toList(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_from_list", (r, c) ->
            SortedMapNatives.fromList(getList(r, "xs"), getFunction(r, "cmp")));
    }

    // ==================== Deque ====================

    private static void registerDequeHandlers() {
        HANDLERS.put("__native_deque_empty", (r, c) -> DequeNatives.empty());
        HANDLERS.put("__native_deque_singleton", (r, c) ->
            DequeNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_deque_size", (r, c) ->
            DequeNatives.length(getDeque(r, "d")));
        HANDLERS.put("__native_deque_is_empty", (r, c) ->
            DequeNatives.isEmpty(getDeque(r, "d")));
        HANDLERS.put("__native_deque_push_front", (r, c) ->
            DequeNatives.pushFront(getDeque(r, "d"), getField(r, "x")));
        HANDLERS.put("__native_deque_push_back", (r, c) ->
            DequeNatives.pushBack(getDeque(r, "d"), getField(r, "x")));
        HANDLERS.put("__native_deque_pop_front", (r, c) ->
            DequeNatives.popFront(getDeque(r, "d")));
        HANDLERS.put("__native_deque_pop_back", (r, c) ->
            DequeNatives.popBack(getDeque(r, "d")));
        HANDLERS.put("__native_deque_peek_front", (r, c) ->
            DequeNatives.peekFront(getDeque(r, "d")));
        HANDLERS.put("__native_deque_peek_back", (r, c) ->
            DequeNatives.peekBack(getDeque(r, "d")));
        HANDLERS.put("__native_deque_reverse", (r, c) ->
            DequeNatives.reverse(getDeque(r, "d")));
        HANDLERS.put("__native_deque_concat", (r, c) ->
            DequeNatives.concat(getDeque(r, "d1"), getDeque(r, "d2")));
        HANDLERS.put("__native_deque_equals", (r, c) ->
            DequeNatives.equals(getDeque(r, "d1"), getDeque(r, "d2")));
        HANDLERS.put("__native_deque_compare", (r, c) ->
            DequeNatives.compare(getDeque(r, "d1"), getDeque(r, "d2"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_deque_to_list", (r, c) ->
            DequeNatives.toList(getDeque(r, "d")));
        HANDLERS.put("__native_deque_to_seq", (r, c) ->
            DequeNatives.toArray(getDeque(r, "d")));
        HANDLERS.put("__native_deque_from_list", (r, c) ->
            DequeNatives.fromList(getList(r, "xs")));

        // HOF operations
        HANDLERS.put("__native_deque_map", (r, c) ->
            DequeNatives.map(getDeque(r, "d"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_filter", (r, c) ->
            DequeNatives.filter(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_fold", (r, c) ->
            DequeNatives.fold(getDeque(r, "d"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_any", (r, c) ->
            DequeNatives.any(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_all", (r, c) ->
            DequeNatives.all(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_none", (r, c) ->
            DequeNatives.none(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_find", (r, c) ->
            DequeNatives.find(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_count", (r, c) ->
            DequeNatives.count(getDeque(r, "d"), getFunction(r, "p"), c));

        // Min/Max/Sum/Product
        HANDLERS.put("__native_deque_sum", (r, c) ->
            DequeNatives.sum(getDeque(r, "d")));
        HANDLERS.put("__native_deque_product", (r, c) ->
            DequeNatives.product(getDeque(r, "d")));

        // Contains

        // Conversion
        HANDLERS.put("__native_deque_to_seq", (r, c) ->
            DequeNatives.toSeq(getDeque(r, "d")));
        HANDLERS.put("__native_deque_from_seq", (r, c) ->
            DequeNatives.fromSeq(getSeq(r, "xs")));

        // Additional operations
        HANDLERS.put("__native_deque_reduce", (r, c) ->
            DequeNatives.reduce(getDeque(r, "d"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_flat_map", (r, c) ->
            DequeNatives.flatMap(getDeque(r, "d"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_partition", (r, c) ->
            DequeNatives.partition(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_take", (r, c) ->
            DequeNatives.take(getDeque(r, "d"), getLong(r, "n")));
        HANDLERS.put("__native_deque_drop", (r, c) ->
            DequeNatives.drop(getDeque(r, "d"), getLong(r, "n")));
        HANDLERS.put("__native_deque_unique", (r, c) ->
            DequeNatives.unique(getDeque(r, "d")));
        HANDLERS.put("__native_deque_sort", (r, c) ->
            DequeNatives.sort(getDeque(r, "d")));
        HANDLERS.put("__native_deque_sum_float", (r, c) ->
            DequeNatives.sumFloat(getDeque(r, "d")));
        HANDLERS.put("__native_deque_product_float", (r, c) ->
            DequeNatives.productFloat(getDeque(r, "d")));

        // Task #9: Basic operations
        HANDLERS.put("__native_deque_min_by", (r, c) ->
            DequeNatives.minBy(getDeque(r, "d"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_deque_max_by", (r, c) ->
            DequeNatives.maxBy(getDeque(r, "d"), getFunction(r, "f"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_deque_get", (r, c) ->
            DequeNatives.get(getDeque(r, "d"), getLong(r, "i")));
        HANDLERS.put("__native_deque_slice", (r, c) ->
            DequeNatives.slice(getDeque(r, "d"), getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_deque_find_index", (r, c) ->
            DequeNatives.findIndex(getDeque(r, "d"), getFunction(r, "p"), c));

        // Task #10: Slicing operations
        HANDLERS.put("__native_deque_take_while", (r, c) ->
            DequeNatives.takeWhile(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_drop_while", (r, c) ->
            DequeNatives.dropWhile(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_split_at", (r, c) ->
            DequeNatives.splitAt(getDeque(r, "d"), getLong(r, "i")));
        HANDLERS.put("__native_deque_span", (r, c) ->
            DequeNatives.span(getDeque(r, "d"), getFunction(r, "p"), c));

        // Task #11: Transformation operations
        HANDLERS.put("__native_deque_scan", (r, c) ->
            DequeNatives.scan(getDeque(r, "d"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_dedup", (r, c) ->
            DequeNatives.dedup(getDeque(r, "d"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_deque_dedup_by", (r, c) ->
            DequeNatives.dedupBy(getDeque(r, "d"), getFunction(r, "f"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_deque_intersperse", (r, c) ->
            DequeNatives.intersperse(getDeque(r, "d"), getField(r, "sep")));

        // Task #12: Combining operations
        HANDLERS.put("__native_deque_zip", (r, c) ->
            DequeNatives.zip(getDeque(r, "d1"), getDeque(r, "d2")));
        HANDLERS.put("__native_deque_zip_with_index", (r, c) ->
            DequeNatives.zipWithIndex(getDeque(r, "d")));
        HANDLERS.put("__native_deque_rotate_left", (r, c) ->
            DequeNatives.rotateLeft(getDeque(r, "d"), getLong(r, "n")));
        HANDLERS.put("__native_deque_rotate_right", (r, c) ->
            DequeNatives.rotateRight(getDeque(r, "d"), getLong(r, "n")));

        // Task #28: Cross-collection conversions (Deque to other types)
        HANDLERS.put("__native_deque_to_set", (r, c) ->
            DequeNatives.toSet(getDeque(r, "d")));
        HANDLERS.put("__native_deque_to_sorted_set", (r, c) ->
            DequeNatives.toSortedSet(getDeque(r, "d")));
        HANDLERS.put("__native_deque_to_map", (r, c) ->
            DequeNatives.toMap(getDeque(r, "d")));
        HANDLERS.put("__native_deque_to_sorted_map", (r, c) ->
            DequeNatives.toSortedMap(getDeque(r, "d")));
        HANDLERS.put("__native_deque_from_set", (r, c) ->
            DequeNatives.fromSet(getSet(r, "s")));
        HANDLERS.put("__native_deque_from_sorted_set", (r, c) ->
            DequeNatives.fromSortedSet(getSortedSet(r, "s")));
        HANDLERS.put("__native_deque_from_map", (r, c) ->
            DequeNatives.fromMap(getMap(r, "m")));
        HANDLERS.put("__native_deque_from_sorted_map", (r, c) ->
            DequeNatives.fromSortedMap(getSortedMap(r, "m")));

        // forEach / first / last
        HANDLERS.put("__native_deque_for_each", (r, c) ->
            DequeNatives.forEach(getDeque(r, "d"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_first", (r, c) ->
            DequeNatives.first(getDeque(r, "d")));
        HANDLERS.put("__native_deque_last", (r, c) ->
            DequeNatives.last(getDeque(r, "d")));
    }

    // ==================== NonEmptyList ====================

    private static void registerNonEmptyListHandlers() {
        HANDLERS.put("__native_nel_singleton", (r, c) ->
            NonEmptyListNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_nel_from_head_tail", (r, c) ->
            NonEmptyListNatives.fromHeadTail(getField(r, "head"), getList(r, "tail")));
        HANDLERS.put("__native_nel_from_list", (r, c) ->
            NonEmptyListNatives.fromList(getList(r, "xs")));
        HANDLERS.put("__native_nel_from_seq", (r, c) ->
            NonEmptyListNatives.fromArray(getArray(r, "xs")));
        HANDLERS.put("__native_nel_size", (r, c) ->
            NonEmptyListNatives.length(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_head", (r, c) ->
            NonEmptyListNatives.head(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_tail", (r, c) ->
            NonEmptyListNatives.tail(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_last", (r, c) ->
            NonEmptyListNatives.last(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_get", (r, c) ->
            NonEmptyListNatives.get(getNonEmptyList(r, "xs"), getLong(r, "index")));
        HANDLERS.put("__native_nel_cons", (r, c) ->
            NonEmptyListNatives.cons(getField(r, "x"), getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_append", (r, c) ->
            NonEmptyListNatives.append(getNonEmptyList(r, "xs"), getNonEmptyList(r, "ys")));
        HANDLERS.put("__native_nel_append_list", (r, c) ->
            NonEmptyListNatives.appendList(getNonEmptyList(r, "xs"), getList(r, "ys")));
        HANDLERS.put("__native_nel_prepend_list", (r, c) ->
            NonEmptyListNatives.prependList(getList(r, "xs"), getField(r, "y")));
        HANDLERS.put("__native_nel_reverse", (r, c) ->
            NonEmptyListNatives.reverse(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_intersperse", (r, c) ->
            NonEmptyListNatives.intersperse(getNonEmptyList(r, "xs"), getField(r, "sep")));
        HANDLERS.put("__native_nel_sort", (r, c) ->
            NonEmptyListNatives.sort(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_to_list", (r, c) ->
            NonEmptyListNatives.toList(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_to_seq", (r, c) ->
            NonEmptyListNatives.toArray(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_zip", (r, c) ->
            NonEmptyListNatives.zip(getNonEmptyList(r, "xs"), getNonEmptyList(r, "ys")));

        // HOF operations
        HANDLERS.put("__native_nel_map", (r, c) ->
            NonEmptyListNatives.map(getNonEmptyList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_flat_map", (r, c) ->
            NonEmptyListNatives.flatMap(getNonEmptyList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_fold_left", (r, c) ->
            NonEmptyListNatives.foldLeft(getNonEmptyList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_fold_right", (r, c) ->
            NonEmptyListNatives.foldRight(getNonEmptyList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_reduce", (r, c) ->
            NonEmptyListNatives.reduce(getNonEmptyList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_any", (r, c) ->
            NonEmptyListNatives.any(getNonEmptyList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_nel_all", (r, c) ->
            NonEmptyListNatives.all(getNonEmptyList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_nel_sort_by", (r, c) ->
            NonEmptyListNatives.sortBy(getNonEmptyList(r, "xs"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_nel_equals", (r, c) ->
            NonEmptyListNatives.equals(getNonEmptyList(r, "xs"), getNonEmptyList(r, "ys"), getFunction(r, "eq"), c));
        HANDLERS.put("__native_nel_compare", (r, c) ->
            NonEmptyListNatives.compare(getNonEmptyList(r, "xs"), getNonEmptyList(r, "ys"), getFunction(r, "cmp"), c));
    }

    // ==================== Nat ====================

    private static void registerNatHandlers() {
        HANDLERS.put("__native_nat_from_int", (r, c) ->
            NatNatives.fromInt(getLong(r, "n")));
        HANDLERS.put("__native_nat_from_string", (r, c) ->
            NatNatives.fromString(getString(r, "s")));
        HANDLERS.put("__native_nat_from_float", (r, c) ->
            NatNatives.fromFloat(getDouble(r, "n")));
        HANDLERS.put("__native_nat_to_int", (r, c) ->
            NatNatives.toInt(getLong(r, "n")));
        HANDLERS.put("__native_nat_to_str", (r, c) ->
            NatNatives.toString(getLong(r, "n")));
        HANDLERS.put("__native_nat_subtract_to_int", (r, c) ->
            NatNatives.subtractToInt(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_pow", (r, c) ->
            NatNatives.pow(getLong(r, "base"), getLong(r, "exp")));
        HANDLERS.put("__native_nat_min", (r, c) ->
            NatNatives.min(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_max", (r, c) ->
            NatNatives.max(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_clamp", (r, c) ->
            NatNatives.clamp(getLong(r, "n"), getLong(r, "min"), getLong(r, "max")));
        // Nat arithmetic
        HANDLERS.put("__native_nat_add", (r, c) ->
            PrimitiveNatives.natAdd(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_multiply", (r, c) ->
            PrimitiveNatives.natMultiply(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_divide", (r, c) ->
            PrimitiveNatives.natDivide(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_modulo", (r, c) ->
            PrimitiveNatives.natModulo(getLong(r, "l"), getLong(r, "r")));
        // Nat comparisons
        HANDLERS.put("__native_nat_eq", (r, c) ->
            PrimitiveNatives.natEq(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_neq", (r, c) ->
            PrimitiveNatives.natNeq(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_lt", (r, c) ->
            PrimitiveNatives.natLt(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_le", (r, c) ->
            PrimitiveNatives.natLte(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_gt", (r, c) ->
            PrimitiveNatives.natGt(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_ge", (r, c) ->
            PrimitiveNatives.natGte(getLong(r, "l"), getLong(r, "r")));
    }

    // ==================== Char ====================

    private static void registerCharHandlers() {
        HANDLERS.put("__native_char_from_string", (r, c) ->
            CharNatives.fromString(getString(r, "s")));
        HANDLERS.put("__native_char_from_int", (r, c) ->
            CharNatives.fromInt(getLong(r, "code")));
        HANDLERS.put("__native_char_to_int", (r, c) ->
            CharNatives.toInt(getString(r, "c")));
        HANDLERS.put("__native_char_to_str", (r, c) ->
            getString(r, "c"));
        HANDLERS.put("__native_char_is_letter", (r, c) ->
            CharNatives.isLetter(getString(r, "c")));
        HANDLERS.put("__native_char_is_digit", (r, c) ->
            CharNatives.isDigit(getString(r, "c")));
        HANDLERS.put("__native_char_is_alpha_numeric", (r, c) ->
            CharNatives.isAlphaNumeric(getString(r, "c")));
        HANDLERS.put("__native_char_is_whitespace", (r, c) ->
            CharNatives.isWhitespace(getString(r, "c")));
        HANDLERS.put("__native_char_is_upper", (r, c) ->
            CharNatives.isUpper(getString(r, "c")));
        HANDLERS.put("__native_char_is_lower", (r, c) ->
            CharNatives.isLower(getString(r, "c")));
        HANDLERS.put("__native_char_to_upper", (r, c) ->
            CharNatives.toUpper(getString(r, "c")));
        HANDLERS.put("__native_char_to_lower", (r, c) ->
            CharNatives.toLower(getString(r, "c")));
        HANDLERS.put("__native_char_digit_to_int", (r, c) ->
            CharNatives.digitToInt(getString(r, "c"), getLong(r, "radix")));
        HANDLERS.put("__native_char_eq", (r, c) ->
            CharNatives.eq(getString(r, "a"), getString(r, "b")));
        HANDLERS.put("__native_char_compare_int", (r, c) ->
            CharNatives.compareInt(getString(r, "a"), getString(r, "b")));
    }

    // ==================== Frac ====================

    private static void registerFracHandlers() {
        HANDLERS.put("__native_make_frac", (r, c) ->
            FracNatives.make(getLong(r, "numerator"), getLong(r, "denominator")));
        HANDLERS.put("__native_int_to_frac", (r, c) ->
            FracNatives.fromInt(getLong(r, "n")));
        HANDLERS.put("__native_nat_to_frac", (r, c) ->
            FracNatives.fromNat(getLong(r, "n")));
        HANDLERS.put("__native_frac_numerator", (r, c) ->
            FracNatives.numerator(getRecord(r, "n")));
        HANDLERS.put("__native_frac_denominator", (r, c) ->
            FracNatives.denominator(getRecord(r, "n")));
        HANDLERS.put("__native_frac_add", (r, c) ->
            FracNatives.add(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_subtract", (r, c) ->
            FracNatives.subtract(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_multiply", (r, c) ->
            FracNatives.multiply(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_divide", (r, c) ->
            FracNatives.divide(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_negate_frac", (r, c) ->
            FracNatives.negate(getRecord(r, "n")));
        HANDLERS.put("__native_abs_frac", (r, c) ->
            FracNatives.abs(getRecord(r, "n")));
        HANDLERS.put("__native_reduce_frac", (r, c) ->
            FracNatives.reduce(getRecord(r, "n")));
        HANDLERS.put("__native_frac_equals", (r, c) ->
            FracNatives.equals(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_less_than", (r, c) ->
            FracNatives.lessThan(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_greater_than", (r, c) ->
            FracNatives.greaterThan(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_to_float", (r, c) ->
            FracNatives.toFloat(getRecord(r, "n")));
        HANDLERS.put("__native_frac_to_str", (r, c) ->
            FracNatives.fracToString(getRecord(r, "n")));
    }

    // ==================== Bytes ====================

    private static void registerBytesHandlers() {
        HANDLERS.put("__native_bytes_empty", (r, c) -> BytesNatives.empty());
        HANDLERS.put("__native_bytes_from_string", (r, c) ->
            BytesNatives.fromString(getString(r, "s")));
        HANDLERS.put("__native_bytes_from_seq", (r, c) ->
            BytesNatives.fromArray(getArray(r, "arr")));
        HANDLERS.put("__native_bytes_from_hex", (r, c) ->
            BytesNatives.fromHex(getString(r, "s")));
        HANDLERS.put("__native_bytes_from_base64", (r, c) ->
            BytesNatives.fromBase64(getString(r, "s")));
        HANDLERS.put("__native_bytes_size", (r, c) ->
            BytesNatives.length(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_is_empty", (r, c) ->
            BytesNatives.isEmpty(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_get", (r, c) ->
            BytesNatives.get(getBytes(r, "b"), getLong(r, "i")));
        HANDLERS.put("__native_bytes_slice", (r, c) ->
            BytesNatives.slice(getBytes(r, "b"), getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_bytes_take", (r, c) ->
            BytesNatives.take(getBytes(r, "b"), getLong(r, "n")));
        HANDLERS.put("__native_bytes_drop", (r, c) ->
            BytesNatives.drop(getBytes(r, "b"), getLong(r, "n")));
        HANDLERS.put("__native_bytes_concat", (r, c) ->
            BytesNatives.concat(getBytes(r, "b1"), getBytes(r, "b2")));
        HANDLERS.put("__native_bytes_concat_all", (r, c) ->
            BytesNatives.concatAll(getArray(r, "bs")));
        HANDLERS.put("__native_bytes_contains", (r, c) ->
            BytesNatives.contains(getBytes(r, "b"), getBytes(r, "pattern")));
        HANDLERS.put("__native_bytes_index_of", (r, c) ->
            BytesNatives.indexOf(getBytes(r, "b"), getBytes(r, "pattern")));
        HANDLERS.put("__native_bytes_starts_with", (r, c) ->
            BytesNatives.startsWith(getBytes(r, "b"), getBytes(r, "prefix")));
        HANDLERS.put("__native_bytes_ends_with", (r, c) ->
            BytesNatives.endsWith(getBytes(r, "b"), getBytes(r, "suffix")));
        HANDLERS.put("__native_bytes_reverse", (r, c) ->
            BytesNatives.reverse(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_repeat", (r, c) ->
            BytesNatives.repeat(getBytes(r, "b"), getLong(r, "n")));
        HANDLERS.put("__native_bytes_to_str", (r, c) ->
            BytesNatives.bytesToString(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_to_hex", (r, c) ->
            BytesNatives.toHex(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_to_base64", (r, c) ->
            BytesNatives.toBase64(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_to_seq", (r, c) ->
            BytesNatives.toArray(getBytes(r, "b")));

        // HOF operations
        HANDLERS.put("__native_bytes_map", (r, c) ->
            BytesNatives.map(getBytes(r, "b"), getFunction(r, "f"), c));
        HANDLERS.put("__native_bytes_fold", (r, c) ->
            BytesNatives.fold(getBytes(r, "b"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_bytes_equals", (r, c) ->
            BytesNatives.equals(getBytes(r, "b1"), getBytes(r, "b2")));
        HANDLERS.put("__native_bytes_compare", (r, c) ->
            BytesNatives.compare(getBytes(r, "b1"), getBytes(r, "b2")));
    }

    // ==================== DateTime (#72) ====================
    //
    // Backs std::datetime (types + pure ops) and std::effects::time
    // (Time effect ops). All natives here are thin passthroughs to
    // java.time via DateTimeNatives.
    //
    // Value flow:
    //   __native_instant_*  operate on/return DateTimeNatives.MezzeInstant
    //   __native_duration_* operate on/return DateTimeNatives.MezzeDuration
    //   __native_zone_*     operate on/return DateTimeNatives.MezzeZone
    //   Fields naming: `i` = Instant, `d` = Duration, `z` = Zone.

    private static void registerDateTimeHandlers() {
        // ---- Instant ----
        HANDLERS.put("__native_instant_now", (r, c) -> DateTimeNatives.instantNow());
        HANDLERS.put("__native_instant_from_millis", (r, c) ->
            DateTimeNatives.instantFromMillis(getLong(r, "millis")));
        HANDLERS.put("__native_instant_from_seconds_nanos", (r, c) ->
            DateTimeNatives.instantFromSecondsNanos(getLong(r, "seconds"), getLong(r, "nanos")));
        HANDLERS.put("__native_instant_to_millis", (r, c) ->
            DateTimeNatives.instantToMillis(asInstant(r, "i")));
        HANDLERS.put("__native_instant_to_seconds", (r, c) ->
            DateTimeNatives.instantToSeconds(asInstant(r, "i")));
        HANDLERS.put("__native_instant_nano_of_second", (r, c) ->
            DateTimeNatives.instantNanoOfSecond(asInstant(r, "i")));
        HANDLERS.put("__native_instant_add", (r, c) ->
            DateTimeNatives.instantAdd(asInstant(r, "i"), asDuration(r, "d")));
        HANDLERS.put("__native_instant_sub_duration", (r, c) ->
            DateTimeNatives.instantSubDuration(asInstant(r, "i"), asDuration(r, "d")));
        HANDLERS.put("__native_instant_diff", (r, c) ->
            DateTimeNatives.instantDiff(asInstant(r, "end"), asInstant(r, "start")));
        HANDLERS.put("__native_instant_before", (r, c) ->
            DateTimeNatives.instantBefore(asInstant(r, "a"), asInstant(r, "b")));
        HANDLERS.put("__native_instant_after", (r, c) ->
            DateTimeNatives.instantAfter(asInstant(r, "a"), asInstant(r, "b")));
        HANDLERS.put("__native_instant_compare", (r, c) ->
            DateTimeNatives.instantCompare(asInstant(r, "a"), asInstant(r, "b")));
        HANDLERS.put("__native_instant_equals", (r, c) ->
            DateTimeNatives.instantEquals(asInstant(r, "a"), asInstant(r, "b")));

        // ---- Duration ----
        HANDLERS.put("__native_duration_monotonic", (r, c) -> DateTimeNatives.durationMonotonic());
        HANDLERS.put("__native_duration_from_nanos", (r, c) ->
            DateTimeNatives.durationFromNanos(getLong(r, "n")));
        HANDLERS.put("__native_duration_from_millis", (r, c) ->
            DateTimeNatives.durationFromMillis(getLong(r, "n")));
        HANDLERS.put("__native_duration_from_seconds", (r, c) ->
            DateTimeNatives.durationFromSeconds(getLong(r, "n")));
        HANDLERS.put("__native_duration_from_minutes", (r, c) ->
            DateTimeNatives.durationFromMinutes(getLong(r, "n")));
        HANDLERS.put("__native_duration_from_hours", (r, c) ->
            DateTimeNatives.durationFromHours(getLong(r, "n")));
        HANDLERS.put("__native_duration_from_days", (r, c) ->
            DateTimeNatives.durationFromDays(getLong(r, "n")));
        HANDLERS.put("__native_duration_to_nanos", (r, c) ->
            DateTimeNatives.durationToNanos(asDuration(r, "d")));
        HANDLERS.put("__native_duration_to_millis", (r, c) ->
            DateTimeNatives.durationToMillis(asDuration(r, "d")));
        HANDLERS.put("__native_duration_to_seconds", (r, c) ->
            DateTimeNatives.durationToSeconds(asDuration(r, "d")));
        HANDLERS.put("__native_duration_add", (r, c) ->
            DateTimeNatives.durationAdd(asDuration(r, "a"), asDuration(r, "b")));
        HANDLERS.put("__native_duration_sub", (r, c) ->
            DateTimeNatives.durationSub(asDuration(r, "a"), asDuration(r, "b")));
        HANDLERS.put("__native_duration_negate", (r, c) ->
            DateTimeNatives.durationNegate(asDuration(r, "d")));
        HANDLERS.put("__native_duration_abs", (r, c) ->
            DateTimeNatives.durationAbs(asDuration(r, "d")));
        HANDLERS.put("__native_duration_is_negative", (r, c) ->
            DateTimeNatives.durationIsNegative(asDuration(r, "d")));
        HANDLERS.put("__native_duration_compare", (r, c) ->
            DateTimeNatives.durationCompare(asDuration(r, "a"), asDuration(r, "b")));
        HANDLERS.put("__native_duration_equals", (r, c) ->
            DateTimeNatives.durationEquals(asDuration(r, "a"), asDuration(r, "b")));

        // ---- Zone ----
        HANDLERS.put("__native_zone_utc", (r, c) -> DateTimeNatives.zoneUtc());
        HANDLERS.put("__native_zone_system", (r, c) -> DateTimeNatives.zoneSystem());
        HANDLERS.put("__native_zone_of", (r, c) -> DateTimeNatives.zoneOf(getString(r, "id")));
        HANDLERS.put("__native_zone_id", (r, c) -> DateTimeNatives.zoneId(asZone(r, "z")));
        HANDLERS.put("__native_zone_offset_at", (r, c) ->
            DateTimeNatives.zoneOffsetAt(asZone(r, "z"), asInstant(r, "at")));

        // ---- Decompose / format / parse ----
        HANDLERS.put("__native_datetime_decompose", (r, c) ->
            DateTimeNatives.decompose(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_datetime_compose", (r, c) ->
            DateTimeNatives.compose(
                getLong(r, "year"), getLong(r, "month"), getLong(r, "day"),
                getLong(r, "hour"), getLong(r, "minute"), getLong(r, "second"),
                getLong(r, "nanos"), asZone(r, "z")));
        HANDLERS.put("__native_datetime_format", (r, c) ->
            DateTimeNatives.format(asInstant(r, "i"), getString(r, "pattern"), asZone(r, "z")));
        HANDLERS.put("__native_datetime_parse", (r, c) ->
            DateTimeNatives.parse(getString(r, "s"), getString(r, "pattern"), asZone(r, "z")));

        // ---- Direct field accessors (skip DateTime record allocation) ----
        HANDLERS.put("__native_year_of", (r, c) ->
            DateTimeNatives.yearOf(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_month_of", (r, c) ->
            DateTimeNatives.monthOf(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_day_of", (r, c) ->
            DateTimeNatives.dayOf(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_hour_of", (r, c) ->
            DateTimeNatives.hourOf(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_minute_of", (r, c) ->
            DateTimeNatives.minuteOf(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_second_of", (r, c) ->
            DateTimeNatives.secondOf(asInstant(r, "i"), asZone(r, "z")));
        HANDLERS.put("__native_nanos_of", (r, c) ->
            DateTimeNatives.nanosOf(asInstant(r, "i"), asZone(r, "z")));

        // ---- Codecs (ISO8601 for Instant/Duration; zone id for Zone) ----
        HANDLERS.put("__native_instant_encode", (r, c) ->
            DateTimeNatives.instantEncode(asInstant(r, "i")));
        HANDLERS.put("__native_instant_decode", (r, c) ->
            DateTimeNatives.instantDecode(getString(r, "s")));
        HANDLERS.put("__native_duration_encode", (r, c) ->
            DateTimeNatives.durationEncode(asDuration(r, "d")));
        HANDLERS.put("__native_duration_decode", (r, c) ->
            DateTimeNatives.durationDecode(getString(r, "s")));
        HANDLERS.put("__native_zone_encode", (r, c) ->
            DateTimeNatives.zoneEncode(asZone(r, "z")));
        HANDLERS.put("__native_zone_decode", (r, c) ->
            DateTimeNatives.zoneDecode(getString(r, "s")));

        // ---- TestClock (mutable-state mock handle) ----
        HANDLERS.put("__native_test_clock_at", (r, c) ->
            DateTimeNatives.testClockAt(asInstant(r, "i")));
        HANDLERS.put("__native_test_clock_now", (r, c) ->
            DateTimeNatives.testClockNow(asTestClock(r, "c")));
        HANDLERS.put("__native_test_clock_monotonic", (r, c) ->
            DateTimeNatives.testClockMonotonic(asTestClock(r, "c")));
        HANDLERS.put("__native_test_clock_set", (r, c) ->
            DateTimeNatives.testClockSet(asTestClock(r, "c"), asInstant(r, "i")));
        HANDLERS.put("__native_test_clock_advance", (r, c) ->
            DateTimeNatives.testClockAdvance(asTestClock(r, "c"), asDuration(r, "d")));

        // ---- Sleep (backs Time.sleep) ----
        HANDLERS.put("__native_time_sleep", (r, c) ->
            DateTimeNatives.sleep(asDuration(r, "d")));
    }

    private static DateTimeNatives.MezzeInstant asInstant(MezzeRecord r, String field) {
        Object v = getField(r, field);
        if (v instanceof DateTimeNatives.MezzeInstant i) return i;
        throw new NativeTypeMismatchException("Expected Instant for field '" + field + "', got: " +
            (v == null ? "null" : v.getClass().getName()));
    }

    private static DateTimeNatives.MezzeDuration asDuration(MezzeRecord r, String field) {
        Object v = getField(r, field);
        if (v instanceof DateTimeNatives.MezzeDuration d) return d;
        throw new NativeTypeMismatchException("Expected Duration for field '" + field + "', got: " +
            (v == null ? "null" : v.getClass().getName()));
    }

    private static DateTimeNatives.MezzeZone asZone(MezzeRecord r, String field) {
        Object v = getField(r, field);
        if (v instanceof DateTimeNatives.MezzeZone z) return z;
        throw new NativeTypeMismatchException("Expected Zone for field '" + field + "', got: " +
            (v == null ? "null" : v.getClass().getName()));
    }

    private static DateTimeNatives.MezzeTestClock asTestClock(MezzeRecord r, String field) {
        Object v = getField(r, field);
        if (v instanceof DateTimeNatives.MezzeTestClock tc) return tc;
        throw new NativeTypeMismatchException("Expected TestClock for field '" + field + "', got: " +
            (v == null ? "null" : v.getClass().getName()));
    }

    // ==================== String Case Conversions ====================

    private static void registerStringCaseHandlers() {
        HANDLERS.put("__native_string_to_camel_case", (r, c) ->
            toCamelCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_snake_case", (r, c) ->
            toSnakeCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_kebab_case", (r, c) ->
            toKebabCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_screaming_snake_case", (r, c) ->
            toScreamingSnakeCase(getString(r, "s")));
        HANDLERS.put("__native_string_lines", (r, c) ->
            stringLines(getString(r, "s")));
        HANDLERS.put("__native_string_words", (r, c) ->
            stringWords(getString(r, "s")));
        HANDLERS.put("__native_string_chars", (r, c) ->
            stringChars(getString(r, "s")));
        HANDLERS.put("__native_string_pad_left", (r, c) ->
            padLeft(getString(r, "s"), getLong(r, "len"), getString(r, "c")));
        HANDLERS.put("__native_string_pad_right", (r, c) ->
            padRight(getString(r, "s"), getLong(r, "len"), getString(r, "c")));
        HANDLERS.put("__native_string_last_index_of", (r, c) ->
            lastIndexOf(getString(r, "s"), getString(r, "sub")));
        HANDLERS.put("__native_string_glob_match", (r, c) ->
            globMatch(getString(r, "pattern"), getString(r, "s")));
    }

    // ==================== Float Additional ====================

    private static void registerFloatHandlers() {
        HANDLERS.put("__native_float_from_nat", (r, c) ->
            (double) getLong(r, "n"));
        HANDLERS.put("__native_float_from_string", (r, c) ->
            floatFromString(getString(r, "s")));
        HANDLERS.put("__native_float_clamp", (r, c) ->
            Math.max(getDouble(r, "min"), Math.min(getDouble(r, "max"), getDouble(r, "n"))));
        HANDLERS.put("__native_float_sign", (r, c) ->
            Math.signum(getDouble(r, "n")));
        HANDLERS.put("__native_float_to_int", (r, c) -> {
            double n = getDouble(r, "n");
            if (Double.isNaN(n) || Double.isInfinite(n)) {
                return MezzeVariant.none();
            }
            return MezzeVariant.some((long) n);
        });
        HANDLERS.put("__native_float_is_nan", (r, c) ->
            Double.isNaN(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_infinite", (r, c) ->
            Double.isInfinite(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_finite", (r, c) ->
            Double.isFinite(getDouble(r, "n")));
        HANDLERS.put("__native_float_ln", (r, c) ->
            Math.log(getDouble(r, "n")));
        HANDLERS.put("__native_float_log10", (r, c) ->
            Math.log10(getDouble(r, "n")));
        HANDLERS.put("__native_float_exp", (r, c) ->
            Math.exp(getDouble(r, "n")));
        HANDLERS.put("__native_float_asin", (r, c) ->
            Math.asin(getDouble(r, "n")));
        HANDLERS.put("__native_float_acos", (r, c) ->
            Math.acos(getDouble(r, "n")));
        HANDLERS.put("__native_float_atan", (r, c) ->
            Math.atan(getDouble(r, "n")));
        HANDLERS.put("__native_float_atan2", (r, c) ->
            Math.atan2(getDouble(r, "y"), getDouble(r, "x")));
    }

    // ==================== Field Accessors ====================

    private static Object getField(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new NativeTypeMismatchException("Missing field '" + name + "'");
        }
        return r.getField(index);
    }

    private static long getLong(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Long l) return l;
        // Accept Doubles that are exact integers (e.g., 0.0, 1.0) — handles
        // cases where float operations produce values used as integer args
        if (val instanceof Double d && d == Math.floor(d) && !Double.isInfinite(d)) {
            return (long) d.doubleValue();
        }
        throw new NativeTypeMismatchException("Expected Long for field '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getName() + "=" + val));
    }

    private static double getDouble(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Double d) return d;
        if (val instanceof Long l) return l.doubleValue();
        throw new NativeTypeMismatchException("Expected Double for field '" + name + "'");
    }

    private static String getString(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + name + "'");
    }

    private static boolean getBool(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Boolean b) return b;
        throw new NativeTypeMismatchException("Expected Boolean for field '" + name + "'");
    }

    private static Object[] getArray(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Object[] arr) return arr;
        // Handle MezzeSeq - convert to Object[]
        if (val instanceof MezzeSeq seq) return seq.toArray();
        // Also handle MezzeList - properly convert to Object[] using toArray
        if (val instanceof MezzeList list) return ListNatives.toArray(list);
        throw new NativeTypeMismatchException("Expected Object[] for field '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getSimpleName()));
    }

    private static MezzeList getList(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeList list) return list;
        // Handle array literals (Object[]) - convert to MezzeList
        if (val instanceof Object[] arr) {
            return MezzeList.of(arr);
        }
        // Handle Cons/Nil linked list variants
        if (val instanceof MezzeVariant variant) {
            return convertLinkedListToMezzeList(variant);
        }
        throw new NativeTypeMismatchException("Expected MezzeList for field '" + name + "'");
    }

    /**
     * Convert a Cons/Nil linked list variant to MezzeList
     */
    @TruffleBoundary
    private static MezzeList convertLinkedListToMezzeList(MezzeVariant variant) {
        java.util.ArrayList<Object> elements = new java.util.ArrayList<>();
        Object current = variant;

        while (current instanceof MezzeVariant v) {
            if (v.getTag() == MezzeVariant.NIL_TAG) {
                break;
            } else if (v.getTag() == MezzeVariant.CONS_TAG) {
                // Cons always has 2 fields (head, tail) → always MezzeRecord.
                Object pv = v.getPayloadValue();
                if (!(pv instanceof MezzeRecord payload)) break;
                elements.add(payload.getField("head"));
                current = payload.getField("tail");
            } else {
                // Not a Cons/Nil list, return empty
                break;
            }
        }

        return MezzeList.of(elements.toArray());
    }

    /**
     * Convert a MezzeList to Object[]
     */
    @TruffleBoundary
    private static Object[] listToObjectArray(MezzeList list) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    private static MezzeFunction getFunction(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeFunction fn) return fn;
        throw new NativeTypeMismatchException("Expected MezzeFunction for field '" + name + "'");
    }

    private static MezzeRecord getRecord(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeRecord rec) return rec;
        throw new NativeTypeMismatchException("Expected MezzeRecord for field '" + name + "'");
    }

    private static MezzeSet getSet(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeSet set) return set;
        throw new NativeTypeMismatchException("Expected MezzeSet for field '" + name + "'");
    }

    private static MezzeSortedSet getSortedSet(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeSortedSet set) return set;
        throw new NativeTypeMismatchException("Expected MezzeSortedSet for field '" + name + "'");
    }

    private static MezzeMap getMap(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeMap map) return map;
        throw new NativeTypeMismatchException("Expected MezzeMap for field '" + name + "'");
    }

    private static MezzeSortedMap getSortedMap(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeSortedMap map) return map;
        throw new NativeTypeMismatchException("Expected MezzeSortedMap for field '" + name + "'");
    }

    private static MezzeSeq getSeq(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeSeq seq) return seq;
        // Also handle Object[] - convert to MezzeSeq
        if (val instanceof Object[] arr) return MezzeSeq.fromArray(arr);
        throw new NativeTypeMismatchException("Expected MezzeSeq for field '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getSimpleName()));
    }

    private static MezzeDeque getDeque(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeDeque deque) return deque;
        throw new NativeTypeMismatchException("Expected MezzeDeque for field '" + name + "'");
    }

    private static NonEmptyListNatives.MezzeNonEmptyList getNonEmptyList(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof NonEmptyListNatives.MezzeNonEmptyList nel) return nel;
        throw new NativeTypeMismatchException("Expected MezzeNonEmptyList for field '" + name + "'");
    }

    private static BytesNatives.MezzeBytes getBytes(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof BytesNatives.MezzeBytes bytes) return bytes;
        throw new NativeTypeMismatchException("Expected MezzeBytes for field '" + name + "'");
    }

    // ==================== String Helper Methods ====================

    @TruffleBoundary
    private static String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    @TruffleBoundary
    private static String toSnakeCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('_');
                result.append(Character.toLowerCase(c));
            } else if (c == '-' || c == ' ') {
                result.append('_');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    @TruffleBoundary
    private static String toKebabCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('-');
                result.append(Character.toLowerCase(c));
            } else if (c == '_' || c == ' ') {
                result.append('-');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    @TruffleBoundary
    private static String toScreamingSnakeCase(String s) {
        return toSnakeCase(s).toUpperCase();
    }

    @TruffleBoundary
    private static MezzeSeq stringLines(String s) {
        String[] lines = s.split("\n", -1);
        Object[] result = new Object[lines.length];
        System.arraycopy(lines, 0, result, 0, lines.length);
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    private static MezzeSeq stringWords(String s) {
        String[] words = s.trim().split("\\s+");
        if (words.length == 1 && words[0].isEmpty()) {
            return MezzeSeq.empty();
        }
        Object[] result = new Object[words.length];
        System.arraycopy(words, 0, result, 0, words.length);
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    private static MezzeSeq stringChars(String s) {
        Object[] result = new Object[s.length()];
        for (int i = 0; i < s.length(); i++) {
            result[i] = String.valueOf(s.charAt(i));
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    private static String padLeft(String s, long width, String pad) {
        if (s.length() >= width) return s;
        String padding = pad.isEmpty() ? " " : pad;
        StringBuilder sb = new StringBuilder();
        while (sb.length() + s.length() < width) {
            sb.append(padding);
        }
        return sb.substring(0, Math.max(0, (int) width - s.length())) + s;
    }

    @TruffleBoundary
    private static String padRight(String s, long width, String pad) {
        if (s.length() >= width) return s;
        String padding = pad.isEmpty() ? " " : pad;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(padding);
        }
        return sb.substring(0, (int) width);
    }

    @TruffleBoundary
    private static MezzeVariant lastIndexOf(String s, String sub) {
        int idx = s.lastIndexOf(sub);
        if (idx < 0) return MezzeVariant.none();
        return MezzeVariant.some((long) idx);
    }

    @TruffleBoundary
    private static boolean globMatch(String pattern, String s) {
        // Custom glob matching optimized for both file paths and URL patterns
        // ** = matches any characters including / (zero or more)
        // * = matches any characters EXCEPT /
        // ? = matches any single character EXCEPT /
        // [abc] = character class
        // [!abc] or [^abc] = negated character class

        StringBuilder regex = new StringBuilder();
        regex.append("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    // Check if **/ - matches zero or more path segments
                    if (i + 2 < pattern.length() && pattern.charAt(i + 2) == '/') {
                        // **/ matches any path prefix including empty
                        regex.append("(?:[^/]*/)*");
                        i += 3;
                    } else {
                        // ** at end or before non-slash matches anything including /
                        regex.append(".*");
                        i += 2;
                    }
                } else {
                    // * matches anything except /
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                // ? matches any single char except /
                regex.append("[^/]");
                i++;
            } else if (c == '[') {
                // Character class
                int end = pattern.indexOf(']', i);
                if (end > i) {
                    String charClass = pattern.substring(i + 1, end);
                    // Handle negation
                    if (charClass.startsWith("!") || charClass.startsWith("^")) {
                        regex.append("[^").append(charClass.substring(1)).append("]");
                    } else {
                        regex.append("[").append(charClass).append("]");
                    }
                    i = end + 1;
                } else {
                    regex.append("\\[");
                    i++;
                }
            } else if (c == '.' || c == '+' || c == '(' || c == ')' || c == '{' || c == '}'
                       || c == '^' || c == '$' || c == '|' || c == '\\') {
                // Escape regex special chars
                regex.append("\\").append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        regex.append("$");
        return s.matches(regex.toString());
    }

    // ==================== Log ====================

    private static void registerLogHandlers() {
        HANDLERS.put("__native_console_print", (r, c) -> LogNatives.consolePrint(r));
        HANDLERS.put("__native_console_println", (r, c) -> LogNatives.consolePrintln(r));
        HANDLERS.put("__native_log_info", (r, c) -> LogNatives.logInfo(r));
        HANDLERS.put("__native_log_warn", (r, c) -> LogNatives.logWarn(r));
        HANDLERS.put("__native_log_error", (r, c) -> LogNatives.logError(r));
        HANDLERS.put("__native_log_debug", (r, c) -> LogNatives.logDebug(r));
        HANDLERS.put("__native_log_trace", (r, c) -> LogNatives.logTrace(r));
    }

    // ==================== Inspect ====================

    private static void registerInspectHandlers() {
        HANDLERS.put("__native_inspect_to_str", (r, c) -> InspectNatives.toStr(r));
    }

    // ==================== Random ====================

    private static void registerRandomHandlers() {
        HANDLERS.put("__native_random_float", (r, c) -> RandomNatives.randomFloat(r));
        HANDLERS.put("__native_random_int", (r, c) -> RandomNatives.randomInt(r));
        HANDLERS.put("__native_random_nat", (r, c) -> RandomNatives.randomNat(r));
        HANDLERS.put("__native_random_seed", (r, c) -> RandomNatives.randomSeed(r));
        HANDLERS.put("__native_seeded_random_float", (r, c) -> RandomNatives.seededRandomFloat(r));
        HANDLERS.put("__native_seeded_random_int", (r, c) -> RandomNatives.seededRandomInt(r));
        HANDLERS.put("__native_seeded_random_nat", (r, c) -> RandomNatives.seededRandomNat(r));
        HANDLERS.put("__native_update_seed", (r, c) -> RandomNatives.updateSeed(r));
        HANDLERS.put("__native_reset_seeded_random", (r, c) -> RandomNatives.resetSeededRandom(r));
    }

    // ==================== Filesystem (LocalDisk, #82) ====================
    // 13 primitive ops. Bytes are the wire format; text conversions live
    // in Mezze helpers. FileHandle is opaque; open/read_chunk/write_chunk/
    // close forms the streaming primitives.

    private static void registerFilesystemHandlers() {
        // Whole-file
        HANDLERS.put("__native_fs_read_bytes",       (r, c) -> FilesystemNatives.readBytes(r));
        HANDLERS.put("__native_fs_write_bytes",      (r, c) -> FilesystemNatives.writeBytes(r));
        // Metadata
        HANDLERS.put("__native_fs_stat",             (r, c) -> FilesystemNatives.stat(r));
        HANDLERS.put("__native_fs_list",             (r, c) -> FilesystemNatives.list(r));
        HANDLERS.put("__native_fs_list_with_stat",   (r, c) -> FilesystemNatives.listWithStat(r));
        // Structural
        HANDLERS.put("__native_fs_delete",           (r, c) -> FilesystemNatives.delete(r));
        HANDLERS.put("__native_fs_create_directory", (r, c) -> FilesystemNatives.createDirectory(r));
        HANDLERS.put("__native_fs_copy_file",        (r, c) -> FilesystemNatives.copyFile(r));
        HANDLERS.put("__native_fs_move_file",        (r, c) -> FilesystemNatives.moveFile(r));
        // Streaming
        HANDLERS.put("__native_fs_open",             (r, c) -> FilesystemNatives.open(r));
        HANDLERS.put("__native_fs_read_chunk",       (r, c) -> FilesystemNatives.readChunk(r));
        HANDLERS.put("__native_fs_write_chunk",      (r, c) -> FilesystemNatives.writeChunk(r));
        HANDLERS.put("__native_fs_close",            (r, c) -> FilesystemNatives.close(r));
    }

    // ==================== Memory Filesystem (MemoryFS, #82) ====================

    private static void registerMemoryFilesystemHandlers() {
        // Whole-file
        HANDLERS.put("__native_memfs_read_bytes",       (r, c) -> MemoryFilesystemNatives.readBytes(r));
        HANDLERS.put("__native_memfs_write_bytes",      (r, c) -> MemoryFilesystemNatives.writeBytes(r));
        // Metadata
        HANDLERS.put("__native_memfs_stat",             (r, c) -> MemoryFilesystemNatives.stat(r));
        HANDLERS.put("__native_memfs_list",             (r, c) -> MemoryFilesystemNatives.list(r));
        HANDLERS.put("__native_memfs_list_with_stat",   (r, c) -> MemoryFilesystemNatives.listWithStat(r));
        // Structural
        HANDLERS.put("__native_memfs_delete",           (r, c) -> MemoryFilesystemNatives.delete(r));
        HANDLERS.put("__native_memfs_create_directory", (r, c) -> MemoryFilesystemNatives.createDirectory(r));
        HANDLERS.put("__native_memfs_copy_file",        (r, c) -> MemoryFilesystemNatives.copyFile(r));
        HANDLERS.put("__native_memfs_move_file",        (r, c) -> MemoryFilesystemNatives.moveFile(r));
        // Streaming
        HANDLERS.put("__native_memfs_open",             (r, c) -> MemoryFilesystemNatives.open(r));
        HANDLERS.put("__native_memfs_read_chunk",       (r, c) -> MemoryFilesystemNatives.readChunk(r));
        HANDLERS.put("__native_memfs_write_chunk",      (r, c) -> MemoryFilesystemNatives.writeChunk(r));
        HANDLERS.put("__native_memfs_close",            (r, c) -> MemoryFilesystemNatives.close(r));
        // Test isolation
        HANDLERS.put("__native_memfs_clear", (r, c) -> {
            MemoryFilesystemNatives.clear();
            return MezzeRecord.empty();
        });
    }

    // ==================== Environment ====================

    private static void registerEnvironmentHandlers() {
        HANDLERS.put("__native_env_get", (r, c) -> EnvironmentNatives.envGet(r));
        HANDLERS.put("__native_env_set", (r, c) -> EnvironmentNatives.envSet(r));
        HANDLERS.put("__native_env_get_all", (r, c) -> EnvironmentNatives.envGetAll(r));
        HANDLERS.put("__native_mock_env_get", (r, c) -> EnvironmentNatives.envGetMock(r));
        HANDLERS.put("__native_env_current_dir", (r, c) -> EnvironmentNatives.currentDir(r));
        HANDLERS.put("__native_env_change_dir", (r, c) -> EnvironmentNatives.changeDir(r));
        HANDLERS.put("__native_env_os_name", (r, c) -> EnvironmentNatives.osName(r));
        HANDLERS.put("__native_env_os_version", (r, c) -> EnvironmentNatives.osVersion(r));
        HANDLERS.put("__native_env_arch", (r, c) -> EnvironmentNatives.arch(r));
        HANDLERS.put("__native_env_process_id", (r, c) -> EnvironmentNatives.processId(r));
        HANDLERS.put("__native_env_process_args", (r, c) -> EnvironmentNatives.processArgs(r));
        HANDLERS.put("__native_env_exit", (r, c) -> EnvironmentNatives.exit(r));
    }

    // ==================== Stdin ====================

    private static void registerStdinHandlers() {
        HANDLERS.put("__native_stdin_read_line", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.readLine(r));
        HANDLERS.put("__native_stdin_read_char", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.readChar(r));
        HANDLERS.put("__native_stdin_read_bytes", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.readBytes(r));
        HANDLERS.put("__native_stdin_is_tty", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.isTty(r));
        HANDLERS.put("__native_stdin_read_password", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.readPassword(r));
        HANDLERS.put("__native_stdin_term_size", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.termSize(r));
        HANDLERS.put("__native_stdin_set_raw_mode", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.setRawMode(r));
        HANDLERS.put("__native_stdin_unset_raw_mode", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.StdinNatives.unsetRawMode(r));

        HANDLERS.put("__native_mock_stdin_read_line", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.MockStdinNatives.readLine(r));
        HANDLERS.put("__native_mock_stdin_read_char", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.MockStdinNatives.readChar(r));
        HANDLERS.put("__native_mock_stdin_read_bytes", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.MockStdinNatives.readBytes(r));
        HANDLERS.put("__native_mock_stdin_read_password", (r, c) -> com.mezze.interpreter.nodes.builtin.effects.MockStdinNatives.readPassword(r));
    }

    // ==================== Async (Loom, OS Thread, Coop) ====================

    private static void registerAsyncHandlers() {
        // === Loom (Virtual Threads) - scales to millions ===
        // Operations that need TruffleContext for cross-thread execution
        HANDLERS.put("__native_async_spawn", (r, c) -> AsyncNatives.spawn(r, c, null));
        HANDLERS.put("__native_async_spawn_tracked", (r, c) -> AsyncNatives.spawnTracked(r, c, null));
        HANDLERS.put("__native_async_scoped", (r, c) -> AsyncNatives.scoped(r, c, null));
        HANDLERS.put("__native_async_scope_spawn", (r, c) -> AsyncNatives.scopeSpawn(r, c, null));

        // Loom scope lifecycle (scoped effects)
        HANDLERS.put("__native_async_init_scope", (r, c) -> AsyncNatives.initScope(r));
        HANDLERS.put("__native_async_finalize_scope", (r, c) -> AsyncNatives.finalizeScopeNative(r));

        // Simple operations that don't spawn threads use regular HANDLERS
        HANDLERS.put("__native_promise_await", (r, c) -> AsyncNatives.awaitOp(r));
        HANDLERS.put("__native_async_sleep", (r, c) -> AsyncNatives.sleep(r));
        HANDLERS.put("__native_async_yield_now", (r, c) -> AsyncNatives.yieldNow(r));
        HANDLERS.put("__native_async_park_until", (r, c) -> AsyncNatives.parkUntil(r, c));
        HANDLERS.put("__native_async_scope_cancel_all", (r, c) -> AsyncNatives.scopeCancelAll(r));
        HANDLERS.put("__native_async_scope_is_cancelled", (r, c) -> AsyncNatives.scopeIsCancelled(r));
        HANDLERS.put("__native_async_scope_get_token", (r, c) -> AsyncNatives.scopeGetToken(r));

        // === Promise-value operations (#117/#118/#123) ===
        // Promise is the user-facing newtype; these natives operate on the
        // underlying MezzePromise regardless of which Async impl spawned it.
        HANDLERS.put("__native_promise_inspect",      (r, c) -> AsyncNatives.promiseInspect(r));
        HANDLERS.put("__native_promise_cancel",       (r, c) -> AsyncNatives.promiseCancel(r));
        HANDLERS.put("__native_promise_succeed",      (r, c) -> AsyncNatives.promiseSucceed(r));
        HANDLERS.put("__native_promise_fail",         (r, c) -> AsyncNatives.promiseFail(r));
        HANDLERS.put("__native_promise_await_result", (r, c) -> AsyncNatives.promiseAwaitResult(r));

        // === User-space Promise constructor (#77) ===
        // promise_from takes a setup callback that receives {complete, fail}
        // capabilities; whichever it invokes transitions the promise to that
        // state. Setup raising auto-fails the promise. Backing for
        // std::effects::async::promise_from + promise_of / promise_failed sugar.
        HANDLERS.put("__native_promise_from", (r, c) -> PromiseNatives.promiseFrom(r, c, null));

        // === OS Threads - true parallelism, limited scale ===
        // Only ops that genuinely create platform threads keep their own
        // natives. Stateless ops (await/race/all/etc.) and parallel
        // collection ops are now pure-Mezze in stdlib/std/effects/async.mz,
        // dispatching to __native_async_* / __native_promise_* against MezzePromise values.
        HANDLERS.put("__native_async_spawn_tracked_os", (r, c) -> AsyncNatives.osSpawnTracked(r, c, null));

        // OS thread scope lifecycle (scoped effects)
        HANDLERS.put("__native_async_init_scope_os", (r, c) -> AsyncNatives.osInitScope(r));
        HANDLERS.put("__native_async_finalize_scope_os", (r, c) -> AsyncNatives.osFinalizeScope(r));

    }

    // ==================== Concurrency Primitives ====================

    private static void registerConcurrencyHandlers() {
        // === TVar (STM) ===
        HANDLERS.put("__native_tvar_new", (r, c) -> ConcurrencyNatives.tvarNew(r));
        HANDLERS.put("__native_tvar_deref", (r, c) -> ConcurrencyNatives.tvarDeref(r));
        HANDLERS.put("__native_stm_read", (r, c) -> ConcurrencyNatives.stmRead(r));
        HANDLERS.put("__native_stm_write", (r, c) -> ConcurrencyNatives.stmWrite(r));
        HANDLERS.put("__native_stm_ensure", (r, c) -> ConcurrencyNatives.stmEnsure(r));
        HANDLERS.put("__native_stm_read_cfg", (r, c) -> ConcurrencyNatives.stmRead(r));
        HANDLERS.put("__native_stm_write_cfg", (r, c) -> ConcurrencyNatives.stmWrite(r));
        HANDLERS.put("__native_stm_ensure_cfg", (r, c) -> ConcurrencyNatives.stmEnsure(r));
        HANDLERS.put("__native_stm_seq_read", (r, c) -> ConcurrencyNatives.stmSeqRead(r));
        HANDLERS.put("__native_stm_seq_write", (r, c) -> ConcurrencyNatives.stmSeqWrite(r));
        HANDLERS.put("__native_stm_seq_ensure", (r, c) -> ConcurrencyNatives.stmSeqEnsure(r));

        // === Atom ===
        HANDLERS.put("__native_atom_new", (r, c) -> ConcurrencyNatives.atomNew(r));
        HANDLERS.put("__native_atom_new_validated", (r, c) -> ConcurrencyNatives.atomNewValidated(r, c));
        HANDLERS.put("__native_atom_deref", (r, c) -> ConcurrencyNatives.atomDeref(r));
        HANDLERS.put("__native_atom_reset", (r, c) -> ConcurrencyNatives.atomReset(r, c));
        HANDLERS.put("__native_atom_swap", (r, c) -> ConcurrencyNatives.atomSwapFunc(r, c));
        HANDLERS.put("__native_atom_compare_and_set", (r, c) -> ConcurrencyNatives.atomCompareAndSet(r));

        // === Mutex ===
        HANDLERS.put("__native_mutex_new", (r, c) -> ConcurrencyNatives.mutexNew(r));
        HANDLERS.put("__native_mutex_lock", (r, c) -> ConcurrencyNatives.mutexLock(r));
        HANDLERS.put("__native_mutex_unlock", (r, c) -> ConcurrencyNatives.mutexUnlock(r));
        HANDLERS.put("__native_mutex_try_lock", (r, c) -> ConcurrencyNatives.mutexTryLock(r));

        // === Semaphore ===
        HANDLERS.put("__native_semaphore_new", (r, c) -> ConcurrencyNatives.semaphoreNew(r));
        HANDLERS.put("__native_semaphore_acquire", (r, c) -> ConcurrencyNatives.semaphoreAcquire(r));
        HANDLERS.put("__native_semaphore_release", (r, c) -> ConcurrencyNatives.semaphoreRelease(r));
        HANDLERS.put("__native_semaphore_try_acquire", (r, c) -> ConcurrencyNatives.semaphoreTryAcquire(r));
        HANDLERS.put("__native_semaphore_available", (r, c) -> ConcurrencyNatives.semaphoreAvailable(r));

        // === Latch ===
        HANDLERS.put("__native_latch_new", (r, c) -> ConcurrencyNatives.latchNew(r));
        HANDLERS.put("__native_latch_count_down", (r, c) -> ConcurrencyNatives.latchCountDown(r));
        HANDLERS.put("__native_latch_await", (r, c) -> ConcurrencyNatives.latchAwait(r));
        HANDLERS.put("__native_latch_get_count", (r, c) -> ConcurrencyNatives.latchGetCount(r));

        // === WaitGroup ===
        HANDLERS.put("__native_waitgroup_new", (r, c) -> ConcurrencyNatives.waitgroupNew(r));
        HANDLERS.put("__native_waitgroup_add", (r, c) -> ConcurrencyNatives.waitgroupAdd(r));
        HANDLERS.put("__native_waitgroup_done", (r, c) -> ConcurrencyNatives.waitgroupDone(r));
        HANDLERS.put("__native_waitgroup_wait", (r, c) -> ConcurrencyNatives.waitgroupWait(r));
        HANDLERS.put("__native_waitgroup_count", (r, c) -> ConcurrencyNatives.waitgroupCount(r));

        // === Channel ===
        // Park-aware send/recv live in stdlib (channel.mz) on top of try_send/try_recv +
        // Async.park. The blocking native send/recv are gone along with the Channel
        // effect (#115).
        HANDLERS.put("__native_channel_make", (r, c) -> ConcurrencyNatives.channelMake(r));
        HANDLERS.put("__native_channel_close", (r, c) -> ConcurrencyNatives.channelClose(r));
        HANDLERS.put("__native_channel_is_closed", (r, c) -> ConcurrencyNatives.channelIsClosed(r));
        HANDLERS.put("__native_channel_try_send", (r, c) -> ConcurrencyNatives.channelTrySend(r));
        HANDLERS.put("__native_channel_try_recv", (r, c) -> ConcurrencyNatives.channelTryRecv(r));
        HANDLERS.put("__native_channel_recv_any", (r, c) -> ConcurrencyNatives.channelRecvAny(r));
        HANDLERS.put("__native_channel_recv_any_timeout", (r, c) -> ConcurrencyNatives.channelRecvAnyTimeout(r));

        // === CancellationToken ===
        HANDLERS.put("__native_cancellation_token_new", (r, c) -> ConcurrencyNatives.cancellationTokenNew(r));
        HANDLERS.put("__native_cancellation_token_cancel", (r, c) -> ConcurrencyNatives.cancellationTokenCancel(r));
        HANDLERS.put("__native_cancellation_token_is_cancelled", (r, c) -> ConcurrencyNatives.cancellationTokenIsCancelled(r));

        // === Agent ===
        HANDLERS.put("__native_agent_new", (r, c) -> ConcurrencyNatives.agentNew(r));
        HANDLERS.put("__native_agent_deref", (r, c) -> ConcurrencyNatives.agentDeref(r));
        HANDLERS.put("__native_agent_send", (r, c) -> ConcurrencyNatives.agentSend(r, null));
        HANDLERS.put("__native_agent_await", (r, c) -> ConcurrencyNatives.agentAwait(r));
        HANDLERS.put("__native_agent_error", (r, c) -> ConcurrencyNatives.agentError(r));

        // === Additional STM functions ===
        HANDLERS.put("__native_stm_atomic", (r, c) -> ConcurrencyNatives.stmAtomic(r, c));
        HANDLERS.put("__native_stm_modify", (r, c) -> ConcurrencyNatives.stmModify(r, c));
        HANDLERS.put("__native_stm_seq_modify", (r, c) -> ConcurrencyNatives.stmSeqModify(r, c));
        HANDLERS.put("__native_stm_seq_swap", (r, c) -> ConcurrencyNatives.stmSeqSwap(r));
        HANDLERS.put("__native_stm_read_all", (r, c) -> ConcurrencyNatives.stmReadAll(r));
        HANDLERS.put("__native_stm_write_all", (r, c) -> ConcurrencyNatives.stmWriteAll(r));

        // === Additional Atom functions ===
        HANDLERS.put("__native_atom_cas", (r, c) -> ConcurrencyNatives.atomCas(r));
        HANDLERS.put("__native_atom_add_watch", (r, c) -> ConcurrencyNatives.atomAddWatch(r, c));
        HANDLERS.put("__native_atom_remove_watch", (r, c) -> ConcurrencyNatives.atomRemoveWatch(r));

        // === Additional Mutex functions ===
        HANDLERS.put("__native_mutex_with_lock", (r, c) -> ConcurrencyNatives.mutexWithLock(r, c));
        HANDLERS.put("__native_mutex_is_held_by_current_thread", (r, c) -> ConcurrencyNatives.mutexIsHeldByCurrentThread(r));

        // === Additional Semaphore functions ===
        HANDLERS.put("__native_semaphore_with_permit", (r, c) -> ConcurrencyNatives.semaphoreWithPermit(r, c));
        HANDLERS.put("__native_semaphore_acquire_n", (r, c) -> ConcurrencyNatives.semaphoreAcquireN(r));
        HANDLERS.put("__native_semaphore_release_n", (r, c) -> ConcurrencyNatives.semaphoreReleaseN(r));

        // === Additional Latch functions ===
        HANDLERS.put("__native_latch_await_timeout", (r, c) -> ConcurrencyNatives.latchAwaitTimeout(r));

        // === Additional WaitGroup functions ===
        HANDLERS.put("__native_waitgroup_get_count", (r, c) -> ConcurrencyNatives.waitgroupGetCount(r));
        HANDLERS.put("__native_waitgroup_wait_timeout", (r, c) -> ConcurrencyNatives.waitgroupWaitTimeout(r));

        // === Additional CancellationToken functions ===
        HANDLERS.put("__native_cancellation_token_create_child", (r, c) -> ConcurrencyNatives.cancellationTokenCreateChild(r));
        HANDLERS.put("__native_cancellation_token_get_reason", (r, c) -> ConcurrencyNatives.cancellationTokenGetReason(r));

        // === Parallel effect: 1-primitive (join) + init/finalize scope ===
        // LoomParallel: Loom virtual threads per fork.
        HANDLERS.put("__native_loom_parallel_init", (r, c) -> ParallelNatives.loomParallelInit(r, null));
        HANDLERS.put("__native_loom_parallel_finalize", (r, c) -> ParallelNatives.loomParallelFinalize(r));
        HANDLERS.put("__native_loom_parallel_join", (r, c) -> ParallelNatives.loomParallelJoin(r, c, null));
        // OSThreadParallel: platform-thread-per-fork (#465). Shares init/finalize
        // with LoomParallel; join spawns virtual(false) Truffle threads.
        HANDLERS.put("__native_osthread_parallel_init", (r, c) -> ParallelNatives.osthreadParallelInit(r, null));
        HANDLERS.put("__native_osthread_parallel_finalize", (r, c) -> ParallelNatives.osthreadParallelFinalize(r));
        HANDLERS.put("__native_osthread_parallel_join", (r, c) -> ParallelNatives.osthreadParallelJoin(r, c, null));
        HANDLERS.put("__native_parallel_min_chunk_hint", (r, c) -> ParallelNatives.minChunkHint(r));
        // Parallel numeric fast paths (#69) — handler-neutral: use ForkJoinPool.commonPool() over long[].
        HANDLERS.put("__native_parallel_sum_long", (r, c) -> ParallelNatives.parallelSumLong(r));
        HANDLERS.put("__native_parallel_product_long", (r, c) -> ParallelNatives.parallelProductLong(r));

        // === Sequential operations ===

        // === Parallel operations ===

        // === SortedMap additional ===
        HANDLERS.put("__native_sorted_map_min_key", (r, c) ->
            SortedMapNatives.minKey(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_max_key", (r, c) ->
            SortedMapNatives.maxKey(getSortedMap(r, "m")));

        // === Additional STM ===
        HANDLERS.put("__native_stm_swap", (r, c) -> ConcurrencyNatives.stmSwap(r));
        HANDLERS.put("__native_stm_check", (r, c) -> ConcurrencyNatives.stmCheck(r));
        HANDLERS.put("__native_stm_seq_check", (r, c) -> ConcurrencyNatives.stmSeqCheck(r));
        HANDLERS.put("__native_stm_retry", (r, c) -> ConcurrencyNatives.stmRetry(r));
        HANDLERS.put("__native_stm_seq_retry", (r, c) -> ConcurrencyNatives.stmSeqRetry(r));
        HANDLERS.put("__native_stm_or_else", (r, c) -> ConcurrencyNatives.stmOrElse(r, c));
        HANDLERS.put("__native_stm_seq_or_else", (r, c) -> ConcurrencyNatives.stmSeqOrElse(r, c));
        HANDLERS.put("__native_stm_check_and_clear_retry_flag", (r, c) -> ConcurrencyNatives.stmCheckAndClearRetryFlag(r));
        HANDLERS.put("__native_stm_park_for_retry", (r, c) -> ConcurrencyNatives.stmParkForRetry(r));
        HANDLERS.put("__native_stm_read_set_changed", (r, c) -> ConcurrencyNatives.stmReadSetChanged(r));
        HANDLERS.put("__native_stm_modify_cfg", (r, c) -> ConcurrencyNatives.stmModifyCfg(r, c));
        HANDLERS.put("__native_stm_begin_transaction", (r, c) -> ConcurrencyNatives.stmBeginTransaction(r));
        HANDLERS.put("__native_stm_commit_transaction", (r, c) -> ConcurrencyNatives.stmCommitTransaction(r));
        HANDLERS.put("__native_stm_commit_transaction_cfg", (r, c) -> ConcurrencyNatives.stmCommitTransactionCfg(r));

        // === Additional Atom ===
        HANDLERS.put("__native_atom_swap_with", (r, c) -> ConcurrencyNatives.atomSwapWith(r, c));
        HANDLERS.put("__native_atom_get_and_swap", (r, c) -> ConcurrencyNatives.atomGetAndSwap(r, c));
        // Validated-atom Result-returning ops
        HANDLERS.put("__native_atom_reset_validated", (r, c) -> ConcurrencyNatives.atomResetValidated(r, c));
        HANDLERS.put("__native_atom_swap_validated", (r, c) -> ConcurrencyNatives.atomSwapValidated(r, c));
        HANDLERS.put("__native_atom_swap_with_validated", (r, c) -> ConcurrencyNatives.atomSwapWithValidated(r, c));
        HANDLERS.put("__native_atom_get_and_swap_validated", (r, c) -> ConcurrencyNatives.atomGetAndSwapValidated(r, c));

        // === Additional Semaphore ===
        HANDLERS.put("__native_semaphore_try_acquire_n", (r, c) -> ConcurrencyNatives.semaphoreTryAcquireN(r));

        // === Additional Channel ===
        HANDLERS.put("__native_channel_make_buffered", (r, c) -> ConcurrencyNatives.channelMakeBuffered(r));

        // === Additional Agent ===
        HANDLERS.put("__native_agent_restart", (r, c) -> ConcurrencyNatives.agentRestart(r));

        // === Additional Sequential ===

        // === Additional Parallel ===

        // === ForkJoin ===

        // === SortedMap from_map ===
        HANDLERS.put("__native_sorted_map_from_map", (r, c) ->
            SortedMapNatives.fromMap(getMap(r, "m"), getFunction(r, "cmp")));
    }

    // ==================== Buffer, JSON, HTTP ====================

    private static void registerIOHandlers() {
        // === Buffer ===
        HANDLERS.put("__native_buffer_empty", (r, c) -> IONatives.bufferEmpty(r));
        HANDLERS.put("__native_buffer_new", (r, c) -> IONatives.bufferNew(r));
        HANDLERS.put("__native_buffer_from_bytes", (r, c) -> IONatives.bufferFromBytes(r));
        HANDLERS.put("__native_buffer_from_string", (r, c) -> IONatives.bufferFromString(r));
        HANDLERS.put("__native_buffer_to_bytes", (r, c) -> IONatives.bufferToBytes(r));
        HANDLERS.put("__native_buffer_to_str", (r, c) -> IONatives.bufferToString(r));
        HANDLERS.put("__native_buffer_length", (r, c) -> IONatives.bufferLength(r));
        HANDLERS.put("__native_buffer_position", (r, c) -> IONatives.bufferPosition(r));
        HANDLERS.put("__native_buffer_set_position", (r, c) -> IONatives.bufferSetPosition(r));
        HANDLERS.put("__native_buffer_write_byte", (r, c) -> IONatives.bufferWriteByte(r));
        HANDLERS.put("__native_buffer_read_byte", (r, c) -> IONatives.bufferReadByte(r));
        HANDLERS.put("__native_buffer_write_bytes", (r, c) -> IONatives.bufferWriteBytes(r));
        HANDLERS.put("__native_buffer_read_bytes", (r, c) -> IONatives.bufferReadBytes(r));
        HANDLERS.put("__native_buffer_slice", (r, c) -> IONatives.bufferSlice(r));
        HANDLERS.put("__native_buffer_concat", (r, c) -> IONatives.bufferConcat(r));
        HANDLERS.put("__native_buffer_copy", (r, c) -> IONatives.bufferCopy(r));
        HANDLERS.put("__native_buffer_clear", (r, c) -> IONatives.bufferClear(r));
        HANDLERS.put("__native_buffer_write_string", (r, c) -> IONatives.bufferWriteString(r));
        HANDLERS.put("__native_buffer_write", (r, c) -> IONatives.bufferWrite(r));
        HANDLERS.put("__native_buffer_is_empty", (r, c) -> IONatives.bufferIsEmpty(r));
        HANDLERS.put("__native_buffer_size", (r, c) -> IONatives.bufferSize(r));

        // === JSON (DSL-JSON powered) ===
        HANDLERS.put("__native_json_serialize", (r, c) -> IONatives.jsonSerialize(r));
        HANDLERS.put("__native_json_serialize_bytes", (r, c) -> IONatives.jsonSerializeBytes(r));
        HANDLERS.put("__native_json_parse", (r, c) -> IONatives.jsonParse(r));
        HANDLERS.put("__native_json_parse_bytes", (r, c) -> IONatives.jsonParseBytes(r));

        // === Codec (auto-generated decode/encode for records) ===
        HANDLERS.put("__native_codec_decode_record", (r, c) -> nativeCodecDecodeRecord(r));

        // === HTTP Mock ===
        HANDLERS.put("__native_http_mock_request", (r, c) -> IONatives.httpMockRequest(r));
        HANDLERS.put("__native_http_mock_set_response", (r, c) -> IONatives.httpMockSetResponse(r));
        HANDLERS.put("__native_http_mock_clear", (r, c) -> IONatives.httpMockClear(r));

        // === HTTP Client ===
        HANDLERS.put("__native_http_client_create", (r, c) -> IONatives.httpClientCreate(r));
        HANDLERS.put("__native_http_client_close", (r, c) -> IONatives.httpClientClose(r));
        HANDLERS.put("__native_http_request", (r, c) -> IONatives.httpRequest(r));
    }

    // ==================== Mutation Operations ====================
    // Safe local mutation - all mutations happen in-place on mutable wrappers

    private static void registerMutationHandlers() {
        // Loop implementation for LocalMut - efficient while loop
        HANDLERS.put("__native_mutation_loop", (r, callNode) -> {
            MezzeFunction cond = (MezzeFunction) getField(r, "cond");
            MezzeFunction body = (MezzeFunction) getField(r, "body");
            whileLoop(cond, body, callNode);
            return MezzeRecord.empty();
        });

        // DebugMut implementation of mutate - executes act with debug logging
        HANDLERS.put("__native_debug_mutation_mutate", (r, callNode) -> {
            MezzeFunction act = (MezzeFunction) getField(r, "act");
            debugMutationMarker();
            Object[] closureValues = act.getClosureValues();
            int closureLen = closureValues.length;
            Object[] callArgs = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, callArgs, 0, closureLen);
            callArgs[closureLen] = MezzeRecord.empty();
            return callNode.call(act.getCallTarget(), callArgs);
        });

        // DebugMut implementation of loop - while loop with debug logging
        HANDLERS.put("__native_debug_mutation_loop", (r, callNode) -> {
            MezzeFunction cond = (MezzeFunction) getField(r, "cond");
            MezzeFunction body = (MezzeFunction) getField(r, "body");
            debugWhileLoop(cond, body, callNode);
            return MezzeRecord.empty();
        });

        // Seq conversions
        HANDLERS.put("__native_seq_to_mut", (r, c) -> {
            MezzeSeq seq = getSeq(r, "seq");
            return MutableSeq.fromImmutable(seq);
        });

        HANDLERS.put("__native_seq_to_immut", (r, c) -> {
            Object seqObj = getField(r, "seq");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_seq_to_immut expects MutableSeq");
            }
            return mutSeq.toImmutable();
        });

        HANDLERS.put("__native_mut_seq_with_capacity", (r, c) -> {
            long capacity = getLong(r, "capacity");
            return MutableSeq.withCapacity((int) capacity);
        });

        // Map conversions
        HANDLERS.put("__native_map_to_mut", (r, c) -> {
            MezzeMap map = getMap(r, "map");
            return MutableMap.fromImmutable(map);
        });

        HANDLERS.put("__native_map_to_immut", (r, c) -> {
            Object mapObj = getField(r, "map");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_map_to_immut expects MutableMap");
            }
            return mutMap.toImmutable();
        });

        HANDLERS.put("__native_mut_map_with_capacity", (r, c) -> {
            long capacity = getLong(r, "capacity");
            return MutableMap.withCapacity((int) capacity);
        });

        // Set conversions
        HANDLERS.put("__native_set_to_mut", (r, c) -> {
            MezzeSet set = getSet(r, "set");
            return MutableSet.fromImmutable(set);
        });

        HANDLERS.put("__native_set_to_immut", (r, c) -> {
            Object setObj = getField(r, "set");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_set_to_immut expects MutableSet");
            }
            return mutSet.toImmutable();
        });

        HANDLERS.put("__native_mut_set_with_capacity", (r, c) -> {
            long capacity = getLong(r, "capacity");
            return MutableSet.withCapacity((int) capacity);
        });

        // Bytes conversions
        HANDLERS.put("__native_bytes_to_mut", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            byte[] bytes;
            if (bytesObj instanceof byte[] b) {
                bytes = b;
            } else if (bytesObj instanceof MezzeBytes mb) {
                bytes = mb.getData();
            } else if (bytesObj instanceof BytesNatives.MezzeBytes bnb) {
                bytes = bnb.getData();
            } else {
                throw new NativeTypeMismatchException("__native_bytes_to_mut expects Bytes, got: " +
                    (bytesObj == null ? "null" : bytesObj.getClass().getName()));
            }
            return MutableBytes.fromImmutable(bytes);
        });

        HANDLERS.put("__native_bytes_to_immut", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_bytes_to_immut expects MutableBytes");
            }
            return mutBytes.toImmutable();
        });

        // Primitive conversions
        HANDLERS.put("__native_int_to_mut", (r, c) -> {
            long n = getLong(r, "n");
            return new MutableInt(n);
        });

        HANDLERS.put("__native_int_to_immut", (r, c) -> {
            Object nObj = getField(r, "n");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_int_to_immut expects MutableInt");
            }
            return mutInt.get();
        });

        HANDLERS.put("__native_float_to_mut", (r, c) -> {
            double n = getDouble(r, "n");
            return new MutableFloat(n);
        });

        HANDLERS.put("__native_float_to_immut", (r, c) -> {
            Object nObj = getField(r, "n");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_float_to_immut expects MutableFloat");
            }
            return mutFloat.get();
        });

        HANDLERS.put("__native_bool_to_mut", (r, c) -> {
            boolean b = getBool(r, "b");
            return new MutableBool(b);
        });

        HANDLERS.put("__native_bool_to_immut", (r, c) -> {
            Object bObj = getField(r, "b");
            if (!(bObj instanceof MutableBool mutBool)) {
                throw new NativeTypeMismatchException("__native_bool_to_immut expects MutableBool");
            }
            return mutBool.get();
        });

        HANDLERS.put("__native_mut_bool_read", (r, c) -> {
            Object bObj = getField(r, "b");
            if (!(bObj instanceof MutableBool mutBool)) {
                throw new NativeTypeMismatchException("__native_mut_bool_read expects MutableBool");
            }
            return mutBool.get();
        });

        HANDLERS.put("__native_mut_bool_write", (r, c) -> {
            Object bObj = getField(r, "b");
            if (!(bObj instanceof MutableBool mutBool)) {
                throw new NativeTypeMismatchException("__native_mut_bool_write expects MutableBool");
            }
            boolean v = getBool(r, "v");
            mutBool.set(v);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bool_toggle", (r, c) -> {
            Object bObj = getField(r, "b");
            if (!(bObj instanceof MutableBool mutBool)) {
                throw new NativeTypeMismatchException("__native_mut_bool_toggle expects MutableBool");
            }
            mutBool.toggle();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bool_set_true", (r, c) -> {
            Object bObj = getField(r, "b");
            if (!(bObj instanceof MutableBool mutBool)) {
                throw new NativeTypeMismatchException("__native_mut_bool_set_true expects MutableBool");
            }
            mutBool.set(true);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bool_set_false", (r, c) -> {
            Object bObj = getField(r, "b");
            if (!(bObj instanceof MutableBool mutBool)) {
                throw new NativeTypeMismatchException("__native_mut_bool_set_false expects MutableBool");
            }
            mutBool.set(false);
            return MezzeRecord.empty();
        });

        // MutString operations
        HANDLERS.put("__native_string_to_mut", (r, c) -> {
            String s = getString(r, "s");
            return new MutableString(s);
        });

        HANDLERS.put("__native_string_with_capacity", (r, c) -> {
            long capacity = getLong(r, "capacity");
            return new MutableString((int) capacity);
        });

        HANDLERS.put("__native_string_to_immut", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_string_to_immut expects MutableString");
            }
            return mutStr.get();
        });

        HANDLERS.put("__native_mut_string_read", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_read expects MutableString");
            }
            return mutStr.get();
        });

        HANDLERS.put("__native_mut_string_write", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_write expects MutableString");
            }
            String v = getString(r, "v");
            mutStr.set(v);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_len", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_len expects MutableString");
            }
            return (long) mutStr.length();
        });

        HANDLERS.put("__native_mut_string_is_empty", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_is_empty expects MutableString");
            }
            return mutStr.isEmpty();
        });

        HANDLERS.put("__native_mut_string_append", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_append expects MutableString");
            }
            String suffix = getString(r, "suffix");
            mutStr.append(suffix);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_prepend", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_prepend expects MutableString");
            }
            String prefix = getString(r, "prefix");
            mutStr.prepend(prefix);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_clear", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_clear expects MutableString");
            }
            mutStr.clear();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_reverse", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_reverse expects MutableString");
            }
            mutStr.reverse();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_insert", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_insert expects MutableString");
            }
            int index = (int) getLong(r, "i");
            String text = getString(r, "text");
            mutStr.insert(index, text);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_delete", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_delete expects MutableString");
            }
            int start = (int) getLong(r, "start");
            int end = (int) getLong(r, "end");
            mutStr.delete(start, end);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_string_char_at", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_char_at expects MutableString");
            }
            int index = (int) getLong(r, "i");
            char ch = mutStr.charAt(index);
            if (ch == '\0') {
                return MezzeVariant.none();
            }
            // Char type is represented as a single-character String
            return MezzeVariant.some(String.valueOf(ch));
        });

        HANDLERS.put("__native_mut_string_set_char_at", (r, c) -> {
            Object sObj = getField(r, "s");
            if (!(sObj instanceof MutableString mutStr)) {
                throw new NativeTypeMismatchException("__native_mut_string_set_char_at expects MutableString");
            }
            int index = (int) getLong(r, "i");
            // Char type is represented as a single-character String
            String charStr = getString(r, "c");
            if (charStr.isEmpty()) {
                throw new NativeTypeMismatchException("[internal] __native_mut_string_set_char_at: Char value must be a single-character String, got empty");
            }
            mutStr.setCharAt(index, charStr.charAt(0));
            return MezzeRecord.empty();
        });

        // MutChar operations
        // Note: Mezze Char type is represented as a single-character String
        HANDLERS.put("__native_char_to_mut", (r, c) -> {
            String charStr = getString(r, "c");
            if (charStr.isEmpty()) {
                throw new NativeTypeMismatchException("[internal] __native_char_to_mut: Char value must be a single-character String, got empty");
            }
            return new MutableChar(charStr.charAt(0));
        });

        HANDLERS.put("__native_char_to_immut", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_char_to_immut expects MutableChar");
            }
            return String.valueOf(mutChar.get());
        });

        HANDLERS.put("__native_mut_char_read", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_read expects MutableChar");
            }
            return String.valueOf(mutChar.get());
        });

        HANDLERS.put("__native_mut_char_write", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_write expects MutableChar");
            }
            String vStr = getString(r, "v");
            if (vStr.isEmpty()) {
                throw new NativeTypeMismatchException("[internal] __native_mut_char_write: Char value must be a single-character String, got empty");
            }
            mutChar.set(vStr.charAt(0));
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_char_to_upper", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_to_upper expects MutableChar");
            }
            mutChar.toUpper();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_char_to_lower", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_to_lower expects MutableChar");
            }
            mutChar.toLower();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_char_is_digit", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_is_digit expects MutableChar");
            }
            return mutChar.isDigit();
        });

        HANDLERS.put("__native_mut_char_is_letter", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_is_letter expects MutableChar");
            }
            return mutChar.isLetter();
        });

        HANDLERS.put("__native_mut_char_is_whitespace", (r, c) -> {
            Object cObj = getField(r, "c");
            if (!(cObj instanceof MutableChar mutChar)) {
                throw new NativeTypeMismatchException("__native_mut_char_is_whitespace expects MutableChar");
            }
            return mutChar.isWhitespace();
        });

        // MutSeq operations
        HANDLERS.put("__native_mut_seq_sort", (r, c) -> {
            Object seqObj = getField(r, "seq");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_sort expects MutableSeq");
            }
            mutSeq.sort();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_seq_sort_by", (r, c) -> {
            Object seqObj = getField(r, "seq");
            Object cmpObj = getField(r, "cmp");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_sort_by expects MutableSeq");
            }
            if (!(cmpObj instanceof MezzeFunction cmp)) {
                throw new NativeTypeMismatchException("__native_mut_seq_sort_by expects comparator function");
            }
            mutSeq.sortBy(cmp, c);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_seq_reverse", (r, c) -> {
            Object seqObj = getField(r, "seq");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_reverse expects MutableSeq");
            }
            mutSeq.reverse();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_seq_fill", (r, c) -> {
            Object seqObj = getField(r, "seq");
            Object value = getField(r, "v");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_fill expects MutableSeq");
            }
            mutSeq.fill(value);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_seq_fill_range", (r, c) -> {
            Object seqObj = getField(r, "seq");
            long start = getLong(r, "start");
            long end = getLong(r, "end");
            Object value = getField(r, "v");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_fill_range expects MutableSeq");
            }
            mutSeq.fillRange((int) start, (int) end, value);
            return MezzeRecord.empty();
        });

        // MutInt operations
        HANDLERS.put("__native_mut_int_read", (r, c) -> {
            Object nObj = getField(r, "n");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_read expects MutableInt");
            }
            return mutInt.get();
        });

        HANDLERS.put("__native_mut_int_write", (r, c) -> {
            Object nObj = getField(r, "n");
            long v = getLong(r, "v");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_write expects MutableInt");
            }
            mutInt.set(v);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_int_incr", (r, c) -> {
            Object nObj = getField(r, "n");
            long delta = getLong(r, "delta");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_incr expects MutableInt");
            }
            mutInt.add(delta);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_int_decr", (r, c) -> {
            Object nObj = getField(r, "n");
            long delta = getLong(r, "delta");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_decr expects MutableInt");
            }
            mutInt.subtract(delta);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_int_mul", (r, c) -> {
            Object nObj = getField(r, "n");
            long factor = getLong(r, "factor");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_mul expects MutableInt");
            }
            mutInt.multiply(factor);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_int_div", (r, c) -> {
            Object nObj = getField(r, "n");
            long divisor = getLong(r, "divisor");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_div expects MutableInt");
            }
            mutInt.divide(divisor);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_int_mod", (r, c) -> {
            Object nObj = getField(r, "n");
            long divisor = getLong(r, "divisor");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_mod expects MutableInt");
            }
            mutInt.mod(divisor);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_int_cas", (r, c) -> {
            Object nObj = getField(r, "n");
            long expected = getLong(r, "expected");
            long newValue = getLong(r, "new_val");
            if (!(nObj instanceof MutableInt mutInt)) {
                throw new NativeTypeMismatchException("__native_mut_int_cas expects MutableInt");
            }
            return mutInt.compareAndSwap(expected, newValue);
        });

        // MutFloat extended operations
        HANDLERS.put("__native_mut_float_read", (r, c) -> {
            Object nObj = getField(r, "n");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_mut_float_read expects MutableFloat");
            }
            return mutFloat.get();
        });

        HANDLERS.put("__native_mut_float_write", (r, c) -> {
            Object nObj = getField(r, "n");
            double v = getDouble(r, "v");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_mut_float_write expects MutableFloat");
            }
            mutFloat.set(v);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_float_add", (r, c) -> {
            Object nObj = getField(r, "n");
            double delta = getDouble(r, "delta");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_mut_float_add expects MutableFloat");
            }
            mutFloat.add(delta);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_float_sub", (r, c) -> {
            Object nObj = getField(r, "n");
            double delta = getDouble(r, "delta");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_mut_float_sub expects MutableFloat");
            }
            mutFloat.subtract(delta);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_float_mul", (r, c) -> {
            Object nObj = getField(r, "n");
            double factor = getDouble(r, "factor");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_mut_float_mul expects MutableFloat");
            }
            mutFloat.multiply(factor);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_float_div", (r, c) -> {
            Object nObj = getField(r, "n");
            double divisor = getDouble(r, "divisor");
            if (!(nObj instanceof MutableFloat mutFloat)) {
                throw new NativeTypeMismatchException("__native_mut_float_div expects MutableFloat");
            }
            mutFloat.divide(divisor);
            return MezzeRecord.empty();
        });

        // MutSeq dynamic operations
        HANDLERS.put("__native_mut_seq_push", (r, c) -> {
            Object seqObj = getField(r, "seq");
            Object value = getField(r, "v");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_push expects MutableSeq");
            }
            mutSeq.push(value);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_seq_pop", (r, c) -> {
            Object seqObj = getField(r, "seq");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_pop expects MutableSeq");
            }
            Object value = mutSeq.pop();
            // Return Option: Some {val} or None
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_seq_get", (r, c) -> {
            Object seqObj = getField(r, "seq");
            long index = getLong(r, "i");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_get expects MutableSeq");
            }
            Object value = mutSeq.getSafe((int) index);
            // Return Option: Some {val} or None
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_seq_set", (r, c) -> {
            Object seqObj = getField(r, "seq");
            long index = getLong(r, "i");
            Object value = getField(r, "v");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_set expects MutableSeq");
            }
            boolean success = mutSeq.setSafe((int) index, value);
            return success;
        });

        HANDLERS.put("__native_mut_seq_len", (r, c) -> {
            Object seqObj = getField(r, "seq");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_len expects MutableSeq");
            }
            return (long) mutSeq.size();
        });

        HANDLERS.put("__native_mut_seq_clear", (r, c) -> {
            Object seqObj = getField(r, "seq");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_clear expects MutableSeq");
            }
            mutSeq.clear();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_seq_insert", (r, c) -> {
            Object seqObj = getField(r, "seq");
            long index = getLong(r, "i");
            Object value = getField(r, "v");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_insert expects MutableSeq");
            }
            return mutSeq.insert((int) index, value);
        });

        HANDLERS.put("__native_mut_seq_remove", (r, c) -> {
            Object seqObj = getField(r, "seq");
            long index = getLong(r, "i");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_remove expects MutableSeq");
            }
            Object value = mutSeq.remove((int) index);
            // Return Option: Some {val} or None
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_seq_swap", (r, c) -> {
            Object seqObj = getField(r, "seq");
            long i = getLong(r, "i");
            long j = getLong(r, "j");
            if (!(seqObj instanceof MutableSeq mutSeq)) {
                throw new NativeTypeMismatchException("__native_mut_seq_swap expects MutableSeq");
            }
            try {
                mutSeq.swap((int) i, (int) j);
                return true;
            } catch (IndexOutOfBoundsException e) {
                return false;
            }
        });

        // MutMap operations
        HANDLERS.put("__native_mut_map_get", (r, c) -> {
            Object mapObj = getField(r, "map");
            Object key = getField(r, "key");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_get expects MutableMap");
            }
            Object value = mutMap.get(key);
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_map_put", (r, c) -> {
            Object mapObj = getField(r, "map");
            Object key = getField(r, "key");
            Object value = getField(r, "value");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_put expects MutableMap");
            }
            mutMap.put(key, value);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_map_remove", (r, c) -> {
            Object mapObj = getField(r, "map");
            Object key = getField(r, "key");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_remove expects MutableMap");
            }
            mutMap.remove(key);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_map_contains", (r, c) -> {
            Object mapObj = getField(r, "map");
            Object key = getField(r, "key");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_contains expects MutableMap");
            }
            return mutMap.containsKey(key);
        });

        HANDLERS.put("__native_mut_map_size", (r, c) -> {
            Object mapObj = getField(r, "map");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_size expects MutableMap");
            }
            return (long) mutMap.size();
        });

        HANDLERS.put("__native_mut_map_clear", (r, c) -> {
            Object mapObj = getField(r, "map");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_clear expects MutableMap");
            }
            mutMap.clear();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_map_keys", (r, c) -> {
            Object mapObj = getField(r, "map");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_keys expects MutableMap");
            }
            return MezzeSeq.fromArray(mutMap.keys());
        });

        HANDLERS.put("__native_mut_map_values", (r, c) -> {
            Object mapObj = getField(r, "map");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_values expects MutableMap");
            }
            return MezzeSeq.fromArray(mutMap.values());
        });

        HANDLERS.put("__native_mut_map_entries", (r, c) -> {
            Object mapObj = getField(r, "map");
            if (!(mapObj instanceof MutableMap mutMap)) {
                throw new NativeTypeMismatchException("__native_mut_map_entries expects MutableMap");
            }
            return MezzeSeq.fromArray(mutMap.entries());
        });

        // MutSet operations
        HANDLERS.put("__native_mut_set_add", (r, c) -> {
            Object setObj = getField(r, "set");
            Object elem = getField(r, "elem");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_add expects MutableSet");
            }
            mutSet.add(elem);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_set_remove", (r, c) -> {
            Object setObj = getField(r, "set");
            Object elem = getField(r, "elem");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_remove expects MutableSet");
            }
            mutSet.remove(elem);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_set_contains", (r, c) -> {
            Object setObj = getField(r, "set");
            Object elem = getField(r, "elem");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_contains expects MutableSet");
            }
            return mutSet.contains(elem);
        });

        HANDLERS.put("__native_mut_set_size", (r, c) -> {
            Object setObj = getField(r, "set");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_size expects MutableSet");
            }
            return (long) mutSet.size();
        });

        HANDLERS.put("__native_mut_set_clear", (r, c) -> {
            Object setObj = getField(r, "set");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_clear expects MutableSet");
            }
            mutSet.clear();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_set_elements", (r, c) -> {
            Object setObj = getField(r, "set");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_elements expects MutableSet");
            }
            return MezzeSeq.fromArray(mutSet.elements());
        });

        HANDLERS.put("__native_mut_set_toggle", (r, c) -> {
            Object setObj = getField(r, "set");
            Object elem = getField(r, "elem");
            if (!(setObj instanceof MutableSet mutSet)) {
                throw new NativeTypeMismatchException("__native_mut_set_toggle expects MutableSet");
            }
            return mutSet.toggle(elem);
        });

        // MutBytes operations
        HANDLERS.put("__native_mut_bytes_get", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long index = getLong(r, "i");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_get expects MutableBytes");
            }
            int value = mutBytes.getSafe((int) index);
            if (value == -1) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some((long) value);
            }
        });

        HANDLERS.put("__native_mut_bytes_set", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long index = getLong(r, "i");
            long value = getLong(r, "v");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_set expects MutableBytes");
            }
            return mutBytes.setSafe((int) index, (int) value);
        });

        HANDLERS.put("__native_mut_bytes_len", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_len expects MutableBytes");
            }
            return (long) mutBytes.length();
        });

        HANDLERS.put("__native_mut_bytes_fill", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long value = getLong(r, "v");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_fill expects MutableBytes");
            }
            mutBytes.fill((byte) value);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bytes_fill_range", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long start = getLong(r, "start");
            long end = getLong(r, "end");
            long value = getLong(r, "v");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_fill_range expects MutableBytes");
            }
            mutBytes.fillRange((int) start, (int) end, (byte) value);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bytes_slice", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long start = getLong(r, "start");
            long end = getLong(r, "end");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_slice expects MutableBytes");
            }
            return mutBytes.slice((int) start, (int) end);
        });

        HANDLERS.put("__native_mut_bytes_resize", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long newSize = getLong(r, "size");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_resize expects MutableBytes");
            }
            mutBytes.resize((int) newSize);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bytes_swap", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            long i = getLong(r, "i");
            long j = getLong(r, "j");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_swap expects MutableBytes");
            }
            return mutBytes.swap((int) i, (int) j);
        });

        HANDLERS.put("__native_mut_bytes_reverse", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_reverse expects MutableBytes");
            }
            mutBytes.reverse();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bytes_clear", (r, c) -> {
            Object bytesObj = getField(r, "bytes");
            if (!(bytesObj instanceof MutableBytes mutBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_clear expects MutableBytes");
            }
            mutBytes.clear();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_bytes_copy", (r, c) -> {
            Object destObj = getField(r, "dest");
            Object srcObj = getField(r, "src");
            long srcOffset = getLong(r, "src_offset");
            long destOffset = getLong(r, "dest_offset");
            long length = getLong(r, "length");
            if (!(destObj instanceof MutableBytes destBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_copy expects MutableBytes for dest");
            }
            if (!(srcObj instanceof MutableBytes srcBytes)) {
                throw new NativeTypeMismatchException("__native_mut_bytes_copy expects MutableBytes for src");
            }
            destBytes.copyFromMutable(srcBytes, (int) srcOffset, (int) destOffset, (int) length);
            return MezzeRecord.empty();
        });

        // MutDeque operations
        HANDLERS.put("__native_deque_to_mut", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MezzeDeque deque)) {
                throw new NativeTypeMismatchException("__native_deque_to_mut expects MezzeDeque");
            }
            return MutableDeque.fromImmutable(deque);
        });

        HANDLERS.put("__native_deque_to_immut", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_deque_to_immut expects MutableDeque");
            }
            return mutDeque.toImmutable();
        });

        HANDLERS.put("__native_mut_deque_with_capacity", (r, c) -> {
            long capacity = getLong(r, "capacity");
            return MutableDeque.withCapacity((int) capacity);
        });

        HANDLERS.put("__native_mut_deque_push_front", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            Object elem = getField(r, "elem");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_push_front expects MutableDeque");
            }
            mutDeque.pushFront(elem);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_deque_push_back", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            Object elem = getField(r, "elem");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_push_back expects MutableDeque");
            }
            mutDeque.pushBack(elem);
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_deque_pop_front", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_pop_front expects MutableDeque");
            }
            Object value = mutDeque.popFront();
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_deque_pop_back", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_pop_back expects MutableDeque");
            }
            Object value = mutDeque.popBack();
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_deque_peek_front", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_peek_front expects MutableDeque");
            }
            Object value = mutDeque.peekFront();
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_deque_peek_back", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_peek_back expects MutableDeque");
            }
            Object value = mutDeque.peekBack();
            if (value == null) {
                return MezzeVariant.none();
            } else {
                return MezzeVariant.some(value);
            }
        });

        HANDLERS.put("__native_mut_deque_size", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_size expects MutableDeque");
            }
            return (long) mutDeque.size();
        });

        HANDLERS.put("__native_mut_deque_is_empty", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_is_empty expects MutableDeque");
            }
            return mutDeque.isEmpty();
        });

        HANDLERS.put("__native_mut_deque_clear", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_clear expects MutableDeque");
            }
            mutDeque.clear();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_deque_reverse", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_reverse expects MutableDeque");
            }
            mutDeque.reverse();
            return MezzeRecord.empty();
        });

        HANDLERS.put("__native_mut_deque_to_seq", (r, c) -> {
            Object dequeObj = getField(r, "deque");
            if (!(dequeObj instanceof MutableDeque mutDeque)) {
                throw new NativeTypeMismatchException("__native_mut_deque_to_seq expects MutableDeque");
            }
            return MezzeSeq.fromArray(mutDeque.toArray());
        });

        // Reference equality for opaque types (newtypes wrapping native objects)
        HANDLERS.put("__native_ref_eq", (r, c) -> {
            Object l = getField(r, "l");
            Object r2 = getField(r, "r");
            return l == r2;
        });
        HANDLERS.put("__native_ref_neq", (r, c) -> {
            Object l = getField(r, "l");
            Object r2 = getField(r, "r");
            return l != r2;
        });
    }

    // ==================== Polyglot Interop ====================
    //
    // Bridges std::polyglot::core / std::polyglot::jvm to the Truffle
    // InteropLibrary. Each Foreign-consuming native takes a Foreign value
    // (auto-wrapping raw values in a MezzeForeign) and returns either a
    // primitive result, a wrapped Foreign, or a Result Foreign InteropError.
    //
    // Context lifecycle natives (create/close/eval/binding) route through
    // MezzePolyglotSupport via MezzeContext.get(callNode) — the callNode
    // is used to resolve the enclosing TruffleLanguage.Env.

    private static void registerPolyglotHandlers() {
        // Type predicates & coercion
        HANDLERS.put("__native_polyglot_is_null",
            (r, c) -> PolyglotNatives.isNull(r));
        HANDLERS.put("__native_polyglot_is_string",
            (r, c) -> PolyglotNatives.isString(r));
        HANDLERS.put("__native_polyglot_as_string",
            (r, c) -> PolyglotNatives.asString(r));
        HANDLERS.put("__native_polyglot_is_number",
            (r, c) -> PolyglotNatives.isNumber(r));
        HANDLERS.put("__native_polyglot_fits_in_long",
            (r, c) -> PolyglotNatives.fitsInLong(r));
        HANDLERS.put("__native_polyglot_as_long",
            (r, c) -> PolyglotNatives.asLong(r));
        HANDLERS.put("__native_polyglot_as_double",
            (r, c) -> PolyglotNatives.asDouble(r));
        HANDLERS.put("__native_polyglot_as_boolean",
            (r, c) -> PolyglotNatives.asBoolean(r));

        // Constructors
        HANDLERS.put("__native_polyglot_from_int",
            (r, c) -> PolyglotNatives.fromInt(r));
        HANDLERS.put("__native_polyglot_from_float",
            (r, c) -> PolyglotNatives.fromFloat(r));
        HANDLERS.put("__native_polyglot_from_str",
            (r, c) -> PolyglotNatives.fromStr(r));
        HANDLERS.put("__native_polyglot_from_bool",
            (r, c) -> PolyglotNatives.fromBool(r));
        HANDLERS.put("__native_polyglot_null_val",
            (r, c) -> PolyglotNatives.nullVal(r));

        // Members
        HANDLERS.put("__native_polyglot_has_members",
            (r, c) -> PolyglotNatives.hasMembers(r));
        HANDLERS.put("__native_polyglot_has_member",
            (r, c) -> PolyglotNatives.hasMember(r));
        HANDLERS.put("__native_polyglot_read_member",
            (r, c) -> PolyglotNatives.readMember(r));
        HANDLERS.put("__native_polyglot_write_member",
            (r, c) -> PolyglotNatives.writeMember(r));
        HANDLERS.put("__native_polyglot_invoke_member",
            (r, c) -> PolyglotNatives.invokeMember(r));

        // Arrays
        HANDLERS.put("__native_polyglot_has_array_elements",
            (r, c) -> PolyglotNatives.hasArrayElements(r));
        HANDLERS.put("__native_polyglot_array_size",
            (r, c) -> PolyglotNatives.arraySize(r));
        HANDLERS.put("__native_polyglot_read_array",
            (r, c) -> PolyglotNatives.readArray(r));
        HANDLERS.put("__native_polyglot_write_array",
            (r, c) -> PolyglotNatives.writeArray(r));
        // BUG 2: the read side existed, the build side did not — so a codec's
        // `encode_list` / `encode_map` had no native to call and containers had
        // no `.to_py {}` / `.to_js {}` chain at all.
        HANDLERS.put("__native_polyglot_make_list",
            (r, c) -> PolyglotNatives.makeList(r));
        HANDLERS.put("__native_polyglot_make_dict",
            (r, c) -> PolyglotNatives.makeDict(r));

        // Execution
        HANDLERS.put("__native_polyglot_is_executable",
            (r, c) -> PolyglotNatives.isExecutable(r));
        HANDLERS.put("__native_polyglot_execute",
            (r, c) -> PolyglotNatives.execute(r));
        HANDLERS.put("__native_polyglot_is_instantiable",
            (r, c) -> PolyglotNatives.isInstantiable(r));
        HANDLERS.put("__native_polyglot_instantiate",
            (r, c) -> PolyglotNatives.instantiate(r));

        // Meta
        HANDLERS.put("__native_polyglot_type_name",
            (r, c) -> PolyglotNatives.typeName(r));

        // JVM host access + language-tagged constructors
        HANDLERS.put("__native_jvm_class",
            (r, c) -> JvmNatives.lookupClass(r, c));
        HANDLERS.put("__native_jvm_from_int",
            (r, c) -> PolyglotNatives.fromInt(r, "jvm"));
        HANDLERS.put("__native_jvm_from_float",
            (r, c) -> PolyglotNatives.fromFloat(r, "jvm"));
        HANDLERS.put("__native_jvm_from_str",
            (r, c) -> PolyglotNatives.fromStr(r, "jvm"));
        HANDLERS.put("__native_jvm_from_bool",
            (r, c) -> PolyglotNatives.fromBool(r, "jvm"));
        HANDLERS.put("__native_jvm_make_list",
            (r, c) -> PolyglotNatives.makeList(r, "jvm"));
        HANDLERS.put("__native_jvm_make_dict",
            (r, c) -> PolyglotNatives.makeDict(r, "jvm"));
        HANDLERS.put("__native_jvm_null_val",
            (r, c) -> PolyglotNatives.nullVal(r, "jvm"));

        // Per-language value constructors used by each provider's `to_py {}` /
        // `to_js {}` codec (`mezze_python::codec` etc.). The provider .mz emits a
        // language-namespaced `__native_<lang>_from_int` so the resulting Foreign
        // is tagged with the guest language; they all delegate to the generic
        // PolyglotNatives constructor. Registered in-tree (not left to the
        // provider plugin, which only overrides eval/import) so a codec `.to_py {}`
        // works without a no-handler crash.
        for (String lang : new String[] { "python", "js", "wasm" }) {
            final String l = lang;
            HANDLERS.put("__native_" + l + "_from_int",   (r, c) -> PolyglotNatives.fromInt(r, l));
            HANDLERS.put("__native_" + l + "_from_float", (r, c) -> PolyglotNatives.fromFloat(r, l));
            HANDLERS.put("__native_" + l + "_from_str",   (r, c) -> PolyglotNatives.fromStr(r, l));
            HANDLERS.put("__native_" + l + "_from_bool",  (r, c) -> PolyglotNatives.fromBool(r, l));
            HANDLERS.put("__native_" + l + "_null_val",   (r, c) -> PolyglotNatives.nullVal(r, l));
            // BUG 2: the container constructors a provider's `encode_list` /
            // `encode_map` needs, tagged with the guest language like the
            // scalar constructors above.
            HANDLERS.put("__native_" + l + "_make_list",  (r, c) -> PolyglotNatives.makeList(r, l));
            HANDLERS.put("__native_" + l + "_make_dict",  (r, c) -> PolyglotNatives.makeDict(r, l));
        }

        // Python / JavaScript / WebAssembly and any other non-JVM guest
        // language arrive via user-installed polyglot provider packages
        // (`mezze-python`, `mezze-js`, `mezze-wasm`, …). Each package
        // ships a `polyglot-<lang>-natives.jar` on the wire child's
        // classpath and a `META-INF/services/com.mezze.interpreter.spi
        // .NativePlugin` entry pointing at its `Polyglot<Lang>Plugin`.
        // Plugin discovery happens once at class-load time of this file
        // (see the ServiceLoader hookup near the top of registerAll),
        // and each plugin calls `overrideHandler` to install its own
        // `__native_<lang>_*` handlers on top of the language-agnostic
        // kernel above. Bare `mez` — with no provider jars on the
        // classpath — leaves those names unbound, and any Mezze source
        // that tries to call one gets a normal "unknown native" error.
    }

    @TruffleBoundary
    private static void debugMutationMarker() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // Find the first non-internal frame
        String location = "unknown";
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!className.startsWith("com.mezze.interpreter") &&
                !className.startsWith("java.") &&
                !className.startsWith("com.oracle.truffle")) {
                location = frame.toString();
                break;
            }
        }
        System.out.println("[DebugLocalMut] mutation at: " + location);
    }

    @TruffleBoundary
    private static void debugMutationLoop(int iteration) {
        System.out.println("[DebugLocalMut] loop iteration: " + iteration);
    }

    @TruffleBoundary
    private static void whileLoop(MezzeFunction cond, MezzeFunction body, IndirectCallNode callNode) {
        Object[] condClosureValues = cond.getClosureValues();
        int condClosureLen = condClosureValues.length;
        Object[] condCallArgs = new Object[condClosureLen + 1];
        System.arraycopy(condClosureValues, 0, condCallArgs, 0, condClosureLen);
        condCallArgs[condClosureLen] = MezzeRecord.empty();

        Object[] bodyClosureValues = body.getClosureValues();
        int bodyClosureLen = bodyClosureValues.length;
        Object[] bodyCallArgs = new Object[bodyClosureLen + 1];
        System.arraycopy(bodyClosureValues, 0, bodyCallArgs, 0, bodyClosureLen);
        bodyCallArgs[bodyClosureLen] = MezzeRecord.empty();

        // Detach from CURRENT so ops inside cond/body route through the
        // per-thread handler chain instead of suspending whichever fiber
        // happens to be CURRENT (an outer perform's, under nested
        // performs — strands the rest of the body). See #255 and
        // HandlerChainRegistry.runDetached.
        com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.runDetached(() -> {
            while (true) {
                Object condResult = callNode.call(cond.getCallTarget(), condCallArgs);
                if (!(condResult instanceof Boolean b) || !b) {
                    break;
                }
                callNode.call(body.getCallTarget(), bodyCallArgs);
            }
            return null;
        });
    }

    @TruffleBoundary
    private static void debugWhileLoop(MezzeFunction cond, MezzeFunction body, IndirectCallNode callNode) {
        Object[] condClosureValues = cond.getClosureValues();
        int condClosureLen = condClosureValues.length;
        Object[] condCallArgs = new Object[condClosureLen + 1];
        System.arraycopy(condClosureValues, 0, condCallArgs, 0, condClosureLen);
        condCallArgs[condClosureLen] = MezzeRecord.empty();

        Object[] bodyClosureValues = body.getClosureValues();
        int bodyClosureLen = bodyClosureValues.length;
        Object[] bodyCallArgs = new Object[bodyClosureLen + 1];
        System.arraycopy(bodyClosureValues, 0, bodyCallArgs, 0, bodyClosureLen);
        bodyCallArgs[bodyClosureLen] = MezzeRecord.empty();

        // Same detach reason as whileLoop — see #255.
        com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.runDetached(() -> {
            int iteration = 0;
            while (true) {
                debugMutationLoop(iteration);
                Object condResult = callNode.call(cond.getCallTarget(), condCallArgs);
                if (!(condResult instanceof Boolean b) || !b) {
                    break;
                }
                callNode.call(body.getCallTarget(), bodyCallArgs);
                iteration++;
            }
            return null;
        });
    }


    // ========================================================================
    // Codec native implementations
    // ========================================================================

    /**
     * Native record decode: extract fields from a Json JObject variant and build a Mezze record.
     * Args: { json: Json, field_names: [Str], field_types: [Str] }
     * Returns: Result record DecodeError
     */
    @CompilerDirectives.TruffleBoundary
    private static Object nativeCodecDecodeRecord(MezzeRecord args) {
        Object jsonObj = args.getField("json");
        Object fieldNamesObj = args.getField("field_names");

        if (!(jsonObj instanceof MezzeVariant jv)) {
            return makeDecodeError("Expected Json value, got " + (jsonObj == null ? "null" : jsonObj.getClass().getSimpleName()));
        }

        if (!"JObject".equals(jv.getConstructorName())) {
            return makeDecodeError("Expected JObject, got " + jv.getConstructorName());
        }

        // Extract fields sequence from JObject payload
        Object payload = jv.getPayloadValue();
        if (!(payload instanceof MezzeRecord payloadRec)) {
            return makeDecodeError("JObject payload is not a record");
        }
        Object fieldsSeqObj = payloadRec.getField("fields");
        if (!(fieldsSeqObj instanceof com.mezze.datastructures.seq.MezzeSeq<?> fieldsSeq)) {
            return makeDecodeError("JObject.fields is not a sequence");
        }

        // Get expected field names
        if (!(fieldNamesObj instanceof com.mezze.datastructures.seq.MezzeSeq<?> namesSeq)) {
            return makeDecodeError("field_names is not a sequence");
        }

        int numFields = namesSeq.size();
        String[] resultFieldNames = new String[numFields];
        Object[] resultValues = new Object[numFields];

        for (int i = 0; i < numFields; i++) {
            String fieldName = (String) namesSeq.get(i);
            resultFieldNames[i] = fieldName;

            // Find the field in the JObject's fields sequence
            Object fieldValue = null;
            for (int j = 0; j < fieldsSeq.size(); j++) {
                Object entry = fieldsSeq.get(j);
                if (entry instanceof MezzeRecord entryRec) {
                    Object key = entryRec.getField("key");
                    if (fieldName.equals(key)) {
                        fieldValue = entryRec.getField("value");
                        break;
                    }
                }
            }

            if (fieldValue == null) {
                return makeDecodeError("Missing field: " + fieldName);
            }

            // Decode the Json value to a Mezze primitive
            Object decoded = decodeJsonFieldValue(fieldValue);
            if (decoded instanceof MezzeVariant errV && "Err".equals(errV.getConstructorName())) {
                return decoded; // Propagate error
            }
            resultValues[i] = decoded;
        }

        MezzeRecord result = MezzeRecord.fromFields(resultFieldNames, resultValues);
        return MezzeVariant.ok(result);
    }

    /**
     * Decode a single Json variant value to a Mezze primitive.
     */
    @CompilerDirectives.TruffleBoundary
    private static Object decodeJsonFieldValue(Object json) {
        if (!(json instanceof MezzeVariant jv)) {
            return makeDecodeError("Expected Json variant");
        }
        String tag = jv.getConstructorName();
        return switch (tag) {
            case "JString", "JInt", "JFloat", "JBool" -> jv.getPayloadFieldByName("val");
            case "JNull" -> {
                int noneTag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("None");
                yield MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), noneTag);
            }
            default -> makeDecodeError("Unsupported Json type: " + tag);
        };
    }

    /**
     * Create a DecodeError wrapped in Err variant.
     */
    private static MezzeVariant makeDecodeError(String message) {
        var registry = MezzeVariant.TagRegistry.getGlobal();
        int noneTag = registry.getOrCreateTag("None");
        MezzeVariant none = MezzeVariant.noPayload(registry, noneTag);
        MezzeRecord errorRecord = MezzeRecord.fromFields(
            new String[]{"expected", "found", "message", "path"},
            new Object[]{"", none, message, com.mezze.datastructures.seq.MezzeSeq.empty()}
        );
        return MezzeVariant.err(errorRecord);
    }


}
