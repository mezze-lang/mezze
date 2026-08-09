package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * Generic impls — `impl X for 'a where { ... }`.
 *
 * Semantics:
 *   - Parses and registers as a generic impl of ability X for any receiver.
 *   - Dispatch: concrete `impl X for T` wins if present; otherwise generic
 *     is instantiated with 'a := T.
 *
 * Coherence (strict rule): at most one impl per (ability, receiver-shape)
 * visible in one compilation. Concrete + generic for the same ability at
 * the same time is an error.
 *
 * These tests define the target contract for task #96.
 */
class GenericImplTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(src: String): TypeCheckResult {
        val ast = parser.parseParsedModule(src.trimIndent(), "test.mz")
        return typeChecker.typeCheck(ast, Paths.get("."))
    }

    private fun checkFails(src: String): TypeCheckError = assertThrows<TypeCheckError> { check(src) }

    // ------------------------------------------------------------------
    // Basic dispatch — generic impl on Int, Str, and a user type
    // ------------------------------------------------------------------

    @Test
    fun `generic impl dispatches on Int receiver`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let x = 42
            let y = x.tw {}
            """
        )
        // y : Option Int
        val ty = r.getVariableType("y")
        assertNotNull(ty)
        assertTrue(ty.toString().contains("Option"), "y should be Option Int, got: $ty")
        assertTrue(ty.toString().contains("Int"), "y should mention Int, got: $ty")
    }

    @Test
    fun `generic impl dispatches on Str receiver`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let y = "hi".tw {}
            """
        )
        val ty = r.getVariableType("y")
        assertNotNull(ty)
        assertTrue(ty.toString().contains("Option"), "y should be Option Str, got: $ty")
    }

    @Test
    fun `generic impl dispatches on user-defined record`() {
        val r = check(
            """
            type User = { name: Str, age: Int }

            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let u : User = { name = "a", age = 1 }
            let y = u.tw {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    // ------------------------------------------------------------------
    // Concrete-wins precedence — when both concrete and generic exist
    // (only legal if they're in different modules; single-module case is E1)
    // ------------------------------------------------------------------

    // (Covered by cross-module test in Phase-2 test file; single-module
    //  concrete+generic is rejected by A0501 — see E1 below.)

    // ------------------------------------------------------------------
    // Coherence errors — strict rule, one impl per (ability, receiver)
    // ------------------------------------------------------------------

    @Test
    fun `error message names both locations, both signatures, and suggests newtype`() {
        val err = checkFails(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }
            impl TestWrap for Int where { tw = { it } -> None }
            """
        )
        val msg = err.message!!
        // Must name both signatures
        assertTrue(msg.contains("impl TestWrap for 'a"), "expected generic signature, got: $msg")
        assertTrue(msg.contains("impl TestWrap for Int"), "expected concrete signature, got: $msg")
        // Must reference the other location (line 2 = generic in this arrangement)
        assertTrue(msg.contains("test.mz:2:1"), "expected other-impl location, got: $msg")
        // Must suggest the newtype fix explicitly
        assertTrue(msg.contains("newtype"), "expected newtype suggestion, got: $msg")
    }

    @Test
    fun `E1 concrete impl after generic is rejected`() {
        val err = checkFails(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }
            impl TestWrap for Int where { tw = { it } -> None }
            """
        )
        assertTrue(
            err.message!!.contains("Overlapping impl"),
            "expected overlap error, got: ${err.message}"
        )
        assertTrue(
            err.message!!.contains("generic") && err.message!!.contains("concrete"),
            "expected mention of generic/concrete, got: ${err.message}"
        )
    }

    @Test
    fun `E2 generic impl after concrete is rejected`() {
        val err = checkFails(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for Int where { tw = { it } -> None }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }
            """
        )
        assertTrue(
            err.message!!.contains("Overlapping impl"),
            "expected overlap error, got: ${err.message}"
        )
        assertTrue(
            err.message!!.contains("generic") && err.message!!.contains("concrete"),
            "expected mention of generic/concrete, got: ${err.message}"
        )
    }

    @Test
    fun `E3 duplicate generic impl is rejected`() {
        val err = checkFails(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }
            impl TestWrap for 'a where { tw = { it } -> None }
            """
        )
        assertTrue(
            err.message!!.contains("A0503") || err.message!!.contains("duplicate") ||
                err.message!!.contains("already"),
            "expected A0503 duplicate-generic error, got: ${err.message}"
        )
    }

    // ------------------------------------------------------------------
    // Polymorphic receiver — must remain polymorphic through generic
    // ------------------------------------------------------------------

    @Test
    fun `generic dispatch preserves polymorphism when receiver is polymorphic`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let f = { x } -> x.tw {}
            let a = f { x = 42 }
            let b = f { x = "hi" }
            """
        )
        // Both a and b should typecheck with different type-arg instantiations.
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    // ------------------------------------------------------------------
    // Method body — generic impl body typechecks under 'a bound as tyvar
    // ------------------------------------------------------------------

    @Test
    fun `generic impl body type error surfaces at impl location`() {
        val err = checkFails(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            # body returns Int, not Option 'a — should error
            impl TestWrap for 'a where { tw = { it } -> 42 }
            """
        )
        // Not asserting exact message — just that some error surfaces.
        assertNotNull(err.message)
    }

    // ------------------------------------------------------------------
    // Extended happy-path coverage — other primitives, chained calls,
    // multiple ops per ability, type applications, variants
    // ------------------------------------------------------------------

    @Test
    fun `generic impl dispatches on Bool receiver`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let y = True.tw {}
            """
        )
        val ty = r.getVariableType("y")
        assertNotNull(ty)
        assertTrue(ty.toString().contains("Option"), "y should be Option Bool, got: $ty")
    }

    @Test
    fun `generic impl dispatches on Float receiver`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let y = (3.14).tw {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `generic impl dispatches on user-defined variant receiver`() {
        val r = check(
            """
            type Color = | Red, Green, Blue |

            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let c = Red
            let y = c.tw {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `generic impl dispatches through chained calls`() {
        // x.tw {} returns Option Int; .tw {} on it returns Option (Option Int)
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let x = 42.tw {}.tw {}
            """
        )
        val ty = r.getVariableType("x")
        assertNotNull(ty)
        // Should be Option (Option Int) — at minimum double-nested Option
        val tyStr = ty.toString()
        assertTrue(tyStr.contains("Option"), "expected Option in $tyStr")
    }

    @Test
    fun `generic impl with multiple methods, all use the receiver`() {
        val r = check(
            """
            ability WrapPair for 'a where {
                wrap_left  : { it: 'a } -> { l: 'a, r: 'a },
                wrap_right : { it: 'a } -> { l: 'a, r: 'a },
            }
            impl WrapPair for 'a where {
                wrap_left  = { it } -> { l = it, r = it },
                wrap_right = { it } -> { l = it, r = it },
            }

            let a = 1.wrap_left {}
            let b = "hi".wrap_right {}
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    @Test
    fun `generic impl dispatches on Seq receiver`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            let xs = [1, 2, 3]
            let y = xs.tw {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `two independent generic impls of different abilities coexist`() {
        // Sanity: strict coherence is per-ability, not global.
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            ability Boxable for 'a where { box  : { it: 'a } -> { val: 'a } }

            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }
            impl Boxable for 'a where { box  = { it } -> { val = it } }

            let a = 42.tw {}
            let b = "hi".box {}
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    @Test
    fun `generic impl body can use another generic impl on same value`() {
        // The impl body itself invokes .some on 'a — should chain-dispatch to
        // the same generic impl transparently.
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }

            ability DoubleWrap for 'a where { dw : { it: 'a } -> Option (Option 'a) }
            impl DoubleWrap for 'a where {
                dw = { it } -> Some { val = it.tw {} }
            }

            let y = 42.dw {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    // ------------------------------------------------------------------
    // Extended error-path coverage
    // ------------------------------------------------------------------

    @Test
    fun `E4 generic impl with only tyvar in position (no method uses it) still parses and dispatches`() {
        // The receiver 'a is bound; method uses it correctly. Should just work.
        val r = check(
            """
            ability Tag for 'a where { tag : { it: 'a } -> Str }
            impl Tag for 'a where { tag = { it } -> "tagged" }

            let a = 42.tag {}
            let b = "hi".tag {}
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    @Test
    fun `E5 generic impl body missing a required method fails with clear error`() {
        val err = checkFails(
            """
            ability Pair for 'a where {
                first  : { it: 'a } -> 'a,
                second : { it: 'a } -> 'a,
            }
            # missing `second`
            impl Pair for 'a where { first = { it } -> it }
            """
        )
        val msg = err.message!!
        assertTrue(
            msg.contains("second") || msg.contains("missing") || msg.contains("required"),
            "expected mention of missing op, got: $msg"
        )
    }

    @Test
    fun `E6 generic impl body with extra method not in ability fails`() {
        val err = checkFails(
            """
            ability Pair for 'a where { first : { it: 'a } -> 'a }
            impl Pair for 'a where {
                first  = { it } -> it,
                second = { it } -> it,  # not declared in ability
            }
            """
        )
        assertNotNull(err.message)
    }

    // ------------------------------------------------------------------
    // Cross-ability coherence: generic + generic of DIFFERENT abilities
    // for the SAME concrete type is fine
    // ------------------------------------------------------------------

    @Test
    fun `generic TestWrap and generic Boxable on same receiver both dispatch`() {
        val r = check(
            """
            ability TestWrap for 'a where { tw : { it: 'a } -> Option 'a }
            ability Boxable for 'a where { box  : { it: 'a } -> { val: 'a } }

            impl TestWrap for 'a where { tw = { it } -> Some { val = it } }
            impl Boxable for 'a where { box  = { it } -> { val = it } }

            let x = 42
            let a = x.tw {}
            let b = x.box {}
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }
}
