package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach

class AbilityAssociatedTypesTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test basic ability with having associated type`() {
        val source = """
            ability Collection for 'c having 'item where {
                empty: {} -> 'c,
                add: {it: 'c, element: 'item} -> 'c,
                size: {it: 'c} -> Int,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(1, parsedModule.decls.size)
        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("Collection", ability.name)
        assertEquals("'item", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability implementation with associated type binding`() {
        val source = """
            ability Collection for 'c having 'item where {
                empty: {} -> 'c,
                add: {it: 'c, element: 'item} -> 'c,
                size: {it: 'c} -> Int,
            }

            impl Collection for [Int] having 'item = Int where {
                empty = {} -> [],
                add = {it, element} -> it,
                size = {it} -> 0,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(2, parsedModule.decls.size)

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'item", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability with associated type in multiple operations`() {
        val source = """
            ability Container for 'c having 'item where {
                empty: {} -> 'c,
                add: {it: 'c, element: 'item} -> 'c,
                contains: {it: 'c, element: 'item} -> Bool,
                get: {it: 'c, index: Int} -> 'item,
                toArray: {it: 'c} -> ['item],
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'item", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability with having and method-level constraint together`() {
        val source = """
            ability Container for 'c having 'item where {
                empty: {} -> 'c,
                add: {it: 'c, element: 'item} -> 'c,
                contains: {it: 'c, element: 'item} with Eq 'item -> Bool,
            }

            impl Container for [Int] having 'item = Int where {
                empty = {} -> [],
                add = {it, element} -> it,
                contains = {it, element} -> False,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'item", ability.associatedTypes.firstOrNull())
        // (Ability-level constraints/dependencies were removed in #212 —
        // they're now a parse error.)
        // Method-level constraint on 'contains'
        val containsOp = ability.operations.find { it.name == "contains" }
        assertNotNull(containsOp)
        assertEquals(1, containsOp!!.abilityConstraints.size)
        assertEquals("Eq", containsOp.abilityConstraints[0].abilityName)
    }

    @Test
    fun `test multiple implementations with different associated type bindings`() {
        val source = """
            ability Collection for 'c having 'item where {
                empty: {} -> 'c,
                add: {it: 'c, element: 'item} -> 'c,
            }

            impl Collection for [Int] having 'item = Int where {
                empty = {} -> [],
                add = {it, element} -> it,
            }

            impl Collection for [Str] having 'item = Str where {
                empty = {} -> [],
                add = {it, element} -> it,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(3, parsedModule.decls.size)

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'item", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability with associated type used in return position`() {
        val source = """
            ability Factory for 'f having 'product where {
                create: {it: 'f} -> 'product,
                createMany: {it: 'f, count: Int} -> ['product],
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'product", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability operations can reference associated type multiple times`() {
        val source = """
            ability Transformable for 't having 'item where {
                size: {it: 't} -> Int,
                first: {it: 't} -> 'item,
                add: {it: 't, item: 'item} -> 't,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'item", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability with having type variable must start with apostrophe`() {
        // This should be caught by the parser, but we test the complete flow
        val source = """
            ability Collection for 'c having item where {
                empty: {} -> 'c,
            }
        """.trimIndent()

        // Parser should reject this
        assertThrows<Exception> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability using associated type in complex type structure`() {
        val source = """
            ability Keyed for 'k having 'key where {
                getKey: {it: 'k} -> 'key,
                setKey: {it: 'k, key: 'key} -> 'k,
                keys: {collection: ['k]} -> ['key],
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'key", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test ability implementation with concrete type binding`() {
        val source = """
            ability Store for 's having 'value where {
                get: {it: 's} -> 'value,
                put: {it: 's, value: 'value} -> 's,
            }

            impl Store for {value: Int} having 'value = Int where {
                get = {it} -> it.value,
                put = {it, value} -> {value = value},
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Should have 2 items: ability and impl
        assertEquals(2, parsedModule.decls.size)
    }

    @Test
    fun `test ability method call with associated types`() {
        val source = """
            ability Collection for 'c having 'item where {
                empty: {} -> 'c,
                append: {it: 'c, element: 'item} -> 'c,
            }

            impl Collection for [Int] having 'item = Int where {
                empty = {} -> [],
                append = {it, element} -> it,
            }

            let intColl: [Int] = [1, 2]
            let withInt = intColl.append {element = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should type check without errors
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability with multiple method-level constraints and associated type`() {
        val source = """
            ability SortedSet for 's having 'elem where {
                empty: {} -> 's,
                insert: {it: 's, element: 'elem} with Eq 'elem, Ord 'elem -> 's,
                contains: {it: 's, element: 'elem} with Eq 'elem -> Bool,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'elem", ability.associatedTypes.firstOrNull())
        // (Ability-level constraints/dependencies were removed in #212 —
        // they're now a parse error.)
        // Method-level constraints
        val insertOp = ability.operations.find { it.name == "insert" }
        assertNotNull(insertOp)
        assertEquals(2, insertOp!!.abilityConstraints.size)
    }

    @Test
    fun `test ability associated type in nested type constructors`() {
        val source = """
            ability NestedCollection for 'c having 'item where {
                empty: {} -> 'c,
                nested: {it: 'c, outer: ['c], inner: 'item} -> [['item]],
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("'item", ability.associatedTypes.firstOrNull())
    }

    @Test
    fun `test error when impl has binding but ability doesn't`() {
        val source = """
            ability Show for 't where {
                show: {it: 't} -> Str,
            }

            impl Show for Int having 'extra = Str where {
                show = {it} -> "42",
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("provides associated type bindings") == true)
        assertTrue(exception.message?.contains("does not declare associated types") == true)
    }

    // ===== Tests for Multiple Associated Types =====

    @Test
    fun `test ability with two associated types`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
                tryConvert: {it: 'from} -> (Result 'to 'error),
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(1, parsedModule.decls.size)
        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals("Convert", ability.name)
        assertEquals(2, ability.associatedTypes.size)
        assertTrue(ability.associatedTypes.contains("'to"))
        assertTrue(ability.associatedTypes.contains("'error"))
    }

    @Test
    fun `test ability with three associated types`() {
        val source = """
            ability Triple for 't having 'a, 'b, 'c where {
                first: {it: 't} -> 'a,
                second: {it: 't} -> 'b,
                third: {it: 't} -> 'c,
                make: {a: 'a, b: 'b, c: 'c} -> 't,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals(3, ability.associatedTypes.size)
        assertTrue(ability.associatedTypes.contains("'a"))
        assertTrue(ability.associatedTypes.contains("'b"))
        assertTrue(ability.associatedTypes.contains("'c"))
    }

    @Test
    fun `test ability implementation with two associated type bindings`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
                tryConvert: {it: 'from} -> (Result 'to 'error),
            }

            impl Convert for String having 'to = Int, 'error = Str where {
                convert = {it} -> 42,
                tryConvert = {it} -> Ok { val = 42 },
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(2, parsedModule.decls.size)
    }

    @Test
    fun `test ability implementation with three associated type bindings`() {
        val source = """
            ability Triple for 't having 'a, 'b, 'c where {
                first: {it: 't} -> 'a,
                second: {it: 't} -> 'b,
                third: {it: 't} -> 'c,
            }

            impl Triple for {x: Int, y: Str, z: Bool} having 'a = Int, 'b = Str, 'c = Bool where {
                first = {it} -> it.x,
                second = {it} -> it.y,
                third = {it} -> it.z,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(2, parsedModule.decls.size)
    }

    @Test
    fun `test type substitution works correctly for each associated type in ability`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
                getError: {it: 'from} -> 'error,
            }

            impl Convert for String having 'to = Int, 'error = Bool where {
                convert = {it} -> 42,
                getError = {it} -> True,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Just verify the program type checks successfully
        assertEquals(2, parsedModule.decls.size)
    }

    @Test
    fun `test error when missing one of multiple associated type bindings in ability`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
            }

            impl Convert for String having 'to = Int where {
                convert = {it} -> 42,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("missing bindings for") == true)
        assertTrue(exception.message?.contains("'error") == true)
    }

    @Test
    fun `test error when missing multiple associated type bindings in ability`() {
        val source = """
            ability Triple for 't having 'a, 'b, 'c where {
                first: {it: 't} -> 'a,
                second: {it: 't} -> 'b,
                third: {it: 't} -> 'c,
            }

            impl Triple for {x: Int} having 'a = Int where {
                first = {it} -> it.x,
                second = {it} -> 0,
                third = {it} -> 0,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("missing bindings for") == true)
        assertTrue(exception.message?.contains("'b") == true)
        assertTrue(exception.message?.contains("'c") == true)
    }

    @Test
    fun `test error when providing wrong binding name with multiple types in ability`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
            }

            impl Convert for String having 'to = Int, 'wrong = Str where {
                convert = {it} -> 42,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        // Should catch that 'wrong is not declared and 'error is missing
        assertTrue(exception.message?.contains("'wrong") == true || exception.message?.contains("'error") == true)
    }

    @Test
    fun `test complex types as multiple associated type bindings in ability`() {
        val source = """
            ability Pair for 'p having 'first, 'second where {
                getFirst: {it: 'p} -> 'first,
                getSecond: {it: 'p} -> 'second,
            }

            impl Pair for {x: Int, y: Str} having 'first = Int, 'second = Str where {
                getFirst = {it} -> it.x,
                getSecond = {it} -> it.y,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Just verify the program type checks successfully
        assertEquals(2, parsedModule.decls.size)
    }

    @Test
    fun `test multiple abilities each with multiple associated types`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
            }

            ability Parser for 'input having 'output, 'state where {
                parse: {it: 'input} -> 'output,
                getState: {it: 'input} -> 'state,
            }

            impl Convert for String having 'to = Int, 'error = Str where {
                convert = {it} -> 42,
            }

            impl Parser for String having 'output = Bool, 'state = Float where {
                parse = {it} -> True,
                getState = {it} -> 3.14,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(4, parsedModule.decls.size)

        val ability1 = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals(2, ability1.associatedTypes.size)

        val ability2 = parsedModule.decls[1] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals(2, ability2.associatedTypes.size)
    }

    @Test
    fun `test ability with multiple associated types and method-level constraints`() {
        val source = """
            ability Converter for 'from having 'to, 'error where {
                convert: {it: 'from} with Eq 'from -> 'to,
                describe: {it: 'from, error: 'error} with Show 'error -> Str,
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val ability = parsedModule.decls[0] as com.mezze.language.parsedast.ParsedAbilityDecl
        assertEquals(2, ability.associatedTypes.size)
        // (Ability-level constraints/dependencies were removed in #212 —
        // they're now a parse error.)
        // Method-level constraints
        assertEquals(1, ability.operations[0].abilityConstraints.size)
        assertEquals(1, ability.operations[1].abilityConstraints.size)
    }

    @Test
    fun `test multiple implementations with different associated type bindings for same ability`() {
        val source = """
            ability Convert for 'from having 'to, 'error where {
                convert: {it: 'from} -> 'to,
            }

            impl Convert for String having 'to = Int, 'error = Str where {
                convert = {it} -> 42,
            }

            impl Convert for Bool having 'to = Int, 'error = Str where {
                convert = {it} -> 1,
            }

            impl Convert for Float having 'to = Str, 'error = Bool where {
                convert = {it} -> "3.14",
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertEquals(4, parsedModule.decls.size)
    }

    // ===== Tests for Associated Type RESOLUTION =====
    // These tests verify that when calling ability methods, the associated types
    // are correctly resolved to their concrete bindings from the impl.

    @Test
    fun `test associated type resolves to concrete type in method return`() {
        val source = """
            ability Container for 'c having 'item where {
                get: {it: 'c, index: Int} -> (Option 'item),
            }

            impl Container for [Int] having 'item = Int where {
                get = {it, index} -> Some { val = 0 },
            }

            let arr: [Int] = [1, 2, 3]
            let result = arr.get {index = 0}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // The type should be Option Int, not Option 'a
        val resultType = result.getVariableType("result")
        assertNotNull(resultType)
        assertTrue(resultType is MezzeType.TypeApplication, "Expected TypeApplication but got $resultType")
        val typeApp = resultType as MezzeType.TypeApplication
        assertEquals("Option", typeApp.constructor)
        assertEquals(1, typeApp.args.size)
        assertEquals(MezzeType.IntType, typeApp.args[0], "Expected Option Int but got Option ${typeApp.args[0]}")
    }

    @Test
    fun `test associated type resolves to concrete type in method parameter`() {
        val source = """
            ability Container for 'c having 'item where {
                append: {it: 'c, element: 'item} -> 'c,
            }

            impl Container for [Int] having 'item = Int where {
                append = {it, element} -> it,
            }

            let arr: [Int] = [1, 2, 3]
            let result = arr.append {element = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should type check without errors - element = 42 should unify with Int (the bound 'item)
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test associated type resolution with multiple associated types`() {
        val source = """
            ability BiContainer for 'c having 'key, 'value where {
                getKey: {it: 'c} -> 'key,
                getValue: {it: 'c} -> 'value,
            }

            impl BiContainer for {k: Int, v: Str} having 'key = Int, 'value = Str where {
                getKey = {it} -> it.k,
                getValue = {it} -> it.v,
            }

            let container: {k: Int, v: Str} = {k = 42, v = "hello"}
            let key = container.getKey {}
            let value = container.getValue {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Find the 'key' binding - should be Int
        assertEquals(MezzeType.IntType, result.getVariableType("key"), "Expected key to be Int")

        // Find the 'value' binding - should be String
        assertEquals(MezzeType.StringType, result.getVariableType("value"), "Expected value to be String")
    }

    @Test
    fun `test associated type resolution with array return type`() {
        val source = """
            ability Container for 'c having 'item where {
                toArray: {it: 'c} -> ['item],
            }

            impl Container for [Int] having 'item = Int where {
                toArray = {it} -> it,
            }

            let arr: [Int] = [1, 2, 3]
            let result = arr.toArray {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Find the 'result' binding - should be [Int]
        val resultType = result.getVariableType("result")
        assertNotNull(resultType)
        assertTrue(resultType is MezzeType.SeqType, "Expected SeqType but got $resultType")
        assertEquals(MezzeType.IntType, (resultType as MezzeType.SeqType).elementType)
    }

    @Test
    fun `test associated type resolution with nested type constructors`() {
        val source = """
            ability Container for 'c having 'item where {
                nested: {it: 'c} -> (Option ['item]),
            }

            impl Container for [Int] having 'item = Int where {
                nested = {it} -> Some { val = it },
            }

            let arr: [Int] = [1, 2, 3]
            let result = arr.nested {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Find the 'result' binding - should be Option [Int]
        val resultType = result.getVariableType("result")
        assertNotNull(resultType)
        assertTrue(resultType is MezzeType.TypeApplication, "Expected TypeApplication but got $resultType")
        val typeApp = resultType as MezzeType.TypeApplication
        assertEquals("Option", typeApp.constructor)
        assertTrue(typeApp.args[0] is MezzeType.SeqType)
        assertEquals(MezzeType.IntType, (typeApp.args[0] as MezzeType.SeqType).elementType)
    }

    @Test
    fun `test associated type resolution error when wrong type passed`() {
        val source = """
            ability Container for 'c having 'item where {
                add: {it: 'c, element: 'item} -> 'c,
            }

            impl Container for [Int] having 'item = Int where {
                add = {it, element} -> it,
            }

            let arr: [Int] = [1, 2, 3]
            let result = arr.add {element = "wrong"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should fail because "wrong" is String but 'item = Int
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }
}
