package com.mezze.language.interpreter

import com.mezze.language.ast.Program
import com.mezze.language.ast.ImplDeclaration
import com.mezze.language.ast.ImplAbilityDeclaration
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.hashing.ContentHashComputer
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test helper to execute Mezze programs using 100% Truffle (no MezzeInterpreter).
 *
 * This replaces MezzeInterpreter for tests, executing programs directly via
 * the GraalVM polyglot API and MezzeLanguage.
 *
 * IMPORTANT: Tests that use virtual threads (LoomConcurrencyTest, StructuredConcurrencyTest)
 * should use @Execution(ExecutionMode.SAME_THREAD) to prevent scheduler saturation.
 */
object MezzeTestHelper {
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    // Shared engine for all test executions to avoid sharing layer violations
    // This engine is reused across all test contexts to ensure CallTargets can be shared
    private val sharedEngine = org.graalvm.polyglot.Engine.newBuilder()
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /**
     * Execute a Mezze program from source code
     */
    fun run(sourceCode: String, projectRoot: Path? = null): MezzeValue {
        val root = projectRoot ?: Paths.get(System.getProperty("mezze.project.root", "."))
        val sourcePath = root.resolve("__test__.mz").toString()

        // Clear module cache to ensure clean state for each test execution
        com.mezze.language.typechecker.ModuleResolver.clearCache()

        // Create polyglot context with multi-threading enabled for Loom concurrency
        // Use the shared engine to avoid sharing layer violations
        Context.newBuilder("mezze")
            .engine(sharedEngine)
            .allowAllAccess(true)
            .allowCreateThread(true)  // Allow spawning threads
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL)
            .build().use { context ->
                // Create source and execute via MezzeLanguage
                val source = Source.newBuilder("mezze", sourceCode, sourcePath)
                    .buildLiteral()

                context.eval(source)

                // Retrieve result from MezzeLanguage storage
                return MezzeLanguage.getLastExecutionResult() ?: MezzeNull
            }
    }

    /**
     * Execute a pre-parsed and typechecked program
     */
    fun execute(program: Program, projectRoot: Path? = null): MezzeValue {
        return execute(program, emptyMap(), projectRoot)
    }

    /**
     * Execute a program with monomorphic instances
     */
    fun execute(program: Program, monomorphicInstances: Map<String, com.mezze.language.ast.LetBinding>, projectRoot: Path? = null): MezzeValue {
        val root = projectRoot ?: Paths.get(System.getProperty("mezze.project.root", "."))

        // Ensure the program has content hashes computed
        // This handles cases where tests pass raw parsed ASTs instead of type-checked programs
        val programWithHashes = ensureContentHashes(program)

        // NOTE: Do NOT clear module cache here!
        // The type-checker has already populated the cache with typed modules during type-checking.
        // Clearing it would break module imports since the interpreter expects modules to be in the typed cache.

        // Create polyglot context with multi-threading enabled for Loom concurrency
        // Use the shared engine to avoid sharing layer violations
        val context = Context.newBuilder("mezze")
            .engine(sharedEngine)
            .allowAllAccess(true)
            .allowCreateThread(true)  // Allow spawning threads
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL)
            .build()

        try {
            // Set the polyglot context in ThreadLocal for cross-thread Truffle execution
            TruffleContextHolder.setPolyglotContext(context)

            // Execute the program directly by converting typed AST to Truffle nodes
            // This preserves all type information, monomorphization, etc.
            executeProgramDirectly(programWithHashes, monomorphicInstances, context, root)

            // Retrieve result from MezzeLanguage storage
            return MezzeLanguage.getLastExecutionResult() ?: MezzeNull
        } finally {
            // Clear the context from ThreadLocal
            TruffleContextHolder.clearEnv()

            // Close context with cancelIfExecuting=true to properly terminate any lingering virtual threads
            // This prevents "The language did not complete all polyglot threads" errors
            context.close(true)
        }
    }

    /**
     * Execute a typed program directly without re-parsing
     */
    private fun executeProgramDirectly(program: Program, monomorphicInstances: Map<String, com.mezze.language.ast.LetBinding>, context: Context, projectRoot: Path) {
        // We need to invoke the Truffle language to execute the AST
        // The trick is to eval a minimal source that will trigger language initialization,
        // then we can access the context and execute our AST

        // Actually, the cleaner approach is to use a dummy source that MezzeLanguage.parse()
        // can intercept and replace with our pre-parsed AST

        // For now, let's use a workaround: store the program in a ThreadLocal and
        // have MezzeLanguage.parse() check for it

        // Better approach: Just create a minimal Truffle program that evaluates our AST
        // We'll use the existing mechanism but with a special marker

        // Store the program and monomorphic instances for MezzeLanguage to use
        MezzeLanguage.setProgramForDirectExecution(program, projectRoot)
        MezzeLanguage.setMonomorphicInstances(monomorphicInstances)

        // Trigger execution with a special marker source
        val source = org.graalvm.polyglot.Source.newBuilder("mezze", "# DIRECT_AST_EXECUTION", "__direct_execution__.mz")
            .buildLiteral()
        context.eval(source)
    }

    /**
     * Execute a program and return both result and context
     */
    fun executeWithContext(program: Program, projectRoot: Path? = null): Pair<MezzeValue, Map<String, MezzeValue>> {
        return executeWithContext(program, emptyMap(), projectRoot)
    }

    /**
     * Execute a program with monomorphic instances and return both result and context
     */
    fun executeWithContext(program: Program, monomorphicInstances: Map<String, com.mezze.language.ast.LetBinding>, projectRoot: Path? = null): Pair<MezzeValue, Map<String, MezzeValue>> {
        val result = execute(program, monomorphicInstances, projectRoot)
        val context = MezzeLanguage.getLastExecutionContextGlobals()
        return Pair(result, context)
    }

    /**
     * Get the last execution context (for backward compatibility)
     */
    fun getLastExecutionContext(): Map<String, MezzeValue> {
        return MezzeLanguage.getLastExecutionContextGlobals()
    }

    /**
     * Execute a program and then call a specific function within the same context
     * This avoids Truffle context sharing violations by adding the function call to the program AST
     */
    fun executeAndCallFunction(
        program: Program,
        functionName: String,
        arguments: MezzeRecord = MezzeRecord(emptyMap()),
        monomorphicInstances: Map<String, com.mezze.language.ast.LetBinding> = emptyMap(),
        projectRoot: Path? = null
    ): MezzeValue {
        // Create a modified program that includes calling the function
        // This way the function call happens within the same execution context as the function definition
        val modifiedItems = program.items.toMutableList()

        // Add a function call expression at the end
        // Convert arguments record to AST RecordLiteral
        val argumentsLiteral = com.mezze.language.ast.RecordLiteral(
            entries = arguments.fields.map { (name, value) ->
                com.mezze.language.ast.RecordFieldEntry(
                    name = name,
                    value = when (value) {
                        is MezzeInt -> com.mezze.language.ast.IntLiteral(value.value)
                        is MezzeString -> com.mezze.language.ast.StringLiteral(value.value)
                        is MezzeBool -> com.mezze.language.ast.BooleanLiteral(value.value)
                        else -> throw IllegalArgumentException("Unsupported argument type: ${value::class.simpleName}")
                    }
                )
            }
        )

        // Create a function call: functionName(arguments)
        val functionCall = com.mezze.language.ast.FunctionCall(
            function = com.mezze.language.ast.Identifier(functionName),
            arguments = argumentsLiteral
        )

        // Add the call as a top-level expression
        modifiedItems.add(com.mezze.language.ast.TopLevelExpression(functionCall))

        // Execute the modified program
        val modifiedProgram = com.mezze.language.ast.Program(modifiedItems)
        return execute(modifiedProgram, monomorphicInstances, projectRoot)
    }

    // NOTE: programToSource() and related methods removed
    // We now execute typed ASTs directly without converting back to source
    // This preserves all type information, monomorphization, and optimizations
    //
    // The old approach of converting AST → source → re-parse was fundamentally flawed
    // because it lost all type information, monomorphization, and ability resolution.
    // Now we execute typed ASTs directly via MezzeLanguage.setProgramForDirectExecution()

    /**
     * Ensure the program has content hashes computed for all declarations
     * AND that resolved abilities/handlers have implementation hashes.
     *
     * For programs from the TypeChecker, hashes are already computed and consistent.
     * For raw parsed programs (backward compatibility), we compute them fresh.
     */
    private fun ensureContentHashes(program: Program): Program {
        // Check if any effect handler or ability impl is missing a content hash
        val needsHashing = program.items.any { item ->
            when (item) {
                is ImplDeclaration -> item.contentHash == null
                is ImplAbilityDeclaration -> item.contentHash == null
                else -> false
            }
        }

        return if (needsHashing) {
            // Raw parsed program - compute hashes for all declarations
            // ResolvedAbility.implementationHash is now set directly by SubstitutionApplier,
            // so we only need to compute declaration hashes here
            ContentHashComputer.computeHashes(program).program
        } else {
            // Program from TypeChecker - hashes are already computed
            program
        }
    }
}
