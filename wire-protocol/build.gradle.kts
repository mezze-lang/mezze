plugins {
    kotlin("jvm")
    // @Serializable on every payload class — required.
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
}
