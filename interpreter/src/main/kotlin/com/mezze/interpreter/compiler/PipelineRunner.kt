package com.mezze.interpreter.compiler

import com.mezze.interpreter.nodes.HashModuleLoaderNode
import com.mezze.interpreter.nodes.MezzeNode
import com.mezze.interpreter.nodes.MezzeRootNode
import com.mezze.interpreter.nodes.literal.UnitLiteralNode
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.TypeChecker
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.mezze.language.truffleir.SlotKind
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
object PipelineRunner {

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

    /**
     * Evict entries for the given hashes. Hash-keyed caches are content-addressed,
     * so unchanged hashes can stay; only changed hashes need to go.
     * Used by DevRunner watch mode to keep stdlib warm across reloads.
     */
    @JvmStatic
    fun invalidateHashes(hashes: Collection<String>) {
        for (h in hashes) {
            typedModuleCache.remove(h)
            compiledFunctionCache.remove(h)
        }
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
        entryName: String? = null
    ): CallTarget {
        if (functions.isEmpty()) {
            val fd = FrameDescriptor.newBuilder().build()
            val body: MezzeNode = UnitLiteralNode()
            val wrapped = MezzeLanguage.createResultCapturingNode(body)
            return MezzeRootNode(language, fd, wrapped).callTarget
        }

        // Compile only REFERENCED stdlib functions (lazy, per-Language instance).
        // ~35 per test instead of 1044. Each Language instance (=Context) compiles its own.
        val compiler = TruffleCompiler(language)
        val precompiled = com.mezze.language.typechecker.PrecompiledStdlib
        val compiledFunctions = mutableMapOf<com.mezze.language.typedast.Hash, CallTarget>()

        val userHashes = functions.map { it.hash }.toSet()
        val referencedHashes = collectReferencedHashes(functions)
        language.compileReferencedStdlib(
            compiler,
            referencedHashes,
            userHashes
        )

        // Register user functions for lazy compilation — DON'T pre-compile CallTargets.
        // Each test has unique functions; pre-compiling creates CallTargets that persist on
        // the shared Engine and cause OOM over thousands of tests. Instead, register FunctionIRs
        // and let MonomorphicCallNode compile on-demand via Language.getCachedStdlibCallTarget.
        compiler.registerFunctions(functions)

        // Resolve function aliases: `let toggle_bool = toggle` compiles to a FunctionIR whose
        // body is ReadGlobalByHashIR(toggle_hash). Register the alias hash → target's FunctionIR
        // so MonomorphicCallNode finds the real function (with its params) directly.
        val aliasMap = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typedast.Hash>() // alias_hash → target_hash
        for (fn in functions) {
            if (fn.paramSlots.isEmpty() && fn.captureSlots.isEmpty() && fn.localSlots.isEmpty()) {
                val body = fn.body
                if (body is com.mezze.language.truffleir.ReadGlobalByHashIR) {
                    aliasMap[fn.hash] = body.hash
                }
            }
        }

        // Build index for resolving aliases to their target FunctionIR
        val fnByHash = functions.associateBy { it.hash }
        for (fn in functions) {
            var targetHash = aliasMap[fn.hash]
            if (targetHash != null) {
                // Find the target's FunctionIR (may be in user functions or stdlib)
                val targetIR = fnByHash[targetHash]
                if (targetIR != null) {
                    // Register alias pointing to the target's FunctionIR directly
                    language.registerFunctionIR(fn.hash, targetIR)
                } else {
                    // Target is in stdlib — register alias hash mapping so getCachedStdlibCallTarget resolves it
                    language.registerFunctionIR(fn.hash, fn)
                    // Also try to copy the stdlib CallTarget to the alias hash
                    val stdlibCT = language.getCachedStdlibCallTarget(targetHash)
                    if (stdlibCT != null) {
                        language.cachedStdlibCallTargetCache[fn.hash] = stdlibCT
                    }
                }
            } else {
                language.registerFunctionIR(fn.hash, fn)
            }
        }

        // Eagerly compile performer factory functions so they're available via HashModuleLoaderNode.
        // Performer factories are identified by their hash appearing in performer records.
        val performerFactoryHashes = mutableSetOf<com.mezze.language.typedast.Hash>()
        for (pr in performerRecords) {
            if (pr.isNotEmpty()) performerFactoryHashes.add(com.mezze.language.typedast.Hash(pr[0]))
        }
        for (fn in functions) {
            if (fn.hash in performerFactoryHashes && fn.paramSlots.isNotEmpty()) {
                // This is a parameterized performer factory — compile eagerly
                val rootNode = compiler.compile(fn)
                compiledFunctions[fn.hash] = rootNode.callTarget
            }
        }

        // Find the entry point and compile it.
        //
        // If a named entry function exists (from Mezze.toml entryName), compile a call
        // to it: `entryName {}`. This is the mez dev path.
        //
        // If no named entry, use __entry__ (tests/REPL wrapping of top-level expressions)
        // or fall back to the last function.
        val namedEntry = if (entryName != null) functions.find { it.name == entryName } else null

        val mainFn: com.mezze.language.truffleir.FunctionIR
        val source: com.oracle.truffle.api.source.Source
        val bodyNode: com.mezze.interpreter.nodes.MezzeNode

        if (namedEntry != null) {
            // Named entry function exists (mez dev path).
            // __entry__ body evaluates all declarations and returns the entry closure.
            // We then invoke that closure with {}.
            val wrapperFn = functions.find { it.name == "__entry__" }
            mainFn = wrapperFn ?: namedEntry
            source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)

            // __entry__ body returns the entry function closure
            val entryClosureNode = compiler.compileNode(mainFn.body, source)
            // Create empty record {} argument (MezzeRecord.empty(), not MezzeUnit)
            val emptyRecordNode = object : com.mezze.interpreter.nodes.MezzeNode() {
                override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any {
                    return com.mezze.interpreter.values.MezzeRecord.empty()
                }
            }
            // Invoke the closure with {}
            bodyNode = com.mezze.interpreter.nodes.function.InvokeNode.create(
                entryClosureNode, arrayOf(emptyRecordNode)
            )
        } else {
            // No named entry — fall back to __entry__ (tests/REPL) or last function
            mainFn = functions.find { it.name == "__entry__" } ?: functions.last()
            source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)
            bodyNode = compiler.compileNode(mainFn.body, source)
        }

        // Build hash→name map for performer/handler lookup by name
        val hashToName = mutableMapOf<com.mezze.language.typedast.Hash, String>()
        for (fn in functions) {
            if (fn.name != "lambda" && fn.name != "__entry__" && !fn.name.startsWith("__toplevel")) {
                hashToName[fn.hash] = fn.name
            }
        }

        // Add stdlib name mappings + performer records (both cached at Language level)
        val allPerformerRecords = performerRecords.toMutableList()
        hashToName.putAll(language.stdlibHashToName)
        allPerformerRecords.addAll(language.stdlibPerformerRecords)

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
            mainFn.name,
            mainFn.paramSlots.size,
            0,
            IntArray(0)
        ).callTarget
    }

    /**
     * Collect all function hashes referenced transitively by the user's FunctionIRs.
     * Walks the IR tree to find CallDirectIR, MakeLambdaIR, HandleIR, and PerformOpIR
     * references, then follows them into stdlib to collect the full transitive closure.
     */
    private fun collectReferencedHashes(
        userFunctions: List<com.mezze.language.truffleir.FunctionIR>
    ): Set<com.mezze.language.typedast.Hash> {
        val referenced = mutableSetOf<com.mezze.language.typedast.Hash>()
        val queue = ArrayDeque<com.mezze.language.typedast.Hash>()

        for (fn in userFunctions) {
            referenced.add(fn.hash)
            collectHashesFromNode(fn.body, queue)
        }

        val depGraph = com.mezze.language.typechecker.PrecompiledStdlib.stdlibDependencyGraph

        while (queue.isNotEmpty()) {
            val hash = queue.removeFirst()
            if (hash in referenced) continue
            referenced.add(hash)
            depGraph[hash]?.let { queue.addAll(it) }
        }

        return referenced
    }

    private fun collectHashesFromNode(node: com.mezze.language.truffleir.NodeIR, queue: ArrayDeque<com.mezze.language.typedast.Hash>) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                queue.add(node.targetHash)
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                queue.add(node.targetHash)
                node.partialFields.forEach { collectHashesFromNode(it.value, queue) }
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> queue.add(node.functionHash)
            is com.mezze.language.truffleir.HandleIR -> {
                collectHashesFromNode(node.body, queue)
                for (h in node.handlers) {
                    queue.add(h.handlerHash)
                    queue.add(h.effectHash)
                }
            }
            is com.mezze.language.truffleir.PerformOpIR -> {
                queue.add(node.effectHash)
                collectHashesFromNode(node.arg, queue)
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) {
                    when (stmt) {
                        is com.mezze.language.truffleir.StmtIR.Let -> collectHashesFromNode(stmt.value, queue)
                        is com.mezze.language.truffleir.StmtIR.Expr -> collectHashesFromNode(stmt.node, queue)
                    }
                }
                collectHashesFromNode(node.result, queue)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectHashesFromNode(node.arg, queue)
            is com.mezze.language.truffleir.CallNativeIR -> collectHashesFromNode(node.arg, queue)
            is com.mezze.language.truffleir.CallIndirectIR -> {
                collectHashesFromNode(node.function, queue)
                collectHashesFromNode(node.arg, queue)
            }
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                collectHashesFromNode(node.function, queue)
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectHashesFromNode(it.value, queue) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectHashesFromNode(node.record, queue)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectHashesFromNode(node.record, queue)
                node.updates.forEach { collectHashesFromNode(it.value, queue) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectHashesFromNode(it, queue) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectHashesFromNode(node.scrutinee, queue)
                for (c in node.cases) {
                    collectHashesFromNode(c.body, queue)
                    c.guard?.let { collectHashesFromNode(it, queue) }
                    for (ilb in c.ifLetBindings) {
                        collectHashesFromNode(ilb.expr, queue)
                    }
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectHashesFromNode(node.cond, queue)
                collectHashesFromNode(node.thenBr, queue)
                collectHashesFromNode(node.elseBr, queue)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> collectHashesFromNode(node.value, queue)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectHashesFromNode(node.left, queue)
                collectHashesFromNode(node.right, queue)
            }
            is com.mezze.language.truffleir.SpecializedCallIR -> {
                node.args.forEach { collectHashesFromNode(it, queue) }
            }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (part in node.parts) {
                    if (part is com.mezze.language.truffleir.StringPartIR.Expr) collectHashesFromNode(part.node, queue)
                }
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (elem in node.elements) {
                    when (elem) {
                        is com.mezze.language.truffleir.SeqElementIR.Regular -> collectHashesFromNode(elem.node, queue)
                        is com.mezze.language.truffleir.SeqElementIR.Spread -> collectHashesFromNode(elem.node, queue)
                    }
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                collectHashesFromNode(node.seq, queue)
                collectHashesFromNode(node.index, queue)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> {
                collectHashesFromNode(node.arg, queue)
            }
            // Leaf nodes — no children to walk
            is com.mezze.language.truffleir.ReadLocalIR -> {}
            is com.mezze.language.truffleir.ReadCaptureIR -> {}
            is com.mezze.language.truffleir.ReadArgIR -> {}
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> queue.add(node.hash)
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> queue.add(node.hash)
            is com.mezze.language.truffleir.ReadScopeIR -> {} // local scope variable, not a function ref
            is com.mezze.language.truffleir.LongLitIR -> {}
            is com.mezze.language.truffleir.DoubleLitIR -> {}
            is com.mezze.language.truffleir.BoolLitIR -> {}
            is com.mezze.language.truffleir.StringLitIR -> {}
            is com.mezze.language.truffleir.UnitLitIR -> {}
            is com.mezze.language.truffleir.CharLitIR -> {}
            is com.mezze.language.truffleir.FracLitIR -> {}
        }
    }

    private fun slotKindToFrameKind(kind: SlotKind): FrameSlotKind = when (kind) {
        SlotKind.LONG -> FrameSlotKind.Long
        SlotKind.DOUBLE -> FrameSlotKind.Double
        SlotKind.BOOLEAN -> FrameSlotKind.Boolean
        SlotKind.OBJECT -> FrameSlotKind.Object
    }

}
