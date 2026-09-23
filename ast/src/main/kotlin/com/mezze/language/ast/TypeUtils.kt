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
    collectTypeVarsRecursive(type, result, sortFields)
    return result
}

fun collectTypeVariables(type: MezzeType, collector: MutableSet<TypeVar>) {
    collector.addAll(collectTypeVarsInOrder(type, sortFields = false))
}

private fun collectTypeVarsRecursive(type: MezzeType, collector: MutableList<TypeVar>, sortFields: Boolean) {
    when (type) {
        is MezzeType.VarType -> collector.add(type.variable)
        is MezzeType.FunctionType -> {
            collectTypeVarsRecursive(type.paramType, collector, sortFields)
            collectTypeVarsRecursive(type.returnType, collector, sortFields)
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    collectTypeVarsRecursive(arg, collector, sortFields)
                }
            }
        }
        is MezzeType.RecordType -> {
            val fieldEntries = if (sortFields) {
                type.fields.entries.sortedBy { it.key }
            } else {
                type.fields.entries
            }
            fieldEntries.forEach { (_, fieldType) ->
                collectTypeVarsRecursive(fieldType, collector, sortFields)
            }
        }
        is MezzeType.VariantType -> {
            val constructorEntries = if (sortFields) {
                type.constructors.entries.sortedBy { it.key }
            } else {
                type.constructors.entries
            }
            constructorEntries.forEach { (_, constructorType) ->
                constructorType?.let { collectTypeVarsRecursive(it, collector, sortFields) }
            }
        }
        is MezzeType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVarsRecursive(arg, collector, sortFields)
            }
        }
        is MezzeType.NewtypeType -> collectTypeVarsRecursive(type.wrappedType, collector, sortFields)
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.FracType,
        is MezzeType.BytesType,
        is MezzeType.CharType, is MezzeType.EffectRow -> { }
    }
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
    val uniqueTypeVars = collectTypeVarsInOrder(type).distinct()

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

