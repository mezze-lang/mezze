plugins {
    kotlin("jvm")
    kotlin("kapt") // Re-enable for Truffle annotation processing
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
}

// Enable annotation processing
kotlin {
    jvmToolchain(21)
}

dependencies {
    // Core project dependencies
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":stdlib"))
    
    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.2") // Re-enable for annotation processing
    
    // Kotlin Coroutines for async support
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")
}
