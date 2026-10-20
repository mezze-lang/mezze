package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A type with no impl of an ability gets the ABILITY AUTHOR's advice when they
 * wrote one (`#{ missing: "…" }` on the declaration). The fact — which type,
 * which ability — stays the compiler's; only the "what to do" is theirs.
 *
 * The stdlib's own advice used to be a `when (abilityName)` in the checker,
 * matching three ability names; it is metadata on those abilities now.
 */
class MissingImplMessageTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun errorsOf(source: String): String {
        val parsed = MezzeParser().parseParsedModule(source, "app.mz")
        return try {
            when (val r = TypeChecker().checkParsedModule(parsed, Paths.get("."), enablePrelude = true)) {
                is TypeChecker.TypeCheckResult.Failure -> r.errors.joinToString("\n") { it.message }
                is TypeChecker.TypeCheckResult.Success -> error("expected a missing-impl error")
            }
        } catch (e: TypeCheckError) {
            e.message ?: ""
        }
    }

    @Test
    fun `an ability's own advice replaces the generic one`() {
        val msg = errorsOf("""
            #{ missing: "Every shape here comes from `std::shapes`; derive it there." }
            ability Area for 'a where { area: { it: 'a } -> Int }

            let measure: { x: 'a } with Area 'a -> Int = { x } -> x.area {}
            let main = {} -> measure { x = 1 }
        """.trimIndent())

        assertTrue(msg.contains("does not implement 'Area'"), "the fact is the compiler's: $msg")
        assertTrue(msg.contains("Every shape here comes from `std::shapes`; derive it there."), msg)
        assertFalse(msg.contains("To enable this, implement"), "the author's advice replaces the skeleton: $msg")
    }

    @Test
    fun `an imported ability's advice reaches the user's module`() {
        val msg = errorsOf("""
            use std::abilities::tryable::{ Tryable }

            let unwrap: { x: 'm } with Tryable 'm -> Int = { x } -> 0
            let main = {} -> unwrap { x = 5 }
        """.trimIndent())

        assertTrue(msg.contains("`.try {}` unwraps `Option`, `Result`, or any type with an `impl Tryable`."), msg)
    }

    @Test
    fun `without advice the skeleton is still offered`() {
        val msg = errorsOf("""
            ability Area for 'a where { area: { it: 'a } -> Int }

            let measure: { x: 'a } with Area 'a -> Int = { x } -> x.area {}
            let main = {} -> measure { x = 1 }
        """.trimIndent())

        assertTrue(msg.contains("To enable this, implement"), msg)
    }
}
