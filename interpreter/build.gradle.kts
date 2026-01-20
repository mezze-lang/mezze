plugins {
    kotlin("jvm")
    kotlin("kapt")
}

// Enable annotation processing
kotlin {
    jvmToolchain(24)
}

dependencies {
    // Core project dependencies
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":stdlib"))

    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.2")

    // Kotlin Coroutines for async support
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    // Interpreter tests can be slow due to Truffle initialization
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true  // Show output for debugging
    }

    // Run tests serially to avoid Truffle context conflicts
    maxParallelForks = 1

    // Increase timeout for Truffle initialization
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")

    // Suppress WarnInterpreterOnly in tests (expected - tests run without JIT compiler)
    // Production JARs have full Truffle JIT compilation via HotSpotTruffleRuntime
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")

    // Enable native access for Java 24 (Truffle and Gradle use System::load for native libraries)
    // Suppresses warnings about restricted method access
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // Note: Truffle 24.2.2 uses sun.misc.Unsafe::objectFieldOffset (terminally deprecated in Java 24)
    // This warning comes from com.oracle.truffle.api.nodes.NodeClassImpl$NodeFieldData
    // Cannot be suppressed - will be fixed when Truffle updates to newer reflection APIs
    // WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release

    // Increase heap size for Truffle
    maxHeapSize = "2g"
}
