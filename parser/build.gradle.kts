plugins {
    antlr
}

dependencies {
    implementation(project(":ast"))
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")

    // Add testing dependencies
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.generateGrammarSource {
    maxHeapSize = "64m"
    arguments = arguments + listOf("-visitor", "-long-messages", "-package", "com.funk.language.parser.generated")
    outputDirectory = file("build/generated-src/antlr/main")
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateGrammarSource)
    dependsOn(tasks.generateTestGrammarSource)
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("build/generated-src/antlr/main")
        }
    }
}
