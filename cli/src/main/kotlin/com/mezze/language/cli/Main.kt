package com.mezze.language.cli

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as PVisibility
import com.mezze.language.formatter.Formatter
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.typechecker.*
import com.mezze.language.langservice.printing.ErrorFormatter
import org.graalvm.polyglot.PolyglotException
import com.mezze.language.codebase.SQLiteMezzeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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
    // Register the SQLite-backed store opener so PrecompiledStdlib can
    // hydrate from `stdlib.sqlite` without reflection. Must happen before
    // any code touches PrecompiledStdlib (its init block calls
    // openStdlibResourceAsStore → MezzeStoreOpener.current()). #168 — the
    // reflective workaround for the `:typechecker` → `:store-sqlite` dep
    // cycle silently failed under native-image and forced compile-from-
    // source on every startup.
    com.mezze.language.store.MezzeStoreOpener.register { file ->
        com.mezze.language.codebase.SQLiteMezzeStore.readOnly(file)
    }

    // Auto-detect and set mezze.project.root if not already set
    // This is mainly for development mode - stdlib is embedded in production native binary
    if (System.getProperty("mezze.project.root") == null) {
        val projectRoot = detectProjectRoot()
        if (projectRoot != null) {
            System.setProperty("mezze.project.root", projectRoot.absolutePath)
        }
    }

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

    if (command == "--explain") {
        com.mezze.language.cli.commands.ExplainCommand().execute(remainingArgs)
        return
    }

    // Handle command-specific help
    if (remainingArgs.contains("--help") || remainingArgs.contains("-h")) {
        showCommandHelp(command)
        return
    }

    try {
        when (command) {
            "check" -> handleCheckCommand(remainingArgs)
            "dev" -> handleDevCommand(remainingArgs)
            "exec" -> handleExecCommand(remainingArgs)
            "build" -> handleBuildCommand(remainingArgs)
            "init" -> handleInitCommand(remainingArgs)
            "index" -> handleIndexCommand(remainingArgs)
            "lsp" -> handleLspCommand(remainingArgs)
            "fmt" -> handleFmtCommand(remainingArgs)
            "sync" -> handleSyncCommand(remainingArgs)
            "add" -> handleAddCommand(remainingArgs)
            "remove" -> handleRemoveCommand(remainingArgs)
            else -> {
                println("Error: Unknown command: $command")
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
 * Render the version for human display. Prepends `v` only when the
 * version begins with a digit (semver-style); leaves nightly /
 * dev / branch tags as-is, since `vnightly-...` reads oddly.
 */
private fun displayVersion(): String {
    val v = BuildInfo.VERSION
    return if (v.firstOrNull()?.isDigit() == true) "v$v" else v
}

/**
 * Show main help information
 */
private fun showHelp() {
    println("Mezze Language ${displayVersion()}")
    println("A modern functional programming language with full Truffle support")
    println()
    showUsage()
    println()
    println("PROJECT COMMANDS (requires Mezze.toml):")
    println("  init      Create a new Mezze package")
    println("  dev       Run app — watches files and hot-reloads (--no-watch to run once)")
    println("  build     Build app to a standalone JAR")
    println("  check     Type check the project")
    println()
    println("EDITOR / FORMATTING:")
    println("  fmt       Format Mezze source code")
    println("  lsp       Start Language Server Protocol server")
    println()
    println("GLOBAL OPTIONS:")
    println("  -h, --help        Show this help message")
    println("  -v, --version     Show version information")
    println("  --explain <CODE>  Show long-form docs for an error code (e.g. T0201)")
    println()
    println("EXAMPLES:")
    println("  mez init my_app                 # Create an app package")
    println("  cd my_app && mez dev            # Run app, hot-reload on save")
    println("  mez dev --no-watch              # Run once and exit")
    println("  mez build                       # Build app to JAR")
    println()
    println("For command-specific help: mez <command> --help")
}

/**
 * Show version information
 */
private fun showVersion() {
    println("Mezze Language ${displayVersion()} (${BuildInfo.GIT_SHA})")
    // Probe runtime JDK + GraalVM info instead of hardcoding a version
    // that drifts on every distribution bump. java.vm.vendor tends to be
    // "GraalVM Community" / "Oracle GraalVM" / "OpenJDK" + "Inc.";
    // java.vm.version carries the build string like "25.0.2+10-jvmci-b01".
    val vmVendor = System.getProperty("java.vm.vendor") ?: "unknown JVM"
    val vmVersion = System.getProperty("java.vm.version")
        ?: System.getProperty("java.version") ?: "?"
    println("Built with $vmVendor $vmVersion")
    println()
    println("Features:")
    println("  Bidirectional type inference")
    println("  Pattern matching")
    println("  Record types with row polymorphism")
    println("  Variant types")
    println("  Full Truffle polyglot interop")
}

/**
 * Show basic usage
 */
private fun showUsage() {
    println("USAGE:")
    println("  mez <command> [options]")
    println()
    println("COMMANDS:")
    println("  Project:  init, dev, exec, build, check")
    println("  Tools:    fmt, lsp, index")
    println()
    println("For command-specific help: mez <command> --help")
}

/**
 * Show command-specific help
 */
private fun showCommandHelp(command: String) {
    when (command) {
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
            println("  --json             Emit machine-readable JSON instead of text")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mezze check src/app.mz       # Check file + imports")
            println("  mezze check --quiet app.mz   # Quiet mode")
            println("  mezze check                    # Check package app")
            println("  mezze check --app server       # Check specific app")
            println("  mezze check --json app.mz      # JSON output for CI/tooling")
        }
        
        "init" -> {
            println("USAGE: mezze init [options]")
            println()
            println("Create a new Mezze package in the current directory")
            println()
            println("OPTIONS:")
            println("  --lib              Create a library package")
            println("  --app              Create an app package (default)")
            println("  --lib --app        Create both library and app")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mez init my_app                  # Create app (default)")
            println("  mez init my_lib --lib            # Create library")
            println("  mez init my_pkg --lib --app      # Create both")
            println()
        }

        "dev" -> {
            println("USAGE: mez dev [options]")
            println()
            println("Run the app defined in Mezze.toml.")
            println()
            println("Default mode is watch: compile, execute, then watch source files and")
            println("hot-reload on save. Press 'r' to re-run, 'q' to quit, 'h' for help.")
            println()
            println("MODES:")
            println("  --no-watch, --nw   Run once and exit (skip the file watcher).")
            println("  --app <name>       Pick a specific [[app]] entry.")
            println()
            println("DEBUG / PROFILE:")
            println("  --inspect          Attach Chrome DevTools / VS Code debugger.")
            println("  --inspect-brk      Same as --inspect, but breaks before main.")
            println("  --profile          CPU sampler → .mez/profile/mezze-profile.txt")
            println("  --coverage         Line coverage → .mez/profile/mezze-coverage.txt")
            println("  --allocations      Allocation tracer (records, seqs, variants) → .mez/profile/mezze-allocations.txt")
            println("  --trace-jit        Trace Graal compilation + deopts to stderr.")
            println("  --insight=<file>   Attach a GraalVM Insight JS probe.")
            println()
            println("OTHER:")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mez dev                              # Watch + hot-reload")
            println("  mez dev --no-watch                   # Run once and exit")
            println("  mez dev --profile --no-watch         # Profile a single run")
            println("  mez dev --insight=trace.js           # Run with a JS probe")
            println("  mez dev --app server                 # Run a specific app")
            println()
        }

        "exec" -> {
            println("USAGE: mez exec [app_name]")
            println()
            println("Run the project's app once with the JIT-on Truffle engine, then exit.")
            println()
            println("Same fast execution path as 'mez dev' (load → typecheck → lower → run),")
            println("but without the file watcher and without writing any artifacts to disk.")
            println()
            println("APP SELECTION:")
            println("  mez exec               Run the first [[app]] in Mezze.toml")
            println("  mez exec <name>        Run the [[app]] with that name")
            println("  mez exec --app=<name>  Same, explicit flag form")
            println()
            println("OTHER:")
            println("  -h, --help             Show this help message")
            println()
            println("EXAMPLES:")
            println("  mez exec               # Run the default app")
            println("  mez exec server        # Run the 'server' app")
        }

        "build" -> {
            println("USAGE: mez build [options]")
            println()
            println("Compile the app defined in Mezze.toml to a standalone JAR.")
            println()
            println("OPTIONS:")
            println("  --app <name>       Build a specific app by name")
            println("  --out <path>       Write artifacts to <path> instead of .build/")
            println("  -o <path>          Alias for --out")
            println("  --native, -n       Also produce an AOT-compiled native binary")
            println("                     (requires native-image; ~60s build cost)")
            println("  -h, --help         Show this help message")
            println()
            println("EXAMPLES:")
            println("  mez build                      # Build default app to .build/")
            println("  mez build --app server         # Build a specific app")
            println("  mez build --out dist           # Write to dist/ instead")
            println("  mez build --native             # Also build native binary")
            println()
            println("ENV VARS:")
            println("  MEZZE_NATIVE_VERBOSE=1  Stream native-image output live")
            println("                         (default: capture + dump on failure)")
            println()
            println("OUTPUT DIR (precedence: --out flag > [build].out in Mezze.toml > .build/):")
            println("  Single app:    <out>/<package-name>.jar + launcher")
            println("  Multiple apps: <out>/<app-name>/<app-name>.jar + launcher")
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
            println("Error: Unknown command: $command")
            println()
            showUsage()
        }
    }
}

private fun handleCheckCommand(args: List<String>) {
    com.mezze.language.cli.commands.CheckCommand().execute(args)
}

private fun handleIndexCommand(args: List<String>) {
    com.mezze.language.cli.commands.IndexCommand().execute(args)
}

private fun handleSyncCommand(args: List<String>) {
    com.mezze.language.cli.commands.SyncCommand().execute(args)
}

private fun handleAddCommand(args: List<String>) {
    com.mezze.language.cli.commands.AddCommand().executeAdd(args)
}

private fun handleRemoveCommand(args: List<String>) {
    com.mezze.language.cli.commands.AddCommand().executeRemove(args)
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
        is ParsedNewtypeConstruct -> "${expr.typeName}.(${prettyPrintParsedExpr(expr.value)})"
        is ParsedIf -> "if ${prettyPrintParsedExpr(expr.condition)} then ${prettyPrintParsedExpr(expr.thenBranch)} else ${prettyPrintParsedExpr(expr.elseBranch)}"
        is ParsedMatch -> "match ${prettyPrintParsedExpr(expr.scrutinee)} is ..."
        is ParsedDo -> "do ... ${prettyPrintParsedExpr(expr.result)}"
        is ParsedSeqLit -> "[${expr.elements.size} elements]"
        is ParsedPerform -> "perform ... in ${prettyPrintParsedExpr(expr.body)}"
        is ParsedEffectCall -> "${expr.operation} ${prettyPrintParsedExpr(expr.arguments)}"
        is ParsedPipe -> "${prettyPrintParsedExpr(expr.left)} |> ${prettyPrintParsedExpr(expr.right)}"
        is ParsedPipePlaceholder -> "@"
        is ParsedParens -> "(${prettyPrintParsedExpr(expr.expr)})"
        is ParsedBinaryOp -> "${prettyPrintParsedExpr(expr.left)} ${expr.op.symbol} ${prettyPrintParsedExpr(expr.right)}"
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
        is ParsedTypeAliasDef -> prettyPrintParsedType(def.aliasedType)
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
    println("-".repeat(50))

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

private fun handleDevCommand(args: List<String>) {
    com.mezze.language.cli.commands.DevCommand().execute(args)
}

private fun handleExecCommand(args: List<String>) {
    com.mezze.language.cli.commands.ExecCommand().execute(args)
}

private fun handleBuildCommand(args: List<String>) {
    com.mezze.language.cli.commands.BuildCommand().execute(args)
}

/**
 * Package management command handlers
 */
private fun handleInitCommand(args: List<String>) {
    com.mezze.language.cli.commands.InitCommand().execute(args)
}

// AddCommand / SyncCommand classes remain in tree — re-wire here when the
// dependency-resolution backend (#108) lands: deps.sqlite + Mezze.lock +
// pipeline reads from mezze_packages/.

/**
 * Start the Language Server Protocol server
 */
private fun handleLspCommand(args: List<String>) {
    // Handle version flag
    if (args.contains("--version") || args.contains("-v")) {
        println("Mezze Language Server ${displayVersion()}")
        return
    }

    // Start the LSP server (communicates via stdin/stdout). Pass
    // BuildInfo.VERSION so the version reported via LSP `initialize`'s
    // ServerInfo matches `mez --version`.
    com.mezze.language.lsp.startServer(System.`in`, System.out, BuildInfo.VERSION)
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
    println("Error: Type Error")
    println("-".repeat(50))
    
    // Print location if available
    if (error.loc != null) {
        val loc = error.loc!!
        println("Location: line ${loc.startLine}, column ${loc.startCol}")
        println()
    }
    
    // Print error message with better formatting
    when (error.errorType) {
        ErrorType.TYPE_MISMATCH -> {
            println("Type Mismatch")
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
            println("Undefined Variable")
            val variable = error.metadata["variable"] as? String
            if (variable != null) {
                println("   Variable '$variable' is not defined in the current scope")
                println()
                println("Hint: Tip: Check for typos or make sure the variable is declared before use")
            } else {
                println("   ${error.message}")
            }
        }
        ErrorType.MISSING_FIELD -> {
            println("Missing Field")
            val field = error.metadata["field"] as? String
            val type = error.metadata["type"] as? MezzeType
            if (field != null && type != null) {
                println("   Field '$field' not found in type ${prettyPrintType(type)}")
                println()
                println("Hint: Tip: Check the field name for typos")
            } else {
                println("   ${error.message}")
            }
        }
        ErrorType.INEXHAUSTIVE_MATCH -> {
            println("Non-exhaustive Pattern Match")
            @Suppress("UNCHECKED_CAST")
            val missing = error.metadata["missing"] as? List<String>
            if (missing != null) {
                println("   Missing patterns: ${missing.joinToString(", ")}")
                println()
                println("Hint: Tip: Add cases for all missing patterns to make the match exhaustive")
            } else {
                println("   ${error.message}")
            }
        }
        else -> {
            println("${error.errorType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}")
            println("   ${error.message}")
        }
    }
    
    println()
    println("Hint: For more help, see: https://mezze-lang.org/docs/type-errors")
}

private fun printGeneralError(error: Exception) {
    println("Error: Error")
    println("-".repeat(50))
    println("${error.javaClass.simpleName}")
    println("   ${error.message ?: "Unknown error"}")
    println()
    
    // Show stack trace for debugging if it's not a known user error
    if (error !is TypeCheckError && error !is PolyglotException) {
        println("Debug: Debug Information:")
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

