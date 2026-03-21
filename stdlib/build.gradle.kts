plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":ast"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

kotlin {
    jvmToolchain(24)
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
