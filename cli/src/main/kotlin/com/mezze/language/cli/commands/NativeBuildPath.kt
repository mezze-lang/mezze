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

    /**
     * -O1 native build: copy a prebuilt "shell" native binary and append
     * this app's CBOR payload as a footer. The shell binary reads the
     * footer at startup via [MezzeAppMain.readTailFromBinary].
     *
     * Shell binary resolution order:
     *   1. MEZZE_SHELL_BIN env var (absolute path)
     *   2. ~/.mezze/lib/mez-shell-native (installed by `mez` or first -O2 build)
     *
     * If the shell doesn't exist, falls back to building it via -O2 and
     * caching it for future -O1 builds. This means the FIRST -O1 build
     * on a machine incurs the -O2 cost; subsequent builds are instant.
     */
    fun buildShell(
        jarFile: File,
        outputDir: File,
        outputName: String,
        appName: String,
        entryName: String,
        loweredFunctions: List<FunctionIR>,
        performerRecords: List<Array<String>>,
        timing: Boolean,
        relativeTime: () -> Long,
    ): File? {
        var shell = locateShellBinary()
        if (shell == null) {
            println("No cached shell binary found — building one via -O2 (this is a one-time cost)...")
            val shellBinary = build(
                jarFile = jarFile,
                outputDir = outputDir,
                outputName = ".mez-shell-tmp",
                appName = appName,
                entryName = entryName,
                loweredFunctions = loweredFunctions,
                performerRecords = performerRecords,
                optimized = true,
                timing = timing,
                relativeTime = relativeTime,
            )
            if (shellBinary == null) {
                System.err.println("Error: failed to build shell binary. Check that native-image is installed.")
                return null
            }
            // build() already caches to ~/.mezze/lib/mez-shell-native
            shell = locateShellBinary()
            // Clean up the temp binary — the real output gets the CBOR append below
            try { shellBinary.delete() } catch (_: Exception) {}
            if (shell == null) {
                System.err.println("Error: shell binary was built but not cached. Check ~/.mezze/lib/ permissions.")
                return null
            }
            println("Shell binary cached. Future -O1 builds will be instant.")
        }
        val outBinary = File(outputDir, outputName)
        outputDir.mkdirs()
        val closure = computeReachableClosure(loweredFunctions)
        val (fnBytes, metaBytes) = packEmbeddedAppCbor(closure, appName, entryName, performerRecords)
        shell.copyTo(outBinary, overwrite = true)
        outBinary.setExecutable(true, false)
        appendCborTrailer(outBinary, fnBytes, metaBytes)
        if (timing) System.err.println(
            "[${relativeTime()}ms] -O1: appended ${fnBytes.size + metaBytes.size + 24} bytes to $outBinary"
        )
        return outBinary
    }

    fun hasShellBinary(): Boolean = locateShellBinary() != null

    private fun locateShellBinary(): File? {
        System.getenv("MEZZE_SHELL_BIN")?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        System.getProperty("user.home")?.let { home ->
            val installed = File("$home/.mezze/lib/mez-shell-native")
            if (installed.isFile) return installed
        }
        return null
    }

    private fun appendCborTrailer(binary: File, fnBytes: ByteArray, metaBytes: ByteArray) {
        java.io.RandomAccessFile(binary, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(fnBytes)
            raf.write(metaBytes)
            fun writeU64Le(v: Long) {
                raf.writeLong(java.lang.Long.reverseBytes(v))
            }
            writeU64Le(fnBytes.size.toLong())
            writeU64Le(metaBytes.size.toLong())
            raf.write("MEZZBIN ".toByteArray(Charsets.US_ASCII))
        }
    }

    /**
     * -O2 native build: full native-image AOT compilation. Produces a
     * self-contained binary with everything baked into the image heap.
     */
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

        // Compute the closure + CBOR bytes ONCE up front. We hash these
        // (content-addressed, deterministic) for the native-image cache key,
        // then pass them to packEmbeddedAppJar which would otherwise
        // recompute them. Hashing the jar bytes directly is non-determ
        // because JarEntry stamps with current time.
        val closure = computeReachableClosure(loweredFunctions)
        if (timing) System.err.println("[${relativeTime()}ms] native: closure computed (${closure.size} fns)")
        val (fnBytes, metaBytes) = packEmbeddedAppCbor(closure, appName, entryName, performerRecords)

        // Native-image cache: hash everything that determines the produced
        // binary; on cache hit, copy the prior binary and skip the 70s
        // native-image invocation. Stored at <projectRoot>/.mez/native-cache/
        // keyed by the SHA-256 of (closure CBOR + meta CBOR + mez version +
        // optimized flag + native-image path). Cache hits cost ~10ms
        // (one sha256 + one file copy).
        val outBinary = File(outputDir, outputName)
        val cacheDir = findProjectMzDir(outputDir)?.let { File(it, "native-cache") }
        // Sentinel: wipe the cache dir on mez upgrade (runtime jar bytes
        // changed). Cheap — sha256 of a 20 MB file is ~50 ms; far below
        // the 60 s native-image cost. Decoupled from per-binary cache
        // keys so user code that didn't change still picks up a fresh
        // build automatically after upgrade.
        invalidateOnRuntimeShift(cacheDir, findRuntimeJar(), timing, relativeTime)
        val cacheKey = computeNativeCacheKey(fnBytes, metaBytes, nativeImage, optimized)
        val cacheFile = cacheDir?.let { File(it, "$cacheKey.bin") }
        if (cacheFile != null && cacheFile.exists()) {
            if (timing) System.err.println("[${relativeTime()}ms] native: cache hit ($cacheKey) — copying instead of building")
            cacheFile.copyTo(outBinary, overwrite = true)
            outBinary.setExecutable(true, false)
            try { stagingDir.deleteRecursively() } catch (_: Exception) {}
            return outBinary
        }
        if (timing) System.err.println("[${relativeTime()}ms] native: cache miss ($cacheKey) — invoking native-image")

        // Only build the staging jars on cache miss.
        val embeddedJar = File(stagingDir, "embedded-app.jar")
        writeJar(embeddedJar, mapOf(
            "META-INF/mezze/embedded-app.cbor" to fnBytes,
            "META-INF/mezze/embedded-app.meta" to metaBytes,
        ))
        if (timing) System.err.println("[${relativeTime()}ms] native: embedded-app.jar (${embeddedJar.length() / 1024}KB)")

        val classpathJars = extractRuntimeClasspath(stagingDir, timing, relativeTime)
        val sanitisedUserJar = sanitiseUserJar(jarFile, stagingDir, timing, relativeTime)
        val logbackConfig = writeSilentLogbackConfig(stagingDir)

        val cmd = buildNativeImageCommand(
            nativeImage = nativeImage,
            classpathJars = classpathJars,
            sanitisedUserJar = sanitisedUserJar,
            embeddedJar = embeddedJar,
            outBinary = outBinary,
            optimized = optimized,
            logbackConfig = logbackConfig,
        )

        if (timing) System.err.println("[${relativeTime()}ms] invoking native-image (${cmd.size} args)")

        val debugCmd = System.getenv("MEZZE_NATIVE_DEBUG_CMD")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        if (debugCmd) {
            System.err.println("--- MEZZE_NATIVE_DEBUG_CMD: full native-image invocation ---")
            cmd.forEach { System.err.println("  $it") }
            System.err.println("--- end ---")
            System.err.println("--- classpath jars (${classpathJars.size}) ---")
            classpathJars.forEach { System.err.println("  ${it.name} (${it.length()} bytes)") }
            System.err.println("--- staged in ${stagingDir.absolutePath} ---")
        }

        // Quiet-by-default native-image output (#191).
        // native-image is loud: pages of warnings, deprecation notes, build
        // progress, and image-info banners. Useful when debugging, but noise
        // for the common-case `mez build --native` run that just needs to
        // succeed. Capture to a temp log; on success, throw it away; on
        // failure, dump the log so the user can see what broke. Set
        // MEZZE_NATIVE_VERBOSE=1 to opt back into INHERIT (stream straight
        // to the user's terminal).
        val verbose = System.getenv("MEZZE_NATIVE_VERBOSE")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: timing // timing implies the user already wants the noise
        val logFile = if (!verbose) {
            File.createTempFile("mez-native-image-", ".log").apply { deleteOnExit() }
        } else null

        val pb = ProcessBuilder(cmd)
            .directory(outputDir)
            .redirectErrorStream(true)
        val exit = if (logFile != null) {
            pb.redirectOutput(logFile).start().waitFor()
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT).start().waitFor()
        }

        if (timing) System.err.println("[${relativeTime()}ms] native-image exit=$exit")

        try { stagingDir.deleteRecursively() } catch (_: Exception) {}

        if (exit != 0) {
            if (logFile != null) {
                // Surface the captured output so the user can act. We dump
                // the whole log — failure cases are rare enough that the
                // noise tradeoff favours user clarity over brevity.
                System.err.println("--- native-image output (last build) ---")
                try {
                    logFile.readLines().forEach { System.err.println(it) }
                } catch (_: Exception) {
                    System.err.println("(failed to read $logFile)")
                }
                System.err.println("--- end native-image output ---")
            }
            println("Error: native-image failed (exit $exit). " +
                if (logFile != null) "See output above for details. Re-run with MEZZE_NATIVE_VERBOSE=1 to stream output live."
                else "See output above for details.")
            return null
        }
        // Successful build — drop the captured log silently.
        if (logFile != null) try { logFile.delete() } catch (_: Exception) {}
        if (!outBinary.exists()) {
            println("Error: native-image succeeded but output binary not found: ${outBinary.absolutePath}")
            return null
        }
        outBinary.setExecutable(true, false)

        // Populate cache for next time.
        if (cacheFile != null) {
            try {
                cacheDir!!.mkdirs()
                outBinary.copyTo(cacheFile, overwrite = true)
                if (timing) System.err.println("[${relativeTime()}ms] native: cached binary at ${cacheFile.absolutePath}")
            } catch (e: Exception) {
                if (timing) System.err.println("[${relativeTime()}ms] native: cache write failed: ${e.message}")
            }
        }

        // Cache as the shell binary for future -O1 builds. The -O2 binary
        // can serve as the shell because MezzeAppMain checks for a CBOR
        // tail trailer first; if absent, it falls back to the build-time
        // embedded app (which is whatever this -O2 build compiled). When
        // used as a shell, the tail-appended CBOR overrides the embedded
        // app, so the shell's own baked-in app is irrelevant.
        try {
            val shellDest = System.getProperty("user.home")?.let {
                File("$it/.mezze/lib/mez-shell-native")
            }
            if (shellDest != null) {
                shellDest.parentFile?.mkdirs()
                outBinary.copyTo(shellDest, overwrite = true)
                shellDest.setExecutable(true, false)
                if (timing) System.err.println("[${relativeTime()}ms] native: cached as shell at ${shellDest.absolutePath}")
            }
        } catch (e: Exception) {
            if (timing) System.err.println("[${relativeTime()}ms] native: shell cache write failed: ${e.message}")
        }

        return outBinary
    }

    /**
     * Cache key for the produced native binary:
     *   - closure CBOR + meta CBOR (content-addressed user app)
     *   - native-image path (different graalvm releases → different output)
     *   - optimized flag (different native-image args)
     *
     * SHA-256, hex-encoded.
     *
     * Notably NOT in the key: mezze-runtime.jar contents. That's handled
     * by [invalidateOnRuntimeShift] as a directory-level sentinel (wipe
     * the cache dir on mez upgrade) rather than mixing it into every
     * key — the latter would bloat keys and obscure intent.
     *
     * Hashing CBOR bytes (not the staging embedded-app.jar): JarEntry
     * stamps every entry with current time → jar bytes change on every
     * build. The CBOR is deterministic.
     */
    private fun computeNativeCacheKey(
        fnBytes: ByteArray,
        metaBytes: ByteArray,
        nativeImage: File,
        optimized: Boolean,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(fnBytes)
        digest.update(metaBytes)
        digest.update(nativeImage.absolutePath.toByteArray())
        digest.update(if (optimized) byteArrayOf(1) else byteArrayOf(0))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * If the mez runtime jar at `~/.mezze/lib/mezze-runtime.jar` has
     * changed since we last cached, wipe the native cache dir. Same
     * spirit as `StoreHydration.invalidateOnVersionShift` for the IR
     * cache — mez upgrade self-invalidates downstream caches.
     *
     * Implementation: stash the jar's sha256 in `<cacheDir>/runtime.sha256`.
     * If absent or mismatched, delete every `.bin` in the dir and rewrite
     * the sentinel. Costs one jar sha256 (~50 ms on 20 MB jar).
     */
    private fun invalidateOnRuntimeShift(
        cacheDir: File?,
        runtimeJar: File?,
        timing: Boolean,
        relativeTime: () -> Long,
    ) {
        if (cacheDir == null || runtimeJar == null || !runtimeJar.isFile) return
        val currentSha = sha256OfFile(runtimeJar)
        val sentinel = File(cacheDir, "runtime.sha256")
        val priorSha = if (sentinel.isFile) sentinel.readText().trim() else null
        if (priorSha == currentSha) return
        if (cacheDir.isDirectory) {
            cacheDir.listFiles { f -> f.name.endsWith(".bin") }?.forEach { it.delete() }
            if (timing) System.err.println("[${relativeTime()}ms] native: runtime jar changed — cache wiped")
        } else {
            cacheDir.mkdirs()
        }
        sentinel.writeText(currentSha)
    }

    private fun sha256OfFile(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Locate mezze-runtime.jar — same logic as BuildCommand.findRuntimeJar.
     * Production install is at ~/.mezze/lib/; dev tree is at
     * interpreter/build/libs/.
     */
    private fun findRuntimeJar(): File? {
        System.getProperty("user.home")?.let { home ->
            val installed = File("$home/.mezze/lib/mezze-runtime.jar")
            if (installed.isFile) return installed
        }
        System.getProperty("mezze.project.root")?.let { root ->
            val dev = File(root, "interpreter/build/libs/mezze-runtime.jar")
            if (dev.isFile) return dev
        }
        return null
    }

    /**
     * Walk upward from outputDir looking for the project metadata
     * directory (created by SQLiteMezzeStore.openProject). Returns null
     * if outside any Mezze project.
     *
     * Env-override fast path: when MEZZE_CACHE_DIR is set, projectDir is
     * project-independent — there's nothing to walk, just probe the
     * override path directly.
     */
    private fun findProjectMzDir(outputDir: File): File? {
        // Env-override path is absolute and project-independent.
        if (System.getenv(com.mezze.language.store.ProjectLayout.CACHE_DIR_ENV)?.isNotBlank() == true) {
            val overrideDir = com.mezze.language.store.ProjectLayout.projectDir(outputDir)
            return if (overrideDir.isDirectory) overrideDir else null
        }
        // Default: walk upward looking for `<dir>/.mez`.
        var dir: File? = outputDir.absoluteFile
        repeat(10) {
            val candidate = File(dir, com.mezze.language.store.ProjectLayout.PROJECT_DIR)
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile ?: return null
        }
        return null
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

    /**
     * User code + every stdlib FunctionIR reachable from it.
     *
     * Sorted by hash for determinism: same closure → same CBOR bytes
     * regardless of how callers ordered their input list. That stability
     * is what makes the native-image cache key reliable — both the
     * fresh-compile path and the cache-load path (BuildCache) produce
     * byte-identical CBOR for the same app.
     */
    fun computeReachableClosure(loweredFunctions: List<FunctionIR>): List<FunctionIR> {
        val needed = com.mezze.language.stdlib.FunctionIRReachability
            .collectReferencedHashes(loweredFunctions)
        val stdlibFns = com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions
        val closureMap = HashMap<com.mezze.language.typedast.Hash, FunctionIR>()
        for (fn in loweredFunctions) closureMap[fn.hash] = fn
        for (hash in needed) {
            if (hash in closureMap) continue
            val fn = stdlibFns[hash] ?: continue
            closureMap[hash] = fn
        }
        return closureMap.values.sortedBy { it.hash.hex }
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
            // #401: truffle-runtime-* used to be dropped here to force the
            // interpreter-only DefaultTruffleRuntime, since --macro:truffle-svm
            // was off for user builds. Both are now on by default; keeping
            // the jar on the classpath is required for the macro's PE hooks
            // to resolve at build time.
            "sqlite-jdbc-",
            "chromeinspector-tool-",
            "dap-tool-",
            "profiler-tool-",
            "coverage-tool-",
            "insight-tool-",
            // Host-tool modules — `mez` uses these for parse / typecheck /
            // lowering / project management. The user app's native binary
            // executes pre-lowered FunctionIR and needs none of them.
            "parser-",
            "typechecker-",
            "formatter-",
            "lsp-",
            "store-sqlite-",
            "cli-",
            // LSP4J + its transitives (gson, lsp4j-jsonrpc). Used only by
            // `mez lsp`, never by user runtime.
            "org.eclipse.lsp4j-",
            "org.eclipse.lsp4j.jsonrpc-",
            "gson-",
            // Logback / SLF4J — `mez` uses these for its own logging.
            // User-app logging goes through Mezze's Console effect, not
            // SLF4J. Helidon's transitive slf4j use is shimmed elsewhere.
            "logback-classic-",
            "logback-core-",
            "slf4j-api-",
            // Coroutines — `:lsp` is the only consumer; user runtime
            // doesn't need them.
            "kotlinx-coroutines-core-jvm-",
            "kotlinx-coroutines-core-",
            "kotlinx-coroutines-slf4j-",
            "kotlinx-coroutines-jdk8-",
            // Mez-tool-only libs (TOML for Mezze.toml, semver for version
            // compare, commons-compress for tar.gz in `mez sync`).
            "ktoml-core-jvm-",
            "ktoml-core-",
            "semver-jvm-",
            "semver-",
            "commons-compress-",
            "commons-codec-",
            "commons-io-",
            "commons-lang3-",
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
        stripJarEntries(jarFile, out, FAT_JAR_DIRECTIVES_TO_STRIP, BUILDER_CLASS_PREFIXES_TO_STRIP)
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
        logbackConfig: File,
    ): List<String> {
        val cp = (classpathJars + sanitisedUserJar + embeddedJar)
            .joinToString(File.pathSeparator) { it.absolutePath }

        val cmd = mutableListOf(nativeImage.absolutePath)
        cmd.addAll(NativeImageFlags.common)
        cmd.addAll(NativeImageFlags.userInitAtBuildTimeArgs())

        cmd.addAll(USER_BUILD_SPECIFIC_FLAGS)
        // Point logback at the silent config we just wrote to the staging
        // dir (#193). Has to be an absolute path: relative paths would be
        // resolved against the builder JVM's cwd, which is outputDir.
        cmd.add("-J-Dlogback.configurationFile=${logbackConfig.absolutePath}")

        // -O2: full optimization (~2min). Default in mez — we want the
        // fastest binary out of the box for `mez build --native`. Pass
        // `--quick` to flip to -Ob (~30-50s build, ~20% slower binary)
        // for fast iteration during dev.
        cmd.add(if (optimized) "-O2" else "-Ob")
        // Debug info on for quick builds (Linux only — macOS native-image
        // doesn't implement the debug-info backend yet; passing -g there
        // just emits a "not supported" warning and a phantom `sources/`
        // directory). Stripped on optimized builds regardless of OS.
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

        // Portable binary: target an x86-64-v2 / armv8-a baseline so the
        // binary runs on any user machine, not just the build machine.
        // Without this, native-image may bake in AVX-512 etc. from the
        // build host's CPU. Universal across Oracle GraalVM and GraalVM CE.
        cmd.add("-march=compatibility")
        // Fail fast: if reachability registration is wrong, surface it at
        // build, not at first runtime call. Avoids accidental fallback-jar
        // builds that ship a JVM-launched binary instead of real native.
        cmd.add("--no-fallback")

        // --gc=G1 is an Oracle GraalVM feature, and Substrate VM additionally
        // restricts G1 to Linux AMD64/AArch64 — passing it on macOS Oracle
        // GraalVM fails the build with "currently only supported on Linux
        // AMD64 and AArch64". Gate on both: must be Oracle AND must be
        // Linux. GraalVM CE on any OS, or any flavor on macOS/Windows,
        // gets the serial GC default — slower for long-running processes
        // but still correct.
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        if (isLinux && isOracleGraalVm(nativeImage)) cmd.add("--gc=G1")
        return cmd
    }

    /**
     * Cached probe: is the native-image binary from Oracle GraalVM (vs.
     * GraalVM Community Edition or another distribution)? Determined by
     * grepping `native-image --version` output for "Oracle GraalVM". Any
     * probe failure conservatively returns false — we'd rather take the
     * portable-flags path on an unknown distribution than crash the
     * build on an unsupported flag.
     */
    private val oracleGraalVmCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private fun isOracleGraalVm(nativeImage: File): Boolean =
        oracleGraalVmCache.computeIfAbsent(nativeImage.absolutePath) { _ ->
            runCatching {
                val out = ProcessBuilder(nativeImage.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().readText()
                out.contains("Oracle GraalVM", ignoreCase = true)
            }.getOrDefault(false)
        }

    // ─── flag groups specific to the user-app build ──────────────────────────

    /**
     * Flags required only on the user-app build path (NOT on the CLI's
     * own native build). Anything common goes in
     * `META-INF/mezze/native-image-flags.txt`.
     */
    private val USER_BUILD_SPECIFIC_FLAGS = listOf(
        "--enable-native-access=ALL-UNNAMED",
        // native-image's macOS toolchain probe doesn't recognise Nix's
        // wrapped clang. The wrapped binary is still used for linking;
        // we just skip the probe.
        "-H:-CheckToolchain",
        // Note: `-J-Dlogback.configurationFile=<absolute-path>` is added
        // in buildNativeImageCommand — it needs the staging dir, which is
        // per-build. See writeSilentLogbackConfig.
    )

    /**
     * Logback config that silences Netty's startup DEBUG chatter on the
     * native-image builder JVM (#193). mezze-runtime.jar transitively
     * pulls in logback-classic 1.4.11 + slf4j; Netty auto-picks logback
     * as its SLF4J binding, and with no logback.xml on the classpath
     * logback's default config emits DEBUG. That's ~50 lines of
     * "sun.misc.Unsafe.theUnsafe: available" / platform-detection noise
     * per build — drowns out actual native-image diagnostics when
     * MEZZE_NATIVE_VERBOSE=1.
     *
     * Tried `-Dorg.slf4j.simpleLogger.defaultLogLevel=error` first —
     * doesn't help because logback (not slf4j-simple) is the binding.
     * Tried `-Dio.netty.logger.factory=...JdkLoggerFactory` — Netty only
     * checks that via API call, not system property. The reliable path is
     * to write an actual logback config and point logback at it.
     *
     * `:cli` already ships a similar config in its resources for the
     * mez CLI itself, but the user-build classpath doesn't include the
     * CLI jar — we have to provide a copy from here.
     */
    private fun writeSilentLogbackConfig(stagingDir: File): File {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <statusListener class="ch.qos.logback.core.status.NopStatusListener"/>
                <logger name="io.netty" level="ERROR"/>
                <root level="ERROR"/>
            </configuration>
        """.trimIndent()
        return File(stagingDir, "logback-silent.xml").apply { writeText(xml) }
    }

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
        // #402: mezze-runtime.jar ships the shaded org.graalvm.truffle:
        // truffle-runtime jar, which registers HotSpotTruffleRuntimeAccess
        // as the com.oracle.truffle.api.TruffleRuntimeAccess service.
        // On JVM that's what we want (real HotSpot Graal JIT). On native-
        // image builds, --macro:truffle-svm installs SubstrateTruffleRuntime
        // via a build-time Feature; but if this ServiceLoader entry survives
        // into the image, HotSpot wins at Truffle.getRuntime() init and the
        // native binary falls back to DefaultTruffleRuntime (interpreter
        // only, no JIT). Drop the service registration from the sanitised
        // user fat-jar so the SVM runtime installed by the macro wins.
        "META-INF/services/com.oracle.truffle.api.TruffleRuntimeAccess",
    )

    /**
     * Package prefixes for graal-sdk "builder" classes that belong INSIDE
     * native-image, not on the user classpath. shadowJar pulls them in
     * transitively via graal-sdk 25 → 200+ warnings of the form
     * "Class-path entry ... contains class org.graalvm.nativeimage.hosted.X
     * ... must not be passed via -cp". Dropping them from the sanitised
     * fat-jar clears the warnings AND lets us delete the deprecated
     * -H:+AllowDeprecatedBuilderClassesOnImageClasspath flag. #189.
     *
     * Prefix match, not exact — the packages have many classes and grow
     * with each graal-sdk release.
     *
     * graal-sdk 25 update (#400): in graal-sdk 25 the entire
     * org.graalvm.nativeimage module lives inside the image builder JVM
     * (jrt:/org.graalvm.nativeimage), including the top-level classes,
     * the c/ subtree (WordPointer, etc.), impl/, hosted/, and their
     * clinit tracking. Any of them appearing on the -cp is rejected
     * with a hard error by NativeImageClassLoaderSupport.
     * reportBuilderClassesInApplication. Strip the whole org/graalvm/
     * nativeimage/ tree from the user fat-jar — native-image supplies
     * all of it from the builder module.
     */
    private val BUILDER_CLASS_PREFIXES_TO_STRIP = listOf(
        "org/graalvm/nativeimage/",
        "com/oracle/svm/core/annotate/",
        // graal-sdk 25: Truffle compiler classes (Utilities, etc.) also live
        // in the builder module jrt:/org.graalvm.truffle.compiler and are
        // rejected on -cp with the same hard error. #400.
        "com/oracle/truffle/compiler/",
        // #402: shaded HotSpot Truffle runtime classes from mezze-runtime.jar.
        // Companion to the TruffleRuntimeAccess service strip above:
        // dropping just the service isn't enough — if any code (e.g. via
        // reachability analysis of Class.forName strings) resolves these
        // classes, the SVM feature refuses to install SubstrateTruffleRuntime
        // and we still end up on the interpreter. The user native binary
        // gets its Truffle runtime from SubstrateTruffleCompilationSupport
        // installed by --macro:truffle-svm; the HotSpot copy is dead code.
        "com/oracle/truffle/runtime/hotspot/",
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

    /** Copy [source] to [target], dropping entries whose name is in
     *  [excludePaths] OR starts with any entry in [excludePrefixes]. */
    private fun stripJarEntries(
        source: File,
        target: File,
        excludePaths: Set<String>,
        excludePrefixes: List<String> = emptyList(),
    ) {
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
                    val name = entry.name
                    val excluded = name in excludePaths
                            || excludePrefixes.any { name.startsWith(it) }
                    if (excluded) { jin.closeEntry(); continue }
                    val newEntry = java.util.jar.JarEntry(name).apply { time = entry.time }
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
