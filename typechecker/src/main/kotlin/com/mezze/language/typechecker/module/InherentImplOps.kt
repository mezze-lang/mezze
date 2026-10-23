package com.mezze.language.typechecker.module

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility
import com.mezze.language.typechecker.ProgramIndex
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedModule as TypedAstModule
import com.mezze.language.typechecker.util.parsedUsePathToCore

/**
 * What an importer reads off an imported module's inherent impls — their ops'
 * return and parameter types, effects and constraints. Interface facts: kept
 * with a cached module's interface, so importing it does not read its body.
 */
@kotlinx.serialization.Serializable
data class InherentImplOps(
    val returnTypes: Map<Hash, Map<String, MezzeType>>,
    val paramTypes: Map<Hash, Map<String, MezzeType>>,
    val effects: Map<Hash, Map<String, MezzeType.EffectRow>>,
    val constraints: Map<Hash, Map<String, List<MezzeType.FunctionAbilityConstraint>>>,
)

internal fun harvestInherentImplOps(module: TypedAstModule, exports: Map<String, ExportedItem>) = InherentImplOps(
    returnTypes = harvestInherentImplOpReturnTypes(module, exports),
    paramTypes = harvestInherentImplOpParamTypes(module, exports),
    effects = harvestInherentImplOpEffects(module),
    constraints = harvestInherentImplOpConstraints(module),
)

internal fun harvestInherentImplOpReturnTypes(
    module: TypedAstModule,
    exports: Map<String, ExportedItem>
): Map<Hash, Map<String, MezzeType>> {
    val result = mutableMapOf<Hash, Map<String, MezzeType>>()
    for (impl in module.abilityImpls) {
        if (impl.abilityHash != Hash.ZERO) continue
        val opMap = mutableMapOf<String, MezzeType>()
        for ((opName, opExpr) in impl.operations) {
            // helped for thin-wrapper impls that #215 strips, but
            // silently corrupted Stage B's chainable HTTP: `Client.get`
            // returns a Request record, while the exported `pub let get`
            // returns `Result Response HttpError`. Conflating them
            // typed `c.get { path }` as `Result …`, and the follow-up
            // `.send {}` reported "Result vs Request". Effects have the
            // same warning (#181); apply it to return types too.
            val bodyReturnType: MezzeType =
                if (opExpr is TypedLambda) {
                    opExpr.body.type
                } else when (val t = opExpr.type) {
                    is MezzeType.FunctionType -> t.returnType
                    else -> t
                }

            // #215 case where TypedAstBuilder dropped polymorphic args
            // and the export is the only place the full signature
            // survives. Concrete record / variant / nominal body types
            // always win.
            val exportedFn = exports[opName]
            val exportFnType = exportedFn?.mezzeType as? MezzeType.FunctionType
            val exportReturnType = exportFnType?.returnType

            // The impl body's return may be an unpinned var (a thin forwarder
            // like `get_or = {it,def} -> get_or{result=it,def=def}`
            // whose forwarded-call return the impl's own checking never tied back
            // to `def`). The export carries the real signature, e.g.
            // `{result: Result 'a 'e, def: 'a} -> 'a`, where the return shares `'a`
            // with a PARAM. Params are harvested separately with their OWN var
            // instances, so lifting the export's return verbatim leaves it
            // disconnected — `top.get_or{def=..}` then records `'a` instead of
            // the arg's type. Re-tie the return to the impl's OWN param var by
            // matching the export param field whose type is the export's return.
            val remappedReturn: MezzeType? =
                if (bodyReturnType is MezzeType.VarType && exportFnType != null)
                    remapExportReturnToImplParamVar(exportFnType, opExpr)
                else null

            val rawReturnType = remappedReturn
                ?: if (bodyReturnType is MezzeType.VarType && exportReturnType != null) exportReturnType
                else bodyReturnType

            // A remapped return IS a real impl-param var (its name may be plain,
            // e.g. `a`, not `'a`) — it is deliberately shared, not a placeholder.
            // Only the NON-remapped fallback needs the placeholder guard, which
            // drops the internal `unknown`-style vars TypedAstBuilder leaves on
            // bodies it couldn't pin.
            if (remappedReturn == null) {
                val isPlaceholder = rawReturnType is MezzeType.VarType &&
                    !rawReturnType.variable.name.startsWith("'")
                if (isPlaceholder) continue
            }

            opMap[opName] = rawReturnType
        }
        if (opMap.isNotEmpty()) {
            result[impl.hash] = opMap
        }
    }
    return result
}

/**
 * When an imported inherent method forwards to a same-named free function
 * (`get_or = {it,def} -> get_or{result=it,def=def}`), its body
 * return is an unpinned var and we fall back to the EXPORT's declared return.
 * That return often shares a type var with one of the export's PARAMS
 * (`{…, def: 'a} -> 'a`). Because params are harvested into a separate map
 * with their own var instances, the export's return var is disconnected from
 * the impl's param var — so the call-site records `'a` instead of the pinned
 * argument type. This re-ties them: find the export param field whose type IS
 * the export's return var, then return the SAME field's var off the impl's
 * own params. Returns null when the return isn't a bare var shared with a
 * param (nothing to re-tie — caller keeps the export return as-is).
 */
private fun remapExportReturnToImplParamVar(
    exportFnType: MezzeType.FunctionType,
    opExpr: TypedExpr,
): MezzeType? {
    val exportRetVar = (exportFnType.returnType as? MezzeType.VarType)?.variable ?: return null
    val exportParams = (exportFnType.paramType as? MezzeType.RecordType)?.fields ?: return null
    // The export param field carrying the return's var (e.g. `def: 'a`).
    val sharedField = exportParams.entries.firstOrNull { (_, ty) ->
        (ty as? MezzeType.VarType)?.variable == exportRetVar
    }?.key ?: return null
    // Read that field off the impl lambda's own params to get the impl's var.
    val implParams = (opExpr as? TypedLambda)?.params ?: return null
    return implParams.firstOrNull { it.name == sharedField }?.type
}

/**
 * #181: harvest the EFFECT ROW of each inherent impl op. Reads the
 * lambda's FunctionType.effects directly — the body's effects were
 * baked in by ConstraintGenerator (#180) and preserved on
 * TypedLambda.type by TypedAstBuilder. Without this harvest, the
 * importer reconstructs a pure FunctionType in
 * registerImportedInherentImpl line 1120, and the call site
 * `r.send {}` looks effect-free across the module boundary.
 *
 * NOTE: We do NOT consult `exports[opName]` here. The same-named
 * free function may share a return-type shape with the impl op but
 * may perform completely different effects — conflating them
 * across two impls with shared op names (e.g. Request.with_auth
 * and Client.with_auth) is a bug. Effects come ONLY from the
 * impl's own TypedLambda.
 */
internal fun harvestInherentImplOpEffects(
    module: TypedAstModule
): Map<Hash, Map<String, MezzeType.EffectRow>> {
    val result = mutableMapOf<Hash, Map<String, MezzeType.EffectRow>>()
    for (impl in module.abilityImpls) {
        if (impl.abilityHash != Hash.ZERO) continue
        val opMap = mutableMapOf<String, MezzeType.EffectRow>()
        for ((opName, opExpr) in impl.operations) {
            val effects = (opExpr.type as? MezzeType.FunctionType)?.effects
            if (effects != null) {
                opMap[opName] = effects
            }
        }
        if (opMap.isNotEmpty()) {
            result[impl.hash] = opMap
        }
    }
    return result
}

/**
 * Harvest the inferred ability constraints on each inherent impl op (e.g.
 * `decode = { it } -> decode_result { resp = it }` inherits `with FromJson
 * 'a Str` from decode_result). These live on the op's post-solve
 * FunctionType.abilityConstraints and reference the SAME type vars as the
 * op's return type (the lambda's body type IS its return), so re-attaching
 * them to the registered method type keeps the constraint tied to the
 * return var — pinning the return at the call site then pins the codec
 * target and the derivation fires. Only the auto-derived inherent impls
 * (abilityHash == ZERO) are inherent methods.
 */
internal fun harvestInherentImplOpConstraints(
    module: TypedAstModule
): Map<Hash, Map<String, List<MezzeType.FunctionAbilityConstraint>>> {
    val result = mutableMapOf<Hash, Map<String, List<MezzeType.FunctionAbilityConstraint>>>()
    for (impl in module.abilityImpls) {
        if (impl.abilityHash != Hash.ZERO) continue
        val opMap = mutableMapOf<String, List<MezzeType.FunctionAbilityConstraint>>()
        for ((opName, opExpr) in impl.operations) {
            val constraints = (opExpr.type as? MezzeType.FunctionType)?.abilityConstraints
            if (!constraints.isNullOrEmpty()) opMap[opName] = constraints
        }
        if (opMap.isNotEmpty()) result[impl.hash] = opMap
    }
    return result
}

/**
 * Issue #230: harvest the post-solve PARAM-record type for each inherent
 * impl operation. The op's param record carries fully-typed fields by the
 * time the home module finished checking — including callback fields like
 * `p: { x: 'a } -> Bool`. Importer registration (without this harvest)
 * substitutes a fresh TypeVar for each field, so callers can pass any
 * value where a function was expected.
 *
 * Same shape rules as harvestInherentImplOpReturnTypes — including
 * preferring the exported binding's signature over the impl op's body
 * when both are available (the export carries the un-stripped
 * polymorphic record; #215 may have stripped TypeApplication args from
 * the body's stored type).
 */
internal fun harvestInherentImplOpParamTypes(
    module: TypedAstModule,
    exports: Map<String, ExportedItem>
): Map<Hash, Map<String, MezzeType>> {
    val result = mutableMapOf<Hash, Map<String, MezzeType>>()
    for (impl in module.abilityImpls) {
        if (impl.abilityHash != Hash.ZERO) continue
        val opMap = mutableMapOf<String, MezzeType>()
        for ((opName, opExpr) in impl.operations) {
            val rawParamType: MezzeType =
                if (opExpr is TypedLambda) {
                    MezzeType.RecordType(
                        fields = opExpr.params.associate { it.name to it.type },
                        rowVar = null
                    )
                } else when (val t = opExpr.type) {
                    is MezzeType.FunctionType -> t.paramType
                    else -> continue
                }

            val record = rawParamType as? MezzeType.RecordType
            if (record != null) {
                val anyInformative = record.fields.values.any { v ->
                    v !is MezzeType.VarType ||
                        v.variable.name.startsWith("'")
                }
                if (!anyInformative) continue
            }

            opMap[opName] = rawParamType
        }
        if (opMap.isNotEmpty()) {
            result[impl.hash] = opMap
        }
    }
    return result
}
