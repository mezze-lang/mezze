package com.funk.language.typechecker

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar
import com.funk.language.ast.SourceLocation
import com.funk.language.typechecker.support.Constraint

/**
 * Represents an ability definition with operations and super-abilities
 */
data class Ability(
    val name: String,
    val operations: Map<String, FunkType>,
    val superAbilities: List<String> = emptyList()
)

/**
 * Registry for managing built-in and user-defined abilities
 */
class AbilityRegistry {
    private val abilities = mutableMapOf<String, Ability>()
    private val typeAbilities = mutableMapOf<FunkType, MutableSet<String>>()
    
    init {
        registerBuiltinAbilities()
    }
    
    /**
     * Register a new ability
     */
    fun registerAbility(ability: Ability) {
        abilities[ability.name] = ability
    }
    
    /**
     * Check if a type has a specific ability
     */
    fun hasAbility(type: FunkType, abilityName: String): Boolean {
        // CRITICAL FIX: Inspect is universally available for ALL types (including type variables)
        // This prevents exponential blowup in constraint solving for Inspect.to_str calls
        if (abilityName == "Inspect") {
            return true
        }

        return when (type) {
            is FunkType.IntType -> abilityName in setOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord")
            is FunkType.FracType -> abilityName in setOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord")
            is FunkType.FloatType -> abilityName in setOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord")
            is FunkType.NatType -> abilityName in setOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord")
            is FunkType.BooleanType -> abilityName in setOf("Eq", "LogicalAnd", "LogicalOr", "LogicalNot")
            is FunkType.StringType -> abilityName in setOf("Eq", "Ord")
            is FunkType.VarType -> false // Type variables don't have abilities until constrained (except Inspect which is universal)
            else -> typeAbilities[type]?.contains(abilityName) ?: false
        }
    }
    
    /**
     * Get required abilities for an operation
     */
    fun getRequiredAbilities(operation: String): Set<String> {
        return when (operation) {
            "+" -> setOf("NumAdd")
            "-" -> setOf("NumSub") 
            "*" -> setOf("NumMul")
            "/" -> setOf("NumDiv")
            "%" -> setOf("NumMod")  // Modulo operation
            "==" -> setOf("Eq")
            "!=" -> setOf("Eq")
            "<", ">", "<=", ">=" -> setOf("Ord")
            "&&" -> setOf("LogicalAnd")
            "||" -> setOf("LogicalOr")
            "!" -> setOf("LogicalNot")
            else -> emptySet()
        }
    }
    
    /**
     * Register built-in abilities for primitive types
     */
    private fun registerBuiltinAbilities() {
        // Create type variables for ability operation signatures
        val aVar = TypeVar("'a")
        val aType = FunkType.VarType(aVar)
        
        // Arithmetic operation type: 'a -> 'a -> 'a
        val arithmeticOpType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to aType, "right" to aType)),
            aType
        )
        
        // Comparison operation type: 'a -> 'a -> Bool
        val comparisonOpType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("left" to aType, "right" to aType)),
            FunkType.BooleanType
        )
        
        // Register built-in abilities
        registerAbility(Ability("NumAdd", mapOf("+" to arithmeticOpType)))
        registerAbility(Ability("NumSub", mapOf("-" to arithmeticOpType)))
        registerAbility(Ability("NumMul", mapOf("*" to arithmeticOpType)))
        registerAbility(Ability("NumDiv", mapOf("/" to arithmeticOpType)))
        registerAbility(Ability("NumMod", mapOf("%" to arithmeticOpType)))
        registerAbility(Ability("Eq", mapOf("==" to comparisonOpType, "!=" to comparisonOpType)))
        registerAbility(Ability("Ord", mapOf("<" to comparisonOpType, ">" to comparisonOpType, "<=" to comparisonOpType, ">=" to comparisonOpType), listOf("Eq")))
        registerAbility(Ability("LogicalAnd", mapOf("&&" to arithmeticOpType)))
        registerAbility(Ability("LogicalOr", mapOf("||" to arithmeticOpType)))
        registerAbility(Ability("LogicalNot", mapOf("!" to FunkType.FunctionType(aType, FunkType.BooleanType))))
    }
}

/**
 * Context for tracking constraints during type inference
 */
data class ConstraintContext(
    val equalityConstraints: MutableSet<Constraint.Equality> = mutableSetOf(),
    val abilityConstraints: MutableSet<Constraint.AbilityConstraint> = mutableSetOf(),
    val instanceConstraints: MutableSet<Constraint.Instance> = mutableSetOf(),
    val annotationConstraints: MutableSet<Constraint.AnnotationConstraint> = mutableSetOf()
) {
    /**
     * Add an ability constraint
     */
    fun addAbilityConstraint(typeVar: TypeVar, ability: String, location: SourceLocation? = null) {
        abilityConstraints.add(Constraint.AbilityConstraint(typeVar, ability, location))
    }

    /**
     * Add an annotation constraint
     */
    fun addAnnotationConstraint(
        inferredType: FunkType,
        declaredType: FunkType,
        expression: com.funk.language.ast.Expression,
        location: SourceLocation? = null
    ) {
        annotationConstraints.add(Constraint.AnnotationConstraint(inferredType, declaredType, expression, location))
    }

    /**
     * Get all constraints as a single set
     */
    fun getAllConstraints(): Set<Constraint> {
        return equalityConstraints + abilityConstraints + instanceConstraints + annotationConstraints
    }

    /**
     * Clear all constraints
     */
    fun clear() {
        equalityConstraints.clear()
        abilityConstraints.clear()
        instanceConstraints.clear()
        annotationConstraints.clear()
    }
}

/**
 * Represents a type substitution (mapping from type variables to types)
 */
data class Substitution(val mapping: Map<TypeVar, FunkType> = emptyMap()) {

    companion object {
        /**
         * Create a substitution with a single mapping
         */
        fun single(typeVar: TypeVar, type: FunkType): Substitution {
            return Substitution(mapOf(typeVar to type))
        }

        /**
         * Create an empty substitution
         */
        fun empty(): Substitution {
            return Substitution()
        }
    }

    /**
     * Apply this substitution to a type with proper cycle detection
     */
    fun apply(type: FunkType): FunkType {
        return applyWithVisited(type, mutableSetOf())
    }

    /**
     * Apply substitution with cycle detection via visited set
     *
     * The visited set tracks type variables currently being processed in this path.
     * If we encounter a variable that's already in the visited set, we've found a cycle
     * and return the variable unchanged to prevent infinite recursion.
     *
     * After processing a variable, we remove it from visited to allow the same variable
     * to be substituted in independent branches (e.g., both fields in {x: 'a, y: 'a}).
     */
    private fun applyWithVisited(type: FunkType, visited: MutableSet<TypeVar>): FunkType {

        return when (type) {
            is FunkType.VarType -> {
                // Cycle detection: if we've already visited this type variable, return it as-is
                if (type.variable in visited) {
                    return type
                }

                val substituted = mapping[type.variable]
                if (substituted != null) {
                    // Add this type variable to visited set to detect cycles
                    visited.add(type.variable)
                    val result = applyWithVisited(substituted, visited)
                    // Remove from visited after processing to allow this variable in independent branches
                    visited.remove(type.variable)
                    result
                } else {
                    type
                }
            }
            is FunkType.FunctionType -> {
                FunkType.FunctionType(
                    paramType = applyWithVisited(type.paramType, visited),
                    returnType = applyWithVisited(type.returnType, visited),
                    effects = type.effects?.let { applyToEffectRowWithVisited(it, visited) },
                    abilityConstraints = type.abilityConstraints.map { constraint ->
                        FunkType.FunctionAbilityConstraint(
                            constraint.abilityName,
                            constraint.typeArgs.map { applyWithVisited(it, visited) }
                        )
                    }
                )
            }
            is FunkType.RecordType -> {
                FunkType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) -> applyWithVisited(fieldType, visited) },
                    rowVar = type.rowVar // Row variables handled separately
                )
            }
            is FunkType.VariantType -> {
                FunkType.VariantType(
                    constructors = type.constructors.mapValues { (_, dataType) ->
                        dataType?.let { applyWithVisited(it, visited) }
                    },
                    rowVar = type.rowVar // Row variables handled separately
                )
            }
            is FunkType.ArrayType -> {
                FunkType.ArrayType(elementType = applyWithVisited(type.elementType, visited))
            }
            is FunkType.TypeApplication -> {
                FunkType.TypeApplication(
                    constructor = type.constructor,
                    args = type.args.map { applyWithVisited(it, visited) }
                )
            }
            is FunkType.ListType -> {
                FunkType.ListType(elementType = applyWithVisited(type.elementType, visited))
            }
            is FunkType.MapType -> {
                FunkType.MapType(
                    keyType = applyWithVisited(type.keyType, visited),
                    valueType = applyWithVisited(type.valueType, visited)
                )
            }
            is FunkType.SetType -> {
                FunkType.SetType(elementType = applyWithVisited(type.elementType, visited))
            }
            is FunkType.SuspendedType -> {
                FunkType.SuspendedType(
                    valueType = applyWithVisited(type.valueType, visited),
                    effects = applyToEffectRowWithVisited(type.effects, visited)
                )
            }

            // Base types remain unchanged
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.FracType, is FunkType.NatType,
            is FunkType.OrderingType -> type

            is FunkType.EffectRow -> applyToEffectRow(type)
        }
    }
    
    private fun applyToEffectRow(effectRow: FunkType.EffectRow): FunkType.EffectRow {
        return FunkType.EffectRow(
            effects = effectRow.effects.toSet(),
            rowVar = effectRow.rowVar // Row variables handled separately
        )
    }

    private fun applyToEffectRowWithVisited(effectRow: FunkType.EffectRow, visited: MutableSet<TypeVar>): FunkType.EffectRow {
        return FunkType.EffectRow(
            effects = effectRow.effects.toSet(),
            rowVar = effectRow.rowVar // Row variables handled separately
        )
    }
    
    /**
     * Compose this substitution with another (this ∘ other)
     */
    fun compose(other: Substitution): Substitution {
        val newMapping = mutableMapOf<TypeVar, FunkType>()

        // Apply this substitution to the other's mappings
        for ((typeVar, type) in other.mapping) {
            newMapping[typeVar] = apply(type)
        }

        // Add our own mappings, applying the other substitution to them
        for ((typeVar, type) in mapping) {
            if (!newMapping.containsKey(typeVar)) {
                newMapping[typeVar] = other.apply(type)
            }
        }

        // Return the composed substitution
        // Note: We don't apply the result to itself because that can create circular references
        // when the two input substitutions reference each other's type variables.
        // The transitive resolution is already handled by applying each substitution to the other's mappings above.
        return Substitution(newMapping)
    }
}

/**
 * Enhanced constraint-based type inference solver with ability support and memoization
 */
class ConstraintSolver(
    private val abilityRegistry: AbilityRegistry = AbilityRegistry(),
    private val typeAliases: Map<String, com.funk.language.ast.TypeAliasInfo> = emptyMap()
) {
    // Memoization cache for unification results
    // Key: Pair of types (canonicalized), Value: Result substitution
    private val unificationCache = mutableMapOf<Pair<FunkType, FunkType>, Substitution>()

    // Cache for constraint solving results
    // Key: Hash of constraint set + current substitution, Value: Result substitution
    private val constraintCache = mutableMapOf<Int, Substitution>()

    // Statistics for performance monitoring
    private var unificationCacheHits = 0
    private var unificationCacheMisses = 0
    private var constraintCacheHits = 0
    private var constraintCacheMisses = 0

    /**
     * Clear all caches (useful for testing or between type checking sessions)
     */
    fun clearCaches() {
        unificationCache.clear()
        constraintCache.clear()
        unificationCacheHits = 0
        unificationCacheMisses = 0
        constraintCacheHits = 0
        constraintCacheMisses = 0
    }

    /**
     * Print cache statistics for debugging
     */
    fun printCacheStats() {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            val unificationTotal = unificationCacheHits + unificationCacheMisses
            val constraintTotal = constraintCacheHits + constraintCacheMisses
            val unificationHitRate = if (unificationTotal > 0)
                (unificationCacheHits * 100.0 / unificationTotal) else 0.0
            val constraintHitRate = if (constraintTotal > 0)
                (constraintCacheHits * 100.0 / constraintTotal) else 0.0

            println("DEBUG: Cache Statistics:")
            println("  Unification cache: $unificationCacheHits hits, $unificationCacheMisses misses (${String.format("%.1f%%", unificationHitRate)} hit rate)")
            println("  Constraint cache: $constraintCacheHits hits, $constraintCacheMisses misses (${String.format("%.1f%%", constraintHitRate)} hit rate)")
        }
    }

    /**
     * Solve a set of constraints and return the most general unifier
     */
    fun solve(constraints: List<Constraint>): Substitution {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: ConstraintSolver.solve called with ${constraints.size} constraints")
        }

        var substitution = solveConstraints(constraints, Substitution.empty())

        // CRITICAL FIX: Apply transitive constraint propagation to handle cases like:
        // 'a = Int, { left: 'a, right: 'b } = { left: 'a, right: 'a } => 'b = Int
        substitution = applyTransitiveConstraintPropagation(substitution, constraints)

        return substitution
    }
    
    /**
     * Solve constraints with ability validation
     */
    fun solveWithAbilities(constraints: Set<Constraint>): Substitution {
        // Partition constraints into equality/instance vs validation constraints
        val equalityConstraints = constraints.filter { it is Constraint.Equality || it is Constraint.Instance }
        val abilityConstraints = constraints.filterIsInstance<Constraint.AbilityConstraint>()
        val annotationConstraints = constraints.filterIsInstance<Constraint.AnnotationConstraint>()

        // First solve equality constraints using existing unification
        var substitution = solveConstraints(equalityConstraints, Substitution.empty())

        // ENHANCED: Apply aggressive constraint propagation for related type variables
        substitution = applyAggressiveConstraintPropagation(substitution, abilityConstraints)

        // CRITICAL FIX: Apply transitive constraint propagation to handle cases like:
        // 'a = Int, { left: 'a, right: 'b } = { left: 'a, right: 'a } => 'b = Int
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: About to apply transitive constraint propagation")
        }
        substitution = applyTransitiveConstraintPropagation(substitution, equalityConstraints)

        // Then validate ability constraints
        validateAbilityConstraints(abilityConstraints, substitution)

        // Finally validate annotation constraints
        validateAnnotationConstraints(annotationConstraints, substitution)

        return substitution
    }

    /**
     * Solve constraints with bidirectional type flow from usage constraints
     *
     * This is the main entry point for bidirectional type inference. It takes both
     * regular constraints (from forward type inference) and usage constraints (from
     * backward type propagation) and solves them together.
     *
     * Example:
     *   let parse = {str} -> Decode.decode {value = str}
     *   let x = parse {str = "123"}
     *   let y = x + 10
     *
     * Regular constraints: parse returns 'a with Decode 'a
     * Usage constraints: x (which has type 'a) is used with +, so 'a = Int
     * Result: parse : {str: String} -> Int with Decode Int
     *
     * @param regularConstraints Constraints from forward type inference
     * @param usageConstraints Constraints from usage patterns (bidirectional)
     * @return The most general unifier incorporating both constraint types
     */
    fun solveWithUsageFlow(
        regularConstraints: List<Constraint>,
        usageConstraints: List<com.funk.language.ast.UsageConstraint>
    ): Substitution {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("\n🔄 BIDIRECTIONAL SOLVING:")
            println("   Regular constraints: ${regularConstraints.size}")
            println("   Usage constraints: ${usageConstraints.size}")
        }

        // Step 1: Solve regular constraints first (forward inference)
        var substitution = solve(regularConstraints)

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("   After forward solving: ${substitution.mapping.size} mappings")
        }

        // Step 2: Apply usage constraints (backward propagation)
        val additionalConstraints = mutableListOf<Constraint>()

        for (usageConstraint in usageConstraints) {
            // Apply current substitution to get the most up-to-date types
            val typeVarType = substitution.apply(FunkType.VarType(usageConstraint.typeVar))
            val inferredType = substitution.apply(usageConstraint.inferredType)

            // Only create constraint if the type variable is still unresolved
            if (typeVarType is FunkType.VarType) {
                // Check for circular constraint (would create infinite type)
                if (!occursCheck(typeVarType.variable, inferredType)) {
                    additionalConstraints.add(Constraint.Equality(typeVarType, inferredType))

                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("   Usage: ${typeVarType.variable.name} = ${prettyPrintType(inferredType)}")
                        println("     Context: ${usageConstraint.usageContext::class.simpleName}")
                    }
                } else {
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("   Skipping circular usage constraint: ${typeVarType.variable.name} occurs in ${prettyPrintType(inferredType)}")
                    }
                }
            } else {
                if (System.getProperty("funk.typecheck.debug") == "true") {
                    println("   Skipping already resolved type variable: ${usageConstraint.typeVar.name} = ${prettyPrintType(typeVarType)}")
                }
            }
        }

        // Step 3: Solve the additional constraints if any were created
        if (additionalConstraints.isNotEmpty()) {
            val additionalSubstitution = solve(additionalConstraints)
            val combinedSubstitution = substitution.compose(additionalSubstitution)

            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("   After bidirectional solving: ${combinedSubstitution.mapping.size} mappings")
                for ((typeVar, type) in combinedSubstitution.mapping) {
                    println("     ${typeVar.name} -> ${prettyPrintType(type)}")
                }
            }

            return combinedSubstitution
        }

        return substitution
    }
    
    /**
     * Apply aggressive constraint propagation to ensure related type variables get unified
     */
    private fun applyAggressiveConstraintPropagation(
        substitution: Substitution, 
        abilityConstraints: List<Constraint.AbilityConstraint>
    ): Substitution {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Applying aggressive constraint propagation")
        }
        
        // Group ability constraints by ability name
        val constraintsByAbility = abilityConstraints.groupBy { it.ability }
        
        var currentSubstitution = substitution
        
        // For each ability group, if any type variable is resolved to a concrete type,
        // propagate that concrete type to all other type variables with the same ability
        for ((abilityName, constraints) in constraintsByAbility) {
            if (abilityName == "NumAdd") { // Focus on NumAdd for arithmetic operations
                val typeVars = constraints.map { it.typeVar }
                
                // Find any type variable that's already resolved to a concrete type
                var concreteType: FunkType? = null
                for (typeVar in typeVars) {
                    val resolvedType = currentSubstitution.apply(FunkType.VarType(typeVar))
                    if (resolvedType !is FunkType.VarType) {
                        concreteType = resolvedType
                        if (System.getProperty("funk.typecheck.debug") == "true") {
                            println("DEBUG: Found concrete type ${prettyPrintType(concreteType)} for ${typeVar.name}")
                        }
                        break
                    }
                }
                
                // If we found a concrete type, propagate it to all other type variables
                if (concreteType != null) {
                    val additionalMappings = mutableMapOf<TypeVar, FunkType>()
                    for (typeVar in typeVars) {
                        val resolvedType = currentSubstitution.apply(FunkType.VarType(typeVar))
                        if (resolvedType is FunkType.VarType) {
                            additionalMappings[typeVar] = concreteType
                            if (System.getProperty("funk.typecheck.debug") == "true") {
                                println("DEBUG: Propagating ${prettyPrintType(concreteType)} to ${typeVar.name}")
                            }
                        }
                    }
                    
                    // Create new substitution with additional mappings
                    if (additionalMappings.isNotEmpty()) {
                        val newMapping = currentSubstitution.mapping + additionalMappings
                        currentSubstitution = Substitution(newMapping)
                    }
                }
            }
        }
        
        return currentSubstitution
    }
    
    /**
     * Apply transitive constraint propagation to handle cases where type variables
     * are connected through record unification but not directly unified
     */
    private fun applyTransitiveConstraintPropagation(
        substitution: Substitution,
        constraints: List<Constraint>
    ): Substitution {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Applying transitive constraint propagation")
        }
        
        var currentSubstitution = substitution
        var changed = true
        
        // Keep iterating until no more changes are made
        while (changed) {
            changed = false
            val additionalMappings = mutableMapOf<TypeVar, FunkType>()
            
            // Look for constraints that connect type variables transitively
            for (constraint in constraints) {
                if (constraint is Constraint.Equality) {
                    val left = currentSubstitution.apply(constraint.left)
                    val right = currentSubstitution.apply(constraint.right)
                    
                    // Handle record unification cases like { left: 'a, right: 'b } = { left: 'a, right: 'a }
                    if (left is FunkType.RecordType && right is FunkType.RecordType) {
                        for ((fieldName, leftFieldType) in left.fields) {
                            val rightFieldType = right.fields[fieldName]
                            if (rightFieldType != null) {
                                val resolvedLeft = currentSubstitution.apply(leftFieldType)
                                val resolvedRight = currentSubstitution.apply(rightFieldType)
                                
                                // If one side is resolved to a concrete type and the other is a type variable
                                if (resolvedLeft is FunkType.VarType && resolvedRight !is FunkType.VarType) {
                                    additionalMappings[resolvedLeft.variable] = resolvedRight
                                    changed = true
                                    if (System.getProperty("funk.typecheck.debug") == "true") {
                                        println("DEBUG: Transitive propagation: ${resolvedLeft.variable.name} -> ${prettyPrintType(resolvedRight)}")
                                    }
                                } else if (resolvedRight is FunkType.VarType && resolvedLeft !is FunkType.VarType) {
                                    additionalMappings[resolvedRight.variable] = resolvedLeft
                                    changed = true
                                    if (System.getProperty("funk.typecheck.debug") == "true") {
                                        println("DEBUG: Transitive propagation: ${resolvedRight.variable.name} -> ${prettyPrintType(resolvedLeft)}")
                                    }
                                }
                            }
                        }
                    }
                    
                    // Handle direct type variable unification
                    if (left is FunkType.VarType && right !is FunkType.VarType) {
                        if (!currentSubstitution.mapping.containsKey(left.variable)) {
                            // Check for occurs check before adding mapping
                            if (!occursCheck(left.variable, right)) {
                                additionalMappings[left.variable] = right
                                changed = true
                                if (System.getProperty("funk.typecheck.debug") == "true") {
                                    println("DEBUG: Direct propagation: ${left.variable.name} -> ${prettyPrintType(right)}")
                                }
                            } else {
                                if (System.getProperty("funk.typecheck.debug") == "true") {
                                    println("DEBUG: Skipping direct propagation due to occurs check: ${left.variable.name} -> ${prettyPrintType(right)}")
                                }
                            }
                        }
                    } else if (right is FunkType.VarType && left !is FunkType.VarType) {
                        if (!currentSubstitution.mapping.containsKey(right.variable)) {
                            // Check for occurs check before adding mapping
                            if (!occursCheck(right.variable, left)) {
                                additionalMappings[right.variable] = left
                                changed = true
                                if (System.getProperty("funk.typecheck.debug") == "true") {
                                    println("DEBUG: Direct propagation: ${right.variable.name} -> ${prettyPrintType(left)}")
                                }
                            } else {
                                if (System.getProperty("funk.typecheck.debug") == "true") {
                                    println("DEBUG: Skipping direct propagation due to occurs check: ${right.variable.name} -> ${prettyPrintType(left)}")
                                }
                            }
                        }
                    }
                }
            }
            
            // Apply additional mappings
            if (additionalMappings.isNotEmpty()) {
                // CRITICAL FIX: Apply the current substitution to all additional mappings
                // to ensure transitive resolution. For example, if we have:
                // currentSubstitution = {'b -> Int}
                // additionalMappings = {'a -> 'b}
                // We need to resolve 'a -> Int, not just 'a -> 'b
                val resolvedAdditionalMappings = additionalMappings.mapValues { (_, type) ->
                    currentSubstitution.apply(type)
                }
                val newMapping = currentSubstitution.mapping + resolvedAdditionalMappings
                currentSubstitution = Substitution(newMapping)
            }
        }

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Final transitive substitution: ${currentSubstitution.mapping}")
        }

        return currentSubstitution
    }
    
    private fun solveConstraints(constraints: List<Constraint>, substitution: Substitution): Substitution {
        if (constraints.isEmpty()) {
            return substitution
        }
        
        val constraint = constraints.first()
        val remainingConstraints = constraints.drop(1)
        
        return when (constraint) {
            is Constraint.Equality -> {
                val unifier = unify(constraint.left, constraint.right)
                val newSubstitution = substitution.compose(unifier)

                // Apply the new substitution to remaining constraints
                val updatedConstraints = remainingConstraints.map { applySubstitutionToConstraint(it, unifier) }

                solveConstraints(updatedConstraints, newSubstitution)
            }
            is Constraint.Instance -> {
                // For now, treat instance constraints as equality constraints
                // This is a simplification - full polymorphism would require more work
                val equalityConstraint = Constraint.Equality(constraint.type, constraint.scheme)
                solveConstraints(listOf(equalityConstraint) + remainingConstraints, substitution)
            }
            is Constraint.AbilityConstraint -> {
                // Ability constraints are validated after unification
                solveConstraints(remainingConstraints, substitution)
            }
            is Constraint.AnnotationConstraint -> {
                // Annotation constraints are validated after unification
                solveConstraints(remainingConstraints, substitution)
            }
        }
    }
    
    /**
     * Validate ability constraints against resolved types
     */
    private fun validateAbilityConstraints(
        constraints: List<Constraint.AbilityConstraint>, 
        substitution: Substitution
    ) {
        for (constraint in constraints) {
            val resolvedType = substitution.apply(FunkType.VarType(constraint.typeVar))
            
            // Only validate if the type variable was actually resolved to a concrete type
            // Unresolved type variables (still VarType) are not validated
            if (resolvedType is FunkType.VarType) {
                // Type variable is still unresolved - skip validation
                continue
            }
            
            if (!abilityRegistry.hasAbility(resolvedType, constraint.ability)) {
                throw AbilityConstraintError(
                    "Type '${prettyPrintType(resolvedType)}' does not implement ability '${constraint.ability}'",
                    constraint.sourceLocation,
                    resolvedType,
                    constraint.ability
                )
            }
        }
    }

    /**
     * Validate annotation constraints against resolved types
     */
    private fun validateAnnotationConstraints(
        constraints: List<Constraint.AnnotationConstraint>,
        substitution: Substitution
    ) {
        for (constraint in constraints) {
            // Apply substitutions to both inferred and declared types
            val resolvedInferred = substitution.apply(constraint.inferredType)
            val resolvedDeclared = substitution.apply(constraint.declaredType)

            // Check if the resolved types are compatible
            if (!areTypesCompatible(resolvedInferred, resolvedDeclared)) {
                throw TypeCheckError.typeMismatch(resolvedDeclared, resolvedInferred, constraint.expression)
            }
        }
    }

    /**
     * Check if two types are compatible after substitution.
     * Unlike unification, this does not create new substitutions.
     * Type variables with the same name are considered compatible.
     */
    private fun areTypesCompatible(type1: FunkType, type2: FunkType): Boolean {
        return when {
            // Base types must match exactly
            type1 is FunkType.IntType && type2 is FunkType.IntType -> true
            type1 is FunkType.FloatType && type2 is FunkType.FloatType -> true
            type1 is FunkType.StringType && type2 is FunkType.StringType -> true
            type1 is FunkType.BooleanType && type2 is FunkType.BooleanType -> true

            // Type variables - if both are type variables, they're compatible
            // (represents polymorphic/generic types)
            type1 is FunkType.VarType && type2 is FunkType.VarType -> true

            // Function types - check parameter and return types recursively
            type1 is FunkType.FunctionType && type2 is FunkType.FunctionType ->
                areTypesCompatible(type1.paramType, type2.paramType) &&
                areTypesCompatible(type1.returnType, type2.returnType)

            // List types - check element type recursively
            type1 is FunkType.ListType && type2 is FunkType.ListType ->
                areTypesCompatible(type1.elementType, type2.elementType)

            // Record types - check all fields match
            type1 is FunkType.RecordType && type2 is FunkType.RecordType -> {
                if (type1.fields.size != type2.fields.size) return false
                type1.fields.all { (name, fieldType1) ->
                    val fieldType2 = type2.fields[name]
                    fieldType2 != null && areTypesCompatible(fieldType1, fieldType2)
                }
            }

            // Variant types - check all constructors match
            type1 is FunkType.VariantType && type2 is FunkType.VariantType -> {
                if (type1.constructors.size != type2.constructors.size) return false
                type1.constructors.all { (name, constrType1) ->
                    val constrType2 = type2.constructors[name]
                    if (constrType2 == null) return@all false
                    (constrType1 == null && constrType2 == null) ||
                    (constrType1 != null && constrType2 != null && areTypesCompatible(constrType1, constrType2))
                }
            }

            else -> false
        }
    }

    /**
     * Check if two types match structurally (after substitution)
     * This is NOT unification - we check if the types are already equal
     */
    private fun typesMatch(type1: FunkType, type2: FunkType): Boolean {
        return when {
            // Base types must match exactly
            type1 is FunkType.IntType && type2 is FunkType.IntType -> true
            type1 is FunkType.FloatType && type2 is FunkType.FloatType -> true
            type1 is FunkType.StringType && type2 is FunkType.StringType -> true
            type1 is FunkType.BooleanType && type2 is FunkType.BooleanType -> true

            // Type variables - same variable means match
            type1 is FunkType.VarType && type2 is FunkType.VarType ->
                type1.variable == type2.variable

            // Function types - check parameter and return types recursively
            type1 is FunkType.FunctionType && type2 is FunkType.FunctionType ->
                typesMatch(type1.paramType, type2.paramType) &&
                typesMatch(type1.returnType, type2.returnType)

            // List types - check element type recursively
            type1 is FunkType.ListType && type2 is FunkType.ListType ->
                typesMatch(type1.elementType, type2.elementType)

            // Record types - check all fields match
            type1 is FunkType.RecordType && type2 is FunkType.RecordType -> {
                if (type1.fields.size != type2.fields.size) return false
                type1.fields.all { (name, fieldType1) ->
                    val fieldType2 = type2.fields[name]
                    fieldType2 != null && typesMatch(fieldType1, fieldType2)
                }
            }

            // Variant types - check all constructors match
            type1 is FunkType.VariantType && type2 is FunkType.VariantType -> {
                if (type1.constructors.size != type2.constructors.size) return false
                type1.constructors.all { (name, constrType1) ->
                    val constrType2 = type2.constructors[name]
                    if (constrType2 == null) return@all false
                    // Both null or both match
                    (constrType1 == null && constrType2 == null) ||
                    (constrType1 != null && constrType2 != null && typesMatch(constrType1, constrType2))
                }
            }

            // Named types - check name and type arguments (skipped for now due to complex matching)
            // type1 is FunkType.NamedType && type2 is FunkType.NamedType -> ...

            // Otherwise types don't match
            else -> false
        }
    }

    /**
     * Get the ability registry
     */
    fun getAbilityRegistry(): AbilityRegistry = abilityRegistry
    
    private fun applySubstitutionToConstraint(constraint: Constraint, substitution: Substitution): Constraint {
        return when (constraint) {
            is Constraint.Equality -> Constraint.Equality(
                left = substitution.apply(constraint.left),
                right = substitution.apply(constraint.right)
            )
            is Constraint.Instance -> Constraint.Instance(
                type = substitution.apply(constraint.type),
                scheme = substitution.apply(constraint.scheme)
            )
            is Constraint.AbilityConstraint -> {
                // Apply substitution to type variable if it exists in the mapping
                val newTypeVar = substitution.mapping[constraint.typeVar]?.let { type ->
                    when (type) {
                        is FunkType.VarType -> type.variable
                        else -> constraint.typeVar // Keep original if substituted to concrete type
                    }
                } ?: constraint.typeVar

                Constraint.AbilityConstraint(
                    typeVar = newTypeVar,
                    ability = constraint.ability,
                    sourceLocation = constraint.sourceLocation
                )
            }
            is Constraint.AnnotationConstraint -> {
                Constraint.AnnotationConstraint(
                    inferredType = substitution.apply(constraint.inferredType),
                    declaredType = substitution.apply(constraint.declaredType),
                    expression = constraint.expression,
                    sourceLocation = constraint.sourceLocation
                )
            }
        }
    }
    
    /**
     * Unify two types and return the most general unifier (with memoization)
     */
    private fun unify(type1: FunkType, type2: FunkType): Substitution {
        // Create a canonical cache key (order-independent for symmetric unification)
        val cacheKey = canonicalizeTypePair(type1, type2)

        // Check cache first
        unificationCache[cacheKey]?.let { cached ->
            unificationCacheHits++
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Unification cache HIT for ${prettyPrintType(type1)} ~ ${prettyPrintType(type2)}")
            }
            return cached
        }

        unificationCacheMisses++

        // Compute unification
        val result = unifyUncached(type1, type2)

        // Store in cache
        unificationCache[cacheKey] = result

        return result
    }

    /**
     * Canonicalize a pair of types for cache key (order-independent)
     */
    private fun canonicalizeTypePair(type1: FunkType, type2: FunkType): Pair<FunkType, FunkType> {
        // Use structural hash to create order-independent key
        val hash1 = type1.hashCode()
        val hash2 = type2.hashCode()
        return if (hash1 <= hash2) {
            Pair(type1, type2)
        } else {
            Pair(type2, type1)
        }
    }

    /**
     * Unify two types without caching (actual implementation)
     */
    private fun unifyUncached(type1: FunkType, type2: FunkType): Substitution {
        // CRITICAL FIX: Expand TypeApplication before unification
        // This ensures that TypeApplication (like Tree Int) is properly expanded to its
        // underlying VariantType before attempting unification
        var expandedType1 = type1
        var expandedType2 = type2

        // Expand type1 if it's a TypeApplication
        if (type1 is FunkType.TypeApplication) {
            val expanded = expandTypeApplication(type1)
            if (expanded != null) {
                expandedType1 = expanded
                if (System.getProperty("funk.typecheck.debug") == "true") {
                    println("DEBUG: Expanded type1 from ${prettyPrintType(type1)} to ${prettyPrintType(expandedType1)}")
                }
            } else {
                if (System.getProperty("funk.typecheck.debug") == "true") {
                    println("DEBUG: Could not expand TypeApplication: ${type1.constructor}")
                }
            }
        }

        // Expand type2 if it's a TypeApplication
        if (type2 is FunkType.TypeApplication) {
            val expanded = expandTypeApplication(type2)
            if (expanded != null) {
                expandedType2 = expanded
                if (System.getProperty("funk.typecheck.debug") == "true") {
                    println("DEBUG: Expanded type2 from ${prettyPrintType(type2)} to ${prettyPrintType(expandedType2)}")
                }
            } else {
                if (System.getProperty("funk.typecheck.debug") == "true") {
                    println("DEBUG: Could not expand TypeApplication: ${type2.constructor}")
                }
            }
        }

        return when {
            // Same type
            expandedType1 == expandedType2 -> Substitution.empty()

            // Type variable cases
            expandedType1 is FunkType.VarType -> unifyVariable(expandedType1.variable, expandedType2)
            expandedType2 is FunkType.VarType -> unifyVariable(expandedType2.variable, expandedType1)

            // Function types
            expandedType1 is FunkType.FunctionType && expandedType2 is FunkType.FunctionType -> {
                val paramUnifier = unify(expandedType1.paramType, expandedType2.paramType)
                val returnUnifier = unify(
                    paramUnifier.apply(expandedType1.returnType),
                    paramUnifier.apply(expandedType2.returnType)
                )
                // Unify effect rows (no substitution needed as effects don't contain type variables)
                val effectsUnifier = unifyEffectRows(expandedType1.effects, expandedType2.effects)
                paramUnifier.compose(returnUnifier).compose(effectsUnifier)
            }

            // Record types
            expandedType1 is FunkType.RecordType && expandedType2 is FunkType.RecordType -> {
                unifyRecords(expandedType1, expandedType2)
            }

            // Variant types
            expandedType1 is FunkType.VariantType && expandedType2 is FunkType.VariantType -> {
                unifyVariants(expandedType1, expandedType2)
            }

            // Array types - only unify with other Arrays
            expandedType1 is FunkType.ArrayType && expandedType2 is FunkType.ArrayType -> {
                unify(expandedType1.elementType, expandedType2.elementType)
            }

            // List types - only unify with other Lists
            expandedType1 is FunkType.ListType && expandedType2 is FunkType.ListType -> {
                unify(expandedType1.elementType, expandedType2.elementType)
            }

            // Map types
            expandedType1 is FunkType.MapType && expandedType2 is FunkType.MapType -> {
                val keyUnifier = unify(expandedType1.keyType, expandedType2.keyType)
                val valueUnifier = unify(
                    keyUnifier.apply(expandedType1.valueType),
                    keyUnifier.apply(expandedType2.valueType)
                )
                keyUnifier.compose(valueUnifier)
            }

            // Set types
            expandedType1 is FunkType.SetType && expandedType2 is FunkType.SetType -> {
                unify(expandedType1.elementType, expandedType2.elementType)
            }

            // BLOCK: Array vs non-Array types (except type variables)
            expandedType1 is FunkType.ArrayType -> {
                throw ConstraintConflictError(
                    "Type conflict: Array cannot unify with ${prettyPrintType(expandedType2)}. Arrays and Lists are distinct types.",
                    listOf(expandedType1, expandedType2)
                )
            }
            expandedType2 is FunkType.ArrayType -> {
                throw ConstraintConflictError(
                    "Type conflict: ${prettyPrintType(expandedType1)} cannot unify with Array. Arrays and Lists are distinct types.",
                    listOf(expandedType1, expandedType2)
                )
            }

            // Base types must be identical - strict type checking
            else -> {
                // Check for common type conflicts and provide helpful error messages
                when {
                    expandedType1 is FunkType.IntType && expandedType2 is FunkType.FloatType -> {
                        throw ConstraintConflictError(
                            "Type conflict: Cannot unify Int and Float. Consider using explicit type conversion.",
                            listOf(expandedType1, expandedType2)
                        )
                    }
                    expandedType1 is FunkType.FloatType && expandedType2 is FunkType.IntType -> {
                        throw ConstraintConflictError(
                            "Type conflict: Cannot unify Float and Int. Consider using explicit type conversion.",
                            listOf(expandedType1, expandedType2)
                        )
                    }
                    expandedType1 is FunkType.IntType && expandedType2 is FunkType.NatType -> {
                        throw ConstraintConflictError(
                            "Type conflict: Cannot unify Int and Nat. Use 'n' suffix for Nat literals (e.g., 5n).",
                            listOf(expandedType1, expandedType2)
                        )
                    }
                    expandedType1 is FunkType.NatType && expandedType2 is FunkType.IntType -> {
                        throw ConstraintConflictError(
                            "Type conflict: Cannot unify Nat and Int. Use 'n' suffix for Nat literals (e.g., 5n).",
                            listOf(expandedType1, expandedType2)
                        )
                    }
                    expandedType1 is FunkType.StringType && (expandedType2 is FunkType.IntType || expandedType2 is FunkType.FloatType || expandedType2 is FunkType.BooleanType) -> {
                        throw ConstraintConflictError(
                            "Type conflict: Cannot unify String with ${prettyPrintType(expandedType2)}. Consider using string conversion functions.",
                            listOf(expandedType1, expandedType2)
                        )
                    }
                    (expandedType1 is FunkType.IntType || expandedType1 is FunkType.FloatType || expandedType1 is FunkType.BooleanType) && expandedType2 is FunkType.StringType -> {
                        throw ConstraintConflictError(
                            "Type conflict: Cannot unify ${prettyPrintType(expandedType1)} with String. Consider using string conversion functions.",
                            listOf(expandedType1, expandedType2)
                        )
                    }
                    else -> {
                        // For other cases (like different variant/record types), just return empty substitution
                        // This allows structural subtyping to work
                        if (System.getProperty("funk.typecheck.debug") == "true") {
                            println("Warning: Cannot unify ${prettyPrintType(expandedType1)} with ${prettyPrintType(expandedType2)} - ignoring constraint")
                        }
                        Substitution.empty()
                    }
                }
            }
        }
    }
    
    private fun unifyVariable(typeVar: TypeVar, type: FunkType): Substitution {
        return when (type) {
            is FunkType.VarType -> {
                if (typeVar == type.variable) {
                    Substitution.empty()
                } else {
                    Substitution.single(typeVar, type)
                }
            }
            else -> {
                // Occurs check: prevent infinite types like T = T -> Int
                if (occursCheck(typeVar, type)) {
                    // Instead of throwing, just return empty substitution
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Warning: Occurs check failed: $typeVar occurs in $type - ignoring constraint")
                    }
                    return Substitution.empty()
                }
                Substitution.single(typeVar, type)
            }
        }
    }
    
    /**
     * Occurs check: returns true if typeVar occurs in type
     */
    private fun occursCheck(typeVar: TypeVar, type: FunkType): Boolean {
        return when (type) {
            is FunkType.VarType -> typeVar == type.variable
            is FunkType.FunctionType -> 
                occursCheck(typeVar, type.paramType) || occursCheck(typeVar, type.returnType)
            is FunkType.RecordType -> 
                type.fields.values.any { occursCheck(typeVar, it) }
            is FunkType.VariantType -> 
                type.constructors.values.any { it != null && occursCheck(typeVar, it) }
            is FunkType.EffectRow -> false // Effects are strings, not types with type variables
            else -> false
        }
    }
    
    private fun unifyRecords(record1: FunkType.RecordType, record2: FunkType.RecordType): Substitution {
        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Unifying records:")
            println("  Record1: {${record1.fields.map { "${it.key}: ${prettyPrintType(it.value)}" }.joinToString(", ")}}")
            println("  Record2: {${record2.fields.map { "${it.key}: ${prettyPrintType(it.value)}" }.joinToString(", ")}}")
        }

        // Handle record unification with proper open/closed semantics
        val fields1 = record1.fields.keys.toSet()
        val fields2 = record2.fields.keys.toSet()
        val commonFields = fields1.intersect(fields2)
        val disjointFields1 = fields1 - fields2
        val disjointFields2 = fields2 - fields1

        // CRITICAL FIX: When unifying two CLOSED records with disjoint fields,
        // this represents accumulation of field accesses on the same variable.
        // Example: rest.yes and rest.no should merge into {yes: 'a, no: 'b}
        //
        // This is distinct from traditional record unification which requires exact field matches.
        // Here, we MERGE disjoint fields when both records are closed.
        if (record1.isClosed && record2.isClosed && disjointFields1.isNotEmpty() && disjointFields2.isNotEmpty()) {
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Merging disjoint closed records")
                println("  Fields only in record1: $disjointFields1")
                println("  Fields only in record2: $disjointFields2")
                println("  Common fields: $commonFields")
            }

            // Unify common fields first
            var substitution = Substitution.empty()
            for (fieldName in commonFields) {
                val fieldType1 = record1.fields[fieldName]!!
                val fieldType2 = record2.fields[fieldName]!!
                val fieldUnifier = unify(substitution.apply(fieldType1), substitution.apply(fieldType2))
                substitution = substitution.compose(fieldUnifier)
            }

            // The merged fields will be created by the type inference system
            // We don't need to create a new record type here - just return the substitution
            // from unifying common fields
            return substitution
        }

        if (fields1 != fields2) {
            // Check if this is a valid subtyping relationship
            when {
                // Both closed - must have exact same fields (strict validation)
                record1.isClosed && record2.isClosed -> {
                    // Only throw if this is clearly a type error (e.g., function call with wrong record)
                    // For now, be more permissive and only unify common fields
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Warning: Unifying closed records with different fields: $fields1 vs $fields2")
                    }
                }

                // One closed, one open - this should generally work (structural subtyping)
                record1.isClosed && record2.isOpen -> {
                    // Closed record can be used where open record is expected if it has required fields
                    val missingFields = fields2 - fields1
                    if (missingFields.isNotEmpty()) {
                        if (System.getProperty("funk.typecheck.debug") == "true") {
                            println("Warning: Closed record missing fields: $missingFields")
                        }
                    }
                }

                record1.isOpen && record2.isClosed -> {
                    // Open record can be used where closed record is expected if it has all required fields
                    val missingFields = fields2 - fields1
                    if (missingFields.isNotEmpty()) {
                        if (System.getProperty("funk.typecheck.debug") == "true") {
                            println("WARNING: Open record missing required fields: $missingFields")
                        }
                    }
                }

                // Both open - unify common fields (always allowed)
                record1.isOpen && record2.isOpen -> {
                    // This is always allowed - structural subtyping
                }
            }
        }

        // Unify common fields
        var substitution = Substitution.empty()

        for (fieldName in commonFields) {
            val fieldType1 = record1.fields[fieldName]!!
            val fieldType2 = record2.fields[fieldName]!!

            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Unifying field $fieldName: ${prettyPrintType(fieldType1)} = ${prettyPrintType(fieldType2)}")
            }

            val fieldUnifier = unify(substitution.apply(fieldType1), substitution.apply(fieldType2))
            substitution = substitution.compose(fieldUnifier)

            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Field unifier for $fieldName: ${fieldUnifier.mapping}")
                println("DEBUG: Composed substitution: ${substitution.mapping}")
            }
        }

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Final record substitution: ${substitution.mapping}")
        }

        return substitution
    }
    
    private fun unifyVariants(variant1: FunkType.VariantType, variant2: FunkType.VariantType): Substitution {
        // Handle variant subtyping: open variants can be unified with closed variants
        val isVariant1Open = variant1.rowVar != null
        val isVariant2Open = variant2.rowVar != null
        
        return when {
            // Both closed - unify common constructors
            // PERMISSIVE: Allow unifying closed variants with different constructors
            // This is needed for match branches that return different variant constructors
            // e.g., match x is Red -> Blue; _ -> Yellow should give type | Blue, Yellow |
            !isVariant1Open && !isVariant2Open -> {
                unifyVariantConstructors(variant1, variant2, onlyCommon = true)
            }
            
            // One open, one closed - check if closed is subset of open
            isVariant1Open && !isVariant2Open -> {
                // variant2 (closed) must be a subset of variant1 (open) - be more permissive
                if (!variant2.constructors.keys.all { it in variant1.constructors.keys }) {
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Warning: Closed variant has constructors not in open variant: ${variant2.constructors.keys - variant1.constructors.keys} - using common constructors only")
                    }
                }
                unifyVariantConstructors(variant1, variant2, onlyCommon = true)
            }
            
            !isVariant1Open && isVariant2Open -> {
                // variant1 (closed) must be a subset of variant2 (open) - be more permissive
                if (!variant1.constructors.keys.all { it in variant2.constructors.keys }) {
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Warning: Closed variant has constructors not in open variant: ${variant1.constructors.keys - variant2.constructors.keys} - using common constructors only")
                    }
                }
                unifyVariantConstructors(variant1, variant2, onlyCommon = true)
            }
            
            // Both open - unify common constructors
            else -> {
                unifyVariantConstructors(variant1, variant2, onlyCommon = true)
            }
        }
    }
    
    private fun unifyVariantConstructors(
        variant1: FunkType.VariantType, 
        variant2: FunkType.VariantType, 
        onlyCommon: Boolean = false
    ): Substitution {
        var substitution = Substitution.empty()
        
        val constructorsToUnify = if (onlyCommon) {
            variant1.constructors.keys.intersect(variant2.constructors.keys)
        } else {
            variant1.constructors.keys
        }
        
        for (constructorName in constructorsToUnify) {
            val dataType1 = variant1.constructors[constructorName]
            val dataType2 = variant2.constructors[constructorName]
            
            when {
                dataType1 == null && dataType2 == null -> {
                    // Both constructors have no data - OK
                }
                dataType1 != null && dataType2 != null -> {
                    val constructorUnifier = unify(substitution.apply(dataType1), substitution.apply(dataType2))
                    substitution = substitution.compose(constructorUnifier)
                }
                else -> {
                    if (System.getProperty("funk.typecheck.debug") == "true") {
                        println("Warning: Constructor $constructorName has mismatched data types: $dataType1 vs $dataType2 - ignoring constraint")
                    }
                    Substitution.empty()
                }
            }
        }
        
        return substitution
    }

    /**
     * Expand a TypeApplication to its underlying type definition by substituting type parameters.
     *
     * For example, given:
     *   type Tree 'a = | Leaf, Node { val: 'a, left: Tree 'a, right: Tree 'a } |
     *
     * expandTypeApplication(Tree Int) returns:
     *   | Leaf, Node { val: Int, left: Tree Int, right: Tree Int } |
     *
     * @param typeApp The TypeApplication to expand (e.g., Tree Int)
     * @return The expanded type with type parameters substituted, or null if the type alias is not found
     */
    internal fun expandTypeApplication(typeApp: FunkType.TypeApplication): FunkType? {
        // Look up the type alias definition
        val aliasInfo = typeAliases[typeApp.constructor]

        if (aliasInfo == null) {
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Type alias '${typeApp.constructor}' not found in typeAliases")
            }
            return null
        }

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Expanding TypeApplication ${typeApp.constructor} with args: ${typeApp.args.map { prettyPrintType(it) }}")
            println("DEBUG: Type alias has ${aliasInfo.typeParams.size} parameters: ${aliasInfo.typeParams}")
        }

        // Check that the number of type arguments matches the number of type parameters
        if (typeApp.args.size != aliasInfo.typeParams.size) {
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("DEBUG: Type argument count mismatch: expected ${aliasInfo.typeParams.size}, got ${typeApp.args.size}")
            }
            return null
        }

        // Build a substitution map from type parameter names to the provided type arguments
        // Type parameters are strings like "'a", "'b", etc.
        val substitutionMap = mutableMapOf<TypeVar, FunkType>()
        for ((param, arg) in aliasInfo.typeParams.zip(typeApp.args)) {
            // Create a TypeVar from the parameter name (remove leading quote if present)
            val paramName = if (param.startsWith("'")) param else "'$param"
            substitutionMap[TypeVar(paramName)] = arg
        }

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Built substitution map: ${substitutionMap.map { (k, v) -> "${k.name} -> ${prettyPrintType(v)}" }}")
        }

        // Apply the substitution to the alias definition
        val substitution = Substitution(substitutionMap)
        val expandedType = substitution.apply(aliasInfo.definition)

        if (System.getProperty("funk.typecheck.debug") == "true") {
            println("DEBUG: Expanded ${typeApp.constructor} to: ${prettyPrintType(expandedType)}")
        }

        return expandedType
    }

    /**
     * Unify two effect rows according to effect polymorphism rules
     *
     * Rules:
     * 1. <...> ∪ <...> = <...> (both purely polymorphic)
     * 2. <...> ∪ <E1, E2> = <E1, E2> (polymorphic unifies with concrete)
     * 3. <E1, ...> ∪ <E2, E3> = <E1, E2, E3> (extended unifies with concrete)
     * 4. <E1, ...> ∪ <E2, ...> = <E1, E2, ...> (both extended)
     * 5. <E1, E2> ∪ <E2, E3> = <E1, E2, E3> (merge concrete effects)
     */
    private fun unifyEffectRows(
        row1: FunkType.EffectRow?,
        row2: FunkType.EffectRow?
    ): Substitution {
        // If both are null (no effects), they unify
        if (row1 == null && row2 == null) {
            return Substitution.empty()
        }

        // If one is null and the other is not, they don't unify for now
        // (could be made more permissive later)
        if (row1 == null || row2 == null) {
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("Warning: Unifying null effect row with non-null effect row - using non-null")
            }
            return Substitution.empty()
        }

        // Check if rows are purely polymorphic (rowVar != null, no concrete effects)
        val isPurelyPolymorphic1 = row1.isOpen && row1.effects.isEmpty()
        val isPurelyPolymorphic2 = row2.isOpen && row2.effects.isEmpty()

        return when {
            // Rule 1: Both purely polymorphic - they unify
            isPurelyPolymorphic1 && isPurelyPolymorphic2 -> {
                Substitution.empty()
            }

            // Rule 2: One is purely polymorphic - use the concrete one
            isPurelyPolymorphic1 -> {
                Substitution.empty()
            }
            isPurelyPolymorphic2 -> {
                Substitution.empty()
            }

            // Rules 3-5: Merge effects
            else -> {
                // Effects are just strings, no type variable substitution needed
                // The merged effect row will be constructed at a higher level
                Substitution.empty()
            }
        }
    }

    /**
     * Check if two types are clearly incompatible base types
     */
    private fun isIncompatibleBaseTypes(type1: FunkType, type2: FunkType): Boolean {
        // Define clearly incompatible combinations
        val baseTypes = setOf(
            FunkType.StringType::class,
            FunkType.IntType::class,
            FunkType.FloatType::class,
            FunkType.BooleanType::class,
            FunkType.NatType::class,
            FunkType.FracType::class
        )
        
        val type1Class = type1::class
        val type2Class = type2::class
        
        // Both are base types but different
        return type1Class in baseTypes && type2Class in baseTypes && type1Class != type2Class
    }
}

/**
 * Exception thrown when unification fails
 */
class UnificationException(message: String) : Exception(message)

/**
 * Exception thrown when ability constraint validation fails
 */
class AbilityConstraintError(
    message: String,
    val sourceLocation: SourceLocation?,
    val type: FunkType,
    val ability: String
) : Exception(message)

/**
 * Helper function to pretty print types (placeholder - should use actual implementation)
 */
private fun prettyPrintType(type: FunkType): String {
    return when (type) {
        is FunkType.IntType -> "Int"
        is FunkType.FracType -> "Frac"
        is FunkType.NatType -> "Nat"
        is FunkType.BooleanType -> "Bool"
        is FunkType.StringType -> "String"
        is FunkType.VarType -> type.variable.name
        is FunkType.FunctionType -> {
            // Add parentheses around function types when they appear as parameter types
            val paramStr = if (type.paramType is FunkType.FunctionType) {
                "(${prettyPrintType(type.paramType)})"
            } else {
                prettyPrintType(type.paramType)
            }
            "$paramStr -> ${prettyPrintType(type.returnType)}"
        }
        is FunkType.RecordType -> "{${type.fields.map { "${it.key}: ${prettyPrintType(it.value)}" }.joinToString(", ")}}"
        else -> type.toString()
    }
}