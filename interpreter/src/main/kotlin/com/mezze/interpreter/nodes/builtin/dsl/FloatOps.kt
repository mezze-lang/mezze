package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Float natives declared in DSL form. Each declaration is the single
 * source of truth for both IR lowering (positional order + kinds) and
 * runtime dispatch (handler body).
 *
 * To avoid drift with the slow path in NativeDispatch.registerFloatBasicHandlers,
 * the slow path should be deleted as each op is migrated here. Until then,
 * `NativeRegistry.signatureOf(name)` returning non-null is what flips a
 * given op onto the scalar ABI; ops still in the slow-path table stay
 * on the legacy record path.
 *
 * Order of params here = positional order passed to the handler =
 * positional order in CallNativeIR.args after IR transposition.
 */
object FloatOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    private val FLOAT_SQRT = NativeOp(
        name = "__native_float_sqrt",
        params = listOf(Param("n", SlotKind.DOUBLE)),
        returns = SlotKind.DOUBLE,
    ) { args -> PrimitiveNatives.floatSqrt(args.double(0)) }
}
