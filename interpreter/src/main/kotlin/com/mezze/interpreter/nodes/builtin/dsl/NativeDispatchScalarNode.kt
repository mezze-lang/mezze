package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.MezzeNode
import com.mezze.language.truffleir.SlotKind
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.UnexpectedResultException

/**
 * Scalar-ABI native call site.
 *
 * Evaluates the positional argument nodes in declared order, packs the
 * results into a small `Object[]`, and invokes the registered handler.
 * No `MezzeRecord` is allocated, no field-name lookup happens, and the
 * argKinds are read at construction time so PE can specialize the
 * per-arg cast inside the handler body.
 *
 * The `@ExplodeLoop` annotation on `execute` tells Truffle PE to unroll
 * the argument loop at the known fixed arity, turning each
 * `argNodes[i].execute(frame)` into a direct call. Together with the
 * handler body's typed accessors (`args.double(0)`), the JIT collapses
 * the dispatch to a straight-line call to the underlying Java method.
 *
 * The slow path (record-shaped natives) still routes through
 * `NativeDispatch`. This node is only used for ops registered via
 * `NativeOp(...)` in the DSL.
 */
@NodeInfo(shortName = "native-scalar")
class NativeDispatchScalarNode(
    @CompilerDirectives.CompilationFinal private val name: String,
    @Children private val argNodes: Array<MezzeNode>,
    @CompilerDirectives.CompilationFinal(dimensions = 1) private val argKinds: IntArray,
    @CompilerDirectives.CompilationFinal private val handler: NativeHandler,
    @CompilerDirectives.CompilationFinal private val needsNode: Boolean,
) : MezzeNode() {

    /**
     * Adopted at construction when the handler needs an
     * IndirectCallNode (HOF callbacks). Null otherwise so we don't
     * pay the @Child slot for ops that never use it.
     */
    @Child private var callNode: IndirectCallNode? = null

    @ExplodeLoop
    override fun execute(frame: VirtualFrame): Any {
        val n = argNodes.size
        val values = arrayOfNulls<Any>(n)
        // Per-arg typed evaluation. argKinds[i] is @CompilationFinal so
        // PE unrolls the loop and picks one branch per arg statically.
        // When the arg node also overrides executeLong/executeDouble
        // (e.g. DirectAddNode does), the primitive value flows in
        // unboxed form right up until we put it in the Object[] —
        // Truffle's escape analysis can then scalar-replace the
        // Long.valueOf / Double.valueOf box because it's consumed
        // immediately inside the handler body (which @CompilationFinal-
        // resolves to the lambda's per-arg `as Long` / `as Double` cast,
        // letting EA delete both halves of the box-unbox pair).
        // Without typed exec here, the arg's primitive result was first
        // boxed by `execute()`'s Object return convention, escaping the
        // EA-friendly site. (#103)
        for (i in 0 until n) {
            val v: Any = try {
                when (argKinds[i]) {
                    SlotKind.LONG.ordinal    -> argNodes[i].executeLong(frame)
                    SlotKind.DOUBLE.ordinal  -> argNodes[i].executeDouble(frame)
                    SlotKind.BOOLEAN.ordinal -> argNodes[i].executeBoolean(frame)
                    else                     -> argNodes[i].execute(frame)
                }
            } catch (ure: UnexpectedResultException) {
                // Typed exec saw a value of a different kind. Surface as the
                // raw result so the cast in the handler will throw a
                // ClassCastException at the right place (matching the
                // pre-typed-exec behavior).
                ure.result
            }
            values[i] = v
        }
        val args = if (needsNode) {
            // First call lazily creates and adopts the IndirectCallNode.
            // CompilerDirectives.transferToInterpreterAndInvalidate keeps
            // this off the hot path after warmup.
            if (callNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate()
                callNode = insert(IndirectCallNode.create())
            }
            PositionalArgs(values, callNode = callNode, callerNode = this)
        } else {
            PositionalArgs(values)
        }
        return handler.invoke(args)
    }

    companion object {
        /**
         * Build a scalar-native dispatch node.
         *
         * `argKinds` is the per-position [SlotKind] from the registered
         * `NativeSignature`. Caller has already pre-resolved the handler
         * via `NativeHandlers.handlerOf(name)`. The kinds are encoded as
         * a primitive IntArray (not a List<SlotKind>) so Truffle PE
         * inspects a compilation-final primitive when unrolling.
         *
         * Set `needsNode = true` for ops registered via `NativeOpWithNode`
         * (Async / effect-runtime ops that read `args.callerNode`).
         */
        @JvmStatic
        fun create(
            name: String,
            argNodes: Array<MezzeNode>,
            argKinds: List<SlotKind>,
            handler: NativeHandler,
            needsNode: Boolean = false,
        ): NativeDispatchScalarNode {
            val asInts = IntArray(argKinds.size) { argKinds[it].ordinal }
            return NativeDispatchScalarNode(name, argNodes, asInts, handler, needsNode)
        }
    }
}
