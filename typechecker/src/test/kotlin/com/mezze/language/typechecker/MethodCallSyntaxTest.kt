package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.LetBinding
import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Tests for method call syntax: `value.method { args }`.
 *
 * Method call syntax allows calling ability methods on values directly:
 *   42.show {}          → equivalent to Show.for { it = 42 }.show {}
 *   x.eq { r = y }     → equivalent to Eq.for { it = x }.eq { r = y }
 *
 * Resolution order:
 * 1. Field access (record fields)
 * 2. Anonymous impl methods (future)
 * 3. Ability impl methods
 */
class MethodCallSyntaxTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // BASIC METHOD CALL SYNTAX
    // ========================================================================

    @Test
    fun `simple method call on user-defined ability`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "int"
            }

            let x = 42
            let result = x.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    @Test
    fun `method call with additional arguments`() {
        val source = """
            ability Format for 'a where {
                format: {it: 'a, style: Str } -> Str
            }

            impl Format for Int where {
                format = {it, style } -> style
            }

            let x = 42
            let result = x.format { style = "decimal" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    @Test
    fun `method call on string literal`() {
        val source = """
            ability Length for 'a where {
                length: {it: 'a} -> Int
            }

            impl Length for Str where {
                length = {it} -> 0
            }

            let result = "hello".length {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.IntType, resultBinding!!.inferredType)
    }

    // ========================================================================
    // FIELD ACCESS TAKES PRIORITY OVER METHOD CALL
    // ========================================================================

    @Test
    fun `impl method name conflicting with target type field is compile error`() {
        // If a type has a field "onClick" and you try to impl a method "onClick" on it,
        // that's a compile-time error.
        val source = """
            type Button = { onClick: {} -> {} }

            ability Handler for 'a where {
                onClick: {it: 'a} -> Str
            }

            impl Handler for Button where {
                onClick = {it} -> "clicked"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        assertTrue(error.message!!.contains("conflict with fields"))
    }

    @Test
    fun `field access on record with same name as ability method is fine`() {
        // Having a record field that happens to share a name with an ability method
        // is fine — it's just field access, no impl is involved.
        val source = """
            let ops = { show = { x: Int } -> "field" }
            let result = ops.show { x = 1 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    // ========================================================================
    // TRADITIONAL ABILITY SYNTAX STILL WORKS
    // ========================================================================

    @Test
    fun `method call directly on literal`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "int"
            }

            let result = 42.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    // ========================================================================
    // METHOD CALL ON VARIOUS TYPES
    // ========================================================================

    @Test
    fun `method call on boolean value`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Bool where {
                show = {it} ->
                    match it is
                        True -> "true"
                        False -> "false"
            }

            let result = True.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    @Test
    fun `method call on variable`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "int"
            }

            let f = { x } ->
                x.show {}

            let result = f { x = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    // ========================================================================
    // ERROR CASES
    // ========================================================================

    @Test
    @org.junit.jupiter.api.Disabled("Ability impl resolution errors are deferred — not yet detected at type-check time")
    fun `method call on type without impl fails`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            # No impl for Float!
            let result = 3.14.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        // Should mention that Show is not implemented for Float
        assertNotNull(error.message)
    }

    // ========================================================================
    // `it` IMPLICIT RECEIVER IN IMPL BODIES
    // ========================================================================

    @Test
    fun `it is available in impl method body`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} ->
                    match it is
                        _ -> "works"
            }

            let result = 42.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    @Test
    fun `it has the correct type in impl method body`() {
        val source = """
            ability Double for 'a where {
                double: {it: 'a} -> 'a
            }

            impl Double for Int where {
                double = {it} -> it + it
            }

            let result = 21.double {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.IntType, resultBinding!!.inferredType)
    }

    // ========================================================================
    // Ability.for { it = value } SYNTAX
    // ========================================================================

    @Test
    fun `method call on user-defined Show with Int`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "int"
            }

            let result = 42.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    @Test
    fun `method call with built-in Eq`() {
        val source = """
            let result = 42.eq { r = 10 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val resultBinding = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }
        assertNotNull(resultBinding)
        assertEquals(MezzeType.BoolType, resultBinding!!.inferredType)
    }

    @Test
    fun `multi-method ability with method call syntax`() {
        val source = """
            ability Showable for 'a where {
                show: {it: 'a} -> Str,
                display: {it: 'a, verbose: Bool } -> Str
            }

            impl Showable for Int where {
                show = {it} -> "int",
                display = {it, verbose } -> "int"
            }

            let r1 = 42.show {}
            let r2 = 42.display { verbose = True }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
        val r1 = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "r1" }
        val r2 = result.program!!.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "r2" }
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(MezzeType.StringType, r1!!.inferredType)
        assertEquals(MezzeType.StringType, r2!!.inferredType)
    }
}
