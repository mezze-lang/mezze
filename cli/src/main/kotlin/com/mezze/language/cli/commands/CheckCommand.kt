package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedTypeDecl
import java.io.File
import java.security.MessageDigest

/**
 * Handle `mez check` — typecheck all project files, report errors.
 *
 * Modes:
 *   mez check              — typecheck app from Mezze.toml
 *   mez check --app server — typecheck specific app
 *   mez check src/file.mz  — typecheck a single file
 */
class CheckCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val quietMode = args.contains("-q") || args.contains("--quiet")
        val appIndex = args.indexOf("--app")
        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: if (appIndex >= 0 && appIndex + 1 < args.size) args[appIndex + 1] else null
        val fileArgs = args.filter { arg ->
            !arg.startsWith("-") && arg != appName &&
            !(appIndex >= 0 && args.indexOf(arg) == appIndex + 1)
        }

        if (fileArgs.size == 1) {
            // Single file mode
            checkFile(File(fileArgs[0]), quietMode)
        } else {
            // Package mode
            checkPackage(currentDir, appName, quietMode)
        }
    }

    private fun checkFile(file: File, quiet: Boolean) {
        if (!file.exists()) {
            println("Error: File not found: ${file.path}")
            kotlin.system.exitProcess(1)
        }

        val source = file.readText()
        val projectRoot = file.toPath().toAbsolutePath().parent ?: file.toPath().toAbsolutePath()

        try {
            val startTime = System.nanoTime()
            val parser = MezzeParser()
            val parsedModule = parser.parseParsedModule(source, file = file.absolutePath)
            val parseTime = (System.nanoTime() - startTime) / 1_000_000.0

            val typeCheckStart = System.nanoTime()
            val typeChecker = TypeChecker()
            val typedModule = typeChecker.checkParsedModuleToTyped(parsedModule, projectRoot)
            val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000.0
            val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

            if (quiet) {
                println("OK (${String.format("%.1f", totalTime)}ms)")
            } else {
                printTypedModuleSummary(typedModule)
                println()
                println("Parse: ${String.format("%.1f", parseTime)}ms | Check: ${String.format("%.1f", typeCheckTime)}ms | Total: ${String.format("%.1f", totalTime)}ms")
            }
        } catch (e: TypeCheckError) {
            println(ErrorFormatter.format(e, source))
            kotlin.system.exitProcess(4)
        } catch (e: com.mezze.language.parser.ParseException) {
            println("Parse Error: ${e.message}")
            kotlin.system.exitProcess(3)
        }
    }

    private fun checkPackage(currentDir: File, appName: String?, quiet: Boolean) {
        val mezzeToml = File(currentDir, "Mezze.toml")
        if (!mezzeToml.exists()) {
            println("Error: No Mezze.toml found. Use 'mez check <file>' for single-file mode.")
            kotlin.system.exitProcess(1)
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), mezzeToml.readText())
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            kotlin.system.exitProcess(1)
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            println("Error: No [[app]] entries in Mezze.toml")
            kotlin.system.exitProcess(1)
        }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName } ?: run {
                println("Error: App '$appName' not found. Available: ${manifest.app.map { it.name }}")
                kotlin.system.exitProcess(1)
            }
        } else {
            manifest.app.first()
        }

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            println("Error: App file not found: ${appFile.absolutePath}")
            kotlin.system.exitProcess(1)
        }

        val source = appFile.readText()
        val store = SQLiteMezzeStore.forProject(currentDir)
        com.mezze.language.codebase.StdlibSeed.seedIfNeeded(store)
        com.mezze.language.parser.ParsedModuleCacheHolder.set(
            com.mezze.language.codebase.StoreParsedModuleCache(store)
        )

        try {
            val startTime = System.nanoTime()

            val parser = MezzeParser()
            val ast = parser.parseParsedModule(source, file = appFile.name)

            val typeChecker = TypeChecker()
            val typeCheckResult = typeChecker.typeCheck(ast, currentDir.toPath())
            val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

            // Scatter into store, including the entry + per-import ParsedModules.
            val sourceHash = sha256(source)
            val depParsedModules = com.mezze.language.typechecker.ModuleResolver.getAllParsedModules()
            StorePopulator.populate(
                store, typeCheckResult, manifest.`package`.name,
                appFile.absolutePath, sourceHash,
                entrySource = source,
                parsedModule = ast,
                loadedParsedModules = depParsedModules
            )

            if (quiet) {
                println("OK (${String.format("%.1f", totalTime)}ms)")
            } else {
                val typedModule = typeCheckResult.typedModule
                if (typedModule != null) {
                    printTypedModuleSummary(typedModule)
                }
                println()
                println("Check passed: ${appConfig.name} (${String.format("%.1f", totalTime)}ms)")
            }
        } catch (e: TypeCheckError) {
            println(ErrorFormatter.format(e, source))
            kotlin.system.exitProcess(4)
        } catch (e: com.mezze.language.parser.ParseException) {
            println("Parse Error: ${e.message}")
            kotlin.system.exitProcess(3)
        } finally {
            store.close()
        }
    }

    private fun printTypedModuleSummary(module: com.mezze.language.typedast.TypedModule) {
        val letDecls = module.decls.filterIsInstance<TypedLetDecl>()
        val typeDecls = module.decls.filterIsInstance<TypedTypeDecl>()

        if (typeDecls.isNotEmpty()) {
            for (td in typeDecls) println("type ${td.name}")
            println()
        }

        if (module.effectDecls.isNotEmpty()) {
            for (ed in module.effectDecls) println("effect ${ed.name} (${ed.operations.size} ops)")
            println()
        }

        if (module.abilityDecls.isNotEmpty()) {
            for (ad in module.abilityDecls) println("ability ${ad.name} (${ad.operations.size} ops)")
            println()
        }

        for (decl in letDecls) {
            val vis = if (decl.isPublic) "pub " else ""
            val typeStr = prettyPrintType(decl.scheme.type)
            println("${vis}${decl.name} : $typeStr")
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
