package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class ResultModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return MezzeTestHelper.getLastExecutionContext()
    }

    // === map ===

    @Test
    fun `test result map - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let mapped = result::map { result = res, f = { val } -> val * 2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["mapped"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(10L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test result map - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let mapped = result::map { result = res, f = { val } -> val * 2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["mapped"] as MezzeVariant
        assertEquals("Err", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals("oops", (payload.fields["error"] as MezzeString).value)
    }

    // === map_err ===

    @Test
    fun `test result map_err - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let mapped = result::map_err { result = res, f = { error } -> "wrapped: " }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["mapped"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
    }

    @Test
    fun `test result map_err - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let mapped = result::map_err { result = res, f = { error } -> "wrapped" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["mapped"] as MezzeVariant
        assertEquals("Err", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals("wrapped", (payload.fields["error"] as MezzeString).value)
    }

    // === get_or_else ===

    @Test
    fun `test result get_or_else - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 42 }
            let value = result::get_or_else { result = res, def = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42L, (variables["value"] as MezzeInt).value)
    }

    @Test
    fun `test result get_or_else - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let value = result::get_or_else { result = res, def = 99 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(99L, (variables["value"] as MezzeInt).value)
    }

    // === is_ok / is_err ===

    @Test
    fun `test result is_ok - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 1 }
            let check = result::is_ok { result = res }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["check"] as MezzeBool).value)
    }

    @Test
    fun `test result is_ok - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let check = result::is_ok { result = res }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["check"] as MezzeBool).value)
    }

    @Test
    fun `test result is_err - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 1 }
            let check = result::is_err { result = res }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["check"] as MezzeBool).value)
    }

    @Test
    fun `test result is_err - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let check = result::is_err { result = res }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["check"] as MezzeBool).value)
    }

    // === flat_map ===

    @Test
    fun `test result flat_map - Ok to Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let chained = result::flat_map { result = res, f = { val } -> Ok { val = val * 2 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["chained"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(10L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test result flat_map - Ok to Err`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let chained: Result Int String = result::flat_map { result = res, f = { val } -> Err { error = "failed" } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["chained"] as MezzeVariant
        assertEquals("Err", variant.constructor)
    }

    @Test
    fun `test result flat_map - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "original" }
            let chained = result::flat_map { result = res, f = { val } -> Ok { val = val * 2 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["chained"] as MezzeVariant
        assertEquals("Err", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals("original", (payload.fields["error"] as MezzeString).value)
    }

    // === or_else ===

    @Test
    fun `test result or_else - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let recovered = result::or_else { result = res, f = { error } -> Ok { val = 0 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["recovered"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(5L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test result or_else - Err recovered`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let recovered = result::or_else { result = res, f = { error } -> Ok { val = 99 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["recovered"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(99L, (payload.fields["val"] as MezzeInt).value)
    }

    // === to_option ===

    @Test
    fun `test result to_option - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 42 }
            let opt = result::to_option { result = res }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["opt"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(42L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test result to_option - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let opt = result::to_option { result = res }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["opt"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === from_option ===

    @Test
    fun `test result from_option - Some`() {
        val source = """
            use std::result
            let opt = Some { val = 42 }
            let res = result::from_option { opt = opt, error = "was none" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["res"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(42L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test result from_option - None`() {
        val source = """
            use std::result
            let opt: Option Int = None
            let res = result::from_option { opt = opt, error = "was none" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["res"] as MezzeVariant
        assertEquals("Err", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals("was none", (payload.fields["error"] as MezzeString).value)
    }

    // === map2 ===

    @Test
    fun `test result map2 - both Ok`() {
        val source = """
            use std::result
            let a: Result Int String = Ok { val = 3 }
            let b: Result Int String = Ok { val = 4 }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["combined"] as MezzeVariant
        assertEquals("Ok", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(7L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test result map2 - first Err`() {
        val source = """
            use std::result
            let a: Result Int String = Err { error = "first failed" }
            let b: Result Int String = Ok { val = 4 }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["combined"] as MezzeVariant
        assertEquals("Err", variant.constructor)
    }

    @Test
    fun `test result map2 - second Err`() {
        val source = """
            use std::result
            let a: Result Int String = Ok { val = 3 }
            let b: Result Int String = Err { error = "second failed" }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["combined"] as MezzeVariant
        assertEquals("Err", variant.constructor)
    }

    // === fold ===

    @Test
    fun `test result fold - Ok`() {
        val source = """
            use std::result
            let res: Result Int String = Ok { val = 5 }
            let folded = result::fold {
                result = res,
                on_err = { error } -> "error",
                on_ok = { val } -> "success"
            }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("success", (variables["folded"] as MezzeString).value)
    }

    @Test
    fun `test result fold - Err`() {
        val source = """
            use std::result
            let res: Result Int String = Err { error = "oops" }
            let folded = result::fold {
                result = res,
                on_err = { error } -> "error",
                on_ok = { val } -> "success"
            }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("error", (variables["folded"] as MezzeString).value)
    }
}
