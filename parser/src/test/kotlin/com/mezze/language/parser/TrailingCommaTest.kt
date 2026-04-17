package com.mezze.language.parser

import com.mezze.language.parsedast.*
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val array = letBinding.value as ParsedSeqLit
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val array = letBinding.value as ParsedSeqLit
        assertEquals(3, array.elements.size)
    }

    @Test
    fun `parse array literal without trailing comma still works`() {
        val code = "let nums = [1, 2, 3]"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val array = letBinding.value as ParsedSeqLit
        assertEquals(3, array.elements.size)
    }

    @Test
    fun `parse nested array literals with trailing commas`() {
        val code = "let matrix = [[1, 2,], [3, 4,],]"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val array = letBinding.value as ParsedSeqLit
        assertEquals(2, array.elements.size)
    }

    @Test
    fun `parse single element array with trailing comma`() {
        val code = "let singleton = [42,]"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val array = letBinding.value as ParsedSeqLit
        assertEquals(1, array.elements.size)
    }

    // ========== Record Literals ==========

    @Test
    fun `parse record literal with trailing comma - single line`() {
        val code = """let user = {name = "Alice", age = 30,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse record literal without trailing comma still works`() {
        val code = """let user = {name = "Alice", age = 30}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse nested record literals with trailing commas`() {
        val code = """let config = {server = {host = "localhost", port = 8080,}, debug = true,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse single field record with trailing comma`() {
        val code = """let wrapper = {value = 42,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
        assertEquals(3, pattern.elements.size)
    }

    @Test
    fun `parse array pattern with rest and trailing comma`() {
        val code = """
            match xs is
              [head, ...tail,] -> head
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern
        assertEquals(2, pattern.entries.size)
    }

    @Test
    fun `parse record pattern with explicit binding and trailing comma`() {
        val code = """
            match user is
              {name = n, age = a,} -> n
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern
        assertEquals(2, pattern.entries.size)
    }

    @Test
    fun `parse single field record pattern with trailing comma`() {
        val code = """
            match user is
              {name,} -> name
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val pattern = matchExpr.cases[0].patterns.first() as ParsedRecordPattern
        assertEquals(1, pattern.entries.size)
    }

    // ========== Variant Constructors ==========

    @Test
    fun `parse variant constructor with trailing comma in payload`() {
        val code = """let result = Some {value = 42,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val constructor = letBinding.value as ParsedVariantConstruct
        assertTrue(constructor.value is ParsedRecordLit)
        val payload = constructor.value as ParsedRecordLit
        assertEquals(1, payload.entries.size)
    }

    @Test
    fun `parse variant constructor with multiple fields and trailing comma`() {
        val code = """let person = User {name = "Alice", age = 30,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val constructor = letBinding.value as ParsedVariantConstruct
        assertTrue(constructor.value is ParsedRecordLit)
        val payload = constructor.value as ParsedRecordLit
        assertEquals(2, payload.entries.size)
    }

    // ========== Lambda Parameters ==========

    @Test
    fun `parse lambda with trailing comma in untyped params`() {
        val code = """let add = {x, y, z,} -> x + y + z"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val lambda = letBinding.value as ParsedLambda
        assertTrue(lambda.params is ParsedLambdaParams.Untyped)
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(3, params.names.size)
    }

    @Test
    fun `parse lambda with trailing comma in typed params`() {
        val code = """let add: {x: Int, y: Int, z: Int,} -> Int = {x, y, z} -> x + y + z"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        // Type annotation should have trailing comma parsed correctly
        val funcType = letBinding.typeAnnotation as ParsedFunctionType
        val paramType = funcType.paramType as ParsedRecordType
        assertEquals(3, paramType.entries.size)
    }

    @Test
    fun `parse single param lambda with trailing comma`() {
        val code = """let identity = {x,} -> x"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val lambda = letBinding.value as ParsedLambda
        assertTrue(lambda.params is ParsedLambdaParams.Untyped)
        val params = lambda.params as ParsedLambdaParams.Untyped
        assertEquals(1, params.names.size)
    }

    // ========== Function Type Parameters ==========

    @Test
    fun `parse function type with trailing comma in params`() {
        val code = """let process: {x: Int, y: Int,} -> Int = {x, y} -> x + y"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val funcType = letBinding.typeAnnotation as ParsedFunctionType
        val paramType = funcType.paramType as ParsedRecordType
        assertEquals(2, paramType.entries.size)
    }

    @Test
    fun `parse nested function type with trailing commas`() {
        val code = """let apply: {f: {x: Int,} -> Int, value: Int,} -> Int = {f, value} -> f {x = value}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val funcType = letBinding.typeAnnotation as ParsedFunctionType
        val paramType = funcType.paramType as ParsedRecordType
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val typeDecl = module.decls.first() as ParsedTypeDecl
        val recordDef = typeDecl.definition as ParsedRecordDef
        assertEquals(2, recordDef.fields.size)
    }

    @Test
    fun `parse newtype with trailing comma`() {
        val code = """newtype UserId = UserId {value: Int,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val typeDecl = module.decls.first() as ParsedTypeDecl
        val newtypeDef = typeDecl.definition as ParsedNewtypeDef
        val recordType = newtypeDef.wrappedType as ParsedRecordType
        assertEquals(1, recordType.entries.size)
    }

    @Test
    fun `parse type with single field and trailing comma`() {
        val code = """type Wrapper = {value: Int,}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val typeDecl = module.decls.first() as ParsedTypeDecl
        val recordDef = typeDecl.definition as ParsedRecordDef
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
        // Just verify it parses without errors
    }

    @Test
    fun `parse record with array fields and trailing commas`() {
        val code = """let data = {items = [1, 2, 3,], tags = ["a", "b",],}"""

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        assertEquals(3, matchExpr.cases.size)

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedRecordPattern
        assertEquals(2, pattern1.entries.size)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedRecordPattern
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
        // Just verify it parses without errors
    }
}
