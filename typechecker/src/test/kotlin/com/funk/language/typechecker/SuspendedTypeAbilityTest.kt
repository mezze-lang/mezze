package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for ability checking on SuspendedType values.
 *
 * CRITICAL: SuspendedType (e.g., Int <Log>) should have the same abilities as its value type.
 * Effects don't affect what operations can be performed on a value - they only track
 * which effects need to be handled before the value can be fully computed.
 *
 * Example: Int <Log> should support NumAdd, NumMul, Eq, etc. just like Int
 */
class SuspendedTypeAbilityTest {

    private fun parseAndTypeCheck(code: String): Program {
        val parser = FunkParser()
        val ast = parser.parse(code)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(ast)
    }

    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val program = parseAndTypeCheck(code)
        val actualType = getVariableType(program, variableName)
        assertNotNull(actualType, "Variable '$variableName' not found")
        assertEquals(expectedType, prettyPrintType(actualType))
    }

    private fun assertTypeChecks(code: String) {
        parseAndTypeCheck(code)
    }

    private fun assertTypeCheckFails(code: String) {
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // ===== Numeric Abilities on SuspendedType =====

    @Test
    fun `test Int suspended type supports NumAdd`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            let result = getNumber {} + 10
            result
            """.trimIndent(),
            "result",
            "Int <Log>"
        )
    }

    @Test
    fun `test Int suspended type supports NumMul`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                5

            let result = getNumber {} * 3
            result
            """.trimIndent(),
            "result",
            "Int <Log>"
        )
    }

    @Test
    fun `test Int suspended type supports NumSub`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                100

            let result = getNumber {} - 25
            result
            """.trimIndent(),
            "result",
            "Int <Log>"
        )
    }

    @Test
    fun `test Int suspended type supports NumDiv`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                100

            let result = getNumber {} / 4
            result
            """.trimIndent(),
            "result",
            "Int <Log>"
        )
    }

    @Test
    fun `test nested effectful arithmetic operations`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getX: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting X" }
                10

            let getY: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting Y" }
                5

            let result = (getX {} * 2) + (getY {} * 3)
            result
            """.trimIndent(),
            "result",
            "Int <Log>"
        )
    }

    @Test
    fun `test effectful function with arithmetic in do block`() {
        // This is the CRITICAL test case from the interpreter bug
        assertTypeChecks(
            """
            effect Log {
              print: {msg: String} -> {}
            }

            impl Log for Console where {
              print: {msg: String} -> {} = {msg} -> {}
            }

            let innerFunc: {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "inner"}
              x * 2

            let outerFunc: {y: Int} -> Int <Log> = {y} -> do
              let result = innerFunc {x = y}
              result + 1

            outerFunc
            """.trimIndent(),
            "outerFunc",
            "{ y: Int } -> Int <Log>"
        )
    }

    // ===== String Abilities on SuspendedType =====

    @Test
    fun `test String suspended type supports string interpolation`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getText: {} -> String <Log> = {} -> do
                Log.println { msg = "Getting text" }
                "Hello"

            let result = "$(getText {}) World"
            result
            """.trimIndent(),
            "result",
            "String"
        )
    }

    @Test
    fun `test String suspended type supports Eq`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getText: {} -> String <Log> = {} -> do
                Log.println { msg = "Getting text" }
                "Hello"

            let result = getText {} == "Hello"
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    // ===== Boolean Abilities on SuspendedType =====

    @Test
    fun `test Bool suspended type supports LogicalAnd`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getBool: {} -> Bool <Log> = {} -> do
                Log.println { msg = "Getting bool" }
                true

            let result = getBool {} && false
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    @Test
    fun `test Bool suspended type supports LogicalOr`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getBool: {} -> Bool <Log> = {} -> do
                Log.println { msg = "Getting bool" }
                false

            let result = getBool {} || true
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    // ===== Eq Ability on Various SuspendedTypes =====

    @Test
    fun `test Int suspended type supports Eq`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            let result = getNumber {} == 42
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    @Test
    fun `test comparing two suspended Ints`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getX: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting X" }
                10

            let getY: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting Y" }
                10

            let result = getX {} == getY {}
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    // ===== Ord Ability on SuspendedTypes =====

    @Test
    fun `test Int suspended type supports Ord`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            let result = getNumber {} < 50
            result
            """.trimIndent(),
            "result",
            "Bool"
        )
    }

    // ===== Multiple Effects =====

    @Test
    fun `test suspended type with multiple effects still supports abilities`() {
        assertTypeChecks(
            """
            use std::effects::log::{Log, Console}
            use std::effects::filesystem::{FileSystem, LocalDisk}

            let getNumber: {} -> Int <FileSystem, Log> = {} -> do
                Log.println { msg = "Getting number" }
                FileSystem.write_file { path = "/tmp/test.txt", content = "data" }
                42

            let result = getNumber {} + 10
            result
            """.trimIndent(),
            "result",
            "Int <FileSystem, Log>"
        )
    }

    // ===== Error Cases =====

    @Test
    fun `test suspended String type does not support NumAdd`() {
        assertTypeCheckFails(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getText: {} -> String <Log> = {} -> do
                Log.println { msg = "Getting text" }
                "Hello"

            let result = getText {} + 10
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test suspended Int type used with string interpolation`() {
        // Note: String interpolation works with any type that can be converted to string
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            # String interpolation converts values to strings
            getNumber
            """.trimIndent(),
            "getNumber",
            "{} -> Int <Log>"
        )
    }

    // ===== Complex Scenarios =====

    @Test
    fun `test nested suspended types with arithmetic`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let makeAdder: { base: Int } -> { x: Int } -> Int <Log> = { base } -> {x} -> do
                Log.println { msg = "Adding" }
                base + x

            let adder = makeAdder { base = 10 }
            let result = adder { x = 5 } * 2
            result
            """.trimIndent(),
            "result",
            "Int <Log>"
        )
    }

    @Test
    fun `test suspended type in conditional`() {
        assertTypeChecks(
            """
            effect Log {
              println: { msg: String } -> {}
            }

            impl Log for Console where {
              println: { msg: String } -> {} = { msg } -> {}
            }

            let getBool: {} -> Bool <Log> = {} -> do
                Log.println { msg = "Getting bool" }
                true

            let result = if getBool {} then 1 else 2
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }
}
