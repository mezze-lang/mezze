import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// The GraalVM/Truffle line this build bundles. Single source of truth for the
// Truffle/graal-sdk dependency versions AND the value baked into BuildInfo so
// the runtime GraalVM resolver can compare a host GraalVM against what we want
// (matching line → JIT + native; different line → degrade with a warning).
// `graalLine` is the major.minor ("25.0") used for that comparison and for the
// ~/.mezze/graalvm/<line> provisioning slot.
val graalVersion = "25.0.3"
val graalLine = graalVersion.split(".").take(2).joinToString(".")

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
    implementation(project(":langservice"))
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

    // Apache Aether — maven-coord resolution for `[maven]` entries in
    // Mezze.toml. Cache lives under `~/.mezze/cache/maven/`, isolated
    // from the user's `~/.m2/`; default remote is Maven Central plus
    // any `[[maven-repo]]` the manifest declares.
    implementation("org.apache.maven.resolver:maven-resolver-api:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-spi:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-util:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-transport-file:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.20")
    // Explicit wiring for the RepositorySystem. The ServiceLocator it replaces
    // instantiates services REFLECTIVELY, which native-image cannot do without
    // per-class config — `mez sync` with `[maven]` deps died on the installed
    // native binary with "RepositorySystem missing from service locator" while
    // working fine on the JVM. This supplier wires the same graph with plain
    // constructor calls, so it needs no native-image configuration at all.
    implementation("org.apache.maven.resolver:maven-resolver-supplier:1.9.20")
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")

    // Store module for content-addressed storage (SQLite implementation)
    implementation(project(":store-sqlite"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // TEST-ONLY: the CLI deliberately does NOT compile against :interpreter
    // (see the NOTE above — it is referenced by string literal at run time).
    // PolyglotConfigContractTest is the one place that imports both the CLI's
    // `PolyglotResolver.Resolved` and the interpreter's `ResolvedPolyglot` to
    // prove the two hand-synced manifest parsers agree on the same Mezze.toml.
    // This never reaches the production/shadowJar classpath.
    testImplementation(project(":interpreter"))
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

    val graalVersionProp = graalVersion
    val graalLineProp = graalLine

    inputs.property("version", versionString)
    inputs.property("timestamp", timestamp)
    inputs.property("gitSha", gitSha)
    inputs.property("graalVersion", graalVersionProp)
    inputs.property("graalLine", graalLineProp)
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

                /** Full GraalVM/Truffle version this build bundles (e.g. "25.0.3"). */
                const val GRAAL_VERSION: String = "$graalVersionProp"

                /** GraalVM line (major.minor, e.g. "25.0") a host GraalVM must match
                 *  for JIT + native-image; a different line degrades with a warning. */
                const val GRAAL_LINE: String = "$graalLineProp"
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
    // Sibling schema fingerprint StdlibPrecompiler writes next to the cbor; checked
    // at load before decode to reject a schema-skewed snapshot cleanly.
    outputs.file(layout.buildDirectory.file("generated/resources/META-INF/mezze/stdlib-snapshot.fp"))
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
    val fingerprintFile = layout.buildDirectory.file("generated/resources/META-INF/mezze/stdlib-snapshot.fp")
    val classesOut = layout.buildDirectory.dir("resources/main/META-INF/mezze")
    doFirst {
        val src = snapshotFile.get().asFile
        val destDir = classesOut.get().asFile
        if (src.exists()) {
            destDir.mkdirs()
            src.copyTo(destDir.resolve("stdlib-snapshot.cbor"), overwrite = true)
        }
        val fpSrc = fingerprintFile.get().asFile
        if (fpSrc.exists()) {
            destDir.mkdirs()
            fpSrc.copyTo(destDir.resolve("stdlib-snapshot.fp"), overwrite = true)
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

            // C-compiler override + linker search paths are for unix/nix toolchains
            // ONLY. On Windows, native-image must detect MSVC (cl.exe) itself — and
            // `which cc` on a GitHub Windows runner returns the MSYS `/c/mingw64/bin/cc`,
            // which forcing via -H:CCompilerPath makes native-image reject ("does not
            // specify a path to an executable"). So skip the whole block on Windows.
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            if (!isWindows) {
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

            // Probe `native-image --version` to detect Oracle vs CE and
            // glibc version issues.
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("linux")) {
                val niVersionOut = try {
                    ProcessBuilder("native-image", "--version")
                        .redirectErrorStream(true).start()
                        .inputStream.bufferedReader().readText()
                } catch (_: Exception) { "" }

                // G1GC requires Oracle GraalVM (not CE).
                if (niVersionOut.contains("Oracle GraalVM")) {
                    args.add("--gc=G1")
                }

                // glibc >= 2.42 broke static libm: ifunc dispatchers in
                // libm-2.42.a reference `_dl_x86_cpu_features`, a symbol
                // only defined inside the dynamic linker. native-image
                // links libm.a explicitly, causing "undefined reference".
                // Fix: compile a stub that provides a zero-initialized
                // _dl_x86_cpu_features — ifunc dispatchers select the
                // generic (non-SIMD) math path. The Mezze runtime never
                // calls transcendental math in hot paths.
                val stubDir = layout.buildDirectory.dir(
                    "native-stubs").get().asFile
                stubDir.mkdirs()
                val stubSrc = File(stubDir, "dl_cpu_features_stub.c")
                stubSrc.writeText(
                    "/* glibc 2.42+ static libm ifunc stub */\n" +
                    "__attribute__((visibility(\"default\")))\n" +
                    "char _dl_x86_cpu_features[512] __attribute__((aligned(64)));\n"
                )
                val stubObj = File(stubDir, "dl_cpu_features_stub.o")
                val ccBin = System.getenv("CC") ?: "cc"
                val stubRc = ProcessBuilder(ccBin, "-c", "-fPIC",
                    "-o", stubObj.absolutePath, stubSrc.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                if (stubRc == 0 && stubObj.exists()) {
                    args.add("-H:NativeLinkerOption=${stubObj.absolutePath}")
                }
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

// Each install goes into its own versioned toolchain dir; a proxy on the PATH
// (~/.mezze/bin/mez) dispatches to the pinned one (see ProxyDispatch). This is
// the layout `mez toolchain` manages and lets several releases coexist.
val mezzeToolchainVersion: String = project.version.toString()
val toolchainDir = mezzeHome.map { File(it, "toolchains/$mezzeToolchainVersion") }

/**
 * Install `mezze-runtime.jar` (the slim runtime fat-jar) into the
 * VERSIONED toolchain lib — `~/.mezze/toolchains/<version>/lib/`, see
 * `toolchainDir` below, not the unversioned `~/.mezze/lib/` this comment
 * used to claim.
 *
 * That distinction bites: `JvmLauncher.findRuntimeJar` searches the
 * UNVERSIONED `~/.mezze/lib/` first, so a jar left there by an older release
 * shadows every versioned install and this task can never replace it. An old
 * provider-bundled jar in that position put 19,157 GraalPy classes into every
 * `mez build` — ~100MB app jars, and GraalPy reachable in every native image
 * (see C5/W-C2). `NativeBuildPath` now strips the providers and says so.
 *
 * This is the only library artifact users need at run time:
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
    description = "Install mezze-runtime.jar into the versioned toolchain's lib/"
    dependsOn(":interpreter:runtimeShadowJar")
    from(project(":interpreter").layout.buildDirectory.file("libs/mezze-runtime.jar"))
    into(toolchainDir.map { File(it, "lib") })
}

// Stage mezze-runtime.jar into the DEV installDist output's lib/ too, so a
// `just build` dev launcher (cli/build/install/mez/bin/mez) finds its runtime via
// MezzeHome.selfLib (bin/mez -> ../lib) — without it, `mez dev` from a dev build
// fell back to ~/.mezze/lib (only the installed toolchain has the jar) and
// errored "mezze-runtime.jar not found". installDist depends on this below.
tasks.register<Copy>("installRuntimeDev") {
    group = "install"
    description = "Install mezze-runtime.jar into the dev installDist lib/ (so `mez dev` works from a dev build)"
    dependsOn(":interpreter:runtimeShadowJar")
    from(project(":interpreter").layout.buildDirectory.file("libs/mezze-runtime.jar"))
    into(layout.buildDirectory.dir("install/mez/lib"))
}
tasks.named("installDist") { finalizedBy("installRuntimeDev") }

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
    into(toolchainDir.map { File(it, "lib/tools") })
    // Wipe the dir before copying so old tool jars from prior installs
    // don't accumulate across upgrades.
    doFirst {
        val toolsDir = File(toolchainDir.get(), "lib/tools")
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
    // Flat (shared) copy — the fallback the LSP uses for a dev/JVM launch or a
    // pre-versioned install. The per-toolchain copy below is preferred when
    // present (so a pinned project cmd-clicks into ITS version's stdlib source).
    into(mezzeHome.map { File(it, "stdlib") })
}

/**
 * Per-toolchain stdlib sources at ~/.mezze/toolchains/<version>/stdlib, so LSP
 * go-to-definition on a `std::…` symbol lands in the source of the toolchain the
 * project is pinned to — not whichever version last wrote the flat copy. The
 * runtime still compiles from the binary's embedded snapshot; this is display
 * only (MezzeTextDocumentService.toolchainStdlibDir).
 */
tasks.register<Copy>("installToolchainStdlib") {
    group = "install"
    description = "Install stdlib .mz sources into the versioned toolchain's stdlib/"
    mustRunAfter(":stdlib:processResources", ":stdlib:compileKotlin")
    from(rootProject.projectDir.resolve("stdlib")) {
        include("**/*.mz")
    }
    into(toolchainDir.map { File(it, "stdlib") })
}

/**
 * Install the full mez toolchain into ~/.mezze/ as a *versioned* toolchain,
 * plus the PATH proxy. ~3 minutes the first time; faster after thanks to
 * incremental compile.
 *
 * Layout (version = project version, e.g. `nightly-…` or `-Pversion=0.1.3`):
 *   - ~/.mezze/toolchains/<version>/bin/mez           native binary (AOT CLI)
 *   - ~/.mezze/toolchains/<version>/lib/mezze-runtime.jar   slim runtime jar
 *                                    (JVM child for mez dev/exec; templated by
 *                                    mez build for user app jars)
 *   - ~/.mezze/toolchains/<version>/lib/tools/...     Truffle dev tools
 *   - ~/.mezze/bin/mez               proxy on the PATH → dispatches to the
 *                                    pinned toolchain (see ProxyDispatch)
 *   - ~/.mezze/stdlib/std/...        stdlib .mz sources (flat; LSP go-to-def)
 *   - ~/.mezze/settings.toml         seeded `default = "<version>"` if unset
 *
 * Installing several versions this way lets them coexist; `mez toolchain`
 * lists/pins them and the proxy picks the right one per project. The native
 * binary is NOT self-contained: it spawns a JVM child for `mez dev` / `mez exec`
 * (Truffle/Loom need a real JVM), so its runtime jar ships alongside it in the
 * same versioned lib/.
 */
tasks.register("installNative") {
    group = "install"
    description = "Install the versioned mez toolchain + PATH proxy into ~/.mezze/"
    dependsOn("installRuntime", "installDevTools", "installStdlibSources", "installToolchainStdlib", "nativeCompile")
    doLast {
        // Executable name is platform-specific (native-image emits mez.exe on
        // Windows); the proxy detects itself by extension (ProxyDispatch).
        val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "mez.exe" else "mez"
        val src = layout.buildDirectory.file("native/nativeCompile/$exe").get().asFile

        // rm-then-cp to defeat the macOS launch-services kernel cache —
        // overwriting an existing `mez` in place hangs because the cached
        // codesign signature is stale. Deleting first invalidates that cache.
        fun install(target: File) {
            target.parentFile.mkdirs()
            if (target.exists()) target.delete()
            src.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
        }

        // 1. The versioned toolchain binary.
        install(File(toolchainDir.get(), "bin/$exe"))
        // 2. The PATH proxy — a copy of the same binary; ProxyDispatch keys off
        //    its path (~/.mezze/bin/mez), not its version, so any copy works and
        //    the newest install always refreshes it.
        install(File(mezzeHome.get(), "bin/$exe"))

        // 3. Point the global default at the version just installed — ALWAYS, not
        //    only on a first install. The proxy (~/.mezze/bin/mez) resolves through
        //    this setting, so leaving a stale `default` meant `mez`, and every LSP
        //    launched through it, kept running an older toolchain while the repo
        //    was rebuilt. That is invisible from the CLI's own output and cost a
        //    day of chasing a "dispatch bug" that was a stale binary (BUG 1c).
        //    An explicit `mez toolchain default <v>` afterwards still wins; this
        //    only guarantees a freshly-installed toolchain is the one that runs.
        val settings = File(mezzeHome.get(), "settings.toml")
        settings.parentFile.mkdirs()
        val defaultLine = "default = \"$mezzeToolchainVersion\""
        val previousDefault = if (settings.exists()) {
            settings.readLines().firstOrNull { it.trimStart().startsWith("default") }
        } else null
        val kept = if (settings.exists()) {
            settings.readLines().filterNot { it.trimStart().startsWith("default") }
        } else emptyList()
        settings.writeText((kept + defaultLine).joinToString("\n").trimStart('\n') + "\n")

        logger.lifecycle("Installed toolchain $mezzeToolchainVersion → ${toolchainDir.get()}")
        logger.lifecycle("Proxy → ${File(mezzeHome.get(), "bin/mez")}  (put ~/.mezze/bin on PATH)")
        if (previousDefault != null && !previousDefault.contains(mezzeToolchainVersion)) {
            logger.lifecycle("Default toolchain → $mezzeToolchainVersion  (was ${previousDefault.substringAfter('=').trim()})")
        } else {
            logger.lifecycle("Default toolchain → $mezzeToolchainVersion")
        }
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
    description = "Wipe installed toolchains, proxy, cache, settings + the cli build outputs"
    delete(
        // Repo-side: stale gradle outputs.
        layout.buildDirectory.dir("libs"),
        layout.buildDirectory.dir("native"),
        layout.buildDirectory.file("resources/main/stdlib.sqlite"),
        layout.buildDirectory.file("generated/resources/stdlib.sqlite"),
        // User-side: every installed toolchain, the proxy, and the resolver's
        // global default — plus the legacy flat `lib` from pre-versioned
        // installs (no longer read; safe to remove).
        mezzeHome.map { File(it, "toolchains") },
        mezzeHome.map { File(it, "bin/mez") },
        mezzeHome.map { File(it, "settings.toml") },
        mezzeHome.map { File(it, "lib") },
        mezzeHome.map { File(it, "cache") },
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
