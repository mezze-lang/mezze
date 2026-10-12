package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Declaring an ability called `Default` does not make it the built-in `Default`.
 *
 * `generateMemberAccess` routes a `Receiver.method` access down a built-in path
 * when the receiver names a built-in ability, and it asked
 * `BuiltInAbilities.isBuiltInAbility(name)` — a name SET. So a module declaring its
 * own `ability Default` was routed down the path meant for the stdlib's and
 * dispatched through the built-in's notion of the method, ending in a runtime
 * "Expected record for field 'default', got: MezzeVariant". The routing decision is
 * now made by the declaration's CONTENT HASH, so a same-named ability is a
 * different ability.
 *
 * The control is the same program with the ability renamed. Both must resolve to
 * the impl the program wrote — that is what "the name is not the identity" means
 * here, and a wrong answer is as much a failure as a crash.
 *
 * This suite previously asserted that both forms were REJECTED ("Direct ability
 * access is not allowed"). That was the shape of a different defect — a static
 * operation had no callable form at all — and the rejection was the only behaviour
 * available, not the intended one. With static operations callable
 * ([StaticAbilityOpTest]) the question this suite exists to ask is answerable
 * directly: does the shadowing name reach the right impl?
 *
 * Its operations are `make`/`spare`, not `default`: an operation name means one
 * ability in a scope, and `default` already means the prelude's `Default`. The
 * question here is the ability NAME, which the rule leaves alone.
 */
class ShadowingAbilityNameTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun program(abilityName: String) = """
        ability $abilityName for 'a where { make: {} -> 'a, spare: {} -> 'a }
        type P = { x: Int }
        impl $abilityName for P where { make = {} -> { x = 7 }, spare = {} -> { x = 9 } }
        let p: P = $abilityName.make {}
        p.x
    """.trimIndent()

    @Test
    fun `a user ability named Default resolves to the user's own impl`() {
        assertEquals(7L, MezzeTestHelper.run(program("Default")))
    }

    @Test
    fun `the control under an unrelated name answers identically`() {
        assertEquals(7L, MezzeTestHelper.run(program("Fallback")))
    }

    @Test
    fun `a user ability named Default still dispatches through the method form`() {
        // The rejection is about the ACCESS form, not about the name: the same
        // ability used the way the error suggests works.
        assertEquals(
            9L,
            MezzeTestHelper.run(
                """
                ability Default for 'a where { make: {} -> 'a, spare: {} -> 'a }
                type P = { x: Int }
                impl Default for P where { make = {} -> { x = 7 }, spare = {} -> { x = 9 } }
                let p: P = { x = 1 }
                (p.spare {}).x
                """.trimIndent(),
            ),
        )
    }
}
