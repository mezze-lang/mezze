plugins {
    kotlin("jvm")
    kotlin("kapt")
}

dependencies {
    // Core project dependencies
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    
    // GraalVM Truffle dependencies
    implementation("org.graalvm.truffle:truffle-api:24.2.1")
    implementation("org.graalvm.truffle:truffle-dsl-processor:24.2.1")
    implementation("org.graalvm.truffle:truffle-runtime:24.2.1")
    
    
    // Annotation processing for Truffle (using kapt for Kotlin)
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.1")
    
    // Test dependencies
    testImplementation("org.graalvm.truffle:truffle-tck:24.2.1")
    testImplementation("org.graalvm.polyglot:polyglot:24.2.1")
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
