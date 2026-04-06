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
import com.mezze.language.typechecker.mono.Lowerer
import com.mezze.language.typechecker.mono.Monomorphizer
import com.mezze.language.typechecker.mono.Resolver
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

    @JvmStatic
    fun tryCompile(language: MezzeLanguage, sourceCode: String, projectRoot: Path, fileName: String?): CallTarget {
        val file = fileName ?: projectRoot.resolve("main.mz").toString()

        // Stage 0: Parse
        val parser = MezzeParser()
        val parsed = parser.parseParsedModule(sourceCode, file = file)

        // Stage 1: Typecheck (uses ModuleResolver cache internally)
        val typeChecker = TypeChecker()
        val checkResult = typeChecker.checkParsedModuleWithDeps(parsed, projectRoot, enablePrelude = true)
        var entryTyped = checkResult.module
        val v1LoadedModules = checkResult.loadedModules

        // Stage 1.5: Wrap top-level code into single entry function
        entryTyped = EntryFunctionBuilder.wrapTopLevel(entryTyped)

        // Stage 1b: Collect v2 TypedModules from loaded dependencies (already available from v2 pipeline)
        val allV2Modules = mutableMapOf(entryTyped.hash to entryTyped)
        for ((path, v1Module) in v1LoadedModules) {
            val v2Module = v1Module.v2TypedModule ?: continue  // Skip modules without v2 TypedModule
            val hashKey = v2Module.hash.hex
            val cached = typedModuleCache[hashKey]
            if (cached != null) {
                allV2Modules[cached.hash] = cached
            } else {
                typedModuleCache[hashKey] = v2Module
                allV2Modules[v2Module.hash] = v2Module
            }
        }

        // Stage 2: Monomorphize from entry point across ALL modules
        val mono = Monomorphizer()
        val monoModule = mono.monomorphize(entryTyped, allV2Modules)

        // Stage 3: Resolve
        val resolver = Resolver()
        val resolved = resolver.resolve(monoModule)

        // Stage 4: Lower
        val lowerer = Lowerer()
        val lowered = lowerer.lower(resolved)

        if (lowered.functions.isEmpty()) {
            val fd = FrameDescriptor.newBuilder().build()
            val body: MezzeNode = UnitLiteralNode()
            val wrapped = MezzeLanguage.createResultCapturingNode(body)
            return MezzeRootNode(language, fd, wrapped).callTarget
        }

        // Stage 5: Compile functions to Truffle CallTargets (cached by hash)
        val compiler = TruffleCompiler(language)
        compiler.registerFunctions(lowered.functions)
        val compiledFunctions = mutableMapOf<String, CallTarget>()

        for (fn in lowered.functions) {
            val rootNode = compiler.compile(fn)
            compiledFunctions[fn.hash.hex] = rootNode.callTarget
        }

        // Find the entry function (named __entry__ or main)
        val mainFn = lowered.functions.find { it.name == "__entry__" }
            ?: lowered.functions.find { it.name == "main" }
            ?: lowered.functions.last()

        // Compile the entry function body (always fresh — user code changes each test)
        val source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)
        val bodyNode = compiler.compileNode(mainFn.body, source)

        // Build hash→name map for performer/handler lookup by name
        val hashToName = mutableMapOf<String, String>()
        for (fn in lowered.functions) {
            if (fn.name != "lambda" && fn.name != "__entry__" && !fn.name.startsWith("__toplevel")) {
                hashToName[fn.hash.hex] = fn.name
            }
        }

        // Wrap: ResultCapture(HashModuleLoader(body))
        // No ModuleLoadingNode — monomorphizer inlines all reachable stdlib functions
        val withV2Functions = HashModuleLoaderNode(bodyNode, compiledFunctions, hashToName)
        val wrapped = MezzeLanguage.createResultCapturingNode(withV2Functions)

        // Frame descriptor for the entry function
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
        performerRecords: List<Array<String>> = emptyList()
    ): CallTarget {
        if (functions.isEmpty()) {
            val fd = FrameDescriptor.newBuilder().build()
            val body: MezzeNode = UnitLiteralNode()
            val wrapped = MezzeLanguage.createResultCapturingNode(body)
            return MezzeRootNode(language, fd, wrapped).callTarget
        }

        // Compile all functions
        val compiler = TruffleCompiler(language)
        compiler.registerFunctions(functions)
        val compiledFunctions = mutableMapOf<String, CallTarget>()

        for (fn in functions) {
            val rootNode = compiler.compile(fn)
            compiledFunctions[fn.hash.hex] = rootNode.callTarget
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

        // HashModuleLoaderNode registers v2-compiled functions by hash AND name
        val performerInfos = performerRecords.toTypedArray()
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
