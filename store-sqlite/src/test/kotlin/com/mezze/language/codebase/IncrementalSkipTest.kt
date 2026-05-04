package com.mezze.language.codebase

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typedast.*
import com.mezze.language.truffleir.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.security.MessageDigest

class IncrementalSkipTest {

    private lateinit var store: SQLiteMezzeStore
    private lateinit var tmpFile: File

    @BeforeEach
    fun setUp() {
        store = SQLiteMezzeStore.inMemory()
        tmpFile = File.createTempFile("inc-skip-test-", ".mz")
        tmpFile.deleteOnExit()
    }

    @AfterEach
    fun tearDown() {
        store.close()
        tmpFile.delete()
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Set up a minimal project state in [store] that mirrors what
     * StorePopulator + DevRunner would produce for a successful compile.
     */
    private fun seedHappyPath(source: String, modulePath: String = "myapp"): Pair<com.mezze.language.parsedast.ParsedModule, String> {
        tmpFile.writeText(source)
        val parsed = MezzeParser().parseParsedModule(source, tmpFile.absolutePath)
        val sourceHash = sha256(source)

        // Use the parsed decl's actual loc so the synthetic TypedModule's
        // decl source range matches what the gate computes from the
        // ParsedModule (otherwise the per-decl source_hash diverges).
        val parsedMain = parsed.decls.filterIsInstance<com.mezze.language.parsedast.ParsedLetDecl>()
            .first { it.isSimpleBinding && it.variableName == "main" }
        val testLoc = parsedMain.loc

        // Synthesize a minimal TypedModule that round-trips through CBOR.
        val mainHash = Hash("a".repeat(64))
        val typedModule = TypedModule(
            path = modulePath,
            hash = Hash("0".repeat(64)),
            imports = emptyList(),
            exports = emptyMap(),
            decls = listOf(
                TypedLetDecl(
                    hash = mainHash,
                    name = "main",
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

        StorePopulator.populate(
            store,
            TypeCheckResult(typedModule = typedModule),
            modulePath = modulePath,
            filePath = tmpFile.absolutePath,
            sourceHash = sourceHash,
            entrySource = source,
            parsedModule = parsed
        )
        // App bookkeeping that the gate's loadFunctions step needs.
        store.transaction {
            store.putApp("myapp", "main", modulePath, tmpFile.absolutePath)
            store.setAppFunctionHashes("myapp", listOf(mainHash))
        }
        // Stash a dummy IR so loadFunctions returns a complete list.
        val src = SourceInfo(0, 1, 1, 1, 10)
        val fakeIR = FunctionIR(
            hash = mainHash, name = "main", qualifiedName = "$modulePath::main",
            paramSlots = emptyList(), paramNames = emptyList(),
            localSlots = emptyList(), localNames = emptyList(),
            captureSlots = emptyList(), captureNames = emptyList(),
            body = LongLitIR(42, src),
            sourceFile = tmpFile.name, sourceSection = src
        )
        val irBytes = CodebaseSerialization.serializeFunctionIR(fakeIR)
        val existing = store.getBlob(mainHash)
        store.putBlob(
            mainHash,
            existing?.kind ?: BlobKind.FUNCTION,
            existing?.visibility ?: Visibility.PUBLIC,
            existing?.typed,
            irBytes
        )

        return parsed to sourceHash
    }

    @Test
    fun `skip fires when nothing changed`() {
        val source = "let main = {} -> 42\n"
        val (parsed, sh) = seedHappyPath(source)

        val result = IncrementalSkip.maybeSkip(
            store = store,
            entryParsed = parsed,
            entrySource = source,
            entrySourceHash = sh,
            modulePath = "myapp",
            entryFilePath = tmpFile.absolutePath,
            appName = "myapp"
        )
        assertTrue(result is IncrementalSkip.Result.Skip,
            "expected Skip, got $result")
        val hit = (result as IncrementalSkip.Result.Skip).hit
        assertEquals("myapp", hit.typedModule.path)
        assertEquals(1, hit.loweredFunctions.size)
    }

    @Test
    fun `skip denied when file hash changes`() {
        val orig = "let main = {} -> 42\n"
        val (_, _) = seedHappyPath(orig)

        // Re-parse the modified source; gate is asked with the new hash.
        val edited = "let main = {} -> 43\n"
        val newParsed = MezzeParser().parseParsedModule(edited, tmpFile.absolutePath)

        val result = IncrementalSkip.maybeSkip(
            store = store,
            entryParsed = newParsed,
            entrySource = edited,
            entrySourceHash = sha256(edited),
            modulePath = "myapp",
            entryFilePath = tmpFile.absolutePath,
            appName = "myapp"
        )
        assertTrue(result is IncrementalSkip.Result.Miss,
            "expected Miss, got $result")
        // Either FILE_HASH_MISMATCH (modules.source_hash differs) or
        // DECL_BODY_CHANGED (decls.source_hash differs) — both correct.
        val miss = (result as IncrementalSkip.Result.Miss).reason
        assertTrue(
            miss == IncrementalSkip.MissReason.FILE_HASH_MISMATCH ||
            miss == IncrementalSkip.MissReason.DECL_BODY_CHANGED,
            "unexpected miss reason: $miss"
        )
    }

    @Test
    fun `skip denied when a dep file is stale on disk`() {
        // Seed a module that imports another. The dep file then gets
        // mutated on disk; the gate must catch it.
        val depFile = File.createTempFile("inc-skip-dep-", ".mz")
        depFile.deleteOnExit()
        try {
            val depSource = "pub let helper = {} -> 1\n"
            depFile.writeText(depSource)
            val depHash = sha256(depSource)
            store.putModule("dep", depFile.absolutePath, depHash)

            val source = "let main = {} -> 42\n"
            val (parsed, sh) = seedHappyPath(source)

            // Plant an imports row pointing at our dep module. The dep's
            // target_hash doesn't matter for this gate — only that we
            // can locate `dep`'s module row.
            store.putImport(
                modulePath = "myapp",
                localName = "helper",
                targetModule = "dep",
                targetName = "helper",
                targetHash = null,
                startLine = 1, startCol = 1, endLine = 1, endCol = 10
            )

            // Modify the dep file on disk so the on-disk hash diverges
            // from modules.source_hash for "dep".
            depFile.writeText("pub let helper = {} -> 999\n")

            val result = IncrementalSkip.maybeSkip(
                store = store,
                entryParsed = parsed,
                entrySource = source,
                entrySourceHash = sh,
                modulePath = "myapp",
                entryFilePath = tmpFile.absolutePath,
                appName = "myapp"
            )
            assertTrue(result is IncrementalSkip.Result.Miss,
                "expected Miss for stale dep, got $result")
            assertEquals(
                IncrementalSkip.MissReason.DEP_FILE_STALE,
                (result as IncrementalSkip.Result.Miss).reason
            )
        } finally {
            depFile.delete()
        }
    }
}
