package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class OptionModuleTest {

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
    fun `test option map - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result = option::map { opt = opt, f = { val } -> val * 2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(10L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test option map - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::map { opt = opt, f = { val } -> val * 2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === get_or_else ===

    @Test
    fun `test option get_or_else - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 42 }
            let result = option::get_or_else { opt = opt, def = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test option get_or_else - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::get_or_else { opt = opt, def = 99 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(99L, (variables["result"] as MezzeInt).value)
    }

    // === is_some / is_none ===

    @Test
    fun `test option is_some - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 1 }
            let result = option::is_some { opt = opt }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test option is_some - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::is_some { opt = opt }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test option is_none - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 1 }
            let result = option::is_none { opt = opt }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test option is_none - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::is_none { opt = opt }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    // === filter ===

    @Test
    fun `test option filter - Some passes predicate`() {
        val source = """
            use std::option
            let opt = Some { val = 10 }
            let result = option::filter { opt = opt, p = { val } -> val > 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
    }

    @Test
    fun `test option filter - Some fails predicate`() {
        val source = """
            use std::option
            let opt = Some { val = 3 }
            let result = option::filter { opt = opt, p = { val } -> val > 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test option filter - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::filter { opt = opt, p = { val } -> val > 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === flat_map ===

    @Test
    fun `test option flat_map - Some to Some`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result = option::flat_map { opt = opt, f = { val } -> Some { val = val * 2 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(10L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test option flat_map - Some to None`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result: Option Int = option::flat_map { opt = opt, f = { val } -> None }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test option flat_map - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::flat_map { opt = opt, f = { val } -> Some { val = val * 2 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === or_else ===

    @Test
    fun `test option or_else - first is Some`() {
        val source = """
            use std::option
            let opt1 = Some { val = 1 }
            let opt2 = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(1L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test option or_else - first is None`() {
        val source = """
            use std::option
            let opt1: Option Int = None
            let opt2 = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(2L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test option or_else - both None`() {
        val source = """
            use std::option
            let opt1: Option Int = None
            let opt2: Option Int = None
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === map2 ===

    @Test
    fun `test option map2 - both Some`() {
        val source = """
            use std::option
            let opt_a = Some { val = 3 }
            let opt_b = Some { val = 4 }
            let result = option::map2 { opt_a = opt_a, opt_b = opt_b, f = { a, b } -> a + b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(7L, (payload.fields["val"] as MezzeInt).value)
    }

    @Test
    fun `test option map2 - first None`() {
        val source = """
            use std::option
            let opt_a: Option Int = None
            let opt_b = Some { val = 4 }
            let result = option::map2 { opt_a = opt_a, opt_b = opt_b, f = { a, b } -> a + b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test option map2 - second None`() {
        val source = """
            use std::option
            let opt_a = Some { val = 3 }
            let opt_b: Option Int = None
            let result = option::map2 { opt_a = opt_a, opt_b = opt_b, f = { a, b } -> a + b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    // === fold ===

    @Test
    fun `test option fold - Some`() {
        val source = """
            use std::option
            let opt = Some { val = 5 }
            let result = option::fold {
                opt = opt,
                on_none = {} -> "empty",
                on_some = { val } -> "value"
            }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("value", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test option fold - None`() {
        val source = """
            use std::option
            let opt: Option Int = None
            let result = option::fold {
                opt = opt,
                on_none = {} -> "empty",
                on_some = { val } -> "value"
            }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("empty", (variables["result"] as MezzeString).value)
    }
}
