package com.mezze.language.codebase

import com.mezze.language.store.*

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

    /**
     * Insert a stub blob so FK-constrained tables (names, edges, ability_impls,
     * effect_performers, decls, imports, usages, app_*) can reference it.
     * v7 enforces FKs; tests that synthesize hashes need to register a blob
     * row first. Returns the same hash for chaining.
     */
    private fun stubBlob(hash: Hash, kind: BlobKind = BlobKind.FUNCTION): Hash {
        store.putBlob(hash, kind, Visibility.PUBLIC, null, null)
        return hash
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
    fun `getAllBlobHashes includes attached tiers`() {
        // Regression: getAllBlobHashes was `SELECT FROM blobs` (main only).
        // Now: UNION ALL across main + stdlib + deps. A project-mode store
        // must see its stdlib's blobs when bulk-loading.
        val mainHash = Hash("1".repeat(64))
        val stdlibHash = Hash("2".repeat(64))
        store.putBlob(mainHash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        // Write a blob directly into the attached stdlib tier.
        store.executeRaw { conn ->
            conn.prepareStatement(
                "INSERT INTO stdlib.blobs (hash, kind, visibility, typed, ir) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, stdlibHash.hex)
                stmt.setString(2, "FUNCTION")
                stmt.setString(3, "PUBLIC")
                stmt.setBytes(4, null)
                stmt.setBytes(5, null)
                stmt.executeUpdate()
            }
        }

        val all = store.getAllBlobHashes()
        assertTrue(all.contains(mainHash), "Expected main blob in list; got $all")
        assertTrue(all.contains(stdlibHash), "Expected stdlib blob in list; got $all")
    }

    @Test
    fun `getIR finds IR in non-main tier even if main has null-ir row`() {
        // Regression test: previously, if `main.blobs` had a row with `ir = NULL`
        // for some hash, `getIR()` returned null even when an attached tier
        // (stdlib/deps) had the same hash with real IR bytes. The UNION ALL
        // picked main's null row first and LIMIT 1 cut off the search.
        // Fix: filter `ir IS NOT NULL` per-tier inside the UNION ALL branches.
        val hash = Hash("e".repeat(64))
        val irBytes = "real-ir-bytes".toByteArray()

        // Main has an IR-less placeholder (e.g. a typed-only row written before IR scatter).
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, "typed".toByteArray(), null)

        // Stdlib has the same hash with real IR. Use executeRaw to write directly
        // into the attached stdlib database since the public API only writes main.
        store.executeRaw { conn ->
            conn.prepareStatement(
                "INSERT INTO stdlib.blobs (hash, kind, visibility, typed, ir) VALUES (?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, hash.hex)
                stmt.setString(2, "FUNCTION")
                stmt.setString(3, "PUBLIC")
                stmt.setBytes(4, null)
                stmt.setBytes(5, irBytes)
                stmt.executeUpdate()
            }
        }

        // L1 cache may have memoized the previous main-only read; clear by
        // creating a fresh store instance against the same hash via getIR.
        // Actually we haven't read this hash yet, so L1 is empty.
        assertArrayEquals(irBytes, store.getIR(hash),
            "Expected getIR to find the IR-bearing row in stdlib, " +
            "but it returned null — main's null-ir row shadowed the search.")
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
        store.putModule("mymodule", "src/m.mz", "h")
        store.registerName("mymodule", "add", hash)

        assertEquals(hash, store.resolveName("mymodule", "add"))
    }

    @Test
    fun `resolveName returns null for missing name`() {
        assertNull(store.resolveName("mymodule", "nonexistent"))
    }

    @Test
    fun `getNamesForHash returns all names`() {
        val hash = stubBlob(Hash("a".repeat(64)))
        store.putModule("mod1", "1.mz", "h")
        store.putModule("mod2", "2.mz", "h")
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
        store.putModule("mod", "m.mz", "h")
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
        store.putModule("std::str", "/tmp/str.mz", "h")
        store.registerName("std::str", "starts_with", hash)
        store.registerName("std::str", "split", hash)

        val results = store.searchExportsByName("sta")
        assertEquals(1, results.size)
        assertEquals("starts_with", results[0].name)

        val results2 = store.searchExportsByName("s")
        assertEquals(2, results2.size)
    }

    @Test
    fun `searchExportsByName escapes LIKE metacharacters`() {
        // Without escaping, `_` in a query matches any single char, so `foo_bar`
        // would also match `fooXbar`, `fooQbar`, etc. Same for `%` (any string).
        // Fix: escape `_`, `%`, `\` in user input before binding.
        val hash = Hash("b".repeat(64))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putModule("m", "/tmp/m.mz", "h")
        store.registerName("m", "foo_bar", hash)
        store.registerName("m", "fooXbar", hash)
        store.registerName("m", "fooYbar", hash)

        // Literal underscore should match only `foo_bar`, not the X/Y variants.
        val results = store.searchExportsByName("foo_bar")
        assertEquals(1, results.size,
            "Expected exact-underscore match only; got ${results.map { it.name }}")
        assertEquals("foo_bar", results[0].name)
    }

    @Test
    fun `searchDeclsByName escapes LIKE metacharacters`() {
        val hash = Hash("c".repeat(64))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putModule("m", "/tmp/m.mz", "h")
        // Use stubBlob helper isn't needed — putBlob already done.
        store.putDecl("m", "foo_bar", DeclKind.LET, "src1", hash, 1, 1, 1, 10)
        store.putDecl("m", "fooXbar", DeclKind.LET, "src2", hash, 2, 1, 2, 10)

        val results = store.searchDeclsByName("foo_bar")
        assertEquals(1, results.size,
            "Expected exact-underscore match only; got ${results.map { it.declName }}")
        assertEquals("foo_bar", results[0].declName)
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
        val a = stubBlob(Hash("a".repeat(64)))
        val b = stubBlob(Hash("b".repeat(64)))
        store.addEdge(a, b, EdgeKind.CALLS)

        val edges = store.getEdgesFrom(a)
        assertEquals(1, edges.size)
        assertEquals(b, edges[0].toHash)
        assertEquals(EdgeKind.CALLS, edges[0].kind)
    }

    @Test
    fun `getEdgesTo returns incoming edges`() {
        val a = stubBlob(Hash("a".repeat(64)))
        val b = stubBlob(Hash("b".repeat(64)))
        val c = stubBlob(Hash("c".repeat(64)))
        store.addEdge(a, c, EdgeKind.CALLS)
        store.addEdge(b, c, EdgeKind.REFERENCES)

        val incoming = store.getEdgesTo(c)
        assertEquals(2, incoming.size)
    }

    @Test
    fun `addEdge is idempotent — INSERT OR IGNORE`() {
        val a = stubBlob(Hash("a".repeat(64)))
        val b = stubBlob(Hash("b".repeat(64)))
        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(a, b, EdgeKind.CALLS) // duplicate

        assertEquals(1, store.getEdgesFrom(a).size)
    }

    @Test
    fun `getTransitiveClosure walks full graph`() {
        val a = stubBlob(Hash("a".repeat(64)))
        val b = stubBlob(Hash("b".repeat(64)))
        val c = stubBlob(Hash("c".repeat(64)))
        val d = stubBlob(Hash("d".repeat(64)))

        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(b, c, EdgeKind.CALLS)
        store.addEdge(b, d, EdgeKind.REFERENCES)

        val closure = store.getTransitiveClosure(a)
        assertEquals(setOf(a, b, c, d), closure)
    }

    @Test
    fun `getTransitiveClosure handles cycles`() {
        val a = stubBlob(Hash("a".repeat(64)))
        val b = stubBlob(Hash("b".repeat(64)))
        store.addEdge(a, b, EdgeKind.CALLS)
        store.addEdge(b, a, EdgeKind.CALLS) // cycle

        val closure = store.getTransitiveClosure(a)
        assertEquals(setOf(a, b), closure)
    }

    @Test
    fun `getTransitiveClosure single node no edges`() {
        val a = stubBlob(Hash("a".repeat(64)))
        assertEquals(setOf(a), store.getTransitiveClosure(a))
    }

    // ====================================================================
    // ABILITY / EFFECT INDEX
    // ====================================================================

    @Test
    fun `putAbilityImpl and getAbilityImpls`() {
        val implHash = stubBlob(Hash("a".repeat(64)), BlobKind.ABILITY_IMPL)
        val abilityHash = stubBlob(Hash("b".repeat(64)), BlobKind.ABILITY)
        store.putAbilityImpl(implHash, abilityHash, "Int", null)

        val impls = store.getAbilityImpls(abilityHash)
        assertEquals(1, impls.size)
        assertEquals(implHash, impls[0].hash)
        assertEquals("Int", impls[0].targetType)
    }

    @Test
    fun `getAbilityImplForType returns specific impl`() {
        val implInt = stubBlob(Hash("a".repeat(64)), BlobKind.ABILITY_IMPL)
        val implStr = stubBlob(Hash("b".repeat(64)), BlobKind.ABILITY_IMPL)
        val abilityHash = stubBlob(Hash("c".repeat(64)), BlobKind.ABILITY)
        store.putAbilityImpl(implInt, abilityHash, "Int", null)
        store.putAbilityImpl(implStr, abilityHash, "Str", null)

        assertEquals(implInt, store.getAbilityImplForType(abilityHash, "Int"))
        assertEquals(implStr, store.getAbilityImplForType(abilityHash, "Str"))
        assertNull(store.getAbilityImplForType(abilityHash, "Bool"))
    }

    @Test
    fun `putEffectPerformer and getEffectPerformers`() {
        val perfHash = stubBlob(Hash("a".repeat(64)), BlobKind.PERFORMER)
        val effectHash = stubBlob(Hash("b".repeat(64)), BlobKind.EFFECT)
        store.putEffectPerformer(perfHash, effectHash, "LocalConsole", null)

        val perfs = store.getEffectPerformers(effectHash)
        assertEquals(1, perfs.size)
        assertEquals("LocalConsole", perfs[0].performerName)
    }

    // ====================================================================
    // USAGES (LSP cross-file find-references)
    // ====================================================================

    @Test
    fun `putUsage and getUsages`() {
        val hash = stubBlob(Hash("a".repeat(64)))
        store.putUsage(hash, "src/main.mz", 10, 5, 10, 8)
        store.putUsage(hash, "src/utils.mz", 20, 1, 20, 4)

        val refs = store.getUsages(hash)
        assertEquals(2, refs.size)
    }

    @Test
    fun `clearUsagesForFile removes only that file`() {
        val hash = stubBlob(Hash("a".repeat(64)))
        store.putUsage(hash, "src/main.mz", 1, 1, 1, 5)
        store.putUsage(hash, "src/utils.mz", 2, 1, 2, 5)

        store.clearUsagesForFile("src/main.mz")

        val refs = store.getUsages(hash)
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
        assertEquals(SQLiteMezzeStore.SCHEMA_VERSION.toString(), store.getMetadata("schema_version"))
    }

    // ====================================================================
    // TYPED MODULES
    // ====================================================================

    @Test
    fun `putTypedModule and getTypedModule round-trip`() {
        store.putModule("myapp::utils", "u.mz", "abc123")
        val blob = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        store.putTypedModule("myapp::utils", "abc123", blob)
        val got = store.getTypedModule("myapp::utils", "abc123")
        assertNotNull(got)
        assertArrayEquals(blob, got!!)
    }

    @Test
    fun `getTypedModule returns null when missing`() {
        assertNull(store.getTypedModule("nope", "nope"))
    }

    @Test
    fun `same path different sourceHash coexist`() {
        store.putModule("myapp::utils", "u.mz", "v1")
        store.putTypedModule("myapp::utils", "v1", byteArrayOf(0x01))
        store.putTypedModule("myapp::utils", "v2", byteArrayOf(0x02))
        assertArrayEquals(byteArrayOf(0x01), store.getTypedModule("myapp::utils", "v1")!!)
        assertArrayEquals(byteArrayOf(0x02), store.getTypedModule("myapp::utils", "v2")!!)
    }

    @Test
    fun `clearTypedModulesForPath drops all versions`() {
        store.putModule("myapp::utils", "u.mz", "v1")
        store.putModule("myapp::other", "o.mz", "v1")
        store.putTypedModule("myapp::utils", "v1", byteArrayOf(0x01))
        store.putTypedModule("myapp::utils", "v2", byteArrayOf(0x02))
        store.putTypedModule("myapp::other", "v1", byteArrayOf(0x03))
        store.clearTypedModulesForPath("myapp::utils")
        assertNull(store.getTypedModule("myapp::utils", "v1"))
        assertNull(store.getTypedModule("myapp::utils", "v2"))
        assertArrayEquals(byteArrayOf(0x03), store.getTypedModule("myapp::other", "v1")!!)
    }

    @Test
    fun `putTypedModule is idempotent`() {
        store.putModule("p", "p.mz", "h")
        val blob = byteArrayOf(0x05, 0x06)
        store.putTypedModule("p", "h", blob)
        store.putTypedModule("p", "h", blob)
        assertArrayEquals(blob, store.getTypedModule("p", "h")!!)
    }

    // ====================================================================
    // TRANSACTIONS
    // ====================================================================

    @Test
    fun `transaction commits on success`() {
        val hash = Hash("a".repeat(64))
        store.transaction {
            store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
            store.putModule("mod", "m.mz", "h")
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
            assertTrue(com.mezze.language.store.ProjectLayout.srcFile(tempDir).exists())

            // Reopen and verify data persists
            val reopened = SQLiteMezzeStore.forProject(tempDir)
            assertTrue(reopened.blobExists(hash))
            assertArrayEquals("ir".toByteArray(), reopened.getIR(hash))
            reopened.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * #302: concurrent reads on a single SQLiteMezzeStore must not throw
     * "ResultSet already requested" — a sqlite-jdbc error that fires when
     * two threads share the same cached PreparedStatement.
     *
     * This reproduces the LSP failure mode: many `textDocument/hover`
     * requests arrive in parallel and each does its own getUsages /
     * findDeclByHash / getModule call.
     */
    @Test
    fun `concurrent reads don't throw ResultSet already requested`() {
        val targetHash = stubBlob(Hash("c".repeat(64)))
        store.putModule("std::test", "/tmp/test.mz", "h1")
        store.registerName("std::test", "thing", targetHash)
        store.putUsage(targetHash, "/tmp/a.mz", 1, 0, 1, 5)
        store.putUsage(targetHash, "/tmp/b.mz", 2, 0, 2, 5)

        val threads = 8
        val iterations = 50
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val latch = java.util.concurrent.CountDownLatch(threads)
        try {
            for (t in 0 until threads) {
                executor.submit {
                    try {
                        repeat(iterations) {
                            store.getUsages(targetHash)
                            store.resolveName("std::test", "thing")
                            store.getModule("std::test")
                            store.blobExists(targetHash)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS),
                "concurrent reads timed out")
        } finally {
            executor.shutdown()
        }
        if (errors.isNotEmpty()) {
            val first = errors.first()
            fail<Unit>("concurrent reads raised ${errors.size} error(s); first: $first")
        }
    }

    // ====================================================================
    // IMPORTS
    // ====================================================================

    @Test
    fun `putImport with null targetHash writes NULL not empty string`() {
        // Regression: schema used to declare `target_hash NOT NULL` while the API
        // accepted `Hash?`. `putImport` wrote "" for null, which external SQL
        // tools couldn't distinguish from a real empty hash. Now: schema allows
        // NULL, and unresolved imports write a real SQL NULL.
        store.putModule("m", "/tmp/m.mz", "h")
        store.putImport(
            modulePath = "m",
            localName = "foo",
            targetModule = "other",
            targetName = "foo",
            targetHash = null,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
        )

        // API round-trip: null in → null out.
        val rows = store.getImportsForModule("m")
        assertEquals(1, rows.size)
        assertNull(rows[0].targetHash)

        // Direct SQL: column is actually NULL, not an empty string.
        store.executeRaw { conn ->
            conn.prepareStatement(
                "SELECT target_hash FROM imports WHERE module_path = 'm' AND local_name = 'foo'"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val raw = rs.getString("target_hash")
                    assertNull(raw, "Expected SQL NULL, got '$raw'")
                    assertTrue(rs.wasNull(), "Expected wasNull() = true")
                }
            }
        }
    }

    @Test
    fun `putImport with concrete targetHash round-trips`() {
        store.putModule("m", "/tmp/m.mz", "h")
        val hash = Hash("a".repeat(64))
        store.putBlob(hash, BlobKind.FUNCTION, Visibility.PUBLIC, null, null)
        store.putImport(
            modulePath = "m",
            localName = "bar",
            targetModule = "other",
            targetName = "bar",
            targetHash = hash,
            startLine = 1, startCol = 1, endLine = 1, endCol = 10,
        )
        val rows = store.getImportsForModule("m")
        assertEquals(1, rows.size)
        assertEquals(hash, rows[0].targetHash)
    }

    // ====================================================================
    // TRANSACTIONS / L1 CACHE
    // ====================================================================

    @Test
    fun `transaction rollback restores L1 cache state`() {
        // Regression: writes during a transaction update L1 immediately. If
        // the transaction rolls back (throws), L1 used to retain the rolled-
        // back entries — subsequent reads saw ghost data. Fix: snapshot L1
        // on entry, restore on rollback.
        val existingHash = Hash("1".repeat(64))
        val ghostHash = Hash("2".repeat(64))
        store.putBlob(existingHash, BlobKind.FUNCTION, Visibility.PUBLIC, null, "real-ir".toByteArray())
        // Prime L1 by reading.
        assertNotNull(store.getBlob(existingHash))

        assertThrows<RuntimeException> {
            store.transaction {
                // Write a ghost blob inside the transaction; L1 will pick it up.
                store.putBlob(ghostHash, BlobKind.FUNCTION, Visibility.PUBLIC, null, "ghost-ir".toByteArray())
                throw RuntimeException("simulated typecheck failure")
            }
        }

        // After rollback: the ghost blob must not be visible via any read path.
        assertNull(store.getBlob(ghostHash),
            "L1 retained the rolled-back blob — subsequent reads see ghost data.")
        assertFalse(store.blobExists(ghostHash))
        assertNull(store.getIR(ghostHash))

        // The pre-existing blob is still there.
        assertNotNull(store.getBlob(existingHash))
        assertArrayEquals("real-ir".toByteArray(), store.getIR(existingHash))
    }
}
