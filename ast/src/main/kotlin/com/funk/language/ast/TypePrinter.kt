package com.funk.language.ast

// ============================================================================
// VARIANT TYPE HELPERS
// ============================================================================

/**
 * Check if a variant type represents the built-in Bool type.
 */
private fun isBoolVariant(type: FunkType.VariantType): Boolean =
    type.constructors.size == 2 &&
    type.constructors.containsKey("True") &&
    type.constructors.containsKey("False") &&
    type.constructors["True"] == null &&
    type.constructors["False"] == null &&
    !type.isOpen

/**
 * Check if a variant type represents the Option type.
 */
private fun isOptionVariant(type: FunkType.VariantType): Boolean =
    type.constructors.size == 2 &&
    type.constructors.containsKey("Some") &&
    type.constructors.containsKey("None") &&
    type.constructors["None"] == null &&
    !type.isOpen

/**
 * Check if a variant type represents the Result type.
 * Returns the value type and error type if it's a proper Result, null otherwise.
 */
private fun getResultTypes(type: FunkType.VariantType): Pair<FunkType, FunkType>? {
    if (type.constructors.size != 2 || type.isOpen) return null

    val okType = type.constructors["Ok"] ?: return null
    val errType = type.constructors["Err"] ?: return null

    // Extract value type from Ok { val: T }
    val valueType = when (okType) {
        is FunkType.RecordType -> okType.fields["val"]
        else -> null
    } ?: return null

    // Extract error type from Err { error: E }
    val errorType = when (errType) {
        is FunkType.RecordType -> errType.fields["error"]
        else -> null
    } ?: return null

    return Pair(valueType, errorType)
}

/**
 * Extract the value type from an Option's Some constructor.
 */
private fun getOptionValueType(type: FunkType.VariantType): FunkType? {
    val someType = type.constructors["Some"] ?: return null
    return when (someType) {
        is FunkType.RecordType -> someType.fields["val"]
        else -> someType
    }
}

/**
 * Print a variant type, handling special cases (Bool, Option, Result) and named type declarations.
 */
private fun printVariantType(
    type: FunkType.VariantType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>,
    typeDeclarations: List<TypeDeclaration>
): String {
    // Special case: Bool variant
    if (isBoolVariant(type)) {
        return "Bool"
    }

    // Special case: Option variant
    if (isOptionVariant(type)) {
        val valueType = getOptionValueType(type)
        val valueTypeStr = if (valueType != null) {
            prettyPrintWithAliasCheck(valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
        } else {
            "'a"
        }
        return "Option $valueTypeStr"
    }

    // Check if this variant matches a known type declaration
    val declMatch = findTypeDeclarationForVariant(type, typeDeclarations, typeVarMapping)
    if (declMatch != null) {
        val typeArgsStr = if (declMatch.typeArgs.isEmpty()) {
            ""
        } else {
            " " + declMatch.typeArgs.joinToString(" ") {
                prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
            }
        }
        return declMatch.declName + typeArgsStr
    }

    // Special case: Result variant
    val resultTypes = getResultTypes(type)
    if (resultTypes != null) {
        val (valueType, errorType) = resultTypes
        val valueTypeStr = prettyPrintWithAliasCheck(valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
        val errorTypeStr = when (errorType) {
            is FunkType.VariantType -> {
                val errorConstructors = errorType.constructors.entries.map { (name, dataType) ->
                    if (dataType != null) {
                        "$name ${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
                    } else {
                        name
                    }
                }
                "| ${errorConstructors.joinToString(", ")} |"
            }
            else -> prettyPrintWithAliasCheck(errorType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
        }
        return "Result $valueTypeStr $errorTypeStr"
    }

    // Regular variant type
    val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
        when (dataType) {
            null -> name
            is FunkType.RecordType -> "$name ${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
            else -> "$name(${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)})"
        }
    }
    return if (type.isOpen) {
        "| $constructors, ... |"
    } else {
        "| $constructors |"
    }
}

// ============================================================================
// PRETTY PRINTING
// ============================================================================

/**
 * Pretty printer for Funk types with proper type variable naming
 */
fun prettyPrintType(type: FunkType): String {
    val typeVarMapping = collectTypeVariables(type)
    return prettyPrintTypeWithMapping(type, typeVarMapping)
}

/**
 * Collect all type variables in a type and assign them readable names.
 * Uses the consolidated collectTypeVarsInOrder from TypeUtils.
 */
private fun collectTypeVariables(type: FunkType): Map<TypeVar, String> {
    val orderedVars = collectTypeVarsInOrder(type).distinct()

    // Assign names 'a, 'b, 'c, etc. in order of appearance
    return orderedVars.mapIndexed { index, typeVar ->
        val letter = ('a' + index).toString()
        typeVar to "'$letter"
    }.toMap()
}

/**
 * Pretty print a type using the given type variable mapping
 */
private fun prettyPrintTypeWithMapping(type: FunkType, typeVarMapping: Map<TypeVar, String>): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Bool"
    is FunkType.NatType -> "Nat"
    is FunkType.FracType -> "Frac"
    is FunkType.CharType -> "Char"
    is FunkType.VarType -> typeVarMapping[type.variable] ?: type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintTypeWithMapping(fieldType, typeVarMapping)}"
        }
        if (type.isOpen) {
            if (fields.isEmpty()) "{ ... }" else "{ $fields, ... }"
        } else {
            if (fields.isEmpty()) "{}" else "{ $fields }"
        }
    }
    is FunkType.VariantType -> {
        // Special case: Bool variant prints as "Bool" instead of "| True, False |"
        if (isBoolVariant(type)) {
            "Bool"
        } else {
            // Print as regular variant type (inline variant syntax)
            val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
                when (dataType) {
                    null -> name
                    is FunkType.RecordType -> "$name ${prettyPrintTypeWithMapping(dataType, typeVarMapping)}"
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
    is FunkType.ArrayType -> "[${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}]"
    is FunkType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintTypeWithMapping(it, typeVarMapping) }
        if (argsStr.isEmpty()) type.constructor else "${type.constructor} $argsStr"
    }
    is FunkType.ListType -> "List ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is FunkType.MapType -> "Map ${prettyPrintTypeWithMapping(type.keyType, typeVarMapping)} ${prettyPrintTypeWithMapping(type.valueType, typeVarMapping)}"
    is FunkType.SetType -> "Set ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is FunkType.SortedMapType -> "SortedMap ${prettyPrintTypeWithMapping(type.keyType, typeVarMapping)} ${prettyPrintTypeWithMapping(type.valueType, typeVarMapping)}"
    is FunkType.SortedSetType -> "SortedSet ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is FunkType.SuspendedType -> {
        // Suspended types print as: valueType <effects>
        val valueTypeStr = prettyPrintTypeWithMapping(type.valueType, typeVarMapping)
        val effectList = type.effects.effects.sorted().joinToString(", ")
        "$valueTypeStr <$effectList>"
    }
    is FunkType.FunctionType -> {
        // Build parameter type string - add parentheses if parameter is also a function type
        val paramTypeStr = if (type.paramType is FunkType.FunctionType) {
            "(${prettyPrintTypeWithMapping(type.paramType, typeVarMapping)})"
        } else {
            prettyPrintTypeWithMapping(type.paramType, typeVarMapping)
        }

        // Add ability constraints after parameter type but before arrow
        // Filter out constraints that are already satisfied by concrete types
        val filteredConstraints = type.abilityConstraints.filter { constraint ->
            // Keep constraint if any of its type args is a type variable
            // Remove constraint if all type args are concrete types that already have the ability
            constraint.typeArgs.any { arg -> arg is FunkType.VarType }
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
        
        // Add effects (including performed marker)
        val effects = if (type.effects != null && (type.effects.effects.isNotEmpty() || type.effects.performed)) {
            // Deduplicate effects by keeping shortest name variant
            // E.g., if we have both "FileSystem" and "filesystem::FileSystem", keep "FileSystem"
            val deduped = type.effects.effects.groupBy { effect ->
                // Group by the simple name (last component after ::)
                effect.substringAfterLast("::")
            }.mapValues { (_, variants) ->
                // Keep the shortest variant (usually the unqualified name)
                variants.minByOrNull { it.length } ?: variants.first()
            }.values.sorted()

            val parts = mutableListOf<String>()
            parts.addAll(deduped)
            if (type.effects.isOpen) parts.add("...")
            if (type.effects.performed) parts.add("*")
            " <${parts.joinToString(", ")}>"
        } else {
            ""
        }

        // Construct final string: param with constraints -> return effects
        paramTypeStr + abilityConstraints + " -> " + returnTypeStr + effects
    }
    is FunkType.OrderingType -> "Ordering"
    is FunkType.BytesType -> "Bytes"
    is FunkType.ByteBufferType -> "ByteBuffer"
    is FunkType.EffectRow -> {
        val parts = mutableListOf<String>()
        parts.addAll(type.effects.sorted())
        if (type.isOpen) parts.add("...")
        if (type.performed) parts.add("*")
        "<${parts.joinToString(", ")}>"
    }
}

/**
 * Helper to match a variant type against type declarations and extract type arguments
 */
private data class TypeDeclMatch(val declName: String, val typeArgs: List<FunkType>)

private fun findTypeDeclarationForVariant(
    variantType: FunkType.VariantType,
    typeDeclarations: List<TypeDeclaration>,
    typeVarMapping: Map<TypeVar, String>
): TypeDeclMatch? {
    for (decl in typeDeclarations) {
        val variantDef = decl.definition as? VariantDefinition ?: continue

        val declConstructors = variantDef.constructors.map { it.name }.toSet()
        val typeConstructors = variantType.constructors.keys

        // CRITICAL FIX: Allow the inferred type to be a subset of the declared type
        // Example: | Node ... | should match Tree if Tree = | Leaf, Node ... |
        // This handles cases where only some constructors are used
        if (!typeConstructors.all { it in declConstructors }) {
            continue
        }

        // Additional check: For exact matches (all constructors present), require exact equality
        // For subset matches (some constructors missing), allow it
        val isSubsetMatch = typeConstructors.size < declConstructors.size
        val isExactMatch = typeConstructors.size == declConstructors.size && declConstructors == typeConstructors

        if (!isSubsetMatch && !isExactMatch) {
            continue
        }

        // If no type parameters, it's a simple match
        if (decl.typeParams.isEmpty()) {
            return TypeDeclMatch(decl.name, emptyList())
        }

        // Try to extract type arguments by examining constructor types
        // For simplicity, extract type variables that appear in the variant type
        val typeArgs = extractTypeArgumentsFromVariant(variantType, decl.typeParams.size, typeVarMapping)
        if (typeArgs != null) {
            return TypeDeclMatch(decl.name, typeArgs)
        }

        // CRITICAL FIX: For concrete types, infer type arguments by matching structure
        // Example: | Node { value: Int, ... } | matches Tree 'a if we can infer 'a = Int
        if (decl.typeParams.isNotEmpty()) {
            val inferredArgs = inferTypeArgsFromStructure(variantType, variantDef, decl.typeParams)
            if (inferredArgs != null) {
                return TypeDeclMatch(decl.name, inferredArgs)
            }
        }

        // FALLBACK: If we have the right constructors but couldn't extract type args,
        // try a more lenient approach - collect all distinct type vars and use those
        if (decl.typeParams.size == 1) {
            // For single-parameter types, collect all type variables that appear
            val allTypeVarsList = variantType.constructors.values
                .filterNotNull()
                .flatMap { collectTypeVarsInOrder(it, sortFields = false) }
            // Get the most common type variable (heuristic: the "main" type parameter)
            val typeVarCounts = allTypeVarsList.groupingBy { it }.eachCount()
            val mostCommonVar = typeVarCounts.maxByOrNull { it.value }?.key
            if (mostCommonVar != null) {
                return TypeDeclMatch(decl.name, listOf(FunkType.VarType(mostCommonVar)))
            }
        }
    }
    return null
}

private fun extractTypeArgumentsFromVariant(
    variantType: FunkType.VariantType,
    expectedParamCount: Int,
    typeVarMapping: Map<TypeVar, String>
): List<FunkType>? {
    // Collect all type variables from the variant constructors using consolidated function
    val typeVars = variantType.constructors.values
        .filterNotNull()
        .flatMap { collectTypeVarsInOrder(it, sortFields = false) }

    // Get unique type variables in order of appearance
    val uniqueTypeVars = typeVars.distinctBy { typeVarMapping[it] ?: it.name }

    if (uniqueTypeVars.size != expectedParamCount) {
        return null
    }

    return uniqueTypeVars.map { FunkType.VarType(it) }
}

/**
 * Infer type arguments from structural matching for concrete types.
 * This handles cases like: | Node { value: Int, ...} | matching Tree 'a where we infer 'a = Int
 */
private fun inferTypeArgsFromStructure(
    variantType: FunkType.VariantType,
    variantDef: VariantDefinition,
    typeParams: List<String>
): List<FunkType>? {
    // Find a constructor that exists in both the inferred type and the declaration
    val commonConstructor = variantType.constructors.keys.firstOrNull { constructorName ->
        variantDef.constructors.any { it.name == constructorName }
    } ?: return null

    val inferredPayload = variantType.constructors[commonConstructor]
    val declConstructor = variantDef.constructors.find { it.name == commonConstructor } ?: return null

    // If there's no payload, we can't infer type arguments
    if (inferredPayload == null || declConstructor.dataType == null) {
        return null
    }

    // Match the payloads and extract type arguments
    // For single-parameter types, look for a simple field that contains the type param
    if (typeParams.size == 1 && inferredPayload is FunkType.RecordType && declConstructor.dataType is RecordTypeAnnotation) {
        val typeParam = typeParams[0]

        // Find a field in the declaration that has type 'a (the type parameter)
        for (declField in declConstructor.dataType.entries) {
            if (declField.type is IdentifierTypeAnnotation && declField.type.name == typeParam) {
                // Found a field with type parameter - get the concrete type from the inferred payload
                val concreteType = inferredPayload.fields[declField.name]
                if (concreteType != null) {
                    return listOf(concreteType)
                }
            }
        }
    }

    return null
}

/**
 * Pretty printer for Funk types with type alias expansion support
 */
fun prettyPrintTypeWithAliases(
    type: FunkType,
    typeAliases: Map<String, TypeAliasInfo> = emptyMap(),
    showExpansion: Boolean = false,
    typeDeclarations: List<TypeDeclaration> = emptyList()
): String {
    val typeVarMapping = collectTypeVariables(type)

    // CRITICAL FIX: For variant types, check typeDeclarations FIRST
    // This handles recursive types like Tree 'a which can't be matched via unification
    if (type is FunkType.VariantType) {
        val declMatch = findTypeDeclarationForVariant(type, typeDeclarations, typeVarMapping)
        if (declMatch != null) {
            val typeArgsStr = if (declMatch.typeArgs.isEmpty()) {
                ""
            } else {
                " " + declMatch.typeArgs.joinToString(" ") {
                    prettyPrintTypeWithAliases(it, typeAliases, showExpansion, typeDeclarations)
                }
            }
            val aliasName = declMatch.declName + typeArgsStr

            return if (showExpansion) {
                "$aliasName (= ${prettyPrintTypeWithMapping(type, typeVarMapping)})"
            } else {
                aliasName
            }
        }
    }

    // Second, check if this type matches any alias (for non-recursive types)
    val aliasMatch = findTypeAliasForType(type, typeAliases)

    return if (aliasMatch != null) {
        val aliasName = if (aliasMatch.typeArgs.isEmpty()) {
            aliasMatch.aliasName
        } else {
            val typeArgsStr = aliasMatch.typeArgs.joinToString(" ") { prettyPrintTypeWithAliases(it, typeAliases, showExpansion, typeDeclarations) }
            "${aliasMatch.aliasName} $typeArgsStr"
        }

        if (showExpansion) {
            "$aliasName (= ${prettyPrintTypeWithMapping(type, typeVarMapping)})"
        } else {
            aliasName
        }
    } else {
        // No direct alias match - recursively apply aliases to subcomponents
        prettyPrintTypeWithAliasesRecursive(type, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
    }
}

/**
 * Helper function to print a type with alias checking, using an existing type variable mapping
 */
private fun prettyPrintWithAliasCheck(
    type: FunkType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>,
    typeDeclarations: List<TypeDeclaration> = emptyList()
): String {
    // First, check if this type matches any alias
    val aliasMatch = findTypeAliasForType(type, typeAliases)

    return if (aliasMatch != null) {
        val aliasName = if (aliasMatch.typeArgs.isEmpty()) {
            aliasMatch.aliasName
        } else {
            val typeArgsStr = aliasMatch.typeArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping, typeDeclarations) }
            "${aliasMatch.aliasName} $typeArgsStr"
        }

        if (showExpansion) {
            "$aliasName (= ${prettyPrintTypeWithMapping(type, typeVarMapping)})"
        } else {
            aliasName
        }
    } else {
        // No direct alias match - recursively apply aliases to subcomponents
        prettyPrintTypeWithAliasesRecursive(type, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
    }
}

/**
 * Recursively apply type aliases to subcomponents of a type
 */
private fun prettyPrintTypeWithAliasesRecursive(
    type: FunkType,
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>,
    typeDeclarations: List<TypeDeclaration> = emptyList()
): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Bool"
    is FunkType.NatType -> "Nat"
    is FunkType.FracType -> "Frac"
    is FunkType.CharType -> "Char"
    is FunkType.VarType -> typeVarMapping[type.variable] ?: type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintWithAliasCheck(fieldType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
        }
        if (type.isOpen) {
            if (fields.isEmpty()) "{ ... }" else "{ $fields, ... }"
        } else {
            if (fields.isEmpty()) "{}" else "{ $fields }"
        }
    }
    is FunkType.VariantType -> {
        printVariantType(type, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
    }
    is FunkType.ArrayType -> "[${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}]"
    is FunkType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping, typeDeclarations) }
        if (argsStr.isEmpty()) type.constructor else "${type.constructor} $argsStr"
    }
    is FunkType.ListType -> "List ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.MapType -> "Map ${prettyPrintWithAliasCheck(type.keyType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)} ${prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.SetType -> "Set ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.SortedMapType -> "SortedMap ${prettyPrintWithAliasCheck(type.keyType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)} ${prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.SortedSetType -> "SortedSet ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.FunctionType -> {
        // Build parameter type string - add parentheses if parameter is also a function type
        val paramTypeStr = if (type.paramType is FunkType.FunctionType) {
            "(${prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)})"
        } else {
            prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
        }

        // Add ability constraints after parameter type but before arrow
        // Filter out constraints that are already satisfied by concrete types
        val filteredConstraints = type.abilityConstraints.filter { constraint ->
            // Keep constraint if any of its type args is a type variable
            // Remove constraint if all type args are concrete types that already have the ability
            constraint.typeArgs.any { arg -> arg is FunkType.VarType }
        }

        val abilityConstraints = if (filteredConstraints.isNotEmpty()) {
            " with " + filteredConstraints.joinToString(", ") { constraint ->
                val typeArgs = constraint.typeArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping, typeDeclarations) }
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
        val returnTypeStr = prettyPrintWithAliasCheck(type.returnType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)

        // Add effects (including performed marker)
        val effects = if (type.effects != null && (type.effects.effects.isNotEmpty() || type.effects.performed)) {
            // Deduplicate effects by keeping shortest name variant
            // E.g., if we have both "FileSystem" and "filesystem::FileSystem", keep "FileSystem"
            val deduped = type.effects.effects.groupBy { effect ->
                // Group by the simple name (last component after ::)
                effect.substringAfterLast("::")
            }.mapValues { (_, variants) ->
                // Keep the shortest variant (usually the unqualified name)
                variants.minByOrNull { it.length } ?: variants.first()
            }.values.sorted()

            val parts = mutableListOf<String>()
            parts.addAll(deduped)
            if (type.effects.isOpen) parts.add("...")
            if (type.effects.performed) parts.add("*")
            " <${parts.joinToString(", ")}>"
        } else {
            ""
        }

        // Construct final string: param with constraints -> return effects
        paramTypeStr + abilityConstraints + " -> " + returnTypeStr + effects
    }
    is FunkType.SuspendedType -> {
        // CRITICAL FIX: Suspended types should print their effects!
        // Example: String <Log, FileSystem>
        // These represent values with unhandled effects
        val valueTypeStr = prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
        val parts = mutableListOf<String>()
        parts.addAll(type.effects.effects.sorted())
        if (type.effects.isOpen) parts.add("...")
        if (type.effects.performed) parts.add("*")
        "$valueTypeStr <${parts.joinToString(", ")}>"
    }
    is FunkType.OrderingType -> "Ordering"
    is FunkType.BytesType -> "Bytes"
    is FunkType.ByteBufferType -> "ByteBuffer"
    is FunkType.EffectRow -> {
        val parts = mutableListOf<String>()
        parts.addAll(type.effects.sorted())
        if (type.isOpen) parts.add("...")
        if (type.performed) parts.add("*")
        "<${parts.joinToString(", ")}>"
    }
}

/**
 * Data class for type alias information
 */
data class TypeAliasInfo(
    val name: String,
    val typeParams: List<String>,
    val definition: FunkType
)

/**
 * Find a type alias that matches the given type
 */
private fun findTypeAliasForType(type: FunkType, typeAliases: Map<String, TypeAliasInfo>): TypeAliasMatch? {
    for ((_, aliasInfo) in typeAliases) {
        if (aliasInfo.typeParams.isEmpty()) {
            // Simple type alias without parameters - use unification logic
            val substitutions = mutableMapOf<String, FunkType>()
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
    val typeArgs: List<FunkType>
)

/**
 * Try to infer type arguments by matching a concrete type against a parameterized type definition
 */
private fun inferTypeArguments(
    concreteType: FunkType,
    parameterizedDef: FunkType,
    typeParams: List<String>
): List<FunkType>? {
    val substitutions = mutableMapOf<String, FunkType>()
    
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
    concrete: FunkType,
    pattern: FunkType,
    substitutions: MutableMap<String, FunkType>
): Boolean {
    return when {
        // If pattern is a type variable, record the substitution
        pattern is FunkType.VarType && pattern.variable.name.startsWith("'") -> {
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
        concrete is FunkType.RecordType && pattern is FunkType.RecordType -> {
            concrete.fields.size == pattern.fields.size &&
            concrete.isOpen == pattern.isOpen &&
            concrete.fields.all { (name, concreteFieldType) ->
                val patternFieldType = pattern.fields[name]
                patternFieldType != null && unifyTypes(concreteFieldType, patternFieldType, substitutions)
            }
        }
        
        concrete is FunkType.VariantType && pattern is FunkType.VariantType -> {
            // Handle open/closed variant matching for type aliases
            val canMatch = when {
                concrete.isOpen && !pattern.isOpen -> {
                    // Open concrete (like | Red, ... |) can match closed pattern (like | Red, Blue |)
                    // if all concrete constructors exist in the pattern
                    concrete.constructors.all { (name, _) -> pattern.constructors.containsKey(name) }
                }
                !concrete.isOpen && pattern.isOpen -> {
                    // Closed concrete can match open pattern if all pattern constructors exist in concrete
                    pattern.constructors.all { (name, _) -> concrete.constructors.containsKey(name) }
                }
                concrete.isOpen == pattern.isOpen -> {
                    // Both same openness - for type aliases, allow subset matching
                    if (!concrete.isOpen) {
                        // Both closed - concrete can be a subset of pattern (for type alias matching)
                        concrete.constructors.all { (name, _) -> pattern.constructors.containsKey(name) }
                    } else {
                        // Both open - concrete must be subset of pattern
                        concrete.constructors.all { (name, _) -> pattern.constructors.containsKey(name) }
                    }
                }
                else -> false
            }
            
            // Check that all concrete constructors unify with their pattern counterparts
            canMatch && concrete.constructors.all { (name, concreteConstructorType) ->
                val patternConstructorType = pattern.constructors[name]
                when {
                    concreteConstructorType == null && patternConstructorType == null -> true
                    concreteConstructorType != null && patternConstructorType != null ->
                        unifyTypes(concreteConstructorType, patternConstructorType, substitutions)
                    patternConstructorType == null -> false // concrete has constructor not in pattern
                    else -> false
                }
            }
        }
        
        concrete is FunkType.FunctionType && pattern is FunkType.FunctionType -> {
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
private fun typesAreStructurallyEqual(type1: FunkType, type2: FunkType): Boolean {
    return when {
        type1 == type2 -> true
        
        type1 is FunkType.RecordType && type2 is FunkType.RecordType -> {
            type1.fields.size == type2.fields.size &&
            type1.fields.all { (name, fieldType1) ->
                val fieldType2 = type2.fields[name]
                fieldType2 != null && typesAreStructurallyEqual(fieldType1, fieldType2)
            } &&
            type1.isOpen == type2.isOpen
        }
        
        type1 is FunkType.VariantType && type2 is FunkType.VariantType -> {
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
        
        type1 is FunkType.FunctionType && type2 is FunkType.FunctionType -> {
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
    effects1: FunkType.EffectRow?,
    effects2: FunkType.EffectRow?
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
fun printTypeWithConstraints(type: FunkType, constraints: List<InspectAbilityConstraint>): String {
    val typeStr = prettyPrintType(type)
    val constraintStr = printConstraints(constraints)
    
    return if (constraintStr.isNotEmpty()) {
        "$typeStr with $constraintStr"
    } else {
        typeStr
    }
}

/**
 * Print a function type with its constraints
 */
fun printFunctionTypeWithConstraints(type: FunkType.FunctionType, constraints: List<InspectAbilityConstraint>): String {
    return printTypeWithConstraints(type, constraints)
}

/**
 * Print a type scheme
 */
fun printTypeScheme(scheme: TypeScheme): String {
    return if (scheme.isMonomorphic) {
        prettyPrintType(scheme.type)
    } else {
        val vars = scheme.quantifiedVars.map { "'${it.name}" }.sorted().joinToString(" ")
        "∀$vars. ${prettyPrintType(scheme.type)}"
    }
}

/**
 * Check if a type is a built-in type (as opposed to a user-defined or stdlib type)
 */
fun isBuiltinType(type: FunkType): Boolean {
    return when (type) {
        // Primitive built-in types
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType, is FunkType.BytesType, is FunkType.ByteBufferType,
        is FunkType.CharType -> true

        // Core built-in types
        is FunkType.ListType, is FunkType.MapType, is FunkType.SetType,
        is FunkType.SortedMapType, is FunkType.SortedSetType -> true

        // Function types are built-in constructs
        is FunkType.FunctionType -> true

        // Suspended types are built-in constructs (values with unhandled effects)
        is FunkType.SuspendedType -> true

        // Effect rows are built-in constructs
        is FunkType.EffectRow -> true

        // Type variables and other constructs are not built-in types per se
        is FunkType.VarType -> false

        // Record and variant types are user-defined (unless they're built-in constructors)
        is FunkType.RecordType, is FunkType.VariantType -> false

        // Array types
        is FunkType.ArrayType -> false

        // TypeApplication (e.g., Option Int) - check if constructor is built-in
        is FunkType.TypeApplication -> type.constructor in setOf("Option", "Result")
    }
}

/**
 * Get the name of a built-in type for display purposes
 */
fun getBuiltinTypeName(type: FunkType): String? {
    return when (type) {
        is FunkType.IntType -> "Int"
        is FunkType.FloatType -> "Float"
        is FunkType.StringType -> "String"
        is FunkType.BooleanType -> "Bool"
        is FunkType.NatType -> "Nat"
        is FunkType.FracType -> "Frac"
        is FunkType.OrderingType -> "Ordering"
        is FunkType.ListType -> "List"
        is FunkType.MapType -> "Map"
        is FunkType.SetType -> "Set"
        is FunkType.TypeApplication -> if (type.constructor in setOf("Option", "Result")) type.constructor else null
        else -> null
    }
}

/**
 * Pretty print a type with built-in type highlighting
 * Returns a pair of (printed type, is built-in)
 */
fun prettyPrintTypeWithBuiltinInfo(type: FunkType): Pair<String, Boolean> {
    val typeStr = prettyPrintType(type)
    val isBuiltin = isBuiltinType(type)
    return Pair(typeStr, isBuiltin)
}

/**
 * Check if two types are alpha-equivalent (structurally equal modulo variable renaming)
 * Also handles record field ordering - fields can be in any order as long as they match
 */
fun areTypesAlphaEquivalent(type1: FunkType, type2: FunkType): Boolean {
    return alphaEquivHelper(type1, type2, mutableMapOf(), mutableMapOf())
}

private fun alphaEquivHelper(
    type1: FunkType,
    type2: FunkType,
    mapping1to2: MutableMap<TypeVar, TypeVar>,
    mapping2to1: MutableMap<TypeVar, TypeVar>
): Boolean {
    return when {
        // Base types - exact match
        type1 is FunkType.IntType && type2 is FunkType.IntType -> true
        type1 is FunkType.FloatType && type2 is FunkType.FloatType -> true
        type1 is FunkType.StringType && type2 is FunkType.StringType -> true
        type1 is FunkType.BooleanType && type2 is FunkType.BooleanType -> true
        type1 is FunkType.NatType && type2 is FunkType.NatType -> true
        type1 is FunkType.FracType && type2 is FunkType.FracType -> true
        type1 is FunkType.OrderingType && type2 is FunkType.OrderingType -> true

        // Type variables - check consistent renaming
        type1 is FunkType.VarType && type2 is FunkType.VarType -> {
            val existingMapping = mapping1to2[type1.variable]
            val existingReverseMapping = mapping2to1[type2.variable]

            when {
                existingMapping != null && existingReverseMapping != null -> {
                    // Both variables already mapped - check consistency
                    existingMapping == type2.variable && existingReverseMapping == type1.variable
                }
                existingMapping == null && existingReverseMapping == null -> {
                    // Neither mapped yet - create new mapping
                    mapping1to2[type1.variable] = type2.variable
                    mapping2to1[type2.variable] = type1.variable
                    true
                }
                else -> false // Inconsistent mapping
            }
        }

        // Function types
        type1 is FunkType.FunctionType && type2 is FunkType.FunctionType -> {
            // Check parameter and return types
            alphaEquivHelper(type1.paramType, type2.paramType, mapping1to2, mapping2to1) &&
            alphaEquivHelper(type1.returnType, type2.returnType, mapping1to2, mapping2to1) &&
            // Check ability constraints (must match in count and structure)
            type1.abilityConstraints.size == type2.abilityConstraints.size &&
            type1.abilityConstraints.zip(type2.abilityConstraints).all { (c1, c2) ->
                c1.abilityName == c2.abilityName &&
                c1.typeArgs.size == c2.typeArgs.size &&
                c1.typeArgs.zip(c2.typeArgs).all { (a1, a2) ->
                    alphaEquivHelper(a1, a2, mapping1to2, mapping2to1)
                }
            } &&
            // Check effect rows (both nullable)
            when {
                type1.effects == null && type2.effects == null -> true
                type1.effects != null && type2.effects != null ->
                    alphaEquivHelper(type1.effects, type2.effects, mapping1to2, mapping2to1)
                else -> false
            }
        }

        // Record types - fields can be in any order!
        type1 is FunkType.RecordType && type2 is FunkType.RecordType -> {
            type1.isOpen == type2.isOpen &&
            type1.fields.size == type2.fields.size &&
            type1.fields.all { (name, fieldType1) ->
                val fieldType2 = type2.fields[name]
                fieldType2 != null && alphaEquivHelper(fieldType1, fieldType2, mapping1to2, mapping2to1)
            }
        }

        // Variant types
        type1 is FunkType.VariantType && type2 is FunkType.VariantType -> {
            type1.isOpen == type2.isOpen &&
            type1.constructors.size == type2.constructors.size &&
            type1.constructors.all { (name, dataType1) ->
                val dataType2 = type2.constructors[name]
                when {
                    dataType1 == null && dataType2 == null -> true
                    dataType1 != null && dataType2 != null ->
                        alphaEquivHelper(dataType1, dataType2, mapping1to2, mapping2to1)
                    else -> false
                }
            }
        }

        // List types
        type1 is FunkType.ListType && type2 is FunkType.ListType -> {
            alphaEquivHelper(type1.elementType, type2.elementType, mapping1to2, mapping2to1)
        }

        // Array types
        type1 is FunkType.ArrayType && type2 is FunkType.ArrayType -> {
            alphaEquivHelper(type1.elementType, type2.elementType, mapping1to2, mapping2to1)
        }

        // Map types
        type1 is FunkType.MapType && type2 is FunkType.MapType -> {
            alphaEquivHelper(type1.keyType, type2.keyType, mapping1to2, mapping2to1) &&
            alphaEquivHelper(type1.valueType, type2.valueType, mapping1to2, mapping2to1)
        }

        // Set types
        type1 is FunkType.SetType && type2 is FunkType.SetType -> {
            alphaEquivHelper(type1.elementType, type2.elementType, mapping1to2, mapping2to1)
        }

        // Suspended types
        type1 is FunkType.SuspendedType && type2 is FunkType.SuspendedType -> {
            alphaEquivHelper(type1.valueType, type2.valueType, mapping1to2, mapping2to1)
        }

        // Type applications (e.g., Option Int, Result String Int)
        type1 is FunkType.TypeApplication && type2 is FunkType.TypeApplication -> {
            type1.constructor == type2.constructor &&
            type1.args.size == type2.args.size &&
            type1.args.zip(type2.args).all { (a1, a2) ->
                alphaEquivHelper(a1, a2, mapping1to2, mapping2to1)
            }
        }

        // Effect rows
        type1 is FunkType.EffectRow && type2 is FunkType.EffectRow -> {
            type1.isOpen == type2.isOpen &&
            type1.effects == type2.effects
        }

        else -> false
    }
}