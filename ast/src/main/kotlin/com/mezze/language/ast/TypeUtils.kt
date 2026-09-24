package com.mezze.language.ast

/**
 * Is this name a type *variable* (as opposed to a concrete/nominal type)?
 * Mezze's convention: a name that starts with a `'` (e.g. `'a`) or with a
 * lowercase letter (e.g. `a`) is a type variable; anything else (uppercase,
 * `Int`, `Option`) is a concrete type. Empty-safe.
 */
fun String.isTypeVarName(): Boolean =
    startsWith("'") || (isNotEmpty() && this[0].isLowerCase())

fun collectTypeVarsInOrder(type: MezzeType, sortFields: Boolean = true): List<TypeVar> {
    val result = mutableListOf<TypeVar>()
    collectTypeVarsRecursive(type, result, sortFields, deep = false)
    return result
}

fun collectTypeVariables(type: MezzeType, collector: MutableSet<TypeVar>) {
    collector.addAll(collectTypeVarsInOrder(type, sortFields = false))
}

private fun collectTypeVarsRecursive(
    type: MezzeType,
    collector: MutableList<TypeVar>,
    sortFields: Boolean,
    deep: Boolean,
    seen: MutableSet<MezzeType> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap()),
) {
    // Only the deep walk can meet a cycle — an ability constraint's `having`
    // binding can hold the function's own return var, and a recursive effect type
    // (Promise, Semaphore, TVar) closes the loop. `occursCheck` avoids this by
    // refusing to descend at all; this walk has to descend, so it remembers where
    // it has been. By OBJECT identity, not structural equality: two equal
    // subtrees at different places are two things to visit.
    if (deep && type !is MezzeType.VarType && !seen.add(type)) return
    fun recurse(t: MezzeType) = collectTypeVarsRecursive(t, collector, sortFields, deep, seen)
    when (type) {
        is MezzeType.VarType -> collector.add(type.variable)
        is MezzeType.FunctionType -> {
            recurse(type.paramType)
            recurse(type.returnType)
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    recurse(arg)
                }
                // INFERRED positions, shallow walk skips them. The `'e` in
                // `<Exception having 'error = 'e>` is what the body turned out to
                // throw, not something the signature promised to be polymorphic
                // in, and the shallow walk answers "which variables does this type
                // DECLARE". `AnnotationValidator` asks it exactly that, so
                // collecting these made it reject `divide` for "forcing 'a to Str"
                // when the body had simply pinned an inferred error type. 27 tests
                // said so.
                if (deep) constraint.associatedTypeBindings.values.forEach { recurse(it) }
            }
            if (deep) type.effects?.let { recurse(it) }
        }
        is MezzeType.RecordType -> {
            val fieldEntries = if (sortFields) {
                type.fields.entries.sortedBy { it.key }
            } else {
                type.fields.entries
            }
            fieldEntries.forEach { (_, fieldType) ->
                recurse(fieldType)
            }
        }
        is MezzeType.VariantType -> {
            val constructorEntries = if (sortFields) {
                type.constructors.entries.sortedBy { it.key }
            } else {
                type.constructors.entries
            }
            constructorEntries.forEach { (_, constructorType) ->
                constructorType?.let { recurse(it) }
            }
            // #194: an applied polymorphic variant carries its arguments here
            // (`Tree Int` is VariantType(typeArgs=[Int])), and they are structural
            // children like any other. No live case was measured, unlike the
            // effects gap above — this is here because the walk should match
            // `MezzeType.anyChild`, which is the reference for what positions
            // exist, rather than match it in the places someone happened to check.
            type.typeArgs.forEach { recurse(it) }
        }
        is MezzeType.TypeApplication -> {
            type.args.forEach { arg ->
                recurse(arg)
            }
        }
        is MezzeType.NewtypeType -> recurse(type.wrappedType)
        is MezzeType.EffectRow ->
            if (deep) {
                type.effectInstances.forEach { instance ->
                    val entries = if (sortFields) instance.associatedTypes.entries.sortedBy { it.key }
                        else instance.associatedTypes.entries
                    entries.forEach { (_, t) -> recurse(t) }
                }
            }
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType -> { }
    }
}

/**
 * EVERY type variable in [type], including the ones [collectTypeVarsInOrder]
 * leaves out on purpose — a function's effect row, an ability constraint's
 * `having` bindings, and a standalone row.
 *
 * The two walks answer different questions and both are wanted. The shallow one
 * answers "which variables does this type DECLARE", which is what a validator
 * checking an annotation against its body needs. This one answers "which
 * variables are IN this type", which is what anything rewriting or IDENTIFYING
 * the whole type needs — and getting that wrong is not cosmetic: it feeds
 * `normalizeTypeVariables`, so a variable living only in
 * `<Exception having 'error = 'e>` was never renamed and the content hash kept
 * whatever number the solver happened to give it. Two compiles of one source,
 * two hashes. MEASURED at 5 sites across both suites.
 *
 * Structurally this is [MezzeType.anyChild], which is the reference for which
 * positions exist.
 */
fun collectTypeVarsDeep(type: MezzeType, sortFields: Boolean = true): List<TypeVar> {
    val result = mutableListOf<TypeVar>()
    collectTypeVarsRecursive(type, result, sortFields, deep = true)
    return result
}

/**
 * Rename a type's variables to `'a`, `'b`, … in first-seen order.
 *
 * Distinctness is by VALUE. The worry that motivated an identity-keyed map here
 * — that `.distinct()` would merge two independent variables sharing a name —
 * does not apply: [TypeVar] carries a globally unique `id` that participates in
 * equality, so two independent variables are already unequal however they are
 * spelled. Identity hashes were strictly worse at the job: they collide between
 * distinct live objects and are reused after collection, either of which merges
 * two variables that are genuinely different.
 *
 * MEASURED before the change: across both suites, identity-distinct and
 * value-distinct never produced a different partition, so no normalized name —
 * and no content hash derived from one — moves.
 */
fun normalizeTypeVariables(type: MezzeType): MezzeType {
    // DEEP: a variable this misses keeps its solver-assigned number in the
    // normalized type, and this feeds the content hasher.
    val uniqueTypeVars = collectTypeVarsDeep(type).distinct()

    val mapping = uniqueTypeVars.withIndex().associate { (index, typeVar) ->
        typeVar to TypeVar.freshWithName("'${('a' + index)}", typeVar.level)
    }

    return substituteTypeVariables(type, mapping)
}


fun substituteTypeVariables(type: MezzeType, mapping: Map<TypeVar, TypeVar>): MezzeType {
    if (type is MezzeType.VarType) {
        val newVar = mapping[type.variable] ?: type.variable
        return MezzeType.VarType(newVar)
    }
    return type.transformChildren { substituteTypeVariables(it, mapping) }
}

