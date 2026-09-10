package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.builtin.dsl.NativeHandler;
import com.mezze.interpreter.nodes.builtin.dsl.NativeHandlers;
import com.mezze.interpreter.nodes.builtin.dsl.PositionalArgs;
import com.mezze.interpreter.nodes.builtin.dsl.TypedNativeHandlers;
import com.mezze.interpreter.nodes.function.TailCallException;
import com.mezze.language.typedast.Hash;
import com.mezze.interpreter.nodes.ReadGlobalByHashNode;
import com.mezze.interpreter.nodes.ReadGlobalFnByHashNode;
import com.mezze.interpreter.nodes.inline.DirectAddNode;
import com.mezze.interpreter.nodes.inline.DirectAndNode;
import com.mezze.interpreter.nodes.inline.DirectDivideNode;
import com.mezze.interpreter.nodes.inline.DirectEqualNode;
import com.mezze.interpreter.nodes.inline.DirectGreaterThanNode;
import com.mezze.interpreter.nodes.inline.DirectGreaterThanOrEqualNode;
import com.mezze.interpreter.nodes.inline.DirectLessThanNode;
import com.mezze.interpreter.nodes.inline.DirectLessThanOrEqualNode;
import com.mezze.interpreter.nodes.inline.DirectModuloNode;
import com.mezze.interpreter.nodes.inline.DirectMultiplyNode;
import com.mezze.interpreter.nodes.inline.DirectNegateNode;
import com.mezze.interpreter.nodes.inline.DirectNotEqualNode;
import com.mezze.interpreter.nodes.inline.DirectNotNode;
import com.mezze.interpreter.nodes.inline.DirectOrNode;
import com.mezze.interpreter.nodes.inline.DirectSubtractNode;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.mezze.interpreter.values.ShapeCache;
import com.mezze.interpreter.values.MezzeVariant;
import com.mezze.interpreter.values.MezzeVariant.TagRegistry;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.InlinedLoopConditionProfile;

/**
 * Bytecode DSL root node for Mezze — Phase 1 walking skeleton.
 *
 * <p>This is the FIRST commit of the Bytecode DSL migration
 * (task #277, plan in docs/perf/bytecode-dsl-migration-2026-07.md).
 * Deliberately minimal: only three operations, enough to prove the
 * annotation processor generates code and the skeleton links.
 *
 * <p>Operations shipped here:
 * <ul>
 *   <li>{@code LoadLongConstant} — push a compile-time long onto the stack</li>
 *   <li>{@code AddLong} — pop two longs, push their overflow-checked sum</li>
 *   <li>The implicit {@code Return} op that BDSL provides for free</li>
 * </ul>
 *
 * <p>Everything else — records, effects, calls, control flow — comes
 * in later phases. Programs that need those still route through the
 * AST interpreter (see BytecodeCompiler.canLower).
 *
 * <p>Generated class: {@code MezzeBytecodeRootNodeGen}. To construct
 * an executable root node, call
 * {@code MezzeBytecodeRootNodeGen.create(...)} with a builder
 * callback that emits the operation sequence.
 */
/**
 * @GenerateBytecode flag settings — chosen after Phase 0 reading of the
 * Truffle 25 BDSL user guide + Optimization guide + SimpleLanguage's
 * production BDSL root. Rationale for each:
 *
 * <ul>
 *   <li>{@code boxingEliminationTypes = long/double/boolean}: THE
 *     setting that makes BDSL beat AST perf on numeric loops. Without
 *     it primitive→object→primitive shuffles cost both the interpreter
 *     and the JIT. Same three types our existing typed native bridges
 *     already handle.
 *   <li>{@code enableUncachedInterpreter = true}: docs "strongly
 *     recommended". Executes without profiling overhead until
 *     defaultUncachedThreshold, then transitions to cached. Great for
 *     cold `mez exec` runs and tests.
 *   <li>{@code enableQuickening = true}: underlying mechanism for
 *     boxing elimination + typed hot paths. Requires per-op
 *     {@code @ForceQuickening} to opt into type-specialized rewrites
 *     (see AddLong below).
 *   <li>{@code enableYield = true}: emits ContinuationResult/
 *     ContinuationRootNode primitives that Phase 5 will build
 *     algebraic-effect handlers on top of. Cheap to turn on now.
 *   <li>{@code enableBlockScoping = true} (default): frame slots
 *     inside blocks auto-recycle on block exit. Reduces frame size
 *     for functions with branches — free win.
 *   <li>{@code enableSerialization = true}: our stdlib snapshot may
 *     want to serialize precompiled BDSL later.
 *   <li>{@code enableTagInstrumentation = true}: LSP / debugger
 *     tooling (chromeinspector, etc.) works out of the box.
 * </ul>
 */
@GenerateBytecode(
    languageClass = MezzeLanguage.class,
    // Boxing elimination DISABLED — BDSL quickenings (LOAD_ARGUMENT$LONG,
    // POP$LONG, TAG_LEAVE$LONG) mutate the bytecode array in-flight,
    // invalidating compilation assumptions. With recursive call patterns
    // (fib), this causes an infinite compile→deopt storm where compiled
    // code address stays 0x0 and is never installed, regardless of
    // compilation threshold. The Truffle compiler and quickening system
    // race on different threads — even with thresholds high enough for
    // quickenings to stabilize, the invalidation cycle persists.
    // Requires upstream Truffle fix (quickening-aware compilation
    // assumption) or BDSL-level manual typed locals.
    boxingEliminationTypes = {},
    enableUncachedInterpreter = true,
    enableYield = true,
    enableSerialization = true,
    enableTagInstrumentation = true,
    // #359 — store bci in frame so debug-mode inline-eval executable
    // nodes (FrameEvalNode) can call bytecodeNode.getBytecodeIndex(frame)
    // to reach the enclosing bytecode's local table. Runtime cost is
    // one extra frame slot per fn invocation (JIT specializes the write
    // away in hot loops).
    storeBytecodeIndexInFrame = true
)
/**
 * PROXY PROBE (Phase 0 finding follow-up): {@code DirectAddNode} is
 * our existing AST @Specialization-based add. Proxying it here
 * generates a BDSL operation named "DirectAdd" that reuses its
 * specialization logic (long+long, long+long overflow → double,
 * double+double, string+string, fraction+fraction, suspended
 * propagation, type-error fallback). If this works, most of Phase
 * 2/3 becomes @OperationProxy annotations rather than hand-written
 * ops — potentially weeks of work saved.
 */
@OperationProxy(value = DirectAddNode.class, name = "DirectAdd")
@OperationProxy(value = DirectSubtractNode.class, name = "DirectSubtract")
@OperationProxy(value = DirectMultiplyNode.class, name = "DirectMultiply")
@OperationProxy(value = DirectDivideNode.class, name = "DirectDivide")
@OperationProxy(value = DirectModuloNode.class, name = "DirectModulo")
@OperationProxy(value = DirectEqualNode.class, name = "DirectEqual")
@OperationProxy(value = DirectNotEqualNode.class, name = "DirectNotEqual")
@OperationProxy(value = DirectLessThanNode.class, name = "DirectLessThan")
@OperationProxy(value = DirectLessThanOrEqualNode.class, name = "DirectLessThanOrEqual")
@OperationProxy(value = DirectGreaterThanNode.class, name = "DirectGreaterThan")
@OperationProxy(value = DirectGreaterThanOrEqualNode.class, name = "DirectGreaterThanOrEqual")
@OperationProxy(value = DirectAndNode.class, name = "DirectAnd")
@OperationProxy(value = DirectOrNode.class, name = "DirectOr")
@OperationProxy(value = DirectNegateNode.class, name = "DirectNegate")
@OperationProxy(value = DirectNotNode.class, name = "DirectNot")
@OperationProxy(value = com.mezze.interpreter.nodes.seq.SeqAccessNode.class, name = "SeqGet")
@OperationProxy(value = com.mezze.datastructures.nodes.SeqSumLongScalarNode.class, name = "BuiltinSumLongSeq")
@OperationProxy(value = com.mezze.datastructures.nodes.SumRangeLongNode.class, name = "BuiltinSumRangeLong")
@ReportPolymorphism
public abstract class MezzeBytecodeRootNode extends RootNode implements BytecodeRootNode {

    protected MezzeBytecodeRootNode(MezzeLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /** Check if a trampoline has any hidden dict params ({@code __dict_*}). */
    @CompilerDirectives.TruffleBoundary
    static boolean hasDictParams(TrampolineRootNode tramp) {
        String[] names = tramp.getParameterNames();
        if (names == null) return false;
        for (String name : names) {
            if (com.mezze.language.ast.MagicParams.INSTANCE.isDict(name)) return true;
        }
        return false;
    }

    /**
     * Push a compile-time long constant onto the operand stack.
     *
     * <p>Used to lower {@code LongLitIR}. The value is baked into the
     * bytecode's constant pool at build time via {@link ConstantOperand} —
     * the builder emits it as {@code b.emitLoadLongConstant(42L)}, not
     * as a child operation.
     */
    @Operation
    @ConstantOperand(type = long.class)
    public static final class LoadLongConstant {
        @Specialization
        public static long doLong(long value) {
            return value;
        }
    }

    /**
     * Push a compile-time double constant. Mirror of
     * {@link LoadLongConstant} for {@link com.mezze.language.truffleir.DoubleLitIR}.
     */
    @Operation
    @ConstantOperand(type = double.class)
    public static final class LoadDoubleConstant {
        @Specialization
        public static double doDouble(double value) {
            return value;
        }
    }

    /**
     * Push a compile-time boolean constant. Mirror for
     * {@link com.mezze.language.truffleir.BoolLitIR}.
     */
    @Operation
    @ConstantOperand(type = boolean.class)
    public static final class LoadBooleanConstant {
        @Specialization
        public static boolean doBool(boolean value) {
            return value;
        }
    }

    /**
     * Push {@code null} onto the operand stack. Truffle DSL forbids null
     * in {@code @ConstantOperand}, so this exists as a no-operand op
     * dedicated to producing null (for zero-init match-binding slots so
     * a LoadLocal after a non-matching arm reads null instead of
     * throwing FrameSlotTypeException).
     */
    @Operation
    public static final class LoadNullConstant {
        @Specialization
        public static Object doLoad() {
            return null;
        }
    }

    /**
     * Push a compile-time Object constant. Used for strings, chars, and
     * any other reference-typed literals — anything that isn't captured
     * by the boxing-elimination fast paths for long/double/boolean.
     */
    @Operation
    @ConstantOperand(type = Object.class)
    public static final class LoadObjectConstant {
        @Specialization
        public static Object doObject(Object value) {
            return value;
        }
    }

    /**
     * Read a local slot as Object, returning null if the slot is
     * uninitialized (FrameSlotKind.Illegal). Mirrors AST's
     * {@code frame.getValue(slot)} semantics: catches the
     * FrameSlotTypeException that plain {@code LoadLocal} throws when
     * the slot has not yet been written on this control-flow path.
     *
     * <p>Used by {@code MakeLambdaIR} emit for local-slot captures
     * (Phase 5): a recursive-let (`let f = {n} -> ... f {n=n-1}`)
     * captures its own slot on the RHS, but slot(f) isn't written
     * until AFTER the MakeLambda executes. The typechecker replaces
     * body-side references to `f` with fresh MakeLambdas
     * (recursiveLetLambdas in TypedASTCompiler.compileVar), so the
     * captured null is a placeholder that's never read.
     *
     * <p>Constant operand: the LocalAccessor to read.
     */
    @Operation
    @ConstantOperand(type = com.oracle.truffle.api.bytecode.LocalAccessor.class)
    public static final class LoadLocalOrNull {
        @Specialization
        public static Object doLoad(VirtualFrame frame,
                                    com.oracle.truffle.api.bytecode.LocalAccessor local,
                                    @Bind com.oracle.truffle.api.bytecode.BytecodeNode bytecodeNode) {
            try {
                return local.getObject(bytecodeNode, frame);
            } catch (com.oracle.truffle.api.frame.FrameSlotTypeException e) {
                return null;
            }
        }
    }

    /**
     * #346 Group B — inline auto-derived Inspect impl for a receiver.
     * Rewrites the desugared shape
     * {@code CallIndirect(ReadField(x, "to_dbg"|"to_str"),
     * MakeRecord{it=x})} into a single stringify op. BDSL emits this
     * via the peephole in {@code BytecodeCompiler.tryFoldInspectMethod};
     * the operand is the receiver, the boolean constant is dbgStyle
     * ("to_dbg" quotes strings + wraps chars; "to_str" is raw).
     *
     * Matches DirectInspectNode's shape so behaviour is identical to
     * the AST auto-derive.
     */
    @Operation
    @ConstantOperand(type = boolean.class)
    public static final class InlineScalarInspect {
        /**
         * `@TruffleBoundary` on `doLong` and `doDouble`: {@link Long#toString(long)}
         * and {@link Double#toString(double)} reach {@link String#String(char[], int, int)}
         * → {@code Preconditions.checkFromIndexSize} → {@code String.format} →
         * blocklisted {@link java.util.AbstractCollection#toString} chain. Under
         * native-image runtime compilation this reachability trips
         * SVM TruffleFeature's blocklist. Boundaries keep PE from following into
         * the JDK number-formatting closure. See #402.
         */
        @Specialization
        @TruffleBoundary
        public static String doLong(boolean dbgStyle, long v) { return Long.toString(v); }
        @Specialization
        @TruffleBoundary
        public static String doDouble(boolean dbgStyle, double v) { return Double.toString(v); }
        @Specialization
        public static String doBool(boolean dbgStyle, boolean v) { return v ? "True" : "False"; }
        /**
         * `@TruffleBoundary` on doChar/doString: string concat via `+` lowers
         * to StringConcatHelper.stringCoder, blocklisted under SVM runtime
         * compilation. `to_dbg` on scalars is cold — boundary is free. #402.
         */
        @Specialization(guards = "isChar(v)")
        @TruffleBoundary
        public static String doChar(boolean dbgStyle, Object v) {
            char c = (Character) v;
            return dbgStyle ? "'" + c + "'" : String.valueOf(c);
        }
        @Specialization(guards = "isString(v)")
        @TruffleBoundary
        public static String doString(boolean dbgStyle, Object v) {
            String s = (String) v;
            return dbgStyle ? "\"" + s + "\"" : s;
        }
        @Fallback
        @TruffleBoundary
        @InliningCutoff
        public static String doOther(boolean dbgStyle, Object v) {
            // Compound values (records, variants, lists, maps, ...) must render
            // in Mezze surface syntax — `Err { error = "x" }`, `Some { val = 42 }`,
            // `{ a = 1, b = "hi" }` — quoting strings and spacing braces. A raw
            // `v.toString()` produced Java form (`Err({error = x})`, unquoted
            // strings), which is the wrong `to_dbg` output. Route through the
            // shared inspect formatter used by the AST path.
            return com.mezze.interpreter.nodes.builtin.effects.InspectNatives.valueToString(v);
        }

        static boolean isChar(Object o) { return o instanceof Character; }
        static boolean isString(Object o) { return o instanceof String; }
    }

    /**
     * Inline string predicate: CONTAINS(0), STARTS_WITH(1), ENDS_WITH(2).
     * Bypasses MakeRecord + NativeDispatch for str::contains / str::starts_with /
     * str::ends_with.  `@TruffleBoundary` because the JDK implementations can
     * reach blocklisted internal String helpers under SVM runtime compilation (#402).
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class InlineStringPredicate {
        /** CONTAINS = 0 */  public static final int CONTAINS    = 0;
        /** STARTS_WITH = 1 */ public static final int STARTS_WITH = 1;
        /** ENDS_WITH = 2 */  public static final int ENDS_WITH   = 2;

        @Specialization
        @TruffleBoundary
        public static boolean doStrStr(int op, String haystack, String needle) {
            return switch (op) {
                case CONTAINS    -> haystack.contains(needle);
                case STARTS_WITH -> haystack.startsWith(needle);
                case ENDS_WITH   -> haystack.endsWith(needle);
                default -> throw com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere(
                        "InlineStringPredicate: unknown op " + op);
            };
        }
    }

    /**
     * Inline string size: bypasses MakeRecord + NativeDispatch for
     * str::size { s }.  No {@code @TruffleBoundary} — {@code String.length()}
     * is a trivial field access, fully PE-transparent.
     */
    @Operation
    public static final class InlineStringSize {
        @Specialization
        public static long doSize(String s) {
            return (long) s.length();
        }
    }

    /**
     * Inline unary string operations: TRIM(0), TO_UPPER(1), TO_LOWER(2),
     * REVERSE(3), TRIM_START(4), TRIM_END(5).
     * Bypasses MakeRecord + NativeDispatch for single-arg string natives.
     * {@code @TruffleBoundary} because JDK string transformations can reach
     * blocklisted internals under SVM runtime compilation (#402).
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class InlineStringUnary {
        /** TRIM = 0 */       public static final int TRIM       = 0;
        /** TO_UPPER = 1 */   public static final int TO_UPPER   = 1;
        /** TO_LOWER = 2 */   public static final int TO_LOWER   = 2;
        /** REVERSE = 3 */    public static final int REVERSE    = 3;
        /** TRIM_START = 4 */ public static final int TRIM_START = 4;
        /** TRIM_END = 5 */   public static final int TRIM_END   = 5;

        @Specialization
        @TruffleBoundary
        public static String doOp(int op, String s) {
            return switch (op) {
                case TRIM       -> s.trim();
                case TO_UPPER   -> s.toUpperCase();
                case TO_LOWER   -> s.toLowerCase();
                case REVERSE    -> new StringBuilder(s).reverse().toString();
                case TRIM_START -> s.stripLeading();
                case TRIM_END   -> s.stripTrailing();
                default -> throw CompilerDirectives.shouldNotReachHere(
                        "InlineStringUnary: unknown op " + op);
            };
        }
    }

    /**
     * #394 — type-driven Inspect. Emitted for {@code x.to_dbg {}} /
     * {@code x.to_str {}} from {@link com.mezze.language.truffleir.InspectIR},
     * carrying the receiver's static shape as a constant {@code PrintType}. The
     * printer walks (value, printType) in lockstep, which is the only way to
     * render Nat vs Int, Frac, Newtype (erased), and single-char Str-as-Char —
     * shapes indistinguishable from the runtime value alone.
     *
     * Constant operands: [printType, dbgStyle]. Cold path (a `to_dbg` call is
     * never hot), so a single @TruffleBoundary through the shared renderer is
     * free and keeps PE off the JDK string-formatting blocklist chain (#402).
     */
    @Operation
    @ConstantOperand(type = Object.class)
    @ConstantOperand(type = boolean.class)
    public static final class TypedInspect {
        @Specialization
        @TruffleBoundary
        public static String doInspect(Object printType, boolean dbgStyle, Object v) {
            return com.mezze.interpreter.nodes.builtin.effects.InspectNatives.render(
                v, (com.mezze.language.truffleir.PrintType) printType, dbgStyle);
        }
    }

    /**
     * Read a global definition by content hash from the current
     * {@code MezzeContext}. Auto-evaluates zero-param zero-capture
     * "value thunks" (constants like {@code math::pi}, top-level
     * {@code let x = ...}). Mirrors the AST's
     * {@link com.mezze.interpreter.nodes.ReadGlobalByHashNode}.
     *
     * <p>Used for {@link com.mezze.language.truffleir.ReadGlobalByHashIR}.
     * The hash is baked at emit time as a ConstantOperand; the receive
     * side delegates to a cached ReadGlobalByHashNode child so any
     * IndirectCallNode / suspended-force logic lives in exactly one
     * place.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class ReadGlobalByHash {
        @Specialization
        public static Object doRead(
                Hash hash,
                @Cached(value = "createReader(hash)", allowUncached = true) ReadGlobalByHashNode reader) {
            // The AST node's execute(frame) is frame-agnostic — it looks
            // up its parent Node context, not the frame. Passing null is
            // safe.
            return reader.execute(null);
        }

        /** `@TruffleBoundary` — Node.<init> is neverPartOfCompilation. #402. */
        @TruffleBoundary
        static ReadGlobalByHashNode createReader(Hash hash) {
            return new ReadGlobalByHashNode(hash);
        }
    }

    /**
     * Read a function from the global registry WITHOUT auto-evaluating
     * thunks. Mirrors {@link com.mezze.interpreter.nodes.ReadGlobalFnByHashNode};
     * used for dictionary-passing paths (ability op function injection)
     * where the raw MezzeFunction is required.
     *
     * <p>Used for {@link com.mezze.language.truffleir.ReadGlobalFnByHashIR}.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class ReadGlobalFnByHash {
        @Specialization
        public static Object doRead(
                Hash hash,
                @Cached(value = "createReader(hash)", allowUncached = true) ReadGlobalFnByHashNode reader) {
            return reader.execute(null);
        }

        /** `@TruffleBoundary` — Node.<init> is neverPartOfCompilation. #402. */
        @TruffleBoundary
        static ReadGlobalFnByHashNode createReader(Hash hash) {
            return new ReadGlobalFnByHashNode(hash);
        }
    }

    /**
     * Call a target by content hash with a single record arg, handling
     * partial application. Mirrors the AST's
     * {@link com.mezze.interpreter.nodes.function.MonomorphicCallNode}:
     * when the argument record lacks parameters, returns a partially-
     * applied {@link com.mezze.interpreter.values.MezzeFunction};
     * otherwise completes any prior partial args and calls the target.
     *
     * <p>Used for {@link com.mezze.language.truffleir.PartialApplyDirectIR}.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class MonomorphicCall {
        @Specialization
        public static Object doCall(
                Hash hash,
                Object arg,
                @Cached(value = "createCall(hash)", allowUncached = true)
                    com.mezze.interpreter.nodes.function.MonomorphicCallNode callNode) {
            return callNode.executeWith(arg);
        }

        /** `@TruffleBoundary` — Node.<init> is neverPartOfCompilation-tagged;
         *  Uncached BDSL path must not reach it from a PE root. #402. */
        @TruffleBoundary
        static com.mezze.interpreter.nodes.function.MonomorphicCallNode createCall(Hash hash) {
            return com.mezze.interpreter.nodes.function.MonomorphicCallNode.create(
                    hash, hash.getHex(),
                    new com.mezze.interpreter.nodes.literal.UnitLiteralNode());
        }
    }

    /**
     * Register a value in the current {@link com.mezze.interpreter.runtime.MezzeContext}
     * globals (by content hash) AND in the test-inspection map (by name),
     * then return the value unchanged so the surrounding StoreLocal can
     * still fill the frame slot.
     *
     * <p>Emitted by {@link BytecodeCompiler#compileEntryFn} as a wrapper
     * around the value of each top-level {@code StmtIR.Let} — mirrors what
     * the AST {@code WriteLocalWithContextNode} did. Only used for the
     * immediate top-level {@code BlockIR} of {@code __entry__}; nested
     * blocks inside handlers, lambdas, etc. still write frame slots only.
     *
     * <p>Note: the surrounding StoreLocal consumes the value we return —
     * this op is transparent from the stack's perspective (1 in, 1 out).
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    @ConstantOperand(type = String.class)
    public static final class RegisterGlobal {
        @TruffleBoundary
        @Specialization
        public static Object doRegister(Hash contentHash, String name, Object value, @Bind Node node) {
            // Match WriteLocalWithContextNode.registerGlobal semantics: null
            // values legitimately arrive from lazy-effect edge cases
            // (suspended value resolving to no result). Skip both maps then.
            if (value == null) return null;
            com.mezze.interpreter.runtime.MezzeContext ctx =
                com.mezze.interpreter.runtime.MezzeContext.get(node);
            if (contentHash != null) {
                ctx.defineGlobal(contentHash, value);
            }
            ctx.recordTestInspectionVar(name, value);
            return value;
        }
    }

    /**
     * Pop two longs, push their overflow-checked sum.
     *
     * <p>Used to lower {@code InlineBinaryOpIR} with op = ADD when
     * both operands are typed as {@code Int}. Overflow throws
     * {@code ArithmeticException} — matching the AST
     * {@code DirectAddNode.mulLongs} specialization.
     */
    @Operation
    public static final class AddLong {
        /**
         * @ForceQuickening: opts this specialization into BDSL's
         * quickening mechanism — the interpreter speculatively rewrites
         * to a long-only bytecode after warmup, avoiding boxing +
         * type-check overhead. Combined with
         * {@code boxingEliminationTypes = long.class}, primitive-long
         * arith chains stay unboxed end-to-end.
         */
        @Specialization
        @ForceQuickening
        public static long doLong(long left, long right) {
            return Math.addExact(left, right);
        }
    }

    /**
     * Direct-call a fixed CallTarget with any number of positional
     * arguments, push the result on the operand stack.
     *
     * <p>Used to lower {@code CallDirectIR} for the general case: any
     * arity, scalar-ABI, non-tail-call. Callee's {@link CallTarget} is
     * baked as a constant operand; the args come from the stack via
     * {@link Variadic}, which BDSL packs into an {@code Object[]} that
     * we forward straight to {@link DirectCallNode#call(Object...)}.
     *
     * <p>Phase 2 opened this up from the previous 2-arg hardcode. The
     * cached path still gets Truffle inlining/splitting once warm.
     */
    @Operation
    @ConstantOperand(type = CallTarget.class)
    public static final class CallDirectN {
        /**
         * Cached path (steady-state): a {@link DirectCallNode} tied to
         * the compile-time CallTarget. Enables Truffle inlining +
         * splitting once the interpreter warms up. forceInlining lets PE
         * see through the call to the callee's bytecodes, enabling
         * boxing elimination via escape analysis.
         */
        @Specialization(guards = "callNode.getCallTarget() == target")
        public static Object doCached(
                CallTarget target,
                @Variadic Object[] args,
                @Cached("createForceInlined(target)") DirectCallNode callNode) {
            return callNode.call(args);
        }

        /**
         * Uncached path — the {@code enableUncachedInterpreter=true}
         * flag requires every specialization to have an uncached
         * alternative. Falls through to a shared IndirectCallNode.
         */
        @Specialization(replaces = "doCached")
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                CallTarget target,
                @Variadic Object[] args,
                @Cached IndirectCallNode indirectCallNode) {
            return indirectCallNode.call(target, args);
        }

        static DirectCallNode createForceInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }
    }

    /**
     * Resolve a CallTarget through a trampoline wrapper when safe.
     *
     * <p>When the target wraps a {@link TrampolineRootNode} whose inner
     * body has NO tail-calls, the trampoline is a pure ABI shim — its
     * {@code execute()} just runs normalizeArgs (a no-op for scalar ABI)
     * and then calls the inner via directInnerCall. Scalar-ABI callers
     * (CallDirect1/2/3) pass flat args that skip normalizeArgs, so the
     * trampoline hop costs one extra frame allocation + DirectCallNode
     * dispatch for nothing.
     *
     * <p>This helper unwraps the trampoline at {@code @Cached}
     * specialization time (first execution), when the inner target is
     * guaranteed to be wired. This fixes the self-recursive case where
     * the emit-time bypass in BytecodeCompiler fails because
     * {@code tramp.getInnerTarget()} is null during BDSL construction
     * (two-phase construction for self-referencing functions).
     *
     * <p>NOT safe for tail-call functions (the trampoline's LoopNode
     * must catch TailCallException) or when the inner isn't wired yet
     * (shouldn't happen at execution time, but guarded defensively).
     */
    /**
     * Resolve a CallTarget through a trampoline wrapper when safe.
     *
     * <p>When the target wraps a {@link TrampolineRootNode} whose inner
     * body has NO tail-calls, the trampoline is a pure ABI shim — its
     * {@code execute()} just runs normalizeArgs (a no-op for scalar ABI)
     * and then calls the inner via directInnerCall. Scalar-ABI callers
     * (CallDirect1/2/3 with {@code canBypass=true}) pass flat args that
     * skip normalizeArgs, so the trampoline hop costs one extra frame
     * allocation + DirectCallNode dispatch for nothing.
     *
     * <p>This helper unwraps the trampoline at {@code @Cached}
     * specialization time (first execution), when the inner target is
     * guaranteed to be wired. This fixes the self-recursive case where
     * the emit-time bypass in BytecodeCompiler fails because
     * {@code tramp.getInnerTarget()} is null during BDSL construction
     * (two-phase construction for self-referencing functions).
     *
     * <p><b>MUST NOT</b> be called for record-ABI calls — the trampoline's
     * {@link TrampolineRootNode#normalizeArgs} unpacks the record into flat
     * positional slots; bypassing it sends the raw record to
     * LoadArgument(0), leaving other param slots uninitialized.
     *
     * @param canBypass compile-time flag from BytecodeCompiler; true only
     *                  when the call site is scalar-ABI and the callee
     *                  wraps a non-tail-call TrampolineRootNode
     */
    static CallTarget resolveScalarTarget(CallTarget target, boolean canBypass) {
        if (!canBypass) return target;
        if (target instanceof com.oracle.truffle.api.RootCallTarget rct
                && rct.getRootNode() instanceof TrampolineRootNode tramp
                && !tramp.hasTailCalls()
                && tramp.getInnerTarget() != null) {
            return tramp.getInnerTarget();
        }
        return target;
    }

    /**
     * Fixed-arity single-argument direct call. Avoids the {@code @Variadic}
     * overhead of {@link CallDirectN} — no intermediate {@code Object[]}
     * allocation by BDSL, just a single child operand on the stack.
     *
     * <p>Used for scalar-ABI calls with exactly one argument (the common
     * case: {@code fib {n = n - 1}} compiles to scalar ABI where the single
     * param is passed directly).
     *
     * <p>The {@code canBypass} constant operand is set by BytecodeCompiler
     * when the call site is scalar-ABI and the callee wraps a non-tail-call
     * TrampolineRootNode. When true, the {@code @Cached resolve} unwraps
     * the trampoline at specialization time — see
     * {@link #resolveScalarTarget}. For self-recursive functions like fib,
     * this eliminates the trampoline hop that the emit-time bypass can't
     * remove (inner target null during BDSL construction). Saves one frame
     * allocation + one DirectCallNode dispatch per non-inlined recursive
     * call.
     */
    @Operation
    @ConstantOperand(type = CallTarget.class)
    @ConstantOperand(type = boolean.class)
    public static final class CallDirect1 {
        @Specialization(guards = "callNode.getCallTarget() == resolved")
        public static Object doCached(
                CallTarget target,
                boolean canBypass,
                Object arg,
                @Cached("resolve(target, canBypass)") CallTarget resolved,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg);
        }

        @Specialization(replaces = "doCached")
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                CallTarget target,
                boolean canBypass,
                Object arg,
                @Cached IndirectCallNode indirectCallNode) {
            return indirectCallNode.call(resolve(target, canBypass), arg);
        }

        static CallTarget resolve(CallTarget t, boolean canBypass) {
            return resolveScalarTarget(t, canBypass);
        }

        static DirectCallNode createForceInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }
    }

    /**
     * Fixed-arity two-argument direct call. Same rationale as
     * {@link CallDirect1} but for two-param scalar-ABI functions.
     */
    @Operation
    @ConstantOperand(type = CallTarget.class)
    @ConstantOperand(type = boolean.class)
    public static final class CallDirect2 {
        @Specialization(guards = "callNode.getCallTarget() == resolved")
        public static Object doCached(
                CallTarget target,
                boolean canBypass,
                Object arg0,
                Object arg1,
                @Cached("resolve(target, canBypass)") CallTarget resolved,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg0, arg1);
        }

        @Specialization(replaces = "doCached")
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                CallTarget target,
                boolean canBypass,
                Object arg0,
                Object arg1,
                @Cached IndirectCallNode indirectCallNode) {
            return indirectCallNode.call(resolve(target, canBypass), arg0, arg1);
        }

        static CallTarget resolve(CallTarget t, boolean canBypass) {
            return resolveScalarTarget(t, canBypass);
        }

        static DirectCallNode createForceInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }
    }

    /**
     * Fixed-arity three-argument direct call. Covers closureCount=0 +
     * 3 scalar params, or closureCount=1 + 2 scalar params, etc.
     */
    @Operation
    @ConstantOperand(type = CallTarget.class)
    @ConstantOperand(type = boolean.class)
    public static final class CallDirect3 {
        @Specialization(guards = "callNode.getCallTarget() == resolved")
        public static Object doCached(
                CallTarget target,
                boolean canBypass,
                Object arg0,
                Object arg1,
                Object arg2,
                @Cached("resolve(target, canBypass)") CallTarget resolved,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg0, arg1, arg2);
        }

        @Specialization(replaces = "doCached")
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                CallTarget target,
                boolean canBypass,
                Object arg0,
                Object arg1,
                Object arg2,
                @Cached IndirectCallNode indirectCallNode) {
            return indirectCallNode.call(resolve(target, canBypass), arg0, arg1, arg2);
        }

        static CallTarget resolve(CallTarget t, boolean canBypass) {
            return resolveScalarTarget(t, canBypass);
        }

        static DirectCallNode createForceInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }
    }

    /**
     * Construct a record from a compile-time array of field names and a
     * runtime-variadic set of field values.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.MakeRecordIR}. The
     * field-name array is baked into the bytecode as a
     * {@link ConstantOperand}; the values are pushed via
     * {@link Variadic}, which BDSL packs into an {@code Object[]}
     * automatically.
     *
     * <p>Phase 2 keeps the AST-side "all-object storage" strategy —
     * matches {@code RecordLiteralNode.allObjects}. A later phase can
     * specialize on shape stability for unboxed long/double storage.
     */
    @Operation
    @ConstantOperand(type = String[].class)
    public static final class MakeRecord {
        /**
         * Cached path: resolve the Shape once at specialization time.
         * Since fieldNames is a constant operand (same array identity
         * every execution), the shape is looked up once and reused —
         * no ConcurrentHashMap hit on the hot path.
         */
        @Specialization
        public static MezzeRecord doAllObjects(String[] fieldNames, @Variadic Object[] values,
                @Cached(value = "resolveShape(fieldNames)", allowUncached = true)
                    com.mezze.interpreter.values.MezzeRecord.Shape cachedShape) {
            // The @Variadic array is a fresh per-execution allocation from
            // the BDSL framework — safe to hand off directly instead of
            // copying. Saves one Object[] allocation per record creation.
            return new MezzeRecord(cachedShape, EMPTY_LONGS, EMPTY_DOUBLES, values);
        }

        @TruffleBoundary
        static com.mezze.interpreter.values.MezzeRecord.Shape resolveShape(String[] fieldNames) {
            return com.mezze.interpreter.values.ShapeCache.getOrCreateAllObjects(fieldNames);
        }
    }

    private static final long[] EMPTY_LONGS = new long[0];
    private static final double[] EMPTY_DOUBLES = new double[0];

    /**
     * Specialized single-field record creation for the very common
     * pattern: {@code { val = i }}, {@code { x = v }}, etc.
     *
     * <p>Unlike the variadic {@link MakeRecord}, this takes exactly one
     * operand — no {@code @Variadic Object[]} framework allocation,
     * simpler JIT optimization path. The single value goes directly
     * into a freshly allocated {@code Object[1]}.
     */
    @Operation
    @ConstantOperand(type = String[].class)
    public static final class MakeSingleFieldRecord {
        @Specialization
        public static MezzeRecord doObject(String[] fieldNames, Object value,
                @Cached(value = "resolveShape(fieldNames)", allowUncached = true)
                    com.mezze.interpreter.values.MezzeRecord.Shape cachedShape) {
            return new MezzeRecord(cachedShape, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{value});
        }

        @TruffleBoundary
        static com.mezze.interpreter.values.MezzeRecord.Shape resolveShape(String[] fieldNames) {
            return com.mezze.interpreter.values.ShapeCache.getOrCreateAllObjects(fieldNames);
        }
    }

    /**
     * General record spread with interleaved sources and explicit
     * fields: {@code { ...r, x = 1, ...s, y = 2 }}.
     *
     * <p>Takes a compile-time {@code entryNames} array whose entries
     * are either a field name (regular field) or the literal string
     * {@code "__spread__"} (spread source). Runtime values are pushed
     * variadically in the same order. Boundary-fold merges each spread
     * source's fields into a LinkedHashMap in insertion order, with
     * regular fields overriding same-named entries, then hands off to
     * {@link #buildAllObjectRecord(String[], Object[])}.
     */
    @Operation
    @ConstantOperand(type = String[].class)
    public static final class SpreadRecord {
        @Specialization
        public static MezzeRecord doSpread(String[] entryNames, @Variadic Object[] values) {
            return buildSpreadRecord(entryNames, values);
        }
    }

    /**
     * Update-form record spread: {@code { ...r, x = 1, y = 2 }}. Takes
     * the source record from the stack and a compile-time array of
     * update field names + their runtime values (variadic).
     *
     * <p>Lowers {@link com.mezze.language.truffleir.RecordUpdateIR}.
     * Boundary-fold to a new {@link MezzeRecord}: copy every field of
     * the source's shape into the result, then overlay the updates.
     * All-object storage — matches {@code RecordSpreadNode.create}'s
     * simplest path without the shape-cache PIC (that's a later
     * optimization; correctness first).
     */
    @Operation
    @ConstantOperand(type = String[].class)
    public static final class UpdateRecord {
        @Specialization
        public static MezzeRecord doAllObjects(String[] updateNames, MezzeRecord source, @Variadic Object[] updateValues) {
            return buildUpdatedRecord(source, updateNames, updateValues);
        }
    }

    /**
     * Read a field by name from a {@link MezzeRecord}.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.ReadFieldIR}.
     * Analog of {@code CachedFieldAccessNode} but without the typed
     * primitive specializations — Phase 2 objective is functional
     * parity; typed unboxed access lands in a later phase once we
     * decide whether to proxy CachedFieldAccessNode (needs an
     * instance-field → operand refactor) or hand-write typed variants.
     *
     * <p>Cached path: monomorphic on record Shape. Guards the cached
     * shape and returns the pre-resolved field via {@link MezzeRecord#getObjectField}.
     * Megamorphic fallback goes through {@link MezzeRecord#getField(String)}.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class ReadFieldByName {
        // Records: shape-cached PIC (up to 6 shapes) with location index
        // pre-resolved. Common hot path — matches CachedFieldAccessNode.
        // Under JIT-warmed steady state, this specialization runs with all
        // three @Cached values constant-folded — zero string lookup, zero
        // hashmap access. The name is used only at PIC-miss time to seed
        // the cache. See #348.
        @Specialization(guards = "record.getShape() == cachedShape", limit = "6")
        public static Object doRecordCached(
                String fieldName,
                MezzeRecord record,
                @Cached("record.getShape()") Shape cachedShape,
                @Cached("cachedShape.findFieldIndex(fieldName)") int fieldIndex,
                @Cached("resolveLocationIndex(cachedShape, fieldIndex)") int locationIndex) {
            if (fieldIndex < 0) {
                return fallbackNotFound(record, fieldName);
            }
            return record.getObjectField(locationIndex);
        }

        // Megamorphic fallback (>6 distinct shapes at one site).
        // Uses getField(int) after a single findFieldIndex — one string
        // hash-lookup then array access. Documented as cold-path per #348.
        @Specialization(replaces = "doRecordCached")
        @ReportPolymorphism.Megamorphic
        public static Object doRecordGeneric(String fieldName, MezzeRecord record) {
            int fieldIndex = record.getShape().findFieldIndex(fieldName);
            if (fieldIndex < 0) {
                return fallbackNotFound(record, fieldName);
            }
            return record.getField(fieldIndex);
        }

        // Boolean.not/.and/.or — ability method shortcut. Matches
        // CachedFieldAccessNode.accessBooleanMethod so ability-op chains
        // like `bool.and {r=...}` route through BDSL when possible.
        @Specialization(guards = "isBooleanMethod(fieldName)")
        public static Object doBoolean(String fieldName, boolean value) {
            if ("not".equals(fieldName)) return !value;
            // and/or short-circuit: return the receiver (matches
            // CachedFieldAccessNode's behaviour — the record arg then
            // decides the final value).
            return value;
        }

        // MezzeVariant with a payload — unwrap to the payload record and
        // read the field there. Matches AST's accessVariantPayload.
        // Uses getPayloadFieldByName to avoid allocating a materialized
        // MezzeRecord wrapper for fused single-field variants.
        @Specialization(guards = "isVariantWithPayload(receiver)")
        public static Object doVariantPayload(
                String fieldName,
                com.mezze.interpreter.values.MezzeVariant receiver) {
            Object v = receiver.getPayloadFieldByName(fieldName);
            if (v != null) return v;
            return fallbackNotFound(receiver, fieldName);
        }

        // Everything else — mirror CachedFieldAccessNode.accessNotRecord.
        // Ability-op chains where the receiver is a primitive Long/Double
        // that happens NOT to match a specific method (like the failing
        // `(x + y).add {...}` where AST also throws) hit this path.
        //
        // Group B (task #346): scalar-receiver `.to_dbg{}` /
        // `.to_str{}` on Long/Double/Bool/Str/Char also lands here but
        // AST's auto-derived Inspect impl produces a string. Fixing
        // this requires either (1) a peephole in BytecodeCompiler that
        // rewrites CallIndirect(ReadField(scalar, "to_dbg"), ...)
        // to a direct inline stringify, or (2) a MezzeFunction wrapper
        // returned from here. Both are nontrivial — parked. Users hit
        // this path only when the hasReadField gate is dropped, and
        // the gate stays on for now (see MezzeLanguage.isBdslSafeCallee).
        /** `@TruffleBoundary` — the string-concat error-message path drags
         *  Throwable+Formatter reachability that hits native-image blocklist.
         *  Cold fallback (type error). See #402. */
        @Fallback
        @TruffleBoundary
        @InliningCutoff
        public static Object doNotRecord(String fieldName, Object receiver) {
            throw new RuntimeException("Expected record for field '" + fieldName + "', got: " +
                (receiver == null ? "null" : receiver.getClass().getSimpleName()));
        }

        static boolean isBooleanMethod(String fieldName) {
            return "not".equals(fieldName) || "and".equals(fieldName) || "or".equals(fieldName);
        }

        static boolean isVariantWithPayload(Object obj) {
            return obj instanceof com.mezze.interpreter.values.MezzeVariant v
                    && v.hasPayload();
        }

        protected static int resolveLocationIndex(Shape shape, int fieldIndex) {
            if (fieldIndex < 0) return -1;
            FieldLocation loc = shape.getFieldLocation(fieldIndex);
            return loc.index;
        }

        @TruffleBoundary
        @InliningCutoff
        private static Object fallbackNotFound(Object receiver, String fieldName) {
            throw new RuntimeException("Field not found: " + fieldName);
        }
    }

    /**
     * Boundary helper: build an all-Object-storage {@link MezzeRecord}
     * from a fieldName array + values. Marked {@link TruffleBoundary}
     * so the Shape constructor doesn't inline into every op call site
     * (mirrors the AST's fold at {@code RecordLiteralNode.allObjects}).
     */
    /**
     * Tail-call variant of {@link CallDirectN}. Instead of invoking
     * the callee and returning its result, throws a
     * {@link TailCallException} carrying the CallTarget + args — the
     * enclosing trampoline (BodyRepeatingNode / MezzeFunctionRootNode)
     * catches it and re-drives the loop, keeping stack depth flat for
     * deep tail-recursion.
     */
    @Operation
    @ConstantOperand(type = CallTarget.class)
    public static final class TailCallDirectN {
        @Specialization
        public static Object doThrow(CallTarget target, @Variadic Object[] args) {
            throw new TailCallException(target, args);
        }
    }

    /**
     * Tail-call variant of {@link CallIndirect}. Reads the closure's
     * CallTarget + closureValues, prepends closureValues to the args,
     * and throws a {@link TailCallException} for the trampoline to
     * re-drive.
     */
    @Operation
    public static final class TailCallIndirect {
        @Specialization
        public static Object doThrow(MezzeFunction fn, @Variadic Object[] args) {
            // Same partial-application slow path as CallIndirect. A tail
            // call to a partially-applied MezzeFunction still needs to
            // merge/complete the partial args before firing.
            if (args.length == 1 && args[0] instanceof MezzeRecord record
                    && fn.getParameterNames().length > 0) {
                if (!fn.hasAllParameters(record)) {
                    // Returning the partial as the tail result is
                    // semantically correct — the caller frame receives it
                    // as its return value (i.e. the outer function
                    // evaluates to a curried MezzeFunction).
                    return fn.withPartialArgs(record);
                }
                if (fn.hasPartialArgs()) {
                    MezzeRecord complete = fn.buildCompleteRecord(record);
                    Object[] closureValues = fn.getClosureValues();
                    int closureLen = closureValues.length;
                    Object[] combined;
                    if (closureLen == 0) {
                        combined = new Object[] { complete };
                    } else {
                        combined = new Object[closureLen + 1];
                        System.arraycopy(closureValues, 0, combined, 0, closureLen);
                        combined[closureLen] = complete;
                    }
                    throw new TailCallException(fn.getCallTarget(), combined);
                }
            }
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            Object[] combined;
            if (closureLen == 0) {
                combined = args;
            } else {
                combined = new Object[closureLen + args.length];
                System.arraycopy(closureValues, 0, combined, 0, closureLen);
                System.arraycopy(args, 0, combined, closureLen, args.length);
            }
            throw new TailCallException(fn.getCallTarget(), combined);
        }
    }

    /**
     * Call a function value (a {@link MezzeFunction}) with a variadic
     * argument list. Prepends the function's {@code closureValues} to
     * the arg list — matches the calling convention in
     * {@link com.mezze.interpreter.nodes.function.MezzeFunctionRootNode}
     * where args[0..closureCount-1] are closure values.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.CallIndirectIR} and
     * (eventually) {@link com.mezze.language.truffleir.CallIndirectScalarIR}'s
     * general path.
     */
    @Operation
    public static final class CallIndirect {

        /**
         * Monomorphic fast path: when a call site always dispatches to
         * the same CallTarget (common for HOF callbacks like map/filter/
         * fold), use a DirectCallNode instead of IndirectCallNode. Truffle
         * can inline the DirectCallNode target into the compilation unit,
         * while IndirectCallNode stays polymorphic and prevents inlining.
         *
         * Guard: fn.getCallTarget() == cachedTarget AND no partial args.
         * Limit 3 = up to 3 distinct targets before generic fallback.
         */
        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()"
        }, limit = "3")
        public static Object doDirect(
                MezzeFunction fn,
                @Variadic Object[] args,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("createForceInlined(cachedTarget)") DirectCallNode callNode) {
            // Skip partial-application check: the guard ensures no
            // outstanding partial args. For HOF callbacks the arg record
            // always fills all parameters (the typechecker guarantees
            // this at monomorphic call sites).
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(args);
            }
            Object[] combined = new Object[closureLen + args.length];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            System.arraycopy(args, 0, combined, closureLen, args.length);
            return callNode.call(combined);
        }

        static DirectCallNode createForceInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }

        @Specialization(replaces = "doDirect")
        @ReportPolymorphism.Megamorphic
        public static Object doInvoke(
                MezzeFunction fn,
                @Variadic Object[] args,
                @Cached IndirectCallNode callNode) {
            // Partial-application slow path: mirrors
            // InvokeScalarNNode.slowGenericInvoke — when the fn has named
            // params and the single record arg doesn't fill all of them
            // (or completes a prior partial), route through
            // withPartialArgs/buildCompleteRecord. Called from BDSL when a
            // local slot holds a MezzeFunction that we're re-applying.
            if (args.length == 1 && args[0] instanceof MezzeRecord record
                    && fn.getParameterNames().length > 0) {
                if (!fn.hasAllParameters(record)) {
                    return fn.withPartialArgs(record);
                }
                if (fn.hasPartialArgs()) {
                    MezzeRecord complete = fn.buildCompleteRecord(record);
                    return invokeWithClosure(fn, complete, callNode);
                }
            }
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(fn.getCallTarget(), args);
            }
            Object[] combined = new Object[closureLen + args.length];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            System.arraycopy(args, 0, combined, closureLen, args.length);
            return callNode.call(fn.getCallTarget(), combined);
        }



        private static Object invokeWithClosure(MezzeFunction fn, Object arg,
                                                IndirectCallNode callNode) {
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(fn.getCallTarget(), new Object[] { arg });
            }
            Object[] combined = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = arg;
            return callNode.call(fn.getCallTarget(), combined);
        }
    }

    /**
     * Scalar-ABI indirect call with exactly one argument. When the callee
     * is a BDSL function (TrampolineRootNode wrapping a non-tail-call
     * inner), bypasses both record allocation and the trampoline hop —
     * the raw scalar value is passed directly to the inner root.
     *
     * <p>When the callee is NOT a BDSL function (AST function, effect
     * handler, etc.), falls back to wrapping the arg in a single-field
     * record so the callee's existing normalizeArgs path works correctly.
     * The field name is baked as a compile-time constant from the IR.
     *
     * <p>Monomorphic fast path: cache the CallTarget and whether the
     * target can be bypassed. Typical HOF callbacks (map/filter/fold
     * predicates) hit the bypass path every iteration.
     */
    @Operation
    @ConstantOperand(type = String.class) // the single field name for record fallback
    public static final class CallIndirectScalar1 {

        // ── Identity-guarded specializations ──
        // When the SAME function instance is called every iteration
        // (the common case for locally-defined lambdas in loops), the
        // identity guard `fn == cachedFn` makes the captured values
        // @CompilationFinal. After PE + inlining, the callee body
        // operates on constants — e.g. `k * arg` where k is folded.
        // This eliminates the 30×+ overhead measured in closure-heavy
        // hot loops vs manually-inlined equivalents.

        @Specialization(guards = {
            "fn == cachedFn",
            "!cachedFn.hasPartialArgs()",
            "canBypass",
            "closureLen == 0"
        }, limit = "3")
        public static Object doStableNoClosure(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn") MezzeFunction cachedFn,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg);
        }

        @Specialization(guards = {
            "fn == cachedFn",
            "!cachedFn.hasPartialArgs()",
            "canBypass",
            "closureLen == 1"
        }, limit = "3")
        public static Object doStable1Closure(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn") MezzeFunction cachedFn,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("closureAt(fn, 0)") Object capture0,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(capture0, arg);
        }

        @Specialization(guards = {
            "fn == cachedFn",
            "!cachedFn.hasPartialArgs()",
            "canBypass",
            "closureLen == 2"
        }, limit = "3")
        public static Object doStable2Closures(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn") MezzeFunction cachedFn,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("closureAt(fn, 0)") Object capture0,
                @Cached("closureAt(fn, 1)") Object capture1,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(capture0, capture1, arg);
        }

        // ── CallTarget-guarded specializations (fallback) ──
        // Hit when the function instance changes across iterations but
        // the CallTarget is stable (e.g. HOFs that receive different
        // closure instances of the same lambda from different callers).

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass",
            "closureLen == 0"
        }, limit = "3")
        public static Object doBypassNoClosure(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg);
        }

        /**
         * Bypass with exactly 1 captured value: pass (closure0, arg)
         * directly as varargs — avoids explicit Object[] allocation +
         * System.arraycopy that would otherwise run per call.
         */
        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass",
            "closureLen == 1"
        }, limit = "3")
        public static Object doBypass1Closure(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(fn.getClosureValues()[0], arg);
        }

        /**
         * Bypass with exactly 2 captured values: (closure0, closure1, arg).
         */
        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass",
            "closureLen == 2"
        }, limit = "3")
        public static Object doBypass2Closures(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            Object[] cv = fn.getClosureValues();
            return callNode.call(cv[0], cv[1], arg);
        }

        /**
         * Generic bypass with 3+ captured values — falls back to
         * explicit array allocation.
         */
        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass"
        }, limit = "3")
        public static Object doBypassGenericClosure(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            Object[] closureValues = fn.getClosureValues();
            Object[] combined = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = arg;
            return callNode.call(combined);
        }

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "!canBypass"
        }, limit = "3")
        public static Object doDirectRecord(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("createInlined(cachedTarget)") DirectCallNode callNode) {
            // Callee is not a bypassable BDSL function — wrap arg in a
            // record so the callee's normalizeArgs handles it correctly.
            MezzeRecord record = MezzeRecord.fromFields(
                    new String[] { fieldName }, new Object[] { arg });
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(record);
            }
            Object[] combined = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = record;
            return callNode.call(combined);
        }

        @Specialization(replaces = {"doStableNoClosure", "doStable1Closure", "doStable2Closures", "doBypassNoClosure", "doBypass1Closure", "doBypass2Closures", "doBypassGenericClosure", "doDirectRecord"})
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                String fieldName,
                MezzeFunction fn,
                Object arg,
                @Cached IndirectCallNode callNode) {
            // Bypass path for BDSL callees (no partial args, no effect handlers).
            CallTarget target = fn.getCallTarget();
            if (!fn.hasPartialArgs() && canBypassTarget(target)) {
                target = resolveInner(target);
                Object[] closureValues = fn.getClosureValues();
                int closureLen = closureValues.length;
                try {
                    if (closureLen == 0) {
                        return callNode.call(target, arg);
                    }
                    Object[] combined = new Object[closureLen + 1];
                    System.arraycopy(closureValues, 0, combined, 0, closureLen);
                    combined[closureLen] = arg;
                    return callNode.call(target, combined);
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw e;
                }
            }
            // Fallback: wrap in record and route through the full
            // CallIndirect dispatch (handles partial application,
            // effect handlers, AST callees).
            MezzeRecord record = MezzeRecord.fromFields(
                    new String[] { fieldName }, new Object[] { arg });
            // Partial-application check (mirrors CallIndirect.doInvoke).
            if (fn.getParameterNames().length > 0) {
                if (!fn.hasAllParameters(record)) {
                    return fn.withPartialArgs(record);
                }
                if (fn.hasPartialArgs()) {
                    MezzeRecord complete = fn.buildCompleteRecord(record);
                    Object[] closureValues = fn.getClosureValues();
                    int closureLen = closureValues.length;
                    if (closureLen == 0) {
                        return callNode.call(target, complete);
                    }
                    Object[] combined = new Object[closureLen + 1];
                    System.arraycopy(closureValues, 0, combined, 0, closureLen);
                    combined[closureLen] = complete;
                    return callNode.call(target, combined);
                }
            }
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(target, record);
            }
            Object[] combined = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = record;
            return callNode.call(target, combined);
        }

        static Object closureAt(MezzeFunction fn, int index) {
            return fn.getClosureValues()[index];
        }

        static DirectCallNode createInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }

        @CompilerDirectives.TruffleBoundary
        static boolean canBypassTarget(CallTarget target) {
            if (target instanceof com.oracle.truffle.api.RootCallTarget rct) {
                var root = rct.getRootNode();
                // Non-tail-call trampoline: bypass to inner BDSL root
                // ONLY when the trampoline's parameter count matches CIS1's
                // arity (1 user arg) AND there are no dict params. Dict-passing
                // inflates paramCount beyond the user-visible arity — bypassing
                // skips the trampoline's dict-fallback resolution and sends the
                // user's record as a dict slot → UnsupportedSpecializationException.
                if (root instanceof TrampolineRootNode tramp
                        && !tramp.hasTailCalls()
                        && tramp.getParameterCount() == 1
                        && !hasDictParams(tramp)
                        && tramp.getInnerTarget() != null) {
                    return true;
                }
            }
            return false;
        }

        @CompilerDirectives.TruffleBoundary
        static CallTarget resolveInner(CallTarget target) {
            if (target instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof TrampolineRootNode tramp
                    && tramp.getInnerTarget() != null) {
                return tramp.getInnerTarget();
            }
            return target;
        }
    }

    /**
     * Scalar-ABI indirect call with exactly two arguments. Mirrors
     * {@link CallIndirectScalar1} but passes two raw scalar values
     * instead of one — eliminates MakeRecord allocation for 2-arg
     * HOF callbacks like fold/reduce ({acc, x} -> ...).
     *
     * <p>Field names are baked as a compile-time constant for the
     * cold record-fallback path (AST callees, effect handlers).
     * The hot bypass path ignores them entirely.
     */
    @Operation
    @ConstantOperand(type = String[].class) // field names for record fallback
    public static final class CallIndirectScalar2 {

        // ── Identity-guarded specializations ──

        @Specialization(guards = {
            "fn == cachedFn",
            "!cachedFn.hasPartialArgs()",
            "canBypass",
            "closureLen == 0"
        }, limit = "3")
        public static Object doStableNoClosure(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn") MezzeFunction cachedFn,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg1, arg2);
        }

        @Specialization(guards = {
            "fn == cachedFn",
            "!cachedFn.hasPartialArgs()",
            "canBypass",
            "closureLen == 1"
        }, limit = "3")
        public static Object doStable1Closure(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn") MezzeFunction cachedFn,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("closureAt(fn, 0)") Object capture0,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(capture0, arg1, arg2);
        }

        @Specialization(guards = {
            "fn == cachedFn",
            "!cachedFn.hasPartialArgs()",
            "canBypass",
            "closureLen == 2"
        }, limit = "3")
        public static Object doStable2Closures(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn") MezzeFunction cachedFn,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("closureAt(fn, 0)") Object capture0,
                @Cached("closureAt(fn, 1)") Object capture1,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(capture0, capture1, arg1, arg2);
        }

        // ── CallTarget-guarded specializations (fallback) ──

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass",
            "closureLen == 0"
        }, limit = "3")
        public static Object doBypassNoClosure(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(arg1, arg2);
        }

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass",
            "closureLen == 1"
        }, limit = "3")
        public static Object doBypass1Closure(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            return callNode.call(fn.getClosureValues()[0], arg1, arg2);
        }

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass",
            "closureLen == 2"
        }, limit = "3")
        public static Object doBypass2Closures(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            Object[] cv = fn.getClosureValues();
            return callNode.call(cv[0], cv[1], arg1, arg2);
        }

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "canBypass"
        }, limit = "3")
        public static Object doBypassGenericClosure(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("resolveInner(cachedTarget)") CallTarget resolved,
                @Cached("fn.getClosureValues().length") int closureLen,
                @Cached("createInlined(resolved)") DirectCallNode callNode) {
            Object[] closureValues = fn.getClosureValues();
            Object[] combined = new Object[closureLen + 2];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = arg1;
            combined[closureLen + 1] = arg2;
            return callNode.call(combined);
        }

        @Specialization(guards = {
            "fn.getCallTarget() == cachedTarget",
            "!fn.hasPartialArgs()",
            "!canBypass"
        }, limit = "3")
        public static Object doDirectRecord(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @Cached("canBypassTarget(cachedTarget)") boolean canBypass,
                @Cached("createInlined(cachedTarget)") DirectCallNode callNode) {
            MezzeRecord record = MezzeRecord.fromFields(
                    fieldNames, new Object[] { arg1, arg2 });
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(record);
            }
            Object[] combined = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = record;
            return callNode.call(combined);
        }

        @Specialization(replaces = {"doStableNoClosure", "doStable1Closure", "doStable2Closures", "doBypassNoClosure", "doBypass1Closure", "doBypass2Closures", "doBypassGenericClosure", "doDirectRecord"})
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                String[] fieldNames,
                MezzeFunction fn, Object arg1, Object arg2,
                @Cached IndirectCallNode callNode) {
            CallTarget target = fn.getCallTarget();
            if (!fn.hasPartialArgs() && canBypassTarget(target)) {
                target = resolveInner(target);
                Object[] closureValues = fn.getClosureValues();
                int closureLen = closureValues.length;
                if (closureLen == 0) {
                    return callNode.call(target, arg1, arg2);
                }
                Object[] combined = new Object[closureLen + 2];
                System.arraycopy(closureValues, 0, combined, 0, closureLen);
                combined[closureLen] = arg1;
                combined[closureLen + 1] = arg2;
                return callNode.call(target, combined);
            }
            MezzeRecord record = MezzeRecord.fromFields(
                    fieldNames, new Object[] { arg1, arg2 });
            if (fn.getParameterNames().length > 0) {
                if (!fn.hasAllParameters(record)) {
                    return fn.withPartialArgs(record);
                }
                if (fn.hasPartialArgs()) {
                    MezzeRecord complete = fn.buildCompleteRecord(record);
                    Object[] closureValues = fn.getClosureValues();
                    int closureLen = closureValues.length;
                    if (closureLen == 0) {
                        return callNode.call(target, complete);
                    }
                    Object[] combined = new Object[closureLen + 1];
                    System.arraycopy(closureValues, 0, combined, 0, closureLen);
                    combined[closureLen] = complete;
                    return callNode.call(target, combined);
                }
            }
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            if (closureLen == 0) {
                return callNode.call(target, record);
            }
            Object[] combined = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, combined, 0, closureLen);
            combined[closureLen] = record;
            return callNode.call(target, combined);
        }

        static Object closureAt(MezzeFunction fn, int index) {
            return fn.getClosureValues()[index];
        }

        static DirectCallNode createInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }

        @CompilerDirectives.TruffleBoundary
        static boolean canBypassTarget(CallTarget target) {
            if (target instanceof com.oracle.truffle.api.RootCallTarget rct) {
                var root = rct.getRootNode();
                // CIS2 bypass: safe only when paramCount == 2 AND no dict
                // params. See CIS1.canBypassTarget for rationale.
                if (root instanceof TrampolineRootNode tramp
                        && !tramp.hasTailCalls()
                        && tramp.getParameterCount() == 2
                        && !hasDictParams(tramp)
                        && tramp.getInnerTarget() != null) {
                    return true;
                }
            }
            return false;
        }

        @CompilerDirectives.TruffleBoundary
        static CallTarget resolveInner(CallTarget target) {
            if (target instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof TrampolineRootNode tramp
                    && tramp.getInnerTarget() != null) {
                return tramp.getInnerTarget();
            }
            return target;
        }
    }

    /**
     * Construct a {@link MezzeFunction} closure — takes a compile-time
     * {@link CallTarget} + function name, pops the capture values off
     * the stack (variadic), pushes a MezzeFunction wrapping them.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.MakeLambdaIR}. The
     * closureValues array order matches the callee's captureSlots (so
     * the callee's ReadLocalIR on capture slot i receives the same
     * value the caller put at position i).
     */
    @Operation
    @ConstantOperand(type = CallTarget.class)
    @ConstantOperand(type = String.class)
    @ConstantOperand(type = String[].class)
    public static final class MakeLambda {
        /** PE-transparent: MezzeFunction ctor is now just field assignments
         *  (inlineAssumption removed), so PE can see through lambda creation
         *  and potentially scalar-replace the MezzeFunction when the closure
         *  is constant. This was @TruffleBoundary in #402 because the ctor
         *  called Truffle.getRuntime().createAssumption() which hit SVM's
         *  blocklist — that call is gone now. */
        @Specialization
        public static MezzeFunction doMake(CallTarget target, String name, String[] paramNames,
                                            @Variadic Object[] captures) {
            // #374 — must thread paramNames through so partial application
            // works. Without them, MezzeFunction.parameterNames is EMPTY
            // and CallIndirect/MonomorphicCall skip the partial-app slow
            // path (hasAllParameters trivially true over empty list),
            // silently calling the target with incomplete args.
            return new MezzeFunction(target, name, captures, paramNames);
        }
    }

    /**
     * MakeLambda by content hash — for the case where the callee
     * CallTarget isn't yet in this compiler's local cache at emit
     * time. Resolves the target lazily via
     * {@link MezzeLanguage#getCachedStdlibCallTarget} on first execute
     * and caches it as an inline-cache slot (PIC).
     *
     * <p>After #304's per-Language cache-every-fn change, this is
     * safe across threads — every compiled fn (including user
     * lambdas) lands in the per-Language map keyed by hash.
     */
    @Operation
    @ConstantOperand(type = String.class)
    @ConstantOperand(type = String.class)
    public static final class MakeLambdaByHash {
        /** PE-transparent: MezzeFunction ctor is just field assignments
         *  now (see MakeLambda). The @Cached resolveByHash stays
         *  @TruffleBoundary (HashMap lookup), but doMake itself is
         *  PE-visible so the JIT can scalar-replace the MezzeFunction
         *  when closure values are constant. */
        @Specialization
        public static MezzeFunction doMake(
                String hashHex, String name, @Variadic Object[] captures,
                @Cached(value = "resolveByHash(hashHex)", allowUncached = true) CallTarget cachedTarget) {
            return new MezzeFunction(cachedTarget, name, captures);
        }

        @TruffleBoundary
        static CallTarget resolveByHash(String hashHex) {
            CallTarget ct = MezzeLanguage.get(null)
                    .getCachedStdlibCallTarget(new Hash(hashHex));
            if (ct == null) {
                throw new IllegalStateException(
                        "MakeLambdaByHash: no function registered for hash "
                        + new Hash(hashHex).getHex());
            }
            return ct;
        }
    }

    /**
     * Call by content hash — for CallDirectIR whose callee CallTarget
     * isn't in this compiler's local cache at emit time. Same lazy
     * PIC-cached shape as {@link MakeLambdaByHash}; matches the ABI
     * of {@link CallDirectN} once warm (variadic Object[] args).
     *
     * <p>After #304's per-Language cache-every-fn change, every
     * compiled fn is resolvable by hash from any thread.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class CallByHash {
        @Specialization(guards = "callNode.getCallTarget() == cachedTarget")
        public static Object doCached(
                String hashHex,
                @Variadic Object[] args,
                @Cached(value = "resolveByHash(hashHex)", allowUncached = true) CallTarget cachedTarget,
                @Cached(value = "callByHashNeedsMarker(cachedTarget, args.length)", allowUncached = true, neverDefault = false) boolean needsMarker,
                @Cached("create(cachedTarget)") DirectCallNode callNode) {
            return callNode.call(needsMarker ? callByHashPrependMarker(args) : args);
        }

        @Specialization(replaces = "doCached")
        @ReportPolymorphism.Megamorphic
        public static Object doIndirect(
                String hashHex,
                @Variadic Object[] args,
                @Cached IndirectCallNode indirectCallNode) {
            CallTarget target = resolveByHash(hashHex);
            Object[] finalArgs = callByHashNeedsMarker(target, args.length)
                    ? callByHashPrependMarker(args)
                    : args;
            return indirectCallNode.call(target, finalArgs);
        }

        @TruffleBoundary
        static CallTarget resolveByHash(String hashHex) {
            CallTarget ct = MezzeLanguage.get(null)
                    .getCachedStdlibCallTarget(new Hash(hashHex));
            if (ct == null) {
                throw new IllegalStateException(
                        "CallByHash: no function registered for hash "
                        + new Hash(hashHex).getHex());
            }
            return ct;
        }

        /**
         * ABI discrimination for CallByHash / TailCallByHash:
         * AST callees at arity==1 need ScalarABIMarker prepended so
         * MezzeFunctionRootNode's normalizeArgs treats the arg as scalar
         * not record. BDSL trampolines strip markers themselves — marker
         * optional. Native record-ABI callees expect the raw record —
         * must NOT prepend.
         */
        public static boolean callByHashNeedsMarker(CallTarget target, int argsLen) {
            if (argsLen != 1) return false;
            if (!(target instanceof com.oracle.truffle.api.RootCallTarget rct)) return false;
            var root = rct.getRootNode();
            return root instanceof com.mezze.interpreter.nodes.function.MezzeFunctionRootNode;
        }

        public static Object[] callByHashPrependMarker(Object[] args) {
            Object[] out = new Object[args.length + 1];
            out[0] = com.mezze.interpreter.nodes.function.ScalarABIMarker.INSTANCE;
            System.arraycopy(args, 0, out, 1, args.length);
            return out;
        }
    }

    /**
     * Tail-call variant of {@link CallByHash}. Resolves the target
     * lazily by hash, then throws a {@link TailCallException} for
     * the enclosing trampoline to re-drive.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class TailCallByHash {
        @Specialization
        public static Object doThrow(
                String hashHex,
                @Variadic Object[] args,
                @Cached(value = "resolveByHash(hashHex)", allowUncached = true) CallTarget cachedTarget,
                @Cached(value = "needsMarkerFor(cachedTarget, args.length)", allowUncached = true, neverDefault = false) boolean needsMarker) {
            Object[] finalArgs = needsMarker ? prependMarkerFor(args) : args;
            throw new TailCallException(cachedTarget, finalArgs);
        }

        @TruffleBoundary
        static CallTarget resolveByHash(String hashHex) {
            return CallByHash.resolveByHash(hashHex);
        }

        static boolean needsMarkerFor(CallTarget target, int argsLen) {
            return CallByHash.callByHashNeedsMarker(target, argsLen);
        }

        static Object[] prependMarkerFor(Object[] args) {
            return CallByHash.callByHashPrependMarker(args);
        }
    }


    /**
     * Call a scalar-ABI native op by name. Args are positional, packed
     * into an {@link PositionalArgs}, and passed to the registered
     * {@link NativeHandler}.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.CallNativeIR} when
     * {@code isScalarABI == true} AND {@code NativeHandlers.needsNode(name)}
     * is false. For {@code needsNode == true} ops (Async, HOF-callback
     * consumers) the sibling {@link CallNativeScalarWithNode} op below
     * threads an {@link IndirectCallNode} and a caller {@link Node} into
     * the handler.
     *
     * <p>The handler is resolved once at build time via {@link Cached}.
     * If a name isn't registered, we look up eagerly at compile time
     * via {@link BytecodeCompiler#supports} — no runtime null-check
     * overhead in the hot path.
     */
    @Operation
    @ConstantOperand(type = NativeHandler.class)
    public static final class CallNativeScalar {
        /**
         * `@TruffleBoundary` on the scalar native dispatch — the handler lambda
         * is opaque to PE (its impl body can reach anything the JDK exposes:
         * String.replace, Pattern.quote, Math libs, allocating collections, ...).
         * SVM TruffleFeature's blocklist check follows that reachability and
         * bails on native-image build.
         *
         * <p>Perf-critical numeric ops (+, -, *, /, %, comparisons on Int/Float/Bool)
         * do NOT go through CallNativeScalar — they lower to {@link InlineBinaryOp}
         * which stays PE-inline. This dispatch is only for names that lack an
         * inline IR shortcut (String ops, math like sqrt/pow, allocating seq
         * builtins). Those are inherently allocating / heavier so the boundary
         * has no measurable impact.
         *
         * <p>See #402.
         */
        @Specialization
        @TruffleBoundary
        public static Object doInvoke(NativeHandler handler, @Variadic Object[] args) {
            return handler.invoke(new PositionalArgs(args, null, null));
        }
    }

    /**
     * Legacy record-ABI native call: takes the pre-resolved
     * {@link com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler}
     * as a constant operand and a MezzeRecord arg on the stack, calls
     * the handler with a fresh IndirectCallNode.
     *
     * <p>#350: handler is resolved at emit time (via
     * {@link com.mezze.interpreter.nodes.builtin.NativeDispatch#recordHandlerOf})
     * so the hot path has no HashMap lookup and no string keys — mirrors
     * the pattern {@link CallNativeScalar} already uses.
     *
     * <p>Still allocates/unpacks a MezzeRecord per call (that's what
     * separates record-ABI from scalar-ABI); the win here is only
     * eliminating the string-keyed dispatch tax.
     */
    @Operation
    @ConstantOperand(type = com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler.class)
    public static final class CallNativeRecord {
        /**
         * `@TruffleBoundary` — legacy record-ABI native dispatch. The handler
         * lambda is opaque to PE (it's stored in a static Map and can reach
         * any JDK utility). Under native-image SVM's runtime compilation the
         * reachability closure lands on blocklisted methods (List.of, Vector,
         * Map.remove, String.format, ...). Boundaries here match the sibling
         * {@link CallNativeScalar} #402 fix; the record-ABI path is inherently
         * allocating (unpacks a MezzeRecord) so it was never a JIT hot path.
         *
         * <p>See #402.
         */
        @Specialization
        @TruffleBoundary
        public static Object doInvoke(
                com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler handler,
                Object arg,
                @Cached(value = "createIndirect()", allowUncached = true)
                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            if (!(arg instanceof com.mezze.interpreter.values.MezzeRecord record)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("native handler expects record argument, got: "
                        + (arg == null ? "null" : arg.getClass().getName()));
            }
            return handler.handle(record, callNode);
        }
        /** `@TruffleBoundary` — IndirectCallNode.create → Node.<init>. #402. */
        @TruffleBoundary
        static com.oracle.truffle.api.nodes.IndirectCallNode createIndirect() {
            return com.oracle.truffle.api.nodes.IndirectCallNode.create();
        }
    }

    // CallNativeRecordWithNode REMOVED — the SVM blocklist checker does
    // not respect @TruffleBoundary on BDSL-generated operations (neither
    // on the @Specialization method nor on subordinate helpers). All
    // handlers previously in HANDLERS_WITH_NODE now live in HANDLERS and
    // dispatch through {@link CallNativeRecord} whose @TruffleBoundary
    // IS respected. See #402, NativeDispatch.registerSeqHandlers().

    /**
     * Typed fast path for (long, long) -> long natives: int add, sub,
     * mul, div, mod, min, max, pow, gcd, lcm. Handler is looked up
     * once at BDSL build time from {@link TypedNativeHandlers} and
     * baked as a constant; args come in unboxed and the return is
     * unboxed too. No {@link PositionalArgs} alloc, no
     * {@link NativeHandler} indirection.
     */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.LLL.class)
    public static final class CallNativeLLL {
        @Specialization
        @ForceQuickening
        public static long doInvoke(TypedNativeHandlers.LLL handler, long a, long b) {
            return handler.invoke(a, b);
        }
    }

    /** Typed fast path for (long) -> long natives: int abs/neg/sign. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.LL.class)
    public static final class CallNativeLL {
        @Specialization
        @ForceQuickening
        public static long doInvoke(TypedNativeHandlers.LL handler, long a) {
            return handler.invoke(a);
        }
    }

    /**
     * Typed fast path for (long, long) -> boolean natives: int
     * comparisons (eq/neq/lt/le/gt/ge).
     */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.LLB.class)
    public static final class CallNativeLLB {
        @Specialization
        @ForceQuickening
        public static boolean doInvoke(TypedNativeHandlers.LLB handler, long a, long b) {
            return handler.invoke(a, b);
        }
    }

    /** Typed fast path for (double, double) -> double natives: float arith. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.DDD.class)
    public static final class CallNativeDDD {
        @Specialization
        @ForceQuickening
        public static double doInvoke(TypedNativeHandlers.DDD handler, double a, double b) {
            return handler.invoke(a, b);
        }
    }

    /** Typed fast path for (double) -> double natives: float unary (sqrt/floor/trig/etc.). */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.DD.class)
    public static final class CallNativeDD {
        @Specialization
        @ForceQuickening
        public static double doInvoke(TypedNativeHandlers.DD handler, double a) {
            return handler.invoke(a);
        }
    }

    /** Typed fast path for (double, double) -> boolean natives: float comparisons. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.DDB.class)
    public static final class CallNativeDDB {
        @Specialization
        @ForceQuickening
        public static boolean doInvoke(TypedNativeHandlers.DDB handler, double a, double b) {
            return handler.invoke(a, b);
        }
    }

    /** Typed fast path for (double) -> boolean natives: is_nan / is_infinite / is_finite. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.DB.class)
    public static final class CallNativeDB {
        @Specialization
        @ForceQuickening
        public static boolean doInvoke(TypedNativeHandlers.DB handler, double a) {
            return handler.invoke(a);
        }
    }

    /** Typed fast path for (long) -> double natives: int→float conversion. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.LD.class)
    public static final class CallNativeLD {
        @Specialization
        @ForceQuickening
        public static double doInvoke(TypedNativeHandlers.LD handler, long a) {
            return handler.invoke(a);
        }
    }

    /** Typed fast path for (double) -> long natives: Math.round-style. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.DL.class)
    public static final class CallNativeDL {
        @Specialization
        @ForceQuickening
        public static long doInvoke(TypedNativeHandlers.DL handler, double a) {
            return handler.invoke(a);
        }
    }

    /** Typed fast path for (boolean) -> boolean natives: bool_not. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.BB.class)
    public static final class CallNativeBB {
        @Specialization
        @ForceQuickening
        public static boolean doInvoke(TypedNativeHandlers.BB handler, boolean a) {
            return handler.invoke(a);
        }
    }

    /** Typed fast path for (boolean, boolean) -> boolean natives: and/or/xor/eq/neq. */
    @Operation
    @ConstantOperand(type = TypedNativeHandlers.BBB.class)
    public static final class CallNativeBBB {
        @Specialization
        @ForceQuickening
        public static boolean doInvoke(TypedNativeHandlers.BBB handler, boolean a, boolean b) {
            return handler.invoke(a, b);
        }
    }

    /**
     * Variant of {@link CallNativeScalar} for handlers registered as
     * {@code NativeOpWithNode} — Async ops (spawn/await/park), HOF
     * callbacks that need a shared {@link IndirectCallNode}, and any op
     * that reads {@code args.callerNode} for source-section lookup.
     *
     * <p>BDSL exposes the enclosing operation's {@link Node} via a
     * {@code @Bind Node} parameter, and gives us a cached
     * {@link IndirectCallNode} through {@code @Cached}. Both are
     * threaded into the {@link PositionalArgs}, matching the AST-side
     * {@code NativeDispatchScalarNode}'s slow path.
     */
    @Operation
    @ConstantOperand(type = NativeHandler.class)
    public static final class CallNativeScalarWithNode {
        /**
         * `@TruffleBoundary` — this variant is used only by "needs-node"
         * handlers (Async spawn/await/park, HOF-callback consumers, and
         * stringy allocating ops). Their lambda bodies reach String.replace,
         * Pattern.quote, and other JDK utilities whose reachability closure
         * hits blocklisted native-image runtime methods. The plain scalar
         * variant {@link CallNativeScalar} stays PE-inline for numeric/bool
         * primitives (the actual hot path). See #402.
         */
        @Specialization
        @TruffleBoundary
        public static Object doInvoke(
                NativeHandler handler,
                @Variadic Object[] args,
                @Cached IndirectCallNode callNode,
                @Bind Node node) {
            return handler.invoke(new PositionalArgs(args, callNode, (com.mezze.interpreter.nodes.MezzeNode) null));
        }
    }

    /**
     * String interpolation: {@code "hello $(name), you are $(age)"}.
     *
     * <p>{@code parts} is a compile-time {@code String[]} where a
     * non-null entry is a literal chunk and a {@code null} entry marks
     * an interpolated expression position. Variadic values fill only
     * the expression positions in order.
     *
     * <p>Boundary-fold appends everything to a StringBuilder — matches
     * the AST's {@code InterpolatedStringNode} semantics (which uses
     * {@code String.valueOf} for non-string values).
     */
    @Operation
    @ConstantOperand(type = String[].class)
    public static final class InterpolateString {
        @Specialization
        public static String doInterpolate(String[] parts, @Variadic Object[] exprValues) {
            return buildInterpolatedString(parts, exprValues);
        }
    }

    /**
     * Seq size equals compile-time N. Used by Seq patterns without a
     * rest — {@code [a, b, c]} matches only length-3 sequences.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SeqSizeEquals {
        @Specialization
        public static boolean doSeq(int expected, MezzeSeq<?> seq) {
            return seq.size() == expected;
        }
    }

    /**
     * Seq size at least compile-time N. Used by Seq patterns with a
     * rest — {@code [a, b, ...rest]} matches sequences of size >= 2.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SeqSizeAtLeast {
        @Specialization
        public static boolean doSeq(int min, MezzeSeq<?> seq) {
            return seq.size() >= min;
        }
    }

    /** Read seq element at a compile-time index. */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SeqGetAt {
        @Specialization
        public static Object doSeq(int index, MezzeSeq<?> seq) {
            return seq.get(index);
        }
    }

    /** Get seq size as a long. Used by #407 fold-fusion inlined loops.
     *  Extended for Object[] to support list chain fusion (list.toArray()
     *  produces Object[], then the fused loop iterates by index). */
    @Operation
    public static final class SeqSize {
        @Specialization
        public static long doSeq(MezzeSeq<?> seq) {
            return seq.size();
        }
        @Specialization
        public static long doObjectArray(Object[] array) {
            return array.length;
        }
    }

    // SeqExtractLongArray + SeqGetLong REMOVED: the useUnboxedLongArray
    // code path in BytecodeCompiler was never activated (always false).
    // The operations remained in the BDSL spec, and SeqExtractLongArray's
    // doFallback → Long.longValue() → Number.longValue() virtual dispatch
    // pulled BigDecimal.longValue() into the SVM runtime-compilation graph,
    // tripping the blocklist. BDSL Uncached node paths don't respect
    // @TruffleBoundary (neither @Operation nor @OperationProxy), so the
    // only fix is to remove the dead operation. If unboxed long-array
    // iteration is re-enabled, use FusedLongPipeline instead. (#native-image)

    // ── Fused Long Pipeline: single-operation tight loop for numeric chains ──

    /**
     * Sink kind constants for {@link FusedLongPipeline}.
     */
    public static final int FLP_SINK_SUM = 0, FLP_SINK_COUNT = 1,
        FLP_SINK_ANY = 2, FLP_SINK_ALL = 3, FLP_SINK_FIND = 4,
        FLP_SINK_TO_SEQ = 5, FLP_SINK_TO_LIST = 6, FLP_SINK_REDUCE = 7,
        FLP_SINK_NONE = 8;

    /**
     * Pack op ordinals and flags into a single int for FusedLongPipeline.
     *
     * <p>Layout: bits 0-3 sinkKind, 4-7 mapOp+1, 8-11 filterPreOp+1,
     * 12-15 filterCmpOp+1, 16-19 sinkPredPreOp+1, 20-23 sinkPredCmpOp+1,
     * 24-27 foldOp+1, bit 28 selfMap, bit 29 filterFirst, bit 30 hasTake.
     * 0 in any 4-bit field means "absent".
     */
    public static int flpPackConfig(
            int sinkKind, int mapOp, int filterPreOp, int filterCmpOp,
            int sinkPredPreOp, int sinkPredCmpOp, int foldOp,
            boolean selfMap, boolean filterFirst, boolean hasTake) {
        return (sinkKind & 0xF)
             | (((mapOp + 1) & 0xF) << 4)
             | (((filterPreOp + 1) & 0xF) << 8)
             | (((filterCmpOp + 1) & 0xF) << 12)
             | (((sinkPredPreOp + 1) & 0xF) << 16)
             | (((sinkPredCmpOp + 1) & 0xF) << 20)
             | (((foldOp + 1) & 0xF) << 24)
             | (selfMap ? (1 << 28) : 0)
             | (filterFirst ? (1 << 29) : 0)
             | (hasTake ? (1 << 30) : 0);
    }

    /**
     * Single-operation fused pipeline over a long-backed Seq. Replaces
     * the BDSL While loop for all-inline numeric chains.
     *
     * <p>Config is a {@code @ConstantOperand} so the JIT constant-folds
     * all branch decisions. Numeric operands are dynamic children — for
     * compile-time constants the emitter emits LoadConstant nodes that PE
     * folds identically; for runtime expressions (e.g. {@code n - 10})
     * the value is computed once before the loop and passed in.
     *
     * <p>Children (in order): seq, mapOperand, filterPreRhs, filterCmpRhs,
     * sinkPredPreRhs, sinkPredCmpRhs, foldInit, takeLimit.
     */
    @Operation
    @ConstantOperand(type = int.class)   // config: packed ops (PE constant)
    public static final class FusedLongPipeline {

        @Specialization(guards = "isLongStorage(seq)")
        public static Object doLong(
                int config,
                MezzeSeq<?> seq,
                long mapOperand, long filterPreRhs,
                long filterCmpRhs, long sinkPredPreRhs,
                long sinkPredCmpRhs, long foldInit,
                long takeLimit,
                @Bind Node node,
                @Cached InlinedLoopConditionProfile loopProfile) {
            // Unpack config — each field is PE-constant.
            int sinkKind      = config & 0xF;
            int mapOp         = ((config >> 4) & 0xF) - 1;
            int filterPreOp   = ((config >> 8) & 0xF) - 1;
            int filterCmpOp   = ((config >> 12) & 0xF) - 1;
            int sinkPredPreOp = ((config >> 16) & 0xF) - 1;
            int sinkPredCmpOp = ((config >> 20) & 0xF) - 1;
            int foldOp        = ((config >> 24) & 0xF) - 1;
            boolean selfMap   = (config & (1 << 28)) != 0;

            com.mezze.datastructures.seq.LongSeqStorage ls =
                    (com.mezze.datastructures.seq.LongSeqStorage) seq.getStorage();
            long[] data = ls.getArray();
            int n = ls.size();

            // Take stage: clamp iteration count to takeLimit.
            boolean hasTake = (config & (1 << 30)) != 0;
            if (hasTake) n = (int) Math.min(n, takeLimit);

            boolean filterFirst = (config & (1 << 29)) != 0;

            // ── SIMD fast path for SUM/COUNT/REDUCE on materialized long[] ──
            // Gated off on SVM and when jdk.incubator.vector is absent.
            if (FlpSimd.AVAILABLE
                    && n >= FlpSimd.SIMD_THRESHOLD
                    && (sinkKind == FLP_SINK_SUM || sinkKind == FLP_SINK_COUNT
                        || sinkKind == FLP_SINK_REDUCE)
                    && sinkPredCmpOp < 0) {
                return FlpSimd.flpArraySimd(data, n, sinkKind,
                        mapOp, mapOperand, selfMap,
                        filterPreOp, filterPreRhs, filterCmpOp, filterCmpRhs,
                        foldOp, foldInit, filterFirst);
            }

            long acc = foldInit;
            boolean found = false;
            long foundVal = 0;

            // Reduce sink: init from first post-filter/map element inside the loop.
            boolean reduceNeedInit = sinkKind == FLP_SINK_REDUCE;
            if (reduceNeedInit && n == 0) return flpResultReduceEmpty();

            // Materialize sinks: pre-allocate to source size, fill, trim.
            long[] matData = null;
            int matCount = 0;
            if (sinkKind == FLP_SINK_TO_SEQ || sinkKind == FLP_SINK_TO_LIST) {
                matData = new long[n > 0 ? n : 0];
            }

            loopProfile.profileCounted(node, n);
            for (int i = 0; loopProfile.inject(node, i < n); i++) {
                long v = data[i];

                // ── Filter-first path ──
                if (filterFirst && filterCmpOp >= 0) {
                    long fv = filterPreOp >= 0
                            ? flpOp(filterPreOp, v, filterPreRhs) : v;
                    if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
                }

                // ── Map stage ──
                if (mapOp >= 0) v = flpOp(mapOp, v, selfMap ? v : mapOperand);

                // ── Filter-second path ──
                if (!filterFirst && filterCmpOp >= 0) {
                    long fv = filterPreOp >= 0
                            ? flpOp(filterPreOp, v, filterPreRhs) : v;
                    if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
                }

                // ── Sink ──
                if (sinkKind == FLP_SINK_SUM) {
                    acc = flpOp(foldOp, acc, v);
                } else if (sinkKind == FLP_SINK_REDUCE) {
                    if (reduceNeedInit) { acc = v; reduceNeedInit = false; }
                    else { acc = flpOp(foldOp, acc, v); }
                } else if (sinkKind == FLP_SINK_COUNT) {
                    acc++;
                } else if (sinkKind == FLP_SINK_TO_SEQ || sinkKind == FLP_SINK_TO_LIST) {
                    matData[matCount++] = v;
                } else if (sinkKind == FLP_SINK_ANY) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; break;
                        }
                    } else { found = true; break; }
                } else if (sinkKind == FLP_SINK_ALL) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (!flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; break;
                        }
                    }
                } else if (sinkKind == FLP_SINK_FIND) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; foundVal = v; break;
                        }
                    }
                } else if (sinkKind == FLP_SINK_NONE) {
                    // none = !any: if any element matches, result is false.
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; break;
                        }
                    } else { found = true; break; }
                }
            }

            // Report loop iterations to Truffle — drives PE on SVM native.
            LoopNode.reportLoopCount(node, n);

            // ── Post-loop result ──
            if (sinkKind == FLP_SINK_REDUCE && reduceNeedInit) {
                return flpResultReduceEmpty();
            }
            if (sinkKind == FLP_SINK_TO_SEQ) {
                return FusedLongRangePipeline.flpResultToSeq(matData, matCount);
            } else if (sinkKind == FLP_SINK_TO_LIST) {
                return FusedLongRangePipeline.flpResultToList(matData, matCount);
            }
            return flpResult(sinkKind, acc, found, foundVal);
        }

        @Specialization(guards = "!isLongStorage(seq)")
        @TruffleBoundary
        public static Object doFallback(
                int config,
                MezzeSeq<?> seq,
                long mapOperand, long filterPreRhs,
                long filterCmpRhs, long sinkPredPreRhs,
                long sinkPredCmpRhs, long foldInit,
                long takeLimit) {
            int sinkKind      = config & 0xF;
            int mapOp         = ((config >> 4) & 0xF) - 1;
            int filterPreOp   = ((config >> 8) & 0xF) - 1;
            int filterCmpOp   = ((config >> 12) & 0xF) - 1;
            int sinkPredPreOp = ((config >> 16) & 0xF) - 1;
            int sinkPredCmpOp = ((config >> 20) & 0xF) - 1;
            int foldOp        = ((config >> 24) & 0xF) - 1;
            boolean selfMap    = (config & (1 << 28)) != 0;
            boolean filterFirst = (config & (1 << 29)) != 0;

            int n = seq.size();
            boolean hasTake = (config & (1 << 30)) != 0;
            if (hasTake) n = (int) Math.min(n, takeLimit);
            long acc = foldInit;
            boolean found = false;
            long foundVal = 0;
            boolean reduceNeedInit = sinkKind == FLP_SINK_REDUCE;
            if (reduceNeedInit && n == 0) return flpResultReduceEmpty();
            for (int i = 0; i < n; i++) {
                Object raw = seq.get(i);
                long v = raw instanceof Long l ? l : ((Number) raw).longValue();
                if (filterFirst && filterCmpOp >= 0) {
                    long fv = filterPreOp >= 0
                            ? flpOp(filterPreOp, v, filterPreRhs) : v;
                    if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
                }
                if (mapOp >= 0) v = flpOp(mapOp, v, selfMap ? v : mapOperand);
                if (!filterFirst && filterCmpOp >= 0) {
                    long fv = filterPreOp >= 0
                            ? flpOp(filterPreOp, v, filterPreRhs) : v;
                    if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
                }
                if (sinkKind == FLP_SINK_SUM) { acc = flpOp(foldOp, acc, v); }
                else if (sinkKind == FLP_SINK_REDUCE) {
                    if (reduceNeedInit) { acc = v; reduceNeedInit = false; }
                    else { acc = flpOp(foldOp, acc, v); }
                }
                else if (sinkKind == FLP_SINK_COUNT) { acc++; }
                else if (sinkKind == FLP_SINK_ANY) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0 ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) { found = true; break; }
                    } else { found = true; break; }
                } else if (sinkKind == FLP_SINK_ALL) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0 ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (!flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) { found = true; break; }
                    }
                } else if (sinkKind == FLP_SINK_FIND) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0 ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) { found = true; foundVal = v; break; }
                    }
                } else if (sinkKind == FLP_SINK_NONE) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0 ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) { found = true; break; }
                    } else { found = true; break; }
                }
            }
            if (sinkKind == FLP_SINK_REDUCE && reduceNeedInit) return flpResultReduceEmpty();
            return flpResult(sinkKind, acc, found, foundVal);
        }

        static long flpOp(int op, long a, long b) {
            return switch (op) {
                case 0 -> a + b;   // ADD
                case 1 -> a - b;   // SUB
                case 2 -> a * b;   // MUL
                case 3 -> a / b;   // DIV
                case 4 -> a % b;   // MOD
                default -> a;
            };
        }

        static boolean flpCmp(int op, long a, long b) {
            return switch (op) {
                case 5 -> a == b;   // EQ
                case 6 -> a != b;   // NEQ
                case 7 -> a < b;    // LT
                case 8 -> a <= b;   // LE
                case 9 -> a > b;    // GT
                case 10 -> a >= b;  // GE
                default -> false;
            };
        }

        /**
         * PE-transparent result extraction. For SUM/COUNT the result is
         * just the accumulator (a long) — no reason to cross a boundary.
         * Only FIND needs @TruffleBoundary because it allocates a
         * MezzeVariant (Some/None). sinkKind is PE-constant (derived
         * from the config @ConstantOperand), so PE folds the switch and
         * the FIND branch is dead-code-eliminated for sum/count/any/all.
         */
        static Object flpResult(int sinkKind,
                long acc, boolean found, long foundVal) {
            if (sinkKind == FLP_SINK_SUM || sinkKind == FLP_SINK_COUNT) {
                return acc;
            } else if (sinkKind == FLP_SINK_REDUCE) {
                return flpResultReduceSome(acc);
            } else if (sinkKind == FLP_SINK_ANY) {
                return found;
            } else if (sinkKind == FLP_SINK_ALL) {
                return !found;
            } else if (sinkKind == FLP_SINK_NONE) {
                return !found;  // none = !any: found means a match exists → result is false
            } else if (sinkKind == FLP_SINK_FIND) {
                return flpResultFind(found, foundVal);
            }
            return acc;
        }

        @TruffleBoundary
        private static Object flpResultFind(boolean found, long foundVal) {
            MezzeVariant.TagRegistry reg = MezzeVariant.TagRegistry.getGlobal();
            int someTag = com.mezze.language.ast.BuiltinTypes.INSTANCE
                    .computeVariantTag(com.mezze.language.ast.BuiltinTypes.SOME, null);
            int noneTag = com.mezze.language.ast.BuiltinTypes.INSTANCE
                    .computeVariantTag(com.mezze.language.ast.BuiltinTypes.NONE, null);
            if (found) {
                reg.getOrCreateTag(com.mezze.language.ast.BuiltinTypes.SOME);
                MezzeRecord payload = MezzeRecord.fromFields(
                        new String[]{"val"}, new Object[]{foundVal});
                return MezzeVariant.singlePayload(reg, someTag, payload);
            } else {
                reg.getOrCreateTag(com.mezze.language.ast.BuiltinTypes.NONE);
                return MezzeVariant.noPayload(reg, noneTag);
            }
        }

        @TruffleBoundary
        static Object flpResultReduceEmpty() {
            MezzeVariant.TagRegistry reg = MezzeVariant.TagRegistry.getGlobal();
            int noneTag = com.mezze.language.ast.BuiltinTypes.INSTANCE
                    .computeVariantTag(com.mezze.language.ast.BuiltinTypes.NONE, null);
            reg.getOrCreateTag(com.mezze.language.ast.BuiltinTypes.NONE);
            return MezzeVariant.noPayload(reg, noneTag);
        }

        @TruffleBoundary
        private static Object flpResultReduceSome(long acc) {
            MezzeVariant.TagRegistry reg = MezzeVariant.TagRegistry.getGlobal();
            int someTag = com.mezze.language.ast.BuiltinTypes.INSTANCE
                    .computeVariantTag(com.mezze.language.ast.BuiltinTypes.SOME, null);
            reg.getOrCreateTag(com.mezze.language.ast.BuiltinTypes.SOME);
            MezzeRecord payload = MezzeRecord.fromFields(
                    new String[]{"val"}, new Object[]{acc});
            return MezzeVariant.singlePayload(reg, someTag, payload);
        }

        public static boolean isLongStorage(MezzeSeq<?> seq) {
            return seq.getStorage() instanceof com.mezze.datastructures.seq.LongSeqStorage;
        }
    }

    // ── Fused Long Range Pipeline: range(start, end) with inline ops, no seq allocation ──

    /**
     * Tight loop over a range [start, end) applying inline map/filter/fold
     * operations entirely in long-space.  Eliminates the seq::range
     * allocation that FusedLongPipeline requires — the loop variable IS
     * the element.
     *
     * <p>Children (in order): start, end, mapOperand, filterPreRhs,
     * filterCmpRhs, sinkPredPreRhs, sinkPredCmpRhs, foldInit, takeLimit.
     *
     * <p>Config (ConstantOperand) uses the same packing as FusedLongPipeline.
     */
    @Operation
    @ConstantOperand(type = int.class)   // config: packed ops (PE constant)
    public static final class FusedLongRangePipeline {

        @Specialization
        public static Object doRange(
                int config,
                long start, long end,
                long mapOperand, long filterPreRhs,
                long filterCmpRhs, long sinkPredPreRhs,
                long sinkPredCmpRhs, long foldInit,
                long takeLimit,
                @Bind Node node,
                @Cached InlinedLoopConditionProfile loopProfile) {
            // Unpack config — each field is PE-constant.
            CompilerAsserts.partialEvaluationConstant(config);
            int sinkKind      = config & 0xF;
            int mapOp         = ((config >> 4) & 0xF) - 1;
            int filterPreOp   = ((config >> 8) & 0xF) - 1;
            int filterCmpOp   = ((config >> 12) & 0xF) - 1;
            int sinkPredPreOp = ((config >> 16) & 0xF) - 1;
            int sinkPredCmpOp = ((config >> 20) & 0xF) - 1;
            int foldOp        = ((config >> 24) & 0xF) - 1;
            boolean selfMap    = (config & (1 << 28)) != 0;
            boolean filterFirst = (config & (1 << 29)) != 0;

            int n = (int) (end - start);

            // Take stage: clamp iteration count to takeLimit.
            boolean hasTake = (config & (1 << 30)) != 0;
            if (hasTake) n = (int) Math.min(n, takeLimit);

            // ── SIMD fast path for SUM/COUNT/REDUCE sinks on large ranges ──
            // Delegates to a @TruffleBoundary method that uses the Java
            // Vector API (jdk.incubator.vector) to process VECTOR_LENGTH
            // elements per cycle. The threshold matches VectorOps; below
            // it, the PE-optimized scalar loop wins on overhead.
            // Gated off on SVM and when jdk.incubator.vector is absent.
            if (FlpSimd.AVAILABLE
                    && n >= FlpSimd.SIMD_THRESHOLD
                    && (sinkKind == FLP_SINK_SUM || sinkKind == FLP_SINK_COUNT
                        || sinkKind == FLP_SINK_REDUCE)
                    && sinkPredCmpOp < 0) {
                return FlpSimd.flpRangeSimd(start, n, sinkKind,
                        mapOp, mapOperand, selfMap,
                        filterPreOp, filterPreRhs, filterCmpOp, filterCmpRhs,
                        foldOp, foldInit, filterFirst);
            }

            long acc = foldInit;
            boolean found = false;
            long foundVal = 0;

            // Reduce sink: init from first post-filter/map element inside the loop.
            boolean reduceNeedInit = sinkKind == FLP_SINK_REDUCE;
            if (reduceNeedInit && n == 0) return FusedLongPipeline.flpResultReduceEmpty();

            // Materialize sinks: pre-allocate array to range size (upper bound),
            // fill during loop, trim post-loop. Avoids per-element bounds checks
            // and growth copies that the generic SeqBuilder path requires.
            long[] matData = null;
            int matCount = 0;
            if (sinkKind == FLP_SINK_TO_SEQ || sinkKind == FLP_SINK_TO_LIST) {
                matData = new long[n > 0 ? n : 0];
            }

            loopProfile.profileCounted(node, n);
            for (int i = 0; loopProfile.inject(node, i < n); i++) {
                long v = start + i;

                // ── Filter-first path ──
                if (filterFirst && filterCmpOp >= 0) {
                    long fv = filterPreOp >= 0
                            ? FusedLongPipeline.flpOp(filterPreOp, v, filterPreRhs) : v;
                    if (!FusedLongPipeline.flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
                }

                // ── Map stage ──
                if (mapOp >= 0) v = FusedLongPipeline.flpOp(mapOp, v, selfMap ? v : mapOperand);

                // ── Filter-second path ──
                if (!filterFirst && filterCmpOp >= 0) {
                    long fv = filterPreOp >= 0
                            ? FusedLongPipeline.flpOp(filterPreOp, v, filterPreRhs) : v;
                    if (!FusedLongPipeline.flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
                }

                // ── Sink ──
                if (sinkKind == FLP_SINK_SUM) {
                    acc = FusedLongPipeline.flpOp(foldOp, acc, v);
                } else if (sinkKind == FLP_SINK_REDUCE) {
                    if (reduceNeedInit) { acc = v; reduceNeedInit = false; }
                    else { acc = FusedLongPipeline.flpOp(foldOp, acc, v); }
                } else if (sinkKind == FLP_SINK_COUNT) {
                    acc++;
                } else if (sinkKind == FLP_SINK_TO_SEQ || sinkKind == FLP_SINK_TO_LIST) {
                    matData[matCount++] = v;
                } else if (sinkKind == FLP_SINK_ANY) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? FusedLongPipeline.flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (FusedLongPipeline.flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; break;
                        }
                    } else { found = true; break; }
                } else if (sinkKind == FLP_SINK_ALL) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? FusedLongPipeline.flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (!FusedLongPipeline.flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; break;
                        }
                    }
                } else if (sinkKind == FLP_SINK_FIND) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? FusedLongPipeline.flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (FusedLongPipeline.flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; foundVal = v; break;
                        }
                    }
                } else if (sinkKind == FLP_SINK_NONE) {
                    if (sinkPredCmpOp >= 0) {
                        long sv = sinkPredPreOp >= 0
                                ? FusedLongPipeline.flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                        if (FusedLongPipeline.flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs)) {
                            found = true; break;
                        }
                    } else { found = true; break; }
                }
            }

            // Report loop iterations to Truffle — drives PE on SVM native.
            LoopNode.reportLoopCount(node, n);

            // ── Post-loop result ──
            if (sinkKind == FLP_SINK_REDUCE && reduceNeedInit) {
                return FusedLongPipeline.flpResultReduceEmpty();
            }
            if (sinkKind == FLP_SINK_TO_SEQ) {
                return flpResultToSeq(matData, matCount);
            } else if (sinkKind == FLP_SINK_TO_LIST) {
                return flpResultToList(matData, matCount);
            }
            return FusedLongPipeline.flpResult(sinkKind, acc, found, foundVal);
        }

        /** Materialize a long[] into a MezzeSeq with LongSeqStorage. */
        @TruffleBoundary
        private static Object flpResultToSeq(long[] data, int count) {
            if (count == 0) return MezzeSeq.empty();
            long[] trimmed = count == data.length ? data : java.util.Arrays.copyOf(data, count);
            return MezzeSeq.fromLongs(trimmed);
        }

        /** Materialize a long[] into a MezzeList. */
        @TruffleBoundary
        private static Object flpResultToList(long[] data, int count) {
            if (count == 0) return com.mezze.datastructures.list.MezzeList.empty();
            Object[] boxed = new Object[count];
            for (int i = 0; i < count; i++) boxed[i] = data[i];
            return com.mezze.datastructures.list.MezzeList.of(boxed);
        }
    }

    // ── Fused tail-loop constants ──
    // Op encoding (same as FLP): ADD=0, SUB=1, MUL=2, DIV=3, MOD=4
    // Cmp encoding (same as FLP): EQ=5, NEQ=6, LT=7, LE=8, GT=9, GE=10
    // Identity (no update): -1
    //
    // Config packing for FusedLongTailLoop:
    //   bits 0-3:   condCmpOp      (cmp op for loop condition)
    //   bit  4:     condOnP0       (1 = compare p0, 0 = compare p1)
    //   bits 5-8:   p0UpdateOp     (arith op for p0, +1 bias: 0=identity)
    //   bits 9-12:  p1UpdateOp     (arith op for p1, +1 bias: 0=identity)
    //   bits 13-14: resultIdx      (which param to return: 0=p0, 1=p1)
    //   bit  15:    p0RhsIsOtherP  (1 = p0 update rhs is p1 instead of literal)
    //   bit  16:    p1RhsIsOtherP  (1 = p1 update rhs is p0 instead of literal)

    static int fltlPack(int condCmpOp, boolean condOnP0,
                        int p0Op, int p1Op,
                        int resultIdx,
                        boolean p0RhsOther, boolean p1RhsOther) {
        return (condCmpOp & 0xF)
             | ((condOnP0 ? 1 : 0) << 4)
             | (((p0Op + 1) & 0xF) << 5)
             | (((p1Op + 1) & 0xF) << 9)
             | ((resultIdx & 0x3) << 13)
             | ((p0RhsOther ? 1 : 0) << 15)
             | ((p1RhsOther ? 1 : 0) << 16);
    }

    /**
     * Fused two-parameter long tail-loop — compiles the common pattern:
     * <pre>
     *   let f = { p0, p1 } ->
     *     if p0 cmpOp condRhs
     *       then resultParam
     *       else f { p0 = p0 op0 rhs0, p1 = p1 op1 rhs1 }
     * </pre>
     * into a single BDSL operation with a tight Java while loop.
     * Eliminates all per-iteration BDSL dispatch, frame slot reads/writes,
     * and temp-variable overhead from stlEmitParamUpdate.
     *
     * <p>Children (in order): p0Init, p1Init, condRhs, p0UpdateRhs, p1UpdateRhs.
     * Config encodes the comparison op, update ops, and result index.
     */
    @Operation
    @ConstantOperand(type = int.class)  // config: packed ops (PE constant)
    public static final class FusedLongTailLoop {

        @Specialization
        public static long doLoop(
                int config,
                long p0Init, long p1Init,
                long condRhs, long p0Rhs, long p1Rhs,
                @Bind Node node,
                @Cached InlinedLoopConditionProfile loopProfile) {
            CompilerAsserts.partialEvaluationConstant(config);
            int condCmpOp    = config & 0xF;
            boolean condOnP0 = (config & (1 << 4)) != 0;
            int p0Op         = ((config >> 5) & 0xF) - 1;
            int p1Op         = ((config >> 9) & 0xF) - 1;
            int resultIdx    = (config >> 13) & 0x3;
            boolean p0Other  = (config & (1 << 15)) != 0;
            boolean p1Other  = (config & (1 << 16)) != 0;

            long p0 = p0Init, p1 = p1Init;

            // Loop while the condition is NOT met (the condition triggers
            // the base case, so we loop while it's false).
            for (long i = 0; loopProfile.inject(node,
                    !FusedLongPipeline.flpCmp(condCmpOp,
                            condOnP0 ? p0 : p1, condRhs)); i++) {
                long newP0 = p0Op >= 0
                        ? FusedLongPipeline.flpOp(p0Op, p0, p0Other ? p1 : p0Rhs)
                        : p0;
                long newP1 = p1Op >= 0
                        ? FusedLongPipeline.flpOp(p1Op, p1, p1Other ? p0 : p1Rhs)
                        : p1;
                p0 = newP0;
                p1 = newP1;
            }

            return resultIdx == 0 ? p0 : p1;
        }
    }

    // Config packing for FusedLongTailLoop1P:
    //   bits 0-3:   condCmpOp      (cmp op for loop condition)
    //   bits 4-7:   p0UpdateOp     (arith op for p0, +1 bias: 0=identity)
    //   bit  8:     resultIsParam  (1=return p0, 0=return baseResult child)

    static int fltl1pPack(int condCmpOp, int p0Op, boolean resultIsParam) {
        return (condCmpOp & 0xF)
             | (((p0Op + 1) & 0xF) << 4)
             | ((resultIsParam ? 1 : 0) << 8);
    }

    /**
     * Fused single-parameter long tail-loop — compiles the common pattern:
     * <pre>
     *   let f = { n } ->
     *     if n cmpOp condRhs
     *       then baseResult       (literal or n)
     *       else f { n = n op rhs }
     * </pre>
     * into a single BDSL operation with a tight Java while loop.
     * Handles 1-param tail-recursive functions (e.g. countdown, collatz).
     *
     * <p>Children (in order): p0Init, condRhs, p0UpdateRhs, baseResult.
     * Config encodes the comparison op, update op, and whether the result
     * is the final param value or the base literal.
     */
    @Operation
    @ConstantOperand(type = int.class)  // config: packed ops (PE constant)
    public static final class FusedLongTailLoop1P {

        @Specialization
        public static long doLoop(
                int config,
                long p0Init,
                long condRhs, long p0Rhs,
                long baseResult,
                @Bind Node node,
                @Cached InlinedLoopConditionProfile loopProfile) {
            CompilerAsserts.partialEvaluationConstant(config);
            int condCmpOp       = config & 0xF;
            int p0Op            = ((config >> 4) & 0xF) - 1;
            boolean resultParam = (config & (1 << 8)) != 0;

            long p0 = p0Init;

            // Loop while condition is NOT met (condition triggers base case).
            for (long i = 0; loopProfile.inject(node,
                    !FusedLongPipeline.flpCmp(condCmpOp, p0, condRhs)); i++) {
                p0 = p0Op >= 0
                        ? FusedLongPipeline.flpOp(p0Op, p0, p0Rhs)
                        : p0;
            }

            return resultParam ? p0 : baseResult;
        }
    }

    /** Convert a MezzeList to Object[] for indexed iteration in fused loops.
     *  Single O(n) pass — the resulting array supports O(1) SeqGet access. */
    @Operation
    public static final class ListToArray {
        @Specialization
        public static Object[] doList(com.mezze.datastructures.list.MezzeList<?> list) {
            return list.toArray();
        }
    }

    /**
     * Convert a MezzeList of longs to a MezzeSeq backed by LongSeqStorage.
     * Used to route list sources through FusedLongPipeline for scalar-fast
     * fold/sum/count/any/all/find/to_seq/to_list. The conversion is O(n)
     * with pointer-chasing, but the subsequent FLP iteration is O(n) with
     * contiguous long[] access — net win over cons-cell traversal with
     * boxing per element.
     *
     * Falls back to the empty seq for nil.
     */
    @Operation
    public static final class ListToLongSeq {
        @Specialization
        @TruffleBoundary
        public static MezzeSeq<?> doLongList(com.mezze.datastructures.list.MezzeLongCons list) {
            return MezzeSeq.fromLongs(list.toLongArray());
        }
        @Specialization
        public static MezzeSeq<?> doNil(com.mezze.datastructures.list.MezzeNil<?> list) {
            return MezzeSeq.empty();
        }
    }

    // ===== Cons-cell traversal ops for list-source fused loops =====
    // These avoid the O(n) ListToArray conversion by walking the list
    // in place: cursor = list; while (!isEmpty(cursor)) { x = head(cursor); cursor = tail(cursor); }

    /** Test whether a MezzeList cursor is empty (Nil).
     *  Specialized on Cons → constant false for JIT devirtualization. */
    @Operation
    public static final class ListIsEmpty {
        @Specialization
        public static boolean doCons(com.mezze.datastructures.list.MezzeCons<?> list) {
            return false;
        }
        @Specialization
        public static boolean doLongCons(com.mezze.datastructures.list.MezzeLongCons list) {
            return false;
        }
        @Specialization
        public static boolean doNil(com.mezze.datastructures.list.MezzeNil<?> list) {
            return true;
        }
    }

    /** Read the head element of a non-empty MezzeList cursor.
     *  Specialized on Cons/LongCons for direct field access. */
    @Operation
    public static final class ListHead {
        @Specialization
        public static Object doCons(com.mezze.datastructures.list.MezzeCons<?> list) {
            return list.head();
        }
        @Specialization
        public static Object doLongCons(com.mezze.datastructures.list.MezzeLongCons list) {
            return list.head();
        }
    }

    /** Advance a MezzeList cursor to its tail.
     *  Specialized on Cons/LongCons for direct field access. */
    @Operation
    public static final class ListTail {
        @Specialization
        public static com.mezze.datastructures.list.MezzeList<?> doCons(com.mezze.datastructures.list.MezzeCons<?> list) {
            return list.tail();
        }
        @Specialization
        public static com.mezze.datastructures.list.MezzeList<?> doLongCons(com.mezze.datastructures.list.MezzeLongCons list) {
            return list.tail();
        }
    }

    /** Drop N elements from the head of a seq, returning the tail. */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SeqDrop {
        @Specialization
        public static MezzeSeq<?> doSeq(int n, MezzeSeq<?> seq) {
            return seq.drop(n);
        }
    }

    /**
     * Construct a {@link MezzeSeq} from a variadic list of element
     * values. Lowers {@link com.mezze.language.truffleir.MakeSeqIR}
     * for the no-spread case ({@code [1, 2, 3]}); spread elements
     * ({@code [x, ...rest]}) are Phase 3+.
     */
    @Operation
    public static final class MakeSeq {
        @Specialization
        public static MezzeSeq<Object> doMake(@Variadic Object[] elements) {
            if (elements.length == 0) {
                return MezzeSeq.empty();
            }
            return MezzeSeq.of(elements);
        }
    }

    /**
     * Emitted as the fall-through of a {@link com.mezze.language.truffleir.MatchIR}
     * whose last case is a variant tag (or otherwise refutable). Reached
     * only when every preceding case's tag mismatched — since the
     * typechecker guarantees exhaustive coverage, this should be dead
     * code at runtime. If a typechecker gap or unsound cast reaches it,
     * throw a RuntimeException rather than falling through to whatever
     * value the operand stack happens to hold.
     */
    @Operation
    public static final class NonExhaustiveMatch {
        @Specialization
        @TruffleBoundary
        @InliningCutoff
        public static Object doThrow() {
            throw new RuntimeException("Non-exhaustive match: no variant tag matched — "
                    + "typechecker exhaustiveness guarantee violated");
        }
    }

    /**
     * Build a {@link com.mezze.datastructures.seq.MezzeSeq} literal that
     * contains one or more spread elements ({@code [a, ...xs, b]}).
     *
     * <p>Constant operand {@code isSpread} is a bit-packed per-slot flag
     * — bit {@code i} set means values[i] is a MezzeSeq/Object[] whose
     * elements flatten into the result; unset means values[i] contributes
     * one element as-is. Stored as {@code long} (fits ≤63 elements) to
     * keep the constant compact; larger literals fall back to a boolean[]
     * variant not yet implemented (typechecker sizes aren't near 63).
     */
    @Operation
    @ConstantOperand(type = long.class)
    public static final class MakeSeqWithSpread {
        @Specialization
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public static MezzeSeq<Object> doMake(long isSpreadBits, @Variadic Object[] elements) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            for (int i = 0; i < elements.length; i++) {
                if (((isSpreadBits >>> i) & 1L) != 0L) {
                    Object v = elements[i];
                    if (v instanceof MezzeSeq<?> seq) {
                        for (int j = 0; j < seq.size(); j++) result.add(seq.get(j));
                    } else if (v instanceof Object[] arr) {
                        for (Object elem : arr) result.add(elem);
                    } else {
                        result.add(v);
                    }
                } else {
                    result.add(elements[i]);
                }
            }
            return MezzeSeq.of(result.toArray());
        }
    }

    // ── Seq/List builder operations for fused materializing sinks ───────

    /**
     * Create a new mutable seq builder (backed by ArrayList) for use
     * in fused ToSeq/ToList loops. Optional size-hint parameter enables
     * pre-allocation when the output size is known (e.g. range source
     * with no filter stages). Returns raw Object to satisfy Truffle's
     * BDSL type constraints (no generics).
     */
    @Operation
    public static final class NewSeqBuilder {
        @Specialization
        @SuppressWarnings("unchecked")
        public static Object doNew(long sizeHint) {
            return new java.util.ArrayList<>(sizeHint > 0 ? (int) sizeHint : 16);
        }
    }

    /**
     * Append an element to the mutable seq builder. Returns the
     * builder itself (for balanced BDSL stack).
     */
    @Operation
    public static final class SeqBuilderAdd {
        @Specialization
        @SuppressWarnings("unchecked")
        public static Object doAdd(Object builder, Object element) {
            ((java.util.ArrayList<Object>) builder).add(element);
            return builder;
        }
    }

    /**
     * Finalize the seq builder into an immutable {@link MezzeSeq}.
     * The builder should not be used after this call.
     */
    @Operation
    public static final class SeqBuilderToSeq {
        @Specialization
        @SuppressWarnings("unchecked")
        public static MezzeSeq<Object> doFinalize(Object builder) {
            var list = (java.util.ArrayList<Object>) builder;
            if (list.isEmpty()) return MezzeSeq.empty();
            return MezzeSeq.of(list.toArray());
        }
    }

    /**
     * Finalize the seq builder into a {@link com.mezze.datastructures.list.MezzeList}.
     * The builder should not be used after this call.
     */
    @Operation
    public static final class SeqBuilderToList {
        @Specialization
        @SuppressWarnings("unchecked")
        public static Object doFinalize(Object builder) {
            var list = (java.util.ArrayList<Object>) builder;
            if (list.isEmpty()) return com.mezze.datastructures.list.MezzeList.empty();
            return com.mezze.datastructures.list.MezzeList.of(list.toArray());
        }
    }

    // ── Long-specialized seq builder for fused RangeSeq→filter→to_seq ──
    //
    // The generic SeqBuilder uses ArrayList<Object>, forcing every long
    // through autoboxing. For RangeSeq sources (which produce longs), we
    // use a long[] directly — no boxing, no GC pressure, and the result
    // lands in MezzeSeq's LongSeqStorage (unboxed).

    /**
     * Create a long[] builder with initial capacity. Returns a long[2]
     * header: [count, capacity] followed by the data array stored as a
     * two-element Object[]: {long[] data, long count}.
     */
    @Operation
    public static final class NewLongSeqBuilder {
        @Specialization
        public static Object doNew(long sizeHint) {
            int cap = sizeHint > 0 ? (int) Math.min(sizeHint, Integer.MAX_VALUE - 8) : 16;
            return new long[cap + 1]; // slot 0 = count, slots 1..cap = data
        }
    }

    /**
     * Append a long to the builder. Returns the (possibly grown) builder.
     */
    @Operation
    public static final class LongSeqBuilderAdd {
        @Specialization
        public static long[] doAdd(long[] builder, long element) {
            int count = (int) builder[0];
            if (count + 1 >= builder.length) {
                int newCap = builder.length * 2;
                long[] grown = new long[newCap];
                System.arraycopy(builder, 0, grown, 0, count + 1);
                builder = grown;
            }
            builder[count + 1] = element; // data starts at index 1
            builder[0] = count + 1;
            return builder;
        }
    }

    /**
     * Finalize long builder into a MezzeSeq with LongSeqStorage.
     */
    @Operation
    public static final class LongSeqBuilderToSeq {
        @Specialization
        public static Object doFinalize(long[] builder) {
            int count = (int) builder[0];
            if (count == 0) return MezzeSeq.empty();
            long[] data = new long[count];
            System.arraycopy(builder, 1, data, 0, count);
            return MezzeSeq.fromLongs(data);
        }
    }

    /**
     * Finalize long builder into a MezzeList.
     * Converts the unboxed long[] accumulator into a boxed Object[] for
     * {@link com.mezze.datastructures.list.MezzeList}.
     */
    @Operation
    public static final class LongSeqBuilderToList {
        @Specialization
        public static Object doFinalize(long[] builder) {
            int count = (int) builder[0];
            if (count == 0) return com.mezze.datastructures.list.MezzeList.empty();
            Object[] boxed = new Object[count];
            for (int i = 0; i < count; i++) boxed[i] = builder[i + 1];
            return com.mezze.datastructures.list.MezzeList.of(boxed);
        }
    }

    /**
     * Construct a variant with no payload (e.g. {@code None}, {@code True}).
     *
     * <p>Lowers {@link com.mezze.language.truffleir.MakeVariantIR} when
     * {@code payload == null}. Tag + tagName are baked as constant
     * operands; the tag is (re)registered in the global
     * {@link TagRegistry} at operation build time so downstream match
     * ops see a consistent int→name mapping.
     */
    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = String.class)
    public static final class MakeVariantNoPayload {
        @Specialization
        public static MezzeVariant doNoPayload(int tag, String tagName,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.noPayload(registry, tag);
        }

        @TruffleBoundary
        static TagRegistry ensureRegistered(String tagName) {
            TagRegistry registry = TagRegistry.getGlobal();
            registry.getOrCreateTag(tagName);
            return registry;
        }
    }

    /**
     * Construct a variant with a single payload value (e.g.
     * {@code Some(x)}, {@code Ok(v)}, {@code Err(e)}).
     *
     * <p>Lowers {@link com.mezze.language.truffleir.MakeVariantIR}
     * when {@code payload != null}. Payload comes from the operand
     * stack; tag + tagName are baked as constant operands.
     */
    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = String.class)
    public static final class MakeVariantSinglePayload {
        @Specialization
        public static MezzeVariant doLong(int tag, String tagName, long payload,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.singleLongPayload(registry, tag, payload);
        }

        @Specialization
        public static MezzeVariant doDouble(int tag, String tagName, double payload,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.singleDoublePayload(registry, tag, payload);
        }

        @Specialization(replaces = {"doLong", "doDouble"})
        public static MezzeVariant doObject(int tag, String tagName, Object payload,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.singlePayload(registry, tag, payload);
        }

        @TruffleBoundary
        static TagRegistry ensureRegistered(String tagName) {
            TagRegistry registry = TagRegistry.getGlobal();
            registry.getOrCreateTag(tagName);
            return registry;
        }
    }

    /**
     * Fused single-field variant creation: skips {@link MakeRecord} for
     * 1-field variant payloads and stores the raw value directly in the
     * variant's typed payload slot.  The extra {@code fieldName} constant
     * operand lets {@link MezzeVariant#getPayloadValue()} lazily
     * materialize a wrapping record for cold-path consumers.
     *
     * <p>BDSL match-side uses {@link ReadVariantField} which reads the
     * raw typed payload directly — no materialization overhead.
     */
    @Operation
    @ConstantOperand(type = int.class)       // tag
    @ConstantOperand(type = String.class)    // tagName
    @ConstantOperand(type = String.class)    // fieldName
    public static final class MakeVariantSingleFieldPayload {
        @Specialization
        public static MezzeVariant doLong(int tag, String tagName, String fieldName,
                long payload,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.singleLongPayloadFused(registry, tag, payload, fieldName);
        }

        @Specialization
        public static MezzeVariant doDouble(int tag, String tagName, String fieldName,
                double payload,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.singleDoublePayloadFused(registry, tag, payload, fieldName);
        }

        @Specialization(replaces = {"doLong", "doDouble"})
        public static MezzeVariant doObject(int tag, String tagName, String fieldName,
                Object payload,
                @Cached(value = "ensureRegistered(tagName)", allowUncached = true)
                    TagRegistry registry) {
            return MezzeVariant.singlePayloadFused(registry, tag, payload, fieldName);
        }

        @TruffleBoundary
        static TagRegistry ensureRegistered(String tagName) {
            TagRegistry registry = TagRegistry.getGlobal();
            registry.getOrCreateTag(tagName);
            return registry;
        }
    }

    /**
     * Structural / primitive equality between two operands. Used by
     * literal patterns in match: {@code match x is 1 -> ... | _ -> ...}
     * lowers to a {@code PrimEquals(scrutinee, literal)} inside a
     * {@code Conditional} — same shape as variant-tag matching.
     *
     * <p>Specializes on {@code long==long}, {@code double==double},
     * {@code boolean==boolean}, and falls through to
     * {@link java.util.Objects#equals(Object, Object)} for reference
     * comparison (strings, chars, unit).
     */
    @Operation
    public static final class PrimEquals {
        @Specialization
        public static boolean doLong(long left, long right) {
            return left == right;
        }

        @Specialization
        public static boolean doDouble(double left, double right) {
            return left == right;
        }

        @Specialization
        public static boolean doBoolean(boolean left, boolean right) {
            return left == right;
        }

        /**
         * Object-eq fallback for the primitive-eq operation. `@TruffleBoundary`
         * gates PE at this method, which is required for
         * {@code mez build --native}: without it, the SubstrateVM Truffle
         * feature's blocklist check follows the reachability chain
         * {@code Objects.equals → left.equals(right)} into user-defined
         * .equals implementations ({@link com.mezze.datastructures.set.MezzeSet},
         * {@link java.util.List}, etc.) that transitively reach blocklisted
         * methods like identity hashCode and HashMap.hashCode. Build fails
         * with "Blocklisted methods are reachable for runtime compilation".
         *
         * The eq compare is only used by literal-pattern match (int/str/char/unit),
         * so the object-fallback path is cold — the boundary has zero perf
         * cost on the hot paths (doLong/doDouble/doBoolean). See #402.
         */
        @Specialization(replaces = {"doLong", "doDouble", "doBoolean"})
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        @InliningCutoff
        public static boolean doObject(Object left, Object right) {
            return java.util.Objects.equals(left, right);
        }
    }

    /**
     * True iff the operand is non-null. Used by MatchStringInterp
     * emit (#360) to test whether the match succeeded (returns
     * String[] on match, null on mismatch).
     */
    @Operation
    public static final class PrimNotNull {
        @Specialization
        public static boolean doAny(Object value) {
            return value != null;
        }
    }

    /**
     * Index a {@code String[]} by a compile-time constant index. Used
     * by MatchStringInterp emit (#360) to pull captured substrings
     * out of the returned captures array into per-slot locals.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class ArrayGetString {
        @Specialization
        public static String doGet(int index, String[] array) {
            return array[index];
        }
    }

    /**
     * Compare a variant's tag against a compile-time int tag. Returns
     * true iff the operand is a {@link MezzeVariant} with matching tag.
     *
     * <p>Used to lower {@link com.mezze.language.truffleir.MatchIR}
     * for {@link com.mezze.language.truffleir.PatternIR.Variant}
     * cases — the compiler chains one of these per case guarded by an
     * {@code IfThenElse}. Wildcard cases become the final {@code else}
     * branch.
     */
    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = String.class)
    public static final class VariantTagEquals {
        @Specialization
        public static boolean doVariant(int expectedTag, String tagName, MezzeVariant variant) {
            return variant.getTag() == expectedTag;
        }

        // #384: mirror AST's MatchNode.executeListAsVariantMatchFast — native
        // MezzeList optimizations (MezzeLongCons, MezzeDoubleCons, MezzeCons,
        // MezzeNil) are pattern-matched AS the "Nil"/"Cons" variants of the
        // list ADT. Without these specializations, matching a native list
        // against `Nil -> ...` or `Cons { head, tail } -> ...` would always
        // return false and fall through to a non-exhaustive-match crash.
        @Specialization
        public static boolean doList(int expectedTag, String tagName,
                                     com.mezze.datastructures.list.MezzeList<?> list) {
            // Match on the expected TAG int (which the pattern already carries),
            // not the constructor-name spelling — consistent with doVariant above
            // and the SOME_TAG/NONE_TAG tag-based path. Tags are name-deterministic,
            // so expectedTag for a `Nil`/`Cons` pattern equals NIL_TAG/CONS_TAG.
            if (expectedTag == MezzeVariant.NIL_TAG) return list.isEmpty();
            if (expectedTag == MezzeVariant.CONS_TAG) return !list.isEmpty();
            return false;
        }

        @Specialization(guards = {"!isVariant(other)", "!isList(other)"})
        public static boolean doNonVariant(int expectedTag, String tagName, Object other) {
            return false;
        }

        protected static boolean isVariant(Object obj) {
            return obj instanceof MezzeVariant;
        }

        protected static boolean isList(Object obj) {
            return obj instanceof com.mezze.datastructures.list.MezzeList<?>;
        }
    }

    /**
     * Read the single payload value from a {@link MezzeVariant}.
     * Used for pattern-variant bindings that capture the payload:
     * {@code Some(x) -> ...} lowers to a WriteLocal on {@code x} sourced
     * from {@code UnwrapSinglePayload(scrutinee)}.
     */
    @Operation
    public static final class UnwrapSinglePayload {
        @Specialization
        public static Object doVariant(MezzeVariant variant) {
            return variant.getPayloadValue();
        }

        // #384: native MezzeList Cons acts as a variant with payload
        // {head, tail}. Called after a successful VariantTagEquals("Cons")
        // — so this only runs on non-empty lists. Materialize the payload
        // record; the caller's ReadField "head" / "tail" pulls the parts.
        @com.oracle.truffle.api.dsl.Specialization
        public static Object doList(com.mezze.datastructures.list.MezzeList<?> list) {
            return com.mezze.interpreter.values.MezzeRecord.fromFields(
                    LIST_CONS_FIELDS,
                    new Object[]{list.get(0), list.tail()});
        }
    }

    private static final String[] LIST_CONS_FIELDS = new String[]{"head", "tail"};

    /**
     * Fused single-field variant payload extraction. Combines
     * {@link UnwrapSinglePayload} + {@link ReadFieldByName} into one
     * operation for patterns like {@code Ok { val } -> val}.
     *
     * <p>Handles two payload layouts:
     * <ul>
     *   <li><b>Record payload</b>: legacy path — the variant wraps a
     *       MezzeRecord, extract the named field.</li>
     *   <li><b>Direct value</b>: optimized path — the variant stores
     *       the raw value (long, double, object) without a record
     *       wrapper; return it directly.</li>
     * </ul>
     *
     * <p>Lowers single-field {@link com.mezze.language.truffleir.PatternIR.Variant}
     * in the BytecodeCompiler, paired with the fused MakeVariantSinglePayload
     * that skips MakeRecord for 1-field payloads.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class ReadVariantField {
        @Specialization(guards = "isLongPayload(variant)")
        public static long doLong(String fieldName, MezzeVariant variant) {
            return variant.getLongPayload();
        }

        @Specialization(guards = "isDoublePayload(variant)")
        public static double doDouble(String fieldName, MezzeVariant variant) {
            return variant.getDoublePayload();
        }

        @Specialization(replaces = {"doLong", "doDouble"})
        public static Object doGeneric(String fieldName, MezzeVariant variant) {
            Object payload = variant.getRawPayloadValue();
            if (variant.isFused()) {
                // Fused: the raw payload IS the field value directly —
                // no wrapper record to dig into, even when the value
                // itself happens to be a MezzeRecord with a same-named
                // field (e.g. Ok { val = { val = 42 } }).
                return payload;
            }
            // Non-fused: payload is a wrapper MezzeRecord, extract the
            // named field from it.
            if (payload instanceof MezzeRecord record) {
                int idx = record.getFieldIndex(fieldName);
                if (idx >= 0) return record.getField(idx);
            }
            return payload;
        }

        static boolean isLongPayload(MezzeVariant v) {
            return v.getPayloadType() == MezzeVariant.PayloadType.LONG;
        }

        static boolean isDoublePayload(MezzeVariant v) {
            return v.getPayloadType() == MezzeVariant.PayloadType.DOUBLE;
        }
    }

    /**
     * Extract a closure value from a captured {@link MezzeFunction}.
     *
     * <p>Used by the captured-lambda devirtualization: when a function
     * captures a lambda (e.g. {@code let go = {…} -> …mul{x}…} where
     * {@code mul} is captured), and we devirtualize the call to use
     * {@code CallDirect} instead of {@code CallIndirect}, we need to
     * extract the captured lambda's own closure values to pass as
     * arguments to the inner target.
     *
     * <p>The {@code closureValueIndex} is a {@code @ConstantOperand}
     * (PE-visible), so the array index is constant-folded. The child
     * evaluates to the {@link MezzeFunction} holding the closure values.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class ReadClosureValue {
        @Specialization
        public static Object doRead(int closureValueIndex, MezzeFunction fn) {
            return fn.getClosureValues()[closureValueIndex];
        }
    }

    /**
     * String-interpolation pattern match: try to match {@code scrutinee}
     * (a Str) against a template composed of alternating literal and
     * capture parts (encoded via {@code partTypes} + {@code literals}
     * arrays, baked as compile-time constants). On match, returns the
     * captured substrings as a {@code String[]}; on mismatch, returns
     * {@code null}. Caller emits {@code beginConditional} with a null
     * check on the result to control fallthrough to the next case.
     *
     * <p>Mirrors AST's {@code MatchBranchNode.matchStringPattern}
     * (interpreter/src/main/java/com/mezze/interpreter/nodes/variant/MatchBranchNode.java:886).
     * {@code partTypes[i] == 0} → literal at {@code literals[i]},
     * {@code partTypes[i] == 1} → capture (literal slot is null).
     *
     * <p>Lowers {@link com.mezze.language.truffleir.PatternIR.StringInterp}.
     */
    @Operation
    @ConstantOperand(type = int[].class)
    @ConstantOperand(type = String[].class)
    public static final class MatchStringInterp {
        @Specialization
        @TruffleBoundary
        public static Object doMatch(int[] partTypes, String[] literals, Object scrutinee) {
            if (!(scrutinee instanceof String s)) return null;
            int captureCount = 0;
            for (int t : partTypes) if (t == 1) captureCount++;
            String[] captures = new String[captureCount];
            if (matchStringPattern(s, 0, 0, partTypes, literals, captures, 0)) {
                return captures;
            }
            return null;
        }

        private static boolean matchStringPattern(
                String input, int inputPos, int partIndex,
                int[] partTypes, String[] literals,
                String[] captures, int captureIndex) {
            if (partIndex >= partTypes.length) {
                return inputPos == input.length();
            }
            int partType = partTypes[partIndex];
            if (partType == 0) {
                String lit = literals[partIndex];
                int len = lit.length();
                if (inputPos + len > input.length()) return false;
                if (!input.regionMatches(inputPos, lit, 0, len)) return false;
                return matchStringPattern(input, inputPos + len, partIndex + 1,
                        partTypes, literals, captures, captureIndex);
            }
            // CAPTURE
            if (partIndex + 1 >= partTypes.length) {
                captures[captureIndex] = input.substring(inputPos);
                return true;
            }
            int nextPartType = partTypes[partIndex + 1];
            if (nextPartType == 0) {
                String nextLit = literals[partIndex + 1];
                int searchPos = inputPos;
                while (true) {
                    int litPos = input.indexOf(nextLit, searchPos);
                    if (litPos == -1) return false;
                    String saved = captures[captureIndex];
                    captures[captureIndex] = input.substring(inputPos, litPos);
                    if (matchStringPattern(input, litPos, partIndex + 1,
                            partTypes, literals, captures, captureIndex + 1)) {
                        return true;
                    }
                    captures[captureIndex] = saved;
                    searchPos = litPos + 1;
                }
            } else {
                // Adjacent captures — try every split.
                for (int splitPos = inputPos; splitPos <= input.length(); splitPos++) {
                    String saved = captures[captureIndex];
                    captures[captureIndex] = input.substring(inputPos, splitPos);
                    if (matchStringPattern(input, splitPos, partIndex + 1,
                            partTypes, literals, captures, captureIndex + 1)) {
                        return true;
                    }
                    captures[captureIndex] = saved;
                }
                return false;
            }
        }
    }

    /**
     * Effect perform-op call: pop the argument value and delegate to a
     * cached {@link com.mezze.interpreter.nodes.effect.OpCallNode} via
     * {@code executeWith}. Chain-dispatch is the only path; on no
     * handler in scope the op is wrapped in a MezzeSuspend thunk.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.PerformOpIR} (default
     * path). The Mut fast path uses a separate op below.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    @ConstantOperand(type = String.class)
    public static final class Perform {
        @Specialization
        public static Object doPerform(
                Hash effectHash,
                String opName,
                Object arg,
                @Cached(value = "createOpCall(opName, effectHash)", allowUncached = true)
                    com.mezze.interpreter.nodes.effect.OpCallNode opCall) {
            return opCall.executeWith(arg);
        }

        /** `@TruffleBoundary` — see PerformMutMutateAct.createOpCall. #402. */
        @TruffleBoundary
        static com.mezze.interpreter.nodes.effect.OpCallNode createOpCall(
                String opName, Hash effectHash) {
            return com.mezze.interpreter.nodes.effect.OpCallNode.create(
                    opName, effectHash,
                    new com.mezze.interpreter.nodes.literal.UnitLiteralNode());
        }
    }

    /**
     * Path D perform-op: pop the arg and dispatch directly against the
     * current thread's handler chain via {@link YieldDispatch#dispatch}.
     * No fiber, no Loom continuation, no reflection — the dispatch pushes
     * a lightweight per-thread dispatch-only fiber with resume/abort
     * handles so existing {@code EffectBindingIR} reads in the impl body
     * work unchanged. See {@link YieldDispatch} for semantics
     * (including the fall-through-only resume caveat) and
     * docs/perf/bdsl-effects-path-d-2026-07.md for the design.
     *
     * <p>Emitted at every {@link com.mezze.language.truffleir.PerformOpIR}
     * site when {@link MezzeLanguage#BDSL_YIELD_ENABLED} is on.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    @ConstantOperand(type = String.class)
    public static final class PerformD {
        // Phase 5 — cached fast-path: resolve handler + op field index
        // ONCE, cache with a handler-chain-stable Assumption. When the
        // assumption holds (no push/pop between calls — i.e. inside a
        // fused loop whose lambdas don't install handlers), the JIT
        // inlines through the op dispatch with zero ThreadLocal probes.
        //
        // On assumption invalidation (handler pushed/popped), this
        // specialization deactivates. The DSL re-instantiates it on the
        // next call with a fresh assumption from the CyclicAssumption,
        // re-resolving the handler.
        @Specialization(assumptions = "effectStable",
                        guards = {"cachedHandler != null", "cachedTarget != null"},
                        excludeForUncached = true)
        public static Object doCached(Hash effectHash, String opName, Object arg,
                @Cached("resolveHandler(effectHash)") MezzeRecord cachedHandler,
                @Cached("resolveFieldIndex(cachedHandler, opName)") int cachedFieldIndex,
                @Cached("resolveCallTarget(cachedHandler, cachedFieldIndex)") CallTarget cachedTarget,
                @Cached("resolveClosureValues(cachedHandler, cachedFieldIndex)") Object[] cachedCV,
                @Cached("getEffectStableAssumption(effectHash)") com.oracle.truffle.api.Assumption effectStable,
                @Cached("createInlined(cachedTarget)") DirectCallNode callNode) {
            // Zero-cost effect dispatch: no dispatch fiber, no
            // @TruffleBoundary crossings on the normal path. When the
            // handler chain is stable (assumption holds), the handler,
            // call target, and closure values are all compilation
            // constants — Graal PE inlines through the handler's op
            // function. EffectBindingNode returns the fall-through
            // resume/abort singletons when no dispatch fiber is set,
            // so handlers that read resume/abort still work correctly.
            // This eliminates 4 @TruffleBoundary crossings per effect
            // op-call (acquireDispatchOnly, enterDispatch, exitDispatch,
            // releaseDispatchOnly) that existed solely to shuttle
            // the same global singletons to EffectBindingNode.
            Object[] opArgs = new Object[cachedCV.length + 1];
            System.arraycopy(cachedCV, 0, opArgs, 0, cachedCV.length);
            opArgs[cachedCV.length] = arg;
            try {
                return callNode.call(opArgs);
            } catch (com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
                if (com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.insideBdslHandle()) {
                    throw abort;
                }
                throw new com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException(
                    new com.mezze.interpreter.nodes.continuation.OpCall(opName, arg, effectHash));
            }
        }

        // Fallback — full chain dispatch through YieldDispatch (with
        // @TruffleBoundary). Handles: no handler in scope, assumption
        // invalidated, handler-shape mismatch.
        @Specialization
        public static Object doGeneric(Hash effectHash, String opName, Object arg,
                                       @Cached(value = "initFieldIndex()", allowUncached = true, dimensions = 1)
                                       int[] fieldIndexBox) {
            return YieldDispatch.dispatch(effectHash, opName, arg, fieldIndexBox);
        }

        static int[] initFieldIndex() {
            return new int[] { -1 };
        }

        @TruffleBoundary
        static MezzeRecord resolveHandler(Hash effectHash) {
            return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .peekHandlerForEffect(effectHash);
        }

        @TruffleBoundary
        static com.oracle.truffle.api.Assumption getEffectStableAssumption(Hash effectHash) {
            return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .getEffectStableAssumption(effectHash);
        }

        static int resolveFieldIndex(MezzeRecord handler, String opName) {
            return handler.getFieldIndex(opName);
        }

        static CallTarget resolveCallTarget(MezzeRecord handler, int fieldIndex) {
            if (fieldIndex < 0) return null;
            Object val = handler.getField(fieldIndex);
            if (val instanceof MezzeFunction fn) return fn.getCallTarget();
            return null;
        }

        static Object[] resolveClosureValues(MezzeRecord handler, int fieldIndex) {
            if (fieldIndex < 0) return new Object[0];
            Object val = handler.getField(fieldIndex);
            if (val instanceof MezzeFunction fn) return fn.getClosureValues();
            return new Object[0];
        }

        static DirectCallNode createInlined(CallTarget target) {
            DirectCallNode n = DirectCallNode.create(target);
            n.forceInlining();
            return n;
        }
    }

    /**
     * Simple push: non-scoped effects (13 of 19 stdlib effects).
     * No scope-hook check, no scope allocation — just handler + effectHash
     * onto the flat array stack. Zero heap allocation per push.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class PushHandlerChainSimple {
        @TruffleBoundary
        @Specialization
        public static void doPush(Hash effectHash, Object handler) {
            if (!(handler instanceof MezzeRecord rec)) {
                throw new RuntimeException(
                    "Handler must be a record, got: "
                    + (handler == null ? "null" : handler.getClass().getSimpleName()));
            }
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .pushSimple(rec, effectHash);
        }
    }

    /**
     * Scoped push: effects with initialize_scope / finalize_scope
     * (Random, STM, Async, Parallel, HTTP, Workflow).
     * Calls initialize_scope, installs scope in context, stores
     * scope+prevScope on the merged stack for the paired pop.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class PushHandlerChainScoped {
        @TruffleBoundary
        @Specialization
        public static void doPush(Hash effectHash, Object handler, @Bind Node node) {
            if (!(handler instanceof MezzeRecord rec)) {
                throw new RuntimeException(
                    "Handler must be a record, got: "
                    + (handler == null ? "null" : handler.getClass().getSimpleName()));
            }
            Object initFn = rec.getField(
                com.mezze.language.ast.ScopedEffectConstants.INITIALIZE_SCOPE);
            Object scope = null;
            Object prevScope = null;
            if (initFn instanceof MezzeFunction fn) {
                scope = D8ScopeHooks.invokeInitializeScope(fn);
                prevScope = D8ScopeHooks.installScope(node, scope);
            }
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .pushScoped(rec, effectHash, scope, prevScope);
        }
    }

    /**
     * Simple pop: non-scoped effects. Just decrement depth and null
     * the slot. No scope cleanup, no return value, no allocation.
     * Emitted twice per Handle (otherwise + catch branches).
     */
    @Operation
    public static final class PopHandlerChainSimple {
        @TruffleBoundary
        @Specialization
        public static void doPop() {
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.popSimple();
        }
    }

    /**
     * Scoped pop: effects with finalize_scope. Pops the stack,
     * invokes finalize_scope with the stored scope value, then
     * restores the previous context scope.
     */
    @Operation
    public static final class PopHandlerChainScoped {
        @TruffleBoundary
        @Specialization
        public static void doPop(@Bind Node node) {
            var result = com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.popScoped();
            if (result.handler != null) {
                Object finFn = result.handler.getField(
                    com.mezze.language.ast.ScopedEffectConstants.FINALIZE_SCOPE);
                if (finFn instanceof MezzeFunction fn) {
                    D8ScopeHooks.invokeFinalizeScope(fn, result.scope);
                }
                D8ScopeHooks.restoreScope(node, result.prevScope);
            }
        }
    }

    /**
     * Legacy PushHandlerChain — kept for backwards compatibility with
     * any pre-compiled bytecode that references the old operation name.
     * Delegates to the appropriate simple/scoped variant at runtime.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class PushHandlerChain {
        @TruffleBoundary
        @Specialization
        public static void doPush(Hash effectHash, Object handler, @Bind Node node) {
            if (!(handler instanceof MezzeRecord rec)) {
                throw new RuntimeException(
                    "Handler must be a record, got: "
                    + (handler == null ? "null" : handler.getClass().getSimpleName()));
            }
            Object initFn = rec.getField(
                com.mezze.language.ast.ScopedEffectConstants.INITIALIZE_SCOPE);
            Object scope = null;
            Object prevScope = null;
            if (initFn instanceof MezzeFunction fn) {
                scope = D8ScopeHooks.invokeInitializeScope(fn);
                prevScope = D8ScopeHooks.installScope(node, scope);
            }
            com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .pushHandlerAndEnterHandle(rec, effectHash, scope, prevScope);
        }
    }

    /**
     * Legacy PopHandlerChain — kept for backwards compatibility.
     */
    @Operation
    public static final class PopHandlerChain {
        @TruffleBoundary
        @Specialization
        public static void doPop(@Bind Node node) {
            var result = com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .popHandlerAndExitHandle();
            if (result.scopeFrame != null && result.handler != null) {
                Object finFn = result.handler.getField(
                    com.mezze.language.ast.ScopedEffectConstants.FINALIZE_SCOPE);
                if (finFn instanceof MezzeFunction fn) {
                    D8ScopeHooks.invokeFinalizeScope(fn, result.scopeFrame.scope);
                }
                D8ScopeHooks.restoreScope(node, result.scopeFrame.prevScope);
            }
        }
    }

    /**
     * Path D8: look up the pre-constructed handler record for a
     * non-parameterized performer by content hash. Mirrors
     * {@code HandlerLookupNode.lookupInContext} — reads from
     * {@code MezzeContext.handlersByHash}, throws loudly on miss.
     *
     * <p>Zero stack input; produces the handler record.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class LookupHandlerByHash {
        /** `@TruffleBoundary` — MezzeContext.lookupHandler dispatches through
         *  a hash-keyed map + throws a String-formatted RuntimeException on miss;
         *  both reach blocklisted methods under SVM. Handler lookup happens
         *  once per handle-block install, not per op-call. #402. */
        @Specialization
        @TruffleBoundary
        public static Object doLookup(Hash contentHash, @Bind Node node) {
            com.mezze.interpreter.runtime.MezzeContext ctx =
                com.mezze.interpreter.runtime.MezzeContext.get(node);
            Object handler = ctx.lookupHandler(contentHash);
            if (!(handler instanceof MezzeRecord)) {
                throw new RuntimeException(
                    "Handler not found for: " + contentHash.getHex());
            }
            return handler;
        }
    }

    /**
     * Path D8: look up a parameterized performer's handler record by
     * calling its factory CallTarget with the user-provided params record.
     * Mirrors {@code ParameterizedHandlerLookupNode.lookupInContextWithParams}.
     *
     * <p>Consumes params record from stack; produces the handler record.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class LookupParameterizedHandlerByHash {
        @TruffleBoundary
        @Specialization
        public static Object doLookup(Hash contentHash, Object params, @Bind Node node) {
            var lang = MezzeLanguage.get(node);
            var ct = lang.getCachedStdlibCallTarget(contentHash);
            if (ct != null) {
                MezzeFunction handlerFn = MezzeFunction.create(ct, contentHash.getHex());
                Object[] closure = handlerFn.getClosureValues();
                Object[] callArgs = new Object[closure.length + 1];
                System.arraycopy(closure, 0, callArgs, 0, closure.length);
                callArgs[closure.length] = params;
                Object result = handlerFn.getCallTarget().call(callArgs);
                if (result instanceof MezzeFunction innerFn) {
                    Object[] innerClosure = innerFn.getClosureValues();
                    Object[] innerCallArgs = new Object[innerClosure.length + 1];
                    System.arraycopy(innerClosure, 0, innerCallArgs, 0, innerClosure.length);
                    innerCallArgs[innerClosure.length] = params;
                    result = innerFn.getCallTarget().call(innerCallArgs);
                }
                if (result instanceof MezzeRecord) return result;
            }
            com.mezze.interpreter.runtime.MezzeContext ctx =
                com.mezze.interpreter.runtime.MezzeContext.get(node);
            Object record = ctx.lookupHandler(contentHash);
            if (record instanceof MezzeRecord) return record;
            throw new RuntimeException(
                "Parameterized performer factory not found for: " + contentHash.getHex());
        }
    }

    /**
     * Path E (#336) auto-force: consumes the top of the operand stack.
     * If it's a MezzeSuspend, force it under the current handler chain
     * (whatever handlers are pushed on this thread's chain right now).
     * Otherwise pass through unchanged.
     *
     * <p>Emitted at the OUTERMOST Handle of a nested `perform A, B, C in body`
     * chain when the outer Handle statically fully-discharges the effect
     * row. If body returned any MezzeSuspend (legacy single-op from a
     * top-level `let` producing a lazy effect, or Path E from an inner
     * nested Handle that deferred), force it here so callers see a ground
     * value.
     *
     * <p>Chain state is read live from HandlerChain, so this op picks up
     * whatever the enclosing BDSL PushHandlerChain sequences have installed.
     */
    @Operation
    public static final class AutoForceIfSuspended {
        @TruffleBoundary
        @Specialization
        public static Object doForce(Object value) {
            if (!(value instanceof com.mezze.interpreter.values.MezzeSuspend s)) {
                return value;
            }
            if (s.isPathE()) {
                // Path E full-body continuation. Only force if the current
                // thread's live chain can discharge at least one of the
                // unhandled effects — otherwise re-execution just re-suspends
                // recursively (potential infinite loop) or leaks a wrong
                // value. When none of our chain matches, pass the Suspend
                // through unforced so the outer caller (which may install
                // a wider chain later) sees a still-forceable lazy value.
                // This mirrors legacy shape's "return unforced" fallback
                // and unblocks migrating OpCallNode/YieldDispatch to
                // Path E — see Phase 3.5 audit.
                if (canDischargeAny(s)) {
                    return s.force(com.oracle.truffle.api.nodes.IndirectCallNode.getUncached());
                }
                return value;
            }
            // Legacy MezzeThunk shape — return unforced (Phase 3.5 migrates
            // remaining legacy construction sites; once done this branch
            // becomes dead code alongside MezzeThunk itself).
            return value;
        }

        /** True when at least one effect this Suspend needs is present on
         *  the current thread's handler chain. Cheap: walk the chain once,
         *  test each entry's effectHash against the Suspend's unhandled set.
         */
        private static boolean canDischargeAny(com.mezze.interpreter.values.MezzeSuspend s) {
            java.util.Set<com.mezze.language.typedast.Hash> needed = s.getUnhandledEffects();
            if (needed.isEmpty()) {
                // Ground value wrapped as a MezzeSuspend (rare). Force to unwrap.
                return true;
            }
            com.mezze.interpreter.nodes.continuation.HandlerChainEntry[] chain =
                com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.snapshotHandlerChainEntries();
            for (com.mezze.interpreter.nodes.continuation.HandlerChainEntry entry : chain) {
                com.mezze.language.typedast.Hash eff = entry.effectHash();
                if (eff != null && needed.contains(eff)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Path D8: inside the catch branch of a Handle's TryCatchOtherwise,
     * unwrap an {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException}
     * to its carried value. Any other exception is rethrown so it can
     * propagate to the enclosing handler or the caller.
     *
     * <p>Consumes the exception (from {@code LoadException}), produces the
     * aborted value.
     */
    @Operation
    public static final class UnwrapAbortValue {
        /**
         * `@TruffleBoundary` — {@code throw new RuntimeException((Throwable) exception)}
         * (the non-abort fallthrough) drags the entire Throwable-init closure
         * (getMessage, Formatter.format, ...) into PE reachability, which
         * hits ~100 blocklisted methods (java.util.List.of, HashMap.remove,
         * Sched.sched_yield, StringConcatHelper, ...). The boundary cuts
         * that closure at this method: PE treats doUnwrap as opaque, so
         * only the direct callees matter. The boundary is free — the
         * common path (abort → return value) is one branch, and rethrow is
         * a cold error path. See #402.
         */
        @Specialization
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public static Object doUnwrap(Object exception) {
            if (exception instanceof com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
                return abort.value;
            }
            // Not an abort — rethrow. Wrap in RuntimeException only if the
            // exception isn't already unchecked (it always is in practice —
            // BDSL exceptions are all Truffle/Runtime — but the compiler
            // doesn't know that at this signature).
            if (exception instanceof RuntimeException re) throw re;
            if (exception instanceof Error err) throw err;
            throw new RuntimeException((Throwable) exception);
        }
    }

    /**
     * #361 — Truffle exception carrying a {@link com.mezze.interpreter.values.MezzeSuspend}
     * escaping a mid-block non-tail statement. Under Path E semantics
     * (LAZY_EFFECTS on), an unhandled effect in the middle of a do-block
     * must propagate as MezzeSuspend so the outer handler can compose
     * it. AST's {@link com.mezze.interpreter.nodes.control.BlockNode}
     * detects this by inspecting each stmt's return value; BDSL discards
     * mid-block stmt values implicitly, so we insert a
     * {@link CheckSuspendedThrow} after each non-tail Expr stmt that
     * could yield suspended. If it fires, we unwind via this exception
     * to the outer TryCatch wrapping the function body (see
     * BytecodeCompiler emit at beginRoot), which returns the suspended
     * value as the fn's result.
     *
     * <p>Extends AbstractTruffleException so beginTryCatch catches it.
     */
    public static final class SuspendedInBlockException
            extends com.oracle.truffle.api.exception.AbstractTruffleException {
        private static final long serialVersionUID = 1L;
        public final com.mezze.interpreter.values.MezzeSuspend suspended;
        public SuspendedInBlockException(com.mezze.interpreter.values.MezzeSuspend s) {
            this.suspended = s;
        }
    }

    /**
     * #361 — Check top-of-stack for MezzeSuspend and either throw
     * {@link SuspendedInBlockException} or drop the value. Emitted by
     * {@link BytecodeCompiler} after every non-tail {@code StmtIR.Expr}
     * in a BlockIR whose result could yield suspended (Perform, or
     * transitive call whose callee could Perform).
     *
     * <p>Void return — matches the "bare expr as statement" slot in
     * a BDSL block (implicit-pop shape).
     */
    @Operation
    public static final class CheckSuspendedThrow {
        @Specialization
        public static void doCheck(Object value) {
            if (value instanceof com.mezze.interpreter.values.MezzeSuspend susp) {
                throw new SuspendedInBlockException(susp);
            }
            // Non-suspended: drop by returning (void).
        }
    }

    /**
     * #361 — Value-preserving suspend check for inline positions like
     * interpolated-string argument slots. Takes 1 input, returns it
     * unchanged (no drop) but throws {@link SuspendedInBlockException}
     * if it's a {@link com.mezze.interpreter.values.MezzeSuspend}.
     *
     * <p>Contrast with {@link CheckSuspendedThrow} which is void-return
     * (drops the value) and used for bare-Expr slots in a Block.
     * PassThrough is used when we need to keep the value on the stack
     * for a downstream op that consumes it, e.g. a per-part suspend
     * check on an {@code InterpolateString} argument.
     */
    @Operation
    public static final class CheckSuspendedPassThrough {
        @Specialization
        public static Object doCheck(Object value) {
            if (value instanceof com.mezze.interpreter.values.MezzeSuspend susp) {
                throw new SuspendedInBlockException(susp);
            }
            return value;
        }
    }

    /**
     * #361 — Extract the MezzeSuspend from a caught
     * {@link SuspendedInBlockException} on the exception stack. Consumes
     * the exception (from {@code LoadException}), produces the
     * suspended value. Used inside the outer function-root TryCatch.
     *
     * <p>Rethrows anything else — the outer catch is broad
     * (AbstractTruffleException) so we must let non-suspended exceptions
     * escape to the caller.
     */
    @Operation
    public static final class UnwrapSuspendedInBlock {
        /** `@TruffleBoundary` — same rationale as {@link UnwrapAbortValue#doUnwrap}:
         *  the rethrow through {@code new RuntimeException(Throwable)} makes PE
         *  reach the full Throwable init closure (Formatter, List.of, ...) and
         *  hits ~150 blocklisted methods. Cold path — boundary is free. #402. */
        @Specialization
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        public static Object doUnwrap(Object exception) {
            if (exception instanceof SuspendedInBlockException sib) {
                return sib.suspended;
            }
            if (exception instanceof RuntimeException re) throw re;
            if (exception instanceof Error err) throw err;
            throw new RuntimeException((Throwable) exception);
        }
    }

    // ---- KnownMutOps: direct native emission for Mut stdlib functions ----
    // MutOpKind constants — used by InlineMutOp1/2/3 and KnownMutOps.
    // The ordinal is baked into BDSL bytecode as a ConstantOperand so Graal
    // constant-folds through the switch during PE.
    // ── MutInt ops ─────────────────────────────────────────────────
    static final int MUT_READ_INT     = 0;  // 1-arg: (n: MutableInt) → long
    static final int MUT_SIZE         = 1;  // 1-arg: (seq: MutableSeq) → long
    static final int MUT_INT_TO_MUT   = 2;  // 1-arg: (n: long) → MutableInt
    static final int MUT_SEQ_TO_MUT   = 3;  // 1-arg: (seq: MezzeSeq) → MutableSeq
    static final int MUT_SEQ_TO_IMMUT = 4;  // 1-arg: (seq: MutableSeq) → MezzeSeq
    static final int MUT_INT_TO_IMMUT = 5;  // 1-arg: (n: MutableInt) → long
    static final int MUT_INCR         = 10; // 2-arg: (n: MutableInt, delta: long)
    static final int MUT_DECR         = 11; // 2-arg: (n: MutableInt, delta: long)
    static final int MUT_WRITE_INT    = 12; // 2-arg: (n: MutableInt, v: long)
    static final int MUT_GET          = 13; // 2-arg: (seq: MutableSeq, i: long) → Option
    static final int MUT_MUL_INT      = 14; // 2-arg: (n: MutableInt, factor: long)
    static final int MUT_DIV_INT      = 15; // 2-arg: (n: MutableInt, divisor: long)
    static final int MUT_MOD_INT      = 16; // 2-arg: (n: MutableInt, divisor: long)
    static final int MUT_SET          = 20; // 3-arg: (seq: MutableSeq, i: long, v: Object) → Bool
    static final int MUT_SWAP         = 21; // 3-arg: (seq: MutableSeq, i: long, j: long) → Bool
    static final int MUT_CAS_INT      = 22; // 3-arg: (n: MutableInt, expected: long, new: long) → boolean
    static final int MUT_FOR_RANGE    = 30; // 4-arg: (start: long, end: long, step: long, body: MezzeFunction)

    // ── MutFloat ops ────────────────────────────────────────────────
    static final int MUT_READ_FLOAT     = 40; // 1-arg: (f: MutableFloat) → double
    static final int MUT_FLOAT_TO_MUT   = 41; // 1-arg: (v: double) → MutableFloat
    static final int MUT_FLOAT_TO_IMMUT = 42; // 1-arg: (f: MutableFloat) → double
    static final int MUT_WRITE_FLOAT    = 43; // 2-arg: (f: MutableFloat, v: double)
    static final int MUT_ADD_FLOAT      = 44; // 2-arg: (f: MutableFloat, delta: double)
    static final int MUT_SUB_FLOAT      = 45; // 2-arg: (f: MutableFloat, delta: double)
    static final int MUT_MUL_FLOAT      = 46; // 2-arg: (f: MutableFloat, factor: double)
    static final int MUT_DIV_FLOAT      = 47; // 2-arg: (f: MutableFloat, divisor: double)

    // ── MutBool ops ─────────────────────────────────────────────────
    static final int MUT_READ_BOOL     = 50; // 1-arg: (b: MutableBool) → boolean
    static final int MUT_TOGGLE_BOOL   = 51; // 1-arg: (b: MutableBool) → {}
    static final int MUT_BOOL_TO_MUT   = 52; // 1-arg: (v: boolean) → MutableBool
    static final int MUT_BOOL_TO_IMMUT = 53; // 1-arg: (b: MutableBool) → boolean
    static final int MUT_WRITE_BOOL    = 54; // 2-arg: (b: MutableBool, v: boolean)
    static final int MUT_SET_TRUE      = 55; // 1-arg: (b: MutableBool) → {}
    static final int MUT_SET_FALSE     = 56; // 1-arg: (b: MutableBool) → {}

    // ── MutChar ops ─────────────────────────────────────────────────
    static final int MUT_READ_CHAR     = 60; // 1-arg: (c: MutableChar) → long
    static final int MUT_CHAR_TO_MUT   = 61; // 1-arg: (v: long) → MutableChar
    static final int MUT_CHAR_TO_IMMUT = 62; // 1-arg: (c: MutableChar) → long
    static final int MUT_WRITE_CHAR    = 63; // 2-arg: (c: MutableChar, v: long)
    static final int MUT_CHAR_TO_UPPER = 64; // 1-arg: (c: MutableChar) → {}
    static final int MUT_CHAR_TO_LOWER = 65; // 1-arg: (c: MutableChar) → {}
    static final int MUT_CHAR_IS_DIGIT      = 66; // 1-arg: → boolean
    static final int MUT_CHAR_IS_LETTER     = 67; // 1-arg: → boolean
    static final int MUT_CHAR_IS_WHITESPACE = 68; // 1-arg: → boolean

    // ── MutString ops ───────────────────────────────────────────────
    static final int MUT_STRING_LEN      = 70; // 1-arg: → long
    static final int MUT_STRING_IS_EMPTY = 71; // 1-arg: → boolean
    static final int MUT_STRING_READ     = 72; // 1-arg: → Object (String)
    static final int MUT_STRING_CLEAR    = 73; // 1-arg: → {}
    static final int MUT_STRING_REVERSE  = 74; // 1-arg: → {}
    static final int MUT_STRING_TO_MUT   = 75; // 1-arg: (s: String) → MutableString
    static final int MUT_STRING_TO_IMMUT = 76; // 1-arg: (ms: MutableString) → String
    static final int MUT_STRING_WRITE    = 77; // 2-arg: (ms, s) → {}
    static final int MUT_STRING_APPEND   = 78; // 2-arg: (ms, s) → {}
    static final int MUT_STRING_PREPEND  = 79; // 2-arg: (ms, s) → {}

    // ── MutMap ops ──────────────────────────────────────────────────
    static final int MUT_MAP_SIZE     = 80; // 1-arg: → long
    static final int MUT_MAP_CLEAR    = 81; // 1-arg: → {}
    static final int MUT_MAP_TO_MUT   = 82; // 1-arg: (m: MezzeMap) → MutableMap
    static final int MUT_MAP_TO_IMMUT = 83; // 1-arg: (mm: MutableMap) → MezzeMap
    static final int MUT_MAP_CONTAINS = 84; // 2-arg: (mm, key) → boolean
    static final int MUT_MAP_GET      = 85; // 2-arg: (mm, key) → Option
    static final int MUT_MAP_REMOVE   = 86; // 2-arg: (mm, key) → {}
    static final int MUT_MAP_PUT      = 87; // 3-arg: (mm, key, val) → {}

    // ── MutSet ops ──────────────────────────────────────────────────
    static final int MUT_SET_SIZE       = 90; // 1-arg: → long
    static final int MUT_SET_CLEAR      = 91; // 1-arg: → {}
    static final int MUT_CSET_TO_MUT    = 92; // 1-arg: (s: MezzeSet) → MutableSet
    static final int MUT_CSET_TO_IMMUT  = 93; // 1-arg: (ms: MutableSet) → MezzeSet
    static final int MUT_SET_CONTAINS   = 94; // 2-arg: (ms, elem) → boolean
    static final int MUT_SET_ADD        = 95; // 2-arg: (ms, elem) → {}
    static final int MUT_SET_REMOVE     = 96; // 2-arg: (ms, elem) → {}
    static final int MUT_SET_TOGGLE     = 97; // 2-arg: (ms, elem) → boolean

    // ── MutBytes ops ────────────────────────────────────────────────
    static final int MUT_BYTES_LEN      = 100; // 1-arg: → long
    static final int MUT_BYTES_CLEAR    = 101; // 1-arg: → {}
    static final int MUT_BYTES_REVERSE  = 102; // 1-arg: → {}
    static final int MUT_BYTES_TO_MUT   = 103; // 1-arg: (b: Bytes) → MutableBytes
    static final int MUT_BYTES_TO_IMMUT = 104; // 1-arg: (mb: MutableBytes) → Bytes
    static final int MUT_BYTES_GET      = 105; // 2-arg: (mb, i: long) → Option
    static final int MUT_BYTES_SET      = 106; // 3-arg: (mb, i: long, v: long) → boolean
    static final int MUT_BYTES_SWAP     = 107; // 3-arg: (mb, i: long, j: long) → boolean

    // ── MutDeque ops ────────────────────────────────────────────────
    static final int MUT_DEQUE_SIZE      = 110; // 1-arg: → long
    static final int MUT_DEQUE_IS_EMPTY  = 111; // 1-arg: → boolean
    static final int MUT_DEQUE_CLEAR     = 112; // 1-arg: → {}
    static final int MUT_DEQUE_REVERSE   = 113; // 1-arg: → {}
    static final int MUT_DEQUE_POP_FRONT = 114; // 1-arg: → Option
    static final int MUT_DEQUE_POP_BACK  = 115; // 1-arg: → Option
    static final int MUT_DEQUE_PEEK_FRONT = 116; // 1-arg: → Option
    static final int MUT_DEQUE_PEEK_BACK  = 117; // 1-arg: → Option
    static final int MUT_DEQUE_TO_MUT    = 118; // 1-arg: → MutableDeque
    static final int MUT_DEQUE_TO_IMMUT  = 119; // 1-arg: → MezzeDeque
    static final int MUT_DEQUE_PUSH_FRONT = 120; // 2-arg: (md, elem) → {}
    static final int MUT_DEQUE_PUSH_BACK  = 121; // 2-arg: (md, elem) → {}

    // ── MutSeq bulk ops ─────────────────────────────────────────────
    static final int MUT_SEQ_SORT      = 130; // 1-arg: → {}
    static final int MUT_SEQ_REVERSE   = 131; // 1-arg: → {}
    static final int MUT_SEQ_CLEAR     = 132; // 1-arg: → {}
    static final int MUT_SEQ_PUSH      = 133; // 2-arg: (ms, elem) → {}
    static final int MUT_SEQ_POP       = 134; // 1-arg: → Option
    static final int MUT_SEQ_INSERT    = 135; // 3-arg: (ms, i, elem) → boolean
    static final int MUT_SEQ_REMOVE    = 136; // 2-arg: (ms, i) → Option

    /**
     * Direct native emission for 1-argument Mut stdlib functions.
     * When the active handler is LocalMut, executes the native Java call
     * directly — no lambda creation, no effect dispatch, no function call.
     * Falls back to calling the original stdlib function via CallTarget
     * when the handler is not LocalMut (e.g. DebugLocalMut).
     */
    @Operation
    @ConstantOperand(type = int.class)    // MutOpKind
    @ConstantOperand(type = Hash.class)   // effect hash (unused — Mut is hermetic)
    @ConstantOperand(type = Hash.class)   // original function hash (unused)
    public static final class InlineMutOp1 {
        // LocalMut is hermetic: unconditional execution, no effect checks.

        @Specialization
        public static Object doOp(int opKind, Hash effectHash, Hash fnHash,
                Object arg0) {
            return switch (opKind) {
                // MutInt
                case MUT_READ_INT     -> ((com.mezze.interpreter.values.MutableInt) arg0).get();
                case MUT_INT_TO_MUT   -> new com.mezze.interpreter.values.MutableInt((long) arg0);
                case MUT_INT_TO_IMMUT -> ((com.mezze.interpreter.values.MutableInt) arg0).get();
                // MutSeq
                case MUT_SIZE         -> (long) ((com.mezze.interpreter.values.MutableSeq) arg0).size();
                case MUT_SEQ_TO_MUT   -> com.mezze.interpreter.values.MutableSeq
                                            .fromImmutable((MezzeSeq<?>) arg0);
                case MUT_SEQ_TO_IMMUT -> ((com.mezze.interpreter.values.MutableSeq) arg0).toImmutable();
                case MUT_SEQ_SORT     -> { ((com.mezze.interpreter.values.MutableSeq) arg0).sort(); yield MezzeRecord.empty(); }
                case MUT_SEQ_REVERSE  -> { ((com.mezze.interpreter.values.MutableSeq) arg0).reverse(); yield MezzeRecord.empty(); }
                case MUT_SEQ_CLEAR    -> { ((com.mezze.interpreter.values.MutableSeq) arg0).clear(); yield MezzeRecord.empty(); }
                case MUT_SEQ_POP      -> { Object v = ((com.mezze.interpreter.values.MutableSeq) arg0).pop(); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                // MutFloat
                case MUT_READ_FLOAT     -> ((com.mezze.interpreter.values.MutableFloat) arg0).get();
                case MUT_FLOAT_TO_MUT   -> new com.mezze.interpreter.values.MutableFloat((double) arg0);
                case MUT_FLOAT_TO_IMMUT -> ((com.mezze.interpreter.values.MutableFloat) arg0).get();
                // MutBool
                case MUT_READ_BOOL     -> ((com.mezze.interpreter.values.MutableBool) arg0).get();
                case MUT_TOGGLE_BOOL   -> { ((com.mezze.interpreter.values.MutableBool) arg0).toggle(); yield MezzeRecord.empty(); }
                case MUT_BOOL_TO_MUT   -> new com.mezze.interpreter.values.MutableBool((boolean) arg0);
                case MUT_BOOL_TO_IMMUT -> ((com.mezze.interpreter.values.MutableBool) arg0).get();
                case MUT_SET_TRUE      -> { ((com.mezze.interpreter.values.MutableBool) arg0).set(true); yield MezzeRecord.empty(); }
                case MUT_SET_FALSE     -> { ((com.mezze.interpreter.values.MutableBool) arg0).set(false); yield MezzeRecord.empty(); }
                // MutChar — Mezze Char is a single-character String at runtime
                case MUT_READ_CHAR     -> String.valueOf(((com.mezze.interpreter.values.MutableChar) arg0).get());
                case MUT_CHAR_TO_MUT   -> new com.mezze.interpreter.values.MutableChar(((String) arg0).charAt(0));
                case MUT_CHAR_TO_IMMUT -> String.valueOf(((com.mezze.interpreter.values.MutableChar) arg0).get());
                case MUT_CHAR_TO_UPPER -> { ((com.mezze.interpreter.values.MutableChar) arg0).toUpper(); yield MezzeRecord.empty(); }
                case MUT_CHAR_TO_LOWER -> { ((com.mezze.interpreter.values.MutableChar) arg0).toLower(); yield MezzeRecord.empty(); }
                case MUT_CHAR_IS_DIGIT      -> ((com.mezze.interpreter.values.MutableChar) arg0).isDigit();
                case MUT_CHAR_IS_LETTER     -> ((com.mezze.interpreter.values.MutableChar) arg0).isLetter();
                case MUT_CHAR_IS_WHITESPACE -> ((com.mezze.interpreter.values.MutableChar) arg0).isWhitespace();
                // MutString
                case MUT_STRING_LEN      -> (long) ((com.mezze.interpreter.values.MutableString) arg0).length();
                case MUT_STRING_IS_EMPTY -> ((com.mezze.interpreter.values.MutableString) arg0).isEmpty();
                case MUT_STRING_READ     -> ((com.mezze.interpreter.values.MutableString) arg0).get();
                case MUT_STRING_CLEAR    -> { ((com.mezze.interpreter.values.MutableString) arg0).clear(); yield MezzeRecord.empty(); }
                case MUT_STRING_REVERSE  -> { ((com.mezze.interpreter.values.MutableString) arg0).reverse(); yield MezzeRecord.empty(); }
                case MUT_STRING_TO_MUT   -> com.mezze.interpreter.values.MutableString.createBoundary((String) arg0);
                case MUT_STRING_TO_IMMUT -> ((com.mezze.interpreter.values.MutableString) arg0).get();
                // MutMap
                case MUT_MAP_SIZE     -> (long) ((com.mezze.interpreter.values.MutableMap) arg0).size();
                case MUT_MAP_CLEAR    -> { ((com.mezze.interpreter.values.MutableMap) arg0).clear(); yield MezzeRecord.empty(); }
                case MUT_MAP_TO_MUT   -> com.mezze.interpreter.values.MutableMap.fromImmutable((com.mezze.datastructures.map.MezzeMap<?,?>) arg0);
                case MUT_MAP_TO_IMMUT -> ((com.mezze.interpreter.values.MutableMap) arg0).toImmutable();
                // MutSet
                case MUT_SET_SIZE       -> (long) ((com.mezze.interpreter.values.MutableSet) arg0).size();
                case MUT_SET_CLEAR      -> { ((com.mezze.interpreter.values.MutableSet) arg0).clear(); yield MezzeRecord.empty(); }
                case MUT_CSET_TO_MUT    -> com.mezze.interpreter.values.MutableSet.fromImmutable((com.mezze.datastructures.set.MezzeSet<?>) arg0);
                case MUT_CSET_TO_IMMUT  -> ((com.mezze.interpreter.values.MutableSet) arg0).toImmutable();
                // MutBytes
                case MUT_BYTES_LEN     -> (long) ((com.mezze.interpreter.values.MutableBytes) arg0).length();
                case MUT_BYTES_CLEAR   -> { ((com.mezze.interpreter.values.MutableBytes) arg0).clear(); yield MezzeRecord.empty(); }
                case MUT_BYTES_REVERSE -> { ((com.mezze.interpreter.values.MutableBytes) arg0).reverse(); yield MezzeRecord.empty(); }
                case MUT_BYTES_TO_MUT  -> {
                    byte[] bytes;
                    if (arg0 instanceof byte[] b) bytes = b;
                    else if (arg0 instanceof com.mezze.datastructures.bytes.MezzeBytes mb) bytes = mb.getData();
                    else if (arg0 instanceof com.mezze.interpreter.nodes.builtin.io.BytesNatives.MezzeBytes bnb) bytes = bnb.getData();
                    else throw bytesToMutError(arg0);
                    yield com.mezze.interpreter.values.MutableBytes.fromImmutable(bytes);
                }
                case MUT_BYTES_TO_IMMUT -> ((com.mezze.interpreter.values.MutableBytes) arg0).toImmutable();
                // MutDeque
                case MUT_DEQUE_SIZE      -> (long) ((com.mezze.interpreter.values.MutableDeque) arg0).size();
                case MUT_DEQUE_IS_EMPTY  -> ((com.mezze.interpreter.values.MutableDeque) arg0).isEmpty();
                case MUT_DEQUE_CLEAR     -> { ((com.mezze.interpreter.values.MutableDeque) arg0).clear(); yield MezzeRecord.empty(); }
                case MUT_DEQUE_REVERSE   -> { ((com.mezze.interpreter.values.MutableDeque) arg0).reverse(); yield MezzeRecord.empty(); }
                case MUT_DEQUE_POP_FRONT -> { Object v = ((com.mezze.interpreter.values.MutableDeque) arg0).popFront(); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                case MUT_DEQUE_POP_BACK  -> { Object v = ((com.mezze.interpreter.values.MutableDeque) arg0).popBack(); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                case MUT_DEQUE_PEEK_FRONT -> { Object v = ((com.mezze.interpreter.values.MutableDeque) arg0).peekFront(); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                case MUT_DEQUE_PEEK_BACK  -> { Object v = ((com.mezze.interpreter.values.MutableDeque) arg0).peekBack(); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                case MUT_DEQUE_TO_MUT    -> com.mezze.interpreter.values.MutableDeque.fromImmutable((com.mezze.datastructures.deque.MezzeDeque<?>) arg0);
                case MUT_DEQUE_TO_IMMUT  -> ((com.mezze.interpreter.values.MutableDeque) arg0).toImmutable();
                default -> throw CompilerDirectives.shouldNotReachHere();
            };
        }
    }

    @CompilerDirectives.TruffleBoundary
    private static RuntimeException bytesToMutError(Object arg0) {
        throw CompilerDirectives.shouldNotReachHere("bytes_to_mut: unexpected type " + arg0.getClass());
    }

    // ── Typed Mut operations ───────────────────────────────────────
    // These return primitive long (not Object) so the BDSL quickening
    // keeps them unboxed through the frame.  Hot-path ops that return
    // long should be emitted via these instead of InlineMutOp1/2.

    /** read_int — returns unboxed long (no boxing). */
    @Operation
    public static final class MutReadInt {
        @Specialization
        public static long doOp(Object mutInt) {
            return ((com.mezze.interpreter.values.MutableInt) mutInt).get();
        }
    }

    /** size — returns unboxed long (no boxing). */
    @Operation
    public static final class MutSize {
        @Specialization
        public static long doLong(com.mezze.interpreter.values.MutableLongSeq mutSeq) {
            return (long) mutSeq.size();
        }
        @Specialization
        public static long doOp(com.mezze.interpreter.values.MutableSeq mutSeq) {
            return (long) mutSeq.size();
        }
    }

    /** incr — side effect only, returns empty record. */
    @Operation
    public static final class MutIncr {
        @Specialization
        public static Object doOp(Object mutInt, long delta) {
            ((com.mezze.interpreter.values.MutableInt) mutInt).add(delta);
            return MezzeRecord.empty();
        }
    }

    /** decr — side effect, returns empty record. */
    @Operation
    public static final class MutDecr {
        @Specialization
        public static Object doOp(Object mutInt, long delta) {
            ((com.mezze.interpreter.values.MutableInt) mutInt).subtract(delta);
            return MezzeRecord.empty();
        }
    }

    /** write_int — set MutableInt value, returns empty record. */
    @Operation
    public static final class MutWriteInt {
        @Specialization
        public static Object doOp(Object mutInt, long val) {
            ((com.mezze.interpreter.values.MutableInt) mutInt).set(val);
            return MezzeRecord.empty();
        }
    }

    /** mul_int — multiply MutableInt, returns empty record. */
    @Operation
    public static final class MutMulInt {
        @Specialization
        public static Object doOp(Object mutInt, long factor) {
            ((com.mezze.interpreter.values.MutableInt) mutInt).multiply(factor);
            return MezzeRecord.empty();
        }
    }

    /** div_int — integer divide MutableInt, returns empty record. */
    @Operation
    public static final class MutDivInt {
        @Specialization
        public static Object doOp(Object mutInt, long divisor) {
            ((com.mezze.interpreter.values.MutableInt) mutInt).divide(divisor);
            return MezzeRecord.empty();
        }
    }

    /** mod_int — modulo MutableInt, returns empty record. */
    @Operation
    public static final class MutModInt {
        @Specialization
        public static Object doOp(Object mutInt, long divisor) {
            ((com.mezze.interpreter.values.MutableInt) mutInt).mod(divisor);
            return MezzeRecord.empty();
        }
    }

    /** cas_int — compare-and-swap, returns boolean success. */
    @Operation
    public static final class MutCasInt {
        @Specialization
        public static boolean doOp(Object mutInt, long expected, long newValue) {
            return ((com.mezze.interpreter.values.MutableInt) mutInt).compareAndSwap(expected, newValue);
        }
    }

    // ── MutFloat typed operations ─────────────────────────────────

    /** read_float — returns unboxed double. */
    @Operation
    public static final class MutReadFloat {
        @Specialization
        public static double doOp(Object mutFloat) {
            return ((com.mezze.interpreter.values.MutableFloat) mutFloat).get();
        }
    }

    /** Float mutation ops — ConstantOperand selects sub-op, typed double param. */
    @Operation
    @ConstantOperand(type = int.class) // sub-op: WRITE=0, ADD=1, SUB=2, MUL=3, DIV=4
    public static final class MutFloatMutate {
        @Specialization
        public static Object doOp(int subOp, Object mutFloat, double val) {
            var f = (com.mezze.interpreter.values.MutableFloat) mutFloat;
            switch (subOp) {
                case 0 -> f.set(val);
                case 1 -> f.add(val);
                case 2 -> f.subtract(val);
                case 3 -> f.multiply(val);
                case 4 -> f.divide(val);
                default -> throw CompilerDirectives.shouldNotReachHere();
            }
            return MezzeRecord.empty();
        }
        static final int WRITE = 0, ADD = 1, SUB = 2, MUL = 3, DIV = 4;
    }

    // ── MutBool typed operations ──────────────────────────────────

    /** read_bool — returns unboxed boolean. */
    @Operation
    public static final class MutReadBool {
        @Specialization
        public static boolean doOp(Object mutBool) {
            return ((com.mezze.interpreter.values.MutableBool) mutBool).get();
        }
    }

    /** write_bool — side effect, returns empty record. */
    @Operation
    public static final class MutWriteBool {
        @Specialization
        public static Object doOp(Object mutBool, Object val) {
            ((com.mezze.interpreter.values.MutableBool) mutBool).set((boolean) val);
            return MezzeRecord.empty();
        }
    }

    /** toggle_bool — side effect, returns empty record. */
    @Operation
    public static final class MutToggleBool {
        @Specialization
        public static Object doOp(Object mutBool) {
            ((com.mezze.interpreter.values.MutableBool) mutBool).toggle();
            return MezzeRecord.empty();
        }
    }

    // ── MutChar typed operations ──────────────────────────────────

    /** read_char — Mezze Char is a single-character String at runtime. */
    @Operation
    public static final class MutReadChar {
        @Specialization
        public static Object doOp(Object mutChar) {
            return String.valueOf(((com.mezze.interpreter.values.MutableChar) mutChar).get());
        }
    }

    /** write_char — side effect, returns empty record. Mezze Char is String. */
    @Operation
    public static final class MutWriteChar {
        @Specialization
        public static Object doOp(Object mutChar, Object val) {
            ((com.mezze.interpreter.values.MutableChar) mutChar).set(((String) val).charAt(0));
            return MezzeRecord.empty();
        }
    }

    // ── MutMap typed operations ───────────────────────────────────

    /** map_size — returns unboxed long. */
    @Operation
    public static final class MutMapSize {
        @Specialization
        public static long doOp(Object mutMap) {
            return (long) ((com.mezze.interpreter.values.MutableMap) mutMap).size();
        }
    }

    /** map_contains — returns unboxed boolean. */
    @Operation
    public static final class MutMapContains {
        @Specialization
        public static boolean doOp(Object mutMap, Object key) {
            return ((com.mezze.interpreter.values.MutableMap) mutMap).containsKey(key);
        }
    }

    /** map_get — returns Option (Some{val}/None). */
    @Operation
    public static final class MutMapGet {
        @Specialization
        public static Object doOp(Object mutMap, Object key) {
            Object val = ((com.mezze.interpreter.values.MutableMap) mutMap).get(key);
            return val != null ? MezzeVariant.some(val) : MezzeVariant.none();
        }
    }

    /** map_put — side effect, returns empty record. */
    @Operation
    public static final class MutMapPut {
        @Specialization
        public static Object doOp(Object mutMap, Object key, Object val) {
            ((com.mezze.interpreter.values.MutableMap) mutMap).put(key, val);
            return MezzeRecord.empty();
        }
    }

    /** map_remove — side effect, returns empty record. */
    @Operation
    public static final class MutMapRemove {
        @Specialization
        public static Object doOp(Object mutMap, Object key) {
            ((com.mezze.interpreter.values.MutableMap) mutMap).remove(key);
            return MezzeRecord.empty();
        }
    }

    // ── MutSet typed operations ───────────────────────────────────

    /** set_size — returns unboxed long. */
    @Operation
    public static final class MutCSetSize {
        @Specialization
        public static long doOp(Object mutSet) {
            return (long) ((com.mezze.interpreter.values.MutableSet) mutSet).size();
        }
    }

    /** set_contains — returns unboxed boolean. */
    @Operation
    public static final class MutCSetContains {
        @Specialization
        public static boolean doOp(Object mutSet, Object elem) {
            return ((com.mezze.interpreter.values.MutableSet) mutSet).contains(elem);
        }
    }

    /** set_add — side effect, returns empty record. */
    @Operation
    public static final class MutCSetAdd {
        @Specialization
        public static Object doOp(Object mutSet, Object elem) {
            ((com.mezze.interpreter.values.MutableSet) mutSet).add(elem);
            return MezzeRecord.empty();
        }
    }

    /** set_remove — side effect, returns empty record. */
    @Operation
    public static final class MutCSetRemove {
        @Specialization
        public static Object doOp(Object mutSet, Object elem) {
            ((com.mezze.interpreter.values.MutableSet) mutSet).remove(elem);
            return MezzeRecord.empty();
        }
    }

    /** set_toggle — add if absent, remove if present, returns boolean (was added). */
    @Operation
    public static final class MutCSetToggle {
        @Specialization
        public static boolean doOp(Object mutSet, Object elem) {
            return ((com.mezze.interpreter.values.MutableSet) mutSet).toggle(elem);
        }
    }

    // ── MutBytes typed operations ─────────────────────────────────

    /** bytes_len — returns unboxed long. */
    @Operation
    public static final class MutBytesLen {
        @Specialization
        public static long doOp(Object mutBytes) {
            return (long) ((com.mezze.interpreter.values.MutableBytes) mutBytes).length();
        }
    }

    /** bytes_get — returns Option (Some{val}/None). */
    @Operation
    public static final class MutBytesGet {
        @Specialization
        public static Object doOp(Object mutBytes, long i) {
            int val = ((com.mezze.interpreter.values.MutableBytes) mutBytes).getSafe((int) i);
            return val >= 0 ? MezzeVariant.some((long) val) : MezzeVariant.none();
        }
    }

    /** bytes_set — returns boolean success. */
    @Operation
    public static final class MutBytesSet {
        @Specialization
        public static boolean doOp(Object mutBytes, long i, long val) {
            return ((com.mezze.interpreter.values.MutableBytes) mutBytes).setSafe((int) i, (int) val);
        }
    }

    /** bytes_swap — returns boolean success. */
    @Operation
    public static final class MutBytesSwap {
        @Specialization
        public static boolean doOp(Object mutBytes, long i, long j) {
            return ((com.mezze.interpreter.values.MutableBytes) mutBytes).swap((int) i, (int) j);
        }
    }

    // ── MutDeque typed operations ─────────────────────────────────

    /** deque_size — returns unboxed long. */
    @Operation
    public static final class MutDequeSize {
        @Specialization
        public static long doOp(Object mutDeque) {
            return (long) ((com.mezze.interpreter.values.MutableDeque) mutDeque).size();
        }
    }

    /** deque_is_empty — returns unboxed boolean. */
    @Operation
    public static final class MutDequeIsEmpty {
        @Specialization
        public static boolean doOp(Object mutDeque) {
            return ((com.mezze.interpreter.values.MutableDeque) mutDeque).isEmpty();
        }
    }

    /** deque_push_back — side effect, returns empty record. */
    @Operation
    public static final class MutDequePushBack {
        @Specialization
        public static Object doOp(Object mutDeque, Object elem) {
            ((com.mezze.interpreter.values.MutableDeque) mutDeque).pushBack(elem);
            return MezzeRecord.empty();
        }
    }

    /** deque_push_front — side effect, returns empty record. */
    @Operation
    public static final class MutDequePushFront {
        @Specialization
        public static Object doOp(Object mutDeque, Object elem) {
            ((com.mezze.interpreter.values.MutableDeque) mutDeque).pushFront(elem);
            return MezzeRecord.empty();
        }
    }

    /** deque_pop_back — returns Option. */
    @Operation
    public static final class MutDequePopBack {
        @Specialization
        public static Object doOp(Object mutDeque) {
            Object val = ((com.mezze.interpreter.values.MutableDeque) mutDeque).popBack();
            return val != null ? MezzeVariant.some(val) : MezzeVariant.none();
        }
    }

    /** deque_pop_front — returns Option. */
    @Operation
    public static final class MutDequePopFront {
        @Specialization
        public static Object doOp(Object mutDeque) {
            Object val = ((com.mezze.interpreter.values.MutableDeque) mutDeque).popFront();
            return val != null ? MezzeVariant.some(val) : MezzeVariant.none();
        }
    }

    /** deque_peek_front — returns Option. */
    @Operation
    public static final class MutDequePeekFront {
        @Specialization
        public static Object doOp(Object mutDeque) {
            Object val = ((com.mezze.interpreter.values.MutableDeque) mutDeque).peekFront();
            return val != null ? MezzeVariant.some(val) : MezzeVariant.none();
        }
    }

    /** deque_peek_back — returns Option. */
    @Operation
    public static final class MutDequePeekBack {
        @Specialization
        public static Object doOp(Object mutDeque) {
            Object val = ((com.mezze.interpreter.values.MutableDeque) mutDeque).peekBack();
            return val != null ? MezzeVariant.some(val) : MezzeVariant.none();
        }
    }

    // ── MutString typed operations ────────────────────────────────

    /** string_len — returns unboxed long. */
    @Operation
    public static final class MutStringLen {
        @Specialization
        public static long doOp(Object mutStr) {
            return (long) ((com.mezze.interpreter.values.MutableString) mutStr).length();
        }
    }

    /** string_is_empty — returns unboxed boolean. */
    @Operation
    public static final class MutStringIsEmpty {
        @Specialization
        public static boolean doOp(Object mutStr) {
            return ((com.mezze.interpreter.values.MutableString) mutStr).isEmpty();
        }
    }

    /** swap — side effect, returns boolean. Pure long swap for MutableLongSeq. */
    @Operation
    public static final class MutSwap {
        @Specialization
        public static boolean doLong(com.mezze.interpreter.values.MutableLongSeq mutSeq, long i, long j) {
            try {
                mutSeq.swap((int) i, (int) j);
                return true;
            } catch (IndexOutOfBoundsException e) {
                return false;
            }
        }
        @Specialization
        public static boolean doOp(com.mezze.interpreter.values.MutableSeq mutSeq, long i, long j) {
            try {
                mutSeq.swap((int) i, (int) j);
                return true;
            } catch (IndexOutOfBoundsException e) {
                return false;
            }
        }
    }

    /** set — side effect, returns boolean. Unboxed long path for MutableLongSeq. */
    @Operation
    public static final class MutSet {
        @Specialization
        public static boolean doLong(com.mezze.interpreter.values.MutableLongSeq mutSeq, long i, long val) {
            return mutSeq.setSafeLong((int) i, val);
        }
        @Specialization
        public static boolean doOp(com.mezze.interpreter.values.MutableSeq mutSeq, long i, Object val) {
            return mutSeq.setSafe((int) i, val);
        }
    }

    /**
     * get — returns raw value or null (no MezzeVariant wrapping).
     * The bytecode compiler emits this when it detects the pattern
     * {@code match get { seq, i } is Some { val } -> expr, None -> default}
     * and fuses the get + match into a null check.
     *
     * For MutableLongSeq, reads from {@code long[]} (cache-friendly) and
     * autoboxes to Long.  Graal escape-analyses the box away when the value
     * flows directly into an unbox (the common case in comparisons).
     */
    @Operation
    public static final class MutGetRaw {
        @Specialization
        public static Object doLong(com.mezze.interpreter.values.MutableLongSeq mutSeq, long i) {
            return mutSeq.getSafe((int) i);
        }
        @Specialization
        public static Object doOp(com.mezze.interpreter.values.MutableSeq mutSeq, long i) {
            return mutSeq.getSafe((int) i);
        }
    }

    /** Null check — used by the fused MutGetRaw + match pattern. */
    @Operation
    public static final class IsNotNull {
        @Specialization
        public static boolean doOp(Object val) {
            return val != null;
        }
    }

    /**
     * Bounds check for the split MutInBounds + MutReadLong pattern.
     * Returns true if index is in [0, seq.size()), false otherwise.
     * Used in the fused get+match when the Some branch binds a long val.
     */
    @Operation
    public static final class MutInBounds {
        @Specialization
        public static boolean doLong(com.mezze.interpreter.values.MutableLongSeq mutSeq, long i) {
            int idx = (int) i;
            return idx >= 0 && idx < mutSeq.size();
        }
        @Specialization
        public static boolean doOp(com.mezze.interpreter.values.MutableSeq mutSeq, long i) {
            int idx = (int) i;
            return idx >= 0 && idx < mutSeq.size();
        }
    }

    /**
     * Unchecked read — only safe after a MutInBounds guard.
     * For MutableLongSeq, returns unboxed long (zero boxing).
     * For MutableSeq, returns Object (the element directly).
     */
    @Operation
    public static final class MutReadLong {
        @Specialization
        public static long doLong(com.mezze.interpreter.values.MutableLongSeq mutSeq, long i) {
            return mutSeq.getLong((int) i);
        }
        @Specialization
        public static Object doOp(com.mezze.interpreter.values.MutableSeq mutSeq, long i) {
            return mutSeq.get((int) i);
        }
    }

    /**
     * Direct native emission for 2-argument Mut stdlib functions.
     * @see InlineMutOp1
     */
    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = Hash.class)
    @ConstantOperand(type = Hash.class)
    public static final class InlineMutOp2 {
        // See InlineMutOp1 — Mut is hermetic, unconditional execution.

        @Specialization
        public static Object doOp(int opKind, Hash effectHash, Hash fnHash,
                Object arg0, Object arg1) {
            return switch (opKind) {
                // MutInt
                case MUT_INCR     -> { ((com.mezze.interpreter.values.MutableInt) arg0).add((long) arg1); yield MezzeRecord.empty(); }
                case MUT_DECR     -> { ((com.mezze.interpreter.values.MutableInt) arg0).subtract((long) arg1); yield MezzeRecord.empty(); }
                case MUT_WRITE_INT -> { ((com.mezze.interpreter.values.MutableInt) arg0).set((long) arg1); yield MezzeRecord.empty(); }
                case MUT_MUL_INT  -> { ((com.mezze.interpreter.values.MutableInt) arg0).multiply((long) arg1); yield MezzeRecord.empty(); }
                case MUT_DIV_INT  -> { ((com.mezze.interpreter.values.MutableInt) arg0).divide((long) arg1); yield MezzeRecord.empty(); }
                case MUT_MOD_INT  -> { ((com.mezze.interpreter.values.MutableInt) arg0).mod((long) arg1); yield MezzeRecord.empty(); }
                // MutSeq
                case MUT_GET      -> { Object v = ((com.mezze.interpreter.values.MutableSeq) arg0).getSafe((int) (long) arg1); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                case MUT_SEQ_PUSH -> { ((com.mezze.interpreter.values.MutableSeq) arg0).push(arg1); yield MezzeRecord.empty(); }
                case MUT_SEQ_REMOVE -> { Object v = ((com.mezze.interpreter.values.MutableSeq) arg0).remove((int) (long) arg1); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                // MutFloat
                case MUT_WRITE_FLOAT -> { ((com.mezze.interpreter.values.MutableFloat) arg0).set((double) arg1); yield MezzeRecord.empty(); }
                case MUT_ADD_FLOAT   -> { ((com.mezze.interpreter.values.MutableFloat) arg0).add((double) arg1); yield MezzeRecord.empty(); }
                case MUT_SUB_FLOAT   -> { ((com.mezze.interpreter.values.MutableFloat) arg0).subtract((double) arg1); yield MezzeRecord.empty(); }
                case MUT_MUL_FLOAT   -> { ((com.mezze.interpreter.values.MutableFloat) arg0).multiply((double) arg1); yield MezzeRecord.empty(); }
                case MUT_DIV_FLOAT   -> { ((com.mezze.interpreter.values.MutableFloat) arg0).divide((double) arg1); yield MezzeRecord.empty(); }
                // MutBool
                case MUT_WRITE_BOOL -> { ((com.mezze.interpreter.values.MutableBool) arg0).set((boolean) arg1); yield MezzeRecord.empty(); }
                // MutChar — Mezze Char is a single-character String
                case MUT_WRITE_CHAR -> { ((com.mezze.interpreter.values.MutableChar) arg0).set(((String) arg1).charAt(0)); yield MezzeRecord.empty(); }
                // MutString
                case MUT_STRING_WRITE   -> { ((com.mezze.interpreter.values.MutableString) arg0).set((String) arg1); yield MezzeRecord.empty(); }
                case MUT_STRING_APPEND  -> { ((com.mezze.interpreter.values.MutableString) arg0).append((String) arg1); yield MezzeRecord.empty(); }
                case MUT_STRING_PREPEND -> { ((com.mezze.interpreter.values.MutableString) arg0).prepend((String) arg1); yield MezzeRecord.empty(); }
                // MutMap
                case MUT_MAP_CONTAINS -> ((com.mezze.interpreter.values.MutableMap) arg0).containsKey(arg1);
                case MUT_MAP_GET      -> { Object v = ((com.mezze.interpreter.values.MutableMap) arg0).get(arg1); yield v != null ? MezzeVariant.some(v) : MezzeVariant.none(); }
                case MUT_MAP_REMOVE   -> { ((com.mezze.interpreter.values.MutableMap) arg0).remove(arg1); yield MezzeRecord.empty(); }
                // MutSet
                case MUT_SET_CONTAINS -> ((com.mezze.interpreter.values.MutableSet) arg0).contains(arg1);
                case MUT_SET_ADD      -> { ((com.mezze.interpreter.values.MutableSet) arg0).add(arg1); yield MezzeRecord.empty(); }
                case MUT_SET_REMOVE   -> { ((com.mezze.interpreter.values.MutableSet) arg0).remove(arg1); yield MezzeRecord.empty(); }
                case MUT_SET_TOGGLE   -> ((com.mezze.interpreter.values.MutableSet) arg0).toggle(arg1);
                // MutBytes
                case MUT_BYTES_GET -> { int v = ((com.mezze.interpreter.values.MutableBytes) arg0).getSafe((int) (long) arg1); yield v >= 0 ? MezzeVariant.some((long) v) : MezzeVariant.none(); }
                // MutDeque
                case MUT_DEQUE_PUSH_FRONT -> { ((com.mezze.interpreter.values.MutableDeque) arg0).pushFront(arg1); yield MezzeRecord.empty(); }
                case MUT_DEQUE_PUSH_BACK  -> { ((com.mezze.interpreter.values.MutableDeque) arg0).pushBack(arg1); yield MezzeRecord.empty(); }
                default -> throw CompilerDirectives.shouldNotReachHere();
            };
        }
    }

    /**
     * Direct native emission for 3-argument Mut stdlib functions.
     * @see InlineMutOp1
     */
    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = Hash.class)
    @ConstantOperand(type = Hash.class)
    public static final class InlineMutOp3 {
        // See InlineMutOp1 — Mut is hermetic, unconditional execution.

        @Specialization
        public static Object doOp(int opKind, Hash effectHash, Hash fnHash,
                Object arg0, Object arg1, Object arg2) {
            return switch (opKind) {
                // MutSeq
                case MUT_SET -> {
                    boolean ok = ((com.mezze.interpreter.values.MutableSeq) arg0)
                        .setSafe((int) (long) arg1, arg2);
                    yield ok;
                }
                case MUT_SWAP -> {
                    try {
                        ((com.mezze.interpreter.values.MutableSeq) arg0)
                            .swap((int) (long) arg1, (int) (long) arg2);
                        yield true;
                    } catch (IndexOutOfBoundsException e) {
                        yield false;
                    }
                }
                case MUT_SEQ_INSERT -> ((com.mezze.interpreter.values.MutableSeq) arg0).insert((int) (long) arg1, arg2);
                // MutInt
                case MUT_CAS_INT -> ((com.mezze.interpreter.values.MutableInt) arg0).compareAndSwap((long) arg1, (long) arg2);
                // MutMap
                case MUT_MAP_PUT -> { ((com.mezze.interpreter.values.MutableMap) arg0).put(arg1, arg2); yield MezzeRecord.empty(); }
                // MutBytes
                case MUT_BYTES_SET  -> ((com.mezze.interpreter.values.MutableBytes) arg0).setSafe((int) (long) arg1, (int) (long) arg2);
                case MUT_BYTES_SWAP -> ((com.mezze.interpreter.values.MutableBytes) arg0).swap((int) (long) arg1, (int) (long) arg2);
                default -> throw CompilerDirectives.shouldNotReachHere();
            };
        }
    }

    // ── Generic Mut native bypass ──────────────────────────────────
    // For Mut ops WITHOUT typed fast paths.  Builds a MezzeRecord from
    // scalar args and calls the record-ABI NativeDispatch handler directly.
    // @TruffleBoundary for SVM safety.  Still vastly faster than full
    // effect dispatch (eliminates lambda + record + handler lookup + act call).

    /** Generic 1-arg Mut native bypass. */
    @Operation
    @ConstantOperand(type = com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler.class)
    @ConstantOperand(type = MezzeRecord.Shape.class)
    public static final class InlineMutRecord1 {
        @Specialization
        @TruffleBoundary
        public static Object doOp(
                com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler handler,
                MezzeRecord.Shape shape,
                Object arg0,
                @Cached(value = "createIndirect()", allowUncached = true)
                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            MezzeRecord rec = MezzeRecord.withAllObjects(shape, new Object[]{arg0});
            return handler.handle(rec, callNode);
        }
        @TruffleBoundary
        static com.oracle.truffle.api.nodes.IndirectCallNode createIndirect() {
            return com.oracle.truffle.api.nodes.IndirectCallNode.create();
        }
    }

    /** Generic 2-arg Mut native bypass. */
    @Operation
    @ConstantOperand(type = com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler.class)
    @ConstantOperand(type = MezzeRecord.Shape.class)
    public static final class InlineMutRecord2 {
        @Specialization
        @TruffleBoundary
        public static Object doOp(
                com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler handler,
                MezzeRecord.Shape shape,
                Object arg0, Object arg1,
                @Cached(value = "createIndirect()", allowUncached = true)
                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            MezzeRecord rec = MezzeRecord.withAllObjects(shape, new Object[]{arg0, arg1});
            return handler.handle(rec, callNode);
        }
        @TruffleBoundary
        static com.oracle.truffle.api.nodes.IndirectCallNode createIndirect() {
            return com.oracle.truffle.api.nodes.IndirectCallNode.create();
        }
    }

    /** Generic 3-arg Mut native bypass. */
    @Operation
    @ConstantOperand(type = com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler.class)
    @ConstantOperand(type = MezzeRecord.Shape.class)
    public static final class InlineMutRecord3 {
        @Specialization
        @TruffleBoundary
        public static Object doOp(
                com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler handler,
                MezzeRecord.Shape shape,
                Object arg0, Object arg1, Object arg2,
                @Cached(value = "createIndirect()", allowUncached = true)
                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            MezzeRecord rec = MezzeRecord.withAllObjects(shape, new Object[]{arg0, arg1, arg2});
            return handler.handle(rec, callNode);
        }
        @TruffleBoundary
        static com.oracle.truffle.api.nodes.IndirectCallNode createIndirect() {
            return com.oracle.truffle.api.nodes.IndirectCallNode.create();
        }
    }

    /** Generic 4-arg Mut native bypass. */
    @Operation
    @ConstantOperand(type = com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler.class)
    @ConstantOperand(type = MezzeRecord.Shape.class)
    public static final class InlineMutRecord4 {
        @Specialization
        @TruffleBoundary
        public static Object doOp(
                com.mezze.interpreter.nodes.builtin.NativeDispatch.NativeHandler handler,
                MezzeRecord.Shape shape,
                Object arg0, Object arg1, Object arg2, Object arg3,
                @Cached(value = "createIndirect()", allowUncached = true)
                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
            MezzeRecord rec = MezzeRecord.withAllObjects(shape,
                new Object[]{arg0, arg1, arg2, arg3});
            return handler.handle(rec, callNode);
        }
        @TruffleBoundary
        static com.oracle.truffle.api.nodes.IndirectCallNode createIndirect() {
            return com.oracle.truffle.api.nodes.IndirectCallNode.create();
        }
    }

    /**
     * Compiled loop for Mut for_range.  Mut is hermetic — no handler check,
     * no CyclicAssumption, just a tight compiled loop with the body inlined.
     * Closure-count-specialized to avoid Object[] (Graal sees exact arity).
     *
     * Scalar ABI: (start: long, end: long, step: long, body: MezzeFunction)
     */
    @Operation
    @ConstantOperand(type = Hash.class)   // effect hash (unused — Mut is hermetic)
    @ConstantOperand(type = Hash.class)   // original function hash (unused)
    public static final class CompiledForRange {

        @Specialization(guards = {"bodyTargetMatches(bodyFn, cachedBodyTarget)",
                                  "closureLen == 0"},
                        excludeForUncached = true)
        public static Object doLoop0(Hash effectHash, Hash fnHash,
                long start, long end, long step, Object bodyFn,
                @Cached("extractBodyTarget(bodyFn)") CallTarget cachedBodyTarget,
                @Cached("resolveInner(cachedBodyTarget)") CallTarget resolved,
                @Cached("closureCount(bodyFn)") int closureLen,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            for (long i = start; i < end; i += step) {
                callNode.call(i);
            }
            return MezzeRecord.empty();
        }

        @Specialization(guards = {"bodyTargetMatches(bodyFn, cachedBodyTarget)",
                                  "closureLen == 1"},
                        excludeForUncached = true)
        public static Object doLoop1(Hash effectHash, Hash fnHash,
                long start, long end, long step, Object bodyFn,
                @Cached("extractBodyTarget(bodyFn)") CallTarget cachedBodyTarget,
                @Cached("resolveInner(cachedBodyTarget)") CallTarget resolved,
                @Cached("closureCount(bodyFn)") int closureLen,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            Object c0 = ((MezzeFunction) bodyFn).getClosureValues()[0];
            for (long i = start; i < end; i += step) {
                callNode.call(c0, i);
            }
            return MezzeRecord.empty();
        }

        @Specialization(guards = {"bodyTargetMatches(bodyFn, cachedBodyTarget)",
                                  "closureLen == 2"},
                        excludeForUncached = true)
        public static Object doLoop2(Hash effectHash, Hash fnHash,
                long start, long end, long step, Object bodyFn,
                @Cached("extractBodyTarget(bodyFn)") CallTarget cachedBodyTarget,
                @Cached("resolveInner(cachedBodyTarget)") CallTarget resolved,
                @Cached("closureCount(bodyFn)") int closureLen,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            Object[] cv = ((MezzeFunction) bodyFn).getClosureValues();
            Object c0 = cv[0], c1 = cv[1];
            for (long i = start; i < end; i += step) {
                callNode.call(c0, c1, i);
            }
            return MezzeRecord.empty();
        }

        @Specialization(guards = {"bodyTargetMatches(bodyFn, cachedBodyTarget)",
                                  "closureLen == 3"},
                        excludeForUncached = true)
        public static Object doLoop3(Hash effectHash, Hash fnHash,
                long start, long end, long step, Object bodyFn,
                @Cached("extractBodyTarget(bodyFn)") CallTarget cachedBodyTarget,
                @Cached("resolveInner(cachedBodyTarget)") CallTarget resolved,
                @Cached("closureCount(bodyFn)") int closureLen,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            Object[] cv = ((MezzeFunction) bodyFn).getClosureValues();
            Object c0 = cv[0], c1 = cv[1], c2 = cv[2];
            for (long i = start; i < end; i += step) {
                callNode.call(c0, c1, c2, i);
            }
            return MezzeRecord.empty();
        }

        @Specialization(guards = "bodyTargetMatches(bodyFn, cachedBodyTarget)",
                        excludeForUncached = true)
        public static Object doLoopN(Hash effectHash, Hash fnHash,
                long start, long end, long step, Object bodyFn,
                @Cached("extractBodyTarget(bodyFn)") CallTarget cachedBodyTarget,
                @Cached("resolveInner(cachedBodyTarget)") CallTarget resolved,
                @Cached("closureCount(bodyFn)") int closureLen,
                @Cached("createForceInlined(resolved)") DirectCallNode callNode) {
            Object[] cv = ((MezzeFunction) bodyFn).getClosureValues();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(cv, 0, args, 0, closureLen);
            for (long i = start; i < end; i += step) {
                args[closureLen] = i;
                callNode.call(args);
            }
            return MezzeRecord.empty();
        }

        // Uncached fallback — required by BDSL for uncached root node.
        // Never reached on the hot path (all call sites specialise on
        // the body target at first execution).
        @Specialization(replaces = {"doLoop0", "doLoop1", "doLoop2", "doLoop3", "doLoopN"})
        @TruffleBoundary
        public static Object doUncached(Hash effectHash, Hash fnHash,
                long start, long end, long step, Object bodyFn) {
            if (bodyFn instanceof MezzeFunction fn) {
                Object[] cv = fn.getClosureValues();
                int closureLen = cv.length;
                Object[] args = new Object[closureLen + 1];
                System.arraycopy(cv, 0, args, 0, closureLen);
                com.oracle.truffle.api.nodes.IndirectCallNode callNode =
                    com.oracle.truffle.api.nodes.IndirectCallNode.getUncached();
                CallTarget ct = fn.getCallTarget();
                for (long i = start; i < end; i += step) {
                    args[closureLen] = i;
                    callNode.call(ct, args);
                }
            }
            return MezzeRecord.empty();
        }

        static CallTarget extractBodyTarget(Object bodyFn) {
            return bodyFn instanceof MezzeFunction fn ? fn.getCallTarget() : null;
        }

        static boolean bodyTargetMatches(Object bodyFn, CallTarget cached) {
            return cached != null
                && bodyFn instanceof MezzeFunction fn
                && fn.getCallTarget() == cached;
        }

        static int closureCount(Object bodyFn) {
            return bodyFn instanceof MezzeFunction fn ? fn.getClosureValues().length : 0;
        }

        static CallTarget resolveInner(CallTarget target) {
            if (target instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof TrampolineRootNode tramp
                    && tramp.getInnerTarget() != null) {
                return tramp.getInnerTarget();
            }
            return target;
        }

        static DirectCallNode createForceInlined(CallTarget target) {
            DirectCallNode node = DirectCallNode.create(target);
            node.forceInlining();
            return node;
        }
    }

    /**
     * Fast path for {@code perform Mut mutate { act = <lambda> }}: pop
     * the raw act lambda (no wrapper record on the operand stack).
     * Builds a one-field {@code {act = fn}} wrapper with a constant
     * Shape (EA-scalarizable) and dispatches through the same
     * Assumption-guarded cached path as {@link PerformD} — zero
     * ThreadLocal probes, handler/target are compilation constants,
     * PE inlines through the handler's mutate impl.
     *
     * <p>Preserves the AST's Mut marker fast path — must not regress the
     * LocalMut hot loop.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class PerformMutMutateAct {

        // ---- LocalMut bypass detection ----
        // Lazily resolved CallTarget of LocalMut.mutate = { act } -> act {}
        // (the well-known passthrough). Compared by reference to decide
        // whether to bypass the handler. Resolved once from
        // StdlibRuntimeData; null means "couldn't resolve, don't bypass."
        private static volatile CallTarget knownLocalMutMutateTarget;
        private static volatile boolean localMutTargetResolved;

        // LocalMut bypass — when the handler is the well-known LocalMut
        // passthrough ({ act } -> act {}), call actFn directly from this
        // per-call-site node. This makes the actFn dispatch MONOMORPHIC:
        // each stdlib Mut wrapper (get, swap, incr, …) always passes the
        // same act-lambda CallTarget, so Graal can devirtualize + inline
        // through to the native impl. Without this, the shared handler's
        // internal `act {}` IndirectCallNode is megamorphic across all Mut
        // call sites and can never be inlined — the root cause of the
        // 500× overhead on Mut-heavy workloads.
        @Specialization(assumptions = "effectStable",
                        guards = {"cachedHandler != null", "cachedHandlerTarget != null",
                                  "isLocalMutPassthrough(cachedHandlerTarget)",
                                  "cachedActTarget != null",
                                  "actTargetMatches(actFn, cachedActTarget)"},
                        excludeForUncached = true)
        public static Object doDirectBypass(Hash effectHash, Object actFn,
                @Cached("resolveHandler(effectHash)") MezzeRecord cachedHandler,
                @Cached("resolveMutateIndex(cachedHandler)") int cachedFieldIndex,
                @Cached("resolveCallTarget(cachedHandler, cachedFieldIndex)") CallTarget cachedHandlerTarget,
                @Cached("getEffectStableAssumption(effectHash)") com.oracle.truffle.api.Assumption effectStable,
                @Cached("resolveActCallTarget(actFn)") CallTarget cachedActTarget,
                @Cached("createInlined(cachedActTarget)") DirectCallNode actCallNode) {
            MezzeFunction fn = (MezzeFunction) actFn;
            Object[] cv = fn.getClosureValues();
            Object unit = MezzeRecord.empty();
            try {
                // Arity-specialized call to avoid varargs array allocation
                // for common closure counts (0–2 captures cover get, swap,
                // read_int, incr, int_to_mut, etc.). Graal EA scalarizes
                // the varargs array in the default branch too, but explicit
                // cases help in interpreter mode and early compilation tiers.
                return switch (cv.length) {
                    case 0 -> actCallNode.call(unit);
                    case 1 -> actCallNode.call(cv[0], unit);
                    case 2 -> actCallNode.call(cv[0], cv[1], unit);
                    default -> {
                        Object[] args = new Object[cv.length + 1];
                        System.arraycopy(cv, 0, args, 0, cv.length);
                        args[cv.length] = unit;
                        yield actCallNode.call(args);
                    }
                };
            } catch (com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
                if (com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.insideBdslHandle()) {
                    throw abort;
                }
                throw new com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException(
                    new com.mezze.interpreter.nodes.continuation.OpCall("mutate", null, effectHash));
            }
        }

        // Cached handler-dispatch path — still needed for DebugLocalMut
        // and as fallback when the bypass can't fire.
        @Specialization(assumptions = "effectStable",
                        guards = {"cachedHandler != null", "cachedTarget != null"},
                        excludeForUncached = true)
        public static Object doCached(Hash effectHash, Object actFn,
                @Cached("resolveHandler(effectHash)") MezzeRecord cachedHandler,
                @Cached("resolveMutateIndex(cachedHandler)") int cachedFieldIndex,
                @Cached("resolveCallTarget(cachedHandler, cachedFieldIndex)") CallTarget cachedTarget,
                @Cached("resolveClosureValues(cachedHandler, cachedFieldIndex)") Object[] cachedCV,
                @Cached("getEffectStableAssumption(effectHash)") com.oracle.truffle.api.Assumption effectStable,
                @Cached("createInlined(cachedTarget)") DirectCallNode callNode,
                @Cached("resolveActWrapperShape()") MezzeRecord.Shape actShape) {
            MezzeRecord wrapper = MezzeRecord.withAllObjects(actShape, new Object[] { actFn });
            Object[] opArgs = new Object[cachedCV.length + 1];
            System.arraycopy(cachedCV, 0, opArgs, 0, cachedCV.length);
            opArgs[cachedCV.length] = wrapper;
            try {
                return callNode.call(opArgs);
            } catch (com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.AbortPerformException abort) {
                if (com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.insideBdslHandle()) {
                    throw abort;
                }
                throw new com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.TaskUnhandledOpException(
                    new com.mezze.interpreter.nodes.continuation.OpCall("mutate", wrapper, effectHash));
            }
        }

        // Fallback — full chain dispatch through YieldDispatch.
        @Specialization
        public static Object doGeneric(Hash effectHash, Object actFn,
                @Cached(value = "resolveActWrapperShape()", allowUncached = true)
                    MezzeRecord.Shape actShape,
                @Cached(value = "initFieldIndex()", allowUncached = true, dimensions = 1)
                    int[] fieldIndexBox) {
            MezzeRecord wrapper = MezzeRecord.withAllObjects(actShape, new Object[] { actFn });
            return YieldDispatch.dispatch(effectHash, "mutate", wrapper, fieldIndexBox);
        }

        static int[] initFieldIndex() {
            return new int[] { -1 };
        }

        @TruffleBoundary
        static MezzeRecord resolveHandler(Hash effectHash) {
            return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .peekHandlerForEffect(effectHash);
        }

        @TruffleBoundary
        static com.oracle.truffle.api.Assumption getEffectStableAssumption(Hash effectHash) {
            return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .getEffectStableAssumption(effectHash);
        }

        static int resolveMutateIndex(MezzeRecord handler) {
            return handler.getFieldIndex("mutate");
        }

        static CallTarget resolveCallTarget(MezzeRecord handler, int fieldIndex) {
            if (fieldIndex < 0) return null;
            Object val = handler.getField(fieldIndex);
            if (val instanceof MezzeFunction fn) return fn.getCallTarget();
            return null;
        }

        static Object[] resolveClosureValues(MezzeRecord handler, int fieldIndex) {
            if (fieldIndex < 0) return new Object[0];
            Object val = handler.getField(fieldIndex);
            if (val instanceof MezzeFunction fn) return fn.getClosureValues();
            return new Object[0];
        }

        static DirectCallNode createInlined(CallTarget target) {
            DirectCallNode n = DirectCallNode.create(target);
            n.forceInlining();
            return n;
        }

        static MezzeRecord.Shape resolveActWrapperShape() {
            return ShapeCache.getOrCreateAllObjects(new String[] { "act" });
        }

        // ---- LocalMut bypass helpers ----

        static CallTarget resolveActCallTarget(Object actFn) {
            if (actFn instanceof MezzeFunction fn) return fn.getCallTarget();
            return null;
        }

        static boolean actTargetMatches(Object actFn, CallTarget cachedActTarget) {
            return actFn instanceof MezzeFunction fn
                && fn.getCallTarget() == cachedActTarget;
        }

        @TruffleBoundary
        static boolean isLocalMutPassthrough(CallTarget handlerMutateTarget) {
            if (!localMutTargetResolved) resolveLocalMutTarget();
            return handlerMutateTarget != null
                && knownLocalMutMutateTarget != null
                && handlerMutateTarget == knownLocalMutMutateTarget;
        }

        @TruffleBoundary
        private static synchronized void resolveLocalMutTarget() {
            if (localMutTargetResolved) return;
            try {
                var pre = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE
                    .getPreloweredFunctions();
                Hash candidateHash = null;
                for (var e : pre.entrySet()) {
                    var fn = e.getValue();
                    if ("mutate".equals(fn.getName())
                            && fn.getSourceFile() != null
                            && fn.getSourceFile().endsWith("mutation.mz")) {
                        // LocalMut.mutate = { act } -> act {}  (indirect call body)
                        // DebugLocalMut.mutate = { act } -> __native_debug_... (native call body)
                        if (!(fn.getBody() instanceof com.mezze.language.truffleir.CallNativeIR)) {
                            candidateHash = e.getKey();
                            break;
                        }
                    }
                }
                if (candidateHash != null) {
                    knownLocalMutMutateTarget = MezzeLanguage.get(null)
                        .getCachedStdlibCallTarget(candidateHash);
                }
            } catch (Exception e) {
                // Resolution failed — leave null, bypass won't activate
            }
            localMutTargetResolved = true;
        }
    }

    /**
     * Read a name contributed by the effect-handler runtime (one of
     * {@code resume}, {@code abort}, {@code scope}). Delegates to a
     * cached {@link com.mezze.interpreter.nodes.effect.EffectBindingNode}
     * whose {@code execute} reads {@link com.mezze.interpreter.nodes.continuation.HandlerChainRegistry#dispatching}
     * for resume/abort, or {@link com.mezze.interpreter.runtime.MezzeContext}
     * for scope.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.EffectBindingIR}.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class EffectBinding {
        /**
         * Fast path for resume/abort: when the handler chain is stable
         * (same CyclicAssumption PerformD.doCached guards on), we know
         * no dispatch fiber is set — the doCached path never enters
         * dispatch mode. So resume → singleton fallThroughResumeFn,
         * abort → singleton makeAbortFunction, with zero ThreadLocal
         * probes and zero @TruffleBoundary crossings. PE constant-folds
         * these to the singleton function pointer.
         *
         * <p>The guard resolves {@link EffectBindingKind} from the name
         * once at specialization time (the name is a @ConstantOperand,
         * so it's a compilation constant). The cached singleton is the
         * function object PE sees as a constant — no dispatch, no
         * ThreadLocal, no boundary.
         *
         * <p>This eliminates the last @TruffleBoundary on the hot
         * op-call path: previously EffectBindingNode.lookupOnFiber was
         * called for every resume/abort read inside an inlined handler
         * body, crossing a boundary to call
         * HandlerChainRegistry.dispatching() only to return the same
         * singleton every time.
         */
        @Specialization(assumptions = "handlerStable",
                        guards = {"isResumeOrAbortKind(name)"},
                        excludeForUncached = true)
        public static Object doCachedResumeAbort(
                String name,
                @Cached("getHandlerStableAssumption()") com.oracle.truffle.api.Assumption handlerStable,
                @Cached("resolveSingleton(name)") Object singleton) {
            return singleton;
        }

        /**
         * Fallback: handler chain unstable or SCOPE binding. Delegates
         * to the full EffectBindingNode which crosses @TruffleBoundary
         * to read the dispatch fiber or context.
         */
        @Specialization
        public static Object doGeneric(
                String name,
                @Cached(value = "createBinding(name)", allowUncached = true)
                    com.mezze.interpreter.nodes.effect.EffectBindingNode binding) {
            return binding.execute(null);
        }

        /**
         * Guard: true for RESUME or ABORT bindings — the two kinds
         * whose singleton functions can be constant-folded when the
         * handler chain is stable. Resolves via
         * {@link EffectBindingKind#fromName} so the name set is
         * maintained in one place (ScopedEffectConstants).
         */
        static boolean isResumeOrAbortKind(String name) {
            com.mezze.language.ast.EffectBindingKind kind =
                com.mezze.language.ast.EffectBindingKind.Companion.fromName(name);
            return kind == com.mezze.language.ast.EffectBindingKind.RESUME
                || kind == com.mezze.language.ast.EffectBindingKind.ABORT;
        }

        /**
         * Resolve the singleton function for a resume/abort binding.
         * Called once at specialization time — the result becomes a
         * compilation constant.
         */
        static Object resolveSingleton(String name) {
            com.mezze.language.ast.EffectBindingKind kind =
                com.mezze.language.ast.EffectBindingKind.Companion.fromName(name);
            if (kind == com.mezze.language.ast.EffectBindingKind.RESUME) {
                return YieldDispatch.fallThroughResumeFnPublic();
            } else if (kind == com.mezze.language.ast.EffectBindingKind.ABORT) {
                return com.mezze.interpreter.nodes.builtin.EffectHandlerDriver.makeAbortFunction();
            }
            // SCOPE or unknown — should not reach here due to guard,
            // but return null defensively (fallback handles it).
            return null;
        }

        @TruffleBoundary
        static com.oracle.truffle.api.Assumption getHandlerStableAssumption() {
            return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
                .getHandlerChainStableAssumption();
        }

        /** `@TruffleBoundary` — Node.<init> is neverPartOfCompilation. #402. */
        @TruffleBoundary
        static com.mezze.interpreter.nodes.effect.EffectBindingNode createBinding(String name) {
            return new com.mezze.interpreter.nodes.effect.EffectBindingNode(name);
        }
    }

    /**
     * Boundary helper: fold an interleaved literal/expression parts
     * array into a single String. Null entries in {@code parts} pull
     * from {@code exprValues} in order; non-null entries are literal
     * chunks. Non-string expression values go through
     * {@link String#valueOf(Object)}.
     */
    @TruffleBoundary
    private static String buildInterpolatedString(String[] parts, Object[] exprValues) {
        StringBuilder sb = new StringBuilder();
        int exprIdx = 0;
        for (String p : parts) {
            if (p == null) {
                sb.append(String.valueOf(exprValues[exprIdx++]));
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /**
     * Boundary helper: fold interleaved spread sources + explicit
     * fields into a new MezzeRecord. Later entries in insertion order
     * override same-named earlier ones — matches
     * {@link com.mezze.interpreter.nodes.record.RecordSpreadNode}
     * merge semantics.
     */
    @TruffleBoundary
    private static MezzeRecord buildSpreadRecord(String[] entryNames, Object[] values) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entryNames.length; i++) {
            String name = entryNames[i];
            if ("__spread__".equals(name)) {
                Object v = values[i];
                if (!(v instanceof MezzeRecord src)) {
                    throw new RuntimeException("Cannot spread non-record value");
                }
                Shape s = src.getShape();
                for (int j = 0; j < s.getFieldCount(); j++) {
                    String fieldName = s.getFieldName(j);
                    merged.put(fieldName, src.getField(fieldName));
                }
            } else {
                merged.put(name, values[i]);
            }
        }
        String[] resultNames = merged.keySet().toArray(new String[0]);
        Object[] resultValues = merged.values().toArray(new Object[0]);
        return buildAllObjectRecord(resultNames, resultValues);
    }

    /**
     * Boundary helper: apply update fields to a source record. Preserves
     * the source record's field order + adds any brand-new update
     * names at the tail, all in Object storage. Overrides for an
     * existing name replace that field's value; new names extend the
     * shape.
     */
    @TruffleBoundary
    private static MezzeRecord buildUpdatedRecord(MezzeRecord source, String[] updateNames, Object[] updateValues) {
        Shape srcShape = source.getShape();
        int srcCount = srcShape.getFieldCount();
        // Compute final field name list: source fields (in order), then
        // any update names not already in source.
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(srcCount + updateNames.length);
        for (int i = 0; i < srcCount; i++) {
            merged.put(srcShape.getFieldName(i), source.getField(srcShape.getFieldName(i)));
        }
        for (int i = 0; i < updateNames.length; i++) {
            merged.put(updateNames[i], updateValues[i]);
        }
        String[] resultNames = merged.keySet().toArray(new String[0]);
        Object[] resultValues = merged.values().toArray(new Object[0]);
        return buildAllObjectRecord(resultNames, resultValues);
    }

    @TruffleBoundary
    private static MezzeRecord buildAllObjectRecord(String[] fieldNames, Object[] values) {
        Shape shape = com.mezze.interpreter.values.ShapeCache.getOrCreateAllObjects(fieldNames);
        Object[] objectFields = new Object[fieldNames.length];
        System.arraycopy(values, 0, objectFields, 0, fieldNames.length);
        return new MezzeRecord(shape, EMPTY_LONGS, EMPTY_DOUBLES, objectFields);
    }
}
