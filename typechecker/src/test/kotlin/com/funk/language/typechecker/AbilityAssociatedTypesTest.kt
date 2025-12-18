package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class AbilityAssociatedTypesTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test basic ability with having associated type`() {
        val source = """
            ability Collection 'c having 'item where {
                empty: {} -> 'c
                add: {collection: 'c, element: 'item} -> 'c
                size: {collection: 'c} -> Int
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(1, typedProgram.items.size)
        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("Collection", ability.name)
        assertEquals("'item", ability.associatedType)
        assertNotNull(ability.inferredType)
        assertTrue(ability.inferredType is FunkType.RecordType)

        val recordType = ability.inferredType as FunkType.RecordType
        assertTrue(recordType.fields.containsKey("empty"))
        assertTrue(recordType.fields.containsKey("add"))
        assertTrue(recordType.fields.containsKey("size"))
    }

    @Test
    fun `test ability implementation with associated type binding`() {
        val source = """
            ability Collection 'c having 'item where {
                empty: {} -> 'c
                add: {collection: 'c, element: 'item} -> 'c
                size: {collection: 'c} -> Int
            }

            impl Collection [Int] having 'item = Int where {
                empty = {} -> []
                add = {collection, element} -> collection
                size = {collection} -> 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(2, typedProgram.items.size)

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)

        val impl = typedProgram.items[1] as ImplAbilityDeclaration
        assertNotNull(impl.inferredType)
    }

    @Test
    fun `test ability with associated type in multiple operations`() {
        val source = """
            ability Container 'c having 'item where {
                empty: {} -> 'c
                add: {container: 'c, element: 'item} -> 'c
                contains: {container: 'c, element: 'item} -> Bool
                get: {container: 'c, index: Int} -> 'item
                toArray: {container: 'c} -> ['item]
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)

        val recordType = ability.inferredType as FunkType.RecordType
        assertEquals(5, recordType.fields.size)
    }

    @Test
    fun `test ability with having and with constraint together`() {
        val source = """
            ability Container 'c having 'item with Eq 'item where {
                empty: {} -> 'c
                add: {container: 'c, element: 'item} -> 'c
                contains: {container: 'c, element: 'item} -> Bool
            }

            impl Container [Int] having 'item = Int where {
                empty = {} -> []
                add = {container, element} -> container
                contains = {container, element} -> False
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)
        assertEquals(1, ability.dependencies.size)
        assertEquals("Eq", ability.dependencies[0].abilityName)
    }

    @Test
    fun `test multiple implementations with different associated type bindings`() {
        val source = """
            ability Collection 'c having 'item where {
                empty: {} -> 'c
                add: {collection: 'c, element: 'item} -> 'c
            }

            impl Collection [Int] having 'item = Int where {
                empty = {} -> []
                add = {collection, element} -> collection
            }

            impl Collection [String] having 'item = String where {
                empty = {} -> []
                add = {collection, element} -> collection
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(3, typedProgram.items.size)

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)

        val impl1 = typedProgram.items[1] as ImplAbilityDeclaration
        assertNotNull(impl1.inferredType)

        val impl2 = typedProgram.items[2] as ImplAbilityDeclaration
        assertNotNull(impl2.inferredType)
    }

    @Test
    fun `test ability with associated type used in return position`() {
        val source = """
            ability Factory 'f having 'product where {
                create: {factory: 'f} -> 'product
                createMany: {factory: 'f, count: Int} -> ['product]
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'product", ability.associatedType)

        val recordType = ability.inferredType as FunkType.RecordType
        assertTrue(recordType.fields.containsKey("create"))
        assertTrue(recordType.fields.containsKey("createMany"))
    }

    @Test
    fun `test ability operations can reference associated type multiple times`() {
        val source = """
            ability Transformable 't having 'item where {
                size: {container: 't} -> Int
                first: {container: 't} -> 'item
                add: {container: 't, item: 'item} -> 't
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)

        val recordType = ability.inferredType as FunkType.RecordType
        assertEquals(3, recordType.fields.size)
    }

    @Test
    fun `test ability with having type variable must start with apostrophe`() {
        // This should be caught by the parser, but we test the complete flow
        val source = """
            ability Collection 'c having item where {
                empty: {} -> 'c
            }
        """.trimIndent()

        // Parser should reject this
        assertThrows<Exception> {
            val program = parser.parse(source)
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test ability using associated type in complex type structure`() {
        val source = """
            ability Keyed 'k having 'key where {
                getKey: {keyed: 'k} -> 'key
                setKey: {keyed: 'k, key: 'key} -> 'k
                keys: {collection: ['k]} -> ['key]
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'key", ability.associatedType)
    }

    @Test
    fun `test ability implementation with concrete type binding`() {
        val source = """
            ability Store 's having 'value where {
                get: {store: 's} -> 'value
                put: {store: 's, value: 'value} -> 's
            }

            impl Store {value: Int} having 'value = Int where {
                get = {store} -> store.value
                put = {store, value} -> {value = value}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Should have 2 items: ability and impl
        assertEquals(2, typedProgram.items.size)

        val impl = typedProgram.items[1] as ImplAbilityDeclaration
        assertNotNull(impl.inferredType)
    }

    @Test
    fun `test ability method call with associated types`() {
        val source = """
            ability Collection 'c having 'item where {
                empty: {} -> 'c
                add: {collection: 'c, element: 'item} -> 'c
            }

            impl Collection [Int] having 'item = Int where {
                empty = {} -> []
                add = {collection, element} -> collection
            }

            let intColl = Collection.empty {}
            let withInt = Collection.add {collection = intColl, element = 42}
        """.trimIndent()

        val program = parser.parse(source)

        // Should type check without errors
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test ability with multiple constraints and associated type`() {
        val source = """
            ability SortedSet 's having 'elem with Eq 'elem, Ord 'elem where {
                empty: {} -> 's
                insert: {set: 's, element: 'elem} -> 's
                contains: {set: 's, element: 'elem} -> Bool
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'elem", ability.associatedType)
        assertEquals(2, ability.dependencies.size)
    }

    @Test
    fun `test ability associated type in nested type constructors`() {
        val source = """
            ability NestedCollection 'c having 'item where {
                empty: {} -> 'c
                nested: {outer: ['c], inner: 'item} -> [['item]]
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)
    }

    @Test
    fun `test error when impl has binding but ability doesn't`() {
        val source = """
            ability Show 't where {
                show: {value: 't} -> String
            }

            impl Show Int having 'extra = String where {
                show = {value} -> "42"
            }
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("provides associated type bindings") == true)
        assertTrue(exception.message?.contains("does not declare associated types") == true)
    }

    // ===== Tests for Multiple Associated Types =====

    @Test
    fun `test ability with two associated types`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
                tryConvert: {value: 'from} -> (Result 'to 'error)
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(1, typedProgram.items.size)
        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("Convert", ability.name)
        assertEquals(2, ability.associatedTypes.size)
        assertTrue(ability.associatedTypes.contains("'to"))
        assertTrue(ability.associatedTypes.contains("'error"))
    }

    @Test
    fun `test ability with three associated types`() {
        val source = """
            ability Triple 't having 'a, 'b, 'c where {
                first: {triple: 't} -> 'a
                second: {triple: 't} -> 'b
                third: {triple: 't} -> 'c
                make: {a: 'a, b: 'b, c: 'c} -> 't
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals(3, ability.associatedTypes.size)
        assertTrue(ability.associatedTypes.contains("'a"))
        assertTrue(ability.associatedTypes.contains("'b"))
        assertTrue(ability.associatedTypes.contains("'c"))
    }

    @Test
    fun `test ability implementation with two associated type bindings`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
                tryConvert: {value: 'from} -> (Result 'to 'error)
            }

            impl Convert String having 'to = Int, 'error = String where {
                convert = {value} -> 42
                tryConvert = {value} -> Ok { val = 42 }
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(2, typedProgram.items.size)
        val impl = typedProgram.items[1] as ImplAbilityDeclaration
        assertNotNull(impl.inferredType)
    }

    @Test
    fun `test ability implementation with three associated type bindings`() {
        val source = """
            ability Triple 't having 'a, 'b, 'c where {
                first: {triple: 't} -> 'a
                second: {triple: 't} -> 'b
                third: {triple: 't} -> 'c
            }

            impl Triple {x: Int, y: String, z: Bool} having 'a = Int, 'b = String, 'c = Bool where {
                first = {triple} -> triple.x
                second = {triple} -> triple.y
                third = {triple} -> triple.z
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(2, typedProgram.items.size)
        val impl = typedProgram.items[1] as ImplAbilityDeclaration
        assertNotNull(impl.inferredType)
    }

    @Test
    fun `test type substitution works correctly for each associated type in ability`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
                getError: {} -> 'error
            }

            impl Convert String having 'to = Int, 'error = Bool where {
                convert = {value} -> 42
                getError = {} -> true
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Just verify the program type checks successfully
        assertEquals(2, typedProgram.items.size)
    }

    @Test
    fun `test error when missing one of multiple associated type bindings in ability`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
            }

            impl Convert String having 'to = Int where {
                convert = {value} -> 42
            }
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("missing bindings for") == true)
        assertTrue(exception.message?.contains("'error") == true)
    }

    @Test
    fun `test error when missing multiple associated type bindings in ability`() {
        val source = """
            ability Triple 't having 'a, 'b, 'c where {
                first: {triple: 't} -> 'a
                second: {triple: 't} -> 'b
                third: {triple: 't} -> 'c
            }

            impl Triple {x: Int} having 'a = Int where {
                first = {triple} -> triple.x
                second = {triple} -> 0
                third = {triple} -> 0
            }
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("missing bindings for") == true)
        assertTrue(exception.message?.contains("'b") == true)
        assertTrue(exception.message?.contains("'c") == true)
    }

    @Test
    fun `test error when providing wrong binding name with multiple types in ability`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
            }

            impl Convert String having 'to = Int, 'wrong = String where {
                convert = {value} -> 42
            }
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        // Should catch that 'wrong is not declared and 'error is missing
        assertTrue(exception.message?.contains("'wrong") == true || exception.message?.contains("'error") == true)
    }

    @Test
    fun `test complex types as multiple associated type bindings in ability`() {
        val source = """
            ability Pair 'p having 'first, 'second where {
                getFirst: {pair: 'p} -> 'first
                getSecond: {pair: 'p} -> 'second
            }

            impl Pair {x: Int, y: String} having 'first = Int, 'second = String where {
                getFirst = {pair} -> pair.x
                getSecond = {pair} -> pair.y
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Just verify the program type checks successfully
        assertEquals(2, typedProgram.items.size)
    }

    @Test
    fun `test multiple abilities each with multiple associated types`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
            }

            ability Parser 'input having 'output, 'state where {
                parse: {input: 'input} -> 'output
                getState: {input: 'input} -> 'state
            }

            impl Convert String having 'to = Int, 'error = String where {
                convert = {value} -> 42
            }

            impl Parser String having 'output = Bool, 'state = Float where {
                parse = {input} -> true
                getState = {input} -> 3.14
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(4, typedProgram.items.size)

        val ability1 = typedProgram.items[0] as AbilityDeclaration
        assertEquals(2, ability1.associatedTypes.size)

        val ability2 = typedProgram.items[1] as AbilityDeclaration
        assertEquals(2, ability2.associatedTypes.size)
    }

    @Test
    fun `test ability with multiple associated types and constraints`() {
        val source = """
            ability Converter 'from having 'to, 'error with Eq 'from, Show 'error where {
                convert: {value: 'from} -> 'to
                describe: {error: 'error} -> String
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals(2, ability.associatedTypes.size)
        assertEquals(2, ability.dependencies.size)
    }

    @Test
    fun `test multiple implementations with different associated type bindings for same ability`() {
        val source = """
            ability Convert 'from having 'to, 'error where {
                convert: {value: 'from} -> 'to
            }

            impl Convert String having 'to = Int, 'error = String where {
                convert = {value} -> 42
            }

            impl Convert Bool having 'to = Int, 'error = String where {
                convert = {value} -> 1
            }

            impl Convert Float having 'to = String, 'error = Bool where {
                convert = {value} -> "3.14"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertEquals(4, typedProgram.items.size)

        val impl1 = typedProgram.items[1] as ImplAbilityDeclaration
        assertNotNull(impl1.inferredType)

        val impl2 = typedProgram.items[2] as ImplAbilityDeclaration
        assertNotNull(impl2.inferredType)

        val impl3 = typedProgram.items[3] as ImplAbilityDeclaration
        assertNotNull(impl3.inferredType)
    }
}
