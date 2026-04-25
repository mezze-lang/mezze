package com.mezze.language.cli

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as PVisibility
import com.mezze.language.formatter.Formatter
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.typechecker.*
import com.mezze.language.typechecker.printing.ErrorFormatter
import org.graalvm.polyglot.PolyglotException
import com.mezze.language.codebase.SQLiteMezzeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Flag to track if pre-compiled stdlib has been loaded.
 */
@Volatile
private var stdlibLoaded = false

/**
 * Load pre-compiled stdlib from embedded resource (stdlib.db).
 * This skips parsing and type-checking of stdlib modules entirely.
 */
private fun loadPrecompiledStdlib() {
    if (stdlibLoaded) return

    // TODO: Rework in #107 — stdlib will be loaded from MezzeStore
    // For now, stdlib is loaded dynamically by the typechecker on demand
    stdlibLoaded = true
}

/**
 * Detect the project root by finding the directory containing the stdlib folder.
 * This allows the mezze CLI to find stdlib modules regardless of where it's run from.
 */
private fun detectProjectRoot(): File? {
    // Strategy 0: Read embedded project root from JAR resource (works when binary is relocated)
    try {
        val resourceStream = object {}.javaClass.classLoader.getResourceAsStream("mezze-project-root.txt")
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
    // For installed mezze CLI, this will be in cli/build/install/mezze/lib/...
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
    // The user will need to set mezze.project.root manually via JAVA_OPTS
    return null
}

fun main(args: Array<String>) {
    // Auto-detect and set mezze.project.root if not already set
    // This is mainly for development mode - stdlib is embedded in production native binary
    if (System.getProperty("mezze.project.root") == null) {
        val projectRoot = detectProjectRoot()
        if (projectRoot != null) {
            System.setProperty("mezze.project.root", projectRoot.absolutePath)
        }
    }

    // Load pre-compiled stdlib from embedded resource if available.
    // This must happen before any type-checking so preloaded modules
    // are in the cache for prelude injection and module resolution.
    loadPrecompiledStdlib()

    if (args.isEmpty()) {
        showUsage()
        kotlin.system.exitProcess(1)
    }

    val command = args[0]
    val remainingArgs = args.drop(1)

    // Handle global flags - only when they are the first argument (no command)
    if (command == "--help" || command == "-h") {
        showHelp()
        return
    }

    if (command == "--version" || command == "-v") {
        showVersion()
        return
    }

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
            // Developer tools
            "test" -> com.mezze.language.cli.commands.TestCommand().execute(remainingArgs)
            "lsp" -> handleLspCommand(remainingArgs)
            "fmt" -> handleFmtCommand(remainingArgs)
            // Internal commands (for build system)
            "--compile-stdlib" -> handleCompileStdlibCommand(remainingArgs)
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
    println("🚀 Mezze Language v1.0.0")
    println("A modern functional programming language with full Truffle support")
    println()
    showUsage()
    println()
    println("FILE-BASED COMMANDS (no Mezze.toml needed):")
    println("  parse     Parse Mezze code and show AST")
    println("  check     Type check Mezze code and show inferred types")
    println("  exec      Execute a .mz file directly")
    println("  compile   Compile a .mz file to standalone JAR")
    println()
    println("PACKAGE-BASED COMMANDS (requires Mezze.toml):")
    println("  dev       Run app from Mezze.toml (for development)")
    println("  build     Compile app from Mezze.toml to JAR (for production)")
    println()
    println("PACKAGE MANAGEMENT:")
    println("  init      Create a new Mezze package")
    println("  add       Add a dependency to Mezze.toml")
    println("  remove    Remove a dependency from Mezze.toml")
    println("  sync      Download and install all dependencies")
    println()
    println("DEVELOPER TOOLS:")
    println("  lsp       Start Language Server Protocol server")
    println("  fmt       Format Mezze source code")
    println()
    println("GLOBAL OPTIONS:")
    println("  -h, --help     Show this help message")
    println("  -v, --version  Show version information")
    println()
    println("EXAMPLES:")
    println("  mezze init --app                      # Create an app package")
    println("  mezze dev                             # Run app from Mezze.toml")
    println("  mezze build                           # Build app to JAR")
    println("  mezze exec program.mz               # Execute a file")
    println("  mezze compile program.mz            # Compile file to JAR")
    println()
    println("For command-specific help: mezze <command> --help")
}

/**
 * Show version information
 */
private fun showVersion() {
    println("🚀 Mezze Language v1.0.0")
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
    println("  mezze <command> [options] [file]")
    println()
    println("COMMANDS:")
    println("  File-based:    parse, check, exec, compile")
    println("  Package-based: dev, build")
    println("  Packages:      init, add, remove, sync")
    println("  Tools:         lsp, fmt")
    println()
    println("For command-specific help: mezze <command> --help")
}

/**
 * Show command-specific help
 */
private fun showCommandHelp(command: String) {
    when (command) {
        "parse" -> {
            println("USAGE: mezze parse [file]")
            println()
            println("Parse Mezze source code and display the Abstract Syntax Tree")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze parse program.mz       # Parse a file")
            println("  echo 'let x = 5' | mezze parse # Parse from stdin")
        }
        
        "check" -> {
            println("USAGE: mezze check [options] [file]")
            println()
            println("Type check Mezze source code")
            println()
            println("MODES:")
            println("  mezze check <file>          Check file + all imports, show types")
            println("  mezze check                 Check whole app from Mezze.toml")
            println("  mezze check --app <name>    Check specific app from Mezze.toml")
            println()
            println("OPTIONS:")
            println("  -q, --quiet        Only show errors (no type info)")
            println("  --app <name>       Check specific app from Mezze.toml")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze check src/app.mz       # Check file + imports")
            println("  mezze check --quiet app.mz   # Quiet mode")
            println("  mezze check                    # Check package app")
            println("  mezze check --app server       # Check specific app")
        }
        
        "exec" -> {
            println("USAGE: mezze exec [file]")
            println()
            println("Execute Mezze source code directly without compilation")
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
            println("USAGE: mezze compile <file> [output]")
            println()
            println("Compile Mezze source code to a standalone JAR")
            println()
            println("ARGUMENTS:")
            println("  <file>             Input Mezze source file")
            println("  [output]           Output JAR name (default: same as input with .jar)")
            println()
            println("OPTIONS:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze compile program.mz                # Creates program.jar")
            println("  mezze compile program.mz myapp.jar      # Creates myapp.jar")
            println()
            println("OUTPUT:")
            println("  Pre-compiled AST + Interpreter + Truffle runtime")
            println("  Smaller size, faster startup than full JIT builds")
            println()
        }

        "init" -> {
            println("USAGE: mezze init [options]")
            println()
            println("Create a new Mezze package in the current directory")
            println()
            println("OPTIONS:")
            println("  --lib              Create a library package")
            println("  --app              Create an app package")
            println("  --lib --app        Create both library and app")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze init --lib                 # Create library")
            println("  mezze init --app                 # Create app")
            println("  mezze init --lib --app           # Create both")
            println("  mezze init                       # Interactive mode")
            println()
        }

        "add" -> {
            println("USAGE: mezze add [options] <url>")
            println()
            println("Add a dependency to Mezze.toml")
            println()
            println("ARGUMENTS:")
            println("  <url>              Full URL with SHA-256 checksum")
            println()
            println("OPTIONS:")
            println("  --dev              Add to [dev-dependencies]")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze add \"https://github.com/user/pkg/releases/download/v1.0.0/pkg.tar.gz#sha256:abc123...\"")
            println("  mezze add --dev \"https://github.com/user/test/releases/download/v0.1.0/test.tar.gz#sha256:def456...\"")
            println()
            println("💡 After adding dependencies, run 'mezze sync' to download them")
            println()
        }

        "remove" -> {
            println("USAGE: mezze remove <package_name>")
            println()
            println("Remove a dependency from Mezze.toml")
            println()
            println("ARGUMENTS:")
            println("  <package_name>     Name of the package to remove")
            println()
            println("EXAMPLES:")
            println("  mezze remove json")
            println("  mezze remove test_framework")
            println()
        }

        "sync" -> {
            println("USAGE: mezze sync [options]")
            println()
            println("Download and install all dependencies from Mezze.toml")
            println()
            println("OPTIONS:")
            println("  --clean            Delete and re-download all packages")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze sync                       # Download missing packages")
            println("  mezze sync --clean               # Re-download everything")
            println()
            println("📦 Packages are installed to: ./mezze_packages/")
            println("🔒 Lock file is generated: ./Mezze.lock")
            println()
        }

        "dev" -> {
            println("USAGE: mezze dev [options]")
            println()
            println("Execute app from Mezze.toml (for development)")
            println()
            println("Reads [[app]] entries from Mezze.toml and executes the specified app.")
            println("Auto-calls the main function. For quick iteration during development.")
            println()
            println("OPTIONS:")
            println("  --app <name>       Run a specific app by name")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze dev                        # Run default app (first [[app]])")
            println("  mezze dev --app server           # Run specific app")
            println()
            println("💡 For file-based execution, use: mezze exec <file>")
            println()
        }

        "build" -> {
            println("USAGE: mezze build [options]")
            println()
            println("Compile app from Mezze.toml to standalone JAR (for production)")
            println()
            println("Reads [[app]] entries from Mezze.toml and compiles to a JAR.")
            println("Includes dependencies and Truffle runtime for deployment.")
            println()
            println("OPTIONS:")
            println("  --app <name>       Build a specific app by name")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze build                      # Build default app")
            println("  mezze build --app server         # Build specific app")
            println()
            println("OUTPUT:")
            println("  Single app:    <package-name>.jar")
            println("  Multiple apps: <app-name>.jar")
            println()
            println("💡 For file-based compilation, use: mezze compile <file>")
            println()
        }

        "lsp" -> {
            println("USAGE: mezze lsp [options]")
            println()
            println("Start the Mezze Language Server Protocol server")
            println()
            println("The server communicates via stdin/stdout using the Language Server Protocol.")
            println("This is primarily used by editor extensions (VS Code, Neovim, etc.).")
            println()
            println("OPTIONS:")
            println("  --stdio            Use stdio for communication (default)")
            println("  -v, --version      Print version and exit")
            println("  -h, --help         Show this help message")
            println()
            println("FEATURES:")
            println("  - Diagnostics (parse errors, type errors)")
            println("  - Hover (show types on hover)")
            println("  - Go to definition")
            println("  - Find references")
            println("  - Code completion")
            println("  - Document symbols (outline)")
            println()
            println("EDITOR INTEGRATION:")
            println("  VS Code:  Install the Mezze extension")
            println("  Neovim:   Configure LSP client to run 'mezze lsp'")
            println()
        }

        "fmt" -> {
            println("USAGE: mezze fmt [options] [files...]")
            println()
            println("Format Mezze source code")
            println()
            println("Formats .mz files in a consistent style. Without arguments, formats")
            println("all .mz files in the current directory recursively.")
            println()
            println("OPTIONS:")
            println("  --check            Check formatting without modifying files")
            println("                     Exits with code 1 if files need formatting")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze fmt                        # Format all .mz files in current dir")
            println("  mezze fmt src/                   # Format all .mz files in src/")
            println("  mezze fmt app.mz lib.mz          # Format specific files")
            println("  mezze fmt --check                # Check if files are formatted")
            println("  mezze fmt --check src/app.mz     # Check specific file")
            println()
            println("STYLE:")
            println("  - Tabs for indentation (2-space width for line calculation)")
            println("  - 100 character max line length")
            println("  - Spaces around binary operators (x + y)")
            println("  - No spaces in empty records/arrays ({}, [])")
            println("  - Trailing newline at end of file")
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
        val parser = MezzeParser()
        val module = parser.parseParsedModule(source, file = fileName ?: "<stdin>")
        val parseTime = (System.nanoTime() - startTime) / 1_000_000.0

        println(prettyPrintParsedModule(module))
        println()
        println("Parsed in ${String.format("%.2f", parseTime)}ms")
    } catch (e: com.mezze.language.parser.ParseException) {
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
    com.mezze.language.cli.commands.CheckCommand().execute(args)
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



private fun prettyPrintParsedModule(module: ParsedModule): String {
    val sb = StringBuilder()
    sb.appendLine("Module: ${module.path}")
    sb.appendLine("${module.decls.size} declarations:")
    module.decls.forEachIndexed { index, decl ->
        sb.appendLine("  ${index + 1}. ${prettyPrintParsedDecl(decl)}")
    }
    return sb.toString()
}

private fun prettyPrintParsedDecl(decl: ParsedDecl): String {
    val vis = if (decl.visibility == PVisibility.PUBLIC) "pub " else ""
    return when (decl) {
        is ParsedLetDecl -> {
            val name = if (decl.isSimpleBinding) decl.variableName else "<pattern>"
            val typeAnn = decl.typeAnnotation?.let { ": ${prettyPrintParsedType(it)}" } ?: ""
            "${vis}let $name$typeAnn = ${prettyPrintParsedExpr(decl.value)}"
        }
        is ParsedTypeDecl -> "${vis}type ${decl.name} = ${prettyPrintParsedTypeDef(decl.definition)}"
        is ParsedAbilityDecl -> "${vis}ability ${decl.name} (${decl.operations.size} ops)"
        is ParsedImplAbilityDecl -> {
            val target = when (val t = decl.target) {
                is ParsedImplTarget.Named -> "impl ${t.ability.localName}"
                is ParsedImplTarget.Inherent -> "impl (inherent)"
            }
            "$target (${decl.operations.size} ops)"
        }
        is ParsedEffectDecl -> "${vis}effect ${decl.name} (${decl.operations.size} ops)"
        is ParsedPerformerDecl -> "impl ${decl.effect.localName} for ${decl.performerName} (${decl.operations.size} ops)"
        is ParsedUseDecl -> "use ${prettyPrintUsePath(decl.path)}"
        is ParsedPubUseDecl -> "pub use ${prettyPrintUsePath(decl.path)}"
        is ParsedTopLevelExpr -> prettyPrintParsedExpr(decl.expression)
    }
}

private fun prettyPrintParsedExpr(expr: ParsedExpr): String {
    return when (expr) {
        is ParsedIntLit -> expr.value.toString()
        is ParsedNatLit -> "${expr.value}n"
        is ParsedFloatLit -> expr.value.toString()
        is ParsedFracLit -> "${expr.numerator}//${expr.denominator}"
        is ParsedStringLit -> {
            val content = expr.parts.joinToString("") { part ->
                when (part) {
                    is ParsedStringPart.Literal -> part.value
                    is ParsedStringPart.Interpolation -> "\$(${prettyPrintParsedExpr(part.expr)})"
                }
            }
            "\"$content\""
        }
        is ParsedCharLit -> "'${expr.value}'"
        is ParsedIdentifier -> {
            if (expr.qualifier != null) "${expr.qualifier}::${expr.localName}"
            else expr.localName
        }
        is ParsedLambda -> {
            val params = when (val p = expr.params) {
                is ParsedLambdaParams.Typed -> "{ ${p.fields.joinToString(", ") { "${it.name}: ${prettyPrintParsedType(it.type)}" }} }"
                is ParsedLambdaParams.Untyped -> "{ ${p.names.joinToString(", ")} }"
            }
            "$params -> ${prettyPrintParsedExpr(expr.body)}"
        }
        is ParsedFunctionCall -> "${prettyPrintParsedExpr(expr.function)} ${prettyPrintParsedExpr(expr.arguments)}"
        is ParsedRecordLit -> {
            val entries = expr.entries.joinToString(", ") { entry ->
                when (entry) {
                    is ParsedRecordEntry.Field -> {
                        if (entry.value != null) "${entry.name} = ${prettyPrintParsedExpr(entry.value!!)}"
                        else entry.name
                    }
                    is ParsedRecordEntry.Spread -> "...${prettyPrintParsedExpr(entry.expr)}"
                }
            }
            "{ $entries }"
        }
        is ParsedMemberAccess -> "${prettyPrintParsedExpr(expr.receiver)}.${expr.member}"
        is ParsedVariantConstruct -> {
            if (expr.value != null) "${expr.constructor} ${prettyPrintParsedExpr(expr.value!!)}"
            else expr.constructor
        }
        is ParsedIf -> "if ${prettyPrintParsedExpr(expr.condition)} then ${prettyPrintParsedExpr(expr.thenBranch)} else ${prettyPrintParsedExpr(expr.elseBranch)}"
        is ParsedMatch -> "match ${prettyPrintParsedExpr(expr.scrutinee)} is ..."
        is ParsedDo -> "do ... ${prettyPrintParsedExpr(expr.result)}"
        is ParsedSeqLit -> "[${expr.elements.size} elements]"
        is ParsedPerform -> "perform ... in ${prettyPrintParsedExpr(expr.body)}"
        is ParsedEffectCall -> "${expr.operation} ${prettyPrintParsedExpr(expr.arguments)}"
        is ParsedPipe -> "${prettyPrintParsedExpr(expr.left)} |> ${prettyPrintParsedExpr(expr.right)}"
        is ParsedPipePlaceholder -> "@"
        is ParsedParens -> "(${prettyPrintParsedExpr(expr.expr)})"
        is ParsedBinaryOp -> "${prettyPrintParsedExpr(expr.left)} ${expr.op} ${prettyPrintParsedExpr(expr.right)}"
        is ParsedUnaryOp -> "${expr.op}${prettyPrintParsedExpr(expr.operand)}"
    }
}

private fun prettyPrintParsedType(type: ParsedTypeAnnotation): String {
    return when (type) {
        is ParsedNamedType -> type.name
        is ParsedQualifiedType -> "${type.modulePath.joinToString("::")}::${type.typeName}"
        is ParsedTypeApp -> "${type.constructor} ${type.args.joinToString(" ") { prettyPrintParsedType(it) }}"
        is ParsedRecordType -> "{ ${type.entries.joinToString(", ") { "${it.name}: ${prettyPrintParsedType(it.type)}" }} }"
        is ParsedVariantType -> "| ${type.constructors.joinToString(", ") { it.name }} |"
        is ParsedFunctionType -> "${prettyPrintParsedType(type.paramType)} -> ${prettyPrintParsedType(type.returnType)}"
        is ParsedSeqType -> "[${prettyPrintParsedType(type.elementType)}]"
    }
}

private fun prettyPrintParsedTypeDef(def: ParsedTypeDef): String {
    return when (def) {
        is ParsedRecordDef -> "{ ${def.fields.joinToString(", ") { "${it.name}: ${prettyPrintParsedType(it.type)}" }} }"
        is ParsedVariantDef -> "| ${def.constructors.joinToString(", ") { it.name }} |"
        is ParsedNewtypeDef -> "${def.constructor} ${prettyPrintParsedType(def.wrappedType)}"
    }
}

private fun prettyPrintUsePath(path: ParsedUsePath): String {
    return when (path) {
        is ParsedUsePath.Module -> path.path.toString()
        is ParsedUsePath.Selective -> "${path.modulePath}::{${path.items.joinToString(", ") { it.alias?.let { a -> "${it.name} as $a" } ?: it.name }}}"
    }
}

private fun printTypedModuleSuccess(module: com.mezze.language.typedast.TypedModule) {
    println("Type check passed")
    println("━".repeat(50))

    // Print types
    val typeDecls = module.decls.filterIsInstance<com.mezze.language.typedast.TypedTypeDecl>()
    if (typeDecls.isNotEmpty()) {
        for (td in typeDecls) {
            println("type ${td.name}")
        }
        println()
    }

    // Print effects
    if (module.effectDecls.isNotEmpty()) {
        for (ed in module.effectDecls) {
            println("effect ${ed.name} (${ed.operations.size} operations)")
        }
        println()
    }

    // Print abilities
    if (module.abilityDecls.isNotEmpty()) {
        for (ad in module.abilityDecls) {
            println("ability ${ad.name} (${ad.operations.size} operations)")
        }
        println()
    }

    // Print exports
    if (module.exports.isNotEmpty()) {
        println("Exports:")
        for ((name, entry) in module.exports) {
            val kindStr = entry.kind.name.lowercase()
            val typeStr = prettyPrintType(entry.type)
            println("  $name : $typeStr  [$kindStr]")
        }
    }

    // Print let bindings with types
    val letDecls = module.decls.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
    if (letDecls.isNotEmpty()) {
        println()
        for (decl in letDecls) {
            val vis = if (decl.isPublic) "pub " else ""
            val typeStr = prettyPrintType(decl.scheme.type)
            val hashStr = decl.hash.hex.take(8)
            println("${vis}${decl.name} : $typeStr  [#$hashStr]")
        }
    }
}

private fun handleExecCommand(args: List<String>) {
    val showTiming = System.getenv("MEZZE_TIMING") != null
    val totalStart = System.nanoTime()

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
    val parseStart = System.nanoTime()
    val parser = MezzeParser()
    val fileName = filePath?.fileName?.toString()
    val ast = parser.parseParsedModule(source, file = fileName ?: "<unknown>")
    val parseTime = (System.nanoTime() - parseStart) / 1_000_000

    // Use the source file's directory for module resolution
    // This allows the ModuleResolver to find Mezze.toml in parent directories
    // Note: mezze.project.root is for stdlib location, NOT for user module resolution
    val typeCheckProjectRoot = if (filePath != null) {
        filePath.toAbsolutePath().parent ?: filePath.toAbsolutePath()
    } else {
        java.nio.file.Paths.get(".").toAbsolutePath()
    }

    // Type-check with main validation: main must be {} -> {} <*>
    val typeCheckStart = System.nanoTime()
    val typeChecker = TypeChecker()
    val typeCheckResult = try {
        typeChecker.typeCheck(ast, typeCheckProjectRoot, enablePrelude = true)
    } catch (e: TypeCheckError) {
        // Format error with source location and code snippet
        val formattedError = SourceContext.formatError(
            errorType = "Type Error",
            message = e.message ?: "Unknown error",
            location = e.loc?.let { loc ->
                if (loc.file.isEmpty() && fileName != null) {
                    com.mezze.language.parsedast.Loc(fileName, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
                } else {
                    loc
                }
            },
            sourceCode = source
        )
        println(formattedError)
        kotlin.system.exitProcess(1)
    } catch (e: com.mezze.language.typechecker.module.ModuleError.PrivateExport) {
        // Pretty-print private export error with source context
        println("❌ Module Error [Private Item]")
        println("━".repeat(50))
        e.loc?.let { loc ->
            println("   --> ${loc.file.ifEmpty { fileName ?: "<input>" }}:${loc.startLine}:${loc.startCol}")
            val lines = source.lines()
            if (loc.startLine > 0 && loc.startLine <= lines.size) {
                val lineNum = loc.startLine
                val lineContent = lines[lineNum - 1]
                println("    |")
                println("${lineNum.toString().padStart(3)} | $lineContent")
                val underlineLen = if (loc.startLine == loc.endLine && loc.endCol > loc.startCol) loc.endCol - loc.startCol else 1
                println("    | ${" ".repeat(maxOf(0, loc.startCol - 1))}${"^".repeat(maxOf(1, underlineLen))}")
            }
            println()
        }
        println("Error: '${e.exportName}' exists in '${e.modulePath}' but is not public")
        println()
        println("💡 To fix this, add 'pub' to the definition in ${e.modulePath}:")
        println()
        println("   pub let ${e.exportName} = ...")
        println()
        kotlin.system.exitProcess(1)
    } catch (e: com.mezze.language.typechecker.module.ModuleError.ExportNotFound) {
        // Pretty-print export not found error with source context
        println("❌ Module Error [Export Not Found]")
        println("━".repeat(50))
        e.loc?.let { loc ->
            println("   --> ${loc.file.ifEmpty { fileName ?: "<input>" }}:${loc.startLine}:${loc.startCol}")
            // Show source code snippet
            val lines = source.lines()
            if (loc.startLine > 0 && loc.startLine <= lines.size) {
                val lineNum = loc.startLine
                val lineContent = lines[lineNum - 1]
                println("    |")
                println("${lineNum.toString().padStart(3)} | $lineContent")
                val underlineLen = if (loc.startLine == loc.endLine && loc.endCol > loc.startCol) loc.endCol - loc.startCol else 1
                println("    | ${" ".repeat(maxOf(0, loc.startCol - 1))}${"^".repeat(maxOf(1, underlineLen))}")
            }
            println()
        }
        println("Error: Export '${e.exportName}' not found in module '${e.modulePath}'")
        println()

        // Find similar exports using Levenshtein distance
        val similarExports = e.availableExports
            .map { it to levenshteinDistance(e.exportName.lowercase(), it.lowercase()) }
            .filter { it.second <= 3 }  // Max distance of 3
            .sortedBy { it.second }
            .take(5)
            .map { it.first }

        if (similarExports.isNotEmpty()) {
            println("💡 Did you mean:")
            similarExports.forEach { println("   • $it") }
            println()
        }

        if (e.availableExports.isNotEmpty()) {
            println("📦 Available exports from '${e.modulePath}':")
            val displayExports = if (e.availableExports.size > 10) {
                e.availableExports.take(10) + listOf("... and ${e.availableExports.size - 10} more")
            } else {
                e.availableExports
            }
            displayExports.forEach { println("   • $it") }
        } else {
            println("💡 Suggestions:")
            println("   - Check the export name spelling")
            println("   - Verify the module exports this item with 'pub'")
        }
        kotlin.system.exitProcess(1)
    } catch (e: com.mezze.language.typechecker.module.ModuleError) {
        // Handle other module errors with source location if available
        println("❌ Module Error")
        println("━".repeat(50))
        println("Error: ${e.message ?: "Unknown module error"}")
        kotlin.system.exitProcess(1)
    }

    val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000

    // Use TruffleIR (loweredFunctions) — all modules are already included via monomorphization.
    // The compileFromIR path finds "main" or "__entry__" and executes its body directly,
    // so no need to append a main() call.
    val functions = typeCheckResult.loweredFunctions

    try {
        // Suppress verbose Truffle logging (JIT compilation is enabled via truffle-runtime)
        System.setProperty("polyglot.log.level", "OFF")

        // Use the source file's directory for module resolution
        val projectRootPath = if (filePath != null) {
            filePath.toAbsolutePath().parent ?: filePath.toAbsolutePath()
        } else {
            java.nio.file.Paths.get(".").toAbsolutePath()
        }

        // Execute using V2 interpreter with TruffleIR
        val execStart = System.nanoTime()
        executeWithV2Interpreter(functions, projectRootPath)
        val execTime = (System.nanoTime() - execStart) / 1_000_000
        val totalTime = (System.nanoTime() - totalStart) / 1_000_000

        // Print timing if enabled
        if (showTiming) {
            System.err.println("\n⏱️  Timing breakdown:")
            System.err.println("   Parse:      ${parseTime}ms")
            System.err.println("   TypeCheck:  ${typeCheckTime}ms")
            System.err.println("   Execute:    ${execTime}ms")
            System.err.println("   Total:      ${totalTime}ms")
        }

        // Program should return {}, so we don't print the result
        // Execution completed silently

    } catch (e: TypeCheckError) {
        // Pass source code and file for enhanced error messages with code snippets
        println(ErrorFormatter.format(e, source))
        kotlin.system.exitProcess(1)
    } catch (e: com.mezze.language.parser.ParseException) {
        println("❌ Parse Error")
        println("━".repeat(50))
        println(e.message)
        kotlin.system.exitProcess(1)
    } catch (e: PolyglotException) {
        println("❌ Runtime Error:")
        println(e.message ?: "Unknown runtime error")
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

/**
 * Execute pre-lowered TruffleIR using the V2 interpreter (optimized Truffle implementation).
 * V2 uses shape-cached record access, typed frame slots, and jump table dispatch.
 * All modules are already included in the FunctionIR list via monomorphization.
 */
private fun executeWithV2Interpreter(
    functions: List<com.mezze.language.truffleir.FunctionIR>,
    projectRoot: java.nio.file.Path
) {
    // Set up V2 context
    val context = org.graalvm.polyglot.Context.newBuilder(com.mezze.interpreter.v2.runtime.MezzeLanguage.ID)
        .allowAllAccess(true)
        .allowCreateThread(true)
        .build()

    try {
        // Store TruffleIR functions for direct execution (bypasses parsing)
        com.mezze.interpreter.v2.runtime.MezzeLanguage.setFunctionsForDirectExecution(functions, projectRoot)

        // Create a dummy source to trigger MezzeLanguage.parse()
        val source = org.graalvm.polyglot.Source.newBuilder(
            com.mezze.interpreter.v2.runtime.MezzeLanguage.ID,
            "# Direct TruffleIR execution",
            "__direct_execution__.mz"
        ).buildLiteral()

        // Execute via polyglot API
        context.eval(source)

        // Result is stored in MezzeLanguage.getLastExecutionResult() if needed
    } finally {
        // Use cancelIfExecuting=true for Go/Rust-style semantics:
        // When main exits, any fire-and-forget tasks (unawaited spawns) are cancelled.
        // This matches Go (goroutines die when main exits) and Rust (tokio tasks cancelled).
        // See: https://www.graalvm.org/truffle/javadoc/org/graalvm/polyglot/Context.html
        context.close(true)
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
        println("USAGE: mezze compile <file> [output]")
        println("Use 'mezze compile --help' for more information")
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
        // Default: replace .mz extension with .jar, or add .jar
        val inputName = inputFile.nameWithoutExtension
        File(inputFile.parent ?: ".", "$inputName.jar")
    }

    // Read source code before try block so it's available in catch for error messages
    val source = inputFile.readText()

    try {
        val startTime = System.nanoTime()

        // Parse the source
        val parseStart = System.nanoTime()
        val parser = MezzeParser()
        val ast = parser.parseParsedModule(source)
        val parseTime = (System.nanoTime() - parseStart) / 1_000_000.0

        // Type-check (throws TypeCheckError on failure)
        val typeCheckStart = System.nanoTime()
        val typeChecker = TypeChecker()
        val typeCheckResult = typeChecker.typeCheck(ast, inputFile.toPath().parent ?: inputFile.toPath(), enablePrelude = true)
        val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000.0

        // Always create standalone JAR (AST + Interpreter + Truffle)
        val jarStart = System.nanoTime()
        createStandaloneJar(inputFile, outputFile, source)
        val jarTime = (System.nanoTime() - jarStart) / 1_000_000.0
        val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

        println()
        println("Created: ${outputFile.name} (${outputFile.length() / 1024}KB)")
        println("Parse: ${String.format("%.2f", parseTime)}ms  |  Type check: ${String.format("%.2f", typeCheckTime)}ms  |  JAR build: ${String.format("%.2f", jarTime)}ms  |  Total: ${String.format("%.2f", totalTime)}ms")
        println()
        println("Run: java -jar ${outputFile.name}")

    } catch (e: TypeCheckError) {
        // Pass source code and file for enhanced error messages with code snippets
        println(ErrorFormatter.format(e, source))
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Compilation Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}

private fun createStandaloneJar(inputFile: File, outputFile: File, source: String) {
        println("🔧 Creating standalone JAR with full Truffle JIT support...")

    // Create a temporary directory for JAR contents
    val tempDir = kotlin.io.path.createTempDirectory("mezze-compile").toFile()
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
    val parser = MezzeParser()
    val typeChecker = TypeChecker()

    val parsedModule = parser.parseParsedModule(source, inputFile.name)
    val projectRoot = inputFile.toPath().parent ?: inputFile.toPath()
    val typeCheckResult = typeChecker.typeCheck(parsedModule, projectRoot)

    // Get all modules that were type-checked (stdlib + imports + packages)
    val loadedModules = ModuleResolver.getAllModules()

    println("📦 Bundling ${loadedModules.size} pre-parsed modules (stdlib, imports, packages)")

    // TODO: Rework in #103 — create blobs.sqlite for JAR embedding via MezzeStore
    val codebaseBytes = ByteArray(0)
    println("TODO: JAR codebase embedding via MezzeStore (#103)")

    // Save codebase as resource file
    val resourcesDir = File(tempDir, "src/main/resources/com/mezze/standalone")
    resourcesDir.mkdirs()
    File(resourcesDir, "codebase.db").writeBytes(codebaseBytes)

    val mainClassDir = File(tempDir, "src/main/java/com/mezze/standalone")
    mainClassDir.mkdirs()

    val mainClassContent = """
package com.mezze.standalone;

import com.mezze.language.cli.standalone.StandaloneHelper;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/**
 * Standalone executable for ${inputFile.name}
 * Generated by Mezze compiler with pre-processed enhanced AST
 *
 * This JAR contains a SQLite codebase with all definitions and types.
 * Uses full Truffle execution with JIT compilation for maximum performance.
 */
public class Main {
    private static final String PROGRAM_NAME = "${inputFile.name}";

    public static void main(String[] args) {
        try {
            // Load codebase from embedded resource
            byte[] codebaseBytes = loadResourceBytes("/com/mezze/standalone/codebase.db");

            // Execute using the codebase
            StandaloneHelper.executeFromCodebase(codebaseBytes);

        } catch (Exception e) {
            System.err.println("Error executing " + PROGRAM_NAME + ": " + e.getMessage());
            if (args.length > 0 && args[0].equals("--debug")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static byte[] loadResourceBytes(String path) throws Exception {
        InputStream is = Main.class.getResourceAsStream(path);
        if (is == null) {
            throw new IllegalStateException("Cannot find resource: " + path);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[16384];
        int bytesRead;
        while ((bytesRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        is.close();
        return buffer.toByteArray();
    }
}
    """.trimIndent()

    File(mainClassDir, "Main.java").writeText(mainClassContent)
    println("✅ Created Java wrapper (using SQLite codebase)")
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
        var current: File? = cliJarOrClassDir
        while (current != null && !File(current, "lib").exists()) {
            current = current.parentFile
        }
        File(current ?: cliJarOrClassDir, "lib")
    }

    // Build classpath from all JARs in CLI's lib directory
    val libJars = cliLibDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
    val mezzeJarsDir = File(tempDir, "libs")
    val mezzeJars = mezzeJarsDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()

    val classpath = (libJars + mezzeJars).joinToString(File.pathSeparator) { it.absolutePath }

    // 1. Compile Java source with javac (comes with GraalVM)
    println("📝 Compiling Java source...")
    val classesDir = File(tempDir, "classes")
    classesDir.mkdirs()

    val javacCmd = listOf(
        "javac",
        "-cp", classpath,
        "-d", classesDir.absolutePath,
        "--release", "24",
        File(tempDir, "src/main/java/com/mezze/standalone/Main.java").absolutePath
    )

    val javacProcess = try {
        ProcessBuilder(javacCmd)
            .directory(tempDir)
            .redirectErrorStream(true)
            .start()
    } catch (e: java.io.IOException) {
        throw RuntimeException("""
            ❌ Cannot find 'javac' command!

            The Mezze CLI needs javac (Java compiler) to build standalone JARs.

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
    for (jar in libJars + mezzeJars) {
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
Main-Class: com.mezze.standalone.Main
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

            The Mezze CLI needs the jar tool to package standalone JARs.

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

private fun handleDevCommand(args: List<String>) {
    com.mezze.language.cli.commands.DevCommand().execute(args)
}

private fun handleBuildCommand(args: List<String>) {
    com.mezze.language.cli.commands.BuildCommand().execute(args) { inputFile, outputFile, source, _ ->
        createStandaloneJar(inputFile, outputFile, source)
    }
}

/**
 * Package management command handlers
 */
private fun handleInitCommand(args: List<String>) {
    com.mezze.language.cli.commands.InitCommand().execute(args)
}

private fun handleAddCommand(args: List<String>) {
    com.mezze.language.cli.commands.AddCommand().executeAdd(args)
}

private fun handleRemoveCommand(args: List<String>) {
    com.mezze.language.cli.commands.AddCommand().executeRemove(args)
}

private fun handleSyncCommand(args: List<String>) {
    com.mezze.language.cli.commands.SyncCommand().execute(args)
}

/**
 * Start the Language Server Protocol server
 */
private fun handleLspCommand(args: List<String>) {
    // Handle version flag
    if (args.contains("--version") || args.contains("-v")) {
        println("Mezze Language Server v1.0.0")
        return
    }

    // Start the LSP server (communicates via stdin/stdout)
    com.mezze.language.lsp.startServer(System.`in`, System.out)
}

/**
 * Format Mezze source code
 */
private fun handleFmtCommand(args: List<String>) {
    val checkOnly = args.contains("--check")
    val fileArgs = args.filter { !it.startsWith("-") }

    // Collect files to format
    val filesToFormat = mutableListOf<File>()

    if (fileArgs.isEmpty()) {
        // Format all .mz files in current directory recursively
        val currentDir = File(System.getProperty("user.dir"))
        collectMezzeFiles(currentDir, filesToFormat)
    } else {
        // Format specified files/directories
        for (arg in fileArgs) {
            val file = File(arg)
            if (!file.exists()) {
                println("Error: File not found: $arg")
                kotlin.system.exitProcess(1)
            }
            if (file.isDirectory) {
                collectMezzeFiles(file, filesToFormat)
            } else if (file.extension == "mz") {
                filesToFormat.add(file)
            } else {
                println("Warning: Skipping non-.mz file: $arg")
            }
        }
    }

    if (filesToFormat.isEmpty()) {
        println("No .mz files found")
        return
    }

    val formatter = Formatter()
    var unformattedCount = 0
    var formattedCount = 0
    var errorCount = 0

    for (file in filesToFormat) {
        try {
            val source = file.readText()
            val formatted = formatter.format(source)

            if (source != formatted) {
                unformattedCount++
                if (checkOnly) {
                    println("Would format: ${file.path}")
                } else {
                    file.writeText(formatted)
                    println("Formatted: ${file.path}")
                    formattedCount++
                }
            }
        } catch (e: Exception) {
            errorCount++
            println("Error formatting ${file.path}: ${e.message}")
        }
    }

    // Print summary
    println()
    if (checkOnly) {
        if (unformattedCount > 0) {
            println("$unformattedCount file(s) need formatting")
            kotlin.system.exitProcess(1)
        } else {
            println("All ${filesToFormat.size} file(s) are formatted correctly")
        }
    } else {
        if (formattedCount > 0) {
            println("Formatted $formattedCount file(s)")
        } else {
            println("All ${filesToFormat.size} file(s) are already formatted")
        }
    }

    if (errorCount > 0) {
        println("$errorCount file(s) had errors")
        kotlin.system.exitProcess(1)
    }
}

/**
 * Internal command: Pre-compile stdlib to SQLite for embedding in native image.
 * Usage: mezze --compile-stdlib <output-path>
 */
private fun handleCompileStdlibCommand(args: List<String>) {
    val outputPath = args.firstOrNull() ?: run {
        System.err.println("Usage: mezze --compile-stdlib <output.db>")
        kotlin.system.exitProcess(1)
    }

    val projectRoot = System.getProperty("mezze.project.root")?.let { File(it) }
        ?: run {
            System.err.println("Error: mezze.project.root not set")
            kotlin.system.exitProcess(1)
        }

    val stdlibDir = File(projectRoot, "stdlib")
    if (!stdlibDir.exists()) {
        System.err.println("Error: stdlib directory not found at ${stdlibDir.absolutePath}")
        kotlin.system.exitProcess(1)
    }

    println("Compiling stdlib from ${stdlibDir.absolutePath}...")

    val stdlibModules = mutableMapOf<String, com.mezze.language.parsedast.ParsedModule>()
    val parser = MezzeParser()
    val typeChecker = TypeChecker()
    var successCount = 0
    var errorCount = 0

    // Clear all caches to start fresh
    ModuleResolver.clearCache()
    TypeChecker.clearCache()
    com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
    com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()

    // Collect all .mz files
    val moduleFiles = mutableListOf<File>()
    collectMezzeFiles(stdlibDir, moduleFiles)

    for (file in moduleFiles) {
        val relativePath = file.relativeTo(stdlibDir).path
        val modulePath = relativePath
            .removeSuffix(".mz")
            .replace(File.separator, "::")

        try {
            val source = file.readText()
            val ast = parser.parseParsedModule(source, file = file.toString())
            // typeCheck throws TypeCheckError on failure, returns TypeCheckResult on success
            val result = typeChecker.typeCheck(ast, projectRoot.toPath(), modulePath = modulePath)
            stdlibModules[modulePath] = ast
            successCount++
        } catch (e: TypeCheckError) {
            errorCount++
            System.err.println("  Warning: $modulePath failed: ${e.message}")
        } catch (e: Exception) {
            errorCount++
            System.err.println("  Warning: $modulePath error: ${e.message}")
        }

        // Clear type-checking stack after each module to prevent cross-contamination
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
    }

    println("Compiled $successCount modules ($errorCount errors)")

    // Save to SQLite
    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()

    // TODO: Rework in #107 — store stdlib in MezzeStore format
    val store = SQLiteMezzeStore.forProject(outputFile.parentFile)
    store.use {
        // Stdlib modules will be scattered into blobs table
        println("TODO: Store stdlib modules in MezzeStore (#107)")
    }

    println("Saved to $outputPath (${outputFile.length() / 1024} KB)")
}

/**
 * Recursively collect all .mz files in a directory
 */
private fun collectMezzeFiles(dir: File, files: MutableList<File>) {
    dir.listFiles()?.forEach { file ->
        if (file.isDirectory) {
            // Skip hidden directories (like .deps, .mez) and build output
            if (!file.name.startsWith(".") && file.name != "build") {
                collectMezzeFiles(file, files)
            }
        } else if (file.extension == "mz") {
            files.add(file)
        }
    }
}

/**
 * Enhanced error reporting functions
 */
private fun printTypeError(error: TypeCheckError) {
    println("❌ Type Error")
    println("━".repeat(50))
    
    // Print location if available
    if (error.loc != null) {
        val loc = error.loc!!
        println("📍 Location: line ${loc.startLine}, column ${loc.startCol}")
        println()
    }
    
    // Print error message with better formatting
    when (error.errorType) {
        ErrorType.TYPE_MISMATCH -> {
            println("🔍 Type Mismatch")
            val expected = error.metadata["expected"] as? MezzeType
            val actual = error.metadata["actual"] as? MezzeType
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
            val type = error.metadata["type"] as? MezzeType
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
    println("💡 For more help, see: https://mezze-lang.org/docs/type-errors")
}

private fun printGeneralError(error: Exception) {
    println("❌ Error")
    println("━".repeat(50))
    println("🔍 ${error.javaClass.simpleName}")
    println("   ${error.message ?: "Unknown error"}")
    println()
    
    // Show stack trace for debugging if it's not a known user error
    if (error !is TypeCheckError && error !is PolyglotException) {
        println("🐛 Debug Information:")
        error.printStackTrace()
    }
}

/**
 * Compute Levenshtein distance between two strings.
 * Used for "did you mean?" suggestions.
 */
private fun levenshteinDistance(s1: String, s2: String): Int {
    val m = s1.length
    val n = s2.length
    val dp = Array(m + 1) { IntArray(n + 1) }

    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j

    for (i in 1..m) {
        for (j in 1..n) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,      // deletion
                dp[i][j - 1] + 1,      // insertion
                dp[i - 1][j - 1] + cost // substitution
            )
        }
    }
    return dp[m][n]
}

