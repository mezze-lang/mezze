package com.mezze.language.cli.standalone

import com.mezze.language.codebase.CodebaseLoader
import com.mezze.language.codebase.SQLiteCodebase
import com.mezze.interpreter.v2.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source

/**
 * Helper class for standalone JAR execution.
 * This class is compiled once in the CLI and included in every generated JAR.
 * It handles deserialization and execution of pre-compiled Mezze programs.
 */
object StandaloneHelper {

    /**
     * Execute from SQLite codebase bytes (new format).
     * The codebase is loaded entirely into memory for fast execution.
     */
    @JvmStatic
    fun executeFromCodebase(codebaseBytes: ByteArray): Any? {
        // Load codebase from bytes into in-memory SQLite
        val codebase = SQLiteCodebase.fromBytes(codebaseBytes)

        return codebase.use {
            // Load all modules
            val modules = CodebaseLoader.loadAllParsedModules(codebase)

            // Preload modules into TypeChecker cache
            TypeChecker.preloadModules(modules)

            // Re-type-check the main program to get TruffleIR (loweredFunctions)
            // The v2 pipeline handles module resolution and monomorphization.
            val typeChecker = TypeChecker()
            val projectRoot = java.nio.file.Paths.get(".")
            // Use the main program source to re-parse into a ParsedModule
            val parser = com.mezze.language.parser.MezzeParser()
            // mainProgram is a v1 Program stub (always empty items), so we
            // need to find the original source. For now, use an empty module
            // and rely on the preloaded modules.
            val parsedModule = parser.parseParsedModule("let main = {} -> {}", file = "<standalone>")
            val typeCheckResult = typeChecker.typeCheck(parsedModule, projectRoot)
            val functions = typeCheckResult.loweredFunctions

            // Execute using full Truffle with JIT optimization
            executeTruffleFunctions(functions)
        }
    }

    /**
     * Execute from JSON (legacy format, for backwards compatibility).
     * Note: v1 Program serialization is dead -- this re-parses via v2 pipeline.
     */
    @JvmStatic
    fun executeFromJson(astJson: String, @Suppress("UNUSED_PARAMETER") modulesJson: String): Any? {
        // Type-check using v2 pipeline — re-parse the serialized source
        val typeChecker = TypeChecker()
        val projectRoot = java.nio.file.Paths.get(".")
        val parser = com.mezze.language.parser.MezzeParser()
        val parsedModule = parser.parseParsedModule(astJson, file = "<standalone>")
        val typeCheckResult = typeChecker.typeCheck(parsedModule, projectRoot)
        val functions = typeCheckResult.loweredFunctions

        // Execute using full Truffle with JIT optimization
        return executeTruffleFunctions(functions)
    }

    private fun executeTruffleFunctions(functions: List<FunctionIR>): Any? {
        val engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .build()

        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowExperimentalOptions(true)
            .allowCreateThread(true)
            .build()

        return try {
            val projectRoot = java.nio.file.Paths.get(".")

            // Store TruffleIR functions for direct execution (bypasses parsing)
            MezzeLanguage.setFunctionsForDirectExecution(functions, projectRoot)

            // Create a dummy source to trigger MezzeLanguage.parse()
            val source = Source.newBuilder(
                MezzeLanguage.ID,
                "# Direct TruffleIR execution",
                "__direct_execution__.mz"
            ).buildLiteral()

            // Execute via polyglot API
            context.eval(source)

            // V2 returns unboxed values (Long, String, Boolean, Double) instead of MezzeInt, MezzeString, etc.
            // For standalone JARs, we just return null since main returns {}
            null
        } finally {
            // Use cancelIfExecuting=true for Go/Rust-style semantics:
            // When main exits, any fire-and-forget tasks (unawaited spawns) are cancelled.
            // This matches Go (goroutines die when main exits) and Rust (tokio tasks cancelled).
            // See: https://www.graalvm.org/truffle/javadoc/org/graalvm/polyglot/Context.html
            context.close(true)
            engine.close()
        }
    }
}
