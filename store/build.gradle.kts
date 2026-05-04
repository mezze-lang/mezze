plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ast"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // CBOR serialization for compact AST storage
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
}
