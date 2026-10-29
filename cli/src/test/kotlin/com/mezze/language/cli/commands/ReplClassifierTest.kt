package com.mezze.language.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The REPL decides what an input IS by parsing it, not by matching leading
 * keywords — so the decision stays correct as the grammar grows. These pin the
 * decision table: expression, import, definition, and the two things that make the
 * REPL feel like one — a redefinition shadows by a STABLE key, and a bad line is a
 * reported syntax error rather than a silent nothing.
 */
class ReplClassifierTest {

    @Test
    fun `a bare expression is an expression`() {
        val c = ReplClassifier.classify("6 * 7")
        assertTrue(c is ReplClassifier.Input.Expression, "6 * 7 should evaluate, got $c")
    }

    @Test
    fun `a simple let is a definition keyed and named by its binding`() {
        val c = ReplClassifier.classify("let x = 5")
        c as? ReplClassifier.Input.Definition ?: error("let should define, got $c")
        assertEquals(listOf("x"), c.keys)
        assertEquals(listOf("x"), c.names)
        assertEquals("x", c.singleLetName, "a single simple let is shown by evaluating its name")
        assertTrue(c.importLines.isEmpty())
    }

    @Test
    fun `a function let is a definition but still a value to show`() {
        val c = ReplClassifier.classify("let greet = { name: Str } -> name")
        c as? ReplClassifier.Input.Definition ?: error("expected a definition, got $c")
        assertEquals("greet", c.singleLetName)
    }

    @Test
    fun `a use is an import, one line`() {
        val c = ReplClassifier.classify("use std::effects::log::{ println, Console }")
        c as? ReplClassifier.Input.Import ?: error("use should import, got $c")
        assertEquals(listOf("use std::effects::log::{ println, Console }"), c.lines)
    }

    @Test
    fun `a type definition is keyed by its name, with no value to show`() {
        val c = ReplClassifier.classify("type Id = Int")
        c as? ReplClassifier.Input.Definition ?: error("type should define, got $c")
        assertEquals(listOf("Id"), c.keys)
        assertNull(c.singleLetName, "a type has no value to print back")
    }

    @Test
    fun `redefining a name reuses the same key, so the later one shadows the earlier`() {
        val first = ReplClassifier.classify("let x = 1") as ReplClassifier.Input.Definition
        val again = ReplClassifier.classify("let x = 2") as ReplClassifier.Input.Definition
        assertEquals(first.keys, again.keys, "both must key on `x` so the REPL's def map shadows rather than collides")
        assertEquals("let x = 2", again.texts.single())
    }

    @Test
    fun `an import mixed in with a definition is split out of the appended decls`() {
        // A `use` cannot live after the module's decls, so it must be lifted into
        // the import set — never kept in the definition text.
        val c = ReplClassifier.classify("use std::str::{ chars }\nlet n = 0")
        c as? ReplClassifier.Input.Definition ?: error("expected a definition, got $c")
        assertEquals(listOf("use std::str::{ chars }"), c.importLines)
        assertEquals(listOf("n"), c.keys)
        assertEquals(listOf("let n = 0"), c.texts)
    }

    @Test
    fun `an unparseable line is a syntax error, not a silent no-op`() {
        val c = ReplClassifier.classify("1 +")
        assertTrue(c is ReplClassifier.Input.SyntaxError, "a broken line must report, got $c")
    }
}
