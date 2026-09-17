package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A type NAME is not a type identity, and declaring `Option` yourself does not make
 * your type the stdlib's.
 *
 * `Option Int` arrives as a name-carried `TypeApplication`, and unifying one
 * against a `VariantType` expands it through a declaration. That expansion looked
 * the declaration up by NAME whenever the hash-keyed lookup missed — so in a module
 * declaring `type Option = | Yes, No |`, the stdlib's `Option Int` expanded to
 * `| Yes, No |`, unified with the local type, and produced a WELL-TYPED program
 * that died at runtime:
 *
 *     Non-exhaustive match: no variant tag matched
 *       — typechecker exhaustiveness guarantee violated
 *
 * That is the well-typed⇒runs-to-a-value invariant broken by a name collision. The
 * tests below are the two shapes that reached it plus the controls that prove the
 * rejection is about IDENTITY, not about the name `Option` being special: a local
 * `Result` already rejected correctly, and a local `Option` used consistently must
 * keep working.
 */
class ShadowingTypeNameIdentityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val localOption = "type Option = | Yes, No |\n"

    private fun errorFrom(src: String): String? =
        runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()?.message

    @Test
    fun `a stdlib Option cannot be passed where a shadowing local Option is expected`() {
        val e = errorFrom(
            """
            use std::int
            $localOption
            let f: { o: Option } -> Int = { o } ->
              match o is
                Yes -> 1
                No -> 0
            f { o = int::from_str { s = "5" } }
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("expects Option but got Option Int") == true,
            "expected a type error, not a runtime exhaustiveness violation; got: $e",
        )
    }

    @Test
    fun `a stdlib Option cannot be bound to a shadowing local Option annotation`() {
        val e = errorFrom(
            """
            use std::int
            $localOption
            let b: Option = int::from_str { s = "5" }
            match b is
              Yes -> 1
              No -> 0
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("declared as Option but expression has type Option Int") == true,
            "expected a type error, not a runtime exhaustiveness violation; got: $e",
        )
    }

    @Test
    fun `the shadowing type still works when used consistently`() {
        // The fix rejects a MISMATCH; it must not make the local type unusable.
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                $localOption
                let f: { o: Option } -> Int = { o } ->
                  match o is
                    Yes -> 1
                    No -> 0
                f { o = Yes }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the shadowing type does not accept the stdlib's constructors`() {
        // This half always worked: an inline `Some { val = 1 }` is a VariantType, so
        // it never went through the TypeApplication expansion that was at fault.
        // Kept as the pair to the case above — the two representations of the same
        // value have to give the same answer.
        val e = errorFrom("$localOption\nlet a: Option = Some { val = 1 }\na")
        assertTrue(
            e?.contains("declared as Option but expression has type") == true,
            "expected a type error; got: $e",
        )
    }

    @Test
    fun `a local type under an unrelated stdlib name was already correct`() {
        // The control that showed the bug was specific to the expansion path rather
        // than to shadowing in general.
        val e = errorFrom(
            """
            use std::int
            type Result = | Up, Down |
            let f: { o: Result } -> Int = { o } ->
              match o is
                Up -> 1
                Down -> 0
            f { o = int::from_str { s = "5" } }
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("expects Result but got Option Int") == true,
            "expected a type error; got: $e",
        )
    }
}
