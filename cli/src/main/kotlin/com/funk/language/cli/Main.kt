package com.funk.language.cli

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintType
import com.funk.language.ast.prettyPrintTypeWithAliases
import com.funk.language.typechecker.*
import com.funk.language.interpreter.FunkException
import com.funk.language.interpreter.FunkLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
            println("Execute Funk source code directly without compilation")
            println("Fast startup, direct execution with full Truffle JIT optimization")
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

    val fileName = if (args.size == 1) args[0] else null

    try {
        val parser = FunkParser()
        val ast = parser.parse(source, file = fileName)
        println("✅ Parsing successful!")
        println()
        println(prettyPrintAST(ast))
    } catch (e: com.funk.language.parser.ParseException) {
        println("❌ Parse Error")
        println("━".repeat(50))
        println(e.message)
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
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
        val fileName = filePath?.fileName?.toString()
        val ast = parser.parse(source, file = fileName)

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
        // Pass source code and file for enhanced error messages with code snippets
        val fileName = filePath?.fileName?.toString()
        println(ErrorPrettyPrinter.prettyPrint(e, source, fileName))
        kotlin.system.exitProcess(1)
    } catch (e: com.funk.language.parser.ParseException) {
        println("❌ Parse Error")
        println("━".repeat(50))
        println(e.message)
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
        // BinaryOperation removed - operators are desugared at parse time
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

    // First, type-check to ensure program returns {} with no unhandled effects
    val parser = FunkParser()
    val fileName = filePath?.fileName?.toString()
    val ast = parser.parse(source, file = fileName)

    val typeChecker = TypeChecker()
    val typeCheckResult = if (filePath != null) {
        typeChecker.typeCheck(ast, filePath.parent ?: filePath)
    } else {
        typeChecker.typeCheck(ast)
    }

    // Check if program has a 'main' function and auto-call it
    val mainBinding = typeCheckResult.program.items.filterIsInstance<LetBinding>()
        .find { it.variableName == "main" }

    val programToExecute = if (mainBinding != null) {
        // Validate that main has type {} -> {}
        val mainType = mainBinding.inferredType
        val isValidMainType = mainType is FunkType.FunctionType &&
                              mainType.paramType is FunkType.RecordType &&
                              (mainType.paramType as FunkType.RecordType).fields.isEmpty() &&
                              !(mainType.paramType as FunkType.RecordType).isOpen &&
                              mainType.returnType is FunkType.RecordType &&
                              (mainType.returnType as FunkType.RecordType).fields.isEmpty() &&
                              !(mainType.returnType as FunkType.RecordType).isOpen

        if (!isValidMainType) {
            println("❌ Type Error: 'main' must have type {} -> {}")
            println("━".repeat(50))
            println("Expected: {} -> {}")
            println("Actual:   ${prettyPrintType(mainType!!)}")
            println()
            println("💡 The main function must:")
            println("   - Take no arguments: {}")
            println("   - Return nothing: {}")
            println("   - Perform side effects (like printing) instead of returning values")
            kotlin.system.exitProcess(1)
        }

        // Add a call to main() at the end of the program
        val mainCall = com.funk.language.ast.FunctionCall(
            function = com.funk.language.ast.Identifier("main", null, null),
            arguments = com.funk.language.ast.RecordLiteral(emptyList(), null, null),
            inferredType = null,
            sourceLocation = null
        )
        typeCheckResult.program.copy(
            items = typeCheckResult.program.items + listOf(com.funk.language.ast.TopLevelExpression(mainCall))
        )
    } else {
        // No main function - check that the last expression returns {}
        val lastItem = typeCheckResult.program.items.lastOrNull()
        val programType = when (lastItem) {
            is LetBinding -> lastItem.inferredType
            is TopLevelExpression -> lastItem.expression.inferredType
            else -> null
        }

        val isValidType = programType is FunkType.RecordType &&
                          programType.fields.isEmpty() &&
                          !programType.isOpen

        if (!isValidType && programType != null) {
            println("❌ Type Error: Program must return {} with no unhandled effects")
            println("━".repeat(50))
            println("Expected: {}")
            println("Actual:   ${prettyPrintType(programType)}")
            println()

            if (programType is FunkType.SuspendedType) {
                println("💡 You have unhandled effects: <${programType.effects.effects.joinToString(", ")}>")
                println("   Use 'perform <Handler> in' to handle all effects before the program ends")
            } else {
                println("💡 Tip: Programs executed with 'funk exec' must return {}")
                println("   Define a 'main' function or end your program with {}")
            }
            kotlin.system.exitProcess(1)
        }

        typeCheckResult.program
    }

    try {
        // Execute using the interpreter directly with the already type-checked AST
        val interpreter = com.funk.language.interpreter.FunkInterpreter(skipParserSetup = false)
        val result = interpreter.execute(programToExecute)

        // Program should return {}, so we don't print the result
        println()
        println("✅ Execution successful!")

    } catch (e: TypeCheckError) {
        // Pass source code and file for enhanced error messages with code snippets
        val fileName = filePath?.fileName?.toString()
        println(ErrorPrettyPrinter.prettyPrint(e, source, fileName))
        kotlin.system.exitProcess(1)
    } catch (e: com.funk.language.parser.ParseException) {
        println("❌ Parse Error")
        println("━".repeat(50))
        println(e.message)
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

    // Read source code before try block so it's available in catch for error messages
    val source = inputFile.readText()

    try {
        println("🔨 Compiling ${inputFile.name} to ${outputFile.name}...")
        println()

        // Parse and type check the source
        val parser = FunkParser()
        val ast = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val typeCheckResult = typeChecker.typeCheck(ast, inputFile.toPath().parent ?: inputFile.toPath())

        println("✅ Type checking successful")

        // Validate that program has a 'main' function with type {} -> {}
        val mainBinding = typeCheckResult.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        if (mainBinding == null) {
            println("❌ Compilation Error: Missing 'main' function")
            println("━".repeat(50))
            println("Compiled programs must define a 'main' function")
            println()
            println("Example:")
            println("  use std::effects::log::{println, Log, Console}")
            println()
            println("  let main = {} -> perform Console in do")
            println("    println { msg = \"Hello, World!\" }")
            println()
            kotlin.system.exitProcess(1)
        }

        // Check that main has type {} -> {}
        val mainType = mainBinding.inferredType
        val isValidMainType = mainType is FunkType.FunctionType &&
                              mainType.paramType is FunkType.RecordType &&
                              (mainType.paramType as FunkType.RecordType).fields.isEmpty() &&
                              !(mainType.paramType as FunkType.RecordType).isOpen &&
                              mainType.returnType is FunkType.RecordType &&
                              (mainType.returnType as FunkType.RecordType).fields.isEmpty() &&
                              !(mainType.returnType as FunkType.RecordType).isOpen

        if (!isValidMainType) {
            println("❌ Type Error: 'main' must have type {} -> {}")
            println("━".repeat(50))
            println("Expected: {} -> {}")
            println("Actual:   ${prettyPrintType(mainType!!)}")
            println()
            println("💡 The main function must:")
            println("   - Take no arguments: {}")
            println("   - Return nothing: {}")
            println("   - Perform side effects (like printing) instead of returning values")
            println()
            println("Example:")
            println("  use std::effects::log::{println, Log, Console}")
            println()
            println("  let main = {} -> perform Console in do")
            println("    println { msg = \"Hello, World!\" }")
            println()
            kotlin.system.exitProcess(1)
        }

        // Always create standalone JAR (AST + Interpreter + Truffle)
        createStandaloneJar(inputFile, outputFile, source, typeCheckResult.program)
        
        println("✅ Compilation successful!")
        println()
        println("📦 Created: ${outputFile.absolutePath}")
        println("📏 Size: ${outputFile.length() / 1024}KB")
        println()
        println("🚀 Run: java --sun-misc-unsafe-memory-access=allow -jar ${outputFile.name}")
        println("   (Suppresses Truffle deprecation warnings in Java 24)")
        println()
        println("💡 Alternative (with warnings): java -jar ${outputFile.name}")
        
    } catch (e: TypeCheckError) {
        // Pass source code and file for enhanced error messages with code snippets
        println(ErrorPrettyPrinter.prettyPrint(e, source, inputFile.name))
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
    println("🔍 Build directory: ${tempDir.absolutePath}")
    
    try {
        // 1. Create a Kotlin main class that runs the embedded program
        createKotlinMainClass(tempDir, inputFile, source)

        // 2. Build the JAR using Gradle task (which will compile Kotlin)
        buildJarWithGradle(tempDir, outputFile, inputFile.nameWithoutExtension)

        // 3. Create launcher script for seamless execution (no warnings!)
        createLauncherScript(outputFile)

        // Clean up temp directory on success
        tempDir.deleteRecursively()
    } catch (e: Exception) {
        // Keep temp directory on failure for debugging
        println("⚠️  Temp directory preserved for debugging: ${tempDir.absolutePath}")
        throw e
    }
}

private fun createKotlinMainClass(tempDir: File, inputFile: File, source: String) {
    // Parse and type-check at compile time to get enhanced AST
    val parser = FunkParser()
    val typeChecker = TypeChecker()

    val program = parser.parse(source)
    val projectRoot = inputFile.toPath().parent ?: inputFile.toPath()
    val typeCheckResult = typeChecker.typeCheck(program, projectRoot)

    // Serialize the enhanced AST to JSON (clean user AST only, no compiler artifacts)
    val json = kotlinx.serialization.json.Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    val serializedAST = json.encodeToString(Program.serializer(), typeCheckResult.program)

    // Get all cached modules (including stdlib) and serialize them
    val cachedModules = TypeChecker.getAllCachedModules()
    val serializedModules = json.encodeToString(
        MapSerializer(
            String.serializer(),
            TypeChecker.Companion.CachedModule.serializer()
        ),
        cachedModules
    )
    val mainClassDir = File(tempDir, "src/main/kotlin/com/funk/standalone")
    mainClassDir.mkdirs()
    
    val mainClassContent = """
package com.funk.standalone

import com.funk.language.ast.Program
import com.funk.language.interpreter.FunkInterpreter
import com.funk.language.typechecker.TypeChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    private val ENHANCED_AST_JSON = ${escapeForKotlin(serializedAST)}
    private val CACHED_MODULES_JSON = ${escapeForKotlin(serializedModules)}
    private const val PROGRAM_NAME = "${inputFile.name}"
    private const val PROJECT_ROOT = "${projectRoot.toAbsolutePath()}"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // Deserialize all cached modules (stdlib, etc.) and restore to TypeChecker
            val json = Json { ignoreUnknownKeys = true }
            val cachedModules = json.decodeFromString(
                MapSerializer(String.serializer(), TypeChecker.Companion.CachedModule.serializer()),
                CACHED_MODULES_JSON
            )
            TypeChecker.restoreModuleCache(cachedModules)

            // Deserialize the enhanced AST (no parsing needed!)
            val enhancedProgram = json.decodeFromString<Program>(ENHANCED_AST_JSON)

            // Add a call to main() at the end of the program
            val mainCall = com.funk.language.ast.FunctionCall(
                function = com.funk.language.ast.Identifier("main", null, null),
                arguments = com.funk.language.ast.RecordLiteral(emptyList(), null, null),
                inferredType = null,
                sourceLocation = null
            )
            val programWithMainCall = enhancedProgram.copy(
                items = enhancedProgram.items + listOf(com.funk.language.ast.TopLevelExpression(mainCall))
            )

            // Execute using full Truffle with JIT optimization
            val result = executeTruffleProgram(programWithMainCall)

            // Don't print the result since main returns {} which is not meaningful output
            
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
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
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
    jvmToolchain(24)
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.funk.standalone.Main"
        // Java 24 native access permissions for Truffle polyglot API
        attributes["Add-Opens"] = "java.base/jdk.internal.misc=ALL-UNNAMED java.base/sun.nio.ch=ALL-UNNAMED"
        // Enable native access for Truffle (suppresses System::load warnings)
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        // JVM flags for cleaner execution (optional, user can override with JAVA_TOOL_OPTIONS)
        // Suppresses both System::load and sun.misc.Unsafe warnings
        attributes["JVM-Args"] = "--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
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
    // Funk language components (all needed for runtime)
    implementation(files("libs/funk-ast.jar"))
    implementation(files("libs/funk-parser.jar"))
    implementation(files("libs/funk-typechecker.jar"))
    implementation(files("libs/funk-interpreter.jar"))

    // ANTLR runtime (required by parser)
    implementation("org.antlr:antlr4-runtime:4.13.1")
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

private fun createLauncherScript(jarFile: File) {
    val jarName = jarFile.name
    val baseName = jarFile.nameWithoutExtension

    // Create Unix launcher script
    val unixScript = File(jarFile.parentFile, baseName)
    val unixScriptContent = """
#!/usr/bin/env bash
# Launcher script for ${jarName}
# Auto-generated by Funk compiler - suppresses Java 24 warnings

# Get directory of this script
DIR="${'$'}( cd "${'$'}( dirname "${'$'}{BASH_SOURCE[0]}" )" && pwd )"

# Run with flags to suppress Truffle deprecation warnings
exec java --sun-misc-unsafe-memory-access=allow -jar "${'$'}DIR/${jarName}" "${'$'}@"
    """.trimIndent()

    unixScript.writeText(unixScriptContent)
    unixScript.setExecutable(true)

    // Create Windows launcher script
    val windowsScript = File(jarFile.parentFile, "$baseName.bat")
    val windowsScriptContent = """
@echo off
REM Launcher script for ${jarName}
REM Auto-generated by Funk compiler - suppresses Java 24 warnings

REM Get directory of this script
set "DIR=%~dp0"

REM Run with flags to suppress Truffle deprecation warnings
java --sun-misc-unsafe-memory-access=allow -jar "%DIR%${jarName}" %*
    """.trimIndent()

    windowsScript.writeText(windowsScriptContent)

    println("📜 Created launcher scripts: ${baseName} (Unix) and ${baseName}.bat (Windows)")
}

private fun escapeForKotlin(source: String): String {
    // Escape $ signs to prevent Kotlin string interpolation
    // Escape """ to prevent premature string termination
    val escaped = source
        .replace("$", "\${'$'}")  // $var becomes ${'$'}var (no interpolation)
        .replace("\"\"\"", "\\\"\\\"\\\"")  // """ becomes \"\"\" (escaped)
    return "\"\"\"" + escaped + "\"\"\""
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

private fun printTypeCheckSuccess(result: TypeCheckResult, typeAliases: Map<String, TypeAliasInfo>) {
    println("✅ Type checking successful!")
    println("━".repeat(50))

    // Count different types of items
    val letBindings = result.program.items.filterIsInstance<LetBinding>()
    val typeDeclarations = result.program.items.filterIsInstance<TypeDeclaration>()
    val effectDeclarations = result.program.items.filterIsInstance<EffectDeclaration>()
    val implDeclarations = result.program.items.filterIsInstance<ImplDeclaration>()
    
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
        is com.funk.language.interpreter.NativeFunkFunction -> "<native-function:${result.name}>"
        else -> {
            // Check if it's FunkTruffleFunction (from either package) using class name
            if (result::class.simpleName == "FunkTruffleFunction") {
                "<function>"
            } else {
                result.getDisplayString()
            }
        }
    }
}