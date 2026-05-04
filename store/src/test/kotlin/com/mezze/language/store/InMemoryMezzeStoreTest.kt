package com.mezze.language.store

import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryMezzeStoreTest {

    private var counter = 0
    private fun hash(label: String = ""): Hash {
        val hex = "%064x".format(++counter)
        return Hash(hex)
    }

    @Test
    fun `put and get blob`() {
        val store = InMemoryMezzeStore()
        val h = hash()
        store.putBlob(h, BlobKind.FUNCTION, Visibility.PUBLIC, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))

        val row = store.getBlob(h)
        assertNotNull(row)
        assertEquals(BlobKind.FUNCTION, row.kind)
        assertEquals(Visibility.PUBLIC, row.visibility)
        assertTrue(row.typed!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(row.ir!!.contentEquals(byteArrayOf(4, 5)))
    }

    @Test
    fun `blob exists`() {
        val store = InMemoryMezzeStore()
        val h = hash()
        assertNull(store.getBlob(h))

        store.putBlob(h, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        assertTrue(store.blobExists(h))
    }

    @Test
    fun `register and resolve name`() {
        val store = InMemoryMezzeStore()
        val h = hash()
        store.registerName("mymod", "add", h)

        assertEquals(h, store.resolveName("mymod", "add"))
        assertNull(store.resolveName("mymod", "sub"))
        assertNull(store.resolveName("other", "add"))
    }

    @Test
    fun `module exports`() {
        val store = InMemoryMezzeStore()
        store.registerName("std::int", "add", hash())
        store.registerName("std::int", "sub", hash())
        store.registerName("std::str", "len", hash())

        val exports = store.getModuleExports("std::int")
        assertEquals(2, exports.size)
    }

    @Test
    fun `put and get module`() {
        val store = InMemoryMezzeStore()
        store.putModule("mymod", "/path/to/file.mz", "sha256abc")

        val mod = store.getModule("mymod")
        assertNotNull(mod)
        assertEquals("/path/to/file.mz", mod.filePath)
        assertEquals("sha256abc", mod.sourceHash)
    }

    @Test
    fun `file staleness check`() {
        val store = InMemoryMezzeStore()
        store.putModule("mymod", "/path/file.mz", "aabb")

        assertTrue(store.isFileStale("/path/file.mz", "ccdd"))
        assertTrue(!store.isFileStale("/path/file.mz", "aabb"))
        assertTrue(store.isFileStale("/other/file.mz", "aabb"))
    }

    @Test
    fun `edges and transitive closure`() {
        val store = InMemoryMezzeStore()
        val a = hash()
        val b = hash()
        val c = hash()

        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(b, c, EdgeKind.CALLS)

        assertEquals(listOf(EdgeRow(a, b, EdgeKind.CALLS)), store.getEdgesFrom(a))
        assertEquals(listOf(EdgeRow(a, b, EdgeKind.CALLS)), store.getEdgesTo(b))

        val closure = store.getTransitiveClosure(a)
        assertEquals(setOf(a, b, c), closure)
    }

    @Test
    fun `ability impls`() {
        val store = InMemoryMezzeStore()
        val implHash = hash()
        val abilityHash = hash()

        store.putAbilityImpl(implHash, abilityHash, "Int", "std::int")

        val impls = store.getAbilityImpls(abilityHash)
        assertEquals(1, impls.size)
        assertEquals("Int", impls[0].targetType)

        assertEquals(implHash, store.getAbilityImplForType(abilityHash, "Int"))
        assertNull(store.getAbilityImplForType(abilityHash, "Str"))
    }

    @Test
    fun `usages and clear`() {
        val store = InMemoryMezzeStore()
        val h = hash()

        store.putUsage(h, "file1.mz", 10, 5, 10, 8)
        store.putUsage(h, "file2.mz", 20, 1, 20, 4)

        assertEquals(2, store.getUsages(h).size)

        store.clearUsagesForFile("file1.mz")
        assertEquals(1, store.getUsages(h).size)
        assertEquals("file2.mz", store.getUsages(h)[0].filePath)
    }

    @Test
    fun `parsed and typed module cache`() {
        val store = InMemoryMezzeStore()
        val blob = byteArrayOf(1, 2, 3, 4)

        store.putParsedModule("mymod", "sha1", blob)
        assertTrue(store.getParsedModule("mymod", "sha1")!!.contentEquals(blob))
        assertNull(store.getParsedModule("mymod", "sha2"))

        store.putTypedModule("mymod", "sha1", blob)
        assertTrue(store.getTypedModule("mymod", "sha1")!!.contentEquals(blob))

        store.clearParsedModulesForPath("mymod")
        assertNull(store.getParsedModule("mymod", "sha1"))
    }

    @Test
    fun `decls search`() {
        val store = InMemoryMezzeStore()
        val h1 = hash()
        val h2 = hash()
        val h3 = hash()
        store.putDecl("mymod", "add", DeclKind.LET, "src1", h1, 1, 1, 1, 10)
        store.putDecl("mymod", "addTwo", DeclKind.LET, "src2", h2, 2, 1, 2, 15)
        store.putDecl("other", "sub", DeclKind.LET, "src3", h3, 1, 1, 1, 10)

        assertEquals(2, store.getDeclsForModule("mymod").size)
        assertEquals(2, store.searchDeclsByName("add").size)
        assertEquals(1, store.searchDeclsByName("sub").size)
        assertEquals(3, store.getDeclsByKind(DeclKind.LET).size)
        assertNotNull(store.findDeclByHash(h1))
        assertNull(store.findDeclByHash(hash()))
    }

    @Test
    fun `transaction is passthrough`() {
        val store = InMemoryMezzeStore()
        val h = hash()
        val result = store.transaction {
            store.putBlob(h, BlobKind.TYPE, Visibility.PRIVATE, null, null)
            42
        }
        assertEquals(42, result)
        assertTrue(store.blobExists(h))
    }
}
