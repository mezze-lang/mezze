package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for trailing comma support (Rust-style)
 *
 * Trailing commas are allowed (but not required) in all comma-separated constructs:
 * - Array literals
 * - Record literals
 * - Array patterns
 * - Record patterns
 * - Variant constructor payloads
 * - Lambda parameters
 * - Function type parameters
 * - Type definitions
 */
class TrailingCommaTest {

    private val parser = MezzeParser()

    // ========== Array Literals ==========

    @Test
    fun `parse array literal with trailing comma - single line`() {
        val code = "let nums = [1, 2, 3,]"

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val array = letBinding.expression as ArrayLiteral
        assertEquals(3, array.elements.size)
    }

    @Test
    fun `parse array literal with trailing comma - multiline`() {
        val code = """
            let nums = [
              1,
              2,
              3,
            ]
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val array = letBinding.expression as ArrayLiteral
        assertEquals(3, array.elements.size)
    }

    @Test
    fun `parse array literal without trailing comma still works`() {
        val code = "let nums = [1, 2, 3]"

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val array = letBinding.expression as ArrayLiteral
        assertEquals(3, array.elements.size)
    }

    @Test
    fun `parse nested array literals with trailing commas`() {
        val code = "let matrix = [[1, 2,], [3, 4,],]"

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val array = letBinding.expression as ArrayLiteral
        assertEquals(2, array.elements.size)
    }

    @Test
    fun `parse single element array with trailing comma`() {
        val code = "let singleton = [42,]"

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val array = letBinding.expression as ArrayLiteral
        assertEquals(1, array.elements.size)
    }

    // ========== Record Literals ==========

    @Test
    fun `parse record literal with trailing comma - single line`() {
        val code = """let user = {name = "Alice", age = 30,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse record literal with trailing comma - multiline`() {
        val code = """
            let user = {
              name = "Alice",
              age = 30,
            }
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse record literal without trailing comma still works`() {
        val code = """let user = {name = "Alice", age = 30}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse nested record literals with trailing commas`() {
        val code = """let config = {server = {host = "localhost", port = 8080,}, debug = true,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse single field record with trailing comma`() {
        val code = """let wrapper = {value = 42,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(1, record.entries.size)
    }

    // ========== Array Patterns ==========

    @Test
    fun `parse array pattern with trailing comma - two elements`() {
        val code = """
            match xs is
              [x, y,] -> x + y
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertEquals(2, pattern.elements.size)
    }

    @Test
    fun `parse array pattern with trailing comma - multiline`() {
        val code = """
            match xs is
              [
                x,
                y,
                z,
              ] -> x + y + z
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertEquals(3, pattern.elements.size)
    }

    @Test
    fun `parse array pattern with rest and trailing comma`() {
        val code = """
            match xs is
              [head, ...tail,] -> head
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertEquals(1, pattern.elements.size)
        assertEquals("tail", pattern.restPattern)
    }

    @Test
    fun `parse single element array pattern with trailing comma`() {
        val code = """
            match xs is
              [x,] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertEquals(1, pattern.elements.size)
    }

    // ========== Record Patterns ==========

    @Test
    fun `parse record pattern with trailing comma`() {
        val code = """
            match user is
              {name, age,} -> name
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as RecordPattern
        assertEquals(2, pattern.entries.size)
    }

    @Test
    fun `parse record pattern with explicit binding and trailing comma`() {
        val code = """
            match user is
              {name = n, age = a,} -> n
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as RecordPattern
        assertEquals(2, pattern.entries.size)
    }

    @Test
    fun `parse single field record pattern with trailing comma`() {
        val code = """
            match user is
              {name,} -> name
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val pattern = matchExpr.cases[0].patterns.first() as RecordPattern
        assertEquals(1, pattern.entries.size)
    }

    // ========== Variant Constructors ==========

    @Test
    fun `parse variant constructor with trailing comma in payload`() {
        val code = """let result = Some {value = 42,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val constructor = letBinding.expression as VariantConstruction
        assertTrue(constructor.value is RecordLiteral)
        val payload = constructor.value as RecordLiteral
        assertEquals(1, payload.entries.size)
    }

    @Test
    fun `parse variant constructor with multiple fields and trailing comma`() {
        val code = """let person = User {name = "Alice", age = 30,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val constructor = letBinding.expression as VariantConstruction
        assertTrue(constructor.value is RecordLiteral)
        val payload = constructor.value as RecordLiteral
        assertEquals(2, payload.entries.size)
    }

    // ========== Lambda Parameters ==========

    @Test
    fun `parse lambda with trailing comma in untyped params`() {
        val code = """let add = {x, y, z,} -> x + y + z"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val lambda = letBinding.expression as LambdaExpression
        assertTrue(lambda.parameters is UntypedParameters)
        val params = lambda.parameters as UntypedParameters
        assertEquals(3, params.names.size)
    }

    @Test
    fun `parse lambda with trailing comma in typed params`() {
        val code = """let add: {x: Int, y: Int, z: Int,} -> Int = {x, y, z} -> x + y + z"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        // Type annotation should have trailing comma parsed correctly
        val funcType = letBinding.typeAnnotation as FunctionTypeAnnotation
        val paramType = funcType.paramType as RecordTypeAnnotation
        assertEquals(3, paramType.entries.size)
    }

    @Test
    fun `parse single param lambda with trailing comma`() {
        val code = """let identity = {x,} -> x"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val lambda = letBinding.expression as LambdaExpression
        assertTrue(lambda.parameters is UntypedParameters)
        val params = lambda.parameters as UntypedParameters
        assertEquals(1, params.names.size)
    }

    // ========== Function Type Parameters ==========

    @Test
    fun `parse function type with trailing comma in params`() {
        val code = """let process: {x: Int, y: Int,} -> Int = {x, y} -> x + y"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val funcType = letBinding.typeAnnotation as FunctionTypeAnnotation
        val paramType = funcType.paramType as RecordTypeAnnotation
        assertEquals(2, paramType.entries.size)
    }

    @Test
    fun `parse nested function type with trailing commas`() {
        val code = """let apply: {f: {x: Int,} -> Int, value: Int,} -> Int = {f, value} -> f {x = value}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val funcType = letBinding.typeAnnotation as FunctionTypeAnnotation
        val paramType = funcType.paramType as RecordTypeAnnotation
        assertEquals(2, paramType.entries.size)
    }

    // ========== Type Definitions ==========

    @Test
    fun `parse type definition with trailing comma`() {
        val code = """
            type User = {
              name: String,
              age: Int,
            }
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val typeDecl = program.items.first() as TypeDeclaration
        val recordDef = typeDecl.definition as RecordDefinition
        assertEquals(2, recordDef.fields.size)
    }

    @Test
    fun `parse newtype with trailing comma`() {
        val code = """newtype UserId = UserId {value: Int,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val typeDecl = program.items.first() as TypeDeclaration
        val newtypeDef = typeDecl.definition as NewtypeDefinition
        val recordType = newtypeDef.wrappedType as RecordTypeAnnotation
        assertEquals(1, recordType.entries.size)
    }

    @Test
    fun `parse type with single field and trailing comma`() {
        val code = """type Wrapper = {value: Int,}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val typeDecl = program.items.first() as TypeDeclaration
        val recordDef = typeDecl.definition as RecordDefinition
        assertEquals(1, recordDef.fields.size)
    }

    // ========== Mixed Tests ==========

    @Test
    fun `parse complex expression with trailing commas everywhere`() {
        val code = """
            let partition = {pred: {x: Int,} -> Bool, xs: [Int],} ->
                match xs is
                  [] -> {yes = [], no = [],}
                  [head, ...tail,] ->
                    {yes = [head,], no = tail,}
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
        // Just verify it parses without errors
    }

    @Test
    fun `parse record with array fields and trailing commas`() {
        val code = """let data = {items = [1, 2, 3,], tags = ["a", "b",],}"""

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse match with multiple patterns all having trailing commas`() {
        val code = """
            match point is
              {x, y,} -> "2D"
              {x, y, z,} -> "3D"
              _ -> "unknown"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        assertEquals(3, matchExpr.cases.size)

        val pattern1 = matchExpr.cases[0].patterns.first() as RecordPattern
        assertEquals(2, pattern1.entries.size)

        val pattern2 = matchExpr.cases[1].patterns.first() as RecordPattern
        assertEquals(3, pattern2.entries.size)
    }

    @Test
    fun `parse deeply nested structures with trailing commas`() {
        val code = """
            let nested = {
              outer = {
                inner = {
                  values = [1, 2, 3,],
                  more = [[4, 5,], [6,],],
                },
              },
            }
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
        // Just verify it parses without errors
    }
}
