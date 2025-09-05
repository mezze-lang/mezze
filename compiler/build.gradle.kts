plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.test {
    useJUnitPlatform()
}
