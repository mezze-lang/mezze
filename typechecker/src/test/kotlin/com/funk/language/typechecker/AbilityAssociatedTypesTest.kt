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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
            typeChecker.typeCheck(program)
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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

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
            typeChecker.typeCheck(program)
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
        val typedProgram = typeChecker.typeCheck(program)

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
        val typedProgram = typeChecker.typeCheck(program)

        val ability = typedProgram.items[0] as AbilityDeclaration
        assertEquals("'item", ability.associatedType)
    }
}
