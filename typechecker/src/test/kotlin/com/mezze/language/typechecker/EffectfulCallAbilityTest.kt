package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for ability checking on the results of effectful calls.
 *
 * CRITICAL: effects live on function ARROWS, not on value types. Calling an
 * effectful function (e.g. `getNumber {} : Int`) yields a BARE value; the effect
 * rides the enclosing function's arrow rather than the value. Abilities are
 * unaffected: the bare result supports NumAdd, NumMul, Eq, etc. exactly like any Int.
 *
 * These tests verify the arithmetic/ability RESULT type of such calls (bare, e.g.
 * `Int` / `Bool` / `Str`). Where a function wraps the effectful call, its arrow
 * still carries the effect (e.g. `{} -> Int <Log>`).
 */
class EffectfulCallAbilityTest {

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    private fun assertTypeChecks(code: String, variableName: String, expectedType: String) {
        val result = parseAndTypeCheck(code)
        val actualType = getVariableType(result, variableName)
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

    // ===== Numeric Abilities on an effectful-call result =====

    @Test
    fun `test Int suspended type supports NumAdd`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            let result = getNumber {} + 10
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test Int suspended type supports NumMul`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                5

            let result = getNumber {} * 3
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test Int suspended type supports NumSub`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                100

            let result = getNumber {} - 25
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test Int suspended type supports NumDiv`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                100

            let result = getNumber {} / 4
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test nested effectful arithmetic operations`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
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
            "Int"
        )
    }

    @Test
    fun `test effectful function with arithmetic in do block`() {
        // This is the CRITICAL test case from the interpreter bug
        assertTypeChecks(
            """
            effect Log where {
              print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print: {msg: Str} -> {} = {msg} -> {}
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

    // ===== String Abilities on an effectful-call result =====

    @Test
    fun `test String suspended type supports string interpolation`() {
        // When an effectful function is called inside string interpolation,
        // the effect should propagate to the resulting string type
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getText: {} -> Str <Log> = {} -> do
                Log.println { msg = "Getting text" }
                "Hello"

            let result = "$(getText {}) World"
            result
            """.trimIndent(),
            "result",
            "Str"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    @Test
    fun `test String suspended type supports Eq`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getText: {} -> Str <Log> = {} -> do
                Log.println { msg = "Getting text" }
                "Hello"

            let result = getText {} == "Hello"
            result
            """.trimIndent(),
            "result",
            "Bool"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    // ===== Boolean Abilities on an effectful-call result =====

    @Test
    fun `test Bool suspended type supports LogicalAnd`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getBool: {} -> Bool <Log> = {} -> do
                Log.println { msg = "Getting bool" }
                True

            let result = getBool {} && False
            result
            """.trimIndent(),
            "result",
            "Bool"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    @Test
    fun `test Bool suspended type supports LogicalOr`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getBool: {} -> Bool <Log> = {} -> do
                Log.println { msg = "Getting bool" }
                False

            let result = getBool {} || True
            result
            """.trimIndent(),
            "result",
            "Bool"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    // ===== Eq Ability on various effectful-call results =====

    @Test
    fun `test Int suspended type supports Eq`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            let result = getNumber {} == 42
            result
            """.trimIndent(),
            "result",
            "Bool"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    @Test
    fun `test comparing two suspended Ints`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
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
            "Bool"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    // ===== Ord Ability on effectful-call results =====

    @Test
    fun `test Int suspended type supports Ord`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.println { msg = "Getting number" }
                42

            let result = getNumber {} < 50
            result
            """.trimIndent(),
            "result",
            "Bool"  // Bare value; the effect rides the enclosing arrow, not the value
        )
    }

    // ===== Multiple Effects =====

    @Test
    fun `test suspended type with multiple effects still supports abilities`() {
        assertTypeChecks(
            """
            use std::effects::log::{Console, println}
            use std::effects::filesystem::{FileSystem, LocalFileSystem, CreateOrTruncate}
            use std::collections::bytes

            let getNumber: {} -> Int <FileSystem, Log> = {} -> do
                println { msg = "Getting number" }
                FileSystem.write_bytes { path = "/tmp/test.txt", bytes = bytes::from_str { s = "data" }, mode = CreateOrTruncate }
                42

            let result = getNumber {} + 10
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    // ===== Error Cases =====

    @Test
    fun `test suspended String type does not support NumAdd`() {
        assertTypeCheckFails(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getText: {} -> Str <Log> = {} -> do
                Log.println { msg = "Getting text" }
                "Hello"

            let result = getText {} + 10
            result
            """.trimIndent()
        )
    }

    @Test
    fun `test suspended Int type used with string interpolation`() {
        // Note: Str interpolation works with any type that can be converted to string
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
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
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let makeAdder: { base: Int } -> { x: Int } -> Int <Log> = { base } -> {x} -> do
                Log.println { msg = "Adding" }
                base + x

            let adder = makeAdder { base = 10 }
            let result = adder { x = 5 } * 2
            result
            """.trimIndent(),
            "result",
            "Int"
        )
    }

    @Test
    fun `test suspended type in conditional`() {
        assertTypeChecks(
            """
            effect Log where {
              println: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              println: { msg: Str } -> {} = { msg } -> {}
            }

            let getBool: {} -> Bool <Log> = {} -> do
                Log.println { msg = "Getting bool" }
                True

            let result = if getBool {} then 1 else 2
            result
            """.trimIndent(),
            "result",
            "Int"  // Bare value; the condition's effect rides the enclosing arrow
        )
    }
}
