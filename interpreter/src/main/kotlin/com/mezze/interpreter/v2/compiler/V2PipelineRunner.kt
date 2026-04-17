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
        performerRecords: List<Array<String>> = emptyList()
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
        val compiledFunctions = mutableMapOf<String, CallTarget>()

        val userHashes = functions.map { it.hash.hex }.toSet()
        val referencedHashes = collectReferencedHashes(functions, precompiled.preloweredFunctions)
        // Debug: function counts
        // System.err.println("V2Pipeline: ${functions.size} user FnIRs, ${referencedHashes.size} ref stdlib")
        language.compileReferencedStdlib(compiler, referencedHashes, userHashes)

        // Register user functions for lazy compilation — DON'T pre-compile CallTargets.
        // Each test has unique functions; pre-compiling creates CallTargets that persist on
        // the shared Engine and cause OOM over thousands of tests. Instead, register FunctionIRs
        // and let MonomorphicCallNode compile on-demand via Language.getCachedStdlibCallTarget.
        compiler.registerFunctions(functions)
        for (fn in functions) {
            language.registerFunctionIR(fn.hash.hex, fn)
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
            "main",
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
        userFunctions: List<com.mezze.language.truffleir.FunctionIR>,
        stdlibFunctions: Map<String, com.mezze.language.truffleir.FunctionIR>
    ): Set<String> {
        val referenced = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        // Seed with user function hashes
        for (fn in userFunctions) {
            referenced.add(fn.hash.hex)
            collectHashesFromNode(fn.body, queue)
        }

        // BFS: follow references into stdlib
        while (queue.isNotEmpty()) {
            val hash = queue.removeFirst()
            if (hash in referenced) continue
            referenced.add(hash)
            val stdlibFn = stdlibFunctions[hash]
            if (stdlibFn != null) {
                collectHashesFromNode(stdlibFn.body, queue)
            }
        }

        return referenced
    }

    private fun collectHashesFromNode(node: com.mezze.language.truffleir.NodeIR, queue: ArrayDeque<String>) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                queue.add(node.targetHash.hex)
                collectHashesFromNode(node.arg, queue)
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> queue.add(node.functionHash.hex)
            is com.mezze.language.truffleir.HandleIR -> {
                collectHashesFromNode(node.body, queue)
                for (h in node.handlers) {
                    queue.add(h.handlerHash.hex)
                    queue.add(h.effectHash.hex)
                }
            }
            is com.mezze.language.truffleir.PerformOpIR -> {
                queue.add(node.effectHash.hex)
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
            is com.mezze.language.truffleir.DynamicMethodCallIR -> {
                collectHashesFromNode(node.receiver, queue)
                collectHashesFromNode(node.arg, queue)
                node.candidateHashes.forEach { queue.add(it) }
            }
            // Leaf nodes — no children to walk
            is com.mezze.language.truffleir.ReadLocalIR -> {}
            is com.mezze.language.truffleir.ReadCaptureIR -> {}
            is com.mezze.language.truffleir.ReadArgIR -> {}
            is com.mezze.language.truffleir.ReadContextIR -> {}
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
