plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":ast"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    // CBOR for StdlibRuntimeSnapshot — the runtime-only slice of the
    // precompiled stdlib that the interpreter consumes without
    // depending on :typechecker.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")
}

kotlin {
    jvmToolchain(25)
}

// Include .mz stdlib files as resources so they're available on the classpath
// for runtime parsing (e.g., BuiltInAbilities loading ability declarations)
sourceSets {
    main {
        resources {
            srcDir(".")
            include("std/**/*.mz")
        }
    }
}

// Every stdlib module, for the compiler's registry of stdlib identities
// (StdlibIdentities): a stdlib public type is nameable without an import, so
// the registry must know all of them, not only the modules loaded so far.
tasks.named<ProcessResources>("processResources") {
    val stdDir = projectDir.resolve("std")
    inputs.dir(stdDir)
    doLast {
        val out = destinationDir.resolve("std/MANIFEST.txt")
        out.parentFile.mkdirs()
        out.writeText(
            fileTree(stdDir) { include("**/*.mz") }.files
                .map { "std/" + it.relativeTo(stdDir).invariantSeparatorsPath }
                .sorted()
                .joinToString("\n")
        )
    }
}
