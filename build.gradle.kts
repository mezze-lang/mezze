import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    java // Needed for java { toolchain { ... } } in root project
}

// Project version: pre-1.0 nightly tag. Format: nightly-YYYYMMDDHHMM,
// computed at configure time so every build is uniquely identifiable.
// Override with -Pversion=1.2.3 on the gradle command line for a release.
val mezzeVersion: String = (findProperty("version") as? String)?.takeIf { it != "unspecified" }
    ?: "nightly-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))

allprojects {
    group = "com.mezze.language"
    version = mezzeVersion

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java") // required for java { toolchain { ... } }
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // Kotlin toolchain (Kotlin 2.2.0 fully supports JDK 24 - verified bytecode major version 68)
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    // Java toolchain (GraalVM JDK 24 with Truffle JIT compiler)
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    // Ensure Java bytecode targets JVM 24 (verified: compiles to major version 68)
    tasks.withType<JavaCompile> {
        options.release.set(24)
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

        // Set global timeout for all tests (can be overridden per module).
        // 120s (was 60s): the exhaustiveness/property fuzzers sit near the old
        // bound and flake under full-suite parallel load; headroom kills the
        // flake without hiding a genuine hang.
        systemProperty("junit.jupiter.execution.timeout.default", "120s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")

        // GATE MEMORY GUARD (2026-11-20). A test task is `maxParallelForks` JVMs of
        // `maxHeapSize` each, plus ~1 GB per fork of metaspace, Truffle code and
        // off-heap. Several sessions run gates on one machine at once; when their
        // forks add up past physical memory the box does not fail, it SWAPS (25 GB of
        // swap on 27 GB of RAM) — a nine-minute gate becomes a ninety-minute one and
        // the machine freezes until someone reboots it. Five times in one week.
        //
        // So at execution time, after every module has set its own forks and heap:
        //  - size the forks by the memory AVAILABLE NOW (never more than fit, and a
        //    3 GB floor stays for the desktop and the Gradle JVMs);
        //  - refuse to start when not even one fork fits — a fast, named failure
        //    beats a swap death. `MEZZE_GATE_UNGUARDED=1` bypasses (CI containers
        //    whose /proc/meminfo is not theirs); `MEZZE_GATE_AVAIL_MB` injects the
        //    reading, for the guard's own test.
        doFirst {
            if (System.getenv("MEZZE_GATE_UNGUARDED") == null) {
                val heapMb = maxHeapSize?.lowercase()?.let { h ->
                    val n = h.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    when { h.endsWith("g") -> n * 1024; h.endsWith("k") -> n / 1024; else -> n }
                } ?: 512L
                val perForkMb = heapMb + 1024
                val availMb = System.getenv("MEZZE_GATE_AVAIL_MB")?.toLongOrNull()
                    ?: runCatching {
                        File("/proc/meminfo").readLines()
                            .first { it.startsWith("MemAvailable:") }
                            .filter { it.isDigit() }.toLong() / 1024
                    }.getOrNull()
                if (availMb != null) {
                    val fits = ((availMb - 3072) / perForkMb).toInt()
                    val wanted = maxParallelForks
                    if (fits < 1) throw GradleException(
                        "gate memory guard: ${project.path}:${name} needs ${perForkMb} MB per fork " +
                            "(${heapMb} MB heap + ~1 GB) but only ${availMb} MB is available — " +
                            "another gate or build is probably running on this machine. Wait for it, " +
                            "or run with fewer/lighter forks. (MEZZE_GATE_UNGUARDED=1 bypasses.)")
                    if (fits < wanted) {
                        logger.quiet("gate memory guard: ${project.path}:${name} forks ${wanted} -> ${fits} " +
                            "(${availMb} MB available, ${perForkMb} MB per fork)")
                        maxParallelForks = fits
                    }
                }
            }
        }
    }
}

// Configure ANTLR generation task
tasks.register("generateGrammarSource") {
    dependsOn(":parser:generateGrammarSource")
    description = "Generate ANTLR grammar sources for all modules"
}

// Configure Detekt for all subprojects
subprojects {
    afterEvaluate {
        extensions.findByType<io.gitlab.arturbosch.detekt.extensions.DetektExtension>()?.apply {
            config.setFrom(rootProject.files("detekt.yml"))
            buildUponDefaultConfig = true
            parallel = true
        }
        // Detekt doesn't support JDK 24 yet, use 22 for analysis
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            jvmTarget = "25"
        }
    }
}
