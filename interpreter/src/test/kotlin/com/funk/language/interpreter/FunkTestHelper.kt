package com.funk.language.interpreter

import com.funk.language.ast.Program
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test helper to execute Funk programs using 100% Truffle (no FunkInterpreter).
 *
 * This replaces FunkInterpreter for tests, executing programs directly via
 * the GraalVM polyglot API and FunkLanguage.
 */
object FunkTestHelper {
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    /**
     * Execute a Funk program from source code
     */
    fun run(sourceCode: String, projectRoot: Path? = null): FunkValue {
        val root = projectRoot ?: Paths.get(System.getProperty("funk.project.root", "."))
        val sourcePath = root.resolve("__test__.funk").toString()

        // Clear module cache to ensure clean state for each test execution
        com.funk.language.typechecker.ModuleResolver.clearCache()

        // Create polyglot context
        Context.newBuilder("funk")
            .allowAllAccess(true)
            .build().use { context ->
                // Create source and execute via FunkLanguage
                val source = Source.newBuilder("funk", sourceCode, sourcePath)
                    .buildLiteral()

                context.eval(source)

                // Retrieve result from FunkLanguage storage
                return FunkLanguage.getLastExecutionResult() ?: FunkNull
            }
    }

    /**
     * Execute a pre-parsed and typechecked program
     */
    fun execute(program: Program, projectRoot: Path? = null): FunkValue {
        return execute(program, emptyMap(), projectRoot)
    }

    /**
     * Execute a program with monomorphic instances
     */
    fun execute(program: Program, monomorphicInstances: Map<String, com.funk.language.ast.LetBinding>, projectRoot: Path? = null): FunkValue {
        val root = projectRoot ?: Paths.get(System.getProperty("funk.project.root", "."))

        // Clear module cache to ensure clean state for each test execution
        com.funk.language.typechecker.ModuleResolver.clearCache()

        // Create polyglot context
        Context.newBuilder("funk")
            .allowAllAccess(true)
            .build().use { context ->
                // Execute the program directly by converting typed AST to Truffle nodes
                // This preserves all type information, monomorphization, etc.
                executeProgramDirectly(program, monomorphicInstances, context, root)

                // Retrieve result from FunkLanguage storage
                return FunkLanguage.getLastExecutionResult() ?: FunkNull
            }
    }

    /**
     * Execute a typed program directly without re-parsing
     */
    private fun executeProgramDirectly(program: Program, monomorphicInstances: Map<String, com.funk.language.ast.LetBinding>, context: Context, projectRoot: Path) {
        // We need to invoke the Truffle language to execute the AST
        // The trick is to eval a minimal source that will trigger language initialization,
        // then we can access the context and execute our AST

        // Actually, the cleaner approach is to use a dummy source that FunkLanguage.parse()
        // can intercept and replace with our pre-parsed AST

        // For now, let's use a workaround: store the program in a ThreadLocal and
        // have FunkLanguage.parse() check for it

        // Better approach: Just create a minimal Truffle program that evaluates our AST
        // We'll use the existing mechanism but with a special marker

        // Store the program and monomorphic instances for FunkLanguage to use
        FunkLanguage.setProgramForDirectExecution(program, projectRoot)
        FunkLanguage.setMonomorphicInstances(monomorphicInstances)

        // Trigger execution with a special marker source
        val source = org.graalvm.polyglot.Source.newBuilder("funk", "# DIRECT_AST_EXECUTION", "__direct_execution__.funk")
            .buildLiteral()
        context.eval(source)
    }

    /**
     * Execute a program and return both result and context
     */
    fun executeWithContext(program: Program, projectRoot: Path? = null): Pair<FunkValue, Map<String, FunkValue>> {
        return executeWithContext(program, emptyMap(), projectRoot)
    }

    /**
     * Execute a program with monomorphic instances and return both result and context
     */
    fun executeWithContext(program: Program, monomorphicInstances: Map<String, com.funk.language.ast.LetBinding>, projectRoot: Path? = null): Pair<FunkValue, Map<String, FunkValue>> {
        val result = execute(program, monomorphicInstances, projectRoot)
        val context = FunkLanguage.getLastExecutionContextGlobals()
        return Pair(result, context)
    }

    /**
     * Get the last execution context (for backward compatibility)
     */
    fun getLastExecutionContext(): Map<String, FunkValue> {
        return FunkLanguage.getLastExecutionContextGlobals()
    }

    // NOTE: programToSource() and related methods removed
    // We now execute typed ASTs directly without converting back to source
    // This preserves all type information, monomorphization, and optimizations
    //
    // The old approach of converting AST → source → re-parse was fundamentally flawed
    // because it lost all type information, monomorphization, and ability resolution.
    // Now we execute typed ASTs directly via FunkLanguage.setProgramForDirectExecution()
}
