package com.mezze.language.ast.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

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

        return applyWithVisited(type, mutableSetOf(), HashMap())
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
        val hops = ArrayList<TypeVar>()
        val hopSet = HashSet<TypeVar>()
        while (true) {
            if (current in visitedVars || current in hopSet) {
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
                for (v in hops) chainCache[v] = result
                return result
            }
            if (mapped is MezzeType.VarType) {
                hops.add(current)
                hopSet.add(current)
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
        is MezzeType.BoolType, is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> true
        is MezzeType.VarType, is MezzeType.FunctionType, is MezzeType.RecordType,
        is MezzeType.VariantType, is MezzeType.TypeApplication, is MezzeType.ListType,
        is MezzeType.MapType, is MezzeType.SetType,
 is MezzeType.SeqType,
        is MezzeType.NewtypeType, is MezzeType.EffectRow -> false
    }

    private fun needsSubstitution(type: MezzeType): Boolean {
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

            is MezzeType.ParametricBuiltin -> type.typeArgs.any { needsSubstitution(it) }

            is MezzeType.NewtypeType -> needsSubstitution(type.wrappedType)

            is MezzeType.EffectRow -> needsSubstitutionInEffectRow(type)

            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
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
        is MezzeType.FracType, is MezzeType.StringType, is MezzeType.BoolType,
        is MezzeType.CharType, is MezzeType.BytesType,
        is MezzeType.VarType -> true
        else -> false
    }

    private fun applyWithVisited(
        type: MezzeType,
        visitedVars: MutableSet<TypeVar>,
        chainCache: MutableMap<TypeVar, MezzeType>
    ): MezzeType {
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

            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyWithVisited(type.paramType, visitedVars, chainCache),
                returnType = applyWithVisited(type.returnType, visitedVars, chainCache),
                effects = type.effects?.let { applyToEffectRowWithVisited(it, visitedVars, chainCache) },
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

            is MezzeType.RecordType -> {
                var fields = type.fields.mapValues { (_, fieldType) ->
                    applyWithVisited(fieldType, visitedVars, chainCache)
                }
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
                val appliedConstructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyWithVisited(it, visitedVars, chainCache) }
                }
                MezzeType.VariantType(
                    constructors = appliedConstructors,
                    rowVar = type.rowVar,
                    typeArgs = type.typeArgs.map { applyWithVisited(it, visitedVars, chainCache) },
                    identity = type.identity,
                    ascriptionWall = type.ascriptionWall
                )
            }

            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { applyWithVisited(it, visitedVars, chainCache) },
                identity = type.identity
            )

            is MezzeType.ParametricBuiltin ->
                type.rebuild(type.typeArgs.map { applyWithVisited(it, visitedVars, chainCache) })

            is MezzeType.NewtypeType -> type.copy(
                wrappedType = applyWithVisited(type.wrappedType, visitedVars, chainCache)
            )

            is MezzeType.EffectRow -> applyToEffectRowWithVisited(type, visitedVars, chainCache)

            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
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
    fun compose(other: Substitution): Substitution {
        return Substitution(
            mappings = other.mappings + mappings,
            rowVarMappings = other.rowVarMappings + rowVarMappings,
            recordRowMappings = other.recordRowMappings + recordRowMappings
        )
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
    is MezzeType.ParametricBuiltin ->
        type.rebuild(type.typeArgs.map { closeGeneratedRowVars(it, mustBeOpenRowVars) })
    is MezzeType.TypeApplication -> MezzeType.TypeApplication(
        constructor = type.constructor,
        args = type.args.map { closeGeneratedRowVars(it, mustBeOpenRowVars) },
        identity = type.identity
    )
    is MezzeType.NewtypeType -> type.copy(
        wrappedType = closeGeneratedRowVars(type.wrappedType, mustBeOpenRowVars)
    )
    is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
    is MezzeType.StringType, is MezzeType.BoolType,
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

        is MezzeType.ParametricBuiltin -> type.typeArgs.any { occursCheck(typeVar, it) }

        is MezzeType.NewtypeType -> occursCheck(typeVar, type.wrappedType)

        is MezzeType.EffectRow -> occursCheckEffectRow(typeVar, type)

        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
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
 *  (mirrors [occursCheckEffectRow]). Used by collectFreeTypeVars for a
 *  standalone EffectRow so generalization accounts for them. */
private fun collectFreeTypeVarsInEffectRow(effectRow: MezzeType.EffectRow): Set<TypeVar> =
    effectRow.effectInstances
        .flatMap { instance -> instance.associatedTypes.values.flatMap { collectFreeTypeVars(it) } }
        .toSet()

fun collectFreeTypeVars(type: MezzeType): Set<TypeVar> {
    return when (type) {
        is MezzeType.VarType -> setOf(type.variable)

        is MezzeType.FunctionType -> {
            val paramVars = collectFreeTypeVars(type.paramType)
            val returnVars = collectFreeTypeVars(type.returnType)
            val constraintVars = type.abilityConstraints.flatMap { constraint ->
                constraint.typeArgs.flatMap { collectFreeTypeVars(it) }
            }.toSet()
            paramVars + returnVars + constraintVars
        }

        is MezzeType.RecordType ->
            type.fields.values.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.VariantType ->
            (type.constructors.values.flatMap { dataType ->
                dataType?.let { collectFreeTypeVars(it) } ?: emptySet()
            } + type.typeArgs.flatMap { collectFreeTypeVars(it) }).toSet()

        is MezzeType.TypeApplication ->
            type.args.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.ParametricBuiltin ->
            type.typeArgs.flatMap { collectFreeTypeVars(it) }.toSet()

        is MezzeType.NewtypeType -> collectFreeTypeVars(type.wrappedType)

        is MezzeType.EffectRow -> collectFreeTypeVarsInEffectRow(type)

        is MezzeType.IntType,
        is MezzeType.FloatType,
        is MezzeType.StringType,
        is MezzeType.BoolType,
        is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> emptySet()
    }
}
