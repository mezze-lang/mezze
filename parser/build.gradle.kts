plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":ast"))

    // Parser combinator library - we'll use Kotlin's own or a lightweight one
    // For now, we'll hand-roll combinators

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    // Add timeout to prevent tests from hanging indefinitely
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }

    // Set max parallel forks to 1 to avoid test interference
    maxParallelForks = 1

    // Set timeout for each test
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
}
