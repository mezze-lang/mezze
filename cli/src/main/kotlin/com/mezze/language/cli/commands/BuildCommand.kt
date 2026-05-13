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

        // Output directory precedence (highest first):
        //   1. CLI flag:    --out=<path> or -o <path>
        //   2. Mezze.toml:  [build] out = "..."
        //   3. Default:     BuildConfig.DEFAULT_OUT (".build")
        val cliOut = args.find { it.startsWith("--out=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--out") + 1)?.takeIf { !it.startsWith("--") }
            ?: args.getOrNull(args.indexOf("-o") + 1)?.takeIf { !it.startsWith("--") && !it.startsWith("-o") }
        val outDirName = cliOut ?: manifest.build.out

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
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)

        // Same cache-invalidation gate as mez dev — if this build is run
        // against a project whose .mz/ was last typechecked by an older
        // mez binary, the cached IR has stale hashes and would produce a
        // JAR that crashes at runtime. Nuke and rebuild.
        run {
            val current = "${com.mezze.language.cli.BuildInfo.VERSION}+${com.mezze.language.cli.BuildInfo.GIT_SHA}"
            store.transaction {
                com.mezze.language.codebase.StoreHydration
                    .invalidateOnVersionShift(store, current)
            }
        }

        try {
            val startTime = System.nanoTime()
            val timing = System.getenv("MEZZE_TIMING") != null
            fun ms() = (System.nanoTime() - startTime) / 1_000_000

            // 1. Compile
            val source = appFile.readText()
            val sourceHash = sha256(source)
            val modulePath = manifest.`package`.name

            val parser = MezzeParser()
            // mez build is strict-no-errors: if ANY parse error exists,
            // refuse to write the JAR. But surface ALL parse errors at
            // once via parseWithRecovery — better DX than fix-one-at-a-time.
            val parseResult = parser.parseWithRecovery(source, file = appFile.name)
            if (parseResult.errors.isNotEmpty()) {
                println(ParseErrorFormatter.formatAll(parseResult.errors))
                kotlin.system.exitProcess(3)
            }
            val ast = parseResult.module
            if (timing) System.err.println("[${ms()}ms] parsed")

            val typeChecker = TypeChecker()
            // Use the result-returning lower-level API so we surface ALL
            // collected errors (matching `mez check` + `mez dev` + LSP),
            // not just the first one.
            val rawResult = try {
                typeChecker.checkParsedModule(ast, currentDir.toPath(), enablePrelude = true)
            } catch (e: TypeCheckError) {
                println(ErrorFormatter.format(e, source))
                kotlin.system.exitProcess(4)
            }
            if (rawResult is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure) {
                println(TypeCheckRunner.formatErrors(rawResult, source))
                kotlin.system.exitProcess(4)
            }
            val typeCheckResult = TypeCheckRunner.wrapSuccess(
                rawResult as com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success
            )
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
                val candidates = stdlibFunctions.keys.toList()
                val present = store.blobsExist(candidates)
                val missing = stdlibFunctions.filter { (hash, _) -> hash !in present }
                if (missing.isNotEmpty()) {
                    println("Loading ${missing.size} stdlib functions into store (${present.size} already cached)...")
                    store.transaction {
                        for ((hash, fnIR) in missing) {
                            val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fnIR)
                            store.putBlob(hash, com.mezze.language.codebase.BlobKind.FUNCTION,
                                com.mezze.language.codebase.Visibility.PUBLIC, null, irBytes)
                            collectEdgesFromIR(fnIR, store, hash)
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

            // 5. Resolve output directory + create it.
            //    Layout:
            //      single app:  <out>/<package>.jar + <out>/<package> launcher
            //      multi app:   <out>/<app-name>/<app-name>.jar + ../<app-name>
            //                   (nested so multiple apps don't collide flat)
            val outRoot = File(currentDir, outDirName)
            val targetDir = if (manifest.app.size > 1) File(outRoot, appConfig.name) else outRoot
            targetDir.mkdirs()

            val outputJarName = if (manifest.app.size == 1) "${manifest.`package`.name}" else appConfig.name
            val blobsFile = File(targetDir, "$outputJarName.blobs.sqlite")

            createMinimalBlobsDb(store, reachable, entryHash, blobsFile, entryName)
            if (timing) System.err.println("[${ms()}ms] blobs.sqlite created (reachable subset)")

            // Write the user's lowered functions + ANY hash referenced by
            // them that wasn't in the reachable-closure set. The closure
            // walk uses store edges; impl-op hashes and freshly-emitted
            // user IR aren't always edge-linked at build time, so we top
            // up explicitly. Stdlib functions that *aren't* referenced are
            // omitted — the runtime resolves them through the same store
            // (open blobs.sqlite as MezzeStore at runtime), so unused
            // stdlib doesn't ship. This is the foundation for hash-based
            // remote fetching (#169): the JAR carries exactly what the
            // program reaches, nothing more.
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
                // Write user-emitted IR not already in the closure.
                for (fn in typeCheckResult.loweredFunctions) {
                    if (fn.hash.hex in existing) continue
                    val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fn)
                    insertBlob.setString(1, fn.hash.hex)
                    insertBlob.setBytes(2, irBytes)
                    insertBlob.executeUpdate()
                    existing.add(fn.hash.hex)
                }
                // Top up with stdlib hashes that the user's IR transitively
                // references but that aren't in the closure. PipelineRunner's
                // helper walks user IR + follows stdlib dependency edges —
                // the same closure the runtime computes on cold start.
                val needed = com.mezze.interpreter.compiler.PipelineRunner
                    .collectReferencedHashes(typeCheckResult.loweredFunctions)
                val stdlibFns = com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions
                var stdlibAdded = 0
                for (hash in needed) {
                    if (hash.hex in existing) continue
                    val fnIR = stdlibFns[hash] ?: continue
                    val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fnIR)
                    insertBlob.setString(1, hash.hex)
                    insertBlob.setBytes(2, irBytes)
                    insertBlob.executeUpdate()
                    existing.add(hash.hex)
                    stdlibAdded++
                }
                if (stdlibAdded > 0 && timing) {
                    System.err.println("[${ms()}ms] added $stdlibAdded referenced stdlib blobs")
                }

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

            // 6b. Clean up the intermediate blobs.sqlite — it's already
            //     packed into the jar at META-INF; the file on disk was
            //     just a staging artifact. Same for any sqlite WAL/SHM
            //     sidecars sqlite leaves behind.
            blobsFile.delete()
            File(blobsFile.absolutePath + "-wal").delete()
            File(blobsFile.absolutePath + "-shm").delete()
            File(blobsFile.absolutePath + "-journal").delete()

            // 7. Generate launcher scripts (Unix sh + Windows bat).
            //    These pre-set production-grade JVM flags so users don't have
            //    to remember them. They invoke `java -jar <app>.jar` with:
            //      -XX:+UseZGC
            //          → <2ms GC pauses (vs G1's ~200ms). Functional code
            //            generates a lot of short-lived values; generational
            //            ZGC (default on JDK 24+) is built for exactly that.
            //      -Xmx4g
            //          → sane default heap; user can override.
            //      --enable-native-access=ALL-UNNAMED
            //      --sun-misc-unsafe-memory-access=allow
            //          → silences the JVM warnings that SQLite (JNI load) and
            //            Truffle (sun.misc.Unsafe.objectFieldOffset) trigger
            //            on every startup. Both uses are legitimate.
            //    Users can still run the bare jar with `java -jar` if they
            //    want different flags (will see the warnings, code still works).
            val unixLauncher = File(targetDir, outputJarName)
            writeUnixLauncher(unixLauncher, jarFile.name)
            val winLauncher = File(targetDir, "$outputJarName.bat")
            writeWindowsLauncher(winLauncher, jarFile.name)
            if (timing) System.err.println("[${ms()}ms] launchers written")

            val buildTime = (System.nanoTime() - startTime) / 1_000_000

            // Show paths relative to the project root so the user sees
            // exactly what to type. targetDir is either <out> (single
            // app) or <out>/<app-name> (multi).
            val relTarget = currentDir.toPath().relativize(targetDir.toPath()).toString()
            val launcherRel = "$relTarget/${unixLauncher.name}"
            val jarRel = "$relTarget/${jarFile.name}"

            println()
            println("Build complete: ${jarFile.name} (${jarFile.length() / 1024}KB)")
            println("Output: $relTarget/")
            println("Run: ./$launcherRel     (Unix — preconfigured)")
            println("     java -jar $jarRel  (any JVM, default settings)")
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

        // Open a raw sqlite connection on the output file. We previously
        // called SQLiteMezzeStore.forProject(outputFile.parentFile) here as
        // dead code — it created an unused target/.mz/cache.sqlite as a side
        // effect of the standard "open a project store" path. The actual
        // store we want is a single self-contained file at outputFile, with
        // just the schema this app needs at runtime.
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
     *   -XX:+UseZGC                     → <2ms GC pauses (generational by
     *                                     default on JDK 24+; older flag
     *                                     +ZGenerational was removed in 24).
     *   -Xmx4g                          → sane default heap
     *   --enable-native-access=ALL-...  → silences the SQLite + sun.misc.Unsafe
     *                                     warnings that otherwise print on every
     *                                     JVM start. Truffle and SQLite both use
     *                                     JNI/Unsafe legitimately.
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
            DEFAULT_OPTS="-XX:+UseZGC -Xmx4g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
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
            if "%MEZZE_JAVA_OPTS%"=="" set "MEZZE_JAVA_OPTS=-XX:+UseZGC -Xmx4g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
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

        // Find the runtime JAR(s) by asking the running CLI where its own
        // classes were loaded from. That location IS the source of truth
        // for the bytecode that should be embedded in the user JAR. We
        // never look at `~/.mezze/lib/` directly — system installs are
        // isolated per-mez-binary, not a shared library other invocations
        // consult. (When `~/.mezze/bin/mez` runs, it walks up from its
        // own location and naturally finds `~/.mezze/lib/`. When
        // `cli/build/install/mez/bin/mez` runs, it walks up from there
        // instead. The two never cross.)
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

        // Stream entries from each source JAR into the output JAR using
        // `copyRawEntries` — no extract, no recompression, just byte copy.
        // Tracks seen entry names so when multiple sources contain the same
        // path (rare — e.g. duplicate META-INF/services entries) we skip the
        // later copy. Then append blobs.sqlite + a fresh MANIFEST.
        val seen = HashSet<String>()
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(
            java.io.FileOutputStream(outputJar).buffered(64 * 1024)
        ).use { out ->
            for (sourceJar in libJars) {
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
                            !seen.add(name) -> false  // already copied from an earlier jar
                            else -> true
                        }
                    })
                } finally {
                    src.close()
                }
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
     * Find the directory of runtime JARs that should be embedded into the
     * user's standalone JAR.
     *
     * **Single source of truth**: whatever classpath the *running* mez was
     * launched from. We never look at `~/.mezze/lib/` — that's just where
     * the system-installed mez happens to live; it is NOT a shared library
     * other invocations should consult. Each `mez` binary embeds its own
     * runtime via the location it was launched from.
     *
     * Concretely:
     * - If `startDir` is a JAR file (shadow build, e.g.
     *   `~/.mezze/lib/mezze-lang.jar` when running `~/.mezze/bin/mez`):
     *   return its parent dir, which holds the single shadow JAR.
     * - If `startDir` is a JAR file inside a `lib/` directory of many
     *   sibling JARs (`installDist` layout, e.g.
     *   `cli/build/install/mez/lib/cli-*.jar`): return that `lib/` dir,
     *   which holds the full set of dependency JARs.
     * - If `startDir` is a class directory (running from gradle, IDE, or
     *   tests): walk up looking for a sibling `lib/`, falling back to
     *   `cli/build/libs/` under `mezze.project.root`.
     *
     * Returning null means we genuinely couldn't locate the running CLI's
     * own dependencies — caller emits a warning and produces blobs.sqlite
     * only.
     */
    private fun findLibDir(startDir: File?): File? {
        if (startDir == null) return null

        // Case 1: startDir is the JAR we're running from. Its parent IS
        // the lib dir we want.
        if (startDir.isFile && startDir.extension == "jar") {
            val parent = startDir.parentFile
            if (parent != null && parent.isDirectory) return parent
        }

        // Case 2: startDir is a class dir (or build/classes/...). Walk up
        // looking for a sibling `lib/`.
        var current = startDir
        while (current != null) {
            val lib = File(current, "lib")
            if (lib.exists() && lib.isDirectory) return lib
            current = current.parentFile
        }

        // Case 3: dev fallback — running from gradle test runner where
        // there's no install layout yet. Honor `mezze.project.root` to
        // point at `cli/build/libs/`. Only used for tests.
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
                    node.args.forEach { walk(it) }
                }
                is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                    try { store.addEdge(fromHash, node.targetHash, com.mezze.language.codebase.EdgeKind.CALLS) } catch (_: Exception) {}
                    node.partialFields.forEach { walk(it.value) }
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
                is com.mezze.language.truffleir.CallIndirectScalarIR -> { walk(node.function); node.args.forEach { walk(it) } }
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
                is com.mezze.language.truffleir.ReadGlobalByHashIR -> {
                    try { store.addEdge(fromHash, node.hash, com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> {
                    try { store.addEdge(fromHash, node.hash, com.mezze.language.codebase.EdgeKind.REFERENCES) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.ReadScopeIR -> {} // local scope, not a global ref
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
