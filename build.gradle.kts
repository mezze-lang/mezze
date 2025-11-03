plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "com.funk.language"
    version = "1.0.0"

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "implementation"(kotlin("stdlib"))
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.1")
    }

    tasks.withType<Test> {
        useJUnitPlatform()

        // Add timeout to prevent tests from hanging indefinitely
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }

        // Set max parallel forks to 1 to avoid test interference
        maxParallelForks = 1

        // Set global timeout for all tests (can be overridden per module)
        systemProperty("junit.jupiter.execution.timeout.default", "60s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
    }
}

// Configure ANTLR generation task
tasks.register("generateGrammarSource") {
    dependsOn(":parser:generateGrammarSource")
    description = "Generate ANTLR grammar sources for all modules"
}
