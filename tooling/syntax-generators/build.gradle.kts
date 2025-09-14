plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":parser"))
    implementation(project(":ast"))
    
    // ANTLR for parsing the grammar file
    implementation("org.antlr:antlr4-runtime:4.13.1")
    
    // JSON handling for TextMate grammar generation
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    
    // Testing
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Task to generate TextMate grammar from ANTLR
tasks.register("generateTextMate") {
    group = "syntax"
    description = "Generate TextMate grammar from ANTLR Funk.g4"
    
    inputs.file("${project.rootDir}/parser/src/main/antlr/Funk.g4")
    outputs.file("${project.rootDir}/tooling/vscode-extension/syntaxes/funk.tmLanguage.json")
    
    doLast {
        javaexec {
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("com.funk.tooling.generators.TextMateGeneratorKt")
            args(
                "${project.rootDir}/parser/src/main/antlr/Funk.g4",
                "${project.rootDir}/tooling/vscode-extension/syntaxes/funk.tmLanguage.json"
            )
        }
    }
}

// Task to generate Tree-sitter grammar from ANTLR (future)
tasks.register("generateTreeSitter") {
    group = "syntax"
    description = "Generate Tree-sitter grammar from ANTLR Funk.g4"
    
    inputs.file("${project.rootDir}/parser/src/main/antlr/Funk.g4")
    outputs.file("${project.rootDir}/tooling/tree-sitter-funk/grammar.js")
    
    doLast {
        javaexec {
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("com.funk.tooling.generators.TreeSitterGeneratorKt")
            args(
                "${project.rootDir}/parser/src/main/antlr/Funk.g4",
                "${project.rootDir}/tooling/tree-sitter-funk/grammar.js"
            )
        }
    }
}

// Task to test generated grammars with example files
tasks.register("testSyntaxHighlighting") {
    group = "syntax"
    description = "Test generated syntax highlighting with Funk example files"
    dependsOn("generateTextMate")
    
    doLast {
        javaexec {
            classpath = sourceSets.main.get().runtimeClasspath
            mainClass.set("com.funk.tooling.testing.SyntaxHighlightingTesterKt")
            args("${project.rootDir}/examples")
        }
    }
}

