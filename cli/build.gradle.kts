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
    // Suppress Truffle/GraalVM warnings on JDK 21+
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
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
    implementation(project(":lsp"))

    // Polyglot API (Context, Value, Source) - host API - match native-image version
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")

    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.2") // Add annotation processor

    // JSON serialization for enhanced AST
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

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
    from(project.rootDir.resolve("stdlib")) {
        include("**/*.mz")
        into("stdlib")
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

tasks.shadowJar {
    archiveFileName.set("mezze.jar")
    mergeServiceFiles()
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
            "Created-By" to "Mezze Compiler"
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

            // Add zlib library path if available
            if (zlibLibPath.isNotEmpty()) {
                args.add("-H:NativeLinkerOption=-L$zlibLibPath")
                args.add("-H:NativeLinkerOption=-Wl,-rpath,$zlibLibPath")
            }

            args.addAll(listOf(
                // Essential Polyglot support
                "-H:IncludeResources=META-INF/truffle-languages/.*",
                "-H:IncludeResources=META-INF/services/.*",

                // Include stdlib as resources (embedded in native binary)
                "-H:IncludeResources=stdlib/.*\\.mz",

                // Include all charsets for language support
                "-H:+AddAllCharsets",

                // Initialize V2 interpreter classes at build time
                "--initialize-at-build-time=com.mezze.interpreter.v2",

                // Initialize kotlin and kotlinx classes at build time
                // Required for AST serialization and related static initializers
                "--initialize-at-build-time=kotlin",
                "--initialize-at-build-time=kotlinx"
            ))

            buildArgs.addAll(args)
        }
    }
}
