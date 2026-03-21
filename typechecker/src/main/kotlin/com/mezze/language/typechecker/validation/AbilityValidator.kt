package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.ast.PrimitiveTypes

/**
 * Validates ability declarations and implementations.
 *
 * Based on validation logic from OldTypeChecker.kt:13063-13122
 * which implements all 8 validation rules for abilities.
 */
object AbilityValidator {

    /**
     * Validate ability declaration.
     *
     * Checks:
     * 1. No ability-level constraints (use method-level constraints instead)
     * 2. Operation effects are not polymorphic (<...>)
     * 3. Operation effects are not performed (<*>)
     *
     * Ability operations must declare concrete effects because they define a contract.
     * Polymorphic or performed effects would make the contract ambiguous.
     *
     * @param ability The ability declaration to validate
     * @throws TypeCheckError if validation fails
     */
    fun validateAbilityDeclaration(ability: AbilityDeclaration) {
        // Rule 1: No ability-level constraints - use method-level constraints instead
        // This ensures a simpler, more uniform constraint model where constraints are always on functions
        if (ability.constraints.isNotEmpty() || ability.dependencies.isNotEmpty()) {
            // Format constraints nicely for error message
            val constraintsList = (ability.constraints.map { "${it.abilityName} ${it.typeParam}" } +
                                   ability.dependencies.map { dep ->
                                       val args = dep.typeArgs.joinToString(" ") { typeAnnotationToString(it) }
                                       "${dep.abilityName} $args"
                                   })
                .joinToString(", ")

            // Generate suggestion for method-level constraints
            val suggestion = buildString {
                append("Move constraints to method signatures. For example:\n\n")
                append("  ability ${ability.name} ${ability.typeParams.joinToString(" ")} where {\n")
                for (op in ability.operations) {
                    append("      ${op.name}: ... with $constraintsList -> ...\n")
                }
                append("  }")
            }

            throw TypeCheckError(
                "Ability '${ability.name}' uses ability-level constraints ('with $constraintsList'), " +
                "but only method-level constraints are allowed.\n\n$suggestion",
                ability.sourceLocation
            )
        }

        for (operation in ability.operations) {
            val effects = operation.effects ?: continue  // No effects is fine (pure operation)

            // Rule 1: No polymorphic effects (<...> or <Effect, ...>)
            if (effects.isPolymorphic) {
                throw TypeCheckError(
                    "Ability operation '${operation.name}' in '${ability.name}' cannot have polymorphic effects (<...>). " +
                    "Ability operations must declare concrete effects to define a clear contract. " +
                    "Use specific effects like <Mut> or no effects for pure operations.",
                    ability.sourceLocation
                )
            }

            // Rule 2: No performed marker (<*> or <Effect, *>)
            if (effects.isPerformed) {
                throw TypeCheckError(
                    "Ability operation '${operation.name}' in '${ability.name}' cannot have performed marker (<*>). " +
                    "Ability operations declare what effects they perform, not that effects are handled. " +
                    "The <*> marker is only valid at call sites where effects are handled with 'perform'.",
                    ability.sourceLocation
                )
            }
        }
    }
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
        abilityDeclarations: Map<String, AbilityDeclaration>,
        typeDeclarations: Map<String, TypeDeclaration> = emptyMap()
    ) {
        // Rule 0: Check impl method names don't conflict with target type's record fields.
        // e.g., if Button has field `onClick`, you can't impl an ability with method `onClick` for Button.
        validateNoFieldMethodConflict(impl, typeDeclarations)

        // Inherent impls don't implement an ability — validate target type exists, then return
        if (impl.isInherent) {
            validateInherentImplTargetType(impl, typeDeclarations)
            return
        }

        val abilityName = impl.abilityName!!  // safe: not inherent
        val abilityQualifier = impl.abilityRef?.qualifier

        // Find the ability declaration
        // Support both simple name (EncodeFormat) and qualified name (codec::EncodeFormat)
        val qualifiedAbilityName = abilityQualifier?.let { "${it.fullPath}::$abilityName" } ?: abilityName
        val abilityDeclaration = abilityDeclarations[abilityName]
            ?: abilityDeclarations[qualifiedAbilityName]
            ?: throw TypeCheckError(
                "Ability '${qualifiedAbilityName}' not found - cannot validate implementation",
                impl.sourceLocation
            )

        // Rule 1: Check type parameter count matches
        // Concrete abilityTypeArgs (before 'for') + targetTypes (after 'for') must match ability's type params.
        // For `impl Into Str for Int`: abilityTypeArgs=[Str](concrete), targetTypes=[Int] → total=2
        // For `impl Into for Str Int`: abilityTypeArgs=[], targetTypes=[Str, Int] → total=2
        // For `impl Eq 'a for ['a]`: abilityTypeArgs=['a](variable, not counted), targetTypes=[['a]] → total=1
        val concreteAbilityTypeArgCount = impl.abilityTypeArgs
            .count { it !is IdentifierTypeAnnotation || !it.name.startsWith("'") }
        val totalTypeParams = concreteAbilityTypeArgCount + impl.targetTypes.size
        if (totalTypeParams != abilityDeclaration.typeParams.size) {
            throw TypeCheckError(
                "Type parameter count mismatch in ability implementation for '${abilityName}': " +
                "ability expects ${abilityDeclaration.typeParams.size} type parameters, " +
                "but implementation provides ${totalTypeParams}",
                impl.sourceLocation
            )
        }

        // Rule 2-5: Validate associated types
        val abilityAssociatedTypes = abilityDeclaration.associatedTypes
        val implAssociatedTypeBindings = impl.associatedTypeBindings

        // Rule 2: If ability declares associated types, impl must provide bindings
        if (abilityAssociatedTypes.isNotEmpty() && implAssociatedTypeBindings.isEmpty()) {
            val typesList = abilityAssociatedTypes.joinToString(", ")
            throw TypeCheckError(
                "Ability '${abilityName}' declares associated types [$typesList] " +
                "but implementation does not provide bindings. " +
                "Use: impl ${abilityName} ${impl.targetTypes.joinToString(" ")} " +
                "having ${abilityAssociatedTypes.joinToString(", ") { "$it = <Type>" }} where { ... }",
                impl.sourceLocation
            )
        }

        // Rule 3: If ability doesn't declare associated types, impl cannot provide bindings
        if (abilityAssociatedTypes.isEmpty() && implAssociatedTypeBindings.isNotEmpty()) {
            val bindingsList = implAssociatedTypeBindings.keys.joinToString(", ")
            throw TypeCheckError(
                "Implementation provides associated type bindings [$bindingsList] " +
                "but ability '${abilityName}' does not declare associated types. " +
                "Remove the 'having' clause or add it to the ability declaration.",
                impl.sourceLocation
            )
        }

        // Rule 4: Check that all declared associated types have bindings
        val missingBindings = abilityAssociatedTypes.filter { it !in implAssociatedTypeBindings.keys }
        if (missingBindings.isNotEmpty()) {
            throw TypeCheckError(
                "Ability '${abilityName}' declares associated types ${abilityAssociatedTypes.joinToString(", ")} " +
                "but implementation is missing bindings for: ${missingBindings.joinToString(", ")}. " +
                "All associated types must be bound in the implementation.",
                impl.sourceLocation
            )
        }

        // Rule 5: Check that impl doesn't provide extra bindings not declared in ability
        val extraBindings = implAssociatedTypeBindings.keys.filter { it !in abilityAssociatedTypes }
        if (extraBindings.isNotEmpty()) {
            throw TypeCheckError(
                "Implementation provides bindings for ${extraBindings.joinToString(", ")} " +
                "but ability '${abilityName}' only declares: ${abilityAssociatedTypes.joinToString(", ")}. " +
                "Remove the extra bindings or add them to the ability declaration.",
                impl.sourceLocation
            )
        }

        // Rule 6: Check all operations are implemented (OldTypeChecker.kt:13129-13138)
        val declaredOps = abilityDeclaration.operations.map { it.name }.toSet()
        val implementedOps = impl.operations.map { it.name }.toSet()
        val missingOps = declaredOps - implementedOps

        if (missingOps.isNotEmpty()) {
            val missing = missingOps.joinToString(", ")
            val example = missingOps.first()
            throw TypeCheckError(
                "Implementation of '${abilityName}' is missing required ${if (missingOps.size == 1) "method" else "methods"}: $missing. " +
                "Add: $example = {args} -> ...",
                impl.sourceLocation
            )
        }

        // Rule 7: Check no extra operations (OldTypeChecker.kt:13140-13146)
        val extraOps = implementedOps - declaredOps
        if (extraOps.isNotEmpty()) {
            val extra = extraOps.joinToString(", ")
            // Get source location from first extra operation
            val extraOpLocation = impl.operations.find { it.name in extraOps }?.implementation?.sourceLocation
            throw TypeCheckError(
                "Implementation of '${abilityName}' has extra ${if (extraOps.size == 1) "method" else "methods"} not in ability: $extra. " +
                "Remove ${if (extraOps.size == 1) "it" else "them"} or add to the ability declaration.",
                extraOpLocation
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
        // e.g., for impl Show for Int: 'a -> Int
        val substitution = abilityDeclaration.typeParams.zip(impl.allTypeArgs).toMap()

        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue  // Already caught by missing/extra operation checks

            // Substitute type parameters in declaration types
            val expectedParamType = substituteTypeParameters(declOp.paramType, substitution)
            val expectedReturnType = substituteTypeParameters(declOp.returnType, substitution)

            // Get actual types from implementation (if provided)
            val actualParamType = implOp.paramType ?: expectedParamType
            val actualReturnType = implOp.returnType ?: expectedReturnType

            // Get source location from implementation expression for better error messages
            val sourceLocation = implOp.implementation.sourceLocation

            // Check for type variables in concrete implementations
            // For generic impls (e.g., impl Eq 'a for ['a]), type variables from
            // the impl header are allowed in operation signatures.
            val allowedTypeVars = impl.abilityTypeArgs
                .filterIsInstance<IdentifierTypeAnnotation>()
                .filter { it.name.startsWith("'") }
                .map { it.name }
                .toSet()

            if (implOp.paramType != null && hasTypeVariables(actualParamType, allowedTypeVars)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' parameter type contains type variables. " +
                    "All type variables must be replaced with concrete types.",
                    sourceLocation
                )
            }
            if (implOp.returnType != null && hasTypeVariables(actualReturnType, allowedTypeVars)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' return type contains type variables. " +
                    "All type variables must be replaced with concrete types.",
                    sourceLocation
                )
            }

            // Check parameter type matches
            if (!typeAnnotationsMatch(actualParamType, expectedParamType)) {
                throw TypeCheckError(
                    "Wrong parameter type for '${implOp.name}' in impl ${impl.abilityName}: " +
                    "expected ${typeAnnotationToString(expectedParamType)}, " +
                    "but got ${typeAnnotationToString(actualParamType)}.",
                    sourceLocation
                )
            }

            // Check return type matches
            if (!typeAnnotationsMatch(actualReturnType, expectedReturnType)) {
                throw TypeCheckError(
                    "Wrong return type for '${implOp.name}' in impl ${impl.abilityName}: " +
                    "expected ${typeAnnotationToString(expectedReturnType)}, " +
                    "but got ${typeAnnotationToString(actualReturnType)}.",
                    sourceLocation
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
            is SeqTypeAnnotation -> {
                SeqTypeAnnotation(
                    elementType = substituteTypeParameters(annotation.elementType, substitution)
                )
            }
            is EffectRowAnnotation -> annotation // No substitution needed
            is QualifiedTypeAnnotation -> {
                QualifiedTypeAnnotation(
                    modulePath = annotation.modulePath,
                    typeName = annotation.typeName,
                    args = annotation.args.map { substituteTypeParameters(it, substitution) }
                )
            }
        }
    }

    /**
     * Check if a type annotation contains type variables
     */
    private fun hasTypeVariables(annotation: TypeAnnotation, allowedVars: Set<String> = emptySet()): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                val isTypeVar = annotation.name.startsWith("'") || annotation.name.first().isLowerCase()
                isTypeVar && annotation.name !in allowedVars
            }
            is FunctionTypeAnnotation -> {
                hasTypeVariables(annotation.paramType, allowedVars) || hasTypeVariables(annotation.returnType, allowedVars)
            }
            is RecordTypeAnnotation -> {
                annotation.entries.any { hasTypeVariables(it.type, allowedVars) }
            }
            is VariantTypeAnnotation -> {
                annotation.constructors.any { it.dataType?.let { hasTypeVariables(it, allowedVars) } == true }
            }
            is TypeApplicationAnnotation -> {
                annotation.args.any { hasTypeVariables(it, allowedVars) }
            }
            is SeqTypeAnnotation -> hasTypeVariables(annotation.elementType, allowedVars)
            is EffectRowAnnotation -> false
            is QualifiedTypeAnnotation -> annotation.args.any { hasTypeVariables(it, allowedVars) }
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
            a is SeqTypeAnnotation && b is SeqTypeAnnotation -> {
                typeAnnotationsMatch(a.elementType, b.elementType)
            }
            a is VariantTypeAnnotation && b is VariantTypeAnnotation -> {
                a.constructors.size == b.constructors.size &&
                a.constructors.zip(b.constructors).all { (aCtor, bCtor) ->
                    aCtor.name == bCtor.name &&
                    ((aCtor.dataType == null && bCtor.dataType == null) ||
                     (aCtor.dataType != null && bCtor.dataType != null &&
                      typeAnnotationsMatch(aCtor.dataType!!, bCtor.dataType!!)))
                }
            }
            a is QualifiedTypeAnnotation && b is QualifiedTypeAnnotation -> {
                a.modulePath == b.modulePath &&
                a.typeName == b.typeName &&
                a.args.size == b.args.size &&
                a.args.zip(b.args).all { (aArg, bArg) -> typeAnnotationsMatch(aArg, bArg) }
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
            is SeqTypeAnnotation -> "[${typeAnnotationToString(annotation.elementType)}]"
            is EffectRowAnnotation -> "<${annotation.effects.joinToString(", ")}>"
            is QualifiedTypeAnnotation -> {
                if (annotation.args.isEmpty()) annotation.qualifiedName
                else "${annotation.qualifiedName} ${annotation.args.joinToString(" ") { typeAnnotationToString(it) }}"
            }
        }
    }

    /**
     * Validate orphan rules for ability implementation.
     *
     * An impl is an "orphan" if:
     * - The ability is NOT defined in the current module, AND
     * - NONE of the target types are defined in the current module
     *
     * Following Rust/Roc coherence rules:
     * - impl Ability for Type must be in the same module as either the ability or one of the types
     * - This prevents third-party modules from defining impls for types they don't own
     *
     * @param impl The ability implementation to validate
     * @param localAbilityNames Set of ability names defined in the current module
     * @param localTypeNames Set of type names defined in the current module
     * @param importedAbilityNames Set of ability names imported from other modules
     * @param importedTypeNames Set of type names imported from other modules
     * @throws TypeCheckError if the impl is an orphan
     */
    fun validateOrphanRules(
        impl: ImplAbilityDeclaration,
        localAbilityNames: Set<String>,
        localTypeNames: Set<String>,
        importedAbilityNames: Set<String>,
        importedTypeNames: Set<String>
    ) {
        // Inherent impls: target type must be defined locally
        if (impl.isInherent) {
            validateInherentImplLocality(impl, localTypeNames)
            return
        }

        val abilityName = impl.abilityName!!  // safe: not inherent

        // Check if ability is defined locally
        val isAbilityLocal = localAbilityNames.contains(abilityName)

        // Check if ability is imported (from another module)
        val isAbilityImported = importedAbilityNames.contains(abilityName)

        // If ability is not found anywhere, skip orphan check (will fail ability lookup later)
        if (!isAbilityLocal && !isAbilityImported) {
            return  // Let validateAbilityImplementation handle "ability not found"
        }

        // If ability is local, this impl is valid (in same module as ability)
        if (isAbilityLocal) {
            return  // Valid: impl is in same module as ability declaration
        }

        // Ability is imported - check if any target type is local
        // Include both concrete abilityTypeArgs (before 'for') and targetTypes (after 'for')
        val concreteAbilityTypeArgs = impl.abilityTypeArgs
            .filter { it !is IdentifierTypeAnnotation || !it.name.startsWith("'") }
        val targetTypeNames = (concreteAbilityTypeArgs + impl.targetTypes).map { getTypeNameFromAnnotation(it) }

        // Check if any target type is defined locally
        val hasLocalTargetType = targetTypeNames.any { typeName ->
            localTypeNames.contains(typeName)
        }

        if (hasLocalTargetType) {
            return  // Valid: impl is in same module as at least one target type
        }

        // At this point, both ability and all types are imported - this is an orphan impl
        val typesList = targetTypeNames.joinToString(", ")
        throw TypeCheckError(
            "Orphan impl: cannot implement imported ability '$abilityName' for types [$typesList] " +
            "in this module. An impl must be in the same module as either the ability declaration " +
            "or at least one of the target types. Move this impl to the module where '$abilityName' " +
            "or one of [$typesList] is defined.",
            impl.sourceLocation
        )
    }

    /**
     * Extract type name from a type annotation for orphan rule checking.
     */
    private fun getTypeNameFromAnnotation(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name.removePrefix("'")
            is TypeApplicationAnnotation -> annotation.constructor
            is QualifiedTypeAnnotation -> annotation.typeName
            is RecordTypeAnnotation -> "Record"
            is VariantTypeAnnotation -> "Variant"
            is FunctionTypeAnnotation -> "Function"
            is SeqTypeAnnotation -> "Seq"
            is EffectRowAnnotation -> "EffectRow"
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

    /**
     * Validate that an inherent impl's target type exists.
     *
     * For primitive types (Int, Float, Str, Bool, etc.), the type always exists.
     * For user-defined types, the type must be declared.
     */
    private fun validateInherentImplTargetType(
        impl: ImplAbilityDeclaration,
        typeDeclarations: Map<String, TypeDeclaration>
    ) {
        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromAnnotation(targetType)

            // Primitive types always exist
            if (PrimitiveTypes.isPrimitiveName(typeName)) continue

            // Built-in compound types always exist
            if (typeName in BuiltinTypes.coreTypeNames) continue

            // User-defined type must be declared
            if (!typeDeclarations.containsKey(typeName)) {
                throw TypeCheckError(
                    "Cannot define inherent impl for unknown type '$typeName'. " +
                    "The type must be declared before implementing methods on it.",
                    impl.sourceLocation
                )
            }
        }
    }

    /**
     * Validate that an inherent impl is defined in the same module as the target type.
     *
     * Inherent impls can only be defined where the type is defined:
     * - The target type must be locally defined in this module
     * - Primitive/built-in types are NOT exempted — only stdlib can impl for them
     *   (stdlib modules will have those types in their localTypeNames)
     */
    private fun validateInherentImplLocality(
        impl: ImplAbilityDeclaration,
        localTypeNames: Set<String>
    ) {
        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromAnnotation(targetType)

            // Target type must be locally defined — no exceptions
            if (!localTypeNames.contains(typeName)) {
                throw TypeCheckError(
                    "Cannot define inherent impl for type '$typeName' in this module. " +
                    "Inherent impls can only be defined where the type is defined. " +
                    "Move this impl to the module where '$typeName' is declared.",
                    impl.sourceLocation
                )
            }
        }
    }

    /**
     * Validate that impl method names don't conflict with fields on the target type.
     *
     * For example, if `type Button = { onClick: {} -> {} }`, then:
     *   impl Button where { onClick = ... }           — ERROR
     *   impl Handler for Button where { onClick = ... } — ERROR
     *
     * The target type's record fields and the impl's method names must be disjoint.
     */
    private fun validateNoFieldMethodConflict(
        impl: ImplAbilityDeclaration,
        typeDeclarations: Map<String, TypeDeclaration>
    ) {
        if (typeDeclarations.isEmpty()) return

        // Get the impl's method names
        val implMethodNames = impl.operations.map { it.name }.toSet()
        if (implMethodNames.isEmpty()) return

        // Check each target type for field conflicts
        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromAnnotation(targetType)
            val typeDecl = typeDeclarations[typeName] ?: continue
            val definition = typeDecl.definition

            // Only record types have fields to conflict with
            if (definition is RecordDefinition) {
                val fieldNames = definition.fields.map { it.name }.toSet()
                val conflicts = implMethodNames.intersect(fieldNames)
                if (conflicts.isNotEmpty()) {
                    val conflictList = conflicts.joinToString(", ") { "'$it'" }
                    throw TypeCheckError(
                        "Cannot implement ${if (impl.isInherent) "methods" else "ability '${impl.abilityName}'"} " +
                            "for type '$typeName': method names $conflictList conflict with fields " +
                            "already defined on '$typeName'. Rename the fields or the ability methods to avoid ambiguity.",
                        impl.sourceLocation
                    )
                }
            }
        }
    }
}
