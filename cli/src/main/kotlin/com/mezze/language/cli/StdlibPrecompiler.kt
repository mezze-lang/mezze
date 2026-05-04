package com.mezze.language.cli

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.BlobKind
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.codebase.Visibility
import com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typedast.Hash
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.serializer
import java.io.File

/**
 * Build-time tool: pre-compiles all stdlib modules to FunctionIR and stores
 * them in a SQLite file that ships with the JAR.
 *
 * Run by Gradle during cli build:
 *   java -cp <classpath> com.mezze.language.typechecker.StdlibPrecompilerKt <output-path> <project-root>
 *
 * The output stdlib.sqlite contains:
 * - All stdlib FunctionIRs in the blobs table
 * - Performer records in metadata
 * - Function name mappings in metadata
 * - Dependency graph edges
 */
fun main(args: Array<String>) {
    val outputPath = args[0]
    val projectRoot = args.getOrElse(1) { "." }

    System.setProperty("mezze.project.root", projectRoot)
    // Force slow path so PrecompiledStdlib retains stdlibTypedModules /
    // loadedModules for the v7-row scatter below. Without this, an
    // existing ~/.mezze/cache/stdlib.sqlite would let the fast path win
    // and clear those maps to emptyMap() before we get to walk them.
    System.setProperty("mezze.stdlib.force-recompile", "1")

    val startTime = System.nanoTime()

    // Trigger full PrecompiledStdlib init (parse + typecheck + lower)
    val precompiled = PrecompiledStdlib
    val functions = precompiled.preloweredFunctions
    val performers = precompiled.preloweredPerformerRecords

    val initTime = (System.nanoTime() - startTime) / 1_000_000
    println("Stdlib compiled: ${functions.size} functions, ${performers.size} performers (${initTime}ms)")

    // Write to SQLite
    val outputFile = File(outputPath)
    outputFile.parentFile?.mkdirs()
    if (outputFile.exists()) outputFile.delete()

    val store = SQLiteMezzeStore.forFile(outputFile)
    try {
        // Slice C: write proper v7 rows per stdlib module so the project
        // cache seed pulls in modules / names / decls / typed_modules /
        // parsed_modules / imports / usages all keyed by category='stdlib'.
        // Loops StorePopulator over each loaded stdlib module — same scatter
        // path user code goes through, but with category=STDLIB and reading
        // from PrecompiledStdlib's already-loaded TypedModules instead of
        // re-typechecking.
        val stdlibParsed = ModuleResolver.getAllParsedModules()
            .filterKeys { it.startsWith(STDLIB_NAMESPACE_PREFIX) }
        // Iterate every cached typechecker module so we capture both the
        // 18 prelude-loaded ones and the ~5 effects/abilities pulled in
        // transitively. PrecompiledStdlib.loadedModules only has the
        // first set; ModuleTypeChecker.getAllCachedModules sees all.
        // Each entry's key IS the canonical module path (e.g. "std::int").
        val allCached = com.mezze.language.typechecker.module.ModuleTypeChecker
            .getAllCachedModules()
            .filterKeys { it.startsWith(STDLIB_NAMESPACE_PREFIX) }
        for ((modPath, v1) in allCached) {
            val typedMod = v1.typedModule ?: continue
            val source = readModuleSource(v1.filePath)
            val sourceHash = source?.let { sha256Hex(it) } ?: ""
            val parsed = stdlibParsed[modPath] ?: v1.parsedModule

            StorePopulator.populate(
                store,
                TypeCheckResult(typedModule = typedMod),
                modulePath = modPath,
                filePath = v1.filePath,
                sourceHash = sourceHash,
                entrySource = source,
                parsedModule = parsed
            )
        }

        store.transaction {
            // Merge IR bytes into the typed-only blobs StorePopulator just
            // wrote. Each function lookup updates ir on the existing row,
            // matching the user-code merge path in scatterFunctionIRs.
            for ((hash, fn) in functions) {
                val existing = store.getBlob(hash)
                val irBytes = CodebaseSerialization.serializeFunctionIR(fn)
                store.putBlob(
                    hash,
                    existing?.kind ?: BlobKind.FUNCTION,
                    existing?.visibility ?: Visibility.PUBLIC,
                    existing?.typed,
                    irBytes
                )
            }

            // Store performer records
            if (performers.isNotEmpty()) {
                store.setMetadata("performer_records",
                    performers.joinToString(";") { it.joinToString(",") })
            }

            // Store function name mappings. SQLite metadata keys/values
            // are strings — write `hash.hex` explicitly at this serialization
            // boundary; Hash.toString() truncates to 8 chars for readability.
            for ((hash, fn) in functions) {
                if (fn.name != "lambda" && !fn.name.startsWith("__")) {
                    store.setMetadata("fn_name:${hash.hex}", fn.name)
                }
            }

            // Store dependency graph
            for ((hash, deps) in precompiled.stdlibDependencyGraph) {
                if (deps.isNotEmpty()) {
                    store.setMetadata("deps:${hash.hex}", deps.joinToString(";") { it.hex })
                }
            }

            // Store prelude context (types, abilities, effects, bindings)
            // This eliminates the need to parse + typecheck stdlib at runtime.
            @OptIn(ExperimentalSerializationApi::class)
            run {
                val cborFmt = Cbor { ignoreUnknownKeys = true }

                val preludeBytes = cborFmt.encodeToByteArray(
                    serializer<com.mezze.language.typechecker.module.ModuleImportContext>(),
                    precompiled.preludeImportContext
                )
                store.setMetadata("prelude_import_context",
                    java.util.Base64.getEncoder().encodeToString(preludeBytes))

                val implBytes = cborFmt.encodeToByteArray(
                    serializer<List<com.mezze.language.typedast.TypedAbilityImpl>>(),
                    precompiled.stdlibAbilityImpls
                )
                store.setMetadata("stdlib_ability_impls",
                    java.util.Base64.getEncoder().encodeToString(implBytes))
            }

            // Store decl and module hashes
            store.setMetadata("stdlib_decl_hashes",
                precompiled.stdlibDeclHashes.joinToString(";") { it.hex })
            store.setMetadata("stdlib_module_hashes",
                precompiled.stdlibModuleHashes.joinToString(";") { it.hex })

            // Store metadata counts for verification
            store.setMetadata("stdlib_version", "2")
            store.setMetadata("function_count", functions.size.toString())
            store.setMetadata("performer_count", performers.size.toString())
        }

        val fileSize = outputFile.length() / 1024
        println("Wrote ${outputFile.name} (${fileSize}KB) with prelude context")
    } finally {
        store.close()
    }
}

/**
 * Read a stdlib module's source. The filePath PrecompiledStdlib carries
 * is a classpath-relative `stdlib/<...>.mz` (extracted via
 * `ModuleResolver.readStdlibFile`). When PrecompiledStdlib loads from
 * disk during build, it's an absolute filesystem path — try both.
 */
private fun readModuleSource(filePath: String?): String? {
    if (filePath == null) return null
    val asFile = File(filePath)
    if (asFile.exists()) return asFile.readText()
    return ModuleResolver.readStdlibFile(filePath)
}

private fun sha256Hex(s: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
