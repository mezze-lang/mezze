package com.mezze.language.codebase

import com.mezze.language.store.*

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
        val loaded = StoreSerialization.deserializeFunctionIR(irBytes!!)
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

        // FK requires fnB exists in blobs before edges can reference it.
        store.putBlob(fnB, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)

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
            body = CallDirectIR(fnB, listOf(LongLitIR(1, src)), false, src),
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

        // FK requires abilityHash exists in blobs before edges/impls
        // reference it. In production the ability decl is registered
        // alongside the impl; here we stub it.
        store.putBlob(abilityHash, BlobKind.ABILITY, Visibility.PUBLIC, null, null)

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
    fun `populate writes decls index when entrySource provided`() {
        val letHash = makeHash('a')
        val typeHash = makeHash('b')
        val letLoc = Loc("u.mz", 1, 1, 1, 25)    // line 1, cols 1..25 inclusive
        val typeLoc = Loc("u.mz", 2, 1, 2, 30)   // line 2

        val typedModule = TypedModule(
            path = "myapp::utils",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(
                TypedLetDecl(
                    hash = letHash,
                    name = "add",
                    typeParams = emptyList(),
                    value = TypedIntLit(42, MezzeType.IntType, letLoc),
                    scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                    isPublic = true,
                    loc = letLoc
                ),
                TypedTypeDecl(
                    hash = typeHash,
                    name = "Point",
                    typeParams = emptyList(),
                    definition = TypedRecordDef(emptyMap(), isOpen = false),
                    isPublic = true,
                    loc = typeLoc
                )
            ),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        // Source bytes that match the locs above.
        val source = "let add = { l: Int } -> 42\n" +
                     "type Point = { x: Int, y: Int }\n"

        StorePopulator.populate(
            store,
            TypeCheckResult(typedModule = typedModule),
            modulePath = "myapp::utils",
            filePath = "src/utils.mz",
            sourceHash = "sh",
            entrySource = source
        )

        val rows = store.getDeclsForModule("myapp::utils").associateBy { it.declName }
        assertEquals(2, rows.size)

        val add = rows["add"] ?: error("missing add")
        assertEquals(DeclKind.LET, add.declKind)

        // The signature index is populated HERE, from the let's scheme, not by
        // whoever calls putDecl later. A type decl has no signature to index,
        // so its column stays null rather than holding something that is not a
        // signature.
        assertEquals("Int", add.signature)
        assertNull(rows["Point"]?.signature, "a type declaration has no signature")
        assertEquals(letHash, add.hash)
        assertEquals(1, add.startLine)
        assertEquals(25, add.endCol)
        assertTrue(add.sourceHash.length == 64, "source_hash should be 64 hex chars")

        val point = rows["Point"] ?: error("missing Point")
        assertEquals(DeclKind.TYPE, point.declKind)
        assertEquals(typeHash, point.hash)

        // sourceHash differs between the two decls (different bytes).
        assertNotEquals(add.sourceHash, point.sourceHash)
    }

    @Test
    fun `searchDeclsByName returns prefix matches across modules`() {
        val hashA = makeHash('a')
        val hashB = makeHash('b')
        val loc = Loc("a.mz", 1, 1, 1, 10)

        val source1 = "let add_one = {} -> 1\n"
        val source2 = "let other = {} -> 2\n"

        val mod1 = TypedModule(
            path = "m1", hash = makeHash('0'),
            imports = emptyList(), exports = emptyMap(),
            decls = listOf(TypedLetDecl(
                hash = hashA, name = "add_one", typeParams = emptyList(),
                value = TypedIntLit(1, MezzeType.IntType, loc),
                scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                isPublic = true, loc = loc
            )),
            abilityDecls = emptyList(), abilityImpls = emptyList(),
            effectDecls = emptyList(), performerDecls = emptyList(),
            dependencies = emptyList()
        )

        val mod2 = TypedModule(
            path = "m2", hash = makeHash('1'),
            imports = emptyList(), exports = emptyMap(),
            decls = listOf(TypedLetDecl(
                hash = hashB, name = "other", typeParams = emptyList(),
                value = TypedIntLit(2, MezzeType.IntType, loc),
                scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                isPublic = true, loc = loc
            )),
            abilityDecls = emptyList(), abilityImpls = emptyList(),
            effectDecls = emptyList(), performerDecls = emptyList(),
            dependencies = emptyList()
        )

        StorePopulator.populate(store, TypeCheckResult(typedModule = mod1), "m1", "m1.mz", "sh1", entrySource = source1)
        StorePopulator.populate(store, TypeCheckResult(typedModule = mod2), "m2", "m2.mz", "sh2", entrySource = source2)

        val results = store.searchDeclsByName("add")
        assertEquals(1, results.size)
        assertEquals("add_one", results[0].declName)
        assertEquals("m1", results[0].modulePath)
    }

    @Test
    fun `populate writes imports when ParsedModule has selective use clauses`() {
        // Build a ParsedModule with a `use std::int::{add, multiply as mul}`
        // by hand. The typechecker would normally produce this, but we want
        // to test scatterImports without dragging in the whole pipeline.
        val pkgPath = com.mezze.language.parsedast.ParsedModulePath(listOf("std", "int"))
        val useLoc = Loc("u.mz", 1, 1, 1, 30)
        val itemAdd = com.mezze.language.parsedast.ParsedImportItem(
            name = "add",
            alias = null,
            loc = Loc("u.mz", 1, 16, 1, 18)
        )
        val itemMul = com.mezze.language.parsedast.ParsedImportItem(
            name = "multiply",
            alias = "mul",
            loc = Loc("u.mz", 1, 21, 1, 31)
        )
        val useDecl = com.mezze.language.parsedast.ParsedUseDecl(
            path = com.mezze.language.parsedast.ParsedUsePath.Selective(
                modulePath = pkgPath,
                items = listOf(itemAdd, itemMul),
                loc = useLoc
            ),
            loc = useLoc
        )
        val parsed = com.mezze.language.parsedast.ParsedModule(
            path = "myapp",
            decls = listOf(useDecl)
        )

        val typedModule = TypedModule(
            path = "myapp",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = emptyList(),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        // Pre-register std::int::add so the writer picks up its hash.
        // multiply stays unresolved → null targetHash on that row.
        val addHash = makeHash('a')
        store.putBlob(addHash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putModule("std::int", null, "sh")
        store.registerName("std::int", "add", addHash)

        StorePopulator.populate(
            store,
            TypeCheckResult(typedModule = typedModule),
            modulePath = "myapp",
            filePath = "src/u.mz",
            sourceHash = "sh",
            parsedModule = parsed
        )

        val rows = store.getImportsForModule("myapp").associateBy { it.localName }
        assertEquals(2, rows.size)

        val addRow = rows["add"] ?: error("missing add")
        assertEquals("std::int", addRow.targetModule)
        assertEquals("add", addRow.targetName)
        assertEquals(addHash, addRow.targetHash)

        val mulRow = rows["mul"] ?: error("missing mul")
        assertEquals("std::int", mulRow.targetModule)
        assertEquals("multiply", mulRow.targetName)  // original name preserved
        assertNull(mulRow.targetHash, "multiply was never registered, hash should be null")

        // Reverse lookup: who imports std::int::add?
        val importers = store.getImportersOf("std::int", "add")
        assertEquals(1, importers.size)
        assertEquals("myapp", importers[0].modulePath)
    }

    @Test
    fun `populate writes usages for ByHash references in decl bodies`() {
        // Build a TypedLetDecl whose body is `add` (a TypedVar referring
        // to another decl by hash). The walker should record one usages
        // row pointing at the TypedVar's loc.
        val targetHash = makeHash('a')
        val callerHash = makeHash('b')
        val callerLoc = Loc("u.mz", 1, 1, 1, 30)
        val varLoc = Loc("u.mz", 1, 14, 1, 17)

        val typedModule = TypedModule(
            path = "myapp",
            hash = makeHash('0'),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(
                TypedLetDecl(
                    hash = callerHash,
                    name = "use_add",
                    typeParams = emptyList(),
                    value = TypedVar(
                        name = "add",
                        binding = BindingRef.ByHash(targetHash),
                        type = MezzeType.IntType,
                        loc = varLoc
                    ),
                    scheme = TypeScheme(emptyList(), emptyList(), MezzeType.IntType),
                    isPublic = true,
                    loc = callerLoc
                )
            ),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList()
        )

        StorePopulator.populate(
            store,
            TypeCheckResult(typedModule = typedModule),
            modulePath = "myapp",
            filePath = "src/u.mz",
            sourceHash = "sh"
        )

        val rows = store.getUsages(targetHash)
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("src/u.mz", row.filePath)
        assertEquals(varLoc.startLine, row.startLine)
        assertEquals(varLoc.startCol, row.startCol)
        assertEquals(varLoc.endLine, row.endLine)
        assertEquals(varLoc.endCol, row.endCol)
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
