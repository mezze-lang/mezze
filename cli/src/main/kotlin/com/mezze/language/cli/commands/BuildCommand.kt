package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.cli.CodebaseHelper
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.ast.*
import com.mezze.language.typedast.TypedLetDecl
import java.io.File

/**
 * Handle `mezze build` command to compile apps from Mezze.toml to JAR
 *
 * Usage:
 *   mezze build              # Build default app (first [[app]])
 *   mezze build --app server # Build specific app by name
 */
class BuildCommand {

    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        createJarFunction: (File, File, String, TypeCheckResult) -> Unit
    ) {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("❌ Error: No Mezze.toml found in current directory")
            println()
            println("This doesn't look like a Mezze package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'mezze init' to create a new package, or use 'mezze compile <file>' for file-based compilation.")
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
            println("   Or use 'mezze compile <file>' for file-based compilation.")
            return
        }

        // Determine which app to build
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

        println("🔨 Building app: ${appConfig.name}")
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
            val parser = MezzeParser()
            val ast = parser.parseParsedModule(source, file = appFile.name)

            val typeChecker = TypeChecker()
            val typeCheckResult = typeChecker.typeCheck(ast, currentDir.toPath())

            println("✅ Type checking successful")

            // Populate codebase in project mode
            if (CodebaseHelper.populateIfInProject(
                projectRoot = currentDir,
                typeCheckResult = typeCheckResult,
                modulePath = manifest.`package`.name
            )) {
                println("📦 Updated .mez/codebase.db")
            }

            // Validate that program has a 'main' function with type {} -> {}
            val mainBinding = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>()
                ?.find { it.name == "main" }

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
            val mainType = mainBinding.scheme.type
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
                println("Actual:   ${prettyPrintType(mainType)}")
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
            createJarFunction(appFile, outputFile, source, typeCheckResult)

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
            println(ErrorFormatter.format(e, source))
            kotlin.system.exitProcess(1)
        } catch (e: Exception) {
            println("❌ Build Error:")
            println(e.message ?: "Unknown error")
            kotlin.system.exitProcess(1)
        }
    }
}
