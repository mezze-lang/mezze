package com.mezze.language.codebase

import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.compiler.TypedASTCompiler

/**
 * Compiles stdlib modules and stores the results in a SQLiteCodebase.
 *
 * Uses TypedASTCompiler (single-pass) to compile each module.
 */
object StdlibCompiler {

    fun compile(codebase: SQLiteCodebase): CompileResult {
        val v2Modules = PrecompiledStdlib.v2Modules
        val loadedModules = PrecompiledStdlib.loadedModules

        var typedModuleCount = 0
        var functionIRCount = 0

        for ((hash, module) in v2Modules) {
            val blob = CodebaseSerialization.serializeTypedModule(module)
            codebase.putStdlibCompiled(hash.hex, module.path, "typed_module", blob)
            typedModuleCount++
        }

        val allAbilityImpls = v2Modules.values.flatMap { it.abilityImpls }

        for ((path, v1Module) in loadedModules) {
            try {
                val v2Module = v1Module.v2TypedModule ?: continue

                val compiler = TypedASTCompiler(abilityImpls = allAbilityImpls)
                val result = compiler.compile(v2Module)

                for (fn in result.functions) {
                    if (!codebase.hasStdlibCompiled(fn.hash.hex)) {
                        val blob = CodebaseSerialization.serializeFunctionIR(fn)
                        codebase.putStdlibCompiled(fn.hash.hex, path, "function_ir", blob)
                        functionIRCount++
                    }
                }
            } catch (e: Exception) {
                System.err.println("[StdlibCompiler] Failed to compile $path: ${e.message}")
            }
        }

        return CompileResult(typedModuleCount, functionIRCount)
    }

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
