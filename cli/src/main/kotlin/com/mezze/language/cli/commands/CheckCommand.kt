package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.langservice.diagnostics.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.langservice.printing.ErrorFormatter
import com.mezze.language.langservice.printing.ParseErrorFormatter
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
        val jsonMode = args.contains("--json")
        val appIndex = args.indexOf("--app")
        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: if (appIndex >= 0 && appIndex + 1 < args.size) args[appIndex + 1] else null
        val fileArgs = args.filter { arg ->
            !arg.startsWith("-") && arg != appName &&
            !(appIndex >= 0 && args.indexOf(arg) == appIndex + 1)
        }

        if (fileArgs.size == 1) {
            // Single file mode
            checkFile(File(fileArgs[0]), quietMode, jsonMode)
        } else {
            // Package mode
            checkPackage(currentDir, appName, quietMode, jsonMode)
        }
    }

    private fun checkFile(file: File, quiet: Boolean, jsonMode: Boolean) {
        if (!file.exists()) {
            if (jsonMode) {
                jsonExitWith(file.path, null,
                    DiagnosticJson(Severity.ERROR, message = "File not found: ${file.path}"),
                    exitCode = 1)
            }
            println("Error: File not found: ${file.path}")
            kotlin.system.exitProcess(1)
        }

        val source = file.readText()
        val projectRoot = file.toPath().toAbsolutePath().parent ?: file.toPath().toAbsolutePath()
        val absPath = file.absolutePath

        try {
            val startTime = System.nanoTime()
            val parser = MezzeParser()
            // Use parseWithRecovery so multiple parse errors surface together
            // (the throwing parseParsedModule stopped at the first one). The
            // partial ParsedModule from a failed parse is still fed to the
            // typechecker so cross-decl errors in surviving code also surface.
            val parseResult = parser.parseWithRecovery(source, file = absPath)
            val parsedModule = parseResult.module
            val parseTime = (System.nanoTime() - startTime) / 1_000_000.0

            // Text mode: print parse errors first, then continue to typecheck the partial.
            // JSON mode: collect everything into a single diagnostics list, output at end.
            if (!jsonMode && parseResult.errors.isNotEmpty()) {
                println(ParseErrorFormatter.formatAll(parseResult.errors))
            }

            val typeCheckStart = System.nanoTime()
            val typeChecker = TypeChecker(strictTopLevel = true)
            // Use the lower-level result-returning API so we can report ALL
            // type errors at once, not just the first. The previous
            // checkParsedModuleToTyped path threw the first error and dropped
            // the rest, hiding any independent errors in sibling decls.
            val result = typeChecker.checkParsedModule(parsedModule, projectRoot, enablePrelude = true)
            val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000.0
            val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

            when (result) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> {
                    val typedModule = result.typedModule
                        ?: throw TypeCheckError("V2 pipeline did not emit a TypedModule")
                    if (jsonMode) {
                        if (parseResult.errors.isNotEmpty()) {
                            jsonExitWith(absPath, null,
                                parseResult.errors.map { JsonDiagnosticFormatter.fromParseException(it) },
                                exitCode = 3)
                        }
                        emitJson(JsonDiagnosticFormatter.ok(absPath, null, typedModule, emptyList()))
                        return
                    }
                    if (parseResult.errors.isNotEmpty()) {
                        // Surviving decls typechecked, but parse errors mean
                        // some code is missing. The "aborting due to N
                        // previous parse errors" footer from
                        // ParseErrorFormatter.formatAll already says it;
                        // exit non-zero so callers see a failure.
                        kotlin.system.exitProcess(3)
                    }
                    if (quiet) {
                        println("OK (${String.format("%.1f", totalTime)}ms)")
                    } else {
                        printTypedModuleSummary(typedModule)
                        println()
                        println("Parse: ${String.format("%.1f", parseTime)}ms | Check: ${String.format("%.1f", typeCheckTime)}ms | Total: ${String.format("%.1f", totalTime)}ms")
                    }
                }
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> {
                    if (jsonMode) {
                        val diags = parseResult.errors.map { JsonDiagnosticFormatter.fromParseException(it) } +
                                    result.errors.map { JsonDiagnosticFormatter.fromTypeCheckError(it) }
                        jsonExitWith(absPath, null, diags,
                            exitCode = if (parseResult.errors.isNotEmpty()) 3 else 4)
                    }
                    if (parseResult.errors.isNotEmpty()) println()
                    for ((idx, err) in result.errors.withIndex()) {
                        if (idx > 0) println()
                        println(ErrorFormatter.format(err, source))
                    }
                    val total = parseResult.errors.size + result.errors.size
                    if (total > 1) {
                        println()
                        val parts = buildList {
                            if (parseResult.errors.isNotEmpty()) add("${parseResult.errors.size} parse")
                            if (result.errors.isNotEmpty()) add("${result.errors.size} type")
                        }
                        println("($total errors: ${parts.joinToString(" + ")})")
                    }
                    printExplainHint(result.errors.map { it.code })
                    kotlin.system.exitProcess(if (parseResult.errors.isNotEmpty()) 3 else 4)
                }
            }
        } catch (e: TypeCheckError) {
            if (jsonMode) {
                jsonExitWith(absPath, null, JsonDiagnosticFormatter.fromTypeCheckError(e), exitCode = 4)
            }
            println(ErrorFormatter.format(e, source))
            printExplainHint(listOf(e.code))
            kotlin.system.exitProcess(4)
        } catch (e: com.mezze.language.typechecker.module.ModuleError) {
            if (jsonMode) {
                jsonExitWith(absPath, null, JsonDiagnosticFormatter.fromModuleError(e), exitCode = 4)
            }
            // Module errors carry their own ErrorCode + hints now; route
            // through the canonical formatter so users see `error[M0801]:`.
            println(ErrorFormatter.formatModuleError(e))
            printExplainHint(listOf(e.code))
            kotlin.system.exitProcess(4)
        } catch (e: com.mezze.language.parser.ParseException) {
            if (jsonMode) {
                jsonExitWith(absPath, null, JsonDiagnosticFormatter.fromParseException(e), exitCode = 3)
            }
            // Should be rare now (parseWithRecovery doesn't throw), but
            // keep as a safety net for unrecoverable lex errors.
            println(ParseErrorFormatter.format(e))
            kotlin.system.exitProcess(3)
        }
    }

    private fun emitJson(result: CheckResultJson) {
        println(JsonDiagnosticFormatter.render(result))
    }

    /**
     * Emit a JSON error envelope for a single error source and exit with
     * [exitCode]. `Nothing` return type tells the compiler this is terminal,
     * eliminating dead-code in catch blocks.
     */
    private fun jsonExitWith(
        file: String?,
        app: String?,
        diagnostic: DiagnosticJson,
        exitCode: Int,
    ): Nothing {
        emitJson(JsonDiagnosticFormatter.errors(file, app, listOf(diagnostic)))
        kotlin.system.exitProcess(exitCode)
    }

    /** Bulk variant — for combined parse + type error envelopes. */
    private fun jsonExitWith(
        file: String?,
        app: String?,
        diagnostics: List<DiagnosticJson>,
        exitCode: Int,
    ): Nothing {
        emitJson(JsonDiagnosticFormatter.errors(file, app, diagnostics))
        kotlin.system.exitProcess(exitCode)
    }

    private fun checkPackage(currentDir: File, appName: String?, quiet: Boolean, jsonMode: Boolean) {
        // Local helper for the setup-phase early exits (before we have an app context).
        fun bail(message: String, exitCode: Int): Nothing {
            if (jsonMode) {
                jsonExitWith(file = null, app = appName,
                    diagnostic = DiagnosticJson(Severity.ERROR, message = message),
                    exitCode = exitCode)
            }
            println("Error: $message")
            kotlin.system.exitProcess(exitCode)
        }

        val mezzeToml = File(currentDir, "Mezze.toml")
        if (!mezzeToml.exists()) {
            bail("No Mezze.toml found. Use 'mez check <file>' for single-file mode.", 1)
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), mezzeToml.readText())
        } catch (e: Exception) {
            bail("Failed to parse Mezze.toml: ${e.message}", 1)
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            bail("No [[app]] entries in Mezze.toml", 1)
        }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName }
                ?: bail("App '$appName' not found. Available: ${manifest.app.map { it.name }}", 1)
        } else {
            manifest.app.first()
        }

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            bail("App file not found: ${appFile.absolutePath}", 1)
        }

        val source = appFile.readText()
        // Opens + invalidates cache written by a different mez version — so a
        // toolchain switch never type-checks against stale IR (see ProjectStore).
        val store = ProjectStore.open(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)

        try {
            val startTime = System.nanoTime()

            val parser = MezzeParser()
            // parseWithRecovery: collect every parse error (synchronizes to
            // next top-level decl) so we can show them all at once and still
            // feed the partial ParsedModule into the typechecker.
            val parseResult = parser.parseWithRecovery(source, file = appFile.name)
            val ast = parseResult.module

            // Text mode prints parse errors first then continues; JSON mode collects all.
            if (!jsonMode && parseResult.errors.isNotEmpty()) {
                println(ParseErrorFormatter.formatAll(parseResult.errors))
            }

            val typeChecker = TypeChecker(strictTopLevel = true)
            // Run via the result-returning lower-level API so we can show
            // ALL collected errors (#218), not just the first one — sibling
            // declarations with independent failures used to be hidden.
            val rawResult = typeChecker.checkParsedModule(ast, currentDir.toPath(), enablePrelude = true)
            val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

            when (rawResult) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Failure -> {
                    if (jsonMode) {
                        val diags = parseResult.errors.map { JsonDiagnosticFormatter.fromParseException(it) } +
                                    rawResult.errors.map { JsonDiagnosticFormatter.fromTypeCheckError(it) }
                        jsonExitWith(null, appConfig.name, diags,
                            exitCode = if (parseResult.errors.isNotEmpty()) 3 else 4)
                    }
                    if (parseResult.errors.isNotEmpty()) println()
                    println(TypeCheckRunner.formatErrors(rawResult, source))
                    if (parseResult.errors.isNotEmpty()) {
                        // Re-print combined summary that includes parse counts.
                        // (formatErrors already prints "(N errors: N type)" when N > 1,
                        // but we need the parse breakdown too.)
                        val total = parseResult.errors.size + rawResult.errors.size
                        if (total > 1) {
                            val parts = buildList {
                                if (parseResult.errors.isNotEmpty()) add("${parseResult.errors.size} parse")
                                if (rawResult.errors.isNotEmpty()) add("${rawResult.errors.size} type")
                            }
                            println("\n($total errors: ${parts.joinToString(" + ")})")
                        }
                    }
                    kotlin.system.exitProcess(if (parseResult.errors.isNotEmpty()) 3 else 4)
                }
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> {
                    if (jsonMode) {
                        if (parseResult.errors.isNotEmpty()) {
                            jsonExitWith(null, appConfig.name,
                                parseResult.errors.map { JsonDiagnosticFormatter.fromParseException(it) },
                                exitCode = 3)
                        }
                        // Successful — fall through; we still want to populate the
                        // store + emit ok JSON below. Don't return here; the store
                        // population happens in the shared tail.
                    } else if (parseResult.errors.isNotEmpty()) {
                        // Parse errors but typecheck succeeded on the
                        // partial AST. The footer from
                        // ParseErrorFormatter.formatAll already says it;
                        // exit non-zero so callers see a failure.
                        kotlin.system.exitProcess(3)
                    }
                    /* fall through */
                }
            }

            // Convert Success to the TypeCheckOutput shape StorePopulator expects.
            val typeCheckResult = TypeCheckRunner.wrapSuccess(
                rawResult as com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success
            )

            // Entry-point acceptability is a CLI rule (EntryPointCheck): the entry
            // must be `{} -> {} <*>` — no user params, `{}` return, and NO unhandled
            // concrete effect (an unhandled effect has no handler at the entry and
            // leaks at runtime). `mez check` enforces the same rule as build/exec/dev,
            // so a leaky entry fails here rather than silently at runtime.
            val entryName = appConfig.entry
            val entryDecl = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>()
                ?.find { it.name == entryName }
            if (entryDecl != null) {
                EntryPointCheck.errorMessage(EntryPointCheck.check(entryDecl), entryName)?.let { msg ->
                    if (jsonMode) {
                        jsonExitWith(null, appConfig.name,
                            DiagnosticJson(Severity.ERROR, code = "V0501", message = msg), exitCode = 4)
                    }
                    println("Error: $msg")
                    kotlin.system.exitProcess(4)
                }
            }

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

            if (jsonMode) {
                emitJson(JsonDiagnosticFormatter.ok(null, appConfig.name, typeCheckResult.typedModule, emptyList()))
            } else if (quiet) {
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
            if (jsonMode) {
                jsonExitWith(null, appConfig.name, JsonDiagnosticFormatter.fromTypeCheckError(e), exitCode = 4)
            }
            println(ErrorFormatter.format(e, source))
            kotlin.system.exitProcess(4)
        } catch (e: com.mezze.language.typechecker.module.ModuleError) {
            if (jsonMode) {
                jsonExitWith(null, appConfig.name, JsonDiagnosticFormatter.fromModuleError(e), exitCode = 4)
            }
            println(ErrorFormatter.formatModuleError(e))
            kotlin.system.exitProcess(4)
        } catch (e: com.mezze.language.parser.ParseException) {
            if (jsonMode) {
                jsonExitWith(null, appConfig.name, JsonDiagnosticFormatter.fromParseException(e), exitCode = 3)
            }
            println(ParseErrorFormatter.format(e))
            kotlin.system.exitProcess(3)
        } finally {
            store.close()
        }
    }

    private fun printExplainHint(codes: List<com.mezze.language.parsedast.diagnostics.ErrorCode>) {
        val first = codes.firstOrNull { ExplainDocs.hasDoc(it) } ?: return
        println()
        println("For more information about this error, try `mez --explain ${first.display()}`.")
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
