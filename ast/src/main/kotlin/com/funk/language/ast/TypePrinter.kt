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
    val variables = mutableSetOf<TypeVar>()
    collectTypeVariablesRecursive(type, variables)
    
    // Sort by name to ensure consistent ordering
    val sortedVars = variables.sortedBy { it.name }
    
    // Assign names 'a, 'b, 'c, etc.
    return sortedVars.mapIndexed { index, typeVar ->
        val letter = ('a' + index).toString()
        typeVar to "'$letter"
    }.toMap()
}

/**
 * Recursively collect type variables from a type
 */
private fun collectTypeVariablesRecursive(type: FunkType, variables: MutableSet<TypeVar>) {
    when (type) {
        is FunkType.VarType -> variables.add(type.variable)
        is FunkType.NumType -> variables.add(type.constraint)
        is FunkType.RecordType -> {
            type.fields.values.forEach { fieldType ->
                collectTypeVariablesRecursive(fieldType, variables)
            }
        }
        is FunkType.VariantType -> {
            type.constructors.values.forEach { constructorType ->
                constructorType?.let { collectTypeVariablesRecursive(it, variables) }
            }
        }
        is FunkType.ArrayListType -> {
            collectTypeVariablesRecursive(type.elementType, variables)
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
        is FunkType.EffectRow -> { /* no type variables */ }
    }
}

/**
 * Pretty print a type using the given type variable mapping
 */
private fun prettyPrintTypeWithMapping(type: FunkType, typeVarMapping: Map<TypeVar, String>): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Boolean"
    is FunkType.NatType -> "Nat"
    is FunkType.FracType -> "Frac"
    is FunkType.NumType -> "Num ${typeVarMapping[type.constraint] ?: type.constraint.name}"
    is FunkType.VarType -> typeVarMapping[type.variable] ?: type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintTypeWithMapping(fieldType, typeVarMapping)}"
        }
        if (type.isOpen) {
            "{ $fields, ... }"
        } else {
            "{ $fields }"
        }
    }
    is FunkType.VariantType -> {
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
            val effectList = type.effects.effects.sorted().joinToString(", ")
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
 * Pretty printer for Funk types with type alias expansion support
 */
fun prettyPrintTypeWithAliases(
    type: FunkType, 
    typeAliases: Map<String, TypeAliasInfo> = emptyMap(),
    showExpansion: Boolean = false
): String {
    val typeVarMapping = collectTypeVariables(type)
    
    // First, check if this type matches any alias
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
 * Recursively apply type aliases to subcomponents of a type
 */
private fun prettyPrintTypeWithAliasesRecursive(
    type: FunkType, 
    typeAliases: Map<String, TypeAliasInfo>,
    showExpansion: Boolean,
    typeVarMapping: Map<TypeVar, String>
): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Boolean"
    is FunkType.NatType -> "Nat"
    is FunkType.FracType -> "Frac"
    is FunkType.NumType -> "Num ${typeVarMapping[type.constraint] ?: type.constraint.name}"
    is FunkType.VarType -> typeVarMapping[type.variable] ?: type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintTypeWithAliases(fieldType, typeAliases, showExpansion)}"
        }
        if (type.isOpen) {
            "{ $fields, ... }"
        } else {
            "{ $fields }"
        }
    }
    is FunkType.VariantType -> {
        val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
            when (dataType) {
                null -> name
                is FunkType.RecordType -> "$name ${prettyPrintTypeWithAliases(dataType, typeAliases, showExpansion)}"
                else -> "$name(${prettyPrintTypeWithAliases(dataType, typeAliases, showExpansion)})"
            }
        }
        if (type.isOpen) {
            "| $constructors, ... |"
        } else {
            "| $constructors |"
        }
    }
    is FunkType.ArrayListType -> "[${prettyPrintTypeWithAliases(type.elementType, typeAliases, showExpansion)}]"
    is FunkType.FunctionType -> {
        // Build parameter type string
        val paramTypeStr = prettyPrintTypeWithAliases(type.paramType, typeAliases, showExpansion)
        
        // Add ability constraints after parameter type but before arrow
        val abilityConstraints = if (type.abilityConstraints.isNotEmpty()) {
            " with " + type.abilityConstraints.joinToString(", ") { constraint ->
                val typeArgs = constraint.typeArgs.joinToString(" ") { prettyPrintTypeWithAliases(it, typeAliases, showExpansion) }
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
        val returnTypeStr = prettyPrintTypeWithAliases(type.returnType, typeAliases, showExpansion)
        
        // Add effects
        val effects = if (type.effects != null && type.effects.effects.isNotEmpty()) {
            val effectList = type.effects.effects.sorted().joinToString(", ")
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
