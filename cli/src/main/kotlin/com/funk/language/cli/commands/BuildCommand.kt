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
 * Handle `funk build` command to compile apps from Funk.toml to JAR
 *
 * Usage:
 *   funk build              # Build default app (first [[app]])
 *   funk build --app server # Build specific app by name
 */
class BuildCommand {

    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        createJarFunction: (File, File, String, Program) -> Unit
    ) {
        val funkToml = File(currentDir, "Funk.toml")

        if (!funkToml.exists()) {
            println("❌ Error: No Funk.toml found in current directory")
            println()
            println("This doesn't look like a Funk package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'funk init' to create a new package, or use 'funk compile <file>' for file-based compilation.")
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
            println("   Or use 'funk compile <file>' for file-based compilation.")
            return
        }

        // Determine which app to build
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

        println("🔨 Building app: ${appConfig.name}")
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

        // Determine output JAR name
        val outputJarName = if (manifest.app.size == 1) {
            // Single app: use package name
            "${manifest.`package`.name}.jar"
        } else {
            // Multiple apps: use app name
            "${appConfig.name}.jar"
        }
        val outputFile = File(currentDir, outputJarName)

        // Read source
        val source = appFile.readText()

        try {
            // Parse and type-check
            val parser = FunkParser()
            val ast = parser.parse(source, file = appFile.name)

            val typeChecker = TypeChecker()
            val typeCheckResult = typeChecker.typeCheck(ast, currentDir.toPath())

            println("✅ Type checking successful")

            // Validate that program has a 'main' function with type {} -> {}
            val mainBinding = typeCheckResult.program.items.filterIsInstance<LetBinding>()
                .find { it.variableName == "main" }

            if (mainBinding == null) {
                println("❌ Build Error: App must have a 'main' function")
                println("━".repeat(50))
                println("File: ${appFile.absolutePath}")
                println()
                println("Apps must define a main function with type {} -> {}")
                println()
                println("Example:")
                println("  use std::effects::log::{println, Log, Console}")
                println()
                println("  let main = {} -> perform Console in do")
                println("    println { msg = \"Hello from ${appConfig.name}!\" }")
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
                println("    println { msg = \"Hello from ${appConfig.name}!\" }")
                println()
                kotlin.system.exitProcess(1)
            }

            // Create standalone JAR
            createJarFunction(appFile, outputFile, source, typeCheckResult.program)

            println("✅ Build successful!")
            println()
            println("📦 Created: ${outputFile.absolutePath}")
            println("📏 Size: ${outputFile.length() / 1024}KB")
            println()
            println("🚀 Run: java --sun-misc-unsafe-memory-access=allow -jar ${outputFile.name}")
            println("   (Suppresses Truffle deprecation warnings in Java 24)")
            println()
            println("💡 Alternative (with warnings): java -jar ${outputFile.name}")

        } catch (e: TypeCheckError) {
            println(ErrorPrettyPrinter.prettyPrint(e, source, appFile.name))
            kotlin.system.exitProcess(1)
        } catch (e: Exception) {
            println("❌ Build Error:")
            println(e.message ?: "Unknown error")
            kotlin.system.exitProcess(1)
        }
    }
}
