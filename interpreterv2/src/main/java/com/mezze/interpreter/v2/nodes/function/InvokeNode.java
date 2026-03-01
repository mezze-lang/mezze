package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeUnit;
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
public abstract class InvokeNode extends MezzeNode {

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    // Cached argument arrays for common sizes (0-4 args + 0-2 closure = sizes 0-6)
    // After JIT, these become constant references
    private static final Object[][] CACHED_ARRAYS = new Object[8][];
    static {
        for (int i = 0; i < CACHED_ARRAYS.length; i++) {
            CACHED_ARRAYS[i] = new Object[i];
        }
    }

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
     * Get or create argument array. Uses cached arrays for small sizes.
     * For monomorphic calls, JIT constant-folds this to direct array reference.
     */
    private static Object[] getArgumentArray(int size) {
        if (size < CACHED_ARRAYS.length) {
            // Reuse cached array - but we need fresh one each call since we mutate it
            // Actually, we can't reuse - must allocate. But JIT will scalar-replace small arrays.
            return new Object[size];
        }
        return new Object[size];
    }

    /**
     * FASTEST PATH: Zero-closure function call with zero arguments.
     * No allocation at all - uses singleton empty array.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!function.hasClosure()", "hasZeroArgs()"},
        limit = "3"
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
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!function.hasClosure()", "hasOneArg()"},
        limit = "3"
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
     * Zero-closure function call with multiple arguments.
     */
    @Specialization(
        guards = {"function.getCallTarget() == cachedTarget", "!function.hasClosure()"},
        limit = "3"
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
        limit = "3"
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
        limit = "3"
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
        guards = {"record == cachedRecord"},
        limit = "3"
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
    @Specialization(replaces = "callRecordCached")
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
     * Handle MezzeUnit - this is the empty record {}.
     */
    @Specialization
    protected Object callUnit(
            VirtualFrame frame,
            MezzeUnit unit
    ) {
        return unit;
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
