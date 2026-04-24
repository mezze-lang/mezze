package com.mezze.language.ast

/**
 * Print a variant type, handling special cases (Bool, Option, Result) and type aliases.
 */
private fun printVariantType(
    type: MezzeType.VariantType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    // Special case: Bool variant
    if (BuiltinTypes.isBoolVariant(type)) {
        return "Bool"
    }

    // Special case: Option variant
    if (BuiltinTypes.isOptionVariant(type)) {
        val valueType = BuiltinTypes.getOptionValueType(type)
        val valueTypeStr = if (valueType != null) {
            prettyPrintWithAliasCheck(valueType, typeAliases, showExpansion, typeVarMapping)
        } else {
            "'a"
        }
        return "Option $valueTypeStr"
    }

    // Check type aliases by constructor name matching (handles recursive types where
    // structural unification fails because type vars are the same on both sides)
    for ((aliasName, aliasInfo) in typeAliases) {
        val aliasDef = aliasInfo.definition
        if (aliasDef is MezzeType.VariantType) {
            val aliasCtors = aliasDef.constructors.keys.sorted()
            val typeCtors = type.constructors.keys.sorted()
            if (aliasCtors == typeCtors) {
                // Constructors match by name — infer type args
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

    // Check type aliases via structural unification
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

    // Special case: Result variant
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

    // Regular variant type
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


/**
 * Pretty print an effect row, including effect instances with their associated types.
 *
 * Examples:
 * - Simple effects: <Log, FileSystem>
 * - Effect with associated types: <Exception having 'error = String>
 * - Mixed: <Log, Exception having 'error = String, *>
 */
private fun prettyPrintEffectRow(effectRow: MezzeType.EffectRow, typeVarMapping: Map<TypeVar, String>): String {
    // Collect all effect parts
    val parts = mutableListOf<String>()

    // Add simple effects (deduplicated by keeping shortest name variant)
    val deduped = effectRow.effects.groupBy { effect ->
        effect.substringAfterLast("::")
    }.mapValues { (_, variants) ->
        variants.minByOrNull { it.length } ?: variants.first()
    }.values.sorted()
    parts.addAll(deduped)

    // Add effect instances with their associated types
    for (instance in effectRow.effectInstances.sortedBy { it.name }) {
        // Skip if we already have this effect as a simple name
        if (instance.name in effectRow.effects || deduped.any { it == instance.name.substringAfterLast("::") }) {
            // If the simple effect is already listed but we have associated types, replace it
            if (instance.associatedTypes.isNotEmpty()) {
                // Remove the simple effect and add the instance with associated types
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
            // Effect not in simple effects set
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

    // Add row variable marker if open
    if (effectRow.isOpen) parts.add("...")

    // Add performed marker
    if (effectRow.performed) parts.add("*")

    return "<${parts.joinToString(", ")}>"
}


/**
 * Pretty printer for Mezze types with proper type variable naming
 */
fun prettyPrintType(type: MezzeType): String {
    val typeVarMapping = collectTypeVariables(type)
    return prettyPrintTypeWithMapping(type, typeVarMapping)
}

/**
 * Collect all type variables in a type and assign them readable names.
 * Uses the consolidated collectTypeVarsInOrder from TypeUtils.
 */
private fun collectTypeVariables(type: MezzeType): Map<TypeVar, String> {
    val orderedVars = collectTypeVarsInOrder(type).distinct()

    // Assign names 'a, 'b, ..., 'z, 'a1, 'b1, ..., 'z1, 'a2, etc. in order of appearance
    return orderedVars.mapIndexed { index, typeVar ->
        val letterIndex = index % 26
        val suffix = if (index >= 26) (index / 26).toString() else ""
        val letter = ('a' + letterIndex).toString()
        typeVar to "'$letter$suffix"
    }.toMap()
}

/**
 * Pretty print a type using the given type variable mapping
 */
private fun prettyPrintTypeWithMapping(type: MezzeType, typeVarMapping: Map<TypeVar, String>): String {
    // Check if it's a primitive type first
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
        } else {
            // Print as regular variant type (inline variant syntax)
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
        // Suspended types print as: valueType <effects, *>
        val valueTypeStr = prettyPrintTypeWithMapping(type.valueType, typeVarMapping)
        "$valueTypeStr ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
    }
    is MezzeType.FunctionType -> {
        // Build parameter type string - add parentheses if parameter is also a function type
        val paramTypeStr = if (type.paramType is MezzeType.FunctionType) {
            "(${prettyPrintTypeWithMapping(type.paramType, typeVarMapping)})"
        } else {
            prettyPrintTypeWithMapping(type.paramType, typeVarMapping)
        }

        // Add ability constraints after parameter type but before arrow
        // Filter out constraints that are already satisfied by concrete types
        val filteredConstraints = type.abilityConstraints.filter { constraint ->
            // Keep constraint if any of its type args is a type variable
            // Remove constraint if all type args are concrete types that already have the ability
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

        // Build return type string
        val returnTypeStr = prettyPrintTypeWithMapping(type.returnType, typeVarMapping)

        // Add effects (including performed marker and effect instances with associated types)
        val effects = if (type.effects != null && (type.effects.effects.isNotEmpty() || type.effects.effectInstances.isNotEmpty() || type.effects.performed)) {
            " ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
        } else {
            ""
        }

        // Construct final string: param with constraints -> return effects
        paramTypeStr + abilityConstraints + " -> " + returnTypeStr + effects
    }
    is MezzeType.OrderingType -> "Ordering"
    is MezzeType.EffectRow -> prettyPrintEffectRow(type, typeVarMapping)
    else -> throw IllegalArgumentException("Unexpected type in prettyPrintTypeWithMapping: $type")
}
}

/** Pretty print a type with type alias expansion support. */
fun prettyPrintTypeWithAliases(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo> = emptyMap(),
    showExpansion: Boolean = false
): String {
    val typeVarMapping = collectTypeVariables(type)

    // For variant types with a typeName, check typeAliases directly
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

    // Check if this type matches any alias (for non-recursive types)
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
        // No direct alias match - recursively apply aliases to subcomponents
        prettyPrintTypeWithAliasesRecursive(type, typeAliases, showExpansion, typeVarMapping)
    }
}

/**
 * Helper function to print a type with alias checking, using an existing type variable mapping
 */
private fun prettyPrintWithAliasCheck(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    // For variant types: match by constructor names (handles recursive types and row-var differences)
    if (type is MezzeType.VariantType) {
        // First check typeName (set by type declarations in the solver)
        if (type.typeName != null) {
            val alias = typeAliases[type.typeName]
            if (alias != null) {
                // Collect type args by finding all type vars in the concrete type
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
        // Then check by constructor name matching
        val typeCtors = type.constructors.keys.sorted()
        for ((aliasName, aliasInfo) in typeAliases) {
            val aliasDef = aliasInfo.definition
            if (aliasDef is MezzeType.VariantType && aliasDef.constructors.keys.sorted() == typeCtors) {
                if (aliasInfo.typeParams.isEmpty()) return aliasName
                // Infer type args by collecting unique type vars from concrete payloads
                val typeVars = mutableListOf<MezzeType>()
                for ((_, payload) in type.constructors) {
                    if (payload != null) collectFreeTypeVars(payload, typeVars.toMutableSet())
                }
                // Use distinct type vars as type args (in order they appear)
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
                // Fallback: just print the alias name with generic vars
                if (aliasInfo.typeParams.isNotEmpty()) {
                    val genericArgs = aliasInfo.typeParams.joinToString(" ") { "'${it.removePrefix("'")}" }
                    return "$aliasName $genericArgs"
                }
                return aliasName
            }
        }
    }

    // Check if this type matches any alias via structural unification
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
        // No direct alias match - recursively apply aliases to subcomponents
        prettyPrintTypeWithAliasesRecursive(type, typeAliases, showExpansion, typeVarMapping)
    }
}

/**
 * Recursively apply type aliases to subcomponents of a type
 */
private fun prettyPrintTypeWithAliasesRecursive(
    type: MezzeType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String {
    // Check if it's a primitive type first
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
        // Build parameter type string - add parentheses if parameter is also a function type
        val paramTypeStr = if (type.paramType is MezzeType.FunctionType) {
            "(${prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping)})"
        } else {
            prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping)
        }

        // Add ability constraints after parameter type but before arrow
        // Filter out constraints that are already satisfied by concrete types
        val filteredConstraints = type.abilityConstraints.filter { constraint ->
            // Keep constraint if any of its type args is a type variable
            // Remove constraint if all type args are concrete types that already have the ability
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

        // Build return type string
        val returnTypeStr = prettyPrintWithAliasCheck(type.returnType, typeAliases, showExpansion, typeVarMapping)

        // Add effects (including performed marker and effect instances)
        val effects = if (type.effects != null && (type.effects.effects.isNotEmpty() || type.effects.effectInstances.isNotEmpty() || type.effects.performed)) {
            " ${prettyPrintEffectRow(type.effects, typeVarMapping)}"
        } else {
            ""
        }

        // Construct final string: param with constraints -> return effects
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

/**
 * Data class for type alias information
 */
data class TypeAliasInfo(
    val name: String,
    val typeParams: List<String>,
    val definition: MezzeType
)

/**
 * Find a type alias that matches the given type
 */
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

/**
 * Result of matching a type against a type alias
 */
private data class TypeAliasMatch(
    val aliasName: String,
    val typeArgs: List<MezzeType>
)

/**
 * Try to infer type arguments by matching a concrete type against a parameterized type definition
 */
private fun inferTypeArguments(
    concreteType: MezzeType,
    parameterizedDef: MezzeType,
    typeParams: List<String>
): List<MezzeType>? {
    val substitutions = mutableMapOf<String, MezzeType>()

    if (unifyTypes(concreteType, parameterizedDef, substitutions)) {
        // Check if we found substitutions for all type parameters
        val typeArgs = typeParams.mapNotNull { param ->
            substitutions[param]
        }

        return if (typeArgs.size == typeParams.size) typeArgs else null
    }

    return null
}

/**
 * Unify two types and collect substitutions for type variables
 */
private fun unifyTypes(
    concrete: MezzeType,
    pattern: MezzeType,
    substitutions: MutableMap<String, MezzeType>
): Boolean {
    return when {
        // If pattern is a type variable, record the substitution
        pattern is MezzeType.VarType && pattern.variable.name.startsWith("'") -> {
            val existing = substitutions[pattern.variable.name]
            if (existing == null) {
                substitutions[pattern.variable.name] = concrete
                true
            } else {
                // Type variable already has a substitution - check consistency
                typesAreStructurallyEqual(concrete, existing)
            }
        }

        // Structural unification
        concrete is MezzeType.RecordType && pattern is MezzeType.RecordType -> {
            concrete.fields.size == pattern.fields.size &&
            concrete.isOpen == pattern.isOpen &&
            concrete.fields.all { (name, concreteFieldType) ->
                val patternFieldType = pattern.fields[name]
                patternFieldType != null && unifyTypes(concreteFieldType, patternFieldType, substitutions)
            }
        }

        concrete is MezzeType.VariantType && pattern is MezzeType.VariantType -> {
            // Handle open/closed variant matching for type aliases
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

        // Base case - types must be equal
        else -> typesAreStructurallyEqual(concrete, pattern)
    }
}

/**
 * Check if two types are structurally equal (ignoring names)
 */
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

/**
 * Check if two effect rows match (for type unification)
 */
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

/**
 * Print a list of display ability constraints in a readable format
 */
fun printConstraints(constraints: List<InspectAbilityConstraint>): String {
    if (constraints.isEmpty()) return ""

    // Group constraints by type parameter
    val grouped = constraints.groupBy { it.typeParam }

    return grouped.entries.joinToString(", ") { (typeParam, constraintList) ->
        val abilities = constraintList.map { it.abilityName }.distinct().sorted().joinToString(" + ")
        "$typeParam : $abilities"
    }
}

/**
 * Print a type with its constraints
 */
fun printTypeWithConstraints(type: MezzeType, constraints: List<InspectAbilityConstraint>): String {
    val typeStr = prettyPrintType(type)
    val constraintStr = printConstraints(constraints)

    return if (constraintStr.isNotEmpty()) {
        "$typeStr with $constraintStr"
    } else {
        typeStr
    }
}

/** Collect free type variables from a MezzeType. */
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
