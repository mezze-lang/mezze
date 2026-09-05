package com.mezze.language.langservice.hover

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import java.nio.file.Paths

/**
 * A scoped effect handler's `initialize_scope`/`finalize_scope` are lifecycle
 * hooks, not operations a caller invokes — hover must hide them and show only
 * the real ops (`query`), the way HttpClient should show `request` alone.
 */
class HoverScopeHookTest {

    @Test
    fun `handler hover hides scope lifecycle hooks, shows real ops`() {
        val src = """
            effect Db where { query: { sql: Str } -> Str <Db> }

            impl Db for Conn where {
              initialize_scope = {} -> { handle = 1 },
              finalize_scope = {} -> {},
              query = { sql } -> "row",
            }
        """.trimIndent()

        val parser = MezzeParser()
        val module = parser.parseParsedModule(src, "t.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(module, Paths.get("."))

        val ctx = HoverInfo.Context(
            content = src,
            typeCheckResult = result,
            typeAliases = tc.getTypeAliases(),
            parsedModule = module,
        )

        val lines = src.lines()
        // hover the impl header target `Conn` (the handler decl) — 0-based
        val implLine = lines.indexOfFirst { it.startsWith("impl Db for Conn") }
        val col = lines[implLine].indexOf("Conn")
        val hover = HoverInfo.at(ctx, implLine, col)

        assertNotNull(hover, "expected a hover for the Conn handler")
        assertTrue(hover!!.contains("query"), "hover should list the real op `query`:\n$hover")
        assertFalse(hover.contains("initialize_scope"), "hover must hide initialize_scope:\n$hover")
        assertFalse(hover.contains("finalize_scope"), "hover must hide finalize_scope:\n$hover")
    }
}
