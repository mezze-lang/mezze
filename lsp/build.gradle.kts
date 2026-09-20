plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    // Internal dependencies
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":langservice"))
    implementation(project(":store-sqlite"))
    implementation(project(":formatter"))

    // LSP4J - Language Server Protocol implementation for Java/Kotlin
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")

    // Coroutines for async handling
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

application {
    mainClass.set("com.mezze.language.lsp.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Was: "this suite hangs after all tests pass" (lsp4j's launcher and the
    // typechecker/store transitive deps leaving non-daemon threads alive), with
    // `timeout 300` and Ctrl-C given as the workarounds. Re-checked 2026-09-20
    // across several full runs: it does NOT hang — every run completed on its own
    // in ~3 minutes. What DID happen, every time, was the worker dying of heap
    // exhaustion after the last test passed, which presents the same way from the
    // outside. That is fixed below; the workarounds are no longer needed.
    // forkEvery=1 isolates each class JVM so a hang in one doesn't poison
    // siblings.
    forkEvery = 1
    // These tests typecheck real modules against the stdlib — the same work the
    // typechecker suite does, which is configured at 1500m. This module was never
    // given a size at all, so it ran at Gradle's 512m default and the worker died
    // with "Java heap space: failed reallocation of scalar replaced objects"
    // AFTER every test had passed. The task failed; the tests did not. That is why
    // it went unnoticed — `:lsp:test` is outside the typechecker+interpreter gate
    // pair, and the report reads 122 tests / 0 failures / 0 errors.
    // 1500m matches the typechecker suite, which does the same work. The set is
    // BOUNDED, not leaking: 768m also passes, so 512m was merely a little short and
    // this is headroom rather than a lid on a leak.
    maxHeapSize = "1500m"
}

// Create a fat JAR for the LSP server
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.mezze.language.lsp.MainKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // Exclude signature files from signed JARs to avoid SecurityException
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }
}
