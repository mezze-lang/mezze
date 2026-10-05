package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * L-3: a receiver-less ability op is dispatched on the ability's CLASS VARIABLE,
 * not on whatever sits at the top of the type the call produces.
 *
 * `mk: {} -> ({} -> 'a)` could not be dispatched at all:
 *
 *     let f: {} -> P = Mk.mk {}
 *     error[V0408]: 'Mk.mk' takes no receiver, so the type it produces is what
 *                  chooses the implementation — and nothing at this call
 *                  determines that type.
 *
 * The annotation determines it completely; `'a` is `P`. What failed is that
 * dispatch read the impl name off the TOP of the produced type, and the top of
 * `{} -> P` is an arrow, which has no name. For `mk2: {} -> 'a` the produced
 * type and the class variable coincide, which is why reading `produced` worked
 * at all — and why the two controls below are the ones that matter: an op
 * returning a function dispatches fine WITH a receiver, and an op returning the
 * value directly dispatches fine without one. Only the combination failed.
 *
 * The class variable was already recorded per call site for exactly this
 * purpose (`ExprMethodConstraints`: "everything the call learns about the
 * produced type flows through it"); it simply was not consulted.
 */
class ReceiverlessOpFunctionReturnTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    /** THE case. Refused outright before the fix. */
    @Test
    fun `an op returning a function dispatches from the annotation`() {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("""
            ability Mk for 'a where { mk: {} -> ({} -> 'a) }
            type P = { x: Int }
            impl Mk for P where { mk = {} -> {} -> { x = 1 } }
            let f: {} -> P = Mk.mk {}
            (f {}).x
        """.trimIndent())))
    }

    /** Control: the var IS the return type — the shape that always worked. */
    @Test
    fun `an op returning the value directly still dispatches`() {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("""
            ability Mk2 for 'a where { mk2: {} -> 'a }
            type P = { x: Int }
            impl Mk2 for P where { mk2 = {} -> { x = 1 } }
            let v: P = Mk2.mk2 {}
            v.x
        """.trimIndent())))
    }

    /** Control: a function return type is fine when there IS a receiver. */
    @Test
    fun `an op returning a function dispatches on its receiver`() {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("""
            ability Mk3 for 'a where { mk3: { it: 'a } -> ({} -> 'a) }
            type P = { x: Int }
            impl Mk3 for P where { mk3 = { it } -> {} -> it }
            let g = { p: P } -> ((p.mk3 {}) {}).x
            g { p = { x = 1 } }
        """.trimIndent())))
    }

    /** The var nested one level deeper still resolves. */
    @Test
    fun `the class variable is found nested inside a record return`() {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("""
            ability Mk4 for 'a where { mk4: {} -> { made: 'a } }
            type P = { x: Int }
            impl Mk4 for P where { mk4 = {} -> { made = { x = 1 } } }
            let w: { made: P } = Mk4.mk4 {}
            (w.made).x
        """.trimIndent())))
    }

    /**
     * A receiver-less op IS forwardable through a `with` constraint — the caller
     * picks the impl, exactly as for a receiver-taking op.
     *
     * Here as a test because the diagnostic used to deny it outright: "An enclosing
     * `with Mk 'a` does not carry it: an operation with no receiver is resolved at
     * the call, never forwarded." That named the one remedy that works and told the
     * user it was unavailable.
     */
    @Test
    fun `a receiver-less op is forwarded through a with constraint`() {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("""
            ability Mk6 for 'a where { mk6: {} -> 'a }
            type P = { x: Int }
            impl Mk6 for P where { mk6 = {} -> { x = 1 } }
            let make_one: { n: Int } with Mk6 'a -> 'a = { n } -> Mk6.mk6 {}
            let p: P = make_one { n = 1 }
            p.x
        """.trimIndent())))
    }

    /**
     * The same forwarding when the op returns a FUNCTION. This needs the fix too:
     * the forwarding branch tested whether the PRODUCED type was a variable, and
     * with a nested return type it is an arrow even while the variable inside it is
     * unresolved — so the call was rejected instead of forwarded.
     */
    @Test
    fun `a function-returning receiver-less op is forwarded too`() {
        assertEquals(1L, MezzeTestHelper.asLong(MezzeTestHelper.run("""
            ability Mk7 for 'a where { mk7: {} -> ({} -> 'a) }
            type P = { x: Int }
            impl Mk7 for P where { mk7 = {} -> {} -> { x = 1 } }
            let make_one: { n: Int } with Mk7 'a -> ({} -> 'a) = { n } -> Mk7.mk7 {}
            let f: {} -> P = make_one { n = 1 }
            (f {}).x
        """.trimIndent())))
    }

    /**
     * Still refused when genuinely undeterminable — the fix must not turn an
     * honest "nothing here says what this produces" into a silent guess.
     */
    @Test
    fun `an unpinned receiver-less op is still refused`() {
        val t = assertThrows<Throwable> {
            MezzeTestHelper.run("""
                ability Mk5 for 'a where { mk5: {} -> ({} -> 'a) }
                type P = { x: Int }
                impl Mk5 for P where { mk5 = {} -> {} -> { x = 1 } }
                let f = Mk5.mk5 {}
                1
            """.trimIndent())
        }
        assertTrue(
            (t.message ?: "").contains("takes no receiver"),
            "an undeterminable call must still be refused: ${t.message}",
        )
    }
}
