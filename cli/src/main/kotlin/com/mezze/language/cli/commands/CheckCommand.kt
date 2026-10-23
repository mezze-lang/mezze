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
        // `--dump-constraints`: print each binding's constraints, its own solve,
        // and the final substitution. `ConstraintDumper` already did this behind
        // a JVM system property, which meant knowing the property exists and
        // getting it into the right JVM; the check runs the typechecker
        // in-process, so the flag is the property.
        if (args.contains("--dump-constraints")) {
            System.setProperty("mezze.dump.constraints", "true")
        }
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

    /** Run [block], printing what it prints, and return that too. */
    private fun printed(block: () -> Unit): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        val text = buffer.toString(Charsets.UTF_8)
        print(text)
        return text
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
            // Checking needs types, not IR: lowering is for build and dev.
            com.mezze.language.typechecker.Lowering.enabled = false
            val result = typeChecker.checkParsedModule(parsedModule, projectRoot, enablePrelude = true)
            val typeCheckTime = (System.nanoTime() - typeCheckStart) / 1_000_000.0
            val totalTime = (System.nanoTime() - startTime) / 1_000_000.0

            when (result) {
                is com.mezze.language.typechecker.TypeChecker.TypeCheckResult.Success -> {
                    val typedModule = result.typedModule
                        ?: throw TypeCheckError("the typecheck pipeline did not emit a TypedModule")
                    if (jsonMode) {
                        if (parseResult.errors.isNotEmpty()) {
                            jsonExitWith(absPath, null,
                                parseResult.errors.map { JsonDiagnosticFormatter.fromParseException(it) },
                                exitCode = 3)
                        }
                        emitJson(JsonDiagnosticFormatter.ok(absPath, null, typedModule, result.warnings))
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
                    printWarnings(result.warnings, source)
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

    private fun printWarnings(warnings: List<com.mezze.language.typechecker.TypeChecker.TypeCheckWarning>, source: String) {
        for (w in warnings) {
            println(com.mezze.language.langservice.printing.ErrorFormatter.formatWarning(w, source))
            println()
        }
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

        // Resolve the project root by walking up to the nearest Mezze.toml, so
        // `mez check` works from any subdirectory and the .mez/ store lands at the
        // root (see ProjectRoot). Falls back to cwd only for the no-project error.
        val root = ProjectRoot.discover(currentDir) ?: currentDir
        val mezzeToml = File(root, "Mezze.toml")
        if (!mezzeToml.exists()) {
            bail("No Mezze.toml in ${currentDir.absolutePath} or any parent. " +
                "Run 'mez init' to create a project, or 'mez check <file>' for a single file.", 1)
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

        val appFile = File(root, appConfig.path)
        if (!appFile.exists()) {
            bail("App file not found: ${appFile.absolutePath}", 1)
        }

        val source = appFile.readText()
        // `check` checks: it indexes nothing (that is `mez index`), and opens the
        // project store only to keep what it derives across runs — macro
        // expansions, checked modules — the first time it reads or keeps one.
        // Opening it (SQLite, the native library, schema checks) costs more than
        // checking a small module, so a check that keeps nothing never does.
        // Project modules that do not import each other are checked at once.
        // MEZZE_CHECK_THREADS=1 checks them one at a time.
        com.mezze.language.typechecker.module.ParallelModuleChecker.threads =
            System.getenv("MEZZE_CHECK_THREADS")?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()
        val cacheStore = lazy { ProjectStore.openInMemory(root) }
        com.mezze.language.typechecker.ModuleResolver.cacheStore = cacheStore
        // `check` ends in exitProcess on most paths; the hook runs on all of them.
        Runtime.getRuntime().addShutdownHook(Thread {
            com.mezze.language.typechecker.module.CheckedModuleCache.flush()
            if (cacheStore.isInitialized()) cacheStore.value.close()
        })

        // Nothing changed since the last check that passed: say so again. Only
        // where a check has kept something before — a one-file program has no
        // store, and checks faster than opening one.
        val manifestText = mezzeToml.readText()
        if (!jsonMode && com.mezze.language.store.ProjectLayout.storeDir(root).exists()) {
            val replayStart = System.nanoTime()
            CheckReplay.replay(cacheStore.value, root, appConfig.name, quiet, manifestText)?.let { output ->
                print(output)
                val ms = String.format("%.1f", (System.nanoTime() - replayStart) / 1_000_000.0)
                println(if (quiet) "OK (${ms}ms)" else "Check passed: ${appConfig.name} (${ms}ms)")
                return
            }
        }
        /** What a passing check printed, kept for [CheckReplay] where there is a store. */
        fun remember(output: String) {
            if (!cacheStore.isInitialized()) return
            val read = com.mezze.language.typechecker.ModuleResolver.snapshot()
                .filterKeys { !com.mezze.language.ast.ModulePathConventions.isStdlib(it) }
                .values.map { File(it.filePath) }
            CheckReplay.record(cacheStore.value, root, appConfig.name, quiet, manifestText, read + appFile, output)
        }

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
            com.mezze.language.typechecker.Lowering.enabled = false
            val rawResult = com.mezze.language.typechecker.PhaseTiming.phase("app", "check-total") {
                typeChecker.checkParsedModule(ast, root.toPath(), enablePrelude = true)
            }
            val totalTime = (System.nanoTime() - startTime) / 1_000_000.0
            if (com.mezze.language.typechecker.PhaseTiming.on) {
                val all = com.mezze.language.typechecker.module.ModuleTypeChecker.getAllCachedModules()
                    .filterKeys { !it.startsWith("std::") }.values
                System.err.println("[timing] bodies read: ${all.count { it.bodyRead }} of ${all.size}")
            }

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
            // A MISSING entry is part of that same rule, and `check` used to skip
            // its whole entry block when the decl was absent. This path is
            // app-only: `check` already bailed above when the manifest has no
            // `[[app]]`, so a library never reaches it.
            val entryName = appConfig.entry
            val decls = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>().orEmpty()
            EntryPointCheck.entryError(decls, entryName, typeCheckResult.metadataByHash)?.let { msg ->
                if (jsonMode) {
                    jsonExitWith(null, appConfig.name,
                        DiagnosticJson(Severity.ERROR, code = "V0501", message = msg), exitCode = 4)
                }
                println("Error: $msg")
                kotlin.system.exitProcess(4)
            }

            if (jsonMode) {
                emitJson(JsonDiagnosticFormatter.ok(null, appConfig.name, typeCheckResult.typedModule, rawResult.warnings))
            } else if (quiet) {
                val shown = printed { printWarnings(rawResult.warnings, source) }
                println("OK (${String.format("%.1f", totalTime)}ms)")
                remember(shown)
            } else {
                val shown = printed {
                    printWarnings(rawResult.warnings, source)
                    val typedModule = typeCheckResult.typedModule
                    if (typedModule != null) {
                        printTypedModuleSummary(typedModule)
                    }
                    println()
                }
                println("Check passed: ${appConfig.name} (${String.format("%.1f", totalTime)}ms)")
                remember(shown)
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
