package com.mezze.language.cli.commands

import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * AOT-compile the user's app into a self-contained native executable.
 *
 * Pipeline:
 *  1. Compute the transitive-closure FunctionIRs the runtime needs
 *     (user code + reachable stdlib).
 *  2. CBOR-serialize the closure + entry metadata into a tiny
 *     `embedded-app.jar`.
 *  3. Extract the per-library runtime jars that were bundled inside
 *     the running `mez` binary (the shadowJar fat-jar would lose each
 *     module-info.class; the originals carry them).
 *  4. Strip conflicting META-INF/native-image directives from the user
 *     fat-jar.
 *  5. Invoke native-image with the shared flag set
 *     ([NativeImageFlags]) plus a few user-build-specific flags.
 *
 * Runtime path: MezzeAppMain (in :interpreter, com.mezze.interpreter.standalone)
 * reads `EmbeddedApp` static fields populated at image-build time and
 * dispatches into the runtime. No SQLite, no fallback. Stdlib is loaded
 * the same way via `StdlibSnapshotData`.
 *
 * MezzeAppMain lives in :interpreter so the native binary's reachability
 * surface doesn't include :cli / :lsp / store-sqlite / Ktor / lsp4j /
 * logback / formatter — those come in via :cli's transitive deps but
 * aren't needed at runtime. Keeping the user-app classpath narrow is
 * what lets Truffle JIT activate (broad classpaths trip native-image's
 * blocklist for runtime compilation).
 */
object NativeBuildPath {

    fun build(
        jarFile: File,
        outputDir: File,
        outputName: String,
        appName: String,
        entryName: String,
        loweredFunctions: List<FunctionIR>,
        performerRecords: List<Array<String>>,
        optimized: Boolean,
        timing: Boolean,
        relativeTime: () -> Long,
    ): File? {
        val nativeImage = NativeImageRunner.locate()
            ?: error("native-image disappeared between probe and build")

        val stagingDir = File(outputDir, ".native-staging").apply { mkdirs() }

        val embeddedJar = packEmbeddedAppJar(
            stagingDir = stagingDir,
            loweredFunctions = loweredFunctions,
            performerRecords = performerRecords,
            appName = appName,
            entryName = entryName,
            timing = timing,
            relativeTime = relativeTime,
        )

        val classpathJars = extractRuntimeClasspath(stagingDir, timing, relativeTime)
        val sanitisedUserJar = sanitiseUserJar(jarFile, stagingDir, timing, relativeTime)

        val outBinary = File(outputDir, outputName)
        val cmd = buildNativeImageCommand(
            nativeImage = nativeImage,
            classpathJars = classpathJars,
            sanitisedUserJar = sanitisedUserJar,
            embeddedJar = embeddedJar,
            outBinary = outBinary,
            optimized = optimized,
        )

        if (timing) System.err.println("[${relativeTime()}ms] invoking native-image (${cmd.size} args)")

        val exit = ProcessBuilder(cmd)
            .directory(outputDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()

        if (timing) System.err.println("[${relativeTime()}ms] native-image exit=$exit")

        try { stagingDir.deleteRecursively() } catch (_: Exception) {}

        if (exit != 0) {
            println("Error: native-image failed (exit $exit). See output above for details.")
            return null
        }
        if (!outBinary.exists()) {
            println("Error: native-image succeeded but output binary not found: ${outBinary.absolutePath}")
            return null
        }
        outBinary.setExecutable(true, false)
        return outBinary
    }

    // ─── pipeline steps ──────────────────────────────────────────────────────

    /**
     * Step 2: CBOR-serialize the closure + metadata into a tiny jar that
     * native-image's EmbeddedAppLoader will decode at build time.
     */
    private fun packEmbeddedAppJar(
        stagingDir: File,
        loweredFunctions: List<FunctionIR>,
        performerRecords: List<Array<String>>,
        appName: String,
        entryName: String,
        timing: Boolean,
        relativeTime: () -> Long,
    ): File {
        val closure = computeReachableClosure(loweredFunctions)
        if (timing) System.err.println("[${relativeTime()}ms] native: closure computed (${closure.size} fns)")

        val (fnBytes, metaBytes) = packEmbeddedAppCbor(closure, appName, entryName, performerRecords)

        val embeddedJar = File(stagingDir, "embedded-app.jar")
        writeJar(embeddedJar, mapOf(
            "META-INF/mezze/embedded-app.cbor" to fnBytes,
            "META-INF/mezze/embedded-app.meta" to metaBytes,
        ))
        if (timing) System.err.println("[${relativeTime()}ms] native: embedded-app.jar (${embeddedJar.length() / 1024}KB)")
        return embeddedJar
    }

    /**
     * Serialize an already-computed closure + metadata to the two byte
     * blobs that EmbeddedAppLoader reads at startup:
     *   - functions: List<FunctionIR>  → embedded-app.cbor
     *   - app metadata               → embedded-app.meta
     *
     * Shared by `mez build` (JVM jar packing) and `mez build --native`
     * (passes the bytes through a tiny staging jar to native-image).
     */
    fun packEmbeddedAppCbor(
        closure: List<FunctionIR>,
        appName: String,
        entryName: String,
        performerRecords: List<Array<String>>,
    ): Pair<ByteArray, ByteArray> {
        val fnBytes = StoreSerialization.serializeFunctionIRList(closure)
        val metaBytes = StoreSerialization.serializeEmbeddedAppMeta(
            StoreSerialization.EmbeddedAppMeta(
                appName = appName,
                entryName = entryName,
                performerRecords = performerRecords.map { it.toList() },
            )
        )
        return fnBytes to metaBytes
    }

    /** User code + every stdlib FunctionIR reachable from it. */
    fun computeReachableClosure(loweredFunctions: List<FunctionIR>): List<FunctionIR> {
        val needed = com.mezze.interpreter.compiler.PipelineRunner
            .collectReferencedHashes(loweredFunctions)
        val stdlibFns = com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions
        val closureMap = LinkedHashMap<com.mezze.language.typedast.Hash, FunctionIR>()
        for (fn in loweredFunctions) closureMap[fn.hash] = fn
        for (hash in needed) {
            if (hash in closureMap) continue
            val fn = stdlibFns[hash] ?: continue
            closureMap[hash] = fn
        }
        return closureMap.values.toList()
    }

    /**
     * Step 3: extract the per-library runtime jars bundled inside the
     * running `mez` binary at `META-INF/mezze/native-classpath.zip`.
     *
     * Why: shadowJar merges everything into one fat-jar at install time,
     * but native-image needs each jar's `module-info.class` intact to
     * discover MezzeLanguageProvider. The originals were captured by the
     * `bundleNativeClasspath` gradle task; we just unzip them.
     *
     * Drops jars from the runtime classpath. Each one's reason:
     *
     *   - truffle-runtime-*: keeping it activates Truffle JIT via
     *     --macro:truffle-svm — but compiled Mezze code transitively
     *     reaches JDK blocklisted methods (ConcurrentHashMap.size,
     *     WeakHashMap.containsKey, ServiceLoader.parse, ...) through
     *     Direct*Node.execute fallback paths. Until interpreter-level
     *     work tightens dispatch, drop truffle-runtime → native binary
     *     uses DefaultTruffleRuntime (interpreter-only, no JIT). See
     *     #184.
     *   - sqlite-jdbc: native binary loads stdlib + user IR from CBOR
     *     snapshots, never opens SQLite at runtime.
     *   - chromeinspector-tool, dap-tool, profiler-tool, coverage-tool,
     *     insight-tool: Truffle debugging instruments. Useful for
     *     `mez dev`, dead weight in a production native binary.
     *
     * Note: the runtime jar we build (mezze-runtime.jar) already
     * excludes most of these via its shadowJar filter — the list here
     * is belt-and-suspenders for any extras the per-library extract
     * might still surface.
     */
    private fun extractRuntimeClasspath(
        stagingDir: File,
        timing: Boolean,
        relativeTime: () -> Long,
    ): List<File> {
        val cpDir = File(stagingDir, "cp").apply { mkdirs() }
        val all = extractBundledNativeClasspath(cpDir)
        if (all.isEmpty()) error(
            "Native classpath resource not found inside mez. " +
            "Did you build this `mez` with `./gradlew :cli:installNative`?"
        )
        val drop = listOf(
            "truffle-runtime-",
            "sqlite-jdbc-",
            "chromeinspector-tool-",
            "dap-tool-",
            "profiler-tool-",
            "coverage-tool-",
            "insight-tool-",
        )
        val kept = all.filterNot { jar -> drop.any { jar.name.startsWith(it) } }
        if (timing) System.err.println(
            "[${relativeTime()}ms] native: classpath (${kept.size} jars, dropped ${all.size - kept.size})"
        )
        return kept
    }

    /**
     * Step 4: strip META-INF/native-image directives from the user
     * fat-jar that demand a `ForceOnModulePath` module-info.class
     * shadowJar has already stripped. The real (working) directives
     * come from the extracted per-library jars on the classpath.
     *
     * Also drops sqlite-jdbc's directive — its Feature class lives in
     * sqlite-jdbc.jar, which we no longer ship.
     */
    private fun sanitiseUserJar(
        jarFile: File,
        stagingDir: File,
        timing: Boolean,
        relativeTime: () -> Long,
    ): File {
        val out = File(stagingDir, "user-no-modpath-directives.jar")
        stripJarEntries(jarFile, out, FAT_JAR_DIRECTIVES_TO_STRIP)
        if (timing) System.err.println("[${relativeTime()}ms] native: user jar sanitised")
        return out
    }

    /** Step 5: assemble the full native-image command. */
    private fun buildNativeImageCommand(
        nativeImage: File,
        classpathJars: List<File>,
        sanitisedUserJar: File,
        embeddedJar: File,
        outBinary: File,
        optimized: Boolean,
    ): List<String> {
        val cp = (classpathJars + sanitisedUserJar + embeddedJar)
            .joinToString(File.pathSeparator) { it.absolutePath }

        val cmd = mutableListOf(nativeImage.absolutePath)
        cmd.addAll(NativeImageFlags.common)
        cmd.addAll(NativeImageFlags.userInitAtBuildTimeArgs())

        cmd.addAll(USER_BUILD_SPECIFIC_FLAGS)

        // -Ob: native-image's "quick build" mode — ~30-50s, ~20% slower
        // binary. Default for fast iteration.
        // -O2: full optimization, ~2min, used when `--optimized` is set.
        cmd.add(if (optimized) "-O2" else "-Ob")
        // Debug info on for dev builds (Linux only — macOS native-image
        // doesn't implement the debug-info backend yet; passing -g there
        // just emits a "not supported" warning and a phantom `sources/`
        // directory). Stripped on `--optimized` regardless of OS.
        if (!optimized && !System.getProperty("os.name").lowercase().contains("mac")) {
            cmd.add("-g")
        }

        cmd.add("-cp")
        cmd.add(cp)
        // Use the :interpreter-resident main class for native binaries.
        // :cli's StandaloneMain is dual-mode (embedded path + SQLite/JVM-
        // jar fallback) and drags in lsp4j/ktor/store-sqlite via shadowJar.
        // MezzeAppMain knows only the embedded path — no SQLite, no CLI deps.
        cmd.add("com.mezze.interpreter.standalone.MezzeAppMain")
        cmd.add("-o")
        cmd.add(outBinary.absolutePath)

        for (libDir in discoverLinkerLibPaths()) cmd.add("-H:CLibraryPath=$libDir")

        if (System.getProperty("os.name").lowercase().contains("linux")) cmd.add("--gc=G1")
        return cmd
    }

    // ─── flag groups specific to the user-app build ──────────────────────────

    /**
     * Flags required only on the user-app build path (NOT on the CLI's
     * own native build). Anything common goes in
     * `META-INF/mezze/native-image-flags.txt`.
     */
    private val USER_BUILD_SPECIFIC_FLAGS = listOf(
        // EffectFiber (the Loom-backed effect handler runtime) wraps
        // jdk.internal.vm.Continuation. Without these add-opens passed
        // both to the builder JVM (-J prefix) and into the resulting
        // image (--enable-native-access), the image-build's class-init
        // and runtime invocations both fail with InaccessibleObjectException.
        "-J--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        // The Mezze runtime jar transitively pulls in graal-sdk +
        // truffle-compiler classes that native-image considers
        // "builder-only". Proper fix is to split the runtime (still
        // pending); for now downgrade to a warning so the build proceeds.
        "-H:+AllowDeprecatedBuilderClassesOnImageClasspath",
        // native-image's macOS toolchain probe doesn't recognise Nix's
        // wrapped clang. The wrapped binary is still used for linking;
        // we just skip the probe.
        "-H:-CheckToolchain",
    )

    /**
     * META-INF/native-image properties files inside the shadowJar that
     * reference `ForceOnModulePath` for modules whose module-info.class
     * shadowJar already stripped. The extracted per-library jars on the
     * classpath carry working copies — we just need to drop the broken
     * fat-jar copies.
     */
    private val FAT_JAR_DIRECTIVES_TO_STRIP = setOf(
        "META-INF/native-image/org.graalvm.polyglot/polyglot/native-image.properties",
        "META-INF/native-image/org.graalvm.truffle/truffle-api/native-image.properties",
        "META-INF/native-image/org.graalvm.truffle/truffle-compiler/native-image.properties",
        "META-INF/native-image/org.graalvm.truffle/truffle-runtime/native-image.properties",
        "META-INF/native-image/org.graalvm.sdk/graal-sdk/native-image.properties",
        "META-INF/native-image/org.graalvm.sdk/jniutils/native-image.properties",
        "META-INF/native-image/org.graalvm.sdk/word/native-image.properties",
        "META-INF/native-image/org.graalvm.sdk/nativeimage/native-image.properties",
        "META-INF/native-image/org.graalvm.sdk/collections/native-image.properties",
        // sqlite-jdbc's Feature class lives in the jar we no longer
        // ship — the directive would resolve to nothing.
        "META-INF/native-image/org.xerial/sqlite-jdbc/native-image.properties",
    )

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * Extract the per-library runtime jars bundled inside `mez` at
     * `META-INF/mezze/native-classpath.zip` (produced by the
     * `bundleNativeClasspath` gradle task). Empty list when the resource
     * is missing (an older `mez` install).
     */
    private fun extractBundledNativeClasspath(destDir: File): List<File> {
        val resourceName = "META-INF/mezze/native-classpath.zip"
        val resourceStream = NativeBuildPath::class.java.classLoader
            .getResourceAsStream(resourceName) ?: return emptyList()

        val extracted = mutableListOf<File>()
        java.util.zip.ZipInputStream(resourceStream.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".jar")) {
                    zin.closeEntry(); continue
                }
                val outFile = File(destDir, File(entry.name).name)
                outFile.outputStream().buffered().use { zin.copyTo(it) }
                extracted.add(outFile)
                zin.closeEntry()
            }
        }
        return extracted
    }

    /**
     * Find directories the native linker needs (libz in particular). On
     * Nix-managed macOS systems libz lives at a hash-prefixed store path
     * not on the default search path; native-image's link step then
     * dies with `ld: library not found for -lz`.
     */
    private fun discoverLinkerLibPaths(): List<String> {
        val needed = listOf("libz.dylib", "libz.so", "libz.tbd")
        val found = mutableSetOf<String>()
        val candidates = listOfNotNull(
            System.getenv("LD_LIBRARY_PATH"),
            System.getenv("DYLD_LIBRARY_PATH"),
            System.getenv("LIBRARY_PATH"),
        ).flatMap { it.split(File.pathSeparator) }

        for (dir in candidates) {
            val d = File(dir).takeIf { it.isDirectory } ?: continue
            if (needed.any { File(d, it).exists() }) found.add(d.absolutePath)
        }

        if (found.isEmpty() && File("/nix/store").isDirectory) {
            try {
                File("/nix/store").listFiles { f -> f.isDirectory && f.name.contains("zlib-") }
                    ?.asSequence()
                    ?.map { File(it, "lib") }
                    ?.firstOrNull { d -> needed.any { File(d, it).exists() } }
                    ?.let { found.add(it.absolutePath) }
            } catch (_: Exception) { /* best-effort */ }
        }
        return found.toList()
    }

    /** Copy [source] to [target], dropping entries whose name is in [excludePaths]. */
    private fun stripJarEntries(source: File, target: File, excludePaths: Set<String>) {
        java.util.jar.JarInputStream(source.inputStream().buffered()).use { jin ->
            val manifest = jin.manifest
            val out: java.util.jar.JarOutputStream = if (manifest != null) {
                java.util.jar.JarOutputStream(target.outputStream().buffered(), manifest)
            } else {
                java.util.jar.JarOutputStream(target.outputStream().buffered())
            }
            out.use {
                while (true) {
                    val entry = jin.nextJarEntry ?: break
                    if (entry.name in excludePaths) { jin.closeEntry(); continue }
                    val newEntry = java.util.jar.JarEntry(entry.name).apply { time = entry.time }
                    it.putNextEntry(newEntry)
                    jin.copyTo(it)
                    it.closeEntry()
                    jin.closeEntry()
                }
            }
        }
    }

    /** Minimal jar writer — manifest + supplied resource entries. */
    private fun writeJar(target: File, entries: Map<String, ByteArray>) {
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Created-By", "mez build --native")
        }
        JarOutputStream(target.outputStream().buffered(), manifest).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }
}
