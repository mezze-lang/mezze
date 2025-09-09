package com.funk.language.compiler

import com.funk.language.typechecker.TypedProgram as TypecheckerTypedProgram
import com.funk.language.typechecker.TypedItem
import com.funk.language.typechecker.TypedLetBinding
import com.funk.language.typechecker.TypedExpression
import com.funk.language.typechecker.FunkType
import com.funk.language.parser.*

/**
 * Minimal working compiler for Funk language
 */
class MinimalCompiler {
    
    fun compile(
        typedProgram: TypecheckerTypedProgram,
        target: CompilationTarget = CompilationTarget.JavaScript
    ): CompilationResult {
        return try {
            val code = when (target) {
                CompilationTarget.JavaScript -> generateJavaScript(typedProgram)
                CompilationTarget.JVM -> generateJVM(typedProgram)
                CompilationTarget.LLVM -> generateLLVM(typedProgram)
                CompilationTarget.C -> generateC(typedProgram)
            }
            
            CompilationResult.Success(
                target = target,
                code = code,
                metadata = CompilationMetadata(
                    sourceItems = typedProgram.items.size,
                    generatedLines = code.split('\n').size,
                    optimizationLevel = OptimizationLevel.O1
                )
            )
        } catch (e: Exception) {
            CompilationResult.Error(
                message = "Compilation failed: ${e.message}",
                cause = e
            )
        }
    }
    
    private fun generateJavaScript(program: TypecheckerTypedProgram): String {
        val codeGen = JavaScriptCodeGen()
        return codeGen.generateProgram(program)
    }
    
    
    private fun generateJVM(program: TypecheckerTypedProgram): String {
        return "// JVM compilation not implemented yet"
    }
    
    private fun generateLLVM(program: TypecheckerTypedProgram): String {
        // Native compilation should use Truffle nodes, not LLVM IR
        // This creates a native binary that uses the existing Truffle interpreter
        // compiled by GraalVM Native Image with full polyglot support
        return generateTruffleNativeBinary(program)
    }
    
    /**
     * Generate a true standalone native binary using Truffle nodes and GraalVM Native Image
     * This creates an executable that runs independently without the funk CLI
     */
    private fun generateTruffleNativeBinary(program: TypecheckerTypedProgram): String {
        // Convert the program to source code for embedding
        val sourceCode = reconstructSourceFromTypedProgram(program)
        
        // Create a native binary by generating a specialized version of the CLI
        // that has the Funk program embedded directly in it
        return generateEmbeddedNativeBinary(sourceCode)
    }
    
    /**
     * Generate an embedded native binary by creating a specialized CLI
     * that has the Funk program compiled directly into it
     */
    private fun generateEmbeddedNativeBinary(sourceCode: String): String {
        // Create a temporary directory for the standalone project
        val tempDir = java.nio.file.Files.createTempDirectory("funk-standalone-")
        
        try {
            // Parse the Funk program at compile time to validate it
            val parser = com.funk.language.parser.FunkParserImpl()
            val ast = parser.parse(sourceCode)
            
            // Create the standalone project structure
            val srcDir = tempDir.resolve("src/main/kotlin").toFile()
            srcDir.mkdirs()
            
            // Create resources directory and save the Funk program as a resource
            val resourcesDir = tempDir.resolve("src/main/resources").toFile()
            resourcesDir.mkdirs()
            val programResource = resourcesDir.resolve("program.funk")
            programResource.writeText(sourceCode)
            
            // Generate a main class that loads the Funk program from resources
            val mainFile = srcDir.resolve("StandaloneFunkProgram.kt")
            mainFile.writeText(generateStandaloneMainClass())
            
            // Generate build.gradle.kts for the standalone project
            val buildFile = tempDir.resolve("build.gradle.kts").toFile()
            buildFile.writeText(generateStandaloneBuildGradle())
            
            // Copy gradle wrapper if it exists
            copyGradleWrapper(tempDir)
            
            // Compile to native binary
            val binaryPath = compileStandaloneNativeBinary(tempDir)
            
            return binaryPath
            
        } finally {
            // Clean up temporary directory
            tempDir.toFile().deleteRecursively()
        }
    }
    
    private fun generateStandaloneMainClass(): String {
        return """
package com.funk.standalone

import com.funk.language.interpreter.FunkInterpreter
import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.TypeChecker

/**
 * Standalone Funk program compiled into a native binary
 * This is a self-contained executable that runs a specific Funk program
 */
object StandaloneFunkProgram {
    
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // Load the embedded program from resources
            val programResource = StandaloneFunkProgram::class.java.getResourceAsStream("/program.funk")
                ?: throw RuntimeException("Could not find embedded program resource")
            
            val programCode = programResource.bufferedReader().use { it.readText() }
            
            // Execute the program using the Truffle interpreter
            val interpreter = FunkInterpreter()
            val result = interpreter.execute(programCode)
            
            // Print the result
            println(result)
            
        } catch (e: Exception) {
            System.err.println("Error: ${'$'}{e.message}")
            kotlin.system.exitProcess(1)
        }
    }
}
        """.trimIndent()
    }
    
    private fun generateStandaloneBuildGradle(): String {
        return """
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.graalvm.buildtools.native") version "0.10.4"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("${System.getProperty("user.dir")}/parser/build/libs/parser-1.0.0.jar"))
    implementation(files("${System.getProperty("user.dir")}/typechecker/build/libs/typechecker-1.0.0.jar"))
    implementation(files("${System.getProperty("user.dir")}/interpreter/build/libs/interpreter-1.0.0.jar"))
    implementation("org.graalvm.truffle:truffle-api:23.0.4")
    implementation("org.graalvm.truffle:truffle-dsl-processor:23.0.4")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("funk-program")
            mainClass.set("com.funk.standalone.StandaloneFunkProgram")
            buildArgs.add("--no-fallback")
            buildArgs.add("--initialize-at-build-time")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("-H:+AllowDeprecatedBuilderClassesOnImageClasspath")
            buildArgs.add("-H:-CheckToolchain")
            buildArgs.add("--language:nfi")
            buildArgs.add("--enable-url-protocols=http,https")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}
        """.trimIndent()
    }
    
    private fun copyGradleWrapper(tempDir: java.nio.file.Path) {
        val projectRoot = java.io.File(System.getProperty("user.dir"))
        val gradlewFile = java.io.File(projectRoot, "gradlew")
        val gradlewBatFile = java.io.File(projectRoot, "gradlew.bat")
        val gradleWrapperDir = java.io.File(projectRoot, "gradle/wrapper")
        
        if (gradlewFile.exists()) {
            gradlewFile.copyTo(tempDir.resolve("gradlew").toFile())
            tempDir.resolve("gradlew").toFile().setExecutable(true)
        }
        if (gradlewBatFile.exists()) {
            gradlewBatFile.copyTo(tempDir.resolve("gradlew.bat").toFile())
        }
        if (gradleWrapperDir.exists()) {
            val targetWrapperDir = tempDir.resolve("gradle/wrapper").toFile()
            targetWrapperDir.mkdirs()
            gradleWrapperDir.copyRecursively(targetWrapperDir)
        }
    }
    
    private fun compileStandaloneNativeBinary(tempDir: java.nio.file.Path): String {
        val projectRoot = System.getProperty("user.dir")
        
        // First, build the required JAR dependencies
        val buildDepsProcess = ProcessBuilder("gradle", ":parser:build", ":typechecker:build", ":interpreter:build", "-x", "test")
            .directory(java.io.File(projectRoot))
            .inheritIO()
            .start()
        
        if (buildDepsProcess.waitFor() != 0) {
            throw RuntimeException("Failed to build dependencies")
        }
        
        // Then compile the standalone project to native binary
        val nativeCompileProcess = ProcessBuilder("gradle", "nativeCompile")
            .directory(tempDir.toFile())
            .inheritIO()
            .start()
        
        if (nativeCompileProcess.waitFor() != 0) {
            throw RuntimeException("Failed to compile native binary")
        }
        
        // Copy the generated binary to the current directory
        val generatedBinary = tempDir.resolve("build/native/nativeCompile/funk-program").toFile()
        val targetBinary = java.io.File("funk-program")
        
        if (generatedBinary.exists()) {
            generatedBinary.copyTo(targetBinary, overwrite = true)
            targetBinary.setExecutable(true)
            return targetBinary.name
        } else {
            throw RuntimeException("Native binary was not generated")
        }
    }
    
    /**
     * Reconstruct source code from typed program for Truffle execution
     */
    private fun reconstructSourceFromTypedProgram(program: TypecheckerTypedProgram): String {
        val builder = StringBuilder()
        
        for (item in program.items) {
            when (item) {
                is com.funk.language.typechecker.TypedLetBinding -> {
                    builder.appendLine("let ${item.variableName} = ${reconstructExpression(item.expression.expression)}")
                }
                is com.funk.language.typechecker.TypedModuleDeclaration,
                is com.funk.language.typechecker.TypedTypeDeclaration -> {
                    // Skip declarations for source reconstruction
                }
            }
        }
        
        return builder.toString()
    }
    
    /**
     * Reconstruct expression source code
     */
    private fun reconstructExpression(expr: com.funk.language.parser.Expression): String {
        return when (expr) {
            is com.funk.language.parser.Literal<*> -> {
                when (val value = expr.value) {
                    is String -> "\"$value\""
                    is Int -> value.toString()
                    is Double -> value.toString()
                    is Boolean -> value.toString()
                    else -> "null"
                }
            }
            is com.funk.language.parser.Identifier -> expr.name
            is com.funk.language.parser.LambdaExpression -> {
                val params = when (val p = expr.parameters) {
                    is com.funk.language.parser.UntypedParameters -> "{${p.names.joinToString(", ")}}"
                    is com.funk.language.parser.TypedParameters -> "{${p.recordType.entries.joinToString(", ") { it.name }}}"
                }
                "$params -> ${reconstructExpression(expr.body)}"
            }
            is com.funk.language.parser.BinaryOperation -> {
                "${reconstructExpression(expr.left)} ${expr.operator} ${reconstructExpression(expr.right)}"
            }
            is com.funk.language.parser.FunctionCall -> {
                "${reconstructExpression(expr.function)} ${reconstructExpression(expr.arguments)}"
            }
            is com.funk.language.parser.RecordLiteral -> {
                val entries = expr.entries.joinToString(", ") { entry ->
                    val value = entry.value
                    if (value != null) {
                        "${entry.name} = ${reconstructExpression(value as com.funk.language.parser.Expression)}"
                    } else {
                        entry.name
                    }
                }
                "{$entries}"
            }
            is com.funk.language.parser.VariantConstruction -> {
                val value = expr.value
                if (value != null) {
                    // Check if the value is already a record literal
                    if (value is com.funk.language.parser.RecordLiteral) {
                        "${expr.constructor} ${reconstructExpression(value as com.funk.language.parser.Expression)}"
                    } else {
                        "${expr.constructor} { val = ${reconstructExpression(value as com.funk.language.parser.Expression)} }"
                    }
                } else {
                    expr.constructor
                }
            }
            is com.funk.language.parser.MatchExpression -> {
                val cases = expr.cases.joinToString("\n                ") { case ->
                    val pattern = when (val p = case.pattern) {
                        is com.funk.language.parser.ConstructorPattern -> {
                            if (p.recordPattern != null) {
                                "${p.constructor} { val }"
                            } else {
                                p.constructor
                            }
                        }
                        is com.funk.language.parser.WildcardPattern -> "_"
                        else -> "_"
                    }
                    "$pattern -> ${reconstructExpression(case.expression)}"
                }
                "match ${reconstructExpression(expr.expression)} {\n                $cases\n            }"
            }
            else -> "/* unsupported expression: ${expr::class.simpleName} */"
        }
    }
    
    private fun generateC(program: TypecheckerTypedProgram): String {
        return "// C compilation not implemented yet"
    }
}
