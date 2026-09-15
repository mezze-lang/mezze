package com.mezze.language.typechecker

import com.mezze.language.parsedast.ParsedUseDecl
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.validation.UnusedImportLinter
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * An ability imported so its METHODS can be called is used — even though the
 * ability's own name appears nowhere. `x.from_json {}` references the method
 * `from_json`, not `FromJson`, so the syntactic scan alone flagged the import
 * as unused, and removing it (as the warning instructs) broke the build.
 */
class AbilityImportUsageTest {

    private fun findings(src: String, abilityOps: Map<String, Set<String>>) =
        UnusedImportLinter.lint(MezzeParser().parseParsedModule(src, "t.mz"), abilityOps)
            .map { it.message }

    @Test
    fun `ability whose method is called is not reported unused`() {
        val src = """
            use std::codec::json::{ FromJson }
            let f = { body : Str } -> body.from_json {}
        """.trimIndent()
        val fs = findings(src, mapOf("FromJson" to setOf("from_json", "from_json_with")))
        assertFalse(fs.any { it.contains("FromJson") }, "should not flag a used ability:\n$fs")
    }

    @Test
    fun `an ability whose method is NOT called is still reported`() {
        val src = """
            use std::codec::json::{ FromJson }
            let f = { n : Int } -> n
        """.trimIndent()
        val fs = findings(src, mapOf("FromJson" to setOf("from_json", "from_json_with")))
        assertTrue(fs.any { it.contains("FromJson") }, "an unused ability should still warn:\n$fs")
    }

    @Test
    fun `a plain unused import is unaffected`() {
        val src = """
            use std::collections::deque::{ Deque }
            let f = { n : Int } -> n
        """.trimIndent()
        // No ability ops in play — the ordinary syntactic check must still fire.
        val fs = findings(src, emptyMap())
        assertTrue(fs.any { it.contains("Deque") }, "a genuinely unused import must warn:\n$fs")
    }

    @Test
    fun `unusedItemsFor also counts dot-dispatch as a use`() {
        // The LSP's remove-unused-import fix calls unusedItemsFor, not lint. Before
        // it was given the ability-ops map it recomputed a narrower answer and
        // offered to delete an import the diagnostic never flagged — applying the
        // fix then broke the build.
        val src = """
            use std::codec::json::{ Json, FromJson }
            let decode = { j: Json } -> j.from_json {}
        """.trimIndent()
        val module = MezzeParser().parseParsedModule(src, "t.mz")
        val useDecl = module.decls.filterIsInstance<ParsedUseDecl>().single()
        val abilityOps = mapOf("FromJson" to setOf("from_json", "from_json_with"))
        assertTrue(
            "FromJson" !in UnusedImportLinter.unusedItemsFor(module, useDecl, abilityOps),
            "an ability used only via dot-dispatch must not be reported unused",
        )
    }
}
