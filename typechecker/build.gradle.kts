plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":ast"))
    implementation(project(":compiler"))
    implementation(project(":store"))
    implementation(project(":parser"))
    implementation(project(":stdlib"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.2")
}

// Resolvable classpath for the WellKnownHashGenerator main, declared in
// this project (the consumer) rather than reaching across to :parser's /
// :ast's runtimeClasspath. Gradle 9 forbids the old pattern as unsafe
// configuration resolution (cross-project lock).
val wellKnownHashGenClasspath: Configuration = configurations.create("wellKnownHashGenClasspath") {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    "wellKnownHashGenClasspath"(project(":parser"))
    "wellKnownHashGenClasspath"(project(":ast"))
}

// Generate WellKnownHashConstants.kt at build time from stdlib source files.
// Hashes are deterministic content hashes computed by ParsedDeclHasher.
// No runtime filesystem access or lazy parsing needed.
val generateWellKnownHashes by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate well-known ability/effect hash constants from stdlib"

    classpath = wellKnownHashGenClasspath

    mainClass.set("com.mezze.language.parser.WellKnownHashGeneratorKt")

    val outputFile = layout.buildDirectory.file("generated/sources/wellKnownHashes/com/mezze/language/typechecker/WellKnownHashConstants.kt")
    val stdlibDir = rootProject.projectDir.resolve("stdlib")

    args = listOf(outputFile.get().asFile.absolutePath, stdlibDir.absolutePath)
    outputs.file(outputFile)
    inputs.dir(stdlibDir.resolve("std"))
}

// Add generated sources to compilation
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/sources/wellKnownHashes"))
}

tasks.named("compileKotlin") {
    dependsOn(generateWellKnownHashes)
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
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")

    // Set mezze.project.root so tests can access stdlib modules
    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)
}