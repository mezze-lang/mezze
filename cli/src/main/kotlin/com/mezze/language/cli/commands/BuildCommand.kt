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
            ?: args.indexOf("--app").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                ?.takeIf { !it.startsWith("-") }

        // `--native` / `-n` produces an AOT-compiled standalone executable
        // next to the JAR. Build is slow (~30s quick / ~2min optimized);
        // runtime is fast (no JVM boot, no SQLite, FunctionIRs frozen in
        // the image heap via --initialize-at-build-time).
        val nativeBuild = args.contains("--native") || args.contains("-n")

        // `--optimized` / `-o` switches native-image from quick-build
        // (-Ob, ~30-50s) to optimized (-O2, ~2m) and strips debug symbols.
        // Default is quick-build with `-g` for fast iteration.
        val optimizedBuild = args.contains("--optimized") || args.contains("-o")

        // Output directory precedence (highest first):
        //   1. CLI flag:    --out=<path> or --out <path>
        //   2. Mezze.toml:  [build] out = "..."
        //   3. Default:     BuildConfig.DEFAULT_OUT (".build")
        val cliOut = args.find { it.startsWith("--out=") }?.substringAfter("=")
            ?: args.indexOf("--out").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                ?.takeIf { !it.startsWith("-") }
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

        // Fail fast if `--native` was requested but the toolchain is
        // missing — building the JVM jar first and crashing 20 seconds
        // in is a bad UX. The actual native-image invocation is later in
        // the pipeline; this is just the early prereq check.
        if (nativeBuild && NativeImageRunner.locate() == null) {
            println(NativeImageRunner.missingMessage())
            kotlin.system.exitProcess(2)
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

            // 4. Resolve output directory + create it.
            //    Layout:
            //      single app:  <out>/<package>.jar + <out>/<package> launcher
            //      multi app:   <out>/<app-name>/<app-name>.jar + ../<app-name>
            //                   (nested so multiple apps don't collide flat)
            val outRoot = File(currentDir, outDirName)
            val targetDir = if (manifest.app.size > 1) File(outRoot, appConfig.name) else outRoot
            targetDir.mkdirs()

            val outputJarName = if (manifest.app.size == 1) "${manifest.`package`.name}" else appConfig.name

            // 5b. Compute the FunctionIR closure the runtime will need
            //     (user code + reachable stdlib). Same logic the native
            //     build uses — single source of truth.
            val closure = NativeBuildPath.computeReachableClosure(typeCheckResult.loweredFunctions)
            if (timing) System.err.println("[${ms()}ms] runtime closure (${closure.size} fns)")

            // 6. Create runnable JAR — clones mezze-runtime.jar (slim
            //    interpreter + Truffle, no parser/typechecker/CLI/LSP),
            //    appends the user's app as a pair of CBOR resources that
            //    EmbeddedAppLoader decodes on boot.
            val jarFile = File(targetDir, "$outputJarName.jar")
            createStandaloneJar(
                outputJar = jarFile,
                appName = appConfig.name,
                entryName = appConfig.entry,
                closure = closure,
                performerRecords = typeCheckResult.performerRecords,
            )
            if (timing) System.err.println("[${ms()}ms] standalone JAR packed")

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

            // 8. Optional: AOT-compile to a native executable.
            //    Runs native-image on the JAR we just built. Long build
            //    (~2 min) for very fast cold start (<100ms). The native
            //    binary lands next to the .jar as `<app>-native`.
            var nativeBinary: File? = null
            if (nativeBuild) {
                println()
                if (optimizedBuild) {
                    println("Compiling native binary (optimized, ~2 min)...")
                } else {
                    println("Compiling native binary (quick mode, ~30-50s — pass -o for optimized)...")
                }
                nativeBinary = NativeBuildPath.build(
                    jarFile = jarFile,
                    outputDir = targetDir,
                    outputName = "$outputJarName-native",
                    appName = appConfig.name,
                    entryName = appConfig.entry,
                    loweredFunctions = typeCheckResult.loweredFunctions,
                    performerRecords = typeCheckResult.performerRecords,
                    optimized = optimizedBuild,
                    timing = timing,
                    relativeTime = { (System.nanoTime() - startTime) / 1_000_000 },
                )
            }

            val buildTime = (System.nanoTime() - startTime) / 1_000_000

            // Paths relative to project root — what the user types to run.
            val relTarget = currentDir.toPath().relativize(targetDir.toPath()).toString()

            println()
            println("Built ${appConfig.name} in ${formatDuration(buildTime)}")
            println()
            println("  Run:")
            println("    ./$relTarget/${unixLauncher.name}")
            if (nativeBinary != null) {
                println("    ./$relTarget/${nativeBinary.name}    (native, no JVM)")
            }

        } catch (e: Exception) {
            println("Error: ${e.message}")
            kotlin.system.exitProcess(1)
        } finally {
            store.close()
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
     * Create a standalone runnable JAR.
     *
     * Approach: take a copy of `mezze-runtime.jar` (the slim runtime
     * shadowJar produced by :interpreter), append two CBOR resources
     * the runtime's EmbeddedAppLoader reads at startup, and write a
     * fresh MANIFEST.MF pointing at NativeMain.
     *
     * Result: a self-contained JAR that runs on JVM via
     *   `java -jar app.jar`
     * and is also the staging input for `mez build --native`.
     *
     * No SQLite, no JDBC, no parser/typechecker classes — just the
     * runtime needed to execute pre-compiled FunctionIR.
     */
    private fun createStandaloneJar(
        outputJar: File,
        appName: String,
        entryName: String,
        closure: List<com.mezze.language.truffleir.FunctionIR>,
        performerRecords: List<Array<String>>,
    ) {
        val runtimeJar = findRuntimeJar() ?: run {
            println("Error: mezze-runtime.jar not found. Reinstall mez with `./gradlew :cli:installNative`.")
            return
        }

        // CBOR-serialize the FunctionIR closure + a small metadata blob.
        // EmbeddedAppLoader (in :interpreter) reads these two resources
        // from the classpath at boot.
        val fnBytes = com.mezze.language.store.StoreSerialization.serializeFunctionIRList(closure)
        val metaBytes = com.mezze.language.store.StoreSerialization.serializeEmbeddedAppMeta(
            com.mezze.language.store.StoreSerialization.EmbeddedAppMeta(
                appName = appName,
                entryName = entryName,
                performerRecords = performerRecords.map { it.toList() },
            )
        )

        val seen = HashSet<String>()
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(
            java.io.FileOutputStream(outputJar).buffered(64 * 1024)
        ).use { out ->
            @Suppress("DEPRECATION")
            val src = org.apache.commons.compress.archivers.zip.ZipFile(runtimeJar)
            try {
                src.copyRawEntries(out, org.apache.commons.compress.archivers.zip.ZipArchiveEntryPredicate { e ->
                    val name = e.name
                    when {
                        name == "META-INF/MANIFEST.MF" -> false  // we write our own
                        name.startsWith("META-INF/") &&
                            (name.endsWith(".SF") || name.endsWith(".RSA") ||
                             name.endsWith(".DSA") || name.endsWith(".EC")) -> false
                        !seen.add(name) -> false
                        else -> true
                    }
                })
            } finally {
                src.close()
            }

            // Append the user's app — CBOR resources at the paths
            // EmbeddedAppLoader reads.
            fun put(path: String, bytes: ByteArray) {
                out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry(path))
                out.write(bytes)
                out.closeArchiveEntry()
            }
            put("META-INF/mezze/embedded-app.cbor", fnBytes)
            put("META-INF/mezze/embedded-app.meta", metaBytes)

            // Fresh MANIFEST.MF — points at NativeMain in :interpreter.
            val manifest = "Manifest-Version: 1.0\n" +
                "Main-Class: com.mezze.interpreter.standalone.NativeMain\n\n"
            put("META-INF/MANIFEST.MF", manifest.toByteArray())
        }
    }

    /**
     * Locate `mezze-runtime.jar`.
     *
     * Three sources, in order:
     *   1. `~/.mezze/lib/mezze-runtime.jar` — the installed CLI lays
     *      this down via `installRuntime`. Native binaries (where
     *      `protectionDomain.codeSource.location` is null) always
     *      take this path.
     *   2. A JAR sibling: when running from `mezze-lang.jar`, look for
     *      `mezze-runtime.jar` in the same dir.
     *   3. Dev fallback: `interpreter/build/libs/mezze-runtime.jar`
     *      under `mezze.project.root` system property, or by walking
     *      up from the running class location.
     *
     * Returns null if nothing matched — the caller surfaces a clear
     * error pointing at `./gradlew :cli:installNative`.
     */
    private fun findRuntimeJar(): File? {
        // Source 1: ~/.mezze/lib/mezze-runtime.jar (the installed path).
        // Works for both native binaries (where codeSource is null) and
        // installed JVM jars.
        val home = System.getProperty("user.home")
        if (home != null) {
            val installed = File("$home/.mezze/lib/mezze-runtime.jar")
            if (installed.isFile) return installed
        }

        // Source 2: JAR sibling — running from `mezze-lang.jar` outside
        // the standard install location (custom deploy).
        val cliLocation = javaClass.protectionDomain.codeSource?.location
        val cliJarOrClassDir = if (cliLocation != null) File(cliLocation.toURI()) else null
        if (cliJarOrClassDir?.isFile == true && cliJarOrClassDir.extension == "jar") {
            val sibling = File(cliJarOrClassDir.parentFile, "mezze-runtime.jar")
            if (sibling.isFile) return sibling
        }

        // Source 3a: dev fallback via system property.
        val projectRoot = System.getProperty("mezze.project.root")
        if (projectRoot != null) {
            val devRuntime = File(projectRoot, "interpreter/build/libs/mezze-runtime.jar")
            if (devRuntime.isFile) return devRuntime
        }

        // Source 3b: walk up from running class dir to find the build
        // output. Only matters when running from gradle / IDE.
        var current: File? = cliJarOrClassDir
        repeat(8) {
            current = current?.parentFile ?: return@repeat
            val candidate = File(current, "interpreter/build/libs/mezze-runtime.jar")
            if (candidate.isFile) return candidate
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

    /** Format a duration as either "Xs" or "Xm Ys" depending on magnitude. */
    private fun formatDuration(millis: Long): String {
        val secs = millis / 1000
        return if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }

}
