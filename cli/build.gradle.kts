plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("kapt") // Add for Truffle annotation processing
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
    jvmToolchain(24)
}

dependencies {
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":interpreter"))
    implementation(project(":datastructures"))
    implementation(project(":lsp"))
    implementation(project(":formatter"))

    // Polyglot API (Context, Value, Source) - host API - match native-image version
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")

    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.2") // Add annotation processor

    // Serialization for enhanced AST and stdlib pre-compilation
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")

    // Package manager dependencies
    implementation("io.github.z4kn4fein:semver:2.0.0")           // Semantic versioning
    implementation("com.akuleshov7:ktoml-core:0.7.1")            // TOML parsing
    implementation("io.ktor:ktor-client-core:2.3.12")            // HTTP client
    implementation("io.ktor:ktor-client-cio:2.3.12")             // CIO engine for Ktor
    implementation("org.apache.commons:commons-compress:1.28.0") // tar.gz handling

    // Codebase module for content-addressed storage
    implementation(project(":codebase"))
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
    dependsOn(":typechecker:classes", ":codebase:classes", ":parser:classes", ":ast:classes", ":stdlib:classes")
    group = "build"
    description = "Pre-compile stdlib to SQLite (FunctionIRs + performer records)"

    classpath = sourceSets.main.get().runtimeClasspath

    mainClass.set("com.mezze.language.cli.StdlibPrecompilerKt")

    val outputFile = layout.buildDirectory.file("generated/resources/stdlib.sqlite")
    val stdlibDir = rootProject.projectDir

    args = listOf(outputFile.get().asFile.absolutePath, stdlibDir.absolutePath)
    outputs.file(outputFile)
    inputs.dir(rootProject.projectDir.resolve("stdlib/std"))

    // Needs access to stdlib source files for parsing
    systemProperty("mezze.project.root", stdlibDir.absolutePath)
}

// Include pre-compiled stdlib.sqlite as a JAR resource
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}

// processResources copies the generated stdlib.sqlite into resources/main/.
// It must run AFTER compileStdlib produces the file; otherwise it copies an
// empty (or stale) version. Without this, shadowJar bundles the wrong bytes
// and the project's `.mz/stdlib.sqlite` ends up empty on first open.
tasks.processResources {
    dependsOn("compileStdlib")
}

// shadowJar depends on compileStdlib so stdlib.db is always fresh in the JAR
tasks.shadowJar {
    dependsOn("compileStdlib")
    archiveFileName.set("mezze.jar")
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
    
    // Include all runtime dependencies (including Truffle API)
    from(configurations.runtimeClasspath.get().map { zipTree(it) })
    
    // Include Truffle compiler JAR for JIT compilation
    val truffleCompilerJar = File(System.getenv("JAVA_HOME"), "lib/truffle/truffle-compiler.jar")
    if (truffleCompilerJar.exists()) {
        from(zipTree(truffleCompilerJar))
        println("✅ Including Truffle JIT compiler: ${truffleCompilerJar.name}")
    } else {
        println("⚠️  Truffle compiler JAR not found at: ${truffleCompilerJar}")
    }
    
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
            imageName.set("mezze")
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

            args.addAll(listOf(
                // Essential Polyglot support
                "-H:IncludeResources=META-INF/truffle-languages/.*",
                "-H:IncludeResources=META-INF/services/.*",

                // Include stdlib as resources (embedded in native binary)
                "-H:IncludeResources=stdlib/.*\\.mz",
                "-H:IncludeResources=stdlib/MANIFEST.txt",
                "-H:IncludeResources=stdlib\\.db",  // Pre-compiled stdlib SQLite

                // Include all charsets for language support
                "-H:+AddAllCharsets",

                // Initialize V2 interpreter classes at build time
                "--initialize-at-build-time=com.mezze.interpreter",

                // Initialize datastructures classes at build time (MezzeSeq, MezzeList, etc.)
                "--initialize-at-build-time=com.mezze.datastructures",

                // Initialize kotlin and kotlinx classes at build time
                // Required for AST serialization and related static initializers
                "--initialize-at-build-time=kotlin",
                "--initialize-at-build-time=kotlinx",

                // Parser and AST classes
                "--initialize-at-build-time=com.mezze.language.parser",
                "--initialize-at-build-time=com.mezze.language.ast",

                // Truffle runtime optimizations
                "-H:+TruffleHostInlining"
            ))

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
