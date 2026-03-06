package com.mezze.language.typechecker

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

    private fun typeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    // ========================================================================
    // TESTS FOR OPTIONAL having CLAUSE IN ABILITY CONSTRAINTS
    // ========================================================================

    @Test
    fun `test ability constraint with having clause specifies associated type`() {
        val code = """
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> (Option 'item),
                size: {container: 'c} -> Int,
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> Some { val = 0 },
                size = {container} -> 0,
            }

            let getFirst: {c: 'c} -> (Option Int) with Container 'c having 'item = Int = {c} ->
                Container.get {container = c, index = 0}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "getFirst" }
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.TypeApplication)
        val returnTypeApp = funcType.returnType as MezzeType.TypeApplication
        assertEquals("Option", returnTypeApp.constructor)
    }

    @Test
    fun `test ability constraint without having clause infers associated type`() {
        val code = """
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> (Option 'item),
                size: {container: 'c} -> Int,
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> Some { val = 0 },
                size = {container} -> 0,
            }

            let getFirst: {c: 'c} -> (Option 'item) with Container 'c = {c} ->
                Container.get {container = c, index = 0}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "getFirst" }
        assertNotNull(binding.inferredType)
    }

    @Test
    fun `test ability constraint without having clause works with concrete usage`() {
        val code = """
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> (Option 'item),
                size: {container: 'c} -> Int,
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> Some { val = 0 },
                size = {container} -> 0,
            }

            let arr: [Int] = [1, 2, 3]
            let result = Container.get {container = arr, index = 0}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        val resultType = binding.inferredType
        assertTrue(resultType is MezzeType.TypeApplication)
        val typeApp = resultType as MezzeType.TypeApplication
        assertEquals("Option", typeApp.constructor)
        assertEquals(MezzeType.IntType, typeApp.args[0])
    }

    @Test
    fun `test multiple ability constraints one with having one without`() {
        val code = """
            ability Container 'c having 'item where {
                size: {container: 'c} -> Int,
            }

            ability Show 'a where {
                show: {value: 'a} -> String,
            }

            impl Show Int where {
                show = {value} -> "int",
            }

            impl Container [Int] having 'item = Int where {
                size = {container} -> 0,
            }

            let showAndSize: {c: 'c, v: 'v} -> Int with Container 'c having 'item = Int, Show 'v = {c, v} ->
                do
                    let s = Show.show {value = v}
                    Container.size {container = c}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ability constraint without having clause multiple associated types`() {
        val code = """
            ability BiContainer 'c having 'key, 'value where {
                getKey: {container: 'c} -> 'key,
                getValue: {container: 'c} -> 'value,
            }

            impl BiContainer {k: Int, v: String} having 'key = Int, 'value = String where {
                getKey = {container} -> container.k,
                getValue = {container} -> container.v,
            }

            let getBoth: {c: 'c} -> {key: 'key, value: 'value} with BiContainer 'c = {c} ->
                {key = BiContainer.getKey {container = c}, value = BiContainer.getValue {container = c}}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ability constraint with partial having clause specifies one associated type`() {
        val code = """
            ability BiContainer 'c having 'key, 'value where {
                getKey: {container: 'c} -> 'key,
                getValue: {container: 'c} -> 'value,
            }

            impl BiContainer {k: Int, v: String} having 'key = Int, 'value = String where {
                getKey = {container} -> container.k,
                getValue = {container} -> container.v,
            }

            let getWithKnownKey: {c: 'c} -> Int with BiContainer 'c having 'key = Int = {c} ->
                BiContainer.getKey {container = c}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "getWithKnownKey" }
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test simple ability without associated types works without having`() {
        val code = """
            ability Eq 'a where {
                eq: {a: 'a, b: 'a} -> Bool,
            }

            impl Eq Int where {
                eq = {a, b} -> True,
            }

            let areEqual: {a: 'a, b: 'a} -> Bool with Eq 'a = {a, b} ->
                Eq.eq {a = a, b = b}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "areEqual" }
        val funcType = binding.inferredType as MezzeType.FunctionType
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
            ability Eq 'a where {
                eq: {a: 'a, b: 'a} -> Bool,
            }

            ability Show 'a where {
                show: {value: 'a} -> String,
            }

            impl Eq Int where {
                eq = {a, b} -> True,
            }

            impl Show Int where {
                show = {value} -> "int",
            }

            let checkAndShow: {a: 'a, b: 'a} -> String with Eq 'a, Show 'a = {a, b} ->
                do
                    let isEqual = Eq.eq {a = a, b = b}
                    Show.show {value = a}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "checkAndShow" }
        val funcType = binding.inferredType as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test ability constraint inference propagates through function calls`() {
        val code = """
            ability Container 'c having 'item where {
                add: {container: 'c, element: 'item} -> 'c,
            }

            impl Container [Int] having 'item = Int where {
                add = {container, element} -> container,
            }

            let addItem: {c: 'c, item: 'item} -> 'c with Container 'c = {c, item} ->
                Container.add {container = c, element = item}

            let arr: [Int] = [1, 2, 3]
            let result = addItem {c = arr, item = 42}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        val resultType = binding.inferredType
        assertTrue(resultType is MezzeType.ArrayType)
        assertEquals(MezzeType.IntType, (resultType as MezzeType.ArrayType).elementType)
    }

    @Test
    fun `test ability constraint with complex associated type without having`() {
        val code = """
            ability Container 'c having 'item where {
                getAll: {container: 'c} -> ['item],
            }

            impl Container {items: [String]} having 'item = String where {
                getAll = {container} -> container.items,
            }

            let getAllItems: {c: 'c} -> ['item] with Container 'c = {c} ->
                Container.getAll {container = c}
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
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> (Option 'item),
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> Some { val = 0 },
            }

            let getFirst: {c: 'c} -> (Option String) with Container 'c having 'item = Int = {c} ->
                Container.get {container = c, index = 0}
        """.trimIndent()

        val program = parser.parse(code)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test correct ability associated type annotation matches inferred type`() {
        val code = """
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> (Option 'item),
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> Some { val = 0 },
            }

            let getFirst: {c: 'c} -> (Option Int) with Container 'c having 'item = Int = {c} ->
                Container.get {container = c, index = 0}
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
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> (Option 'item),
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> Some { val = 0 },
            }

            let getFirst: {c: 'c} -> (Option String) with Container 'c having 'item = Int = {c} ->
                Container.get {container = c, index = 0}
        """.trimIndent()

        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        // Error message should mention the type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("String"),
            "Error message should mention the conflicting types: $message"
        )
    }

    @Test
    fun `test error message for complex ability associated type mismatch`() {
        val code = """
            ability Container 'c having 'item where {
                get: {container: 'c, index: Int} -> 'item,
            }

            impl Container [Int] having 'item = Int where {
                get = {container, index} -> 0,
            }

            let getFirst: {c: 'c} -> String with Container 'c having 'item = Int = {c} ->
                Container.get {container = c, index = 0}
        """.trimIndent()

        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        // Error should indicate type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("String") || message.contains("mismatch") || message.contains("expected"),
            "Error message should indicate type mismatch: $message"
        )
    }

    @Test
    fun `test error message for multiple ability associated type mismatches`() {
        val code = """
            ability BiContainer 'c having 'key, 'value where {
                getKey: {container: 'c} -> 'key,
                getValue: {container: 'c} -> 'value,
            }

            impl BiContainer {k: Int, v: String} having 'key = Int, 'value = String where {
                getKey = {container} -> container.k,
                getValue = {container} -> container.v,
            }

            let getWrongKey: {c: 'c} -> String with BiContainer 'c having 'key = Int, 'value = String = {c} ->
                BiContainer.getKey {container = c}
        """.trimIndent()

        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        // Error should indicate type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("String"),
            "Error message should mention conflicting types: $message"
        )
    }
}
