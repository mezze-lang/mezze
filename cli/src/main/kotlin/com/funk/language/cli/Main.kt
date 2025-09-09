package com.funk.language.cli

import com.funk.language.parser.FunkParserImpl
import com.funk.language.typechecker.*
import com.funk.language.interpreter.FunkException
import com.funk.language.interpreter.ASTToTruffleConverter
import com.funk.language.interpreter.FunkLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.Path

fun main(args: Array<String>) {
    // Handle global flags first
    if (args.contains("--help") || args.contains("-h")) {
        showHelp()
        return
    }
    
    if (args.contains("--version") || args.contains("-v")) {
        showVersion()
        return
    }
    
    if (args.isEmpty()) {
        showUsage()
        kotlin.system.exitProcess(1)
    }

    val command = args[0]
    val remainingArgs = args.drop(1)
    
    // Handle command-specific help
    if (remainingArgs.contains("--help") || remainingArgs.contains("-h")) {
        showCommandHelp(command)
        return
    }
    
    try {
        when (command) {
            "parse" -> handleParseCommand(remainingArgs)
            "check" -> handleCheckCommand(remainingArgs)
            "exec" -> handleExecCommand(remainingArgs)
            "compile" -> handleCompileCommand(remainingArgs)
            else -> {
                println("❌ Unknown command: $command")
                println()
                showUsage()
                kotlin.system.exitProcess(1)
            }
        }
    } catch (e: StructuredTypeError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

/**
 * Show main help information
 */
private fun showHelp() {
    println("🚀 Funk Language v1.0.0")
    println("A modern functional programming language with full Truffle support")
    println()
    showUsage()
    println()
    println("COMMANDS:")
    println("  parse     Parse Funk code and show AST")
    println("  check     Type check Funk code and show inferred types")
    println("  exec      Execute Funk code using Truffle runtime (fast startup)")
    println("  compile   Compile Funk code to standalone JAR (with JIT optimization)")
    println()
    println("GLOBAL OPTIONS:")
    println("  -h, --help     Show this help message")
    println("  -v, --version  Show version information")
    println()
    println("EXAMPLES:")
    println("  funk parse program.funk              # Parse a file")
    println("  echo 'let x = 5 + 10' | funk exec    # Execute from stdin")
    println("  funk compile program.funk            # Compile to standalone JAR")
    println("  funk check --help                    # Get help for check command")
    println()
    println("For command-specific help: funk <command> --help")
}

/**
 * Show version information
 */
private fun showVersion() {
    println("🚀 Funk Language v1.0.0")
    println("Built with Oracle GraalVM 24.0.2")
    println()
    println("Features:")
    println("  ✅ Bidirectional type inference")
    println("  ✅ Pattern matching")
    println("  ✅ Record types with row polymorphism")
    println("  ✅ Variant types")
    println("  ✅ Full Truffle polyglot interop")
}

/**
 * Show basic usage
 */
private fun showUsage() {
    println("USAGE:")
    println("  funk <command> [options] [file]")
    println()
    println("COMMANDS: parse, check, exec, compile")
    println("For command-specific help: funk <command> --help")
}

/**
 * Show command-specific help
 */
private fun showCommandHelp(command: String) {
    when (command) {
        "parse" -> {
            println("USAGE: funk parse [file]")
            println()
            println("Parse Funk source code and display the Abstract Syntax Tree")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk parse program.funk       # Parse a file")
            println("  echo 'let x = 5' | funk parse # Parse from stdin")
        }
        
        "check" -> {
            println("USAGE: funk check [file]")
            println()
            println("Type check Funk source code and show inferred types")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk check program.funk       # Type check a file")
            println("  echo 'let x = 5 + 10' | funk check  # Type check from stdin")
        }
        
        "exec" -> {
            println("USAGE: funk exec [file]")
            println()
            println("Execute Funk source code using the Truffle runtime")
            println("Fast startup, interpreter-only execution in native binary")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk exec program.funk              # Execute a file")
            println("  echo 'let x = 5 + 10' | funk exec   # Execute from stdin")
        }
        
        "compile" -> {
            println("USAGE: funk compile <file> [output]")
            println()
            println("Compile Funk source code to a standalone JAR with JIT optimization")
            println("The resulting JAR includes the Truffle runtime for maximum performance")
            println()
            println("ARGUMENTS:")
            println("  <file>             Input Funk source file")
            println("  [output]           Output JAR name (default: same as input with .jar)")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk compile program.funk            # Creates program.jar")
            println("  funk compile app.funk myapp.jar     # Creates myapp.jar")
            println()
            println("RUNNING COMPILED JARS:")
            println("  java -jar program.jar                         # Basic execution (interpreter-only)")
            println("  java -XX:+EnableJVMCI \\")
            println("       -XX:+UnlockExperimentalVMOptions \\")
            println("       -XX:+UseJVMCICompiler \\")
            println("       -jar program.jar                         # With JIT optimization (recommended)")
            println("  java -XX:+EnableJVMCI \\")
            println("       -XX:+UnlockExperimentalVMOptions \\")
            println("       -XX:+UseJVMCICompiler \\")
            println("       --enable-native-access=ALL-UNNAMED \\")
            println("       -jar program.jar                         # JIT + clean output")
        }
        
        else -> {
            println("❌ Unknown command: $command")
            println()
            showUsage()
        }
    }
}

private fun handleParseCommand(args: List<String>) {
    val source = when {
        args.isEmpty() -> {
            // Read from stdin
            readStdin()
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            file.readText()
        }
        else -> {
            println("Error: parse command takes at most 1 argument (filename)")
            showUsage()
            return
        }
    }

    try {
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        println("✅ Parsing successful!")
        println()
        println(prettyPrintAST(ast))
    } catch (e: Exception) {
        println("❌ Parse error:")
        println(e.message ?: "Unknown error")
    }
}

private fun handleCheckCommand(args: List<String>) {
    val (source, filePath) = when {
        args.isEmpty() -> {
            // Read from stdin
            Pair(readStdin(), null)
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            Pair(file.readText(), file.toPath())
        }
        else -> {
            println("Error: check command takes at most 1 argument (filename)")
            showUsage()
            return
        }
    }

    try {
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        
        // Use module-aware type checking if we have a file path
        val result = if (filePath != null) {
        val typeChecker = TypeChecker()
            typeChecker.typeCheck(ast, filePath.parent ?: filePath)
        } else {
            // Fallback to simple type checking for stdin
            val typeChecker = TypeChecker()
            typeChecker.typeCheck(ast)
        }
        
        println("✅ Type checking successful!")
        println()
        println("Variables:")
        for (item in result.items) {
            when (item) {
                is TypedLetBinding -> {
                    println("  ${item.variableName} : ${prettyPrintType(item.inferredType)}")
                }
                is TypedTypeDeclaration -> {
                    println("  type ${item.name}")
                }
                else -> {
                    // Skip other items for now
                }
            }
        }
    } catch (e: StructuredTypeError) {
        // For demonstration, show both simple and detailed error formats
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        
        // Uncomment the line below to see the detailed error format:
        // println("\n" + ErrorPrettyPrinter.prettyPrintError(e))
        
        // Exit with error code for type errors
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

private fun readStdin(): String {
    val lines = mutableListOf<String>()
    var line = readLine()
    while (line != null) {
        lines.add(line)
        line = readLine()
    }
    return lines.joinToString("\n")
}

private fun prettyPrintType(type: FunkType): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Boolean"
    is FunkType.VarType -> type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintType(fieldType)}"
        }
        val openMarker = if (type.rowVar != null) ", ..." else ""
        "{ $fields$openMarker }"
    }
    is FunkType.VariantType -> {
        val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
            when (dataType) {
                null -> name
                is FunkType.RecordType -> {
                    // For record types, format as "Red { value: Int }" without extra braces
                    val fields = dataType.fields.entries.joinToString(", ") { (fieldName, fieldType) ->
                        "$fieldName: ${prettyPrintType(fieldType)}"
                    }
                    val openMarker = if (dataType.rowVar != null) ", ..." else ""
                    "$name { $fields$openMarker }"
                }
                else -> "$name(${prettyPrintType(dataType)})" // Ok(Int) for non-record types
            }
        }
        val openMarker = if (type.rowVar != null) ", ..." else ""
        "| $constructors$openMarker |"
    }
    is FunkType.FunctionType -> {
        "${prettyPrintType(type.paramType)} -> ${prettyPrintType(type.returnType)}"
    }
    is FunkType.TypeAlias -> type.name
    is FunkType.TypeConstructor -> {
        if (type.args.isEmpty()) type.name
        else "${type.name}<${type.args.joinToString(", ") { prettyPrintType(it) }}>"
    }
}


private fun prettyPrintAST(ast: com.funk.language.parser.Program): String {
    val sb = StringBuilder()
    sb.appendLine("Program with ${ast.items.size} items:")
    ast.items.forEachIndexed { index, item ->
        sb.appendLine("  ${index + 1}. ${prettyPrintItem(item)}")
    }
    return sb.toString()
}

private fun prettyPrintItem(item: com.funk.language.parser.ProgramItem): String {
    return when (item) {
        is com.funk.language.parser.LetBinding -> {
            val typeAnnotation = item.typeAnnotation?.let { ": $it" } ?: ""
            "let ${item.variableName}$typeAnnotation = ${prettyPrintExpression(item.expression)}"
        }
        is com.funk.language.parser.TypeDeclaration -> {
            "type ${item.name} = ${item.definition}"
        }
        is com.funk.language.parser.ModuleDeclaration -> {
            "module ${item.name} { ... }"
        }
        is com.funk.language.parser.ExposeDeclaration -> {
            "expose { ... }"
        }
        is com.funk.language.parser.AccessDeclaration -> {
            "access ${item.moduleName}"
        }
        else -> item.toString()
    }
}

private fun prettyPrintExpression(expr: com.funk.language.parser.Expression): String {
    return when (expr) {
        is com.funk.language.parser.Literal<*> -> expr.value.toString()
        is com.funk.language.parser.Identifier -> expr.name
        is com.funk.language.parser.LambdaExpression -> {
            val params = when (val p = expr.parameters) {
                is com.funk.language.parser.TypedParameters -> p.recordType.toString()
                is com.funk.language.parser.UntypedParameters -> "{ ${p.names.joinToString(", ")} }"
            }
            "$params -> ${prettyPrintExpression(expr.body)}"
        }
        is com.funk.language.parser.BinaryOperation -> {
            "${prettyPrintExpression(expr.left)} ${expr.operator} ${prettyPrintExpression(expr.right)}"
        }
        is com.funk.language.parser.FunctionCall -> {
            "${prettyPrintExpression(expr.function)} ${prettyPrintExpression(expr.arguments)}"
        }
        is com.funk.language.parser.RecordLiteral -> {
            val entries = expr.entries.joinToString(", ") { entry ->
                val value = entry.value
                if (value != null) {
                    "${entry.name} = ${prettyPrintExpression(value)}"
                } else {
                    entry.name  // Punned syntax
                }
            }
            "{ $entries }"
        }
        is com.funk.language.parser.IfExpression -> {
            "if ${prettyPrintExpression(expr.condition)} then ${prettyPrintExpression(expr.thenBranch)} else ${prettyPrintExpression(expr.elseBranch)}"
        }
        else -> expr.toString()
    }
}

private fun handleExecCommand(args: List<String>) {
    val (source, filePath) = when {
        args.isEmpty() -> {
            // Read from stdin
            Pair(readStdin(), null)
        }
        args.size == 1 -> {
            // Read from file
            val file = File(args[0])
            if (!file.exists()) {
                println("Error: File not found at '${args[0]}'")
                return
            }
            Pair(file.readText(), file.toPath())
        }
        else -> {
            println("Error: exec command takes at most 1 argument (filename)")
            showUsage()
            return
        }
    }

    try {
        // Create Polyglot context with explicit language configuration
        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .build()
            
        println("Available languages: ${engine.languages.keys}")
        
        val context = Context.newBuilder("funk")
            .engine(engine)
            .allowAllAccess(true)
            .allowExperimentalOptions(true)
            .build()
        
        try {
            val polyglotSource = Source.newBuilder("funk", source, filePath?.toString() ?: "<stdin>").build()
            val result = context.eval(polyglotSource)
            
            println("✅ Execution successful!")
            println()
            println("Result: ${result.toString()}")
        } finally {
            context.close()
            engine.close()
        }
        
    } catch (e: StructuredTypeError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        kotlin.system.exitProcess(1)
    } catch (e: FunkException) {
        println("❌ Runtime Error:")
        println(e.message ?: "Unknown runtime error")
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

private fun handleCompileCommand(args: List<String>) {
    // Validate arguments
    if (args.isEmpty()) {
        println("❌ Error: compile command requires an input file")
        println()
        println("USAGE: funk compile <file> [output]")
        println("Use 'funk compile --help' for more information")
        return
    }
    
    if (args.size > 2) {
        println("❌ Error: compile command takes at most 2 arguments")
        println()
        println("USAGE: funk compile <file> [output]")
        return
    }
    
    val inputFile = File(args[0])
    if (!inputFile.exists()) {
        println("❌ Error: Input file not found at '${args[0]}'")
        return
    }
    
    // Determine output file name
    val outputFile = if (args.size == 2) {
        File(args[1])
    } else {
        // Default: replace .funk extension with .jar, or add .jar
        val inputName = inputFile.nameWithoutExtension
        File(inputFile.parent ?: ".", "$inputName.jar")
    }
    
    try {
        println("🔨 Compiling ${inputFile.name} to ${outputFile.name}...")
        println()
        
        // Parse and type check the source
        val source = inputFile.readText()
        val parser = FunkParserImpl()
        val ast = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val typedProgram = typeChecker.typeCheck(ast, inputFile.toPath().parent ?: inputFile.toPath())
        
        println("✅ Type checking successful")
        
        // Create a standalone JAR with embedded program and Truffle runtime
        createStandaloneJar(inputFile, outputFile, source, typedProgram)
        
        println("✅ Compilation successful!")
        println()
        println("📦 Created: ${outputFile.absolutePath}")
        println("📏 Size: ${outputFile.length() / 1024}KB")
        println()
        println("🚀 Run with JIT: java -XX:+EnableJVMCI -XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler -jar ${outputFile.name}")
        println("   (Includes Truffle runtime optimization support)")
        println()
        println("💡 For cleaner output, add: --enable-native-access=ALL-UNNAMED")
        
    } catch (e: StructuredTypeError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrintErrorSimple(e))
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Compilation Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

private fun createStandaloneJar(inputFile: File, outputFile: File, source: String, typedProgram: TypedProgram) {
    println("🔧 Creating standalone JAR with Truffle runtime...")
    
    // Create a temporary directory for JAR contents
    val tempDir = kotlin.io.path.createTempDirectory("funk-compile").toFile()
    
    try {
        // 1. Create a Kotlin main class that runs the embedded program
        createKotlinMainClass(tempDir, inputFile, source)
        
        // 2. Build the JAR using Gradle task (which will compile Kotlin)
        buildJarWithGradle(tempDir, outputFile, inputFile.nameWithoutExtension)
        
    } finally {
        // Clean up temp directory
        tempDir.deleteRecursively()
    }
}

private fun createKotlinMainClass(tempDir: File, inputFile: File, source: String) {
    val mainClassDir = File(tempDir, "src/main/kotlin/com/funk/standalone")
    mainClassDir.mkdirs()
    
    val mainClassContent = """
package com.funk.standalone

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source

/**
 * Standalone executable for ${inputFile.name}
 * Generated by Funk compiler with embedded source code
 */
object Main {
    private const val EMBEDDED_SOURCE = ${escapeForKotlin(source)}
    private const val PROGRAM_NAME = "${inputFile.name}"
    
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // Create engine with JIT compilation enabled
            val engine = Engine.newBuilder()
                .allowExperimentalOptions(true)
                .build()
                
            // Create context for Funk language
            val context = Context.newBuilder("funk")
                .engine(engine)
                .allowAllAccess(true)
                .allowExperimentalOptions(true)
                .build()
                
            // Execute the embedded Funk program
            val polyglotSource = Source.newBuilder("funk", EMBEDDED_SOURCE, PROGRAM_NAME).build()
            val result = context.eval(polyglotSource)
            
            // Print result (last expression value)
            if (result != null && result.toString() != "null") {
                println("Result: ${'$'}{result}")
            }
            
            context.close()
            engine.close()
            
        } catch (e: Exception) {
            System.err.println("Error executing ${'$'}PROGRAM_NAME: ${'$'}{e.message}")
            if (args.isNotEmpty() && args[0] == "--debug") {
                e.printStackTrace()
            }
            kotlin.system.exitProcess(1)
        }
    }
}
    """.trimIndent()
    
    File(mainClassDir, "Main.kt").writeText(mainClassContent)
    
    // Also create a simple build.gradle.kts for this standalone project
    val buildGradleContent = """
plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

application {
    mainClass.set("com.funk.standalone.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    // Official GraalVM Polyglot dependencies for JIT optimization
    implementation("org.graalvm.polyglot:polyglot:24.2.2")
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    
    // Runtime optimization support (this enables JIT compilation!)
    runtimeOnly("org.graalvm.truffle:truffle-runtime:24.2.2")
    
    // Include our Funk language implementation (copy from parent project)
    ${generateFunkDependencies()}
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    
    manifest {
        attributes["Main-Class"] = "com.funk.standalone.Main"
    }
}
    """.trimIndent()
    
    File(tempDir, "build.gradle.kts").writeText(buildGradleContent)
    
    // Copy our Funk language JARs to the temp directory
    copyFunkLanguageJars(tempDir)
    
    println("✅ Created Kotlin main class wrapper")
}

private fun generateFunkDependencies(): String {
    return """
    // ANTLR runtime for parsing
    implementation("org.antlr:antlr4-runtime:4.13.1")
    
    // Local Funk language JARs (copied from parent project)
    implementation(files("libs/funk-parser.jar"))
    implementation(files("libs/funk-typechecker.jar")) 
    implementation(files("libs/funk-interpreter.jar"))
    """.trimIndent()
}


private fun copyFunkLanguageJars(tempDir: File) {
    val libsDir = File(tempDir, "libs")
    libsDir.mkdirs()
    
    // Copy our built JARs (with version numbers)
    val projectRoot = File(".")
    
    // Copy parser JAR
    val parserJar = File(projectRoot, "parser/build/libs/parser-1.0.0.jar")
    if (parserJar.exists()) {
        parserJar.copyTo(File(libsDir, "funk-parser.jar"), overwrite = true)
        println("📦 Copied parser JAR")
    } else {
        println("⚠️  Parser JAR not found: ${parserJar.absolutePath}")
    }
    
    // Copy typechecker JAR
    val typecheckerJar = File(projectRoot, "typechecker/build/libs/typechecker-1.0.0.jar")
    if (typecheckerJar.exists()) {
        typecheckerJar.copyTo(File(libsDir, "funk-typechecker.jar"), overwrite = true)
        println("📦 Copied typechecker JAR")
    } else {
        println("⚠️  Typechecker JAR not found: ${typecheckerJar.absolutePath}")
    }
    
    // Copy interpreter JAR
    val interpreterJar = File(projectRoot, "interpreter/build/libs/interpreter-1.0.0.jar")
    if (interpreterJar.exists()) {
        interpreterJar.copyTo(File(libsDir, "funk-interpreter.jar"), overwrite = true)
        println("📦 Copied interpreter JAR")
    } else {
        println("⚠️  Interpreter JAR not found: ${interpreterJar.absolutePath}")
    }
    
    // Copy Truffle compiler JAR for JIT optimization
    val truffleCompilerJar = File(System.getenv("JAVA_HOME"), "lib/truffle/truffle-compiler.jar")
    if (truffleCompilerJar.exists()) {
        truffleCompilerJar.copyTo(File(libsDir, "truffle-compiler.jar"), overwrite = true)
        println("📦 Copied Truffle JIT compiler")
    } else {
        println("⚠️  Truffle compiler JAR not found - JAR will run in interpreter mode")
    }
}


private fun buildJarWithGradle(tempDir: File, outputFile: File, programName: String) {
    println("🔧 Building standalone JAR...")
    
    // Build the standalone Kotlin project
    val gradleCmd = listOf(
        "gradle", 
        "shadowJar",
        "--no-daemon"
    )
    
    val process = ProcessBuilder(gradleCmd)
        .directory(tempDir)
        .redirectErrorStream(true)
        .start()
    
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    
    if (exitCode != 0) {
        throw RuntimeException("Failed to build standalone JAR:\n$output")
    }
    
    // Find the generated JAR and copy it to the output location
    val buildLibsDir = File(tempDir, "build/libs")
    val generatedJars = buildLibsDir.listFiles()?.filter { it.name.endsWith(".jar") }
    
    if (generatedJars.isNullOrEmpty()) {
        throw RuntimeException("No JAR file was generated in ${buildLibsDir.absolutePath}")
    }
    
    val sourceJar = generatedJars.first()
    sourceJar.copyTo(outputFile, overwrite = true)
    
    println("✅ Built standalone JAR with Truffle runtime")
}

private fun escapeForKotlin(source: String): String {
    return "\"\"\"" + source.replace("\"\"\"", "\\\"\\\"\\\"") + "\"\"\""
}

private fun escapeForJava(source: String): String {
    return "\"" + source
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""
}


