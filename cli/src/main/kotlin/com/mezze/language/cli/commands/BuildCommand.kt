package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
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
 * 2. Open .mez/store/src.sqlite, compile (same as mez dev)
 * 3. Scatter results into store
 * 4. Compute transitive closure from entry hash
 * 5. Create minimal blobs.sqlite with only reachable IR
 * 6. Package into JAR (future: full fat JAR with Truffle runtime)
 */
class BuildCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val msg = CliMessages()
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            System.err.println(msg.noManifest())
            return
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), mezzeToml.readText())
        } catch (e: Exception) {
            System.err.println(msg.badManifest(e.message))
            return
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            System.err.println(msg.noApp(manifest.`package`.name))
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

        // Native build defaults to optimized (-O2, ~2min, strips debug
        // symbols) — we want the fastest user binary out of the box.
        // Pass `--quick` for native-image's quick-build (-Ob, ~30-50s,
        // ~20% slower binary, retains -g) when iterating during dev.
        val quickBuild = args.contains("--quick") || args.contains("-q")
        val optimizedBuild = !quickBuild

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
                System.err.println(msg.appNotFound(appName, manifest.app.map { it.name }))
                return
            }
        } else {
            manifest.app.first()
        }

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            System.err.println(msg.appFileNotFound(appFile))
            return
        }

        // Fail fast if `--native` was requested but the toolchain is
        // missing — building the JVM jar first and crashing 20 seconds
        // in is a bad UX. The actual native-image invocation is later in
        // the pipeline; this is just the early prereq check.
        if (nativeBuild) {
            val envReport = com.mezze.language.cli.EnvironmentCheck.probe()
            if (com.mezze.language.cli.EnvironmentCheck.isCriticalFailure(envReport)) {
                println(com.mezze.language.cli.EnvironmentCheck.longReport(envReport))
                kotlin.system.exitProcess(2)
            }
            if (NativeImageRunner.locate() == null) {
                println(NativeImageRunner.missingMessage())
                kotlin.system.exitProcess(2)
            }
        }

        val store = SQLiteMezzeStore.openProject(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)

        // Same cache-invalidation gate as mez dev — if this build is run
        // against a project whose .mez/ was last typechecked by an older
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

            // 1. Compile (or load from cache).
            //
            // Cache fast-path: if .mez/store/src.sqlite already has this app's
            // IRs AND every project file on disk content-matches the
            // cached source-hash, skip parse+typecheck+lower entirely.
            //
            // mez dev populates the cache on every save (the typechecker's
            // parser cache writes through), so the typical workflow
            // (edit → save → mez build) hits this fast-path.
            val source = appFile.readText()
            val sourceHash = sha256(source)
            val modulePath = manifest.`package`.name

            val cached = BuildCache.tryLoad(store, appConfig.name)
            val loweredFunctions: List<FunctionIR>
            val performerRecords: List<Array<String>>
            // typedModule available only on the recompile path. The only
            // downstream consumer is entry-shape validation; that's skipped
            // on cache hits (if the IR was good enough to cache, the shape
            // already passed).
            val typedModuleForValidation: com.mezze.language.typedast.TypedModule?
            var warningCount = 0

            if (cached != null) {
                loweredFunctions = cached.functions
                performerRecords = cached.performerRecords
                typedModuleForValidation = null
                if (timing) System.err.println("[${ms()}ms] cache hit (${cached.functions.size} fns) — skipping typecheck")
            } else {
                val compiled = compileFromSource(
                    store = store,
                    source = source,
                    sourceHash = sourceHash,
                    appFile = appFile,
                    appName = appConfig.name,
                    entryName = appConfig.entry,
                    modulePath = modulePath,
                    currentDir = currentDir,
                    timing = timing,
                    ms = ::ms,
                )
                loweredFunctions = compiled.loweredFunctions
                performerRecords = compiled.performerRecords
                typedModuleForValidation = compiled.typedModule
                warningCount = compiled.warningCount
            }

            // 2b. Stdlib FunctionIRs are NOT seeded into cache.sqlite.
            //     The old seed loop wrote ~1164 stdlib functions (~4.8 MB)
            //     into every project's cache. Two of the three things that
            //     needed it now resolve elsewhere:
            //
            //       - Build closure walk: NativeBuildPath.computeReachableClosure
            //         resolves stdlib hashes from PrecompiledStdlib in memory.
            //       - BuildCache.tryLoad: only reads user-code IRs (the closure
            //         saved in app_functions is loweredFunctions, which is user
            //         code only — stdlib is re-resolved from PrecompiledStdlib
            //         when computeReachableClosure runs on cache hit).
            //       - Unresolved-hash check (BuildCache.collectUnresolvedHashes):
            //         already ORs PrecompiledStdlib.containsKey before falling
            //         back to store.blobExists, so stdlib presence in the store
            //         is redundant.
            //
            //     The one thing we lose: edges from stdlib functions in the
            //     `edges` table — LSP find-references into stdlib won't return
            //     stdlib-to-stdlib edges from the user's cache. LSP can still
            //     read them from the bundled stdlib snapshot if needed (or via
            //     a future shared install-time store).

            // 3. Validate entry function — must exist and have shape `{} -> {} <*>`
            //    (effects allowed, ability constraints allowed, dict params hidden).
            //
            // On cache hits we skip this entirely: the IR couldn't have been
            // cached if it hadn't already passed entry validation at the
            // original compile time. typedModule is only populated on the
            // fresh-compile path.
            val entryName = appConfig.entry
            if (typedModuleForValidation != null) {
                val mainBinding = typedModuleForValidation.decls
                    .filterIsInstance<TypedLetDecl>()
                    .find { it.name == entryName }

                if (mainBinding == null) {
                    println("Error: App must define a '$entryName' function with type {} -> {} <*>")
                    kotlin.system.exitProcess(4)
                }
                EntryPointCheck.errorMessage(EntryPointCheck.check(mainBinding!!), entryName)?.let { msg ->
                    println("Error: $msg")
                    kotlin.system.exitProcess(4)
                }
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
            val closure = NativeBuildPath.computeReachableClosure(loweredFunctions)
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
                performerRecords = performerRecords,
            )
            if (timing) System.err.println("[${ms()}ms] standalone JAR packed")

            // 7. (Optional) Train an AppCDS archive next to the jar. The jar
            //    is run once with MEZZE_CDS_DRYRUN=1, which loads everything
            //    (Engine + Context + language + Truffle nodes) but skips
            //    user-code eval. The JVM writes a .jsa snapshot of every
            //    class touched. Subsequent runs mmap that snapshot at startup
            //    and skip ~50% of classloading. Opt-out: MEZZE_NO_CDS=1
            //    during build. (#215)
            //    G1 GC is required (ZGC and CDS are incompatible). The
            //    launcher we emit below uses G1 by default to match.
            val jsaFile = File(targetDir, "$outputJarName.jsa")
            val cdsEnabled = System.getenv("MEZZE_NO_CDS") != "1"
            if (cdsEnabled) {
                val cdsOk = generateCdsArchive(jarFile, jsaFile, timing, ::ms)
                if (!cdsOk) jsaFile.delete()
            }
            val jsaName: String? = if (cdsEnabled && jsaFile.exists()) jsaFile.name else null
            if (timing && jsaName != null) System.err.println("[${ms()}ms] CDS archive written")

            // 8. Generate launcher scripts (Unix sh + Windows bat).
            //    These pre-set production-grade JVM flags so users don't have
            //    to remember them. They invoke `java -jar <app>.jar` with:
            //      -XX:SharedArchiveFile=<jar>.jsa (if step 7 succeeded)
            //          → mmap the AppCDS archive at startup; ~50% cold-start
            //            reduction on hello-style apps.
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
            writeUnixLauncher(unixLauncher, jarFile.name, jsaName)
            val winLauncher = File(targetDir, "$outputJarName.bat")
            writeWindowsLauncher(winLauncher, jarFile.name, jsaName)
            if (timing) System.err.println("[${ms()}ms] launchers written")

            // 8. Optional: AOT-compile to a native executable.
            //    Runs native-image on the JAR we just built. Long build
            //    (~2 min) for very fast cold start (<100ms). The native
            //    binary lands next to the .jar as `<app>-native`.
            var nativeBinary: File? = null
            if (nativeBuild) {
                println()
                if (optimizedBuild) {
                    println("Compiling native binary (optimized, ~2 min — pass --quick for ~30-50s build)...")
                } else {
                    println("Compiling native binary (quick mode, ~30-50s)...")
                }
                nativeBinary = NativeBuildPath.build(
                    jarFile = jarFile,
                    outputDir = targetDir,
                    outputName = "$outputJarName-native",
                    appName = appConfig.name,
                    entryName = appConfig.entry,
                    loweredFunctions = loweredFunctions,
                    performerRecords = performerRecords,
                    optimized = optimizedBuild,
                    timing = timing,
                    relativeTime = { (System.nanoTime() - startTime) / 1_000_000 },
                )
            }

            val buildTime = (System.nanoTime() - startTime) / 1_000_000

            // Paths relative to project root — what the user types to run.
            val relTarget = currentDir.toPath().relativize(targetDir.toPath()).toString()

            // (label, run-command, sizeBytes) — JVM launcher always, native
            // when built. Size is the actual artifact the launcher runs.
            val artifacts = buildList {
                add(Triple("jar", "./$relTarget/${unixLauncher.name}", jarFile.length()))
                if (nativeBinary != null) {
                    add(Triple("native", "./$relTarget/${nativeBinary.name}", nativeBinary.length()))
                }
            }
            val msg = CliMessages()
            println()
            println(msg.buildSummary(
                appName = appConfig.name,
                durationLabel = formatDuration(buildTime),
                artifacts = artifacts,
                warningCount = warningCount,
            ))

        } catch (e: Exception) {
            System.err.println(CliMessages().buildFailed(e.message))
            kotlin.system.exitProcess(1)
        } finally {
            store.close()
        }
    }

    /**
     * Output of a fresh compile: the data the jar-packing code needs,
     * plus the TypedModule for entry-point validation. Mirrors
     * TypeCheckRunner's success shape minus fields the build path doesn't
     * read.
     */
    private data class CompiledApp(
        val loweredFunctions: List<FunctionIR>,
        val performerRecords: List<Array<String>>,
        val typedModule: com.mezze.language.typedast.TypedModule?,
        // Warnings from the fresh typecheck (0 on a cache hit — we didn't
        // re-typecheck, so there's nothing new to report).
        val warningCount: Int = 0,
    )

    /**
     * Full parse + typecheck + lower path. Writes the result back to the
     * store so the next `mez build` / `mez dev` can hit the cache. Calls
     * exitProcess on parse / typecheck errors (matching prior behaviour).
     */
    private fun compileFromSource(
        store: SQLiteMezzeStore,
        source: String,
        sourceHash: String,
        appFile: File,
        appName: String,
        entryName: String,
        modulePath: String,
        currentDir: File,
        timing: Boolean,
        ms: () -> Long,
    ): CompiledApp {
        val parser = MezzeParser()
        val parseResult = parser.parseWithRecovery(source, file = appFile.name)
        if (parseResult.errors.isNotEmpty()) {
            println(ParseErrorFormatter.formatAll(parseResult.errors))
            kotlin.system.exitProcess(3)
        }
        val ast = parseResult.module
        if (timing) System.err.println("[${ms()}ms] parsed")

        // Pre-load typed modules from cache.sqlite so the typechecker
        // only re-typechecks modules whose source actually changed.
        // Stdlib comes from PrecompiledStdlib (no SQLite round-trip);
        // user modules in the entry's import closure come from store.
        // Without this every `mez build` re-typechecks every imported
        // module from scratch.
        StoreHydrator.hydrate(store, modulePath, timing = timing)

        val typeChecker = TypeChecker()
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

        // Scatter result into store so next run hits the cache.
        val depParsedModules = com.mezze.language.typechecker.ModuleResolver.getAllParsedModules()
        StorePopulator.populate(
            store, typeCheckResult, modulePath,
            appFile.absolutePath, sourceHash,
            entrySource = source,
            parsedModule = ast,
            loadedParsedModules = depParsedModules
        )
        store.transaction {
            store.putApp(appName, entryName, modulePath, appFile.absolutePath)
            store.setAppFunctionHashes(appName, typeCheckResult.loweredFunctions.map { it.hash })
            store.setAppPerformerRecords(appName, typeCheckResult.performerRecords)
        }

        return CompiledApp(
            loweredFunctions = typeCheckResult.loweredFunctions,
            performerRecords = typeCheckResult.performerRecords,
            typedModule = typeCheckResult.typedModule,
            warningCount = typeCheckResult.warnings.size,
        )
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
    private fun writeUnixLauncher(launcher: File, jarName: String, jsaName: String? = null) {
        // AppCDS: if a .jsa archive was generated at build time, the launcher
        // mmaps it at startup to skip ~50% of class-loading. The archive
        // requires the default GC (G1 on JDK 21+); ZGC and CDS are
        // incompatible. G1 pauses are higher but for the kind of program
        // that runs `mez build` (short scripts and small services), the
        // startup-time win dominates the GC-pause concern. Users can still
        // opt back into ZGC via MEZZE_JAVA_OPTS. (#215)
        val cdsFlag = if (jsaName != null)
            "-XX:SharedArchiveFile=${'$'}DIR/$jsaName "
        else ""
        val script = """
            #!/usr/bin/env sh
            # Auto-generated by `mez build`. Production launcher with sane JVM flags.
            # To override flags for a single run:
            #   MEZZE_JAVA_OPTS='-Xmx2g -XX:+UseZGC' ./<app>
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
            # --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED: required for
            # EffectFiber, which wraps jdk.internal.vm.Continuation for the
            # Loom-backed effect handler runtime. Without it the JVM refuses
            # to load the wrapping class.
            # --add-modules=jdk.incubator.vector: required by VectorOps,
            # which SIMD-optimizes LongSeqStorage.sum/min/max/indexOf and
            # is class-loaded eagerly. Without it, the first call to
            # seq::sum / seq::min / seq::max / seq::contains crashes with
            # NoClassDefFoundError: jdk/incubator/vector/Vector.
            # G1 (default GC) is used here instead of ZGC so the AppCDS
            # class-data-sharing archive is honored — ZGC and CDS are
            # incompatible.
            DEFAULT_OPTS="-Xmx4g $cdsFlag--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED --add-modules=jdk.incubator.vector"
            OPTS="${'$'}{MEZZE_JAVA_OPTS:-${'$'}DEFAULT_OPTS}"

            exec "${'$'}JAVA" ${'$'}OPTS -jar "${'$'}JAR" "${'$'}@"
        """.trimIndent() + "\n"
        launcher.writeText(script)
        launcher.setExecutable(true, false)
    }

    /** Windows .bat counterpart of [writeUnixLauncher]. */
    private fun writeWindowsLauncher(launcher: File, jarName: String, jsaName: String? = null) {
        val cdsFlag = if (jsaName != null)
            "-XX:SharedArchiveFile=%DIR%$jsaName "
        else ""
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
            if "%MEZZE_JAVA_OPTS%"=="" set "MEZZE_JAVA_OPTS=-Xmx4g $cdsFlag--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED --add-modules=jdk.incubator.vector"
            "%MEZZE_JAVA%" %MEZZE_JAVA_OPTS% -jar "%JAR%" %*
        """.trimIndent() + "\r\n"
        launcher.writeText(script)
    }

    /**
     * Train an AppCDS archive by running the user jar once with the
     * [MEZZE_CDS_DRYRUN=1] env var (see EmbeddedExec — that flag causes
     * the app to load everything but return before user code runs). The
     * JVM writes a class-data-sharing snapshot to [jsaFile]; the launcher
     * mmaps it on real runs to skip ~50% of classloading.
     *
     * Returns true when the .jsa was written. False on any error — the
     * launcher then falls back to the no-CDS path. (#215)
     */
    private fun generateCdsArchive(
        jarFile: File,
        jsaFile: File,
        timing: Boolean,
        ms: () -> Long,
    ): Boolean {
        val javaBin = System.getenv("MEZZE_JAVA") ?: "java"
        // G1 is required for AppCDS (ZGC and CDS are incompatible). We
        // also pass the same enable-native-access / add-opens flags the
        // launcher uses; otherwise the dry-run hits an UnsupportedOpException
        // mid-classload and the archive is incomplete.
        val cmd = listOf(
            javaBin,
            "-XX:ArchiveClassesAtExit=${jsaFile.absolutePath}",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector",
            "-jar", jarFile.absolutePath,
        )
        val pb = ProcessBuilder(cmd)
        pb.environment()["MEZZE_CDS_DRYRUN"] = "1"
        // Redirect output to /dev/null — `mez build` only cares whether
        // the archive was written, not what got printed. CDS itself
        // emits warnings about JFR / proxy classes that aren't actionable.
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        return try {
            val proc = pb.start()
            val exited = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                if (timing) System.err.println("[${ms()}ms] CDS dry-run timed out — skipping")
                return false
            }
            if (proc.exitValue() != 0) {
                if (timing) System.err.println("[${ms()}ms] CDS dry-run exited with ${proc.exitValue()} — skipping")
                return false
            }
            jsaFile.exists() && jsaFile.length() > 0
        } catch (e: Exception) {
            if (timing) System.err.println("[${ms()}ms] CDS dry-run failed: ${e.message} — skipping")
            false
        }
    }

    /**
     * Create a standalone runnable JAR.
     *
     * Approach: take a copy of `mezze-runtime.jar` (the slim runtime
     * shadowJar produced by :interpreter), append two CBOR resources
     * the runtime's EmbeddedAppLoader reads at startup, and write a
     * fresh MANIFEST.MF pointing at MezzeAppMain.
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
        // from the classpath at boot. Shared helper with NativeBuildPath.
        val (fnBytes, metaBytes) = NativeBuildPath.packEmbeddedAppCbor(
            closure = closure,
            appName = appName,
            entryName = entryName,
            performerRecords = performerRecords,
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

            // Fresh MANIFEST.MF — points at MezzeAppMain in :interpreter.
            // Multi-Release MUST be true: Truffle API is a multi-release JAR
            // and the JDK 21+ overlay of JDKAccessor is what implements
            // virtual threads. Without this header the pre-21 base class is
            // loaded and Async.spawn immediately throws
            // "VirtualThread is not supported on this JDK".
            val manifest = "Manifest-Version: 1.0\n" +
                "Main-Class: com.mezze.interpreter.standalone.MezzeAppMain\n" +
                "Multi-Release: true\n\n"
            put("META-INF/MANIFEST.MF", manifest.toByteArray())
        }
    }

    /**
     * Locate `mezze-runtime.jar` — the slim runtime jar user-app builds
     * clone into their output. Two sources:
     *
     *   1. Production: `~/.mezze/lib/mezze-runtime.jar`, laid down by
     *      `:cli:installRuntime`. Used by both native `mez` (where
     *      `protectionDomain.codeSource.location` is null) and the
     *      installed JVM jar.
     *   2. Dev: `<mezze.project.root>/interpreter/build/libs/mezze-runtime.jar`,
     *      for gradle / IDE runs.
     *
     * Returns null if neither exists — caller surfaces a clear error.
     */
    private fun findRuntimeJar(): File? {
        // Production install.
        System.getProperty("user.home")?.let { home ->
            val installed = File("$home/.mezze/lib/mezze-runtime.jar")
            if (installed.isFile) return installed
        }
        // Dev / IDE.
        System.getProperty("mezze.project.root")?.let { root ->
            val dev = File(root, "interpreter/build/libs/mezze-runtime.jar")
            if (dev.isFile) return dev
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
                    try { store.addEdge(fromHash, node.targetHash, com.mezze.language.store.EdgeKind.CALLS) } catch (_: Exception) {}
                    node.args.forEach { walk(it) }
                }
                is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                    try { store.addEdge(fromHash, node.targetHash, com.mezze.language.store.EdgeKind.CALLS) } catch (_: Exception) {}
                    node.partialFields.forEach { walk(it.value) }
                }
                is com.mezze.language.truffleir.MakeLambdaIR -> {
                    try { store.addEdge(fromHash, node.functionHash, com.mezze.language.store.EdgeKind.CALLS) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.HandleIR -> {
                    walk(node.body)
                    for (h in node.handlers) {
                        try { store.addEdge(fromHash, h.handlerHash, com.mezze.language.store.EdgeKind.REFERENCES) } catch (_: Exception) {}
                        try { store.addEdge(fromHash, h.effectHash, com.mezze.language.store.EdgeKind.REFERENCES) } catch (_: Exception) {}
                    }
                }
                is com.mezze.language.truffleir.PerformOpIR -> {
                    try { store.addEdge(fromHash, node.effectHash, com.mezze.language.store.EdgeKind.REFERENCES) } catch (_: Exception) {}
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
                is com.mezze.language.truffleir.CallNativeIR -> {
                    walk(node.arg); node.args.forEach { walk(it) }
                }
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
                    try { store.addEdge(fromHash, node.hash, com.mezze.language.store.EdgeKind.REFERENCES) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> {
                    try { store.addEdge(fromHash, node.hash, com.mezze.language.store.EdgeKind.REFERENCES) } catch (_: Exception) {}
                }
                is com.mezze.language.truffleir.EffectBindingIR -> {} // runtime-supplied effect binding, not a global ref
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
