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
 * Pipeline runner — compiles Mezze source through the full pipeline.
 *
 * Parse → Typecheck → Wrap top-level → Monomorphize → Resolve → Lower → TruffleCompiler
 */
object PipelineRunner {
    // Entry-point / synthetic function-name conventions — single-sourced in the
    // shared MagicParams (F7), NOT redefined locally, so producer (EntryFunctionBuilder)
    // and consumer (here) can never drift. ENTRY_FN is the compiled entry wrapper;
    // MAIN_FN the user entry; LAMBDA_FN the anonymous-fn placeholder; TOPLEVEL_PREFIX
    // matches numbered synthetic toplevel-expr decls (__toplevel_expr_0, …).
    private val ENTRY_FN = com.mezze.language.ast.MagicParams.ENTRY_FN
    private val MAIN_FN = com.mezze.language.ast.MagicParams.MAIN_FN
    private val LAMBDA_FN = com.mezze.language.ast.MagicParams.LAMBDA_FN
    private val TOPLEVEL_PREFIX = com.mezze.language.ast.MagicParams.TOPLEVEL_EXPR_PREFIX

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
        // Entry-point identity: the entry function's content hash (== entry
        // FunctionIR.hash). Selected by `find { it.hash == entryHash }`, NEVER
        // by name. Null = no configured user entry (tests/REPL) → fall back to
        // the synthetic __entry__ wrapper / last function. (D1/D2.)
        entryHash: Hash? = null,
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
        //
        // #391 — TruffleCompiler is gone. Source resolution + section attribution
        // for the entry root now live in a small SourceHelper. Actual compilation
        // is BDSL-only (via language.compileEntryFn / compileOne).
        val sourceHelper = com.mezze.interpreter.runtime.SourceHelper()
        for ((file, content) in sourceOverrides) sourceHelper.registerSourceContent(file, content)
        mark("SourceHelper constructed")
        // StdlibRuntimeData self-loads via its init block on first class
        // reference (via FunctionIRReachability below). Compile paths that
        // need full PrecompiledStdlib still set up via PrecompiledStdlib.setStore.
        val compiledFunctions = mutableMapOf<com.mezze.language.typedast.Hash, CallTarget>()

        val userHashes = functions.map { it.hash }.toSet()
        val referencedHashes = com.mezze.language.stdlib.FunctionIRReachability.collectReferencedHashes(functions)
        mark("referenced hashes collected (${referencedHashes.size})")
        language.compileReferencedStdlib(
            referencedHashes,
            userHashes
        )
        mark("stdlib compiled (referenced subset)")

        // Register user functions for lazy compilation — DON'T pre-compile CallTargets.
        // Each test has unique functions; pre-compiling creates CallTargets that persist on
        // the shared Engine and cause OOM over thousands of tests. Instead, register FunctionIRs
        // and let MonomorphicCallNode compile on-demand via Language.getCachedStdlibCallTarget.
        // #391 — no per-Compiler function registry needed. `language.registerFunctionIR`
        // below writes each user IR into the per-Context user IR map, which is the
        // authoritative store consulted by BDSL emit + LazyStub inflate.
        mark("user functions registered (SlotTable-only)")

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

        // Performer factory / op functions — register LazyStubRootNode
        // placeholders instead of eagerly BDSL-compiling them. This defers
        // ~100ms of BytecodeCompiler work to first effect-handler use:
        //   Before: 13 fns × ~8ms = 108ms at startup (every program pays)
        //   After:  13 LazyStubs × ~0.2ms = 3ms at startup
        //
        // Parameterized factories go into compiledFunctions so pass 1 of
        // loadModules registers them in globals — the LookupParameterized
        // path resolves lazily via getCachedStdlibCallTarget anyway. Op
        // functions get stub CTs in stdlibCallTargetCache so construct-
        // PerformerRecord wraps stubs into the handler record; the stub
        // inflates transparently on first op call via compileUserIRForStub.
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
            // Performer factory / op functions — defer BDSL compilation to
            // first use via LazyStubRootNode. Saves ~100ms startup for
            // programs that don't use effect handlers (all compute
            // benchmarks, simple scripts). The stub inflates on first call
            // by compiling the FunctionIR through compileUserIRForStub,
            // which finds the IR in the per-context user registry
            // (registerFunctionIR above) or StdlibRuntimeData.
            //
            // Parameterized factories: stub CT goes into compiledFunctions
            // so pass 1 of loadModules registers it in globals —
            // LookupParameterizedHandlerByHash skips globals and resolves
            // lazily via getCachedStdlibCallTarget anyway.
            //
            // Non-parameterized ops: stub CT goes into compiledFunctions
            // + stdlibCallTargetCache. constructPerformerRecord wraps the
            // stub in a MezzeFunction; when the op is called at perform
            // time the stub inflates transparently.
            if (fn.hash in performerFactoryHashes && fn.paramSlots.isNotEmpty()) {
                val stub = com.mezze.interpreter.bytecode.LazyStubRootNode(language, fn.hash, fn.name)
                compiledFunctions[fn.hash] = stub.callTarget
                language.getCachedStdlibCallTargetCache()[fn.hash] = stub.callTarget
            }
            if (fn.hash in performerOpHashes) {
                val stub = com.mezze.interpreter.bytecode.LazyStubRootNode(language, fn.hash, fn.name)
                compiledFunctions[fn.hash] = stub.callTarget
                language.getCachedStdlibCallTargetCache()[fn.hash] = stub.callTarget
            }
        }
        mark("performer factories compiled (${compiledFunctions.size})")

        // #391 Step 3 — eager whole-program compilation into the SlotTable.
        // Gated by MEZZE_EAGER=1 (default off). Populates language.slotTable
        // in parallel; today's lazy path still runs and is authoritative.
        // Step 4 flips callers to read from the slot table.
        if (!"0".equals(System.getenv("MEZZE_EAGER"))) {
            // Seed roots: entrypoints we can identify by name. FunctionIR has
            // no `pub` marker, so we can't reachability-prune at the module
            // level — we seed every top-level user function whose name
            // suggests it's an entry (main / __toplevel* / Mezze.toml-
            // configured entry). The walker still transitively catches
            // everything they reach.
            //
            // __entry__ is EXCLUDED — it's always compiled separately via
            // compileEntryFn (which uses a different BDSL entry-fn path
            // handling RegisterGlobal). Eagerly compiling it here too
            // wastes ~3-5ms on a duplicate that's never used.
            val roots = mutableSetOf<com.mezze.language.typedast.Hash>()
            for (fn in functions) {
                if (fn.name == MAIN_FN || fn.name.startsWith(TOPLEVEL_PREFIX)) {
                    roots.add(fn.hash)
                }
                if (entryHash != null && fn.hash == entryHash) roots.add(fn.hash)
            }
            // Performer factories/ops are NOT included as roots — they're
            // deferred to LazyStubRootNode and compile on first use.
            // Including them here would negate the lazy-stub startup savings
            // (performer compilation was the ~100ms bottleneck).
            if (roots.isEmpty() && functions.isNotEmpty()) roots.add(functions.last().hash)
            // A DEBUGGER NEEDS EVERY ROOT BUILT BEFORE THE PROGRAM RUNS.
            //
            // `setBreakpoints` arrives before evaluation, and Truffle resolves a
            // line breakpoint against whatever is loaded, then keeps that answer.
            // With only `__entry__` built, a breakpoint inside a function body
            // has no section to land on and resolves to the next statement in
            // __entry__ instead — measured: a breakpoint on line 2 of a two-line
            // function body resolved to the `let result = calc {…}` call on line
            // 5, and the debugger stopped there having never entered `calc`.
            //
            // Seeding by name (main / __toplevel*) is a startup optimisation:
            // everything else is deferred to LazyStubRootNode and built on first
            // call. That is right for running and wrong for debugging, so take
            // it only when instrumentation is off — a program being debugged
            // pays a few ms of compile to become breakpointable at all.
            if (MezzeLanguage.isBdslDebugInstrumentation()) {
                functions.forEach { roots.add(it.hash) }
            }
            language.eagerCompileReachable(roots, functions)
            mark("eager BDSL emitted into SlotTable (slotTable.size=${language.getSlotTable().size()})")
        }

        // Find the entry point and compile it.
        //
        // If a user entry function exists (the Mezze.toml entry, resolved to a
        // content hash at pack time), compile a call to it. This is the mez
        // dev / exec / build path. Selection is by hash, never by name.
        //
        // If no user entry, use __entry__ (tests/REPL wrapping of top-level
        // expressions) or fall back to the last function.
        val namedEntry = if (entryHash != null) functions.find { it.hash == entryHash } else null

        val mainFn: com.mezze.language.truffleir.FunctionIR
        // Nullable: a path with neither a registered buffer nor a readable
        // file has no Source, and SourceHelper says so rather than handing back
        // an empty one whose every line lookup fails further away.
        val source: com.oracle.truffle.api.source.Source?
        val bodyNode: com.mezze.interpreter.nodes.MezzeNode

        // #374 — helper: compile a FunctionIR's body through BDSL, via the
        // dedicated entry path that emits RegisterGlobal for top-level lets.
        // There is no fallback: the AST compiler was deleted in #391, so a
        // shape BDSL rejects raises an error naming the rejected node.
        fun bodyOf(fn: com.mezze.language.truffleir.FunctionIR): com.mezze.interpreter.nodes.MezzeNode {
            val entryTarget = language.compileEntryFn(fn)
            if (entryTarget != null) {
                // Wrap CallTarget in a tiny MezzeNode adapter: on execute,
                // calls the entry with a single empty record arg (matches
                // the closure calling convention TrampolineRootNode
                // expects: [captures..., params...]; entry has neither
                // captures nor params, so passing an empty record is a
                // no-op arg that BDSL ignores).
                //
                // `@TruffleBoundary` on execute: Kotlin generates
                // Intrinsics.throwParameterIsNullNPE for method dispatch,
                // whose Throwable.getStackTrace path reaches blocklisted
                // native-image runtime methods (StackTraceUtils, ...). This
                // wrapper is on the entry path (called ONCE per program
                // start), never a hot loop — boundary is free. See #402.
                return object : com.mezze.interpreter.nodes.MezzeNode() {
                    @Child
                    private var callNode: com.oracle.truffle.api.nodes.DirectCallNode =
                        com.oracle.truffle.api.nodes.DirectCallNode.create(entryTarget)
                    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
                    override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): Any {
                        return callNode.call(com.mezze.interpreter.values.MezzeRecord.empty())
                    }
                }
            }
            // Reaching here means BDSL is switched OFF — compileEntryFn now
            // throws on a shape it cannot compile, naming the rejected node, so
            // a null return has exactly one cause left. It used to cover both,
            // which is why #391's failures arrived as "an IR shape supports()
            // doesn't accept or emit() throws on" with no way to tell which.
            throw IllegalStateException(
                "Cannot compile entry-body for '${fn.name}' (hash=${fn.hash}, file=${fn.sourceFile}): " +
                "the bytecode compiler is disabled. Unset MEZZE_BDSL (or set it to 1) — " +
                "there is no other compiler to fall back to."
            )
        }

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
            val wrapperFn = functions.find { it.name == ENTRY_FN }
            mainFn = wrapperFn ?: namedEntry
            source = sourceHelper.getOrCreateSource(mainFn.sourceFile)

            // Run __entry__ (binds all globals via RegisterGlobal). Its return
            // value — the last decl's closure — is discarded by the enclosing
            // BlockNode; only the side effect (global registration) matters.
            val bindGlobalsNode = bodyOf(mainFn)

            // Read the entry function by hash (no auto-eval), then invoke with {}.
            val entryFnNode = com.mezze.interpreter.nodes.ReadGlobalFnByHashNode(namedEntry.hash)
            val emptyRecordNode = object : com.mezze.interpreter.nodes.MezzeNode() {
                // `@TruffleBoundary` — Kotlin object-expression execute
                // pulls Intrinsics.throwParameterIsNullNPE into PE
                // reachability, hitting native-image blocklisted methods.
                // Called ONCE per program entry (never hot). See #402.
                @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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
            mainFn = functions.find { it.name == ENTRY_FN } ?: functions.last()
            source = sourceHelper.getOrCreateSource(mainFn.sourceFile)
            bodyNode = bodyOf(mainFn)
        }

        // Build hash→name map for performer/handler lookup by name
        val hashToName = mutableMapOf<com.mezze.language.typedast.Hash, String>()
        for (fn in functions) {
            if (fn.name != LAMBDA_FN && fn.name != ENTRY_FN && !fn.name.startsWith(TOPLEVEL_PREFIX)) {
                hashToName[fn.hash] = fn.name
            }
        }

        // Add stdlib name mappings + performer records (both cached at Language level)
        val allPerformerRecords = performerRecords.toMutableList()
        hashToName.putAll(language.stdlibHashToName)
        allPerformerRecords.addAll(language.stdlibPerformerRecords)

        // HashModuleLoaderNode registers compiled functions by hash AND name
        val performerInfos = allPerformerRecords.toTypedArray()
        val withFunctions = HashModuleLoaderNode(bodyNode, compiledFunctions, hashToName, performerInfos)
        val wrapped = MezzeLanguage.createResultCapturingNode(withFunctions)

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
        sourceHelper.createSourceSection(source, mainFn.body.src)?.let {
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
