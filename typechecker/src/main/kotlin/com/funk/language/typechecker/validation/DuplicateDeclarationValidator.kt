package com.funk.language.typechecker.validation

import com.funk.language.ast.*
import com.funk.language.typechecker.TypeCheckError
import com.funk.language.typechecker.abilities.BuiltInAbilities

/**
 * Validates that declarations are not duplicated within a program.
 *
 * Implements Bug #9 validation checks:
 * - Bug #9a: Duplicate variable declarations in same scope
 * - Bug #9b: Duplicate effect declarations
 * - Bug #9c: Duplicate effect handler/impl declarations
 * - Bug #9d: Duplicate ability declarations
 * - Bug #9e: Duplicate ability implementations for same type
 *
 * Also validates:
 * - Duplicate type declarations
 * - Duplicate constructor names across types
 *
 * Based on validation logic from OldTypeChecker.kt:839-1143
 */
object DuplicateDeclarationValidator {
    /**
     * Validate all duplicate declarations in a program.
     * This should be called BEFORE context building.
     *
     * @param program The program to validate
     * @throws TypeCheckError if any duplicate declarations are found
     */
    fun validateProgram(program: Program) {
        validateDuplicateTypes(program)
        validateDuplicateConstructors(program)
        validateNewtypeConstructors(program)
        validateDuplicateEffects(program)
        validateDuplicatePerformers(program)
        validateDuplicateAbilities(program)
        validateDuplicateAbilityImpls(program)
        validateDuplicateVariables(program)
    }

    /**
     * Validate no duplicate type declarations (OldTypeChecker.kt:974-976)
     */
    private fun validateDuplicateTypes(program: Program) {
        val declaredTypes = mutableSetOf<String>()

        for (item in program.items.filterIsInstance<TypeDeclaration>()) {
            if (declaredTypes.contains(item.name)) {
                throw TypeCheckError(
                    "Duplicate type declaration: type '${item.name}' is already declared"
                )
            }
            declaredTypes.add(item.name)
        }
    }

    /**
     * Built-in variant constructors that cannot be redefined in OTHER types.
     * These are constructors from built-in types like Option (Some/None) and Result (Ok/Err).
     *
     * Users CAN redefine the built-in type itself (e.g., `type Option = | Some, None |`)
     * but CANNOT use these constructors in a differently-named type
     * (e.g., `type MyOption = | Some, None |` is NOT allowed).
     */
    private val BUILTIN_CONSTRUCTORS = mapOf(
        // Option type constructors
        "Some" to "Option",
        "None" to "Option",
        // Result type constructors
        "Ok" to "Result",
        "Err" to "Result"
        // Note: True/False are literals, not variant constructors
    )

    /**
     * Validate no duplicate constructor names across types (OldTypeChecker.kt:1009-1067)
     * Also validates that user-defined constructors don't conflict with built-in constructors
     * (unless the user is redefining the built-in type itself).
     */
    private fun validateDuplicateConstructors(program: Program) {
        val constructorToTypeMap = mutableMapOf<String, String>()

        for (typeDecl in program.items.filterIsInstance<TypeDeclaration>()) {
            val definition = typeDecl.definition
            when (definition) {
                is VariantDefinition -> {
                    for (constructor in definition.constructors) {
                        val constructorName = constructor.name

                        // Check against built-in constructors, but allow redefining the same type
                        // e.g., `type Option = | Some, None |` is OK, but `type MyOption = | Some, None |` is NOT
                        val builtinType = BUILTIN_CONSTRUCTORS[constructorName]
                        if (builtinType != null && typeDecl.name != builtinType) {
                            throw TypeCheckError(
                                "Cannot redefine built-in constructor: '$constructorName' is already defined " +
                                "in built-in type '$builtinType'. Use the built-in '$builtinType' type or choose a different constructor name."
                            )
                        }

                        val existingType = constructorToTypeMap[constructorName]
                        if (existingType != null) {
                            throw TypeCheckError(
                                "Duplicate constructor name: constructor '$constructorName' is already defined " +
                                "in type '$existingType', cannot reuse in type '${typeDecl.name}'"
                            )
                        }
                        constructorToTypeMap[constructorName] = typeDecl.name
                    }
                }
                is NewtypeDefinition -> {
                    val constructorName = definition.constructor

                    // Check against built-in constructors
                    val builtinType = BUILTIN_CONSTRUCTORS[constructorName]
                    if (builtinType != null && typeDecl.name != builtinType) {
                        throw TypeCheckError(
                            "Cannot redefine built-in constructor: '$constructorName' is already defined " +
                            "in built-in type '$builtinType'. Use the built-in '$builtinType' type or choose a different constructor name."
                        )
                    }

                    val existingType = constructorToTypeMap[constructorName]
                    if (existingType != null) {
                        throw TypeCheckError(
                            "Duplicate constructor name: constructor '$constructorName' is already defined " +
                            "in type '$existingType', cannot reuse in type '${typeDecl.name}'"
                        )
                    }
                    constructorToTypeMap[constructorName] = typeDecl.name
                }
                is RecordDefinition -> {
                    // Records don't have constructors in the same way
                }
            }
        }
    }

    /**
     * Validate newtype constructor naming conventions (OldTypeChecker.kt:1517-1530)
     *
     * - Constructor must start with an uppercase letter
     * - Constructor cannot collide with built-in ability names
     */
    private fun validateNewtypeConstructors(program: Program) {
        for (typeDecl in program.items.filterIsInstance<TypeDeclaration>()) {
            val definition = typeDecl.definition
            if (definition is NewtypeDefinition) {
                val constructorName = definition.constructor

                // Validate constructor naming convention (must start with uppercase)
                if (!constructorName.first().isUpperCase()) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' must start with an uppercase letter"
                    )
                }

                // Validate constructor doesn't collide with built-in ability names
                if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' is already used by a built-in ability"
                    )
                }
            }
        }
    }

    /**
     * Validate no duplicate effect declarations (Bug #9b, OldTypeChecker.kt:880-904)
     */
    private fun validateDuplicateEffects(program: Program) {
        val declaredEffects = mutableSetOf<String>()

        for (effect in program.items.filterIsInstance<EffectDeclaration>()) {
            if (declaredEffects.contains(effect.name)) {
                throw TypeCheckError(
                    "Duplicate effect declaration: effect '${effect.name}' is already defined"
                )
            }
            declaredEffects.add(effect.name)
        }
    }

    /**
     * Validate no duplicate performer/handler declarations (Bug #9c, OldTypeChecker.kt:839-876)
     */
    private fun validateDuplicatePerformers(program: Program) {
        val declaredPerformers = mutableSetOf<String>()

        for (impl in program.items.filterIsInstance<ImplDeclaration>()) {
            if (declaredPerformers.contains(impl.performerName)) {
                throw TypeCheckError(
                    "Duplicate effect handler declaration: performer '${impl.performerName}' is already defined"
                )
            }
            declaredPerformers.add(impl.performerName)
        }
    }

    /**
     * Validate no duplicate ability declarations (Bug #9d, OldTypeChecker.kt:907-931)
     */
    private fun validateDuplicateAbilities(program: Program) {
        val declaredAbilities = mutableSetOf<String>()

        for (ability in program.items.filterIsInstance<AbilityDeclaration>()) {
            if (declaredAbilities.contains(ability.name)) {
                throw TypeCheckError(
                    "Duplicate ability declaration: ability '${ability.name}' is already defined"
                )
            }
            declaredAbilities.add(ability.name)
        }
    }

    /**
     * Validate no duplicate ability implementations (Bug #9e, OldTypeChecker.kt:933-968)
     *
     * Two ability implementations are considered duplicates if they implement
     * the same ability for the same type(s).
     */
    private fun validateDuplicateAbilityImpls(program: Program) {
        val abilityImplKeys = mutableSetOf<String>()

        for (impl in program.items.filterIsInstance<ImplAbilityDeclaration>()) {
            // Create a key from ability name + target type names
            val targetTypeNames = impl.targetTypes.map { typeAnnotationToString(it) }
            val key = "${impl.abilityName}_${targetTypeNames.joinToString("_")}"

            if (abilityImplKeys.contains(key)) {
                throw TypeCheckError(
                    "Duplicate ability implementation: '${impl.abilityName}' is already implemented " +
                    "for type(s) ${targetTypeNames.joinToString(", ")}"
                )
            }
            abilityImplKeys.add(key)
        }
    }

    /**
     * Validate no duplicate variable declarations (Bug #9a, OldTypeChecker.kt:1123-1143)
     */
    private fun validateDuplicateVariables(program: Program) {
        val declaredVariables = mutableSetOf<String>()

        for (item in program.items.filterIsInstance<LetBinding>()) {
            // Extract all variable names from the pattern using the AST utility
            val boundNames = item.pattern.extractBindings()

            for (name in boundNames) {
                if (declaredVariables.contains(name)) {
                    throw TypeCheckError(
                        "Duplicate variable declaration: '$name' is already defined in this scope"
                    )
                }
                declaredVariables.add(name)
            }
        }
    }

    /**
     * Convert type annotation to string for duplicate checking.
     * This doesn't need to be perfect, just unique enough to distinguish types.
     */
    private fun typeAnnotationToString(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name.removePrefix("'")
            is TypeApplicationAnnotation -> {
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) annotation.constructor else "${annotation.constructor}_$args"
            }
            is FunctionTypeAnnotation -> "Function"
            is RecordTypeAnnotation -> "Record"
            is VariantTypeAnnotation -> "Variant"
            is ArrayTypeAnnotation -> "Array_${typeAnnotationToString(annotation.elementType)}"
            is EffectRowAnnotation -> "EffectRow"
            is QualifiedTypeAnnotation -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) "${modulePart}_${annotation.typeName}" else "${modulePart}_${annotation.typeName}_$args"
            }
        }
    }
}
