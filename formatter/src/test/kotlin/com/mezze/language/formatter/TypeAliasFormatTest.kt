package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Formatter roundtrip for `type X = Y` (transparent alias — any RHS shape).
 * Regressions here mean the parser or PrettyPrinter has lost track of the
 * new `ParsedTypeAliasDef` case.
 */
class TypeAliasFormatTest {
    private val formatter = Formatter()

    @Test fun `type X = Int primitive alias roundtrips`() {
        val input = "type MyInt = Int\n"
        assertEquals(input, formatter.format(input))
    }

    @Test fun `type X = Seq Int generic alias roundtrips`() {
        val input = "type Ids = Seq Int\n"
        assertEquals(input, formatter.format(input))
    }

    @Test fun `parametric type X 'a = Seq 'a roundtrips`() {
        val input = "type Wrap 'a = Seq 'a\n"
        assertEquals(input, formatter.format(input))
    }

    @Test fun `type X = record shape stays as record decl not alias`() {
        // A `{...}`-RHS still parses as ParsedRecordDef, so the formatter
        // emits it as a record definition — not an alias to an inline record.
        val input = "type Point = { x: Int, y: Int }\n"
        assertEquals(input, formatter.format(input))
    }

    @Test fun `type X = variant shape stays as variant decl not alias`() {
        val input = "type Color = | Red, Green, Blue |\n"
        assertEquals(input, formatter.format(input))
    }

    @Test fun `newtype and type alias coexist in one module`() {
        val input = """
            type MyInt = Int
            newtype UserId = Int
        """.trimIndent() + "\n"
        val output = formatter.format(input)
        assertTrue(output.contains("type MyInt = Int"),
            "type alias must survive round-trip; got: $output")
        assertTrue(output.contains("newtype UserId = Int"),
            "newtype must NOT double-print its constructor; got: $output")
    }

    @Test fun `newtype with primitive RHS roundtrips without double-ctor`() {
        val input = "newtype Age = Int\n"
        assertEquals(input, formatter.format(input))
    }

    @Test fun `parametric newtype roundtrips without double-ctor`() {
        val input = "newtype Wrap 'a = Seq 'a\n"
        assertEquals(input, formatter.format(input))
    }
}
