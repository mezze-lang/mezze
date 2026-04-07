package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

/**
 * Comprehensive tests for variant merging behavior.
 *
 * Variant merging occurs when:
 * 1. Match/if-then-else branches return different variant constructors
 * 2. Effect associated types combine from different branches
 * 3. Function calls with different effect types are combined
 *
 * Key behaviors:
 * - Anonymous variants merge: if flag then Red else Blue -> | Red, Blue |
 * - Type aliases are preserved: type Color = | Red, Blue | -> returns Color not | Red, Blue |
 * - Effect error types merge: different Exception.throw calls combine their error variants
 */
class VariantMergingTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun getBindingType(result: TypeCheckResult, name: String): String {
        val inferredType = result.getVariableType(name)
        assertNotNull(inferredType, "Binding '$name' has no inferred type")
        return prettyPrintType(inferredType!!)
    }

    // ========================================================================
    // BASIC VARIANT MERGING (no type aliases)
    // ========================================================================

    @Test
    fun `anonymous variants merge in match expression`() {
        val source = """
            let x = {num} -> match num is
                1 -> Red
                _ -> Blue
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue"),
            "Type should contain both variants: $typeStr")
    }

    @Test
    fun `three anonymous variants merge in match expression`() {
        val source = """
            let x = {num} -> match num is
                1 -> Red
                2 -> Blue
                _ -> Green
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue") && typeStr.contains("Green"),
            "Type should contain all three variants: $typeStr")
    }

    @Test
    fun `anonymous variants with payloads merge correctly`() {
        val source = """
            let x = {num} -> match num is
                1 -> Success {val = 42}
                _ -> Failure {msg = "error"}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Success") && typeStr.contains("Failure"),
            "Type should contain both variants: $typeStr")
    }

    @Test
    fun `if-then-else merges anonymous variants`() {
        val source = """
            let x = {flag} -> if flag then Red else Blue
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue"),
            "Type should contain both variants: $typeStr")
    }

    @Test
    fun `nested if-then-else merges multiple anonymous variants`() {
        val source = """
            let x = {a, b} ->
                if a then Red
                else if b then Blue
                else Green
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue") && typeStr.contains("Green"),
            "Type should contain all three variants: $typeStr")
    }

    // ========================================================================
    // TYPE ALIAS BEHAVIOR
    // ========================================================================

    @Test
    fun `constructors from type alias all merge correctly`() {
        // When all constructors come from the same type alias (Color),
        // they merge together. The actual alias name display depends on
        // whether prettyPrintTypeWithAliases is used (CLI shows "Color",
        // prettyPrintType shows the expanded form).
        val source = """
            type Color = | Red, Blue, Green |

            let x = {num} -> match num is
                1 -> Red
                _ -> Blue
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        // The variants should all be present (merged from the type alias)
        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue") && typeStr.contains("Green"),
            "Type should contain all Color constructors: $typeStr")
    }

    @Test
    fun `all constructors from type alias merge`() {
        val source = """
            type Color = | Red, Blue, Green |

            let x = {num} -> match num is
                1 -> Red
                2 -> Blue
                _ -> Green
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue") && typeStr.contains("Green"),
            "Type should contain all Color constructors: $typeStr")
    }

    @Test
    fun `mixing type alias constructor with unknown constructor fails`() {
        // When using constructors from a type alias AND an anonymous constructor,
        // the type check should FAIL because you can't merge a declared type (Color)
        // with an anonymous variant (Pink).
        val source = """
            type Color = | Red, Blue, Green |

            let x = {num} -> match num is
                1 -> Red
                2 -> Blue
                _ -> Pink
        """.trimIndent()

        // This should fail - Red/Blue are from Color, Pink is anonymous
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `mixing anonymous constructors without type declaration merges correctly`() {
        // Without a type declaration, all constructors are anonymous and can merge
        val source = """
            let x = {num} -> match num is
                1 -> Red
                2 -> Blue
                _ -> Pink
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        // All constructors should be present in the merged anonymous variant
        assertTrue(typeStr.contains("Red") && typeStr.contains("Blue") && typeStr.contains("Pink"),
            "Type should contain Red, Blue, and Pink: $typeStr")
    }

    // ========================================================================
    // EFFECT ASSOCIATED TYPE MERGING
    // ========================================================================

    @Test
    fun `effect error types merge from different branches - direct throws`() {
        val source = """
            use std::effects::exception::{ Exception }

            let x = {flag} ->
                if flag then Exception.throw {error = MathError {code = "div"}}
                else Exception.throw {error = NetworkError {url = "http://example.com"}}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("MathError") && typeStr.contains("NetworkError"),
            "Type should contain both error types: $typeStr")
        assertTrue(typeStr.contains("Exception"),
            "Type should mention Exception effect: $typeStr")
    }

    @Test
    fun `effect error types merge from function calls`() {
        val source = """
            use std::effects::exception::{ Exception }

            let throw_math = {} -> Exception.throw {error = MathError {code = "div"}}
            let throw_network = {} -> Exception.throw {error = NetworkError {url = "http://x"}}

            let x = {flag} -> if flag then throw_math {} else throw_network {}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("MathError") && typeStr.contains("NetworkError"),
            "Type should contain both error types: $typeStr")
    }

    @Test
    fun `effect error types merge from mixed direct and function calls`() {
        val source = """
            use std::effects::exception::{ Exception }

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = MathError {code = "div"}}
                else a / b

            let x = {flag} ->
                if flag then safe_div {a = 10, b = 0}
                else Exception.throw {error = NetworkError {url = "http://x"}}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("MathError") && typeStr.contains("NetworkError"),
            "Type should contain both error types: $typeStr")
    }

    @Test
    fun `effect error types merge in match expression`() {
        val source = """
            use std::effects::exception::{ Exception }

            let x = {num} -> match num is
                1 -> Exception.throw {error = Error1 {a = 1}}
                2 -> Exception.throw {error = Error2 {b = "two"}}
                _ -> Exception.throw {error = Error3 {c = True}}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Error1") && typeStr.contains("Error2") && typeStr.contains("Error3"),
            "Type should contain all three error types: $typeStr")
    }

    @Test
    fun `three-way effect error merge from functions`() {
        val source = """
            use std::effects::exception::{ Exception }

            let f1 = {} -> Exception.throw {error = E1}
            let f2 = {} -> Exception.throw {error = E2}
            let f3 = {} -> Exception.throw {error = E3}

            let x = {n} -> match n is
                1 -> f1 {}
                2 -> f2 {}
                _ -> f3 {}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("E1") && typeStr.contains("E2") && typeStr.contains("E3"),
            "Type should contain all three error types: $typeStr")
    }

    // ========================================================================
    // SAME CONSTRUCTOR DIFFERENT VALUES
    // ========================================================================

    @Test
    fun `same constructor name with compatible payload types unifies`() {
        val source = """
            let x = {flag} ->
                if flag then Data {val = 42}
                else Data {val = 100}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("Data"),
            "Type should contain Data variant: $typeStr")
    }

    @Test
    fun `same error constructor merges without duplication`() {
        val source = """
            use std::effects::exception::{ Exception }

            let x = {flag} ->
                if flag then Exception.throw {error = AppError {code = 1}}
                else Exception.throw {error = AppError {code = 2}}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "x")

        assertTrue(typeStr.contains("AppError"),
            "Type should contain AppError: $typeStr")
        // AppError should appear once, not duplicated
        val count = typeStr.split("AppError").size - 1
        assertTrue(count == 1, "AppError should appear exactly once, found $count times: $typeStr")
    }

    // ========================================================================
    // NESTED VARIANTS
    // ========================================================================

    @Test
    fun `variant in variant payload merges correctly`() {
        val source = """
            let inner = {flag} -> if flag then InnerA else InnerB
            let outer = {flag} -> Outer {nested = inner {flag = flag}}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "outer")

        assertTrue(typeStr.contains("Outer") && typeStr.contains("InnerA") && typeStr.contains("InnerB"),
            "Type should contain Outer with nested InnerA/InnerB: $typeStr")
    }

    // ========================================================================
    // EFFECT WITH VALUE RETURN
    // ========================================================================

    @Test
    fun `effect branches with one success and one failure merge errors`() {
        val source = """
            use std::effects::exception::{ Exception }

            let safe_op = {n} ->
                if n > 0 then n * 2
                else Exception.throw {error = NegativeError {val = n}}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "safe_op")

        assertTrue(typeStr.contains("Int") && typeStr.contains("NegativeError") && typeStr.contains("Exception"),
            "Type should show Int return with NegativeError in Exception effect: $typeStr")
    }

    @Test
    fun `chained function calls accumulate error types`() {
        val source = """
            use std::effects::exception::{ Exception }

            let check_positive = {n} ->
                if n <= 0 then Exception.throw {error = NotPositive {val = n}}
                else n

            let check_even = {n} ->
                if n % 2 != 0 then Exception.throw {error = NotEven {val = n}}
                else n

            let validate = {n} -> do
                let pos = check_positive {n = n}
                check_even {n = pos}
        """.trimIndent()

        val result = parseAndTypeCheck(source)
        val typeStr = getBindingType(result, "validate")

        assertTrue(typeStr.contains("NotPositive") && typeStr.contains("NotEven"),
            "Type should contain both error types: $typeStr")
    }

    // ========================================================================
    // MATCH AND IF-ELSE CONSISTENCY
    // ========================================================================

    @Test
    fun `match and if-else produce same variant merging`() {
        val sourceMatch = """
            let via_match = {flag} -> match flag is
                True -> VariantA
                False -> VariantB
        """.trimIndent()

        val sourceIf = """
            let via_if = {flag} -> if flag then VariantA else VariantB
        """.trimIndent()

        val programMatch = parseAndTypeCheck(sourceMatch)
        val typeMatch = getBindingType(programMatch, "via_match")

        // Reset for second parse
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()

        val programIf = parseAndTypeCheck(sourceIf)
        val typeIf = getBindingType(programIf, "via_if")

        assertTrue(typeMatch.contains("VariantA") && typeMatch.contains("VariantB"),
            "Match type should have both variants: $typeMatch")
        assertTrue(typeIf.contains("VariantA") && typeIf.contains("VariantB"),
            "If type should have both variants: $typeIf")
    }
}
