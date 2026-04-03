package com.mezze.language.codebase

import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeCheckerV2
import com.mezze.language.typechecker.mono.Lowerer
import com.mezze.language.typechecker.mono.Monomorphizer
import com.mezze.language.typechecker.mono.Resolver
import com.mezze.language.typedast.TypedModule

/**
 * Compiles stdlib modules and stores the results in a SQLiteCodebase.
 *
 * This runs the full v2 pipeline (TypedModule → Mono → Resolve → Lower → FunctionIR)
 * on all prelude modules and stores the serialized artifacts. At runtime,
 * PrecompiledStdlib can load these instead of re-parsing and typechecking.
 *
 * Usage:
 *   StdlibCompiler.compile(codebase)     // stores in SQLite
 *   StdlibCompiler.compileToFile(path)   // creates a new .sqlite file
 */
object StdlibCompiler {

    /**
     * Compile all stdlib modules and store in the given codebase.
     * @return Number of TypedModules and FunctionIRs stored
     */
    fun compile(codebase: SQLiteCodebase): CompileResult {
        // Get pre-loaded stdlib modules from PrecompiledStdlib
        val v2Modules = PrecompiledStdlib.v2Modules
        val loadedModules = PrecompiledStdlib.loadedModules

        var typedModuleCount = 0
        var functionIRCount = 0

        // Store all v2 TypedModules
        for ((hash, module) in v2Modules) {
            val blob = CodebaseSerialization.serializeTypedModule(module)
            codebase.putStdlibCompiled(hash.hex, module.path, "typed_module", blob)
            typedModuleCount++
        }

        // For each stdlib module, run the monomorphizer pipeline to get FunctionIR
        // We compile each module as if it were the entry point
        val adapter = TypeCheckerV2()
        val allModules = HashMap(v2Modules)

        for ((path, v1Module) in loadedModules) {
            try {
                val v2Module = allModules.values.find { it.path == path }
                    ?: adapter.convertV1TypedModule(path, v1Module).also { allModules[it.hash] = it }

                val wrapped = com.mezze.language.typechecker.emit.EntryFunctionBuilder.wrapTopLevel(v2Module)
                val mono = Monomorphizer().monomorphize(wrapped, allModules)
                val resolved = Resolver().resolve(mono)
                val lowered = Lowerer().lower(resolved)

                for (fn in lowered.functions) {
                    if (!codebase.hasStdlibCompiled(fn.hash.hex)) {
                        val blob = CodebaseSerialization.serializeFunctionIR(fn)
                        codebase.putStdlibCompiled(fn.hash.hex, path, "function_ir", blob)
                        functionIRCount++
                    }
                }
            } catch (e: Exception) {
                // Skip modules that fail to compile — they'll be compiled on-demand
                System.err.println("[StdlibCompiler] Failed to compile $path: ${e.message}")
            }
        }

        return CompileResult(typedModuleCount, functionIRCount)
    }

    /**
     * Compile stdlib and store to a new SQLite file.
     */
    fun compileToFile(outputPath: java.io.File): CompileResult {
        val codebase = SQLiteCodebase.open(outputPath)
        try {
            return compile(codebase)
        } finally {
            codebase.close()
        }
    }

    data class CompileResult(
        val typedModules: Int,
        val functionIRs: Int
    )
}
