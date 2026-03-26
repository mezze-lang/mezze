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
import com.mezze.language.typechecker.TypeCheckerV2
import com.mezze.language.typechecker.mono.Lowerer
import com.mezze.language.typechecker.mono.Monomorphizer
import com.mezze.language.typechecker.mono.Resolver
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
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

    // Cache converted v2 TypedModules by module path — stdlib doesn't change between tests
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

        // Stage 1b: Convert dependency modules to v2 TypedModules (cached)
        val allV2Modules = mutableMapOf(entryTyped.hash to entryTyped)
        val adapter = TypeCheckerV2()
        for ((path, v1Module) in v1LoadedModules) {
            val cached = typedModuleCache[path]
            if (cached != null) {
                allV2Modules[cached.hash] = cached
            } else {
                val v2Module = adapter.convertV1TypedModule(path, v1Module)
                typedModuleCache[path] = v2Module
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
            val cached = compiledFunctionCache[fn.hash.hex]
            if (cached != null) {
                compiledFunctions[fn.hash.hex] = cached
            } else {
                val rootNode = compiler.compile(fn)
                compiledFunctions[fn.hash.hex] = rootNode.callTarget
                compiledFunctionCache[fn.hash.hex] = rootNode.callTarget
            }
        }

        // Find the entry function (named __entry__ or main)
        val mainFn = lowered.functions.find { it.name == "__entry__" }
            ?: lowered.functions.find { it.name == "main" }
            ?: lowered.functions.last()

        // Compile the entry function body (always fresh — user code changes each test)
        val source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)
        val bodyNode = compiler.compileNode(mainFn.body, source)

        // Extract imported module paths for module loading
        val importedModules = mutableListOf(
            "std::int", "std::float", "std::nat", "std::frac",
            "std::bool", "std::str", "std::char",
            "std::option", "std::result",
            "std::collections::seq", "std::collections::list",
            "std::collections::map", "std::collections::set",
            "std::collections::deque", "std::collections::sorted_map",
            "std::collections::sorted_set", "std::collections::nonemptylist",
            "std::collections::bytes"
        )
        for (decl in parsed.decls) {
            when (decl) {
                is ParsedUseDecl -> {
                    when (val path = decl.path) {
                        is ParsedUsePath.Module -> importedModules.add(path.path.toString())
                        is ParsedUsePath.Selective -> importedModules.add(path.modulePath.toString())
                    }
                }
                is ParsedPubUseDecl -> {
                    when (val path = decl.path) {
                        is ParsedUsePath.Module -> importedModules.add(path.path.toString())
                        is ParsedUsePath.Selective -> importedModules.add(path.modulePath.toString())
                    }
                }
                else -> {}
            }
        }

        // Wrap: ResultCapture(ModuleLoader(HashModuleLoader(body)))
        val withV2Functions = HashModuleLoaderNode(bodyNode, compiledFunctions)
        val withModules = MezzeLanguage.createModuleLoadingNode(withV2Functions, importedModules, projectRoot)
        val wrapped = MezzeLanguage.createResultCapturingNode(withModules)

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
    fun compileFromIR(
        language: MezzeLanguage,
        functions: List<com.mezze.language.truffleir.FunctionIR>,
        importedModulePaths: List<String>,
        projectRoot: Path
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
            val cached = compiledFunctionCache[fn.hash.hex]
            if (cached != null) {
                compiledFunctions[fn.hash.hex] = cached
            } else {
                val rootNode = compiler.compile(fn)
                compiledFunctions[fn.hash.hex] = rootNode.callTarget
                compiledFunctionCache[fn.hash.hex] = rootNode.callTarget
            }
        }

        // Find entry function
        val mainFn = functions.find { it.name == "__entry__" }
            ?: functions.find { it.name == "main" }
            ?: functions.last()

        val source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)
        val bodyNode = compiler.compileNode(mainFn.body, source)

        // Module loading + result capture
        val importedModules = mutableListOf(
            "std::int", "std::float", "std::nat", "std::frac",
            "std::bool", "std::str", "std::char",
            "std::option", "std::result",
            "std::collections::seq", "std::collections::list",
            "std::collections::map", "std::collections::set",
            "std::collections::deque", "std::collections::sorted_map",
            "std::collections::sorted_set", "std::collections::nonemptylist",
            "std::collections::bytes"
        )
        importedModules.addAll(importedModulePaths)

        val withV2Functions = HashModuleLoaderNode(bodyNode, compiledFunctions)
        val withModules = MezzeLanguage.createModuleLoadingNode(withV2Functions, importedModules, projectRoot)
        val wrapped = MezzeLanguage.createResultCapturingNode(withModules)

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
}
