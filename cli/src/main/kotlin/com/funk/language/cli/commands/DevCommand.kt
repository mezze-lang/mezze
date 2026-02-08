package com.funk.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.funk.language.cli.`package`.FunkToml
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.TypeCheckError
import com.funk.language.typechecker.ErrorPrettyPrinter
import com.funk.language.ast.*
import java.io.File

/**
 * Handle `funk dev` command to execute apps from Funk.toml
 *
 * Usage:
 *   funk dev              # Run default app (first [[app]])
 *   funk dev --app server # Run specific app by name
 */
class DevCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val funkToml = File(currentDir, "Funk.toml")

        if (!funkToml.exists()) {
            println("❌ Error: No Funk.toml found in current directory")
            println()
            println("This doesn't look like a Funk package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'funk init' to create a new package, or use 'funk exec <file>' for file-based execution.")
            return
        }

        // Parse Funk.toml
        val tomlContent = funkToml.readText()
        val manifest = try {
            Toml.decodeFromString(FunkToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("❌ Error: Failed to parse Funk.toml")
            println()
            println("The manifest file has invalid TOML syntax.")
            println("Error: ${e.message}")
            println()
            println("💡 Check the syntax of your Funk.toml file.")
            return
        }

        // Check if package has any apps
        if (manifest.app == null || manifest.app.isEmpty()) {
            println("❌ Error: No apps defined in Funk.toml")
            println()
            println("This package has no [[app]] entries.")
            println()
            println("💡 Add an app to your Funk.toml:")
            println("   [[app]]")
            println("   name = \"${manifest.`package`.name}\"")
            println("   path = \"src/app.funk\"")
            println()
            println("   Or use 'funk exec <file>' for file-based execution.")
            return
        }

        // Determine which app to run
        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--app") + 1)?.takeIf { !it.startsWith("--") }

        val appConfig = if (appName != null) {
            // Find app by name
            manifest.app.find { it.name == appName } ?: run {
                println("❌ Error: App '$appName' not found in Funk.toml")
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
            println("Configured in Funk.toml: [[app]] path = \"${appConfig.path}\"")
            println()
            println("💡 Check that the path in your Funk.toml is correct.")
            return
        }

        // Read source
        val source = appFile.readText()

        // Parse and type-check
        val parser = FunkParser()
        val fileName = appFile.name
        val ast = try {
            parser.parse(source, file = fileName)
        } catch (e: com.funk.language.parser.ParseException) {
            println("❌ Parse Error")
            println("━".repeat(50))
            println(e.message)
            kotlin.system.exitProcess(1)
        }

        val typeChecker = TypeChecker()
        val typeCheckResult = try {
            typeChecker.typeCheck(ast, currentDir.toPath())
        } catch (e: TypeCheckError) {
            println(ErrorPrettyPrinter.prettyPrint(e, source, fileName))
            kotlin.system.exitProcess(1)
        }

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

        // Check if user is manually calling main (warn them)
        val hasManualMainCall = typeCheckResult.program.items.any { item ->
            when (item) {
                is TopLevelExpression -> {
                    // Check if the expression is a call to main
                    val expr = item.expression
                    expr is FunctionCall &&
                    expr.function is Identifier &&
                    (expr.function as Identifier).name == "main"
                }
                else -> false
            }
        }

        if (hasManualMainCall) {
            println("⚠️  Warning: Manual call to 'main' detected")
            println()
            println("You have a 'main {}' call in your code, but 'funk dev' automatically")
            println("calls main for you. This will cause main to run twice!")
            println()
            println("💡 Remove the manual 'main {}' call from your code.")
            println()
        }

        // Add a call to main() at the end of the program
        val mainCall = FunctionCall(
            function = Identifier("main", null, null),
            arguments = RecordLiteral(emptyList(), null, null),
            inferredType = null,
            sourceLocation = null
        )
        val programToExecute = typeCheckResult.program.copy(
            items = typeCheckResult.program.items + listOf(TopLevelExpression(mainCall))
        )

        // Execute
        try {
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
            System.setProperty("polyglot.log.level", "OFF")

            val interpreter = com.funk.language.interpreter.FunkInterpreter(skipParserSetup = false)
            interpreter.execute(programToExecute, currentDir.toPath(), typeCheckResult.monomorphicInstances)

            // Execution completed silently
        } catch (e: com.funk.language.interpreter.FunkException) {
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
