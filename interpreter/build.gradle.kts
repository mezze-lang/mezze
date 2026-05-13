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
    implementation(project(":store"))
    implementation(project(":typechecker"))
    implementation(project(":datastructures"))

    // Truffle API - for language implementation
    implementation("org.graalvm.truffle:truffle-api:24.2.2")

    // Truffle optimizing runtime (HotSpotTruffleRuntime). Without this,
    // Truffle falls back to DefaultTruffleRuntime — i.e. pure interpreter,
    // no PE, no JIT. On GraalVM JDK the compiler module
    // `org.graalvm.truffle.compiler` is already a jmod in the JDK, so this
    // single artifact is sufficient — no --upgrade-module-path needed.
    implementation("org.graalvm.truffle:truffle-runtime:24.2.2")

    // Truffle DSL annotation processor - generates specialization code
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:24.2.2")

    // GraalVM SDK - for polyglot interop
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")
    implementation("org.graalvm.polyglot:polyglot:24.2.2")

    // Truffle instrumentation tools — Chrome Inspector (CDP), DAP (VS Code
    // debug protocol), CPU sampler / tracer / memtracer, coverage, Insight.
    // Without these on the classpath, Truffle reports "Could not find option"
    // for cpusampler / coverage / inspect / etc.
    implementation("org.graalvm.tools:profiler-tool:24.2.2")
    implementation("org.graalvm.tools:chromeinspector-tool:24.2.2")
    implementation("org.graalvm.tools:dap-tool:24.2.2")
    implementation("org.graalvm.tools:coverage-tool:24.2.2")
    implementation("org.graalvm.tools:insight-tool:24.2.2")

    // HTTP client - Vert.x for async HTTP requests
    implementation("io.vertx:vertx-core:4.5.1")
    implementation("io.vertx:vertx-web-client:4.5.1")

    // DSL-JSON - high performance JSON library (byte-level, no reflection, GraalVM native-image compatible)
    implementation("com.dslplatform:dsl-json:2.0.2")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(project(":typechecker"))
}

// Shared config applied to both the default `test` task and the
// opt-in `bench` task below. Keeps env-passthrough, JVM args, heap, and
// logging in one place — the only difference between the two is which
// test classes they include.
fun org.gradle.api.tasks.testing.Test.applyCommonTestConfig() {
    useJUnitPlatform()

    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    maxParallelForks = 1

    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")

    // Pass through MEZZE_DEBUG env var into the test JVM so the deferred
    // catches in TypeChecker print exception traces.
    System.getenv("MEZZE_DEBUG")?.let { environment("MEZZE_DEBUG", it) }
    // Pass through MEZZE_MONO so MonoDriver.enabled flips on inside tests.
    System.getenv("MEZZE_MONO")?.let { environment("MEZZE_MONO", it) }
    System.getenv("MEZZE_MONO_STATS")?.let { environment("MEZZE_MONO_STATS", it) }
    System.getenv("MEZZE_MONO_TRACE")?.let { environment("MEZZE_MONO_TRACE", it) }
    System.getenv("MEZZE_DISPATCH_CENSUS")?.let { environment("MEZZE_DISPATCH_CENSUS", it) }
    System.getenv("MEZZE_DISABLE_SCALAR")?.let { environment("MEZZE_DISABLE_SCALAR", it) }
    System.getenv("MEZZE_DEBUG_TYPED")?.let { environment("MEZZE_DEBUG_TYPED", it) }
    System.getenv("MEZZE_DEBUG_INLINE")?.let { environment("MEZZE_DEBUG_INLINE", it) }
    System.getenv("MEZZE_DISABLE_INLINE")?.let { environment("MEZZE_DISABLE_INLINE", it) }
    System.getenv("MEZZE_DISABLE_DSWIRE")?.let { environment("MEZZE_DISABLE_DSWIRE", it) }
    // Allow opt-in to Truffle JIT for perf measurement (bench task).
    System.getenv("MEZZE_JIT")?.let { environment("MEZZE_JIT", it) }

    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/sun.misc=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )

    maxHeapSize = "2g"
}

// Names that mark a JUnit class as a benchmark / profiler / stress test
// rather than a correctness test. Filename convention — any class whose
// simple name matches one of these is routed to `:interpreter:bench`,
// not the default `test` task.
val benchClassPatterns = listOf(
    "**/*Benchmark*",
    "**/*Bench*",
    "**/*ProfileTest*",
    "**/StdlibHofSoSurveyTest*",
    "**/MicroBenchmarkTest*",
)

tasks.test {
    applyCommonTestConfig()
    // Default `test` excludes benches so `./gradlew :interpreter:test`
    // stays a fast feedback loop. Benches live in :interpreter:bench.
    benchClassPatterns.forEach { exclude(it) }
}

tasks.register<Test>("bench") {
    group = "verification"
    description = "Performance benchmarks and profiling harnesses (excluded from :test)."
    applyCommonTestConfig()
    // Bench tests need longer per-test timeout — they're allowed to run
    // for tens of seconds. Override the strict 120s default applied above.
    systemProperty("junit.jupiter.execution.timeout.default", "600s")
    benchClassPatterns.forEach { include(it) }
    // Always re-run; bench output is informational, never up-to-date.
    outputs.upToDateWhen { false }
}
