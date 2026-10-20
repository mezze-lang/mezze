package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.store.DeclKind
import com.mezze.language.store.Definition
import com.mezze.language.store.DefinitionIndex
import com.mezze.language.store.InMemoryMezzeStore
import com.mezze.language.store.MezzeStore
import com.mezze.language.store.SignatureIndex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What type, ability and effect declarations DEFINE, from real type-checked code
 * through each store and back.
 *
 * Before this the answer lived only in each declaration's serialized typed blob,
 * so a derive or a doc page asking "what fields does `Point` have" had to decode
 * the compiler's AST.
 */
abstract class DefinitionIndexContract {

    abstract fun open(): MezzeStore

    private lateinit var store: MezzeStore

    private val source = """
        type Point = { x: Int, y: Int, label: Str }
        type Shape = | Circle { r: Float }, Square { side: Float }, Dot |
        type Box 'a = { item: 'a, count: Int }
        newtype Meters = Float
        type Name = Str

        ability Total for 'a where {
          total: { it: 'a } -> Int,
          name: { it: 'a } -> Str
        }

        effect Ping where {
          ping: { times: Int } -> Int <Ping>
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        store = open()
        indexSource(store, source)
    }

    @AfterEach fun tearDown() { store.close() }

    private fun definitionOf(kind: DeclKind, name: String): Definition {
        val hash = store.getDeclsForModule("app").single { it.declKind == kind && it.declName == name }.hash
        return DefinitionIndex.rebuild(store.getDefinition(hash))
    }

    @Test
    fun `a record's fields come back in the order they were written`() {
        val def = definitionOf(DeclKind.TYPE, "Point") as Definition.Record
        assertEquals(listOf("x", "y", "label"), def.fields.map { it.name })
        assertEquals(listOf(MezzeType.IntType, MezzeType.IntType, MezzeType.StringType), def.fields.map { it.type })
    }

    @Test
    fun `a variant keeps every constructor, including one with no payload`() {
        val def = definitionOf(DeclKind.TYPE, "Shape") as Definition.Variant
        assertEquals(listOf("Circle", "Square", "Dot"), def.constructors.map { it.name })
        assertEquals(null, def.constructors.single { it.name == "Dot" }.payload)
        val circle = def.constructors.single { it.name == "Circle" }.payload as MezzeType.RecordType
        assertEquals(setOf("r"), circle.fields.keys)
    }

    @Test
    fun `type parameters are one variable throughout the definition`() {
        val def = definitionOf(DeclKind.TYPE, "Box") as Definition.Record
        assertEquals(listOf("'a"), def.typeParams.map { if (it.startsWith("'")) it else "'$it" })
        val item = def.fields.single { it.name == "item" }.type
        assertTrue(item is MezzeType.VarType, "got $item")
    }

    @Test
    fun `a newtype and an alias say what they stand for`() {
        val meters = definitionOf(DeclKind.TYPE, "Meters") as Definition.Newtype
        assertEquals("Meters", meters.constructor)
        assertEquals(MezzeType.FloatType, meters.wrapped)
        val name = definitionOf(DeclKind.TYPE, "Name") as Definition.Alias
        assertEquals(MezzeType.StringType, name.aliased)
    }

    @Test
    fun `an ability lists its operations in written order`() {
        val def = definitionOf(DeclKind.ABILITY, "Total") as Definition.Ability
        assertEquals(listOf("total", "name"), def.operations.map { it.name })
        assertEquals(MezzeType.IntType, def.operations.first().result)
    }

    @Test
    fun `an effect lists its operations with their parameters`() {
        val def = definitionOf(DeclKind.EFFECT, "Ping") as Definition.Effect
        val ping = def.operations.single()
        assertEquals("ping", ping.name)
        assertEquals(setOf("times"), (ping.param as MezzeType.RecordType).fields.keys)
    }

    @Test
    fun `a definition is never read as a signature`() {
        // Separate tables: the signature of a type declaration is empty, and
        // definition rows handed to the type reader fail loudly.
        val hash = store.getDeclsForModule("app").single { it.declName == "Total" }.hash
        assertTrue(store.getSignature(hash).isEmpty)
        assertThrows<IllegalStateException> { SignatureIndex.rebuild(store.getDefinition(hash)) }
    }
}

class SQLiteDefinitionIndexTest : DefinitionIndexContract() {
    override fun open(): MezzeStore = SQLiteMezzeStore.inMemory()
}

class InMemoryDefinitionIndexTest : DefinitionIndexContract() {
    override fun open(): MezzeStore = InMemoryMezzeStore()
}
