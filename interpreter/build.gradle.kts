plugins {
    java
    kotlin("jvm")
}

// Pure Java module for Truffle DSL nodes
// The converter will be in a separate Kotlin module or in the interpreter module
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(24))
    }
}

dependencies {
    // Core project dependencies
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":datastructures"))

    // Truffle API - for language implementation
    implementation("org.graalvm.truffle:truffle-api:24.2.2")

    // Truffle DSL annotation processor - generates specialization code
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:24.2.2")

    // GraalVM SDK - for polyglot interop
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")
    implementation("org.graalvm.polyglot:polyglot:24.2.2")

    // HTTP client - Vert.x for async HTTP requests
    implementation("io.vertx:vertx-core:4.5.1")
    implementation("io.vertx:vertx-web-client:4.5.1")

    // DSL-JSON - high performance JSON library (byte-level, no reflection, GraalVM native-image compatible)
    implementation("com.dslplatform:dsl-json:2.0.2")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(project(":typechecker"))
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    // Sequential execution like main branch — parallel forks multiply memory
    // (each fork loads 100MB+ PrecompiledStdlib + 4GB heap = OOM on 8GB machines)
    maxParallelForks = 1

    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")

    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/sun.misc=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )

    maxHeapSize = "4g"
}
