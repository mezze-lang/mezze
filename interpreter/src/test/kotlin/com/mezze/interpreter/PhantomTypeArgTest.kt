package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A type argument no field or payload mentions is ERASED (BUG 33, OPEN).
 *
 * `type Tag 'a = | Tagged { n: Int }, |` declares `'a` and uses it nowhere, so
 * the only thing carrying it is the applied type's `typeArgs`. Unification never
 * reads those: `Unification.kt` mentions `typeArgs` once, to CARRY them —
 * "#194: carry the application's args so the printer can render `Tree Int`".
 * They were added for printing and were never wired into type identity. Where a
 * param IS used, the payloads carry the distinction and this never shows; a
 * phantom param is the only case where `typeArgs` are the sole carrier.
 *
 * So `Tag Str` and `Tag Int` are mutually assignable — through a `let`
 * annotation, across a function boundary and out of a return. That defeats what
 * a phantom parameter is for: `Id User` vs `Id Order`, units, tagged handles.
 *
 * THIS TEST PINS THE CURRENT BEHAVIOUR ON PURPOSE, the way
 * `HttpCatchingAnyTest`'s record case pinned BUG 26 at "fails loudly" before it
 * was fixed. Deciding that a phantom arg is identity-bearing is a language
 * semantics call, not a bug fix, and it is not mine to make — so these assert
 * what Mezze does today and will fail loudly the moment it changes, which is
 * what makes the change deliberate. The two `used` cases below are the control
 * and must keep refusing whichever way the decision goes.
 *
 * Related, and NOT the same thing: `MonoDriver.typeParamAppearsIn` and
 * `MonoTypeOps.unify` also skip `typeArgs`, so such a param reads as a PHANTOM
 * type param and `deriveTypeArgs` fills it with its stable `Int` placeholder —
 * the machinery that caused BUG 26 one layer over. A sweep probe confirmed that
 * path IS reached (`'a in Declared(Tag) typeArgs=['a] ctors=[Tagged]`), and that
 * nothing in the stdlib or either suite reaches it: zero hits compiling the whole
 * stdlib, zero across `:typechecker:test` and `:interpreter:test`. Aligning those
 * two walkers with `MezzeType.anyChild` changes NOTHING observable — measured,
 * with the walkers fixed and these six cases unchanged — because the erasure is
 * in the solver. That patch is therefore held until the decision, rather than
 * landed without a test that fails without it.
 */
class PhantomTypeArgTest {
    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    private fun refused(src: String): String {
        val outcome = runCatching { run(src) }
        assert(outcome.isFailure) {
            "expected a type error, got ${outcome.getOrNull()}"
        }
        return outcome.exceptionOrNull()?.message ?: ""
    }

    /** A phantom arg does not survive a `let` annotation. */
    @Test fun `a variant's phantom arg is erased across an annotation`() {
        assertEquals(
            1L,
            run(
                """
                type Tag 'a = | Tagged { n: Int }, |
                let s: Tag Str = Tagged { n = 1 }
                let t: Tag Int = s
                match t is
                  Tagged { n } -> n
                """
            ),
        )
    }

    /** Same for a record alias — records are compared by field, so a phantom
     *  param there is invisible the same way. */
    @Test fun `a record's phantom arg is erased across an annotation`() {
        assertEquals(
            1L,
            run(
                """
                type Tag 'a = { n: Int }
                let s: Tag Str = { n = 1 }
                let t: Tag Int = s
                t.n
                """
            ),
        )
    }

    /** A function asking for `Tag Int` accepts a `Tag Str`. */
    @Test fun `a phantom arg is erased across a function boundary`() {
        assertEquals(
            1L,
            run(
                """
                type Tag 'a = | Tagged { n: Int }, |
                let want_int: { t: Tag Int } -> Int = { t } -> match t is
                  Tagged { n } -> n
                let s: Tag Str = Tagged { n = 1 }
                want_int { t = s }
                """
            ),
        )
    }

    /** And out of a return type. */
    @Test fun `a phantom arg is erased out of a return`() {
        assertEquals(
            1L,
            run(
                """
                type Tag 'a = | Tagged { n: Int }, |
                let mk: {} -> Tag Str = {} -> Tagged { n = 1 }
                let t: Tag Int = mk {}
                match t is
                  Tagged { n } -> n
                """
            ),
        )
    }

    /**
     * CONTROL: a param the payload uses makes the two provably different, and
     * the assignment is refused. This must hold whichever way BUG 33 is decided
     * — it is what says the erasure is specific to the phantom case and not a
     * general hole in variant identity.
     */
    @Test fun `a used variant arg is not erased`() {
        val message = refused(
            """
            type Box 'a = | Boxed { val: 'a }, |
            let s: Box Str = Boxed { val = "x" }
            let t: Box Int = s
            match t is
              Boxed { val } -> val
            """
        )
        assert(message.contains("Type annotation mismatch")) {
            "expected an annotation mismatch naming the two types; got: $message"
        }
    }

    /** CONTROL: the same for a record field. */
    @Test fun `a used record arg is not erased`() {
        val message = refused(
            """
            type Box 'a = { val: 'a }
            let s: Box Str = { val = "x" }
            let t: Box Int = s
            t.val
            """
        )
        assert(message.contains("Type annotation mismatch")) {
            "expected an annotation mismatch naming the two types; got: $message"
        }
    }
}
