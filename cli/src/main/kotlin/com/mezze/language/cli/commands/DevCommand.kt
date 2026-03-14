package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.cli.CodebaseHelper
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.ast.*
import java.io.File

/**
 * Handle `mezze dev` command to execute apps from Mezze.toml
 *
 * Usage:
 *   mezze dev              # Run default app (first [[app]])
 *   mezze dev --app server # Run specific app by name
 */
class DevCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("❌ Error: No Mezze.toml found in current directory")
            println()
            println("This doesn't look like a Mezze package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'mezze init' to create a new package, or use 'mezze exec <file>' for file-based execution.")
            return
        }

        // Parse Mezze.toml
        val tomlContent = mezzeToml.readText()
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("❌ Error: Failed to parse Mezze.toml")
            println()
            println("The manifest file has invalid TOML syntax.")
            println("Error: ${e.message}")
            println()
            println("💡 Check the syntax of your Mezze.toml file.")
            return
        }

        // Check if package has any apps
        if (manifest.app == null || manifest.app.isEmpty()) {
            println("❌ Error: No apps defined in Mezze.toml")
            println()
            println("This package has no [[app]] entries.")
            println()
            println("💡 Add an app to your Mezze.toml:")
            println("   [[app]]")
            println("   name = \"${manifest.`package`.name}\"")
            println("   path = \"src/app.mz\"")
            println()
            println("   Or use 'mezze exec <file>' for file-based execution.")
            return
        }

        // Determine which app to run
        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--app") + 1)?.takeIf { !it.startsWith("--") }

        val appConfig = if (appName != null) {
            // Find app by name
            manifest.app.find { it.name == appName } ?: run {
                println("❌ Error: App '$appName' not found in Mezze.toml")
                println()
                println("Available apps:")
                manifest.app.forEach { app ->
                    println("  - ${app.name} (${app.path})")
                }
                return
            }
        } else {
            // Use first app
            manifest.app.first()
        }

        println("🚀 Running app: ${appConfig.name}")
        println()

        // Resolve app file path
        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            println("❌ Error: App file not found")
            println()
            println("Expected: ${appFile.absolutePath}")
            println("Configured in Mezze.toml: [[app]] path = \"${appConfig.path}\"")
            println()
            println("💡 Check that the path in your Mezze.toml is correct.")
            return
        }

        // Read source
        val source = appFile.readText()

        // Parse and type-check
        val parser = MezzeParser()
        val fileName = appFile.name
        val ast = try {
            parser.parse(source, file = fileName)
        } catch (e: com.mezze.language.parser.ParseException) {
            println("❌ Parse Error")
            println("━".repeat(50))
            println(e.message)
            kotlin.system.exitProcess(1)
        }

        val typeChecker = TypeChecker()
        val typeCheckResult = try {
            typeChecker.typeCheck(ast, currentDir.toPath())
        } catch (e: TypeCheckError) {
            println(ErrorFormatter.format(e, source))
            kotlin.system.exitProcess(1)
        }

        // Populate codebase in project mode
        CodebaseHelper.populateIfInProject(
            projectRoot = currentDir,
            typeCheckResult = typeCheckResult,
            modulePath = manifest.`package`.name
        )

        // Check if program has a 'main' function and auto-call it
        val mainBinding = typeCheckResult.program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        if (mainBinding == null) {
            println("❌ Error: App must have a 'main' function")
            println()
            println("File: ${appFile.absolutePath}")
            println()
            println("💡 Apps must define a main function with type {} -> {}")
            println()
            println("Example:")
            println("  use std::effects::log::{println, Log, Console}")
            println()
            println("  let main = {} -> perform Console in do")
            println("    println { msg = \"Hello from ${appConfig.name}!\" }")
            kotlin.system.exitProcess(1)
        }

        // Validate main has type {} -> {}
        val mainType = mainBinding.inferredType
        val isValidMainType = mainType is MezzeType.FunctionType &&
                              mainType.paramType is MezzeType.RecordType &&
                              (mainType.paramType as MezzeType.RecordType).fields.isEmpty() &&
                              !(mainType.paramType as MezzeType.RecordType).isOpen &&
                              mainType.returnType is MezzeType.RecordType &&
                              (mainType.returnType as MezzeType.RecordType).fields.isEmpty() &&
                              !(mainType.returnType as MezzeType.RecordType).isOpen

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

        // Check if user is manually calling main (warn them)
        val hasManualMainCall = typeCheckResult.program.items.any { item ->
            when (item) {
                is TopLevelExpression -> {
                    // Check if the expression is a call to main
                    val expr = item.expression
                    expr is FunctionCall &&
                    expr.function is Identifier &&
                    (expr.function as Identifier).localName == "main"
                }
                else -> false
            }
        }

        if (hasManualMainCall) {
            println("⚠️  Warning: Manual call to 'main' detected")
            println()
            println("You have a 'main {}' call in your code, but 'mezze dev' automatically")
            println("calls main for you. This will cause main to run twice!")
            println()
            println("💡 Remove the manual 'main {}' call from your code.")
            println()
        }

        // Add a call to main() at the end of the program
        val mainCall = FunctionCall(
            function = Identifier("main", null, null),
            arguments = RecordLiteral(emptyList(), false, null),
            inferredType = null,
            sourceLocation = null
        )
        val programToExecute = typeCheckResult.program.copy(
            items = typeCheckResult.program.items + listOf(TopLevelExpression(mainCall))
        )

        // Execute using V2 interpreter
        try {
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
            System.setProperty("polyglot.log.level", "OFF")

            // Set up V2 context
            val context = org.graalvm.polyglot.Context.newBuilder(com.mezze.interpreter.v2.runtime.MezzeLanguage.ID)
                .allowAllAccess(true)
                .allowCreateThread(true)
                .build()

            try {
                // Store the program for direct execution (bypasses parsing)
                com.mezze.interpreter.v2.runtime.MezzeLanguage.setProgramForDirectExecution(programToExecute, currentDir.toPath())

                // Store monomorphic instances (compiler-generated specializations)
                if (typeCheckResult.monomorphicInstances.isNotEmpty()) {
                    com.mezze.interpreter.v2.runtime.MezzeLanguage.setMonomorphicInstances(typeCheckResult.monomorphicInstances)
                }

                // Create a dummy source to trigger MezzeLanguage.parse()
                val source = org.graalvm.polyglot.Source.newBuilder(
                    com.mezze.interpreter.v2.runtime.MezzeLanguage.ID,
                    "# Direct AST execution",
                    "__direct_execution__.mz"
                ).buildLiteral()

                // Execute via polyglot API
                context.eval(source)
            } finally {
                // Use cancelIfExecuting=true for Go/Rust-style semantics:
                // When main exits, any fire-and-forget tasks (unawaited spawns) are cancelled.
                // This matches Go (goroutines die when main exits) and Rust (tokio tasks cancelled).
                // See: https://www.graalvm.org/truffle/javadoc/org/graalvm/polyglot/Context.html
                context.close(true)
            }

            // Execution completed silently
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            println("❌ Runtime Error:")
            println(e.message ?: "Unknown runtime error")
            kotlin.system.exitProcess(1)
        } catch (e: Exception) {
            println("❌ Error:")
            println(e.message ?: "Unknown error")
            kotlin.system.exitProcess(1)
        }
    }
}
