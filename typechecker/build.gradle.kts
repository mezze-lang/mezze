plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":ast"))
    implementation(project(":parser"))
    implementation(project(":stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.2")
}

tasks.test {
    useJUnitPlatform()
}