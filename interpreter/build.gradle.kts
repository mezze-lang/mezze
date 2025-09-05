plugins {
    kotlin("jvm")
}

dependencies {
    // Core project dependencies
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    
    // GraalVM Truffle dependencies
    implementation("org.graalvm.truffle:truffle-api:24.0.0")
    implementation("org.graalvm.truffle:truffle-dsl-processor:24.0.0")
    
    
    // Annotation processing for Truffle
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:24.0.0")
    
    // Test dependencies
    testImplementation("org.graalvm.truffle:truffle-tck:24.0.0")
    testImplementation("org.graalvm.polyglot:polyglot:24.0.0")
}

// Configure annotation processing for Truffle DSL
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}

// Enable annotation processing
kotlin {
    jvmToolchain(21)
}
