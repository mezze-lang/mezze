plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// Questions about a codebase, asked through the store ABSTRACTION. Nothing here
// may depend on how a store is implemented: SQLite is one implementation, the
// in-memory store another, and every query must answer the same on both.
dependencies {
    api(project(":store"))
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":typechecker"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Tests index real type-checked code into each store implementation.
    testImplementation(project(":store-sqlite"))
    testImplementation(testFixtures(project(":store-sqlite")))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
