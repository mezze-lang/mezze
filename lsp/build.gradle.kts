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
    implementation(project(":codebase"))
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
