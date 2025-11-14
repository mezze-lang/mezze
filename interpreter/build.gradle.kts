plugins {
    kotlin("jvm")
    kotlin("kapt") // Re-enable for Truffle annotation processing
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
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.2") // Re-enable for annotation processing

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

    // Increase heap size for Truffle
    maxHeapSize = "2g"
}
