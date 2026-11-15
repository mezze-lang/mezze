package com.mezze.language.typechecker.solving

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar

/**
 * Give every row variable in a type a FRESH copy, so one instantiation's rows are
 * not the next one's.
 *
 * A row variable that a declaration and its call sites SHARE is pinned by whichever
 * site binds it first, and every later site inherits that answer:
 *
 *     let get = { r } -> r.tls
 *     pub let a: Bool = get { r = { host = "h", tls = False } }
 *     pub let b: Bool = get { r = { name = "n", tls = True } }
 *
 * `a` fixed `get`'s parameter and `b` was then refused against it. `TypeScheme`
 * already states this hazard for a `having` binding's variable ("the first call to
 * discharge the obligation pins it for the rest — and the rejection depends on
 * source order") and freshens that; rows are the same hazard and were not freshened
 * at all, so a SAME-MODULE call site bound the binding's own row.
 *
 * ONE walker, used by both instantiation paths. There were two before this —
 * `ExpressionConstraints`' private copy and nothing in `TypeScheme` — which is how
 * the gap existed at all. [includeEffects] is the only difference between the
 * callers: an effect row is substitutable through `Substitution.rowVarMappings` and
 * its own instantiation path already freshens it, so the scheme path leaves effect
 * rows alone and takes only RECORD and VARIANT rows.
 */
internal fun refreshRowVars(
    type: MezzeType,
    mapping: MutableMap<RowVar, RowVar>,
    includeEffects: Boolean,
): MezzeType {
    fun freshFor(rv: RowVar?): RowVar? {
        if (rv == null) return null
        return mapping.getOrPut(rv) { if (rv.isUserSpecified()) RowVar.userOpen() else RowVar.fresh() }
    }
    return when (type) {
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = refreshRowVars(type.paramType, mapping, includeEffects),
            returnType = refreshRowVars(type.returnType, mapping, includeEffects),
            effects = if (includeEffects) type.effects?.let { r -> r.copy(rowVar = freshFor(r.rowVar)) }
                else type.effects,
            abilityConstraints = type.abilityConstraints,
        )
        is MezzeType.RecordType -> type.copy(
            fields = type.fields.mapValues { (_, v) -> refreshRowVars(v, mapping, includeEffects) },
            rowVar = freshFor(type.rowVar),
        )
        is MezzeType.VariantType -> type.copy(
            constructors = type.constructors.mapValues { (_, v) ->
                v?.let { refreshRowVars(it, mapping, includeEffects) }
            },
            rowVar = freshFor(type.rowVar),
        )
        is MezzeType.TypeApplication ->
            type.copy(args = type.args.map { refreshRowVars(it, mapping, includeEffects) })
        is MezzeType.NewtypeType ->
            type.copy(wrappedType = refreshRowVars(type.wrappedType, mapping, includeEffects))
        else -> type
    }
}

/** Whether [type] has any row variable the walker above would freshen. */
internal fun anyRowVar(type: MezzeType, includeEffects: Boolean): Boolean = when (type) {
    is MezzeType.FunctionType -> (includeEffects && type.effects?.rowVar != null) ||
        anyRowVar(type.paramType, includeEffects) || anyRowVar(type.returnType, includeEffects)
    is MezzeType.RecordType -> type.rowVar != null ||
        type.fields.values.any { anyRowVar(it, includeEffects) }
    is MezzeType.VariantType -> type.rowVar != null ||
        type.constructors.values.any { it != null && anyRowVar(it, includeEffects) }
    is MezzeType.TypeApplication -> type.args.any { anyRowVar(it, includeEffects) }
    is MezzeType.NewtypeType -> anyRowVar(type.wrappedType, includeEffects)
    else -> false
}
