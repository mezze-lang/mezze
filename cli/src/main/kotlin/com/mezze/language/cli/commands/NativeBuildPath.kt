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
        /** Entry-point identity: entry function content-hash hex. */
        entryHash: String,
        /** Display-only entry name. */
        entryName: String?,
        loweredFunctions: List<FunctionIR>,
        performerRecords: List<Array<String>>,
        /** Answers `std::codebase` while comptime folds; see [finalizeClosure]. */
        codebase: com.mezze.language.comptime.CodebaseHost?,
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved =
            com.mezze.language.cli.`package`.PolyglotResolver.Resolved.NONE,
        /** Language jars to bake into the shell (see build's extraClasspath). */
        extraClasspath: List<File> = emptyList(),
        timing: Boolean,
        relativeTime: () -> Long,
    ): File? {
        var shell = locateShellBinary(polyglot, extraClasspath)
        if (shell == null) {
            val what = if (polyglot.enablePython || extraClasspath.isNotEmpty())
                "a polyglot shell (bundles the guest language runtime)" else "a shell"
            println("No matching shell binary for this project — building $what via -O2 (one-time for this language/version set)...")
            val shellBinary = build(
                jarFile = jarFile,
                outputDir = outputDir,
                outputName = ".mez-shell-tmp",
                appName = appName,
                entryHash = entryHash,
                entryName = entryName,
                loweredFunctions = loweredFunctions,
                performerRecords = performerRecords,
                codebase = codebase,
                optimized = true,
                polyglot = polyglot,
                extraClasspath = extraClasspath,
                timing = timing,
                relativeTime = relativeTime,
            )
            if (shellBinary == null) {
                // build() already reported the native-image failure (graceful,
                // trace under --verbose). Returning null propagates to the
                // false-success guard in BuildCommand → clean exit 1.
                return null
            }
            // build() caches the shell under its capability key.
            shell = locateShellBinary(polyglot, extraClasspath)
            // Clean up the temp binary — the real output gets the CBOR append below
            try { shellBinary.delete() } catch (_: Exception) {}
            if (shell == null) {
                System.err.println("${Glyphs.CROSS} shell binary was built but not cached — check .mez/shell/ permissions")
                return null
            }
            println("Shell cached in .mez/shell — future builds of this project reuse it.")
        }
        val outBinary = File(outputDir, outputName)
        outputDir.mkdirs()
        val closure = finalizeClosure(loweredFunctions, performerRecords, codebase)
        val (fnBytes, metaBytes) = packEmbeddedAppCbor(closure, appName, entryHash, entryName, performerRecords)
        shell.copyTo(outBinary, overwrite = true)
        outBinary.setExecutable(true, false)
        appendCborTrailer(outBinary, fnBytes, metaBytes)
        if (timing) System.err.println(
            "[${relativeTime()}ms] -O1: appended ${fnBytes.size + metaBytes.size + 24} bytes to $outBinary"
        )
        // Ship the venv + JVM classpath jars next to the binary so it can
        // run from an arbitrary directory. See bundlePolyglotAssets docs.
        bundlePolyglotAssets(outBinary, polyglot, timing, relativeTime)
        return outBinary
    }

    fun hasShellBinary(
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved =
            com.mezze.language.cli.`package`.PolyglotResolver.Resolved.NONE,
        extraClasspath: List<File> = emptyList(),
    ): Boolean = locateShellBinary(polyglot, extraClasspath) != null

    private var projectShellDir: File? = null

    fun setProjectDir(projectDir: File) {
        projectShellDir = File(projectDir, ".mez/shell")
    }

    /**
     * The per-project shell binary for a given capability fingerprint. Shells are
     * project-local (`.mez/shell/`) and NAMED by a hash of everything baked into
     * the shell — the enabled languages, the exact language-jar identities (the
     * content-addressed `.deps`/maven jar paths, which encode e.g. GraalPy
     * 25.0.3), the GraalVM version that built it, and the mezze compiler version.
     * So a pure-Mezze project, a `[polyglot.python]` project, and the same
     * project after a GraalPy/GraalVM bump each get their OWN shell and never
     * reuse an incompatible one — a stale shell can't silently drop a language.
     */
    private fun shellBinaryFor(key: String): File? =
        projectShellDir?.let { File(it, "mez-shell-$key") }

    /**
     * Fingerprint of what a shell must contain — see shellBinaryFor. The
     * language SET and their VERSIONS are captured by [extraClasspath]: those are
     * the content-addressed `.deps`/maven jar paths (e.g. `…/python-language/
     * 25.0.3/…jar`), so the path already encodes both which languages and which
     * versions. Plus the GraalVM version that builds the image and the mezze
     * compiler version. `enablePython` adds the pure-stdlib case (Python on with
     * no extra jars) so it still differs from a bare Mezze shell.
     */
    fun shellCapabilityKey(
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved,
        extraClasspath: List<File>,
    ): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        d.update(com.mezze.language.cli.BuildInfo.VERSION.toByteArray())
        d.update(com.mezze.language.cli.BuildInfo.GRAAL_VERSION.toByteArray())
        d.update(if (polyglot.enablePython) 1 else 0)
        for (p in extraClasspath.map { it.absolutePath }.sorted()) d.update(p.toByteArray())
        return d.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun locateShellBinary(
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved =
            com.mezze.language.cli.`package`.PolyglotResolver.Resolved.NONE,
        extraClasspath: List<File> = emptyList(),
    ): File? {
        System.getenv("MEZZE_SHELL_BIN")?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        val key = shellCapabilityKey(polyglot, extraClasspath)
        shellBinaryFor(key)?.let { if (it.isFile) return it }
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
        /** Entry-point identity: entry function content-hash hex. */
        entryHash: String,
        /** Display-only entry name. */
        entryName: String?,
        loweredFunctions: List<FunctionIR>,
        performerRecords: List<Array<String>>,
        /** Answers `std::codebase` while comptime folds; see [finalizeClosure]. */
        codebase: com.mezze.language.comptime.CodebaseHost?,
        optimized: Boolean,
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved =
            com.mezze.language.cli.`package`.PolyglotResolver.Resolved.NONE,
        /** Polyglot/[jars]/[maven] language jars (from ~/.mezze/cache/maven +
         *  store/jars) to add to the native-image classpath. `mez dev` sees
         *  these via ResolvedJarsClasspath; the native build must too or polyglot
         *  degrades in the AOT binary. Empty for non-polyglot projects. */
        extraClasspath: List<File> = emptyList(),
        timing: Boolean,
        relativeTime: () -> Long,
    ): File? {
        val nativeImage = NativeImageRunner.locate()
            ?: error("native-image disappeared between probe and build")

        val stagingDir = File(outputDir, ".native-staging").apply { mkdirs() }

        // Compute the closure + CBOR bytes ONCE up front. We hash these
        // (content-addressed, deterministic) for the native-image cache key.
        // Hashing the jar bytes directly is non-determ because JarEntry stamps
        // with current time.
        val closure = finalizeClosure(loweredFunctions, performerRecords, codebase)
        if (timing) System.err.println("[${relativeTime()}ms] native: closure computed (${closure.size} fns)")
        val (fnBytes, metaBytes) = packEmbeddedAppCbor(closure, appName, entryHash, entryName, performerRecords)

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
        val cacheKey = computeNativeCacheKey(fnBytes, metaBytes, nativeImage, optimized, polyglot, extraClasspath)
        val cacheFile = cacheDir?.let { File(it, "$cacheKey.bin") }
        if (cacheFile != null && cacheFile.exists()) {
            if (timing) System.err.println("[${relativeTime()}ms] native: cache hit ($cacheKey) — copying instead of building")
            cacheFile.copyTo(outBinary, overwrite = true)
            outBinary.setExecutable(true, false)
            try { stagingDir.deleteRecursively() } catch (_: Exception) {}
            // Bundle venv + user JVM jars alongside the binary even on a
            // cache hit. The cache stores only the binary itself, but the
            // sidecar assets are per-project (venv, user jars) and must
            // be re-materialised into `outputDir/` every build.
            bundlePolyglotAssets(outBinary, polyglot, timing, relativeTime)
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

        val classpathJars = extractRuntimeClasspath(stagingDir, timing, relativeTime, polyglot)
        val sanitisedUserJar = sanitiseUserJar(
            jarFile, stagingDir, timing, relativeTime,
            enablePolyglot = polyglot.enablePython,
        )
        val logbackConfig = writeSilentLogbackConfig(stagingDir)

        val cmd = buildNativeImageCommand(
            nativeImage = nativeImage,
            classpathJars = classpathJars,
            sanitisedUserJar = sanitisedUserJar,
            embeddedJar = embeddedJar,
            outBinary = outBinary,
            optimized = optimized,
            logbackConfig = logbackConfig,
            polyglot = polyglot,
            extraClasspath = extraClasspath,
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
        val verbose = com.mezze.language.cli.CliContext.verbose ||
            (System.getenv("MEZZE_NATIVE_VERBOSE")?.let {
                it == "1" || it.equals("true", ignoreCase = true)
            } ?: timing) // --verbose, the env var, or timing → stream the noise
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
            System.err.println("${Glyphs.CROSS} native-image failed (exit $exit). " +
                if (logFile != null) "See output above for details. Re-run with --verbose to stream output live."
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

        // Cache as the shell binary for future -O1 builds in this project.
        // The -O2 binary can serve as the shell because MezzeAppMain checks
        // for a CBOR tail trailer first; if absent, it falls back to the
        // build-time embedded app. When used as a shell, the tail-appended
        // CBOR overrides the embedded app.
        try {
            val shellDir = projectShellDir
            if (shellDir != null) {
                shellDir.mkdirs()
                // Cache under the capability key so a later -O1 build reuses this
                // shell only when its languages/versions/GraalVM match.
                val key = shellCapabilityKey(polyglot, extraClasspath)
                val shellDest = File(shellDir, "mez-shell-$key")
                outBinary.copyTo(shellDest, overwrite = true)
                shellDest.setExecutable(true, false)
                if (timing) System.err.println("[${relativeTime()}ms] native: cached as shell at ${shellDest.absolutePath}")
            }
        } catch (e: Exception) {
            if (timing) System.err.println("[${relativeTime()}ms] native: shell cache write failed: ${e.message}")
        }

        // Bundle sidecar assets — venv site-packages + user JVM jars —
        // next to the binary. This is what makes the binary
        // relocatable: a user can `cp` it (with the sidecars) to
        // /usr/local/bin and run it from anywhere.
        bundlePolyglotAssets(outBinary, polyglot, timing, relativeTime)

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
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved,
        extraClasspath: List<File> = emptyList(),
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(fnBytes)
        digest.update(metaBytes)
        digest.update(nativeImage.absolutePath.toByteArray())
        digest.update(if (optimized) byteArrayOf(1) else byteArrayOf(0))
        // Polyglot state changes what native-image is called with —
        // `--language:python` / user JVM cp jars change the resulting
        // binary substantially. Mix them into the key so toggling the
        // manifest section triggers a rebuild instead of reusing an
        // incompatible cached binary.
        digest.update(if (polyglot.enablePython) byteArrayOf(1) else byteArrayOf(0))
        for (cp in polyglot.jvmClasspath.sorted()) digest.update(cp.toByteArray())
        // The resolved [jars]/[maven] language jars are now on the image
        // classpath too — a change there must bust the cache or we'd reuse a
        // binary built against the old set. Key on their absolute paths (which
        // are content-addressed store/cache paths, so path == content).
        for (cp in extraClasspath.map { it.absolutePath }.sorted()) digest.update(cp.toByteArray())
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
        com.mezze.language.cli.MezzeHome.runtimeJar.takeIf { it.isFile }?.let { return it }
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
        /** Entry-point identity: entry function content-hash hex. */
        entryHash: String,
        /** Display-only entry name; NEVER a selection key. */
        entryName: String?,
        performerRecords: List<Array<String>>,
        /**
         * Static shape of the entry's result, for an EVAL closure only.
         *
         * Null for an app: a program's result is its exit value and is never
         * rendered. Set when the closure is one expression a caller wants a VALUE
         * back from — see `StoreSerialization.EmbeddedAppMeta.resultPrintType`.
         */
        resultPrintType: com.mezze.language.truffleir.PrintType? = null,
    ): Pair<ByteArray, ByteArray> {
        val fnBytes = StoreSerialization.serializeFunctionIRList(closure)
        val metaBytes = StoreSerialization.serializeEmbeddedAppMeta(
            StoreSerialization.EmbeddedAppMeta(
                appName = appName,
                entryHash = entryHash,
                entryName = entryName,
                performerRecords = performerRecords.map { it.toList() },
                resultPrintType = resultPrintType,
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
    /**
     * The runnable FunctionIR closure reachable from [loweredFunctions].
     *
     * A referenced (called) function is resolved from, in order: the functions
     * just lowered in this pass, then the precompiled stdlib, then — if provided —
     * [fromStore] (a dependency whose IR was compiled once and cached in the store,
     * keyed by hash; #46). stdlib functions are leaves here (the runtime embeds
     * stdlib and resolves those calls itself), but a store-loaded DEPENDENCY
     * function is NOT embedded — so its own call targets must also be pulled, which
     * is why store resolution walks transitively.
     *
     * [fromStore] is null by default, so with no cached deps this is exactly the
     * previous behavior (only lowered + stdlib) — a no-op until deps are cached.
     */
    /**
     * The closure a runtime is handed: reachable IR, comptime folded, chains
     * canonicalized.
     *
     * One helper because the three native packers and the jar packer all need
     * the same thing, and because the comptime fold has to happen HERE. A
     * dependency's comptime lives in its own module, so its call site — and,
     * critically, the thunk's own `FunctionIR` — appear only once the closure is
     * walked: user code does not contain them, and `DepPrecompiler` stores a dep
     * lowered but unreduced. Folding earlier left the site in the artifact with
     * its thunk nowhere the evaluator could reach, so `mez build` exited 0 and
     * every run died with "comptime thunk ... is not among the compiled
     * functions".
     *
     * Reduction rewrites bodies and preserves hashes, so an entry hash resolved
     * before this stays valid.
     */
    fun finalizeClosure(
        loweredFunctions: List<FunctionIR>,
        performerRecords: List<Array<String>>,
        /**
         * The codebase being built, for `comptime` blocks that ask `std::codebase`
         * about it. Null only where there is no project store to answer from.
         */
        codebase: com.mezze.language.comptime.CodebaseHost?,
    ): List<FunctionIR> {
        val rawClosure = computeReachableClosure(loweredFunctions)
        val folded = try {
            ComptimeHost.reduce(rawClosure, performerRecords, codebase)
        } catch (e: com.mezze.language.comptime.ComptimeReduction.ComptimeFailure) {
            println(e.message)
            kotlin.system.exitProcess(4)
        }
        val closureMap = folded.associateBy { it.hash }
        val canonicalized = com.mezze.language.truffleir.ChainCanonicalizer.canonicalize(closureMap)
        return folded.map { canonicalized[it.hash] ?: it }
    }

    fun computeReachableClosure(
        loweredFunctions: List<FunctionIR>,
        fromStore: ((com.mezze.language.typedast.Hash) -> FunctionIR?)? = null,
    ): List<FunctionIR> {
        val stdlibFns = com.mezze.language.typechecker.PrecompiledStdlib.preloweredFunctions
        val closureMap = HashMap<com.mezze.language.typedast.Hash, FunctionIR>()
        for (fn in loweredFunctions) closureMap[fn.hash] = fn

        val queue = ArrayDeque(
            com.mezze.language.stdlib.FunctionIRReachability.collectReferencedHashes(loweredFunctions)
        )
        while (queue.isNotEmpty()) {
            val hash = queue.removeFirst()
            if (hash in closureMap) continue
            val stdFn = stdlibFns[hash]
            if (stdFn != null) {
                closureMap[hash] = stdFn   // stdlib: leaf (resolved by the embedded runtime), as before
                continue
            }
            // A cached dependency function: served from PrecompiledDeps (populated
            // by the CLI from the dep stores) or the explicit fromStore fallback.
            // Unlike stdlib it is NOT embedded in the runtime, so it must be
            // carried in the closure and its own call targets walked transitively.
            val depFn = com.mezze.language.typechecker.PrecompiledDeps.preloweredFunctions[hash]
                ?: fromStore?.invoke(hash) ?: continue
            closureMap[hash] = depFn
            for (h in com.mezze.language.stdlib.FunctionIRReachability.collectReferencedHashes(listOf(depFn))) {
                if (h !in closureMap) queue.add(h)
            }
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
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved =
            com.mezze.language.cli.`package`.PolyglotResolver.Resolved.NONE,
    ): List<File> {
        val cpDir = File(stagingDir, "cp").apply { mkdirs() }
        val all = extractBundledNativeClasspath(cpDir)
        if (all.isEmpty()) error(
            "Native classpath resource not found inside mez. " +
            "Did you build this `mez` with `./gradlew :cli:installNative`?"
        )
        // Polyglot python builds: drop truffle-runtime so the resulting
        // binary uses DefaultTruffleRuntime (interpreter-only). GraalPy
        // 25.0.3's static analysis surface reaches ~300 JDK blocklist
        // methods via Truffle runtime compilation registration, and any
        // truffle-svm-enabled build aborts with "compilation blocklist
        // violations". Interpreter-only mode sidesteps the blocklist
        // check entirely. Mezze code is slower without PE, but the
        // binary at least builds and runs — same trade-off #401 had
        // before it was fixed for non-polyglot builds. Upstream GraalPy
        // needs to trim its runtime-compilable surface before we can
        // re-enable truffle-svm for python.
        val polyglotDrops = if (polyglot.enablePython) listOf(
            "truffle-runtime-",
        ) else emptyList()
        val drop = polyglotDrops + listOf(
            // #401: truffle-runtime-* used to be dropped here to force the
            // interpreter-only DefaultTruffleRuntime, since --macro:truffle-svm
            // was off for user builds. Both are now on by default for
            // non-polyglot user builds; keeping the jar on the classpath is
            // required for the macro's PE hooks to resolve at build time.
            // (Re-added conditionally above for polyglot.python builds.)
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
        enablePolyglot: Boolean,
    ): File {
        val out = File(stagingDir, "user-no-modpath-directives.jar")
        // An app that declared no `[polyglot.*]` must not carry the language
        // providers into its image. THEY WERE THE 278 BLOCKLIST VIOLATIONS.
        //
        // The fat jar bundles every provider — 19,157 GraalPy classes in a
        // 105 MB jar — because `:cli` shadows them in for its own use. The
        // `drop` list in collectClasspathJars filters the EXTRACTED per-library
        // jars by file name; the fat jar is a separate native-image input and
        // nothing ever filtered its CONTENTS. So GraalPy stayed reachable, and
        // with `--macro:truffle-svm` on (which is correct for a non-polyglot
        // build) its ~300 blocklisted JDK methods became reachable for runtime
        // compilation and the build aborted.
        //
        // The code already knew this hazard — `buildNativeImageCommand` drops
        // `truffle-runtime-` when python IS enabled, for exactly these
        // violations. This is the symmetric case that was missing: when python
        // is NOT enabled, drop the providers instead of the JIT.
        //
        // Why the earlier diagnosis went wrong twice, recorded so it does not
        // go wrong a third time: dumping the native-image classpath shows no
        // python jar, and that is TRUE — the providers are not a jar on the
        // classpath, they are classes inside the fat jar. And the violation
        // traces pass through our own
        // `MezzeBytecodeRootNodeGen$InstructionImpl.getArguments`, which reads
        // like the root cause but is not: it is reached from GraalPy's
        // `CodeUnit.toString` through `Formatter`, and once `Formatter` is
        // reachable the analysis must treat every `toString` as a candidate.
        // Ours is a LEAF of the chain, not its head.
        val prefixes = if (enablePolyglot) BUILDER_CLASS_PREFIXES_TO_STRIP
                       else BUILDER_CLASS_PREFIXES_TO_STRIP + LANGUAGE_PROVIDER_PREFIXES_TO_STRIP
        val stripped = stripJarEntries(jarFile, out, FAT_JAR_DIRECTIVES_TO_STRIP, prefixes)
        if (timing) System.err.println("[${relativeTime()}ms] native: user jar sanitised")

        // Stripping these keeps the IMAGE correct, but their presence means the
        // runtime jar this build read is carrying language providers the project
        // never asked for — and that jar is also what `mez build` fat-jars onto
        // the JVM path, where nothing strips it. Say so rather than silently
        // absorbing it: it is why app jars come out ~100MB instead of ~20MB.
        //
        // The usual cause is a STALE unversioned install. `installRuntime`
        // writes `~/.mezze/toolchains/<version>/lib/`, but the launcher looks in
        // the unversioned `~/.mezze/lib/` FIRST — so a provider-bundled jar left
        // there by an older release shadows every newer one, permanently.
        if (!enablePolyglot && stripped > 0) {
            System.err.println(
                "[mezze] note: the runtime jar bundled $stripped Truffle "
                + "language-provider entries, but this project declares no "
                + "`[polyglot.*]`. They were left out of the native image. If "
                + "`~/.mezze/lib/mezze-runtime.jar` is much larger than ~20MB it "
                + "is a stale install shadowing the versioned one under "
                + "`~/.mezze/toolchains/`; deleting it also shrinks the JVM jar, "
                + "which this does not touch."
            )
        }
        return out
    }

    /**
     * The bundled Truffle language providers, stripped from a NON-polyglot
     * app's image.
     *
     * Classes and their `META-INF/native-image` directives both: a surviving
     * directive is honoured by native-image on its own (the wasm one was,
     * turning on `-H:+VectorAPISupport` in a build that uses no wasm).
     *
     * `com/oracle/truffle/api/` is deliberately NOT here — that is the Truffle
     * framework the Mezze interpreter itself is built on. Only LANGUAGES go.
     */
    private val LANGUAGE_PROVIDER_PREFIXES_TO_STRIP = listOf(
        "com/oracle/graal/python/",
        "META-INF/native-image/org.graalvm.python/",
        "com/oracle/truffle/js/",
        "META-INF/native-image/org.graalvm.js/",
        "org/graalvm/wasm/",
        "META-INF/native-image/org.graalvm.wasm/",
        "com/oracle/truffle/llvm/",
        "META-INF/native-image/org.graalvm.llvm/",
        "com/oracle/truffle/regex/",
        "META-INF/native-image/org.graalvm.regex/",
    )

    /** Step 5: assemble the full native-image command. */
    private fun buildNativeImageCommand(
        nativeImage: File,
        classpathJars: List<File>,
        sanitisedUserJar: File,
        embeddedJar: File,
        outBinary: File,
        optimized: Boolean,
        logbackConfig: File,
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved,
        extraClasspath: List<File> = emptyList(),
    ): List<String> {
        // Polyglot GraalPy → disable Truffle runtime compilation.
        //
        // `--macro:truffle-svm` (in common flags) turns on Truffle
        // partial evaluation inside the native image. Without polyglot
        // that gives Mezze user code full JIT via Graal PE, matching JVM
        // performance for hot loops.
        //
        // GraalPy 25.0.3, however, transitively reaches ~300 JDK
        // methods on native-image's blocklist for runtime compilation
        // (java.util.HashMap.clear, ConcurrentHashMap.clear,
        // FileOutputStream.open0, com.oracle.svm.core.posix.Signal.raise,
        // etc.). Any polyglot-python native build with truffle-svm
        // enabled aborts with "325 compilation blocklist violations"
        // during points-to analysis.
        //
        // Trade-off: with truffle-svm off, the native binary uses
        // DefaultTruffleRuntime — no PE, no JIT. Mezze code AND Python
        // both interpret. Startup is instant, steady-state is slower.
        // For the current polyglot workflow (numpy demos, scripting)
        // this is the correct trade until upstream GraalPy trims its
        // hot paths. The alternative — refusing to build — leaves
        // polyglot users with no native path at all. #402-followup.
        // `[polyglot.jvm] classpath` entries are appended to the
        // native-image -cp. This makes the user's Java classes reachable
        // from the SVM image; at runtime, `java::type("com.foo.Bar")`
        // resolves against the embedded classes rather than a user's
        // separately-installed JAR.
        val userJvmCpFiles = polyglot.jvmClasspath
            .mapNotNull { p -> File(p).takeIf { it.exists() } }
        // Polyglot/[jars]/[maven] language jars resolved from the store/cache —
        // put them on the image classpath so guest languages work in the AOT
        // binary, matching what `mez dev` does. (See ResolvedJarsClasspath.)
        val extraCpFiles = extraClasspath.filter { it.exists() }
        val cpFiles = classpathJars + sanitisedUserJar + embeddedJar + userJvmCpFiles + extraCpFiles
        val cp = cpFiles.joinToString(File.pathSeparator) { it.absolutePath }

        // What went on the image classpath, on request. A native build that fails
        // in the analyser nearly always fails because of what is REACHABLE, and
        // the first question is always "what is even on the classpath?" — which
        // nothing could answer without rebuilding the CLI with a print in it.
        // `--verbose` streams native-image's own output and never showed this.
        if (System.getenv("MEZZE_DEBUG_NATIVE_CP") != null) {
            System.err.println("[mezze] native-image classpath (${cpFiles.size} entries):")
            for (f in cpFiles) System.err.println("  " + f.absolutePath)
        }

        val cmd = mutableListOf(nativeImage.absolutePath)
        val commonFlags = if (polyglot.enablePython) {
            // Drop --macro:truffle-svm for polyglot python builds (see
            // block comment above). Interpreter-only Truffle avoids the
            // blocklist violations that upstream GraalPy trips.
            NativeImageFlags.common.filterNot { it == "--macro:truffle-svm" }
        } else {
            NativeImageFlags.common
        }
        cmd.addAll(commonFlags)
        cmd.addAll(NativeImageFlags.userInitAtBuildTimeArgs())

        cmd.addAll(USER_BUILD_SPECIFIC_FLAGS)

        // GraalPy discovery: python-language.jar ships its own
        // META-INF/native-image/.../native-image.properties which
        // native-image auto-applies when the jar is on the classpath.
        // That covers reflection config, resource config, and the
        // required `--initialize-at-build-time=com.oracle.graal.python`
        // etc. — so we don't need `--language:python` on the command
        // line (and 25.0.3's driver doesn't recognise it as a flag
        // anyway; the jar-side Args are the supported entry point).
        //
        // We only need to pass polyglot state through so the build
        // gets the JVM classpath jars (see cp assembly above) and the
        // cache key differentiates polyglot from non-polyglot builds.
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

        // glibc >= 2.42 broke static libm: ifunc dispatchers in
        // libm-2.42.a reference `_dl_x86_cpu_features`, a symbol only
        // in the dynamic linker. Provide a zero-initialized stub so
        // ifunc dispatchers select the generic math path.
        if (isLinux && !isOracleGraalVm(nativeImage)) {
            val stubDir = File(outBinary.parentFile, "native-stubs")
            stubDir.mkdirs()
            val stubSrc = File(stubDir, "dl_cpu_features_stub.c")
            stubSrc.writeText(
                "/* glibc 2.42+ static libm ifunc stub */\n" +
                "__attribute__((visibility(\"default\")))\n" +
                "char _dl_x86_cpu_features[512] __attribute__((aligned(64)));\n"
            )
            val stubObj = File(stubDir, "dl_cpu_features_stub.o")
            val ccBin = System.getenv("CC") ?: "cc"
            val rc = ProcessBuilder(ccBin, "-c", "-fPIC",
                "-o", stubObj.absolutePath, stubSrc.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            if (rc == 0 && stubObj.exists()) {
                cmd.add("-H:NativeLinkerOption=${stubObj.absolutePath}")
            }
        }
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
        // Truffle NFI (Native Function Interface). The runtime jar shades
        // truffle-nfi but not as a separate JPMS module, so the
        // `ForceOnModulePath = com.oracle.truffle.truffle_nfi` directive in
        // the fat-jar copy fails with "Module descriptor was not found in
        // class-path entry". truffle-nfi is only reachable via GraalPy's
        // C extension loader; for pure-Python programs it's dead code.
        // Strip the directives; if a future user needs the NFI module,
        // it must ship as its own jar.
        "META-INF/native-image/org.graalvm.truffle/truffle-nfi/native-image.properties",
        "META-INF/native-image/org.graalvm.truffle/truffle-nfi-libffi/native-image.properties",
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
        // Strip stdlib CBOR from the native build classpath. The embedded
        // app's FunctionIR closure is self-contained (computeReachableClosure
        // includes every transitively-referenced stdlib function). Letting
        // the CBOR survive causes StdlibRuntimeData.init{} to decode ~15 MB
        // of FunctionIR data at native-image build time — all of it lands
        // in the image heap, adding ~15 MB to the binary and ~100 ms to
        // startup for data the runtime never reads. #native-image-size.
        "META-INF/mezze/stdlib-snapshot.cbor",
        "META-INF/mezze/stdlib-runtime.cbor",
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

    // ─── polyglot sidecar bundling ──────────────────────────────────────────

    /**
     * Place polyglot assets next to the produced [outBinary] so it can
     * run from any directory:
     *
     *   - `<binary-stem>-venv/lib/python3.12/site-packages/…` — full
     *     copy of the venv's site-packages. `EmbeddedExec`'s runtime
     *     discovery falls back to this path when no `Mezze.toml` is
     *     found in cwd, so a `cp binary` (with its sibling dir) is a
     *     working deploy.
     *   - `<binary-stem>-jvm/…jar` — user JVM classpath jars.
     *     `EmbeddedExec` adds these to the polyglot Context's host
     *     classpath at runtime.
     *
     * On repeat builds we clean the sibling dirs first so removals in
     * the source venv don't linger in the target.
     */
    private fun bundlePolyglotAssets(
        outBinary: File,
        polyglot: com.mezze.language.cli.`package`.PolyglotResolver.Resolved,
        timing: Boolean,
        relativeTime: () -> Long,
    ) {
        val stem = outBinary.nameWithoutExtension.takeIf { it.isNotEmpty() } ?: outBinary.name

        // 1) Python venv site-packages. python_path in Mezze.toml
        //    typically points at `.../lib/pythonX.Y/site-packages/`;
        //    we mirror that structure so the runtime can auto-discover.
        //
        //    When `[polyglot.python]` is declared WITHOUT `python_path`
        //    (pure-stdlib usage), we still create an empty `<stem>-venv/`
        //    directory as a marker so `PolyglotManifest.sidecarLookup`
        //    can enable Python without a site-packages path. This is
        //    what lets a demo like `polyglot_python_pure` (math /
        //    statistics only, no wheels) run relocated from the
        //    project tree.
        if (polyglot.enablePython) {
            val destVenv = File(outBinary.parentFile, "$stem-venv")
            if (destVenv.isDirectory) destVenv.deleteRecursively()
            destVenv.mkdirs()
            val srcSitePackages = polyglot.pythonPath?.let { File(it) }
            if (srcSitePackages != null && srcSitePackages.isDirectory) {
                // Recover the python version segment from the source
                // path, defaulting to python3.12. Path shape:
                //   .../venv/lib/pythonX.Y/site-packages
                val pyDirName = srcSitePackages.parentFile?.name?.takeIf { it.startsWith("python") }
                    ?: "python3.12"
                val destSitePackages = File(destVenv, "lib/$pyDirName/site-packages")
                destSitePackages.mkdirs()
                if (timing) System.err.println(
                    "[${relativeTime()}ms] polyglot: bundling venv → ${destSitePackages.absolutePath}"
                )
                try {
                    copyDirectoryFast(srcSitePackages, destSitePackages)
                } catch (e: Exception) {
                    System.err.println(
                        "Warning: could not bundle venv from ${srcSitePackages.absolutePath}: ${e.message}. " +
                        "Binary will only run in project directories with Mezze.toml pointing at a valid venv."
                    )
                }
            } else {
                // Pure-stdlib marker: an empty venv dir is enough for
                // sidecarLookup to enable python without a path.
                if (timing) System.err.println(
                    "[${relativeTime()}ms] polyglot: python enabled, no python_path — writing empty marker at ${destVenv.absolutePath}"
                )
                if (polyglot.pythonPath != null && !srcSitePackages!!.isDirectory) {
                    System.err.println(
                        "Warning: [polyglot.python].python_path='${polyglot.pythonPath}' does not exist. " +
                        "Binary will run without a site-packages path."
                    )
                }
            }
        }

        // 2) User JVM classpath jars — always copy fresh (source paths
        //    can change per-project). Empty list → skip entirely so
        //    non-polyglot builds don't create empty dirs.
        if (polyglot.jvmClasspath.isNotEmpty()) {
            val destJvm = File(outBinary.parentFile, "$stem-jvm")
            if (destJvm.isDirectory) destJvm.deleteRecursively()
            destJvm.mkdirs()
            for (path in polyglot.jvmClasspath) {
                val src = File(path)
                if (!src.exists()) {
                    System.err.println("Warning: [polyglot.jvm].classpath entry not found: $path — skipping")
                    continue
                }
                val dest = File(destJvm, src.name)
                try { src.copyTo(dest, overwrite = true) } catch (e: Exception) {
                    System.err.println("Warning: failed to copy $path → ${dest.absolutePath}: ${e.message}")
                }
            }
            if (timing) System.err.println(
                "[${relativeTime()}ms] polyglot: bundled ${polyglot.jvmClasspath.size} JVM jar(s) → ${destJvm.absolutePath}"
            )
        }
    }

    /**
     * Fast directory copy that follows symlinks. Uses a
     * FileVisitor to walk once. Preferable to `Files.walk` when the
     * source is dominated by many small files (numpy: ~40 000 files).
     */
    private fun copyDirectoryFast(src: File, dest: File) {
        val srcPath = src.toPath()
        val destPath = dest.toPath()
        java.nio.file.Files.walkFileTree(srcPath, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(
                dir: java.nio.file.Path,
                attrs: java.nio.file.attribute.BasicFileAttributes,
            ): java.nio.file.FileVisitResult {
                val target = destPath.resolve(srcPath.relativize(dir))
                java.nio.file.Files.createDirectories(target)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: java.nio.file.Path,
                attrs: java.nio.file.attribute.BasicFileAttributes,
            ): java.nio.file.FileVisitResult {
                val target = destPath.resolve(srcPath.relativize(file))
                java.nio.file.Files.copy(
                    file, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                )
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

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
    /** Copies [source] to [target] minus the excluded entries; returns how
     *  many LANGUAGE-PROVIDER entries were dropped (see the caller's note). */
    private fun stripJarEntries(
        source: File,
        target: File,
        excludePaths: Set<String>,
        excludePrefixes: List<String> = emptyList(),
    ): Int {
        // A class removed from the jar must also lose its SERVICE REGISTRATION,
        // or the loader fails on a provider it can no longer find:
        //   ServiceConfigurationError: InternalResourceProvider:
        //   Provider com.oracle.graal.python.resources.PythonResourceProvider not found
        // Deleting the whole service file is wrong — one file lists several
        // providers and the survivors are still needed — so the file is
        // REWRITTEN with the stripped classes' lines dropped, and omitted only
        // if nothing is left.
        val dottedPrefixes = excludePrefixes
            .filter { it.endsWith("/") && !it.startsWith("META-INF/") }
            .map { it.replace('/', '.') }
        var providerEntries = 0

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
                    if (excluded) {
                        if (LANGUAGE_PROVIDER_PREFIXES_TO_STRIP.any { name.startsWith(it) }) {
                            providerEntries++
                        }
                        jin.closeEntry(); continue
                    }

                    if (dottedPrefixes.isNotEmpty()
                        && name.startsWith("META-INF/services/")
                        && !entry.isDirectory) {
                        val kept = jin.readBytes().decodeToString()
                            .lineSequence()
                            .filterNot { line ->
                                val cls = line.substringBefore('#').trim()
                                cls.isNotEmpty() && dottedPrefixes.any { cls.startsWith(it) }
                            }
                            .toList()
                        jin.closeEntry()
                        if (kept.none { it.substringBefore('#').isNotBlank() }) continue
                        it.putNextEntry(java.util.jar.JarEntry(name).apply { time = entry.time })
                        it.write(kept.joinToString("\n").toByteArray())
                        it.closeEntry()
                        continue
                    }

                    val newEntry = java.util.jar.JarEntry(name).apply { time = entry.time }
                    it.putNextEntry(newEntry)
                    jin.copyTo(it)
                    it.closeEntry()
                    jin.closeEntry()
                }
            }
        }
        return providerEntries
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
