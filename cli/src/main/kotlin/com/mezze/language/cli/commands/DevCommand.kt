package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml

import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.ast.*
import com.mezze.language.typedast.TypedLetDecl
import java.io.File
import java.security.MessageDigest

/**
 * Handle `mez dev` command — incremental compile + execute.
 *
 * Flow:
 * 1. Read Mezze.toml → find app entry file
 * 2. Open .mz/cache.sqlite
 * 3. Check staleness (source hash vs stored hash)
 * 4. If stale: parse → typecheck → lower → scatter into store
 * 5. Execute via Truffle
 */
class DevCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val inspect = args.contains("--inspect")
        val inspectBrk = args.contains("--inspect-brk")
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("Error: No Mezze.toml found in current directory")
            println()
            println("Run 'mez init' to create a new project, or use 'mez exec <file>' for single-file execution.")
            return
        }

        // Parse Mezze.toml
        val tomlContent = mezzeToml.readText()
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            return
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            println("Error: No [[app]] entries in Mezze.toml")
            println()
            println("Add an app:")
            println("  [[app]]")
            println("  name = \"${manifest.`package`.name}\"")
            println("  path = \"src/app.mz\"")
            return
        }

        // Determine which app to run
        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--app") + 1)?.takeIf { !it.startsWith("--") }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName } ?: run {
                println("Error: App '$appName' not found. Available: ${manifest.app.map { it.name }}")
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

        // Open project store
        val store = SQLiteMezzeStore.forProject(currentDir)

        // Load stdlib into store on first use
        if (store.getMetadata("stdlib_loaded") == null) {
            try {
                val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
                store.transaction {
                    for ((hash, fnIR) in precompiled.preloweredFunctions) {
                        val irBytes = com.mezze.language.codebase.CodebaseSerialization.serializeFunctionIR(fnIR)
                        store.putBlob(com.mezze.language.typedast.Hash(hash),
                            com.mezze.language.codebase.BlobKind.FUNCTION,
                            com.mezze.language.codebase.Visibility.PUBLIC, null, irBytes)
                    }
                    store.setMetadata("stdlib_loaded", "true")
                }
            } catch (_: Exception) {}
        }

        try {
            val totalStart = System.nanoTime()

            // Read source and check staleness
            val source = appFile.readText()
            val sourceHash = sha256(source)
            val filePath = appFile.absolutePath
            val modulePath = manifest.`package`.name

            val stale = store.isFileStale(filePath, sourceHash)

            val functions: List<com.mezze.language.truffleir.FunctionIR>
            val typeCheckResult: com.mezze.language.typechecker.TypeCheckResult

            if (stale) {
                // Compile: parse → typecheck → lower
                val parser = MezzeParser()
                val ast = try {
                    parser.parseParsedModule(source, file = appFile.name)
                } catch (e: com.mezze.language.parser.ParseException) {
                    println("Parse Error: ${e.message}")
                    kotlin.system.exitProcess(3)
                }

                val typeChecker = TypeChecker()
                typeCheckResult = try {
                    typeChecker.typeCheck(ast, currentDir.toPath())
                } catch (e: TypeCheckError) {
                    println(ErrorFormatter.format(e, source))
                    kotlin.system.exitProcess(4)
                }

                functions = typeCheckResult.loweredFunctions

                // Scatter into store
                StorePopulator.populate(store, typeCheckResult, modulePath, filePath, sourceHash)
            } else {
                // Fresh — still need to typecheck for execution (functions aren't in store yet as CallTargets)
                // TODO: When MezzeExecutor is wired to load IR from store directly,
                // this path can skip typechecking entirely and just load cached FunctionIRs.
                val parser = MezzeParser()
                val ast = parser.parseParsedModule(source, file = appFile.name)
                val typeChecker = TypeChecker()
                typeCheckResult = try {
                    typeChecker.typeCheck(ast, currentDir.toPath())
                } catch (e: TypeCheckError) {
                    println(ErrorFormatter.format(e, source))
                    kotlin.system.exitProcess(4)
                }
                functions = typeCheckResult.loweredFunctions
            }

            // Validate entry function exists
            val entryName = appConfig.entry
            val mainBinding = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>()
                ?.find { it.name == entryName }

            if (mainBinding == null) {
                println("Error: App must define a '$entryName' function with type {} -> {}")
                kotlin.system.exitProcess(4)
            }

            // Debug: print performer records
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[DEBUG] ${functions.size} functions, ${typeCheckResult.performerRecords.size} performer records")
                for ((i, pr) in typeCheckResult.performerRecords.withIndex()) {
                    System.err.println("[DEBUG] performer[$i]: ${pr.joinToString(", ") { it.take(16) + "..." }}")
                }
            }

            // Execute via Truffle
            executeWithTruffle(functions, typeCheckResult.performerRecords, currentDir, inspect || inspectBrk, inspectBrk)

            val totalTime = (System.nanoTime() - totalStart) / 1_000_000
            if (System.getenv("MEZZE_TIMING") != null) {
                System.err.println("Total: ${totalTime}ms (${if (stale) "recompiled" else "cached"})")
            }

        } catch (e: org.graalvm.polyglot.PolyglotException) {
            println("Runtime Error: ${e.message}")
            kotlin.system.exitProcess(5)
        } catch (e: Exception) {
            println("Error: ${e.message}")
            kotlin.system.exitProcess(1)
        } finally {
            store.close()
        }
    }

    private fun executeWithTruffle(
        functions: List<com.mezze.language.truffleir.FunctionIR>,
        performerRecords: List<Array<String>>,
        projectDir: File,
        inspect: Boolean = false,
        inspectBrk: Boolean = false
    ) {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        val builder = org.graalvm.polyglot.Context.newBuilder(com.mezze.interpreter.runtime.MezzeLanguage.ID)
            .allowAllAccess(true)
            .allowCreateThread(true)

        // Enable Chrome DevTools debugger
        if (inspect || inspectBrk) {
            builder.option("inspect", "true")
            if (inspectBrk) builder.option("inspect.Suspend", "true")
            System.err.println("Debugger listening on ws://127.0.0.1:9229")
            System.err.println("Open chrome://inspect in Chrome to connect")
        }

        val context = builder.build()

        try {
            com.mezze.interpreter.runtime.MezzeLanguage.setFunctionsForDirectExecution(functions, performerRecords, projectDir.toPath())

            val source = org.graalvm.polyglot.Source.newBuilder(
                com.mezze.interpreter.runtime.MezzeLanguage.ID,
                "# Direct TruffleIR execution",
                "__direct_execution__.mz"
            ).buildLiteral()

            context.eval(source)
        } finally {
            context.close(true)
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
