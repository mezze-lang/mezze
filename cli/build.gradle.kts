plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("kapt") // Add for Truffle annotation processing
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

application {
    mainClass.set("com.funk.language.cli.MainKt")
    applicationName = "funk"
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":interpreter"))

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
}

// Include stdlib funk files as resources in the JAR
tasks.named<ProcessResources>("processResources") {
    from(project.rootDir.resolve("stdlib")) {
        include("**/*.funk")
        into("stdlib")
    }

    // Create a marker file with the project root path for auto-detection
    doLast {
        val resourcesDir = sourceSets.main.get().output.resourcesDir
        if (resourcesDir != null) {
            val markerFile = File(resourcesDir, "funk-project-root.txt")
            markerFile.writeText(project.rootDir.absolutePath)
        }
    }
}

tasks.shadowJar {
    archiveFileName.set("funk.jar")
    mergeServiceFiles()
}

// Task to build standalone JARs with Truffle runtime for compiled Funk programs
tasks.register<Jar>("buildStandaloneJar") {
    group = "build"
    description = "Build a standalone JAR with Truffle runtime for a Funk program"
    
    // Configure dynamically based on project properties
    val programName = project.findProperty("programName")?.toString() ?: "funk-program"
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
            "Main-Class" to "com.funk.standalone.Main",
            "Implementation-Title" to "Funk Compiled Program",
            "Implementation-Version" to "1.0.0",
            "Created-By" to "Funk Compiler"
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
            imageName.set("funk")
            mainClass.set("com.funk.language.cli.MainKt")

            // Get zlib path from environment or use default
            val zlibLibPath = System.getenv("ZLIB_LIB_PATH") ?: ""

            val args = mutableListOf(
                // Unlock experimental options FIRST
                "-H:+UnlockExperimentalVMOptions",

                // Core native image settings for GraalVM 24.0.2
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:+AllowVMInspection",
                "-H:-CheckToolchain"
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
                "-H:IncludeResources=stdlib/.*\\.funk",

                // Include all charsets for language support
                "-H:+AddAllCharsets",
                
                // Debug output
                "-H:+PrintClassInitialization",
                
                // Initialize our language classes at build time
                "--initialize-at-build-time=com.funk.language.interpreter.FunkLanguageProvider",
                "--initialize-at-build-time=com.funk.language.interpreter.FunkValueGen\$InteropLibraryExports",
                
                // Initialize all our interpreter classes at build time (safer approach)
                "--initialize-at-build-time=com.funk.language.interpreter"
            ))

            buildArgs.addAll(args)
        }
    }
}
