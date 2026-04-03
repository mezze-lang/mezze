package com.mezze.language.codebase

import java.io.File

/**
 * CLI entry point for stdlib compilation.
 * Called by the Gradle `compileStdlib` task.
 *
 * Usage: StdlibCompilerCLI <output-path>
 */
object StdlibCompilerCLI {
    @JvmStatic
    fun main(args: Array<String>) {
        val outputPath = if (args.isNotEmpty()) File(args[0]) else File("build/stdlib-compiled.sqlite")
        outputPath.parentFile?.mkdirs()
        if (outputPath.exists()) outputPath.delete()

        println("Compiling stdlib to ${outputPath.absolutePath}...")
        val result = StdlibCompiler.compileToFile(outputPath)
        println("Done: ${result.typedModules} TypedModules, ${result.functionIRs} FunctionIRs")
        println("Output: ${outputPath.absolutePath} (${outputPath.length() / 1024} KB)")
    }
}
