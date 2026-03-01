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
 * Test helper to execute Mezze programs using the V2 optimized interpreter.
 *
 * This uses the V2 interpreter (language ID "mezze") and converts results
 * back to V1 MezzeValue types for compatibility with existing tests.
 *
 * V2 returns raw JVM types:
 * - Long for Int
 * - Double for Float
 * - String for String
 * - Boolean for Bool
 * - MezzeRecord (V2) for records
 * - MezzeVariant (V2) for variants
 * - MezzeList (V2) for lists
 * - MezzeUnit.INSTANCE for Unit
 *
 * This helper converts them back to V1 MezzeValue types.
 */
object MezzeTestHelper {
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    // Use V2's language ID
    private const val LANGUAGE_ID = "mezze"

    // Shared engine for all test executions to avoid sharing layer violations
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
        com.mezze.interpreter.v2.runtime.MezzeLanguage.clearLastResult()

        // Create polyglot context with multi-threading enabled for Loom concurrency
        Context.newBuilder(LANGUAGE_ID)
            .engine(sharedEngine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL)
            .build().use { context ->
                val source = Source.newBuilder(LANGUAGE_ID, sourceCode, sourcePath)
                    .buildLiteral()

                context.eval(source)

                // Get V2 result and convert to V1 MezzeValue
                val v2Result = com.mezze.interpreter.v2.runtime.MezzeLanguage.getLastExecutionResult()
                return convertV2ToV1(v2Result)
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

        val programWithHashes = ensureContentHashes(program)

        com.mezze.interpreter.v2.runtime.MezzeLanguage.clearLastResult()

        val context = Context.newBuilder(LANGUAGE_ID)
            .engine(sharedEngine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.ALL)
            .build()

        try {
            // Set the program for direct execution via V2
            com.mezze.interpreter.v2.runtime.MezzeLanguage.setProgramForDirectExecution(programWithHashes, root)
            // Set monomorphic instances if provided
            if (monomorphicInstances.isNotEmpty()) {
                com.mezze.interpreter.v2.runtime.MezzeLanguage.setMonomorphicInstances(monomorphicInstances)
            }

            val source = Source.newBuilder(LANGUAGE_ID, "# DIRECT_AST_EXECUTION", "__direct_execution__.mz")
                .buildLiteral()
            context.eval(source)

            val v2Result = com.mezze.interpreter.v2.runtime.MezzeLanguage.getLastExecutionResult()
            return convertV2ToV1(v2Result)
        } finally {
            context.close(true)
        }
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
        // Get V2's captured context and convert to V1 MezzeValue types
        val v2Context = com.mezze.interpreter.v2.runtime.MezzeLanguage.getLastExecutionContext()
        val v1Context = v2Context.mapValues { (_, v2Value) ->
            try {
                convertV2ToV1(v2Value)
            } catch (e: Exception) {
                // If conversion fails (e.g., for function values), use a placeholder
                MezzeNull
            }
        }
        return Pair(result, v1Context)
    }

    /**
     * Get the last execution context (for backward compatibility)
     */
    fun getLastExecutionContext(): Map<String, MezzeValue> {
        val v2Context = com.mezze.interpreter.v2.runtime.MezzeLanguage.getLastExecutionContext()
        return v2Context.mapValues { (_, v2Value) ->
            try {
                convertV2ToV1(v2Value)
            } catch (e: Exception) {
                MezzeNull
            }
        }
    }

    /**
     * Execute a program and then call a specific function within the same context
     */
    fun executeAndCallFunction(
        program: Program,
        functionName: String,
        arguments: MezzeRecord = MezzeRecord(emptyMap()),
        monomorphicInstances: Map<String, com.mezze.language.ast.LetBinding> = emptyMap(),
        projectRoot: Path? = null
    ): MezzeValue {
        val modifiedItems = program.items.toMutableList()

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

        val functionCall = com.mezze.language.ast.FunctionCall(
            function = com.mezze.language.ast.Identifier(functionName),
            arguments = argumentsLiteral
        )

        modifiedItems.add(com.mezze.language.ast.TopLevelExpression(functionCall))

        val modifiedProgram = com.mezze.language.ast.Program(modifiedItems)
        return execute(modifiedProgram, monomorphicInstances, projectRoot)
    }

    /**
     * Convert V2 raw values to V1 MezzeValue types
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
                // Fallback: try to convert unknown types
                throw IllegalArgumentException("Cannot convert V2 value to V1: ${v2Value::class.simpleName}")
            }
        }
    }

    /**
     * Convert V2 MezzeRecord to V1 MezzeRecord
     */
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

    /**
     * Convert V2 MezzeVariant to V1 MezzeVariant
     */
    private fun convertV2Variant(v2Variant: com.mezze.interpreter.v2.values.MezzeVariant): MezzeVariant {
        val constructorName = v2Variant.constructorName
        val payload = if (v2Variant.hasPayload()) {
            val payloadValue = v2Variant.payloadValue
            if (payloadValue is com.mezze.interpreter.v2.values.MezzeRecord) {
                convertV2Record(payloadValue)
            } else {
                // Single value payload - wrap in record with "val" field
                MezzeRecord(mapOf("val" to convertV2ToV1(payloadValue)))
            }
        } else {
            null
        }
        return MezzeVariant(constructorName, payload)
    }

    /**
     * Convert V2 MezzeList to V1 MezzeList
     */
    private fun convertV2List(v2List: com.mezze.interpreter.v2.values.MezzeList): MezzeList {
        val elements = mutableListOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeList.fromKotlinList(elements)
    }

    /**
     * Convert V2 MezzeMap to V1 MezzeMap
     */
    private fun convertV2Map(v2Map: com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.MezzeMap): MezzeMap {
        val keys = com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.keys(v2Map)
        val entries = mutableMapOf<MezzeValue, MezzeValue>()
        for (key in keys) {
            val v2Result = com.mezze.interpreter.v2.nodes.builtin.collections.MapNatives.get(v2Map, key)
            // get returns Option variant, extract value if Some
            if (v2Result is com.mezze.interpreter.v2.values.MezzeVariant && v2Result.isSome) {
                val payload = v2Result.payloadValue as com.mezze.interpreter.v2.values.MezzeRecord
                entries[convertV2ToV1(key)] = convertV2ToV1(payload.getField(0))
            }
        }
        return MezzeMap(entries)
    }

    /**
     * Convert V2 MezzeSet to V1 MezzeSet
     */
    private fun convertV2Set(v2Set: com.mezze.interpreter.v2.nodes.builtin.collections.SetNatives.MezzeSet): MezzeSet {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.SetNatives.toList(v2Set)
        val elements = mutableSetOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeSet(elements)
    }

    /**
     * Convert V2 MezzeDeque to V1 MezzeDeque
     * V1 MezzeDeque uses two Lists (front/back), so we put all elements in front
     */
    private fun convertV2Deque(v2Deque: com.mezze.interpreter.v2.nodes.builtin.collections.DequeNatives.MezzeDeque): MezzeDeque {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.DequeNatives.toList(v2Deque)
        val elements = mutableListOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeDeque(elements, emptyList())
    }

    /**
     * Convert V2 MezzeNonEmptyList to V1 MezzeNonEmptyList
     * V1 takes a single List<MezzeValue> that must be non-empty
     */
    private fun convertV2NonEmptyList(v2Nel: com.mezze.interpreter.v2.nodes.builtin.collections.NonEmptyListNatives.MezzeNonEmptyList): MezzeNonEmptyList {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.NonEmptyListNatives.toList(v2Nel)
        val elements = mutableListOf<MezzeValue>()
        for (i in 0 until v2List.size()) {
            elements.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeNonEmptyList(elements)
    }

    /**
     * Convert V2 MezzeSortedMap to V1 MezzeSortedMap
     * V1 uses io.vavr.collection.TreeMap
     */
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

    /**
     * Convert V2 MezzeSortedSet to V1 MezzeSortedSet
     * V1 uses io.vavr.collection.TreeSet
     */
    private fun convertV2SortedSet(v2SortedSet: com.mezze.interpreter.v2.nodes.builtin.collections.SortedSetNatives.MezzeSortedSet): MezzeSortedSet {
        val v2List = com.mezze.interpreter.v2.nodes.builtin.collections.SortedSetNatives.toList(v2SortedSet)
        var treeSet = io.vavr.collection.TreeSet.empty<MezzeValue>(MezzeValueComparator())
        for (i in 0 until v2List.size()) {
            treeSet = treeSet.add(convertV2ToV1(v2List.get(i)))
        }
        return MezzeSortedSet(treeSet)
    }

    /**
     * Convert V2 MezzeBytes to V1 MezzeBytes
     */
    private fun convertV2Bytes(v2Bytes: com.mezze.interpreter.v2.nodes.builtin.io.BytesNatives.MezzeBytes): MezzeBytes {
        return MezzeBytes(v2Bytes.data)
    }

    /**
     * Ensure the program has content hashes computed
     */
    private fun ensureContentHashes(program: Program): Program {
        val needsHashing = program.items.any { item ->
            when (item) {
                is ImplDeclaration -> item.contentHash == null
                is ImplAbilityDeclaration -> item.contentHash == null
                else -> false
            }
        }

        return if (needsHashing) {
            ContentHashComputer.computeHashes(program).program
        } else {
            program
        }
    }
}
