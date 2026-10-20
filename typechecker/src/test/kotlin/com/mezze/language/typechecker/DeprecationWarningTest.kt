package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A use of a declaration marked `#{ deprecated: True }` warns at the use
 * (W0501), wherever the declaration lives — the person who needs telling is
 * the caller, usually in another module.
 */
class DeprecationWarningTest {
    private lateinit var root: Path

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        root = Files.createTempDirectory("mezze_deprecation_test")
    }

    private fun deprecations(source: String): List<TypeChecker.TypeCheckWarning> {
        val parsed = MezzeParser().parseParsedModule(source, "app.mz")
        val result = TypeChecker().checkParsedModule(parsed, root, enablePrelude = true)
        val ok = result as? TypeChecker.TypeCheckResult.Success ?: error("did not type-check: $result")
        return ok.warnings.filter { it.code == WarningCode.W0501_DEPRECATED }
    }

    @Test
    fun `a call to an imported deprecated function warns at the call`() {
        Files.writeString(root.resolve("old.mz"), """
            #{ deprecated: True, since: "0.3", note: "use `fresh` instead" }
            pub let stale: { n: Int } -> Int = { n } -> n + 1

            pub let fresh: { n: Int } -> Int = { n } -> n + 2
        """.trimIndent())

        val found = deprecations("""
            use std::effects::log::{ println, Console }
            use old::{ stale, fresh }

            let main = {} -> perform Console in do
              println { msg = stale { n = 1 }.to_str {} }
              println { msg = fresh { n = 1 }.to_str {} }
        """.trimIndent())

        val only = found.single()
        assertEquals("`stale` is deprecated since 0.3: use `fresh` instead", only.message)
        assertEquals(5, only.loc?.startLine, "at the call, not at the declaration")
    }

    @Test
    fun `a deprecated declaration of this module warns where it is used`() {
        val found = deprecations("""
            use std::effects::log::{ println, Console }

            #{ deprecated: True }
            let stale: { n: Int } -> Int = { n } -> n + 1

            let main = {} -> perform Console in println { msg = stale { n = 1 }.to_str {} }
        """.trimIndent())

        assertEquals(listOf("`stale` is deprecated"), found.map { it.message })
    }

    @Test
    fun `a deprecated effect warns at its operation, and its performer at the perform`() {
        val found = deprecations("""
            use std::effects::log::{ println, Console }

            #{ deprecated: True, note: "beeping is over" }
            effect Beep where { beep: {} -> Int <Beep> }

            #{ deprecated: True }
            impl Beep for Silent where { beep = {} -> resume { val = 0 } }

            let main = {} -> perform Console in do
              let n = perform Silent in Beep.beep {}
              println { msg = n.to_str {} }
        """.trimIndent())

        assertEquals(
            setOf("`Beep` is deprecated: beeping is over", "`Silent` is deprecated"),
            found.map { it.message }.toSet(),
        )
    }

    @Test
    fun `deprecated code using deprecated code is not reported`() {
        val found = deprecations("""
            #{ deprecated: True }
            let old_a: { n: Int } -> Int = { n } -> n + 1

            #{ deprecated: True }
            let old_b: { n: Int } -> Int = { n } -> old_a { n = n }
        """.trimIndent())

        assertTrue(found.isEmpty(), "$found")
    }
}
