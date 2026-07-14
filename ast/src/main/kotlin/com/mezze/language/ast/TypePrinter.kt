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
        val errorTypeStr = when (errorType) {
            is MezzeType.VariantType -> {
                val errorConstructors = errorType.constructors.entries.map { (name, dataType) ->
                    if (dataType != null) {
                        "$name ${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping)}"
                    } else {
                        name
                    }
                }
                "| ${errorConstructors.joinToString(", ")} |"
            }
            else -> prettyPrintWithAliasCheck(errorType, typeAliases, showExpansion, typeVarMapping)
        }
        return "Result $valueTypeStr $errorTypeStr"
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


fun prettyPrintType(type: MezzeType): String {
    val typeVarMapping = collectTypeVariables(type)
    return prettyPrintTypeWithMapping(type, typeVarMapping)
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
    is MezzeType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintTypeWithMapping(fieldType, typeVarMapping)}"
        }
        if (type.isOpen) {
            if (fields.isEmpty()) "{ ... }" else "{ $fields, ... }"
        } else {
            if (fields.isEmpty()) "{}" else "{ $fields }"
        }
    }
    is MezzeType.VariantType -> {
        // Special case: Bool variant prints as "Bool" instead of "| True, False |"
        if (BuiltinTypes.isBoolVariant(type)) {
            "Bool"
        } else if (type.typeName != null
            && type.typeName != "<inline>"        // #148: placeholder for inline ascriptions — render structurally
            && !type.typeName.startsWith("<")     // any other synthetic <…> placeholder
            && !BuiltinTypes.isOptionVariant(type)
            && BuiltinTypes.getResultTypes(type) == null) {
            // The solver tagged this variant with its declared name (e.g. `Tree`) and,
            // for polymorphic types, its type-arg vector. Print as `Tree <args>`.
            val argsStr = if (type.typeArgs.isEmpty()) "" else " " + type.typeArgs.joinToString(" ") {
                prettyPrintTypeWithMapping(it, typeVarMapping)
            }
            "${type.typeName}$argsStr"
        } else {
            // Anonymous / inline variant (no name) — print structurally.
            val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
                when (dataType) {
                    null -> name
                    is MezzeType.RecordType -> "$name ${prettyPrintTypeWithMapping(dataType, typeVarMapping)}"
                    else -> "$name(${prettyPrintTypeWithMapping(dataType, typeVarMapping)})"
                }
            }
            if (type.isOpen) {
                "| $constructors, ... |"
            } else {
                "| $constructors |"
            }
        }
    }
    is MezzeType.SeqType -> "[${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}]"
    is MezzeType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintTypeWithMapping(it, typeVarMapping) }
        if (argsStr.isEmpty()) type.constructor else "${type.constructor} $argsStr"
    }
    is MezzeType.ListType -> "List ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is MezzeType.MapType -> "Map ${prettyPrintTypeWithMapping(type.keyType, typeVarMapping)} ${prettyPrintTypeWithMapping(type.valueType, typeVarMapping)}"
    is MezzeType.SetType -> "Set ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is MezzeType.SortedMapType -> "SortedMap ${prettyPrintTypeWithMapping(type.keyType, typeVarMapping)} ${prettyPrintTypeWithMapping(type.valueType, typeVarMapping)}"
    is MezzeType.SortedSetType -> "SortedSet ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is MezzeType.SuspendedType -> {
        val valueTypeStr = prettyPrintTypeWithMapping(type.valueType, typeVarMapping)
        "$valueTypeStr ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
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
                if (typeArgs.isNotEmpty()) {
                    "${constraint.abilityName} $typeArgs"
                } else {
                    constraint.abilityName
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
    is MezzeType.OrderingType -> "Ordering"
    is MezzeType.EffectRow -> prettyPrintEffectRow(type, typeVarMapping)
    else -> throw IllegalArgumentException("Unexpected type in prettyPrintTypeWithMapping: $type")
}
}

fun prettyPrintTypeWithAliases(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo> = emptyMap(),
    showExpansion: Boolean = false
): String {
    val typeVarMapping = collectTypeVariables(type)

    if (type is MezzeType.VariantType && type.typeName != null) {
        val alias = typeAliases[type.typeName]
        if (alias != null) {
            val typeVars = mutableSetOf<MezzeType>()
            for ((_, payload) in type.constructors) {
                if (payload != null) collectFreeTypeVars(payload, typeVars)
            }
            val argsStr = if (typeVars.isEmpty()) "" else " " + typeVars.joinToString(" ") {
                prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
            }
            val aliasName = type.typeName + argsStr
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
        if (type.typeName != null) {
            val alias = typeAliases[type.typeName]
            if (alias != null) {
                val typeVars = mutableSetOf<MezzeType>()
                for ((_, payload) in type.constructors) {
                    if (payload != null) collectFreeTypeVars(payload, typeVars)
                }
                val argsStr = if (typeVars.isEmpty()) "" else " " + typeVars.joinToString(" ") {
                    prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping)
                }
                return type.typeName + argsStr
            }
        }
        val typeCtors = type.constructors.keys.sorted()
        for ((aliasName, aliasInfo) in typeAliases) {
            val aliasDef = aliasInfo.definition
            if (aliasDef is MezzeType.VariantType && aliasDef.constructors.keys.sorted() == typeCtors) {
                if (aliasInfo.typeParams.isEmpty()) return aliasName
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
    is MezzeType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintWithAliasCheck(fieldType, typeAliases, showExpansion, typeVarMapping)}"
        }
        if (type.isOpen) {
            if (fields.isEmpty()) "{ ... }" else "{ $fields, ... }"
        } else {
            if (fields.isEmpty()) "{}" else "{ $fields }"
        }
    }
    is MezzeType.VariantType -> {
        printVariantType(type, typeAliases, showExpansion, typeVarMapping)
    }
    is MezzeType.SeqType -> "[${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping)}]"
    is MezzeType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping) }
        if (argsStr.isEmpty()) type.constructor else "${type.constructor} $argsStr"
    }
    is MezzeType.ListType -> "List ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping)}"
    is MezzeType.MapType -> "Map ${prettyPrintWithAliasCheck(type.keyType, typeAliases, showExpansion, typeVarMapping)} ${prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping)}"
    is MezzeType.SetType -> "Set ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping)}"
    is MezzeType.SortedMapType -> "SortedMap ${prettyPrintWithAliasCheck(type.keyType, typeAliases, showExpansion, typeVarMapping)} ${prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping)}"
    is MezzeType.SortedSetType -> "SortedSet ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping)}"
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
                if (typeArgs.isNotEmpty()) {
                    "${constraint.abilityName} $typeArgs"
                } else {
                    constraint.abilityName
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
    is MezzeType.SuspendedType -> {
        val valueTypeStr = prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping)
        "$valueTypeStr ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
    }
    is MezzeType.EffectRow -> prettyPrintEffectRow(type, typeVarMapping)
    else -> throw IllegalArgumentException("Unexpected type in prettyPrintTypeWithAliasesRecursive: $type")
}
}

data class TypeAliasInfo(
    val name: String,
    val typeParams: List<String>,
    val definition: MezzeType
)

private fun findTypeAliasForType(type: MezzeType, typeAliases: Map<String, TypeAliasInfo>): TypeAliasMatch? {
    for ((_, aliasInfo) in typeAliases) {
        if (aliasInfo.typeParams.isEmpty()) {
            // Simple type alias without parameters - use unification logic
            val substitutions = mutableMapOf<String, MezzeType>()
            if (unifyTypes(type, aliasInfo.definition, substitutions)) {
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

    if (unifyTypes(concreteType, parameterizedDef, substitutions)) {
        val typeArgs = typeParams.mapNotNull { param ->
            substitutions[param]
        }

        return if (typeArgs.size == typeParams.size) typeArgs else null
    }

    return null
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
        is MezzeType.ListType -> collectFreeTypeVars(type.elementType, result)
        else -> {}
    }
}
