package com.mezze.interpreter.compiler

import com.mezze.interpreter.nodes.HashModuleLoaderNode
import com.mezze.interpreter.nodes.MezzeNode
import com.mezze.interpreter.nodes.MezzeRootNode
import com.mezze.interpreter.nodes.literal.UnitLiteralNode
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*
import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.mezze.language.truffleir.SlotKind
import java.nio.file.Path

/**
 * V2 Pipeline Runner — compiles Mezze source through the full v2 pipeline.
 *
 * Parse → Typecheck → Wrap top-level → Monomorphize → Resolve → Lower → TruffleCompiler
 */
object PipelineRunner {

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
        entryName: String? = null,
        // Caller-provided source text keyed by file path. When present, the
        // compiler builds Truffle Sources from THIS content (not disk) so the
        // debugger's breakpoints resolve against the exact source the client
        // evaluated. Empty = read from disk (the default, unchanged behavior).
        sourceOverrides: Map<String, String> = emptyMap()
    ): CallTarget {
        val timing = System.getenv("MEZZE_TIMING") != null
        val tStart = System.nanoTime()
        fun mark(label: String) {
            if (timing) System.err.println("[${(System.nanoTime() - tStart) / 1_000_000}ms] PipelineRunner: $label")
        }
        mark("compileFromIR enter (${functions.size} fns)")

        if (functions.isEmpty()) {
            val fd = FrameDescriptor.newBuilder().build()
            val body: MezzeNode = UnitLiteralNode()
            val wrapped = MezzeLanguage.createResultCapturingNode(body)
            return MezzeRootNode(language, fd, wrapped).callTarget
        }

        // Compile only REFERENCED stdlib functions (lazy, per-Language instance).
        // ~35 per test instead of 1044. Each Language instance (=Context) compiles its own.
        val compiler = TruffleCompiler(language)
        for ((file, content) in sourceOverrides) compiler.registerSourceContent(file, content)
        mark("TruffleCompiler constructed")
        // StdlibRuntimeData self-loads via its init block on first class
        // reference (via FunctionIRReachability below). Compile paths that
        // need full PrecompiledStdlib still set up via PrecompiledStdlib.setStore.
        val compiledFunctions = mutableMapOf<com.mezze.language.typedast.Hash, CallTarget>()

        val userHashes = functions.map { it.hash }.toSet()
        val referencedHashes = com.mezze.language.stdlib.FunctionIRReachability.collectReferencedHashes(functions)
        mark("referenced hashes collected (${referencedHashes.size})")
        language.compileReferencedStdlib(
            compiler,
            referencedHashes,
            userHashes
        )
        mark("stdlib compiled (referenced subset)")

        // Register user functions for lazy compilation — DON'T pre-compile CallTargets.
        // Each test has unique functions; pre-compiling creates CallTargets that persist on
        // the shared Engine and cause OOM over thousands of tests. Instead, register FunctionIRs
        // and let MonomorphicCallNode compile on-demand via Language.getCachedStdlibCallTarget.
        compiler.registerFunctions(functions)
        mark("user functions registered")

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
        //
        // Also promote the compiled CallTarget into the Language-level
        // stdlibCallTargetCache. Without this, the factory only lives on the
        // current MezzeContext's per-context user-IR registry; subsequent
        // evals in the same JVM use a fresh MezzeContext (whose registry is
        // empty), and ParameterizedHandlerLookupNode's lookup misses with
        // "factory not found" — the impl factory is in stdlib but the
        // precompiled-stdlib path only covers prelude modules, so anything
        // under stdlib/std/effects/* slips through unless we cache here.
        val performerFactoryHashes = mutableSetOf<com.mezze.language.typedast.Hash>()
        val performerOpHashes = mutableSetOf<com.mezze.language.typedast.Hash>()
        for (pr in performerRecords) {
            if (pr.isNotEmpty()) performerFactoryHashes.add(com.mezze.language.typedast.Hash(pr[0]))
            // performer record layout: [performerHash, effectHash, opHash1, opName1, opHash2, opName2, ...]
            // Collect op-function hashes so we can also promote them.
            if (pr.size >= 4) {
                var i = 2
                while (i + 1 < pr.size) {
                    performerOpHashes.add(com.mezze.language.typedast.Hash(pr[i]))
                    i += 2
                }
            }
        }
        for (fn in functions) {
            // Parameterized performer factories — compile eagerly + cache the
            // CallTarget so ParameterizedHandlerLookupNode finds it.
            if (fn.hash in performerFactoryHashes && fn.paramSlots.isNotEmpty()) {
                val rootNode = language.compileOneJava(compiler, fn)
                compiledFunctions[fn.hash] = rootNode.callTarget
                language.getCachedStdlibCallTargetCache()[fn.hash] = rootNode.callTarget
            }
            // Non-parameterized impl op functions — compile eagerly + cache so
            // HashModuleLoaderNode.constructPerformerRecord can resolve them
            // on subsequent contexts. Without this, a fresh context after the
            // first eval has an empty per-context user-IR registry, op funcs
            // resolve to null, and the handler record becomes all-MezzeUnit
            // (or constructPerformerRecord skips publishing entirely under
            // our "write-once" rule).
            if (fn.hash in performerOpHashes) {
                val rootNode = language.compileOneJava(compiler, fn)
                compiledFunctions[fn.hash] = rootNode.callTarget
                language.getCachedStdlibCallTargetCache()[fn.hash] = rootNode.callTarget
            }
        }
        mark("performer factories compiled (${compiledFunctions.size})")

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
            // Named entry function exists (mez dev / exec / build path).
            //
            // __entry__'s block body binds every top-level `let` as a global
            // (its side effect) and evaluates to the value of the LAST decl —
            // NOT necessarily the entry. So we must NOT invoke __entry__'s
            // result: any `let` declared AFTER `main` would otherwise be run
            // instead of `main` (main's effects never fire). Instead we run
            // __entry__'s body purely for its global-binding side effect, then
            // read the NAMED entry function by its content hash and invoke it
            // with {}.
            val wrapperFn = functions.find { it.name == "__entry__" }
            mainFn = wrapperFn ?: namedEntry
            source = compiler.getOrCreateSourcePublic(mainFn.sourceFile)

            // Run __entry__'s body (binds all globals). Its return value — the
            // last decl's closure — is discarded because it's not the last
            // statement of the enclosing BlockNode.
            val bindGlobalsNode = compiler.compileNode(mainFn.body, source)

            // Read the entry function by hash (no auto-eval), then invoke with {}.
            val entryFnNode = com.mezze.interpreter.nodes.ReadGlobalFnByHashNode(namedEntry.hash)
            val emptyRecordNode = object : com.mezze.interpreter.nodes.MezzeNode() {
                override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any {
                    return com.mezze.interpreter.values.MezzeRecord.empty()
                }
            }
            val invokeEntryNode = com.mezze.interpreter.nodes.function.InvokeNode.create(
                entryFnNode, arrayOf(emptyRecordNode)
            )
            bodyNode = com.mezze.interpreter.nodes.control.BlockNode(
                arrayOf(bindGlobalsNode, invokeEntryNode)
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

        val rootNode = MezzeRootNode(
            language,
            fdBuilder.build(),
            wrapped,
            mainFn.name,
            mainFn.paramSlots.size,
            0,
            IntArray(0)
        )
        // Attribute the entry root to the user source so the debugger doesn't
        // treat it as internal (isInternal() = section == null) and skip it —
        // without a root section, NO statement probe inside the root ever fires,
        // so a resolved breakpoint never suspends.
        compiler.createSourceSectionPublic(source, mainFn.body.src)?.let {
            rootNode.setSourceSection(it)
        }
        val target = rootNode.callTarget
        mark("entry CallTarget built — compileFromIR returning")
        return target
    }

    private fun slotKindToFrameKind(kind: SlotKind): FrameSlotKind = when (kind) {
        SlotKind.LONG -> FrameSlotKind.Long
        SlotKind.DOUBLE -> FrameSlotKind.Double
        SlotKind.BOOLEAN -> FrameSlotKind.Boolean
        SlotKind.OBJECT -> FrameSlotKind.Object
    }

}
