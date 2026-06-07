package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.language.truffleir.NativeParam
import com.mezze.language.truffleir.NativeSignature
import com.mezze.language.truffleir.NativeSignatures
import com.mezze.language.truffleir.SlotKind
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.nodes.IndirectCallNode

/**
 * Single source of truth for a native operation.
 *
 * The typechecker doesn't read this; it reads the Mezze-level signature
 * declared in the `types` package (e.g. `BasicTypes`, `CharTypes`). What
 * the registry adds is the **runtime** view:
 *
 *  - the canonical positional order of arguments at the IR boundary,
 *  - the per-arg `SlotKind` (LONG / DOUBLE / BOOLEAN / OBJECT), and
 *  - the body lambda that runs at execution time.
 *
 * The IR-builder uses `NativeSignatures.signatureOf(name)` (from `ast`) to
 * transpose a user record `{r = ey, l = ex}` into positional args in
 * declared order. The interpreter uses `NativeHandlers.handlerOf(name)`
 * (this module) to invoke the body on positional args without a
 * `MezzeRecord` ever existing.
 *
 * See docs/perf/scalar-native-abi-2026-06.md for the broader design.
 */

/**
 * Positional view of a native call's evaluated arguments.
 *
 * The implementation behind this interface is just an `Array<Any?>`, but
 * the typed accessors (`long(i)`, `double(i)`, …) keep the handler body
 * readable and let Truffle PE see the cast as a stable type profile.
 *
 * Handlers should call accessors in the same order as the declared
 * [NativeSignature.params]; mismatches indicate a registry bug.
 */
interface NativeArgs {
    fun long(i: Int): Long
    fun double(i: Int): Double
    fun bool(i: Int): Boolean
    fun string(i: Int): String
    fun obj(i: Int): Any
    /** Optional IndirectCallNode for HOF callbacks. Null when not needed. */
    val callNode: IndirectCallNode?
    /**
     * The calling node, set for ops registered as [NativeOpWithNode]
     * (mostly Async / effect-runtime ops that need `Node.getLanguage()`,
     * `Node.getEncapsulatingSourceSection()`, or a CompilationFinal owner
     * for `IndirectCallNode.create()`). Null on regular [NativeOp] calls
     * so the registry can't accidentally hand out a stale parent.
     */
    val callerNode: com.mezze.interpreter.nodes.MezzeNode?
}

/**
 * Concrete `NativeArgs` over an Object[] payload. Allocated by the
 * interpreter's scalar-native dispatch node per call. The array's slots
 * carry boxed primitives; the typed accessors cast on the way out.
 */
class PositionalArgs(
    @JvmField val values: Array<Any?>,
    override val callNode: IndirectCallNode? = null,
    override val callerNode: com.mezze.interpreter.nodes.MezzeNode? = null,
) : NativeArgs {
    override fun long(i: Int): Long = values[i] as Long
    override fun double(i: Int): Double = values[i] as Double
    override fun bool(i: Int): Boolean = values[i] as Boolean
    override fun string(i: Int): String = values[i] as String
    override fun obj(i: Int): Any = values[i]!!
}

/**
 * Handler body. Receives positional args, returns the op's result.
 * Result type matches [NativeSignature.returns] kind — Long for LONG,
 * Double for DOUBLE, Boolean for BOOLEAN, anything for OBJECT.
 */
fun interface NativeHandler {
    fun invoke(args: NativeArgs): Any
}

/** Convenience constructor mirroring the data-class shape of [NativeParam]. */
fun Param(name: String, kind: SlotKind): NativeParam = NativeParam(name, kind)

/**
 * Declare a native op. The constructor self-registers into [NativeHandlers]
 * (handler side, this module) and into [NativeSignatures] (signature side,
 * the `ast` module).
 *
 * Example:
 *
 *   val SQRT = NativeOp(
 *     "__native_float_sqrt",
 *     params = listOf(Param("n", SlotKind.DOUBLE)),
 *     returns = SlotKind.DOUBLE,
 *   ) { args -> PrimitiveNatives.floatSqrt(args.double(0)) }
 */
class NativeOp(
    name: String,
    params: List<NativeParam>,
    returns: SlotKind,
    private val handler: NativeHandler,
) {
    val signature: NativeSignature = NativeSignature(name, params, returns)

    init {
        // Publish to both registries from a single declaration site.
        NativeSignatures.register(signature)
        NativeHandlers.register(signature.name, handler, needsNode = false)
    }
}

/**
 * Declare a native op whose handler needs access to the calling
 * [com.mezze.interpreter.nodes.MezzeNode] — used by ops that:
 *
 *  - spawn fibers / threads (`Async.*`, scheduler internals) and call
 *    `TruffleLanguage.getCurrentContext(node.class)` or
 *    `Node.getEncapsulatingSourceSection()`.
 *  - create child Truffle nodes lazily (`IndirectCallNode.create()` for
 *    HOFs that don't know their callee shape ahead of time).
 *  - attach to the Truffle profiling / instrumentation stack.
 *
 * The handler reads the node via `args.callerNode!!` — non-null inside a
 * `NativeOpWithNode`'s body. For ordinary ops use plain [NativeOp]; the
 * field will be null there to make accidental misuse loud.
 */
class NativeOpWithNode(
    name: String,
    params: List<NativeParam>,
    returns: SlotKind,
    private val handler: NativeHandler,
) {
    val signature: NativeSignature = NativeSignature(name, params, returns)

    init {
        NativeSignatures.register(signature)
        NativeHandlers.register(signature.name, handler, needsNode = true)
    }
}

/**
 * Interpreter-side registry of native handlers. The signature side lives
 * in [NativeSignatures] (in the `ast` module, queried by the IR-builder).
 *
 * The `needsNode` flag determines whether the dispatch node passes itself
 * into `PositionalArgs.callerNode`; reading it on a regular [NativeOp]
 * handler always yields null, by design.
 */
object NativeHandlers {
    private val handlers = HashMap<String, NativeHandler>()
    private val needsNodeFlags = HashSet<String>()

    @CompilerDirectives.TruffleBoundary
    internal fun register(name: String, handler: NativeHandler, needsNode: Boolean) {
        val prior = handlers.put(name, handler)
        if (prior != null) {
            throw IllegalStateException(
                "duplicate NativeOp handler registration for $name"
            )
        }
        if (needsNode) needsNodeFlags.add(name)
    }

    /** Returns the registered handler, or null when not registered. */
    fun handlerOf(name: String): NativeHandler? = handlers[name]

    /** True when the registered handler requires `PositionalArgs.callerNode`. */
    fun needsNode(name: String): Boolean = name in needsNodeFlags

    fun size(): Int = handlers.size
}
