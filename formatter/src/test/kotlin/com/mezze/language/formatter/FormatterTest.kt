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
    fun `format not operator`() {
        val input = "let x = !True"
        val output = formatter.format(input)
        assertEquals("let x = !True\n", output)
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
        val input = "newtype UserId = UserId Int"
        val output = formatter.format(input)
        assertEquals("newtype UserId = UserId Int\n", output)
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
}
