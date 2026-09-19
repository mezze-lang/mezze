package com.mezze.language.ast

private fun printVariantType(
    type: MezzeType.VariantType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    if (BuiltinTypes.isBoolVariant(type)) return "Bool"

    if (BuiltinTypes.isOptionVariant(type)) {
        val valueType = BuiltinTypes.getOptionValueType(type)
        val valueTypeStr = if (valueType != null) {
            prettyPrintWithAliasCheck(valueType, typeAliases, showExpansion, typeVarMapping)
        } else {
            "'a"
        }
        return "Option $valueTypeStr"
    }

    // Constructor-name matching handles recursive types where structural unification fails
    for ((aliasName, aliasInfo) in typeAliases) {
        val aliasDef = aliasInfo.definition
        if (aliasDef is MezzeType.VariantType) {
            val aliasCtors = aliasDef.constructors.keys.sorted()
            val typeCtors = type.constructors.keys.sorted()
            if (aliasCtors == typeCtors) {
                val typeArgs = inferTypeArguments(type, aliasDef, aliasInfo.typeParams)
                if (typeArgs != null) {
                    val argsStr = if (typeArgs.isEmpty()) "" else " " + typeArgs.joinToString(" ") {
                        prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
                    }
                    return aliasName + argsStr
                }
            }
        }
    }

    // OPEN-SUBSET match: a value like `Queued` is inferred as an OPEN variant
    // `| Queued, ... |` (rowVar != null) carrying only the constructors used so
    // far. Its constructor set is a SUBSET of a declared alias's (e.g. `Status`
    // has Queued/Shipped/Cancelled). An open variant is compatible with any
    // alias whose constructor set is a superset, so if EXACTLY ONE in-scope
    // alias is a superset we display that alias name (the compiler unifies the
    // value to `Status` anyway — hover just diverged). Guarded UNIQUE so a
    // constructor shared by two aliases stays structural rather than guessing.
    if (type.rowVar != null && type.constructors.isNotEmpty()) {
        val typeCtors = type.constructors.keys
        val supersets = typeAliases.entries.filter { (_, info) ->
            val def = info.definition
            def is MezzeType.VariantType && def.constructors.keys.containsAll(typeCtors)
        }
        if (supersets.size == 1) {
            val (aliasName, aliasInfo) = supersets.first()
            val aliasDef = aliasInfo.definition as MezzeType.VariantType
            val typeArgs = inferTypeArguments(type, aliasDef, aliasInfo.typeParams)
            val argsStr = if (typeArgs.isNullOrEmpty()) "" else " " + typeArgs.joinToString(" ") {
                prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
            }
            return aliasName + argsStr
        }
    }

    val aliasMatch = findTypeAliasForType(type, typeAliases)
    if (aliasMatch != null) {
        val aliasName = if (aliasMatch.typeArgs.isEmpty()) {
            aliasMatch.aliasName
        } else {
            val argsStr = aliasMatch.typeArgs.joinToString(" ") {
                prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
            }
            "${aliasMatch.aliasName} $argsStr"
        }
        return aliasName
    }

    val resultTypes = BuiltinTypes.getResultTypes(type)
    if (resultTypes != null) {
        val (valueType, errorType) = resultTypes
        val valueTypeStr = prettyPrintWithAliasCheck(valueType, typeAliases, showExpansion, typeVarMapping)
        // Render the error slot through the alias-aware path so a NAMED error
        // variant (e.g. `AsyncError`) prints as its name, not its whole
        // constructor union. Anonymous error variants still expand (below).
        val errorTypeStr = prettyPrintWithAliasCheck(errorType, typeAliases, showExpansion, typeVarMapping)
        return "Result $valueTypeStr $errorTypeStr"
    }

    // A variant carrying its declared name (`AsyncError`, `Tree`, …) prints as
    // that name + its type-arg vector — the SAME rule the plain printer uses
    // (prettyPrintTypeWithMapping, "declared name" branch). Without it a named
    // variant reached here (notably the error slot of a `Result`) expands to
    // its full constructor union on every hover. Kept in sync so the two
    // printers agree. Anonymous inline variants (no name) still expand below.
    type.identity.displayNameOrNull()?.let { declaredName ->
        val argsStr = if (type.typeArgs.isEmpty()) "" else " " + type.typeArgs.joinToString(" ") {
            prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
        }
        return "$declaredName$argsStr"
    }

    val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
        when (dataType) {
            null -> name
            is MezzeType.RecordType -> "$name ${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping)}"
            else -> "$name(${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping)})"
        }
    }
    return if (type.isOpen) {
        "| $constructors, ... |"
    } else {
        "| $constructors |"
    }
}


private fun prettyPrintEffectRow(effectRow: MezzeType.EffectRow, typeVarMapping: Map<TypeVar, String>): String {
    val parts = mutableListOf<String>()

    val deduped = effectRow.effects.groupBy { effect ->
        effect.substringAfterLast("::")
    }.mapValues { (_, variants) ->
        variants.minByOrNull { it.length } ?: variants.first()
    }.values.sorted()
    parts.addAll(deduped)

    for (instance in effectRow.effectInstances.sortedBy { it.name }) {
        if (instance.name in effectRow.effects || deduped.any { it == instance.name.substringAfterLast("::") }) {
            if (instance.associatedTypes.isNotEmpty()) {
                val simpleName = instance.name.substringAfterLast("::")
                parts.removeIf { it == simpleName }
                val bindingsStr = instance.associatedTypes.entries
                    .sortedBy { it.key }
                    .joinToString(", ") { (name, type) ->
                        "'$name = ${prettyPrintTypeWithMapping(type, typeVarMapping)}"
                    }
                parts.add("${simpleName} having $bindingsStr")
            }
        } else {
            if (instance.associatedTypes.isEmpty()) {
                parts.add(instance.name.substringAfterLast("::"))
            } else {
                val simpleName = instance.name.substringAfterLast("::")
                val bindingsStr = instance.associatedTypes.entries
                    .sortedBy { it.key }
                    .joinToString(", ") { (name, type) ->
                        "'$name = ${prettyPrintTypeWithMapping(type, typeVarMapping)}"
                    }
                parts.add("$simpleName having $bindingsStr")
            }
        }
    }

    if (effectRow.isOpen) parts.add("...")
    if (effectRow.performed) parts.add("*")

    return "<${parts.joinToString(", ")}>"
}


/**
 * One record renderer for both printers — the record analogue of
 * [printVariantType]. A record carrying a declared type name prints as that
 * name (so `MockRule` in a hover shows `MockRule`, not its expanded fields),
 * identically in the plain and alias-aware walks so they cannot drift. Only an
 * anonymous record, or an explicit `showExpansion`, prints the field structure.
 * The plain printer calls this with no aliases; the alias walk passes its map.
 */
private fun printRecordType(
    type: MezzeType.RecordType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    val displayName = type.identity.displayNameOrNull()
    if (displayName != null && !showExpansion) return displayName
    val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
        "$name: ${prettyPrintWithAliasCheck(fieldType, typeAliases, showExpansion, typeVarMapping)}"
    }
    return if (type.isOpen) {
        if (fields.isEmpty()) "{ ... }" else "{ $fields, ... }"
    } else {
        if (fields.isEmpty()) "{}" else "{ $fields }"
    }
}

fun prettyPrintType(type: MezzeType): String {
    val typeVarMapping = collectTypeVariables(type)
    return prettyPrintTypeWithMapping(type, typeVarMapping)
}

/**
 * Two types printed against ONE shared naming of their type variables.
 *
 * For "expected X but got Y": printed separately, each side numbers its
 * variables from 'a and the same variable can appear under two different names
 * — or worse, two different variables under the same one. Sharing the mapping
 * keeps the pair comparable, which is the whole point of showing them together.
 */
fun prettyPrintTypePair(first: MezzeType, second: MezzeType): Pair<String, String> {
    val shared = collectTypeVariables(
        MezzeType.RecordType(mapOf("a" to first, "b" to second)),
    )
    return prettyPrintTypeWithMapping(first, shared) to
        prettyPrintTypeWithMapping(second, shared)
}

private fun collectTypeVariables(type: MezzeType): Map<TypeVar, String> {
    val orderedVars = collectTypeVarsInOrder(type).distinct()

    return orderedVars.mapIndexed { index, typeVar ->
        val letterIndex = index % 26
        val suffix = if (index >= 26) (index / 26).toString() else ""
        val letter = ('a' + letterIndex).toString()
        typeVar to "'$letter$suffix"
    }.toMap()
}

private fun prettyPrintTypeWithMapping(type: MezzeType, typeVarMapping: Map<TypeVar, String>): String {
    PrimitiveTypes.toName(type)?.let { return it }

    return when (type) {
    is MezzeType.VarType -> typeVarMapping[type.variable] ?: type.variable.name
    is MezzeType.RecordType -> printRecordType(type, emptyMap(), showExpansion = false, typeVarMapping)
    // One variant renderer for both printers: with no aliases printVariantType
    // still names Bool/Option/Result and declared variants (`Tree 'a`), and only
    // an anonymous inline variant expands structurally. Sharing it here is what
    // stops the plain and alias printers from drifting on variant rendering.
    is MezzeType.VariantType -> printVariantType(type, emptyMap(), showExpansion = false, typeVarMapping)
    is MezzeType.SeqType -> "[${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}]"
    is MezzeType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintTypeWithMapping(it, typeVarMapping) }
        if (argsStr.isEmpty()) type.constructor else "${type.constructor} $argsStr"
    }
    is MezzeType.FunctionType -> {
        val paramTypeStr = if (type.paramType is MezzeType.FunctionType) {
            "(${prettyPrintTypeWithMapping(type.paramType, typeVarMapping)})"
        } else {
            prettyPrintTypeWithMapping(type.paramType, typeVarMapping)
        }

        // Only show constraints with unresolved type variables
        val filteredConstraints = type.abilityConstraints.filter { constraint ->
            constraint.typeArgs.any { arg -> arg is MezzeType.VarType }
        }

        val abilityConstraints = if (filteredConstraints.isNotEmpty()) {
            " with " + filteredConstraints.joinToString(", ") { constraint ->
                val typeArgs = constraint.typeArgs.joinToString(" ") { prettyPrintTypeWithMapping(it, typeVarMapping) }
                val head = if (typeArgs.isNotEmpty()) "${constraint.abilityName} $typeArgs" else constraint.abilityName
                if (constraint.associatedTypeBindings.isEmpty()) {
                    head
                } else {
                    // Mirror the effect-row printer: `having 'a = T, 'b = U`,
                    // comma-joined, sorted by key.
                    val bindingsStr = constraint.associatedTypeBindings.entries
                        .sortedBy { it.key.removePrefix("'") }
                        .joinToString(", ") { (name, ty) ->
                            "'${name.removePrefix("'")} = ${prettyPrintTypeWithMapping(ty, typeVarMapping)}"
                        }
                    "$head having $bindingsStr"
                }
            }
        } else {
            ""
        }

        val returnTypeStr = prettyPrintTypeWithMapping(type.returnType, typeVarMapping)

        val effects = if (type.effects != null && (type.effects.effects.isNotEmpty() || type.effects.effectInstances.isNotEmpty() || type.effects.performed)) {
            " ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
        } else {
            ""
        }

        paramTypeStr + abilityConstraints + " -> " + returnTypeStr + effects
    }
    is MezzeType.NewtypeType -> type.name
    is MezzeType.EffectRow -> prettyPrintEffectRow(type, typeVarMapping)
    // #271: a type printer must NEVER throw — it is frequently called WHILE
    // formatting a TypeCheckError, so an uncaught exception here masks the real
    // error with a compiler crash. Degrade to the type's simple name instead.
    else -> type::class.simpleName ?: "<type>"
}
}

fun prettyPrintTypeWithAliases(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo> = emptyMap(),
    showExpansion: Boolean = false
): String {
    val typeVarMapping = collectTypeVariables(type)

    if (type is MezzeType.VariantType && type.identity.displayNameOrNull() != null) {
        val alias = typeAliases[type.identity.displayNameOrNull()]
        if (alias != null) {
            // Prefer the stamped `typeArgs` vector (`Tree Int` → typeArgs=[IntType]).
            // Fall back to harvesting free tyvars from payloads only when typeArgs
            // is empty — the legacy pre-#194 path where the ident vector wasn't
            // preserved on substitution.
            val args: List<MezzeType> = if (type.typeArgs.isNotEmpty()) type.typeArgs else {
                val vars = mutableSetOf<MezzeType>()
                for ((_, payload) in type.constructors) {
                    if (payload != null) collectFreeTypeVars(payload, vars)
                }
                vars.toList()
            }
            val argsStr = if (args.isEmpty()) "" else " " + args.joinToString(" ") {
                prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
            }
            val aliasName = type.identity.displayNameOrNull() + argsStr
            return if (showExpansion) {
                "$aliasName (= ${prettyPrintTypeWithMapping(type, typeVarMapping)})"
            } else {
                aliasName
            }
        }
    }

    val aliasMatch = findTypeAliasForType(type, typeAliases)

    return if (aliasMatch != null) {
        val aliasName = if (aliasMatch.typeArgs.isEmpty()) {
            aliasMatch.aliasName
        } else {
            val typeArgsStr = aliasMatch.typeArgs.joinToString(" ") { prettyPrintTypeWithAliases(it, typeAliases, showExpansion) }
            "${aliasMatch.aliasName} $typeArgsStr"
        }

        if (showExpansion) {
            "$aliasName (= ${prettyPrintTypeWithMapping(type, typeVarMapping)})"
        } else {
            aliasName
        }
    } else {
        prettyPrintTypeWithAliasesRecursive(type, typeAliases, showExpansion, typeVarMapping)
    }
}

private fun prettyPrintWithAliasCheck(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    if (type is MezzeType.VariantType) {
        if (type.identity.displayNameOrNull() != null) {
            val alias = typeAliases[type.identity.displayNameOrNull()]
            if (alias != null) {
                // Prefer the stamped typeArgs (`Tree Int` → typeArgs=[Int]).
                // Fall back to free-var harvest for the legacy case where the
                // ident vector wasn't preserved on substitution.
                val args: List<MezzeType> = if (type.typeArgs.isNotEmpty()) type.typeArgs else {
                    val vars = mutableSetOf<MezzeType>()
                    for ((_, payload) in type.constructors) {
                        if (payload != null) collectFreeTypeVars(payload, vars)
                    }
                    vars.toList()
                }
                val argsStr = if (args.isEmpty()) "" else " " + args.joinToString(" ") {
                    prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
                }
                return type.identity.displayNameOrNull() + argsStr
            }
        }
        val typeCtors = type.constructors.keys.sorted()
        for ((aliasName, aliasInfo) in typeAliases) {
            val aliasDef = aliasInfo.definition
            if (aliasDef is MezzeType.VariantType && aliasDef.constructors.keys.sorted() == typeCtors) {
                if (aliasInfo.typeParams.isEmpty()) return aliasName
                // Prefer alias-aware unification: this recovers the ACTUAL
                // concrete type args by unifying `type` against `aliasDef`.
                // A concrete `Tree Int` has no free vars in its payloads —
                // the pattern-var walk below finds zero and falsely prints
                // `Tree 'a`; unifyTypesForAlias sees payload `value: Int`
                // vs alias `value: 'a`, binds `'a → Int`, returns `[Int]`.
                inferTypeArguments(type, aliasDef, aliasInfo.typeParams)?.let { concreteArgs ->
                    val argsStr = concreteArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping) }
                    return "$aliasName $argsStr"
                }
                val typeVars = mutableListOf<MezzeType>()
                for ((_, payload) in type.constructors) {
                    if (payload != null) collectFreeTypeVars(payload, typeVars.toMutableSet())
                }
                val seen = mutableSetOf<String>()
                val orderedArgs = mutableListOf<MezzeType>()
                for (v in typeVars) {
                    if (v is MezzeType.VarType && v.variable.name !in seen) {
                        seen.add(v.variable.name)
                        orderedArgs.add(v)
                    }
                }
                if (orderedArgs.size == aliasInfo.typeParams.size) {
                    val argsStr = orderedArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping) }
                    return "$aliasName $argsStr"
                }
                if (aliasInfo.typeParams.isNotEmpty()) {
                    val genericArgs = aliasInfo.typeParams.joinToString(" ") { "'${it.removePrefix("'")}" }
                    return "$aliasName $genericArgs"
                }
                return aliasName
            }
        }
    }

    val aliasMatch = findTypeAliasForType(type, typeAliases)

    return if (aliasMatch != null) {
        val aliasName = if (aliasMatch.typeArgs.isEmpty()) {
            aliasMatch.aliasName
        } else {
            val typeArgsStr = aliasMatch.typeArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping) }
            "${aliasMatch.aliasName} $typeArgsStr"
        }

        if (showExpansion) {
            "$aliasName (= ${prettyPrintTypeWithMapping(type, typeVarMapping)})"
        } else {
            aliasName
        }
    } else {
        prettyPrintTypeWithAliasesRecursive(type, typeAliases, showExpansion, typeVarMapping)
    }
}

private fun prettyPrintTypeWithAliasesRecursive(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    PrimitiveTypes.toName(type)?.let { return it }

    return when (type) {
    is MezzeType.VarType -> typeVarMapping[type.variable] ?: type.variable.name
    is MezzeType.RecordType -> printRecordType(type, typeAliases, showExpansion, typeVarMapping)
    is MezzeType.VariantType -> {
        printVariantType(type, typeAliases, showExpansion, typeVarMapping)
    }
    is MezzeType.SeqType -> "[${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping)}]"
    is MezzeType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping) }
        if (argsStr.isEmpty()) type.constructor else "${type.constructor} $argsStr"
    }
    is MezzeType.FunctionType -> {
        val paramTypeStr = if (type.paramType is MezzeType.FunctionType) {
            "(${prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping)})"
        } else {
            prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping)
        }

        // Only show constraints with unresolved type variables
        val filteredConstraints = type.abilityConstraints.filter { constraint ->
            constraint.typeArgs.any { arg -> arg is MezzeType.VarType }
        }

        val abilityConstraints = if (filteredConstraints.isNotEmpty()) {
            " with " + filteredConstraints.joinToString(", ") { constraint ->
                val typeArgs = constraint.typeArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping) }
                val head = if (typeArgs.isNotEmpty()) "${constraint.abilityName} $typeArgs" else constraint.abilityName
                if (constraint.associatedTypeBindings.isEmpty()) {
                    head
                } else {
                    val bindingsStr = constraint.associatedTypeBindings.entries
                        .sortedBy { it.key.removePrefix("'") }
                        .joinToString(", ") { (name, ty) ->
                            "'${name.removePrefix("'")} = ${prettyPrintWithAliasCheck(ty, typeAliases, showExpansion, typeVarMapping)}"
                        }
                    "$head having $bindingsStr"
                }
            }
        } else {
            ""
        }

        val returnTypeStr = prettyPrintWithAliasCheck(type.returnType, typeAliases, showExpansion, typeVarMapping)

        val effects = if (type.effects != null && (type.effects.effects.isNotEmpty() || type.effects.effectInstances.isNotEmpty() || type.effects.performed)) {
            " ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
        } else {
            ""
        }

        paramTypeStr + abilityConstraints + " -> " + returnTypeStr + effects
    }
    is MezzeType.NewtypeType -> type.name
    is MezzeType.EffectRow -> prettyPrintEffectRow(type, typeVarMapping)
    // #271: never throw while printing a type — see prettyPrintTypeWithMapping.
    else -> type::class.simpleName ?: "<type>"
}
}

/** The name this type was DECLARED with, or null for an anonymous shape. */
private fun MezzeType.declaredIdentityName(): String? = when (this) {
    is MezzeType.RecordType -> identity.displayNameOrNull()
    is MezzeType.VariantType -> identity.displayNameOrNull()
    is MezzeType.TypeApplication -> identity.displayNameOrNull() ?: constructor
    is MezzeType.NewtypeType -> name
    else -> null
}

data class TypeAliasInfo(
    val name: String,
    val typeParams: List<String>,
    val definition: MezzeType
)

private fun findTypeAliasForType(type: MezzeType, typeAliases: Map<String, TypeAliasInfo>): TypeAliasMatch? {
    // Fast path: prefer the ORIGINAL label on a labelled type when it names
    // an in-scope alias directly. Avoids picking a same-shape "shadow" alias.
    // For parametric aliases (`Container 'a`), infer args by unifying the
    // concrete shape against the alias's definition so `Container Int` doesn't
    // print bare as "Container".
    when (type) {
        is MezzeType.RecordType -> type.identity.displayNameOrNull()?.let { n ->
            typeAliases[n]?.let { info ->
                val args = if (info.typeParams.isEmpty()) emptyList()
                           else inferTypeArguments(type, info.definition, info.typeParams) ?: emptyList()
                return TypeAliasMatch(n, args)
            }
        }
        is MezzeType.VariantType -> type.identity.displayNameOrNull()?.let { n ->
            typeAliases[n]?.let { info ->
                val args = if (type.typeArgs.isNotEmpty()) type.typeArgs
                           else if (info.typeParams.isEmpty()) emptyList()
                           else inferTypeArguments(type, info.definition, info.typeParams) ?: emptyList()
                return TypeAliasMatch(n, args)
            }
        }
        is MezzeType.TypeApplication -> typeAliases[type.constructor]?.let {
            return TypeAliasMatch(type.constructor, type.args)
        }
        else -> {}
    }

    // A type that already HAS a name of its own is never renamed to another one.
    //
    // The scan below returns the first alias whose definition merely UNIFIES with
    // the type, and it walks a map — so the winner is decided by iteration order.
    // That is fine for an anonymous shape, which has no name to lose. It is wrong
    // for a declared type: `Promise Str` (a newtype, so absent from `typeAliases`
    // and missed by the fast path above) printed as `PromiseState Str` — an
    // unrelated variant that happens to unify — and which of the two you got
    // depended on map order, so an edit anywhere else could flip it.
    //
    // Returning null here means "print it as itself", which is what a type with a
    // Declared identity always wants.
    if (type.declaredIdentityName() != null) return null

    for ((_, aliasInfo) in typeAliases) {
        if (aliasInfo.typeParams.isEmpty()) {
            // Simple type alias without parameters - use unification logic
            val substitutions = mutableMapOf<String, MezzeType>()
            if (unifyTypes(type, aliasInfo.definition, substitutions)) {
                return TypeAliasMatch(aliasInfo.name, emptyList())
            }
            // Fallback: rendered-form equality (record aliases stripped of
            // their label still print the same shape as the alias defn).
            if (aliasNameShapeMatches(type, aliasInfo.definition, typeAliases)) {
                return TypeAliasMatch(aliasInfo.name, emptyList())
            }
        } else {
            // Parameterized type alias - try to match and infer type arguments
            val typeArgs = inferTypeArguments(type, aliasInfo.definition, aliasInfo.typeParams)
            if (typeArgs != null) {
                return TypeAliasMatch(aliasInfo.name, typeArgs)
            }
        }
    }
    return null
}

/**
 * Rendered-form equality — compares the two types via prettyPrint (WITHOUT
 * alias lookup) so representation differences like `IntType` vs
 * `TypeApplication("Int")` or `BoolType` vs `VariantType(Bool arms)` don't
 * defeat matching a bare record against an alias-defined record.
 */
private fun aliasNameShapeMatches(a: MezzeType, b: MezzeType, typeAliases: Map<String, TypeAliasInfo> = emptyMap()): Boolean {
    if (a::class != b::class) return false
    if (a is MezzeType.RecordType && b is MezzeType.RecordType) {
        // Auto-adopt only when openness matches. Open alias needs an explicit
        // annotation to adopt a closed literal (user rule); the annotation
        // flows through as an open concrete, so alias→alias adoption still
        // fires for annotated values.
        if ((a.rowVar == null) != (b.rowVar == null)) return false
        if (b.rowVar == null) {
            if (a.fields.keys != b.fields.keys) return false
        } else {
            if (!a.fields.keys.containsAll(b.fields.keys)) return false
        }
        return b.fields.all { (n, bv) ->
            val av = a.fields[n] ?: return@all false
            prettyPrintTypeWithAliases(av, typeAliases) == prettyPrintTypeWithAliases(bv, typeAliases)
        }
    }
    if (a is MezzeType.VariantType && b is MezzeType.VariantType) {
        if ((a.rowVar == null) != (b.rowVar == null)) return false
        if (b.rowVar == null) {
            if (a.constructors.keys != b.constructors.keys) return false
        } else {
            if (!a.constructors.keys.containsAll(b.constructors.keys)) return false
        }
        return b.constructors.all { (n, bv) ->
            val av = a.constructors[n]
            when {
                av == null && bv == null -> true
                av != null && bv != null -> prettyPrintTypeWithAliases(av, typeAliases) == prettyPrintTypeWithAliases(bv, typeAliases)
                else -> false
            }
        }
    }
    return false
}

private data class TypeAliasMatch(
    val aliasName: String,
    val typeArgs: List<MezzeType>
)

private fun inferTypeArguments(
    concreteType: MezzeType,
    parameterizedDef: MezzeType,
    typeParams: List<String>
): List<MezzeType>? {
    val substitutions = mutableMapOf<String, MezzeType>()

    // Normalise decl param names to match how they appear on the alias's
    // definition types — with and without the leading `'` — so that a
    // parameter declared as `'a` matches a VarType named `a` (stripped
    // during conversion) or `'a` (unstripped) in the alias definition.
    val patternNames = typeParams.flatMap { p ->
        val bare = p.removePrefix("'"); listOf(bare, "'$bare")
    }.toSet()

    if (unifyTypesForAlias(concreteType, parameterizedDef, substitutions, patternNames)) {
        val typeArgs = typeParams.mapNotNull { param ->
            val bare = param.removePrefix("'")
            substitutions[bare] ?: substitutions["'$bare"] ?: substitutions[param]
        }

        return if (typeArgs.size == typeParams.size) typeArgs else null
    }

    return null
}

/**
 * Alias-aware unification: any VarType whose name is in [patternNames]
 * (the alias's declared tyvars, normalised with and without `'`) is
 * treated as a pattern variable and bound to the concrete type. Anything
 * else falls through to the strict [unifyTypes].
 */
private fun unifyTypesForAlias(
    concrete: MezzeType,
    pattern: MezzeType,
    substitutions: MutableMap<String, MezzeType>,
    patternNames: Set<String>,
): Boolean {
    if (pattern is MezzeType.VarType && pattern.variable.name in patternNames) {
        val existing = substitutions[pattern.variable.name]
        if (existing == null) { substitutions[pattern.variable.name] = concrete; return true }
        return typesAreStructurallyEqual(concrete, existing)
    }
    // Self-recursive alias: `type Tree 'a = | ... Node { left: Tree 'a } |`
    // stores Tree's own def with `left` INLINED to VariantType (the resolver
    // unfolds the self-app). At the same time a concrete Tree Int stored on
    // a user site has `left: TypeApplication(Tree, [Int])`. Bridge the two:
    // when pattern is a VariantType with the same ctors as concrete's TypeApp,
    // it's the same self-recursive tail — treat it as satisfied.
    if (concrete is MezzeType.TypeApplication && pattern is MezzeType.VariantType &&
        concrete.args.size == 1) {
        // Bind pattern's tyvar (the sole one we're solving) to concrete's arg.
        // Same alias / same ctors on both sides: the recursion is faithful.
        val patternVarName = patternNames.firstOrNull { it in patternNames }
        if (patternVarName != null) {
            val existing = substitutions[patternVarName]
            if (existing == null) { substitutions[patternVarName] = concrete.args[0]; return true }
            return typesAreStructurallyEqual(concrete.args[0], existing)
        }
    }
    return when {
        concrete is MezzeType.RecordType && pattern is MezzeType.RecordType ->
            concrete.fields.size == pattern.fields.size &&
            concrete.isOpen == pattern.isOpen &&
            concrete.fields.all { (name, cf) ->
                val pf = pattern.fields[name]
                pf != null && unifyTypesForAlias(cf, pf, substitutions, patternNames)
            }
        concrete is MezzeType.VariantType && pattern is MezzeType.VariantType ->
            concrete.constructors.keys == pattern.constructors.keys &&
            concrete.isOpen == pattern.isOpen &&
            concrete.constructors.all { (name, cv) ->
                val pv = pattern.constructors[name]
                when {
                    cv == null && pv == null -> true
                    cv != null && pv != null -> unifyTypesForAlias(cv, pv, substitutions, patternNames)
                    else -> false
                }
            }
        concrete is MezzeType.SeqType && pattern is MezzeType.SeqType ->
            unifyTypesForAlias(concrete.elementType, pattern.elementType, substitutions, patternNames)
        concrete is MezzeType.TypeApplication && pattern is MezzeType.TypeApplication ->
            concrete.constructor == pattern.constructor &&
            concrete.args.size == pattern.args.size &&
            concrete.args.zip(pattern.args).all { (c, p) -> unifyTypesForAlias(c, p, substitutions, patternNames) }
        concrete is MezzeType.FunctionType && pattern is MezzeType.FunctionType ->
            unifyTypesForAlias(concrete.paramType, pattern.paramType, substitutions, patternNames) &&
            unifyTypesForAlias(concrete.returnType, pattern.returnType, substitutions, patternNames)
        else -> typesAreStructurallyEqual(concrete, pattern)
    }
}

private fun unifyTypes(
    concrete: MezzeType,
    pattern: MezzeType,
    substitutions: MutableMap<String, MezzeType>
): Boolean {
    return when {
        pattern is MezzeType.VarType && pattern.variable.name.startsWith("'") -> {
            val existing = substitutions[pattern.variable.name]
            if (existing == null) {
                substitutions[pattern.variable.name] = concrete
                true
            } else {
                typesAreStructurallyEqual(concrete, existing)
            }
        }

        concrete is MezzeType.RecordType && pattern is MezzeType.RecordType -> {
            concrete.fields.size == pattern.fields.size &&
            concrete.isOpen == pattern.isOpen &&
            concrete.fields.all { (name, concreteFieldType) ->
                val patternFieldType = pattern.fields[name]
                patternFieldType != null && unifyTypes(concreteFieldType, patternFieldType, substitutions)
            }
        }

        concrete is MezzeType.VariantType && pattern is MezzeType.VariantType -> {
            val canMatch = when {
                concrete.isOpen && !pattern.isOpen -> {
                    concrete.constructors.all { (name, _) -> pattern.constructors.containsKey(name) }
                }
                !concrete.isOpen && pattern.isOpen -> {
                    pattern.constructors.all { (name, _) -> concrete.constructors.containsKey(name) }
                }
                concrete.isOpen == pattern.isOpen -> {
                    concrete.constructors.all { (name, _) -> pattern.constructors.containsKey(name) }
                }
                else -> false
            }

            canMatch && concrete.constructors.all { (name, concreteConstructorType) ->
                val patternConstructorType = pattern.constructors[name]
                when {
                    concreteConstructorType == null && patternConstructorType == null -> true
                    concreteConstructorType != null && patternConstructorType != null ->
                        unifyTypes(concreteConstructorType, patternConstructorType, substitutions)
                    patternConstructorType == null -> false
                    else -> false
                }
            }
        }

        concrete is MezzeType.FunctionType && pattern is MezzeType.FunctionType -> {
            unifyTypes(concrete.paramType, pattern.paramType, substitutions) &&
            unifyTypes(concrete.returnType, pattern.returnType, substitutions) &&
            effectRowsMatch(concrete.effects, pattern.effects)
        }

        else -> typesAreStructurallyEqual(concrete, pattern)
    }
}

private fun typesAreStructurallyEqual(type1: MezzeType, type2: MezzeType): Boolean {
    return when {
        type1 == type2 -> true

        type1 is MezzeType.RecordType && type2 is MezzeType.RecordType -> {
            type1.fields.size == type2.fields.size &&
            type1.fields.all { (name, fieldType1) ->
                val fieldType2 = type2.fields[name]
                fieldType2 != null && typesAreStructurallyEqual(fieldType1, fieldType2)
            } &&
            type1.isOpen == type2.isOpen
        }

        type1 is MezzeType.VariantType && type2 is MezzeType.VariantType -> {
            type1.constructors.size == type2.constructors.size &&
            type1.constructors.all { (name, constructorType1) ->
                val constructorType2 = type2.constructors[name]
                when {
                    constructorType1 == null && constructorType2 == null -> true
                    constructorType1 != null && constructorType2 != null ->
                        typesAreStructurallyEqual(constructorType1, constructorType2)
                    else -> false
                }
            } &&
            type1.isOpen == type2.isOpen
        }

        type1 is MezzeType.FunctionType && type2 is MezzeType.FunctionType -> {
            typesAreStructurallyEqual(type1.paramType, type2.paramType) &&
            typesAreStructurallyEqual(type1.returnType, type2.returnType) &&
            effectRowsMatch(type1.effects, type2.effects)
        }

        else -> false
    }
}

private fun effectRowsMatch(
    effects1: MezzeType.EffectRow?,
    effects2: MezzeType.EffectRow?
): Boolean {
    return when {
        effects1 == null && effects2 == null -> true
        effects1 != null && effects2 != null -> effects1.effects == effects2.effects
        else -> false // One has effects, the other doesn't
    }
}

fun printConstraints(constraints: List<InspectAbilityConstraint>): String {
    if (constraints.isEmpty()) return ""

    // Group constraints by type parameter
    val grouped = constraints.groupBy { it.typeParam }

    return grouped.entries.joinToString(", ") { (typeParam, constraintList) ->
        val abilities = constraintList.map { it.abilityName }.distinct().sorted().joinToString(" + ")
        "$typeParam : $abilities"
    }
}

fun printTypeWithConstraints(type: MezzeType, constraints: List<InspectAbilityConstraint>): String {
    val typeStr = prettyPrintType(type)
    val constraintStr = printConstraints(constraints)

    return if (constraintStr.isNotEmpty()) {
        "$typeStr with $constraintStr"
    } else {
        typeStr
    }
}

private fun collectFreeTypeVars(type: MezzeType, result: MutableSet<MezzeType>) {
    when (type) {
        is MezzeType.VarType -> result.add(type)
        is MezzeType.RecordType -> type.fields.values.forEach { collectFreeTypeVars(it, result) }
        is MezzeType.VariantType -> type.constructors.values.filterNotNull().forEach { collectFreeTypeVars(it, result) }
        is MezzeType.FunctionType -> { collectFreeTypeVars(type.paramType, result); collectFreeTypeVars(type.returnType, result) }
        is MezzeType.TypeApplication -> type.args.forEach { collectFreeTypeVars(it, result) }
        is MezzeType.SeqType -> collectFreeTypeVars(type.elementType, result)
        else -> {}
    }
}
