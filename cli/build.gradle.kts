plugins {
    application
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":typechecker"))
    implementation(project(":interpreter"))
    
    // GraalVM Truffle dependencies (needed for FunkValue and FunkException)
    implementation("org.graalvm.truffle:truffle-api:24.0.0")
    implementation("org.graalvm.polyglot:polyglot:24.0.0")
}

application {
    mainClass.set("com.funk.language.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
