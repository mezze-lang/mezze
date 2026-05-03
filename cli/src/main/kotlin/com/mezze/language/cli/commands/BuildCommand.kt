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

        val store = SQLiteMezzeStore.openProject(currentDir)

        try {
            val startTime = System.nanoTime()
            val timing = System.getenv("MEZZE_TIMING") != null
            fun ms() = (System.nanoTime() - startTime) / 1_000_000

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
            if (timing) System.err.println("[${ms()}ms] parsed")

            val typeChecker = TypeChecker()
            val typeCheckResult = try {
                typeChecker.typeCheck(ast, currentDir.toPath(), enablePrelude = true)
            } catch (e: TypeCheckError) {
                println(ErrorFormatter.format(e, source))
                kotlin.system.exitProcess(4)
            }
            if (timing) System.err.println("[${ms()}ms] typechecked + lowered (${typeCheckResult.loweredFunctions.size} fns)")

            // 2. Scatter into store. populate() returns a delta — only blobs
            //    whose hashes aren't already present get written.
            val depParsedModules = com.mezze.language.typechecker.ModuleResolver.getAllParsedModules()
            StorePopulator.populate(
                store, typeCheckResult, modulePath,
                appFile.absolutePath, sourceHash,
                entrySource = source,
                parsedModule = ast,
                loadedParsedModules = depParsedModules
            )

            // Store the user's lowered function manifest for standalone JAR loading.
            // (StorePopulator already stored the FunctionIRs by hash — manifest links
            // app -> ordered hashes.)
            store.transaction {
                store.putApp(appConfig.name, appConfig.entry, modulePath, appFile.absolutePath)
                store.setAppFunctionHashes(appConfig.name, typeCheckResult.loweredFunctions.map { it.hash })
                store.setAppPerformerRecords(appConfig.name, typeCheckResult.performerRecords)
            }

            // 2b. Load stdlib FunctionIRs into store — but skip ones already present
            //     (typically all of them after `mez dev` has run).
            try {
                val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
                val stdlibFunctions = precompiled.preloweredFunctions
                val candidates = stdlibFunctions.keys.map { com.mezze.language.typedast.Hash(it) }
                val present = store.blobsExist(candidates)
                val missing = stdlibFunctions.filter { (hash, _) -> com.mezze.language.typedast.Hash(hash) !in present }
                if (missing.isNotEmpty()) {
                    println("Loading ${missing.size} stdlib functions into store (${present.size} already cached)...")
                    store.transaction {
                        for ((hash, fnIR) in missing) {
                            val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fnIR)
                            val h = com.mezze.language.typedast.Hash(hash)
                            store.putBlob(h, com.mezze.language.codebase.BlobKind.FUNCTION,
                                com.mezze.language.codebase.Visibility.PUBLIC, null, irBytes)
                            collectEdgesFromIR(fnIR, store, h)
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Warning: Could not load stdlib into store: ${e.message}")
            }
            if (timing) System.err.println("[${ms()}ms] stdlib seed checked")

            // 3. Validate entry function — must exist and have shape `{} -> {} <*>`
            //    (effects allowed, ability constraints allowed, dict params hidden).
            val entryName = appConfig.entry
            val mainBinding = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>()
                ?.find { it.name == entryName }

            if (mainBinding == null) {
                println("Error: App must define a '$entryName' function with type {} -> {} <*>")
                kotlin.system.exitProcess(4)
            }
            EntryPointCheck.errorMessage(EntryPointCheck.check(mainBinding!!), entryName)?.let { msg ->
                println("Error: $msg")
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

            createMinimalBlobsDb(store, reachable, entryHash, blobsFile, entryName)
            if (timing) System.err.println("[${ms()}ms] blobs.sqlite created (reachable subset)")

            // The standalone JAR's blobs.sqlite needs every blob the program
            // could ever look up at runtime — including stdlib hashes that
            // weren't statically reachable (ability dispatch, effect performers
            // resolved at runtime, etc.). createMinimalBlobsDb wrote the
            // reachable subset; pad it with any remaining stdlib + user blobs
            // that aren't already present in blobs.sqlite.
            val allBlobsConn = java.sql.DriverManager.getConnection("jdbc:sqlite:${blobsFile.absolutePath}")
            try {
                allBlobsConn.autoCommit = false
                val existing = HashSet<String>()
                allBlobsConn.createStatement().use { s ->
                    s.executeQuery("SELECT hash FROM blobs").use { rs ->
                        while (rs.next()) existing.add(rs.getString(1))
                    }
                }
                val insertBlob = allBlobsConn.prepareStatement("INSERT OR IGNORE INTO blobs (hash, ir) VALUES (?, ?)")
                var stdlibAdded = 0
                for ((hash, fnIR) in com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions) {
                    if (hash in existing) continue
                    val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fnIR)
                    insertBlob.setString(1, hash)
                    insertBlob.setBytes(2, irBytes)
                    insertBlob.executeUpdate()
                    stdlibAdded++
                }
                for (fn in typeCheckResult.loweredFunctions) {
                    if (fn.hash.hex in existing) continue
                    val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fn)
                    insertBlob.setString(1, fn.hash.hex)
                    insertBlob.setBytes(2, irBytes)
                    insertBlob.executeUpdate()
                }
                if (stdlibAdded > 0) println("Padded blobs.sqlite with $stdlibAdded extra stdlib blobs (out of ${com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions.size})")

                // Store user function manifest in app_functions table (ordered)
                allBlobsConn.prepareStatement(
                    "INSERT OR REPLACE INTO app_functions (app_name, hash, ord) VALUES (?, ?, ?)"
                ).use { stmt ->
                    typeCheckResult.loweredFunctions.forEachIndexed { i, fn ->
                        stmt.setString(1, appConfig.name)
                        stmt.setString(2, fn.hash.hex)
                        stmt.setInt(3, i)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }

                // Store performer records in app_performers table
                val performers = typeCheckResult.performerRecords
                if (performers.isNotEmpty()) {
                    allBlobsConn.prepareStatement(
                        "INSERT OR REPLACE INTO app_performers (app_name, record_idx, position, hash) VALUES (?, ?, ?, ?)"
                    ).use { stmt ->
                        performers.forEachIndexed { recordIdx, record ->
                            record.forEachIndexed { pos, hash ->
                                stmt.setString(1, appConfig.name)
                                stmt.setInt(2, recordIdx)
                                stmt.setInt(3, pos)
                                stmt.setString(4, hash)
                                stmt.addBatch()
                            }
                        }
                        stmt.executeBatch()
                    }
                }

                // App name needed by the standalone JAR loader at boot time.
                allBlobsConn.createStatement().use { s ->
                    s.executeUpdate("INSERT OR REPLACE INTO metadata (key, value) VALUES ('app_name', '${appConfig.name.replace("'", "''")}')")
                }

                allBlobsConn.commit()
            } finally {
                allBlobsConn.close()
            }
            if (timing) System.err.println("[${ms()}ms] blobs.sqlite padded + manifest written")

            // 6. Create runnable JAR
            val jarFile = File(targetDir, "$outputJarName.jar")
            createStandaloneJar(blobsFile, jarFile)
            if (timing) System.err.println("[${ms()}ms] standalone JAR packed")

            // 7. Generate launcher scripts (Unix sh + Windows bat).
            //    These pre-set production-grade JVM flags so users don't have
            //    to remember them. They invoke `java -jar <app>.jar` with:
            //      -XX:+UseZGC -XX:+ZGenerational
            //          → <2ms GC pauses (vs G1's ~200ms). Functional code
            //            generates a lot of short-lived values; generational
            //            ZGC is built for exactly that.
            //      -Xmx4g
            //          → sane default heap; user can override.
            //    Users can still run the bare jar with `java -jar` if they
            //    want different flags.
            val unixLauncher = File(targetDir, outputJarName)
            writeUnixLauncher(unixLauncher, jarFile.name)
            val winLauncher = File(targetDir, "$outputJarName.bat")
            writeWindowsLauncher(winLauncher, jarFile.name)
            if (timing) System.err.println("[${ms()}ms] launchers written")

            val buildTime = (System.nanoTime() - startTime) / 1_000_000

            println()
            println("Build complete: ${jarFile.name} (${jarFile.length() / 1024}KB)")
            println("Run: ./target/${unixLauncher.name}     (Unix — preconfigured)")
            println("     java -jar target/${jarFile.name}  (any JVM, default settings)")
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
        outputFile: File,
        entryName: String = "main"
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
            // App manifest — what to load and run when this JAR boots.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS app_functions (app_name TEXT NOT NULL, hash TEXT NOT NULL, ord INTEGER NOT NULL, PRIMARY KEY (app_name, hash)) WITHOUT ROWID")
            s.executeUpdate("CREATE TABLE IF NOT EXISTS app_performers (app_name TEXT NOT NULL, record_idx INTEGER NOT NULL, position INTEGER NOT NULL, hash TEXT NOT NULL, PRIMARY KEY (app_name, record_idx, position)) WITHOUT ROWID")
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
                s.executeUpdate("INSERT OR REPLACE INTO metadata (key, value) VALUES ('entry_name', '$entryName')")
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

    /**
     * Write a Unix shell launcher that invokes `java` with production-grade
     * flags. Sits next to the jar; user runs `./target/<app>` instead of
     * remembering the right -XX flags themselves.
     *
     * Flags chosen for FP-style workloads (high allocation, short-lived
     * values, recursive call patterns):
     *   -XX:+UseZGC -XX:+ZGenerational  → <2ms GC pauses
     *   -Xmx4g                          → sane default heap
     *
     * Users override with env var MEZZE_JAVA_OPTS for one-off tuning.
     */
    private fun writeUnixLauncher(launcher: File, jarName: String) {
        val script = """
            #!/usr/bin/env sh
            # Auto-generated by `mez build`. Production launcher with sane JVM flags.
            # To override flags for a single run:
            #   MEZZE_JAVA_OPTS='-Xmx2g -XX:+UseG1GC' ./<app>
            # To override the java binary:
            #   MEZZE_JAVA=/path/to/java ./<app>

            set -e
            DIR="$(cd "$(dirname "$0")" && pwd)"
            JAR="${'$'}DIR/$jarName"

            if [ ! -f "${'$'}JAR" ]; then
                echo "Error: ${'$'}JAR not found" >&2
                exit 1
            fi

            JAVA="${'$'}{MEZZE_JAVA:-java}"
            DEFAULT_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xmx4g"
            OPTS="${'$'}{MEZZE_JAVA_OPTS:-${'$'}DEFAULT_OPTS}"

            exec "${'$'}JAVA" ${'$'}OPTS -jar "${'$'}JAR" "${'$'}@"
        """.trimIndent() + "\n"
        launcher.writeText(script)
        launcher.setExecutable(true, false)
    }

    /** Windows .bat counterpart of [writeUnixLauncher]. */
    private fun writeWindowsLauncher(launcher: File, jarName: String) {
        val script = """
            @echo off
            REM Auto-generated by `mez build`. Production launcher with sane JVM flags.
            REM Override:
            REM   set MEZZE_JAVA_OPTS=-Xmx2g
            REM   <app>.bat
            setlocal
            set "DIR=%~dp0"
            set "JAR=%DIR%$jarName"
            if not exist "%JAR%" (
                echo Error: %JAR% not found 1>&2
                exit /b 1
            )
            if "%MEZZE_JAVA%"=="" set "MEZZE_JAVA=java"
            if "%MEZZE_JAVA_OPTS%"=="" set "MEZZE_JAVA_OPTS=-XX:+UseZGC -XX:+ZGenerational -Xmx4g"
            "%MEZZE_JAVA%" %MEZZE_JAVA_OPTS% -jar "%JAR%" %*
        """.trimIndent() + "\r\n"
        launcher.writeText(script)
    }

    /**
     * Create a standalone JAR that embeds blobs.sqlite + Truffle runtime.
     * Uses the installed mez CLI's lib JARs as the runtime base.
     */
    private fun createStandaloneJar(blobsFile: File, outputJar: File) {
        println("Creating standalone JAR...")

        // Find the runtime JAR (mezze-lang.jar) — this is the single shadow JAR
        // that contains all of mezze + Truffle + dependencies.
        val cliLocation = javaClass.protectionDomain.codeSource?.location
        val cliJarOrClassDir = if (cliLocation != null) File(cliLocation.toURI()) else null
        val libDir = findLibDir(cliJarOrClassDir)

        if (libDir == null || !libDir.exists()) {
            println("Warning: Could not find runtime JARs. Producing blobs.sqlite only.")
            return
        }

        val libJars = libDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
        if (libJars.isEmpty()) {
            println("Warning: No JARs found in ${libDir.absolutePath}")
            return
        }
        if (libJars.size > 1) {
            // We expect a single shadow JAR. If more, fall back to the legacy extract+repack path.
            println("Warning: ${libJars.size} JARs in $libDir; expected 1 shadow JAR. Falling back to legacy packing.")
            createStandaloneJarLegacy(libJars, blobsFile, outputJar)
            return
        }

        val sourceJar = libJars.first()

        // Stream entries from the source JAR into the output JAR using
        // `copyRawEntries` — no extract, no recompression. Just byte copy.
        // Then append blobs.sqlite + a fresh MANIFEST pointing at StandaloneMain.
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(
            java.io.FileOutputStream(outputJar).buffered(64 * 1024)
        ).use { out ->
            @Suppress("DEPRECATION")
            val src = org.apache.commons.compress.archivers.zip.ZipFile(sourceJar)
            try {
                src.copyRawEntries(out, org.apache.commons.compress.archivers.zip.ZipArchiveEntryPredicate { e ->
                    val name = e.name
                    when {
                        name == "META-INF/MANIFEST.MF" -> false  // we'll write our own
                        name.startsWith("META-INF/") &&
                            (name.endsWith(".SF") || name.endsWith(".RSA") ||
                             name.endsWith(".DSA") || name.endsWith(".EC")) -> false
                        else -> true
                    }
                })
            } finally {
                src.close()
            }

            // Append the user's blobs.sqlite under the resource path StandaloneHelper expects.
            val blobEntry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("com/mezze/standalone/blobs.sqlite")
            out.putArchiveEntry(blobEntry)
            java.io.FileInputStream(blobsFile).buffered().use { it.copyTo(out) }
            out.closeArchiveEntry()

            // Write a fresh MANIFEST.MF pointing at StandaloneMain.
            val manEntry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("META-INF/MANIFEST.MF")
            out.putArchiveEntry(manEntry)
            val manifest = "Manifest-Version: 1.0\nMain-Class: com.mezze.language.cli.standalone.StandaloneMain\n\n"
            out.write(manifest.toByteArray())
            out.closeArchiveEntry()
        }

        println("Created ${outputJar.name} (${outputJar.length() / 1024}KB)")
    }

    /**
     * Legacy extract+repack path. Used only when the lib dir contains multiple
     * JARs (development / non-shadow builds). Slow (~7s) but supports merging.
     */
    private fun createStandaloneJarLegacy(libJars: List<File>, blobsFile: File, outputJar: File) {
        val tempDir = kotlin.io.path.createTempDirectory("mez-build").toFile()
        try {
            for (jar in libJars) {
                ProcessBuilder("jar", "xf", jar.absolutePath)
                    .directory(tempDir).redirectErrorStream(true).start().waitFor()
            }
            File(tempDir, "META-INF").listFiles()?.filter {
                it.name.endsWith(".SF") || it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")
            }?.forEach { it.delete() }
            val resourceDir = File(tempDir, "com/mezze/standalone")
            resourceDir.mkdirs()
            blobsFile.copyTo(File(resourceDir, "blobs.sqlite"), overwrite = true)
            val metaInf = File(tempDir, "META-INF")
            metaInf.mkdirs()
            File(metaInf, "MANIFEST.MF").writeText(
                "Manifest-Version: 1.0\nMain-Class: com.mezze.language.cli.standalone.StandaloneMain\n\n"
            )
            val manifest = File(metaInf, "MANIFEST.MF")
            val jarProc = ProcessBuilder("jar", "cfm", outputJar.absolutePath,
                manifest.absolutePath, "-C", tempDir.absolutePath, ".")
                .directory(tempDir).redirectErrorStream(true).start()
            if (jarProc.waitFor() != 0) {
                println("Warning: jar creation failed")
                return
            }
            println("Created ${outputJar.name} (${outputJar.length() / 1024}KB)")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun findLibDir(startDir: File?): File? {
        // 1. ~/.mezze/lib/ (installed via install.sh)
        val mezzeHome = File(System.getProperty("user.home"), ".mezze/lib")
        if (mezzeHome.exists() && mezzeHome.isDirectory) return mezzeHome

        // 2. Walk up from the binary/class location looking for lib/
        var current = startDir
        while (current != null) {
            val lib = File(current, "lib")
            if (lib.exists() && lib.isDirectory) return lib
            current = current.parentFile
        }

        // 3. Development: cli/build/libs/ relative to project root
        val projectRoot = System.getProperty("mezze.project.root")
        if (projectRoot != null) {
            val devLibs = File(projectRoot, "cli/build/libs")
            if (devLibs.exists()) return devLibs
        }

        return null
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
                is com.mezze.language.truffleir.ReadContextIR -> {
                    try { store.addEdge(fromHash, com.mezze.language.typedast.Hash(node.name), com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.ReadContextFnIR -> {
                    try { store.addEdge(fromHash, com.mezze.language.typedast.Hash(node.name), com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                }
                else -> {} // leaf nodes
            }
        }
        walk(fnIR.body)
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

}
