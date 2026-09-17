package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Ctor { … }` matches a VARIANT constructor. Nothing else.
 *
 * A record destructures as `{ field }`; a newtype opens as `N.(inner)`. Those are
 * the forms, and the named-record spelling was accepted for all three — then got
 * stuck at runtime:
 *
 *     type P = { x: Int }
 *     let p: P = { x = 1 }
 *     match p is P { x } -> x
 *     # => Non-exhaustive match: no variant tag matched
 *     #    — typechecker exhaustiveness guarantee violated
 *
 * Three separate holes fed it, each fixed where it lived:
 *
 *  1. A top-level expression's structural constraints were solved and only the
 *     SUCCESS was kept — a Failure was dropped on the floor. The solver had already
 *     produced "Cannot use constructor 'Nonsense' on non-variant type" and nobody
 *     read it. The identical match `let`-bound was rejected properly, which is the
 *     same top-level-vs-bound asymmetry the ability re-solve beside it was written
 *     to close.
 *  2. An undeclared constructor on an unsolved scrutinee INVENTED an open variant
 *     for itself and bound the scrutinee to it.
 *  3. A newtype has a constructor of its own name, so `N { x }` resolved — and a
 *     newtype is erased to its payload, so no `N` tag exists at runtime.
 *
 * The quietest case was the worst: with a wildcard arm present, an arm naming a
 * constructor the variant does not have became DEAD CODE and the match silently
 * returned the wildcard's value.
 */
class ConstructorPatternFormTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun errorFrom(src: String): String? =
        runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()?.message

    // ── rejected ─────────────────────────────────────────────────────────

    @Test
    fun `a record type is not a constructor`() {
        val e = errorFrom("type P = { x: Int }\nlet p: P = { x = 1 }\nmatch p is P { x } -> x")
        assertTrue(
            e?.contains("Cannot use constructor 'P' on non-variant type") == true,
            "expected a type error, not a runtime exhaustiveness violation; got: $e",
        )
    }

    @Test
    fun `a name no type declares is not a constructor`() {
        val e = errorFrom("type P = { x: Int }\nlet p: P = { x = 1 }\nmatch p is Nonsense { x } -> x")
        assertTrue(
            e?.contains("Cannot use constructor 'Nonsense'") == true,
            "expected a type error; got: $e",
        )
    }

    @Test
    fun `a newtype is matched with its dot form, not the named-record form`() {
        val e = errorFrom("newtype N = { x: Int }\nlet n: N = N.({ x = 1 })\nmatch n is N { x } -> x")
        assertTrue(
            e?.contains("is a newtype, not a variant constructor") == true &&
                e.contains("N.(inner)"),
            "expected the newtype form error naming the right spelling; got: $e",
        )
    }

    @Test
    fun `a constructor pattern on a primitive is rejected`() {
        val e = errorFrom("let n = 5\nmatch n is Nope { x } -> x")
        assertTrue(
            e?.contains("Cannot use constructor 'Nope' on non-variant type: Int") == true,
            "expected a type error; got: $e",
        )
    }

    @Test
    fun `an arm naming a constructor the variant lacks is rejected, wildcard or not`() {
        // The quiet one: the wildcard used to catch everything and the `Zed` arm
        // silently became dead code, so the match returned 0 and nothing complained.
        val e = errorFrom(
            """
            type V = | A { v: Int }, B |
            let a: V = A { v = 3 }
            match a is
              Zed { v } -> v
              _ -> 0
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("does not have constructor 'Zed'") == true,
            "expected a type error, not a silently dead arm; got: $e",
        )
    }

    // ── still accepted ───────────────────────────────────────────────────

    @Test
    fun `the forms that were always right still work`() {
        // A record destructures anonymously...
        assertEquals(1L, MezzeTestHelper.run("type P = { x: Int }\nlet p: P = { x = 1 }\nmatch p is { x } -> x"))
        // ...a newtype opens with its dot form...
        assertEquals(1L, MezzeTestHelper.run("newtype N = { x: Int }\nlet n: N = N.({ x = 1 })\nmatch n is N.(p) -> p.x"))
        // ...and a real variant constructor matches.
        assertEquals(
            3L,
            MezzeTestHelper.run(
                """
                type V = | A { v: Int }, B |
                let a: V = A { v = 3 }
                match a is
                  A { v } -> v
                  B -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `builtin variant constructors still match`() {
        assertEquals(
            7L,
            MezzeTestHelper.run(
                """
                let o = Some { val = 7 }
                match o is
                  Some { val } -> val
                  None -> 0
                """.trimIndent(),
            ),
        )
    }
}
