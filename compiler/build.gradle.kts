plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":ast"))
    implementation(project(":parser"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
