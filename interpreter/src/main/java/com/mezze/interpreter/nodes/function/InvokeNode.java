package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Function invocation node - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. DirectCallNode caching for monomorphic/polymorphic calls
 * 2. ZERO-CLOSURE FAST PATH - no closure copying overhead
 * 3. Cached closure length for array size computation
 * 4. @ExplodeLoop for argument evaluation - fully unrolled by JIT
 * 5. Inline loop for closure copy (JIT unrolls small loops better)
 * 6. Cached argument arrays to avoid allocation in hot paths
 *
 * Inline caching:
 * - Zero-closure: No closure handling at all (fastest)
 * - Monomorphic: DirectCallNode + cached array size
 * - Polymorphic: Up to 3 cached DirectCallNodes
 * - Megamorphic: IndirectCallNode (slowest)
 */
@NodeChild("functionNode")
@NodeInfo(shortName = "invoke")
@SuppressWarnings("truffle-splitting")
public abstract class InvokeNode extends MezzeNode {

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    // Singleton empty array used by the zero-arg/zero-closure fast path.
    // Sharing is safe because the callee never mutates the args array.
    // Kept as a small array (length 1) rather than a single Object[] so
    // the access pattern at line ~127 (CACHED_ARRAYS[0]) stays unchanged
    // — and so future no-arg specializations could reuse the slot.
    private static final Object[][] CACHED_ARRAYS = { new Object[0] };

    protected InvokeNode(MezzeNode[] argumentNodes) {
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    /**
     * Guard methods for argument count checking in specializations.
     * These are protected so they can be accessed by Truffle DSL.
     * Marked @Idempotent because argumentCount is final and never changes.
     */
    @Idempotent
    protected final boolean hasZeroArgs() {
        return argumentCount == 0;
    }

    @Idempotent
    protected final boolean hasOneArg() {
        return argumentCount == 1;
    }

    /**
     * Check if a function with partial args has all parameters satisfied.
     * Returns true if the function has partial args AND the new record completes it.
     */
    protected static boolean isCompletePartialCall(MezzeFunction function, Object arg) {
        if (!function.hasPartialArgs()) return false;
        if (!(arg instanceof MezzeRecord record)) return false;
        return function.hasAllParameters(record);
    }

    /**
     * Check if a function call is a partial application.
     * Returns true if the record doesn't provide all required parameters.
     */
    protected static boolean isPartialApplication(MezzeFunction function, Object arg) {
        if (function.getParameterNames().length == 0) return false;
        if (!(arg instanceof MezzeRecord record)) return false;
        return !function.hasAllParameters(record);
    }

    /**
     * FASTEST PATH: Zero-closure function call with zero arguments.
     * No allocation at all - uses singleton empty array.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!function.hasClosure()", "hasZeroArgs()"},
        limit = "6"
    )
    protected Object callDirectNoClosureNoArgs(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") com.oracle.truffle.api.CallTarget cachedTarget,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        return callNode.call(CACHED_ARRAYS[0]);
    }

    /**
     * FAST PATH: Zero-closure function call with single argument.
     * Single element array - JIT scalar-replaces this.
     * Note: doesn't handle partial application - that's handled by separate specializations.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!function.hasClosure()", "hasOneArg()",
                  "function.getParameterNames().length == 0"},  // No partial app if no param names
        limit = "6"
    )
    protected Object callDirectNoClosureOneArg(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") com.oracle.truffle.api.CallTarget cachedTarget,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        return callNode.call(argumentNodes[0].execute(frame));
    }

    /**
     * Partial application: function with parameter names receiving incomplete record.
     * Returns a new function with the partial arguments captured.
     *
     * <p>The hot path (all params present, no partial app) goes through
     * an {@link IndirectCallNode} that participates in Truffle's
     * profiling and inlining infrastructure, instead of a
     * {@code @TruffleBoundary} opaque call.
     */
    @Specialization(
        guards = {"hasOneArg()", "function.getParameterNames().length > 0"}
    )
    protected Object callWithPartialAppCheck(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached @Exclusive IndirectCallNode callNode
    ) {
        Object arg = argumentNodes[0].execute(frame);

        // Most calls pass a record - inject probability hint
        if (CompilerDirectives.injectBranchProbability(0.05, !(arg instanceof MezzeRecord))) {
            // Not a record - just call directly (this handles {} case) - RARE
            return callWithArg(function, arg, callNode);
        }
        MezzeRecord record = (MezzeRecord) arg;

        // Partial args are rare - most functions are called with complete args
        if (CompilerDirectives.injectBranchProbability(0.1, function.hasPartialArgs())) {
            return handlePartialApplication(function, record, callNode);
        }

        // Check if this is a new partial application - usually all params present
        if (CompilerDirectives.injectBranchProbability(0.15, !function.hasAllParameters(record))) {
            return function.withPartialArgs(record);
        }

        // All parameters present - call directly - MOST COMMON PATH
        return callWithArg(function, record, callNode);
    }

    /**
     * Build [closures..., arg] and call through the IndirectCallNode.
     * Not {@code @TruffleBoundary}: PE can see through this and
     * scalar-replace the array when the closure length is constant.
     */
    private static Object callWithArg(MezzeFunction function, Object arg,
                                       IndirectCallNode callNode) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + 1];
        System.arraycopy(closureValues, 0, arguments, 0, closureLength);
        arguments[closureLength] = arg;
        return callNode.call(function.getCallTarget(), arguments);
    }

    /**
     * Handle partial application paths (rare). Kept behind a boundary
     * because buildCompleteRecord / hasAllParameters iterate record
     * fields and are not PE-friendly.
     */
    @TruffleBoundary
    private static Object handlePartialApplication(MezzeFunction function,
                                                    MezzeRecord record,
                                                    IndirectCallNode callNode) {
        if (function.hasAllParameters(record)) {
            MezzeRecord completeRecord = function.buildCompleteRecord(record);
            return callWithArg(function, completeRecord, callNode);
        } else {
            return function.withPartialArgs(record);
        }
    }

    /**
     * Zero-closure function call with multiple arguments.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!function.hasClosure()"},
        limit = "6"
    )
    @ExplodeLoop
    protected Object callDirectNoClosure(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") com.oracle.truffle.api.CallTarget cachedTarget,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        Object[] arguments = new Object[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            arguments[i] = argumentNodes[i].execute(frame);
        }
        return callNode.call(arguments);
    }

    /**
     * Monomorphic call with closure - same function every time.
     * Caches both the call target AND the closure length for optimal performance.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "function.getClosureValues().length == cachedClosureLength"},
        limit = "6"
    )
    @ExplodeLoop
    protected Object callDirectCached(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") com.oracle.truffle.api.CallTarget cachedTarget,
            @Cached("function.getClosureValues().length") int cachedClosureLength,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        // Total size is constant after JIT - enables scalar replacement
        Object[] arguments = new Object[cachedClosureLength + argumentCount];

        // Copy closure values - @ExplodeLoop unrolls based on cachedClosureLength
        Object[] closureValues = function.getClosureValues();
        for (int i = 0; i < cachedClosureLength; i++) {
            arguments[i] = closureValues[i];
        }

        // Evaluate arguments - @ExplodeLoop unrolls based on argumentCount
        for (int i = 0; i < argumentCount; i++) {
            arguments[cachedClosureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(arguments);
    }

    /**
     * Monomorphic call with varying closure (less common).
     */
    @Specialization(
        guards = "function.getCallTarget() == cachedTarget",
        replaces = "callDirectCached",
        limit = "6"
    )
    @ExplodeLoop
    protected Object callDirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") com.oracle.truffle.api.CallTarget cachedTarget,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + argumentCount];

        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(arguments);
    }

    /**
     * Megamorphic call - different functions (rare).
     */
    @Specialization(replaces = "callDirect")
    @ReportPolymorphism.Megamorphic
    @ExplodeLoop
    protected Object callIndirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached @Exclusive IndirectCallNode callNode
    ) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + argumentCount];

        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(function.getCallTarget(), arguments);
    }

    /**
     * Handle MezzeRecord as a callable (effect handler operations).
     */
    @Specialization(
        guards = {"record == cachedRecord", "!isEmptyRecord(record)"},
        limit = "6"
    )
    @ExplodeLoop
    protected Object callRecordCached(
            VirtualFrame frame,
            MezzeRecord record,
            @Cached("record") MezzeRecord cachedRecord,
            @Cached("extractFunction(record)") MezzeFunction function,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + argumentCount];

        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(arguments);
    }

    /**
     * Handle MezzeRecord as callable - uncached fallback.
     */
    @Specialization(guards = "!isEmptyRecord(record)", replaces = "callRecordCached")
    @ExplodeLoop
    protected Object callRecord(
            VirtualFrame frame,
            MezzeRecord record,
            @Cached @Exclusive IndirectCallNode callNode
    ) {
        MezzeFunction function = extractFunction(record);
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments = new Object[closureLength + argumentCount];

        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(function.getCallTarget(), arguments);
    }

    /**
     * Handle the empty record {} (unit). Invoking unit returns unit itself —
     * this preserves the historical MezzeUnit invoke-returns-itself behavior
     * now that unit is unified onto the empty-record singleton.
     */
    @Specialization(guards = "isEmptyRecord(record)")
    protected Object callEmptyRecord(
            VirtualFrame frame,
            MezzeRecord record
    ) {
        return record;
    }

    protected static boolean isEmptyRecord(MezzeRecord record) {
        return record.getFieldCount() == 0;
    }

    /**
     * Boolean "call" - when .not/.and/.or on a Boolean returns the result directly,
     * the subsequent "invoke" just passes through the Boolean value.
     * This supports patterns like: value.not {} where .not returns the negated boolean
     * and {} is the "argument" that gets ignored.
     */
    @Specialization(guards = "isBoolean(function)")
    protected Object callBoolean(
            VirtualFrame frame,
            Object function
    ) {
        return function;
    }

    protected static boolean isBoolean(Object value) {
        return value instanceof Boolean;
    }

    /**
     * Fallback for unexpected types.
     */
    @Fallback
    protected Object callFallback(
            VirtualFrame frame,
            Object function
    ) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Cannot invoke non-function value: " +
            (function == null ? "null" : function.getClass().getName() + " = " + function));
    }

    @TruffleBoundary
    protected static MezzeFunction extractFunction(MezzeRecord record) {
        throw new RuntimeException("Cannot call record directly: " + record);
    }

    public static InvokeNode create(MezzeNode functionNode, MezzeNode[] argumentNodes) {
        return InvokeNodeGen.create(argumentNodes, functionNode);
    }
}
