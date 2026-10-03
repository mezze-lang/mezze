package com.mezze.language.typechecker.emit

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*

/**
 * Per-call-site decoder resolution for `from_json` / `from_json_with`.
 *
 * A `from_json` call returns `Result 'a DecodeError`, and `'a` — the shape the
 * program decodes into — is inferred purely from USAGE (the fields the code
 * reads off the decoded value). That target NEVER lands on the call node or in
 * the solve substitution: it must stay a bare var there, or the `Decode` ability
 * check rejects it and codegen reads a record where the input is a string. So
 * two `from_json` calls of DIFFERENT shapes in one scope both carry `Result 'a`,
 * and codegen — which resolves the inner `.decode` dictionary from the call's
 * concrete types — cannot tell their decoders apart and wires the SECOND to the
 * FIRST's decoder (crash: `Field not found: decode`).
 *
 * This pass recovers each call's concrete target the SAME way hover does —
 * structurally, from the field accesses on the callback binder the decoded value
 * flows into — WITHOUT touching the solve. It matches that target against the
 * registered auto-derived codec impls and records `callLoc -> decode op hash`,
 * which the compiler consults to wire the right decoder per call site.
 */
object DecodeTargetResolver {

    /**
     * An auto-derived codec decoder impl, identified by ABILITY CONTENT HASH
     * (WellKnownHashConstants — the generated stdlib name→hash table is the
     * single sanctioned name binding). No op-name or shape matching: a user
     * ability that happens to declare a `decode` op never qualifies.
     */
    fun isCodecDecodeImpl(impl: TypedAbilityImpl): Boolean =
        com.mezze.language.typechecker.WellKnownHashConstants
            .isDecodeFlavoredCodec(impl.abilityHash)

    /** The derived decoder impl's op key — data read from the impl, never a
     *  literal. Codec generation emits exactly one operation per decode impl. */
    fun decodeOpName(impl: TypedAbilityImpl): String = impl.operations.keys.first()

    /**
     * Decode ENTRY-POINT operations: ops (of any impl in scope) whose own
     * signature carries a `with <decode-flavored ability> …` constraint —
     * stdlib `from_json`/`from_json_with` (`with Decode JsonC 'a`), or a user
     * codec's own entry op (e.g. `from_csv` constrained on a decode-flavored
     * ability). Keyed by the constraint's ABILITY HASH; the returned op-name
     * set is derived data used only to spot candidate call sites
     * pre-resolution (the receiver is still a bare var there — nothing else
     * identifies the call yet). Also returns the entry ops' owning ability
     * hashes, which feed the constraint-target filter in [resolveDecl].
     */
    fun entryPointOps(
        impls: List<TypedAbilityImpl>,
        lets: List<TypedLetDecl> = emptyList(),
    ): Pair<Set<String>, Set<Hash>> {
        val names = HashSet<String>()
        val owningAbilityHashes = HashSet<Hash>()
        // Entry LET decls (e.g. http's `from_json_result` — `with FromJson …`)
        // and entry op hashes, so WRAPPER ops whose bodies CALL an entry decl
        // by hash are entries too (http's inherent `from_json` on
        // Result Response has no written constraint of its own).
        val entryCalleeHashes = HashSet<Hash>()
        fun constraintIsEntry(c: MezzeType.FunctionAbilityConstraint): Boolean =
            com.mezze.language.typechecker.WellKnownHashConstants
                .isDecodeFlavoredCodec(c.abilityHash) ||
                (c.abilityHash != null && c.abilityHash in owningAbilityHashes)
        fun bodyCallsEntry(e: TypedExpr): Boolean {
            if (e is TypedCall) {
                val f = e.function
                if (f is TypedVar) {
                    val b = f.binding
                    if (b is BindingRef.ByHash && b.hash in entryCalleeHashes) return true
                }
                if (f is TypedFieldAccess && f.field in names) return true
            }
            return children(e).any(::bodyCallsEntry)
        }
        // Level 0: the decode-ENTRY abilities' ops — GENERATED data from the
        // stdlib ability declarations (WellKnownHashConstants), needed because
        // stdlib typed modules are snapshot-loaded and their impls are not in
        // scope here. Impls of an entry ability found in scope also contribute
        // (covers locally-visible entry abilities the same way).
        names.addAll(com.mezze.language.typechecker.WellKnownHashConstants.decodeEntryOpNames)
        owningAbilityHashes.addAll(
            com.mezze.language.typechecker.WellKnownHashConstants.decodeEntryAbilityHashes)
        for (impl in impls) {
            if (com.mezze.language.typechecker.WellKnownHashConstants
                    .isDecodeEntryAbility(impl.abilityHash)) {
                names.addAll(impl.operations.keys)
                owningAbilityHashes.add(impl.abilityHash)
            }
        }
        // Fixpoint: each round can promote lets (by constraint) and impl ops
        // (by constraint, or by body-calling an already-known entry by hash).
        var changed = true
        while (changed) {
            changed = false
            for (decl in lets) {
                if (decl.hash in entryCalleeHashes) continue
                val cs = (decl.scheme.type as? MezzeType.FunctionType)?.abilityConstraints ?: continue
                if (cs.any(::constraintIsEntry)) {
                    entryCalleeHashes.add(decl.hash); changed = true
                }
            }
            for (impl in impls) {
                for ((opName, opBody) in impl.operations) {
                    val opHash = Hash.computeOpHash(impl.hash, opName)
                    if (opHash in entryCalleeHashes) continue
                    val cs = (opBody.type as? MezzeType.FunctionType)?.abilityConstraints
                    val isEntry = (cs?.any(::constraintIsEntry) == true) || bodyCallsEntry(opBody)
                    if (isEntry) {
                        names.add(opName)
                        owningAbilityHashes.add(impl.abilityHash)
                        entryCalleeHashes.add(opHash)
                        changed = true
                    }
                }
            }
        }
        return names to owningAbilityHashes
    }

    data class Result(val module: TypedModule, val decodeOpBySite: Map<Loc, Hash>)

    /**
     * Resolve each `from_json` call's decoder (by usage-inferred target), then
     * (1) STAMP the call's instantiation with the decoder's AbilityImplRef so two
     * structurally-identical decode callbacks hash distinctly and are not deduped
     * onto one decoder, and (2) return `decodeOpBySite` for the compiler's dict
     * wiring.
     */
    fun process(module: TypedModule, importedModules: List<TypedModule> = emptyList()): Result {
        val decodeImpls = module.abilityImpls.filter(::isCodecDecodeImpl)
        if (decodeImpls.isEmpty()) return Result(module, emptyMap())
        // Entry-point ops derive from every impl AND let in scope — the stdlib
        // FromJson-for-Str impl and wrapper lets (http's from_json_result) live
        // in IMPORTED modules, not the user module.
        val scopeImpls = module.abilityImpls + importedModules.flatMap { it.abilityImpls }
        val scopeLets = (module.decls + importedModules.flatMap { it.decls })
            .filterIsInstance<TypedLetDecl>()
        val (entryOps, entryAbilityHashes) = entryPointOps(scopeImpls, scopeLets)
        if (entryOps.isEmpty()) return Result(module, emptyMap())

        val opBySite = HashMap<Loc, Hash>()
        val refBySite = HashMap<Loc, AbilityImplRef>()
        for (decl in module.decls) {
            if (decl is TypedLetDecl) resolveDecl(decl, decodeImpls, entryOps, entryAbilityHashes, opBySite, refBySite)
        }
        if (opBySite.isEmpty()) return Result(module, emptyMap())
        val stamped = module.copy(decls = module.decls.map { d ->
            if (d is TypedLetDecl) d.copy(value = stampExpr(d.value, refBySite)) else d
        })
        return Result(stamped, opBySite)
    }

    /**
     * Cross-module `from_json` wrapper wiring.
     *
     * A generic wrapper like `decw : … with FromJson 'a Str -> Result 'a E`
     * lives in module A but is called with a concrete target from module B. The
     * `from_json` call inside decw's body is compiled as part of B's compilation
     * (decw is an imported module), yet [process] only walked B's OWN decls — so
     * decw's from_json call is never mapped to B's derived decoder and degrades to
     * a raw field read (runtime crash).
     *
     * Walk the IMPORTED modules' decls with B's derived [decodeImpls] and return
     * the extra `callLoc -> decode op hash` entries. Same resolution as [process]
     * (usage/constraint reconstruction + the single-decoder fast path); no
     * stamping needed — the compiler consults the returned map by call Loc when it
     * compiles the imported bodies. Only fires when B actually derived a decoder
     * (`decodeImpls` non-empty), so codec-free imports are untouched.
     */
    fun processImportedCalls(
        importedModules: List<TypedModule>,
        decodeImpls: List<TypedAbilityImpl>,
    ): Map<Loc, Hash> {
        if (decodeImpls.isEmpty() || importedModules.isEmpty()) return emptyMap()
        val (entryOps, entryAbilityHashes) = entryPointOps(
            importedModules.flatMap { it.abilityImpls } + decodeImpls,
            importedModules.flatMap { it.decls }.filterIsInstance<TypedLetDecl>(),
        )
        if (entryOps.isEmpty()) return emptyMap()
        val opBySite = HashMap<Loc, Hash>()
        val refSink = HashMap<Loc, AbilityImplRef>()
        for (m in importedModules) {
            for (decl in m.decls) {
                if (decl is TypedLetDecl) resolveDecl(decl, decodeImpls, entryOps, entryAbilityHashes, opBySite, refSink)
            }
        }
        return opBySite
    }

    private fun stampExpr(e: TypedExpr, refBySite: Map<Loc, AbilityImplRef>): TypedExpr {
        val rebuilt = withChildren(e) { stampExpr(it, refBySite) }
        // refBySite is keyed by the entry-point call Locs collected in
        // resolveDecl — membership IS the identification; no name check.
        if (rebuilt is TypedCall && refBySite.containsKey(rebuilt.loc)) {
            refBySite[rebuilt.loc]?.let { ref ->
                val inst = rebuilt.instantiation ?: TypeInstantiation()
                return rebuilt.copy(instantiation = inst.copy(abilityImpls = inst.abilityImpls + ref))
            }
        }
        return rebuilt
    }

    /**
     * Within one decl: each `from_json` call feeds exactly one downstream
     * callback that reads the decoded value's fields. Collect both in the same
     * pre-order walk and pair by position — decode chains are linear, so the Nth
     * `from_json` corresponds to the Nth value-reading callback. Reconstruct each
     * callback binder's record (the target) and map the call to the matching
     * derived decoder.
     */
    private fun resolveDecl(
        decl: TypedLetDecl,
        decodeImpls: List<TypedAbilityImpl>,
        entryOps: Set<String>,
        entryAbilityHashes: Set<Hash>,
        out: MutableMap<Loc, Hash>,
        refOut: MutableMap<Loc, AbilityImplRef>,
    ) {
        val root = decl.value
        // Entry-point call sites (from_json / a user codec's entry op), in
        // pre-order. `entryOps` is DERIVED data (ops constrained on a
        // decode-flavored ability) — see [entryPointOps]; the receiver is a
        // bare var at these sites, so the derived op-name set is the only
        // available pre-resolution signal.
        val entryLocs = ArrayList<Loc>()
        // A concrete decode target per call, from usage reconstruction — its leaf
        // types may be `?` and it does NOT see through list elements.
        val reconTargets = ArrayList<MezzeType>()
        run {
            fun go(e: TypedExpr) {
                if (e is TypedCall && e.function.let { it is TypedFieldAccess && it.field in entryOps }) entryLocs.add(e.loc)
                if (e is TypedLambda && e.params.isNotEmpty())
                    reconstructBinder(e.body, e.params.first().name, 0)?.let { reconTargets.add(it) }
                for (c in children(e)) go(c)
            }
            go(root)
        }
        if (entryLocs.isEmpty()) return

        // Fast path: exactly one derived decoder in scope — every entry call
        // here decodes into it. No reconstruction needed, and it is immune to
        // access patterns reconstruction can't see through (`list.first {}`, `match`).
        if (decodeImpls.size == 1) {
            val impl = decodeImpls[0]
            val opHash = Hash.computeOpHash(impl.hash, decodeOpName(impl))
            val ref = AbilityImplRef(impl.abilityHash, impl.hash, impl.targetTypes[1])
            for (loc in entryLocs) { out[loc] = opHash; refOut[loc] = ref }
            return
        }

        // AUTHORITATIVE targets: the entry/decode constraints the typechecker
        // resolved onto this decl's function type. These are FULLY concrete —
        // list elements and all — unlike usage reconstruction. Present only when
        // the decode surfaces on the signature (not a fully-discharged do-block
        // local), so we fall back to recon for the rest.
        // Constraint membership is by ABILITY HASH: either a decode-flavored
        // codec ability, or the entry ops' own owning ability (FromJson-style
        // wrapper abilities) — never by ability NAME.
        val decodeAbilityHashes = decodeImpls.map { it.abilityHash }.toSet()
        val constraintTargets = ArrayList<MezzeType>()
        (decl.scheme.type as? MezzeType.FunctionType)?.abilityConstraints
            ?.filter {
                it.abilityHash != null &&
                    (it.abilityHash in decodeAbilityHashes || it.abilityHash in entryAbilityHashes)
            }
            ?.forEach { c -> c.typeArgs.firstOrNull()?.let { constraintTargets.add(it) } }

        // AUTHORITATIVE per-call target read straight off the Ok-arm binder (robust
        // to == / arg / match usage). Positional constraint/recon targets are the
        // fallback, each used only when its count lines up with the calls.
        val directTargets = directTargetsByCall(root, entryOps)
        val constraintAligned = constraintTargets.size == entryLocs.size
        val reconAligned = reconTargets.size == entryLocs.size

        for (i in entryLocs.indices) {
            val target = directTargets[entryLocs[i]]
                ?: (if (constraintAligned) constraintTargets[i] else null)
                ?: (if (reconAligned) reconTargets[i] else null)
                ?: continue  // no trustworthy target for this call — leave it to the solver
            val matches = decodeImpls.filter { shapeMatches(it.targetTypes[1], target) }
            val impl = (if (matches.size == 1) matches[0]
                        else matches.firstOrNull { structural(it.targetTypes[1]) == structural(target) }) ?: continue
            out[entryLocs[i]] = Hash.computeOpHash(impl.hash, decodeOpName(impl))
            refOut[entryLocs[i]] = AbilityImplRef(impl.abilityHash, impl.hash, impl.targetTypes[1])
        }
    }

    /**
     * AUTHORITATIVE per-site targets, read straight off the typed AST — robust to
     * any usage (`c == original`, `size { s = c }`, nested `match`) that the
     * field-access reconstruction below cannot see through.
     *
     * A `from_json` result consumed by `match … is Ok { val = c } -> …` binds `c`
     * in the Ok arm's pattern, and `TypedVarPattern` carries `c`'s RESOLVED type.
     * When that type is fully concrete (the solve settled it from the arm's usage),
     * it IS the decode target — map the entry call's Loc to it directly. Without
     * this, a second decode of a different shape fell back to the first decoder in
     * scope (the field-access recon returns nothing for `==`/arg usage).
     */
    private fun directTargetsByCall(root: TypedExpr, entryOps: Set<String>): Map<Loc, MezzeType> {
        val out = HashMap<Loc, MezzeType>()
        fun go(e: TypedExpr) {
            if (e is TypedMatch) {
                val scrut = e.scrutinee
                if (scrut is TypedCall && scrut.function.let { it is TypedFieldAccess && it.field in entryOps }) {
                    // The decoded value is bound in the Ok arm (its binding stays a
                    // bare var, so read the TARGET from how the arm USES it).
                    for (case in e.cases) {
                        val t = case.bindings.firstNotNullOfOrNull { b -> targetFromUsage(case.body, b.name) }
                        if (t != null && isConcreteTarget(t)) { out[scrut.loc] = t; break }
                    }
                }
            }
            for (c in children(e)) go(c)
        }
        go(root)
        return out
    }

    /**
     * Recover the decode target of the value bound to [binder] from its usage in
     * [body], WITHOUT the solve substitution (which keeps it a bare var):
     *   - `binder == expr` — desugars to `binder.eq { r = expr }`, so the RHS's
     *     resolved type IS the target (the two `==` operands share one type).
     *   - else the CLOSED record of field accesses `binder.field` (hover's way).
     */
    private fun targetFromUsage(body: TypedExpr, binder: String): MezzeType? {
        var eqRhs: MezzeType? = null
        fun scan(e: TypedExpr) {
            if (eqRhs != null) return
            // `binder == expr` desugars to the `eq` op applied to a record carrying
            // BOTH operands (`{ it = binder, r = expr }`). `==` forces both operands to
            // ONE type (Eq 'a), so the OTHER operand's resolved type IS the binder's.
            // Restricted to `eq` — a general call's args do NOT share a type
            // (`set::contains { s = c, x = 1 }`).
            if (e is TypedCall) {
                val fn = e.function
                val isEq = (fn is TypedFieldAccess && fn.field == BuiltinTypes.EQ_METHOD) ||
                    (fn is TypedVar && fn.name == BuiltinTypes.EQ_METHOD)
                val arg = e.arg
                if (isEq && arg is TypedRecord) {
                    val fields = arg.entries.filterIsInstance<TypedRecordEntry.Field>()
                    val usesBinder = fields.any { it.value.let { v -> v is TypedVar && v.name == binder } } ||
                        (fn is TypedFieldAccess && fn.record.let { it is TypedVar && it.name == binder })
                    if (usesBinder) {
                        val other = fields.firstNotNullOfOrNull { f ->
                            f.value.takeIf { it !is TypedVar || it.name != binder }
                                ?.type?.takeIf { t -> isConcreteTarget(t) }
                        }
                        if (other != null) { eqRhs = other; return }
                    }
                }
            }
            for (c in children(e)) scan(c)
        }
        scan(body)
        return eqRhs ?: reconstructBinder(body, binder, 0)
    }

    /** No unresolved type variable anywhere — a target we can trust to pin a decoder. */
    private fun isConcreteTarget(t: MezzeType): Boolean = !structural(t).contains("?")

    /**
     * The declared NAME of a type when it has one and carries no distinguishing type
     * args — so the nominal impl target (`TypeApplication("P", [])`) equates with the
     * structural usage target (a `RecordType`/`NewtypeType` named `P`). Null for
     * parametric applications (args compared instead) and anonymous shapes.
     *
     * NOT `TypeNames.head`: that returns the head name ignoring args, so `Map Int Int`
     * and `Map Str Str` both answer `"Map"` and would falsely equate two different
     * parametric decoders. This reuses `identity.displayNameOrNull()` but gates the
     * arg-less case so the name-match only fires where it is unambiguous.
     */
    private fun nominalName(t: MezzeType): String? = when (t) {
        is MezzeType.TypeApplication -> if (t.args.isEmpty()) t.constructor else null
        is MezzeType.RecordType -> t.identity.displayNameOrNull()
        is MezzeType.VariantType -> if (BuiltinTypes.isBoolVariant(t)) null else t.identity.displayNameOrNull()
        is MezzeType.NewtypeType -> t.identity.displayNameOrNull() ?: t.name
        else -> null
    }

    /** Reconstruct a CLOSED record from field accesses on the var named [binder]. */
    private fun reconstructBinder(root: TypedExpr, binder: String, depth: Int): MezzeType? {
        if (depth > 8) return null
        // A method call `x.m {…}` is a TypedCall whose FUNCTION is a
        // TypedFieldAccess `x.m`. Those are not data-field reads and must not
        // become record fields (`val.from_json {}` would spuriously add a
        // `from_json` field). Collect the field-access nodes that sit in a call's
        // function position, and exclude them.
        val methodAccesses = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TypedFieldAccess, Boolean>())
        run {
            fun mark(e: TypedExpr) {
                if (e is TypedCall) { val fn = e.function; if (fn is TypedFieldAccess) methodAccesses.add(fn) }
                for (c in children(e)) mark(c)
            }
            mark(root)
        }
        // Collect DATA field accesses whose receiver is a TypedVar named `binder`.
        val fields = LinkedHashMap<String, MezzeType>()
        fun scan(e: TypedExpr) {
            if (e is TypedFieldAccess && e !in methodAccesses) {
                val r = e.record
                if (r is TypedVar && r.name == binder) {
                    fields.putIfAbsent(e.field, deepen(root, e, depth))
                }
            }
            for (c in children(e)) scan(c)
        }
        scan(root)
        return if (fields.isEmpty()) null else MezzeType.RecordType(fields, null)
    }

    // A field access node may itself be a receiver of deeper accesses (a.b.c);
    // reconstruct the nested record when this node's own type is still a var.
    private fun deepen(root: TypedExpr, node: TypedFieldAccess, depth: Int): MezzeType {
        val ft = node.type
        if (ft !is MezzeType.VarType) return ft
        val nested = LinkedHashMap<String, MezzeType>()
        fun scan(e: TypedExpr) {
            if (e is TypedFieldAccess && e.record === node) nested.putIfAbsent(e.field, deepen(root, e, depth + 1))
            for (c in children(e)) scan(c)
        }
        if (depth < 8) scan(root)
        return if (nested.isEmpty()) ft else MezzeType.RecordType(nested, null)
    }

    // Structural match where a VarType (`?`) on EITHER side is a wildcard. The
    // reconstructed target's leaf types can be unresolved; field names + nesting
    // still pin the decoder.
    private fun shapeMatches(impl: MezzeType, target: MezzeType): Boolean {
        if (impl is MezzeType.VarType || target is MezzeType.VarType) return true
        // A DECLARED type reaches us in two representations: the impl target is
        // NOMINAL (`mezzeTypeToParsedAnnotation` names it — `P`, `Nat`), while the
        // usage-reconstructed target is STRUCTURAL (`{label,x}`, `newtype Nat(Int)`).
        // Match them by nominal name — but only for the ARG-LESS case, so parametric
        // containers (`Map Int Int` vs `Map Str Str`) still go through arg comparison.
        val ni = nominalName(impl); val nt = nominalName(target)
        if (ni != null && ni == nt) return true
        return when {
            impl is MezzeType.RecordType && target is MezzeType.RecordType -> {
                if (impl.fields.keys != target.fields.keys) false
                else impl.fields.all { (k, v) -> shapeMatches(v, target.fields.getValue(k)) }
            }
            impl is MezzeType.TypeApplication && target is MezzeType.TypeApplication ->
                impl.constructor == target.constructor && impl.args.size == target.args.size &&
                    impl.args.indices.all { shapeMatches(impl.args[it], target.args[it]) }
            else -> structural(impl) == structural(target)
        }
    }

    /**
     * One spelling per type, so two targets compare equal exactly when they are
     * the same shape.
     *
     * A VariantType used to fall through to `t.javaClass.simpleName`, which is
     * wrong in both directions at once. A Bool in its VARIANT form spelled
     * "VariantType" while a Bool in its BoolType form spelled "Bool", so a
     * `{ flag: Bool }` decode target matched no derived impl, its call site went
     * unwired, and it ran the FIRST decoder in scope — `"{\"flag\":true}"`
     * decoded as the default `False`. And in the other direction every variant
     * spelled "VariantType", so `{ x: Color }` and `{ x: Status }` compared EQUAL
     * and could select each other's decoder. Which of the two fired depended on
     * which representation the solve happened to settle on.
     *
     * The Bool half has an end-to-end regression test
     * (`JsonDecodeExhaustiveTest.threeDifferentShapes`). The variant half cannot
     * be reached through `from_json` today — the derived codec does not decode a
     * user variant from a JSON string — so it is pinned directly by
     * `DecodeTargetStructuralKeyTest` instead of left to whoever adds that path.
     * That is why this is `internal` rather than private.
     */
    internal fun structural(t: MezzeType): String = when (t) {
        is MezzeType.RecordType ->
            "{" + t.fields.toSortedMap().entries.joinToString(",") { "${it.key}:${structural(it.value)}" } + "}"
        is MezzeType.TypeApplication -> t.constructor + "(" + t.args.joinToString(",") { structural(it) } + ")"
        is MezzeType.VariantType ->
            // Bool IS this arm now — it is a builtin variant, not a primitive.
            if (BuiltinTypes.isBoolVariant(t)) "Bool"
            else "|" + t.constructors.toSortedMap().entries.joinToString(",") { (name, payload) ->
                name + (payload?.let { ":" + structural(it) } ?: "")
            } + (if (t.rowVar != null) ",..." else "") + "|"
        // A newtype is NOMINAL: its declaration identity decides, not its shape.
        is MezzeType.NewtypeType -> "newtype " + (t.identity.displayNameOrNull() ?: t.name) +
            "(" + structural(t.wrappedType) + ")"
        is MezzeType.IntType -> "Int"
        is MezzeType.FloatType -> "Float"
        is MezzeType.StringType -> "Str"
        is MezzeType.CharType -> "Char"
        is MezzeType.FracType -> "Frac"
        is MezzeType.BytesType -> "Bytes"
        is MezzeType.VarType -> "?"
        is MezzeType.FunctionType -> "fn(" + structural(t.paramType) + "->" + structural(t.returnType) + ")"
        is MezzeType.EffectRow -> "<row>"
    }

    private fun withChildren(e: TypedExpr, g: (TypedExpr) -> TypedExpr): TypedExpr = when (e) {
        is TypedCall -> e.copy(function = g(e.function), arg = g(e.arg))
        is TypedLambda -> e.copy(body = g(e.body))
        is TypedFieldAccess -> e.copy(record = g(e.record))
        is TypedRecordUpdate -> e.copy(record = g(e.record), updates = e.updates.map { mapEntry(it, g) })
        is TypedRecord -> e.copy(entries = e.entries.map { mapEntry(it, g) })
        is TypedVariantConstruct -> e.payload?.let { p -> e.copy(payload = g(p)) } ?: e
        is TypedMatch -> e.copy(scrutinee = g(e.scrutinee), cases = e.cases.map { it.copy(guard = it.guard?.let(g), body = g(it.body)) })
        is TypedIf -> e.copy(condition = g(e.condition), thenBranch = g(e.thenBranch), elseBranch = g(e.elseBranch))
        is TypedBlock -> e.copy(statements = e.statements.map { mapStmt(it, g) }, result = g(e.result))
        is TypedSeq -> e.copy(elements = e.elements.map { mapSeq(it, g) })
        is TypedPipe -> e.copy(left = g(e.left), right = g(e.right))
        is TypedPerform -> e.copy(body = g(e.body))
        is TypedStringLit -> e.copy(parts = e.parts.map {
            if (it is TypedStringPart.Interpolation) it.copy(expr = g(it.expr)) else it
        })
        else -> e
    }

    private fun mapEntry(entry: TypedRecordEntry, g: (TypedExpr) -> TypedExpr): TypedRecordEntry = when (entry) {
        is TypedRecordEntry.Field -> entry.copy(value = g(entry.value))
        is TypedRecordEntry.Spread -> entry.copy(expr = g(entry.expr))
    }
    private fun mapStmt(stmt: TypedStatement, g: (TypedExpr) -> TypedExpr): TypedStatement = when (stmt) {
        is TypedStatement.Let -> stmt.copy(value = g(stmt.value))
        is TypedStatement.Expr -> stmt.copy(expr = g(stmt.expr))
    }
    private fun mapSeq(el: TypedSeqElement, g: (TypedExpr) -> TypedExpr): TypedSeqElement = when (el) {
        is TypedSeqElement.Regular -> el.copy(expr = g(el.expr))
        is TypedSeqElement.Spread -> el.copy(expr = g(el.expr))
    }

    private fun children(e: TypedExpr): List<TypedExpr> = when (e) {
        is TypedCall -> listOf(e.function, e.arg)
        is TypedLambda -> listOf(e.body)
        is TypedFieldAccess -> listOf(e.record)
        is TypedRecordUpdate -> listOf(e.record) + e.updates.mapNotNull { entryExpr(it) }
        is TypedRecord -> e.entries.mapNotNull { entryExpr(it) }
        is TypedVariantConstruct -> listOfNotNull(e.payload)
        is TypedMatch -> listOf(e.scrutinee) + e.cases.flatMap { listOfNotNull(it.guard, it.body) }
        is TypedIf -> listOf(e.condition, e.thenBranch, e.elseBranch)
        is TypedBlock -> e.statements.mapNotNull { stmtExpr(it) } + e.result
        is TypedSeq -> e.elements.map { seqExpr(it) }
        is TypedPipe -> listOf(e.left, e.right)
        is TypedPerform -> listOf(e.body)
        // A decoded value used only inside a `"…$(c == original)…"` interpolation
        // lives here; without this the target reconstruction never reaches it.
        is TypedStringLit -> e.parts.mapNotNull { (it as? TypedStringPart.Interpolation)?.expr }
        else -> emptyList()
    }

    private fun entryExpr(entry: TypedRecordEntry): TypedExpr? = when (entry) {
        is TypedRecordEntry.Field -> entry.value
        is TypedRecordEntry.Spread -> entry.expr
    }
    private fun stmtExpr(stmt: TypedStatement): TypedExpr? = when (stmt) {
        is TypedStatement.Let -> stmt.value
        is TypedStatement.Expr -> stmt.expr
    }
    private fun seqExpr(el: TypedSeqElement): TypedExpr = when (el) {
        is TypedSeqElement.Regular -> el.expr
        is TypedSeqElement.Spread -> el.expr
    }
}
