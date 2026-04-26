package com.mezze.language.cli

import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.BlobKind
import com.mezze.language.codebase.Visibility
import com.mezze.language.typechecker.PrecompiledStdlib
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
        store.transaction {
            // Store all FunctionIRs
            for ((hash, fn) in functions) {
                val irBytes = CodebaseSerialization.serializeFunctionIR(fn)
                store.putBlob(Hash(hash), BlobKind.FUNCTION, Visibility.PUBLIC, null, irBytes)
            }

            // Store performer records
            if (performers.isNotEmpty()) {
                store.setMetadata("performer_records",
                    performers.joinToString(";") { it.joinToString(",") })
            }

            // Store function name mappings
            for ((hash, fn) in functions) {
                if (fn.name != "lambda" && !fn.name.startsWith("__")) {
                    store.setMetadata("fn_name:$hash", fn.name)
                }
            }

            // Store dependency graph
            for ((hash, deps) in precompiled.stdlibDependencyGraph) {
                if (deps.isNotEmpty()) {
                    store.setMetadata("deps:$hash", deps.joinToString(";"))
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
                precompiled.stdlibDeclHashes.joinToString(";"))
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
