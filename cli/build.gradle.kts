plugins {
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.graalvm.buildtools.native") version "0.10.2"
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":interpreter"))
    implementation(project(":compiler"))
    
    // GraalVM Truffle dependencies (needed for FunkValue and FunkException)
    implementation("org.graalvm.truffle:truffle-api:24.2.1")
    implementation("org.graalvm.polyglot:polyglot:24.2.1")
}

// Note: Configuration removed due to build conflicts

application {
    mainClass.set("com.funk.language.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// Shadow JAR configuration for executable JAR
tasks.shadowJar {
    archiveBaseName.set("funk")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.funk.language.cli.MainKt"
    }
}

// GraalVM Native Image configuration
graalvmNative {
    binaries {
        named("main") {
            imageName.set("funk")
            mainClass.set("com.funk.language.cli.MainKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-preview")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("-H:IncludeResources=.*\\.g4")
            buildArgs.add("-H:IncludeResources=.*\\.tokens")
            buildArgs.add("--initialize-at-build-time")
            buildArgs.add("-H:-CheckToolchain")
        }
    }
}

// Create a shell script wrapper
tasks.register("createFunkScript") {
    dependsOn("shadowJar")
    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        val scriptFile = file("${rootDir}/funk")
        scriptFile.writeText("""#!/bin/bash
exec java -jar "${jarFile.absolutePath}" "$@"
""")
        scriptFile.setExecutable(true)
    }
}
