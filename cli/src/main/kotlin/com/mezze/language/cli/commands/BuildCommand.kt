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
import com.mezze.language.langservice.printing.ErrorFormatter
import com.mezze.language.langservice.printing.ParseErrorFormatter
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

    fun execute(args: List<String>, invocationDir: File = File(System.getProperty("user.dir"))) {
        val msg = CliMessages()
        // Resolve the project root by walking up to the nearest Mezze.toml, so
        // `mez build` works from any subdirectory and writes .build/ + .mez/ at the
        // root (see ProjectRoot). `currentDir` is the root everywhere below.
        val currentDir = ProjectRoot.discover(invocationDir) ?: invocationDir
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

        // `--native` / `-n` produces a standalone executable next to the JAR.
        //
        // Optimization levels (inspired by gcc -O):
        //   -O1 (default): shell + CBOR tail-append. Builds in seconds by
        //       copying a prebuilt "shell" native binary and appending the
        //       app's serialized IR as a footer. Runtime is the same Truffle
        //       interpreter + JIT; only startup has a small CBOR-deserialize
        //       cost (~10-50ms). Shell binary is built once and cached at
        //       ~/.mezze/lib/mez-shell-native.
        //   -O2: full native-image AOT compilation (~70s). Everything baked
        //       into the image heap — fastest cold start, no deserialization.
        //       Requires GraalVM native-image on PATH.
        val nativeBuild = args.contains("--native") || args.contains("-n")
        val nativeO2 = args.contains("-O2")
        // Legacy compat: --quick mapped to the old quick-build mode; now -O2
        // is the explicit full-rebuild flag. Treat --quick as -O2 quick-mode
        // (native-image with -Ob instead of -O2 optimization).
        val quickBuild = args.contains("--quick") || args.contains("-q")

        if (nativeBuild) NativeBuildPath.setProjectDir(currentDir)

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

        // Fail fast if native-image is needed but missing. -O2 always
        // needs it; -O1 needs it only when no cached shell binary exists
        // (first build triggers an automatic -O2 to create the shell).
        val needsNativeImage = nativeBuild && (nativeO2 || !NativeBuildPath.hasShellBinary())
        if (needsNativeImage) {
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

        // Opens + invalidates any cache a different mez version wrote (the
        // stale-IR-at-runtime guard). Shared with check / exec / dev — see
        // ProjectStore.
        val store = ProjectStore.open(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)

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

            // Front-door name→hash resolution: the user names their entry in
            // Mezze.toml; we resolve it to a content hash ONCE here and carry
            // the hash (never the name) through persistence / packing /
            // runtime selection. FunctionIR.hash == the entry decl's hash.
            val entryHash = loweredFunctions.find { it.name == appConfig.entry }?.hash
            if (entryHash == null) {
                println("Error: App must define a '${appConfig.entry}' function with type {} -> {} <*>")
                kotlin.system.exitProcess(4)
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
                entryHash = entryHash.hex,
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
            //    Serial GC is used (avoids G1's worker-thread startup
            //    overhead; CDS and Serial GC are compatible — only ZGC is not).
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

            // 8. Optional: produce a native executable.
            //    -O1 (default): shell + CBOR tail-append. Seconds.
            //    -O2: full native-image AOT compilation. ~70s.
            var nativeBinary: File? = null
            if (nativeBuild) {
                println()
                if (nativeO2) {
                    val optimizedNI = !quickBuild
                    if (optimizedNI) {
                        println("Compiling native binary -O2 (full AOT, ~2 min — pass --quick for ~30-50s build)...")
                    } else {
                        println("Compiling native binary -O2 (quick mode, ~30-50s)...")
                    }
                    nativeBinary = NativeBuildPath.build(
                        jarFile = jarFile,
                        outputDir = targetDir,
                        outputName = "$outputJarName-native",
                        appName = appConfig.name,
                        entryHash = entryHash.hex,
                        entryName = appConfig.entry,
                        loweredFunctions = loweredFunctions,
                        performerRecords = performerRecords,
                        optimized = optimizedNI,
                        timing = timing,
                        relativeTime = { (System.nanoTime() - startTime) / 1_000_000 },
                    )
                } else {
                    println("Building native binary -O1 (shell + CBOR append)...")
                    nativeBinary = NativeBuildPath.buildShell(
                        jarFile = jarFile,
                        outputDir = targetDir,
                        outputName = "$outputJarName-native",
                        appName = appConfig.name,
                        entryHash = entryHash.hex,
                        entryName = appConfig.entry,
                        loweredFunctions = loweredFunctions,
                        performerRecords = performerRecords,
                        timing = timing,
                        relativeTime = { (System.nanoTime() - startTime) / 1_000_000 },
                    )
                }
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

        val typeChecker = TypeChecker(strictTopLevel = true)
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
        // Front-door name→hash: resolve the entry decl's content hash; persist
        // the HASH as the entry identity (entryName kept as display metadata).
        val entryHash = typeCheckResult.loweredFunctions.find { it.name == entryName }?.hash
            ?: run {
                println("Error: App must define a '$entryName' function with type {} -> {} <*>")
                kotlin.system.exitProcess(4)
            }
        store.transaction {
            store.putApp(appName, entryHash, entryName, modulePath, appFile.absolutePath)
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
            # --add-modules: jdk.incubator.vector is required by VectorOps,
            # which SIMD-optimizes LongSeqStorage.sum/min/max/indexOf and
            # is class-loaded eagerly. jdk.internal.vm.ci is required by
            # the Graal JIT compiler (Truffle). Both must be listed at both
            # CDS dump time and runtime — a mismatch disables AppCDS.
            # Serial GC: avoids G1's worker-thread creation overhead (~14ms
            # saved). Short-lived CLI programs rarely GC at all; Serial's
            # single-threaded stop-the-world is never noticeable. CDS and
            # Serial GC are compatible (only ZGC is not).
            # NewRatio=1: 50% of heap for young gen (default 33%). FP-style
            # code allocates many short-lived values; a larger nursery keeps
            # minor GC frequency low. With 4g heap → ~2g nursery.
            # NOTE: Do NOT use -XX:TieredStopAtLevel=1 here. While it saves
            # ~100ms startup, it disables the Graal/JVMCI JIT compiler that
            # Truffle uses for Mezze code — causing a 10-20× regression on
            # compute-heavy workloads (e.g. 12ms → 139ms for a 20M-iteration
            # tail loop). The Graal JIT requires at least tier 4 (C2 level).
            DEFAULT_OPTS="-Xmx4g -XX:+UseSerialGC -XX:NewRatio=1 $cdsFlag-Xlog:cds=off --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --add-modules=jdk.incubator.vector,jdk.internal.vm.ci"
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
            if "%MEZZE_JAVA_OPTS%"=="" set "MEZZE_JAVA_OPTS=-Xmx4g -XX:+UseSerialGC -XX:NewRatio=1 $cdsFlag-Xlog:cds=off --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --add-modules=jdk.incubator.vector,jdk.internal.vm.ci"
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
        // Match the launcher's JVM flags so the CDS archive captures
        // the same class-loading profile as the runtime. Serial GC
        // matches the launcher (see writeUnixLauncher). The archive
        // must be trained under the same GC + tiered-compilation
        // configuration.
        val cmd = listOf(
            javaBin,
            "-XX:ArchiveClassesAtExit=${jsaFile.absolutePath}",
            "-XX:+UseSerialGC", "-XX:NewRatio=1",
            // CDS training is a dry-run that exits immediately — TieredStopAtLevel=1
            // speeds up the training but must stay out of the production launcher
            // (it kills the Graal JIT). Using it here only is fine: CDS archives
            // don't encode tiered-compilation state.
            "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "--add-modules=jdk.incubator.vector,jdk.internal.vm.ci",
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
        entryHash: String,
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
            entryHash = entryHash,
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
        // Production install (self-located to this toolchain's lib).
        com.mezze.language.cli.MezzeHome.runtimeJar.takeIf { it.isFile }?.let { return it }
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
