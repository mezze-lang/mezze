plugins {
    application
    id("org.graalvm.buildtools.native") version "0.10.2"
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    // Exclude interpreter for native build to avoid Truffle issues
    // implementation(project(":interpreter"))
}

application {
    mainClass.set("com.funk.language.cli.MainNativeKt")
}

// GraalVM Native Image configuration for CLI-only (no interpreter)
graalvmNative {
    binaries {
        named("main") {
            imageName.set("funk-native")
            mainClass.set("com.funk.language.cli.MainNativeKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("-H:IncludeResources=.*\\.g4")
            buildArgs.add("-H:IncludeResources=.*\\.tokens")
            buildArgs.add("-H:-CheckToolchain")
            buildArgs.add("--initialize-at-build-time")
        }
    }
}
