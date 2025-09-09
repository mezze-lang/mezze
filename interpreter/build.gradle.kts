plugins {
    kotlin("jvm")
    kotlin("kapt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}

// Enable annotation processing
kotlin {
    jvmToolchain(21)
}

dependencies {
    // Core project dependencies
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    
    // Truffle API (Language, Node, etc.) - guest language API - match native-image version
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    kapt("org.graalvm.truffle:truffle-dsl-processor:24.2.2")
}
