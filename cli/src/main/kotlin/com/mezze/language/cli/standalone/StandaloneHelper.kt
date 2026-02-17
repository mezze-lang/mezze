package com.mezze.language.cli.standalone

import com.mezze.language.ast.*
import com.mezze.language.interpreter.MezzeInterpreter
import com.mezze.language.typechecker.TypeChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine

/**
 * Helper class for standalone JAR execution.
 * This class is compiled once in the CLI and included in every generated JAR.
 * It handles deserialization and execution of pre-compiled Mezze programs.
 */
object StandaloneHelper {
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

        val context = Context.newBuilder("mezze")
            .engine(engine)
            .allowAllAccess(true)
            .allowExperimentalOptions(true)
            .build()

        return try {
            val interpreter = MezzeInterpreter(skipParserSetup = false)
            val projectRoot = java.nio.file.Paths.get(".")
            val monomorphicInstances = emptyMap<String, LetBinding>()
            interpreter.execute(program, projectRoot, monomorphicInstances)
            null
        } finally {
            context.close()
            engine.close()
        }
    }
}
