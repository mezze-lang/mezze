plugins {
    kotlin("jvm")
    kotlin("kapt") // Required for Truffle DSL annotation processing (@ExportLibrary, @ExportMessage)
}

// Kapt is essential for Truffle interop - processes @ExportLibrary/@ExportMessage annotations
// to generate InteropLibrary exports for MezzeValue types (28 generated Java files)
// Note: Kapt is deprecated in favor of KSP, but Truffle DSL doesn't support KSP yet
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

    // JSON serialization (for std/json codec)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Vavr - persistent functional data structures with O(1) structural sharing
    // Used for Array (Vector), Map (HashMap), Set (HashSet), SortedMap (TreeMap), SortedSet (TreeSet)
    implementation("io.vavr:vavr:0.10.4")

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

    // Set mezze.project.root so tests can access stdlib modules
    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)

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
