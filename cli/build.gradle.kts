import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("kapt") // Add for Truffle annotation processing
    application
    id("com.gradleup.shadow") version "9.4.1"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

application {
    mainClass.set("com.mezze.language.cli.MainKt")
    applicationName = "mez"
    applicationDefaultJvmArgs = listOf(
        // Module access for Truffle/GraalVM
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",

        // GC tuning for short-lived CLI commands
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=10",
        "-XX:G1HeapRegionSize=1m",

        // JIT compilation tuning
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=1",  // Quick compilation for short commands
        "-XX:ReservedCodeCacheSize=64m",

        // Truffle/Polyglot engine options for fast startup
        "-Dpolyglot.engine.WarnInterpreterOnly=false"
    )
}

kotlin {
    jvmToolchain(25)
}

// Used by the compileStdlib task to load NativeOp DSL classes from
// :interpreter without polluting :cli's normal compile/runtime classpath.
// See compileStdlib for the rationale.
val precompileOnly: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":stdlib"))
    implementation(project(":typechecker"))
    implementation(project(":datastructures"))
    implementation(project(":lsp"))
    implementation(project(":formatter"))
    // WireRunner speaks the v1 protocol; payload types live here.
    implementation(project(":wire-protocol"))
    // NOTE: :interpreter is not on the compile classpath. The CLI
    // references interpreter classes only by string literal — as the
    // entry-point class name when spawning a JVM child (RuntimeServer)
    // or assembling native-image's main class (MezzeAppMain). The
    // classes live in mezze-runtime.jar at run time, never linked
    // against here.

    // Precompile task needs :interpreter on its JavaExec classpath so
    // the NativeOp DSL fires and populates NativeSignatures before the
    // typechecker emits CallNativeIR. Runtime-only — not propagated to
    // shadowJar or processResources.
    precompileOnly(project(":interpreter"))

    // Polyglot API (Context, Value, Source) - host API - match native-image version
    implementation("org.graalvm.sdk:graal-sdk:25.0.3")

    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:25.0.3")
    kapt("org.graalvm.truffle:truffle-dsl-processor:25.0.3") // Add annotation processor

    // Serialization for enhanced AST and stdlib pre-compilation
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")

    // Package manager dependencies
    implementation("io.github.z4kn4fein:semver:2.0.0")           // Semantic versioning
    implementation("com.akuleshov7:ktoml-core:0.7.1")            // TOML parsing
    // HTTP client (ktor-client-core + ktor-client-cio) dropped along
    // with PackageDownloader's body — to be reinstated as Helidon
    // WebClient (already on :interpreter's classpath) when mez sync is
    // rewired.
    implementation("org.apache.commons:commons-compress:1.28.0") // tar.gz handling

    // Store module for content-addressed storage (SQLite implementation)
    implementation(project(":store-sqlite"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// ----------------------------------------------------------------------------
// Generated BuildInfo
//
// Single source of truth for the version string baked into the binary.
// Reading project.version from the running code is unreliable across the
// JVM jar / native image paths, so we materialize it as Kotlin source at
// build time and let it follow the normal compileKotlin pipeline.
// ----------------------------------------------------------------------------
val generatedSrcDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")

val generateBuildInfo by tasks.registering {
    description = "Generate BuildInfo.kt with version + git SHA pulled from gradle."
    val outputDir = generatedSrcDir
    val versionString = project.version.toString()
    val timestamp = OffsetDateTime.now()
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val gitSha = try {
        val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
    } catch (_: Exception) { "unknown" }

    inputs.property("version", versionString)
    inputs.property("timestamp", timestamp)
    inputs.property("gitSha", gitSha)
    outputs.dir(outputDir)

    doLast {
        val pkgDir = outputDir.get().asFile.resolve("com/mezze/language/cli")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildInfo.kt").writeText("""
            // Generated. Do not edit. Source of truth: cli/build.gradle.kts.
            package com.mezze.language.cli

            object BuildInfo {
                const val VERSION: String = "$versionString"
                const val BUILD_TIMESTAMP: String = "$timestamp"
                const val GIT_SHA: String = "$gitSha"
            }
        """.trimIndent())
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDir(generatedSrcDir)
}

// Wire up the generator into both Kotlin compile + kapt's stub generator.
// kapt's task is registered lazily, so use tasks.matching {} rather than
// named() to avoid "task not found" at config time.
tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }
tasks.matching { it.name == "kaptGenerateStubsKotlin" }.configureEach {
    dependsOn(generateBuildInfo)
}

// Include stdlib mezze files as resources in the JAR
tasks.named<ProcessResources>("processResources") {
    // Avoid using stdlib directory during stdlib:compileKotlin
    dependsOn(":stdlib:compileKotlin")

    from(project.rootDir.resolve("stdlib/std")) {
        include("**/*.mz")
        into("stdlib/std")
    }

    // Generate a manifest of all stdlib files for runtime discovery
    doLast {
        val resourcesDir = sourceSets.main.get().output.resourcesDir
        if (resourcesDir != null) {
            // Create marker file with project root path for auto-detection
            val markerFile = File(resourcesDir, "mezze-project-root.txt")
            markerFile.writeText(project.rootDir.absolutePath)

            // Generate stdlib manifest - list all .mz files relative to stdlib/
            val stdlibDir = project.rootDir.resolve("stdlib")
            val stdlibFiles = fileTree(stdlibDir) {
                include("**/*.mz")
            }.files.map { file ->
                file.relativeTo(stdlibDir).path
            }.sorted()

            val manifestFile = File(resourcesDir, "stdlib/MANIFEST.txt")
            manifestFile.parentFile?.mkdirs()
            manifestFile.writeText(stdlibFiles.joinToString("\n"))
            println("Generated stdlib manifest with ${stdlibFiles.size} files")
        }
    }
}

// Pre-compile stdlib to SQLite at build time.
// Parses + typechecks + lowers all stdlib modules once, stores FunctionIRs in stdlib.sqlite.
// Embedded in the JAR — PrecompiledStdlib loads from it instead of re-parsing on every JVM start.
val compileStdlib by tasks.registering(JavaExec::class) {
    dependsOn(":typechecker:classes", ":store-sqlite:classes", ":parser:classes", ":ast:classes", ":stdlib:classes", ":store:classes", ":interpreter:classes")
    group = "build"
    description = "Pre-compile stdlib to SQLite (FunctionIRs + performer records)"

    // :cli's main runtime classpath deliberately excludes :interpreter
    // (#220 wire-protocol slimming). But the precompile task needs the
    // interpreter's NativeOp DSL classes loaded so NativeSignatures is
    // populated *before* the typechecker emits CallNativeIR — without it
    // every native call in stdlib lowers as record-ABI and the
    // precompiled snapshot is permanently stuck on the slow path.
    // The `precompileOnly` configuration carries :interpreter's runtime
    // jars for just this task — no effect on shadowJar or processResources.
    // See docs/perf/scalar-native-abi-2026-06.md.
    classpath = sourceSets.main.get().runtimeClasspath + precompileOnly

    mainClass.set("com.mezze.language.cli.StdlibPrecompilerKt")

    val outputFile = layout.buildDirectory.file("generated/resources/stdlib.sqlite")
    // StdlibPrecompiler writes stdlib-snapshot.cbor next to stdlib.sqlite —
    // this is the SQLite-free bootstrap path used by the native binary
    // (sqlite-jdbc is stripped from the native classpath; the snapshot
    // CBOR carries the same FunctionIRs + prelude + dependency graph).
    val snapshotFile = layout.buildDirectory.file("generated/resources/META-INF/mezze/stdlib-snapshot.cbor")
    val stdlibDir = rootProject.projectDir

    // The snapshot lands under META-INF/mezze/ — create the dir before
    // StdlibPrecompiler tries to write into it.
    doFirst {
        snapshotFile.get().asFile.parentFile.mkdirs()
    }
    args = listOf(
        outputFile.get().asFile.absolutePath,
        stdlibDir.absolutePath,
        // Third arg = absolute path for the CBOR snapshot. Older
        // StdlibPrecompiler invocations (without this arg) still work —
        // it derives the path from outputFile's parent.
        snapshotFile.get().asFile.absolutePath,
    )
    outputs.file(outputFile)
    outputs.file(snapshotFile)
    inputs.dir(rootProject.projectDir.resolve("stdlib/std"))

    // Needs access to stdlib source files for parsing
    systemProperty("mezze.project.root", stdlibDir.absolutePath)
}

// Include pre-compiled stdlib.sqlite as a JAR resource via the
// generated/resources srcDir. We DON'T wire processResources to depend
// on compileStdlib because compileStdlib already needs the cli's
// runtime classpath (which transitively depends on processResources)
// — that would be circular. Instead, shadowJar adds the generated
// file directly into the JAR root via `from(...)`, bypassing
// processResources entirely.
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}

// Bundle the per-library runtime jars into a single zip resource at
// META-INF/mezze/native-classpath.zip. `mez build --native` extracts this
// at user-app build time and passes the unpacked jars to native-image
// individually — keeping each jar's `module-info.class` intact so
// native-image's Truffle language registration works. Without this,
// native-image gets the merged shadowJar (no module-info) and fails at
// runtime with "No language and polyglot implementation was found".
//
// Pure resource bundling — no extra files on disk for the user.
val bundleNativeClasspath by tasks.registering(Zip::class) {
    group = "build"
    description = "Zip per-library runtime jars for embedding inside `mez`"
    archiveFileName.set("native-classpath.zip")
    // Land the zip where the resources srcDir above will pick it up,
    // under a META-INF prefix so it doesn't collide with other top-level
    // resources.
    destinationDirectory.set(layout.buildDirectory.dir("generated/resources/META-INF/mezze"))
    // Use `precompileOnly` (which pulls :interpreter's full runtime
    // classpath including `truffle-runtime`) rather than :cli's own
    // `runtimeClasspath` — the CLI only declares `truffle-api` directly
    // to keep its own compile classpath slim, but the user native binary
    // needs the full Truffle runtime jar on-cp for `--macro:truffle-svm`
    // to install SubstrateTruffleRuntime. Without truffle-runtime present
    // at native-image build time, the SVM Truffle Feature detects only
    // truffle-api and refuses to substitute in SubstrateTruffleRuntime,
    // so the resulting binary silently falls back to DefaultTruffleRuntime
    // (interpreter-only, no JIT). See #402.
    from(configurations.runtimeClasspath)
    from(configurations.named("precompileOnly"))
    // Drop directory entries — we only need the jar files themselves.
    includeEmptyDirs = false
    // The two configurations resolve some jars in common
    // (kotlin-stdlib, truffle-api, ...); dedupe by jar name at zip
    // time so we don't error on the merge.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// processResources picks up `generated/resources/` via the srcDir above,
// so it implicitly consumes bundleNativeClasspath's output. Make the dep
// explicit so Gradle 8.x's validation doesn't error.
tasks.named("processResources") {
    dependsOn(bundleNativeClasspath)
}

// :cli:test reads StdlibSnapshotData via META-INF/mezze/stdlib-snapshot.cbor
// on the test classpath. The snapshot is produced by compileStdlib, but
// compileStdlib needs sourceSets.main.runtimeClasspath (which includes
// processResources), so processResources must run BEFORE compileStdlib —
// the snapshot misses processResources's copy step. After `gradle clean`,
// the test then sees an empty classpath. Fix: depend on compileStdlib AND
// copy its output into the test classpath at runtime.
tasks.named<Test>("test") {
    dependsOn("compileStdlib")
    val snapshotFile = layout.buildDirectory.file("generated/resources/META-INF/mezze/stdlib-snapshot.cbor")
    val classesOut = layout.buildDirectory.dir("resources/main/META-INF/mezze")
    doFirst {
        val src = snapshotFile.get().asFile
        val destDir = classesOut.get().asFile
        if (src.exists()) {
            destDir.mkdirs()
            src.copyTo(destDir.resolve("stdlib-snapshot.cbor"), overwrite = true)
        }
    }
}

// shadowJar depends on compileStdlib so stdlib.db is always fresh in the JAR
tasks.shadowJar {
    dependsOn("compileStdlib")
    // bundleNativeClasspath embeds the per-library jars as a resource so
    // `mez build --native` can hand original (un-merged) jars to
    // native-image — required for Truffle's language registration to find
    // MezzeLanguageProvider.
    dependsOn(bundleNativeClasspath)
    archiveFileName.set("mezze-lang.jar")
    mergeServiceFiles()
    // Truffle API is a multi-release JAR — JDK 21+ version of JDKAccessor
    // has virtual thread support. Without this, the base class is loaded
    // which throws "VirtualThread is not supported on this JDK".
    manifest {
        attributes("Multi-Release" to "true")
    }
}

// Task to build standalone JARs with Truffle runtime for compiled Mezze programs
tasks.register<Jar>("buildStandaloneJar") {
    group = "build"
    description = "Build a standalone JAR with Truffle runtime for a Mezze program"
    
    // Configure dynamically based on project properties
    val programName = project.findProperty("programName")?.toString() ?: "mezze-program"
    val tempDirPath = project.findProperty("tempDir")?.toString()
    val outputFilePath = project.findProperty("outputFile")?.toString()
    
    archiveBaseName.set(programName)
    
    // Include compiled main class from temp directory
    if (tempDirPath != null) {
        from(File(tempDirPath, "classes"))
    }
    
    // Include our language implementation
    from(sourceSets.main.get().output)
    from(project(":parser").tasks.jar.get().outputs.files.map { zipTree(it) })
    from(project(":typechecker").tasks.jar.get().outputs.files.map { zipTree(it) })
    from(project(":interpreter").tasks.jar.get().outputs.files.map { zipTree(it) })
    
    // Include all runtime dependencies (including Truffle API + truffle-runtime
    // which gives us HotSpotTruffleRuntime — without it Truffle falls back to
    // DefaultTruffleRuntime, i.e. pure interpreter with no JIT).
    from(configurations.runtimeClasspath.get().map { zipTree(it) })

    // Merge service files properly
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest {
        attributes(
            "Main-Class" to "com.mezze.standalone.Main",
            "Implementation-Title" to "Mezze Compiled Program",
            "Implementation-Version" to "1.0.0",
            "Created-By" to "Mezze Compiler",
            // Recommended JVM args for running compiled Mezze programs (long-running, JIT-optimized)
            "Add-Opens" to "java.base/java.lang java.base/java.nio java.base/sun.nio.ch",
            "Mezze-Recommended-Args" to "--enable-native-access=ALL-UNNAMED -XX:+UseG1GC -XX:+TieredCompilation -XX:ReservedCodeCacheSize=256m -Dpolyglot.engine.Mode=throughput"
        )
    }
    
    // Set output file location if specified
    if (outputFilePath != null) {
        destinationDirectory.set(File(outputFilePath).parentFile)
        archiveFileName.set(File(outputFilePath).name)
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("mez")
            mainClass.set("com.mezze.language.cli.MainKt")

            // Get zlib path from environment or use default
            val zlibLibPath = System.getenv("ZLIB_LIB_PATH") ?: ""

            val args = mutableListOf(
                // Unlock experimental options FIRST
                "-H:+UnlockExperimentalVMOptions",

                // Core native image settings for GraalVM 24.0.2
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:+AllowVMInspection",
                "-H:-CheckToolchain",

                // JDK 24+: System.load is a restricted method. SQLite JDBC
                // calls it during cache.sqlite open. Without this, every
                // `mez` invocation prints a JVM warning before any user
                // output. Mirrors what NativeBuildPath sets for user-app
                // builds — the toolchain itself needed the same.
                "--enable-native-access=ALL-UNNAMED",

                // Optimization level (smaller binary + faster code)
                "-O2"
            )

            // Tell native-image to use the system C compiler (resolves macOS SDK
            // headers in nix environments where cc is a clang wrapper with sysroot).
            val resolvedCc = try {
                ProcessBuilder("which", System.getenv("CC") ?: "cc")
                    .start().inputStream.bufferedReader().readLine()?.trim()
            } catch (_: Exception) { null }
            if (resolvedCc != null) {
                args.add("-H:CCompilerPath=$resolvedCc")
            }

            // Pass library search paths to the linker (nix stores zlib etc. in /nix/store).
            val libPaths = listOfNotNull(
                zlibLibPath.ifEmpty { null },
                *System.getenv("LIBRARY_PATH")?.split(":")?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray()
            ).distinct()
            for (libDir in libPaths) {
                args.add("-H:NativeLinkerOption=-L$libDir")
            }

            // Check for PGO profile file
            val pgoProfile = project.file("default.iprof")
            val usePgo = project.hasProperty("pgo") || pgoProfile.exists()
            val instrumentPgo = project.hasProperty("pgo-instrument")

            if (instrumentPgo) {
                args.add("--pgo-instrument")
                println("Building with PGO instrumentation - run workloads then rebuild with -Ppgo")
            } else if (usePgo && pgoProfile.exists()) {
                args.add("--pgo=${pgoProfile.absolutePath}")
                println("Building with PGO profile: ${pgoProfile.absolutePath}")
            }

            // Shared native-image flags live in a sectioned text file so
            // the CLI's own build and the user-app build path
            // (NativeBuildPath.kt) stay in sync. See the file's header
            // for format; both consumers parse it identically.
            val sharedFlags = parseNativeImageFlags(
                file("src/main/resources/META-INF/mezze/native-image-flags.txt")
            )

            // Common (resource includes, charsets, Truffle inlining).
            args.addAll(sharedFlags["common"].orEmpty())

            // Init-at-build-time packages: shared list + the CLI binary
            // has no extra entries today.
            for (pkg in sharedFlags["init-at-build-time"].orEmpty()) {
                args.add("--initialize-at-build-time=$pkg")
            }

            // Resources only the CLI binary embeds (e.g. native-classpath.zip,
            // consumed by `mez build --native` at user-build time).
            args.addAll(sharedFlags["cli-only-resources"].orEmpty())

            // Raw native-image flags only the CLI binary uses
            // (--macro:truffle-svm for JIT — see flags file).
            args.addAll(sharedFlags["cli-only-flags"].orEmpty())

            // G1GC only supported on Linux
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("linux")) {
                args.add("--gc=G1")
            }


            buildArgs.addAll(args)
        }
    }
}

// Both JAR and native builds depend on compileStdlib for fresh stdlib.db
tasks.named("jar") {
    dependsOn("compileStdlib")
}
tasks.named("nativeCompile") {
    dependsOn("compileStdlib")
}

// ============================================================================
// Install / clean tasks
//
// Single source of truth for putting build outputs into ~/.mezze/. The
// scripts/refresh.sh wrapper just invokes these. CI / docs / tooling should
// also call these gradle tasks rather than re-implementing the logic.
// ============================================================================

val mezzeHome = providers.systemProperty("user.home").map { File(it, ".mezze") }

/**
 * Install `mezze-runtime.jar` (the slim runtime fat-jar) into
 * ~/.mezze/lib/. This is the only library artifact users need at run
 * time:
 *
 *   - `mez dev` / `mez exec` spawn a JVM child loading this jar to
 *     run user code via the wire protocol.
 *   - `mez build` clones this jar to template user-app JVM jars
 *     (`BuildCommand.createStandaloneJar`).
 *
 * The old `mezze-lang.jar` (the full CLI fat-jar) is no longer
 * installed — DevRunner has been deleted, so nothing at run time
 * needs the parser/typechecker/LSP classes embedded in it. The
 * `:cli:shadowJar` task still exists because native-image consumes
 * its output at build time, but the resulting jar is not shipped.
 */
tasks.register<Copy>("installRuntime") {
    group = "install"
    description = "Install mezze-runtime.jar into ~/.mezze/lib/"
    dependsOn(":interpreter:runtimeShadowJar")
    from(project(":interpreter").layout.buildDirectory.file("libs/mezze-runtime.jar"))
    into(mezzeHome.map { File(it, "lib") })
}

// A separate configuration that pulls in the GraalVM Truffle dev tools
// (chromeinspector, profiler, coverage, dap, insight) without putting
// them on the main runtime classpath. The slim mezze-runtime.jar
// deliberately excludes these so user-app builds stay small; we install
// them as separate jars under ~/.mezze/lib/tools/ and WireRunner adds
// that dir to the classpath only when --inspect / --profile / etc.
val devTools by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    devTools("org.graalvm.tools:profiler-tool:25.0.3")
    devTools("org.graalvm.tools:chromeinspector-tool:25.0.3")
    devTools("org.graalvm.tools:dap-tool:25.0.3")
    devTools("org.graalvm.tools:coverage-tool:25.0.3")
    devTools("org.graalvm.tools:insight-tool:25.0.3")
}

/**
 * Install Truffle dev-tool jars to ~/.mezze/lib/tools/.
 *
 * Only loaded when the user passes --inspect / --profile / --coverage /
 * --allocations / --insight / --trace-jit to mez dev or mez exec. The
 * slim mezze-runtime.jar bundled into user-app builds stays small (no
 * 20-30 MB of profiler/inspector machinery in every distributed app).
 */
tasks.register<Copy>("installDevTools") {
    group = "install"
    description = "Install Truffle dev-tool jars (~20MB) to ~/.mezze/lib/tools/"
    from(devTools) {
        // The configuration resolves to many jars including transitive
        // graal-sdk / truffle-api dups that are already in the runtime
        // jar. Skip those — only ship the tool-specific bits.
        include("profiler-*.jar", "chromeinspector-*.jar", "dap-tool-*.jar",
                "coverage-*.jar", "insight-tool-*.jar",
                // Tools depend on org.graalvm.shadowed.* packages so include those too
                "icu4j-*.jar", "regex-*.jar")
    }
    into(mezzeHome.map { File(it, "lib/tools") })
    // Wipe the dir before copying so old tool jars from prior installs
    // don't accumulate across upgrades.
    doFirst {
        val toolsDir = File(mezzeHome.get(), "lib/tools")
        if (toolsDir.exists()) toolsDir.listFiles()?.forEach { it.delete() }
    }
}

/**
 * Install stdlib sources to ~/.mezze/stdlib/ so cmd-click on imported
 * stdlib symbols can land at a stable file path. Without these on
 * disk, the runtime's resource-extraction fallback writes to
 * randomly-named temp files (see ModuleResolver.resolveModuleFile),
 * which the editor can't reuse across sessions.
 *
 * Mirrors the on-disk layout the in-project resolver expects:
 *   ~/.mezze/stdlib/std/...    (matches `stdlib/std/...` in repo)
 */
tasks.register<Copy>("installStdlibSources") {
    group = "install"
    description = "Install stdlib .mz sources into ~/.mezze/stdlib/ for go-to-definition"
    // Hard dependencies — stdlib's resources/classes producers happen to
    // touch the same source dir, and gradle 8.x flags any task that reads
    // their output dir without declaring it. Use mustRunAfter (we don't
    // *need* the outputs, we just want gradle to stop complaining).
    mustRunAfter(":stdlib:processResources", ":stdlib:compileKotlin")
    from(rootProject.projectDir.resolve("stdlib")) {
        include("**/*.mz")
    }
    into(mezzeHome.map { File(it, "stdlib") })
}

/**
 * Install the full mez toolchain into ~/.mezze/. ~3 minutes the first
 * time; faster on subsequent runs thanks to incremental compile.
 *
 * Three artifacts go down:
 *   - ~/.mezze/bin/mez               native binary (AOT-compiled CLI)
 *   - ~/.mezze/lib/mezze-runtime.jar slim runtime jar (JVM child for
 *                                    mez dev / mez exec; templated by
 *                                    mez build for user app jars)
 *   - ~/.mezze/stdlib/std/...        stdlib .mz sources for editor
 *                                    go-to-definition (LSP)
 *
 * The native binary is NOT self-contained: it spawns a JVM child for
 * `mez dev` / `mez exec` to execute user code (Truffle/Loom need a real
 * JVM). Coupling installRuntime ensures users never end up with a
 * binary that can't find its runtime jar.
 */
tasks.register("installNative") {
    group = "install"
    description = "Install native `mez` binary + runtime jar + stdlib sources into ~/.mezze/"
    dependsOn("installRuntime", "installDevTools", "installStdlibSources", "nativeCompile")
    doLast {
        // rm-then-cp to defeat the macOS launch-services kernel cache —
        // overwriting an existing `mez` in place hangs because the cached
        // codesign signature is stale. Deleting first invalidates that
        // cache so the new inode gets a fresh evaluation.
        val target = File(mezzeHome.get(), "bin/mez")
        target.parentFile.mkdirs()
        if (target.exists()) target.delete()
        val src = layout.buildDirectory.file("native/nativeCompile/mez").get().asFile
        src.copyTo(target, overwrite = true)
        target.setExecutable(true, false)
    }
}

/**
 * Wipe everything: build outputs that gradle's incremental sometimes
 * keeps stale, plus the previously-installed runtime. Use when you
 * suspect a build artifact is carrying forward (e.g. the empty
 * stdlib.sqlite issue we hit during the three-file migration).
 *
 * Lives next to `clean` so `gradle clean cleanInstall` is the obvious
 * full reset.
 */
tasks.register<Delete>("cleanInstall") {
    group = "install"
    description = "Wipe ~/.mezze/lib, ~/.mezze/cache, ~/.mezze/bin/mez and the cli build outputs"
    delete(
        // Repo-side: stale gradle outputs.
        layout.buildDirectory.dir("libs"),
        layout.buildDirectory.dir("native"),
        layout.buildDirectory.file("resources/main/stdlib.sqlite"),
        layout.buildDirectory.file("generated/resources/stdlib.sqlite"),
        // User-side: previously-installed runtime + the legacy stdlib
        // sidecar (no longer read by anything; safe to remove).
        mezzeHome.map { File(it, "lib") },
        mezzeHome.map { File(it, "cache") },
        mezzeHome.map { File(it, "bin/mez") }
    )
}

/**
 * Parse the shared native-image flags file used by both this build
 * and the user-app build path. Format matches
 * `NativeImageFlags.parse` exactly (sections in `[brackets]`, `#`
 * comments, blank lines ignored).
 */
fun parseNativeImageFlags(file: File): Map<String, List<String>> {
    val sections = LinkedHashMap<String, MutableList<String>>()
    var current: MutableList<String>? = null
    for (rawLine in file.readLines()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            val name = line.substring(1, line.length - 1)
            current = sections.getOrPut(name) { mutableListOf() }
            continue
        }
        current?.add(line)
    }
    return sections
}

/**
 * Prints the classpath the WASM build needs, one path per line joined by ":".
 *
 * That is :cli's runtime classpath PLUS precompileOnly. The CLI keeps
 * :interpreter off its normal classpath on purpose (it reaches those classes
 * by string literal through Truffle), but MezzeWeb calls MezzeLanguage
 * directly, so the browser build genuinely needs both.
 */
tasks.register("printRuntimeClasspath") {
    val cp = sourceSets.main.get().runtimeClasspath + precompileOnly
    // Depend on the classpath, not just resolve it: jar names carry a build
    // timestamp, so merely printing them yields paths for jars that were
    // never built. Everything named here exists by the time it is printed.
    dependsOn(cp)
    doLast { println(cp.asPath) }
}
