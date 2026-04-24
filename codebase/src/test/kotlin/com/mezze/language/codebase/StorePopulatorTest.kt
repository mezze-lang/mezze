package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.truffleir.*
import com.mezze.language.parsedast.Loc
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class StorePopulatorTest {

    private lateinit var store: SQLiteMezzeStore

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
    }

    @AfterEach
    fun tearDown() {
        store.close()
    }

    private val testLoc = Loc("test.mz", 1, 1, 1, 10)

    private fun makeHash(c: Char) = Hash(c.toString().repeat(64))

    @Test
    fun `populate stores let declarations as blobs with names`() {
        val hash = makeHash('a')
        val typedModule = TypedModule(
            path = "myapp::utils",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(
                TypedLetDecl(
                    hash = hash,
                    name = "add",
                    typeParams = emptyList(),
                    value = TypedIntLit(42, MezzeType.IntType, testLoc),
                    scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                    isPublic = true,
                    loc = testLoc
                )
            ),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        val result = TypeCheckResult(typedModule = typedModule)

        StorePopulator.populate(store, result, "myapp::utils", "src/utils.mz", "sourcehash123")

        // Check blob exists
        val blob = store.getBlob(hash)
        assertNotNull(blob)
        assertEquals(BlobKind.FUNCTION, blob!!.kind)
        assertEquals(Visibility.PUBLIC, blob.visibility)
        assertNotNull(blob.typed)

        // Check name registered
        assertEquals(hash, store.resolveName("myapp::utils", "add"))

        // Check module registered
        val module = store.getModule("myapp::utils")
        assertNotNull(module)
        assertEquals("src/utils.mz", module!!.filePath)
        assertEquals("sourcehash123", module.sourceHash)
    }

    @Test
    fun `populate stores FunctionIRs with ir bytes`() {
        val fnHash = makeHash('b')
        val src = SourceInfo(0, 1, 1, 1, 10)
        val functionIR = FunctionIR(
            hash = fnHash,
            name = "add",
            qualifiedName = "myapp::utils::add",
            paramSlots = listOf(SlotKind.OBJECT),
            paramNames = listOf("args"),
            localSlots = emptyList(),
            localNames = emptyList(),
            captureSlots = emptyList(),
            captureNames = emptyList(),
            body = LongLitIR(42, src),
            sourceFile = "test.mz",
            sourceSection = src
        )

        val result = TypeCheckResult(loweredFunctions = listOf(functionIR))

        StorePopulator.populate(store, result, "myapp::utils", "src/utils.mz", "hash456")

        // Check IR stored
        val irBytes = store.getIR(fnHash)
        assertNotNull(irBytes)

        // Deserialize and verify
        val loaded = CodebaseSerialization.deserializeFunctionIR(irBytes!!)
        assertEquals("add", loaded.name)
        assertEquals(fnHash, loaded.hash)
    }

    @Test
    fun `populate merges typed and ir for same hash`() {
        val hash = makeHash('c')
        val src = SourceInfo(0, 1, 1, 1, 10)

        val typedModule = TypedModule(
            path = "mod",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(
                TypedLetDecl(
                    hash = hash,
                    name = "fn",
                    typeParams = emptyList(),
                    value = TypedIntLit(1, MezzeType.IntType, testLoc),
                    scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                    isPublic = false,
                    loc = testLoc
                )
            ),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        val functionIR = FunctionIR(
            hash = hash,
            name = "fn",
            qualifiedName = "mod::fn",
            paramSlots = emptyList(),
            paramNames = emptyList(),
            localSlots = emptyList(),
            localNames = emptyList(),
            captureSlots = emptyList(),
            captureNames = emptyList(),
            body = LongLitIR(1, src),
            sourceFile = "test.mz",
            sourceSection = src
        )

        val result = TypeCheckResult(typedModule = typedModule, loweredFunctions = listOf(functionIR))

        StorePopulator.populate(store, result, "mod", "src/mod.mz", "hash789")

        // Both typed and ir should be present
        val blob = store.getBlob(hash)
        assertNotNull(blob)
        assertNotNull(blob!!.typed, "typed column should be set")
        assertNotNull(blob.ir, "ir column should be set")
    }

    @Test
    fun `populate records edges from CallDirectIR`() {
        val fnA = makeHash('a')
        val fnB = makeHash('b')
        val src = SourceInfo(0, 1, 1, 1, 10)

        val functionIR = FunctionIR(
            hash = fnA,
            name = "a",
            qualifiedName = "mod::a",
            paramSlots = emptyList(),
            paramNames = emptyList(),
            localSlots = emptyList(),
            localNames = emptyList(),
            captureSlots = emptyList(),
            captureNames = emptyList(),
            body = CallDirectIR(fnB, LongLitIR(1, src), false, src),
            sourceFile = "test.mz",
            sourceSection = src
        )

        val result = TypeCheckResult(loweredFunctions = listOf(functionIR))
        StorePopulator.populate(store, result, "mod", "test.mz", "hash")

        // Edge a → b should exist
        val edges = store.getEdgesFrom(fnA)
        assertEquals(1, edges.size)
        assertEquals(fnB, edges[0].toHash)
        assertEquals(EdgeKind.CALLS, edges[0].kind)
    }

    @Test
    fun `populate stores ability impls with index`() {
        val implHash = makeHash('a')
        val abilityHash = makeHash('b')

        val typedModule = TypedModule(
            path = "mod",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = emptyList(),
            abilityDecls = emptyList(),
            abilityImpls = listOf(
                TypedAbilityImpl(
                    hash = implHash,
                    abilityHash = abilityHash,
                    targetTypes = listOf(MezzeType.IntType),
                    forallVars = emptyList(),
                    associatedTypeBindings = emptyMap(),
                    operations = emptyMap(),
                    loc = testLoc
                )
            ),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        val result = TypeCheckResult(typedModule = typedModule)
        StorePopulator.populate(store, result, "mod", "test.mz", "hash")

        // Ability impl should be indexed
        val impls = store.getAbilityImpls(abilityHash)
        assertEquals(1, impls.size)
        assertEquals(implHash, impls[0].hash)

        // Edge: impl → ability
        val edges = store.getEdgesFrom(implHash)
        assertTrue(edges.any { it.toHash == abilityHash && it.kind == EdgeKind.IMPLEMENTS })
    }

    @Test
    fun `populate is idempotent`() {
        val hash = makeHash('a')
        val typedModule = TypedModule(
            path = "mod",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(
                TypedLetDecl(
                    hash = hash,
                    name = "x",
                    typeParams = emptyList(),
                    value = TypedIntLit(1, MezzeType.IntType, testLoc),
                    scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                    isPublic = true,
                    loc = testLoc
                )
            ),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        val result = TypeCheckResult(typedModule = typedModule)

        // Populate twice
        StorePopulator.populate(store, result, "mod", "test.mz", "hash")
        StorePopulator.populate(store, result, "mod", "test.mz", "hash")

        // Should still have exactly one blob and one name
        assertEquals(hash, store.resolveName("mod", "x"))
        assertNotNull(store.getBlob(hash))
    }
}
