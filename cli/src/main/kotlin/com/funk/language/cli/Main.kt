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

/**
 * Detect the project root by finding the directory containing the stdlib folder.
 * This allows the funk CLI to find stdlib modules regardless of where it's run from.
 */
private fun detectProjectRoot(): File? {
    // Strategy 0: Read embedded project root from JAR resource (works when binary is relocated)
    try {
        val resourceStream = object {}.javaClass.classLoader.getResourceAsStream("funk-project-root.txt")
        if (resourceStream != null) {
            val projectRootPath = resourceStream.bufferedReader().use { it.readText().trim() }
            val projectRoot = File(projectRootPath)

            // Verify the path still exists and has stdlib
            val stdlibDir = File(projectRoot, "stdlib")
            if (stdlibDir.exists() && stdlibDir.isDirectory) {
                return projectRoot
            }
        }
    } catch (e: Exception) {
        // If resource reading fails, try other strategies
    }

    // Strategy 1: Find the location of this class file (Main.class)
    // For installed funk CLI, this will be in cli/build/install/funk/lib/...
    // For development, this will be in cli/build/classes/kotlin/main/...
    try {
        // Get the location of the JAR or class files containing this function
        val classLocation = object {}.javaClass.protectionDomain.codeSource.location
        val classFile = File(classLocation.toURI())

        // Navigate upward to find the project root (directory containing stdlib/)
        var current: File? = classFile.parentFile
        while (current != null) {
            val stdlibDir = File(current, "stdlib")
            val isSymlink = Files.isSymbolicLink(stdlibDir.toPath())

            // Only accept non-symlink stdlib directories (skip cli/stdlib -> ../stdlib)
            if (stdlibDir.exists() && stdlibDir.isDirectory && !isSymlink) {
                // Found it! This is the project root
                return current
            }
            current = current.parentFile
        }
    } catch (e: Exception) {
        // If class location detection fails, try other strategies
    }

    // Strategy 2: Check if stdlib exists in current working directory
    val currentDir = File(System.getProperty("user.dir"))
    val stdlibInCurrentDir = File(currentDir, "stdlib")
    if (stdlibInCurrentDir.exists() && stdlibInCurrentDir.isDirectory) {
        return currentDir
    }

    // Strategy 3: Check parent directories of current working directory
    var current: File? = currentDir.parentFile
    while (current != null) {
        val stdlibDir = File(current, "stdlib")
        if (stdlibDir.exists() && stdlibDir.isDirectory) {
            return current
        }
        current = current.parentFile
    }

    // Could not detect project root - return null
    // The user will need to set funk.project.root manually via JAVA_OPTS
    return null
}

fun main(args: Array<String>) {
    // Auto-detect and set funk.project.root if not already set
    // This is mainly for development mode - stdlib is embedded in production native binary
    if (System.getProperty("funk.project.root") == null) {
        val projectRoot = detectProjectRoot()
        if (projectRoot != null) {
            System.setProperty("funk.project.root", projectRoot.absolutePath)
        }
    }

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
            "dev" -> handleDevCommand(remainingArgs)
            "build" -> handleBuildCommand(remainingArgs)
            // Package management commands
            "init" -> handleInitCommand(remainingArgs)
            "add" -> handleAddCommand(remainingArgs)
            "remove" -> handleRemoveCommand(remainingArgs)
            "sync" -> handleSyncCommand(remainingArgs)
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
    println("FILE-BASED COMMANDS (no Funk.toml needed):")
    println("  parse     Parse Funk code and show AST")
    println("  check     Type check Funk code and show inferred types")
    println("  exec      Execute a .funk file directly")
    println("  compile   Compile a .funk file to standalone JAR")
    println()
    println("PACKAGE-BASED COMMANDS (requires Funk.toml):")
    println("  dev       Run app from Funk.toml (for development)")
    println("  build     Compile app from Funk.toml to JAR (for production)")
    println()
    println("PACKAGE MANAGEMENT:")
    println("  init      Create a new Funk package")
    println("  add       Add a dependency to Funk.toml")
    println("  remove    Remove a dependency from Funk.toml")
    println("  sync      Download and install all dependencies")
    println()
    println("GLOBAL OPTIONS:")
    println("  -h, --help     Show this help message")
    println("  -v, --version  Show version information")
    println()
    println("EXAMPLES:")
    println("  funk init --app                      # Create an app package")
    println("  funk dev                             # Run app from Funk.toml")
    println("  funk build                           # Build app to JAR")
    println("  funk exec program.funk               # Execute a file")
    println("  funk compile program.funk            # Compile file to JAR")
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
    println("COMMANDS:")
    println("  File-based:    parse, check, exec, compile")
    println("  Package-based: dev, build")
    println("  Packages:      init, add, remove, sync")
    println()
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
            println("USAGE: funk check [options] [file]")
            println()
            println("Type check Funk source code")
            println()
            println("MODES:")
            println("  funk check <file>          Check file + all imports, show types")
            println("  funk check                 Check whole app from Funk.toml")
            println("  funk check --app <name>    Check specific app from Funk.toml")
            println()
            println("OPTIONS:")
            println("  -q, --quiet        Only show errors (no type info)")
            println("  --app <name>       Check specific app from Funk.toml")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk check src/app.funk       # Check file + imports")
            println("  funk check --quiet app.funk   # Quiet mode")
            println("  funk check                    # Check package app")
            println("  funk check --app server       # Check specific app")
        }
        
        "exec" -> {
            println("USAGE: funk exec [file]")
            println()
            println("Execute Funk source code directly without compilation")
            println("Fast startup, direct execution with full Truffle JIT optimization")
            println()
            println("REQUIREMENTS:")
            println("  Program must define 'main' with type: {} -> {} <*>")
            println("  - Takes no arguments: {}")
            println("  - Returns unit: {}")
            println("  - Performs effects: <*> (use 'perform Handler in')")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLE:")
            println("  use std::effects::log::{println, Log, Console}")
            println()
            println("  let main = {} -> perform Console in")
            println("    println { msg = \"Hello, World!\" }")
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

        "init" -> {
            println("USAGE: funk init [options]")
            println()
            println("Create a new Funk package in the current directory")
            println()
            println("OPTIONS:")
            println("  --lib              Create a library package")
            println("  --app              Create an app package")
            println("  --lib --app        Create both library and app")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk init --lib                 # Create library")
            println("  funk init --app                 # Create app")
            println("  funk init --lib --app           # Create both")
            println("  funk init                       # Interactive mode")
            println()
        }

        "add" -> {
            println("USAGE: funk add [options] <url>")
            println()
            println("Add a dependency to Funk.toml")
            println()
            println("ARGUMENTS:")
            println("  <url>              Full URL with SHA-256 checksum")
            println()
            println("OPTIONS:")
            println("  --dev              Add to [dev-dependencies]")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk add \"https://github.com/user/pkg/releases/download/v1.0.0/pkg.tar.gz#sha256:abc123...\"")
            println("  funk add --dev \"https://github.com/user/test/releases/download/v0.1.0/test.tar.gz#sha256:def456...\"")
            println()
            println("💡 After adding dependencies, run 'funk sync' to download them")
            println()
        }

        "remove" -> {
            println("USAGE: funk remove <package_name>")
            println()
            println("Remove a dependency from Funk.toml")
            println()
            println("ARGUMENTS:")
            println("  <package_name>     Name of the package to remove")
            println()
            println("EXAMPLES:")
            println("  funk remove json")
            println("  funk remove test_framework")
            println()
        }

        "sync" -> {
            println("USAGE: funk sync [options]")
            println()
            println("Download and install all dependencies from Funk.toml")
            println()
            println("OPTIONS:")
            println("  --clean            Delete and re-download all packages")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk sync                       # Download missing packages")
            println("  funk sync --clean               # Re-download everything")
            println()
            println("📦 Packages are installed to: ./funk_packages/")
            println("🔒 Lock file is generated: ./Funk.lock")
            println()
        }

        "dev" -> {
            println("USAGE: funk dev [options]")
            println()
            println("Execute app from Funk.toml (for development)")
            println()
            println("Reads [[app]] entries from Funk.toml and executes the specified app.")
            println("Auto-calls the main function. For quick iteration during development.")
            println()
            println("OPTIONS:")
            println("  --app <name>       Run a specific app by name")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk dev                        # Run default app (first [[app]])")
            println("  funk dev --app server           # Run specific app")
            println()
            println("💡 For file-based execution, use: funk exec <file>")
            println()
        }

        "build" -> {
            println("USAGE: funk build [options]")
            println()
            println("Compile app from Funk.toml to standalone JAR (for production)")
            println()
            println("Reads [[app]] entries from Funk.toml and compiles to a JAR.")
            println("Includes dependencies and Truffle runtime for deployment.")
            println()
            println("OPTIONS:")
            println("  --app <name>       Build a specific app by name")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  funk build                      # Build default app")
            println("  funk build --app server         # Build specific app")
            println()
            println("OUTPUT:")
            println("  Single app:    <package-name>.jar")
            println("  Multiple apps: <app-name>.jar")
            println()
            println("💡 For file-based compilation, use: funk compile <file>")
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
        val startTime = System.nanoTime()
        val parser = FunkParser()
        val ast = parser.parse(source, file = fileName)
        val parseTime = (System.nanoTime() - startTime) / 1_000_000.0 // Convert to milliseconds

        println(prettyPrintAST(ast))
        println()
        println("Parsed in ${String.format("%.2f", parseTime)}ms")
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
    // Parse flags
    val quietMode = args.contains("-q") || args.contains("--quiet")

    // Parse --app flag (only if --app is actually present)
    val appIndex = args.indexOf("--app")
    val appName = when {
        args.any { it.startsWith("--app=") } -> args.find { it.startsWith("--app=") }?.substringAfter("=")
        appIndex >= 0 && appIndex + 1 < args.size -> args[appIndex + 1]
        else -> null
    }

    // Get file arguments (exclude flags and --app value)
    val fileArgs = args.filter { arg ->
        arg != "-q" && arg != "--quiet" && arg != "--app" &&
        !arg.startsWith("--app=") && arg != appName
    }

    when {
        // Package mode: check whole app from Funk.toml
        fileArgs.isEmpty() && appName == null -> handlePackageCheck(quietMode)
        fileArgs.isEmpty() && appName != null -> handlePackageCheck(quietMode, appName)

        // File mode: check file + all imports
        fileArgs.size == 1 -> handleFileCheck(fileArgs[0], quietMode)

        else -> {
            println("Error: check command takes at most 1 argument (filename)")
            showUsage()
            return
        }
    }
}

private fun handleFileCheck(filePath: String, quietMode: Boolean) {
    val file = File(filePath)
    if (!file.exists()) {
        println("Error: File not found at '$filePath'")
        return
    }

    val source = file.readText()

    try {
        val startTime = System.nanoTime()

        val parser = FunkParser()
        val ast = parser.parse(source, file = file.name)
        val parseTime = (System.nanoTime() - startTime) / 1_000_000.0

        val typeCheckProjectRoot = System.getProperty("funk.project.root")?.let {
            java.nio.file.Paths.get(it)
        } ?: file.toPath().parent ?: file.toPath()

        val typeCheckStart = System.nanoTime()
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(ast, typeCheckProjectRoot)
        val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000.0
        val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

        if (quietMode) {
            println("OK (${String.format("%.2f", totalTime)}ms)")
        } else {
            val typeAliases = typeChecker.getTypeAliases()

            printFileCheckSuccess(result, typeAliases)
            println()
            println("Parse: ${String.format("%.2f", parseTime)}ms  |  Type check: ${String.format("%.2f", typeCheckTime)}ms  |  Total: ${String.format("%.2f", totalTime)}ms")
        }
    } catch (e: TypeCheckError) {
        println(ErrorPrettyPrinter.prettyPrint(e, source, file.name))
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

private fun handlePackageCheck(quietMode: Boolean, appName: String? = null) {
    val currentDir = File(System.getProperty("user.dir"))
    val funkToml = File(currentDir, "Funk.toml")

    if (!funkToml.exists()) {
        println("Error: No Funk.toml found in current directory")
        println()
        println("Either:")
        println("  - Run this command in a Funk package directory")
        println("  - Or use: funk check <file> to check a specific file")
        return
    }

    // Use DevCommand logic to parse Funk.toml and check the app
    val devCommand = com.funk.language.cli.commands.DevCommand()

    try {
        // Read and parse Funk.toml
        val tomlContent = funkToml.readText()
        val manifest = com.akuleshov7.ktoml.Toml.decodeFromString(
            com.funk.language.cli.`package`.FunkToml.serializer(),
            tomlContent
        )

        if (manifest.app == null || manifest.app.isEmpty()) {
            println("Error: No apps defined in Funk.toml")
            println()
            println("Add an app to your Funk.toml or use: funk check <file>")
            return
        }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName } ?: run {
                println("Error: App '$appName' not found in Funk.toml")
                println()
                println("Available apps:")
                manifest.app.forEach { app -> println("  - ${app.name}") }
                return
            }
        } else {
            manifest.app.first()
        }

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            println("Error: App file not found: ${appFile.absolutePath}")
            return
        }

        val startTime = System.nanoTime()
        val source = appFile.readText()

        val parser = FunkParser()
        val ast = parser.parse(source, file = appFile.name)

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(ast, currentDir.toPath())

        val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

        if (quietMode) {
            println("OK (${String.format("%.2f", totalTime)}ms)")
        } else {
            println("Type check passed: ${appConfig.name}")
            println("Time: ${String.format("%.2f", totalTime)}ms")
        }
    } catch (e: TypeCheckError) {
        val appFile = File(currentDir, funkToml.readText().substringAfter("path = \"").substringBefore("\""))
        val source = if (appFile.exists()) appFile.readText() else ""
        println(ErrorPrettyPrinter.prettyPrint(e, source, appFile.name))
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

    // Parse the source
    val parser = FunkParser()
    val fileName = filePath?.fileName?.toString()
    val ast = parser.parse(source, file = fileName)

    // Get the project root for type checking (prioritize auto-detected)
    val typeCheckProjectRoot = System.getProperty("funk.project.root")?.let {
        java.nio.file.Paths.get(it)
    } ?: if (filePath != null) {
        filePath.parent ?: filePath
    } else {
        java.nio.file.Paths.get(".")
    }

    // Type-check with main validation: main must be {} -> {} <*>
    val typeChecker = TypeChecker()
    val typeCheckResult = typeChecker.checkProgramForExecution(ast, typeCheckProjectRoot)

    // Handle type-check failure
    if (typeCheckResult is TypeChecker.TypeCheckResult.Failure) {
        val error = typeCheckResult.errors.first()

        // Format error with source location and code snippet
        val formattedError = formatTypeCheckError(error, source, fileName)
        println(formattedError)

        // Provide helpful suggestions based on error
        if (error.message.contains("pure", ignoreCase = true)) {
            println()
            println("💡 The main function must perform effects using 'perform':")
            println()
            println("   use std::effects::log::{println, Log, Console}")
            println()
            println("   let main = {} -> perform Console in")
            println("     println { msg = \"Hello, World!\" }")
        } else if (error.message.contains("unhandled", ignoreCase = true)) {
            println()
            println("💡 Use 'perform <Handler> in' to handle all effects in main")
        } else if (error.message.contains("no 'main' function", ignoreCase = true)) {
            println()
            println("💡 Define a main function:")
            println()
            println("   use std::effects::log::{println, Log, Console}")
            println()
            println("   let main = {} -> perform Console in")
            println("     println { msg = \"Hello, World!\" }")
        }
        kotlin.system.exitProcess(1)
    }

    val successResult = typeCheckResult as TypeChecker.TypeCheckResult.Success

    // Add a call to main() at the end of the program
    val mainCall = com.funk.language.ast.FunctionCall(
        function = com.funk.language.ast.Identifier("main", null, null),
        arguments = com.funk.language.ast.RecordLiteral(emptyList(), null, null),
        inferredType = null,
        sourceLocation = null
    )
    val programToExecute = successResult.program.copy(
        items = successResult.program.items + listOf(com.funk.language.ast.TopLevelExpression(mainCall))
    )

    try {
        // Suppress Truffle warnings for native binary (interpreter-only mode is expected)
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        // Execute using the interpreter directly with the already type-checked AST
        val interpreter = com.funk.language.interpreter.FunkInterpreter(skipParserSetup = false)

        // Get the project root (prioritize auto-detected, then file path, then current dir)
        val projectRootPath = System.getProperty("funk.project.root")?.let {
            java.nio.file.Paths.get(it)
        } ?: if (filePath != null) {
            filePath.parent ?: filePath
        } else {
            java.nio.file.Paths.get(".")
        }

        val result = interpreter.execute(programToExecute, projectRootPath, successResult.monomorphicInstances)

        // Program should return {}, so we don't print the result
        // Execution completed silently

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
        val startTime = System.nanoTime()

        // Parse the source
        val parseStart = System.nanoTime()
        val parser = FunkParser()
        val ast = parser.parse(source)
        val parseTime = (System.nanoTime() - parseStart) / 1_000_000.0

        // Type-check with main validation: main must be {} -> {} <*>
        val typeCheckStart = System.nanoTime()
        val typeChecker = TypeChecker()
        val typeCheckResult = typeChecker.checkProgramForExecution(ast, inputFile.toPath().parent ?: inputFile.toPath())
        val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000.0

        // Handle type-check failure
        if (typeCheckResult is TypeChecker.TypeCheckResult.Failure) {
            val error = typeCheckResult.errors.first()

            // Format error with source location and code snippet
            val formattedError = formatTypeCheckError(error, source, inputFile.name)
            println(formattedError)

            // Provide helpful suggestions based on error
            if (error.message.contains("pure", ignoreCase = true)) {
                println()
                println("💡 The main function must perform effects using 'perform':")
                println()
                println("   use std::effects::log::{println, Log, Console}")
                println()
                println("   let main = {} -> perform Console in")
                println("     println { msg = \"Hello, World!\" }")
            } else if (error.message.contains("unhandled", ignoreCase = true)) {
                println()
                println("💡 Use 'perform <Handler> in' to handle all effects in main")
            } else if (error.message.contains("no 'main' function", ignoreCase = true)) {
                println()
                println("💡 Compiled programs must define a 'main' function:")
                println()
                println("   use std::effects::log::{println, Log, Console}")
                println()
                println("   let main = {} -> perform Console in")
                println("     println { msg = \"Hello, World!\" }")
            }
            kotlin.system.exitProcess(1)
        }

        val successResult = typeCheckResult as TypeChecker.TypeCheckResult.Success

        // Always create standalone JAR (AST + Interpreter + Truffle)
        val jarStart = System.nanoTime()
        createStandaloneJar(inputFile, outputFile, source, successResult.program)
        val jarTime = (System.nanoTime() - jarStart) / 1_000_000.0
        val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

        println()
        println("Created: ${outputFile.name} (${outputFile.length() / 1024}KB)")
        println("Parse: ${String.format("%.2f", parseTime)}ms  |  Type check: ${String.format("%.2f", typeCheckTime)}ms  |  JAR build: ${String.format("%.2f", jarTime)}ms  |  Total: ${String.format("%.2f", totalTime)}ms")
        println()
        println("Run: java -jar ${outputFile.name}")

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
        // 1. Create a Java main class that runs the embedded program
        createJavaMainClass(tempDir, inputFile, source)

        // 2. Build the JAR using javac + jar (only GraalVM/Java needed!)
        buildJarWithJavac(tempDir, outputFile, inputFile.nameWithoutExtension)

        // Clean up temp directory on success
        tempDir.deleteRecursively()
    } catch (e: Exception) {
        // Keep temp directory on failure for debugging
        println("⚠️  Temp directory preserved for debugging: ${tempDir.absolutePath}")
        throw e
    }
}

private fun createJavaMainClass(tempDir: File, inputFile: File, source: String) {
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

    // Get all modules that were type-checked (stdlib + imports + packages)
    // These will be bundled into the JAR to avoid parsing at runtime
    val loadedModules = ModuleResolver.getAllModules()
    val serializedModules = json.encodeToString(
        MapSerializer(String.serializer(), Program.serializer()),
        loadedModules
    )

    println("📦 Bundling ${loadedModules.size} pre-parsed modules (stdlib, imports, packages)")

    // Save serialized data as resource files
    val resourcesDir = File(tempDir, "src/main/resources/com/funk/standalone")
    resourcesDir.mkdirs()
    File(resourcesDir, "ast.json").writeText(serializedAST)
    File(resourcesDir, "modules.json").writeText(serializedModules)

    val mainClassDir = File(tempDir, "src/main/java/com/funk/standalone")
    mainClassDir.mkdirs()

    val mainClassContent = """
package com.funk.standalone;

import com.funk.language.cli.standalone.StandaloneHelper;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

/**
 * Standalone executable for ${inputFile.name}
 * Generated by Funk compiler with pre-processed enhanced AST
 *
 * This JAR contains the pre-parsed and type-checked AST, so no parser is needed at runtime.
 * Uses full Truffle execution with JIT compilation for maximum performance.
 */
public class Main {
    private static final String PROGRAM_NAME = "${inputFile.name}";

    public static void main(String[] args) {
        try {
            // Load serialized AST and modules from resources
            String astJson = loadResource("/com/funk/standalone/ast.json");
            String modulesJson = loadResource("/com/funk/standalone/modules.json");

            // Delegate to pre-compiled Kotlin helper (already in CLI JAR)
            StandaloneHelper.executeFromJson(astJson, modulesJson);

        } catch (Exception e) {
            System.err.println("Error executing " + PROGRAM_NAME + ": " + e.getMessage());
            if (args.length > 0 && args[0].equals("--debug")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static String loadResource(String path) throws Exception {
        InputStream is = Main.class.getResourceAsStream(path);
        if (is == null) {
            throw new IllegalStateException("Cannot find resource: " + path);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
    """.trimIndent()

    File(mainClassDir, "Main.java").writeText(mainClassContent)
    println("✅ Created Java wrapper (using pre-compiled helper)")
}

private fun copyFunkLanguageJars(tempDir: File) {
    val libsDir = File(tempDir, "libs")
    libsDir.mkdirs()

    // Find the CLI's lib directory by locating where this class is loaded from
    val cliLocation = object {}.javaClass.protectionDomain.codeSource.location
    val cliJarOrClassDir = File(cliLocation.toURI())

    // Navigate to the lib directory
    // For installed CLI: /path/to/funk/lib/cli-1.0.0.jar -> /path/to/funk/lib/
    // For development: /path/to/funk/cli/build/classes/kotlin/main/ -> /path/to/funk/cli/build/install/funk/lib/
    val cliLibDir = if (cliJarOrClassDir.isFile) {
        // Running from JAR - lib directory is parent
        cliJarOrClassDir.parentFile
    } else {
        // Running from classes directory (development) - need to find install/funk/lib
        var current = cliJarOrClassDir
        while (current != null && !File(current, "lib").exists()) {
            current = current.parentFile
        }
        File(current, "lib")
    }

    if (!cliLibDir.exists() || !cliLibDir.isDirectory) {
        throw IllegalStateException("Cannot find CLI lib directory at: ${cliLibDir.absolutePath}")
    }

    // Copy Funk component JARs from CLI's lib directory
    val jarsToCP = mapOf(
        "ast-1.0.0.jar" to "funk-ast.jar",
        "interpreter-1.0.0.jar" to "funk-interpreter.jar",
        "parser-1.0.0.jar" to "funk-parser.jar",
        "typechecker-1.0.0.jar" to "funk-typechecker.jar"
    )

    for ((sourceJarName, destJarName) in jarsToCP) {
        val sourceJar = File(cliLibDir, sourceJarName)
        if (sourceJar.exists()) {
            sourceJar.copyTo(File(libsDir, destJarName), overwrite = true)
            println("📦 Copied $sourceJarName")
        } else {
            throw IllegalStateException("Required JAR not found in CLI lib: ${sourceJar.absolutePath}")
        }
    }

    // Copy Truffle compiler JAR for JIT optimization
    val truffleCompilerJar = File(System.getenv("JAVA_HOME") ?: "/", "lib/truffle/truffle-compiler.jar")
    if (truffleCompilerJar.exists()) {
        truffleCompilerJar.copyTo(File(libsDir, "truffle-compiler.jar"), overwrite = true)
        println("📦 Copied Truffle JIT compiler")
    } else {
        println("⚠️  Truffle compiler JAR not found - JAR will run in interpreter mode")
    }
}

private fun copyStdlib(tempDir: File) {
    val stdlibDest = File(tempDir, "stdlib")
    stdlibDest.mkdirs()

    // Try to extract stdlib from embedded resources first (works anywhere)
    try {
        // Use the Main class's classLoader to ensure we get resources from the CLI JAR
        val classLoader = Thread.currentThread().contextClassLoader ?: object {}.javaClass.classLoader

        // We need to explicitly list them since we can't list resources in a JAR
        val knownStdlibPaths = listOf(
            "std/bool.funk", "std/result.funk", "std/frac.funk", "std/nat.funk", "std/option.funk",
            "std/async.funk", "std/async/thread.funk", "std/async/async.funk", "std/async/structured.funk",
            "std/math/core.funk", "std/math/random.funk", "std/math/trig.funk",
            "std/collections/list.funk", "std/collections/array.funk", "std/collections/map.funk",
            "std/collections/set.funk", "std/effects/log.funk", "std/effects/choice.funk",
            "std/effects/async.funk", "std/effects/concurrency.funk", "std/effects/early_return.funk",
            "std/effects/exception.funk", "std/effects/environment.funk", "std/effects/compile_time.funk",
            "std/effects/composite_logger.funk", "std/codec/json.funk"
        )

        var copiedCount = 0
        for (path in knownStdlibPaths) {
            val resourcePath = "stdlib/$path"
            val inputStream = classLoader.getResourceAsStream(resourcePath)
            if (inputStream != null) {
                val destFile = File(stdlibDest, path)
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                copiedCount++
            }
        }

        if (copiedCount > 0) {
            println("📦 Copied stdlib files from embedded resources ($copiedCount files)")
            return
        }

        // If we got 0 files, print debug info
        println("⚠️ Could not find stdlib resources in JAR, trying filesystem...")
    } catch (e: Exception) {
        // Fall back to filesystem if resource extraction fails
        println("⚠️ Exception while extracting stdlib: ${e.message}, trying filesystem...")
    }

    // Fallback: Try to copy from filesystem (for development)
    val projectRootPath = System.getProperty("funk.project.root")
        ?: detectProjectRoot()?.absolutePath

    if (projectRootPath != null) {
        val projectRoot = File(projectRootPath)
        val stdlibSource = File(projectRoot, "stdlib")
        if (stdlibSource.exists()) {
            stdlibSource.copyRecursively(stdlibDest, overwrite = true)
            println("📦 Copied stdlib files from filesystem")
            return
        }
    }

    throw IllegalStateException("Cannot find stdlib: not in embedded resources and not on filesystem")
}


/**
 * Build JAR using javac + jar (zero external dependencies!)
 * Only requires GraalVM/Java which is already needed to run the JAR
 */
private fun buildJarWithJavac(tempDir: File, outputFile: File, programName: String) {
    println("🔧 Building standalone JAR with zero external dependencies...")

    // Find CLI's lib directory to use as classpath
    val cliLocation = object {}.javaClass.protectionDomain.codeSource.location
    val cliJarOrClassDir = File(cliLocation.toURI())
    val cliLibDir = if (cliJarOrClassDir.isFile) {
        cliJarOrClassDir.parentFile
    } else {
        var current = cliJarOrClassDir
        while (current != null && !File(current, "lib").exists()) {
            current = current.parentFile
        }
        File(current, "lib")
    }

    // Build classpath from all JARs in CLI's lib directory
    val libJars = cliLibDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
    val funkJarsDir = File(tempDir, "libs")
    val funkJars = funkJarsDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()

    val classpath = (libJars + funkJars).joinToString(File.pathSeparator) { it.absolutePath }

    // 1. Compile Java source with javac (comes with GraalVM)
    println("📝 Compiling Java source...")
    val classesDir = File(tempDir, "classes")
    classesDir.mkdirs()

    val javacCmd = listOf(
        "javac",
        "-cp", classpath,
        "-d", classesDir.absolutePath,
        "--release", "24",
        File(tempDir, "src/main/java/com/funk/standalone/Main.java").absolutePath
    )

    val javacProcess = try {
        ProcessBuilder(javacCmd)
            .directory(tempDir)
            .redirectErrorStream(true)
            .start()
    } catch (e: java.io.IOException) {
        throw RuntimeException("""
            ❌ Cannot find 'javac' command!

            The Funk CLI needs javac (Java compiler) to build standalone JARs.

            Solutions:
            1. Install GraalVM or OpenJDK (which include javac)
            2. Ensure JAVA_HOME is set and includes javac
            3. Check that javac is in your PATH

            Current JAVA_HOME: ${System.getenv("JAVA_HOME") ?: "(not set)"}
            Current PATH: ${System.getenv("PATH")}

            For GraalVM installation: https://www.graalvm.org/downloads/
        """.trimIndent())
    }

    val javacOutput = javacProcess.inputStream.bufferedReader().readText()
    val javacExitCode = javacProcess.waitFor()

    if (javacExitCode != 0) {
        throw RuntimeException("Failed to compile Java source:\n$javacOutput")
    }

    println("✅ Compiled Java source")

    // 2. Create shadow JAR by extracting all dependencies
    println("📦 Creating shadow JAR...")
    val shadowDir = File(tempDir, "shadow")
    shadowDir.mkdirs()

    // Extract all dependency JARs to shadow directory
    println("📂 Extracting dependencies...")
    for (jar in libJars + funkJars) {
        val jarCmd = listOf(
            "jar", "xf", jar.absolutePath
        )
        val jarProcess = ProcessBuilder(jarCmd)
            .directory(shadowDir)
            .redirectErrorStream(true)
            .start()
        jarProcess.waitFor()
    }

    // Copy compiled classes
    println("📋 Copying compiled classes...")
    classesDir.copyRecursively(shadowDir, overwrite = true)

    // Copy AST/modules resources
    println("📋 Copying AST resources...")
    val resourcesSource = File(tempDir, "src/main/resources")
    if (resourcesSource.exists()) {
        resourcesSource.copyRecursively(shadowDir, overwrite = true)
    }

    // Copy stdlib resources
    println("📚 Copying stdlib resources...")
    val stdlibSource = File(tempDir, "stdlib")
    if (stdlibSource.exists()) {
        val stdlibDest = File(shadowDir, "stdlib")
        stdlibSource.copyRecursively(stdlibDest, overwrite = true)
    }

    // 3. Create MANIFEST.MF
    println("📄 Creating manifest...")
    val metaInfDir = File(shadowDir, "META-INF")
    metaInfDir.mkdirs()

    val manifestContent = """
Manifest-Version: 1.0
Main-Class: com.funk.standalone.Main
Add-Opens: java.base/jdk.internal.misc=ALL-UNNAMED java.base/sun.nio.ch=ALL-UNNAMED
Enable-Native-Access: ALL-UNNAMED

""".trimIndent()

    File(metaInfDir, "MANIFEST.MF").writeText(manifestContent)

    // 4. Package everything into final JAR
    println("🗜️  Packaging JAR...")
    val manifestFile = File(shadowDir, "META-INF/MANIFEST.MF")
    val jarCreateCmd = listOf(
        "jar", "cfm", outputFile.absolutePath,
        manifestFile.absolutePath,
        "-C", shadowDir.absolutePath, "."
    )

    val jarCreateProcess = try {
        ProcessBuilder(jarCreateCmd)
            .directory(tempDir)
            .redirectErrorStream(true)
            .start()
    } catch (e: java.io.IOException) {
        throw RuntimeException("""
            ❌ Cannot find 'jar' command!

            The Funk CLI needs the jar tool to package standalone JARs.

            Solutions:
            1. Install GraalVM or OpenJDK (which include jar tool)
            2. Ensure JAVA_HOME is set and includes jar
            3. Check that jar is in your PATH

            Current JAVA_HOME: ${System.getenv("JAVA_HOME") ?: "(not set)"}

            For GraalVM installation: https://www.graalvm.org/downloads/
        """.trimIndent())
    }

    val jarCreateOutput = jarCreateProcess.inputStream.bufferedReader().readText()
    val jarCreateExitCode = jarCreateProcess.waitFor()

    if (jarCreateExitCode != 0) {
        throw RuntimeException("Failed to create JAR:\n$jarCreateOutput")
    }

    println("✅ Built standalone JAR with Truffle runtime (zero external dependencies!)")
}

// =============================================================================
// Package-based command handlers
// =============================================================================

/**
 * Package-based command handlers
 */
private fun handleDevCommand(args: List<String>) {
    com.funk.language.cli.commands.DevCommand().execute(args)
}

private fun handleBuildCommand(args: List<String>) {
    com.funk.language.cli.commands.BuildCommand().execute(args) { inputFile, outputFile, source, program ->
        createStandaloneJar(inputFile, outputFile, source, program)
    }
}

/**
 * Package management command handlers
 */
private fun handleInitCommand(args: List<String>) {
    com.funk.language.cli.commands.InitCommand().execute(args)
}

private fun handleAddCommand(args: List<String>) {
    com.funk.language.cli.commands.AddCommand().executeAdd(args)
}

private fun handleRemoveCommand(args: List<String>) {
    com.funk.language.cli.commands.AddCommand().executeRemove(args)
}

private fun handleSyncCommand(args: List<String>) {
    com.funk.language.cli.commands.SyncCommand().execute(args)
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
            val expected = error.metadata["expected"] as? FunkType
            val actual = error.metadata["actual"] as? FunkType
            if (expected != null && actual != null) {
                println("   Expected: ${prettyPrintType(expected)}")
                println("   Actual:   ${prettyPrintType(actual)}")
            } else {
                println("   ${error.message}")
            }
        }
        ErrorType.UNDEFINED_VARIABLE -> {
            println("🔍 Undefined Variable")
            val variable = error.metadata["variable"] as? String
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
            val field = error.metadata["field"] as? String
            val type = error.metadata["type"] as? FunkType
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
            val missing = error.metadata["missing"] as? List<String>
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

private fun printFileCheckSuccess(result: TypeCheckResult, typeAliases: Map<String, TypeAliasInfo>) {
    println("Type check passed")
    println("━".repeat(50))

    // Only print main file bindings
    val mainBindings = result.program.items.filterIsInstance<LetBinding>()
    val mainTypes = result.program.items.filterIsInstance<TypeDeclaration>()
    val mainEffects = result.program.items.filterIsInstance<EffectDeclaration>()

    // Print types
    if (mainTypes.isNotEmpty()) {
        for (typeDecl in mainTypes) {
            val typeParamsStr = if (typeDecl.typeParams.isNotEmpty()) {
                " " + typeDecl.typeParams.joinToString(" ")
            } else {
                ""
            }
            println("type ${typeDecl.name}$typeParamsStr")
        }
        println()
    }

    // Print effects
    if (mainEffects.isNotEmpty()) {
        for (effectDecl in mainEffects) {
            println("effect ${effectDecl.name} (${effectDecl.operations.size} operations)")
        }
        println()
    }

    // Print bindings
    for (binding in mainBindings) {
        val scheme = binding.typeScheme
        val typeStr = if (scheme != null && scheme.type is com.funk.language.ast.FunkType.FunctionType) {
            prettyPrintTypeWithAliases(scheme.type, typeAliases, showExpansion = false, typeDeclarations = mainTypes)
        } else {
            binding.inferredType?.let {
                prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false, typeDeclarations = mainTypes)
            } ?: "unknown"
        }
        println("${binding.variableName} : $typeStr")
    }
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

/**
 * Format a type check error with source location and code snippet.
 * This provides better error messages that help users locate and fix issues.
 */
private fun formatTypeCheckError(
    error: TypeChecker.TypeCheckError,
    sourceCode: String?,
    fileName: String?
): String {
    return SourceContext.formatError(
        errorType = "Type Error",
        message = error.message,
        location = error.sourceLocation?.let { loc ->
            // Add filename to location if not already present
            if (loc.file == null && fileName != null) {
                com.funk.language.ast.SourceLocation(loc.line, loc.column, fileName)
            } else {
                loc
            }
        },
        sourceCode = sourceCode
    )
}