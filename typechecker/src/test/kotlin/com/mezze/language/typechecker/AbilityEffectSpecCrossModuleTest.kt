package com.mezze.language.typechecker

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * The CROSS-MODULE axis: the two coherence gates, and what a caller must import.
 */
class AbilityEffectSpecCrossModuleTest : AbilityEffectSpecBase() {

    @Test
    fun `an impl for a foreign type beside an imported ability is an orphan`() {
        writeModule("abil", "ability Bump for 'a where { bump: { it: 'a } -> Int }")
        rejectsMain(
            """
            use abil::{ Bump }
            impl Bump for Int where { bump = { it } -> 111 }
            $MAIN
            """,
            "V0406",
            "neither the ability nor the type is local, so neither gate is satisfied",
        )
    }

    @Test
    fun `an impl beside its own type is legal`() {
        writeModule("abil", "ability Bump for 'a where { bump: { it: 'a } -> Int }")
        writeModule(
            "boxmod",
            """
            use abil::{ Bump }
            pub type Box = { v: Int }
            impl Bump for Box where { bump = { it } -> it.v + 100 }
            """,
        )
        acceptsMain(
            """
            use abil::{ Bump }
            use boxmod::{ Box }
            let use_it: {} -> Int = {} -> Bump.bump { it = { v = 1 } }
            $MAIN
            """,
            "the TYPE is local to the impl's module, which is the second gate",
        )
    }

    @Test
    fun `without importing the impl's module the call is refused`() {
        // Current behaviour, pinned: the import is what brings the impl.
        writeModule("abil", "ability Bump for 'a where { bump: { it: 'a } -> Int }")
        writeModule(
            "boxmod",
            """
            use abil::{ Bump }
            pub type Box = { v: Int }
            impl Bump for Box where { bump = { it } -> it.v + 100 }
            """,
        )
        rejectsMain(
            """
            use abil::{ Bump }
            let use_it: {} -> Int = {} -> Bump.bump { it = { v = 1 } }
            $MAIN
            """,
            "V0402",
            "an impl must be imported to be dispatched to",
        )
    }

    @Test
    @Disabled("BUG 49a — V0402 says 'write an impl' when one exists in another module")
    fun `V0402 names the module whose impl was not imported`() {
        writeModule("abil", "ability Bump for 'a where { bump: { it: 'a } -> Int }")
        writeModule(
            "boxmod",
            """
            use abil::{ Bump }
            pub type Box = { v: Int }
            impl Bump for Box where { bump = { it } -> it.v + 100 }
            """,
        )
        val r = checkMain(
            """
            use abil::{ Bump }
            let use_it: {} -> Int = {} -> Bump.bump { it = { v = 1 } }
            $MAIN
            """.trimIndent(),
        )
        val msg = r.exceptionOrNull()?.message ?: ""
        kotlin.test.assertTrue(
            msg.contains("boxmod"),
            "the advice must point at the existing impl, not tell the user to write a " +
                "duplicate that would then be V0407 or V0406. Got: $msg",
        )
    }
}
