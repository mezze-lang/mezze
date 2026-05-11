package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import java.io.File
import java.security.MessageDigest

/**
 * Handle `mez index` — build the full SQLite index for external tools.
 *
 * Parses, typechecks, and lowers all project files, then scatters everything
 * into .mz/cache.sqlite: blobs, names, edges, decls, usages, imports,
 * ability_impls, effect_performers.
 *
 * After `mez index`, external tools (linters, doc generators, visualizers)
 * can query .mz/cache.sqlite directly with SQL.
 */
class IndexCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val tomlFile = File(currentDir, "Mezze.toml")
        if (!tomlFile.exists()) {
            println("Error: No Mezze.toml found in ${currentDir.absolutePath}")
            println("Run `mez init` to create a project, or use `mezze` for script mode.")
            kotlin.system.exitProcess(1)
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlFile.readText())
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            kotlin.system.exitProcess(1)
        }

        val apps = manifest.app ?: emptyList()
        val lib = manifest.lib
        if (apps.isEmpty() && lib == null) {
            println("Error: No [lib] or [[app]] entries in Mezze.toml")
            kotlin.system.exitProcess(1)
        }

        val store = SQLiteMezzeStore.openProject(currentDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)

        val startTime = System.nanoTime()
        var totalFiles = 0
        var totalDecls = 0
        var totalErrors = 0

        // Collect all entry points: lib + apps
        data class IndexEntry(val name: String, val path: String)
        val entries = mutableListOf<IndexEntry>()
        if (lib != null) entries.add(IndexEntry(manifest.`package`.name, lib.path))
        for (app in apps) entries.add(IndexEntry(app.name, app.path))

        try {
            for (entry in entries) {
                val entryFile = File(currentDir, entry.path)
                if (!entryFile.exists()) {
                    println("Warning: File not found: ${entryFile.absolutePath}")
                    totalErrors++
                    continue
                }

                val source = entryFile.readText()
                val parser = MezzeParser()
                // parseWithRecovery: gather every parse error across the
                // app file in one pass, then still typecheck the partial AST.
                val parseResult = parser.parseWithRecovery(source, file = entryFile.name)
                val ast = parseResult.module
                if (parseResult.errors.isNotEmpty()) {
                    println("  ${entry.path}: ${parseResult.errors.size} parse error(s)")
                    totalErrors += parseResult.errors.size
                }

                val typeChecker = TypeChecker()
                val rawResult = typeChecker.checkParsedModule(ast, currentDir.toPath(), enablePrelude = true)

                when (rawResult) {
                    is TypeChecker.TypeCheckResult.Failure -> {
                        println("  ${entry.path}: ${rawResult.errors.size} type error(s)")
                        totalErrors += rawResult.errors.size
                    }
                    is TypeChecker.TypeCheckResult.Success -> {
                        val success = rawResult
                        val typeCheckResult = com.mezze.language.typechecker.TypeCheckResult(
                            resolvedImports = emptyList(),
                            warnings = success.warnings.map {
                                com.mezze.language.typechecker.TypeCheckWarning(it.message, it.loc)
                            },
                            importedAbilities = success.importContext?.importedAbilities ?: emptyMap(),
                            importedEffects = success.importContext?.importedEffects ?: emptyMap(),
                            loweredFunctions = success.loweredFunctions ?: emptyList(),
                            typedModule = success.typedModule,
                            performerRecords = success.loweredPerformers,
                            loadedModules = success.loadedModules
                                .mapValues { (_, m) -> m.typedModule }
                                .filterValues { it != null }
                                .mapValues { (_, v) -> v!! },
                            loadedModuleSources = success.loadedModules
                                .mapNotNull { (path, m) ->
                                    val fp = m.filePath ?: return@mapNotNull null
                                    val sh = m.sourceHash ?: return@mapNotNull null
                                    path to com.mezze.language.typechecker.LoadedModuleSource(fp, sh)
                                }.toMap()
                        )

                        val sourceHash = sha256(source)
                        val depParsedModules = com.mezze.language.typechecker.ModuleResolver.getAllParsedModules()

                        val delta = StorePopulator.populate(
                            store = store,
                            result = typeCheckResult,
                            modulePath = entry.name,
                            filePath = entryFile.absolutePath,
                            sourceHash = sourceHash,
                            entrySource = source,
                            parsedModule = ast,
                            loadedParsedModules = depParsedModules
                        )

                        val declCount = (typeCheckResult.typedModule?.decls?.size ?: 0) +
                            (typeCheckResult.typedModule?.abilityDecls?.size ?: 0) +
                            (typeCheckResult.typedModule?.abilityImpls?.size ?: 0) +
                            (typeCheckResult.typedModule?.effectDecls?.size ?: 0) +
                            (typeCheckResult.typedModule?.performerDecls?.size ?: 0)

                        totalDecls += declCount
                        totalFiles += 1 + depParsedModules.size

                        println("  ${entry.path}: ${declCount} declarations, ${delta.newHashes.size} new blobs")
                    }
                }
            }

            val totalTime = (System.nanoTime() - startTime) / 1_000_000

            println()
            println("Index complete:")
            println("  Files indexed: $totalFiles")
            println("  Declarations: $totalDecls")
            if (totalErrors > 0) println("  Errors: $totalErrors")
            println("  Time: ${totalTime}ms")
            println()
            println("Query the index: sqlite3 .mz/cache.sqlite")
            println("  SELECT * FROM decls;")
            println("  SELECT * FROM names WHERE module = 'mymodule';")
            println("  SELECT * FROM usages WHERE target_hash = '<hash>';")

        } finally {
            store.close()
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
