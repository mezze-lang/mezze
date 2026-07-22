package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.VariantAliases
import com.mezze.language.parsedast.Loc
import com.mezze.language.truffleir.*
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.typedast.*

/**
 * TypedASTCompiler — single-pass compiler from TypedAST to FunctionIR.
 *
 * Replaces Monomorphizer + Resolver + Lowerer with one pass.
 *
 * Design:
 * - Walks TypedExpr directly, emits FunctionIR/NodeIR
 * - Frame layout: params at 0..P-1, locals at P..P+L-1
 * - Stdlib references are CallDirect(hash) — not traced
 * - Method calls resolved to ability op hashes inline
 * - Lambdas extracted as separate FunctionIRs
 */
class TypedASTCompiler(
    /** All ability impls from all visible modules — for method call resolution */
    private val abilityImpls: List<TypedAbilityImpl>,
    /** Pre-lowered stdlib function hashes — calls to these are CallDirect, not traced */
    private val stdlibHashes: Set<Hash> = emptySet(),
    /**
     * Mono Phase 1 hybrid: per-call-site rewrites from the Monomorphizer.
     * When a call site is `(genericHash, typeArgs, abilityImpls)` and that
     * triple is in this map, we emit `CallDirectIR(specializedHash)` instead
     * of `CallDirectIR(genericHash)` + dict injection. Sites not in the map
     * keep the existing dict-passing path. Empty by default → mono is off.
     */
    private val monoCallSiteRewrites: Map<com.mezze.language.parsedast.Loc, Hash> = emptyMap(),
    private val preloweredFunctions: Map<Hash, FunctionIR> = emptyMap()
) {

    /** Ability operation index: methodName → list of (implHash, targetTypes, opHash) */
    private data class AbilityOp(
        val implHash: Hash,
        val targetTypes: List<MezzeType>,
        val opHash: Hash
    )
    private val abilityOpIndex = mutableMapOf<String, MutableList<AbilityOp>>()

    /** Extracted lambda functions */
    private val extractedFunctions = mutableListOf<FunctionIR>()

    /** Performer records from TypedPerformerDecls */
    private val performerRecords = mutableListOf<PerformerRecord>()

    /** Current function's param count (for Local→Param remapping) */
    private var currentParamCount = 0

    /** Next local slot index (starts after params) */
    private var nextLocalSlot = 0

    /** Local variable name → slot index (current scope) */
    private val localsByName = mutableMapOf<String, Int>()

    /** Scope stack for nested match bindings: each entry maps name → slot */
    private val scopeStack = ArrayDeque<MutableMap<String, Int>>()

    /** Current function's param names */
    private var currentParamNames = listOf<String>()

    /** Capture name → capture index (for the current lambda being compiled) */
    private var currentCaptureNames = mapOf<String, Int>()

    /** Number of captures in the current function — offsets param slots in the frame */
    private var currentCaptureCount = 0

    /** Hash of the function currently being compiled — used to detect self-recursive
     *  CallDirectIR sites where we statically know the callee's paramNames and can
     *  emit the scalar calling convention. */
    private var currentFunctionHash: Hash? = null

    /** Pre-populated at compile() start: hash → callee's paramNames (regular + dict),
     *  in callee declaration order. Used by emitCallDirect to decide scalar ABI for
     *  cross-function direct calls (extends self-recursive coverage from #61).
     *  Source: declIndex (user TypedLetDecls) ∪ preloweredFunctions. */
    private val hashToParamNames = mutableMapOf<Hash, List<String>>()

    /** Populated as we compile each function: hash → its lowered FunctionIR.
     *  Used by emitCallDirect to inline tiny callees directly into the caller's
     *  IR (eliminates the call entirely). Limited to functions compiled before
     *  the current one — self-recursion and forward refs stay on the call path. */
    private val hashToFunctionIR = mutableMapOf<Hash, FunctionIR>()

    /**
     * Tracks recursive let-bound lambdas inside do blocks.
     * When the lambda body references its own binding name, we resolve the reference
     * to MakeLambdaIR(self_hash, self_captures) instead of ReadCaptureIR — this avoids
     * the closure-captures-null problem.
     *
     * Entry: name -> (lambda_hash, capture_kinds for self-recursive MakeLambda)
     */
    private val recursiveLetLambdas = mutableMapOf<String, RecursiveLetInfo>()

    private data class RecursiveLetInfo(
        val hash: Hash,
        val captureKinds: List<SlotKind>
    )

    /** File table for source info */
    private val files = mutableSetOf<String>()

    /**
     * Pre-scanned: function hash → set of ability method names that need dictionary params.
     * Populated by scanForDictMethods() before compilation begins.
     * A function needs dict params when its body contains VarType method calls
     * to ability methods that require static resolution via dictionary passing.
     */
    private val dictMethodsForHash = mutableMapOf<Hash, Set<String>>()

    /**
     * Current function's dictionary param names (e.g., "__dict_to_dbg_str").
     * Set during compileFunctionBody when the function needs dict passing.
     * These are appended as extra params after the regular params.
     */
    private var currentDictParams = listOf<String>()

    private val census = CallSiteCensus()

    init {
        // Build ability operation index
        for (impl in abilityImpls) {
            for ((opName, _) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(
                    AbilityOp(impl.hash, impl.targetTypes, opHash)
                )
            }
        }
    }

    /**
     * Pre-scan all declarations (including ability impl operation bodies) to find
     * functions whose bodies contain VarType method calls to ability methods.
     * These functions will receive hidden dictionary params at compile time.
     *
     * Also transitively propagates dict requirements: if function A calls function B
     * which needs dict params, then A also needs those same dict params (to forward them).
     */
    private fun prescanForDictMethods(modules: List<TypedModule>) {
        dictMethodsForHash.clear()

        // Phase 1: find functions with direct VarType method calls
        val allDecls = mutableListOf<TypedLetDecl>()
        for (module in modules) {
            for (decl in module.decls) {
                if (decl is TypedLetDecl) {
                    allDecls.add(decl)
                    val value = decl.value
                    if (value is TypedLambda) {
                        val methods = scanExprForVarTypeMethodCalls(value.body)
                        if (methods.isNotEmpty()) {
                            dictMethodsForHash[decl.hash] = methods
                        }
                    }
                }
            }
        }

        // Phase 1b: scan ability impl operation bodies for VarType method calls.
        // Auto-generated codec impls have bodies like `fmt.encode_int { ... }` where
        // `fmt` is a VarType param. These need dict params just like regular functions.
        for (impl in abilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                val bodyExpr = if (opBody is TypedLambda) opBody.body else opBody
                val methods = scanExprForVarTypeMethodCalls(bodyExpr)
                if (methods.isNotEmpty()) {
                    dictMethodsForHash[opHash] = methods
                }
            }
        }

        // Phase 2: transitively propagate dict requirements.
        // If function A's body calls function B (by hash) and B needs dict
        // methods, A also needs those same dict methods (to forward them).
        //
        // Census shows ~9 distinct dict-path call sites total, so the
        // dependency graph between dict-needing functions is tiny. A real
        // fixpoint converges in 1-2 passes; we cap at MAX_PASSES as a
        // defensive guard against degenerate cases. If we ever hit the cap
        // it means there's a deep call chain that needs investigation.
        val maxPasses = 3
        var passes = 0
        var changed = true
        while (changed && passes < maxPasses) {
            changed = false
            passes++
            for (decl in allDecls) {
                val value = decl.value
                if (value is TypedLambda) {
                    val calleeDictMethods = scanExprForCallsToDictFunctions(value.body)
                    if (calleeDictMethods.isNotEmpty()) {
                        val existing = dictMethodsForHash[decl.hash] ?: emptySet()
                        val merged = existing + calleeDictMethods
                        if (merged.size > existing.size) {
                            dictMethodsForHash[decl.hash] = merged
                            changed = true
                        }
                    }
                }
            }
        }
        if (changed && System.getenv("MEZZE_DEBUG") != null) {
            System.err.println(
                "[prescanForDictMethods] hit cap of $maxPasses iterations — " +
                "deep dict-propagation chain detected, may indicate a bug"
            )
        }
    }

    /**
     * Scan an expression for calls to functions that need dictionary params.
     * Returns the set of dict method names required by those callees.
     */
    private fun scanExprForCallsToDictFunctions(expr: TypedExpr): Set<String> {
        val result = mutableSetOf<String>()
        scanExprForDictFunctionCalls(expr, result)
        return result
    }

    private fun scanExprForDictFunctionCalls(expr: TypedExpr, out: MutableSet<String>) {
        when (expr) {
            is TypedCall -> {
                val fn = expr.function
                // Check if the callee is a ByHash reference to a dict-needing function
                if (fn is TypedVar && fn.binding is BindingRef.ByHash) {
                    val calleeHash = (fn.binding as BindingRef.ByHash).hash
                    val calleeDictMethods = dictMethodsForHash[calleeHash]
                    if (calleeDictMethods != null) {
                        // Check if arg types can resolve the dict methods.
                        // If not (VarType args), the caller needs to forward its own dict params.
                        val concreteTypes = mutableListOf<MezzeType>()
                        if (expr.arg is TypedRecord) {
                            for (entry in (expr.arg as TypedRecord).entries) {
                                if (entry is TypedRecordEntry.Field) {
                                    concreteTypes.add(entry.value.type)
                                }
                            }
                        }
                        for (methodName in calleeDictMethods) {
                            val resolved = resolveOpHashFromConcreteTypes(methodName, concreteTypes)
                            if (resolved == null) {
                                // Can't resolve statically — need a dict param to forward
                                out.add(methodName)
                            }
                        }
                    }
                }
                if (fn is TypedFieldAccess) {
                    scanExprForDictFunctionCalls(fn.record, out)
                } else {
                    scanExprForDictFunctionCalls(fn, out)
                }
                scanExprForDictFunctionCalls(expr.arg, out)
            }
            is TypedLambda -> {
                // #195 B2: descend into nested lambdas so transitive dict-passing
                // sees calls inside HOF callbacks (e.g. seq::fold {f = {x} -> ...}).
                scanExprForDictFunctionCalls(expr.body, out)
            }
            is TypedBlock -> {
                for (s in expr.statements) {
                    when (s) {
                        is TypedStatement.Let -> scanExprForDictFunctionCalls(s.value, out)
                        is TypedStatement.Expr -> scanExprForDictFunctionCalls(s.expr, out)
                    }
                }
                scanExprForDictFunctionCalls(expr.result, out)
            }
            is TypedIf -> {
                scanExprForDictFunctionCalls(expr.condition, out)
                scanExprForDictFunctionCalls(expr.thenBranch, out)
                scanExprForDictFunctionCalls(expr.elseBranch, out)
            }
            is TypedMatch -> {
                scanExprForDictFunctionCalls(expr.scrutinee, out)
                for (c in expr.cases) {
                    c.guard?.let { scanExprForDictFunctionCalls(it, out) }
                    scanExprForDictFunctionCalls(c.body, out)
                }
            }
            is TypedRecord -> {
                for (e in expr.entries) {
                    when (e) {
                        is TypedRecordEntry.Field -> scanExprForDictFunctionCalls(e.value, out)
                        is TypedRecordEntry.Spread -> scanExprForDictFunctionCalls(e.expr, out)
                    }
                }
            }
            is TypedFieldAccess -> scanExprForDictFunctionCalls(expr.record, out)
            is TypedVariantConstruct -> expr.payload?.let { scanExprForDictFunctionCalls(it, out) }
            is TypedRecordUpdate -> {
                scanExprForDictFunctionCalls(expr.record, out)
                for (e in expr.updates) {
                    if (e is TypedRecordEntry.Field) scanExprForDictFunctionCalls(e.value, out)
                }
            }
            is TypedPerform -> scanExprForDictFunctionCalls(expr.body, out)
            is TypedPerformOp -> scanExprForDictFunctionCalls(expr.arg, out)
            is TypedPipe -> {
                scanExprForDictFunctionCalls(expr.left, out)
                scanExprForDictFunctionCalls(expr.right, out)
            }
            is TypedSeq -> {
                for (e in expr.elements) {
                    when (e) {
                        is TypedSeqElement.Regular -> scanExprForDictFunctionCalls(e.expr, out)
                        is TypedSeqElement.Spread -> scanExprForDictFunctionCalls(e.expr, out)
                    }
                }
            }
            is TypedStringLit -> {
                for (p in expr.parts) {
                    if (p is TypedStringPart.Interpolation) scanExprForDictFunctionCalls(p.expr, out)
                }
            }
            is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
            is TypedFracLit, is TypedBoolLit, is TypedCharLit -> {}
        }
    }

    /**
     * Recursively scan a TypedExpr for VarType method calls (i.e., calls where
     * the receiver is VarType and the method is a known ability operation).
     * Returns the set of ability method names found.
     */
    private fun scanExprForVarTypeMethodCalls(expr: TypedExpr): Set<String> {
        val result = mutableSetOf<String>()
        scanExprForVarTypeMethods(expr, result)
        return result
    }

    private fun scanExprForVarTypeMethods(expr: TypedExpr, out: MutableSet<String>) {
        when (expr) {
            is TypedCall -> {
                val fn = expr.function
                if (fn is TypedFieldAccess) {
                    val methodName = fn.field
                    val receiverType = fn.record.type
                    // If receiver is VarType and the method is a known ability op,
                    // only flag as needing dict if there are multiple candidates
                    // (ambiguous — can't resolve statically). Single candidate = resolved
                    // in compileCall without needing a dict param.
                    if (receiverType is VarType && methodName !in inlineableOps) {
                        val opCandidates = abilityOpIndex[methodName]
                        if (opCandidates != null && opCandidates.size > 1) {
                            out.add(methodName)
                        }
                    }
                    scanExprForVarTypeMethods(fn.record, out)
                } else {
                    scanExprForVarTypeMethods(fn, out)
                }
                scanExprForVarTypeMethods(expr.arg, out)
            }
            is TypedLambda -> {
                // #195 B2: descend into nested lambdas. When the inner body calls
                // `x.method` on a VarType receiver (e.g. inside a `seq::fold`
                // callback), the OUTER function holds the constraint and must
                // request `__dict_<method>` so the inner lambda can capture it
                // from the outer's frame. Skipping here used to make the inner
                // call fall through to plain field access at runtime → crash.
                scanExprForVarTypeMethods(expr.body, out)
            }
            is TypedBlock -> {
                for (s in expr.statements) {
                    when (s) {
                        is TypedStatement.Let -> scanExprForVarTypeMethods(s.value, out)
                        is TypedStatement.Expr -> scanExprForVarTypeMethods(s.expr, out)
                    }
                }
                scanExprForVarTypeMethods(expr.result, out)
            }
            is TypedIf -> {
                scanExprForVarTypeMethods(expr.condition, out)
                scanExprForVarTypeMethods(expr.thenBranch, out)
                scanExprForVarTypeMethods(expr.elseBranch, out)
            }
            is TypedMatch -> {
                scanExprForVarTypeMethods(expr.scrutinee, out)
                for (c in expr.cases) {
                    c.guard?.let { scanExprForVarTypeMethods(it, out) }
                    scanExprForVarTypeMethods(c.body, out)
                }
            }
            is TypedRecord -> {
                for (e in expr.entries) {
                    when (e) {
                        is TypedRecordEntry.Field -> scanExprForVarTypeMethods(e.value, out)
                        is TypedRecordEntry.Spread -> scanExprForVarTypeMethods(e.expr, out)
                    }
                }
            }
            is TypedFieldAccess -> scanExprForVarTypeMethods(expr.record, out)
            is TypedVariantConstruct -> expr.payload?.let { scanExprForVarTypeMethods(it, out) }
            is TypedRecordUpdate -> {
                scanExprForVarTypeMethods(expr.record, out)
                for (e in expr.updates) {
                    if (e is TypedRecordEntry.Field) scanExprForVarTypeMethods(e.value, out)
                }
            }
            is TypedPerform -> scanExprForVarTypeMethods(expr.body, out)
            is TypedPerformOp -> scanExprForVarTypeMethods(expr.arg, out)
            is TypedPipe -> {
                scanExprForVarTypeMethods(expr.left, out)
                scanExprForVarTypeMethods(expr.right, out)
            }
            is TypedSeq -> {
                for (e in expr.elements) {
                    when (e) {
                        is TypedSeqElement.Regular -> scanExprForVarTypeMethods(e.expr, out)
                        is TypedSeqElement.Spread -> scanExprForVarTypeMethods(e.expr, out)
                    }
                }
            }
            is TypedStringLit -> {
                for (p in expr.parts) {
                    if (p is TypedStringPart.Interpolation) scanExprForVarTypeMethods(p.expr, out)
                }
            }
            // Leaf nodes: no children
            is TypedVar, is TypedIntLit, is TypedNatLit, is TypedFloatLit,
            is TypedFracLit, is TypedBoolLit, is TypedCharLit -> {}
        }
    }

    data class CompileResult(
        val functions: List<FunctionIR>,
        val performerRecords: List<PerformerRecord>
    )

    data class PerformerRecord(
        val performerHash: String,
        val effectHash: String,
        val operationHashes: List<String>,
        val operationNames: List<String>
    )

    /**
     * Compile a TypedModule and its imports to FunctionIR list.
     * @param additionalModules imported modules to also compile (non-prelude)
     * @param additionalPerformers performers from imported modules
     * @param monoSpecializations Mono Phase 1 hybrid: specialized decls
     *   produced by [com.mezze.language.typechecker.mono.Monomorphizer].
     *   Each gets compiled to a FunctionIR alongside the originals — same
     *   path, just with concrete types and no dict params. Empty list →
     *   mono is off.
     */
    fun compile(
        module: TypedModule,
        additionalModules: List<TypedModule> = emptyList(),
        additionalPerformers: List<TypedPerformerDecl> = emptyList(),
        monoSpecializations: List<com.mezze.language.typechecker.mono.Monomorphizer.Specialization> = emptyList()
    ): CompileResult {
        extractedFunctions.clear()
        performerRecords.clear()
        files.clear()
        hashToFunctionIR.clear()
        for ((h, fnIR) in preloweredFunctions) {
            hashToFunctionIR[h] = fnIR
        }

        // Pre-scan all modules to discover which functions need dictionary params.
        // This must happen before compilation so call sites can inject dict fields.
        prescanForDictMethods(listOf(module) + additionalModules)

        // Populate the hash → paramNames map used for scalar-ABI decisions at
        // direct-call sites (see emitCallDirect). Must run AFTER dict prescan
        // so we can include the synthetic __dict_<method> params each callee needs.
        populateHashToParamNames(listOf(module) + additionalModules)

        val functions = mutableListOf<FunctionIR>()
        val compiledHashes = mutableSetOf<Hash>()

        // Compile entry module declarations
        for (decl in module.decls) {
            if (decl is TypedLetDecl && decl.hash !in stdlibHashes) {
                val fnIR = compileLetDecl(decl)
                functions.add(fnIR)
                hashToFunctionIR[fnIR.hash] = fnIR
                compiledHashes.add(decl.hash)
            }
        }

        // Compile imported module declarations (non-prelude, non-already-compiled)
        for (importedModule in additionalModules) {
            for (decl in importedModule.decls) {
                if (decl is TypedLetDecl && decl.hash !in stdlibHashes && decl.hash !in compiledHashes) {
                    val fnIR = compileLetDecl(decl)
                    functions.add(fnIR)
                    hashToFunctionIR[fnIR.hash] = fnIR
                    compiledHashes.add(decl.hash)
                }
            }
        }

        // Compile performer declarations from entry module + imports
        val allPerformers = module.performerDecls + additionalPerformers
        for (pd in allPerformers) {
            compilePerformerDecl(pd)
        }

        // Compile ability impl operations as standalone functions
        // These are needed for method calls like list.length, result.map, etc.
        val compiledOpHashes = compiledHashes.toMutableSet()
        for (impl in abilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                if (opHash in compiledOpHashes || opHash in stdlibHashes) continue
                compiledOpHashes.add(opHash)
                currentCaptureNames = emptyMap() // ability ops aren't closures
                if (opBody is TypedLambda) {
                    functions.add(compileFunctionBody(opHash, opName, opBody.params, opBody.body, opBody.loc))
                } else {
                    functions.add(compileFunctionBody(opHash, opName, emptyList(), opBody, opBody.loc))
                }
            }
        }

        // Compile mono specializations. Each is a wrapped lambda whose
        // body is the substituted generic body — same shape as a top-level
        // let, but with concrete types and no dict params (every dict was
        // pinned during specialization, so the body has direct CallDirectIRs
        // wherever it used to have method calls). The specializedHash is
        // what call sites in already-compiled bodies were rewritten to via
        // [monoCallSiteRewrites], so order doesn't matter for correctness.
        for (spec in monoSpecializations) {
            if (spec.specializedHash in compiledHashes) continue
            currentCaptureNames = emptyMap()
            val body = spec.specializedBody
            val fn = if (body is TypedLambda) {
                compileFunctionBody(spec.specializedHash, "", body.params, body.body, body.loc)
            } else {
                compileFunctionBody(spec.specializedHash, "", emptyList(), body, body.loc)
            }
            functions.add(fn)
            compiledHashes.add(spec.specializedHash)
        }

        // Add extracted lambdas. These are anonymous lambdas hoisted out
        // of expression positions (e.g. `Async.spawn { f = {} -> ... }`).
        // Each add-site appends unconditionally, so the same lambda body
        // can appear multiple times if user code repeats it (e.g. inside
        // a loop). We rely on the per-hash dedupe pass below to collapse
        // them — content-addressed lowering means structurally identical
        // bodies share a hash, so the duplicate rows are semantically the
        // same function, just compiled more than once.
        functions.addAll(extractedFunctions)

        if (System.getenv("MEZZE_DISPATCH_CENSUS") != null && census.total() > 0) {
            val total = census.total()
            val monoOwned = census.inlineOp + census.methodConcrete + census.methodSingleVar +
                census.byHashMono + census.byHashPlain + census.native + census.globalThunk
            val dictOwned = census.methodDictCallee + census.byHashDictInject
            fun pct(n: Int) = if (total == 0) 0.0 else 100.0 * n / total
            System.err.println(
                "[census] total=$total mono-direct=${monoOwned}(${"%.1f".format(pct(monoOwned))}%) " +
                "dict-path=${dictOwned}(${"%.1f".format(pct(dictOwned))}%) " +
                "indirect=${census.indirect}(${"%.1f".format(pct(census.indirect))}%)"
            )
            System.err.println(
                "[census]   inlineOp=${census.inlineOp} methodConcrete=${census.methodConcrete} " +
                "methodSingleVar=${census.methodSingleVar} byHashMono=${census.byHashMono} " +
                "byHashPlain=${census.byHashPlain} native=${census.native} globalThunk=${census.globalThunk}"
            )
            System.err.println(
                "[census]   methodDictCallee=${census.methodDictCallee} " +
                "byHashDictInject=${census.byHashDictInject}"
            )
        }

        // Per-hash dedupe. The compiler's three append phases (decls,
        // mono specializations, extracted lambdas) each have local
        // dedupe sets, but a hash CAN appear across phases (e.g. a
        // lambda hoisted from a mono'd body that happens to match an
        // extracted-lambda body). Content-addressed semantics: every
        // unique hash represents one function, regardless of how many
        // times the compiler emitted it. Downstream consumers
        // (StorePopulator, app_functions table) treat the list as a
        // set, so collapse it here at the source.
        //
        // First occurrence wins for ordering. This is the only place
        // where dedup happens — never let downstream rediscover it.
        val seenHashes = HashSet<Hash>(functions.size)
        val deduped = ArrayList<FunctionIR>(functions.size)
        for (fn in functions) {
            if (seenHashes.add(fn.hash)) deduped.add(fn)
        }

        // β-reduce `Mut.mutate { act = λ }` in every compiled function.
        // Mut is hermetic — the type system already proves no Mut value
        // escapes its handler region — so the effect-dispatch dance is
        // statically unobservable when LocalMut is on top of the chain.
        // After this pass, e.g. `incr1` directly calls `__native_mut_int_incr`
        // with no OpCallNode, no fiber, no marker. Same function hash; only
        // the body changes. The lookup map seeds from PrecompiledStdlib so
        // act-lambdas in already-precompiled stdlib functions resolve.
        val erasureMap = HashMap<Hash, FunctionIR>(deduped.size + 1200)
        for ((h, fnIR) in preloweredFunctions) erasureMap[h] = fnIR
        for (fn in deduped) erasureMap[fn.hash] = fn
        val erasedMap = com.mezze.language.truffleir.MutEraser.erase(erasureMap)
        // Trivial-wrapper inlining for user code. Stdlib already
        // inlined its own intra-stdlib calls at precompile time; this
        // pass picks up user-side calls into stdlib wrappers. Disable
        // with MEZZE_INLINE_WRAPPERS=0. See TrivialWrapperInliner.
        val inlinedMap = if (System.getenv("MEZZE_INLINE_WRAPPERS") == "0") erasedMap
            else com.mezze.language.truffleir.TrivialWrapperInliner.inline(erasedMap)
        // Dead-let elimination is opt-in: MEZZE_DEAD_LET_ELIMINATION=1.
        // The pass exists but its purity check needs work — see
        // DeadLetEliminator's file comment for the design notes.
        val cleanedMap = if (System.getenv("MEZZE_DEAD_LET_ELIMINATION") == "1")
            com.mezze.language.truffleir.DeadLetEliminator.eliminate(inlinedMap)
            else inlinedMap
        // CPS lowering (Path C Phase 1). Off by default via MEZZE_CPS=0.
        // Also honours the `mezze.cps` system property so tests can toggle
        // the pipeline per-run without mutating the process env.
        // Identity transform until Phase 1.4 lands the real rewrite.
        val cpsMap = if (cpsEnabled())
            cleanedMap.mapValues { (_, fn) -> com.mezze.language.truffleir.CpsLowering.lower(fn) }
            else cleanedMap
        val final = deduped.map { cpsMap[it.hash] ?: it }
        return CompileResult(final, performerRecords.toList())
    }

    private fun compileLetDecl(decl: TypedLetDecl): FunctionIR {
        files.add(decl.loc.file)
        // Top-level functions don't have captures — reset capture context
        currentCaptureNames = emptyMap()

        val value = decl.value
        return if (value is TypedLambda) {
            // Function: unwrap lambda, use its params directly
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = value.params,
                body = value.body,
                loc = decl.loc
            )
        } else if (value is TypedVar && value.binding is BindingRef.ByHash) {
            // Function alias: `toggle_bool = toggle`
            // Emit a no-op FunctionIR whose body just reads the target from context.
            // Then register toggle_bool's hash → toggle's hash as an alias in the runtime.
            // This produces a thunk that returns the target function — CalledIR sites for
            // toggle_bool will use CallDirectIR(toggle_bool_hash, args). The MonomorphicCallNode
            // will resolve toggle_bool_hash, find the thunk, evaluate it to get the toggle
            // MezzeFunction, and then the InvokeNode will call that function with the args.
            val targetHash = (value.binding as BindingRef.ByHash).hash
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = emptyList(),
                body = value, // compileExpr will emit ReadContextIR(targetHash) for ByHash
                loc = decl.loc
            )
        } else {
            // Value (thunk): no params
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = emptyList(),
                body = value,
                loc = decl.loc
            )
        }
    }

    private fun compileFunctionBody(
        hash: Hash,
        name: String,
        params: List<TypedParam>,
        body: TypedExpr,
        loc: Loc,
        captureInfo: List<Pair<String, SlotKind>> = emptyList()
    ): FunctionIR {
        // Save and reset state
        val prevParamCount = currentParamCount
        val prevNextLocal = nextLocalSlot
        val prevLocals = localsByName.toMap()
        val prevParamNames = currentParamNames
        val prevCaptureNames = currentCaptureNames
        val prevCaptureCount = currentCaptureCount
        val prevDictParams = currentDictParams
        val prevFunctionHash = currentFunctionHash
        currentFunctionHash = hash

        // Check if this function needs dictionary params (pre-scanned).
        // Dictionary params are hidden params appended after regular params,
        // named __dict_<methodName>. They carry the ability op function
        // (as a MezzeFunction) so VarType method calls can use DictMethodCallIR
        // for statically-resolved indirect dispatch.
        val dictMethods = dictMethodsForHash[hash] ?: emptySet()
        val dictParamNames = dictMethods.sorted().map { com.mezze.language.ast.MagicParams.dictParamName(it) }
        currentDictParams = dictParamNames

        // All params = regular params + dict params
        val allParamNames = params.map { it.name } + dictParamNames

        currentParamCount = allParamNames.size
        // Locals start after captures + params:
        // Frame layout: [capture0..captureN, param0..paramM, dictParam0..dictParamD, local0..localK]
        val captureCount = captureInfo.size
        currentCaptureCount = captureCount
        nextLocalSlot = captureCount + allParamNames.size
        localsByName.clear()
        currentParamNames = allParamNames
        // Note: currentCaptureNames is set by compileLambda BEFORE calling this.
        // For non-lambda functions (called from compileLetDecl), it should be empty.

        // Compile body
        val bodyIR = compileExpr(body)

        // Collect slot info — dict params are OBJECT (they hold MezzeFunction values)
        val paramSlots = params.map { typeToSlotKind(it.type) } + dictParamNames.map { SlotKind.OBJECT }
        val paramNames = allParamNames
        val localCount = nextLocalSlot - captureCount - allParamNames.size
        val localSlots = (0 until localCount).map { SlotKind.OBJECT }
        val localNames = (0 until localCount).map { i ->
            localsByName.entries.find { it.value == captureCount + allParamNames.size + i }?.key ?: "local_$i"
        }

        // Restore state
        currentParamCount = prevParamCount
        nextLocalSlot = prevNextLocal
        localsByName.clear()
        localsByName.putAll(prevLocals)
        currentCaptureNames = prevCaptureNames
        currentParamNames = prevParamNames
        currentCaptureCount = prevCaptureCount
        currentDictParams = prevDictParams
        currentFunctionHash = prevFunctionHash

        val fnIR = FunctionIR(
            hash = hash,
            name = name,
            qualifiedName = name,
            paramSlots = paramSlots,
            paramNames = paramNames,
            localSlots = localSlots,
            localNames = localNames,
            captureSlots = captureInfo.map { it.second },
            captureNames = captureInfo.map { it.first },
            body = bodyIR,
            sourceFile = loc.file,
            sourceSection = locToSourceInfo(loc)
        )
        return fnIR
    }

    private fun compilePerformerDecl(pd: TypedPerformerDecl) {
        val opHashes = mutableListOf<String>()
        val opNames = mutableListOf<String>()
        val isParameterized = pd.params.isNotEmpty()

        if (isParameterized) {
            // For parameterized performers (e.g., SeededRandom { seed: Int }),
            // compile a FACTORY FUNCTION that takes the params record and returns
            // a performer record with operation closures that capture the params.
            //
            // The factory function body is:
            //   { seed } -> { initialize_scope = ..., random_float = ..., random_int = ..., ... }
            //
            // Each operation becomes a MakeLambdaIR (closure) that captures the params.
            val performerParams = pd.params.map { com.mezze.language.typedast.TypedParam(it.name, it.type, it.loc) }

            // Save/reset state for the factory function scope
            val prevParamCount = currentParamCount
            val prevNextLocal = nextLocalSlot
            val prevLocals = localsByName.toMap()
            val prevParamNames = currentParamNames
            val prevCaptureNames = currentCaptureNames
            val prevCaptureCount = currentCaptureCount

            currentParamCount = performerParams.size
            currentCaptureCount = 0
            nextLocalSlot = performerParams.size
            localsByName.clear()
            currentParamNames = performerParams.map { it.name }
            currentCaptureNames = emptyMap()

            // Compile each operation as a MakeLambdaIR (closure capturing params)
            val fieldIRs = mutableListOf<FieldIR>()
            for ((opName, opBody) in pd.operations) {
                val opHash = pd.operationHashes[opName] ?: Hash.ZERO
                opHashes.add(opHash.hex)
                opNames.add(opName)

                // Determine operation params and body
                val opParams: List<com.mezze.language.typedast.TypedParam>
                val opBodyExpr: TypedExpr
                if (opBody is TypedLambda) {
                    opParams = opBody.params
                    opBodyExpr = opBody.body
                } else {
                    opParams = emptyList()
                    opBodyExpr = opBody
                }

                // The capture info: each performer param is a capture from the factory scope
                val captureInfo = performerParams.mapIndexed { idx, p ->
                    p.name to typeToSlotKind(p.type)
                }

                // Set currentCaptureNames so compileVar can resolve captures correctly.
                // compileFunctionBody doesn't set this — it's normally set by compileLambda.
                val prevCaptureNames2 = currentCaptureNames
                currentCaptureNames = captureInfo.mapIndexed { idx, (name, _) -> name to idx }.toMap()

                // Compile the operation function with captures
                val fnIR = compileFunctionBody(
                    hash = opHash,
                    name = opName,
                    params = opParams,
                    body = opBodyExpr,
                    loc = opBody.loc,
                    captureInfo = captureInfo
                )
                extractedFunctions.add(fnIR)

                // Restore capture names
                currentCaptureNames = prevCaptureNames2

                // Create MakeLambdaIR that captures the params from the factory scope
                val captures = performerParams.mapIndexed { idx, p ->
                    // The param is at slot idx in the factory function's frame
                    CaptureIR(idx, typeToSlotKind(p.type))
                }
                val lambdaIR = MakeLambdaIR(opHash, captures, locToSourceInfo(opBody.loc))
                fieldIRs.add(FieldIR(opName, lambdaIR))
            }

            // The factory function body: return a record of operation closures
            val recordIR = MakeRecordIR(fieldIRs, locToSourceInfo(pd.loc))

            // Restore state
            currentParamCount = prevParamCount
            nextLocalSlot = prevNextLocal
            localsByName.clear()
            localsByName.putAll(prevLocals)
            currentCaptureNames = prevCaptureNames
            currentParamNames = prevParamNames
            currentCaptureCount = prevCaptureCount

            // Create the factory FunctionIR
            val factoryFnIR = FunctionIR(
                hash = pd.hash,
                name = pd.name,
                qualifiedName = pd.name,
                paramSlots = performerParams.map { typeToSlotKind(it.type) },
                paramNames = performerParams.map { it.name },
                localSlots = emptyList(),
                localNames = emptyList(),
                captureSlots = emptyList(),
                captureNames = emptyList(),
                body = recordIR,
                sourceFile = pd.loc.file,
                sourceSection = locToSourceInfo(pd.loc)
            )
            extractedFunctions.add(factoryFnIR)

            performerRecords.add(PerformerRecord(
                performerHash = pd.hash.hex,
                effectHash = pd.effectHash.hex,
                operationHashes = opHashes,
                operationNames = opNames
            ))
        } else {
            // Non-parameterized performer: compile operations as standalone functions
            for ((opName, opBody) in pd.operations) {
                val opHash = pd.operationHashes[opName] ?: Hash.ZERO
                opHashes.add(opHash.hex)
                opNames.add(opName)

                // Compile operation as standalone function
                if (opBody is TypedLambda) {
                    extractedFunctions.add(compileFunctionBody(
                        hash = opHash,
                        name = opName,
                        params = opBody.params,
                        body = opBody.body,
                        loc = opBody.loc
                    ))
                } else {
                    extractedFunctions.add(compileFunctionBody(
                        hash = opHash,
                        name = opName,
                        params = emptyList(),
                        body = opBody,
                        loc = opBody.loc
                    ))
                }
            }

            performerRecords.add(PerformerRecord(
                performerHash = pd.hash.hex,
                effectHash = pd.effectHash.hex,
                operationHashes = opHashes,
                operationNames = opNames
            ))
        }
    }

    private fun compileExpr(expr: TypedExpr): NodeIR {
        val src = locToSourceInfo(expr.loc)
        return when (expr) {
            // Literals
            is TypedIntLit -> LongLitIR(expr.value, src)
            is TypedNatLit -> LongLitIR(expr.value, src)
            is TypedFloatLit -> DoubleLitIR(expr.value, src)
            is TypedFracLit -> FracLitIR(expr.numerator, expr.denominator, src)
            is TypedBoolLit -> BoolLitIR(expr.value, src)
            is TypedCharLit -> CharLitIR(expr.value, src)
            is TypedStringLit -> {
                if (expr.parts.size == 1 && expr.parts[0] is TypedStringPart.Literal) {
                    StringLitIR((expr.parts[0] as TypedStringPart.Literal).value, src)
                } else {
                    InterpolatedStringIR(expr.parts.map { p ->
                        when (p) {
                            is TypedStringPart.Literal -> StringPartIR.Lit(p.value)
                            is TypedStringPart.Interpolation -> StringPartIR.Expr(compileExpr(p.expr))
                        }
                    }, src)
                }
            }

            // Variables
            is TypedVar -> compileVar(expr, src)

            // Lambda
            is TypedLambda -> compileLambda(expr, src)

            // Function call
            is TypedCall -> compileCall(expr, src)

            // Record
            is TypedRecord -> MakeRecordIR(
                expr.entries.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> FieldIR(entry.name, compileExpr(entry.value))
                        is TypedRecordEntry.Spread -> FieldIR("__spread__", compileExpr(entry.expr))
                    }
                }, src
            )

            is TypedFieldAccess -> ReadFieldIR(compileExpr(expr.record), expr.field, expr.fieldIndex, src)

            is TypedRecordUpdate -> RecordUpdateIR(
                compileExpr(expr.record),
                expr.updates.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> FieldIR(entry.name, compileExpr(entry.value))
                        is TypedRecordEntry.Spread -> FieldIR("__spread__", compileExpr(entry.expr))
                    }
                }, src
            )

            // Variant
            is TypedVariantConstruct -> {
                val tag = expr.tag.hashCode() and 0x7FFFFFFF
                MakeVariantIR(tag, expr.tag, expr.payload?.let { compileExpr(it) }, src)
            }

            // Match
            is TypedMatch -> compileMatch(expr, src)

            // If
            is TypedIf -> IfIR(compileExpr(expr.condition), compileExpr(expr.thenBranch), compileExpr(expr.elseBranch), src)

            // Block
            is TypedBlock -> compileBlock(expr, src)

            // Seq
            is TypedSeq -> MakeSeqIR(
                expr.elements.map { elem ->
                    when (elem) {
                        is TypedSeqElement.Regular -> SeqElementIR.Regular(compileExpr(elem.expr))
                        is TypedSeqElement.Spread -> SeqElementIR.Spread(compileExpr(elem.expr))
                    }
                }, src
            )

            // Effects
            is TypedPerform -> HandleIR(
                compileExpr(expr.body),
                expr.handlers.map { h ->
                    HandlerBindIR(h.performerHash, h.effectHash, h.performerName, h.params?.let { compileExpr(it) })
                }, src
            )
            is TypedPerformOp -> PerformOpIR(expr.effectHash, expr.operationName, compileExpr(expr.arg), src)

            // Pipe (should be desugared, but handle gracefully)
            is TypedPipe -> {
                val rightIR = compileExpr(expr.right)
                val leftIR = compileExpr(expr.left)
                CallIndirectIR(rightIR, leftIR, false, src)
            }
        }
    }

    /**
     * #284: Decide whether a top-level binding by hash is a real function
     * definition (RHS is an explicit lambda) vs an alias or value thunk.
     * Used at name-as-value read sites to pick `ReadGlobalFnByHashIR`
     * (no auto-eval) for true lambdas, vs `ReadGlobalByHashIR` (auto-eval)
     * for value thunks and aliases.
     *
     * Only user-module decls are inspected. Stdlib references fall through
     * to the auto-evaluating path; that's safe because no current stdlib
     * pattern exposes a named user-callable thunk in value position to a
     * native spawn-like API. If such a pattern is introduced later this
     * check would need to extend to PrecompiledStdlib, but the obvious
     * heuristic (paramSlots.isNotEmpty) breaks a large class of legitimate
     * stdlib value thunks — needs a sharper signal.
     */
    private fun isLambdaHash(hash: com.mezze.language.typedast.Hash, decl: com.mezze.language.typedast.TypedLetDecl?): Boolean {
        return decl != null && decl.value is TypedLambda
    }

    private fun compileVar(expr: TypedVar, src: SourceInfo): NodeIR {
        return when (val binding = expr.binding) {
            is BindingRef.Local -> {
                // Names contributed by the effect-handler runtime inside op
                // handler bodies (`scope`, `resume`, `abort`). See
                // EffectBindingIR / EffectBindingNode for the lookup rules.
                if (expr.name == ScopedEffectConstants.SCOPE
                    || expr.name == ScopedEffectConstants.RESUME
                    || expr.name == ScopedEffectConstants.ABORT
                ) {
                    return EffectBindingIR(expr.name, src)
                }

                // RECURSIVE LET: If this references a recursive let-bound lambda from
                // an outer scope, recreate the lambda inline. The lambda's captures
                // are accessible from inside its own body via ReadCaptureIR.
                // This avoids the closure-captures-null problem.
                val recursiveLet = recursiveLetLambdas[expr.name]
                if (recursiveLet != null && binding.depth > 0) {
                    val captures = recursiveLet.captureKinds.mapIndexed { idx, kind ->
                        CaptureIR(idx, kind)
                    }
                    return MakeLambdaIR(recursiveLet.hash, captures, src)
                }

                // Check if this is a captured variable.
                // For lambdas: depth > 0 means crossing a lambda boundary.
                // For performer operation functions: depth may be 0 but the variable is
                // listed in currentCaptureNames because it's a performer param captured
                // from the factory function's scope.
                val captureIdx = currentCaptureNames[expr.name]
                if (captureIdx != null && (binding.depth > 0 || currentCaptureCount > 0)) {
                    return ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), src)
                }
                // Use depth to find the right scope
                if (binding.depth == 0 && scopeStack.isNotEmpty()) {
                    // Innermost scope (current match bindings)
                    val scope = scopeStack.last()
                    val slot = scope[expr.name]
                    if (slot != null) {
                        return ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                    }
                }
                if (binding.depth > 0 && scopeStack.size > binding.depth) {
                    // Outer scope — look up by depth from the end
                    val scopeIdx = scopeStack.size - 1 - binding.depth
                    if (scopeIdx >= 0) {
                        val scope = scopeStack[scopeIdx]
                        val slot = scope[expr.name]
                        if (slot != null) {
                            return ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                        }
                    }
                }
                // Check if it's a param — params start after captures in the frame
                val paramIdx = currentParamNames.indexOf(expr.name)
                if (paramIdx >= 0) {
                    ReadLocalIR(currentCaptureCount + paramIdx, typeToSlotKind(expr.type), src)
                } else {
                    // Check any scope level
                    val slot = localsByName[expr.name]
                    if (slot != null) {
                        ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                    } else {
                        // If depth > 0 and not found locally, check if it's a top-level
                        // declaration. This handles the case where a top-level function
                        // references a sibling top-level binding (e.g., `test` calling
                        // `spawn` where both are defined at module scope). The typechecker
                        // gives these BindingRef.Local(depth>0) but at the IR level they
                        // should be resolved by hash, not allocated as phantom local slots.
                        if (binding.depth > 0) {
                            val topDecl = declIndex?.values?.find { it.name == expr.name }
                            if (topDecl != null) {
                                // #284: if the top-level declaration's RHS is an
                                // explicit lambda, the user wants the function
                                // reference, not the auto-evaluated result.
                                // Emit the no-eval form so passing the name
                                // as a value (e.g., `Async.spawn { f = grandchild }`)
                                // hands back the MezzeFunction, not the body result.
                                // Aliases (`let toggle_bool = toggle`) and value
                                // thunks (`let pi = 3.14`) still go via the auto-
                                // evaluating path so they cache properly.
                                return if (isLambdaHash(topDecl.hash, topDecl)) {
                                    ReadGlobalFnByHashIR(topDecl.hash, src)
                                } else {
                                    ReadGlobalByHashIR(topDecl.hash, src)
                                }
                            }
                        }
                        val newSlot = allocLocal(expr.name)
                        ReadLocalIR(newSlot, typeToSlotKind(expr.type), src)
                    }
                }
            }
            is BindingRef.Param -> {
                // Check if this Param name is actually a capture (e.g., performer params
                // that are compiled as captures of operation functions)
                val captureIdx = currentCaptureNames[expr.name]
                if (captureIdx != null) {
                    ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), src)
                } else {
                    ReadLocalIR(currentCaptureCount + binding.index, typeToSlotKind(expr.type), src)
                }
            }
            is BindingRef.Capture -> ReadCaptureIR(binding.index, typeToSlotKind(expr.type), src)
            is BindingRef.ByHash -> {
                // Check if it's a local variable or param first
                val slot = localsByName[expr.name]
                if (slot != null) {
                    ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                } else {
                    val paramIdx = currentParamNames.indexOf(expr.name)
                    if (paramIdx >= 0) {
                        ReadLocalIR(currentCaptureCount + paramIdx, typeToSlotKind(expr.type), src)
                    } else {
                        // Check captures (for ByHash refs inside lambdas)
                        val captureIdx = currentCaptureNames[expr.name]
                        if (captureIdx != null) {
                            ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), src)
                        } else {
                            // #284: same logic as the BindingRef.Local site above —
                            // if the top-level RHS is an explicit lambda, the
                            // user wants the function reference. Aliases and
                            // value thunks go through the auto-evaluating path.
                            if (isLambdaHash(binding.hash, declIndex?.get(binding.hash))) {
                                ReadGlobalFnByHashIR(binding.hash, src)
                            } else {
                                ReadGlobalByHashIR(binding.hash, src)
                            }
                        }
                    }
                }
            }
            is BindingRef.Native -> CallNativeIR(binding.name, UnitLitIR(src), src)
        }
    }

    /** Compile a receiver expression for a method call. Global ByHash references
     *  are auto-called (thunk evaluation) since top-level values like `trig::pi`
     *  are compiled as zero-param FunctionIRs that need to be called to get the value. */
    private fun compileReceiverExpr(expr: TypedExpr): NodeIR {
        if (expr is TypedVar && expr.binding is BindingRef.ByHash) {
            val binding = expr.binding as BindingRef.ByHash
            // Check if it's a local/param/capture first
            val slot = localsByName[expr.name]
            if (slot != null) return ReadLocalIR(slot, typeToSlotKind(expr.type), locToSourceInfo(expr.loc))
            val paramIdx = currentParamNames.indexOf(expr.name)
            if (paramIdx >= 0) return ReadLocalIR(currentCaptureCount + paramIdx, typeToSlotKind(expr.type), locToSourceInfo(expr.loc))
            val captureIdx = currentCaptureNames[expr.name]
            if (captureIdx != null) return ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), locToSourceInfo(expr.loc))
            // Global reference — auto-call thunk to get value
            census.globalThunk++
            return CallDirectIR(binding.hash, listOf(UnitLitIR(locToSourceInfo(expr.loc))), false, locToSourceInfo(expr.loc))
        }
        return compileExpr(expr)
    }

    private fun compileCall(expr: TypedCall, src: SourceInfo): NodeIR {
        val fn = expr.function
        val argIR = compileArgWithDictWrapping(expr.arg, src)

        // Check for method call: receiver.method { args }
        if (fn is TypedFieldAccess) {
            val methodName = fn.field
            val receiverType = fn.record.type

            // Try inline ops for primitives
            val inlineOp = tryInlineOp(methodName, receiverType)
            if (inlineOp != null) {
                val receiverIR = compileReceiverExpr(fn.record)
                // Extract 'r' or 'it' field from arg record for binary ops
                census.inlineOp++
                return compileInlineOp(inlineOp, receiverIR, argIR, src)
            }

            // Try ability method resolution. For concrete types, resolve directly.
            // For VarType, try single-candidate resolution (monomorphized code where
            // the type variable wasn't fully substituted but only one impl exists).
            val opHashConcrete = tryResolveAbilityOp(methodName, receiverType)
            val opHash = opHashConcrete
                ?: if (receiverType is VarType) {
                    val candidates = abilityOpIndex[methodName]
                    if (candidates != null && candidates.size == 1) candidates.first().opHash else null
                } else null
            if (opHash != null) {
                if (opHashConcrete != null) census.methodConcrete++ else census.methodSingleVar++
                val receiverIR = compileReceiverExpr(fn.record)
                // Build arg with receiver injected
                val opBody = findAbilityOpBody(methodName, receiverType)
                val receiverParamName = if (opBody is TypedLambda) {
                    opBody.params.firstOrNull()?.name ?: Keywords.IT
                } else Keywords.IT

                // Inject receiver into argument record
                val fields = mutableListOf<FieldIR>()
                if (expr.arg is TypedRecord) {
                    for (entry in (expr.arg as TypedRecord).entries) {
                        if (entry is TypedRecordEntry.Field) {
                            fields.add(FieldIR(entry.name, compileExpr(entry.value)))
                        }
                    }
                }
                if (fields.none { it.name == receiverParamName }) {
                    fields.add(0, FieldIR(receiverParamName, receiverIR))
                }
                // Check if the resolved op needs dict params (e.g., codec impl bodies
                // that call `fmt.encode_int` where `fmt` is a VarType param internally).
                val opDictMethods = dictMethodsForHash[opHash]
                if (opDictMethods != null && opDictMethods.isNotEmpty()) {
                    // Collect concrete types from all fields for resolution
                    val concreteTypesForDict = mutableListOf(receiverType)
                    if (expr.arg is TypedRecord) {
                        for (entry in (expr.arg as TypedRecord).entries) {
                            if (entry is TypedRecordEntry.Field) {
                                concreteTypesForDict.add(entry.value.type)
                            }
                        }
                    }
                    for (dictMethodName in opDictMethods.sorted()) {
                        val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(dictMethodName)
                        val resolvedHash = resolveOpHashFromConcreteTypes(dictMethodName, concreteTypesForDict)
                        if (resolvedHash != null) {
                            fields.add(FieldIR(dictParamName, ReadGlobalFnByHashIR(resolvedHash, src)))
                        } else {
                            // Forward from caller's dict params if available
                            val callerDictIdx = currentParamNames.indexOf(dictParamName)
                            if (callerDictIdx >= 0) {
                                val slot = currentCaptureCount + callerDictIdx
                                fields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                            }
                        }
                    }
                }
                val recordIR = MakeRecordIR(fields, src)
                // #195 mono-fix3: when the monomorphizer specialized this
                // method call, redirect to the specialized hash. The audit
                // (a38ab6c0 / project_mono_blockers.md) flagged this path as
                // "dead writes": MonoDriver.seedFromMethodCall produces specs
                // for ability-method call sites, but the compiler never
                // consulted monoCallSiteRewrites here — so every method-call
                // spec went to waste. Mirror the ByHash branch's check.
                //
                // Phase B3: route through emitCallDirect so scalar ABI fires
                // when the impl's param names match (the common case after
                // monomorphization). Falls back to record path on arity
                // mismatch or unknown param names. (#273)
                val specialized = monoCallSiteRewrites[expr.loc]
                val effectiveHash = specialized ?: opHash
                if (specialized != null) census.methodConcrete++
                return emitCallDirect(effectiveHash, recordIR, expr.isTailCall, src)
            }

            // VarType receiver — try dictionary passing first, then fall back to dynamic dispatch.
            if (receiverType is VarType && methodName !in inlineableOps) {
                val opCandidates = abilityOpIndex[methodName]
                if (opCandidates != null && opCandidates.isNotEmpty()) {
                    val receiverIR = compileReceiverExpr(fn.record)
                    // Build arg with receiver injected as "it"
                    val fields = mutableListOf<FieldIR>()
                    if (expr.arg is TypedRecord) {
                        for (entry in (expr.arg as TypedRecord).entries) {
                            if (entry is TypedRecordEntry.Field) {
                                fields.add(FieldIR(entry.name, compileExpr(entry.value)))
                            }
                        }
                    }
                    if (fields.none { it.name == Keywords.IT }) {
                        fields.add(0, FieldIR(Keywords.IT, receiverIR))
                    }

                    // Transitive dict injection: the impl we'll dispatch to via the
                    // outer caller's __dict_<method> slot may itself need dict params
                    // (e.g. `impl EncodeRepr for Str`'s body `fmt.encode_string {val=it}`
                    // needs __dict_encode_string against whatever `fmt` resolves to).
                    // We don't know the exact impl at this call site, but every impl
                    // candidate's transitive dict requirements can be unioned, and
                    // resolved against the concrete types of the user-visible record
                    // fields (`fmt`, etc.). Mirrors the concrete-receiver path above.
                    val transitiveDictMethods = HashSet<String>()
                    for (candidate in opCandidates) {
                        dictMethodsForHash[candidate.opHash]?.let { transitiveDictMethods.addAll(it) }
                    }
                    if (transitiveDictMethods.isNotEmpty()) {
                        val concreteTypesForDict = mutableListOf<MezzeType>()
                        if (expr.arg is TypedRecord) {
                            for (entry in (expr.arg as TypedRecord).entries) {
                                if (entry is TypedRecordEntry.Field) {
                                    concreteTypesForDict.add(entry.value.type)
                                }
                            }
                        }
                        for (dictMethodName in transitiveDictMethods.sorted()) {
                            val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(dictMethodName)
                            // Don't double-inject if the user already wrote this field.
                            if (fields.any { it.name == dictParamName }) continue
                            val resolvedHash = resolveOpHashFromConcreteTypes(dictMethodName, concreteTypesForDict)
                            if (resolvedHash != null) {
                                fields.add(FieldIR(dictParamName, ReadGlobalFnByHashIR(resolvedHash, src)))
                            } else {
                                // Forward from caller's own dict params (caller is itself polymorphic
                                // on the same format type).
                                val callerDictIdx = currentParamNames.indexOf(dictParamName)
                                if (callerDictIdx >= 0) {
                                    val slot = currentCaptureCount + callerDictIdx
                                    fields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                                }
                            }
                        }
                    }

                    val recordIR = MakeRecordIR(fields, src)

                    // Dictionary passing: if the current function has a __dict_<method> param,
                    // read the resolved impl function from the frame and call it directly.
                    // No runtime dispatch — the typechecker resolved everything statically.
                    val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(methodName)
                    val dictParamIdx = currentParamNames.indexOf(dictParamName)
                    if (dictParamIdx >= 0) {
                        val dictSlot = currentCaptureCount + dictParamIdx
                        census.methodDictCallee++
                        return DictMethodCallIR(dictSlot, recordIR, src)
                    }
                    // #195 B3: also look in captures. A nested lambda may have
                    // gained `__dict_<m>` via synthetic closure capture (see
                    // compileLambda) when its parent function holds the dict.
                    // Capture slots live at frame indices 0..captureCount-1.
                    val dictCaptureIdx = currentCaptureNames[dictParamName]
                    if (dictCaptureIdx != null) {
                        census.methodDictCallee++
                        return DictMethodCallIR(dictCaptureIdx, recordIR, src)
                    }

                    // VarType receiver, multi-candidate, no dict param —
                    // census shows count=0 across the full test corpus, so
                    // this path is unreachable. If it ever fires, mono is
                    // still active downstream and Phase-1 specialization
                    // will pick it up; otherwise we'd reach the indirect
                    // call path below. Removed in #270.
                }
            }
        }

        // Regular call — check if callee needs dictionary params
        val fnIR = compileExpr(fn)
        if (fn is TypedVar) {
            val binding = fn.binding
            return when (binding) {
                is BindingRef.ByHash -> {
                    val calleeHash = binding.hash

                    // Mono Phase 1 hybrid: if the monomorphizer specialized
                    // this call site, redirect to the specialized hash. The
                    // specialized blob has concrete types throughout and
                    // does NOT take dict params — every dict was pinned to
                    // a concrete impl during specialization. So we skip the
                    // dict-injection branch entirely.
                    val specialized = monoCallSiteRewrites[expr.loc]
                    if (specialized != null) {
                        census.byHashMono++
                        return emitCallDirect(specialized, argIR, expr.isTailCall, src)
                    }

                    // Check if the callee needs dictionary params. If so, inject
                    // resolved ability op functions into the argument record.
                    val calleeDictMethods = dictMethodsForHash[calleeHash]
                    if (calleeDictMethods != null && calleeDictMethods.isNotEmpty()) {
                        val enrichedArgIR = injectDictFields(argIR, expr.arg, calleeDictMethods, src)
                        census.byHashDictInject++
                        emitCallDirect(calleeHash, enrichedArgIR, expr.isTailCall, src)
                    } else {
                        // Always use CallDirectIR for hash-based bindings.
                        // DO NOT check localsByName — a user variable with the same name
                        // (e.g., "let chars = str::chars {s = s}") would shadow the function
                        // reference and read from an uninitialized slot.
                        census.byHashPlain++
                        emitCallDirect(binding.hash, argIR, expr.isTailCall, src)
                    }
                }
                is BindingRef.Native -> {
                    census.native++
                    // Scalar-ABI emission: if the native is declared in the
                    // NativeOp DSL, transpose the user record into positional
                    // args in the registry's declared order. The interpreter
                    // then routes through NativeDispatchScalarNode — no
                    // MezzeRecord alloc, no field-name lookup, typed handler
                    // call. See docs/perf/scalar-native-abi-2026-06.md.
                    val sig = com.mezze.language.truffleir.NativeSignatures
                        .signatureOf(binding.name)
                    if (sig != null && argIR is MakeRecordIR &&
                        argIR.fields.none { it.name == "__spread__" }
                    ) {
                        // Build positional args[] by looking up each declared
                        // param in the user's MakeRecordIR. Bail to the
                        // legacy record path if any param is missing —
                        // could be a partial application or a buggy call,
                        // either way the slow path handles it cleanly.
                        val positional = ArrayList<NodeIR>(sig.params.size)
                        var ok = true
                        for (p in sig.params) {
                            val f = argIR.fields.firstOrNull { it.name == p.name }
                            if (f == null) { ok = false; break }
                            positional.add(f.value)
                        }
                        if (ok && positional.size == sig.params.size) {
                            return CallNativeIR(
                                name = binding.name,
                                arg = UnitLitIR(src),
                                src = src,
                                args = positional,
                                argKinds = sig.argKinds,
                            )
                        }
                    }
                    CallNativeIR(binding.name, argIR, src)
                }
                is BindingRef.Local -> {
                    // Resolve using the same scope logic as compileVar
                    val resolvedSlot = resolveLocalSlot(fn.name, binding.depth)
                    if (resolvedSlot >= 0) {
                        census.indirect++
                        emitCallIndirect(ReadLocalIR(resolvedSlot, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                    } else {
                        // If depth > 0 and not found locally, check if it's a top-level
                        // declaration. Use CallDirectIR for better performance.
                        if (binding.depth > 0) {
                            val topDecl = declIndex?.values?.find { it.name == fn.name }
                            if (topDecl != null) {
                                census.byHashPlain++
                                return emitCallDirect(topDecl.hash, argIR, expr.isTailCall, src)
                            }
                        }
                        census.indirect++
                        emitCallIndirect(fnIR, argIR, expr.isTailCall, src)
                    }
                }
                is BindingRef.Param -> { census.indirect++; emitCallIndirect(ReadLocalIR(currentCaptureCount + binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src) }
                is BindingRef.Capture -> { census.indirect++; emitCallIndirect(ReadCaptureIR(binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src) }
            }
        }
        census.indirect++
        return emitCallIndirect(fnIR, argIR, expr.isTailCall, src)
    }

    /**
     * Inject __dict_<method> fields into an argument record for dictionary passing.
     *
     * At the call site, we resolve which ability op function to pass for each
     * required dict method. The function is loaded via ReadContextIR(opHash)
     * so the runtime gets the actual MezzeFunction.
     *
     * @param argIR the already-compiled argument IR
     * @param argExpr the typed argument expression (for type info)
     * @param dictMethods set of method names the callee needs
     * @param src source info for generated IR nodes
     * @return argIR with injected dict fields, or original argIR if resolution fails
     */
    private fun injectDictFields(
        argIR: NodeIR,
        argExpr: TypedExpr,
        dictMethods: Set<String>,
        src: SourceInfo
    ): NodeIR {
        // Collect concrete types from the argument record
        val concreteTypes = mutableListOf<MezzeType>()
        if (argExpr is TypedRecord) {
            for (entry in argExpr.entries) {
                if (entry is TypedRecordEntry.Field) {
                    concreteTypes.add(entry.value.type)
                }
            }
        }

        // For each required dict method, try to resolve the op hash using concrete types
        val dictFields = mutableListOf<FieldIR>()
        for (methodName in dictMethods.sorted()) {
            val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(methodName)
            val opHash = resolveOpHashFromConcreteTypes(methodName, concreteTypes)
            if (opHash != null) {
                // ReadContextFnIR fetches the MezzeFunction from the global registry by hash
                // WITHOUT auto-evaluating it as a thunk. This is critical because ability op
                // functions may look like zero-param thunks but we need the function object itself.
                dictFields.add(FieldIR(dictParamName, ReadGlobalFnByHashIR(opHash, src)))
            } else {
                // Can't resolve — check if caller has this dict param (forwarding)
                val callerDictIdx = currentParamNames.indexOf(dictParamName)
                if (callerDictIdx >= 0) {
                    // Forward the dict from our own params
                    val slot = currentCaptureCount + callerDictIdx
                    dictFields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                } else {
                    // No resolution — pass UnitLitIR as placeholder.
                    // The callee's DictMethodCallIR will fail at runtime if this
                    // path is actually taken. In practice, if the caller can't resolve
                    // the dict, the call site must also have VarType and the pre-scan
                    // should have put dict params on the caller too.
                    dictFields.add(FieldIR(dictParamName, UnitLitIR(src)))
                }
            }
        }

        if (dictFields.isEmpty()) return argIR

        // Merge dict fields into the argument record
        return if (argIR is MakeRecordIR) {
            MakeRecordIR(argIR.fields + dictFields, argIR.src, argIR.typeTag)
        } else {
            // Wrap non-record arg: create a record with just the dict fields
            // This shouldn't normally happen (function args are records), but handle gracefully.
            MakeRecordIR(dictFields, src)
        }
    }

    /**
     * Resolve the ability op hash for a given method name using concrete types.
     * Tries each concrete type against the ability op index to find a match.
     */
    private fun resolveOpHashFromConcreteTypes(methodName: String, concreteTypes: List<MezzeType>): Hash? {
        // First pass: direct match.
        for (concreteType in concreteTypes) {
            if (concreteType is VarType) continue
            val opHash = tryResolveAbilityOp(methodName, concreteType)
            if (opHash != null) return opHash
        }
        // Second pass (#195 B3): descend into container types. When the caller
        // passes `xs: [Str]` and the callee needs an `EncodeRepr` impl for the
        // element type, the right impl is `EncodeRepr for Str` — extract it
        // from the container's element/value type.
        for (concreteType in concreteTypes) {
            val elementType: MezzeType? = when (concreteType) {
                is SeqType -> concreteType.elementType
                is ListType -> concreteType.elementType
                is SetType -> concreteType.elementType
                is SortedSetType -> concreteType.elementType
                is MapType -> concreteType.valueType
                is SortedMapType -> concreteType.valueType
                else -> null
            }
            if (elementType != null && elementType !is VarType) {
                val opHash = tryResolveAbilityOp(methodName, elementType)
                if (opHash != null) return opHash
            }
        }
        return null
    }

    /**
     * Compile a call argument, wrapping any dict-needing function references in
     * wrapper lambdas that capture the resolved dict functions.
     *
     * When a polymorphic function like `encode_value: {x: 'a} with Encoder 'a -> Str`
     * is passed as a value (e.g., `apply_encoder {f = encode_value, val = 42}`),
     * the dict params aren't populated. This method detects such references and
     * wraps them in closures that inject the resolved dict at call time.
     */
    private fun compileArgWithDictWrapping(argExpr: TypedExpr, src: SourceInfo): NodeIR {
        if (argExpr !is TypedRecord) return compileExpr(argExpr)

        // Collect all concrete types from sibling fields for resolution context
        val siblingConcreteTypes = mutableListOf<MezzeType>()
        for (entry in argExpr.entries) {
            if (entry is TypedRecordEntry.Field) {
                siblingConcreteTypes.add(entry.value.type)
            }
        }

        // Check if any field references a dict-needing function
        var needsWrapping = false
        for (entry in argExpr.entries) {
            if (entry is TypedRecordEntry.Field && isDictNeedingFunctionRef(entry.value)) {
                needsWrapping = true
                break
            }
        }

        if (!needsWrapping) return compileExpr(argExpr)

        // Build record with wrapped function references
        val fields = argExpr.entries.map { entry ->
            when (entry) {
                is TypedRecordEntry.Field -> {
                    val wrappedValue = tryWrapDictFunction(entry.value, siblingConcreteTypes, src)
                    FieldIR(entry.name, wrappedValue ?: compileExpr(entry.value))
                }
                is TypedRecordEntry.Spread -> FieldIR("__spread__", compileExpr(entry.expr))
            }
        }
        return MakeRecordIR(fields, src)
    }

    /**
     * Check if an expression is a reference to a function that needs dict params.
     */
    private fun isDictNeedingFunctionRef(expr: TypedExpr): Boolean {
        if (expr !is TypedVar) return false
        val binding = expr.binding
        if (binding !is BindingRef.ByHash) return false
        val dictMethods = dictMethodsForHash[binding.hash] ?: return false
        return dictMethods.isNotEmpty()
    }

    /**
     * Try to wrap a dict-needing function reference in a closure that captures
     * the resolved dict functions. Returns null if wrapping isn't needed or possible.
     *
     * The wrapper lambda:
     * 1. Has the same params as the original function (regular params only, no dict params)
     * 2. Captures the resolved dict function values
     * 3. Calls the original function with both the regular args and the dict args injected
     *
     * For example, wrapping `encode_value` when called with Int:
     *   {x} -> encode_value {x = x, __dict_encode = <captured_int_encode_fn>}
     */
    private fun tryWrapDictFunction(
        expr: TypedExpr,
        contextConcreteTypes: List<MezzeType>,
        src: SourceInfo
    ): NodeIR? {
        if (expr !is TypedVar) return null
        val binding = expr.binding as? BindingRef.ByHash ?: return null
        val dictMethods = dictMethodsForHash[binding.hash] ?: return null
        if (dictMethods.isEmpty()) return null

        // Try to resolve all dict methods using context types and the function's own type
        val allConcreteTypes = mutableListOf<MezzeType>()
        allConcreteTypes.addAll(contextConcreteTypes)

        // Also extract concrete types from the function's type at this usage site
        val fnType = expr.type
        if (fnType is FunctionType) {
            extractConcreteTypesFromFnType(fnType, allConcreteTypes)
        }

        // Resolve each dict method
        val resolvedDicts = mutableListOf<Pair<String, NodeIR>>() // dictParamName -> resolved value IR
        for (methodName in dictMethods.sorted()) {
            val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(methodName)
            val opHash = resolveOpHashFromConcreteTypes(methodName, allConcreteTypes)
            if (opHash != null) {
                resolvedDicts.add(dictParamName to ReadGlobalFnByHashIR(opHash, src))
            } else {
                // Try forwarding from caller's dict params
                val callerDictIdx = currentParamNames.indexOf(dictParamName)
                if (callerDictIdx >= 0) {
                    val slot = currentCaptureCount + callerDictIdx
                    resolvedDicts.add(dictParamName to ReadLocalIR(slot, SlotKind.OBJECT, src))
                } else {
                    // Can't resolve — don't wrap (fall through to normal compilation)
                    return null
                }
            }
        }

        // Find the original function's regular param names (excluding dict params)
        val originalDecl = declIndex?.values?.find { it.hash == binding.hash }
        val originalLambda = originalDecl?.value as? TypedLambda
        val regularParamNames = originalLambda?.params?.map { it.name } ?: listOf("it")

        // Generate a unique hash for the wrapper lambda
        val hashBuilder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        hashBuilder.append("dict_wrapper")
        hashBuilder.append(binding.hash.hex)
        for ((name, _) in resolvedDicts) {
            hashBuilder.append(name)
        }
        // Include context types to make hash unique per instantiation
        for (t in allConcreteTypes) {
            hashBuilder.append(t.toString())
        }
        val wrapperHash = Hash(hashBuilder.build().toFullHexString())

        // Build wrapper body: CallDirectIR(original_hash, {param1=param1, ..., __dict_x=capture0, ...})
        // The wrapper's frame layout: [capture0, ..., captureN, param0, ..., paramM]
        // where captures hold the resolved dict functions
        val captureCount = resolvedDicts.size
        val wrapperParamCount = regularParamNames.size

        // Build arg fields for the inner call
        val innerArgFields = mutableListOf<FieldIR>()
        for ((i, paramName) in regularParamNames.withIndex()) {
            // Read from wrapper's param slots (after captures)
            innerArgFields.add(FieldIR(paramName, ReadLocalIR(captureCount + i, SlotKind.OBJECT, src)))
        }
        for ((i, dictEntry) in resolvedDicts.withIndex()) {
            // Read dict values from wrapper's capture slots
            innerArgFields.add(FieldIR(dictEntry.first, ReadLocalIR(i, SlotKind.OBJECT, src)))
        }
        val innerArgIR = MakeRecordIR(innerArgFields, src)
        val wrapperBodyIR = CallDirectIR(binding.hash, listOf(innerArgIR), false, src)

        // Create the wrapper FunctionIR
        val wrapperFnIR = FunctionIR(
            hash = wrapperHash,
            name = "dict_wrapper_${originalDecl?.name ?: "fn"}",
            qualifiedName = "dict_wrapper_${originalDecl?.name ?: "fn"}",
            paramSlots = regularParamNames.map { SlotKind.OBJECT },
            paramNames = regularParamNames,
            localSlots = emptyList(),
            localNames = emptyList(),
            captureSlots = resolvedDicts.map { SlotKind.OBJECT },
            captureNames = resolvedDicts.map { it.first },
            body = wrapperBodyIR,
            sourceFile = src.fileIndex.toString(),
            sourceSection = src
        )
        extractedFunctions.add(wrapperFnIR)

        // Build captures: read the resolved dict values from current frame/context
        val captures = resolvedDicts.mapIndexed { i, (_, dictValueIR) ->
            // The dictValueIR is either ReadContextFnIR or ReadLocalIR.
            // For MakeLambdaIR captures, we need CaptureIR with frame slots.
            // But the dict values aren't in frame slots — they're from context.
            // We need a different approach: allocate local slots for the dict values
            // and then capture them.
            // Actually, for ReadContextFnIR values, we need to materialize them.
            // Let's use a block that binds the dict values to locals first.
            CaptureIR(-1, SlotKind.OBJECT) // placeholder — will fix below
        }

        // We can't use CaptureIR directly for ReadContextFnIR values since they're
        // not in frame slots. Instead, generate a block that:
        // 1. Evaluates each dict value and stores in a local
        // 2. Creates the lambda with captures from those locals
        val dictLocals = resolvedDicts.map { (name, _) -> allocLocal("__wrap_$name") }
        val stmts = resolvedDicts.mapIndexed { i, (name, dictValueIR) ->
            StmtIR.Let(dictLocals[i], SlotKind.OBJECT, "__wrap_$name", wrapperHash, dictValueIR, src)
        }
        val captureIRs = dictLocals.map { slot -> CaptureIR(slot, SlotKind.OBJECT) }
        val makeLambdaIR = MakeLambdaIR(wrapperHash, captureIRs, src)

        return if (stmts.isEmpty()) {
            makeLambdaIR
        } else {
            BlockIR(stmts, makeLambdaIR, src)
        }
    }

    /**
     * Extract concrete types from a FunctionType's param type for dict resolution.
     * E.g., FunctionType(RecordType({x: Int}), ...) -> [Int]
     */
    private fun extractConcreteTypesFromFnType(fnType: FunctionType, out: MutableList<MezzeType>) {
        val paramType = fnType.paramType
        if (paramType is RecordType) {
            for ((_, fieldType) in paramType.fields) {
                if (fieldType !is VarType) {
                    out.add(fieldType)
                }
            }
        } else if (paramType !is VarType) {
            out.add(paramType)
        }
    }

    private fun compileLambda(expr: TypedLambda, src: SourceInfo): NodeIR {
        // Save parent's capture info BEFORE overwriting for the child.
        // This is needed to resolve captures from grandparent scope:
        // e.g., `outer = {a} -> middle = {b} -> inner = {} -> a`
        // When compiling `inner`, `a` is a capture of `middle`. We need
        // to know middle's capture layout to find `a`'s slot in middle's frame.
        val parentCaptureNames = currentCaptureNames
        val parentCaptureCount = currentCaptureCount
        val parentParamNames = currentParamNames

        // #195 B3: scan the lambda body for VarType ability method calls
        // (e.g. `x.encode_repr {...}` inside a `seq::fold` callback). The
        // lambda needs its own dict params for each method whose receiver is
        // a TyVar. These dicts can't come through the indirect-call signature
        // (it's `f : {acc, x} -> 'b`, no room for extra args), so we capture
        // them from the PARENT's dict-param slots. The parent already gained
        // those slots via the B2 scanner-descent fix.
        val lambdaDictMethods = sortedSetOf<String>()
        scanExprForVarTypeMethods(expr.body, lambdaDictMethods)
        // Only keep dicts the parent actually has — otherwise we'd try to
        // capture a slot that doesn't exist.
        val dictsToCapture = lambdaDictMethods.mapNotNull { method ->
            val dictName = com.mezze.language.ast.MagicParams.dictParamName(method)
            val parentParamIdx = parentParamNames.indexOf(dictName)
            if (parentParamIdx >= 0) {
                dictName to (parentCaptureCount + parentParamIdx)
            } else {
                val parentCaptureIdx = parentCaptureNames[dictName]
                if (parentCaptureIdx != null) dictName to parentCaptureIdx else null
            }
        }
        // Register the lambda's dict needs so compileFunctionBody adds them as
        // captures (not params — params are fixed by the indirect-call ABI).
        // We DON'T put them in dictMethodsForHash here because that would make
        // compileFunctionBody append dict PARAMS; instead we extend the lambda's
        // capture list directly.

        // Build capture name → index map so compileVar can emit ReadCaptureIR
        val captureNameMap = mutableMapOf<String, Int>()
        for ((i, c) in expr.captures.withIndex()) {
            captureNameMap[c.name] = i
        }
        // Append synthetic dict-name captures AFTER user-visible captures so
        // the lambda body can resolve `__dict_<m>` via currentCaptureNames.
        val dictCaptureStart = expr.captures.size
        for ((i, dc) in dictsToCapture.withIndex()) {
            captureNameMap[dc.first] = dictCaptureStart + i
        }
        currentCaptureNames = captureNameMap

        // Extract lambda as a separate FunctionIR
        val lambdaHash = com.mezze.language.typedast.TypedASTHasher.hashExpr(expr)
        val lambdaCaptureInfo = expr.captures.map { c -> c.name to typeToSlotKind(c.type) } +
            dictsToCapture.map { it.first to SlotKind.OBJECT }
        val fnIR = compileFunctionBody(
            hash = lambdaHash,
            name = "lambda",
            params = expr.params,
            body = expr.body,
            loc = expr.loc,
            captureInfo = lambdaCaptureInfo
        )
        extractedFunctions.add(fnIR)

        // Create MakeLambda with captures — always use OBJECT kind.
        // The parent frame may have typed slots (LONG, DOUBLE) but the robust
        // readSlotTyped fallback in FunctionDefinitionNode handles type mismatches.
        // Using OBJECT avoids issues with VarType captures that should be LONG.
        //
        // Use the PARENT's capture/param info to resolve where each captured variable
        // lives in the parent's frame. Variables can come from:
        // 1. Parent's locals (localsByName)
        // 2. Parent's params (parentParamNames, offset by parentCaptureCount)
        // 3. Parent's captures (parentCaptureNames — variables captured from grandparent)
        val captures = expr.captures.mapNotNull { c ->
            val slot = localsByName[c.name]
            if (slot != null) {
                CaptureIR(slot, SlotKind.OBJECT)
            } else if (c.name in parentParamNames) {
                // Parent param slot is offset by parent's capture count
                val paramIdx = parentParamNames.indexOf(c.name)
                CaptureIR(parentCaptureCount + paramIdx, SlotKind.OBJECT)
            } else {
                // Check parent's captures (variable from grandparent scope)
                val parentCaptureIdx = parentCaptureNames[c.name]
                if (parentCaptureIdx != null) {
                    // Parent's capture at index N is at frame slot N (captures are at slots 0..captureCount-1)
                    CaptureIR(parentCaptureIdx, SlotKind.OBJECT)
                } else null
            }
        }
        // Append the synthetic dict captures, in the same order they were added
        // to lambdaCaptureInfo above. Each points at a parent's dict-param (or
        // already-captured dict) slot.
        val allCaptures = captures + dictsToCapture.map { CaptureIR(it.second, SlotKind.OBJECT) }

        // Restore parent's capture info so sibling lambdas in the same function body
        // see the correct capture context. Without this, compiling lambda A inside a
        // function body clobbers currentCaptureNames, and sibling lambda B (compiled
        // later in the same body) loses access to the parent's capture map.
        currentCaptureNames = parentCaptureNames
        currentCaptureCount = parentCaptureCount
        currentParamNames = parentParamNames

        return MakeLambdaIR(lambdaHash, allCaptures, src)
    }

    private fun compileBlock(expr: TypedBlock, src: SourceInfo): NodeIR {
        val savedLocalMappings = localsByName.toMap()
        val savedRecursiveLets = recursiveLetLambdas.toMap()
        val stmts = expr.statements.map { stmt ->
            when (stmt) {
                is TypedStatement.Let -> {
                    val slot = forceAllocLocal(stmt.name)
                    val stmtValue = stmt.value

                    // RECURSIVE LET REGISTRATION: If this is a recursive let-bound lambda,
                    // pre-compute its hash and register so compileVar can resolve self-references
                    // to MakeLambdaIR(self_hash, self_captures) instead of ReadCaptureIR.
                    if (stmt.isRecursive && stmtValue is TypedLambda) {
                        val lambdaHash = com.mezze.language.typedast.TypedASTHasher.hashExpr(stmtValue)
                        val captureKinds = stmtValue.captures.map { typeToSlotKind(it.type) }
                        recursiveLetLambdas[stmt.name] = RecursiveLetInfo(lambdaHash, captureKinds)
                    }

                    val valueIR = if (stmtValue is TypedLambda) {
                        // Let-bound lambda: check if it matches a top-level function.
                        // If so, reference the top-level function's hash (not a re-hashed copy).
                        // Only use this optimization in the entry function (no captures/params),
                        // because nested functions need compileLambda's parent capture resolution.
                        val topLevelDecl = if (currentCaptureCount == 0 && currentParamNames.isEmpty()) {
                            declIndex?.values?.find { it.name == stmt.name }
                        } else null
                        if (topLevelDecl != null && topLevelDecl.value is TypedLambda) {
                            // Top-level function — reference it directly (don't re-compile)
                            val captures = stmtValue.captures.mapNotNull { c ->
                                val s = localsByName[c.name]
                                if (s != null) CaptureIR(s, typeToSlotKind(c.type))
                                else if (c.name in currentParamNames) CaptureIR(currentCaptureCount + currentParamNames.indexOf(c.name), typeToSlotKind(c.type))
                                else {
                                    // Check parent's captures (variable from grandparent scope)
                                    val captIdx = currentCaptureNames[c.name]
                                    if (captIdx != null) CaptureIR(captIdx, SlotKind.OBJECT)
                                    else null
                                }
                            }
                            MakeLambdaIR(topLevelDecl.hash, captures, locToSourceInfo(stmt.loc))
                        } else {
                            compileLambda(stmtValue, locToSourceInfo(stmt.loc))
                        }
                    } else {
                        compileExpr(stmtValue)
                    }
                    StmtIR.Let(slot, typeToSlotKind(stmt.type), stmt.name, stmt.contentHash, valueIR, locToSourceInfo(stmt.loc))
                }
                is TypedStatement.Expr -> StmtIR.Expr(compileExpr(stmt.expr), locToSourceInfo(stmt.loc))
            }
        }
        val resultIR = compileExpr(expr.result)
        localsByName.clear()
        localsByName.putAll(savedLocalMappings)
        recursiveLetLambdas.clear()
        recursiveLetLambdas.putAll(savedRecursiveLets)
        return BlockIR(stmts, resultIR, src)
    }

    private fun compileMatch(expr: TypedMatch, src: SourceInfo): NodeIR {
        val scrutineeIR = compileExpr(expr.scrutinee)
        // Use flatMap to expand or-patterns (cases with multiple patterns) into
        // separate CaseIR entries sharing the same body. This way `[] | [_] -> True`
        // becomes two CaseIR entries: one for `[]` and one for `[_]`.
        val cases = expr.cases.flatMap { case ->
            // Determine which patterns to emit. For or-patterns, case.patterns has multiple entries.
            val patternsToEmit = if (case.patterns.size > 1) case.patterns else listOf(case.patterns.firstOrNull())

            patternsToEmit.map { pat ->
                // Push a new scope for this match case's bindings
                val caseScope = mutableMapOf<String, Int>()

                // Allocate slots for pattern bindings (always fresh)
                val bindings = case.bindings.map { b ->
                    val slot = forceAllocLocal(b.name)
                    caseScope[b.name] = slot
                    BindingIR(slot, typeToSlotKind(b.type))
                }

                // Push scope so nested expressions use these bindings at depth 0
                scopeStack.addLast(caseScope)

                // Collect nested destructuring info BEFORE compilePattern,
                // so temp slots for nested records are allocated.
                val nestedDestructures = mutableListOf<NestedDestructureInfo>()
                collectNestedDestructuring(pat, nestedDestructures)

                // Collect nested seq destructuring (nested seq patterns like [[x]])
                val nestedSeqDestructures = mutableListOf<NestedSeqDestructureInfo>()
                collectNestedSeqDestructuring(pat, nestedSeqDestructures)

                val patternIR = compilePattern(pat)
                var guardIR = case.guard?.let { compileExpr(it) }

                // Compile if-let bindings.
                // Also collect nested destructuring from if-let patterns.
                val ifLetIRs = case.ifLetBindings.map { ifLet ->
                    // Collect nested destructuring from if-let patterns too
                    collectNestedDestructuring(ifLet.pattern, nestedDestructures)
                    val exprIR = compileExpr(ifLet.expr)
                    val ifLetPatternIR = compilePattern(ifLet.pattern)
                    // Resolve binding slots from the case scope
                    val fieldNames = ifLet.bindingNames
                    val fieldSlots = fieldNames.map { name -> caseScope[name] ?: -1 }
                    IfLetBindingIR(exprIR, ifLetPatternIR, fieldNames, fieldSlots, locToSourceInfo(ifLet.loc))
                }

                var bodyIR = compileExpr(case.body)

                // Wrap guard AND body with nested destructuring let-bindings if needed.
                // For pattern `Some { val = { x = px, y = py } } if x > 0 -> x`:
                //   tempSlot holds the intermediate record {x=10, y=20}
                //   Guard becomes: do { let px = tempSlot.x; let py = tempSlot.y; <originalGuard> }
                //   Body becomes:  do { let px = tempSlot.x; let py = tempSlot.y; <originalBody> }
                // Both share the same frame, so the guard's writes are visible to the body.
                // We wrap both so the variables are available regardless of evaluation order.
                if (nestedDestructures.isNotEmpty()) {
                    val destructureStmts = mutableListOf<StmtIR>()
                    for (nd in nestedDestructures) {
                        for ((fieldName, bindingSlot) in nd.fieldBindings) {
                            val readTemp = ReadLocalIR(nd.tempSlot, SlotKind.OBJECT, src)
                            val readField = ReadFieldIR(readTemp, fieldName, -1, src)
                            destructureStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, "__nested_$fieldName", Hash.ZERO, readField, src))
                        }
                    }
                    // Wrap guard (if present) with destructuring so guard can reference nested vars
                    if (guardIR != null) {
                        guardIR = BlockIR(destructureStmts.toList(), guardIR, src)
                    }
                    // Wrap body too (in case guard doesn't exist, or for independent body access)
                    bodyIR = BlockIR(destructureStmts.toList(), bodyIR, src)
                }

                // Handle nested seq destructuring: for patterns like [[x, ...rest1], ...rest2],
                // the inner [x, ...rest1] was bound to a temp slot. Now generate a nested match
                // in the body that extracts bindings from the temp slot.
                if (nestedSeqDestructures.isNotEmpty()) {
                    val seqStmts = mutableListOf<StmtIR>()
                    for (nsd in nestedSeqDestructures) {
                        // Generate: let var = seq_get(tempSlot, index) for each binding in the inner pattern
                        val innerPat = nsd.innerPattern
                        if (innerPat is TypedSeqPattern) {
                            // For each element in the inner seq pattern, generate a seq_get
                            for ((innerIdx, innerElem) in innerPat.elements.withIndex()) {
                                if (innerElem is TypedVarPattern) {
                                    val bindingSlot = scopeStack.lastOrNull()?.get(innerElem.name)
                                        ?: localsByName[innerElem.name]
                                        ?: forceAllocLocal(innerElem.name)
                                    val readTemp = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)
                                    val getElem = SeqGetIR(readTemp, LongLitIR(innerIdx.toLong(), src), src)
                                    seqStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, innerElem.name, Hash.ZERO, getElem, src))
                                }
                            }
                            // Handle rest binding in inner seq pattern
                            val restName = innerPat.rest
                            if (restName != null) {
                                val restSlot = scopeStack.lastOrNull()?.get(restName)
                                    ?: localsByName[restName]
                                    ?: forceAllocLocal(restName)
                                // Generate a nested match to bind elements and rest from the temp slot.
                                // match tempSlot is [e0, e1, ...rest] -> rest
                                val innerCaseBindings = mutableListOf<BindingIR>()
                                val innerElementSlots = innerPat.elements.mapIndexed { _, iElem ->
                                    if (iElem is TypedVarPattern) {
                                        val s = scopeStack.lastOrNull()?.get(iElem.name)
                                            ?: localsByName[iElem.name]
                                            ?: forceAllocLocal(iElem.name)
                                        s
                                    } else -1
                                }
                                val innerPatternIR = PatternIR.Seq(innerElementSlots, restSlot)
                                val innerCaseBody = ReadLocalIR(restSlot, SlotKind.OBJECT, src)
                                val innerCase = CaseIR(innerPatternIR, innerCaseBindings, null, innerCaseBody, src)
                                val readTemp = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)
                                val innerMatch = MatchIR(readTemp, listOf(innerCase), src)
                                seqStmts.add(StmtIR.Let(restSlot, SlotKind.OBJECT, restName, Hash.ZERO, innerMatch, src))
                            }
                        } else if (innerPat is TypedVariantPattern) {
                            // Nested variant pattern in seq: [Ok {val}] -> ...
                            // Generate a nested match on the temp slot to extract variant payload fields.
                            val innerPatternIR = compilePattern(innerPat)
                            val innerCaseBindings = mutableListOf<BindingIR>()

                            // Collect nested record destructuring within the variant payload
                            val innerNestedDestructures = mutableListOf<NestedDestructureInfo>()
                            collectNestedDestructuring(innerPat, innerNestedDestructures)

                            // Build inner body that produces unit (bindings are side-effects on the frame)
                            var innerBody: NodeIR = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)

                            // Wrap with nested record destructuring if needed
                            if (innerNestedDestructures.isNotEmpty()) {
                                val dStmts = mutableListOf<StmtIR>()
                                for (nd in innerNestedDestructures) {
                                    for ((fieldName, bindingSlot) in nd.fieldBindings) {
                                        val readTemp2 = ReadLocalIR(nd.tempSlot, SlotKind.OBJECT, src)
                                        val readField = ReadFieldIR(readTemp2, fieldName, -1, src)
                                        dStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, "__nested_$fieldName", Hash.ZERO, readField, src))
                                    }
                                }
                                innerBody = BlockIR(dStmts, innerBody, src)
                            }

                            val innerCase = CaseIR(innerPatternIR, innerCaseBindings, null, innerBody, src)
                            // Add a wildcard fallback that returns the temp slot unchanged
                            val wildcardCase = CaseIR(PatternIR.Wildcard(-1), emptyList(), null, ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src), src)
                            val readTemp = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)
                            val innerMatch = MatchIR(readTemp, listOf(innerCase, wildcardCase), src)
                            // Use an arbitrary result slot (the match result is not used, only the bindings matter)
                            val resultSlot = forceAllocLocal("__seq_variant_result_${nsd.tempSlot}")
                            seqStmts.add(StmtIR.Let(resultSlot, SlotKind.OBJECT, "__seq_variant_result", Hash.ZERO, innerMatch, src))
                        } else if (innerPat is TypedLiteralPattern) {
                            // Nested literal pattern in seq: [1, 2] -> ...
                            // No bindings needed for literals in seq elements
                        }
                    }
                    if (seqStmts.isNotEmpty()) {
                        if (guardIR != null) {
                            guardIR = BlockIR(seqStmts.toList(), guardIR, src)
                        }
                        bodyIR = BlockIR(seqStmts.toList(), bodyIR, src)
                    }
                }

                // Pop scope
                scopeStack.removeLast()

                CaseIR(patternIR, bindings, guardIR, bodyIR, locToSourceInfo(case.loc), ifLetIRs)
            }
        }
        return MatchIR(scrutineeIR, cases, src)
    }

    /**
     * Info about a nested record destructuring that needs post-bind processing.
     * tempSlot: the frame slot holding the intermediate record value
     * fieldBindings: list of (fieldName, targetSlot) pairs to extract from the record
     */
    private data class NestedDestructureInfo(
        val tempSlot: Int,
        val fieldBindings: List<Pair<String, Int>>  // (fieldName, targetSlot)
    )

    /**
     * Info about a nested seq element destructuring that needs post-bind processing.
     * tempSlot: the frame slot holding the intermediate seq element value
     * innerPattern: the full TypedPattern for the nested seq element, compiled to
     *   a nested match in the body
     */
    private data class NestedSeqDestructureInfo(
        val tempSlot: Int,
        val innerPattern: TypedPattern
    )

    /**
     * Walk a typed pattern tree and collect any nested record sub-patterns
     * that can't be handled by a flat PatternIR. Allocates temp slots for
     * intermediate records.
     */
    private fun collectNestedDestructuring(pattern: TypedPattern?, out: MutableList<NestedDestructureInfo>) {
        if (pattern == null) return
        when (pattern) {
            is TypedVariantPattern -> {
                val payload = pattern.payload
                if (payload is TypedRecordPattern) {
                    for (fp in payload.fields) {
                        if (fp.pattern is TypedRecordPattern) {
                            // Nested record: { val = { x = px, y = py } }
                            // Allocate temp slot for the intermediate field value
                            val tempSlot = forceAllocLocal("__tmp_${fp.name}")
                            val nestedRecord = fp.pattern as TypedRecordPattern
                            val fieldBindings = nestedRecord.fields.map { innerFp ->
                                val bindingName = if (innerFp.pattern is TypedVarPattern) (innerFp.pattern as TypedVarPattern).name else innerFp.name
                                val slot = scopeStack.lastOrNull()?.get(bindingName)
                                    ?: localsByName[bindingName]
                                    ?: forceAllocLocal(bindingName)
                                Pair(innerFp.name, slot)
                            }
                            out.add(NestedDestructureInfo(tempSlot, fieldBindings))
                        }
                    }
                }
            }
            is TypedRecordPattern -> {
                for (fp in pattern.fields) {
                    if (fp.pattern is TypedRecordPattern) {
                        // Nested record: { config = { port = p, host = h } }
                        val tempSlot = forceAllocLocal("__tmp_${fp.name}")
                        val nestedRecord = fp.pattern as TypedRecordPattern
                        val fieldBindings = nestedRecord.fields.map { innerFp ->
                            val bindingName = if (innerFp.pattern is TypedVarPattern) (innerFp.pattern as TypedVarPattern).name else innerFp.name
                            val slot = scopeStack.lastOrNull()?.get(bindingName)
                                ?: localsByName[bindingName]
                                ?: forceAllocLocal(bindingName)
                            Pair(innerFp.name, slot)
                        }
                        out.add(NestedDestructureInfo(tempSlot, fieldBindings))
                    }
                }
            }
            else -> {} // Other patterns don't have nested records
        }
    }

    /**
     * Collect nested seq element patterns that need post-bind destructuring.
     * For pattern `[[x, ...rest1], ...rest2]`, the inner `[x, ...rest1]` is a
     * TypedSeqPattern that can't be represented flat. We bind element 0 to a
     * temp slot and generate a nested match in the body.
     */
    private fun collectNestedSeqDestructuring(pattern: TypedPattern?, out: MutableList<NestedSeqDestructureInfo>) {
        if (pattern == null) return
        if (pattern is TypedSeqPattern) {
            for ((idx, elem) in pattern.elements.withIndex()) {
                if (elem !is TypedVarPattern && elem !is TypedWildcardPattern) {
                    // This is a nested pattern (seq, literal, etc.) that needs
                    // to be bound to a temp slot and matched in the body.
                    val tempSlot = forceAllocLocal("__seq_tmp_$idx")
                    out.add(NestedSeqDestructureInfo(tempSlot, elem))
                }
            }
        }
    }

    private fun compilePattern(pattern: TypedPattern?): PatternIR {
        if (pattern == null) return PatternIR.Wildcard()
        return when (pattern) {
            is TypedWildcardPattern -> PatternIR.Wildcard()
            is TypedVarPattern -> {
                // Bind the scrutinee to the variable's frame slot
                val slot = scopeStack.lastOrNull()?.get(pattern.name)
                    ?: localsByName[pattern.name]
                    ?: -1
                PatternIR.Wildcard(slot)
            }
            is TypedLiteralPattern -> PatternIR.Literal(compileExpr(pattern.value))
            is TypedVariantPattern -> {
                val tag = pattern.tag.hashCode() and 0x7FFFFFFF
                val fieldNames = mutableListOf<String>()
                val fieldSlots = mutableListOf<Int>()
                val payload = pattern.payload
                if (payload is TypedRecordPattern) {
                    for (fp in payload.fields) {
                        fieldNames.add(fp.name)  // record field name (for extraction at runtime)
                        if (fp.pattern is TypedRecordPattern) {
                            // Nested record pattern: bind to temp slot (allocated by collectNestedDestructuring)
                            val tempSlot = localsByName["__tmp_${fp.name}"] ?: forceAllocLocal("__tmp_${fp.name}")
                            fieldSlots.add(tempSlot)
                        } else {
                            // Simple binding: { val = ys } → "ys", { val } → "val"
                            val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                            val slot = scopeStack.lastOrNull()?.get(bindingName)
                                ?: localsByName[bindingName]
                                ?: forceAllocLocal(bindingName)
                            fieldSlots.add(slot)
                        }
                    }
                } else if (payload is TypedVarPattern) {
                    fieldNames.add(payload.name)
                    val slot = scopeStack.lastOrNull()?.get(payload.name)
                        ?: localsByName[payload.name]
                        ?: forceAllocLocal(payload.name)
                    fieldSlots.add(slot)
                }
                PatternIR.Variant(tag, pattern.tag, fieldNames, fieldSlots)
            }
            is TypedRecordPattern -> {
                // Field names are for runtime extraction from the record.
                // Slots must map to the BINDING name (renamed variable), not the field name.
                // { value = v } → fieldName="value", bindingName="v"
                val fieldNames = pattern.fields.map { it.name }
                val fieldSlots = pattern.fields.map { fp ->
                    if (fp.pattern is TypedRecordPattern) {
                        // Nested record pattern: bind to temp slot (allocated by collectNestedDestructuring)
                        localsByName["__tmp_${fp.name}"] ?: forceAllocLocal("__tmp_${fp.name}")
                    } else {
                        val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                        scopeStack.lastOrNull()?.get(bindingName)
                            ?: localsByName[bindingName]
                            ?: forceAllocLocal(bindingName)
                    }
                }
                PatternIR.Record(fieldNames, fieldSlots, pattern.isOpen)
            }
            is TypedSeqPattern -> {
                PatternIR.Seq(
                    pattern.elements.mapIndexed { idx, elem ->
                        when (elem) {
                            is TypedVarPattern -> {
                                scopeStack.lastOrNull()?.get(elem.name)
                                    ?: localsByName[elem.name]
                                    ?: forceAllocLocal(elem.name)
                            }
                            is TypedWildcardPattern -> -1
                            else -> {
                                // Nested pattern (seq, literal, etc.) — bind to temp slot
                                // (allocated by collectNestedSeqDestructuring)
                                localsByName["__seq_tmp_$idx"] ?: forceAllocLocal("__seq_tmp_$idx")
                            }
                        }
                    },
                    run {
                        val restName = pattern.rest
                        if (restName != null) {
                            scopeStack.lastOrNull()?.get(restName)
                                ?: localsByName[restName]
                                ?: forceAllocLocal(restName)
                        } else -1
                    }
                )
            }
            is TypedStringInterpPattern -> {
                val partTypes = mutableListOf<Int>()
                val literals = mutableListOf<String?>()
                // captureSlots is parallel with partTypes: -1 for literals, actual slot for captures
                val captureSlots = mutableListOf<Int>()
                for (part in pattern.parts) {
                    when (part) {
                        is TypedStringPatternPart.Literal -> {
                            partTypes.add(0) // 0 = literal
                            literals.add(part.value)
                            captureSlots.add(-1) // no slot for literal parts
                        }
                        is TypedStringPatternPart.Capture -> {
                            partTypes.add(1) // 1 = capture
                            literals.add(null)
                            val slot = scopeStack.lastOrNull()?.get(part.variableName)
                                ?: localsByName[part.variableName]
                                ?: forceAllocLocal(part.variableName)
                            captureSlots.add(slot)
                        }
                    }
                }
                PatternIR.StringInterp(partTypes, literals, captureSlots)
            }
        }
    }

    /**
     * Strip a SuspendedType wrapper from the receiver for ability dispatch.
     *
     * Effectful expressions like `effectful {}` have type `SuspendedType<T,
     * effects>`. For method dispatch we want the underlying value type.
     *
     * Exception: inlineable primitive ops (`add`, `sub`, ...) must keep the
     * SuspendedType so resolution fails here and the indirect-call path
     * pattern-matches into DirectAddNode etc. — those nodes have
     * MezzeSuspended specializations that propagate suspension at runtime.
     * Without this guard, `result + 1` for `result : Int <Log>` resolves to
     * a direct call to Int.add and crashes when fed a MezzeSuspended `it`
     * (see RuntimeEffectValidationTest.testNestedEffectfulFunctionValidation).
     */
    private fun receiverTypeForDispatch(methodName: String, receiverType: MezzeType): MezzeType =
        if (receiverType is SuspendedType && methodName !in inlineableOps) {
            receiverType.valueType
        } else {
            receiverType
        }

    private fun tryResolveAbilityOp(methodName: String, receiverType: MezzeType): Hash? {
        val effectiveType = receiverTypeForDispatch(methodName, receiverType)
        val candidates = abilityOpIndex[methodName] ?: return null
        val match = candidates.firstOrNull { op ->
            op.targetTypes.any { typeMatchesTarget(effectiveType, it) }
        }
        if (match != null) return match.opHash
        // Fallback: if there's exactly one candidate and the receiver is a variant/type-application,
        // use it. This handles user-defined type aliases where the variant type's constructors
        // don't match the known aliases in typeMatchesTarget.
        if (candidates.size == 1 && (effectiveType is VariantType || effectiveType is TypeApplication)) {
            return candidates.first().opHash
        }
        return null
    }

    private fun findAbilityOpBody(methodName: String, receiverType: MezzeType): TypedExpr? {
        val effectiveType = receiverTypeForDispatch(methodName, receiverType)
        for (impl in abilityImpls) {
            if (impl.operations.containsKey(methodName) &&
                impl.targetTypes.any { typeMatchesTarget(effectiveType, it) }) {
                return impl.operations[methodName]
            }
        }
        return null
    }

    private fun typeMatchesTarget(concreteType: MezzeType, targetType: MezzeType): Boolean {
        if (concreteType == targetType) return true
        return when {
            concreteType is IntType && targetType is IntType -> true
            concreteType is FloatType && targetType is FloatType -> true
            concreteType is StringType && targetType is StringType -> true
            concreteType is BoolType && targetType is BoolType -> true
            concreteType is NatType && targetType is NatType -> true
            concreteType is CharType && targetType is CharType -> true
            concreteType is FracType && targetType is FracType -> true
            concreteType is TypeApplication && targetType is TypeApplication ->
                concreteType.constructor == targetType.constructor
            concreteType is VariantType && targetType is TypeApplication &&
                concreteType.constructors.size == 1 &&
                concreteType.constructors.keys.first() == targetType.constructor -> true
            concreteType is VariantType && targetType is TypeApplication -> {
                concreteType.typeName == targetType.constructor ||
                    VariantAliases.aliasFor(concreteType.constructors.keys) == targetType.constructor
            }
            concreteType is SeqType && targetType is TypeApplication && targetType.constructor == "Seq" -> true
            concreteType is BytesType && targetType is TypeApplication && targetType.constructor == "Bytes" -> true
            concreteType is ListType && targetType is TypeApplication && targetType.constructor == "List" -> true
            concreteType is SetType && targetType is TypeApplication && targetType.constructor == "Set" -> true
            concreteType is MapType && targetType is TypeApplication && targetType.constructor == "Map" -> true
            concreteType is SortedSetType && targetType is TypeApplication && targetType.constructor == "SortedSet" -> true
            concreteType is SortedMapType && targetType is TypeApplication && targetType.constructor == "SortedMap" -> true
            concreteType is RecordType && targetType is TypeApplication &&
                concreteType.typeName == targetType.constructor -> true
            // VariantType to VariantType: match if constructors overlap or type names match
            concreteType is VariantType && targetType is VariantType -> {
                // Same type name (e.g., both are "Json")
                if (concreteType.typeName != null && concreteType.typeName == targetType.typeName) return true
                // Same constructor set
                if (concreteType.constructors.keys == targetType.constructors.keys) return true
                // Concrete has subset of target constructors (e.g., concrete is a single constructor variant)
                if (concreteType.constructors.keys.all { it in targetType.constructors }) return true
                false
            }
            // VarType: don't match — let runtime handle dispatch
            // targetType is VarType -> true
            // concreteType is VarType -> true
            else -> false
        }
    }

    /** Resolve a local variable to its frame slot using depth + name */
    private fun resolveLocalSlot(name: String, depth: Int): Int {
        // Check scope stack with depth
        if (depth == 0 && scopeStack.isNotEmpty()) {
            val slot = scopeStack.last()[name]
            if (slot != null) return slot
        }
        if (depth > 0 && scopeStack.size > depth) {
            val scopeIdx = scopeStack.size - 1 - depth
            if (scopeIdx >= 0) {
                val slot = scopeStack[scopeIdx][name]
                if (slot != null) return slot
            }
        }
        // Check params — offset by capture count since params start after captures in frame
        val paramIdx = currentParamNames.indexOf(name)
        if (paramIdx >= 0) return currentCaptureCount + paramIdx
        // Check locals
        return localsByName[name] ?: -1
    }

    private fun allocLocal(name: String): Int {
        localsByName[name]?.let { return it }
        val slot = nextLocalSlot++
        localsByName[name] = slot
        return slot
    }

    /** Force-allocate a new slot, even if name already exists (for match bindings) */
    private fun forceAllocLocal(name: String): Int {
        val slot = nextLocalSlot++
        localsByName[name] = slot  // overwrites existing entry
        return slot
    }

    private fun typeToSlotKind(type: MezzeType): SlotKind = when (type) {
        is IntType -> SlotKind.LONG
        is NatType -> SlotKind.LONG
        is FloatType -> SlotKind.DOUBLE
        is BoolType -> SlotKind.BOOLEAN
        else -> SlotKind.OBJECT
    }

    companion object {
        @JvmStatic
        fun computeOpHash(implHash: Hash, opName: String): Hash = Hash.computeOpHash(implHash, opName)

        /**
         * Path C Phase 1: MEZZE_CPS env var (process-scoped) or mezze.cps
         * system property (test-scoped override). Off unless either is
         * truthy — accepting "1"/"true" and rejecting "0"/"false".
         */
        internal fun cpsEnabled(): Boolean {
            val sys = System.getProperty("mezze.cps")
            if (sys != null) return truthy(sys)
            val env = System.getenv("MEZZE_CPS") ?: return false
            return truthy(env)
        }

        private fun truthy(v: String): Boolean =
            v != "0" && !v.equals("false", ignoreCase = true)
    }

    private fun locToSourceInfo(loc: Loc): SourceInfo {
        val fileIndex = files.indexOf(loc.file).let { if (it >= 0) it else { files.add(loc.file); files.size - 1 } }
        return SourceInfo(fileIndex, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
    }

    /** Set by callers via [setDeclIndex] before [compile]. Read by
     *  call-site lookup paths that need to find a referenced decl. */
    private var declIndex: Map<Hash, TypedLetDecl>? = null
    fun setDeclIndex(decls: Map<Hash, TypedLetDecl>) { declIndex = decls }

    /** Look up param names for a known callee hash. Returns null if unknown.
     *  Covers: self-recursive calls (currentFunctionHash, freshly available),
     *  user decls (declIndex via hashToParamNames), and stdlib (PrecompiledStdlib). */
    private fun paramNamesForHash(hash: Hash): List<String>? {
        if (hash == currentFunctionHash) return currentParamNames
        return hashToParamNames[hash]
    }

    /** Populate hashToParamNames from user decls (lambdas only) and PrecompiledStdlib.
     *  Each entry pairs a callee hash with its full paramNames list — regular params
     *  in source order followed by dict params (sorted method names) for callees that
     *  need them. This mirrors the layout of FunctionIR.paramNames for those decls. */
    private fun populateHashToParamNames(modules: List<TypedModule>) {
        hashToParamNames.clear()

        // User decls — only TypedLetDecl whose value is a TypedLambda has a callable
        // shape that scalar-ABI can target. Non-lambda lets are zero-param thunks.
        for (mod in modules) {
            for (decl in mod.decls) {
                if (decl !is TypedLetDecl) continue
                val lambda = decl.value as? TypedLambda ?: continue
                val regular = lambda.params.map { it.name }
                val dict = (dictMethodsForHash[decl.hash] ?: emptySet())
                    .sorted()
                    .map { com.mezze.language.ast.MagicParams.dictParamName(it) }
                hashToParamNames[decl.hash] = regular + dict
            }
        }

        for ((hash, fnIR) in preloweredFunctions) {
            hashToParamNames[hash] = fnIR.paramNames
        }
    }

    /** Build a CallDirectIR, choosing the scalar ABI when we can statically prove
     *  the call is fully-applied to a callee whose param names we know. Otherwise
     *  emit the record-shape (1-element args list) — the runtime path that handles
     *  partial application, spread, and unknown-arity callees.
     *
     *  Scalar emission requires:
     *    - argIR is a MakeRecordIR (no opaque expression)
     *    - no `__spread__` field (spread arg)
     *    - field name set == callee paramNames set (full application)
     */
    private fun emitCallDirect(
        targetHash: Hash,
        argIR: NodeIR,
        isTailCall: Boolean,
        src: SourceInfo,
        typeTag: String? = null
    ): NodeIR {
        // Kill switch — MEZZE_DISABLE_SCALAR=true forces the record ABI.
        val scalarDisabled = System.getenv("MEZZE_DISABLE_SCALAR") == "true"
        val calleeParams = if (scalarDisabled) null else paramNamesForHash(targetHash)
        if (calleeParams != null && argIR is MakeRecordIR) {
            val fields = argIR.fields
            val hasSpread = fields.any { it.name == "__spread__" }
            if (!hasSpread) {
                val callerNames = fields.map { it.name }
                if (callerNames.size == calleeParams.size && callerNames.toSet() == calleeParams.toSet()) {
                    val byName = fields.associate { it.name to it.value }
                    val orderedArgs = calleeParams.map { byName[it]!! }

                    // Inlining: if the callee is a tiny pure-expression function
                    // with no closure / dict params and the caller's args are
                    // side-effect-free, splice the callee body directly into
                    // the caller's IR. The call disappears.
                    if (!isTailCall) {
                        val inlined = tryInlineDirectCall(targetHash, orderedArgs)
                        if (inlined != null) return inlined
                    }

                    return CallDirectIR(targetHash, orderedArgs, isTailCall, src, typeTag, isScalarABI = true)
                }
            }
        }
        return CallDirectIR(targetHash, listOf(argIR), isTailCall, src, typeTag, isScalarABI = false)
    }

    /** Inlining cap — keep body size small to prevent IR blowup in callers
     *  with many call sites. 8 nodes covers helpers like {x} -> x + 1,
     *  {a, b} -> if a > b then a else b, accessor wrappers, etc. */
    private val inlineNodeBudget = 8

    /** Try to inline a direct call into the caller's IR. Returns the inlined
     *  expression, or null if any constraint is violated.
     *
     *  Constraints (conservative first cut):
     *    - Callee FunctionIR is registered (compiled before this site).
     *    - Callee has 0 captures (no closures to materialize at call site).
     *    - Callee has 0 locals (body uses only args + literals + arithmetic + If).
     *    - Callee body is "pure" — no calls, no mutation, no effects, no records.
     *    - Callee body fits in inlineNodeBudget.
     *    - Caller args are side-effect-free (Read* / literals) so we can
     *      duplicate them via param substitution without binding to fresh slots. */
    private fun tryInlineDirectCall(targetHash: Hash, args: List<NodeIR>): NodeIR? {
        if (System.getenv("MEZZE_DISABLE_INLINE") == "true") return null

        val callee = hashToFunctionIR[targetHash] ?: return null
        if (callee.captureSlots.isNotEmpty()) return null
        if (callee.localSlots.isNotEmpty()) return null
        if (callee.paramSlots.size != args.size) return null

        for (a in args) if (!isSideEffectFree(a)) return null

        if (!isInlinable(callee.body, callee.paramSlots.size)) return null
        if (countNodes(callee.body, max = inlineNodeBudget + 1) > inlineNodeBudget) return null

        if (System.getenv("MEZZE_DEBUG_INLINE") == "true") {
            System.err.println("[inline] HIT: ${callee.name} (hash=${targetHash.hex.take(8)}) args=${args.size}")
        }
        return substituteArgs(callee.body, args)
    }

    /** A NodeIR is "side-effect-free" if duplicating it in multiple positions
     *  doesn't change semantics. Reads + literals only. */
    private fun isSideEffectFree(node: NodeIR): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR -> true
        else -> false
    }

    /** Body shapes that can be safely substituted: no calls, no allocations,
     *  no mutation, no effects. Only pure expressions over args + arithmetic.
     *
     *  Param reads in Mezze are emitted as ReadLocalIR(slot=closureCount+paramIdx).
     *  We accept ReadLocalIR if its slot is within the callee's param range
     *  (paramOk), i.e. it's reading an arg, not a local binding. Since this
     *  function is called only for callees with 0 captures and 0 locals, every
     *  ReadLocalIR with slot < paramCount is a param read. */
    private fun isInlinable(node: NodeIR, paramCount: Int): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadArgIR -> true
        is ReadLocalIR -> node.slot in 0 until paramCount
        is InlineBinaryOpIR -> isInlinable(node.left, paramCount) && isInlinable(node.right, paramCount)
        is IfIR -> isInlinable(node.cond, paramCount) && isInlinable(node.thenBr, paramCount) && isInlinable(node.elseBr, paramCount)
        else -> false
    }

    /** Bounded node count — bails out as soon as the limit is exceeded. */
    private fun countNodes(node: NodeIR, max: Int): Int {
        if (max <= 0) return Int.MAX_VALUE
        return when (node) {
            is InlineBinaryOpIR -> {
                val l = countNodes(node.left, max - 1)
                if (l >= max) l + 1 else 1 + l + countNodes(node.right, max - 1 - l)
            }
            is IfIR -> {
                val c = countNodes(node.cond, max - 1)
                if (c >= max) return c + 1
                val t = countNodes(node.thenBr, max - 1 - c)
                if (c + t >= max) return c + t + 1
                1 + c + t + countNodes(node.elseBr, max - 1 - c - t)
            }
            else -> 1
        }
    }

    /** Substitute callee's param reads with caller's arg expressions.
     *  Params are at ReadLocalIR(slot=0..paramCount-1) since we required
     *  the callee to have 0 captures and 0 locals. */
    private fun substituteArgs(node: NodeIR, args: List<NodeIR>): NodeIR = when (node) {
        is ReadArgIR -> args.getOrNull(node.index) ?: node
        is ReadLocalIR -> if (node.slot < args.size) args[node.slot] else node
        is InlineBinaryOpIR -> InlineBinaryOpIR(
            node.op, substituteArgs(node.left, args), substituteArgs(node.right, args), node.src
        )
        is IfIR -> IfIR(
            substituteArgs(node.cond, args),
            substituteArgs(node.thenBr, args),
            substituteArgs(node.elseBr, args),
            node.src
        )
        else -> node
    }

    /** Build a CallIndirectIR or CallIndirectScalarIR based on arg shape.
     *  Emits scalar form only for 1-field, no-spread record literals — typecheck
     *  guarantees the single field name matches the callee's single param name,
     *  so positional binding at index 0 is correct. Wider arities defer to the
     *  record path because we can't statically know the callee's param order. */
    private fun emitCallIndirect(
        fnIR: NodeIR,
        argIR: NodeIR,
        isTailCall: Boolean,
        src: SourceInfo
    ): NodeIR {
        // Skip the scalar path when the "function" expression is a record-field
        // read — this matches the inline-operator pattern (e.g. `x.add {r = y}`,
        // `x.neg {it = x}`) that TruffleCompiler.tryCompileInlineOp recognizes
        // and lowers to direct arithmetic / native calls. Going scalar here would
        // bypass that lowering and try to invoke a non-function value.
        val isInlineOpShape = fnIR is com.mezze.language.truffleir.ReadFieldIR
        if (!isInlineOpShape && argIR is MakeRecordIR && argIR.fields.isNotEmpty()
                && argIR.fields.none { it.name == "__spread__" }) {
            // Emit scalar IR for any field-arity ≥ 1. The runtime handles ordering:
            // it computes a permutation from caller field-name order to callee
            // param-name order at inline-cache time. Typecheck has guaranteed the
            // field-name set equals the callee param-name set.
            return com.mezze.language.truffleir.CallIndirectScalarIR(
                fnIR,
                argIR.fields.map { it.value },
                argIR.fields.map { it.name },
                isTailCall, src
            )
        }
        return CallIndirectIR(fnIR, argIR, isTailCall, src)
    }
}
