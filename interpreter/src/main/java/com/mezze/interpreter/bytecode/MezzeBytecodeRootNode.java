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
import com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType;
import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.mezze.interpreter.values.MezzeVariant;
import com.mezze.interpreter.values.MezzeVariant.TagRegistry;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

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
    boxingEliminationTypes = {long.class, double.class, boolean.class},
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
public abstract class MezzeBytecodeRootNode extends RootNode implements BytecodeRootNode {

    protected MezzeBytecodeRootNode(MezzeLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
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
     * {@code CallIndirect(ReadField(x, "to_dbg_str"|"to_str"),
     * MakeRecord{it=x})} into a single stringify op. BDSL emits this
     * via the peephole in {@code BytecodeCompiler.tryFoldInspectMethod};
     * the operand is the receiver, the boolean constant is dbgStyle
     * ("to_dbg_str" quotes strings + wraps chars; "to_str" is raw).
     *
     * Matches DirectInspectNode's shape so behaviour is identical to
     * the AST auto-derive.
     */
    @Operation
    @ConstantOperand(type = boolean.class)
    public static final class InlineScalarInspect {
        @Specialization
        public static String doLong(boolean dbgStyle, long v) { return Long.toString(v); }
        @Specialization
        public static String doDouble(boolean dbgStyle, double v) { return Double.toString(v); }
        @Specialization
        public static String doBool(boolean dbgStyle, boolean v) { return v ? "True" : "False"; }
        @Specialization(guards = "isChar(v)")
        public static String doChar(boolean dbgStyle, Object v) {
            char c = (Character) v;
            return dbgStyle ? "'" + c + "'" : String.valueOf(c);
        }
        @Specialization(guards = "isString(v)")
        public static String doString(boolean dbgStyle, Object v) {
            String s = (String) v;
            return dbgStyle ? "\"" + s + "\"" : s;
        }
        @Fallback
        @TruffleBoundary
        public static String doOther(boolean dbgStyle, Object v) {
            if (v == null) return "null";
            if (v instanceof com.mezze.interpreter.values.MezzeUnit) return "{}";
            return v.toString();
        }

        static boolean isChar(Object o) { return o instanceof Character; }
        static boolean isString(Object o) { return o instanceof String; }
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
         * splitting once the interpreter warms up.
         */
        @Specialization(guards = "callNode.getCallTarget() == target")
        public static Object doCached(
                CallTarget target,
                @Variadic Object[] args,
                @Cached("create(target)") DirectCallNode callNode) {
            return callNode.call(args);
        }

        /**
         * Uncached path — the {@code enableUncachedInterpreter=true}
         * flag requires every specialization to have an uncached
         * alternative. Falls through to a shared IndirectCallNode.
         */
        @Specialization(replaces = "doCached")
        public static Object doIndirect(
                CallTarget target,
                @Variadic Object[] args,
                @Cached IndirectCallNode indirectCallNode) {
            return indirectCallNode.call(target, args);
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
        @Specialization
        public static MezzeRecord doAllObjects(String[] fieldNames, @Variadic Object[] values) {
            return buildAllObjectRecord(fieldNames, values);
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

        // Fallback for records with unfamiliar shapes.
        @Specialization(replaces = "doRecordCached")
        public static Object doRecordGeneric(String fieldName, MezzeRecord record) {
            Object v = record.getField(fieldName);
            if (v == null) {
                return fallbackNotFound(record, fieldName);
            }
            return v;
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
        @Specialization(guards = "isVariantWithRecordPayload(receiver)")
        public static Object doVariantPayload(
                String fieldName,
                com.mezze.interpreter.values.MezzeVariant receiver) {
            Object payload = receiver.getPayloadValue();
            if (payload instanceof MezzeRecord rec) {
                Object v = rec.getField(fieldName);
                if (v != null) return v;
            }
            return fallbackNotFound(receiver, fieldName);
        }

        // Everything else — mirror CachedFieldAccessNode.accessNotRecord.
        // Ability-op chains where the receiver is a primitive Long/Double
        // that happens NOT to match a specific method (like the failing
        // `(x + y).add {...}` where AST also throws) hit this path.
        //
        // Group B (task #346): scalar-receiver `.to_dbg_str{}` /
        // `.to_str{}` on Long/Double/Bool/Str/Char also lands here but
        // AST's auto-derived Inspect impl produces a string. Fixing
        // this requires either (1) a peephole in BytecodeCompiler that
        // rewrites CallIndirect(ReadField(scalar, "to_dbg_str"), ...)
        // to a direct inline stringify, or (2) a MezzeFunction wrapper
        // returned from here. Both are nontrivial — parked. Users hit
        // this path only when the hasReadField gate is dropped, and
        // the gate stays on for now (see MezzeLanguage.isBdslSafeCallee).
        @Fallback
        public static Object doNotRecord(String fieldName, Object receiver) {
            throw new RuntimeException("Expected record for field '" + fieldName + "', got: " +
                (receiver == null ? "null" : receiver.getClass().getSimpleName()));
        }

        static boolean isBooleanMethod(String fieldName) {
            return "not".equals(fieldName) || "and".equals(fieldName) || "or".equals(fieldName);
        }

        static boolean isVariantWithRecordPayload(Object obj) {
            return obj instanceof com.mezze.interpreter.values.MezzeVariant v
                    && v.hasPayload()
                    && v.getPayloadValue() instanceof MezzeRecord;
        }

        protected static int resolveLocationIndex(Shape shape, int fieldIndex) {
            if (fieldIndex < 0) return -1;
            FieldLocation loc = shape.getFieldLocation(fieldIndex);
            return loc.index;
        }

        @TruffleBoundary
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
        @Specialization
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
        @Specialization
        public static Object doInvoke(NativeHandler handler, @Variadic Object[] args) {
            return handler.invoke(new PositionalArgs(args, null, null));
        }
    }

    /**
     * Legacy record-ABI native call: takes the native name as a constant
     * operand and a MezzeRecord arg on the stack, delegates to
     * {@link com.mezze.interpreter.nodes.builtin.NativeDispatch#dispatchByName}
     * with a fresh IndirectCallNode + the surrounding
     * {@link com.oracle.truffle.api.nodes.Node} passed in as {@code $node}
     * (for async handlers that need TruffleLanguage.Env).
     *
     * Unlocks the 89% of misses that were CallNativeIR:record-ABI. Not
     * as fast as CallNativeScalar (still allocates/unpacks a record per
     * call) but routes the surrounding function through BDSL.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class CallNativeRecord {
        @Specialization
        public static Object doInvoke(String name, Object arg,
                @Cached(value = "createIndirect()", allowUncached = true)
                    com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                @Bind com.oracle.truffle.api.nodes.Node node) {
            if (!(arg instanceof com.mezze.interpreter.values.MezzeRecord record)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException(name + " expects record argument, got: "
                        + (arg == null ? "null" : arg.getClass().getName()));
            }
            // The BDSL node has no "MezzeNode" wrapper — pass null; the
            // async handlers only use it to read the Env, which they can
            // reach via TruffleLanguage.get(Node).
            com.mezze.interpreter.nodes.MezzeNode mezzeNode = null;
            return com.mezze.interpreter.nodes.builtin.NativeDispatch
                    .dispatchByName(name, record, callNode, mezzeNode);
        }
        static com.oracle.truffle.api.nodes.IndirectCallNode createIndirect() {
            return com.oracle.truffle.api.nodes.IndirectCallNode.create();
        }
    }

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
        @Specialization
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
        public static Object doThrow() {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
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
        public static MezzeVariant doNoPayload(int tag, String tagName) {
            TagRegistry registry = TagRegistry.getGlobal();
            registry.getOrCreateTag(tagName);
            return MezzeVariant.noPayload(registry, tag);
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
        public static MezzeVariant doSinglePayload(int tag, String tagName, Object payload) {
            TagRegistry registry = TagRegistry.getGlobal();
            registry.getOrCreateTag(tagName);
            return MezzeVariant.singlePayload(registry, tag, payload);
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

        @Specialization(replaces = {"doLong", "doDouble", "doBoolean"})
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
            if ("Nil".equals(tagName)) return list.isEmpty();
            if ("Cons".equals(tagName)) return !list.isEmpty();
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
     * {@code executeWith}. Same lifecycle as AST — Loom fiber suspend
     * when a driver is present, otherwise chain-dispatch or a
     * MezzeSuspend thunk. See docs/perf/bdsl-effects-path-a-2026-07.md.
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

        static com.mezze.interpreter.nodes.effect.OpCallNode createOpCall(
                String opName, Hash effectHash) {
            return com.mezze.interpreter.nodes.effect.OpCallNode.create(
                    opName, effectHash,
                    new com.mezze.interpreter.nodes.literal.UnitLiteralNode());
        }
    }

    /**
     * Path D perform-op: pop the arg and dispatch directly against the
     * current thread's handler chain via {@link BdslYieldDispatch#dispatch}.
     * No fiber, no Loom continuation, no reflection — the dispatch pushes
     * a lightweight per-thread dispatch-only fiber with resume/abort
     * handles so existing {@code EffectBindingIR} reads in the impl body
     * work unchanged. See {@link BdslYieldDispatch} for semantics
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
        @Specialization
        public static Object doPerformD(Hash effectHash, String opName, Object arg) {
            return BdslYieldDispatch.dispatch(effectHash, opName, arg);
        }
    }

    /**
     * Path D8 (Handle @Operation, endgame Shape B — see
     * docs/perf/bdsl-effects-path-d-2026-07.md §D8).
     *
     * <p>Push the handler value (popped from the operand stack) onto the
     * per-thread handler chain, tagged with {@code effectHash} so
     * {@link BdslYieldDispatch#dispatch} finds it by (effectHash, opName).
     * Pairs with {@link PopHandlerChain}. The emit-site brackets the body
     * with these two ops inside a {@code TryCatchOtherwise} so both normal
     * and abort exits pop the chain — no fiber, no lambda, no dispatch
     * object, no heap alloc per handle.
     *
     * <p>Void op: consumes the handler, produces nothing.
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
            // Scope hook: initialize_scope creates the scope value; store
            // (scope, prevContextScope) on the D8 scope stack for the
            // paired PopHandlerChain to invoke finalize_scope + restore.
            // No-alloc common case (no hooks): the stack push writes null
            // and PopHandlerChain sees the null and skips.
            Object initFn = rec.getField(
                com.mezze.language.ast.ScopedEffectConstants.INITIALIZE_SCOPE);
            Object scope = null;
            Object prevScope = null;
            if (initFn instanceof MezzeFunction fn) {
                scope = D8ScopeHooks.invokeInitializeScope(fn);
                prevScope = D8ScopeHooks.installScope(node, scope);
            }
            com.mezze.interpreter.nodes.continuation.EffectFiber
                .pushHandlerOnChain(rec, effectHash);
            com.mezze.interpreter.nodes.continuation.EffectFiber
                .enterBdslHandleWithScope(scope, prevScope);
        }
    }

    /**
     * Path D8: pop the innermost handler off the per-thread chain.
     * Emitted twice per Handle by the compiler — once in the "otherwise"
     * (normal exit) branch of TryCatchOtherwise and once in the "catch"
     * (abort exit) branch — so the chain is always balanced regardless of
     * how the body exits.
     */
    @Operation
    public static final class PopHandlerChain {
        @TruffleBoundary
        @Specialization
        public static void doPop(@Bind Node node) {
            // Read scope hook state from the D8 stack BEFORE popping the
            // chain (the chain entry still points at the handler whose
            // finalize_scope we want to invoke).
            var handler = com.mezze.interpreter.nodes.continuation.EffectFiber
                .peekTopHandlerRecord();
            var frame = com.mezze.interpreter.nodes.continuation.EffectFiber
                .peekBdslHandleScope();
            com.mezze.interpreter.nodes.continuation.EffectFiber
                .popHandlerOffChain();
            com.mezze.interpreter.nodes.continuation.EffectFiber
                .exitBdslHandle();
            if (frame != null && handler != null) {
                Object finFn = handler.getField(
                    com.mezze.language.ast.ScopedEffectConstants.FINALIZE_SCOPE);
                if (finFn instanceof MezzeFunction fn) {
                    D8ScopeHooks.invokeFinalizeScope(fn, frame.scope);
                }
                D8ScopeHooks.restoreScope(node, frame.prevScope);
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
        @Specialization
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
                // and unblocks migrating OpCallNode/BdslYieldDispatch to
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
                com.mezze.interpreter.nodes.continuation.EffectFiber.snapshotHandlerChainEntries();
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
        @Specialization
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
        @Specialization
        public static Object doUnwrap(Object exception) {
            if (exception instanceof SuspendedInBlockException sib) {
                return sib.suspended;
            }
            if (exception instanceof RuntimeException re) throw re;
            if (exception instanceof Error err) throw err;
            throw new RuntimeException((Throwable) exception);
        }
    }

    /**
     * Fast path for {@code perform Mut mutate { act = <lambda> }}: pop
     * the raw act lambda (no wrapper record on the operand stack). The
     * cached OpCallNode was built via
     * {@link com.mezze.interpreter.nodes.effect.OpCallNode#createMutMutateActFast}
     * and knows to wrap the value into the one-field {act = ...} record
     * before dispatching.
     *
     * <p>Preserves the AST's Mut marker fast path — must not regress the
     * LocalMut hot loop.
     */
    @Operation
    @ConstantOperand(type = Hash.class)
    public static final class PerformMutMutateAct {
        @Specialization
        public static Object doPerform(
                Hash effectHash,
                Object actFn,
                @Cached(value = "createOpCall(effectHash)", allowUncached = true)
                    com.mezze.interpreter.nodes.effect.OpCallNode opCall) {
            return opCall.executeMutMutateActFast(actFn);
        }

        static com.mezze.interpreter.nodes.effect.OpCallNode createOpCall(Hash effectHash) {
            return com.mezze.interpreter.nodes.effect.OpCallNode.createMutMutateActFast(
                    effectHash,
                    new com.mezze.interpreter.nodes.literal.UnitLiteralNode());
        }
    }

    /**
     * Read a name contributed by the effect-handler runtime (one of
     * {@code resume}, {@code abort}, {@code scope}). Delegates to a
     * cached {@link com.mezze.interpreter.nodes.effect.EffectBindingNode}
     * whose {@code execute} reads {@link com.mezze.interpreter.nodes.continuation.EffectFiber#dispatching}
     * for resume/abort, or {@link com.mezze.interpreter.runtime.MezzeContext}
     * for scope.
     *
     * <p>Lowers {@link com.mezze.language.truffleir.EffectBindingIR}.
     */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class EffectBinding {
        @Specialization
        public static Object doBinding(
                String name,
                @Cached(value = "createBinding(name)", allowUncached = true)
                    com.mezze.interpreter.nodes.effect.EffectBindingNode binding) {
            // EffectBindingNode.execute doesn't touch the frame; safe to pass null.
            return binding.execute(null);
        }

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
        FieldLocation[] locations = new FieldLocation[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            locations[i] = new FieldLocation(StorageType.OBJECT, i);
        }
        Shape shape = new Shape(fieldNames, locations);
        long[] longFields = new long[0];
        double[] doubleFields = new double[0];
        Object[] objectFields = new Object[fieldNames.length];
        System.arraycopy(values, 0, objectFields, 0, fieldNames.length);
        return new MezzeRecord(shape, longFields, doubleFields, objectFields);
    }
}
