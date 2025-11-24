package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for record field access validation.
 * Both closed and open records should only allow access to explicitly declared fields.
 */
class RecordFieldAccessTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    // ==================== CLOSED RECORD TESTS ====================

    @Test
    fun `closed record - access existing field should pass`() {
        val source = """
            let point = {x = 10, y = 20}
            let result = point.x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing existing field 'x' on closed record")
    }

    @Test
    fun `closed record - access multiple existing fields should pass`() {
        val source = """
            let point = {x = 10, y = 20}
            let sum = point.x + point.y
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing multiple existing fields on closed record")
    }

    @Test
    fun `closed record - access non-existing field should fail`() {
        val source = """
            let point = {x = 10, y = 20}
            let result = point.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: field 'z' does not exist on closed record") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'z' not found") == true, "Error should mention missing field")
    }

    @Test
    fun `closed record - access typo field should fail with suggestion`() {
        val source = """
            let math = {
                add = {x: Int, y: Int} -> x + y,
                subtract = {x: Int, y: Int} -> x - y
            }
            let result = math.substract {x = 1, y = 2}
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: 'substract' is a typo") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'substract' not found") == true, "Error should mention missing field")
    }

    @Test
    fun `closed record with type alias - access existing field should pass`() {
        val source = """
            type Point = {x: Int, y: Int}
            let p: Point = {x = 10, y = 20}
            let result = p.x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing existing field on typed closed record")
    }

    @Test
    fun `closed record with type alias - access non-existing field should fail`() {
        val source = """
            type Point = {x: Int, y: Int}
            let p: Point = {x = 10, y = 20}
            let result = p.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: field 'z' not in Point type") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'z' not found") == true, "Error should mention missing field")
    }

    @Test
    fun `closed record - access field with function value should pass`() {
        val source = """
            let math = {
                add = {x: Int, y: Int} -> x + y
            }
            let result = math.add {x = 1, y = 2}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing function field and calling it")
    }

    @Test
    fun `closed record - access non-existing function field should fail`() {
        val source = """
            let math = {
                add = {x: Int, y: Int} -> x + y
            }
            let result = math.multiply {x = 1, y = 2}
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: 'multiply' does not exist") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'multiply' not found") == true, "Error should mention missing field")
    }

    // ==================== OPEN RECORD TESTS ====================

    @Test
    fun `open record - access existing field should pass`() {
        val source = """
            let point = {x = 10, y = 20, ...}
            let result = point.x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing existing field 'x' on open record")
    }

    @Test
    fun `open record - access multiple existing fields should pass`() {
        val source = """
            let point = {x = 10, y = 20, ...}
            let sum = point.x + point.y
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing multiple existing fields on open record")
    }

    @Test
    fun `open record - access non-existing field should fail`() {
        val source = """
            let point = {x = 10, y = 20, ...}
            let result = point.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: field 'z' not explicitly declared in open record") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'z' not found") == true, "Error should mention missing field")
    }

    @Test
    fun `open record with type annotation - access existing field should pass`() {
        val source = """
            type OpenPoint = {x: Int, y: Int, ...}
            let p: OpenPoint = {x = 10, y = 20}
            let result = p.x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing existing field on typed open record")
    }

    @Test
    fun `open record with type annotation - access non-existing field should fail`() {
        val source = """
            type OpenPoint = {x: Int, y: Int, ...}
            let p: OpenPoint = {x = 10, y = 20}
            let result = p.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: field 'z' not in OpenPoint type") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'z' not found") == true, "Error should mention missing field")
    }

    @Test
    fun `open record in function param - access existing field should pass`() {
        val source = """
            let getX: {x: Int, ...} -> Int = {x, ...} -> x
            let result = getX {x = 42, y = 100}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: open record function can access declared fields")
    }

    @Test
    fun `open record in function - access non-existing field should fail`() {
        val source = """
            let getZ: {x: Int, ...} -> Int = {x, ...} -> z
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: 'z' is not declared in the open record parameter") {
            typeChecker.typeCheck(program)
        }
    }

    // ==================== NESTED RECORD TESTS ====================

    @Test
    fun `nested closed record - access existing nested field should pass`() {
        val source = """
            let data = {
                point = {x = 10, y = 20},
                name = "test"
            }
            let result = data.point.x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing nested field on closed records")
    }

    @Test
    fun `nested closed record - access non-existing nested field should fail`() {
        val source = """
            let data = {
                point = {x = 10, y = 20},
                name = "test"
            }
            let result = data.point.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: nested field 'z' does not exist") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'z' not found") == true, "Error should mention missing field")
    }

    @Test
    fun `nested open record - access existing nested field should pass`() {
        val source = """
            let data = {
                point = {x = 10, y = 20, ...},
                name = "test",
                ...
            }
            let result = data.point.x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Should pass: accessing nested field on open records")
    }

    @Test
    fun `nested open record - access non-existing nested field should fail`() {
        val source = """
            let data = {
                point = {x = 10, y = 20, ...},
                name = "test",
                ...
            }
            let result = data.point.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: nested field 'z' not declared in open record") {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("Field 'z' not found") == true, "Error should mention missing field")
    }

    // ==================== ERROR MESSAGE QUALITY TESTS ====================

    @Test
    fun `error message includes available fields`() {
        val source = """
            let point = {x = 10, y = 20}
            let result = point.z
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
        // The error context should include available fields
        assertTrue(error.context?.get("availableFields") != null, "Error should include available fields in context")
    }

    @Test
    fun `error message includes field name`() {
        val source = """
            let point = {x = 10, y = 20}
            let result = point.nonexistent
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.context?.get("field") == "nonexistent", "Error should include the attempted field name")
    }
}
