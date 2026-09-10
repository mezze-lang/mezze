package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.EffectBindingKind
import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typechecker.solving.IdentityVerdict
import com.mezze.language.typechecker.solving.identityVerdict
import com.mezze.language.typechecker.solving.sameDeclaredIdentity
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
    /** callLoc -> `decode` op hash of the derived codec impl matching that
     *  `from_json` call's USAGE-inferred target (DecodeTargetResolver). Wires the
     *  right decoder per call site where the target is a bare var in the type. */
    private val decodeOpBySite: Map<com.mezze.language.parsedast.Loc, Hash> = emptyMap(),
    /** #424 Piece 5 — for each seed call site, arg-record field names to
     *  strip before compiling to a call. Fields correspond to closure
     *  params that were dead-code eliminated from the specialized decl. */
    private val monoStripBySiteLoc: Map<com.mezze.language.parsedast.Loc, Set<String>> = emptyMap(),
    private val preloweredFunctions: Map<Hash, FunctionIR> = emptyMap(),
    /**
     * Dict method requirements discovered during stdlib pre-compilation.
     * Seeded into [dictMethodsForHash] so user-code call sites correctly inject
     * dict params for pre-lowered stdlib functions (whose modules are NOT
     * re-scanned during user compilation). Without this, a call like
     * `seq::min { xs = [High, Low, Mid] }` would miss the `__dict_lt` injection
     * because the prescan only covers `additionalModules` (a small prelude
     * subset) — not the full set of imported stdlib modules.
     */
    private val preloweredDictMethods: Map<Hash, Set<String>> = emptyMap(),
    /**
     * Final module substitution — used to refresh stale VarType on TypedVar
     * nodes whose type field was captured before the outer scope solved the
     * relevant TyVar (e.g. `<-` desugar binds the payload before the
     * synthetic ExceptionToResult handler's existential unifies with the
     * receiver at the outer scope). See #346 / project_346_stale_typevar_root_cause.
     * Empty default keeps existing behavior for callers that don't have one.
     */
    private val substitution: com.mezze.language.ast.solving.Substitution =
        com.mezze.language.ast.solving.Substitution.empty
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

    /** #359 — persistent slot → name map for the currently-compiling
     *  FunctionIR. Populated by allocLocal/forceAllocLocal every time a
     *  slot is minted. Unlike localsByName (which gets cleared as
     *  block/match scopes exit), this survives to compileFunctionBody's
     *  localNames construction so BDSL emit can pass real source names
     *  (`t1`, `t2`) to createLocal instead of `local_$i` placeholders.
     *  Reset at compileFunctionBody entry.
     */
    private val slotToNameForCurrentFn = mutableMapOf<Int, String>()

    /** Scope stack for nested match bindings: each entry maps name → slot */
    private val scopeStack = ArrayDeque<MutableMap<String, Int>>()

    /** Current function's param names */
    private var currentParamNames = listOf<String>()

    /** When true, the prescan and lambda-capture scan flag inlineable ops (lt, add, …)
     *  for dict-dispatch on VarType receivers. True during stdlib pre-compilation;
     *  false during user compilation (where phase 2 propagation carries dict
     *  requirements from preloweredDictMethods instead). */
    private var isStdlibMode = false

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

    /**
     * Do-block local aliases of a top-level (or ability-op) function reference:
     * `let g = addTen` records `"g" -> addTen's hash`. A call through the alias
     * (`g { x = 5 }`) must be routed through the SAME ByHash path a direct call
     * (`addTen { x = 5 }`) takes — dict injection + mono call-site rewrite —
     * otherwise a CONSTRAINED callee (e.g. `with NumAdd Int 'a`) reaches the
     * plain CallIndirect path with no `__dict_<op>` supplied, and the unresolved
     * dict-shaped op leaks into a value slot → ClassCastException at runtime
     * (the #18 family, indirect-call layer). Populated in the do-block Let
     * compile; cleared/restored with the block scope like [recursiveLetLambdas].
     */
    private val localFnAliases = mutableMapOf<String, Hash>()

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
     * Current function's dictionary param names (e.g., "__dict_to_dbg").
     * Set during compileFunctionBody when the function needs dict passing.
     * These are appended as extra params after the regular params.
     */
    private var currentDictParams = listOf<String>()

    private val census = CallSiteCensus()

    /**
     * decode op hash → the `Decode` impl that op belongs to. Lets a `from_json`
     * call site recover its concrete INPUT type (the impl's `for 'in`, last in
     * the class-arg tuple) from the per-site decoder DecodeTargetResolver picked,
     * even when the from_json receiver stayed a bare var (nested generic callback).
     */
    private val decodeImplByOpHash = mutableMapOf<Hash, TypedAbilityImpl>()

    /** Codec entry points that dispatch their inner `.decode` on the target type. */
    private val FROM_JSON_METHODS = setOf("from_json", "from_json_with")

    init {
        // Build ability operation index
        for (impl in abilityImpls) {
            for ((opName, _) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(
                    AbilityOp(impl.hash, impl.targetTypes, opHash)
                )
                if (opName == "decode") decodeImplByOpHash[opHash] = impl
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
    /**
     * Expose the discovered dict method requirements so the stdlib pre-compilation
     * step can accumulate them and pass them to the user-code compiler.
     */
    fun getDictMethodsForHash(): Map<Hash, Set<String>> = dictMethodsForHash.toMap()

    private fun prescanForDictMethods(modules: List<TypedModule>) {
        dictMethodsForHash.clear()
        // Seed with pre-lowered stdlib dict methods so call sites in the user
        // module correctly inject dicts for stdlib functions whose modules are
        // NOT in `additionalModules` during user compilation.
        for ((h, methods) in preloweredDictMethods) {
            dictMethodsForHash[h] = methods
        }

        // Phase 1: find functions with direct VarType method calls
        val allDecls = mutableListOf<TypedLetDecl>()
        for (module in modules) {
            for (decl in module.decls) {
                if (decl is TypedLetDecl) {
                    allDecls.add(decl)
                    val value = decl.value
                    if (value is TypedLambda) {
                        val constrained = arrowHasAbilityConstraint(value.type)
                        val methods = scanExprForVarTypeMethodCalls(value.body, constrained)
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
        //
        // Bug B (#86): blanket-impl bodies also gate off `with <Ability>` constraints
        // on the enclosing arrow. When the impl is compiled at stdlib-precompile
        // time, the abilityOpIndex has zero entries for the inner method (e.g. no
        // `impl Encode` in scope yet), so the receiver-only check would miss the
        // dispatch. Collect the constraint typevars from the op's own FunctionType
        // and thread them so `receiver.method` on any of those typevars is marked
        // regardless of impl-index size.
        for (impl in abilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                val bodyExpr = if (opBody is TypedLambda) opBody.body else opBody
                val constrained = arrowHasAbilityConstraint(opBody.type)
                val methods = scanExprForVarTypeMethodCalls(bodyExpr, constrained)
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
                                    val refreshed = substitution.apply(entry.value.type)
                                    val recovered = resolveVariantReceiverType(entry.value, refreshed) ?: refreshed
                                    // Extract concrete element types from seq/list literals
                                    // so VarType-wrapped containers (e.g. SeqType(VarType))
                                    // can still resolve the element-level impl.
                                    val elementType = extractConcreteElementTypeFromExpr(entry.value)
                                    if (elementType != null) concreteTypes.add(elementType)
                                    concreteTypes.add(recovered)
                                    collectDeepConcreteTypes(entry.value, concreteTypes)
                                }
                            }
                        }
                        // Filter out types that still contain VarType before resolution.
                        // A SeqType(VarType) could match a container-level impl like
                        // `impl Ord for ['a]` when the dict is actually for the element
                        // type — which is unknown. Only fully-concrete types (or types
                        // where expression-based extraction recovered the element type)
                        // can be safely resolved.
                        val resolvableTypes = concreteTypes.filter { !containsVarType(it) }
                        // Try to resolve each dict method from the concrete types.
                        // Only forward to caller when resolution actually fails.
                        for (methodName in calleeDictMethods) {
                            val resolved = resolveOpHashFromConcreteTypes(methodName, resolvableTypes)
                            if (resolved == null) {
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
     *
     * `activeConstraints` — the set of TypeVar ids that the enclosing function's
     * `with <Ability>` clause quantifies over. A receiver VarType whose id is in
     * this set is marked for dict-passing regardless of impl-index population, so
     * blanket-impl bodies (`with Encode JsonC 'a`) dispatch correctly even when
     * the impl-index has zero entries at stdlib-precompile time (Bug B / #86).
     */
    private fun scanExprForVarTypeMethodCalls(
        expr: TypedExpr,
        arrowIsConstrained: Boolean = false
    ): Set<String> {
        val result = mutableSetOf<String>()
        scanExprForVarTypeMethods(expr, result, arrowIsConstrained)
        return result
    }

    /**
     * True iff the arrow carries at least one `with <Ability>` constraint
     * that is still genuinely polymorphic after applying the final
     * substitution. A constraint like `NumAdd(VarType('a), IntType)` is
     * polymorphic only if `'a` remains a VarType after substitution —
     * if it resolves to `Int`, the constraint is `NumAdd(Int, Int)`,
     * which is a concrete built-in operation that doesn't need
     * dict-passing.
     *
     * Bug B (#86): a `with`-constrained arrow lets its body call ability
     * ops on a still-generic receiver (`{it: 'a} with Encode JsonC 'a
     * -> …` allows `it.encode {…}`). Any VarType-receiver method call
     * in such an arrow must be marked for dict-passing so the resolved
     * impl reaches the call site.
     *
     * Bug #X (dict-leak): recursive functions like `sum_to = {n} -> n +
     * sum_to {n-1}` generate a `NumAdd(VarType, IntType)` constraint
     * during inference. This constraint can leak onto enclosing function
     * types (e.g. `main`). Under the old `isNotEmpty()` check, the
     * leaked constraint caused `main` to be marked as constrained →
     * lambdas inside main got spurious `__dict_mul` captures → crash
     * when the callback's closureValues didn't match the inner BDSL
     * root's capture count. Applying substitution filters out these
     * resolved constraints correctly.
     */
    private fun arrowHasAbilityConstraint(type: MezzeType): Boolean {
        if (type !is MezzeType.FunctionType) return false
        if (type.abilityConstraints.isEmpty()) return false
        // A function with ability constraints only needs dict-passing
        // if its parameter type is genuinely polymorphic (contains VarType
        // after substitution). Functions like `fib: {n: Int} -> Int`
        // can carry stale constraints from type inference (the VarType
        // in the constraint was resolved but the constraint wasn't
        // cleaned up). These functions don't need dict-passing because
        // all their operations can be resolved from concrete types at
        // compile time.
        //
        // The generalized type variables in these stale constraints are
        // NOT in the module substitution (they were let-generalized),
        // so substitution.apply() on the constraint's typeArgs doesn't
        // help — they remain VarType. But the function's own paramType
        // IS fully resolved under the substitution.
        val resolvedParam = substitution.apply(type.paramType)
        return containsVarTypeDeep(resolvedParam)
    }

    /** Like [containsVarType] but also recurses into record fields and
     *  function types — needed to detect VarType in parameter types. */
    private fun containsVarTypeDeep(type: MezzeType): Boolean = when (type) {
        is MezzeType.VarType -> true
        is MezzeType.RecordType -> type.fields.values.any { containsVarTypeDeep(it) }
        is MezzeType.FunctionType -> containsVarTypeDeep(type.paramType) || containsVarTypeDeep(type.returnType)
        is MezzeType.SeqType -> containsVarTypeDeep(type.elementType)
        is MezzeType.ListType -> containsVarTypeDeep(type.elementType)
        is MezzeType.SetType -> containsVarTypeDeep(type.elementType)
        is MezzeType.SortedSetType -> containsVarTypeDeep(type.elementType)
        is MezzeType.MapType -> containsVarTypeDeep(type.keyType) || containsVarTypeDeep(type.valueType)
        is MezzeType.SortedMapType -> containsVarTypeDeep(type.keyType) || containsVarTypeDeep(type.valueType)
        is MezzeType.TypeApplication -> type.args.any { containsVarTypeDeep(it) }
        is MezzeType.VariantType -> type.constructors.values.any { payload ->
            payload != null && containsVarTypeDeep(payload)
        } || type.typeArgs.any { containsVarTypeDeep(it) }
        else -> false
    }

    /**
     * Whether a method call on a VarType receiver should be marked for
     * dict-passing (both when SCANNING for a callee's dict params and when
     * EMITTING the call). Single source of truth for the two mirror sites so
     * collection and consumption can never disagree.
     *
     * `to_str` / `to_dbg` with no real ability op are the InspectIR path
     * (auto-derived, receiver's shape baked at compile time) — never
     * dict-dispatched. There are many same-named `to_str` (one per type),
     * which would otherwise trip the multiple-candidates rule and demand a
     * `__dict_to_str` the caller never passes.
     *
     * #18: comparison / arithmetic ops (`lt`, `eq`, `add`, …) also need a dict
     * when the receiver is a still-generic `'a` under a `with Ord`/`with Eq`/
     * `with Num*` constraint — e.g. `x < acc` inside `list::min`'s body. The
     * old `methodName !in inlineableOps` guard excluded these entirely, so the
     * `<` fell through to the native comparator and crashed on a user variant
     * (`Cannot compare MezzeVariant`). The exclusion existed only so PRIMITIVE
     * operands inline (no dict) — but a primitive receiver is a concrete type,
     * not a VarType, so it never enters this branch; and a VarType receiver
     * that mono later specializes to a primitive gets its dict param stripped
     * during specialization. So for `inlineableOps` we require a REAL ability
     * op of that name to exist (`opCandidates` non-empty) in addition to the
     * constrained-arrow / multi-candidate rule — a bare `'a` with no `Ord`/`Eq`
     * impl anywhere in scope still falls through to the structural default
     * rather than demanding a dict nobody supplies.
     */
    private fun shouldDictDispatchVarTypeOp(
        methodName: String,
        arrowIsConstrained: Boolean
    ): Boolean {
        val isInspectSugar =
            methodName == Keywords.TO_STR || methodName == Keywords.TO_DBG
        if (isInspectSugar) return false
        val opCandidates = abilityOpIndex[methodName]
        val hasRealOp = opCandidates != null && opCandidates.isNotEmpty()
        // Inlineable ops (comparisons / arithmetic) only dict-dispatch when a
        // real ability op backs them — otherwise a VarType `'a` with no impl
        // takes the structural/native default (matching the old behavior of
        // never entering this branch for inlineable ops).
        if (methodName in inlineableOps && !hasRealOp) return false
        // #18 guard: in user compilation (!isStdlibMode), only flag inlineable
        // ops when the enclosing arrow explicitly carries an ability constraint
        // (`with Ord 'a`, `with NumMul 'a`, etc.). Without the constraint guard,
        // every generalized user function like `{x} -> x * 2` gets `__dict_mul`
        // in its frame — and CallIndirect callers (HOF callbacks, closures)
        // crash because they can't inject dicts.
        //
        // During stdlib compilation (isStdlibMode), we allow inlineable ops
        // unconditionally because stdlib functions (sort, min, max) are always
        // called via CallDirect, which can inject dicts at the call site.
        if (methodName in inlineableOps && !isStdlibMode && !arrowIsConstrained) return false
        return when {
            arrowIsConstrained -> true
            opCandidates != null && opCandidates.size > 1 -> true
            // For a non-inlineable op the historical rule required >1 candidate.
            // For an inlineable op backed by a real ability (Ord/Eq/Num), a
            // single candidate still needs the dict at a polymorphic call site,
            // since the receiver stays generic until mono specializes it.
            methodName in inlineableOps && hasRealOp -> true
            else -> false
        }
    }

    private fun scanExprForVarTypeMethods(
        expr: TypedExpr,
        out: MutableSet<String>,
        arrowIsConstrained: Boolean = false
    ) {
        when (expr) {
            is TypedCall -> {
                val fn = expr.function
                if (fn is TypedFieldAccess) {
                    val methodName = fn.field
                    // Refresh against the final substitution so an inlineable op
                    // whose receiver is a resolved primitive (e.g. `rank {..} <
                    // rank {..}` where `rank` returns Int, but the sub-expr's
                    // node type was still a var at build time) is NOT mistaken
                    // for a generic-'a comparison and given a spurious dict param.
                    // #18: without this, an `impl Ord for T` body's own internal
                    // `<` on Int would demand a `__dict_lt` its caller never
                    // supplies, misaligning the arg record at runtime.
                    val receiverType = substitution.apply(fn.record.type)
                    // Bug B (#86): a VarType receiver whose id is in the enclosing
                    // arrow's `with`-constraint typevar set needs dict-passing even
                    // when abilityOpIndex has zero entries at this compile step
                    // (blanket-impl body precompiled before any user impl exists).
                    // Otherwise fall back to the impl-index population check: only
                    // flag as needing dict if there are multiple candidates
                    // (ambiguous — can't resolve statically). Single candidate =
                    // resolved in compileCall without needing a dict param.
                    if (receiverType is VarType && shouldDictDispatchVarTypeOp(methodName, arrowIsConstrained)) {
                        out.add(methodName)
                    }
                    scanExprForVarTypeMethods(fn.record, out, arrowIsConstrained)
                } else {
                    scanExprForVarTypeMethods(fn, out, arrowIsConstrained)
                }
                scanExprForVarTypeMethods(expr.arg, out, arrowIsConstrained)
            }
            is TypedLambda -> {
                // #195 B2: descend into nested lambdas. When the inner body calls
                // `x.method` on a VarType receiver (e.g. inside a `seq::fold`
                // callback), the OUTER function holds the constraint and must
                // request `__dict_<method>` so the inner lambda can capture it
                // from the outer's frame. Skipping here used to make the inner
                // call fall through to plain field access at runtime → crash.
                //
                // Bug B (#86): an inner arrow that carries its own `with`
                // constraint promotes to arrowIsConstrained; the outer scan
                // already runs constrained when the OUTER arrow was.
                val innerConstrained = arrowIsConstrained || arrowHasAbilityConstraint(expr.type)
                scanExprForVarTypeMethods(expr.body, out, innerConstrained)
            }
            is TypedBlock -> {
                for (s in expr.statements) {
                    when (s) {
                        is TypedStatement.Let -> scanExprForVarTypeMethods(s.value, out, arrowIsConstrained)
                        is TypedStatement.Expr -> scanExprForVarTypeMethods(s.expr, out, arrowIsConstrained)
                    }
                }
                scanExprForVarTypeMethods(expr.result, out, arrowIsConstrained)
            }
            is TypedIf -> {
                scanExprForVarTypeMethods(expr.condition, out, arrowIsConstrained)
                scanExprForVarTypeMethods(expr.thenBranch, out, arrowIsConstrained)
                scanExprForVarTypeMethods(expr.elseBranch, out, arrowIsConstrained)
            }
            is TypedMatch -> {
                scanExprForVarTypeMethods(expr.scrutinee, out, arrowIsConstrained)
                for (c in expr.cases) {
                    c.guard?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained) }
                    scanExprForVarTypeMethods(c.body, out, arrowIsConstrained)
                }
            }
            is TypedRecord -> {
                for (e in expr.entries) {
                    when (e) {
                        is TypedRecordEntry.Field -> scanExprForVarTypeMethods(e.value, out, arrowIsConstrained)
                        is TypedRecordEntry.Spread -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained)
                    }
                }
            }
            is TypedFieldAccess -> scanExprForVarTypeMethods(expr.record, out, arrowIsConstrained)
            is TypedVariantConstruct -> expr.payload?.let { scanExprForVarTypeMethods(it, out, arrowIsConstrained) }
            is TypedRecordUpdate -> {
                scanExprForVarTypeMethods(expr.record, out, arrowIsConstrained)
                for (e in expr.updates) {
                    if (e is TypedRecordEntry.Field) scanExprForVarTypeMethods(e.value, out, arrowIsConstrained)
                }
            }
            is TypedPerform -> scanExprForVarTypeMethods(expr.body, out, arrowIsConstrained)
            is TypedPerformOp -> scanExprForVarTypeMethods(expr.arg, out, arrowIsConstrained)
            is TypedPipe -> {
                scanExprForVarTypeMethods(expr.left, out, arrowIsConstrained)
                scanExprForVarTypeMethods(expr.right, out, arrowIsConstrained)
            }
            is TypedSeq -> {
                for (e in expr.elements) {
                    when (e) {
                        is TypedSeqElement.Regular -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained)
                        is TypedSeqElement.Spread -> scanExprForVarTypeMethods(e.expr, out, arrowIsConstrained)
                    }
                }
            }
            is TypedStringLit -> {
                for (p in expr.parts) {
                    if (p is TypedStringPart.Interpolation) scanExprForVarTypeMethods(p.expr, out, arrowIsConstrained)
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
     * Run only the dict-method prescan for a module (no compilation).
     * Used by PrecompiledStdlib to discover dict requirements from cache-loaded
     * typed modules without the cost of full compilation.
     * The abilityOpIndex is populated in init{}, so it's ready at construction.
     */
    fun prescanOnly(module: TypedModule) {
        isStdlibMode = true
        prescanForDictMethods(listOf(module))
        isStdlibMode = false
    }

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
        monoSpecializations: List<com.mezze.language.typechecker.mono.Monomorphizer.Specialization> = emptyList(),
        isStdlibCompilation: Boolean = false
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
        isStdlibMode = isStdlibCompilation
        prescanForDictMethods(listOf(module) + additionalModules)
        // Keep isStdlibMode set during compilation so compileLambda's dict-capture
        // scan (which calls scanExprForVarTypeMethods → shouldDictDispatchVarTypeOp)
        // sees the same flag that prescan used.

        // Populate the hash → paramNames map used for scalar-ABI decisions at
        // direct-call sites (see emitCallDirect). Must run AFTER dict prescan
        // so we can include the synthetic __dict_<method> params each callee needs.
        populateHashToParamNames(listOf(module) + additionalModules)

        // Register mono spec paramNames so scalar-ABI callers in USER decls
        // that route to a specialized hash see the specialized signature
        // (which may have had closure params stripped by Piece 5). Without
        // this, `paramNamesForHash(specHash)` returns null and every call
        // to a spec falls back to the record ABI — correct but not scalar.
        for (spec in monoSpecializations) {
            val body = spec.specializedBody
            if (body is TypedLambda) {
                hashToParamNames[spec.specializedHash] = body.params.map { it.name }
            }
        }

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
        // #446 — canonicalize iter chains into FusedChainIR so the BDSL
        // fusion peel matches one shape. Only user-compiled functions
        // go through; stdlib was already canonicalized at precompile time
        // (StdlibPrecompiler). Passing the full inlinedMap (which merges
        // stdlib for MutEraser/TrivialWrapperInliner lookups) would re-
        // canonicalize stdlib functions without the precompiler's context,
        // producing Opaque sources for param-references that crash at
        // runtime (list::sum → BuiltinSumLongSeq with List input).
        val userHashes = deduped.map { it.hash }.toHashSet()
        val userFunctions = HashMap<Hash, FunctionIR>(userHashes.size)
        for ((h, fn) in inlinedMap) {
            if (h in userHashes) userFunctions[h] = fn
        }
        val canonicalizedUser = com.mezze.language.truffleir.ChainCanonicalizer
            .canonicalize(userFunctions, inlinedMap)
        // Merge back: canonicalized user functions over the full inlined map.
        val canonicalizedMap = HashMap(inlinedMap)
        canonicalizedMap.putAll(canonicalizedUser)
        // Dead-let elimination is opt-in: MEZZE_DEAD_LET_ELIMINATION=1.
        // The pass exists but its purity check needs work — see
        // DeadLetEliminator's file comment for the design notes.
        val cleanedMap = if (System.getenv("MEZZE_DEAD_LET_ELIMINATION") == "1")
            com.mezze.language.truffleir.DeadLetEliminator.eliminate(canonicalizedMap)
            else canonicalizedMap
        val final = deduped.map { cleanedMap[it.hash] ?: it }
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

        // Save recursiveLetLambdas for restore, but do NOT scope here.
        // The compileCall lookup guards on isTailCall, preventing cross-fn
        // CallDirectIR; and compileVar's MakeLambdaIR path needs to see
        // outer entries to avoid the closure-captures-null problem.
        val prevRecursiveLets = recursiveLetLambdas.toMap()

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
        val prevSlotToName = slotToNameForCurrentFn.toMap()
        slotToNameForCurrentFn.clear()
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
        // #359 — pull names from slotToNameForCurrentFn (populated by
        // allocLocal at slot mint time, persists across block-scope
        // clears of localsByName). Falls through to localsByName if
        // some path bypasses allocLocal, then final fallback to
        // local_$i placeholder.
        val localNames = (0 until localCount).map { i ->
            val slot = captureCount + allParamNames.size + i
            slotToNameForCurrentFn[slot]
                ?: localsByName.entries.find { it.value == slot }?.key
                ?: "local_$i"
        }
        // Restore parent fn's slot-name map (nested lambdas save/restore).
        slotToNameForCurrentFn.clear()
        slotToNameForCurrentFn.putAll(prevSlotToName)

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
        recursiveLetLambdas.clear()
        recursiveLetLambdas.putAll(prevRecursiveLets)

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
                        is TypedRecordEntry.Spread -> FieldIR(com.mezze.language.ast.MagicParams.SPREAD_FIELD, compileExpr(entry.expr))
                    }
                }, src
            )

            is TypedFieldAccess -> ReadFieldIR(compileExpr(expr.record), expr.field, expr.fieldIndex, src)

            is TypedRecordUpdate -> RecordUpdateIR(
                compileExpr(expr.record),
                expr.updates.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> FieldIR(entry.name, compileExpr(entry.value))
                        is TypedRecordEntry.Spread -> FieldIR(com.mezze.language.ast.MagicParams.SPREAD_FIELD, compileExpr(entry.expr))
                    }
                }, src
            )

            // Variant
            is TypedVariantConstruct -> {
                // #349 — mix ADT typeHash into the tag to avoid cross-ADT collisions.
                // Built-in ctors (Some/None/Ok/Err/…/LT/GT/EQ) keep old hashCode-only
                // tagging so they collide with runtime-native MezzeVariant.some/ok/…
                // by design.
                val adtHashHex = ((expr.type as? VariantType)?.identity as? TypeIdentity.Declared)?.hash?.hex
                val tag = com.mezze.language.ast.BuiltinTypes.computeVariantTag(expr.tag, adtHashHex)
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
            is TypedPerform -> {
                // Path E (#336): "fully discharged" iff this perform discharges
                // every effect in body's row (row-after-handler empty) → the
                // runtime runs the body eagerly; otherwise it builds a
                // MezzeSuspend holding the body continuation. S0/S1 (effect-model
                // migration, docs/effect-model.md): this signal is now computed
                // at EMIT (TypedAstBuilder) and carried on the node, so the
                // compiler no longer inspects the value-carried effect row.
                HandleIR(
                    compileExpr(expr.body),
                    expr.handlers.map { h ->
                        HandlerBindIR(h.performerHash, h.effectHash, h.performerName, h.params?.let { compileExpr(it) }, h.isScoped)
                    },
                    src,
                    staticallyFullyDischarges = expr.fullyDischarges
                )
            }
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
                if (EffectBindingKind.fromName(expr.name) != null) {
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
            is BindingRef.Native -> CallNativeIR(binding.name, UnitLitIR(src), src, isPure = isPureNative(binding.name))
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
            // Refresh the receiver type against the module's final substitution.
            // TypedVar nodes are built when the substitution may not yet know the
            // TyVar's resolution (e.g. `let next <- safe_decr {...}` — the payload
            // TyVar unifies with Int only at the OUTER scope, after the TypedVar
            // was built). Without this refresh, receiverType stays VarType and
            // every hash-based dispatch path (tryInlineOp, tryResolveAbilityOp,
            // dict-passing) falls through, ending in name-based ReadFieldIR ->
            // runtime crash under BDSL. See project_346_stale_typevar_root_cause.
            val rawReceiverType = substitution.apply(fn.record.type)

            // A `from_json` whose receiver type never solved (its value flows in
            // through a generic combinator callback binder, e.g.
            // `flat_map { f = { val } -> … val.from_json {} }`) reaches here as a
            // bare VarType: `tryResolveAbilityOp` then finds no `from_json` op, so
            // the whole `opHash != null` path — the ONLY place the per-site decoder
            // (decodeOpBySite) is wired — is skipped, and the call degrades to a
            // `ReadFieldByName("from_json")` that crashes CallIndirect at runtime.
            // But DecodeTargetResolver already pinned this site's decoder; that
            // decode impl's `for 'in` (its class-arg tuple's last slot) IS the
            // from_json input type. Recover it structurally and use it as the
            // receiver type, so from_json resolves and dispatch proceeds exactly as
            // for a directly-typed receiver. Keyed on structure (the decoder's input
            // type), never on names.
            val fromJsonInputType: MezzeType? =
                if (rawReceiverType is VarType && methodName in FROM_JSON_METHODS)
                    decodeOpBySite[expr.loc]
                        ?.let { decodeImplByOpHash[it] }
                        ?.targetTypes?.lastOrNull()
                        ?.takeIf { it !is VarType }
                else null

            // #13: a nullary variant-construct receiver whose type never resolved
            // (orphan var — e.g. `Low` in `Low < High`) can still be dispatched:
            // its constructor unambiguously names its declared VariantType. Recover
            // it so an `impl Ord`/`impl Eq for` a variant is found by
            // tryResolveAbilityOp below. No-op for primitives / resolved receivers /
            // ambiguous ctors, and a variant WITHOUT a user impl still resolves to
            // null → structural default (via tryResolveAbilityOp / the fall-through).
            // Composes with the from_json recovery above: apply that first, then the
            // variant recovery, so a receiver that is neither is left as the raw var.
            val receiverType =
                fromJsonInputType
                    ?: resolveVariantReceiverType(fn.record, rawReceiverType)
                    ?: rawReceiverType

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
            // the type variable wasn't fully substituted but only one impl exists),
            // and if that fails, try arg-based resolution (concrete types in the arg
            // record can pin the ability's type parameter — see #346).
            val opHashConcrete = tryResolveAbilityOp(methodName, receiverType, expr.arg)
            val opHash = opHashConcrete
                ?: if (receiverType is VarType) {
                    val candidates = abilityOpIndex[methodName]
                    if (candidates != null && candidates.size == 1) candidates.first().opHash
                    else tryResolveAbilityOpByArgs(methodName, expr.arg)
                } else null

            // #394: `x.to_dbg {}` / `x.to_str {}` with NO resolvable user impl
            // (opHash == null) — the auto-derived Inspect. Lower directly to
            // InspectIR with the receiver's shape baked in as a compile-time
            // PrintType. Gated on opHash == null so a user-declared ability
            // method NAMED to_str/to_dbg (e.g. `ability Stringify where {
            // to_str: ... }`) still dispatches to the user's impl.
            //
            // A VarType receiver takes this path too, as PrintType.Dynamic:
            // the printer then reads the shape from the runtime value. It must
            // NOT fall through to the mono/dict path, because Inspect is
            // derived rather than implemented — no dictionary slot is ever
            // populated for it, so dispatch found a record where it wanted the
            // impl function and died inside CallIndirect with
            // `UnsupportedSpecializationException … [MezzeRecord, Object[]]`.
            // Reachable from any `to_dbg` on a value whose type stayed
            // polymorphic, e.g. a match arm binding on an undeclared variant.
            if (opHash == null
                && (methodName == Keywords.TO_DBG || methodName == Keywords.TO_STR)) {
                val printType = PrintTypeBuilder(printTypeDefs).build(receiverType)
                val receiverIR = compileReceiverExpr(fn.record)
                return com.mezze.language.truffleir.InspectIR(
                    receiverIR, methodName == Keywords.TO_DBG, printType, src)
            }

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
                    // The `decode` dict for a `from_json` call dispatches on the
                    // TARGET type (the `'a` in `Result 'a`), which stays a bare var at
                    // this site. resolveOpHashFromConcreteTypes only sees the receiver/
                    // args (all Str/Json), so it can't tell two decoders apart and picks
                    // one for both. DecodeTargetResolver recovered the concrete target
                    // per call site from usage; use its decoder hash directly.
                    val perSiteDecode = decodeOpBySite[expr.loc]
                    for (dictMethodName in opDictMethods.sorted()) {
                        val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(dictMethodName)
                        val resolvedHash = if (dictMethodName == "decode" && perSiteDecode != null) perSiteDecode
                            else resolveOpHashFromConcreteTypes(dictMethodName, concreteTypesForDict)
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
                // #424 Piece 5: strip closure fields the specialized decl
                // no longer takes as params.
                val strip = if (specialized != null) monoStripBySiteLoc[expr.loc] else null
                val finalRecordIR = if (strip.isNullOrEmpty()) recordIR
                else MakeRecordIR(recordIR.fields.filter { it.name !in strip }, src)
                return emitCallDirect(effectiveHash, finalRecordIR, expr.isTailCall, src)
            }

            // VarType receiver — try dictionary passing first, then fall back to dynamic dispatch.
            // #18: comparison / arithmetic ops (`inlineableOps`) reach here too when the
            // receiver stayed a generic `'a` (concrete primitives already inlined above via
            // tryInlineOp, which returns null for VarType). They dict-dispatch only when a
            // real ability op backs them AND the current function actually holds the matching
            // `__dict_<op>` param (populated by the collection pass through the same
            // shouldDictDispatchVarTypeOp predicate) — otherwise we fall through to the
            // structural/native default, preserving prior behavior for un-constrained vars.
            val opIsInlineable = methodName in inlineableOps
            val inlineableNeedsDict = opIsInlineable &&
                abilityOpIndex[methodName]?.isNotEmpty() == true &&
                (currentParamNames.indexOf(com.mezze.language.ast.MagicParams.dictParamName(methodName)) >= 0 ||
                    currentCaptureNames[com.mezze.language.ast.MagicParams.dictParamName(methodName)] != null)
            if (receiverType is VarType && (!opIsInlineable || inlineableNeedsDict)) {
                val opCandidates = abilityOpIndex[methodName]
                // Bug B (#86): also enter this branch when there are no impl
                // candidates yet (blanket-impl body precompiled with the
                // ability-op index empty) but the current function has a
                // `__dict_<method>` param waiting. Without this, control
                // falls through to the regular-call path which compiles the
                // FieldAccess as `ReadFieldByName`, causing runtime
                // "Field not found: <method>".
                val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(methodName)
                val hasDictParam =
                    currentParamNames.indexOf(dictParamName) >= 0 ||
                    currentCaptureNames[dictParamName] != null
                if ((opCandidates != null && opCandidates.isNotEmpty()) || hasDictParam) {
                    val opCandidatesSafe: List<AbilityOp> = opCandidates ?: emptyList()
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
                    // (e.g. an auto-derived `impl Encode 'config for Str`'s body
                    // `config.encode_str { val = it, ctx = ctx }` needs __dict_encode_str
                    // against whatever `config` resolves to).
                    // We don't know the exact impl at this call site, but every impl
                    // candidate's transitive dict requirements can be unioned, and
                    // resolved against the concrete types of the user-visible record
                    // fields (`fmt`, etc.). Mirrors the concrete-receiver path above.
                    val transitiveDictMethods = HashSet<String>()
                    for (candidate in opCandidatesSafe) {
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
                            val innerDictName = com.mezze.language.ast.MagicParams.dictParamName(dictMethodName)
                            // Don't double-inject if the user already wrote this field.
                            if (fields.any { it.name == innerDictName }) continue
                            val resolvedHash = resolveOpHashFromConcreteTypes(dictMethodName, concreteTypesForDict)
                            if (resolvedHash != null) {
                                fields.add(FieldIR(innerDictName, ReadGlobalFnByHashIR(resolvedHash, src)))
                            } else {
                                // Forward from caller's own dict params (caller is itself polymorphic
                                // on the same format type).
                                val callerDictIdx = currentParamNames.indexOf(innerDictName)
                                if (callerDictIdx >= 0) {
                                    val slot = currentCaptureCount + callerDictIdx
                                    fields.add(FieldIR(innerDictName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                                }
                            }
                        }
                    }

                    val recordIR = MakeRecordIR(fields, src)

                    // Dictionary passing: if the current function has a __dict_<method> param,
                    // read the resolved impl function from the frame and call it directly.
                    // No runtime dispatch — the typechecker resolved everything statically.
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
                    //
                    // BUT skip the specialized CallDirect for a non-lambda user let
                    // (#389). Such a callee is a thunk that evaluates to a
                    // MezzeFunction, possibly PARTIALLY applied — e.g.
                    // `let addTen = adder {base = 10}` where `adder = {base} -> {x} ->
                    // x + base`. CallDirect'ing the specialized hash and passing the
                    // new arg record discards the accumulated partialArgs, so
                    // `addTen {x = 5}` returns the raw inner lambda instead of
                    // computing (an under-applied MezzeFunction). Fall through to the
                    // CallIndirect path below, which routes through the
                    // MezzeFunction's withPartialArgs slow path. The class-parameter
                    // NumAdd feature made the monomorphizer specialize these
                    // curried-`+` call sites where it previously did not — surfacing
                    // this latent gap in the mono-rewrite fast path.
                    val specialized = monoCallSiteRewrites[expr.loc]
                    if (specialized != null && !isNonLambdaUserLet(calleeHash)) {
                        census.byHashMono++
                        // #424 Piece 5: strip closure-arg record fields.
                        val strip = monoStripBySiteLoc[expr.loc]
                        val finalArgIR = if (strip.isNullOrEmpty() || argIR !is MakeRecordIR) argIR
                        else MakeRecordIR(argIR.fields.filter { it.name !in strip }, src)
                        return emitCallDirect(specialized, finalArgIR, expr.isTailCall, src)
                    }

                    // Check if the callee needs dictionary params. If so, inject
                    // resolved ability op functions into the argument record.
                    val calleeDictMethods = dictMethodsForHash[calleeHash]
                    if (calleeDictMethods != null && calleeDictMethods.isNotEmpty()) {
                        val rawFnType = fn.type as? FunctionType
                        val rawConstraintTypeArgs = rawFnType?.abilityConstraints
                            ?.flatMap { it.typeArgs }
                            ?.map { substitution.apply(it) }
                            ?: emptyList()
                        val constraintTypes = rawConstraintTypeArgs.filter { it !is VarType }
                        // When ALL constraint type args are still VarType after substitution,
                        // we're inside a generic function body calling another generic function.
                        // In this case, prefer forwarding dicts from the caller over resolving
                        // from concrete types (which may match a wrong blanket container impl).
                        val calleeConstraintsAreGeneric = rawConstraintTypeArgs.isNotEmpty() &&
                            rawConstraintTypeArgs.all { it is VarType }
                        val enrichedArgIR = injectDictFields(argIR, expr.arg, calleeDictMethods, constraintTypes, calleeConstraintsAreGeneric, src)
                        census.byHashDictInject++
                        emitCallDirect(calleeHash, enrichedArgIR, expr.isTailCall, src)
                    } else if (isNonLambdaUserLet(calleeHash)) {
                        // #389 — a top-level user let whose value is NOT a
                        // lambda (e.g. `let partialMul = multiply {x=2}`) is a
                        // thunk that evaluates to a MezzeFunction, possibly
                        // partial-applied. CallDirect'ing on the thunk hash
                        // and passing the new arg record discards the
                        // accumulated partialArgs from the returned
                        // MezzeFunction — multi-step currying silently
                        // corrupts. Fall through to CallIndirect so runtime
                        // dispatch goes through the MezzeFunction's
                        // withPartialArgs/buildCompleteRecord slow path.
                        // Only user-let thunks trigger this — ability ops and
                        // other ByHash refs still get the CallDirect fast path.
                        census.indirect++
                        emitCallIndirect(fnIR, argIR, expr.isTailCall, src)
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
                        argIR.fields.none { it.name == com.mezze.language.ast.MagicParams.SPREAD_FIELD }
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
                                isPure = sig.pure,
                                inlineOp = sig.inlineOp,
                            )
                        }
                    }
                    val nativeSig = NativeSignatures.signatureOf(binding.name)
                    CallNativeIR(binding.name, argIR, src,
                        isPure = nativeSig?.pure == true,
                        inlineOp = nativeSig?.inlineOp,
                    )
                }
                is BindingRef.Local -> {
                    // Resolve using the same scope logic as compileVar
                    val resolvedSlot = resolveLocalSlot(fn.name, binding.depth)
                    // Function-alias fast path: `let g = addTen; g { x = 5 }`. If g
                    // aliases a top-level function that needs dict params, a plain
                    // CallIndirect on g's slot would never supply `__dict_<op>` and a
                    // CONSTRAINED callee would leak the op into a value slot at runtime.
                    // Route through the alias target's ByHash path (dict injection +
                    // mono rewrite) exactly like a direct call. See [localFnAliases].
                    val aliasTargetHash = localFnAliases[fn.name]
                    val aliasDictMethods = aliasTargetHash?.let { dictMethodsForHash[it] }
                    if (aliasTargetHash != null && aliasDictMethods != null && aliasDictMethods.isNotEmpty()) {
                        val specialized = monoCallSiteRewrites[expr.loc]
                        if (specialized != null && !isNonLambdaUserLet(aliasTargetHash)) {
                            census.byHashMono++
                            val strip = monoStripBySiteLoc[expr.loc]
                            val finalArgIR = if (strip.isNullOrEmpty() || argIR !is MakeRecordIR) argIR
                            else MakeRecordIR(argIR.fields.filter { it.name !in strip }, src)
                            return emitCallDirect(specialized, finalArgIR, expr.isTailCall, src)
                        }
                        // Derive constraint types + generic-ness the same way the
                        // direct-call ByHash path does (~1846), so dict resolution
                        // sees the alias's concrete operand types (or forwards from
                        // the caller when all operands are still vars).
                        val rawFnType = fn.type as? FunctionType
                        val rawConstraintTypeArgs = rawFnType?.abilityConstraints
                            ?.flatMap { it.typeArgs }
                            ?.map { substitution.apply(it) }
                            ?: emptyList()
                        val constraintTypes = rawConstraintTypeArgs.filter { it !is VarType }
                        val calleeConstraintsAreGeneric = rawConstraintTypeArgs.isNotEmpty() &&
                            rawConstraintTypeArgs.all { it is VarType }
                        val enrichedArgIR = injectDictFields(
                            argIR, expr.arg, aliasDictMethods, constraintTypes, calleeConstraintsAreGeneric, src
                        )
                        census.byHashDictInject++
                        return emitCallDirect(aliasTargetHash, enrichedArgIR, expr.isTailCall, src)
                    }
                    if (resolvedSlot >= 0) {
                        census.indirect++
                        emitCallIndirect(ReadLocalIR(resolvedSlot, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                    } else {
                        // Recursive-let self-TAIL-call from within the lambda
                        // body: emit CallDirectIR so the bytecode compiler can
                        // apply self-tail-loop (rewrite as param update + continue).
                        //
                        // ONLY for tail calls: a non-tail self-call emits
                        // CallDirectIR without captures, but the trampoline
                        // expects [captures..., params...] → AIOOBE. Non-tail
                        // self-calls fall through to CallIndirectIR, which
                        // compileVar resolves via MakeLambdaIR (creating a
                        // closure with correct captures).
                        val recursiveLet = recursiveLetLambdas[fn.name]
                        if (recursiveLet != null && expr.isTailCall) {
                            census.byHashPlain++
                            return emitCallDirect(recursiveLet.hash, argIR, true, src)
                        }
                        // If depth > 0 and not found locally, check if it's a top-level
                        // declaration. Use CallDirectIR for better performance.
                        //
                        // #389 — but skip CallDirect when the top-level is a
                        // non-lambda `let X = expr` thunk. Its runtime value
                        // may be a partial-applied MezzeFunction; CallDirect
                        // on the thunk hash discards the accumulated partial
                        // args. Fall through to CallIndirect so runtime
                        // dispatch goes through the MezzeFunction.
                        if (binding.depth > 0) {
                            val topDecl = declIndex?.values?.find { it.name == fn.name }
                            if (topDecl != null && !isNonLambdaUserLet(topDecl.hash)) {
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
     * @param constraintTypes concrete types from the callee's ability constraints (preferred for resolution)
     * @param src source info for generated IR nodes
     * @return argIR with injected dict fields, or original argIR if resolution fails
     */
    private fun injectDictFields(
        argIR: NodeIR,
        argExpr: TypedExpr,
        dictMethods: Set<String>,
        constraintTypes: List<MezzeType>,
        calleeConstraintsAreGeneric: Boolean,
        src: SourceInfo
    ): NodeIR {
        // Collect concrete types from the argument record as fallback. Refresh
        // each field's type against the final substitution and, for a nullary
        // variant literal whose type stayed an orphan var (e.g.
        // `smaller { a = High, b = Low }` where `High`/`Low` never solved to
        // `Priority`), recover the declared VariantType from its constructor —
        // the same #13 recovery the receiver path uses. Without this the arg
        // types read as bare VarType, dict resolution finds no impl, and the
        // callee's DictMethodCallIR is handed an empty record instead of the
        // `impl Ord/Eq` op function.
        val concreteTypes = mutableListOf<MezzeType>()
        if (argExpr is TypedRecord) {
            for (entry in argExpr.entries) {
                if (entry is TypedRecordEntry.Field) {
                    val refreshed = substitution.apply(entry.value.type)
                    val recovered = resolveVariantReceiverType(entry.value, refreshed) ?: refreshed
                    // For container expressions (seq/list literals), extract the
                    // concrete ELEMENT type from the expression tree FIRST. The
                    // container's type annotation may have unresolved VarType elements
                    // (generalized type variable not in the module substitution), but
                    // the individual elements — especially variant constructors — can
                    // be recovered. Placing element types BEFORE container types ensures
                    // `min_manual { xs = [High, Low, Mid] }` resolves `lt` against
                    // `impl Ord for P` (element-level) rather than `impl Ord for ['a]`
                    // (seq-level), because resolveOpHashFromConcreteTypes tries types in
                    // order and returns the first match.
                    val elementType = extractConcreteElementTypeFromExpr(entry.value)
                    if (elementType != null) concreteTypes.add(elementType)
                    concreteTypes.add(recovered)
                    // When the callee's constraint type args are generalized VarType
                    // (not in the module substitution), the direct field type (e.g.
                    // RecordType{x:Int,y:Int} or VariantType{Range{min:Int,max:Int},Empty})
                    // won't match any ability impl. Collect concrete types from deeper
                    // within the arg value's expression tree so the actual leaf types
                    // (Int, Str, etc.) are reachable by resolveOpHashFromConcreteTypes.
                    collectDeepConcreteTypes(entry.value, concreteTypes)
                }
            }
        }

        // For each required dict method, try to resolve the op hash using concrete types.
        // Try constraint types first (exact), then fall back to argument field types.
        //
        // When the callee's constraint type args are all VarType (generic call inside
        // a generic function body), prefer forwarding from the caller's dict params
        // over resolving from concrete types. Inside such a body the arg types are
        // still generic (VarType wrapped in containers like SeqType), and resolving
        // against them can match a CONTAINER-level impl (e.g. `impl Ord for ['a]`)
        // instead of the intended ELEMENT-level impl that the caller's dict carries.
        val dictFields = mutableListOf<FieldIR>()
        for (methodName in dictMethods.sorted()) {
            val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(methodName)

            // When constraints are generic, prefer forwarding from caller
            if (calleeConstraintsAreGeneric) {
                // 1a. Forward from caller param
                val callerDictIdx = currentParamNames.indexOf(dictParamName)
                if (callerDictIdx >= 0) {
                    val slot = currentCaptureCount + callerDictIdx
                    dictFields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                    continue
                }
                // 1b. Forward from caller capture
                val callerCaptureIdx = currentCaptureNames[dictParamName]
                if (callerCaptureIdx != null) {
                    dictFields.add(FieldIR(dictParamName, ReadCaptureIR(callerCaptureIdx, SlotKind.OBJECT, src)))
                    continue
                }
            }

            // 2. Resolve from concrete types
            val opHash = if (constraintTypes.isNotEmpty()) {
                resolveOpHashFromConcreteTypes(methodName, constraintTypes)
                    ?: resolveOpHashFromConcreteTypes(methodName, concreteTypes)
            } else {
                resolveOpHashFromConcreteTypes(methodName, concreteTypes)
            }
            if (opHash != null) {
                dictFields.add(FieldIR(dictParamName, ReadGlobalFnByHashIR(opHash, src)))
            } else {
                // 3. Fallback: forward from caller if available
                val callerDictIdx = currentParamNames.indexOf(dictParamName)
                if (callerDictIdx >= 0) {
                    val slot = currentCaptureCount + callerDictIdx
                    dictFields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                } else {
                    val callerCaptureIdx = currentCaptureNames[dictParamName]
                    if (callerCaptureIdx != null) {
                        dictFields.add(FieldIR(dictParamName, ReadCaptureIR(callerCaptureIdx, SlotKind.OBJECT, src)))
                    } else {
                        // Don't inject a sentinel — leave the field absent so the
                        // callee's trampoline resolves DictFallback for primitive ops
                        // (add, lt, eq, …). See TrampolineRootNode.unpackRecordSlow.
                    }
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
     * Check if a type contains any VarType in its structure.
     * A type like SeqType(VarType) is "partially generic" — the container is
     * known but the element is unknown. Resolving ability impls against such
     * types can match blanket container-level impls (e.g. `impl Ord for ['a]`)
     * when the intended resolution is for the element type.
     */
    private fun containsVarType(type: MezzeType): Boolean = when (type) {
        is VarType -> true
        is SeqType -> containsVarType(type.elementType)
        is ListType -> containsVarType(type.elementType)
        is SetType -> containsVarType(type.elementType)
        is SortedSetType -> containsVarType(type.elementType)
        is MapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
        is SortedMapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
        is TypeApplication -> type.args.any { containsVarType(it) }
        else -> false
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
        // passes `xs: [Str]` and the callee needs an `Encode 'config for 'a` impl
        // for the element type, the right impl is `Encode <config> for Str` —
        // extract it from the container's element/value type.
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
        // Third pass: descend into record field types. When the caller passes
        // `{ point = { x = 6, y = 7 } }` and the callee's constraint is
        // `with NumMul 'a` where `'a` is the type of `x`/`y`, the concrete arg
        // field type is `RecordType{x:Int, y:Int}` — not `Int` directly. The
        // constraint type arg stays a generalized VarType (not in the module
        // substitution), so `constraintTypes` is empty and we fall back to
        // `concreteTypes`. Extract record field types so `Int` is reachable.
        for (concreteType in concreteTypes) {
            if (concreteType is RecordType) {
                for ((_, fieldType) in concreteType.fields) {
                    val resolved = substitution.apply(fieldType)
                    if (resolved !is VarType) {
                        val opHash = tryResolveAbilityOp(methodName, resolved)
                        if (opHash != null) return opHash
                    }
                }
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
                is TypedRecordEntry.Spread -> FieldIR(com.mezze.language.ast.MagicParams.SPREAD_FIELD, compileExpr(entry.expr))
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
        // Include context types to make hash unique per instantiation. Use the
        // structural type hasher (appendTypeInScope), NOT t.toString() — toString is a
        // compiler-internal, depth-truncated rendering (unstable across compiler
        // versions, and lossy → collisions). This wrapperHash is persisted as a
        // FunctionIR key, so it must be a stable function of the type's identity. (#316)
        for (t in allConcreteTypes) {
            com.mezze.language.ast.hashing.appendTypeInScope(hashBuilder, t)
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
        // (e.g. `x.encode { config = config }` inside a `seq::fold` callback). The
        // lambda needs its own dict params for each method whose receiver is
        // a TyVar. These dicts can't come through the indirect-call signature
        // (it's `f : {acc, x} -> 'b`, no room for extra args), so we capture
        // them from the PARENT's dict-param slots. The parent already gained
        // those slots via the B2 scanner-descent fix.
        val lambdaDictMethods = sortedSetOf<String>()
        // The lambda's own arrow might not carry `with <Ability>` constraints,
        // but the PARENT scope may (e.g. `{ a, b } with Ord 'a -> ({ x, y } -> x <= y)…`).
        // If the parent has dict params, the lambda body may reference VarType ops
        // that need those dicts captured. Derive arrowIsConstrained from both the
        // lambda's own type AND whether the parent scope carries dicts.
        val parentHasDicts = currentParamNames.any { it.startsWith("__dict_") }
        val lambdaConstrained = parentHasDicts || arrowHasAbilityConstraint(expr.type)
        scanExprForVarTypeMethods(expr.body, lambdaDictMethods, lambdaConstrained)
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
        val savedFnAliases = localFnAliases.toMap()
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

                    // FUNCTION-ALIAS REGISTRATION: `let g = addTen` where the RHS is a
                    // bare reference to a top-level function. Record `g -> addTen's hash`
                    // so a call through `g` routes through the ByHash path (dict injection
                    // + mono rewrite) instead of a plain CallIndirect. Without this, a
                    // CONSTRAINED callee (`with NumAdd Int 'a`) invoked via the alias never
                    // receives its `__dict_<op>` and the op leaks into a value slot at
                    // runtime. Only genuine lambda callees are aliased here (not value
                    // thunks); the slot is still populated normally below so `g` can also
                    // be used as a plain value.
                    if (stmtValue is TypedVar && stmtValue.binding is BindingRef.ByHash) {
                        val targetHash = (stmtValue.binding as BindingRef.ByHash).hash
                        if (isLambdaHash(targetHash, declIndex?.get(targetHash))) {
                            localFnAliases[stmt.name] = targetHash
                        }
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
        localFnAliases.clear()
        localFnAliases.putAll(savedFnAliases)
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

                // HOLE A: synthesize runtime tests for nested constructor/literal
                // sub-patterns and fold them into the guard. The outer pattern has
                // already bound each field to a slot, so the test reads those slots
                // (ReadLocalIR) and recurses. A false test falls through to the next
                // arm via the guard mechanism below. When there is also a user guard,
                // gate it behind the nested test (IfIR short-circuit) so the guard
                // never runs against a value whose nested shape didn't match.
                val nestedTest: NodeIR? = when (pat) {
                    is TypedRecordPattern -> {
                        val terms = pat.fields.mapNotNull { fp ->
                            if (fp.pattern is TypedVarPattern || fp.pattern is TypedWildcardPattern || fp.pattern == null) {
                                null
                            } else {
                                val slot = fieldBindingSlot(fp)
                                if (slot < 0) null
                                else buildNestedTests(fp.pattern, ReadLocalIR(slot, SlotKind.OBJECT, src), src)
                            }
                        }
                        andAll(terms, src)
                    }
                    is TypedVariantPattern -> {
                        // Nested tests inside a top-level variant arm's payload fields.
                        val payload = pat.payload
                        if (payload is TypedRecordPattern) {
                            val terms = payload.fields.mapNotNull { fp ->
                                if (fp.pattern is TypedVarPattern || fp.pattern is TypedWildcardPattern || fp.pattern == null) {
                                    null
                                } else {
                                    val slot = fieldBindingSlot(fp)
                                    if (slot < 0) null
                                    else buildNestedTests(fp.pattern, ReadLocalIR(slot, SlotKind.OBJECT, src), src)
                                }
                            }
                            andAll(terms, src)
                        } else null
                    }
                    is TypedSeqPattern -> {
                        // B7: a top-level seq arm's REFUTABLE element sub-patterns
                        // (`[Some {val=x}, ...rest]`) are bound (via collectNested-
                        // SeqDestructuring → `__seq_tmp_$idx`) but never TESTED — the
                        // arm matched on length alone, so `[None]` slipped through,
                        // bound x=null, and crashed. Test each refutable element
                        // against its already-bound temp slot.
                        val terms = pat.elements.mapIndexedNotNull { idx, elem ->
                            if (elem is TypedVarPattern || elem is TypedWildcardPattern) null
                            else {
                                val elemSlot = localsByName["__seq_tmp_$idx"]
                                if (elemSlot == null) null
                                else buildNestedTests(elem, ReadLocalIR(elemSlot, SlotKind.OBJECT, src), src)
                            }
                        }
                        andAll(terms, src)
                    }
                    else -> null
                }
                if (nestedTest != null) {
                    guardIR = if (guardIR == null) nestedTest
                              else IfIR(nestedTest, guardIR, BoolLitIR(false, src), src)
                }

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
                        // A level nested two-or-more deep is not bound by the flat
                        // PatternIR; first read its record off the parent temp slot.
                        // Outer levels are earlier in the list, so the parent slot
                        // is already populated by the time we reach a child.
                        val rf = nd.readFrom
                        if (rf != null) {
                            val readParent = ReadLocalIR(rf.first, SlotKind.OBJECT, src)
                            val readThis = ReadFieldIR(readParent, rf.second, -1, src)
                            destructureStmts.add(StmtIR.Let(nd.tempSlot, SlotKind.OBJECT, "__nested_lvl", Hash.ZERO, readThis, src))
                        }
                        for ((fieldName, bindingSlot) in nd.fieldBindings) {
                            val readTemp = ReadLocalIR(nd.tempSlot, SlotKind.OBJECT, src)
                            val readField = ReadFieldIR(readTemp, fieldName, -1, src)
                            destructureStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, "__nested_" + fieldName, Hash.ZERO, readField, src))
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
                            // Handle rest binding in inner seq pattern.
                            // "_" (`..._`) matches the tail but binds nothing — skip it.
                            val restName = innerPat.rest
                            if (restName != null && restName != "_") {
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
        val fieldBindings: List<Pair<String, Int>>,  // (fieldName, targetSlot)
        // Non-null for a level nested two-or-more deep: (parentTempSlot, field)
        // to read this level's record off the parent's temp slot. Chains depth.
        val readFrom: Pair<Int, String>? = null
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
            is TypedVariantPattern -> (pattern.payload as? TypedRecordPattern)?.let {
                collectNestedRecordFields(it, out, null)
            }
            is TypedRecordPattern -> collectNestedRecordFields(pattern, out, null)
            else -> {}
        }
    }

    // For each field of [record] whose sub-pattern is itself a record, allocate a
    // temp slot for the field value, bind its direct-variable inner fields, and
    // RECURSE so arbitrarily deep nesting works. The flat PatternIR only reaches
    // the outermost level, so a field nested two-or-more deep reads its value from
    // its parent's temp slot via [parent] (parentTempSlot to read this field off).
    private fun collectNestedRecordFields(
        record: TypedRecordPattern,
        out: MutableList<NestedDestructureInfo>,
        parent: Int?,
    ) {
        for (fp in record.fields) {
            val nested = fp.pattern as? TypedRecordPattern ?: continue
            val tempSlot = forceAllocLocal("__tmp_${fp.name}")
            val fieldBindings = nested.fields.map { innerFp ->
                val bindingName = if (innerFp.pattern is TypedVarPattern) (innerFp.pattern as TypedVarPattern).name else innerFp.name
                val slot = scopeStack.lastOrNull()?.get(bindingName)
                    ?: localsByName[bindingName]
                    ?: forceAllocLocal(bindingName)
                Pair(innerFp.name, slot)
            }
            // Top-level nested field: the flat PatternIR already bound tempSlot
            // (parent == null). Deeper: read this field off the parent temp slot.
            val readFrom = if (parent == null) null else Pair(parent, fp.name)
            out.add(NestedDestructureInfo(tempSlot, fieldBindings, readFrom))
            collectNestedRecordFields(nested, out, tempSlot)
        }
    }

    /**
     * HOLE A: build a runtime boolean test for nested CONSTRUCTOR/LITERAL
     * sub-patterns inside a record field or variant payload field. A flat
     * `PatternIR.Record`/`PatternIR.Variant` only EXTRACTS + BINDS its fields —
     * it never tests a nested tag/literal. So `match r is { c = Red } -> …` (r a
     * record with `c: Color`) selected its arm on the record's SHAPE alone and
     * bound `c`'s value, silently ignoring that the nested `Red` must MATCH — a
     * `{ c = Blue }` value wrongly ran the `Red` arm. Same for a nested variant
     * in a variant payload (`Wrap { inner = A { n } }`) and a nested literal
     * (`{ n = 0 }`).
     *
     * We synthesize a pure boolean expression that reads the field (via
     * `accessExpr`) and tests the nested pattern, and AND it (short-circuit, see
     * [compileMatch]) into the case guard. Because a false guard falls through to
     * the NEXT arm (the guard→body→next-arm structure already handles that), a
     * nested mismatch correctly abandons the arm — which a body-level test could
     * never do. Returns null when the sub-pattern is irrefutable (var/wildcard/
     * absent), i.e. no test is needed.
     *
     * Bindings are unaffected: the outer pattern + [collectNestedDestructuring]
     * still bind every field; this only ADDS tests. Nested variant *payload*
     * variables are bound by the synthesized `MatchIR`'s Variant pattern into the
     * same frame slots the body reads (resolved by name), so `A { n } -> n` still
     * sees `n`. Nested seq / string-interp sub-patterns are not tested here (they
     * are separate, pre-existing gaps); they return null and keep prior behavior.
     */
    /**
     * The frame slot that [compilePattern] binds a record/variant field's VALUE
     * to — must mirror that logic exactly so [buildNestedTests] reads the same
     * slot the outer pattern populated. A nested-record field goes to `__tmp_*`
     * (allocated by [collectNestedDestructuring]); everything else goes to the
     * field's binding name (renamed var, else the field name).
     */
    private fun fieldBindingSlot(fp: TypedFieldPattern): Int {
        if (fp.pattern is TypedRecordPattern) {
            return localsByName["__tmp_${fp.name}"] ?: -1
        }
        val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
        return scopeStack.lastOrNull()?.get(bindingName)
            ?: localsByName[bindingName]
            ?: -1
    }

    private fun buildNestedTests(pattern: TypedPattern?, accessExpr: NodeIR, src: SourceInfo): NodeIR? {
        if (pattern == null) return null
        return when (pattern) {
            is TypedVarPattern, is TypedWildcardPattern -> null
            is TypedLiteralPattern -> InlineBinaryOpIR(InlineOp.EQ, accessExpr, compileExpr(pattern.value), src)
            is TypedRecordPattern -> {
                val terms = pattern.fields.mapNotNull { fp ->
                    buildNestedTests(fp.pattern, ReadFieldIR(accessExpr, fp.name, -1, src), src)
                }
                andAll(terms, src)
            }
            is TypedVariantPattern -> {
                // Test the tag AND (recursively) the payload, atomically, via a
                // nested match whose true-arm binds the payload and returns the
                // sub-tests, and whose wildcard-arm returns false.
                val adtHashHex = ((pattern.type as? VariantType)?.identity as? TypeIdentity.Declared)?.hash?.hex
                val tag = com.mezze.language.ast.BuiltinTypes.computeVariantTag(pattern.tag, adtHashHex)

                val fieldNames = mutableListOf<String>()
                val fieldSlots = mutableListOf<Int>()
                var payloadTest: NodeIR? = null
                when (val payload = pattern.payload) {
                    is TypedRecordPattern -> {
                        val terms = mutableListOf<NodeIR>()
                        for (fp in payload.fields) {
                            fieldNames.add(fp.name)
                            val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                            val slot = scopeStack.lastOrNull()?.get(bindingName)
                                ?: localsByName[bindingName]
                                ?: forceAllocLocal(bindingName)
                            fieldSlots.add(slot)
                            // A nested sub-pattern inside the payload field reads the
                            // just-bound slot and recurses.
                            buildNestedTests(fp.pattern, ReadLocalIR(slot, SlotKind.OBJECT, src), src)
                                ?.let { terms.add(it) }
                        }
                        payloadTest = andAll(terms, src)
                    }
                    is TypedVarPattern -> {
                        fieldNames.add(payload.name)
                        val slot = scopeStack.lastOrNull()?.get(payload.name)
                            ?: localsByName[payload.name]
                            ?: forceAllocLocal(payload.name)
                        fieldSlots.add(slot)
                    }
                    else -> {}
                }

                val trueBody: NodeIR = payloadTest ?: BoolLitIR(true, src)
                val matchCase = CaseIR(
                    PatternIR.Variant(tag, pattern.tag, fieldNames, fieldSlots),
                    emptyList(), null, trueBody, src,
                )
                val wildcardCase = CaseIR(PatternIR.Wildcard(-1), emptyList(), null, BoolLitIR(false, src), src)
                MatchIR(accessExpr, listOf(matchCase, wildcardCase), src)
            }
            is TypedSeqPattern, is TypedStringInterpPattern -> {
                // B4/B6: a nested seq / string-interpolation sub-pattern needs a
                // runtime shape test (length/prefix), or the enclosing arm would
                // match on the outer tag/field-presence alone and fire wrongly.
                // Build a nested match over the field value: `<pattern> -> true,
                // _ -> false`, reusing compilePattern for the PatternIR + its slot
                // allocation (mirrors the TypedVariantPattern case above).
                val patIR = compilePattern(pattern)
                val trueCase = CaseIR(patIR, emptyList(), null, BoolLitIR(true, src), src)
                val wildcardCase = CaseIR(PatternIR.Wildcard(-1), emptyList(), null, BoolLitIR(false, src), src)
                MatchIR(accessExpr, listOf(trueCase, wildcardCase), src)
            }
        }
    }

    /** AND a list of boolean tests, short-circuit-strict; null if empty. */
    private fun andAll(terms: List<NodeIR>, src: SourceInfo): NodeIR? =
        terms.reduceOrNull { acc, t -> InlineBinaryOpIR(InlineOp.AND, acc, t, src) }

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
                val adtHashHex = ((pattern.type as? VariantType)?.identity as? TypeIdentity.Declared)?.hash?.hex
                val tag = com.mezze.language.ast.BuiltinTypes.computeVariantTag(pattern.tag, adtHashHex)
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
                        // "_" is the tail-ignoring rest (`..._`): match the tail but bind nothing,
                        // just like a wildcard element (slot -1).
                        if (restName != null && restName != "_") {
                            scopeStack.lastOrNull()?.get(restName)
                                ?: localsByName[restName]
                                ?: forceAllocLocal(restName)
                        } else -1
                    },
                    // hasRest: any rest (named OR `..._`) makes this an open, length>=N pattern.
                    hasRest = pattern.rest != null
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

    private fun tryResolveAbilityOp(
        methodName: String,
        receiverType: MezzeType,
        argRecord: TypedExpr? = null,
    ): Hash? {
        val effectiveType = receiverType
        val candidates = abilityOpIndex[methodName] ?: return null
        // `AbilityOp.targetTypes` is the impl's full class-arg tuple, positional to the
        // ability's type params with the receiver ("self") LAST (extra class params
        // first). Match the receiver against the receiver POSITION only, so a class-param
        // co-operand of the same type (e.g. `NumAdd Duration for Instant`, tuple
        // [Duration, Instant]) is not spuriously selected for a Duration receiver.
        // See project_classparam_runtime_dispatch_tuple.
        val receiverMatches = candidates.filter { op ->
            val recvTarget = op.targetTypes.lastOrNull() ?: return@filter false
            typeMatchesTarget(effectiveType, recvTarget)
        }
        // Several impls share the receiver — a class-param ability like `NumSub 'r for
        // 'l` with both `NumSub Duration for Instant` and `NumSub Instant for Instant`.
        // The receiver alone can't disambiguate; pick the impl whose CO-OPERAND (the
        // tuple slot before the receiver) matches the arg's `r` field type. Content
        // comparison only — no positions-as-identity, no Loc, no strings.
        if (receiverMatches.size > 1 && argRecord is TypedRecord) {
            val coOperandType = argRecord.entries
                .filterIsInstance<TypedRecordEntry.Field>()
                .firstOrNull { it.name == Keywords.R }
                ?.let { substitution.apply(it.value.type) }
                ?.takeIf { it !is VarType }
            if (coOperandType != null) {
                val byCoOperand = receiverMatches.filter { op ->
                    val coTarget = op.targetTypes.getOrNull(op.targetTypes.size - 2)
                    coTarget != null && typeMatchesTarget(coOperandType, coTarget)
                }
                // Dedup by resolved impl (opHash): the index may register the same impl
                // more than once (e.g. across module-load passes). A unique WINNING
                // impl is what matters, not a unique index entry.
                val uniqueHashes = byCoOperand.map { it.opHash }.distinct()
                if (uniqueHashes.size == 1) return uniqueHashes.first()
            }
        }
        val match = receiverMatches.firstOrNull()
        if (match != null) return match.opHash
        // Fallback: if there's exactly one candidate and the receiver is a variant/type-application,
        // use it. This handles user-defined type aliases where the variant type's constructors
        // don't match the known aliases in typeMatchesTarget.
        if (candidates.size == 1 && (effectiveType is VariantType || effectiveType is TypeApplication)) {
            return candidates.first().opHash
        }
        return null
    }

    /**
     * Ability method resolution using arg types when the receiver is unresolved.
     *
     * For `x.eq {r = 0}` where `x : VarType` but `0 : Int`, ability
     * `eq: {it: 'a, r: 'a} -> Bool` — the concrete arg pins `'a = Int` so
     * we can dispatch to `impl Eq for Int` even though the receiver type
     * never got solved. Necessary because upstream constraint generation
     * (specifically the `<-` desugar) can leave payload TyVars unresolved
     * in the final substitution — see project_346_stale_typevar_root_cause.
     *
     * Strategy: for each ability-op candidate, check whether ANY concrete
     * type on the arg record matches one of the candidate's targetTypes.
     * If a single candidate matches uniquely, use it. Bail on ambiguity.
     */
    private fun tryResolveAbilityOpByArgs(
        methodName: String,
        argRecord: TypedExpr,
    ): Hash? {
        val candidates = abilityOpIndex[methodName] ?: return null
        if (argRecord !is TypedRecord) return null
        val argConcreteTypes = argRecord.entries.mapNotNull { entry ->
            if (entry !is TypedRecordEntry.Field) return@mapNotNull null
            val t = substitution.apply(entry.value.type)
            if (t is VarType) null else t
        }
        if (argConcreteTypes.isEmpty()) return null
        val matches = candidates.filter { op ->
            op.targetTypes.any { target ->
                argConcreteTypes.any { concrete -> typeMatchesTarget(concrete, target) }
            }
        }
        return if (matches.size == 1) matches.first().opHash else null
    }

    private fun findAbilityOpBody(methodName: String, receiverType: MezzeType): TypedExpr? {
        val effectiveType = receiverType
        for (impl in abilityImpls) {
            // Match the receiver POSITION (self is last in the class-arg tuple), not
            // any element — a class-param co-operand of the same type must not select
            // this impl's body. Used only for the receiver-param name (uniform across
            // impls), but kept precise regardless.
            val recvTarget = impl.targetTypes.lastOrNull()
            if (impl.operations.containsKey(methodName) &&
                recvTarget != null && typeMatchesTarget(effectiveType, recvTarget)) {
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
            concreteType is CharType && targetType is CharType -> true
            concreteType is FracType && targetType is FracType -> true
            // Bool has two surface types — the primitive `BoolType` and the
            // constructor-form `VariantType({True, False})`. A codec-generated
            // `impl … for Bool` registers its target as `BoolType`, but the
            // receiver at a bare `True.encode {…}` call keeps its constructor
            // form. Normalise both directions so ability dispatch on a Bool
            // constant routes through the generated impl.
            concreteType is VariantType && targetType is BoolType &&
                com.mezze.language.ast.PrimitiveTypes.isBoolVariant(concreteType) -> true
            concreteType is BoolType && targetType is VariantType &&
                com.mezze.language.ast.PrimitiveTypes.isBoolVariant(targetType) -> true
            // Hash is authoritative nominal identity: two same-named TypeApplications
            // from DIFFERENT declarations (differing content hash) must NOT match —
            // mirrors the solver's unifyTypeApplications veto (#278/F0). Name match,
            // or the same concrete decl under a rename-safe alias, still matches;
            // identity only ADDS a match after the DISTINCT reject.
            concreteType is TypeApplication && targetType is TypeApplication &&
                identityVerdict(concreteType.identity, targetType.identity) == IdentityVerdict.DISTINCT -> false
            concreteType is TypeApplication && targetType is TypeApplication ->
                concreteType.constructor == targetType.constructor ||
                    sameDeclaredIdentity(concreteType.identity, targetType.identity)
            concreteType is VariantType && targetType is TypeApplication &&
                concreteType.constructors.size == 1 &&
                concreteType.constructors.keys.first() == targetType.constructor -> true
            concreteType is VariantType && targetType is TypeApplication -> {
                concreteType.identity.displayNameOrNull() == targetType.constructor ||
                    VariantAliases.aliasFor(concreteType.constructors.keys) == targetType.constructor ||
                    // The receiver may have had its identity stripped: AliasStripper
                    // sets Anonymous when the declaring name is not in the caller's
                    // scope, which is what lets two same-shaped variants from
                    // different modules unify structurally (#148 rule 6).
                    //
                    // Dispatch still has to find the impl. `Json` from
                    // std::codec::json arrives as a bare seven-constructor variant
                    // while `impl FromJson 'a for Json` targets
                    // TypeApplication(Json), so `j.from_json {}` failed with
                    // `Field not found: from_json` — though the identical call
                    // through an annotated binding worked.
                    //
                    // The constructor SET survives stripping, and for a variant it
                    // is what the declaration is. Comparing the target's declared
                    // arms against the receiver's recovers the link without
                    // restoring a nominal wall that unification relies on being
                    // absent.
                    variantDeclHasConstructors(targetType.constructor, concreteType.constructors.keys)
            }
            // A structural container receiver (SeqType/MapType/…) matches an
            // `impl … for Seq`/`Map`/… whose target is TypeApplication("Seq"/…).
            // Name mapping is the single source in BuiltinTypes.builtinConstructorName.
            targetType is TypeApplication &&
                com.mezze.language.ast.BuiltinTypes.builtinConstructorName(concreteType) == targetType.constructor -> true
            // Structural-to-structural: an impl written `for ['a]` / `for {Map,Set,…} 'a` has a
            // STRUCTURAL target (SeqType/MapType/…), so a same-kind receiver must match element-
            // agnostically (the target's element is a VarType). Without this, `impl Sized for ['a]`
            // never dispatches to a seq receiver and `seq.size {}` degrades to a field read (#279).
            concreteType is SeqType && targetType is SeqType -> true
            concreteType is ListType && targetType is ListType -> true
            concreteType is SetType && targetType is SetType -> true
            concreteType is MapType && targetType is MapType -> true
            concreteType is SortedSetType && targetType is SortedSetType -> true
            concreteType is SortedMapType && targetType is SortedMapType -> true
            concreteType is RecordType && targetType is TypeApplication &&
                concreteType.identity.displayNameOrNull() == targetType.constructor -> true
            // RecordType-to-RecordType. Compare structurally, guarded by an
            // identity DISTINCT reject so two DIFFERENT named records with the
            // same field shape do NOT collide. Fields differ → miss.
            //
            // Fixes the (anonymous impl target, alias-tagged receiver) combo:
            // `impl Summary for { user: Str }` registers an Anonymous target;
            // `let p : Post = { user = "dev" }` where `type Post = { user: Str }`
            // carries a Declared identity. identityVerdict(Declared, Anonymous)
            // is UNDETERMINED, so the receiver reaches the field comparison and
            // matches. Two distinct Declared records with the same fields (rare
            // — the parser also rejects same-shape aliases) still hash-mismatch
            // and are correctly rejected.
            concreteType is RecordType && targetType is RecordType -> {
                identityVerdict(concreteType.identity, targetType.identity) != IdentityVerdict.DISTINCT &&
                    concreteType.fields == targetType.fields
            }
            // Newtype receivers dispatch to `impl … for <NewtypeName>` whose target
            // is built as a TypeApplication (see ContextBuilder ParsedNewtypeDef →
            // WellKnownTypeApp.of). Prefer hash identity (module-distinct, #278);
            // fall back to name when either hash is absent (builtins/pre-hash).
            concreteType is NewtypeType && targetType is TypeApplication -> {
                val cHash = (concreteType.identity as? TypeIdentity.Declared)?.hash
                val tHash = (targetType.identity as? TypeIdentity.Declared)?.hash
                if (cHash != null && tHash != null) cHash == tHash
                else concreteType.name == targetType.constructor
            }
            concreteType is TypeApplication && targetType is NewtypeType -> {
                val cHash = (concreteType.identity as? TypeIdentity.Declared)?.hash
                val tHash = (targetType.identity as? TypeIdentity.Declared)?.hash
                if (cHash != null && tHash != null) cHash == tHash
                else concreteType.constructor == targetType.name
            }
            // VariantType to VariantType: match if constructors overlap or type names match
            concreteType is VariantType && targetType is VariantType -> {
                // Same type name (e.g., both are "Json")
                val concreteName = concreteType.identity.displayNameOrNull()
                if (concreteName != null && concreteName == targetType.identity.displayNameOrNull()) return true
                // Same constructor set
                if (concreteType.constructors.keys == targetType.constructors.keys) return true
                // Concrete has subset of target constructors (e.g., concrete is a single constructor variant)
                if (concreteType.constructors.keys.all { it in targetType.constructors }) return true
                false
            }
            // A bare VarType target is an UNCONSTRAINED impl position — a blanket
            // `impl X for 'a` where the receiver self is a type variable, so it
            // legitimately matches ANY concrete receiver. This is the whole point
            // of a generic wrapper like `impl Somable for 'a` / `impl Okable 'e for
            // 'a`: `x.some {}` / `x.ok {}` must dispatch for every receiver type.
            // Matching here only ADDS a candidate for a blanket impl; disambiguation
            // among multiple matches is handled by the caller (co-operand match +
            // opHash dedup in tryResolveAbilityOp). A concrete target still requires
            // an exact structural match above, so this never widens a non-blanket impl.
            targetType is VarType -> true
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
        slotToNameForCurrentFn.putIfAbsent(slot, name)
        return slot
    }

    /** Force-allocate a new slot, even if name already exists (for match bindings) */
    private fun forceAllocLocal(name: String): Int {
        val slot = nextLocalSlot++
        localsByName[name] = slot  // overwrites existing entry
        slotToNameForCurrentFn.putIfAbsent(slot, name)
        return slot
    }

    private fun typeToSlotKind(type: MezzeType): SlotKind = when (type) {
        is IntType -> SlotKind.LONG
        is FloatType -> SlotKind.DOUBLE
        is BoolType -> SlotKind.BOOLEAN
        // Newtype is compile-time only — runtime slot follows wrapped type. Nat
        // (`newtype Nat = Int`) picks up LONG here just like a raw Int.
        is NewtypeType -> typeToSlotKind(type.wrappedType)
        else -> SlotKind.OBJECT
    }

    companion object {
        @JvmStatic
        fun computeOpHash(implHash: Hash, opName: String): Hash = Hash.computeOpHash(implHash, opName)
    }

    private fun locToSourceInfo(loc: Loc): SourceInfo {
        val fileIndex = files.indexOf(loc.file).let { if (it >= 0) it else { files.add(loc.file); files.size - 1 } }
        return SourceInfo(fileIndex, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
    }

    /** Set by callers via [setDeclIndex] before [compile]. Read by
     *  call-site lookup paths that need to find a referenced decl. */
    private var declIndex: Map<Hash, TypedLetDecl>? = null
    fun setDeclIndex(decls: Map<Hash, TypedLetDecl>) { declIndex = decls }

    /** #394: declared type NAME → resolved underlying MezzeType (newtype
     *  underlying, record body, variant body, alias target). Lets the
     *  PrintType builder resolve a nominal `TypeApplication(name)` receiver
     *  (all a use site carries) to a printable shape. */
    private var printTypeDefs: Map<String, MezzeType> = emptyMap()
    fun setPrintTypeDefs(defs: Map<String, MezzeType>) { printTypeDefs = defs }

    /**
     * True when the variant declared as [name] has exactly [constructors].
     *
     * Used to re-link a receiver whose nominal identity AliasStripper removed —
     * see the VariantType/TypeApplication arm of [typeMatchesTarget]. The
     * constructor set is what a variant declaration IS, so an exact match on it
     * identifies the declaration as reliably as the name would have.
     *
     * Exact, not subset: a variant with the same arms plus one more is a
     * different declaration and must not borrow this one's impls.
     */
    private fun variantDeclHasConstructors(name: String, constructors: Set<String>): Boolean {
        val declared = printTypeDefs[name] as? VariantType ?: return false
        return declared.constructors.keys == constructors
    }

    /**
     * Constructor name -> the declared VariantType that uniquely declares it.
     * Built from [printTypeDefs] (type name -> resolved MezzeType, which for a
     * variant carries its constructors + Declared identity). A constructor
     * declared by MORE THAN ONE type is left OUT (ambiguous — no safe pick).
     *
     * #13: an operator like `Low < High` desugars to `Low.lt { r = High }`, but
     * the variant-literal operand `Low` can reach emit carrying an unresolved
     * orphan TyVar (the solver discharged the `Ord 'a` obligation against
     * `impl Ord for Priority` without writing `'a = Priority` into the final
     * substitution — same "solved-type-doesn't-reach-emit" family as the
     * do-block bugs). With the receiver type a bare var, tryResolveAbilityOp
     * can't find the user impl and the call falls through to the native
     * comparator (which throws for a variant / silently uses structural equals).
     * A NULLARY constructor unambiguously names its declared type, so we can
     * recover that type here for the dispatch lookup ONLY — routed through the
     * normal ability-op resolution, so a variant WITHOUT a user impl still
     * returns null and keeps its structural default.
     */
    private val variantTypeByConstructor: Map<String, MezzeType.VariantType> by lazy {
        val single = HashMap<String, MezzeType.VariantType?>()
        for (t in printTypeDefs.values) {
            if (t is MezzeType.VariantType) {
                for (ctor in t.constructors.keys) {
                    if (single.containsKey(ctor)) single[ctor] = null  // ambiguous
                    else single[ctor] = t
                }
            }
        }
        single.filterValues { it != null }.mapValues { it.value!! }
    }

    /**
     * If [node] is a nullary variant-construct receiver whose type is an
     * unresolved var, recover the declared VariantType from its constructor
     * (unambiguous ctor only). Returns null otherwise — callers fall back to
     * the node's own (possibly still-var) type, unchanged.
     */
    private fun resolveVariantReceiverType(node: TypedExpr, currentType: MezzeType): MezzeType? {
        if (currentType !is VarType) return null
        if (node !is com.mezze.language.typedast.TypedVariantConstruct) return null
        return variantTypeByConstructor[node.tag]
    }

    /**
     * Extract a concrete element type from a container expression.
     * When a seq/list literal's TYPE has an unresolved VarType element (generalized
     * type variable not in the module substitution), the individual ELEMENTS may
     * carry recoverable concrete types — variant constructors map to their declared
     * VariantType, and non-VarType expression types are used directly.
     *
     * Returns the first concrete element type found, or null if all elements have
     * unresolvable types.
     */
    private fun extractConcreteElementTypeFromExpr(expr: TypedExpr): MezzeType? {
        val elements: List<TypedExpr> = when (expr) {
            is com.mezze.language.typedast.TypedSeq -> expr.elements.mapNotNull {
                when (it) {
                    is com.mezze.language.typedast.TypedSeqElement.Regular -> it.expr
                    is com.mezze.language.typedast.TypedSeqElement.Spread -> it.expr
                }
            }
            // TypedCall to list::from_seq, etc. — don't dig into call args
            else -> return null
        }
        for (element in elements) {
            val elemType = substitution.apply(element.type)
            val recovered = resolveVariantReceiverType(element, elemType)
            if (recovered != null) return recovered
            if (elemType !is VarType) return elemType
        }
        return null
    }

    /**
     * Collect concrete leaf types from deeper within an expression tree.
     * When the callee's constraint type args are generalized VarType (not in the
     * module substitution), top-level arg field types (record/variant wrappers)
     * don't match any ability impl. This function traverses into nested records
     * and variant constructors to extract the actual concrete leaf types (Int,
     * Str, user-defined variants, etc.) that the constraint type variables were
     * instantiated to.
     *
     * Only descends into structural shapes (records, variants, sub-expressions
     * of known forms). Does NOT descend into function calls or lambdas — those
     * are opaque at the call site.
     */
    private fun collectDeepConcreteTypes(expr: TypedExpr, out: MutableList<MezzeType>) {
        when (expr) {
            is TypedRecord -> {
                for (entry in expr.entries) {
                    if (entry is TypedRecordEntry.Field) {
                        val t = substitution.apply(entry.value.type)
                        val recovered = resolveVariantReceiverType(entry.value, t) ?: t
                        if (recovered !is VarType) out.add(recovered)
                        collectDeepConcreteTypes(entry.value, out)
                    }
                }
            }
            is com.mezze.language.typedast.TypedVariantConstruct -> {
                expr.payload?.let { payload ->
                    val t = substitution.apply(payload.type)
                    if (t !is VarType) out.add(t)
                    collectDeepConcreteTypes(payload, out)
                }
            }
            // Leaf expressions: their types are already captured by the caller.
            // Don't recurse into calls, lambdas, or other opaque forms.
            else -> {}
        }
    }

    /** Look up param names for a known callee hash. Returns null if unknown.
     *  Covers: self-recursive calls (currentFunctionHash, freshly available),
     *  user decls (declIndex via hashToParamNames), and stdlib (PrecompiledStdlib). */
    private fun paramNamesForHash(hash: Hash): List<String>? {
        if (hash == currentFunctionHash) return currentParamNames
        return hashToParamNames[hash]
    }

    /** #389 — hashes of top-level user `let X = expr` decls whose `expr` is
     *  NOT a TypedLambda. These are thunks that evaluate to some value at
     *  runtime — often a MezzeFunction produced by a partial application
     *  (`let partialMul = multiply {x=2}`). Callers must NOT CallDirect on
     *  such a hash with a fresh arg record: doing so throws away any
     *  accumulated partialArgs held on the returned MezzeFunction. Populated
     *  by populateHashToParamNames. */
    private val nonLambdaUserLetHashes: MutableSet<Hash> = HashSet()

    private fun isNonLambdaUserLet(hash: Hash): Boolean =
        nonLambdaUserLetHashes.contains(hash)

    /** Populate hashToParamNames from user decls (lambdas only) and PrecompiledStdlib.
     *  Each entry pairs a callee hash with its full paramNames list — regular params
     *  in source order followed by dict params (sorted method names) for callees that
     *  need them. This mirrors the layout of FunctionIR.paramNames for those decls. */
    private fun populateHashToParamNames(modules: List<TypedModule>) {
        hashToParamNames.clear()
        nonLambdaUserLetHashes.clear()

        // User decls — only TypedLetDecl whose value is a TypedLambda has a callable
        // shape that scalar-ABI can target. Non-lambda lets are zero-param thunks.
        for (mod in modules) {
            for (decl in mod.decls) {
                if (decl !is TypedLetDecl) continue
                val lambda = decl.value as? TypedLambda
                if (lambda == null) {
                    // #389 — a top-level `let X = expr` where expr is not a
                    // lambda is a thunk. Track it so call sites don't
                    // CallDirect on its hash (which would discard partial-args
                    // when the thunk returns a MezzeFunction).
                    nonLambdaUserLetHashes.add(decl.hash)
                    continue
                }
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
            val hasSpread = fields.any { it.name == com.mezze.language.ast.MagicParams.SPREAD_FIELD }
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

    /** Recursion cap for chained inlining. Depth counts inliner invocations
     *  along the call chain (fold → folder → Int.add → __native_int_add is
     *  depth 3). Beyond this, remaining CallDirect stays as an ordinary
     *  direct call — no correctness cost, just less inlining. */
    private val inlineDepthBudget = 4

    /** Purity is declared once, at the [com.mezze.interpreter.nodes.builtin.dsl.NativeOp]
     *  registration site. The signature carries it — no per-name string
     *  switch in the compiler. */
    private fun isPureNative(name: String): Boolean =
        NativeSignatures.signatureOf(name)?.pure == true

    /** Try to inline a direct call into the caller's IR. Returns the inlined
     *  expression, or null if any constraint is violated.
     *
     *  Constraints (conservative first cut):
     *    - Callee FunctionIR is registered (compiled before this site).
     *    - Callee has 0 captures (no closures to materialize at call site).
     *    - Callee has 0 locals (body uses only args + literals + arithmetic + If).
     *    - Callee body is "pure" — no effects, no mutation.
     *    - Callee body fits in inlineNodeBudget.
     *    - Caller args are side-effect-free (Read* / literals) so we can
     *      duplicate them via param substitution without binding to fresh slots. */
    private fun tryInlineDirectCall(targetHash: Hash, args: List<NodeIR>): NodeIR? {
        return tryInlineDirectCall(targetHash, args, depth = 0)
    }

    private fun tryInlineDirectCall(targetHash: Hash, args: List<NodeIR>, depth: Int): NodeIR? {
        if (System.getenv("MEZZE_DISABLE_INLINE") == "true") return null
        if (depth > inlineDepthBudget) return null

        val callee = hashToFunctionIR[targetHash] ?: return null
        if (callee.captureSlots.isNotEmpty()) return null
        if (callee.localSlots.isNotEmpty()) return null
        if (callee.paramSlots.size != args.size) return null

        for (a in args) if (!isSideEffectFree(a)) return null

        if (!isInlinable(callee.body, callee.paramSlots.size)) return null
        if (countNodes(callee.body, max = inlineNodeBudget + 1) > inlineNodeBudget) return null

        if (System.getenv("MEZZE_DEBUG_INLINE") == "true") {
            System.err.println("[inline] HIT depth=$depth: ${callee.name} (hash=${targetHash.hex.take(8)}) args=${args.size}")
        }
        val substituted = substituteArgs(callee.body, args, depth)
        return maybeCollapseInlineOp(substituted)
    }

    /** A NodeIR is "side-effect-free" if duplicating it in multiple positions
     *  doesn't change semantics. Reads + literals only. */
    private fun isSideEffectFree(node: NodeIR): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR -> true
        else -> false
    }

    /** Body shapes that can be safely substituted: no allocations, no
     *  mutation, no effects. Allows pure primitive calls (CallNativeIR
     *  to an allowlisted name, CallDirectIR to a small pure callee) so
     *  ability-op wrappers like `Int.add = {it,r} -> __native_int_add {...}`
     *  and short helper functions can chain-inline into a caller.
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
        is MakeRecordIR -> node.fields.all { it is FieldIR && isInlinable(it.value, paramCount) }
        is CallNativeIR -> (node.isPure || isPureNative(node.name)) && isInlinable(node.arg, paramCount) &&
            node.args.all { isInlinable(it, paramCount) }
        is CallDirectIR -> !node.isTailCall && node.args.all { isInlinable(it, paramCount) }
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
            is MakeRecordIR -> {
                var used = 1
                for (f in node.fields) {
                    if (f !is FieldIR) return Int.MAX_VALUE
                    used += countNodes(f.value, max - used)
                    if (used >= max) return used + 1
                }
                used
            }
            is CallNativeIR -> {
                var used = 1 + countNodes(node.arg, max - 1)
                for (a in node.args) {
                    used += countNodes(a, max - used)
                    if (used >= max) return used + 1
                }
                used
            }
            is CallDirectIR -> {
                var used = 1
                for (a in node.args) {
                    used += countNodes(a, max - used)
                    if (used >= max) return used + 1
                }
                used
            }
            else -> 1
        }
    }

    /** Substitute callee's param reads with caller's arg expressions.
     *  Params are at ReadLocalIR(slot=0..paramCount-1) since we required
     *  the callee to have 0 captures and 0 locals.
     *
     *  Recursively attempts inlining on any CallDirectIR after substitution —
     *  a nested `add(acc, x)` inside the folder body should collapse to
     *  __native_int_add, which the InlineOps peephole then folds to
     *  InlineBinaryOpIR. */
    private fun substituteArgs(node: NodeIR, args: List<NodeIR>, depth: Int = 0): NodeIR = when (node) {
        is ReadArgIR -> args.getOrNull(node.index) ?: node
        is ReadLocalIR -> if (node.slot < args.size) args[node.slot] else node
        is InlineBinaryOpIR -> InlineBinaryOpIR(
            node.op, substituteArgs(node.left, args, depth), substituteArgs(node.right, args, depth), node.src
        )
        is IfIR -> IfIR(
            substituteArgs(node.cond, args, depth),
            substituteArgs(node.thenBr, args, depth),
            substituteArgs(node.elseBr, args, depth),
            node.src
        )
        is MakeRecordIR -> MakeRecordIR(
            node.fields.map { f ->
                when (f) {
                    is FieldIR -> FieldIR(f.name, substituteArgs(f.value, args, depth))
                    else -> f
                }
            },
            node.src, node.typeTag
        )
        is CallNativeIR -> {
            val newArg = substituteArgs(node.arg, args, depth)
            val newArgs = node.args.map { substituteArgs(it, args, depth) }
            maybeCollapseInlineOp(
                node.copy(arg = newArg, args = newArgs)
            )
        }
        is CallDirectIR -> {
            val newArgs = node.args.map { substituteArgs(it, args, depth) }
            val recursed = tryInlineDirectCall(node.targetHash, newArgs, depth + 1)
            recursed ?: node.copy(args = newArgs)
        }
        else -> node
    }

    /** After substitution, if we produce a CallNativeIR whose signature
     *  declares an equivalent [InlineOp], collapse it to an
     *  [InlineBinaryOpIR] so the BDSL emit + PE fast path fires. The
     *  mapping lives on the NativeSignature (declared once at each
     *  NativeOp site) — no per-name string switch here.
     */
    private fun maybeCollapseInlineOp(node: NodeIR): NodeIR {
        if (node !is CallNativeIR) return node
        // Prefer the IR-embedded inlineOp (survives serialization); fall
        // back to the live registry for backwards compat.
        val op = node.inlineOp
            ?: NativeSignatures.signatureOf(node.name)?.inlineOp
            ?: return node
        // Fetch positional args uniformly. Scalar-ABI already has them in
        // `args`; record-ABI needs unpacking via the signature's param
        // order so we don't hardcode "l"/"r" here.
        val sig = NativeSignatures.signatureOf(node.name)
        val positional: List<NodeIR>? = when {
            node.isScalarABI && node.args.size == 2 -> node.args
            sig != null && node.arg is MakeRecordIR -> {
                val record = node.arg as MakeRecordIR
                val fields = record.fields.filterIsInstance<FieldIR>()
                if (fields.size != sig.params.size) null
                else sig.params.map { p -> fields.find { it.name == p.name }?.value ?: return node }
            }
            else -> null
        }
        if (positional == null || positional.size != 2) return node
        return InlineBinaryOpIR(op, positional[0], positional[1], node.src)
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
                && argIR.fields.none { it.name == com.mezze.language.ast.MagicParams.SPREAD_FIELD }) {
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
