package com.mezze.interpreter.v2.compiler

import com.mezze.interpreter.v2.nodes.HashModuleLoaderNode
import com.mezze.interpreter.v2.nodes.MezzeNode
import com.mezze.interpreter.v2.nodes.MezzeRootNode
import com.mezze.interpreter.v2.nodes.literal.UnitLiteralNode
import com.mezze.interpreter.v2.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.TypeChecker
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.mezze.language.resolvedast.SlotKind
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * V2 Pipeline Runner — compiles Mezze source through the full v2 pipeline.
 *
 * Parse → Typecheck → Wrap top-level → Monomorphize → Resolve → Lower → TruffleCompiler
 *
 * Caches converted TypedModules and compiled CallTargets by content hash so stdlib
 * modules are only converted/compiled once across test runs.
 */
object V2PipelineRunner {

    // Cache converted v2 TypedModules by content hash (module.hash.hex).
    // Hash-based identity means the same module content at different paths shares one cache entry.
    private val typedModuleCache = ConcurrentHashMap<String, TypedModule>()

    // Cache compiled CallTargets by function hash
    private val compiledFunctionCache = ConcurrentHashMap<String, CallTarget>()

    @JvmStatic
    fun clearCache() {
        typedModuleCache.clear()
        compiledFunctionCache.clear()
    }

    // tryCompile deleted — TypedASTCompiler replaces the old Mono→Resolve→Lower pipeline

    /**
     * Compile pre-lowered FunctionIR list to a CallTarget.
     * Called from MezzeLanguage when the typechecker has already produced TruffleIR.
     */
    @JvmStatic
    @JvmOverloads
    fun compileFromIR(
        language: MezzeLanguage,
        functions: List<com.mezze.language.truffleir.FunctionIR>,
        importedModulePaths: List<String> = emptyList(),
        projectRoot: Path = Path.of("."),
        performerRecords: List<Array<String>> = emptyList(),
        loadedModules: Map<String, com.mezze.language.typedast.TypedModule> = emptyMap()
    ): CallTarget {
        if (functions.isEmpty()) {
            val fd = FrameDescriptor.newBuilder().build()
            val body: MezzeNode = UnitLiteralNode()
            val wrapped = MezzeLanguage.createResultCapturingNode(body)
            return MezzeRootNode(language, fd, wrapped).callTarget
        }

        // Add pre-lowered stdlib functions first
        val compiler = TruffleCompiler(language)
        val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
        val compiledFunctions = mutableMapOf<String, CallTarget>()
        for ((hash, fn) in precompiled.preloweredFunctions) {
            if (!compiledFunctions.containsKey(hash)) {
                val rootNode = compiler.compile(fn)
                compiledFunctions[hash] = rootNode.callTarget
            }
        }

        // Compile entry module functions (skip those already pre-lowered)
        compiler.registerFunctions(functions)
        for (fn in functions) {
            if (fn.hash.hex !in precompiled.preloweredFunctions) {
                val rootNode = compiler.compile(fn)
                compiledFunctions[fn.hash.hex] = rootNode.callTarget
            }
        }

        // Find entry function
        val mainFn = functions.find { it.name == "__entry__" }
            ?: functions.find { it.name == "main" }
            ?: functions.last()

        val source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)
        val bodyNode = compiler.compileNode(mainFn.body, source)

        // Build hash→name map for performer/handler lookup by name
        val hashToName = mutableMapOf<String, String>()
        for (fn in functions) {
            if (fn.name != "lambda" && fn.name != "__entry__" && !fn.name.startsWith("__toplevel")) {
                hashToName[fn.hash.hex] = fn.name
            }
        }

        // Pre-lowered functions already added above. Add name mappings + performer records.
        val allPerformerRecords = performerRecords.toMutableList()
        for ((hash, fn) in precompiled.preloweredFunctions) {
            if (fn.name != "lambda" && !fn.name.startsWith("__")) {
                hashToName[hash] = fn.name
            }
        }
        allPerformerRecords.addAll(precompiled.preloweredPerformerRecords)

        // HashModuleLoaderNode registers v2-compiled functions by hash AND name
        val performerInfos = allPerformerRecords.toTypedArray()
        val withV2Functions = HashModuleLoaderNode(bodyNode, compiledFunctions, hashToName, performerInfos)
        val wrapped = MezzeLanguage.createResultCapturingNode(withV2Functions)

        val fdBuilder = FrameDescriptor.newBuilder()
        for (i in mainFn.paramSlots.indices) {
            fdBuilder.addSlot(FrameSlotKind.Illegal, mainFn.paramNames.getOrElse(i) { "p$i" }, null)
        }
        for (i in mainFn.localSlots.indices) {
            fdBuilder.addSlot(FrameSlotKind.Illegal, mainFn.localNames.getOrElse(i) { "l$i" }, null)
        }

        return MezzeRootNode(
            language,
            fdBuilder.build(),
            wrapped,
            "main",
            mainFn.paramSlots.size,
            0,
            IntArray(0)
        ).callTarget
    }

    private fun slotKindToFrameKind(kind: SlotKind): FrameSlotKind = when (kind) {
        SlotKind.LONG -> FrameSlotKind.Long
        SlotKind.DOUBLE -> FrameSlotKind.Double
        SlotKind.BOOLEAN -> FrameSlotKind.Boolean
        SlotKind.OBJECT -> FrameSlotKind.Object
    }
}
