plugins {
    application
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":typechecker"))
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
