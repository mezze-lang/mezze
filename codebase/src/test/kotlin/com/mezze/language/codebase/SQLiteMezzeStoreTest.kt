package com.mezze.language.codebase

import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class SQLiteMezzeStoreTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
    }

    @AfterEach
    fun tearDown() {
        store.close()
    }

    // ====================================================================
    // BLOBS
    // ====================================================================

    @Test
    fun `putBlob and getBlob round-trip`() {
        val hash = Hash("abc123def456abc123def456abc123def456abc123def456abc123def456abc12345")
        val typed = "typed-data".toByteArray()
        val ir = "ir-data".toByteArray()

        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, typed, ir)

        val row = store.getBlob(hash)
        assertNotNull(row)
        assertEquals(hash, row!!.hash)
        assertEquals(BlobKind.FUNCTION, row.kind)
        assertEquals(Visibility.PUBLIC, row.visibility)
        assertArrayEquals(typed, row.typed)
        assertArrayEquals(ir, row.ir)
    }

    @Test
    fun `getBlob returns null for missing hash`() {
        val hash = Hash("0".repeat(64))
        assertNull(store.getBlob(hash))
    }

    @Test
    fun `blobExists returns true for stored blob`() {
        val hash = Hash("a".repeat(64))
        store.putBlob(hash, BlobKind.TYPE, Visibility.PRIVATE, null, null)
        assertTrue(store.blobExists(hash))
    }

    @Test
    fun `blobExists returns false for missing blob`() {
        assertFalse(store.blobExists(Hash("f".repeat(64))))
    }

    @Test
    fun `getIR returns ir bytes only`() {
        val hash = Hash("b".repeat(64))
        val ir = "ir-bytes".toByteArray()
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, "typed".toByteArray(), ir)

        assertArrayEquals(ir, store.getIR(hash))
    }

    @Test
    fun `getIR returns null for missing hash`() {
        assertNull(store.getIR(Hash("c".repeat(64))))
    }

    @Test
    fun `putBlob is idempotent — INSERT OR REPLACE`() {
        val hash = Hash("d".repeat(64))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PRIVATE, "v1".toByteArray(), null)
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, "v2".toByteArray(), "ir".toByteArray())

        val row = store.getBlob(hash)!!
        assertEquals(Visibility.PUBLIC, row.visibility)
        assertArrayEquals("v2".toByteArray(), row.typed)
        assertArrayEquals("ir".toByteArray(), row.ir)
    }

    @Test
    fun `putBlob with null typed and ir`() {
        val hash = Hash("e".repeat(64))
        store.putBlob(hash, BlobKind.ABILITY, Visibility.PUBLIC, null, null)

        val row = store.getBlob(hash)!!
        assertNull(row.typed)
        assertNull(row.ir)
    }

    // ====================================================================
    // NAMES
    // ====================================================================

    @Test
    fun `registerName and resolveName round-trip`() {
        val hash = Hash("a".repeat(64))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.registerName("mymodule", "add", hash)

        assertEquals(hash, store.resolveName("mymodule", "add"))
    }

    @Test
    fun `resolveName returns null for missing name`() {
        assertNull(store.resolveName("mymodule", "nonexistent"))
    }

    @Test
    fun `getNamesForHash returns all names`() {
        val hash = Hash("a".repeat(64))
        store.registerName("mod1", "foo", hash)
        store.registerName("mod2", "bar", hash)

        val names = store.getNamesForHash(hash)
        assertEquals(2, names.size)
        assertTrue(names.any { it.module == "mod1" && it.name == "foo" })
        assertTrue(names.any { it.module == "mod2" && it.name == "bar" })
    }

    @Test
    fun `getModuleExports returns only public blobs`() {
        val pubHash = Hash("a".repeat(64))
        val privHash = Hash("b".repeat(64))
        store.putBlob(pubHash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putBlob(privHash, BlobKind.FUNCTION, Visibility.PRIVATE, null, null)
        store.registerName("mod", "pub_fn", pubHash)
        store.registerName("mod", "priv_fn", privHash)

        val exports = store.getModuleExports("mod")
        assertEquals(1, exports.size)
        assertEquals("pub_fn", exports[0].name)
    }

    @Test
    fun `searchExportsByName finds by prefix`() {
        val hash = Hash("a".repeat(64))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.registerName("std::str", "starts_with", hash)
        store.registerName("std::str", "split", hash)

        val results = store.searchExportsByName("sta")
        assertEquals(1, results.size)
        assertEquals("starts_with", results[0].name)

        val results2 = store.searchExportsByName("s")
        assertEquals(2, results2.size)
    }

    // ====================================================================
    // MODULES
    // ====================================================================

    @Test
    fun `putModule and getModule round-trip`() {
        store.putModule("myapp::utils", "src/utils.mz", "abc123")

        val row = store.getModule("myapp::utils")
        assertNotNull(row)
        assertEquals("myapp::utils", row!!.path)
        assertEquals("src/utils.mz", row.filePath)
        assertEquals("abc123", row.sourceHash)
    }

    @Test
    fun `isFileStale returns true when source hash differs`() {
        store.putModule("mod", "src/mod.mz", "old_hash")
        assertTrue(store.isFileStale("src/mod.mz", "new_hash"))
    }

    @Test
    fun `isFileStale returns false when source hash matches`() {
        store.putModule("mod", "src/mod.mz", "same_hash")
        assertFalse(store.isFileStale("src/mod.mz", "same_hash"))
    }

    @Test
    fun `isFileStale returns true for unknown file`() {
        assertTrue(store.isFileStale("src/unknown.mz", "any_hash"))
    }

    @Test
    fun `getAllModules returns all registered modules`() {
        store.putModule("a", "a.mz", "h1")
        store.putModule("b", "b.mz", "h2")
        assertEquals(2, store.getAllModules().size)
    }

    // ====================================================================
    // EDGES
    // ====================================================================

    @Test
    fun `addEdge and getEdgesFrom`() {
        val a = Hash("a".repeat(64))
        val b = Hash("b".repeat(64))
        store.addEdge(a, b, EdgeKind.CALLS)

        val edges = store.getEdgesFrom(a)
        assertEquals(1, edges.size)
        assertEquals(b, edges[0].toHash)
        assertEquals(EdgeKind.CALLS, edges[0].kind)
    }

    @Test
    fun `getEdgesTo returns incoming edges`() {
        val a = Hash("a".repeat(64))
        val b = Hash("b".repeat(64))
        val c = Hash("c".repeat(64))
        store.addEdge(a, c, EdgeKind.CALLS)
        store.addEdge(b, c, EdgeKind.REFERENCES)

        val incoming = store.getEdgesTo(c)
        assertEquals(2, incoming.size)
    }

    @Test
    fun `addEdge is idempotent — INSERT OR IGNORE`() {
        val a = Hash("a".repeat(64))
        val b = Hash("b".repeat(64))
        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(a, b, EdgeKind.CALLS) // duplicate

        assertEquals(1, store.getEdgesFrom(a).size)
    }

    @Test
    fun `getTransitiveClosure walks full graph`() {
        val a = Hash("a".repeat(64))
        val b = Hash("b".repeat(64))
        val c = Hash("c".repeat(64))
        val d = Hash("d".repeat(64))

        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(b, c, EdgeKind.CALLS)
        store.addEdge(b, d, EdgeKind.REFERENCES)

        val closure = store.getTransitiveClosure(a)
        assertEquals(setOf(a, b, c, d), closure)
    }

    @Test
    fun `getTransitiveClosure handles cycles`() {
        val a = Hash("a".repeat(64))
        val b = Hash("b".repeat(64))
        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(b, a, EdgeKind.CALLS) // cycle

        val closure = store.getTransitiveClosure(a)
        assertEquals(setOf(a, b), closure)
    }

    @Test
    fun `getTransitiveClosure single node no edges`() {
        val a = Hash("a".repeat(64))
        assertEquals(setOf(a), store.getTransitiveClosure(a))
    }

    // ====================================================================
    // ABILITY / EFFECT INDEX
    // ====================================================================

    @Test
    fun `putAbilityImpl and getAbilityImpls`() {
        val implHash = Hash("a".repeat(64))
        val abilityHash = Hash("b".repeat(64))
        store.putAbilityImpl(implHash, abilityHash, "Int", "std::int")

        val impls = store.getAbilityImpls(abilityHash)
        assertEquals(1, impls.size)
        assertEquals(implHash, impls[0].hash)
        assertEquals("Int", impls[0].targetType)
    }

    @Test
    fun `getAbilityImplForType returns specific impl`() {
        val implInt = Hash("a".repeat(64))
        val implStr = Hash("b".repeat(64))
        val abilityHash = Hash("c".repeat(64))
        store.putAbilityImpl(implInt, abilityHash, "Int", null)
        store.putAbilityImpl(implStr, abilityHash, "Str", null)

        assertEquals(implInt, store.getAbilityImplForType(abilityHash, "Int"))
        assertEquals(implStr, store.getAbilityImplForType(abilityHash, "Str"))
        assertNull(store.getAbilityImplForType(abilityHash, "Bool"))
    }

    @Test
    fun `putEffectPerformer and getEffectPerformers`() {
        val perfHash = Hash("a".repeat(64))
        val effectHash = Hash("b".repeat(64))
        store.putEffectPerformer(perfHash, effectHash, "LocalConsole", "std::io")

        val perfs = store.getEffectPerformers(effectHash)
        assertEquals(1, perfs.size)
        assertEquals("LocalConsole", perfs[0].performerName)
    }

    // ====================================================================
    // REFERENCES
    // ====================================================================

    @Test
    fun `putReference and getReferences`() {
        val hash = Hash("a".repeat(64))
        store.putReference(hash, "src/main.mz", 10, 5, 10, 8)
        store.putReference(hash, "src/utils.mz", 20, 1, 20, 4)

        val refs = store.getReferences(hash)
        assertEquals(2, refs.size)
    }

    @Test
    fun `clearReferencesForFile removes only that file`() {
        val hash = Hash("a".repeat(64))
        store.putReference(hash, "src/main.mz", 1, 1, 1, 5)
        store.putReference(hash, "src/utils.mz", 2, 1, 2, 5)

        store.clearReferencesForFile("src/main.mz")

        val refs = store.getReferences(hash)
        assertEquals(1, refs.size)
        assertEquals("src/utils.mz", refs[0].filePath)
    }

    // ====================================================================
    // METADATA
    // ====================================================================

    @Test
    fun `getMetadata and setMetadata`() {
        store.setMetadata("project_name", "my_app")
        assertEquals("my_app", store.getMetadata("project_name"))
    }

    @Test
    fun `getMetadata returns null for missing key`() {
        assertNull(store.getMetadata("nonexistent"))
    }

    @Test
    fun `schema_version is set`() {
        assertEquals("5", store.getMetadata("schema_version"))
    }

    // ====================================================================
    // TRANSACTIONS
    // ====================================================================

    @Test
    fun `transaction commits on success`() {
        val hash = Hash("a".repeat(64))
        store.transaction {
            store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
            store.registerName("mod", "fn", hash)
        }

        assertTrue(store.blobExists(hash))
        assertEquals(hash, store.resolveName("mod", "fn"))
    }

    @Test
    fun `transaction rolls back on exception`() {
        val hash = Hash("a".repeat(64))
        try {
            store.transaction {
                store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
                error("fail")
            }
        } catch (_: Exception) {}

        assertFalse(store.blobExists(hash))
    }

    // ====================================================================
    // FILE-BASED STORE
    // ====================================================================

    @Test
    fun `forProject creates mz directory and cache file`() {
        val tempDir = kotlin.io.path.createTempDirectory("mezze-test").toFile()
        try {
            // Create Mezze.toml so it looks like a project
            java.io.File(tempDir, "Mezze.toml").writeText("[package]\nname = \"test\"\n")

            val projectStore = SQLiteMezzeStore.forProject(tempDir)
            val hash = Hash("a".repeat(64))
            projectStore.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, "ir".toByteArray())
            projectStore.close()

            // Verify file exists
            assertTrue(java.io.File(tempDir, ".mz/cache.sqlite").exists())

            // Reopen and verify data persists
            val reopened = SQLiteMezzeStore.forProject(tempDir)
            assertTrue(reopened.blobExists(hash))
            assertArrayEquals("ir".toByteArray(), reopened.getIR(hash))
            reopened.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
