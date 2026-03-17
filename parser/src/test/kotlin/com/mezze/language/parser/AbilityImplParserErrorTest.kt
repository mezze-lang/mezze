package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityImplParserErrorTest {

    private val parser = MezzeParser()

    // ========================================================================
    // ABILITY DECLARATION ERRORS
    // ========================================================================

    @Test
    fun `ability missing for keyword gives helpful error`() {
        val source = """
            ability Foo where {
                show: {val: Int} -> String
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Expected 'for' keyword in ability declaration") == true,
            "Expected error about missing 'for' keyword, got: ${exception.message}"
        )
    }

    @Test
    fun `ability missing type variable after for gives helpful error`() {
        val source = """
            ability Foo for Int where {
                show: {val: Int} -> String
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Expected type variable after 'for' keyword") == true,
            "Expected error about missing type variable after 'for', got: ${exception.message}"
        )
    }

    @Test
    fun `ability missing comma between methods gives helpful error`() {
        val source = """
            ability Show for 'a where {
                show: {val: 'a} -> String
                debug: {val: 'a} -> String
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Missing ',' between ability methods") == true,
            "Expected error about missing comma between methods, got: ${exception.message}"
        )
    }

    @Test
    fun `ability operation with non-function type gives helpful error`() {
        val source = """
            ability Foo for 'a where {
                op: Int
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("must have a function type") == true,
            "Expected error about non-function type for ability operation, got: ${exception.message}"
        )
    }

    // ========================================================================
    // ABILITY IMPL ERRORS
    // ========================================================================

    @Test
    fun `named impl missing comma between methods is caught by effect impl parser`() {
        // Note: `impl Show for Int where { ... }` is first parsed by the effect impl parser
        // (which interprets Show as an effect and Int as a performer name), so the error
        // message refers to "effect impl operations" rather than "ability impl methods".
        val source = """
            impl Show for Int where {
                show: {val: Int} -> String = {val} -> "int"
                debug: {val: Int} -> String = {val} -> "debug"
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Missing ','") == true,
            "Expected error about missing comma, got: ${exception.message}"
        )
    }

    @Test
    fun `multi-type ability impl missing comma between methods gives helpful error`() {
        // Using multi-type impl syntax to bypass the effect impl parser
        // and trigger the ability impl parser's own comma error.
        val source = """
            impl KVStore for String Int where {
                store: {key: String, value: Int} -> {} = {key, value} -> {}
                load: {key: String} -> Int = {key} -> 0
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Missing ',' between ability impl methods") == true,
            "Expected error about missing comma between impl methods, got: ${exception.message}"
        )
    }

    @Test
    fun `anonymous impl second operation with non-function type gives helpful error`() {
        // The "must have a function type" error is only reachable for the second+ operation
        // in an impl block (after a comma), because the first operation's failure is treated
        // as "no operations" and the parser proceeds to look for '}'.
        val source = """
            impl Int where {
                double = {} -> 42,
                show: Int = 42
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("must have a function type") == true,
            "Expected error about non-function type for impl operation, got: ${exception.message}"
        )
    }

    // ========================================================================
    // PUB RESTRICTION ERRORS
    // ========================================================================

    @Test
    fun `pub ability is rejected with helpful error`() {
        val source = """
            pub ability Show for 'a where {
                show: {val: 'a} -> String
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("'pub' is not allowed on ability declarations") == true,
            "Expected error about pub not allowed on ability, got: ${exception.message}"
        )
    }

    @Test
    fun `pub impl is rejected with helpful error`() {
        val source = """
            pub impl Show for Int where {
                show: {val: Int} -> String = {val} -> "int"
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("'pub' is not allowed on impl declarations") == true,
            "Expected error about pub not allowed on impl, got: ${exception.message}"
        )
    }

    // ========================================================================
    // EMPTY BODY TESTS
    // ========================================================================

    @Test
    fun `empty ability body parses successfully`() {
        val source = """
            ability Empty for 'a where {}
        """.trimIndent()

        // Empty ability body is allowed (no operations is fine)
        val program = parser.parse(source)
        assertEquals(1, program.items.size)
    }

    @Test
    fun `empty impl body parses successfully`() {
        val source = """
            impl Show for Int where {}
        """.trimIndent()

        // Empty impl body is allowed (no operations is fine)
        val program = parser.parse(source)
        assertEquals(1, program.items.size)
    }

    // ========================================================================
    // HAVING CLAUSE ERRORS
    // ========================================================================

    @Test
    fun `ability having clause with non-type-variable gives error`() {
        val source = """
            ability Container for 'a having Int where {
                get: {c: 'a} -> Int
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Type variable must start with '") == true
                || exception.message?.contains("Expected type variable after 'having'") == true,
            "Expected error about type variable in having clause, got: ${exception.message}"
        )
    }

    @Test
    fun `ability having clause missing type variable after comma gives error`() {
        val source = """
            ability Container for 'a having 'item, where {
                get: {c: 'a} -> 'item
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Expected type variable after ','") == true
                || exception.message?.contains("Expected type variable") == true,
            "Expected error about missing type variable after comma in having clause, got: ${exception.message}"
        )
    }

    // ========================================================================
    // ANONYMOUS IMPL ERRORS
    // ========================================================================

    @Test
    fun `anonymous impl missing comma between methods gives helpful error`() {
        val source = """
            impl Int where {
                double = {} -> 42
                triple = {} -> 63
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Missing ',' between ability impl methods") == true,
            "Expected error about missing comma between anonymous impl methods, got: ${exception.message}"
        )
    }

    // ========================================================================
    // ADDITIONAL SYNTAX ERRORS
    // ========================================================================

    @Test
    fun `ability with multiple generic params missing for keyword gives error`() {
        val source = """
            ability Convert 'b where {
                convert: {val: 'a} -> 'b
            }
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("Expected 'for' keyword in ability declaration") == true,
            "Expected error about missing 'for' keyword, got: ${exception.message}"
        )
    }

    @Test
    fun `ability syntax hint includes ability name`() {
        val source = """
            ability MyAbility where {}
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("MyAbility") == true,
            "Expected error to include the ability name 'MyAbility', got: ${exception.message}"
        )
    }

    @Test
    fun `ability for keyword error includes syntax example`() {
        val source = """
            ability Foo where {}
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("ability Foo for 'a where") == true,
            "Expected error to include syntax example, got: ${exception.message}"
        )
    }

    @Test
    fun `ability type variable error includes syntax example`() {
        val source = """
            ability Foo for String where {}
        """.trimIndent()

        val exception = assertThrows(ParseException::class.java) {
            parser.parse(source)
        }
        assertTrue(
            exception.message?.contains("ability Foo for 'a where") == true,
            "Expected error to include syntax example with type variable, got: ${exception.message}"
        )
    }
}
