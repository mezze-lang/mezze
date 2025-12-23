package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull

/**
 * Tests for open record (row polymorphism) functionality.
 * Open records allow functions to accept records with extra fields beyond those explicitly declared.
 */
class OpenRecordTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test simple open record - accepts extra fields`() {
        val source = """
            let my_func: {x: Int, y: Int, ...} -> Int = {x, y} -> x + y

            my_func {x = 1, y = 0, z = ""}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: open records accept extra fields")
    }

    @Test
    fun `test nested open record - accepts extra fields in nested record`() {
        val source = """
            let my_func2: {a: {x: Int, y: Int, ...}, b: Int} -> Int = {a, b} -> a.x + a.y - b

            my_func2 {a = {x = 1, y = 0, z = ""}, b = 3}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: nested open records accept extra fields")
    }

    @Test
    fun `test open record with different extra field types - String`() {
        val source = """
            let processUser: {name: String, age: Int, ...} -> String = {name, age} -> name

            processUser {name = "Alice", age = 30, city = "NYC"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: extra fields can be any type")
    }

    @Test
    fun `test open record with different extra field types - Boolean`() {
        val source = """
            let processUser: {name: String, age: Int, ...} -> String = {name, age} -> name

            processUser {name = "Bob", age = 25, active = true}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: extra fields can be Boolean")
    }

    @Test
    fun `test open record with different extra field types - Float`() {
        val source = """
            let processUser: {name: String, age: Int, ...} -> String = {name, age} -> name

            processUser {name = "Charlie", age = 35, score = 95.5}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: extra fields can be Float")
    }

    @Test
    fun `test open record with multiple extra fields`() {
        val source = """
            let processUser: {name: String, ...} -> String = {name} -> name

            processUser {name = "Alice", age = 30, city = "NYC", active = true, score = 100.0}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: can have multiple extra fields of different types")
    }

    @Test
    fun `test open record function accepts exact match`() {
        val source = """
            let my_func: {x: Int, y: Int, ...} -> Int = {x, y} -> x + y

            my_func {x = 1, y = 0}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: open records accept exact match (no extra fields)")
    }

    @Test
    fun `test closed record function rejects extra fields`() {
        val source = """
            let my_func: {x: Int, y: Int} -> Int = {x, y} -> x + y

            my_func {x = 1, y = 0, z = ""}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: closed records cannot accept extra fields") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test open record with extra fields of mixed types`() {
        val source = """
            let my_func: {x: Int, ...} -> Int = {x} -> x

            my_func {x = 1, name = "test", active = true, score = 3.14}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: extra fields can be of different types")
    }

    @Test
    fun `test nested open and closed records mixed`() {
        val source = """
            let my_func: {a: {x: Int, y: Int, ...}, b: {p: String, q: String}} -> Int = {a, b} -> a.x + a.y

            my_func {a = {x = 1, y = 0, z = ""}, b = {p = "hello", q = "world"}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: nested record 'a' is open, accepts extra field 'z'")
    }

    @Test
    fun `test nested closed record in open record rejects extra fields`() {
        val source = """
            let my_func: {a: {x: Int, y: Int, ...}, b: {p: String, q: String}} -> Int = {a, b} -> a.x + a.y

            my_func {a = {x = 1, y = 0, z = ""}, b = {p = "hello", q = "world", r = true}}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: nested record 'b' is closed, cannot accept extra field 'r'") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test deeply nested open records`() {
        val source = """
            let my_func: {a: {b: {c: Int, ...}, ...}, ...} -> Int = {a} -> a.b.c

            my_func {a = {b = {c = 42, d = "extra"}, e = true}, f = 3.14}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: deeply nested open records all accept extra fields")
    }

    @Test
    fun `test open record with record extra field`() {
        val source = """
            let my_func: {x: Int, ...} -> Int = {x} -> x

            my_func {x = 1, nested = {a = "hello", b = 42}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: extra field can be a record")
    }

    @Test
    fun `test multiple open records in sequence`() {
        val source = """
            let func1: {x: Int, ...} -> Int = {x} -> x
            let func2: {y: String, ...} -> String = {y} -> y

            let result1 = func1 {x = 42, extra = "test"}
            let result2 = func2 {y = "hello", extra = 100}

            result1
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: multiple open record functions can be used independently")
    }

    @Test
    fun `test open record inferred from literal with extra fields`() {
        val source = """
            let openRec = {name = "test", value = 123, ...}

            openRec
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: record literals can be declared as open")
    }

    @Test
    fun `test open record pattern destructuring in lambda`() {
        val source = """
            let my_func = {x, y, ...} -> x + y

            my_func {x = 1, y = 0, z = ""}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: open record pattern in lambda accepts extra fields")
    }

    @Test
    fun `test closed record pattern destructuring in lambda rejects extra fields`() {
        val source = """
            let my_func = {x, y} -> x + y

            my_func {x = 1, y = 0, z = ""}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: closed record pattern cannot accept extra fields") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test open record pattern destructuring with single field`() {
        val source = """
            let my_func = {x, ...} -> x

            my_func {x = 1, y = 2, z = 3}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: single field open record pattern")
    }

    @Test
    fun `test nested record in open pattern - outer open accepts extra fields`() {
        val source = """
            let my_func = {a, b, ...} -> a.x + b

            my_func {a = {x = 10}, b = 5, c = "extra"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: outer pattern is open, accepts extra field 'c'")
    }
}
