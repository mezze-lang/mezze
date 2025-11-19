package com.funk.language.ast

/**
 * Pretty printer for Funk types with proper type variable naming
 */
fun prettyPrintType(type: FunkType): String {
    val typeVarMapping = collectTypeVariables(type)
    return prettyPrintTypeWithMapping(type, typeVarMapping)
}

/**
 * Collect all type variables in a type and assign them readable names
 */
private fun collectTypeVariables(type: FunkType): Map<TypeVar, String> {
    val variables = mutableListOf<TypeVar>()
    collectTypeVariablesRecursive(type, variables)

    // Remove duplicates while preserving order of first appearance
    val orderedVars = variables.distinct()

    // Assign names 'a, 'b, 'c, etc. in order of appearance
    return orderedVars.mapIndexed { index, typeVar ->
        val letter = ('a' + index).toString()
        typeVar to "'$letter"
    }.toMap()
}

/**
 * Recursively collect type variables from a type in order of first appearance
 */
private fun collectTypeVariablesRecursive(type: FunkType, variables: MutableList<TypeVar>) {
    when (type) {
        is FunkType.VarType -> variables.add(type.variable)
        is FunkType.RecordType -> {
            // Sort fields by name for consistent type variable ordering
            type.fields.entries.sortedBy { it.key }.forEach { (_, fieldType) ->
                collectTypeVariablesRecursive(fieldType, variables)
            }
        }
        is FunkType.VariantType -> {
            // Sort constructors by name for consistent type variable ordering
            type.constructors.entries.sortedBy { it.key }.forEach { (_, constructorType) ->
                constructorType?.let { collectTypeVariablesRecursive(it, variables) }
            }
        }
        is FunkType.ArrayListType -> {
            collectTypeVariablesRecursive(type.elementType, variables)
        }
        is FunkType.ArrayType -> {
            collectTypeVariablesRecursive(type.elementType, variables)
        }
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVariablesRecursive(arg, variables)
            }
        }
        is FunkType.ListType -> {
            collectTypeVariablesRecursive(type.elementType, variables)
        }
        is FunkType.MapType -> {
            collectTypeVariablesRecursive(type.keyType, variables)
            collectTypeVariablesRecursive(type.valueType, variables)
        }
        is FunkType.SetType -> {
            collectTypeVariablesRecursive(type.elementType, variables)
        }
        is FunkType.SuspendedType -> {
            collectTypeVariablesRecursive(type.valueType, variables)
        }
        is FunkType.FunctionType -> {
            collectTypeVariablesRecursive(type.paramType, variables)
            collectTypeVariablesRecursive(type.returnType, variables)
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    collectTypeVariablesRecursive(arg, variables)
                }
            }
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType, is FunkType.EffectRow -> { /* no type variables */ }
    }
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
    is FunkType.ArrayListType -> "[${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}]"
    is FunkType.ArrayType -> "[${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}]"
    is FunkType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintTypeWithMapping(it, typeVarMapping) }
        "${type.constructor} $argsStr"
    }
    is FunkType.ListType -> "List ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is FunkType.MapType -> "Map ${prettyPrintTypeWithMapping(type.keyType, typeVarMapping)} ${prettyPrintTypeWithMapping(type.valueType, typeVarMapping)}"
    is FunkType.SetType -> "Set ${prettyPrintTypeWithMapping(type.elementType, typeVarMapping)}"
    is FunkType.SuspendedType -> {
        // Suspended types print as: valueType <effects>
        val valueTypeStr = prettyPrintTypeWithMapping(type.valueType, typeVarMapping)
        val effectList = type.effects.effects.sorted().joinToString(", ")
        "$valueTypeStr <$effectList>"
    }
    is FunkType.FunctionType -> {
        // Build parameter type string
        val paramTypeStr = prettyPrintTypeWithMapping(type.paramType, typeVarMapping)
        
        // Add ability constraints after parameter type but before arrow
        val abilityConstraints = if (type.abilityConstraints.isNotEmpty()) {
            " with " + type.abilityConstraints.joinToString(", ") { constraint ->
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
        
        // Add effects
        val effects = if (type.effects != null && type.effects.effects.isNotEmpty()) {
            // Deduplicate effects by keeping shortest name variant
            // E.g., if we have both "FileSystem" and "filesystem::FileSystem", keep "FileSystem"
            val deduped = type.effects.effects.groupBy { effect ->
                // Group by the simple name (last component after ::)
                effect.substringAfterLast("::")
            }.mapValues { (_, variants) ->
                // Keep the shortest variant (usually the unqualified name)
                variants.minByOrNull { it.length } ?: variants.first()
            }.values.sorted()

            val effectList = deduped.joinToString(", ")
            " <$effectList>"
        } else {
            ""
        }
        
        // Construct final string: param with constraints -> return effects
        paramTypeStr + abilityConstraints + " -> " + returnTypeStr + effects
    }
    else -> type.toString() // Fallback for any other types
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
        if (decl.definition !is VariantDefinition) continue

        val variantDef = decl.definition as VariantDefinition

        // Check if constructor names match
        if (variantDef.constructors.map { it.name }.toSet() != variantType.constructors.keys) {
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

        // FALLBACK: If we have the right constructors but couldn't extract type args,
        // try a more lenient approach - collect all distinct type vars and use those
        if (decl.typeParams.size == 1) {
            // For single-parameter types, collect all type variables that appear
            val allTypeVarsList = mutableListOf<TypeVar>()
            for (constructorType in variantType.constructors.values) {
                constructorType?.let { collectTypeVars(it, allTypeVarsList) }
            }
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
    // Collect all type variables from the variant constructors
    val typeVars = mutableListOf<TypeVar>()
    for (constructorType in variantType.constructors.values) {
        constructorType?.let { collectTypeVars(it, typeVars) }
    }

    // Get unique type variables in order of appearance
    val uniqueTypeVars = typeVars.distinctBy { typeVarMapping[it] ?: it.name }

    if (uniqueTypeVars.size != expectedParamCount) {
        return null
    }

    return uniqueTypeVars.map { FunkType.VarType(it) }
}

private fun collectTypeVars(type: FunkType, result: MutableList<TypeVar>) {
    when (type) {
        is FunkType.VarType -> result.add(type.variable)
        is FunkType.RecordType -> type.fields.values.forEach { collectTypeVars(it, result) }
        is FunkType.VariantType -> type.constructors.values.forEach { it?.let { t -> collectTypeVars(t, result) } }
        is FunkType.TypeApplication -> type.args.forEach { collectTypeVars(it, result) }
        is FunkType.FunctionType -> {
            collectTypeVars(type.paramType, result)
            collectTypeVars(type.returnType, result)
        }
        is FunkType.ListType -> collectTypeVars(type.elementType, result)
        is FunkType.ArrayListType -> collectTypeVars(type.elementType, result)
        is FunkType.ArrayType -> collectTypeVars(type.elementType, result)
        is FunkType.MapType -> {
            collectTypeVars(type.keyType, result)
            collectTypeVars(type.valueType, result)
        }
        else -> {} // Primitive types have no type variables
    }
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

    // First, check if this type matches any alias
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
        // First, check if this variant matches a known type declaration
        val declMatch = findTypeDeclarationForVariant(type, typeDeclarations, typeVarMapping)
        if (declMatch != null) {
            // Print as named type (e.g., "Tree 'a" instead of expanded variant)
            val typeArgsStr = if (declMatch.typeArgs.isEmpty()) {
                ""
            } else {
                " " + declMatch.typeArgs.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping, typeDeclarations) }
            }
            declMatch.declName + typeArgsStr
        } else {
        // Check if this is a Result type (has Ok and Err constructors)
        val hasOk = type.constructors.containsKey("Ok")
        val hasErr = type.constructors.containsKey("Err")

        if (hasOk && hasErr && type.constructors.size == 2) {
            // This is a Result type - print as: Result valueType | errors |
            val okType = type.constructors["Ok"]
            val errType = type.constructors["Err"]

            // Extract value type from Ok { val: T }
            val valueType = when (okType) {
                is FunkType.RecordType -> okType.fields["val"]
                else -> null
            }

            // Extract error variant from Err { error: VariantType }
            val errorVariant = when (errType) {
                is FunkType.RecordType -> errType.fields["error"]
                else -> null
            }

            val valueTypeStr = if (valueType != null) {
                prettyPrintWithAliasCheck(valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)
            } else {
                "'unknown"
            }

            val errorTypeStr = when (errorVariant) {
                is FunkType.VariantType -> {
                    val errorConstructors = errorVariant.constructors.entries.map { (name, dataType) ->
                        if (dataType != null) {
                            "$name ${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
                        } else {
                            name
                        }
                    }
                    "| ${errorConstructors.joinToString(", ")} |"
                }
                else -> if (errorVariant != null) prettyPrintWithAliasCheck(errorVariant, typeAliases, showExpansion, typeVarMapping, typeDeclarations) else "'unknown"
            }

            "Result $valueTypeStr $errorTypeStr"
        } else {
            // Regular variant type
            val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
                when (dataType) {
                    null -> name
                    is FunkType.RecordType -> "$name ${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
                    else -> "$name(${prettyPrintWithAliasCheck(dataType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)})"
                }
            }
            if (type.isOpen) {
                "| $constructors, ... |"
            } else {
                "| $constructors |"
            }
        }
        }
    }
    is FunkType.ArrayListType -> "[${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}]"
    is FunkType.ArrayType -> "[${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}]"
    is FunkType.TypeApplication -> {
        val argsStr = type.args.joinToString(" ") { prettyPrintWithAliasCheck(it, typeAliases, showExpansion, typeVarMapping, typeDeclarations) }
        "${type.constructor} $argsStr"
    }
    is FunkType.ListType -> "List ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.MapType -> "Map ${prettyPrintWithAliasCheck(type.keyType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)} ${prettyPrintWithAliasCheck(type.valueType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.SetType -> "Set ${prettyPrintWithAliasCheck(type.elementType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)}"
    is FunkType.FunctionType -> {
        // Build parameter type string
        val paramTypeStr = prettyPrintWithAliasCheck(type.paramType, typeAliases, showExpansion, typeVarMapping, typeDeclarations)

        // Add ability constraints after parameter type but before arrow
        val abilityConstraints = if (type.abilityConstraints.isNotEmpty()) {
            " with " + type.abilityConstraints.joinToString(", ") { constraint ->
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

        // Add effects
        val effects = if (type.effects != null && type.effects.effects.isNotEmpty()) {
            // Deduplicate effects by keeping shortest name variant
            // E.g., if we have both "FileSystem" and "filesystem::FileSystem", keep "FileSystem"
            val deduped = type.effects.effects.groupBy { effect ->
                // Group by the simple name (last component after ::)
                effect.substringAfterLast("::")
            }.mapValues { (_, variants) ->
                // Keep the shortest variant (usually the unqualified name)
                variants.minByOrNull { it.length } ?: variants.first()
            }.values.sorted()

            val effectList = deduped.joinToString(", ")
            " <$effectList>"
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
        val effectList = type.effects.effects.sorted().joinToString(", ")
        "$valueTypeStr <$effectList>"
    }
    else -> type.toString() // Fallback for any other types
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
        is FunkType.OrderingType -> true

        // Core built-in types
        is FunkType.ListType, is FunkType.MapType, is FunkType.SetType -> true

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

        // ArrayList and Array types are legacy/compatibility types
        is FunkType.ArrayListType, is FunkType.ArrayType -> false

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

        // ArrayList types
        type1 is FunkType.ArrayListType && type2 is FunkType.ArrayListType -> {
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