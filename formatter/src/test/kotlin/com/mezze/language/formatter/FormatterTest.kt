package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FormatterTest {
    private val formatter = Formatter()

    // =========================================================================
    // BASIC LET BINDINGS
    // =========================================================================

    @Test
    fun `format simple let binding`() {
        val input = "let x = 42"
        val output = formatter.format(input)
        assertEquals("let x = 42\n", output)
    }

    @Test
    fun `formatting preserves a multi-line doc comment after a do-block decl`() {
        // Regression (#127): the parser used to drop the `##` doc block on a
        // decl declared after a do-block decl, so the formatter had nothing to
        // print and appeared to "remove" the doc. Formatting must round-trip
        // the whole multi-line markdown doc.
        val input = """
            let main = {} -> do
              let x = 1
              x

            ## This is a doc comment
            ## # Heading
            ## - a list item
            let help = { a, b } -> a + b
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("## This is a doc comment"),
            "doc line dropped by formatter:\n$output")
        assertTrue(output.contains("## # Heading"),
            "markdown heading dropped:\n$output")
        assertTrue(output.contains("## - a list item"),
            "list item dropped:\n$output")
        // Idempotent: formatting the output again is a fixed point.
        assertEquals(output, formatter.format(output),
            "formatting is not idempotent for doc comments")
    }

    @Test
    fun `formatting preserves every comment kind after a do-block decl`() {
        // Regression (#127): the do block's parse span bled onto the next
        // decl's comment line, and the trailing-comment heuristic then stole
        // it — dropping not just `##` docs but regular `#` comments and `#{}`
        // metadata on the following decl too. All three must round-trip.
        val input = """
            let main = {} -> do
              let x = 1
              x

            # a regular leading comment
            ## a doc line
            #{ deprecated: true }
            let help = { a, b } -> a + b
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# a regular leading comment"),
            "regular `#` comment dropped:\n$output")
        assertTrue(output.contains("## a doc line"),
            "`##` doc dropped:\n$output")
        assertTrue(output.contains("deprecated"),
            "`#{}` metadata dropped:\n$output")
        assertEquals(output, formatter.format(output),
            "formatting is not idempotent for mixed comments")
    }

    @Test
    fun `formatting preserves doc comments after an inline match decl`() {
        // Regression: a decl whose body is an INLINE `match x is` (the `match`
        // keyword on the SAME line as `=`, arms hanging indented below) had the
        // match-arm loop speculatively consume the NEXT decl's leading `## doc`
        // while probing for another arm, then discard it on the dedent break —
        // silently deleting the following declaration's documentation. Both
        // docs below must survive; only `map`'s (next-line match) used to.
        val input = """
            ## Apply f to the inner value, or return None.
            pub let map : { opt: Option 'a, f: { val: 'a } -> 'b } -> Option 'b = { opt, f } -> match opt is
              Some { val } -> Some { val = f { val } }
              None -> None

            ## Unwrap the value, or return def if None.
            pub let get_or : { opt: Option 'a, def: 'a } -> 'a = { opt, def } -> match opt is
              Some { val } -> val
              None -> def

            ## True if the option contains a value.
            pub let is_some : { opt: Option 'a } -> Bool = { opt } -> match opt is
              Some { val } -> true
              None -> false
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("## Apply f to the inner value, or return None."),
            "map's doc dropped:\n$output")
        assertTrue(output.contains("## Unwrap the value, or return def if None."),
            "get_or's doc dropped after inline-match decl:\n$output")
        assertTrue(output.contains("## True if the option contains a value."),
            "is_some's doc dropped after inline-match decl:\n$output")
        assertEquals(output, formatter.format(output),
            "formatting is not idempotent for docs after inline-match decls")
    }

    @Test
    fun `formatting preserves ability effect and impl member doc comments`() {
        // Regression: member ops in ability/effect/impl/performer blocks were
        // parsed with a comment-discarding skip, so per-operation `## doc`
        // comments never reached the AST and vanished on reformat.
        val input = """
            ## An ability with a documented op.
            ability Default for 'a where {
              ## Construct the default value.
              default: {} -> 'a
            }

            ## An effect with a documented op.
            effect Reader having 'a where {
              ## Read the current binding.
              ask: {} -> 'a <Reader>
            }

            ## An inherent impl with a documented op.
            impl Request where {
              ## Terminal — perform the request.
              send = { it } -> do_send { req = it }
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("## Construct the default value."),
            "ability member doc dropped:\n$output")
        assertTrue(output.contains("## Read the current binding."),
            "effect member doc dropped:\n$output")
        assertTrue(output.contains("## Terminal — perform the request."),
            "impl member doc dropped:\n$output")
        assertEquals(output, formatter.format(output),
            "formatting is not idempotent for member docs")
    }

    @Test
    fun `formatting preserves a module-top doc block separated by a blank line`() {
        // Regression: a file-top `## …` doc block separated from the first
        // declaration by a blank line was reset-and-discarded by the annotation
        // collector, deleting the whole module doc on reformat.
        val input = """
            ## Module-level documentation.
            ## Second line of it.

            use std::option
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("## Module-level documentation."),
            "module-top doc line 1 dropped:\n$output")
        assertTrue(output.contains("## Second line of it."),
            "module-top doc line 2 dropped:\n$output")
        assertEquals(output, formatter.format(output),
            "formatting is not idempotent for module-top docs")
    }

    @Test
    fun `format let binding with type annotation`() {
        val input = "let x: Int = 42"
        val output = formatter.format(input)
        assertEquals("let x: Int = 42\n", output)
    }

    @Test
    fun `format pub let binding`() {
        val input = "pub let x = 42"
        val output = formatter.format(input)
        assertEquals("pub let x = 42\n", output)
    }

    // =========================================================================
    // LITERALS
    // =========================================================================

    @Test
    fun `format int literal`() {
        val input = "let x = 42"
        val output = formatter.format(input)
        assertEquals("let x = 42\n", output)
    }

    @Test
    fun `format nat literal`() {
        val input = "let x = 42n"
        val output = formatter.format(input)
        assertEquals("let x = 42n\n", output)
    }

    @Test
    fun `format float literal`() {
        val input = "let x = 3.14"
        val output = formatter.format(input)
        assertEquals("let x = 3.14\n", output)
    }

    @Test
    fun `format fraction literal without spaces`() {
        val input = "let x = 1//2"
        val output = formatter.format(input)
        assertEquals("let x = 1//2\n", output)
    }

    @Test
    fun `format string literal`() {
        val input = """let x = "hello""""
        val output = formatter.format(input)
        assertEquals("let x = \"hello\"\n", output)
    }

    @Test
    fun `format boolean true`() {
        val input = "let x = True"
        val output = formatter.format(input)
        assertEquals("let x = True\n", output)
    }

    @Test
    fun `format boolean false`() {
        val input = "let x = False"
        val output = formatter.format(input)
        assertEquals("let x = False\n", output)
    }

    // =========================================================================
    // STRING INTERPOLATION
    // =========================================================================

    @Test
    fun `format string interpolation`() {
        val input = """let x = "Hello $(name)!""""
        val output = formatter.format(input)
        assertEquals("let x = \"Hello \$(name)!\"\n", output)
    }

    @Test
    fun `format string interpolation with expression`() {
        val input = """let x = "Sum: $(a + b)""""
        val output = formatter.format(input)
        assertEquals("let x = \"Sum: \$(a + b)\"\n", output)
    }

    // =========================================================================
    // BINARY OPERATORS
    // =========================================================================

    @Test
    fun `format binary operators with spaces`() {
        val input = "let x = 1+2"
        val output = formatter.format(input)
        assertEquals("let x = 1 + 2\n", output)
    }

    @Test
    fun `format chained operators`() {
        val input = "let x = 1 + 2 * 3"
        val output = formatter.format(input)
        assertEquals("let x = 1 + 2 * 3\n", output)
    }

    @Test
    fun `format subtraction`() {
        val input = "let x = 5-3"
        val output = formatter.format(input)
        assertEquals("let x = 5 - 3\n", output)
    }

    @Test
    fun `format division`() {
        val input = "let x = 10/2"
        val output = formatter.format(input)
        assertEquals("let x = 10 / 2\n", output)
    }

    @Test
    fun `format modulo`() {
        val input = "let x = 10%3"
        val output = formatter.format(input)
        assertEquals("let x = 10 % 3\n", output)
    }

    // =========================================================================
    // COMPARISON OPERATORS
    // =========================================================================

    @Test
    fun `format equality`() {
        val input = "let x = a==b"
        val output = formatter.format(input)
        assertEquals("let x = a == b\n", output)
    }

    @Test
    fun `format not equal`() {
        val input = "let x = a!=b"
        val output = formatter.format(input)
        assertEquals("let x = a != b\n", output)
    }

    @Test
    fun `format less than`() {
        val input = "let x = a<b"
        val output = formatter.format(input)
        assertEquals("let x = a < b\n", output)
    }

    @Test
    fun `format greater than`() {
        val input = "let x = a>b"
        val output = formatter.format(input)
        assertEquals("let x = a > b\n", output)
    }

    @Test
    fun `format less than or equal`() {
        val input = "let x = a<=b"
        val output = formatter.format(input)
        assertEquals("let x = a <= b\n", output)
    }

    @Test
    fun `format greater than or equal`() {
        val input = "let x = a>=b"
        val output = formatter.format(input)
        assertEquals("let x = a >= b\n", output)
    }

    // =========================================================================
    // LOGICAL OPERATORS
    // =========================================================================

    @Test
    fun `format logical and`() {
        val input = "let x = a&&b"
        val output = formatter.format(input)
        assertEquals("let x = a && b\n", output)
    }

    @Test
    fun `format logical or`() {
        val input = "let x = a||b"
        val output = formatter.format(input)
        assertEquals("let x = a || b\n", output)
    }

    // =========================================================================
    // UNARY OPERATORS
    // =========================================================================

    @Test
    fun `format negation`() {
        val input = "let x = -5"
        val output = formatter.format(input)
        assertEquals("let x = -5\n", output)
    }

    @Test
    fun `format not method call`() {
        // `!` unary operator was removed; boolean negation is now `.not {}`.
        val input = "let x = True.not {}"
        val output = formatter.format(input)
        assertEquals("let x = True.not {}\n", output)
    }

    // =========================================================================
    // RECORDS
    // =========================================================================

    @Test
    fun `format empty record`() {
        val input = "let x = {}"
        val output = formatter.format(input)
        assertEquals("let x = {}\n", output)
    }

    @Test
    fun `format simple record on single line`() {
        val input = "let x = {a = 1, b = 2}"
        val output = formatter.format(input)
        assertEquals("let x = { a = 1, b = 2 }\n", output)
    }

    @Test
    fun `format record shorthand`() {
        val input = "let y = {x}"
        val output = formatter.format(input)
        assertEquals("let y = { x }\n", output)
    }

    @Test
    fun `format record with spread`() {
        val input = "let y = {...base, x = 1}"
        val output = formatter.format(input)
        assertEquals("let y = { ...base, x = 1 }\n", output)
    }

    // Note: Record update syntax {x | a = 1} is not currently supported by the parser
    // The AST has RecordUpdate but it may be generated internally, not parsed from source

    // =========================================================================
    // ARRAYS
    // =========================================================================

    @Test
    fun `format empty seq`() {
        val input = "let x = []"
        val output = formatter.format(input)
        assertEquals("let x = []\n", output)
    }

    @Test
    fun `format simple seq on single line`() {
        val input = "let x = [1, 2, 3]"
        val output = formatter.format(input)
        assertEquals("let x = [1, 2, 3]\n", output)
    }

    @Test
    fun `format seq with spread`() {
        val input = "let x = [...arr, 4, 5]"
        val output = formatter.format(input)
        assertEquals("let x = [...arr, 4, 5]\n", output)
    }

    // Note: Seq index access syntax arr[0] is not supported by the parser
    // Use seq::get {xs = arr, i = 0} instead

    // =========================================================================
    // LAMBDAS
    // =========================================================================

    @Test
    fun `format simple lambda`() {
        val input = "let f = {x} -> x"
        val output = formatter.format(input)
        assertEquals("let f = { x } -> x\n", output)
    }

    @Test
    fun `format lambda with multiple parameters`() {
        val input = "let f = {x, y} -> x + y"
        val output = formatter.format(input)
        assertEquals("let f = { x, y } -> x + y\n", output)
    }

    @Test
    fun `format lambda with typed parameters`() {
        val input = "let f = {x: Int, y: Int} -> x + y"
        val output = formatter.format(input)
        assertEquals("let f = { x: Int, y: Int } -> x + y\n", output)
    }

    @Test
    fun `format lambda with unit parameter`() {
        val input = "let f = {} -> 42"
        val output = formatter.format(input)
        assertEquals("let f = {} -> 42\n", output)
    }

    // =========================================================================
    // FUNCTION CALLS
    // =========================================================================

    @Test
    fun `format function call with record argument`() {
        val input = "let x = foo {a = 1, b = 2}"
        val output = formatter.format(input)
        assertEquals("let x = foo { a = 1, b = 2 }\n", output)
    }

    @Test
    fun `format function call with unit argument`() {
        val input = "let x = foo {}"
        val output = formatter.format(input)
        assertEquals("let x = foo {}\n", output)
    }

    // =========================================================================
    // MEMBER ACCESS
    // =========================================================================

    @Test
    fun `format member access`() {
        val input = "let x = record.field"
        val output = formatter.format(input)
        assertEquals("let x = record.field\n", output)
    }

    @Test
    fun `format chained member access`() {
        val input = "let x = a.b.c"
        val output = formatter.format(input)
        assertEquals("let x = a.b.c\n", output)
    }

    // =========================================================================
    // IF EXPRESSIONS
    // =========================================================================

    @Test
    fun `format if expression`() {
        val input = "let x = if True then 1 else 0"
        val output = formatter.format(input)
        assertEquals("let x = if True then 1 else 0\n", output)
    }

    @Test
    fun `format nested if expression`() {
        val input = "let x = if a then 1 else if b then 2 else 3"
        val output = formatter.format(input)
        assertEquals("let x = if a then 1 else if b then 2 else 3\n", output)
    }

    // =========================================================================
    // MATCH EXPRESSIONS
    // =========================================================================

    @Test
    fun `format simple match expression`() {
        val input = """
            let x = match y is
                Some {val} -> val
                None -> 0
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = match y is\n  Some { val } -> val\n  None -> 0\n", output)
    }

    @Test
    fun `format match with guard`() {
        val input = """
            let x = match y is
                Some {val} if val > 0 -> val
                _ -> 0
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = match y is\n  Some { val } if val > 0 -> val\n  _ -> 0\n", output)
    }

    @Test
    fun `format match with or patterns`() {
        val input = """
            let x = match y is
                Some {val} | Ok {val} -> val
                _ -> 0
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = match y is\n  Some { val } | Ok { val } -> val\n  _ -> 0\n", output)
    }

    // =========================================================================
    // DO BLOCKS
    // =========================================================================

    @Test
    fun `format do block`() {
        val input = """
            let x = do
                let a = 1
                let b = 2
                a + b
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = do\n  let a = 1\n  let b = 2\n  a + b\n", output)
    }

    @Test
    fun `format nested do block`() {
        val input = """
            let x = do
                let a = do
                    let b = 1
                    b + 1
                a + 1
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("let x = do\n  let a = do\n    let b = 1\n    b + 1\n  a + 1\n", output)
    }

    // =========================================================================
    // VARIANTS
    // =========================================================================

    @Test
    fun `format variant construction without value`() {
        val input = "let x = None"
        val output = formatter.format(input)
        assertEquals("let x = None\n", output)
    }

    @Test
    fun `format variant construction with value`() {
        val input = "let x = Some {val = 42}"
        val output = formatter.format(input)
        assertEquals("let x = Some { val = 42 }\n", output)
    }

    // =========================================================================
    // TYPE DECLARATIONS
    // =========================================================================

    @Test
    fun `format simple type alias`() {
        val input = "type Point = {x: Int, y: Int}"
        val output = formatter.format(input)
        assertEquals("type Point = { x: Int, y: Int }\n", output)
    }

    @Test
    fun `format variant type`() {
        val input = "type Option 'a = | Some { value: 'a }, None |"
        val output = formatter.format(input)
        assertEquals("type Option 'a = | Some { value: 'a }, None |\n", output)
    }

    @Test
    fun `format newtype`() {
        // New syntax: `newtype UserId = Int` (no redundant constructor).
        val input = "newtype UserId = Int"
        val output = formatter.format(input)
        assertEquals("newtype UserId = Int\n", output)
    }

    @Test
    fun `format pub type`() {
        val input = "pub type Point = {x: Int, y: Int}"
        val output = formatter.format(input)
        assertEquals("pub type Point = { x: Int, y: Int }\n", output)
    }

    // =========================================================================
    // EFFECT DECLARATIONS
    // =========================================================================

    @Test
    fun `format effect declaration`() {
        val input = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("effect Log where {\n  log: { msg: String } -> {} <Log>\n}\n", output)
    }

    @Test
    fun `format effect with multiple operations`() {
        val input = """
            effect Console where {
                print: {msg: String} -> {} <Console>,
                read: {} -> String <Console>
            }
        """.trimIndent()
        val output = formatter.format(input)
        // Each operation on its own line, comma after all but last
        assertEquals("effect Console where {\n  print: { msg: String } -> {} <Console>,\n  read: {} -> String <Console>\n}\n", output)
    }

    // =========================================================================
    // PERFORM EXPRESSIONS
    // =========================================================================

    @Test
    fun `format perform expression`() {
        val input = "let x = perform Handler in body"
        val output = formatter.format(input)
        assertEquals("let x = perform Handler in body\n", output)
    }

    @Test
    fun `format perform with multiple handlers`() {
        val input = "let x = perform Handler1, Handler2 in body"
        val output = formatter.format(input)
        assertEquals("let x = perform Handler1, Handler2 in body\n", output)
    }

    // =========================================================================
    // USE STATEMENTS
    // =========================================================================

    @Test
    fun `format simple use`() {
        val input = "use std::int::Int"
        val output = formatter.format(input)
        assertEquals("use std::int::Int\n", output)
    }

    @Test
    fun `format selective use`() {
        val input = "use std::option::{Some, None}"
        val output = formatter.format(input)
        assertEquals("use std::option::{ Some, None }\n", output)
    }

    @Test
    fun `format pub use`() {
        val input = "pub use std::int::Int"
        val output = formatter.format(input)
        assertEquals("pub use std::int::Int\n", output)
    }

    @Test
    fun `format use statements grouped without blank lines`() {
        val input = """
            use std::int::Int
            use std::str::String
            let x = 42
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("use std::int::Int\nuse std::str::String\n\nlet x = 42\n", output)
    }

    @Test
    fun `format use statements with blank line before code`() {
        val input = """
            use std::int::Int
            let x = 42
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals("use std::int::Int\n\nlet x = 42\n", output)
    }

    // =========================================================================
    // ABILITY DECLARATIONS
    // =========================================================================

    @Test
    fun `format ability declaration`() {
        val input = """
            ability Eq for 'a where {
                eq: {l: 'a, r: 'a} -> Bool
            }
        """.trimIndent()
        val output = formatter.format(input)
        // Check it parses and formats without error
        assertTrue(output.contains("ability Eq"))
        assertTrue(output.contains("eq:"))
    }

    // =========================================================================
    // IMPL DECLARATIONS
    // =========================================================================

    @Test
    fun `format impl ability declaration`() {
        val input = """
            impl NumAdd for Int where {
                add = {l, r} -> l + r
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("impl NumAdd for Int"))
        assertTrue(output.contains("add ="))
    }

    // =========================================================================
    // IDEMPOTENCY TESTS
    // =========================================================================

    @Test
    fun `formatter is idempotent for simple code`() {
        val input = "let x = 42"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for complex code`() {
        val input = """
            let add = {x, y} -> x + y
            let result = add {x = 1, y = 2}
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for match expression`() {
        val input = """
            let x = match y is
                Some {val} -> val
                None -> 0
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for do block`() {
        val input = """
            let x = do
                let a = 1
                a + 1
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for effect declaration`() {
        val input = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for string interpolation`() {
        val input = """let x = "Hello $(name)!""""
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for seq with spread`() {
        val input = "let x = [...arr, 1, 2]"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `formatter is idempotent for record with spread`() {
        val input = "let x = {...base, a = 1}"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    // =========================================================================
    // COMMENTS
    // =========================================================================

    @Test
    fun `format preserves leading comment`() {
        val input = """
            # This is a comment
            let x = 42
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# This is a comment"))
        assertTrue(output.contains("let x = 42"))
    }

    @Test
    fun `format preserves trailing comment`() {
        val input = "let x = 42  # trailing"
        val output = formatter.format(input)
        assertTrue(output.contains("# trailing"))
    }

    @Test
    fun `format preserves multiple comments`() {
        val input = """
            # First comment
            let x = 42
            # Second comment
            let y = 10
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("# First comment"))
        assertTrue(output.contains("# Second comment"))
    }

    @Test
    fun `formatter is idempotent for comments`() {
        val input = """
            # Header comment
            let x = 42  # trailing
            # Another comment
            let y = 10
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    // =========================================================================
    // LINE BREAKING
    // =========================================================================

    @Test
    fun `format long record on multiple lines`() {
        val input = "let x = {very_long_field_name_one = 1, very_long_field_name_two = 2, very_long_field_name_three = 3}"
        val output = formatter.format(input)
        // Should break to multiple lines
        assertTrue(output.contains("\n  "))
        assertTrue(output.contains("very_long_field_name_one = 1"))
    }

    @Test
    fun `format long seq on multiple lines`() {
        val input = "let x = [very_long_element_name_one, very_long_element_name_two, very_long_element_name_three]"
        val output = formatter.format(input)
        // Should break to multiple lines
        assertTrue(output.contains("\n  "))
    }

    @Test
    fun `format short record on single line`() {
        val input = "let x = {a = 1, b = 2}"
        val output = formatter.format(input)
        // Should stay on single line
        assertEquals("let x = { a = 1, b = 2 }\n", output)
    }

    @Test
    fun `formatter is idempotent for long record`() {
        val input = "let x = {very_long_field_name_one = 1, very_long_field_name_two = 2, very_long_field_name_three = 3}"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `format long lambda breaks body to new line`() {
        val input = "let very_long_function_name_that_is_way_too_long = {some_parameter_with_a_long_name, another_very_long_parameter_name, yet_another_parameter} -> some_parameter_with_a_long_name + another_very_long_parameter_name"
        val output = formatter.format(input)
        // Should break the body to a new line due to length
        assertTrue(output.contains("->\n"), "Should break after -> for long lambda")
    }

    @Test
    fun `format short lambda stays on single line`() {
        val input = "let add = {x, y} -> x + y"
        val output = formatter.format(input)
        assertEquals("let add = { x, y } -> x + y\n", output)
    }

    @Test
    fun `formatter is idempotent for long lambda`() {
        val input = "let very_long_function_name_that_is_way_too_long = {some_parameter_with_a_long_name, another_very_long_parameter_name, yet_another_parameter} -> some_parameter_with_a_long_name + another_very_long_parameter_name"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `format long binary expression`() {
        // Long chain of binary operations - these are currently formatted on one line
        // since breaking them mid-expression can be tricky
        val input = "let result = a + b + c + d + e + f + g"
        val output = formatter.format(input)
        assertEquals("let result = a + b + c + d + e + f + g\n", output)
    }

    @Test
    fun `format very long binary expression preserves correctness`() {
        val input = "let result = very_long_variable_name_one + very_long_variable_name_two + very_long_variable_name_three"
        val output = formatter.format(input)
        // Should contain the expression even if long
        assertTrue(output.contains("very_long_variable_name_one + very_long_variable_name_two"))
    }

    // =========================================================================
    // PARENTHESES - ALWAYS PRESERVED
    // =========================================================================

    @Test
    fun `format preserves parentheses for precedence`() {
        val input = "let x = (a + b) * c"
        val output = formatter.format(input)
        assertEquals("let x = (a + b) * c\n", output)
    }

    @Test
    fun `format preserves parentheses on right side`() {
        val input = "let x = a * (b + c)"
        val output = formatter.format(input)
        assertEquals("let x = a * (b + c)\n", output)
    }

    @Test
    fun `format preserves all parentheses even when not strictly needed`() {
        // We preserve what user wrote - don't try to be clever
        val input = "let x = (a * b) + c"
        val output = formatter.format(input)
        assertEquals("let x = (a * b) + c\n", output)
    }

    @Test
    fun `format preserves redundant parentheses`() {
        val input = "let x = (a + b) + c"
        val output = formatter.format(input)
        assertEquals("let x = (a + b) + c\n", output)
    }

    @Test
    fun `format preserves nested parentheses`() {
        val input = "let x = ((a + b))"
        val output = formatter.format(input)
        assertEquals("let x = ((a + b))\n", output)
    }

    // =========================================================================
    // PIPE EXPRESSIONS
    // =========================================================================

    @Test
    fun `format simple pipe expression`() {
        val input = "let x = 1 |> f { a = @ }"
        val output = formatter.format(input)
        assertEquals("let x = 1 |> f { a = @ }\n", output)
    }

    @Test
    fun `format chained pipe expression`() {
        val input = "let x = 1 |> f { a = @ } |> g { b = @ }"
        val output = formatter.format(input)
        assertEquals("let x = 1 |> f { a = @ } |> g { b = @ }\n", output)
    }

    @Test
    fun `format pipe with nested placeholder`() {
        val input = "let x = 1 |> f { a = g { b = @ } }"
        val output = formatter.format(input)
        assertEquals("let x = 1 |> f { a = g { b = @ } }\n", output)
    }

    @Test
    fun `format long pipe chain on multiple lines`() {
        val input = "let x = 1 |> first_function { a = @ } |> second_function { b = @ } |> third_function { c = @ } |> fourth_function { d = @ }"
        val output = formatter.format(input)
        // Should be multi-line due to length
        assertTrue(output.contains("|>"))
        assertTrue(output.contains("first_function"))
        assertTrue(output.contains("fourth_function"))
    }

    @Test
    fun `formatter is idempotent for pipe expression`() {
        val input = "let x = 1 |> f { a = @ } |> g { b = @ }"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second)
    }

    @Test
    fun `format pipe with complex left side`() {
        val input = "let x = (a + b) |> f { n = @ }"
        val output = formatter.format(input)
        assertEquals("let x = (a + b) |> f { n = @ }\n", output)
    }

    // =========================================================================
    // ABILITY & IMPL FORMATTING (regression tests)
    // =========================================================================

    @Test
    fun `format ability with explicit it param`() {
        val input = """
            ability Eq for 'a where {
                eq: {it: 'a, r: 'a} -> Bool,
                neq: {it: 'a, r: 'a} -> Bool
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("ability Eq for 'a"))
        assertTrue(output.contains("eq:"))
        assertTrue(output.contains("neq:"))
    }

    @Test
    fun `format multi-param ability declaration`() {
        val input = """
            ability Into 'b for 'a where {
                into: {it: 'a} -> 'b
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("ability Into 'b for 'a"))
        assertTrue(output.contains("into:"))
    }

    @Test
    fun `format impl preserves ability type args`() {
        val input = """
            impl Into Str for Int where { into = {it} -> __native_int_to_str {n = it} }
            impl Into Float for Int where { into = {it} -> __native_int_to_float {n = it} }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("impl Into Str for Int"), "Missing 'Into Str for Int'")
        assertTrue(output.contains("impl Into Float for Int"), "Missing 'Into Float for Int'")
    }

    @Test
    fun `format impl with parametric type args`() {
        val input = "impl Eq 'a for List 'a where { eq = {it, r} -> true, neq = {it, r} -> false }"
        val output = formatter.format(input)
        assertTrue(output.contains("impl Eq 'a for List 'a"), "Missing parametric type args")
    }

    @Test
    fun `format inherent impl`() {
        val input = """
            impl Point where {
                get_x: {it: Point} -> Int = {it} -> it.x,
                get_y: {it: Point} -> Int = {it} -> it.y
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("impl Point where"))
        assertTrue(output.contains("get_x"))
        assertTrue(output.contains("get_y"))
    }

    // =========================================================================
    // IMPL TYPE ANNOTATION PRESERVATION
    // =========================================================================

    @Test
    fun `format impl preserves operation type annotations`() {
        // Ability must be in same file for parser to resolve impl as ParsedImplAbilityDecl
        val input = """
            ability Eq for 'a where {
                eq: {it: 'a, r: 'a} -> Bool,
                neq: {it: 'a, r: 'a} -> Bool
            }

            impl Eq for Int where {
                eq: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_eq {l = it, r = r},
                neq: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_neq {l = it, r = r}
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("eq: { it: Int, r: Int } -> Bool ="), "eq type annotation must be preserved")
        assertTrue(output.contains("neq: { it: Int, r: Int } -> Bool ="), "neq type annotation must be preserved")
    }

    @Test
    fun `format impl preserves operation type annotations with ability constraints`() {
        val input = """
            impl Show for List 'a where {
                show: {it: List 'a} with Show 'a -> Str = {it} -> "[]"
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("show:"), "Operation name must be present")
        assertTrue(output.contains("with Show 'a"), "Ability constraint on operation must be preserved")
    }

    @Test
    fun `format inherent impl preserves type annotations`() {
        val input = """
            impl Point where {
                get_x: {it: Point} -> Int = {it} -> it.x,
                get_y: {it: Point} -> Int = {it} -> it.y
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("get_x: { it: Point } -> Int ="), "get_x type annotation must be preserved")
        assertTrue(output.contains("get_y: { it: Point } -> Int ="), "get_y type annotation must be preserved")
    }

    @Test
    fun `format impl for imported ability preserves type annotations`() {
        // When ability is NOT in same file, parser keeps as ParsedPerformerDecl.
        // The formatter must still preserve type annotations on performer ops.
        val input = """
            impl Eq for Color where {
                eq: {it: Color, r: Color} -> Bool = {it, r} -> True,
                neq: {it: Color, r: Color} -> Bool = {it, r} -> False
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("eq: { it: Color, r: Color } -> Bool ="), "eq type annotation must be preserved for imported ability impl. Got:\n$output")
        assertTrue(output.contains("neq: { it: Color, r: Color } -> Bool ="), "neq type annotation must be preserved")
    }

    @Test
    fun `format impl for imported ability annotation is idempotent`() {
        val input = """
            impl Eq for Color where {
                eq: {it: Color, r: Color} -> Bool = {it, r} -> True,
                neq: {it: Color, r: Color} -> Bool = {it, r} -> False
            }
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second, "Formatting impl with type annotations must be idempotent")
    }

    @Test
    fun `format impl without type annotations stays without`() {
        val input = """
            impl Eq for Int where {
                eq = {it, r} -> __native_int_eq {l = it, r = r}
            }
        """.trimIndent()
        val output = formatter.format(input)
        // No colon after eq — no type annotation
        assertTrue(output.contains("eq ="), "Should have 'eq =' without type annotation")
        assertFalse(output.contains("eq:"), "Should NOT add type annotation that wasn't there")
    }

    @Test
    fun `format impl type annotation is idempotent`() {
        val input = """
            impl Ord for Int where {
                lt: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_lt {l = it, r = r},
                gt: {it: Int, r: Int} -> Bool = {it, r} -> __native_int_gt {l = it, r = r}
            }
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second, "Impl with type annotations must be idempotent")
    }

    // =========================================================================
    // FUNCTION TYPE ANNOTATIONS
    // =========================================================================

    @Test
    fun `format function type annotation preserves ability constraints`() {
        val input = "pub let min: {l: 'a, r: 'a} with Ord 'a -> 'a = {l, r} -> if l < r then l else r"
        val output = formatter.format(input)
        assertTrue(output.contains("with Ord 'a"), "Missing ability constraint 'with Ord 'a'")
    }

    @Test
    fun `format function type annotation preserves multiple constraints`() {
        val input = "pub let f: {x: 'a} with Eq 'a, Ord 'a -> Bool = {x} -> true"
        val output = formatter.format(input)
        assertTrue(output.contains("with Eq 'a, Ord 'a"), "Missing multiple constraints")
    }

    @Test
    fun `format record type annotation stays single line`() {
        val input = "pub let config = {pretty: Bool, indent: Int, field_transform: Str, skip_null: Bool} -> {pretty = pretty, indent = indent, field_transform = field_transform, skip_null = skip_null}"
        val output = formatter.format(input)
        // Record type annotation must be on one line (no INDENT tokens)
        val typeAnnotationLine = output.lines().first { it.contains("pretty: Bool") }
        assertTrue(typeAnnotationLine.contains("skip_null: Bool"), "Record type annotation should be single-line")
    }

    @Test
    fun `format impl with match body keeps comma on same line`() {
        val input = """
            impl Eq for Color where {
                eq = {it, r} -> match it is
                    Red -> match r is
                        Red -> True
                        _ -> False
                    _ -> False,
                neq = {it, r} -> (it.eq {r = r}).not {}
            }
        """.trimIndent()
        val output = formatter.format(input)
        // The comma after eq's match body should NOT be on its own line
        assertFalse(output.lines().any { it.trim() == "," }, "Comma should not be on its own line")
        assertTrue(output.contains("eq ="))
        assertTrue(output.contains("neq ="))
    }

    @Test
    fun `format nested match has no blank lines between arms`() {
        val input = """
            let x = match a is
                Ok {val} -> match b is
                    Ok {val = v} -> v
                    Err {error} -> 0
                Err {error} -> 0
        """.trimIndent()
        val output = formatter.format(input)
        // No blank lines between match arms
        val lines = output.lines()
        for (i in 0 until lines.size - 1) {
            if (lines[i].isBlank() && lines[i + 1].isBlank()) {
                fail<Unit>("Found consecutive blank lines at line ${i + 1}")
            }
        }
    }

    @Test
    fun `format impl with TryInto preserves error type`() {
        val input = """
            impl TryInto Int |ConversionError {msg: Str}| for Float where {
                try_into = {it} -> Ok {val = 0}
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("TryInto Int"), "Missing 'TryInto Int'")
        assertTrue(output.contains("for Float"), "Missing 'for Float'")
    }

    @Test
    fun `format ability impl is idempotent`() {
        val input = """
            impl Into Str for Int where {
              into = { it } -> __native_int_to_str { n = it }
            }
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second, "Ability impl formatting should be idempotent")
    }

    @Test
    fun `format impl with effect annotation`() {
        val input = """
            impl Channel for BlockingChannel where {
                send = {ch, value} -> __native_channel_send {ch = ch, value = value},
                recv = {ch} -> __native_channel_recv {ch = ch}
            }
        """.trimIndent()
        val output = formatter.format(input)
        assertTrue(output.contains("impl Channel for BlockingChannel"))
        assertTrue(output.contains("send ="))
        assertTrue(output.contains("recv ="))
    }

    // =========================================================================
    // Ability constraints with associated-type bindings — round-trip coverage
    // =========================================================================
    //
    // The pre-arrow `with` clause supports a `having 'tv = T, 'tv2 = T2` suffix
    // per ability for associated-type bindings (e.g. `Container 'c having
    // 'item = Int`). Make sure the formatter (a) emits the `having` clause at
    // all, (b) preserves multi-binding order, and (c) round-trips idempotently
    // across the trickier shapes.

    @Test
    fun `format ability constraint with associated type binding`() {
        val input = "pub let f: {c: 'c} with Container 'c having 'item = Int -> Int = {c} -> 0"
        val output = formatter.format(input)
        assertTrue(
            output.contains("with Container 'c having 'item = Int"),
            "Missing `having 'item = Int`. Output:\n$output",
        )
    }

    @Test
    fun `format ability constraint with multiple associated type bindings`() {
        val input = "pub let f: {i: 'i} with Iterator 'i having 'item = Int, 'state = Bool -> Int = {i} -> 0"
        val output = formatter.format(input)
        assertTrue(
            output.contains("with Iterator 'i having 'item = Int, 'state = Bool"),
            "Missing multi-binding `having` clause. Output:\n$output",
        )
    }

    @Test
    fun `format multiple constraints each with their own associated type bindings`() {
        val input = "pub let f: {a: 'a, b: 'b} with Container 'a having 'item = Int, Iterator 'b having 'elem = Str -> {} = {a, b} -> {}"
        val output = formatter.format(input)
        // Each constraint keeps its own `having` clause; commas inside one
        // clause must not split out into the next constraint.
        assertTrue(
            output.contains("with Container 'a having 'item = Int, Iterator 'b having 'elem = Str"),
            "Constraint/having grouping broken. Output:\n$output",
        )
    }

    @Test
    fun `format mixed constraints — bare and with having clauses`() {
        val input = "pub let f: {a: 'a, b: 'b} with Eq 'a, Container 'b having 'item = Int -> {} = {a, b} -> {}"
        val output = formatter.format(input)
        assertTrue(
            output.contains("with Eq 'a, Container 'b having 'item = Int"),
            "Bare + having-bearing mix broken. Output:\n$output",
        )
    }

    @Test
    fun `format ability constraint with complex associated type`() {
        // Compound type as the binding RHS: Result (Option Int)
        val input = "pub let f: {c: 'c} with Container 'c having 'item = (Option Int) -> Int = {c} -> 0"
        val output = formatter.format(input)
        assertTrue(
            output.contains("'item = (Option Int)") ||
                output.contains("'item = Option Int"),
            "Compound-type associated binding lost. Output:\n$output",
        )
    }

    @Test
    fun `format ability constraint round-trip is idempotent`() {
        // Idempotence guard: formatting twice yields the same string. Catches
        // subtle shape drift (e.g. spaces around `=`, comma placement).
        val input = "pub let f: {a: 'a, b: 'b} with Container 'a having 'item = Int, Eq 'b -> Bool = {a, b} -> true"
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second, "with-clause formatting should be idempotent")
    }

    // =========================================================================
    // IN-BLOCK COMMENT PRESERVATION (#124)
    // =========================================================================

    @Test
    fun `regular comment inside a do block is preserved`() {
        val input = """
            let f = {} -> do
              # a comment inside the block
              let y = 2
              y + 1
        """.trimIndent()
        val out = formatter.format(input)
        assertTrue(out.contains("# a comment inside the block"),
            "in-block regular comment must survive formatting, got:\n$out")
    }

    @Test
    fun `doc and metadata comments inside a do block are preserved`() {
        val input = """
            let f = {} -> do
              ## a doc comment on a statement
              #{ tag: "x" }
              let y = 2
              y + 1
        """.trimIndent()
        val out = formatter.format(input)
        assertTrue(out.contains("## a doc comment on a statement"),
            "in-block `##` doc must survive, got:\n$out")
        assertTrue(out.contains("#{ tag:") && out.contains("\"x\"") ,
            "in-block `#{}` metadata must survive, got:\n$out")
    }

    @Test
    fun `comment before a match arm is preserved`() {
        val input = """
            let f = {x: Bool} -> match x is
              # the true case
              True -> 1
              ## doc for false
              False -> 2
        """.trimIndent()
        val out = formatter.format(input)
        assertTrue(out.contains("# the true case"),
            "in-block match-arm regular comment must survive, got:\n$out")
        assertTrue(out.contains("## doc for false"),
            "in-block match-arm `##` doc must survive, got:\n$out")
        // Idempotent.
        assertEquals(out, formatter.format(out), "match-arm comment formatting must be idempotent")
    }

    @Test
    fun `comment between record fields is preserved on a multi-line record`() {
        val input = """
            let r = {
              a = 1,
              # comment between fields
              b = 2,
            }
        """.trimIndent()
        val out = formatter.format(input)
        assertTrue(out.contains("# comment between fields"),
            "in-record comment must survive, got:\n$out")
        // Presence of a comment must force the record to stay multi-line.
        assertTrue(out.contains("\n"), "record with a comment must not collapse to one line, got:\n$out")
        assertEquals(out, formatter.format(out), "record-comment formatting must be idempotent")
    }

    @Test
    fun `record without comments still collapses to one line`() {
        val input = """
            let r = {
              a = 1,
              b = 2,
            }
        """.trimIndent()
        val out = formatter.format(input)
        assertEquals("let r = { a = 1, b = 2 }\n", out,
            "a small comment-free record should still collapse, got:\n$out")
    }

    @Test
    fun `formatting a do block with comments is idempotent`() {
        val input = """
            let f = {} -> do
              # first
              let a = 1
              ## doc
              let b = 2
              a + b
        """.trimIndent()
        val first = formatter.format(input)
        val second = formatter.format(first)
        assertEquals(first, second, "in-block comment formatting must be idempotent")
        assertTrue(first.contains("# first") && first.contains("## doc"),
            "both comments must survive, got:\n$first")
    }

    // =========================================================================
    // BLANK LINES BETWEEN TOP-LEVEL DECLS (balanced, not always-on)
    // The formatter no longer forces a blank line between every decl. It keeps
    // adjacent one-liners packed, preserves author grouping, collapses runs of
    // blanks to one, and always breathes around multi-line decls.
    // =========================================================================

    @Test
    fun `adjacent one-line lets stay packed`() {
        val input = """
            let name = "Mezze"
            let release = 0.1
            let ready = False
            let versions = [1, 2, 3]
        """.trimIndent()
        val output = formatter.format(input)
        assertEquals(
            "let name = \"Mezze\"\n" +
            "let release = 0.1\n" +
            "let ready = False\n" +
            "let versions = [1, 2, 3]\n",
            output
        )
        assertEquals(output, formatter.format(output), "must be idempotent")
    }

    @Test
    fun `author-written blank line between one-liners is preserved`() {
        val input = "let a = 1\nlet b = 2\n\nlet c = 3\n"
        val output = formatter.format(input)
        assertEquals("let a = 1\nlet b = 2\n\nlet c = 3\n", output)
        assertEquals(output, formatter.format(output), "must be idempotent")
    }

    @Test
    fun `multiple blank lines collapse to one`() {
        val input = "let a = 1\n\n\n\nlet b = 2\n"
        val output = formatter.format(input)
        assertEquals("let a = 1\n\nlet b = 2\n", output)
    }

    @Test
    fun `a comment glued to a decl does not force a blank line`() {
        val input = "let a = 1\n# labels b\nlet b = 2\n"
        val output = formatter.format(input)
        assertEquals("let a = 1\n# labels b\nlet b = 2\n", output)
        assertEquals(output, formatter.format(output), "must be idempotent")
    }

    @Test
    fun `a multi-line decl gets a blank line on each side`() {
        val input = """
            let name = "Mezze"
            let f = { a, b } -> do
              let s = a
              s
            let footer = "bye"
        """.trimIndent()
        val output = formatter.format(input)
        // name / f separated, f / footer separated — f is multi-line.
        assertTrue(
            Regex("let name = \"Mezze\"\\n\\nlet f = ").containsMatchIn(output),
            "expected blank before multi-line f, got:\n$output"
        )
        assertTrue(
            output.contains("  s\n\nlet footer"),
            "expected blank after multi-line f, got:\n$output"
        )
        assertEquals(output, formatter.format(output), "must be idempotent")
    }
}
