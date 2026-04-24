package com.mezze.language.codebase

import com.mezze.language.truffleir.*
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class MezzeExecutorTest {

    private lateinit var store: SQLiteMezzeStore
    private lateinit var executor: MezzeExecutor

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        executor = MezzeExecutor(listOf(store))
    }

    @AfterEach
    fun tearDown() {
        store.close()
    }

    private fun makeHash(c: Char) = Hash(c.toString().repeat(64))

    private fun storeFunctionIR(hash: Hash, ir: FunctionIR) {
        val irBytes = CodebaseSerialization.serializeFunctionIR(ir)
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, irBytes)
    }

    private fun simpleFunctionIR(hash: Hash, name: String, body: NodeIR): FunctionIR {
        val src = SourceInfo(0, 1, 1, 1, 10)
        return FunctionIR(
            hash = hash,
            name = name,
            qualifiedName = name,
            paramSlots = emptyList(),
            paramNames = emptyList(),
            localSlots = emptyList(),
            localNames = emptyList(),
            captureSlots = emptyList(),
            captureNames = emptyList(),
            body = body,
            sourceFile = "test.mz",
            sourceSection = src
        )
    }

    @Test
    fun `loadIR returns deserialized FunctionIR from store`() {
        val hash = makeHash('a')
        val src = SourceInfo(0, 1, 1, 1, 5)
        val ir = simpleFunctionIR(hash, "test_fn", LongLitIR(42, src))
        storeFunctionIR(hash, ir)

        val loaded = executor.loadIR(hash)
        assertNotNull(loaded)
        assertEquals("test_fn", loaded!!.name)
        assertEquals(hash, loaded.hash)
    }

    @Test
    fun `loadIR returns null for missing hash`() {
        assertNull(executor.loadIR(makeHash('9')))
    }

    @Test
    fun `loadIR searches stores in order`() {
        val store2 = SQLiteMezzeStore.inMemory()
        val multiExecutor = MezzeExecutor(listOf(store, store2))

        val hash = makeHash('b')
        val src = SourceInfo(0, 1, 1, 1, 5)
        val ir = simpleFunctionIR(hash, "in_store2", LongLitIR(99, src))

        // Only in store2
        val irBytes = CodebaseSerialization.serializeFunctionIR(ir)
        store2.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, irBytes)

        val loaded = multiExecutor.loadIR(hash)
        assertNotNull(loaded)
        assertEquals("in_store2", loaded!!.name)

        store2.close()
    }

    @Test
    fun `loadTransitiveFunctions follows CallDirectIR references`() {
        val hashA = makeHash('a')
        val hashB = makeHash('b')
        val src = SourceInfo(0, 1, 1, 1, 5)

        // A calls B
        val irA = simpleFunctionIR(hashA, "fn_a",
            CallDirectIR(hashB, LongLitIR(1, src), false, src))
        val irB = simpleFunctionIR(hashB, "fn_b", LongLitIR(2, src))

        storeFunctionIR(hashA, irA)
        storeFunctionIR(hashB, irB)

        val functions = executor.loadTransitiveFunctions(hashA)
        assertEquals(2, functions.size)
        assertTrue(functions.any { it.name == "fn_a" })
        assertTrue(functions.any { it.name == "fn_b" })
    }

    @Test
    fun `loadTransitiveFunctions follows MakeLambdaIR references`() {
        val hashA = makeHash('a')
        val hashLambda = makeHash('b')
        val src = SourceInfo(0, 1, 1, 1, 5)

        val irA = simpleFunctionIR(hashA, "fn_a",
            MakeLambdaIR(hashLambda, emptyList(), src))
        val irLambda = simpleFunctionIR(hashLambda, "lambda", LongLitIR(42, src))

        storeFunctionIR(hashA, irA)
        storeFunctionIR(hashLambda, irLambda)

        val functions = executor.loadTransitiveFunctions(hashA)
        assertEquals(2, functions.size)
    }

    @Test
    fun `loadTransitiveFunctions handles chains`() {
        val hashA = makeHash('a')
        val hashB = makeHash('b')
        val hashC = makeHash('c')
        val src = SourceInfo(0, 1, 1, 1, 5)

        // A → B → C
        val irA = simpleFunctionIR(hashA, "a", CallDirectIR(hashB, UnitLitIR(src), false, src))
        val irB = simpleFunctionIR(hashB, "b", CallDirectIR(hashC, UnitLitIR(src), false, src))
        val irC = simpleFunctionIR(hashC, "c", LongLitIR(1, src))

        storeFunctionIR(hashA, irA)
        storeFunctionIR(hashB, irB)
        storeFunctionIR(hashC, irC)

        val functions = executor.loadTransitiveFunctions(hashA)
        assertEquals(3, functions.size)
    }

    @Test
    fun `loadTransitiveFunctions handles cycles without infinite loop`() {
        val hashA = makeHash('a')
        val hashB = makeHash('b')
        val src = SourceInfo(0, 1, 1, 1, 5)

        // A → B → A (cycle)
        val irA = simpleFunctionIR(hashA, "a", CallDirectIR(hashB, UnitLitIR(src), false, src))
        val irB = simpleFunctionIR(hashB, "b", CallDirectIR(hashA, UnitLitIR(src), false, src))

        storeFunctionIR(hashA, irA)
        storeFunctionIR(hashB, irB)

        val functions = executor.loadTransitiveFunctions(hashA)
        assertEquals(2, functions.size)
    }

    @Test
    fun `loadTransitiveFunctions skips missing hashes gracefully`() {
        val hashA = makeHash('a')
        val hashMissing = makeHash('f')
        val src = SourceInfo(0, 1, 1, 1, 5)

        // A calls missing hash (could be a native function)
        val irA = simpleFunctionIR(hashA, "a",
            CallDirectIR(hashMissing, UnitLitIR(src), false, src))

        storeFunctionIR(hashA, irA)

        val functions = executor.loadTransitiveFunctions(hashA)
        assertEquals(1, functions.size)
        assertEquals("a", functions[0].name)
    }

    @Test
    fun `resolveEntry finds main in store`() {
        val hash = makeHash('a')
        store.registerName("myapp", "main", hash)

        assertEquals(hash, executor.resolveEntry("myapp"))
    }

    @Test
    fun `resolveEntry falls back to __entry__`() {
        val hash = makeHash('a')
        store.registerName("myapp", "__entry__", hash)

        assertEquals(hash, executor.resolveEntry("myapp"))
    }

    @Test
    fun `resolveEntry returns null when not found`() {
        assertNull(executor.resolveEntry("nonexistent"))
    }

    @Test
    fun `resolveEntry searches stores in order`() {
        val store2 = SQLiteMezzeStore.inMemory()
        val multiExecutor = MezzeExecutor(listOf(store, store2))

        val hash = makeHash('a')
        store2.registerName("myapp", "main", hash)

        assertEquals(hash, multiExecutor.resolveEntry("myapp"))
        store2.close()
    }

    @Test
    fun `exists checks all stores`() {
        val store2 = SQLiteMezzeStore.inMemory()
        val multiExecutor = MezzeExecutor(listOf(store, store2))

        val hash = makeHash('a')
        store2.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)

        assertTrue(multiExecutor.exists(hash))
        assertFalse(multiExecutor.exists(makeHash('9')))
        store2.close()
    }
}
