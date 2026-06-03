plugins {
    java
    // jacoco  // Disabled: JaCoCo 0.8.11 doesn't support Java 24 (class file version 68)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Enable Vector API (incubator module)
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--add-modules=jdk.incubator.vector"
    ))
}

dependencies {
    // Truffle API - for @CompilationFinal, @ExplodeLoop, etc.
    implementation("org.graalvm.truffle:truffle-api:25.0.3")

    // Truffle DSL annotation processor
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:25.0.3")

    // GraalVM SDK - for TruffleObject interop
    implementation("org.graalvm.sdk:graal-sdk:25.0.3")
    implementation("org.graalvm.polyglot:polyglot:25.0.3")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    maxParallelForks = 1

    systemProperty("junit.jupiter.execution.timeout.default", "60s")

    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )

    maxHeapSize = "1g"
}

// JaCoCo coverage disabled - version 0.8.11 doesn't support Java 24
// jacoco {
//     toolVersion = "0.8.11"
// }
//
// tasks.jacocoTestReport {
//     dependsOn(tasks.test)
//     reports {
//         xml.required.set(true)
//         html.required.set(true)
//     }
// }
