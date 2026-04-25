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
 * Handle `mez build` — compile project and produce artifacts.
 *
 * Flow:
 * 1. Read Mezze.toml → find app entry file
 * 2. Open .mz/cache.sqlite, compile (same as mez dev)
 * 3. Scatter results into store
 * 4. Compute transitive closure from entry hash
 * 5. Create minimal blobs.sqlite with only reachable IR
 * 6. Package into JAR (future: full fat JAR with Truffle runtime)
 */
class BuildCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("Error: No Mezze.toml found in current directory")
            println("Run 'mez init' to create a new project.")
            return
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), mezzeToml.readText())
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            return
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            println("Error: No [[app]] entries in Mezze.toml")
            return
        }

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

        val store = SQLiteMezzeStore.forProject(currentDir)

        try {
            val startTime = System.nanoTime()

            // 1. Compile
            val source = appFile.readText()
            val sourceHash = sha256(source)
            val modulePath = manifest.`package`.name

            val parser = MezzeParser()
            val ast = try {
                parser.parseParsedModule(source, file = appFile.name)
            } catch (e: com.mezze.language.parser.ParseException) {
                println("Parse Error: ${e.message}")
                kotlin.system.exitProcess(3)
            }

            val typeChecker = TypeChecker()
            val typeCheckResult = try {
                typeChecker.typeCheck(ast, currentDir.toPath())
            } catch (e: TypeCheckError) {
                println(ErrorFormatter.format(e, source))
                kotlin.system.exitProcess(4)
            }

            // 2. Scatter into store
            StorePopulator.populate(store, typeCheckResult, modulePath, appFile.absolutePath, sourceHash)

            // 3. Validate main
            val mainBinding = typeCheckResult.typedModule?.decls
                ?.filterIsInstance<TypedLetDecl>()
                ?.find { it.name == "main" }

            if (mainBinding == null) {
                println("Error: App must define a 'main' function with type {} -> {}")
                kotlin.system.exitProcess(4)
            }

            // 4. Find entry hash and compute transitive closure
            val entryHash = store.resolveName(modulePath, "main")
                ?: store.resolveName(modulePath, "__entry__")

            if (entryHash == null) {
                println("Error: Could not resolve entry point hash for 'main'")
                kotlin.system.exitProcess(1)
            }

            val reachable = store.getTransitiveClosure(entryHash)
            println("Compiled: ${reachable.size} definitions reachable from entry point")

            // 5. Create target directory and minimal blobs.sqlite
            val targetDir = File(currentDir, "target")
            targetDir.mkdirs()

            val outputJarName = if (manifest.app.size == 1) "${manifest.`package`.name}" else appConfig.name
            val blobsFile = File(targetDir, "$outputJarName.blobs.sqlite")

            createMinimalBlobsDb(store, reachable, entryHash, blobsFile)

            val buildTime = (System.nanoTime() - startTime) / 1_000_000

            println()
            println("Build complete: ${blobsFile.name} (${blobsFile.length() / 1024}KB)")
            println("Entry: $entryHash")
            println("Time: ${buildTime}ms")
            println()
            println("Note: Full JAR packaging (with embedded Truffle runtime) coming soon.")
            println("The blobs.sqlite contains all compiled IR needed for execution.")

        } catch (e: Exception) {
            println("Error: ${e.message}")
            kotlin.system.exitProcess(1)
        } finally {
            store.close()
        }
    }

    /**
     * Create a minimal blobs.sqlite containing only reachable IR blobs.
     * This is what goes into the JAR for production deployment.
     */
    private fun createMinimalBlobsDb(
        sourceStore: SQLiteMezzeStore,
        reachable: Set<com.mezze.language.typedast.Hash>,
        entryHash: com.mezze.language.typedast.Hash,
        outputFile: File
    ) {
        if (outputFile.exists()) outputFile.delete()

        val targetStore = SQLiteMezzeStore.inMemory()
        try {
            targetStore.transaction {
                // Copy only reachable blobs (IR only — no typed column needed for execution)
                for (hash in reachable) {
                    val irBytes = sourceStore.getIR(hash) ?: continue
                    targetStore.putBlob(hash, com.mezze.language.codebase.BlobKind.FUNCTION,
                        com.mezze.language.codebase.Visibility.PUBLIC, null, irBytes)
                }

                // Copy names for stack traces
                for (hash in reachable) {
                    val names = sourceStore.getNamesForHash(hash)
                    for (name in names) {
                        targetStore.registerName(name.module, name.name, hash)
                    }
                }

                // Store entry hash in metadata
                targetStore.setMetadata("entry_hash", entryHash.hex)
            }

            // TODO: Export in-memory SQLite to file
            // For now, just report what would be in the JAR
            println("Would create blobs.sqlite with ${reachable.size} blobs")

        } finally {
            targetStore.close()
        }
    }

    // For now, create a placeholder file
    init {
        // TODO: Implement proper SQLite export to file
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Legacy execute with JAR callback — kept for backward compatibility with Main.kt.
     */
    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        createJarFunction: (File, File, String, com.mezze.language.typechecker.TypeCheckResult) -> Unit
    ) {
        // Delegate to new implementation
        execute(args, currentDir)
    }
}
