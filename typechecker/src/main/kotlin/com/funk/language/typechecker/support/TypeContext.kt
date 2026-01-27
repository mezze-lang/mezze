package com.funk.language.typechecker.support

import com.funk.language.ast.*
import com.funk.language.typechecker.*

/**
 * Centralized state container for type checking operations.
 *
 * This class consolidates all mutable state previously scattered throughout the TypeChecker class,
 * making it easier to:
 * - Pass state to engine functions
 * - Test individual components in isolation
 * - Track and debug state changes
 * - Reset state between type checking sessions
 *
 * # Architecture
 *
 * The TypeContext is passed to all engine functions (InferenceEngine, ValidationEngine, etc.)
 * and provides centralized access to:
 * - Type environment (variable bindings)
 * - Type, ability, and effect declarations
 * - Type aliases and newtypes
 * - Ability implementations
 * - Effect performer mappings
 * - Constraint tracking
 * - Module resolution
 * - Type variable generation
 *
 * # Phase 4 Enhancement
 *
 * This version of TypeContext has been enhanced in Phase 4 to consolidate additional
 * state fields from TypeChecker, including:
 * - Type/ability/effect declaration maps
 * - Performer-to-effect mappings
 * - Module binding tracking
 * - Imported values
 *
 * # Thread Safety
 *
 * TypeContext is NOT thread-safe. Each type checking session should use its own instance.
 *
 * @property currentModule The name of the module currently being type-checked (default: "main")
 * @property currentFilePath The file path of the current module being type-checked
 * @property moduleResolver The module resolver for handling imports and dependencies
 */
data class TypeContext(
    // ========== Module Information ==========
    var currentModule: String = "main",
    var currentFilePath: String? = null,
    var moduleResolver: ModuleResolver? = null,

    // ========== Type Environment ==========
    // Maps variable names to their types (monomorphic bindings)
    val context: MutableMap<String, FunkType> = mutableMapOf(),

    // Type schemes for polymorphic bindings (let-polymorphism)
    val typeSchemes: MutableMap<String, TypeScheme> = mutableMapOf(),

    // Imported values from other modules
    val importedValues: MutableMap<String, FunkType> = mutableMapOf(),

    // Maps binding names to their source module (null = current module)
    val bindingSourceModule: MutableMap<String, String?> = mutableMapOf(),

    // ========== Type Definitions ==========
    // Type declarations (records, variants, etc.)
    val typeDeclarationMap: MutableMap<String, TypeDeclaration> = mutableMapOf(),

    // Type aliases defined in the program
    val typeAliases: MutableMap<String, TypeAliasInfo> = mutableMapOf(),

    // Newtype definitions
    val newtypes: MutableMap<String, NewtypeInfo> = mutableMapOf(),

    // ========== Ability System ==========
    // Ability declarations
    val abilityDeclarationMap: MutableMap<String, AbilityDeclaration> = mutableMapOf(),

    // Ability dependencies (for ability requires clauses)
    val abilityDependencies: MutableMap<String, List<AbilityDependency>> = mutableMapOf(),

    // Ability (type class) implementations
    val implAbilities: MutableMap<String, List<ImplAbilityDeclaration>> = mutableMapOf(),

    // ========== Effect System ==========
    // Effect declarations
    val effectDeclarationMap: MutableMap<String, EffectDeclaration> = mutableMapOf(),

    // Maps performer names to their effect names
    val performerToEffectMap: MutableMap<String, String> = mutableMapOf(),

    // Maps performer names to their implementation declarations
    val performerImplMap: MutableMap<String, ImplDeclaration> = mutableMapOf(),

    // ========== Constraint Tracking ==========
    // Type equality constraints for unification
    val constraints: MutableList<Pair<FunkType, FunkType>> = mutableListOf(),

    // Ability constraints for type variables
    val abilityConstraints: MutableList<Triple<TypeVar, String, SourceLocation?>> = mutableListOf(),

    // ========== Type Variable Generation ==========
    // Type variable counter for generating fresh type variables
    private var typeVarCounter: Int = 0
) {
    /**
     * Generate a fresh type variable with a unique name.
     *
     * Type variables are named using Greek letters (α, β, γ, etc.) for readability
     * in error messages and type signatures.
     *
     * @return A new TypeVar with a unique name
     */
    fun freshTypeVar(): TypeVar {
        val name = when (typeVarCounter % 24) {
            0 -> "α"
            1 -> "β"
            2 -> "γ"
            3 -> "δ"
            4 -> "ε"
            5 -> "ζ"
            6 -> "η"
            7 -> "θ"
            8 -> "ι"
            9 -> "κ"
            10 -> "λ"
            11 -> "μ"
            12 -> "ν"
            13 -> "ξ"
            14 -> "ο"
            15 -> "π"
            16 -> "ρ"
            17 -> "σ"
            18 -> "τ"
            19 -> "υ"
            20 -> "φ"
            21 -> "χ"
            22 -> "ψ"
            23 -> "ω"
            else -> "α"
        }
        val suffix = if (typeVarCounter >= 24) "${typeVarCounter / 24}" else ""
        typeVarCounter++
        return TypeVar("'$name$suffix")
    }

    /**
     * Add a type binding to the context.
     *
     * @param name The variable name
     * @param type The type to bind
     */
    fun addBinding(name: String, type: FunkType) {
        context[name] = type
    }

    /**
     * Look up a variable's type in the context.
     *
     * @param name The variable name to look up
     * @return The type if found, null otherwise
     */
    fun lookupType(name: String): FunkType? {
        return context[name]
    }

    /**
     * Add a type scheme for a polymorphic binding.
     *
     * Type schemes represent let-polymorphism where a variable can be instantiated
     * with different type arguments at each use site.
     *
     * @param name The variable name
     * @param scheme The type scheme
     */
    fun addTypeScheme(name: String, scheme: TypeScheme) {
        typeSchemes[name] = scheme
    }

    /**
     * Look up a type scheme.
     *
     * @param name The variable name
     * @return The type scheme if found, null otherwise
     */
    fun lookupTypeScheme(name: String): TypeScheme? {
        return typeSchemes[name]
    }

    /**
     * Add a type alias definition.
     *
     * @param name The alias name
     * @param info The type alias information
     */
    fun addTypeAlias(name: String, info: TypeAliasInfo) {
        typeAliases[name] = info
    }

    /**
     * Add a newtype definition.
     *
     * @param name The newtype name
     * @param info The newtype information
     */
    fun addNewtype(name: String, info: NewtypeInfo) {
        newtypes[name] = info
    }

    /**
     * Add ability implementations for a type.
     *
     * @param abilityName The name of the ability
     * @param impls The list of implementations
     */
    fun addAbilityImplementations(abilityName: String, impls: List<ImplAbilityDeclaration>) {
        implAbilities[abilityName] = impls
    }

    /**
     * Add a type equality constraint.
     *
     * @param left The left-hand type
     * @param right The right-hand type
     */
    fun addConstraint(left: FunkType, right: FunkType) {
        constraints.add(Pair(left, right))
    }

    /**
     * Add an ability constraint for a type variable.
     *
     * @param typeVar The type variable that must have the ability
     * @param ability The ability name (e.g., "NumAdd", "Eq")
     * @param location Optional source location for error reporting
     */
    fun addAbilityConstraint(typeVar: TypeVar, ability: String, location: SourceLocation? = null) {
        abilityConstraints.add(Triple(typeVar, ability, location))
    }

    /**
     * Clear all constraints.
     *
     * This is typically called after constraints are solved to prepare for
     * the next inference session.
     */
    fun clearConstraints() {
        constraints.clear()
        abilityConstraints.clear()
    }

    /**
     * Create a child context with a new scope.
     *
     * The child context shares type/ability/effect declarations with the parent,
     * but has its own variable bindings and constraints.
     *
     * This is useful for checking function bodies where local bindings shouldn't
     * leak to the outer scope.
     *
     * @return A new TypeContext with copied state
     */
    fun createChildContext(): TypeContext {
        return TypeContext(
            // Module info
            currentModule = currentModule,
            currentFilePath = currentFilePath,
            moduleResolver = moduleResolver,

            // Type environment (copied)
            context = context.toMutableMap(),
            typeSchemes = typeSchemes.toMutableMap(),
            importedValues = importedValues.toMutableMap(),
            bindingSourceModule = bindingSourceModule.toMutableMap(),

            // Type definitions (shared)
            typeDeclarationMap = typeDeclarationMap,
            typeAliases = typeAliases,
            newtypes = newtypes,

            // Ability system (shared)
            abilityDeclarationMap = abilityDeclarationMap,
            abilityDependencies = abilityDependencies,
            implAbilities = implAbilities,

            // Effect system (shared)
            effectDeclarationMap = effectDeclarationMap,
            performerToEffectMap = performerToEffectMap,
            performerImplMap = performerImplMap,

            // Constraints (fresh)
            constraints = mutableListOf(),
            abilityConstraints = mutableListOf(),

            // Type variable counter (continued)
            typeVarCounter = typeVarCounter
        )
    }

    /**
     * Reset the context for a new type checking session.
     *
     * Clears all bindings, constraints, and resets the type variable counter.
     * Keeps type/ability/effect declarations.
     */
    fun reset() {
        // Clear type environment
        context.clear()
        typeSchemes.clear()
        importedValues.clear()
        bindingSourceModule.clear()

        // Clear constraints
        constraints.clear()
        abilityConstraints.clear()

        // Reset counter
        typeVarCounter = 0
    }

    /**
     * Get a summary of the current context state for debugging.
     *
     * @return A string representation of the context
     */
    fun debugSummary(): String {
        return """
            TypeContext Summary:
              Module: $currentModule
              File: $currentFilePath

              Type Environment:
                Bindings: ${context.size}
                Type Schemes: ${typeSchemes.size}
                Imported Values: ${importedValues.size}
                Binding Sources: ${bindingSourceModule.size}

              Type Definitions:
                Type Declarations: ${typeDeclarationMap.size}
                Type Aliases: ${typeAliases.size}
                Newtypes: ${newtypes.size}

              Ability System:
                Ability Declarations: ${abilityDeclarationMap.size}
                Ability Dependencies: ${abilityDependencies.size}
                Ability Implementations: ${implAbilities.size}

              Effect System:
                Effect Declarations: ${effectDeclarationMap.size}
                Performers: ${performerToEffectMap.size}
                Performer Impls: ${performerImplMap.size}

              Constraints:
                Type Constraints: ${constraints.size}
                Ability Constraints: ${abilityConstraints.size}

              Next Type Var: $typeVarCounter
        """.trimIndent()
    }
}

/**
 * Helper function to create a TypeContext with common builtin types pre-populated.
 *
 * @param moduleResolver Optional module resolver to use
 * @return A new TypeContext ready for type checking
 */
fun createTypeContext(moduleResolver: ModuleResolver? = null): TypeContext {
    return TypeContext(moduleResolver = moduleResolver)
}
