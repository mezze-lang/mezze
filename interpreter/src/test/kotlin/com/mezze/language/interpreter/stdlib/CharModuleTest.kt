package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class CharModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return MezzeTestHelper.getLastExecutionContext()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // === Construction ===

    @Test
    fun `test char from_int - valid ASCII`() {
        val source = """
            use std::char
            let result = char::from_int { code = 65 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals('A', (payload.fields["val"] as MezzeChar).value)
    }

    @Test
    fun `test char from_int - invalid code point`() {
        val source = """
            use std::char
            let result = char::from_int { code = -1 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test char from_string - valid`() {
        val source = """
            use std::char
            let result = char::from_string { s = "Hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals('H', (payload.fields["val"] as MezzeChar).value)
    }

    @Test
    fun `test char from_string - empty string`() {
        val source = """
            use std::char
            let result = char::from_string { s = "" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === Conversion ===

    @Test
    fun `test char to_int`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::to_int { c = val }
                None -> -1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(65L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test char to_string`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::to_string { c = val }
                None -> "error"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("A", (variables["result"] as MezzeString).value)
    }

    // === Case Conversion ===

    @Test
    fun `test char to_upper`() {
        val source = """
            use std::char
            let c = char::from_int { code = 97 }
            let result = match c is
                Some { val } -> char::to_string { c = char::to_upper { c = val } }
                None -> "error"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("A", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test char to_lower`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::to_string { c = char::to_lower { c = val } }
                None -> "error"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("a", (variables["result"] as MezzeString).value)
    }

    // === Character Classification ===

    @Test
    fun `test char is_digit - digit`() {
        val source = """
            use std::char
            let c = char::from_int { code = 53 }
            let result = match c is
                Some { val } -> char::is_digit { c = val }
                None -> False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_digit - letter`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::is_digit { c = val }
                None -> True
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_letter`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::is_letter { c = val }
                None -> False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_alpha_numeric - letter`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_alpha_numeric - digit`() {
        val source = """
            use std::char
            let c = char::from_int { code = 53 }
            let result = match c is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_alpha_numeric - space`() {
        val source = """
            use std::char
            let c = char::from_int { code = 32 }
            let result = match c is
                Some { val } -> char::is_alpha_numeric { c = val }
                None -> True
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_whitespace`() {
        val source = """
            use std::char
            let c = char::from_int { code = 32 }
            let result = match c is
                Some { val } -> char::is_whitespace { c = val }
                None -> False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char is_upper`() {
        val source = """
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 97 }
            let result1 = match c1 is
                Some { val } -> char::is_upper { c = val }
                None -> False
            let result2 = match c2 is
                Some { val } -> char::is_upper { c = val }
                None -> True
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as MezzeBool).value)
        assertEquals(false, (variables["result2"] as MezzeBool).value)
    }

    @Test
    fun `test char is_lower`() {
        val source = """
            use std::char
            let c1 = char::from_int { code = 97 }
            let c2 = char::from_int { code = 65 }
            let result1 = match c1 is
                Some { val } -> char::is_lower { c = val }
                None -> False
            let result2 = match c2 is
                Some { val } -> char::is_lower { c = val }
                None -> True
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as MezzeBool).value)
        assertEquals(false, (variables["result2"] as MezzeBool).value)
    }

    // === Comparison ===

    @Test
    fun `test char eq - equal`() {
        val source = """
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 65 }
            let result = match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } -> char::eq { a = a, b = b }
                    None -> False
                None -> False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test char eq - not equal`() {
        val source = """
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 66 }
            let result = match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } -> char::eq { a = a, b = b }
                    None -> True
                None -> True
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    // === Compare ===

    @Test
    fun `test char compare - less than`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 66 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } ->
                        match char::compare { a = a, b = b } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(MezzeInt(1), result) // LT
    }

    @Test
    fun `test char compare - equal`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 65 }
            let c2 = char::from_int { code = 65 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } ->
                        match char::compare { a = a, b = b } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(MezzeInt(0), result) // EQ
    }

    @Test
    fun `test char compare - greater than`() {
        val result = eval("""
            use std::char
            let c1 = char::from_int { code = 66 }
            let c2 = char::from_int { code = 65 }
            match c1 is
                Some { val = a } -> match c2 is
                    Some { val = b } ->
                        match char::compare { a = a, b = b } is
                            LT -> 1
                            EQ -> 0
                            GT -> -1
                    None -> -2
                None -> -2
        """)
        assertEquals(MezzeInt(-1), result) // GT
    }

    // === Digit Operations ===

    @Test
    fun `test char digit_to_int - decimal digit`() {
        val source = """
            use std::char
            let c = char::from_int { code = 53 }
            let result = match c is
                Some { val } -> char::digit_to_int { c = val, radix = 10 }
                None -> None
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(5L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test char digit_to_int - hex digit`() {
        val source = """
            use std::char
            let c = char::from_int { code = 65 }
            let result = match c is
                Some { val } -> char::digit_to_int { c = val, radix = 16 }
                None -> None
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(10L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test char digit_to_int - invalid digit`() {
        val source = """
            use std::char
            let c = char::from_int { code = 71 }
            let result = match c is
                Some { val } -> char::digit_to_int { c = val, radix = 16 }
                None -> Some { val = -1 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }
}
