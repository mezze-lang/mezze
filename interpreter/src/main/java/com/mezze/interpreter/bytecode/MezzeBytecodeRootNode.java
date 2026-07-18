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
    enableTagInstrumentation = true
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
    public static final class MakeLambda {
        @Specialization
        public static MezzeFunction doMake(CallTarget target, String name, @Variadic Object[] captures) {
            return new MezzeFunction(target, name, captures);
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
    public static final class VariantTagEquals {
        @Specialization
        public static boolean doVariant(int expectedTag, MezzeVariant variant) {
            return variant.getTag() == expectedTag;
        }

        @Specialization(guards = "!isVariant(other)")
        public static boolean doNonVariant(int expectedTag, Object other) {
            return false;
        }

        protected static boolean isVariant(Object obj) {
            return obj instanceof MezzeVariant;
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
