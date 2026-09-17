package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for ability constraint ANNOTATIONS in function type signatures.
 *
 * This tests the syntax: with Container 'c having 'item = Int
 * in function type annotations, verifying that the 'having' clause is optional
 * and type inference works correctly when it's omitted.
 */
class AbilityConstraintAnnotationsTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ========================================================================
    // TESTS FOR OPTIONAL having CLAUSE IN ABILITY CONSTRAINTS
    // ========================================================================

    @Test
    fun `test ability constraint with having clause specifies associated type`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {it: 'c, index: Int} -> (Option 'item),
                size: {it: 'c} -> Int,
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {it, index} -> Some { val = 0 },
                size = {it} -> 0,
            }

            let getFirst: {c: 'c} with Container 'c having 'item = Int -> (Option Int) = {c} ->
                c.fetch {index = 0}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getFirst") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.TypeApplication)
        val returnTypeApp = funcType.returnType as MezzeType.TypeApplication
        assertEquals("Option", returnTypeApp.constructor)
    }

    @Test
    fun `test ability constraint without having clause infers associated type`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {it: 'c, index: Int} -> (Option 'item),
                size: {it: 'c} -> Int,
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {it, index} -> Some { val = 0 },
                size = {it} -> 0,
            }

            let getFirst: {c: 'c} with Container 'c -> (Option 'item) = {c} ->
                c.fetch {index = 0}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        assertNotNull(program.getVariableType("getFirst"))
    }

    @Test
    fun `test ability constraint without having clause works with concrete usage`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {it: 'c, index: Int} -> (Option 'item),
                size: {it: 'c} -> Int,
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {it, index} -> Some { val = 0 },
                size = {it} -> 0,
            }

            let arr: [Int] = [1, 2, 3]
            let result = arr.fetch {index = 0}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val resultType = program.getVariableType("result")
        assertTrue(resultType is MezzeType.TypeApplication)
        val typeApp = resultType as MezzeType.TypeApplication
        assertEquals("Option", typeApp.constructor)
        assertEquals(MezzeType.IntType, typeApp.args[0])
    }

    @Test
    fun `test multiple ability constraints one with having one without`() {
        val code = """
            ability Container for 'c having 'item where {
                size: {it: 'c} -> Int,
            }

            ability Show for 'a where {
                show: {it: 'a} -> Str,
            }

            impl Show for Int where {
                show = {it} -> "int",
            }

            impl Container for [Int] having 'item = Int where {
                size = {it} -> 0,
            }

            let showAndSize: {c: 'c, v: 'v} with Container 'c having 'item = Int, Show 'v -> Int = {c, v} ->
                do
                    let s = v.show {}
                    c.size {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ability constraint without having clause multiple associated types`() {
        val code = """
            ability BiContainer for 'c having 'key, 'value where {
                getKey: {it: 'c} -> 'key,
                getValue: {it: 'c} -> 'value,
            }

            impl BiContainer for {k: Int, v: Str} having 'key = Int, 'value = Str where {
                getKey = {it} -> it.k,
                getValue = {it} -> it.v,
            }

            let getBoth: {c: 'c} with BiContainer 'c -> {key: 'key, value: 'value} = {c} ->
                {key = c.getKey {}, value = c.getValue {}}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ability constraint with partial having clause specifies one associated type`() {
        val code = """
            ability BiContainer for 'c having 'key, 'value where {
                getKey: {it: 'c} -> 'key,
                getValue: {it: 'c} -> 'value,
            }

            impl BiContainer for {k: Int, v: Str} having 'key = Int, 'value = Str where {
                getKey = {it} -> it.k,
                getValue = {it} -> it.v,
            }

            let getWithKnownKey: {c: 'c} with BiContainer 'c having 'key = Int -> Int = {c} ->
                c.getKey {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getWithKnownKey") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test simple ability without associated types works without having`() {
        val code = """
            ability Same for 'a where {
                same: {it: 'a, r: 'a} -> Bool,
            }

            impl Same for Int where {
                same = {it, r} -> True,
            }

            let areEqual: {a: 'a, b: 'a} with Same 'a -> Bool = {a, b} ->
                a.same {r = b}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("areEqual") as MezzeType.FunctionType
        val isBoolType = funcType.returnType is MezzeType.BoolType ||
            (funcType.returnType is MezzeType.TypeApplication &&
             (funcType.returnType as MezzeType.TypeApplication).constructor == "Bool") ||
            (funcType.returnType is MezzeType.VariantType &&
             (funcType.returnType as MezzeType.VariantType).constructors.containsKey("True") &&
             (funcType.returnType as MezzeType.VariantType).constructors.containsKey("False"))
        assertTrue(isBoolType, "Expected Bool type but got ${funcType.returnType}")
    }

    @Test
    fun `test chained ability constraints without having clauses`() {
        val code = """
            ability Same for 'a where {
                same: {it: 'a, r: 'a} -> Bool,
            }

            ability Show for 'a where {
                show: {it: 'a} -> Str,
            }

            impl Same for Int where {
                same = {it, r} -> True,
            }

            impl Show for Int where {
                show = {it} -> "int",
            }

            let checkAndShow: {a: 'a, b: 'a} with Same 'a, Show 'a -> Str = {a, b} ->
                do
                    let isEqual = a.same {r = b}
                    a.show {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("checkAndShow") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test ability constraint inference propagates through function calls`() {
        val code = """
            ability Container for 'c having 'item where {
                append: {it: 'c, element: 'item} -> 'c,
            }

            impl Container for [Int] having 'item = Int where {
                append = {it, element} -> it,
            }

            let addItem: {c: 'c, item: 'item} with Container 'c -> 'c = {c, item} ->
                c.append {element = item}

            let arr: [Int] = [1, 2, 3]
            let result = addItem {c = arr, item = 42}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val resultType = program.getVariableType("result")
        assertTrue(resultType is MezzeType.SeqType)
        assertEquals(MezzeType.IntType, (resultType as MezzeType.SeqType).elementType)
    }

    @Test
    fun `test ability constraint with complex associated type without having`() {
        val code = """
            ability Container for 'c having 'item where {
                getAll: {it: 'c} -> ['item],
            }

            impl Container for {items: [Str]} having 'item = Str where {
                getAll = {it} -> it.items,
            }

            let getAllItems: {c: 'c} with Container 'c -> ['item] = {c} ->
                c.getAll {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    // ========================================================================
    // TYPE MISMATCH TESTS - annotated vs inferred ability associated types
    // ========================================================================

    @Test
    fun `test mismatch between return type and ability associated type annotation fails`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {index: Int} -> (Option 'item),
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {index} -> Some { val = 0 },
            }

            let getFirst: {c: 'c} with Container 'c having 'item = Int -> (Option Str) = {c} ->
                c.fetch {index = 0}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test correct ability associated type annotation matches inferred type`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {index: Int} -> (Option 'item),
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {index} -> Some { val = 0 },
            }

            let getFirst: {c: 'c} with Container 'c having 'item = Int -> (Option Int) = {c} ->
                c.fetch {index = 0}
        """.trimIndent()

        assertDoesNotThrow {
            typeCheck(code)
        }
    }

    // ========================================================================
    // ERROR MESSAGE TESTS - verify helpful error messages for associated types
    // ========================================================================

    @Test
    fun `test error message mentions types on ability associated type mismatch`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {index: Int} -> (Option 'item),
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {index} -> Some { val = 0 },
            }

            let getFirst: {c: 'c} with Container 'c having 'item = Int -> (Option Str) = {c} ->
                c.fetch {index = 0}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        // Error message should mention the type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("Str"),
            "Error message should mention the conflicting types: $message"
        )
    }

    @Test
    fun `test error message for complex ability associated type mismatch`() {
        val code = """
            ability Container for 'c having 'item where {
                fetch: {it: 'c, index: Int} -> 'item,
            }

            impl Container for [Int] having 'item = Int where {
                fetch = {it, index} -> 0,
            }

            let getFirst: {c: 'c} with Container 'c having 'item = Int -> Str = {c} ->
                c.fetch {index = 0}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        // Error should indicate type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("Str") || message.contains("mismatch") || message.contains("expected"),
            "Error message should indicate type mismatch: $message"
        )
    }

    @Test
    fun `test error message for multiple ability associated type mismatches`() {
        val code = """
            ability BiContainer for 'c having 'key, 'value where {
                getKey: {it: 'c} -> 'key,
                getValue: {it: 'c} -> 'value,
            }

            impl BiContainer for {k: Int, v: Str} having 'key = Int, 'value = Str where {
                getKey = {it} -> it.k,
                getValue = {it} -> it.v,
            }

            let getWrongKey: {c: 'c} with BiContainer 'c having 'key = Int, 'value = Str -> Str = {c} ->
                c.getKey {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        // Error should indicate type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("Str"),
            "Error message should mention conflicting types: $message"
        )
    }
}
