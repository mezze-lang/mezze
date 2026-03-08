package com.mezze.language.cli.standalone

import com.mezze.language.ast.*
import com.mezze.language.codebase.CodebaseLoader
import com.mezze.language.codebase.SQLiteCodebase
import com.mezze.interpreter.v2.runtime.MezzeLanguage
import com.mezze.language.typechecker.TypeChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
            // Load all modules and main program
            val (mainProgram, modules) = CodebaseLoader.loadAll(codebase)

            // Preload modules into TypeChecker cache
            TypeChecker.preloadModules(modules)

            // Build a combined program that includes all module items first,
            // then the main program items, then the main() call.
            // This ensures effect handlers from modules are registered before use.
            val allModuleItems = modules.values.flatMap { it.items }

            // Add a call to main() at the end of the program
            val mainCall = FunctionCall(
                function = Identifier("main", null, null),
                arguments = RecordLiteral(emptyList(), null, null, null),
                inferredType = null,
                sourceLocation = null
            )

            val combinedProgram = Program(
                items = allModuleItems + mainProgram.items + listOf(TopLevelExpression(mainCall))
            )

            // Execute using full Truffle with JIT optimization
            executeTruffleProgram(combinedProgram)
        }
    }

    /**
     * Execute from JSON (legacy format, for backwards compatibility).
     */
    @JvmStatic
    fun executeFromJson(astJson: String, modulesJson: String): Any? {
        val json = Json { ignoreUnknownKeys = true }

        // Deserialize pre-parsed modules
        val cachedModules = json.decodeFromString(
            MapSerializer(String.serializer(), Program.serializer()),
            modulesJson
        )
        TypeChecker.preloadModules(cachedModules)

        // Deserialize the enhanced AST
        val enhancedProgram = json.decodeFromString(Program.serializer(), astJson)

        // Add a call to main() at the end of the program
        val mainCall = FunctionCall(
            function = Identifier("main", null, null),
            arguments = RecordLiteral(emptyList(), null, null, null),
            inferredType = null,
            sourceLocation = null
        )
        val programWithMainCall = enhancedProgram.copy(
            items = enhancedProgram.items + listOf(TopLevelExpression(mainCall))
        )

        // Execute using full Truffle with JIT optimization
        return executeTruffleProgram(programWithMainCall)
    }

    private fun executeTruffleProgram(program: Program): Any? {
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

            // Store the program for direct execution (bypasses parsing)
            MezzeLanguage.setProgramForDirectExecution(program, projectRoot)

            // Create a dummy source to trigger MezzeLanguage.parse()
            val source = Source.newBuilder(
                MezzeLanguage.ID,
                "# Direct AST execution",
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
