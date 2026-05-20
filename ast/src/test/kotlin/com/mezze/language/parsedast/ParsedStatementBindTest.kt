package com.mezze.language.parsedast

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Sanity tests for the `ParsedStatement.Bind` AST node.
 *
 * The Bind variant was added as part of the do-bind feature. These tests
 * ensure round-trip serialization and that a Bind hashes distinctly from
 * a corresponding Let — so renaming `let x = e` to `x <- e` doesn't
 * silently produce the same content hash.
 */
class ParsedStatementBindTest {

    private val loc = Loc(file = "test", startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun mkBind(name: String, value: ParsedExpr): ParsedStatement.Bind =
        ParsedStatement.Bind(
            pattern = ParsedVariablePattern(name, loc),
            expr = value,
            loc = loc
        )

    private fun mkLet(name: String, value: ParsedExpr): ParsedStatement.Let =
        ParsedStatement.Let(
            pattern = ParsedVariablePattern(name, loc),
            typeAnnotation = null,
            value = value,
            loc = loc
        )

    @Test
    fun `Bind constructs with pattern and expr`() {
        val bind = mkBind("x", ParsedIntLit(42, loc))
        assertEquals("x", (bind.pattern as ParsedVariablePattern).name)
        val e = bind.expr
        assertEquals(42L, (e as ParsedIntLit).value)
    }

    @Test
    fun `Bind round-trips through serialization`() {
        val bind: ParsedStatement = mkBind("user", ParsedIntLit(7, loc))
        val serialized = Json.encodeToString(ParsedStatement.serializer(), bind)
        val deserialized = Json.decodeFromString(ParsedStatement.serializer(), serialized)
        assertEquals(bind, deserialized)
    }

    @Test
    fun `Bind and Let with same pattern_value hash differently in a do block`() {
        val bindDo = ParsedDo(
            statements = listOf(mkBind("x", ParsedIntLit(1, loc))),
            result = ParsedIntLit(2, loc),
            loc = loc
        )
        val letDo = ParsedDo(
            statements = listOf(mkLet("x", ParsedIntLit(1, loc))),
            result = ParsedIntLit(2, loc),
            loc = loc
        )

        val bindHash = ParsedDeclHasher.hashExpr(bindDo)
        val letHash = ParsedDeclHasher.hashExpr(letDo)

        // Renaming `let x = 1` to `x <- 1` MUST change the hash — they have
        // different runtime semantics (the latter wraps in a synthetic
        // perform + try call).
        assertNotEquals(letHash, bindHash, "Bind and Let must hash differently")
    }
}
