package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class MonoSpecializationsTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach fun setUp() { store = SQLiteMezzeStore.inMemory() }
    @AfterEach fun tearDown() { store.close() }

    private fun stubBlob(hash: Hash): Hash {
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PRIVATE, null, null)
        return hash
    }

    @Test
    fun `put and get round-trip`() {
        val gh = Hash("a".repeat(64))
        val sh = stubBlob(Hash("b".repeat(64)))
        val cbor = CodebaseSerialization.serializeTypeList(listOf(MezzeType.IntType))

        store.putMonoSpecialization(sh, gh, cbor)

        val row = store.getMonoSpecialization(sh)
        assertNotNull(row)
        assertEquals(sh, row!!.specializedHash)
        assertEquals(gh, row.genericHash)
        assertArrayEquals(cbor, row.typeArgsCbor)
    }

    @Test
    fun `getMonoSpecialization returns null for missing hash`() {
        assertNull(store.getMonoSpecialization(Hash("0".repeat(64))))
    }

    @Test
    fun `getMonoSpecializationsForGeneric groups by generic_hash`() {
        val gh = Hash("a".repeat(64))
        val s1 = stubBlob(Hash("b".repeat(64)))
        val s2 = stubBlob(Hash("c".repeat(64)))
        val s3 = stubBlob(Hash("d".repeat(64)))
        val otherGh = Hash("e".repeat(64))

        val intCbor = CodebaseSerialization.serializeTypeList(listOf(MezzeType.IntType))
        val strCbor = CodebaseSerialization.serializeTypeList(listOf(MezzeType.StringType))

        store.putMonoSpecialization(s1, gh, intCbor)
        store.putMonoSpecialization(s2, gh, strCbor)
        store.putMonoSpecialization(s3, otherGh, intCbor) // different generic

        val rows = store.getMonoSpecializationsForGeneric(gh)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.genericHash == gh })
        assertTrue(rows.map { it.specializedHash }.toSet() == setOf(s1, s2))
    }

    @Test
    fun `put is idempotent (INSERT OR REPLACE)`() {
        val gh = Hash("a".repeat(64))
        val sh = stubBlob(Hash("b".repeat(64)))
        val v1 = CodebaseSerialization.serializeTypeList(listOf(MezzeType.IntType))
        val v2 = CodebaseSerialization.serializeTypeList(listOf(MezzeType.StringType))

        store.putMonoSpecialization(sh, gh, v1)
        store.putMonoSpecialization(sh, gh, v2)

        val row = store.getMonoSpecialization(sh)
        assertNotNull(row)
        assertArrayEquals(v2, row!!.typeArgsCbor)
    }

    @Test
    fun `cascade dropping the specialized blob removes its provenance row`() {
        val gh = Hash("a".repeat(64))
        val sh = stubBlob(Hash("b".repeat(64)))
        store.putMonoSpecialization(sh, gh, CodebaseSerialization.serializeTypeList(listOf(MezzeType.IntType)))

        // Manually delete the blob to verify CASCADE.
        store.transaction {
            store.toString() // no-op; just to ensure we're inside the lambda
        }
        val conn = SQLiteMezzeStore::class.java.getDeclaredField("conn").apply { isAccessible = true }.get(store) as java.sql.Connection
        conn.prepareStatement("DELETE FROM blobs WHERE hash = ?").use { ps ->
            ps.setString(1, sh.hex)
            ps.executeUpdate()
        }
        assertNull(store.getMonoSpecialization(sh))
    }

    @Test
    fun `serializeTypeList round-trip preserves order and content`() {
        val types: List<MezzeType> = listOf(
            MezzeType.IntType,
            MezzeType.StringType,
            MezzeType.ListType(MezzeType.IntType)
        )
        val bytes = CodebaseSerialization.serializeTypeList(types)
        val back = CodebaseSerialization.deserializeTypeList(bytes)
        assertEquals(types, back)
    }

    @Test
    fun `populateMonoSpecializations writes blobs and provenance`() {
        // Build a tiny mono Result containing one specialization. The
        // specialized body is a literal so we don't need a Lambda walker —
        // the populator only cares about the spec's hash + body type.
        val gh = Hash("a".repeat(64))
        val sh = Hash("b".repeat(64))
        val loc = com.mezze.language.parsedast.Loc("test.mz", 1, 1, 1, 1)
        val body = com.mezze.language.typedast.TypedIntLit(42, MezzeType.IntType, loc)
        val spec = com.mezze.language.typechecker.mono.Monomorphizer.Specialization(
            specializedHash = sh,
            genericHash = gh,
            typeArgs = listOf(MezzeType.IntType),
            abilityImpls = emptyList(),
            specializedBody = body,
            specializedType = MezzeType.IntType
        )
        val req = com.mezze.language.typechecker.mono.Monomorphizer.SpecRequest(gh, listOf(MezzeType.IntType), emptyList())
        val result = com.mezze.language.typechecker.mono.Monomorphizer.Result(
            specializations = mapOf(sh to spec),
            callSiteRewrites = mapOf(req to sh),
            fallbacks = emptyList()
        )

        StorePopulator.populateMonoSpecializations(store, result, mapOf(gh to "id"))

        // Blob exists with FUNCTION + PRIVATE.
        val blob = store.getBlob(sh)
        assertNotNull(blob)
        assertEquals(BlobKind.FUNCTION, blob!!.kind)
        assertEquals(Visibility.PRIVATE, blob.visibility)
        assertNotNull(blob.typed)
        // typed bytes deserialize back to a TypedLetDecl with the right hash.
        val decl = CodebaseSerialization.deserializeTypedLetDecl(blob.typed!!)
        assertEquals(sh, decl.hash)
        assertEquals("id", decl.name)
        assertTrue(decl.typeParams.isEmpty())

        // mono_specializations row exists with serialized typeArgs.
        val row = store.getMonoSpecialization(sh)
        assertNotNull(row)
        assertEquals(gh, row!!.genericHash)
        assertEquals(listOf(MezzeType.IntType), CodebaseSerialization.deserializeTypeList(row.typeArgsCbor))

        // Reverse-lookup by generic hash returns this specialization.
        val byGeneric = store.getMonoSpecializationsForGeneric(gh)
        assertTrue(byGeneric.any { it.specializedHash == sh })
    }
}
