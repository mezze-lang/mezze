plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

application {
    mainClass.set("com.funk.language.cli.MainKt")
    applicationName = "funk"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"  // GraalVM 24.0.2 supports JDK 21 features
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":interpreter"))
    
    // Polyglot API (Context, Value, Source) - host API - match native-image version
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")
    
    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
}

tasks.shadowJar {
    archiveFileName.set("funk.jar")
    mergeServiceFiles()
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("funk")
            mainClass.set("com.funk.language.cli.MainKt")
            buildArgs.addAll(
                // Core native image settings for GraalVM 24.0.2
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-H:+AllowVMInspection",
                "-H:-CheckToolchain",
                
                // Essential Polyglot support
                "-H:IncludeResources=META-INF/truffle-languages/.*",
                "-H:IncludeResources=META-INF/services/.*",
                
                // Enable experimental options
                "-H:+UnlockExperimentalVMOptions",
                
                // Include all charsets for language support
                "-H:+AddAllCharsets",
                
                // Debug output
                "-H:+PrintClassInitialization",
                
                // Initialize our language classes at build time
                "--initialize-at-build-time=com.funk.language.interpreter.FunkLanguageProvider",
                "--initialize-at-build-time=com.funk.language.interpreter.FunkValueGen\$InteropLibraryExports",
                
                // Initialize all our interpreter classes at build time (safer approach)
                "--initialize-at-build-time=com.funk.language.interpreter"
            )
        }
    }
}
