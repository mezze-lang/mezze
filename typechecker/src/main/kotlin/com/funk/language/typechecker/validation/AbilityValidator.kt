package com.funk.language.typechecker.validation

import com.funk.language.ast.*
import com.funk.language.typechecker.TypeCheckError
import com.funk.language.typechecker.abilities.BuiltInAbilities

/**
 * Validates ability implementations against ability declarations.
 *
 * Based on validation logic from OldTypeChecker.kt:13063-13122
 * which implements all 8 validation rules for abilities.
 */
object AbilityValidator {
    /**
     * Validate ability implementation against declaration.
     *
     * Checks:
     * 1. Ability exists
     * 2. Type parameter count matches
     * 3. Associated type consistency:
     *    - If ability declares associated types, impl must provide bindings
     *    - If ability doesn't declare associated types, impl cannot provide bindings
     *    - All declared associated types must have bindings
     *    - No extra bindings not declared in ability
     *
     * @param impl The ability implementation to validate
     * @param abilityDeclarations Map of ability name to declaration
     * @throws TypeCheckError if validation fails
     */
    fun validateAbilityImplementation(
        impl: ImplAbilityDeclaration,
        abilityDeclarations: Map<String, AbilityDeclaration>
    ) {
        // Find the ability declaration
        val abilityDeclaration = abilityDeclarations[impl.abilityName]
            ?: throw TypeCheckError(
                "Ability '${impl.abilityName}' not found - cannot validate implementation"
            )

        // Rule 1: Check type parameter count matches
        if (impl.targetTypes.size != abilityDeclaration.typeParams.size) {
            throw TypeCheckError(
                "Type parameter count mismatch in ability implementation for '${impl.abilityName}': " +
                "ability expects ${abilityDeclaration.typeParams.size} type parameters, " +
                "but implementation provides ${impl.targetTypes.size}"
            )
        }

        // Rule 2-5: Validate associated types
        val abilityAssociatedTypes = abilityDeclaration.associatedTypes
        val implAssociatedTypeBindings = impl.associatedTypeBindings

        // Rule 2: If ability declares associated types, impl must provide bindings
        if (abilityAssociatedTypes.isNotEmpty() && implAssociatedTypeBindings.isEmpty()) {
            val typesList = abilityAssociatedTypes.joinToString(", ")
            throw TypeCheckError(
                "Ability '${impl.abilityName}' declares associated types [$typesList] " +
                "but implementation does not provide bindings. " +
                "Use: impl ${impl.abilityName} ${impl.targetTypes.joinToString(" ")} " +
                "having ${abilityAssociatedTypes.joinToString(", ") { "$it = <Type>" }} where { ... }"
            )
        }

        // Rule 3: If ability doesn't declare associated types, impl cannot provide bindings
        if (abilityAssociatedTypes.isEmpty() && implAssociatedTypeBindings.isNotEmpty()) {
            val bindingsList = implAssociatedTypeBindings.keys.joinToString(", ")
            throw TypeCheckError(
                "Implementation provides associated type bindings [$bindingsList] " +
                "but ability '${impl.abilityName}' does not declare associated types. " +
                "Remove the 'having' clause or add it to the ability declaration."
            )
        }

        // Rule 4: Check that all declared associated types have bindings
        val missingBindings = abilityAssociatedTypes.filter { it !in implAssociatedTypeBindings.keys }
        if (missingBindings.isNotEmpty()) {
            throw TypeCheckError(
                "Ability '${impl.abilityName}' declares associated types ${abilityAssociatedTypes.joinToString(", ")} " +
                "but implementation is missing bindings for: ${missingBindings.joinToString(", ")}. " +
                "All associated types must be bound in the implementation."
            )
        }

        // Rule 5: Check that impl doesn't provide extra bindings not declared in ability
        val extraBindings = implAssociatedTypeBindings.keys.filter { it !in abilityAssociatedTypes }
        if (extraBindings.isNotEmpty()) {
            throw TypeCheckError(
                "Implementation provides bindings for ${extraBindings.joinToString(", ")} " +
                "but ability '${impl.abilityName}' only declares: ${abilityAssociatedTypes.joinToString(", ")}. " +
                "Remove the extra bindings or add them to the ability declaration."
            )
        }

        // Rule 6: Check all operations are implemented (OldTypeChecker.kt:13129-13138)
        val declaredOps = abilityDeclaration.operations.map { it.name }.toSet()
        val implementedOps = impl.operations.map { it.name }.toSet()
        val missingOps = declaredOps - implementedOps

        if (missingOps.isNotEmpty()) {
            throw TypeCheckError(
                "Missing operations in implementation of ability '${impl.abilityName}': ${missingOps.joinToString(", ")}"
            )
        }

        // Rule 7: Check no extra operations (OldTypeChecker.kt:13140-13146)
        val extraOps = implementedOps - declaredOps
        if (extraOps.isNotEmpty()) {
            throw TypeCheckError(
                "Extra operations in implementation of ability '${impl.abilityName}' not declared in ability: ${extraOps.joinToString(", ")}"
            )
        }

        // Rule 8: Validate operation signatures match after type parameter substitution
        // (OldTypeChecker.kt:13148-13198)
        validateOperationSignatures(impl, abilityDeclaration)
    }

    /**
     * Validate that all operation signatures in the implementation match the declaration
     * after substituting type parameters.
     *
     * Based on OldTypeChecker.kt:13148-13198
     */
    private fun validateOperationSignatures(
        impl: ImplAbilityDeclaration,
        abilityDeclaration: AbilityDeclaration
    ) {
        // Build type parameter substitution map
        // e.g., for impl Show Int: 'a -> Int
        val substitution = abilityDeclaration.typeParams.zip(impl.targetTypes).toMap()

        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue  // Already caught by missing/extra operation checks

            // Substitute type parameters in declaration types
            val expectedParamType = substituteTypeParameters(declOp.paramType, substitution)
            val expectedReturnType = substituteTypeParameters(declOp.returnType, substitution)

            // Get actual types from implementation (if provided)
            val actualParamType = implOp.paramType ?: expectedParamType
            val actualReturnType = implOp.returnType ?: expectedReturnType

            // Check for type variables in concrete implementations
            if (implOp.paramType != null && hasTypeVariables(actualParamType)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' parameter type contains type variables. " +
                    "All type variables must be replaced with concrete types."
                )
            }
            if (implOp.returnType != null && hasTypeVariables(actualReturnType)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' return type contains type variables. " +
                    "All type variables must be replaced with concrete types."
                )
            }

            // Check parameter type matches
            if (!typeAnnotationsMatch(actualParamType, expectedParamType)) {
                throw TypeCheckError(
                    "Parameter type mismatch in ability '${impl.abilityName}' operation '${implOp.name}': " +
                    "expected ${typeAnnotationToString(expectedParamType)} (after substitution), " +
                    "got ${typeAnnotationToString(actualParamType)}"
                )
            }

            // Check return type matches
            if (!typeAnnotationsMatch(actualReturnType, expectedReturnType)) {
                throw TypeCheckError(
                    "Return type mismatch in ability '${impl.abilityName}' operation '${implOp.name}': " +
                    "expected ${typeAnnotationToString(expectedReturnType)} (after substitution), " +
                    "got ${typeAnnotationToString(actualReturnType)}"
                )
            }
        }
    }

    /**
     * Substitute type parameters in a type annotation.
     * e.g., {val: 'a} with {'a -> Int} becomes {val: Int}
     */
    private fun substituteTypeParameters(
        annotation: TypeAnnotation,
        substitution: Map<String, TypeAnnotation>
    ): TypeAnnotation {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check if this is a type parameter that needs substitution
                val paramName = annotation.name.removePrefix("'")
                substitution[paramName] ?: substitution["'$paramName"] ?: annotation
            }
            is FunctionTypeAnnotation -> {
                FunctionTypeAnnotation(
                    paramType = substituteTypeParameters(annotation.paramType, substitution),
                    returnType = substituteTypeParameters(annotation.returnType, substitution),
                    effects = annotation.effects,
                    abilityConstraints = annotation.abilityConstraints
                )
            }
            is RecordTypeAnnotation -> {
                RecordTypeAnnotation(
                    entries = annotation.entries.map { entry ->
                        RecordTypeEntry(
                            name = entry.name,
                            type = substituteTypeParameters(entry.type, substitution)
                        )
                    }
                )
            }
            is VariantTypeAnnotation -> {
                VariantTypeAnnotation(
                    constructors = annotation.constructors.map { ctor ->
                        VariantTypeConstructor(
                            name = ctor.name,
                            dataType = ctor.dataType?.let { substituteTypeParameters(it, substitution) }
                        )
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                TypeApplicationAnnotation(
                    constructor = annotation.constructor,
                    args = annotation.args.map { substituteTypeParameters(it, substitution) }
                )
            }
            is ArrayTypeAnnotation -> {
                ArrayTypeAnnotation(
                    elementType = substituteTypeParameters(annotation.elementType, substitution)
                )
            }
            is EffectRowAnnotation -> annotation // No substitution needed
        }
    }

    /**
     * Check if a type annotation contains type variables
     */
    private fun hasTypeVariables(annotation: TypeAnnotation): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                annotation.name.startsWith("'") || annotation.name.first().isLowerCase()
            }
            is FunctionTypeAnnotation -> {
                hasTypeVariables(annotation.paramType) || hasTypeVariables(annotation.returnType)
            }
            is RecordTypeAnnotation -> {
                annotation.entries.any { hasTypeVariables(it.type) }
            }
            is VariantTypeAnnotation -> {
                annotation.constructors.any { it.dataType?.let { hasTypeVariables(it) } == true }
            }
            is TypeApplicationAnnotation -> {
                annotation.args.any { hasTypeVariables(it) }
            }
            is ArrayTypeAnnotation -> hasTypeVariables(annotation.elementType)
            is EffectRowAnnotation -> false
        }
    }

    /**
     * Check if two type annotations match structurally
     */
    private fun typeAnnotationsMatch(a: TypeAnnotation, b: TypeAnnotation): Boolean {
        return when {
            a is IdentifierTypeAnnotation && b is IdentifierTypeAnnotation -> {
                a.name == b.name
            }
            a is FunctionTypeAnnotation && b is FunctionTypeAnnotation -> {
                typeAnnotationsMatch(a.paramType, b.paramType) &&
                typeAnnotationsMatch(a.returnType, b.returnType)
            }
            a is RecordTypeAnnotation && b is RecordTypeAnnotation -> {
                a.entries.size == b.entries.size &&
                a.entries.zip(b.entries).all { (aEntry, bEntry) ->
                    aEntry.name == bEntry.name && typeAnnotationsMatch(aEntry.type, bEntry.type)
                }
            }
            a is TypeApplicationAnnotation && b is TypeApplicationAnnotation -> {
                a.constructor == b.constructor &&
                a.args.size == b.args.size &&
                a.args.zip(b.args).all { (aArg, bArg) -> typeAnnotationsMatch(aArg, bArg) }
            }
            a is ArrayTypeAnnotation && b is ArrayTypeAnnotation -> {
                typeAnnotationsMatch(a.elementType, b.elementType)
            }
            else -> false
        }
    }

    /**
     * Convert type annotation to string for error messages
     */
    private fun typeAnnotationToString(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name
            is FunctionTypeAnnotation -> {
                "${typeAnnotationToString(annotation.paramType)} -> ${typeAnnotationToString(annotation.returnType)}"
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.joinToString(", ") { "${it.name}: ${typeAnnotationToString(it.type)}" }
                "{$fields}"
            }
            is VariantTypeAnnotation -> {
                val ctors = annotation.constructors.joinToString(", ") {
                    val dataType = it.dataType
                    if (dataType != null) "${it.name} ${typeAnnotationToString(dataType)}"
                    else it.name
                }
                "| $ctors |"
            }
            is TypeApplicationAnnotation -> {
                if (annotation.args.isEmpty()) annotation.constructor
                else "${annotation.constructor} ${annotation.args.joinToString(" ") { typeAnnotationToString(it) }}"
            }
            is ArrayTypeAnnotation -> "[${typeAnnotationToString(annotation.elementType)}]"
            is EffectRowAnnotation -> "<${annotation.effects.joinToString(", ")}>"
        }
    }

    /**
     * Validate ability dependencies to detect circular dependencies.
     *
     * Checks:
     * 1. No self-dependencies (ability cannot depend on itself)
     * 2. No circular dependencies (A -> B -> A)
     *
     * @param abilityDeclaration The ability declaration to validate
     * @param abilityDeclarations Map of ability name to declaration for dependency lookup
     * @throws TypeCheckError if circular dependency detected
     */
    fun validateAbilityDependencies(
        abilityDeclaration: AbilityDeclaration,
        abilityDeclarations: Map<String, AbilityDeclaration>
    ) {
        // Get dependencies from ability declaration
        val dependencies = abilityDeclaration.dependencies

        // Check for self-dependency
        if (dependencies.any { it.abilityName == abilityDeclaration.name }) {
            throw TypeCheckError(
                "Ability '${abilityDeclaration.name}' cannot depend on itself. " +
                "Remove the self-dependency from the 'requires' clause."
            )
        }

        // Check for circular dependencies using DFS
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun detectCycle(abilityName: String): Boolean {
            if (recursionStack.contains(abilityName)) {
                // Found a cycle
                return true
            }

            if (visited.contains(abilityName)) {
                // Already explored this path, no cycle
                return false
            }

            visited.add(abilityName)
            recursionStack.add(abilityName)

            // Check dependencies
            val currentAbility = abilityDeclarations[abilityName]
            if (currentAbility != null) {
                for (dep in currentAbility.dependencies) {
                    if (detectCycle(dep.abilityName)) {
                        return true
                    }
                }
            }

            recursionStack.remove(abilityName)
            return false
        }

        // Start cycle detection from each dependency
        for (dep in dependencies) {
            recursionStack.clear()
            recursionStack.add(abilityDeclaration.name) // Start from current ability

            if (detectCycle(dep.abilityName)) {
                throw TypeCheckError(
                    "Circular dependency detected in ability '${abilityDeclaration.name}'. " +
                    "Ability '${dep.abilityName}' (directly or indirectly) depends on '${abilityDeclaration.name}'."
                )
            }
        }
    }

    /**
     * Validate name collisions between abilities, types, and effects.
     *
     * Checks:
     * 1. Ability names don't collide with type names
     * 2. Ability names don't collide with effect names
     * 3. Variant constructor names from abilities don't collide with other constructors
     *
     * @param abilityDeclarations Map of ability name to declaration
     * @param typeDeclarations Map of type name to declaration
     * @param effectDeclarations Map of effect name to declaration
     * @throws TypeCheckError if name collision detected
     */
    fun validateNameCollisions(
        abilityDeclarations: Map<String, AbilityDeclaration>,
        typeDeclarations: Map<String, TypeDeclaration>,
        effectDeclarations: Map<String, EffectDeclaration>
    ) {
        // Check for built-in ability/type name collisions
        for (typeName in typeDeclarations.keys) {
            if (BuiltInAbilities.isBuiltInAbility(typeName)) {
                throw TypeCheckError(
                    "Cannot define type '$typeName': name conflicts with built-in ability '$typeName'. " +
                    "Types and built-in abilities must have unique names."
                )
            }
        }

        // Check for user-defined ability/type name collisions
        for (abilityName in abilityDeclarations.keys) {
            if (typeDeclarations.containsKey(abilityName)) {
                throw TypeCheckError(
                    "Cannot define type '$abilityName': name conflicts with ability '$abilityName'. " +
                    "Abilities and types must have unique names."
                )
            }
        }

        // Check for ability/effect name collisions
        for (abilityName in abilityDeclarations.keys) {
            if (effectDeclarations.containsKey(abilityName)) {
                throw TypeCheckError(
                    "Cannot define effect '$abilityName': name conflicts with ability '$abilityName'. " +
                    "Abilities and effects must have unique names."
                )
            }
        }

        // Check for type/effect name collisions
        for (typeName in typeDeclarations.keys) {
            if (effectDeclarations.containsKey(typeName)) {
                throw TypeCheckError(
                    "Cannot define type '$typeName': name conflicts with effect '$typeName'. " +
                    "Types and effects must have unique names."
                )
            }
        }

        // Check for variant constructor name collisions with abilities and effects (Bug #8c)
        for ((typeName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is VariantDefinition) {
                for (constructor in definition.constructors) {
                    val constructorName = constructor.name

                    // Check collision with built-in abilities
                    if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with built-in ability '$constructorName'. " +
                            "Variant constructors and abilities must have unique names."
                        )
                    }

                    // Check collision with user-defined abilities
                    if (abilityDeclarations.containsKey(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with ability '$constructorName'. " +
                            "Variant constructors and abilities must have unique names."
                        )
                    }

                    // Check collision with effects
                    if (effectDeclarations.containsKey(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with effect '$constructorName'. " +
                            "Variant constructors and effects must have unique names."
                        )
                    }
                }
            }
        }
    }
}
