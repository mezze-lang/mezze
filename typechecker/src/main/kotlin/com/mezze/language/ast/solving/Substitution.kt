package com.mezze.language.ast.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.solving.unify

data class Substitution(
    val mappings: Map<TypeVar, MezzeType> = emptyMap(),
    // #274-B: Row-var bindings for effect rows. When an EffectRow's
    // rowVar is bound here, applying the substitution replaces the row
    // var with the target effects (merged with the row's own effects).
    // Distinct from `mappings`: row vars are a separate namespace from
    // type vars, and the value is always an EffectRow (never an
    // arbitrary MezzeType). Record/variant row vars go through their
    // own machinery — this map is effect-row-only.
    val rowVarMappings: Map<RowVar, MezzeType.EffectRow> = emptyMap(),
    // Row-var bindings for RECORD rows. The mapped value is an EXTENSION:
    // the fields the row gained, plus the tail row var that stays open (or
    // null once closed). Applying the substitution to `{a: Int, ...r}` with
    // `r ↦ {b: Str, ...r2}` splices to `{a: Int, b: Str, ...r2}`, chasing
    // tails transitively.
    //
    // Before this channel existed, growing an open record was expressed by
    // hunting for THE type variable whose binding compared equal to the
    // record and rebinding it — and when no variable matched (several vars
    // chaining to one record, or the record reached through a constraint
    // rather than a binding), the extension was silently dropped. A second
    // field accessed on an inferred record then typechecked but never
    // reached the record's type: `val.code` worked while `val.status_verbose`
    // failed at runtime with `Field not found`, because the decoder derived
    // from that type only ever saw the first field.
    //
    // Extension fields never OVERRIDE fields already present on the record —
    // by the time a row is bound the shared fields were already unified, so
    // preferring the existing entry is both safe and cheap.
    val recordRowMappings: Map<RowVar, MezzeType.RecordType> = emptyMap()
) {
    fun apply(type: MezzeType): MezzeType {
        if (mappings.isEmpty() && rowVarMappings.isEmpty() && recordRowMappings.isEmpty()) {
            return type
        }
        if (mappings.isEmpty() && recordRowMappings.isEmpty() && isConcreteType(type)) {
            return type
        }
        // The common answers, without allocating: applied to nearly every type
        // the checker touches, this allocated a visited set and a chain cache on
        // every call — a fifth of everything a check allocated.
        if (type.isGround || !needsSubstitution(type)) return type
        if (type is MezzeType.VarType) {
            var v = type.variable
            for (hop in 0 until 32) {
                val mapped = mappings[v] ?: return if (v === type.variable) type else MezzeType.VarType(v)
                if (mapped !is MezzeType.VarType) break
                v = mapped.variable
            }
        }

        return applyWithVisited(type, HashSet(), HashMap())
    }

    // Resolve a variable chain (var -> var -> ... -> terminal) with path
    // compression, union-find style. `chainCache` memoizes the terminal a
    // variable resolves to *within a single apply() traversal*, so a type that
    // mentions the same var many times (e.g. (a, a, a) -> a) walks each chain
    // once instead of once per occurrence. Only pure variable hops are cached;
    // once the chain reaches a non-var (or an unmapped/cyclic var) we hand off
    // to applyWithVisited so compound terminals still recurse under the cycle
    // guard. Returns the resolved MezzeType for `startVar`, or null if it maps
    // to a non-variable that must be recursed by the caller.
    private fun resolveVarChain(
        startVar: TypeVar,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType? {
        chainCache[startVar]?.let { return it }

        var current = startVar
        // Allocated only for a chain with hops; most variables map straight to
        // their terminal.
        var hops: ArrayList<TypeVar>? = null
        var hopSet: HashSet<TypeVar>? = null   // past a few hops, a linear scan gets slow
        while (true) {
            val seenHop = hopSet?.contains(current) ?: (hops?.contains(current) == true)
            if (current in visitedVars || seenHop) {
                // Cyclic — either against the outer traversal's visitedVars, or
                // a var-only cycle within this chain (a -> b -> a). Leave as the
                // raw var. Do NOT cache: visitedVars membership is transient
                // (added/removed around each recursion), so a hop's cyclic
                // resolution here could be stale once the var leaves visitedVars.
                return MezzeType.VarType(current)
            }
            val mapped = mappings[current]
            if (mapped == null) {
                // Unmapped terminal variable — resolves to itself, independent
                // of visitedVars, so it (and every hop that led here) is safe to
                // cache with path compression for the rest of this traversal.
                val result = MezzeType.VarType(current)
                chainCache[current] = result
                hops?.forEach { chainCache[it] = result }
                return result
            }
            if (mapped is MezzeType.VarType) {
                val list = hops ?: ArrayList<TypeVar>(4).also { hops = it }
                list.add(current)
                if (hopSet != null) hopSet.add(current) else if (list.size > 16) hopSet = HashSet(list)
                current = mapped.variable
                continue
            }
            // Non-variable terminal: caller must recurse it under the cycle
            // guard, so we can't cache a finished value here.
            return null
        }
    }

    private fun isConcreteType(type: MezzeType): Boolean = when (type) {
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> true
        is MezzeType.VarType, is MezzeType.FunctionType, is MezzeType.RecordType,
        is MezzeType.VariantType, is MezzeType.TypeApplication,
        is MezzeType.NewtypeType, is MezzeType.EffectRow -> false
    }

    private fun needsSubstitution(type: MezzeType): Boolean {
        if (type.isGround) return false
        return when (type) {
            is MezzeType.VarType -> type.variable in mappings

            is MezzeType.FunctionType ->
                needsSubstitution(type.paramType) ||
                needsSubstitution(type.returnType) ||
                (type.effects != null && needsSubstitutionInEffectRow(type.effects!!)) ||
                type.abilityConstraints.any { c -> c.typeArgs.any { needsSubstitution(it) } }

            is MezzeType.RecordType ->
                // A bound row var needs application even when every field is
                // concrete — that binding is precisely what splices in the
                // fields the row gained after this record was built.
                (type.rowVar != null && type.rowVar in recordRowMappings) ||
                type.fields.values.any { needsSubstitution(it) }

            is MezzeType.VariantType ->
                type.constructors.values.any { it != null && needsSubstitution(it) } ||
                type.typeArgs.any { needsSubstitution(it) }

            is MezzeType.TypeApplication ->
                type.args.any { needsSubstitution(it) }

            is MezzeType.NewtypeType -> needsSubstitution(type.wrappedType)

            is MezzeType.EffectRow -> needsSubstitutionInEffectRow(type)

            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType -> false
        }
    }

    private fun needsSubstitutionInEffectRow(effectRow: MezzeType.EffectRow): Boolean {
        // #274-B: an EffectRow needs substitution if its row var is
        // bound in rowVarMappings, OR if any effect instance's
        // associated type mentions a bound TypeVar.
        val rowVarBound = effectRow.rowVar != null && effectRow.rowVar in rowVarMappings
        val instanceNeeds = effectRow.effectInstances.any { instance ->
            instance.associatedTypes.values.any { needsSubstitution(it) }
        }
        return rowVarBound || instanceNeeds
    }

    // Substitute a constraint's class-arg / associated-type binding value. These
    // are (almost always) plain type vars; a var can point at the enclosing
    // FunctionType's own return var, so deep-recursing via applyWithVisited can
    // re-enter that FunctionType and reconstruct it unboundedly (OOM on recursive
    // effect newtypes like Promise/Semaphore/TVar). We therefore resolve a VarType
    // arg only to the extent that the target is itself a var or a leaf (concrete)
    // type — following var→var chains and mapping to concrete leaves — but stop at
    // any compound type (FunctionType/Record/etc.), leaving the var in place there.
    // The compound's own structure is already substituted where it appears as a
    // param/return type; here we only need the freshened var identity for
    // resolution, never a deep rewrite. Non-var args (rare) are returned as-is.
    private fun substituteConstraintArg(type: MezzeType, visitedVars: MutableSet<TypeVar>): MezzeType {
        if (type !is MezzeType.VarType) return type
        var current = type.variable
        val seen = HashSet<TypeVar>()
        while (seen.add(current) && current !in visitedVars) {
            when (val mapped = mappings[current]) {
                null -> return MezzeType.VarType(current)
                is MezzeType.VarType -> current = mapped.variable
                else -> return if (isLeafType(mapped)) mapped else MezzeType.VarType(current)
            }
        }
        return MezzeType.VarType(current)
    }

    private fun isLeafType(type: MezzeType): Boolean = when (type) {
        is MezzeType.IntType, is MezzeType.FloatType,
        is MezzeType.FracType, is MezzeType.StringType,
        is MezzeType.CharType, is MezzeType.BytesType,
        is MezzeType.VarType -> true
        else -> false
    }

    private fun applyWithVisited(
        type: MezzeType,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType {
        // A ground type has nothing a substitution could change, whatever chain
        // of variables led here.
        if (type.isGround) return type
        if (visitedVars.isEmpty() && !needsSubstitution(type)) {
            return type
        }

        return when (type) {
            is MezzeType.VarType -> {
                if (type.variable in visitedVars) return type
                // Fast path: chase variable-only chains with path compression.
                // Returns non-null when the chain terminates at a variable
                // (mapped-to-nothing or cyclic); null means the chain ends at a
                // compound type we must recurse into under the cycle guard.
                resolveVarChain(type.variable, visitedVars, chainCache)?.let { return it }
                val substitutedType = mappings[type.variable]
                if (substitutedType != null) {
                    visitedVars.add(type.variable)
                    val result = applyWithVisited(substitutedType, visitedVars, chainCache)
                    visitedVars.remove(type.variable)
                    result
                } else {
                    type
                }
            }

            is MezzeType.FunctionType -> {
                val param = applyWithVisited(type.paramType, visitedVars, chainCache)
                val ret = applyWithVisited(type.returnType, visitedVars, chainCache)
                val effects = type.effects?.let { applyToEffectRowWithVisited(it, visitedVars, chainCache) }
                // Unchanged, the type itself: most of a type a substitution is
                // applied to is untouched, and rebuilding it all was a large share
                // of what checking allocated.
                if (param === type.paramType && ret === type.returnType && effects === type.effects &&
                    type.abilityConstraints.isEmpty()) return type
                MezzeType.FunctionType(
                paramType = param,
                returnType = ret,
                effects = effects,
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        // Substitute the constraint's class-arg + associated-type
                        // binding vars SHALLOWLY: these are plain vars, and a binding
                        // value can be the function's OWN return var — deep-recursing
                        // via applyWithVisited re-enters this FunctionType and, because
                        // the value-graph (not just a var) revisits, loops until OOM.
                        // Applying just the top-level var mapping preserves the link
                        // (freshening/resolution) without following the cycle; deeper
                        // structure is already covered by paramType/returnType.
                        typeArgs = constraint.typeArgs.map { substituteConstraintArg(it, visitedVars) },
                        associatedTypeBindings = constraint.associatedTypeBindings
                            .mapValues { (_, t) -> substituteConstraintArg(t, visitedVars) },
                        abilityHash = constraint.abilityHash
                    )
                }
            )
            }

            is MezzeType.RecordType -> {
                var changed = false
                val applied = type.fields.mapValues { (_, fieldType) ->
                    applyWithVisited(fieldType, visitedVars, chainCache).also { if (it !== fieldType) changed = true }
                }
                if (!changed && (type.rowVar == null || type.rowVar !in recordRowMappings)) return type
                var fields = applied
                // Splice row extensions, chasing tails: r ↦ {b, ...r2}, r2 ↦ {c}
                // yields {…, b, c}. The seen-set guards a cyclic tail chain, and
                // existing fields win — shared fields were unified when the row
                // was bound, so the entry already present is the resolved one.
                var row = type.rowVar
                val seenRows = HashSet<RowVar>()
                while (row != null && seenRows.add(row)) {
                    val ext = recordRowMappings[row] ?: break
                    for ((name, extType) in ext.fields) {
                        if (name !in fields) {
                            fields = fields + (name to applyWithVisited(extType, visitedVars, chainCache))
                        }
                    }
                    row = ext.rowVar
                }
                MezzeType.RecordType(
                    fields = fields,
                    rowVar = row,
                    identity = type.identity
                )
            }

            is MezzeType.VariantType -> {
                var changed = false
                val appliedConstructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyWithVisited(it, visitedVars, chainCache).also { a -> if (a !== it) changed = true } }
                }
                val appliedArgs = type.typeArgs.map { applyWithVisited(it, visitedVars, chainCache) }
                if (!changed && appliedArgs.indices.all { appliedArgs[it] === type.typeArgs[it] }) return type
                MezzeType.VariantType(
                    constructors = appliedConstructors,
                    rowVar = type.rowVar,
                    typeArgs = appliedArgs,
                    identity = type.identity,
                    ascriptionWall = type.ascriptionWall
                )
            }

            is MezzeType.TypeApplication -> {
                val args = type.args.map { applyWithVisited(it, visitedVars, chainCache) }
                if (args.indices.all { args[it] === type.args[it] }) return type
                MezzeType.TypeApplication(constructor = type.constructor, args = args, identity = type.identity)
            }

            is MezzeType.NewtypeType -> {
                val wrapped = applyWithVisited(type.wrappedType, visitedVars, chainCache)
                if (wrapped === type.wrappedType) type else type.copy(wrappedType = wrapped)
            }

            is MezzeType.EffectRow -> applyToEffectRowWithVisited(type, visitedVars, chainCache)

            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType -> type
        }
    }

    private fun applyToEffectRowWithVisited(
        effectRow: MezzeType.EffectRow,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType.EffectRow {
        // First: substitute associated types inside effect instances.
        var current = if (effectRow.effectInstances.isEmpty()) {
            effectRow
        } else {
            var anyChanged = false
            val substitutedInstances = effectRow.effectInstances.map { instance ->
                if (instance.associatedTypes.isEmpty()) {
                    instance
                } else {
                    val needsSub = instance.associatedTypes.values.any { needsSubstitution(it) }
                    if (!needsSub) {
                        instance
                    } else {
                        anyChanged = true
                        val substitutedAssocTypes = instance.associatedTypes.mapValues { (_, type) ->
                            applyWithVisited(type, visitedVars, chainCache)
                        }
                        MezzeType.EffectInstance(instance.name, substitutedAssocTypes)
                    }
                }
            }
            if (anyChanged) effectRow.copy(effectInstances = substitutedInstances.toSet()) else effectRow
        }

        // #274-B: if the row's row var is bound, absorb the bound
        // effects into this row. The rowVar of the result becomes the
        // absorbed row's rowVar (chain through — if the target itself
        // has a row var, that becomes the new tail).
        val rowVar = current.rowVar
        if (rowVar != null && rowVar in rowVarMappings) {
            // Recursively apply so chains of row vars resolve.
            val target = applyToEffectRowWithVisited(rowVarMappings[rowVar]!!, visitedVars, chainCache)
            // Callback-side row subtraction: effects in `caughtByAncestor`
            // are handled by the enclosing function and MUST NOT be
            // absorbed from the row var binding into the return row.
            val caught = current.caughtByAncestor
            val targetEffects = target.effects - caught
            val targetInstances = target.effectInstances.filter { it.name !in caught }.toSet()
            val targetHashes = target.effectHashes.filterKeys { it !in caught }
            current = MezzeType.EffectRow(
                effects = current.effects + targetEffects,
                effectInstances = current.effectInstances + targetInstances,
                effectHashes = current.effectHashes + targetHashes,
                rowVar = target.rowVar,
                performed = current.performed || target.performed,
                caughtByAncestor = current.caughtByAncestor + target.caughtByAncestor
            )
        }
        return current
    }

    // s1's mappings take precedence — we DON'T apply this to other's values because
    // that would bake in intermediate results (e.g. partial variant fields).
    //
    // On a COLLISION — both sides binding the same variable to different values —
    // precedence is not enough. Discarding the loser is a choice between two
    // answers rather than a union of information, and nothing checked the two
    // agreed. Measured across a stdlib compile: 216 collisions, 164 of them one
    // type in two representations (`Bool([True,False])` vs `BoolType`,
    // `Option([Some,None])` vs `TypeApplication(Option 'a)`) and 52 of them two
    // DIFFERENT type variables for one variable — the link between them simply
    // dropped, which is the "one written 'a becomes two TypeVars" shape.
    //
    // So a collision now UNIFIES. Both sides claim to be this variable's value, so
    // they are equal, and unification says what that costs: nothing for the two
    // representation dualities (`unify` knows both), and the missing `'s -> 'z`
    // link for two variables. Those extra bindings are ADDED; the winner for the
    // colliding variable itself is unchanged, so this is monotone — it can only
    // add information, never replace an answer.
    //
    // A binding unification already produced is never overwritten here: extras go
    // in only for variables neither side had bound, so the merge cannot clobber
    // while fixing clobbering.
    fun compose(other: Substitution): Substitution {
        val extras = collisionUnifications(other)
        // [other] is the accumulated substitution, usually large; this step is
        // small. Copying [other] at every step made solving a module quadratic in
        // its size, so it is extended in place of copied: a persistent map shares
        // all but the path to each new binding. Precedence is unchanged — this
        // step's bindings over [other]'s over the collision extras.
        var merged = PersistentHashMap.of(other.mappings)
        if (extras != null) for ((v, t) in extras) if (v !in other.mappings) merged = merged.plus(v, t)
        return Substitution(
            mappings = if (mappings.isEmpty()) merged else merged.plusAll(mappings),
            rowVarMappings = union(other.rowVarMappings, rowVarMappings),
            recordRowMappings = union(other.recordRowMappings, recordRowMappings)
        )
    }

    /** [base] with [top]'s entries over it — sharing, not copying, the accumulated side. */
    private fun <K, V> union(base: Map<K, V>, top: Map<K, V>): Map<K, V> = when {
        top.isEmpty() -> base
        base.isEmpty() -> top
        else -> PersistentHashMap.of(base).plusAll(top)
    }

    /**
     * The bindings implied by every collision between this and [other], or null
     * when there is no collision (the overwhelmingly common case — this returns
     * without allocating).
     *
     * `unify` composes internally, so this is re-entrancy guarded: a collision met
     * while already resolving one is left to unification's own rules rather than
     * recursing into this same merge.
     */
    private fun collisionUnifications(other: Substitution): Map<TypeVar, MezzeType>? {
        if (mappings.isEmpty() || other.mappings.isEmpty()) return null
        var extras: MutableMap<TypeVar, MezzeType>? = null
        for ((v, newValue) in mappings) {
            val previous = other.mappings[v] ?: continue
            if (previous == newValue) continue
            SubstitutionClobbers.record(v, previous, newValue)
            if (SubstitutionClobbers.resolving) continue
            SubstitutionClobbers.resolving = true
            try {
                when (val unified = unify(previous, newValue)) {
                    is UnificationResult.Success ->
                        for ((ev, evalue) in unified.substitution.mappings) {
                            if (ev == v) continue
                            // Only the ALIASES. A collision says the two values are
                            // equal, and `'x = 'y` follows from that no matter when
                            // it is learned — which is the whole of the missing link.
                            // `'x = SomeShape` does not: the collision does not say
                            // which of the two shapes is the refined one, and pinning
                            // the variable to the argument-less representation
                            // (`Option([Some,None])`, the declaration's own payload
                            // var) closes it before the solve can reach the real
                            // element type. Measured: doing that decodes
                            // `{"flag":true}` against the wrong target and returns the
                            // default. Shapes stay the solver's business.
                            if (evalue !is MezzeType.VarType) continue
                            if (ev in mappings || ev in other.mappings) continue
                            val target = extras ?: mutableMapOf<TypeVar, MezzeType>().also { extras = it }
                            target[ev] = evalue
                        }
                    is UnificationResult.Failure -> SubstitutionClobbers.recordConflict(v, unified.message)
                }
            } finally {
                SubstitutionClobbers.resolving = false
            }
        }
        return extras
    }

    // S0.4/#373 — make this substitution IDEMPOTENT by resolving each RHS through
    // the substitution itself, so `apply(apply(t)) == apply(t)`.
    //
    // WHY IT'S NEEDED. `compose` (and the solver's step-by-step
    // `result.compose(substitution)`) is a NON-saturating union: s1 wins, and
    // neither side's RHS is re-applied. That keeps each NEW binding resolved
    // against prior work (the solver pre-applies the accumulated substitution to
    // every constraint before unifying), but it never RE-resolves an OLD RHS when
    // a later binding pins a var that old RHS still mentions. Over a full solve the
    // accumulated map therefore holds entries like `'d -> Fn(_ -> 'e)` alongside
    // `'e -> Str`: `apply` still yields the fixpoint `Fn(_ -> Str)` in one pass
    // (apply is fully recursive), but the STORED RHS is not itself a fixpoint, so
    // `apply(apply(t)) != apply(t)` on the raw map.
    //
    // WHY IT'S SEMANTICALLY TRANSPARENT. `apply` already resolves var chains to the
    // fixpoint, so `saturate` changes NO type any `apply(t)` produces — it only
    // rewrites each stored RHS to the value `apply` would have reached anyway,
    // collapsing the fixpoint into a single stored step. It reuses the SAME `apply`
    // (including the OOM-guarded shallow handling of ability-constraint arg /
    // associated-type-binding vars in `substituteConstraintArg`), so it cannot
    // deep-re-enter a recursive effect type. One pass suffices because `apply` is
    // recursive; a second `apply` over the rewritten RHSs is a no-op fixpoint,
    // which is exactly the post-solve idempotence invariant.
    fun saturate(): Substitution {
        if (mappings.isEmpty()) return this
        var changed = false
        val saturated = HashMap<TypeVar, MezzeType>(mappings.size)
        for ((tv, ty) in mappings) {
            val applied = apply(ty)
            if (applied !== ty && applied != ty) changed = true
            saturated[tv] = applied
        }
        return if (changed) Substitution(saturated, rowVarMappings) else this
    }

    val isEmpty: Boolean
        get() = mappings.isEmpty() && rowVarMappings.isEmpty()

    companion object {
        val empty = Substitution()

        fun single(typeVar: TypeVar, type: MezzeType) = Substitution(mapOf(typeVar to type))

        // #274-B: single-binding constructor for row var → effect row.
        fun singleRow(rowVar: RowVar, effects: MezzeType.EffectRow) =
            Substitution(rowVarMappings = mapOf(rowVar to effects))
    }

    override fun toString(): String {
        if (mappings.isEmpty()) return "{}"
        val entries = mappings.entries.take(5).joinToString(", ") { (typeVar, type) ->
            "$typeVar -> ${type.javaClass.simpleName}"
        }
        val suffix = if (mappings.size > 5) ", ..." else ""
        return "{$entries$suffix}"
    }
}

// Auto-generated row vars (RowVar.fresh()) are closed to null; user-specified (RowVar.userOpen())
// and mustBeOpenRowVars remain open.
//
// The close DECISION is the ONE labeled-row rule that records and variants
// genuinely share (effects do NOT participate here — their rows ride on
// `type.effects` and are closed by their own set-row machinery, not
// drop-and-close). Routed through this single helper so the "close a
// generated, non-user tail unless it must stay open" policy is stated once.
// This is NOT tail BINDING (that is the #268-Stage-2 dead end: binding record
// /variant tails corrupts field-type resolution during solving); it is the
// existing drop-and-close, deduped.
//
// THE SEAM: this is the ONE place record/variant open tails close. Every
// finalization boundary that produces a STORED or DISPLAYED type routes through
// closeGeneratedRowVars — binding types + identifier/member types (TypedAstBuilder)
// and ability-constraint args (ConstraintGenerator.addAbilityConstraintsToFunctionType).
// So signatures, hover, and error messages all show closed records `{ a: T }`,
// never `{ a: T, … }`, without per-site patching. Add a new display path? Call
// this — do NOT special-case a close at the call site.
//
// EXCEPTION, by necessity: an inferred codec target (`from_json`/`.decode`
// result) is NOT closed on the VALUE type the interpreter dispatches on. It must
// stay a bare var there — a concrete record makes the FromJson ability check
// reject it (no `impl FromJson for {…}`) and makes codegen read a record where
// the input is a string (`Expected record for field 'from_json'`). The record
// shape is display-only: it prints on the constraint arg (closed here), never on
// the value the runtime decodes. See project_hover_inferred_open_record_reconstruction.
private fun closeLabeledRowVar(existingRowVar: RowVar?, mustBeOpenRowVars: Set<RowVar>): RowVar? =
    if (existingRowVar != null
        && !existingRowVar.isUserSpecified()
        && existingRowVar !in mustBeOpenRowVars) {
        null
    } else {
        existingRowVar
    }

fun closeGeneratedRowVars(type: MezzeType, mustBeOpenRowVars: Set<RowVar> = emptySet()): MezzeType = when (type) {
    is MezzeType.RecordType -> {
        val closedRowVar = closeLabeledRowVar(type.rowVar, mustBeOpenRowVars)
        MezzeType.RecordType(
            fields = type.fields.mapValues { (_, fieldType) -> closeGeneratedRowVars(fieldType, mustBeOpenRowVars) },
            rowVar = closedRowVar,
            identity = type.identity
        )
    }
    is MezzeType.VariantType -> {
        val closedRowVar = closeLabeledRowVar(type.rowVar, mustBeOpenRowVars)
        MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, payloadType) ->
                payloadType?.let { closeGeneratedRowVars(it, mustBeOpenRowVars) }
            },
            rowVar = closedRowVar,
            typeArgs = type.typeArgs.map { closeGeneratedRowVars(it, mustBeOpenRowVars) },
            identity = type.identity,
            ascriptionWall = type.ascriptionWall
        )
    }
    is MezzeType.FunctionType -> MezzeType.FunctionType(
        paramType = closeGeneratedRowVars(type.paramType, mustBeOpenRowVars),
        returnType = closeGeneratedRowVars(type.returnType, mustBeOpenRowVars),
        effects = type.effects,
        abilityConstraints = type.abilityConstraints
    )
    is MezzeType.TypeApplication -> MezzeType.TypeApplication(
        constructor = type.constructor,
        args = type.args.map { closeGeneratedRowVars(it, mustBeOpenRowVars) },
        identity = type.identity
    )
    is MezzeType.NewtypeType -> type.copy(
        wrappedType = closeGeneratedRowVars(type.wrappedType, mustBeOpenRowVars)
    )
    is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
    is MezzeType.StringType,
    is MezzeType.FracType, is MezzeType.BytesType,
    is MezzeType.CharType, is MezzeType.EffectRow -> type
}

fun occursCheck(typeVar: TypeVar, type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.VarType -> typeVar == type.variable

        is MezzeType.FunctionType ->
            occursCheck(typeVar, type.paramType) ||
            occursCheck(typeVar, type.returnType) ||
            // Check the ability constraints' class-arg + associated-type binding
            // vars DIRECTLY, not by recursing into their bound values. A binding
            // value can be the function's own return var; deep-recursing there
            // re-enters this FunctionType and loops forever (OOM) on recursive
            // effect types (Promise/Semaphore/TVar). These positions hold plain
            // vars, so a direct identity check is exactly the occurs-check we need;
            // deeper structure is already covered by paramType/returnType.
            type.abilityConstraints.any { constraint ->
                (constraint.typeArgs + constraint.associatedTypeBindings.values).any {
                    it is MezzeType.VarType && it.variable == typeVar
                }
            } ||
            (type.effects != null && occursCheckEffectRow(typeVar, type.effects!!))

        is MezzeType.RecordType ->
            type.fields.values.any { occursCheck(typeVar, it) }

        is MezzeType.VariantType ->
            type.constructors.values.any { dataType ->
                dataType != null && occursCheck(typeVar, dataType)
            } || type.typeArgs.any { occursCheck(typeVar, it) }

        is MezzeType.TypeApplication ->
            type.args.any { occursCheck(typeVar, it) }

        is MezzeType.NewtypeType -> occursCheck(typeVar, type.wrappedType)

        is MezzeType.EffectRow -> occursCheckEffectRow(typeVar, type)

        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> false
    }
}

private fun occursCheckEffectRow(typeVar: TypeVar, effectRow: MezzeType.EffectRow): Boolean {
    return effectRow.effectInstances.any { instance ->
        instance.associatedTypes.values.any { occursCheck(typeVar, it) }
    }
}

/** #264: free vars living in an effect row's effect-instance associated types
 *  (mirrors [occursCheckEffectRow]). */
private fun collectFreeTypeVarsInEffectRow(effectRow: MezzeType.EffectRow): Set<TypeVar> =
    effectRow.effectInstances
        .flatMap { instance -> instance.associatedTypes.values.flatMap { collectFreeTypeVars(it) } }
        .toSet()

/**
 * The type variables generalization may quantify.
 *
 * MUST see a variable wherever [occursCheck] can see one THAT A CALLER CAN
 * OBSERVE: a var occurring in the type but left unquantified is not freshened by
 * `TypeScheme.instantiate`, so every call site shares that ONE variable and the
 * first caller to pin it pins it for the rest. The qualifier matters — see the
 * FunctionType arm, where the one position this walk skips is skipped precisely
 * because nothing there is observable.
 *
 * [MezzeType.anyChild] is the structural reference for which positions exist.
 */
fun collectFreeTypeVars(type: MezzeType): Set<TypeVar> {
    if (type.isGround) return emptySet()
    return when (type) {
        is MezzeType.VarType -> setOf(type.variable)

        is MezzeType.FunctionType -> {
            val paramVars = collectFreeTypeVars(type.paramType)
            val returnVars = collectFreeTypeVars(type.returnType)
            val constraintVars = type.abilityConstraints.flatMap { constraint ->
                constraint.typeArgs.flatMap { collectFreeTypeVars(it) }
            }.toSet()
            // Effects ride on ARROWS, so this row — not the standalone EffectRow
            // arm below — is where an `<Exception having 'error = 'e>` var lives.
            // #264 collected the standalone row and missed the arrow-embedded one,
            // which is the ordinary case.
            val effectVars = type.effects?.let { collectFreeTypeVarsInEffectRow(it) } ?: emptySet()
            // NOT COLLECTED, and this is a KNOWN GAP rather than a decision:
            // the ability constraints' own `associatedTypeBindings`
            // (`having 'out = 'r`). [occursCheck] and [MezzeType.anyChild] both
            // see those vars, so this walker is narrower than its two siblings —
            // which is exactly the hole this doc's first line warns about.
            //
            // An earlier version of this comment argued the omission was CORRECT,
            // on an exhaustiveness argument: either `out` occurs elsewhere in the
            // type and is already collected, or it occurs nowhere else and no
            // call site can observe it. The second half is FALSE. The obligation
            // still has to be discharged, and two call sites discharging it at
            // different types collide on the one shared variable:
            //
            //     let ignore = { x } -> 0
            //     let f = { a, b } -> ignore { x = a + b }
            //     let i = f { a = 1, b = 2 }        -- pins the shared `out` to Int
            //     let g = f { a = 1.5, b = 2.5 }    -- REJECTED
            //     -- Type mismatch for associated type 'out': expected Float but got Int
            //
            // `f` is ordinary polymorphic code. Swap the two call sites and the
            // error swaps with them ("expected Int but got Float"), which is the
            // signature of a shared variable and of nothing else. It is a false
            // REJECTION, not a wrong answer — but a well-typed program is refused
            // and no annotation can fix it.
            //
            // Collecting them is a ONE-LINE change that makes that program
            // compile, and it is not the fix. MEASURED: inference output is
            // byte-identical either way (same shapes, different fresh ids) and
            // `LazyEffectsTest.test suspended value in recursive function` turns
            // red — it returns a raw value where it must return an `Err`. The
            // cause is downstream of here. In
            //
            //     sum_to : { n: Int, acc: 'a } with NumAdd Int 'a -> 'a
            //
            // whose constraint carries an internal associated-type binding
            // `'out = 'o`. That binding has NO source spelling — `having`
            // appears only on an `ability`/`impl`/`effect` declaration, never
            // in a `with` clause — it lives in the constraint's
            // `associatedTypeBindings` map. And `'o` there is a DIFFERENT
            // variable from `'a`, so quantifying it makes the call site
            // freshen and pin a COPY while the declaration's own stored
            // constraint keeps the original, unpinned — one written variable,
            // two TypeVars, and evidence selection reads the stale one. Closing
            // this needs call-site re-resolution in the evidence layer, not a
            // wider walk here.
            //
            // Stated as a property, with the repro, in
            // `FreeTypeVarAgreementTest`; `HavingBindingInstantiationTest` covers
            // the half that does work.
            paramVars + returnVars + constraintVars + effectVars
        }

        is MezzeType.RecordType ->
            type.fields.values.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.VariantType ->
            (type.constructors.values.flatMap { dataType ->
                dataType?.let { collectFreeTypeVars(it) } ?: emptySet()
            } + type.typeArgs.flatMap { collectFreeTypeVars(it) }).toSet()

        is MezzeType.TypeApplication ->
            type.args.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.NewtypeType -> collectFreeTypeVars(type.wrappedType)

        is MezzeType.EffectRow -> collectFreeTypeVarsInEffectRow(type)

        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> emptySet()
    }
}
