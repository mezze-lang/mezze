package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.ast.*
import com.mezze.language.typedast.TypedLetDecl
import java.io.File
import java.security.MessageDigest

/**
 * Handle `mez build` — compile project and produce artifacts.
 *
 * Flow:
 * 1. Read Mezze.toml → find app entry file
 * 2. Open .mz/cache.sqlite, compile (same as mez dev)
 * 3. Scatter results into store
 * 4. Compute transitive closure from entry hash
 * 5. Create minimal blobs.sqlite with only reachable IR
 * 6. Package into JAR (future: full fat JAR with Truffle runtime)
 */
class BuildCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("Error: No Mezze.toml found in current directory")
            println("Run 'mez init' to create a new project.")
            return
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), mezzeToml.readText())
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            return
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            println("Error: No [[app]] entries in Mezze.toml")
            return
        }

        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--app") + 1)?.takeIf { !it.startsWith("--") }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName } ?: run {
                println("Error: App '$appName' not found. Available: ${manifest.app.map { it.name }}")
                return
            }
        } else {
            manifest.app.first()
        }

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            println("Error: App file not found: ${appFile.absolutePath}")
            return
        }

        val store = SQLiteMezzeStore.forProject(currentDir)

        try {
            val startTime = System.nanoTime()

            // 1. Compile
            val source = appFile.readText()
            val sourceHash = sha256(source)
            val modulePath = manifest.`package`.name

            val parser = MezzeParser()
            val ast = try {
                parser.parseParsedModule(source, file = appFile.name)
            } catch (e: com.mezze.language.parser.ParseException) {
                println("Parse Error: ${e.message}")
                kotlin.system.exitProcess(3)
            }

            val typeChecker = TypeChecker()
            val typeCheckResult = try {
                typeChecker.typeCheck(ast, currentDir.toPath())
            } catch (e: TypeCheckError) {
                println(ErrorFormatter.format(e, source))
                kotlin.system.exitProcess(4)
            }

            // 2. Scatter into store
            StorePopulator.populate(store, typeCheckResult, modulePath, appFile.absolutePath, sourceHash)

            // 2b. Load stdlib FunctionIRs into store so transitive closure includes them
            try {
                val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
                val stdlibFunctions = precompiled.preloweredFunctions
                println("Loading ${stdlibFunctions.size} stdlib functions into store...")
                store.transaction {
                    for ((hash, fnIR) in stdlibFunctions) {
                        val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fnIR)
                        store.putBlob(
                            com.mezze.language.typedast.Hash(hash),
                            com.mezze.language.codebase.BlobKind.FUNCTION,
                            com.mezze.language.codebase.Visibility.PUBLIC,
                            null,
                            irBytes
                        )
                        // Record edges so transitive closure works
                        val fromHash = com.mezze.language.typedast.Hash(hash)
                        collectEdgesFromIR(fnIR, store, fromHash)
                    }
                }
            } catch (e: Exception) {
                System.err.println("Warning: Could not load stdlib into store: ${e.message}")
            }

            // 3. Validate entry function
            val entryName = appConfig.entry
            val mainBinding = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>()
                ?.find { it.name == entryName }

            if (mainBinding == null) {
                println("Error: App must define a '$entryName' function with type {} -> {}")
                kotlin.system.exitProcess(4)
            }

            // 4. Find entry hash and compute transitive closure
            val entryHash = store.resolveName(modulePath, entryName)
                ?: store.resolveName(modulePath, "__entry__")

            if (entryHash == null) {
                println("Error: Could not resolve entry point hash for '$entryName'")
                kotlin.system.exitProcess(1)
            }

            val reachable = store.getTransitiveClosure(entryHash)
            println("Compiled: ${reachable.size} definitions reachable from entry point")

            // 5. Create target directory and minimal blobs.sqlite
            val targetDir = File(currentDir, "target")
            targetDir.mkdirs()

            val outputJarName = if (manifest.app.size == 1) "${manifest.`package`.name}" else appConfig.name
            val blobsFile = File(targetDir, "$outputJarName.blobs.sqlite")

            createMinimalBlobsDb(store, reachable, entryHash, blobsFile)

            val buildTime = (System.nanoTime() - startTime) / 1_000_000

            println()
            println("Build complete: ${blobsFile.name} (${blobsFile.length() / 1024}KB)")
            println("Entry: $entryHash")
            println("Time: ${buildTime}ms")

        } catch (e: Exception) {
            println("Error: ${e.message}")
            kotlin.system.exitProcess(1)
        } finally {
            store.close()
        }
    }

    /**
     * Create a minimal blobs.sqlite containing only reachable IR blobs.
     * This is what goes into the JAR for production deployment.
     */
    private fun createMinimalBlobsDb(
        sourceStore: SQLiteMezzeStore,
        reachable: Set<com.mezze.language.typedast.Hash>,
        entryHash: com.mezze.language.typedast.Hash,
        outputFile: File
    ) {
        if (outputFile.exists()) outputFile.delete()

        // Create file-based store directly
        val targetStore = SQLiteMezzeStore.forProject(outputFile.parentFile)
        // Actually we need a fresh DB at the output path, not .mz/cache.sqlite
        // Use a direct connection
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite:${outputFile.absolutePath}")
        conn.createStatement().use { s ->
            s.executeUpdate("PRAGMA journal_mode=WAL")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS blobs (hash TEXT PRIMARY KEY, ir BLOB NOT NULL) WITHOUT ROWID")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS names (module TEXT NOT NULL, name TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY (module, name)) WITHOUT ROWID")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
        }

        try {
            conn.autoCommit = false

            val insertBlob = conn.prepareStatement("INSERT OR REPLACE INTO blobs (hash, ir) VALUES (?, ?)")
            val insertName = conn.prepareStatement("INSERT OR REPLACE INTO names (module, name, hash) VALUES (?, ?, ?)")

            var blobCount = 0
            for (hash in reachable) {
                val irBytes = sourceStore.getIR(hash) ?: continue
                insertBlob.setString(1, hash.hex)
                insertBlob.setBytes(2, irBytes)
                insertBlob.executeUpdate()
                blobCount++
            }

            for (hash in reachable) {
                val names = sourceStore.getNamesForHash(hash)
                for (name in names) {
                    insertName.setString(1, name.module)
                    insertName.setString(2, name.name)
                    insertName.setString(3, hash.hex)
                    insertName.executeUpdate()
                }
            }

            conn.createStatement().use { s ->
                s.executeUpdate("INSERT OR REPLACE INTO metadata (key, value) VALUES ('entry_hash', '${entryHash.hex}')")
            }

            conn.commit()
            println("Created blobs.sqlite with $blobCount blobs")

        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.close()
        }
    }

    /** Record dependency edges from a FunctionIR body into the store. */
    private fun collectEdgesFromIR(
        fnIR: com.mezze.language.truffleir.FunctionIR,
        store: SQLiteMezzeStore,
        fromHash: com.mezze.language.typedast.Hash
    ) {
        fun walk(node: com.mezze.language.truffleir.NodeIR) {
            when (node) {
                is com.mezze.language.truffleir.CallDirectIR -> {
                    try { store.addEdge(fromHash, node.targetHash, com.mezze.language.codebase.EdgeKind.CALLS) } catch (_: Exception) {}
                    walk(node.arg)
                }
                is com.mezze.language.truffleir.MakeLambdaIR -> {
                    try { store.addEdge(fromHash, node.functionHash, com.mezze.language.codebase.EdgeKind.CALLS) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.HandleIR -> {
                    walk(node.body)
                    for (h in node.handlers) {
                        try { store.addEdge(fromHash, h.handlerHash, com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                        try { store.addEdge(fromHash, h.effectHash, com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                    }
                }
                is com.mezze.language.truffleir.PerformOpIR -> {
                    try { store.addEdge(fromHash, node.effectHash, com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                    walk(node.arg)
                }
                is com.mezze.language.truffleir.BlockIR -> {
                    for (s in node.stmts) when (s) {
                        is com.mezze.language.truffleir.StmtIR.Let -> walk(s.value)
                        is com.mezze.language.truffleir.StmtIR.Expr -> walk(s.node)
                    }
                    walk(node.result)
                }
                is com.mezze.language.truffleir.CallLocalIR -> walk(node.arg)
                is com.mezze.language.truffleir.CallNativeIR -> walk(node.arg)
                is com.mezze.language.truffleir.CallIndirectIR -> { walk(node.function); walk(node.arg) }
                is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { walk(it.value) }
                is com.mezze.language.truffleir.ReadFieldIR -> walk(node.record)
                is com.mezze.language.truffleir.RecordUpdateIR -> { walk(node.record); node.updates.forEach { walk(it.value) } }
                is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { walk(it) }
                is com.mezze.language.truffleir.MatchIR -> {
                    walk(node.scrutinee)
                    for (c in node.cases) { walk(c.body); c.guard?.let { walk(it) } }
                }
                is com.mezze.language.truffleir.IfIR -> { walk(node.cond); walk(node.thenBr); walk(node.elseBr) }
                is com.mezze.language.truffleir.WriteLocalIR -> walk(node.value)
                is com.mezze.language.truffleir.InlineBinaryOpIR -> { walk(node.left); walk(node.right) }
                is com.mezze.language.truffleir.InterpolatedStringIR -> {
                    for (p in node.parts) if (p is com.mezze.language.truffleir.StringPartIR.Expr) walk(p.node)
                }
                is com.mezze.language.truffleir.MakeSeqIR -> {
                    for (e in node.elements) when (e) {
                        is com.mezze.language.truffleir.SeqElementIR.Regular -> walk(e.node)
                        is com.mezze.language.truffleir.SeqElementIR.Spread -> walk(e.node)
                    }
                }
                is com.mezze.language.truffleir.SeqGetIR -> { walk(node.seq); walk(node.index) }
                is com.mezze.language.truffleir.DictMethodCallIR -> walk(node.arg)
                else -> {} // leaf nodes
            }
        }
        walk(fnIR.body)
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Legacy execute with JAR callback — kept for backward compatibility with Main.kt.
     */
    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        createJarFunction: (File, File, String, com.mezze.language.typechecker.TypeCheckResult) -> Unit
    ) {
        // Delegate to new implementation
        execute(args, currentDir)
    }
}
