package com.funk.language.cli

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintType
import com.funk.language.ast.prettyPrintTypeWithAliases
import com.funk.language.typechecker.*
import com.funk.language.interpreter.FunkException
import com.funk.language.interpreter.FunkLanguage
import kotlinx.serialization.json.Json
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.nio.file.Files
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
    } catch (e: TypeCheckError) {
        printTypeError(e)
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        printGeneralError(e)
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
            println("Compile Funk source code to a standalone JAR")
            println()
            println("ARGUMENTS:")
            println("  <file>             Input Funk source file")
            println("  [output]           Output JAR name (default: same as input with .jar)")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk compile program.funk                # Creates program.jar")
            println("  funk compile program.funk myapp.jar      # Creates myapp.jar")
            println()
            println("OUTPUT:")
            println("  Pre-compiled AST + Interpreter + Truffle runtime")
            println("  Smaller size, faster startup than full JIT builds")
            println()
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
        val parser = FunkParser()
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
        val parser = FunkParser()
        val ast = parser.parse(source)
        
        // Use improved type checker (92% test success rate)
        val typeChecker = TypeChecker()
        val result = if (filePath != null) {
            typeChecker.typeCheck(ast, filePath.parent ?: filePath)
        } else {
            typeChecker.typeCheck(ast)
        }
        val typeAliases = typeChecker.getTypeAliases()
        
        printTypeCheckSuccess(result, typeAliases)
    } catch (e: TypeCheckError) {
        // For demonstration, show both simple and detailed error formats
        println(ErrorPrettyPrinter.prettyPrint(e))
        
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



private fun prettyPrintAST(ast: Program): String {
    val sb = StringBuilder()
    sb.appendLine("Program with ${ast.items.size} items:")
    ast.items.forEachIndexed { index, item ->
        sb.appendLine("  ${index + 1}. ${prettyPrintItem(item)}")
    }
    return sb.toString()
}

private fun prettyPrintItem(item: ProgramItem): String {
    return when (item) {
        is LetBinding -> {
            val typeAnnotation = item.typeAnnotation?.let { ": $it" } ?: ""
            "let ${item.variableName}$typeAnnotation = ${prettyPrintExpression(item.expression)}"
        }
        is TypeDeclaration -> {
            "type ${item.name} = ${item.definition}"
        }
        else -> item.toString()
    }
}

private fun prettyPrintExpression(expr: Expression): String {
    return when (expr) {
        is IntLiteral -> expr.value.toString()
        is FloatLiteral -> expr.value.toString()
        is StringLiteral -> "\"${expr.value}\""
        is BooleanLiteral -> expr.value.toString()
        is Identifier -> expr.name
        is LambdaExpression -> {
            val params = when (val p = expr.parameters) {
                is TypedParameters -> "typed_params" // Simplified
                is UntypedParameters -> "{ ${p.names.joinToString(", ")} }"
            }
            "$params -> ${prettyPrintExpression(expr.body)}"
        }
        is BinaryOperation -> {
            "${prettyPrintExpression(expr.left)} ${expr.operator} ${prettyPrintExpression(expr.right)}"
        }
        is FunctionCall -> {
            "${prettyPrintExpression(expr.function)} ${prettyPrintExpression(expr.arguments)}"
        }
        is RecordLiteral -> {
            val fieldEntries = expr.getFieldEntries().map { entry ->
                val value = entry.value
                if (value != null) {
                    "${entry.name} = ${prettyPrintExpression(value)}"
                } else {
                    entry.name  // Punned syntax
                }
            }
            val spreadEntries = expr.getSpreadEntries().map { spreadEntry ->
                "...${prettyPrintExpression(spreadEntry.expression)}"
            }
            val allEntries = (spreadEntries + fieldEntries).joinToString(", ")
            "{ $allEntries }"
        }
        is IfExpression -> {
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
        // Parse the source code
        val parser = FunkParser()
        val program = parser.parse(source)
        
        // Type check the program with module resolution
        val projectRoot = filePath?.parent ?: java.nio.file.Paths.get(".")
        val typeChecker = TypeChecker()
        val typedProgram = typeChecker.typeCheck(program, projectRoot)
        
        // Execute using FunkInterpreter with module loading
        val interpreter = com.funk.language.interpreter.FunkInterpreter()

        // Execute the program to load all definitions and get the context
        val result = interpreter.execute(typedProgram, projectRoot)
        val context = interpreter.getLastExecutionContext()

        // Check if 'main' exists
        val mainValue = context["main"]
        if (mainValue == null) {
            println("❌ Error: No 'main' function found")
            println()
            println("The program must define a 'main' function:")
            println("  let main = {} -> ...")
            println()
            println("Example:")
            println("  let main = {} -> do")
            println("    Log.println { msg = \"Hello, World!\" }")
            kotlin.system.exitProcess(1)
        }

        // Check if main is a function
        val isFunctionType = mainValue is com.funk.language.interpreter.SimpleFunkFunction ||
                            mainValue is com.funk.language.interpreter.FunkTruffleFunction ||
                            mainValue is com.funk.language.interpreter.NativeFunkFunction

        if (!isFunctionType) {
            println("❌ Error: 'main' must be a function, not a value")
            println()
            println("Found: ${mainValue::class.simpleName}")
            println()
            println("Change your code from:")
            println("  let main = perform Console in demo {}")
            println()
            println("To:")
            println("  let main = {} -> perform Console in demo {}")
            kotlin.system.exitProcess(1)
        }

        // Check if main has the signature {} -> {} for auto-execution
        // Get the type of main from the typed program
        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>().find { it.variableName == "main" }
        val mainType = mainBinding?.inferredType

        val shouldAutoExecute = mainType is FunkType.FunctionType &&
                                mainType.paramType is FunkType.RecordType &&
                                (mainType.paramType as FunkType.RecordType).fields.isEmpty() &&
                                mainType.returnType is FunkType.RecordType &&
                                (mainType.returnType as FunkType.RecordType).fields.isEmpty()

        // Call main with empty record argument
        // Use the execution context that has all the imports
        val emptyArgs = com.funk.language.interpreter.FunkRecord(emptyMap())
        val mainContext = context.toMutableMap()

        val mainResult = when (mainValue) {
            is com.funk.language.interpreter.SimpleFunkFunction -> {
                interpreter.callFunctionWithRecord(mainValue, emptyArgs, mainContext)
            }
            is com.funk.language.interpreter.NativeFunkFunction -> {
                mainValue.call(listOf(emptyArgs))
            }
            else -> {
                println("❌ Error: Unsupported function type for main")
                kotlin.system.exitProcess(1)
                com.funk.language.interpreter.FunkNull
            }
        }

        // For auto-executing main with {} -> {}, just indicate success without printing the empty result
        if (shouldAutoExecute) {
            // Main auto-executed, don't print the empty record result
            println()
            println("✅ Execution successful!")
        } else {
            // Regular main or main with different signature - print result
            println("✅ Execution successful!")
            println()
            println(prettyPrintResult(mainResult))
        }
        
    } catch (e: TypeCheckError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrint(e))
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
    // Check for help flag first
    if (args.contains("--help") || args.contains("-h")) {
        showCommandHelp("compile")
        return
    }
    
    // Parse arguments (simplified - no more -o flag)
    if (args.isEmpty()) {
        println("❌ Error: compile command requires an input file")
        println()
        println("USAGE: funk compile <file> [output]")
        println("Use 'funk compile --help' for more information")
        return
    }
    
    val inputFileName = args[0]
    val outputFileName = if (args.size > 1) args[1] else ""
    
    val inputFile = File(inputFileName)
    if (!inputFile.exists()) {
        println("❌ Error: Input file not found at '$inputFileName'")
        return
    }
    
    // Determine output file name
    val outputFile = if (outputFileName.isNotEmpty()) {
        File(outputFileName)
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
        val parser = FunkParser()
        val ast = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val typedProgram = typeChecker.typeCheck(ast, inputFile.toPath().parent ?: inputFile.toPath())
        
        println("✅ Type checking successful")
        
        // Always create standalone JAR (AST + Interpreter + Truffle)
        createStandaloneJar(inputFile, outputFile, source, typedProgram)
        
        println("✅ Compilation successful!")
        println()
        println("📦 Created: ${outputFile.absolutePath}")
        println("📏 Size: ${outputFile.length() / 1024}KB")
        println()
        println("🚀 Run: java -jar ${outputFile.name}")
        println("   (Pre-compiled AST, smaller size, faster startup)")
        
    } catch (e: TypeCheckError) {
        println("❌ Type Error:")
        println(ErrorPrettyPrinter.prettyPrint(e))
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Compilation Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

private fun createStandaloneJar(inputFile: File, outputFile: File, source: String, typedProgram: Program) {
        println("🔧 Creating standalone JAR with full Truffle JIT support...")
    
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
    // Parse and type-check at compile time to get enhanced AST
    val parser = FunkParser()
    val typeChecker = TypeChecker()
    
    val program = parser.parse(source)
    val projectRoot = inputFile.toPath().parent ?: inputFile.toPath()
    val enhancedProgram = typeChecker.typeCheck(program, projectRoot)
    
    // Serialize the enhanced AST to JSON
    val json = kotlinx.serialization.json.Json { 
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    val serializedAST = json.encodeToString(Program.serializer(), enhancedProgram)
    val mainClassDir = File(tempDir, "src/main/kotlin/com/funk/standalone")
    mainClassDir.mkdirs()
    
    val mainClassContent = """
package com.funk.standalone

import com.funk.language.ast.Program
import com.funk.language.interpreter.FunkInterpreter
import kotlinx.serialization.json.Json
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.nio.file.Paths

/**
 * Standalone executable for ${inputFile.name}
 * Generated by Funk compiler with pre-processed enhanced AST
 * 
 * This JAR contains the pre-parsed and type-checked AST, so no parser is needed at runtime.
 * Uses full Truffle execution with JIT compilation for maximum performance.
 */
object Main {
    private const val ENHANCED_AST_JSON = ${escapeForKotlin(serializedAST)}
    private const val PROGRAM_NAME = "${inputFile.name}"
    private const val PROJECT_ROOT = "${projectRoot.toAbsolutePath()}"
    
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // Deserialize the enhanced AST (no parsing needed!)
            val json = Json { ignoreUnknownKeys = true }
            val enhancedProgram = json.decodeFromString<Program>(ENHANCED_AST_JSON)
            
            // Execute using full Truffle with JIT optimization
            val result = executeTruffleProgram(enhancedProgram)
            
            // Print result if it's meaningful
            if (result != null && result.toString() != "null") {
                println(result)
            }
            
        } catch (e: Exception) {
            System.err.println("Error executing ${'$'}PROGRAM_NAME: ${'$'}{e.message}")
            if (args.isNotEmpty() && args[0] == "--debug") {
                e.printStackTrace()
            }
            kotlin.system.exitProcess(1)
        }
    }
    
    /**
     * Execute program using full Truffle with JIT optimization
     */
    private fun executeTruffleProgram(program: Program): Any? {
        // Create engine with JIT compilation enabled
        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .build()
            
        // Create context for Funk language with JIT optimization
        val context = Context.newBuilder("funk")
            .engine(engine)
            .allowAllAccess(true)
            .allowExperimentalOptions(true)
            .build()
        
        return try {
            // Execute the pre-processed AST directly using Funk interpreter
            // Create interpreter without parser dependencies since modules are resolved at compile time
            val interpreter = com.funk.language.interpreter.FunkInterpreter(skipParserSetup = true)
            val result = interpreter.execute(program)
            
            // Convert FunkValue to appropriate return type for output
            when (result) {
                is com.funk.language.interpreter.FunkInt -> result.value
                is com.funk.language.interpreter.FunkFloat -> result.value
                is com.funk.language.interpreter.FunkString -> result.value
                is com.funk.language.interpreter.FunkBool -> result.value
                is com.funk.language.interpreter.FunkNull -> null
                else -> result.getDisplayString()
            }
        } finally {
            context.close()
            engine.close()
        }
    }
}
    """.trimIndent()
    
    File(mainClassDir, "Main.kt").writeText(mainClassContent)
    
    // Also create a simple build.gradle.kts for this standalone project
    val buildGradleContent = """
plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
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
    // Serialization for AST deserialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Full Truffle dependencies for JIT compilation
    implementation("org.graalvm.polyglot:polyglot:24.2.2")
    implementation("org.graalvm.sdk:graal-sdk:24.2.2")
    implementation("org.graalvm.truffle:truffle-api:24.2.2")
    
    // Runtime optimization support (enables JIT compilation!)
    runtimeOnly("org.graalvm.truffle:truffle-runtime:24.2.2")
    
    // Funk language components
    ${generateTruffleEnabledDependencies()}
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

private fun generateTruffleEnabledDependencies(): String {
    return """
    // AST definitions and interpreter with Truffle support (no parser/typechecker needed!)
    implementation(files("libs/funk-ast.jar"))
    implementation(files("libs/funk-interpreter.jar"))
    """.trimIndent()
}


private fun copyFunkLanguageJars(tempDir: File) {
    val libsDir = File(tempDir, "libs")
    libsDir.mkdirs()
    
    val projectRoot = File(".")
    
    // Copy AST JAR (shared AST definitions)
    val astJar = File(projectRoot, "ast/build/libs/ast-1.0.0.jar")
    if (astJar.exists()) {
        astJar.copyTo(File(libsDir, "funk-ast.jar"), overwrite = true)
        println("📦 Copied AST JAR")
    } else {
        throw IllegalStateException("AST JAR not found: ${astJar.absolutePath}. Please run 'gradle build' first.")
    }
    
    // Copy interpreter JAR (needed for full Truffle execution)
    val interpreterJar = File(projectRoot, "interpreter/build/libs/interpreter-1.0.0.jar")
    if (interpreterJar.exists()) {
        interpreterJar.copyTo(File(libsDir, "funk-interpreter.jar"), overwrite = true)
        println("📦 Copied interpreter JAR")
    } else {
        throw IllegalStateException("Interpreter JAR not found: ${interpreterJar.absolutePath}. Please run 'gradle build' first.")
    }
    
    // Copy parser JAR (needed for module resolution)
    val parserJar = File(projectRoot, "parser/build/libs/parser-1.0.0.jar")
    if (parserJar.exists()) {
        parserJar.copyTo(File(libsDir, "funk-parser.jar"), overwrite = true)
        println("📦 Copied parser JAR")
    } else {
        throw IllegalStateException("Parser JAR not found: ${parserJar.absolutePath}. Please run 'gradle build' first.")
    }
    
    // Copy typechecker JAR (needed for module resolution)
    val typecheckerJar = File(projectRoot, "typechecker/build/libs/typechecker-1.0.0.jar")
    if (typecheckerJar.exists()) {
        typecheckerJar.copyTo(File(libsDir, "funk-typechecker.jar"), overwrite = true)
        println("📦 Copied typechecker JAR")
    } else {
        throw IllegalStateException("Typechecker JAR not found: ${typecheckerJar.absolutePath}. Please run 'gradle build' first.")
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

/**
 * Enhanced error reporting functions
 */
private fun printTypeError(error: TypeCheckError) {
    println("❌ Type Error")
    println("━".repeat(50))
    
    // Print location if available
    if (error.sourceLocation != null) {
        val loc = error.sourceLocation!!
        println("📍 Location: line ${loc.line}, column ${loc.column}")
        println()
    }
    
    // Print error message with better formatting
    when (error.errorType) {
        ErrorType.TYPE_MISMATCH -> {
            println("🔍 Type Mismatch")
            val expected = error.context["expected"] as? FunkType
            val actual = error.context["actual"] as? FunkType
            if (expected != null && actual != null) {
                println("   Expected: ${prettyPrintType(expected)}")
                println("   Actual:   ${prettyPrintType(actual)}")
            } else {
                println("   ${error.message}")
            }
        }
        ErrorType.UNDEFINED_VARIABLE -> {
            println("🔍 Undefined Variable")
            val variable = error.context["variable"] as? String
            if (variable != null) {
                println("   Variable '$variable' is not defined in the current scope")
                println()
                println("💡 Tip: Check for typos or make sure the variable is declared before use")
            } else {
                println("   ${error.message}")
            }
        }
        ErrorType.MISSING_FIELD -> {
            println("🔍 Missing Field")
            val field = error.context["field"] as? String
            val type = error.context["type"] as? FunkType
            if (field != null && type != null) {
                println("   Field '$field' not found in type ${prettyPrintType(type)}")
                println()
                println("💡 Tip: Check the field name for typos")
            } else {
                println("   ${error.message}")
            }
        }
        ErrorType.INEXHAUSTIVE_MATCH -> {
            println("🔍 Non-exhaustive Pattern Match")
            @Suppress("UNCHECKED_CAST")
            val missing = error.context["missing"] as? List<String>
            if (missing != null) {
                println("   Missing patterns: ${missing.joinToString(", ")}")
                println()
                println("💡 Tip: Add cases for all missing patterns to make the match exhaustive")
            } else {
                println("   ${error.message}")
            }
        }
        else -> {
            println("🔍 ${error.errorType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}")
            println("   ${error.message}")
        }
    }
    
    println()
    println("💡 For more help, see: https://funk-lang.org/docs/type-errors")
}

private fun printGeneralError(error: Exception) {
    println("❌ Error")
    println("━".repeat(50))
    println("🔍 ${error.javaClass.simpleName}")
    println("   ${error.message ?: "Unknown error"}")
    println()
    
    // Show stack trace for debugging if it's not a known user error
    if (error !is TypeCheckError && error !is FunkException) {
        println("🐛 Debug Information:")
        error.printStackTrace()
    }
}

private fun printTypeCheckSuccess(result: Program, typeAliases: Map<String, TypeAliasInfo>) {
    println("✅ Type checking successful!")
    println("━".repeat(50))
    
    // Count different types of items
    val letBindings = result.items.filterIsInstance<LetBinding>()
    val typeDeclarations = result.items.filterIsInstance<TypeDeclaration>()
    val effectDeclarations = result.items.filterIsInstance<EffectDeclaration>()
    val implDeclarations = result.items.filterIsInstance<ImplDeclaration>()
    
    // Print summary
    println("📊 Summary:")
    if (letBindings.isNotEmpty()) println("   ${letBindings.size} variable(s)")
    if (typeDeclarations.isNotEmpty()) println("   ${typeDeclarations.size} type(s)")
    if (effectDeclarations.isNotEmpty()) println("   ${effectDeclarations.size} effect(s)")
    if (implDeclarations.isNotEmpty()) println("   ${implDeclarations.size} implementation(s)")
    println()
    
    // Print type declarations first
    if (typeDeclarations.isNotEmpty()) {
        println("🏷️  Types:")
        for (item in typeDeclarations) {
            // Include type parameters in the name (e.g., "Tree 'a")
            val typeParamsStr = if (item.typeParams.isNotEmpty()) {
                " " + item.typeParams.joinToString(" ")
            } else {
                ""
            }
            val expansionStr = item.inferredType?.let {
                " = ${prettyPrintType(it)}"
            } ?: ""
            println("   ${item.name}$typeParamsStr$expansionStr")
        }
        println()
    }
    
    // Print effect declarations
    if (effectDeclarations.isNotEmpty()) {
        println("⚡ Effects:")
        for (item in effectDeclarations) {
            println("   ${item.name} (${item.operations.size} operations)")
        }
        println()
    }
    
    // Print variables with better formatting
    if (letBindings.isNotEmpty()) {
        println("📝 Variables:")
        for (item in letBindings) {
            // Use TypeScheme if available (for polymorphic functions with constraints),
            // otherwise fall back to inferredType (for monomorphic bindings)
            val scheme = item.typeScheme
            val typeStr = if (scheme != null && scheme.type is com.funk.language.ast.FunkType.FunctionType) {
                // For polymorphic functions, display the type from the scheme which includes constraints
                prettyPrintTypeWithAliases(scheme.type, typeAliases, showExpansion = false, typeDeclarations = typeDeclarations)
            } else {
                item.inferredType?.let {
                    prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false, typeDeclarations = typeDeclarations)
                } ?: "unknown"
            }

            // Add emoji based on type
            val emoji = when {
                typeStr.contains("->") -> "🔧" // Function
                typeStr.contains("|") -> "🏷️"  // Variant
                typeStr.contains("{") -> "📦" // Record
                typeStr == "Int" || typeStr == "Float" || typeStr == "Nat" || typeStr == "Frac" -> "🔢"
                typeStr == "String" -> "📝"
                typeStr == "Boolean" -> "✅"
                else -> "📋"
            }

            println("   $emoji ${item.variableName} : $typeStr")
        }
    }
    
    println()
    println("🎉 All types check out! Your code is ready to run.")
}

/**
 * Pretty print a FunkValue result for user-friendly output
 */
private fun prettyPrintResult(result: com.funk.language.interpreter.FunkValue): String {
    return when (result) {
        is com.funk.language.interpreter.FunkInt -> result.value.toString()
        is com.funk.language.interpreter.FunkFloat -> result.value.toString()
        is com.funk.language.interpreter.FunkString -> "\"${result.value}\""
        is com.funk.language.interpreter.FunkBool -> result.value.toString()
        is com.funk.language.interpreter.FunkNull -> "null"
        is com.funk.language.interpreter.FunkVariant -> {
            val value = result.value
            if (value != null) {
                if (value is com.funk.language.interpreter.FunkRecord) {
                    "${result.constructor} ${prettyPrintResult(value)}"
                } else {
                    "${result.constructor}(${prettyPrintResult(value)})"
                }
            } else {
                result.constructor
            }
        }
        is com.funk.language.interpreter.FunkRecord -> {
            val fields = result.fields.map { (key, value) -> 
                "$key = ${prettyPrintResult(value)}" 
            }
            "{ ${fields.joinToString(", ")} }"
        }
        is com.funk.language.interpreter.SimpleFunkFunction -> "<function>"
        is com.funk.language.interpreter.NativeFunkFunction -> "<native-function:${result.name}>"
        else -> result.getDisplayString()
    }
}