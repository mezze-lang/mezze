package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An obligation whose type arguments are ALL CONCRETE at the call site — and why
 * `abilityObligationAtCallSite` is RIGHT to drop it.
 *
 * It raises a `with` obligation only when one of its type arguments is still a
 * type VARIABLE:
 *
 *     val varIndex = constraint.typeArgs.indexOfFirst { it is MezzeType.VarType }
 *     if (varIndex < 0) return null
 *
 * That reads like the hole BUG 25 was — a call site that knows MORE being checked
 * LESS. It is not, and these tests are here to stop someone "fixing" it.
 *
 * An all-concrete obligation is SELF-DISCHARGING. Concrete in the caller means
 * concrete in the callee too, so if the callee's body uses the ability, that body
 * raises its own demand carrying the same concrete type and the impl is derived
 * there; and if the body does not use it, there is nothing to discharge. Only a
 * VARIABLE obligation needs the caller to supply a dictionary, and that case has
 * `varIndex >= 0` and is raised normally.
 *
 * Note what the first three cases do NOT test: at those call sites `'a` is still
 * a FRESH variable, because the argument binds it later by unification. Only an
 * obligation written concretely in the SOURCE reaches the `varIndex < 0` arm, so
 * the fourth case is the one that matters.
 *
 * Measured, not argued: a fix for this was written (bind a fresh variable to the
 * head argument, raise the obligation on that) and changed no observable
 * behaviour anywhere. That was the evidence it was not a bug.
 */
class ConcreteObligationTest {

    private val head = """
        use std::codec::json::{ Json, JsonC, json_default, to_str }
        use std::codec::core::{ Encode }
    """.trimIndent()

    @Test
    fun `a forwarded codec obligation resolves when the caller knows the type`() {
        val r = MezzeTestHelper.run(
            """
            $head
            ## `enc`'s obligation names 'a. At the call below 'a is ALREADY
            ## { a: Int }, so the instantiated obligation has no type variable
            ## left in it at all.
            let enc: { x: 'a } with Encode JsonC 'a -> Str = { x } ->
              to_str { json = x.encode { config = json_default } }
            let main = {} -> enc { x = { a = 1 } }
            main {}
            """.trimIndent(),
        )
        assertEquals("""{"a":1}""", r)
    }

    @Test
    fun `a forwarded obligation resolves for a nested concrete type`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let enc: { x: 'a } with Encode JsonC 'a -> Str = { x } ->
              to_str { json = x.encode { config = json_default } }
            let main = {} -> enc { x = { outer = { inner = 2 } } }
            main {}
            """.trimIndent(),
        )
        assertEquals("""{"outer":{"inner":2}}""", r)
    }

    @Test
    fun `an obligation written with NO type variable at all`() {
        // The three above do NOT exercise the hole: at the call site 'a is still
        // a FRESH variable (the argument binds it later by unification), so the
        // scan finds one. Only an obligation written CONCRETELY in the source has
        // no variable at any position, which is the `varIndex < 0` arm.
        val r = MezzeTestHelper.run(
            """
            $head
            let enc_rec: { x: { a: Int } } with Encode JsonC { a: Int } -> Str = { x } ->
              to_str { json = x.encode { config = json_default } }
            let main = {} -> enc_rec { x = { a = 1 } }
            main {}
            """.trimIndent(),
        )
        assertEquals("""{"a":1}""", r)
    }

    @Test
    fun `a forwarded obligation resolves for a seq of records`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let enc: { x: 'a } with Encode JsonC 'a -> Str = { x } ->
              to_str { json = x.encode { config = json_default } }
            let main = {} -> enc { x = [{ id = 1 }, { id = 2 }] }
            main {}
            """.trimIndent(),
        )
        assertEquals("""[{"id":1},{"id":2}]""", r)
    }
}
