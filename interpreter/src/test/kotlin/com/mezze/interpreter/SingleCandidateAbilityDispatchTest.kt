package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A constrained function dispatches an ability op with only ONE impl.
 *
 * This pins #195's third gap, carried as a blocker: "dict-passing only fires on
 * multi-candidate … with only one candidate for the method name (or none, for
 * primitives), no dict slot is added, so the receiver's `.encode_repr` falls
 * through to plain field access on the raw String value — runtime crash", whose
 * stated canary is
 *
 *     Expected record for field 'encode_repr', got: String
 *
 * Re-run on 2026-10-28 it does not reproduce. The gate now reads
 * `arrowIsConstrained -> true` BEFORE `opCandidates.size > 1`, so a `with`
 * constraint supplies the dictionary whatever the candidate count — the
 * candidate count only decides for an UNCONSTRAINED arrow.
 *
 * Asserted at RUNTIME rather than as a type-check, because the canary was a
 * runtime fall-through: a program can type-check and still read the op as a
 * field. These assert the VALUE, so a regression shows up as the wrong impl or
 * that exact crash rather than as silence.
 *
 * It had no test, which is why the claim outlived the fix.
 */
class SingleCandidateAbilityDispatchTest {
    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    /** One impl, polymorphic receiver, primitive at the call site. */
    @Test fun `a single impl is dispatched through a constraint`() {
        assertEquals(
            "S",
            run(
                """
                ability Show for 'a where { show: { it: 'a } -> Str }
                impl Show for Str where { show = { it } -> "S" }
                let f: { x: 'a } with Show 'a -> Str = { x } -> x.show {}
                f { x = "hi" }
                """
            ).toString(),
        )
    }

    /**
     * #195's own shape: a PRIMITIVE reached through an ability constraint, with
     * the impl returning its receiver so a wrong dispatch cannot pass by
     * answering a constant.
     */
    @Test fun `a primitive dispatches through an ability constraint`() {
        assertEquals(
            "x",
            run(
                """
                ability EncodeRepr for 'a where { encode_repr: { it: 'a } -> Str }
                impl EncodeRepr for Str where { encode_repr = { it } -> it }
                impl EncodeRepr for Int where { encode_repr = { it } -> "an int" }
                let enc: { v: 'a } with EncodeRepr 'a -> Str = { v } -> v.encode_repr {}
                enc { v = "x" }
                """
            ).toString(),
        )
    }

    /** The other arm of the same program, so neither impl is dead. */
    @Test fun `the Int impl is chosen for an Int`() {
        assertEquals(
            "an int",
            run(
                """
                ability EncodeRepr for 'a where { encode_repr: { it: 'a } -> Str }
                impl EncodeRepr for Str where { encode_repr = { it } -> it }
                impl EncodeRepr for Int where { encode_repr = { it } -> "an int" }
                let enc: { v: 'a } with EncodeRepr 'a -> Str = { v } -> v.encode_repr {}
                enc { v = 7 }
                """
            ).toString(),
        )
    }
}
