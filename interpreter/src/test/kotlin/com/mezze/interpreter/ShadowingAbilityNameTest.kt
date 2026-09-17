package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Declaring an ability called `Default` does not make it the built-in `Default`.
 *
 * `generateMemberAccess` routes a `Receiver.method` access down a built-in path
 * when the receiver names a built-in ability, and it asked
 * `BuiltInAbilities.isBuiltInAbility(name)` — a name SET. So a module declaring its
 * own `ability Default` was routed down the path meant for the stdlib's: it skipped
 * the "Direct ability access is not allowed" rejection that every other user
 * ability gets, and dispatched through the built-in's notion of the method, ending
 * in a runtime "Expected record for field 'default', got: MezzeVariant".
 *
 * The control below is the same program with the ability renamed, which was always
 * rejected properly. Both now give the same diagnostic, because both are the same
 * mistake.
 */
class ShadowingAbilityNameTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun errorFrom(src: String): String? =
        runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()?.message

    private fun program(abilityName: String) = """
        ability $abilityName for 'a where { default: {} -> 'a, spare: {} -> 'a }
        type P = { x: Int }
        impl $abilityName for P where { default = {} -> { x = 7 }, spare = {} -> { x = 9 } }
        let p: P = $abilityName.default {}
        p.x
    """.trimIndent()

    @Test
    fun `a user ability named Default gets the same rejection as any other`() {
        val e = errorFrom(program("Default"))
        assertTrue(
            e?.contains("Direct ability access 'Default.default' is not allowed") == true,
            "expected the direct-access rejection, not a runtime crash; got: $e",
        )
    }

    @Test
    fun `the control under an unrelated name was already correct`() {
        val e = errorFrom(program("Fallback"))
        assertTrue(
            e?.contains("Direct ability access 'Fallback.default' is not allowed") == true,
            "expected the direct-access rejection; got: $e",
        )
    }

    @Test
    fun `a user ability named Default still dispatches through the method form`() {
        // The rejection is about the ACCESS form, not about the name: the same
        // ability used the way the error suggests works.
        assertEquals(
            9L,
            MezzeTestHelper.run(
                """
                ability Default for 'a where { default: {} -> 'a, spare: {} -> 'a }
                type P = { x: Int }
                impl Default for P where { default = {} -> { x = 7 }, spare = {} -> { x = 9 } }
                let p: P = { x = 1 }
                (p.spare {}).x
                """.trimIndent(),
            ),
        )
    }
}
