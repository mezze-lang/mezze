package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.EffectBindingKind
import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.holdsTypeVar
import com.mezze.language.ast.holdsUnknownPlaceholder
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
import com.mezze.language.typechecker.comptime.ComptimeRules
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.ast.PrimitiveTypes
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
    internal val abilityImpls: List<TypedAbilityImpl>,
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
    /**
     * Every visible ability's operation names, keyed by the ability's CONTENT HASH.
     *
     * Sourced from the ability DECLARATIONS, which is where an ability's operations
     * are actually written down. The impl index cannot answer this: it only knows
     * the abilities someone has implemented, so an ability whose impls are not in
     * this compile step — the common case at stdlib-precompile time — looks like it
     * has no operations at all.
     *
     * Empty ⇒ no ability is in scope, so no call can be an ability call.
     */
    internal val abilityOpNames: Map<Hash, Set<String>> = emptyMap(),
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
    internal val preloweredDictMethods: Map<Hash, Set<String>> = emptyMap(),
    /**
     * Final module substitution — used to refresh stale VarType on TypedVar
     * nodes whose type field was captured before the outer scope solved the
     * relevant TyVar (e.g. `<-` desugar binds the payload before the
     * synthetic ExceptionToResult handler's existential unifies with the
     * receiver at the outer scope). See #346 / project_346_stale_typevar_root_cause.
     * Empty default keeps existing behavior for callers that don't have one.
     */
    internal val substitution: com.mezze.language.ast.solving.Substitution =
        com.mezze.language.ast.solving.Substitution.empty
) {

    /** Ability operation index: methodName → list of (implHash, targetTypes, opHash) */
    internal data class AbilityOp(
        val implHash: Hash,
        val targetTypes: List<MezzeType>,
        val opHash: Hash,
        /** BUG 9: which ABILITY this op belongs to. The index is keyed on the
         *  operation NAME, so two abilities declaring the same name land in one
         *  bucket and a receiver implementing both matched whichever came first —
         *  `Weigh.rank {…} + Price.rank {…}` ran Weigh twice. The typechecker
         *  already decided the ability per call site
         *  ([ConstraintResult.MethodCallInfo.abilityName]); carrying it here is
         *  what lets the compiler USE that decision instead of re-deriving one. */
        val abilityHash: Hash?
    )
    /**
     * The operation names of the `Hash` ability specifically. `Hash` is DERIVED for
     * every type rather than implemented, exactly like `Inspect`: there is no impl
     * to dispatch to, so a call has to lower to the structural hash directly. Read
     * off [abilityOpNames] by CONTENT HASH — never by the literal "hash", so a user
     * ability that happens to declare a `hash` op is not mistaken for this one.
     */
    private val hashOpNames: Set<String> =
        abilityOpNames.entries
            .firstOrNull { com.mezze.language.typechecker.WellKnownHashConstants.isHash(it.key) }
            ?.value ?: emptySet()

    internal val declaredDictMethodsForHash = mutableMapOf<Hash, Set<String>>()
    internal val abilityOpIndex = mutableMapOf<String, MutableList<AbilityOp>>()

    /** Extracted lambda functions */
    private val extractedFunctions = mutableListOf<FunctionIR>()

    /** Performer records from TypedPerformerDecls */
    private val performerRecords = mutableListOf<PerformerRecord>()

    /** Current function's param count (for Local→Param remapping) */
    private var currentParamCount = 0

    /** Next local slot index (starts after params) */
    private var nextLocalSlot = 0

    /** Local variable name → slot index (current scope) */
    internal val localsByName = mutableMapOf<String, Int>()

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
    internal val scopeStack = ArrayDeque<MutableMap<String, Int>>()

    /** Current function's param names */
    internal var currentParamNames = listOf<String>()

    /** When true, the prescan and lambda-capture scan flag inlineable ops (lt, add, …)
     *  for dict-dispatch on VarType receivers. True during stdlib pre-compilation;
     *  false during user compilation (where phase 2 propagation carries dict
     *  requirements from preloweredDictMethods instead). */
    internal var isStdlibMode = false

    /** Capture name → capture index (for the current lambda being compiled) */
    internal var currentCaptureNames = mapOf<String, Int>()

    /** Number of captures in the current function — offsets param slots in the frame */
    internal var currentCaptureCount = 0

    /** Hash of the function currently being compiled — used to detect self-recursive
     *  CallDirectIR sites where we statically know the callee's paramNames and can
     *  emit the scalar calling convention. */
    private var currentFunctionHash: Hash? = null

    /**
     * Structurally-derived concrete types for locals in the CURRENT function scope.
     *
     * Backs the fix for #polyglot-newtype-dispatch: per-language `Foreign` newtypes
     * (`JvmForeign`, `PyForeign`, ...) all newtype over the same `MezzeForeign` Java
     * class, and expose same-named inherent methods (`.invoke_member`, `.get_member`).
     * When the solver leaves the receiver's `TypedVar.type` as a bare VarType
     * (destructuring off a let whose RHS type never fully unified), the emit path
     * used to fall through to runtime dict-dispatch on the shared `MezzeForeign`
     * class, which can't disambiguate between the four candidates → DictFallback
     * crash at runtime. Static dispatch is the ONLY sound path for these methods.
     *
     * This map is populated whenever the compiler can structurally derive a concrete
     * receiver type from AST context — a let-binding's RHS shape, a match pattern's
     * scrutinee, etc. (see `deriveConcreteType`). At field-access dispatch, when
     * `receiverType is VarType` and `fn.record` is a local, we consult this map;
     * a concrete override routes the call to static dispatch (line ~1747), never
     * to the dict-passing branch. Empty entries mean "no override" — behavior for
     * receivers the solver already resolved is unchanged.
     *
     * INLINE receivers (a `TypedCall` / `TypedPerformOp` / `TypedMatch` directly in
     * the `.method{}` chain, no intermediate `let`) don't populate this map — nothing
     * to key on. The dispatch site instead invokes `deriveConcreteType(fn.record)`
     * directly for those, sharing the same structural walk and the same
     * `tryResolveAbilityOp` uniqueness gate that keeps recovery from over-firing.
     * Same net effect: an inline `Python.eval{...}.as_str{}` chain routes to
     * `impl Result PyForeign InteropError` just as the let-bound form does.
     *
     * Keyed by SLOT INDEX (not name) so nested scopes with shadowed names don't
     * collide. Saved/restored by compileBlock/compileMatch just like localsByName.
     */
    internal val slotConcreteTypes = mutableMapOf<Int, MezzeType>()

    /** Ability-op PARAM name → concrete receiver type, from the impl's
     *  `associatedTypeBindings`. A param left at the associated var (`val: 'in` where
     *  `impl … having 'in = JvmForeign`) would otherwise stay a VarType at dispatch and
     *  a foreign method call falls to DictFallback. Populated/restored per body. */
    internal val paramConcreteReceiverTypes = mutableMapOf<String, MezzeType>()

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
    fun getDictMethodsForHash(): Map<Hash, Set<String>> = dictMethodsForHash.toMap()

    fun getDeclaredDictMethodsForHash(): Map<Hash, Set<String>> = declaredDictMethodsForHash.toMap()

    internal val dictMethodsForHash = mutableMapOf<Hash, Set<String>>()

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

    /**
     * An ability op's own DECLARED arrow, by op hash — kept so a call site can
     * match it against the call's instantiated type and bind the variables the
     * op's `with` obligations are about. See [obligationDispatchTypes].
     */
    internal val declaredOpTypeByHash = mutableMapOf<Hash, MezzeType>()

    /** Codec entry points that dispatch their inner decode on the target type.
     *  DERIVED (never literals): ops/lets whose signatures carry a constraint
     *  on a decode-flavored ability HASH, plus wrappers that call them by hash
     *  — stdlib from_json/from_json_with, http's inherent from_json, or a user
     *  codec's entry op. Lazy: needs declIndex, which callers set just before
     *  [compile]; first read happens during compilation. */
    private val decodeEntryOpNames: Set<String> by lazy {
        com.mezze.language.typechecker.emit.DecodeTargetResolver
            .entryPointOps(abilityImpls, declIndex?.values?.toList() ?: emptyList()).first
    }

    init {
        // Build ability operation index
        for (impl in abilityImpls) {
            for ((opName, _) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(
                    AbilityOp(impl.hash, impl.targetTypes, opHash, impl.abilityHash)
                )
                // Codec decoder index — keyed by ABILITY CONTENT HASH
                // (WellKnownHashConstants decode-flavored set), shared with
                // DecodeTargetResolver so both ends select the same population.
                // Previously any impl with an op named `decode` landed here, so
                // a USER ability's decode op could be mistaken for a codec
                // decoder (name-dispatch hole).
                if (com.mezze.language.typechecker.emit.DecodeTargetResolver.isCodecDecodeImpl(impl)) {
                    decodeImplByOpHash[opHash] = impl
                }
            }
        }
    }


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
                    functions.add(compileFunctionBody(opHash, opName, opBody.params, opBody.body, opBody.loc,
                        assocBindings = impl.associatedTypeBindings))
                } else {
                    functions.add(compileFunctionBody(opHash, opName, emptyList(), opBody, opBody.loc,
                        assocBindings = impl.associatedTypeBindings))
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
            // Salt nested-lambda hashes with the spec hash: the SAME source lambda
            // (e.g. the seq Ord op's `{a,b} -> if a<b …` comparator) compiles to a
            // DIFFERENT FunctionIR per specialization (captures/dispatch differ once
            // types are pinned), but TypedASTHasher.hashExpr is type-blind, so all
            // versions collide on one hash and the last one registered wins — a
            // generic invocation then runs a spec's zero-capture body with a
            // closure-values-prefixed arg array, shifting every arg by one
            // (`[[P]] < [[P]]` saw xs = the Ord-dict MezzeFunction).
            specLambdaSalt = spec.specializedHash
            specTypeArgs = spec.typeArgs
            specGenericHash = spec.genericHash
            specAbilityImpls = spec.abilityImpls
            val fn = try {
                if (body is TypedLambda) {
                    compileFunctionBody(spec.specializedHash, "", body.params, body.body, body.loc)
                } else {
                    compileFunctionBody(spec.specializedHash, "", emptyList(), body, body.loc)
                }
            } finally {
                specLambdaSalt = null
                specTypeArgs = null
                specGenericHash = null
                specAbilityImpls = emptyList()
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

        DebugLogger.debug(DebugLogger.Category.Compiler) {
            val t = census.total()
            val mono = census.inlineOp + census.methodConcrete + census.methodSingleVar +
                census.byHashMono + census.byHashPlain + census.native + census.globalThunk
            val dict = census.methodDictCallee + census.byHashDictInject
            "[dispatch-census] total=$t mono-owned=$mono dict-owned=$dict indirect=${census.indirect}"
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
        val keptByHash = HashMap<Hash, FunctionIR>(functions.size)
        for (fn in functions) {
            if (seenHashes.add(fn.hash)) {
                deduped.add(fn)
                keptByHash[fn.hash] = fn
            } else if (recordDedupeCollisions) {
                // The comment above says duplicate rows are "semantically the same
                // function". Measured, that is not always true: the lambda hash is
                // type- and environment-blind (see `saltedLambdaHash`), so two
                // lambdas with DIFFERENT compiled bodies can share one, and
                // first-occurrence-wins then discards a body some call site needed.
                // Off by default — comparing whole IR bodies is not free — and
                // switched on by `DedupeCollisionGateTest`, which holds the count.
                // The whole function's shape, not just its body: two functions with
                // the same body but different CAPTURE lists are different functions,
                // and the dedupe would keep one and discard the other. Comparing
                // bodies alone left that unguarded.
                val kept = keptByHash[fn.hash]
                if (kept != null && kept.shapeKey() != fn.shapeKey()) {
                    dedupeCollisions.add(fn.hash.hex)
                }
            }
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
            // No `doNotInline` any more: that existed to stop the inliner deleting
            // `comptime`'s `{ eval } -> eval {}` body, which is a textbook trivial
            // wrapper. As an effect operation there is no wrapper to delete.
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

        // Rule 1 — `comptime` applied, never referenced. Decided here rather than
        // during expression lowering because only the lowered form distinguishes
        // the two: a value use leaves a bare `ReadGlobal*ByHash` of the comptime
        // declaration, whereas an application consumes that read as a call's
        // callee. Position is the whole difference, and position is what the IR
        // makes explicit. See ComptimeIr.
        for (fn in final) {
            val valueUse = ComptimeRules.ir.findSites(fn.body).firstOrNull { it.isValueUse }
            if (valueUse != null) {
                val at = valueUse.valueUse?.src
                ComptimeRules.rejectNonCallReference(
                    at?.let {
                        Loc(fn.sourceFile, it.startLine, it.startCol, it.endLine, it.endCol)
                    }
                )
            }
        }
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
        captureInfo: List<Pair<String, SlotKind>> = emptyList(),
        // Enclosing impl's associated-type bindings (see paramConcreteReceiverTypes).
        assocBindings: Map<String, MezzeType> = emptyMap()
    ): FunctionIR {
        // Save and reset state
        val prevParamConcrete = paramConcreteReceiverTypes.toMap()
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

        // Params typed as the impl's associated vars → concrete receiver type, by name.
        paramConcreteReceiverTypes.clear()
        for (p in params) {
            val pt = substitution.apply(p.type) as? VarType ?: continue
            val vn = pt.variable.name.removePrefix("'")
            (assocBindings[vn] ?: assocBindings["'$vn"])?.takeIf { it !is VarType }
                ?.let { paramConcreteReceiverTypes[p.name] = it }
        }

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
        paramConcreteReceiverTypes.apply { clear(); putAll(prevParamConcrete) }
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
            sourceSection = locToSourceInfo(loc),
            returnOrd = ordKindOf(body.type),
        )
        return fnIR
    }

    /**
     * How a value of [type] can be ordered — decided HERE, where the solved type
     * is in hand, and carried on the FunctionIR so no later stage has to guess.
     *
     * Deliberately conservative: anything not provably one of the types the
     * Direct comparison nodes specialize answers INSTANCE, and anything the
     * compiler cannot pin down answers null. A wrong DIRECT silently replaces a
     * user's `Ord` with a primitive `<`; a wrong INSTANCE only declines a
     * fusion.
     *
     * A NEWTYPE is INSTANCE even when it wraps an Int. That is the whole point:
     * `newtype R = Int` with a reversed `Ord` compares as a long at runtime, so
     * the primitive path agrees with the WRAPPED type's order and silently
     * contradicts the one the author wrote. See known-bugs.md BUG 10.
     */
    private fun ordKindOf(type: MezzeType): OrdKind? = when (type) {
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType -> OrdKind.DIRECT
        is MezzeType.VarType -> null // unsolved here; caller treats null as INSTANCE
        else -> OrdKind.INSTANCE
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

    internal fun compileExpr(expr: TypedExpr): NodeIR {
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
            is TypedPerformOp -> {
                // `Comptime.comptime` is the one op whose result shape has to
                // travel with the node: `effectHash` already identifies the site,
                // but the shape is computed from the SOLVED, pre-erasure type and
                // cannot be recovered downstream.
                val isCt = com.mezze.language.typechecker.WellKnownHashConstants
                    .isComptime(expr.effectHash, expr.operationName)
                val shape = if (isCt) checkComptimeSite(expr.arg, expr.type, expr.loc) else null
                PerformOpIR(
                    expr.effectHash, expr.operationName, compileExpr(expr.arg), src,
                    isComptime = isCt,
                    comptimeShape = shape,
                )
            }

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

    /**
     * The two comptime rules that need a call site: the `eval` thunk must be
     * closed, and the result type must be ground. See [ComptimeRules].
     *
     * Both read straight off the typed AST — `TypedLambda` already records what
     * it closes over, so "closed" is `captures.isEmpty()` rather than a scope
     * analysis, and the result type is the one the solver assigned to this call.
     */
    private fun checkComptimeSite(
        arg: TypedExpr,
        exprType: MezzeType,
        loc: Loc,
    ): com.mezze.language.truffleir.PrintType? {
        val evalThunk = (arg as? TypedRecord)
            ?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()
            ?.firstOrNull { it.name == "eval" }
            ?.value

        // Without `eval` there is no site to replace: row polymorphism reads the
        // missing field as a PARTIAL APPLICATION and hands back something still
        // awaiting it. Left alone it reaches the runtime and dies inside a dispatch
        // node instead of being explained.
        if (evalThunk == null) {
            ComptimeRules.rejectNonCallReference(loc)
        }

        if (evalThunk is TypedLambda) {
            ComptimeRules.checkThunkIsClosed(evalThunk.captures.map { it.name }, evalThunk.loc)
        }
        // The solved result shape, from whichever source actually has it. The flat
        // substitution can leave a call's own type open where the enclosing
        // context is what pins it — the same gap #160 documents for
        // inherent-method receivers — so the thunk's body type is consulted too.
        // Several places may know the result type, and they are not equally well
        // solved: the flat substitution can leave the call's own type open where
        // the enclosing context is what pins it (the gap #160 documents for
        // inherent-method receivers), while the thunk's body type is already
        // concrete. Taking the first non-VarType therefore picked a type that
        // LOOKED usable and built a shape with a `Dynamic` hole in it — and a
        // partial shape is no shape, so the site went unstamped and never folded.
        //
        // Choose by the answer instead: the first candidate whose shape is ground
        // all the way down.
        val candidates = listOfNotNull(
            substitution.apply(exprType),
            (evalThunk as? TypedLambda)?.body?.type?.let { substitution.apply(it) },
            (evalThunk?.type as? FunctionType)?.returnType?.let { substitution.apply(it) },
        ).filter { it !is VarType }

        for (candidate in candidates) {
            val built = PrintTypeBuilder(printTypeDefs).build(candidate)
            if (isGround(built)) return built
        }
        val resultType: MezzeType? = null

        // No shape means this call CANNOT be folded — reification builds a value
        // against its static shape and there is none. That is a limitation, not a
        // mistake by the author: `comptime { eval = {} -> Some { val = 7 } }` is
        // perfectly good code whose variant type stays open until a later `match`
        // closes it. So the call is simply left alone and evaluates at run time,
        // via the declaration's own `eval {}` body, which computes the same value.
        //
        // Rejecting here was tried and reverted: it turned working programs into
        // compile errors over a compiler limitation.
        // Built while the type is still unerased — after emit nothing can tell
        // `Nat` from `Int`, or say which newtype a value came from. It rides out
        // on the call node itself, which is why a runtime handed nothing but
        // serialized `FunctionIR` can still find and fold the site.
        //
        // A shape has to be ground ALL THE WAY DOWN, not just at the top. The
        // check above only rejects a bare `VarType`, and `Some { val = 7 }`
        // whose payload never solved builds a perfectly well-formed
        // `Variant(Some -> Dynamic)` — shaped enough to look foldable, and then
        // unreifiable once the value is in hand. Leaving the site alone is the
        // documented behaviour for "no shape"; a partial shape is no shape.
        val shape = resultType?.let { PrintTypeBuilder(printTypeDefs).build(it) }
        return if (shape != null && isGround(shape)) shape else null
    }

    /** Whether [shape] carries no [PrintType.Dynamic] anywhere inside it. */
    private fun isGround(shape: com.mezze.language.truffleir.PrintType): Boolean {
        return when (shape) {
            is com.mezze.language.truffleir.PrintType.Dynamic -> false
            is com.mezze.language.truffleir.PrintType.Function -> false
            is com.mezze.language.truffleir.PrintType.Newtype -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.Record ->
                shape.fields.all { isGround(it.type) }
            // A variant is usable even when some ARM is not ground: the value picks
            // exactly one arm, and the others are irrelevant to writing it down.
            // `Err { error = "nope" }` has type `Result 'ok Str` — `'ok` is never
            // determined and never needs to be, yet demanding every arm be ground
            // rejected the whole shape and the block silently never folded.
            //
            // Safe to relax because a shape that turns out to be insufficient for
            // the actual value is now a BUILD failure from the reifier, not a
            // silent fallback — so the conservative check has nothing left to
            // protect against.
            is com.mezze.language.truffleir.PrintType.Variant -> true
            is com.mezze.language.truffleir.PrintType.MzList -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.MzSeq -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.MzSet -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.MzSortedSet -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.MzDeque -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.MzNonEmptyList -> isGround(shape.inner)
            is com.mezze.language.truffleir.PrintType.MzMap ->
                isGround(shape.key) && isGround(shape.value)
            is com.mezze.language.truffleir.PrintType.MzSortedMap ->
                isGround(shape.key) && isGround(shape.value)
            else -> true
        }
    }

    private fun compileVar(expr: TypedVar, src: SourceInfo): NodeIR {
        return when (val binding = expr.binding) {
            is BindingRef.AbilityOp -> {
                // Referenced without being called — hand back the function the
                // dictionary slot holds. Named directly because an operation with no
                // receiver has no value whose type could point at the slot.
                ReadLocalIR(abilityOpDictSlot(binding), SlotKind.OBJECT, src)
            }
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
                if (rawReceiverType is VarType && methodName in decodeEntryOpNames)
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
                (fromJsonInputType
                    ?: resolveVariantReceiverType(fn.record, rawReceiverType)
                    ?: rawReceiverType)
                    .let { r ->
                        // Polyglot-newtype-dispatch recovery (see slotConcreteTypes
                        // field comment). Fires when the receiver stayed a bare
                        // VarType through raw + variant-recovery + spec-bound stages
                        // AND we can recover a concrete type structurally.
                        //
                        // Two recovery sources, tried in order:
                        //  (a) Local slot override — populated by compileBlock /
                        //      compileMatch via deriveConcreteType at bind time.
                        //      Covers `let x = <expr>; x.method{}` after a
                        //      poly-let / match-arm binding where the solver left
                        //      x's stated type as VarType.
                        //  (b) Inline receiver derivation — invoke
                        //      deriveConcreteType directly on `fn.record`. Covers
                        //      `<expr>.method{}` where `<expr>` is a call whose
                        //      return type is structurally concrete (a
                        //      TypedPerformOp on an effect op with a declared
                        //      return; a TypedCall to a ByHash / field-access
                        //      callee with a concrete FunctionType return; a
                        //      TypedMatch whose Result-preserving shape recovers
                        //      the scrutinee's Result type). This is the case
                        //      that surfaced as an inline chain on a polyglot
                        //      `Result <Lang>Foreign InteropError` receiver —
                        //      `Python.eval{...}.as_str{}` — where the outer
                        //      dispatch never saw a TypedVar-Local to consult.
                        //
                        // Both paths gate on `tryResolveAbilityOp` finding a
                        // unique op for the recovered type at THIS method name,
                        // so the recovery never over-fires on codec paths
                        // (from_json / map / get_or) where a pattern-binding
                        // walker populated slotConcreteTypes with a legitimate
                        // binding type that isn't this call site's receiver.
                        if (r is VarType) {
                            val slotOverride: MezzeType? =
                                if (fn.record is TypedVar) {
                                    val tv = fn.record as TypedVar
                                    val bySlot = if (tv.binding is BindingRef.Local)
                                        localsByName[tv.name]?.let { slotConcreteTypes[it] } else null
                                    bySlot ?: paramConcreteReceiverTypes[tv.name]
                                } else null
                            val recovered = slotOverride ?: deriveConcreteType(fn.record)
                            // Accept if a dispatch target exists: ability op OR inherent method.
                            if (recovered != null && recovered !is VarType &&
                                (tryResolveAbilityOp(methodName, recovered, expr.arg) != null ||
                                 findAbilityOpBody(methodName, recovered) != null)) recovered
                            else r
                        } else r
                    }

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
            // A return-driven codec ability (`Decode 'config 'a for 'in`): its impl is
            // chosen by the TARGET type, which the receiver cannot tell (every
            // generated decoder has receiver `Json`). When the enclosing function
            // holds this method's dict, that dict IS the choice — go through it even
            // though the receiver is typed.
            val viaEnclosingDecodeDict = receiverType !is VarType &&
                enclosingDictSlot(methodName, null) != null &&
                abilityOpIndex[methodName]?.any { decodeImplByOpHash.containsKey(it.opHash) } == true
            // BUG 9 / C.2: pass the ability the node carries. Inside a mono spec the
            // receiver becomes concrete, so this concrete path runs — and without the
            // ability it picks whichever impl of that operation name was registered
            // first, which is the whole bug.
            val opHashConcrete = if (viaEnclosingDecodeDict) null
                else tryResolveAbilityOp(
                    methodName, receiverType, expr.arg,
                    abilityHash = (fn as? TypedFieldAccess)?.abilityHash,
                )
            val opHash = opHashConcrete
                ?: if (receiverType is VarType) {
                    // Same rule for the single-candidate shortcut: "only one op of
                    // this NAME" is not "only one op of this ability".
                    val nodeAbility = (fn as? TypedFieldAccess)?.abilityHash
                    val candidates = abilityOpIndex[methodName]
                        ?.let { all -> if (nodeAbility != null) all.filter { it.abilityHash == nodeAbility } else all }
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

            // The DERIVED `Hash`, lowered the same way and for the same reason:
            // Hash is derived for every type rather than implemented, so no
            // dictionary slot is ever populated for it and there is nothing to
            // dispatch to. The derived hash IS the structural one — exactly what
            // every hash container already computes for its keys — so this changes
            // no behaviour and costs nothing; it only makes the operation
            // NAMEABLE, which is what lets a container be handed the element type's
            // own hash instead of assuming one.
            //
            // Gated on `opHash == null`, so a user `impl Hash for T` still wins:
            // that is the whole point of the ability existing. `hashOpNames` comes
            // from the ability's CONTENT HASH at the emitter, so an unrelated
            // ability declaring its own `hash` op is untouched.
            if (opHash == null && methodName in hashOpNames) {
                val receiverIR = compileReceiverExpr(fn.record)
                return CallNativeIR(
                    name = "__native_structural_hash",
                    arg = MakeRecordIR(listOf(FieldIR(Keywords.IT, receiverIR)), src),
                    src = src,
                    isPure = true,
                )
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
                    // Collect concrete types for resolution. ELEMENT-FIRST: prepend a
                    // container's element type so the dict resolves against the ELEMENT
                    // impl (`Ord P`) rather than re-matching the container impl
                    // (`impl Ord for ['a]`). See project_impl_op_with_constraint_dropped.
                    val concreteTypesForDict = mutableListOf<MezzeType>()
                    // Expr-based element recovery FIRST: a seq literal of nullary
                    // variant constructors (`[Low] < [High]`) carries a LAUNDERED
                    // VarType element on its TYPE (#13 orphan-var), so the type-based
                    // path below can't see `Sev`; the expression tree still can.
                    extractConcreteElementTypeFromExpr(fn.record)?.let { concreteTypesForDict.add(it) }
                    containerElementType(receiverType)?.let { if (it !is VarType) concreteTypesForDict.add(it) }
                    concreteTypesForDict.add(receiverType)
                    if (expr.arg is TypedRecord) {
                        for (entry in (expr.arg as TypedRecord).entries) {
                            if (entry is TypedRecordEntry.Field) {
                                extractConcreteElementTypeFromExpr(entry.value)?.let { concreteTypesForDict.add(it) }
                                containerElementType(entry.value.type)?.let { if (it !is VarType) concreteTypesForDict.add(it) }
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
                    // A.3: the called op's own `with` obligations, resolved against
                    // THIS call's return type. `from_json`'s target is now pinned by
                    // a real `Decode` constraint at the call site, so the concrete
                    // shape is on the call node and the dict resolves from it — the
                    // usage-recovery above is what that replaces.
                    val obligationTypes = obligationDispatchTypes(opHash, expr.type)
                    // STRUCTURAL element binding — how Haskell/Rust resolve this.
                    // `instance Eq a => Eq [a]` peels exactly ONE constructor per step:
                    // the instance HEAD is matched against the type and `a` is bound,
                    // then the constraint is resolved against that binding. Here: unify
                    // the impl's declared target PATTERN against the receiver, bind the
                    // impl's own type var, and dispatch the dict on THAT binding.
                    // The flattened `concreteTypesForDict` pile mixes levels (receiver +
                    // args + their deep element types) and first-match can land a level
                    // too deep — `[[P]]` bound P's eq instead of eq-for-`[P]`, so the
                    // comparator fed a P record to the seq op. Structure, not a bag.
                    // GUARDED to single-type-param impls with a fully concrete binding;
                    // every other shape falls through to the old path verbatim, so no
                    // currently-resolving call site changes behaviour.
                    val opVarBindings = bindImplVarsForOp(opHash, listOf(receiverType))
                    for (dictMethodName in opDictMethods.sorted()) {
                        val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(dictMethodName)
                        val dictMethod = com.mezze.language.ast.MagicParams.methodOfKey(dictMethodName)
                        val perVarBound = boundTypeForKey(dictMethodName, opVarBindings)
                        // Per-site decoder wiring, keyed on HASH IDENTITY rather
                        // than the dict slot being literally named "decode": the
                        // pinned hash fills this slot only if it IS the pinned
                        // codec impl's op for this slot's method — i.e. the slot
                        // and the DecodeTargetResolver pin refer to the same op.
                        // (decodeImplByOpHash holds only structurally codec-shaped
                        // impls, so a user ability's decode op never pins.)
                        val perSiteMatchesSlot = perSiteDecode != null &&
                            decodeImplByOpHash[perSiteDecode]
                                ?.let { computeOpHash(it.hash, dictMethod) == perSiteDecode } == true
                        // A.3: an obligation the op DECLARED, bound to a concrete type
                        // by this call's own return, and whose ability declares this
                        // very slot's method. That is the op's written requirement
                        // resolved against the call's actual types — strictly more
                        // specific than the receiver/argument pile below, which cannot
                        // see the target at all. A slot no obligation covers resolves
                        // exactly as before.
                        val fromObligation = obligationTypes.firstNotNullOfOrNull { (ability, args) ->
                            resolveOpFromObligation(dictMethod, ability, args)
                        }
                        val resolvedHash = fromObligation
                            ?: if (perSiteMatchesSlot) {
                                perSiteDecode
                            } else {
                                (perVarBound?.let { resolveOpHashFromConcreteTypes(dictMethod, listOf(it)) }
                                    ?: resolveOpHashFromConcreteTypes(dictMethod, concreteTypesForDict))
                            }
                        if (resolvedHash != null) {
                            // refToDictedOp wraps the resolved op when IT needs its
                            // own dicts (nested containers: `[[[P]]]`). Seed it with the
                            // structural binding so each recursion peels exactly one
                            // container level instead of re-guessing from the flat pile.
                            val subTypes = perVarBound?.let { listOf(it) } ?: concreteTypesForDict
                            fields.add(FieldIR(dictParamName, refToDictedOp(resolvedHash, subTypes, src)))
                        } else {
                            // Forward from caller's dict params if available
                            val callerDictIdx = enclosingDictSlot(dictMethod, com.mezze.language.ast.MagicParams.typeVarOfKey(dictMethodName))
                                ?.let { currentParamNames.indexOf(it) } ?: -1
                            if (callerDictIdx >= 0) {
                                val slot = currentCaptureCount + callerDictIdx
                                fields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                            } else {
                                // Inside a mono-spec compile: last-resort resolve
                                // against the spec's own typeArgs (see specTypeArgs).
                                resolveDictFromSpecTypeArgs(dictMethod, src)?.let {
                                    fields.add(FieldIR(dictParamName, it))
                                }
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
            // BUG 7: inside a mono spec, a receiver still typed by the generic's
            // own type variable has no dict slot (the spec stripped it, the
            // constraint being concrete now) and cannot inline (it is not a
            // primitive). Before falling through to the structural default, ask
            // the spec what it already decided: `Specialization.abilityImpls`
            // records which impl discharged each constraint. This is evidence the
            // monomorphizer produced, not a guess from shapes.
            // Narrowly scoped, and the narrowing is load-bearing: this applies ONLY
            // where the call would otherwise reach the primitive comparison node —
            // an INLINEABLE operator, backed by a real ability op, on a receiver
            // whose dict slot is gone. A broader version fired for any var-typed
            // receiver inside a spec and resolved unrelated ops against the spec's
            // type args, which broke seven map/set filter tests.
            val wouldFallToPrimitive = receiverType is VarType &&
                methodName in inlineableOps &&
                abilityOpIndex[methodName]?.isNotEmpty() == true &&
                enclosingDictSlot(methodName, (receiverType as? VarType)?.variable?.name) == null
            if (wouldFallToPrimitive) {
                specEvidenceOpHash(methodName)?.let { evidenceOpHash ->
                    val receiverIR = compileReceiverExpr(fn.record)
                    val evidenceFields = mutableListOf<FieldIR>()
                    evidenceFields.add(FieldIR(Keywords.IT, receiverIR))
                    val argRecord = compileExpr(expr.arg)
                    if (argRecord is MakeRecordIR) {
                        for (f in argRecord.fields) if (f.name != Keywords.IT) evidenceFields.add(f)
                    }
                    return emitCallDirect(
                        evidenceOpHash, MakeRecordIR(evidenceFields, src), expr.isTailCall, src,
                    )
                }
            }
            val opIsInlineable = methodName in inlineableOps
            val callAbilityHex = (fn as? TypedFieldAccess)?.abilityHash?.hex
            val inlineableNeedsDict = opIsInlineable &&
                abilityOpIndex[methodName]?.isNotEmpty() == true &&
                enclosingDictSlot(methodName, (receiverType as? VarType)?.variable?.name, callAbilityHex) != null
            if ((receiverType is VarType || viaEnclosingDecodeDict) && (!opIsInlineable || inlineableNeedsDict)) {
                val opCandidates = abilityOpIndex[methodName]
                // Bug B (#86): also enter this branch when there are no impl
                // candidates yet (blanket-impl body precompiled with the
                // ability-op index empty) but the current function has a
                // `__dict_<method>` param waiting. Without this, control
                // falls through to the regular-call path which compiles the
                // FieldAccess as `ReadFieldByName`, causing runtime
                // "Field not found: <method>".
                // The slot for THIS receiver's type variable when the function holds
                // one per variable, else the plain slot.
                val dictParamName = enclosingDictSlot(methodName, (receiverType as? VarType)?.variable?.name, callAbilityHex)
                    ?: com.mezze.language.ast.MagicParams.dictParamName(methodName)
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
                            val innerMethod = com.mezze.language.ast.MagicParams.methodOfKey(dictMethodName)
                            // Don't double-inject if the user already wrote this field.
                            if (fields.any { it.name == innerDictName }) continue
                            val resolvedHash = resolveOpHashFromConcreteTypes(innerMethod, concreteTypesForDict)
                            if (resolvedHash != null) {
                                fields.add(FieldIR(innerDictName, ReadGlobalFnByHashIR(resolvedHash, src)))
                            } else {
                                // Forward from caller's own dict params (caller is itself polymorphic
                                // on the same format type).
                                val callerDictIdx = enclosingDictSlot(innerMethod, com.mezze.language.ast.MagicParams.typeVarOfKey(dictMethodName))
                                    ?.let { currentParamNames.indexOf(it) } ?: -1
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
                is BindingRef.AbilityOp -> {
                    // The same node the receiver-taking path emits, and the same slot
                    // it computes — the only difference is that the argument record is
                    // passed through unchanged, there being no receiver to fold in.
                    census.methodDictCallee++
                    DictMethodCallIR(abilityOpDictSlot(binding), compileExpr(expr.arg), src)
                }
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
                        val rawConstraintTypeArgs = (rawFnType?.abilityConstraints
                            ?.flatMap { it.typeArgs }
                            ?.map { substitution.apply(it) }
                            ?: emptyList())
                            // KEY-constrained callees (min_by's `with Ord 'b` where 'b is
                            // f's RETURN type): the constraint arg stays a generalized var
                            // the module substitution can't solve. Bind it by unifying the
                            // callee's param type against the actual arg type ('b -> K),
                            // otherwise resolution falls to the arg types where the xs
                            // container falsely matches the blanket `impl Ord for ['a]`.
                            .let { bindConstraintArgsViaArg(rawFnType, expr.arg, it) }
                            // …and from the RETURN, for a constraint variable the
                            // parameters never mention (`{} with Zero 'a -> 'a`).
                            .let { bindConstraintArgsViaReturn(rawFnType, expr.type, it) }
                        val structuralConstraintTypes = structuralConstraintTypesFor(calleeHash, expr)
                        val constraintTypes =
                            if (structuralConstraintTypes.isNotEmpty()) structuralConstraintTypes
                            else rawConstraintTypeArgs.filter { it !is VarType }
                        // When ALL constraint type args are still VarType after substitution,
                        // we're inside a generic function body calling another generic function.
                        // In this case, prefer forwarding dicts from the caller over resolving
                        // from concrete types (which may match a wrong blanket container impl).
                        // A structural binding above is a concrete answer, so it overrides this.
                        val calleeConstraintsAreGeneric = structuralConstraintTypes.isEmpty() &&
                            rawConstraintTypeArgs.isNotEmpty() && rawConstraintTypeArgs.all { it is VarType }
                        // Every callee type var → its type here: an impl op binds through
                        // its instance head against the arg record's fields, a generic let
                        // through its declared arrow against the actual arg type.
                        val calleeVarBindings: Map<String, MezzeType> = run {
                            val argTypes = (expr.arg as? TypedRecord)?.entries
                                ?.filterIsInstance<TypedRecordEntry.Field>()
                                ?.map { substitution.apply(it.value.type) } ?: emptyList()
                            // S-3: a receiver-less op's arg record is `{}`, so there are no
                            // argument types to bind through. Its PRODUCED type is the only
                            // thing the instance head can match, same as above.
                            val headTypes = argTypes.ifEmpty { listOf(substitution.apply(expr.type)) }
                            val fromImpl = bindImplVarsForOp(calleeHash, headTypes)
                            if (fromImpl.isNotEmpty()) return@run fromImpl
                            val fromArrow = HashMap<String, MezzeType>()
                            rawFnType?.let { harvestVarBindings(it.paramType, substitution.apply(expr.arg.type), fromArrow) }
                            fromArrow
                        }
                        val enrichedArgIR = injectDictFields(argIR, expr.arg, calleeDictMethods, constraintTypes, calleeConstraintsAreGeneric, src, calleeVarBindings)
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
                    // A hash container buckets by the element's RUNTIME
                    // REPRESENTATION, which IS the derived structural equality — so
                    // it is already right for every element whose `Eq` the compiler
                    // wrote, and wrong only for one the AUTHOR wrote. Where the
                    // author wrote it, hand the container that element's own
                    // `hash`/`eq` and it buckets by those instead.
                    //
                    // Decided HERE and once, from the impl the monomorphizer already
                    // chose for this specialization — not threaded per call, not
                    // captured at construction. Declining leaves the structural
                    // native exactly as it was, so a `Set Int` keeps its specialised
                    // storage and iteration order.
                    evidenceNativeCall(binding.name, argIR, src)?.let { return it }
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
                        val rawConstraintTypeArgs = (rawFnType?.abilityConstraints
                            ?.flatMap { it.typeArgs }
                            ?.map { substitution.apply(it) }
                            ?: emptyList())
                            .let { bindConstraintArgsViaArg(rawFnType, expr.arg, it) }
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
    /**
     * Bind still-generic ability-constraint type args by unifying the callee's
     * param type against the call-site argument's actual type. A KEY-constrained
     * callee (`min_by: { xs: ['a], f: {x:'a} -> 'b } with Ord 'b`) carries a
     * generalized 'b the module substitution never solves; unifying the param
     * record against the concrete arg record (`f: {x:E} -> K`) binds 'b -> K so
     * dict resolution targets the KEY's impl instead of guessing from arg types
     * (where the xs container falsely matches the blanket `impl Ord for ['a]`).
     * Leaves already-concrete args and unbindable vars untouched.
     */
    /**
     * The mirror of [bindConstraintArgsViaArg]: bind a callee's constraint variables
     * from its declared RETURN, unified against the type this call actually produces.
     *
     * A constraint variable does not have to appear in the parameters.
     * `make: {} with Zero 'a -> 'a` mentions `'a` only in what it returns, so the
     * argument-side harvest has nothing to look at and `'a` stays generic — the
     * caller then injects no dictionary and the callee falls through to the built-in
     * fallback. The call's own solved type is the evidence, and it is already here.
     *
     * Runs after the argument harvest and only fills variables it left unbound, so
     * no call site that already resolves changes what it resolves to.
     */
    private fun bindConstraintArgsViaReturn(
        rawFnType: FunctionType?,
        callType: MezzeType,
        rawArgs: List<MezzeType>,
    ): List<MezzeType> {
        if (rawFnType == null || rawArgs.none { it is VarType }) return rawArgs
        val produced = substitution.apply(callType)
        if (produced is VarType || containsVarType(produced)) return rawArgs
        val bindings = mutableMapOf<String, MezzeType>()
        harvestVarBindings(substitution.apply(rawFnType.returnType), produced, bindings)
        if (bindings.isEmpty()) return rawArgs
        return rawArgs.map { t ->
            if (t is VarType) {
                val bound = bindings[t.variable.name]
                if (bound != null && bound !is VarType) substitution.apply(bound) else t
            } else t
        }
    }

    private fun bindConstraintArgsViaArg(
        rawFnType: FunctionType?,
        argExpr: TypedExpr,
        rawArgs: List<MezzeType>
    ): List<MezzeType> {
        if (rawFnType == null || rawArgs.none { it is VarType }) return rawArgs
        val bindings = mutableMapOf<String, MezzeType>()
        val pattern = substitution.apply(rawFnType.paramType)
        harvestVarBindings(pattern, substitution.apply(argExpr.type), bindings)
        // Second chance for KEY vars the type-level harvest missed: a callback arg's
        // stamped return type is often LAUNDERED to a fresh var (`{x} -> x.k` — the
        // field access never gets K stamped through the generic 'a; the 95da1766
        // family). Recover it from the lambda EXPRESSION structurally.
        harvestFromCallbackEntries(pattern, argExpr, bindings)
        if (bindings.isEmpty()) return rawArgs
        return rawArgs.map { t ->
            if (t is VarType) {
                val bound = bindings[t.variable.name]
                if (bound != null && bound !is VarType) substitution.apply(bound) else t
            } else t
        }
    }

    /** For each callback field whose declared return is a still-unbound pattern var
     *  (`f: { x: 'a } -> 'b`), recover the ACTUAL return type from the lambda
     *  expression when its stamped type was laundered: direct body type if concrete,
     *  else a `param.field` body resolved against the param's (possibly
     *  harvest-bound) record type — the same recovery scanExprForVarTypeMethods
     *  uses for the frame-slot fix. */
    private fun harvestFromCallbackEntries(pattern: MezzeType, argExpr: TypedExpr, out: MutableMap<String, MezzeType>) {
        if (pattern !is RecordType || argExpr !is TypedRecord) return
        for (entry in argExpr.entries) {
            if (entry !is TypedRecordEntry.Field) continue
            val pT = pattern.fields[entry.name] as? FunctionType ?: continue
            val retVar = pT.returnType as? VarType ?: continue
            if (out.containsKey(retVar.variable.name)) continue
            val lam = entry.value as? TypedLambda ?: continue
            val recovered = recoverLambdaReturnType(lam, out) ?: continue
            out[retVar.variable.name] = recovered
        }
    }

    private fun recoverLambdaReturnType(lam: TypedLambda, bindings: Map<String, MezzeType>): MezzeType? {
        val direct = substitution.apply(lam.body.type)
        if (direct !is VarType) return direct
        val body = lam.body
        // Identity key (`f = { x } -> x`, i.e. `min_by`/`dedup_by` keyed on the
        // element itself): the return type IS the param's type, which the type-level
        // harvest already bound from the container arg. Without this the key var
        // stays unbound and dict resolution falls back to scavenging the arg types,
        // where the CONTAINER falsely matches its own blanket impl — a `Deque P`
        // handed `impl Ord for Deque 'a` as the key comparator.
        if (body is TypedVar) {
            val paramT = lam.params.find { it.name == body.name }?.type?.let { substitution.apply(it) }
            if (paramT != null && paramT !is VarType) return paramT
            val bound = (paramT as? VarType)?.let { bindings[it.variable.name] }?.let { substitution.apply(it) }
            if (bound != null && bound !is VarType) return bound
        }
        if (body is TypedFieldAccess) {
            val base = body.record
            if (base is TypedVar) {
                var baseT: MezzeType? = lam.params.find { it.name == base.name }?.type?.let { substitution.apply(it) }
                if (baseT is VarType) baseT = bindings[(baseT as VarType).variable.name]
                val rt = baseT as? RecordType ?: return null
                val f = rt.fields[body.field]?.let { substitution.apply(it) }
                if (f != null && f !is VarType) return f
            }
        }
        return null
    }

    /** Best-effort structural harvest of `patternVar -> concreteValue` bindings.
     *  Unlike MonoTypeOps.unify this never FAILS: var-vs-var pairs and shape
     *  mismatches are simply skipped (unify's refuse-to-bind-free-var rule exists
     *  to gate mono SEEDING; here we only want whatever concrete bindings the
     *  call site does pin down). Handles both container representations
     *  (SeqType/... and TypeApplication("Seq", ...)). First binding wins. */
    private fun MezzeType.declaredName(): String? = when (this) {
        is RecordType -> identity.displayNameOrNull()
        is VariantType -> identity.displayNameOrNull()
        is TypeApplication -> identity.displayNameOrNull() ?: constructor
        else -> null
    }

    internal fun harvestVarBindings(pattern: MezzeType, value: MezzeType, out: MutableMap<String, MezzeType>) {
        when (pattern) {
            is VarType -> {
                if (value !is VarType) out.putIfAbsent(pattern.variable.name, value)
            }
            is FunctionType -> if (value is FunctionType) {
                harvestVarBindings(pattern.paramType, value.paramType, out)
                harvestVarBindings(pattern.returnType, value.returnType, out)
            }
            is RecordType -> if (value is RecordType) {
                for ((k, t) in pattern.fields) value.fields[k]?.let { harvestVarBindings(t, it, out) }
            }
            is TypeApplication -> when {
                value is TypeApplication && pattern.constructor == value.constructor && pattern.args.size == value.args.size ->
                    pattern.args.zip(value.args).forEach { (p, v) -> harvestVarBindings(p, v, out) }
                // A declared generic record / variant arrives EXPANDED (`Box P` as
                // `RecordType{v: P}`, the two-representations landmine) while the impl
                // target is the nominal `Box 'a`. Open the declaration's generic shape
                // (`{v: 'p}` over its own params), unify that against the expanded
                // value to learn each DECL param, then map decl params to the
                // pattern's args positionally — so `'a = P`.
                (value is RecordType || value is VariantType) && value.declaredName() == pattern.constructor -> {
                    val shape = printTypeDefs[pattern.constructor]
                    val params = typeDeclParams[pattern.constructor]
                    if (shape != null && params != null && params.size == pattern.args.size) {
                        val declBindings = HashMap<String, MezzeType>()
                        harvestVarBindings(shape, value, declBindings)
                        for ((param, arg) in params.zip(pattern.args)) {
                            val bound = declBindings[param] ?: declBindings[param.removePrefix("'")] ?: declBindings["'" + param.removePrefix("'")]
                            if (bound != null) harvestVarBindings(arg, bound, out)
                        }
                    }
                }
                else -> {}
            }
            is VariantType -> if (value is VariantType) {
                for ((ctor, payload) in pattern.constructors) {
                    val vp = value.constructors[ctor]
                    if (payload != null && vp != null) harvestVarBindings(payload, vp, out)
                }
            }
            else -> {}
        }
    }

    private fun injectDictFields(
        argIR: NodeIR,
        argExpr: TypedExpr,
        dictMethods: Set<String>,
        constraintTypes: List<MezzeType>,
        calleeConstraintsAreGeneric: Boolean,
        src: SourceInfo,
        // Callee type var → type at this site (instance head / declared arrow
        // unified against the actuals); serves the per-var dict slots.
        varBindings: Map<String, MezzeType> = emptyMap(),
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
            val dictMethod = com.mezze.language.ast.MagicParams.methodOfKey(methodName)
            val keyVar = com.mezze.language.ast.MagicParams.typeVarOfKey(methodName)
            // BUG 9 / C.2: the ability this slot belongs to, when the key carries
            // one. Every resolution below must stay inside it — an ability-qualified
            // slot filled from another ability's impl is precisely the bug.
            val keyAbility = com.mezze.language.ast.MagicParams.abilityOfKey(methodName)
            val keyAbilityHash: Hash? = keyAbility?.let { prefix ->
                abilityOpIndex[dictMethod]?.firstNotNullOfOrNull { op ->
                    op.abilityHash?.takeIf { it.hex.startsWith(prefix) }
                }
            }
            // The caller's own slot for this key (per-var when it holds one).
            val callerSlot = enclosingDictSlot(dictMethod, keyVar, keyAbilityHash?.hex) ?: dictParamName
            // A per-var key: the callee wants THIS variable's dict. Bound to a
            // concrete type here → resolve that type's impl; bound to one of the
            // caller's own variables → forward the caller's slot for it.
            if (keyVar != null) {
                val boundHere = boundTypeForKey(methodName, varBindings)
                if (boundHere != null) {
                    val perVarHash = resolveOpHashFromConcreteTypes(dictMethod, listOf(boundHere), keyAbilityHash)
                    if (perVarHash != null) {
                        dictFields.add(FieldIR(dictParamName, refToDictedOp(perVarHash, listOf(boundHere), src)))
                        continue
                    }
                }
                val boundVar = (varBindings[keyVar] ?: varBindings["'$keyVar"]) as? VarType
                val forwardSlot = enclosingDictSlot(dictMethod, boundVar?.variable?.name, keyAbilityHash?.hex)
                if (forwardSlot != null) {
                    val idx = currentParamNames.indexOf(forwardSlot)
                    if (idx >= 0) {
                        dictFields.add(FieldIR(dictParamName, ReadLocalIR(currentCaptureCount + idx, SlotKind.OBJECT, src)))
                        continue
                    }
                    val cap = currentCaptureNames[forwardSlot]
                    if (cap != null) {
                        dictFields.add(FieldIR(dictParamName, ReadCaptureIR(cap, SlotKind.OBJECT, src)))
                        continue
                    }
                }
            }

            // When constraints are generic, prefer forwarding from caller
            if (calleeConstraintsAreGeneric) {
                // 1a. Forward from caller param
                val callerDictIdx = currentParamNames.indexOf(callerSlot)
                if (callerDictIdx >= 0) {
                    val slot = currentCaptureCount + callerDictIdx
                    dictFields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                    continue
                }
                // 1b. Forward from caller capture
                val callerCaptureIdx = currentCaptureNames[callerSlot]
                if (callerCaptureIdx != null) {
                    dictFields.add(FieldIR(dictParamName, ReadCaptureIR(callerCaptureIdx, SlotKind.OBJECT, src)))
                    continue
                }
            }

            // 2. Resolve from concrete types
            val opHash = if (constraintTypes.isNotEmpty()) {
                resolveOpHashFromConcreteTypes(dictMethod, constraintTypes)
                    ?: resolveOpHashFromConcreteTypes(dictMethod, concreteTypes)
            } else {
                resolveOpHashFromConcreteTypes(dictMethod, concreteTypes)
            }
            if (opHash != null) {
                // refToDictedOp wraps the resolved op when IT needs its own dicts —
                // e.g. `sort { xs = [[P]] }`: sort's Ord dict is the seq Ord op
                // (comparing [P]'s), which needs its own element dict (`Ord P`).
                dictFields.add(FieldIR(dictParamName,
                    refToDictedOp(opHash, constraintTypes + concreteTypes, src)))
            } else {
                // 3. Fallback: forward from caller if available
                val callerDictIdx = currentParamNames.indexOf(callerSlot)
                if (callerDictIdx >= 0) {
                    val slot = currentCaptureCount + callerDictIdx
                    dictFields.add(FieldIR(dictParamName, ReadLocalIR(slot, SlotKind.OBJECT, src)))
                } else {
                    val callerCaptureIdx = currentCaptureNames[callerSlot]
                    val specResolved = if (callerCaptureIdx == null)
                        resolveDictFromSpecTypeArgs(methodName, src) else null
                    if (callerCaptureIdx != null) {
                        dictFields.add(FieldIR(dictParamName, ReadCaptureIR(callerCaptureIdx, SlotKind.OBJECT, src)))
                    } else if (specResolved != null) {
                        // 4. Inside a mono-spec compile whose NAME-based substitution
                        // missed this receiver's type var: resolve against the spec's
                        // own typeArgs (see specTypeArgs).
                        dictFields.add(FieldIR(dictParamName, specResolved))
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
     * A type like `['a]` is "partially generic" — the container is
     * known but the element is unknown. Resolving ability impls against such
     * types can match blanket container-level impls (e.g. `impl Ord for ['a]`)
     * when the intended resolution is for the element type.
     */
    internal fun containsVarType(type: MezzeType): Boolean = when (type) {
        is VarType -> true
        is TypeApplication -> type.args.any { containsVarType(it) }
        else -> false
    }

    /**
     * Resolve the ability op hash for a given method name using concrete types.
     * Tries each concrete type against the ability op index to find a match.
     */
    /** The element (or map value) type of a container type, else null. */
    private fun containerElementType(type: MezzeType): MezzeType? = when (type) {
        // A container used to have TWO representations — the dedicated container
        // nodes and `TypeApplication("List"|"Map"|…, args)` — and only the former
        // were read here, which is the bug this arm exists for: `List P == List P`
        // yielded NO element type — element-first dict resolution couldn't descend, no
        // `__dict_eq` was injected, and the comparator reached the native as a
        // captureless stub whose `a == b` fell back to STRUCTURAL equality, silently
        // ignoring the element's Eq impl. Take the LAST arg structurally (mirrors
        // Map/SortedMap → valueType); never key on the constructor NAME.
        // The last CONCRETE argument, not simply the last: `Ok { val = 1 }` infers
        // `Result Int 'a` with the error type still unconstrained, and every caller
        // here discards a `VarType` anyway — so answering with the trailing `'a`
        // answers nothing, while `Int` is the payload actually being compared.
        is TypeApplication -> type.args.lastOrNull { it !is VarType }
        // ...and a name-carried VARIANT is the THIRD representation, which the two
        // above still missed. `let a: Option Int = …` converts the annotation to a
        // TypeApplication and works; `let a = Some { val = 42 }` INFERS the same
        // type as a VariantType carrying `typeArgs = [Int]`, and that yielded no
        // element at all. The `Eq 'a` dict for the payload then resolved against
        // `[Option Int]` — which is Option's OWN `eq` — so comparing two `Some`s ran
        // Option's variant match on the payload `42` and died with "no variant tag
        // matched — typechecker exhaustiveness guarantee violated". A well-typed
        // program, stuck, because one of three spellings of one type was never
        // asked the question.
        is VariantType -> type.typeArgs.lastOrNull { it !is VarType }
        else -> null
    }

    internal fun resolveOpHashFromConcreteTypes(
        methodName: String,
        concreteTypes: List<MezzeType>,
        /** BUG 9 / C.2: confine the search to ONE ability when the caller knows
         *  which. The op index is keyed on the operation name, so without this a
         *  type implementing two abilities that both declare it gets whichever
         *  impl was registered first. */
        abilityHash: Hash? = null,
    ): Hash? {
        // First pass: direct match.
        for (concreteType in concreteTypes) {
            if (concreteType is VarType) continue
            val opHash = tryResolveAbilityOp(methodName, concreteType, abilityHash = abilityHash)
            if (opHash != null) return opHash
        }
        // Second pass (#195 B3): descend into container types. When the caller
        // passes `xs: [Str]` and the callee needs an `Encode 'config for 'a` impl
        // for the element type, the right impl is `Encode <config> for Str` —
        // extract it from the container's element/value type.
        for (concreteType in concreteTypes) {
            val elementType = containerElementType(concreteType)
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
    /** Wrapper hashes already extracted this compile — avoid duplicate FunctionIRs. */
    private val builtDictedOpWrappers = mutableSetOf<Hash>()

    /**
     * Reference to a resolved op `opHash` for dict injection, wrapping it in a
     * dict-supplying closure when the op ITSELF needs `__dict_<m>` params — the
     * nested-container case: for `[[[P]]]` the dict comparing `[P]`-elements is the
     * seq Ord op, which needs its own element dict (`Ord P`). RECURSIVE and
     * depth-independent: each sub-dict is resolved element-first against
     * `concreteTypes` descended one container level, and recursively wrapped.
     * The wrapper has NO captures (sub-dict IRs are self-contained global refs or
     * nested wrappers), so it is frame-independent and safe to inline anywhere.
     * Returns a bare `ReadGlobalFnByHashIR` when no dicts are needed or a sub-dict
     * can't be resolved concretely (runtime DictFallback still covers primitives).
     */
    private fun refToDictedOp(
        opHash: Hash,
        concreteTypes: List<MezzeType>,
        src: SourceInfo,
        onPath: Set<Hash> = emptySet(),
    ): NodeIR {
        val needed = dictMethodsForHash[opHash] ?: emptySet()
        // Termination is by CYCLE DETECTION, not by a counter. This was
        // `depth > 8` — an arbitrary bound that also truncated deep but perfectly
        // finite chains and could not tell the two cases apart. Re-entering an op
        // already on the path is the real condition, so the anti-hang invariant
        // is satisfied for the right reason instead of because a counter ran out.
        // Real cycles DO occur — three distinct ops in a three-suite run. On one,
        // the un-wrapped op is not a degraded guess but the only correct answer:
        // a cyclic dict requirement has no finite wrapper, so the dictionary has
        // to come from the caller's frame instead.
        if (opHash in onPath) return ReadGlobalFnByHashIR(opHash, src)
        if (needed.isEmpty()) return ReadGlobalFnByHashIR(opHash, src)
        // Descend one container level: the sub-op dispatches on the ELEMENT of the
        // current element, so the element is placed FIRST and its impl wins over
        // the container's. Not a fallback and not a preference — MEASURED
        // load-bearing: passing `concreteTypes` unordered breaks every nested
        // comparison in SeqUserTypeCompareTest (nested3Sort, nested3SeqEq,
        // variantNestedSeqEq, variantNestedLt, nested3Lt, newtypeNested3Eq …).
        // For `[[P]]` the inner seq's dict must come from P, not from Seq.
        val deeper = elementFirst(concreteTypes)
        val resolvedDicts = mutableListOf<Pair<String, NodeIR>>()
        for (methodName in needed.sorted()) {
            val dictParamName = com.mezze.language.ast.MagicParams.dictParamName(methodName)
            val dictMethod = com.mezze.language.ast.MagicParams.methodOfKey(methodName)
            val subTypes = deeper
            val subHash = resolveOpHashFromConcreteTypes(dictMethod, subTypes)
            if (subHash != null) {
                resolvedDicts.add(dictParamName to refToDictedOp(subHash, subTypes, src, onPath + opHash))
            } else {
                // Unresolvable concretely; forwarding from the enclosing frame would
                // be invalid inside the wrapper's own frame — leave the op bare.
                return ReadGlobalFnByHashIR(opHash, src)
            }
        }
        // The wrapper's params = the op's regular params (prelowered FunctionIR is
        // authoritative for stdlib ops; drop the synthetic __dict_* params).
        //
        // C.5: when neither source knows the params this used to GUESS
        // `[it, r]` — the shape of a binary ability op — and build a wrapper
        // around it. It fired 18 times in a two-suite run, so it was load-bearing
        // in the sense of being reached, but not in the sense of being needed:
        // declining to wrap and emitting the bare op leaves BOTH gates green.
        // A wrapper built on a guessed signature is worse than no wrapper, since
        // a wrong arity silently mis-binds every argument.
        val preParams = hashToFunctionIR[opHash]?.paramNames
        val regularParamNames = if (preParams != null) {
            preParams.filterNot { com.mezze.language.ast.MagicParams.isDict(it) }
        } else {
            (declIndex?.get(opHash)?.value as? TypedLambda)?.params?.map { it.name }
                ?: return ReadGlobalFnByHashIR(opHash, src)
        }
        val hashBuilder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        hashBuilder.append("dicted_op")
        hashBuilder.append(opHash.hex)
        for ((name, _) in resolvedDicts) hashBuilder.append(name)
        for (t in deeper) com.mezze.language.ast.hashing.appendTypeInScope(hashBuilder, t)
        val wrapperHash = Hash(hashBuilder.build().toFullHexString())
        if (builtDictedOpWrappers.add(wrapperHash)) {
            // Body: opHash { p0 = slot0, …, __dict_m = <inlined sub-dict IR> }.
            // Frame = [param0..paramN] (no captures).
            val innerArgFields = mutableListOf<FieldIR>()
            for ((i, paramName) in regularParamNames.withIndex()) {
                innerArgFields.add(FieldIR(paramName, ReadLocalIR(i, SlotKind.OBJECT, src)))
            }
            for ((name, subDictIR) in resolvedDicts) {
                innerArgFields.add(FieldIR(name, subDictIR))
            }
            extractedFunctions.add(FunctionIR(
                hash = wrapperHash,
                name = "dicted_op",
                qualifiedName = "dicted_op",
                paramSlots = regularParamNames.map { SlotKind.OBJECT },
                paramNames = regularParamNames,
                localSlots = emptyList(),
                localNames = emptyList(),
                captureSlots = emptyList(),
                captureNames = emptyList(),
                body = CallDirectIR(opHash, listOf(MakeRecordIR(innerArgFields, src)), false, src),
                sourceFile = src.fileIndex.toString(),
                sourceSection = src
            ))
        }
        return MakeLambdaIR(wrapperHash, emptyList(), src)
    }

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
            val dictMethod = com.mezze.language.ast.MagicParams.methodOfKey(methodName)
            val opHash = resolveOpHashFromConcreteTypes(dictMethod, allConcreteTypes)
            if (opHash != null) {
                resolvedDicts.add(dictParamName to ReadGlobalFnByHashIR(opHash, src))
            } else {
                // Try forwarding from caller's dict params
                // No unqualified-name fallback. This used to drop the type var and
                // retry under the bare `__dict_<method>` name — the name-keyed
                // guess C.2 replaced. It FIRED (14 times across a full forced
                // rerun), so it was not dead; removing it anyway leaves both
                // gates green, which means those 14 wrappers were being built on
                // a slot chosen by name rather than by (ability, method, var).
                // Not wrapping is the honest answer.
                val slotName = enclosingDictSlot(dictMethod, com.mezze.language.ast.MagicParams.typeVarOfKey(methodName))
                    ?: return null
                val callerDictIdx = currentParamNames.indexOf(slotName)
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
        // No invented parameter list. This used to fall back to `["it"]` when the
        // original lambda was not found; measured DEAD — zero hits across a full
        // forced rerun of the interpreter suite. A wrapper built on a guessed
        // arity mis-binds every argument, so not wrapping is the correct answer.
        val regularParamNames = originalLambda?.params?.map { it.name } ?: return null

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

    /** Non-null while compiling a mono specialization's body (set to the spec hash).
     *  Nested lambdas extracted during that compile get their hash salted with it. */
    private var specLambdaSalt: Hash? = null

    /** The mono specialization's concrete type args, non-null while compiling its
     *  body. LAST-RESORT dict resolution: the monomorphizer's NAME-based body
     *  substitution misses type vars whose name differs from the decl's typeParams
     *  (generalization renames `'a`→`'p237`; lambda params get field-name-stamped
     *  vars), so a comparator inside the spec can keep a VarType receiver and its
     *  callee's dict then resolves to nothing — the spec emits a bare call that hits
     *  the primitive-only runtime fallback (`sort { xs = [[P]] }`). When normal
     *  resolution fails INSIDE a spec, resolve against these typeArgs element-first:
     *  the spec knows exactly what `'a` is. */
    /**
     * BUG 7 — the ability impls the monomorphizer ALREADY RESOLVED for the
     * specialization currently being compiled.
     *
     * A spec records, per ability constraint it discharged, which impl satisfies
     * it (`Specialization.abilityImpls`). That is exactly the evidence a call
     * site inside the spec needs, and it was not being threaded in: the spec
     * strips the generic body's `__dict_<op>` params because the constraint is
     * now concrete, but a receiver left typed by the generic's own type variable
     * can then neither inline (it is not a primitive) nor dict-dispatch (the slot
     * is gone), and `x < acc` falls through to the primitive comparison node —
     * a crash on a record or variant element, and a silently wrong answer for a
     * newtype whose ordering differs from the type it wraps.
     */
    private var specAbilityImpls: List<AbilityImplRef> = emptyList()

    /**
     * The op hash for [methodName] according to the current spec's recorded
     * evidence, or null outside a spec / when no recorded impl provides it.
     *
     * Cross-checked against the op index rather than trusted on the name alone:
     * a ref only answers for a method its own impl actually declares, so a spec
     * carrying several constraints cannot have one ability's evidence answer for
     * another's identically-named op.
     */
    /**
     * Structural container native → the form taking the element's own `hash`/`eq`.
     *
     * A native is identified by its name — that is what a native IS here, the same
     * way `__native_int_eq` names an operation — so this is a table of operations,
     * not a dispatch on a type's spelling.
     */
    private val evidenceNativeForm: Map<String, String> = mapOf(
        "__native_set_from_seq" to "__native_set_from_seq_with_evidence",
        "__native_set_insert" to "__native_set_insert_with_evidence",
        "__native_set_contains" to "__native_set_contains_with_evidence",
        "__native_set_remove" to "__native_set_remove_with_evidence",
        "__native_map_insert" to "__native_map_insert_with_evidence",
        "__native_map_get" to "__native_map_get_with_evidence",
        "__native_map_contains_key" to "__native_map_contains_key_with_evidence",
        "__native_map_remove" to "__native_map_remove_with_evidence",
        // The rebuild side, used by the codec walker to turn decoded entries back
        // into a Map. Same rule as insert: a key whose `Eq` the author wrote needs
        // its own instance installed, or the map buckets by representation and
        // silently disagrees with that instance.
        "__native_map_from_seq" to "__native_map_from_seq_with_evidence",
    )

    /**
     * The evidence-carrying call for [nativeName], or null to leave it structural.
     *
     * Null whenever the element's instance is the compiler's own: the container's
     * structural bucketing already IS that instance, so threading closures would buy
     * nothing and cost the specialised storage tiers.
     */
    private fun evidenceNativeCall(nativeName: String, argIR: NodeIR, src: SourceInfo): NodeIR? {
        val evidenceName = evidenceNativeForm[nativeName] ?: return null
        if (argIR !is MakeRecordIR) return null
        // Both halves or neither: a container that hashed by the element's instance
        // and compared structurally would put equal values in one bucket and then
        // fail to match them.
        val hashOp = specEvidenceOpHash(com.mezze.language.ast.BuiltinTypes.HASH_METHOD) ?: return null
        val eqOp = specEvidenceOpHash(com.mezze.language.ast.BuiltinTypes.EQ_METHOD) ?: return null
        if (specAbilityImpls.none { implIsHandWritten(it.implHash) }) return null
        return CallNativeIR(
            name = evidenceName,
            arg = MakeRecordIR(
                argIR.fields + listOf(
                    FieldIR(com.mezze.language.ast.BuiltinTypes.HASH_METHOD, ReadGlobalFnByHashIR(hashOp, src)),
                    FieldIR(com.mezze.language.ast.BuiltinTypes.EQ_METHOD, ReadGlobalFnByHashIR(eqOp, src)),
                ),
                src,
            ),
            src = src,
        )
    }

    /** True when the author wrote this impl rather than the compiler deriving it. */
    private fun implIsHandWritten(implHash: Hash): Boolean =
        abilityImpls.any { it.hash == implHash && !it.derived }

    private fun specEvidenceOpHash(methodName: String): Hash? {
        val candidates = abilityOpIndex[methodName] ?: return null
        // The ONLY source: the impls the monomorphizer recorded as discharging
        // this spec's constraints. Cross-checked against the op index rather than
        // trusted on the name, so a spec carrying several constraints cannot have
        // one ability's evidence answer for another's identically-named op.
        //
        // There used to be a fallback here that resolved the op against the
        // spec's TYPE ARGUMENTS — what the generic was specialized to — gated to
        // specs with exactly ONE argument, because with two (`map::filter` at
        // `Map K V`) the receiver's variable could be either and resolving
        // against the pile picked the wrong op, breaking seven map/set filter
        // tests. It was a guess with a guard on it.
        //
        // C.1 removed the need for it: a specialization now resolves its OWN
        // ability constraints instead of inheriting its caller's, so the evidence
        // is present wherever it is meaningful. Deleting the fallback outright —
        // not broadening it — leaves both gates green. Anything this declines had
        // no recorded instance, and declining sends it to the structural default,
        // which is the honest answer rather than a shape-based guess.
        for (ref in specAbilityImpls) {
            candidates.firstOrNull { it.implHash == ref.implHash }?.let { return it.opHash }
        }
        return null
    }

    private var specTypeArgs: List<MezzeType>? = null
    /** The generic function / impl op the spec being compiled was cut from. */
    private var specGenericHash: Hash? = null

    /**
     * Structural walk to derive a CONCRETE type for [expr] when the solver left
     * `expr.type` as a bare VarType. Only returns a concrete type; every path that
     * would otherwise yield a VarType (or contain one) returns null so the caller
     * knows to fall back to the annotated type.
     *
     * Backs the `slotConcreteTypes` map (see field comment). The receiver's static
     * type for same-named-inherent-method dispatch across nominal newtypes MUST be
     * concrete at compile time — runtime dispatch keys on Java class, which is the
     * shared `MezzeForeign` wrapper for every `<Lang>Foreign` newtype, so it can't
     * disambiguate. This helper is the "walk the type environment" step: when
     * `expr.type` is a fresh VarType (destructuring off a poly let, an unsolved
     * match branch, ...) we look at what the expression STRUCTURALLY has to be.
     *
     * Handled shapes:
     * - `TypedCall` on a `ByHash` callee (or a `TypedFieldAccess` callee)
     *   whose FunctionType return is already concrete. The Ok arm of the
     *   polyglot chain lands here for the inner nested-match case.
     * - `TypedMatch` — recurse on arms; every arm's derived type must agree.
     *   When arm-derivation doesn't converge but the scrutinee is a concrete
     *   `Result A B` and the match shape is "Ok arm threads `val` as `it`,
     *   Err arm reconstructs Err" (see [matchPreservesResultShape]), fall
     *   back to the scrutinee's Result type. This is the narrow heuristic
     *   that lets `let uuid_val = match uuid_class is …` recover
     *   `Result JvmForeign InteropError` for the outer let.
     * - `TypedVar` whose slot has an override in `slotConcreteTypes` —
     *   recover through the local scope. Enables the chain of
     *   `let uuid_val = match …` → `match uuid_val is Ok { val = u } → …`.
     *
     * Any shape not on this list (or one containing a VarType) returns null.
     * The caller treats null as "no override" — original receiverType stays.
     */
    internal fun deriveConcreteType(expr: TypedExpr): MezzeType? = when (expr) {
        // An effect operation's return type is fixed by its `effect X where { op:
        // <sig> }` declaration — TypedPerformOp carries the substituted op-return
        // type directly on the node. Reading it lets an inline
        // `<Effect>.<op>{...}.<method>{}` recover the concrete Result-typed receiver
        // even when the outer dispatch's `substitution.apply(...)` leaves the site
        // as a bare VarType (the polyglot `Python.eval{...}.as_str{}` shape). The
        // shared VarType / containsVarTypeDeep guard keeps null the answer for
        // ops whose return still carries an unsolved payload/error TyVar.
        is TypedPerformOp -> expr.type.takeIf {
            it !is VarType && !containsVarTypeDeep(it)
        }
        is TypedCall -> {
            val fn = expr.function
            val calleeReturn = when {
                fn is TypedVar && fn.binding is BindingRef.ByHash ->
                    (fn.type as? FunctionType)?.returnType
                fn is TypedFieldAccess ->
                    (fn.type as? FunctionType)?.returnType
                        ?: (expr.type as? FunctionType)?.returnType
                else -> null
            }
            // Fall back to the TypedCall's own solved type when the callee's
            // FunctionType wasn't visible (indirect callee, chained method-of-method,
            // etc.) but the enclosing solve pinned this call's result. Same VarType
            // guard as everywhere else — a concrete inline call is what fires the
            // recovery, nothing else.
            calleeReturn?.takeIf { it !is VarType && !containsVarTypeDeep(it) }
                ?: expr.type.takeIf { it !is VarType && !containsVarTypeDeep(it) }
        }
        is TypedMatch -> run {
            val scrutineeConcrete: MezzeType? = run {
                val stated = expr.scrutinee.type
                if (stated !is VarType && !containsVarTypeDeep(stated)) stated
                else deriveConcreteType(expr.scrutinee)
            }
            var chosen: MezzeType? = null
            var mismatch = false
            for (case in expr.cases) {
                val armType = deriveConcreteType(case.body) ?: continue
                if (chosen == null) chosen = armType
                else if (chosen != armType) { mismatch = true; break }
            }
            if (mismatch) null
            else chosen ?: run {
                // Result-preservation heuristic (see slotConcreteTypes field
                // comment) — narrow polyglot-chain shape: Ok arm threads the
                // pattern-bound val as `it`, Err arm reconstructs Err.
                if (scrutineeConcrete is TypeApplication &&
                    scrutineeConcrete.constructor == "Result" &&
                    matchPreservesResultShape(expr)) scrutineeConcrete
                else null
            }
        }
        is TypedVar -> {
            val binding = expr.binding
            if (binding is BindingRef.Local) {
                localsByName[expr.name]?.let { slotConcreteTypes[it] }
            } else null
        }
        else -> null
    }

    /**
     * Result-preservation shape detector for the polyglot chain (see the
     * heuristic call site in `deriveConcreteType`'s TypedMatch handler).
     *
     * Returns true only for two-arm matches with:
     *  - `Ok { val = <v> } -> <call> { it = <v>, ... }` — the Ok arm's body
     *    is a call that threads the pattern-bound val through as the ability
     *    method's `it` receiver.
     *  - `Err { error = <e> } -> Err { error = <e> }` — the Err arm's body
     *    reconstructs Err with the same `error` binding.
     *
     * Any other shape returns false — an Ok arm that re-wraps
     * (`Ok { val = f(c) }`), a rebranded error, or a scalar body all fall
     * through to null derivation, preserving pre-fix behaviour for every
     * code path that isn't this specific idiom.
     */
    private fun matchPreservesResultShape(expr: TypedMatch): Boolean {
        if (expr.cases.size != 2) return false
        var sawOk = false
        var sawErr = false
        for (case in expr.cases) {
            val pat = case.patterns.firstOrNull() as? TypedVariantPattern ?: return false
            val payload = pat.payload as? TypedRecordPattern ?: return false
            when (pat.tag) {
                "Ok" -> {
                    val valBinding = payload.fields.firstOrNull { it.name == "val" }
                        ?.let { it.pattern as? TypedVarPattern }?.name ?: return false
                    val body = case.body as? TypedCall ?: return false
                    val argRec = body.arg as? TypedRecord ?: return false
                    val itField = argRec.entries.filterIsInstance<TypedRecordEntry.Field>()
                        .firstOrNull { it.name == Keywords.IT } ?: return false
                    val itVar = itField.value as? TypedVar ?: return false
                    if (itVar.name != valBinding) return false
                    sawOk = true
                }
                "Err" -> {
                    // `Err { error }` shorthand: TypedFieldPattern.pattern is
                    // null and the field binds under its own name. `Err {
                    // error = X }` explicit: field.pattern is a TypedVarPattern
                    // named X.
                    val errorFp = payload.fields.firstOrNull { it.name == "error" } ?: return false
                    val errorBinding = when (val sub = errorFp.pattern) {
                        null -> errorFp.name
                        is TypedVarPattern -> sub.name
                        else -> return false
                    }
                    val body = case.body as? TypedVariantConstruct ?: return false
                    if (body.tag != "Err") return false
                    val bodyRec = body.payload as? TypedRecord ?: return false
                    val errorEntry = bodyRec.entries.filterIsInstance<TypedRecordEntry.Field>()
                        .firstOrNull { it.name == "error" } ?: return false
                    val errorVar = errorEntry.value as? TypedVar ?: return false
                    if (errorVar.name != errorBinding) return false
                    sawErr = true
                }
                else -> return false
            }
        }
        return sawOk && sawErr
    }

    /**
     * Read pattern binding types structurally off a CONCRETE scrutinee type.
     * Mirrors `TypedAstBuilder.patternBindingTypes` (which is emit-side, on the
     * ParsedPattern; this one is compile-side on the TypedPattern) — used to
     * populate `slotConcreteTypes` for match-case pattern bindings when we've
     * recovered a concrete scrutinee type via `deriveConcreteType`. Handles the
     * shapes the polyglot dispatch bug lives in: `Ok { val = u }` off a
     * `Result JvmForeign InteropError` binds `u : JvmForeign`.
     *
     * Only records entries with concrete types; VarType payloads (an unsolved
     * generic scrutinee) contribute nothing.
     */
    internal fun readPatternBindings(pattern: TypedPattern, scrutinee: MezzeType, out: MutableMap<String, MezzeType>) {
        when (pattern) {
            is TypedVarPattern -> {
                if (scrutinee !is VarType && !containsVarTypeDeep(scrutinee)) out[pattern.name] = scrutinee
            }
            is TypedVariantPattern -> {
                val payloadType: MezzeType? = when (scrutinee) {
                    is VariantType -> scrutinee.constructors[pattern.tag]
                    is TypeApplication -> {
                        // Well-known variants (Result/Option) — pull the constructor's
                        // payload shape from the type args. Only handled shapes that
                        // are relevant to the current bug are covered; anything else
                        // returns null and the pattern binding is left unresolved (safe:
                        // dispatch just doesn't get an override).
                        when (scrutinee.constructor) {
                            "Result" -> when (pattern.tag) {
                                "Ok"  -> scrutinee.args.getOrNull(0)?.let { MezzeType.RecordType(linkedMapOf("val" to it)) }
                                "Err" -> scrutinee.args.getOrNull(1)?.let { MezzeType.RecordType(linkedMapOf("error" to it)) }
                                else -> null
                            }
                            "Option" -> when (pattern.tag) {
                                "Some" -> scrutinee.args.getOrNull(0)?.let { MezzeType.RecordType(linkedMapOf("val" to it)) }
                                else -> null
                            }
                            else -> null
                        }
                    }
                    else -> null
                }
                if (payloadType != null && pattern.payload != null) {
                    readPatternBindings(pattern.payload as TypedPattern, payloadType, out)
                }
            }
            is TypedRecordPattern -> {
                val fields = (scrutinee as? MezzeType.RecordType)?.fields ?: return
                for (fp in pattern.fields) {
                    val fieldType = fields[fp.name] ?: continue
                    val sub = fp.pattern
                    if (sub != null) readPatternBindings(sub, fieldType, out)
                    else if (fieldType !is VarType && !containsVarTypeDeep(fieldType)) out[fp.name] = fieldType
                }
            }
            else -> {}
        }
    }

    /** Element-first expansion: for each container type, its element precedes it, so
     *  dict resolution prefers the ELEMENT impl over the container impl. */
    private fun elementFirst(types: List<MezzeType>): List<MezzeType> = types.flatMap { t ->
        val e = containerElementType(t)
        if (e != null && e !is VarType) listOf(e, t) else listOf(t)
    }

    /** Last-resort dict resolution inside a mono-spec compile (see [specTypeArgs]). */
    private fun resolveDictFromSpecTypeArgs(methodName: String, src: SourceInfo): NodeIR? {
        val args = specTypeArgs ?: return null
        val deeper = elementFirst(args)
        val opHash = resolveOpHashFromConcreteTypes(methodName, deeper) ?: return null
        return refToDictedOp(opHash, deeper, src)
    }

    /** Hash for an extracted lambda's FunctionIR. TypedASTHasher.hashExpr is
     *  type-blind, so the SAME source lambda inside a generic body and inside each
     *  of its mono specializations would collide on one hash while compiling to
     *  DIFFERENT FunctionIRs (capture lists and dispatch differ once types are
     *  pinned) — last registration wins and other callers run the wrong frame
     *  layout. Salting with the enclosing spec hash keeps every version distinct;
     *  outside spec compilation the plain hash is unchanged (stable stdlib
     *  prelowering, recursive-let self-references, closure keying). */
    private fun saltedLambdaHash(expr: TypedExpr, dictCaptures: List<Pair<String, Int>> = emptyList()): Hash {
        val base = com.mezze.language.typedast.TypedASTHasher.hashExpr(expr)
        val salt = specLambdaSalt
        // NOT salted by the enclosing function, though `sorted_set.mz` holds eight
        // textually identical `{ a, b } -> if a < b …` comparators (singleton,
        // from_list, add, remove, contains, min_by, max_by, from_seq) that all
        // collide on one hash. Folding `currentFunctionHash` in separates them,
        // but it also ties a nested lambda's identity to its declaration's hash,
        // and adding a `newtype` changes that hash — so two programs identical
        // after newtype erasure emit different IR, which NewtypeErasureIRTest
        // rightly rejects. Tried for BUG 7 and reverted: it separated the eight
        // and changed no behaviour, so the collision is not what picks the wrong
        // comparator.
        if (salt == null && dictCaptures.isEmpty()) return base
        val b = com.mezze.language.ast.hashing.MezzeHashBuilder()
        b.append("spec_lambda")
        b.append(base.hex)
        if (salt != null) b.append(salt.hex)
        // The expr hash is type-blind, so the capture LIST has to be folded in:
        // two textually identical comparators that capture different dicts are
        // different functions, and without this they collide onto one registry
        // entry and last-registration-wins runs the wrong one — the #106 collision,
        // environment flavor. See SeqKeyOrdTest.
        //
        // The names, NOT the parent frame slot they are read from. The slot is where
        // the PARENT keeps the dict; it appears in the parent's MakeLambda capture
        // list, never in this lambda's body or its own capture names. Folding it in
        // gave one lambda several identities for a difference it does not contain —
        // over-separation, and the opposite of the stated rule that identity comes
        // from what the lambda IS. Measured on removal: zero change across the
        // interpreter suite (6242 tests), the typechecker suite, SeqKeyOrdTest which
        // is the test the slot was added for, and DedupeCollisionGateTest, which
        // would fail if two differing functions started sharing a hash.
        for ((name, _) in dictCaptures) {
            b.append(name)
        }
        return Hash(b.build().toFullHexString())
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
            // Raw `method@var` keys resolve to whichever slot the PARENT holds for
            // that (method, var) — per-var when it has one, else the plain one.
            // C.5: no unqualified-name fallback. This used to drop the type var
            // and retry under the bare `__dict_<method>` name when the per-var
            // lookup missed — a name-keyed guess of exactly the kind C.2
            // replaced. Measured dead: zero hits across a full forced rerun of
            // the interpreter suite (8m38s, no caching), because
            // `enclosingDictSlotIn` already matches an ability-qualified slot for
            // an unqualified request. A dict we cannot locate is not captured.
            val dictName = enclosingDictSlotIn(
                parentParamNames,
                parentCaptureNames,
                com.mezze.language.ast.MagicParams.methodOfKey(method),
                com.mezze.language.ast.MagicParams.typeVarOfKey(method),
                // S-11: without the ability segment this misses the exact per-variable
                // slot, and the PREFIX scan below answers with whichever comes first —
                // two keys from one lambda collapsing onto ONE captured dictionary.
                com.mezze.language.ast.MagicParams.abilityOfKey(method),
            ) ?: return@mapNotNull null
            val parentParamIdx = parentParamNames.indexOf(dictName)
            if (parentParamIdx >= 0) {
                dictName to (parentCaptureCount + parentParamIdx)
            } else {
                val parentCaptureIdx = parentCaptureNames[dictName]
                if (parentCaptureIdx != null) dictName to parentCaptureIdx else null
            }
        }.distinctBy { it.first }
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
        val lambdaHash = saltedLambdaHash(expr, dictsToCapture)
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
        // POSITIONAL: the lambda reads capture i at frame slot i, so this list must
        // stay aligned with `expr.captures`. `mapNotNull` used to sit here, and a
        // single unresolvable name silently shifted every LATER capture down one —
        // every one of them then reading its neighbour's value. Measured as not
        // firing today, which is exactly why it must fail loudly rather than keep
        // waiting: a capture the typechecker recorded and the emitter cannot place
        // is a defect in one of them, never something to skip.
        val captures = expr.captures.map { c ->
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
                } else {
                    throw IllegalStateException(
                        "lambda captures '${c.name}', which is neither a local, a parameter nor a " +
                            "capture of the enclosing function. Enclosing locals=${localsByName.keys}, " +
                            "params=$parentParamNames, captures=${parentCaptureNames.keys}. " +
                            "Dropping it would shift every later capture's slot.",
                    )
                }
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
        val savedSlotConcreteTypes = slotConcreteTypes.toMap()
        val stmts = expr.statements.map { stmt ->
            when (stmt) {
                is TypedStatement.Let -> {
                    val slot = forceAllocLocal(stmt.name)
                    val stmtValue = stmt.value
                    // Recover a concrete type structurally when the solver left
                    // `stmt.type` as a bare VarType (do-block let over an
                    // under-solved match, etc.). See `slotConcreteTypes`. Only
                    // populate when the annotated type isn't already concrete.
                    if (stmt.type is VarType || containsVarTypeDeep(stmt.type)) {
                        deriveConcreteType(stmtValue)?.let { slotConcreteTypes[slot] = it }
                    }

                    // RECURSIVE LET REGISTRATION: If this is a recursive let-bound lambda,
                    // pre-compute its hash and register so compileVar can resolve self-references
                    // to MakeLambdaIR(self_hash, self_captures) instead of ReadCaptureIR.
                    if (stmt.isRecursive && stmtValue is TypedLambda) {
                        val lambdaHash = saltedLambdaHash(stmtValue)
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
        slotConcreteTypes.clear()
        slotConcreteTypes.putAll(savedSlotConcreteTypes)
        return BlockIR(stmts, resultIR, src)
    }


    private fun tryResolveAbilityOp(
        methodName: String,
        receiverType: MezzeType,
        argRecord: TypedExpr? = null,
        /** BUG 9: the ability the TYPECHECKER resolved for this call site, when
         *  it recorded one. The op index is keyed on the operation NAME, so two
         *  abilities declaring the same name share a bucket; without this the
         *  receiver alone decided, and a type implementing both got whichever
         *  impl was registered first for every call — `Weigh.rank {…} +
         *  Price.rank {…}` returned 6 instead of 106, silently. Narrowing by a
         *  DECISION ALREADY MADE is not name dispatch; it is the opposite, and
         *  it is what C.3 generalizes. */
        abilityHash: Hash? = null,
    ): Hash? {
        // A receiver the compiler never determined resolves NOTHING here.
        // `typeMatchesTarget` answers true for a type variable against any target,
        // so with a placeholder every candidate "matches" and `firstOrNull()` below
        // is a coin flip — that is how a VARIANT `==` came to be answered by `Eq
        // Int`'s operation. The caller's next step, `tryResolveAbilityOpByArgs`,
        // exists for exactly this case and reads the ARGUMENT types instead, which
        // are real.
        //
        // Only a PLACEHOLDER is refused (TypeVar.isPlaceholder). A genuine type
        // variable still resolves as before: that is polymorphism the caller's
        // evidence discharges, and refusing it breaks a user `Ord`/`Eq` on a
        // container element.
        if (receiverType.holdsUnknownPlaceholder()) return null
        val effectiveType = receiverType
        val allCandidates = abilityOpIndex[methodName] ?: return null
        val candidates =
            if (abilityHash != null) allCandidates.filter { it.abilityHash == abilityHash }
                .ifEmpty { allCandidates }
            else allCandidates
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

    // Task F: THE receiver-vs-target rule now lives in one place. This was a
    // 139-line near-copy of mono's, each commented as "mirrors the other" and
    // each carrying arms the other lacked — the compiler had the Bool-variant
    // normalisation, the newtype arms and this declared-variant lookup; mono had
    // the VariantType-vs-VariantType subset rule. The survivor is their union.
    internal fun typeMatchesTarget(concreteType: MezzeType, targetType: MezzeType): Boolean =
        com.mezze.language.typechecker.mono.typeMatchesTarget(
            concreteType,
            targetType,
            ::variantDeclHasConstructors,
        )

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
    internal fun forceAllocLocal(name: String): Int {
        val slot = nextLocalSlot++
        localsByName[name] = slot  // overwrites existing entry
        slotToNameForCurrentFn.putIfAbsent(slot, name)
        return slot
    }

    internal fun typeToSlotKind(type: MezzeType): SlotKind = when {
        type is IntType -> SlotKind.LONG
        type is FloatType -> SlotKind.DOUBLE
        // Bool is a VARIANT in the type system and a primitive boolean at runtime.
        // This is the one place those two facts meet, so it is a guard rather than
        // an `is` arm — there is no `BoolType` to match on any more.
        PrimitiveTypes.isBoolVariant(type) -> SlotKind.BOOLEAN
        // Newtype is compile-time only — runtime slot follows wrapped type. Nat
        // (`newtype Nat = Int`) picks up LONG here just like a raw Int.
        type is NewtypeType -> typeToSlotKind(type.wrappedType)
        else -> SlotKind.OBJECT
    }

    companion object {
        @JvmStatic
        fun computeOpHash(implHash: Hash, opName: String): Hash = Hash.computeOpHash(implHash, opName)

        /** Switched on by `DedupeCollisionGateTest`; off in production because it
         *  compares whole IR bodies. PER THREAD: the suite runs test classes in
         *  parallel, so a global switch would record every other test's compiles
         *  into whichever gate happened to be running. */
        private val recordCollisions = ThreadLocal.withInitial { false }

        /** Hashes whose per-hash dedupe discarded a body DIFFERENT from the one it
         *  kept — i.e. where content addressing did not hold. Per thread, for the
         *  same reason. */
        private val collisions = ThreadLocal.withInitial { LinkedHashSet<String>() }

        var recordDedupeCollisions: Boolean
            get() = recordCollisions.get()
            set(v) = recordCollisions.set(v)

        val dedupeCollisions: MutableSet<String> get() = collisions.get()
    }

    internal fun locToSourceInfo(loc: Loc): SourceInfo {
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
    /** Declared type name → its type-parameter names, positional (`Box 'a` → [`'a`]). */
    private var typeDeclParams: Map<String, List<String>> = emptyMap()
    fun setPrintTypeDefs(defs: Map<String, MezzeType>, params: Map<String, List<String>> = emptyMap()) {
        printTypeDefs = defs
        typeDeclParams = params
    }

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
    internal fun resolveVariantReceiverType(node: TypedExpr, currentType: MezzeType): MezzeType? {
        if (currentType !is VarType) return null
        if (node !is com.mezze.language.typedast.TypedVariantConstruct) return null
        val payloadType = node.payload?.let { substitution.apply(it.type) }
        variantTypeByConstructor[node.tag]?.let { declared ->
            // A payload-carrying construct binds the declared type's params from
            // the ACTUAL payload (`Wrap { p = P }` on `type W 'a = | Wrap { p: 'a }`
            // → `W P`), so the impl selected for the receiver can also select its
            // ELEMENT dict — a bare `W 'a` picks the impl but leaves `Eq 'a` unbound.
            val declaredPayload = declared.constructors[node.tag]
            if (payloadType == null || declaredPayload == null) return declared
            val bindings = HashMap<String, MezzeType>()
            harvestVarBindings(declaredPayload, payloadType, bindings)
            return if (bindings.isEmpty()) declared
                else com.mezze.language.typechecker.solving.substituteTypeParamsByName(declared, bindings)
        }
        // Option / Result are compiler-known synthetic variants with no typed decl
        // behind printTypeDefs: rebuild the nominal application, binding the param
        // the constructed payload fixes and leaving the other fresh.
        fun payloadField(name: String): MezzeType =
            (payloadType as? RecordType)?.fields?.get(name) ?: VarType(TypeVar.freshInternal("a"))
        return when (node.tag) {
            com.mezze.language.ast.BuiltinTypes.SOME ->
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(com.mezze.language.ast.BuiltinTypes.OPTION, listOf(payloadField(com.mezze.language.ast.BuiltinTypes.VAL_FIELD)))
            com.mezze.language.ast.BuiltinTypes.NONE ->
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(com.mezze.language.ast.BuiltinTypes.OPTION, listOf(VarType(TypeVar.freshInternal("a"))))
            com.mezze.language.ast.BuiltinTypes.OK ->
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(com.mezze.language.ast.BuiltinTypes.RESULT, listOf(payloadField(com.mezze.language.ast.BuiltinTypes.VAL_FIELD), VarType(TypeVar.freshInternal("e"))))
            com.mezze.language.ast.BuiltinTypes.ERR ->
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(com.mezze.language.ast.BuiltinTypes.RESULT, listOf(VarType(TypeVar.freshInternal("a")), payloadField(com.mezze.language.ast.BuiltinTypes.ERROR_FIELD)))
            else -> null
        }
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
    internal fun extractConcreteElementTypeFromExpr(expr: TypedExpr): MezzeType? {
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
            // Nested seq literal whose OWN element type is laundered (e.g.
            // `[[Low]]` — the inner `[Low]`'s element is an orphan VarType, #13):
            // recurse into the inner literal and rebuild the concrete container
            // type, so `[[Low]]` recovers `[Sev]` and each nesting level's
            // dict resolves against the right element impl.
            // B.1, matchers-first: ask whether the element type IS a sequence in
            // either representation, not whether it is the dedicated node. A
            // name-carried `TypeApplication("Seq", [T])` with a concrete T is a
            // perfectly usable element type; `!is SeqType` called it unusable and
            // rebuilt it, discarding the application's identity on the way.
            val elemSeqElement =
                com.mezze.language.ast.BuiltinTypes.sequenceElementType(elemType)
            if (element is com.mezze.language.typedast.TypedSeq &&
                (elemSeqElement == null || elemSeqElement is VarType)) {
                extractConcreteElementTypeFromExpr(element)?.let { return com.mezze.language.ast.BuiltinTypes.seqApp(it) }
            }
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
    internal fun collectDeepConcreteTypes(expr: TypedExpr, out: MutableList<MezzeType>) {
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

        DebugLogger.debug(DebugLogger.Category.Compiler) { "[inline] HIT depth=$depth: ${callee.name} (hash=${targetHash.hex.take(8)}) args=${args.size}" }
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
