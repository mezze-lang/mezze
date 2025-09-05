package com.funk.language.compiler

import com.funk.language.typechecker.TypedProgram as TypecheckerTypedProgram

/**
 * Main compiler interface for the Funk language
 */
class FunkCompiler {
    
    private val minimalCompiler = MinimalCompiler()
    
    /**
     * Compile a typed program to the specified target
     */
    fun compile(
        typedProgram: TypecheckerTypedProgram,
        target: CompilationTarget,
        options: CompilerOptions = CompilerOptions()
    ): CompilationResult {
        return minimalCompiler.compile(typedProgram, target)
    }
}

/**
 * Compilation targets supported by the Funk compiler
 */
enum class CompilationTarget {
    JavaScript,
    JVM,
    LLVM,
    C
}

/**
 * Compiler configuration options
 */
data class CompilerOptions(
    val optimizationLevel: OptimizationLevel = OptimizationLevel.O1,
    val includeDebugInfo: Boolean = false,
    val outputFormat: OutputFormat = OutputFormat.SingleFile,
    val customOptions: Map<String, Any> = emptyMap()
)

enum class OptimizationLevel {
    O0, // No optimization
    O1, // Basic optimization
    O2, // Advanced optimization
    O3  // Aggressive optimization
}

enum class OutputFormat {
    SingleFile,
    MultipleFiles,
    Library
}

/**
 * Result of compilation
 */
sealed class CompilationResult {
    data class Success(
        val target: CompilationTarget,
        val code: String,
        val metadata: CompilationMetadata
    ) : CompilationResult()
    
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : CompilationResult()
}

/**
 * Metadata about the compilation process
 */
data class CompilationMetadata(
    val sourceItems: Int,
    val generatedLines: Int,
    val optimizationLevel: OptimizationLevel,
    val compilationTimeMs: Long = System.currentTimeMillis()
)
