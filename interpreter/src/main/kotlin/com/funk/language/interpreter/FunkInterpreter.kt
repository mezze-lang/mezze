package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import com.funk.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Simplified Funk interpreter that delegates all execution to Truffle via GraalVM polyglot API.
 *
 * This class is now a thin wrapper that:
 * - Provides backward-compatible API for tests and CLI
 * - Delegates all actual execution to FunkLanguage (100% Truffle)
 * - No map-based evaluation logic
 *
 * All programs now execute through optimized Truffle nodes with full JIT compilation.
 */
class FunkInterpreter {
    private val parser: FunkParser?
    private val typeChecker: TypeChecker?

    // GraalVM polyglot context for executing Funk programs
    private val polyglotContext: Context

    /**
     * Constructor for full parsing/typechecking (used by funk exec)
     */
    constructor() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        polyglotContext = createPolyglotContext()
    }

    /**
     * Constructor for pre-processed AST (used by compiled JARs and tests)
     */
    constructor(skipParserSetup: Boolean) {
        if (skipParserSetup) {
            parser = null
            typeChecker = null
        } else {
            parser = FunkParser()
            typeChecker = TypeChecker()
        }
        polyglotContext = createPolyglotContext()
    }

    /**
     * Create a GraalVM polyglot context for Funk execution
     */
    private fun createPolyglotContext(): Context {
        return Context.newBuilder("funk")
            .allowAllAccess(true)
            .build()
    }

    /**
     * Parse, typecheck, and execute a Funk program from source code
     */
    fun run(source: String): FunkValue {
        require(parser != null && typeChecker != null) {
            "Parser and typechecker not available. Use constructor without skipParserSetup for source code execution."
        }
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        return execute(typeCheckResult.program, Paths.get("."), typeCheckResult.monomorphicInstances)
    }

    /**
     * Execute a pre-parsed and typechecked program directly
     */
    fun execute(program: Program): FunkValue {
        return executeViaTruffle(program, null, emptyMap())
    }

    /**
     * Execute a pre-parsed and typechecked program with module resolution support
     */
    fun execute(program: Program, projectRoot: Path, monomorphicInstances: Map<String, LetBinding> = emptyMap()): FunkValue {
        return executeViaTruffle(program, projectRoot, monomorphicInstances)
    }

    /**
     * Public method for testing - executes a program and returns both result and context
     */
    fun executeWithContext(program: Program): Pair<FunkValue, Map<String, FunkValue>> {
        val result = executeViaTruffle(program, null)
        val context = getLastExecutionContext()
        return Pair(result, context)
    }

    /**
     * Execute a program using 100% Truffle via the polyglot API.
     * This delegates to FunkLanguage which converts to Truffle nodes.
     */
    private fun executeViaTruffle(program: Program, projectRoot: Path?, monomorphicInstances: Map<String, LetBinding> = emptyMap()): FunkValue {
        // CRITICAL: Use direct AST execution to preserve type information and resolved abilities!
        // Converting AST -> source -> re-parse loses all type metadata
        val root = projectRoot ?: Paths.get(".")

        // Store the program for direct execution (preserves all type information and resolved abilities)
        FunkLanguage.setProgramForDirectExecution(program, root)

        // CRITICAL: Store monomorphic instances for FunkLanguage to register
        // These are compiler-generated specializations (e.g., "add$Int") that
        // must be available at runtime WITHOUT going through the parser
        if (monomorphicInstances.isNotEmpty()) {
            FunkLanguage.setMonomorphicInstances(monomorphicInstances)
        }

        // Create a dummy source to trigger FunkLanguage.parse()
        // FunkLanguage will use the pre-parsed program instead of parsing this source
        val source = Source.newBuilder("funk", "# Direct AST execution", "__direct_execution__.funk")
            .buildLiteral()

        // Execute via polyglot API - FunkLanguage will use the pre-parsed program
        polyglotContext.eval(source)

        // Retrieve the result from FunkLanguage's storage
        // FunkLanguage stores results via FunkLanguage.storeLastExecutionResult()
        return FunkLanguage.getLastExecutionResult() ?: FunkNull
    }

    /**
     * Convert a Program AST back to source code.
     * This is a simple serialization - FunkLanguage will re-parse and typecheck.
     *
     * Note: This is not ideal for performance but maintains backward compatibility.
     * In the future, we could serialize the typed AST directly to avoid re-parsing.
     */
    private fun programToSource(program: Program): String {
        val sb = StringBuilder()

        for (item in program.items) {
            when (item) {
                is LetBinding -> {
                    sb.append("let ${item.variableName} = ")
                    sb.append(expressionToSource(item.expression))
                    sb.append("\n")
                }
                is TopLevelExpression -> {
                    sb.append(expressionToSource(item.expression))
                    sb.append("\n")
                }
                is UseDeclaration -> {
                    val path = when (val p = item.path) {
                        is ModulePath -> p.segments.joinToString("::")
                        is SelectivePath -> {
                            val modulePath = p.module.joinToString("::")
                            val items = p.items.joinToString(", ") { importItem ->
                                if (importItem.alias != null) {
                                    "${importItem.name} as ${importItem.alias}"
                                } else {
                                    importItem.name
                                }
                            }
                            "$modulePath::{$items}"
                        }
                    }
                    sb.append("use $path")
                    if (item.alias != null) {
                        sb.append(" as ${item.alias}")
                    }
                    sb.append("\n")
                }
                is PubUseDeclaration -> {
                    val path = when (val p = item.path) {
                        is ModulePath -> p.segments.joinToString("::")
                        is SelectivePath -> {
                            val modulePath = p.module.joinToString("::")
                            val items = p.items.joinToString(", ") { importItem ->
                                if (importItem.alias != null) {
                                    "${importItem.name} as ${importItem.alias}"
                                } else {
                                    importItem.name
                                }
                            }
                            "$modulePath::{$items}"
                        }
                    }
                    sb.append("pub use $path")
                    if (item.alias != null) {
                        sb.append(" as ${item.alias}")
                    }
                    sb.append("\n")
                }
                is AbilityDeclaration -> {
                    sb.append("ability ${item.name}")
                    // Add type parameters
                    if (item.typeParams.isNotEmpty()) {
                        sb.append(" ${item.typeParams.joinToString(" ")}")
                    }
                    sb.append(" where {\n")
                    // Add operations
                    for (op in item.operations) {
                        sb.append("  ${op.name} : ${typeToSource(op.paramType)} -> ${typeToSource(op.returnType)}\n")
                    }
                    sb.append("}\n")
                }
                is ImplAbilityDeclaration -> {
                    sb.append("impl ${item.abilityName}")
                    // Add target types
                    if (item.targetTypes.isNotEmpty()) {
                        sb.append(" ${item.targetTypes.joinToString(" ") { typeToSource(it) }}")
                    }
                    sb.append(" where {\n")
                    // Add operation implementations
                    for (op in item.operations) {
                        sb.append("  ${op.name} : ")
                        // Include type annotation if present (using local vars to avoid smart cast issues)
                        val paramType = op.paramType
                        val returnType = op.returnType
                        if (paramType != null && returnType != null) {
                            sb.append("${typeToSource(paramType)} -> ${typeToSource(returnType)} = ")
                        }
                        sb.append(expressionToSource(op.implementation))
                        sb.append("\n")
                    }
                    sb.append("}\n")
                }
                // Type declarations, effects - preserved but not executed
                else -> {
                    // Skip other non-executable items
                    // FunkLanguage will handle these during parsing
                }
            }
        }

        return sb.toString()
    }

    /**
     * Convert an expression AST to source code (simplified)
     */
    private fun expressionToSource(expr: Expression): String {
        return when (expr) {
            is IntLiteral -> expr.value.toString()
            is NatLiteral -> expr.value.toString()
            is FloatLiteral -> expr.value.toString()
            is StringLiteral -> "\"${expr.value}\""
            is BooleanLiteral -> expr.value.toString()
            is Identifier -> expr.name
            is FunctionCall -> {
                val func = expressionToSource(expr.function)
                val args = expressionToSource(expr.arguments)
                "$func $args"
            }
            is RecordLiteral -> {
                val fields = expr.entries.joinToString(", ") { entry ->
                    when (entry) {
                        is RecordFieldEntry -> "${entry.name} = ${entry.value?.let { expressionToSource(it) } ?: entry.name}"
                        is RecordSpreadEntry -> "...${expressionToSource(entry.expression)}"
                    }
                }
                "{$fields}"
            }
            is LambdaExpression -> {
                val params = when (val p = expr.parameters) {
                    is UntypedParameters -> p.names.joinToString(", ")
                    is TypedParameters -> p.recordType.entries.joinToString(", ") { "${it.name}: ${typeToSource(it.type)}" }
                }
                "{$params} -> ${expressionToSource(expr.body)}"
            }
            is DoExpression -> {
                "do\n${expr.statements.joinToString("\n") { statementToSource(it) }}\n${expressionToSource(expr.result)}"
            }
            is IfExpression -> {
                "if ${expressionToSource(expr.condition)} then ${expressionToSource(expr.thenBranch)} else ${expressionToSource(expr.elseBranch)}"
            }
            is PerformExpression -> {
                val handlersStr = expr.handlers.joinToString(" in ") { it.name }
                "perform $handlersStr in ${expressionToSource(expr.expression)}"
            }
            is MemberAccess -> {
                "${expressionToSource(expr.receiver)}.${expr.member}"
            }
            // Add more cases as needed for completeness
            else -> {
                // Fallback: return a placeholder
                // In practice, most programs use common expression types above
                "/* unsupported expression: ${expr::class.simpleName} */"
            }
        }
    }

    /**
     * Convert a statement to source code
     */
    private fun statementToSource(stmt: Statement): String {
        return when (stmt) {
            is LetStatement -> "let ${stmt.variableName} = ${expressionToSource(stmt.expression)}"
            is ExpressionStatement -> expressionToSource(stmt.expression)
            else -> "/* unsupported statement */"
        }
    }

    /**
     * Convert a type annotation to source code
     */
    private fun typeToSource(type: TypeAnnotation): String {
        return when (type) {
            is IdentifierTypeAnnotation -> type.name
            is FunctionTypeAnnotation -> {
                "${typeToSource(type.paramType)} -> ${typeToSource(type.returnType)}"
            }
            is RecordTypeAnnotation -> {
                val fields = type.entries.joinToString(", ") { entry ->
                    "${entry.name}: ${typeToSource(entry.type)}"
                }
                "{$fields}"
            }
            is ArrayTypeAnnotation -> {
                "[${typeToSource(type.elementType)}]"
            }
            is TypeApplicationAnnotation -> {
                "${type.constructor} ${type.args.joinToString(" ") { typeToSource(it) }}"
            }
            else -> "UnknownType"
        }
    }

    /**
     * Get the context from the last program execution.
     * Returns globals defined during the last execution.
     */
    fun getLastExecutionContext(): Map<String, FunkValue> {
        return FunkLanguage.getLastExecutionContextGlobals()
    }

    /**
     * Clean up resources when interpreter is no longer needed
     */
    fun close() {
        polyglotContext.close()
    }

    // ========================================================================
    // OLD INTERPRETER CODE REMOVED
    // ========================================================================
    // All legacy methods from the old map-based interpreter have been removed:
    // - evaluateExpression() - old AST walking evaluation
    // - executeStatement() - old statement execution
    // - callFunction() - old function calling
    // - callFunctionWithRecord() - old function calling with record args
    // - getCurrentExecutionState() - old continuation system
    // - pushExecutionState() - old continuation system
    // - popExecutionState() - old continuation system
    // - ExecutionState data class - old continuation state
    //
    // All execution now goes through Truffle nodes via FunkLanguage.
    // Functions are called via FunkTruffleFunction and Truffle call targets.
    // Continuations are handled by the effect system and state machines.
}
