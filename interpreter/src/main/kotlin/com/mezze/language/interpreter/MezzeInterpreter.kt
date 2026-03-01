package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeChecker
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Simplified Mezze interpreter that delegates all execution to Truffle via GraalVM polyglot API.
 *
 * This class is now a thin wrapper that:
 * - Provides backward-compatible API for tests and CLI
 * - Delegates all actual execution to MezzeLanguage (100% Truffle)
 * - No map-based evaluation logic
 *
 * All programs now execute through optimized Truffle nodes with full JIT compilation.
 */
class MezzeInterpreter {
    private val parser: MezzeParser?
    private val typeChecker: TypeChecker?

    // GraalVM polyglot context for executing Mezze programs
    private val polyglotContext: Context

    /**
     * Constructor for full parsing/typechecking (used by mezze exec)
     */
    constructor() {
        parser = MezzeParser()
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
            parser = MezzeParser()
            typeChecker = TypeChecker()
        }
        polyglotContext = createPolyglotContext()
    }

    /**
     * Create a GraalVM polyglot context for Mezze execution
     */
    private fun createPolyglotContext(): Context {
        return Context.newBuilder("mezze")
            .allowAllAccess(true)
            .build()
    }

    /**
     * Parse, typecheck, and execute a Mezze program from source code
     */
    fun run(source: String): MezzeValue {
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
    fun execute(program: Program): MezzeValue {
        return executeViaTruffle(program, null, emptyMap())
    }

    /**
     * Execute a pre-parsed and typechecked program with module resolution support
     */
    fun execute(program: Program, projectRoot: Path, monomorphicInstances: Map<String, LetBinding> = emptyMap()): MezzeValue {
        return executeViaTruffle(program, projectRoot, monomorphicInstances)
    }

    /**
     * Public method for testing - executes a program and returns both result and context
     */
    fun executeWithContext(program: Program): Pair<MezzeValue, Map<String, MezzeValue>> {
        val result = executeViaTruffle(program, null)
        val context = getLastExecutionContext()
        return Pair(result, context)
    }

    /**
     * Execute a program using 100% Truffle via the polyglot API.
     * This delegates to V2 MezzeLanguage which converts to Truffle nodes.
     */
    private fun executeViaTruffle(program: Program, projectRoot: Path?, monomorphicInstances: Map<String, LetBinding> = emptyMap()): MezzeValue {
        // CRITICAL: Use direct AST execution to preserve type information and resolved abilities!
        // Converting AST -> source -> re-parse loses all type metadata
        val root = projectRoot ?: Paths.get(".")

        // Store the program for direct execution via V2 interpreter
        com.mezze.interpreter.v2.runtime.MezzeLanguage.setProgramForDirectExecution(program, root)

        // CRITICAL: Store monomorphic instances for V2 MezzeLanguage to register
        // These are compiler-generated specializations (e.g., "add$Int") that
        // must be available at runtime WITHOUT going through the parser
        if (monomorphicInstances.isNotEmpty()) {
            com.mezze.interpreter.v2.runtime.MezzeLanguage.setMonomorphicInstances(monomorphicInstances)
        }

        // Create a dummy source to trigger V2 MezzeLanguage.parse()
        // V2 MezzeLanguage will use the pre-parsed program instead of parsing this source
        val source = Source.newBuilder("mezze", "# Direct AST execution", "__direct_execution__.mz")
            .buildLiteral()

        // Execute via polyglot API - V2 MezzeLanguage will use the pre-parsed program
        polyglotContext.eval(source)

        // Retrieve the V2 result and convert to V1 MezzeValue
        val v2Result = com.mezze.interpreter.v2.runtime.MezzeLanguage.getLastExecutionResult()
        return convertV2ToV1(v2Result)
    }

    /**
     * Convert V2 raw values to V1 MezzeValue types.
     * This mirrors the conversion in MezzeTestHelper.
     */
    private fun convertV2ToV1(v2Value: Any?): MezzeValue {
        return when (v2Value) {
            null -> MezzeNull
            is Long -> MezzeInt(v2Value)
            is Double -> MezzeFloat(v2Value)
            is String -> MezzeString(v2Value)
            is Boolean -> MezzeBool(v2Value)
            is com.mezze.interpreter.v2.values.MezzeUnit -> MezzeUnit
            is com.mezze.interpreter.v2.values.MezzeRecord -> convertV2Record(v2Value)
            is com.mezze.interpreter.v2.values.MezzeVariant -> convertV2Variant(v2Value)
            is com.mezze.interpreter.v2.values.MezzeList -> convertV2List(v2Value)
            is com.mezze.interpreter.v2.values.MezzeFunction -> NativeMezzeFunction("<v2-function>") { MezzeNull }
            // V2 uses Object[] for mutable arrays (ArrayList)
            is Array<*> -> MezzeArrayList(v2Value.map { convertV2ToV1(it) })
            // V2 collection types
            is com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.MezzeMap -> convertV2Map(v2Value)
            is com.mezze.interpreter.v2.nodes.builtin.collections.SetNatives.MezzeSet -> convertV2Set(v2Value)
            is com.mezze.interpreter.v2.nodes.builtin.collections.DequeNatives.MezzeDeque -> convertV2Deque(v2Value)
            is com.mezze.interpreter.v2.nodes.builtin.collections.NonEmptyListNatives.MezzeNonEmptyList -> convertV2NonEmptyList(v2Value)
            is com.mezze.interpreter.v2.nodes.builtin.collections.SortedMapNatives.MezzeSortedMap -> convertV2SortedMap(v2Value)
            is com.mezze.interpreter.v2.nodes.builtin.collections.SortedSetNatives.MezzeSortedSet -> convertV2SortedSet(v2Value)
            is com.mezze.interpreter.v2.nodes.builtin.io.BytesNatives.MezzeBytes -> convertV2Bytes(v2Value)
            else -> {
                throw IllegalArgumentException("Cannot convert V2 value to V1: ${v2Value::class.simpleName}")
            }
        }
    }

    private fun convertV2Record(v2Record: com.mezze.interpreter.v2.values.MezzeRecord): MezzeRecord {
        val fields = mutableMapOf<String, MezzeValue>()
        val shape = v2Record.shape
        val fieldCount = shape.fieldCount
        for (i in 0 until fieldCount) {
            val name = shape.getFieldName(i)
            val value = v2Record.getField(i)
            fields[name] = convertV2ToV1(value)
        }
        return MezzeRecord(fields)
    }

    private fun convertV2Variant(v2Variant: com.mezze.interpreter.v2.values.MezzeVariant): MezzeVariant {
        val constructorName = v2Variant.constructorName
        val payload = if (v2Variant.hasPayload()) {
            val payloadValue = v2Variant.payloadValue
            if (payloadValue is com.mezze.interpreter.v2.values.MezzeRecord) {
                convertV2Record(payloadValue)
            } else {
                MezzeRecord(mapOf("val" to convertV2ToV1(payloadValue)))
            }
        } else {
            null
        }
        return MezzeVariant(constructorName, payload)
    }

    private fun convertV2List(v2List: com.mezze.interpreter.v2.values.MezzeList): MezzeList {
        val elements = mutableListOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeList.fromKotlinList(elements)
    }

    private fun convertV2Map(v2Map: com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.MezzeMap): MezzeMap {
        val keys = com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.keys(v2Map)
        val entries = mutableMapOf<MezzeValue, MezzeValue>()
        for (key in keys) {
            val v2Result = com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.get(v2Map, key)
            if (v2Result is com.mezze.interpreter.v2.values.MezzeVariant && v2Result.isSome) {
                val payload = v2Result.payloadValue as com.mezze.interpreter.v2.values.MezzeRecord
                entries[convertV2ToV1(key)] = convertV2ToV1(payload.getField(0))
            }
        }
        return MezzeMap(entries)
    }

    private fun convertV2Set(v2Set: com.mezze.interpreter.v2.nodes.builtin.collections.SetNatives.MezzeSet): MezzeSet {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.SetNatives.toList(v2Set)
        val elements = mutableSetOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeSet(elements)
    }

    private fun convertV2Deque(v2Deque: com.mezze.interpreter.v2.nodes.builtin.collections.DequeNatives.MezzeDeque): MezzeDeque {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.DequeNatives.toList(v2Deque)
        val elements = mutableListOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeDeque(elements, emptyList())
    }

    private fun convertV2NonEmptyList(v2Nel: com.mezze.interpreter.v2.nodes.builtin.collections.NonEmptyListNatives.MezzeNonEmptyList): MezzeNonEmptyList {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.NonEmptyListNatives.toList(v2Nel)
        val elements = mutableListOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeNonEmptyList(elements)
    }

    private fun convertV2SortedMap(v2SortedMap: com.mezze.interpreter.v2.nodes.builtin.collections.SortedMapNatives.MezzeSortedMap): MezzeSortedMap {
        val keys = com.mezze.interpreter.v2.nodes.builtin.collections.SortedMapNatives.keys(v2SortedMap)
        var treeMap = io.vavr.collection.TreeMap.empty<MezzeValue, MezzeValue>(MezzeValueComparator())
        for (key in keys) {
            val v2Result = com.mezze.interpreter.v2.nodes.builtin.collections.SortedMapNatives.get(v2SortedMap, key)
            if (v2Result is com.mezze.interpreter.v2.values.MezzeVariant && v2Result.isSome) {
                val payload = v2Result.payloadValue as com.mezze.interpreter.v2.values.MezzeRecord
                treeMap = treeMap.put(convertV2ToV1(key), convertV2ToV1(payload.getField(0)))
            }
        }
        return MezzeSortedMap(treeMap)
    }

    private fun convertV2SortedSet(v2SortedSet: com.mezze.interpreter.v2.nodes.builtin.collections.SortedSetNatives.MezzeSortedSet): MezzeSortedSet {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.SortedSetNatives.toList(v2SortedSet)
        var treeSet = io.vavr.collection.TreeSet.empty<MezzeValue>(MezzeValueComparator())
        for (i in 0 until v2List.size()) {
            treeSet = treeSet.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeSortedSet(treeSet)
    }

    private fun convertV2Bytes(v2Bytes: com.mezze.interpreter.v2.nodes.builtin.io.BytesNatives.MezzeBytes): MezzeBytes {
        return MezzeBytes(v2Bytes.data)
    }

    /**
     * Convert a Program AST back to source code.
     * This is a simple serialization - MezzeLanguage will re-parse and typecheck.
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
                    // MezzeLanguage will handle these during parsing
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
    fun getLastExecutionContext(): Map<String, MezzeValue> {
        return MezzeLanguage.getLastExecutionContextGlobals()
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
    // All execution now goes through Truffle nodes via MezzeLanguage.
    // Functions are called via MezzeTruffleFunction and Truffle call targets.
    // Continuations are handled by the effect system and state machines.
}
