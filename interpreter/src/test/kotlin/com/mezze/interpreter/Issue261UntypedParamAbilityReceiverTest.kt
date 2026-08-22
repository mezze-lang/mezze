package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * #261 regression: an UNTYPED callback parameter whose type is PUSHED from a
 * declared higher-order-function signature must be stamped CONCRETE at emit so
 * an ability method called on it dispatches directly.
 *
 * `apply: { f: { x: Int } -> Str } -> Str` declares that `apply`'s callback
 * takes `{ x: Int }`. When called as `apply { f = { x } -> Describe.describe { it = x } }`,
 * the callback param `x` is written WITHOUT an annotation; its `Int` type is
 * pushed down from `apply`'s declared param type.
 *
 * The bug: `TypedAstBuilder` emitted the call argument (the record `{ f = … }`,
 * and the lambda inside it) with NO expected type, so `x` kept a fresh `VarType`.
 * `MonoDriver` then saw a non-concrete ability receiver (`methodReceiverNotConcrete`),
 * bailed on specialization, and left an UNCACHED `CallIndirect` — which threw
 * `UnsupportedSpecializationException` at runtime (a program that type-checked
 * clean but crashed on execution: a soundness hole).
 *
 * The fix threads the callee's PARAM type into the argument at the call site,
 * and each field's expected type down to the field value, so the untyped `x`
 * becomes `Int` before mono runs. See TypedAstBuilder ParsedFunctionCall /
 * ParsedRecordLit emit.
 */
class Issue261UntypedParamAbilityReceiverTest {

    private val prelude = """
        ability Describe for 'a where { describe: { it: 'a } -> Str }
        impl Describe for Int where { describe = { it } -> "an int" }
        impl Describe for Str where { describe = { it } -> "a string" }
    """.trimIndent()

    @Test fun `untyped callback param pushed to Int pins the ability receiver`() {
        // The exact #261 program. Was UnsupportedSpecializationException; must be "an int".
        val src = "$prelude\n" + """
            let apply: { f: { x: Int } -> Str } -> Str = { f } -> f { x = 7 }
            apply { f = { x } -> Describe.describe { it = x } }
        """.trimIndent()
        assertEquals("an int", MezzeTestHelper.run(src))
    }

    @Test fun `untyped callback param pushed to Str dispatches to the Str impl`() {
        // Same shape, different pushed element type — the receiver must resolve
        // to the Str impl, proving the fix threads the ACTUAL declared type, not
        // a hardcoded default.
        val src = "$prelude\n" + """
            let apply: { f: { x: Str } -> Str } -> Str = { f } -> f { x = "hi" }
            apply { f = { x } -> Describe.describe { it = x } }
        """.trimIndent()
        assertEquals("a string", MezzeTestHelper.run(src))
    }

    @Test fun `explicitly typed callback param control still works`() {
        // Control: annotating `x: Int` always worked (its type was concrete at
        // emit without the push). Guards against a fix that only helps the
        // untyped path by regressing the typed one.
        val src = "$prelude\n" + """
            let apply: { f: { x: Int } -> Str } -> Str = { f } -> f { x = 7 }
            apply { f = { x: Int } -> Describe.describe { it = x } }
        """.trimIndent()
        assertEquals("an int", MezzeTestHelper.run(src))
    }
}
