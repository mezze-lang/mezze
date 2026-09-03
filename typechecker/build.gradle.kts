plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":ast"))
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
    useJUnitPlatform {
        excludeTags("benchmark")
    }

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }

    // Parallel forks (#379). Each fork is a SEPARATE JVM with an ISOLATED heap,
    // so the Truffle-engine CallTarget retention that drives cumulative heap
    // growth is now spread across N forks, each running only ~1/N of the suite.
    // That lets per-fork heap DROP below the old single-fork 3g. N is capped
    // well under ncpu (16 GB machine): 4 forks × 1.5g = 6g fits with headroom
    // for the OS + Gradle client, and avoids oversubscribing the CPU with heavy
    // Truffle JVMs.
    maxParallelForks = 4
    maxHeapSize = "1500m"

    // 120s (was 60s): ExhaustivenessFuzzTest's runSweep(seed, 300) sits right at
    // the old 60s bound under 4-fork parallel load and flakes on a TimeoutException
    // (passes comfortably in isolation). This module-level property overrides the
    // root default, so it must be bumped here too, not only in the root build.
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")

    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)
    // Typechecker phase-boundary invariants ON in the test gate so a regression
    // that re-introduces a structural fault (self-map / occurs / non-idempotent
    // substitution / leaked internal tyvar) fails an assertion at the boundary
    // instead of surfacing as a runtime miscompile. See the TC re-layering plan.
    systemProperty("mezze.invariants", "true")
}

tasks.register<Test>("bench") {
    description = "Typechecker performance benchmarks (excluded from :test)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}