package com.mezze.language.typechecker.module

import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * An impl of an IMPORTED ability is an ability impl in the resolver's module
 * record from the moment the module is resolved — not only after the module has
 * been type-checked.
 *
 * The parser can only recognise abilities declared in the same file, so
 * `impl EncodeScalar for JsC` parses as a performer. The checker reclassifies it
 * once imports are known, and that used to be the ONLY place it happened. A
 * module whose typed result comes from the store is never re-checked, so on every
 * warm `mez dev` `mezze_js::codec`'s codec impls were missing from its index and
 * `.to_js {}` failed with "format lacks EncodeScalar". These tests resolve
 * modules and read the index WITHOUT type-checking anything, which is exactly the
 * state a store-hydrated dependency is in.
 */
class ImportedAbilityImplIndexTest {

    @TempDir
    lateinit var project: Path

    @BeforeEach
    fun setUp() {
        TypeChecker.clearCache()
        project.resolve("shout.mz").writeText(
            """
            ability Shout for 'a where { shout: { it: 'a } -> Str }
            """.trimIndent()
        )
    }

    @Test
    fun `an impl of a selectively imported ability is indexed as an ability impl`() {
        project.resolve("loud.mz").writeText(
            """
            use shout::{ Shout }

            impl Shout for Int where { shout = { it } -> "LOUD" }
            """.trimIndent()
        )
        val loud = ModuleResolver(project).resolveModule("loud")
        assertEquals(1, loud.index.abilityImplsByHash.size,
            "the impl of the imported Shout must be in the ability-impl index before any check")
        assertTrue(loud.index.performersByName.isEmpty(), "and must not be filed as an effect handler")
    }

    @Test
    fun `an impl of an ability imported under an alias is indexed as an ability impl`() {
        project.resolve("aliased.mz").writeText(
            """
            use shout::{ Shout as Yell }

            impl Yell for Int where { shout = { it } -> "LOUD" }
            """.trimIndent()
        )
        val m = ModuleResolver(project).resolveModule("aliased")
        assertEquals(1, m.index.abilityImplsByHash.size)
    }

    @Test
    fun `an effect handler stays a handler`() {
        project.resolve("handler.mz").writeText(
            """
            use shout::{ Shout }

            effect Ping where { ping: {} -> Int <Ping> }

            impl Ping for Pong where { ping = {} -> 1 }
            """.trimIndent()
        )
        val m = ModuleResolver(project).resolveModule("handler")
        assertTrue(m.index.abilityImplsByHash.isEmpty(), "an impl of an effect is not an ability impl")
        assertEquals(setOf("Pong"), m.index.performersByName.keys)
    }
}
