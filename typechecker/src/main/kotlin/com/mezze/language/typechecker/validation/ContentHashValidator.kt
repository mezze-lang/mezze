package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*

/**
 * Validates that content hashes are properly populated after typechecking.
 *
 * This validator ensures that the typechecker contract is upheld:
 * - All top-level simple binding declarations have contentHash
 * - All ability implementations have contentHash
 * - All effect implementations have contentHash
 * - All type declarations have contentHash
 * - All effect declarations have contentHash
 * - All ability declarations have contentHash
 *
 * Local bindings (lambda params, let locals) do NOT need content hashes
 * because they use frame slots for lookup, not global registration.
 *
 * This is a safety net to catch typechecker bugs before they reach the interpreter.
 */
object ContentHashValidator {

    /**
     * Validate content hashes in a program after typechecking.
     *
     * @param program The type-checked program
     * @return List of validation errors (empty if all checks pass)
     */
    fun validate(program: Program): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        for (item in program.items) {
            when (item) {
                is LetBinding -> validateLetBinding(item, errors)
                is ImplAbilityDeclaration -> validateAbilityImpl(item, errors)
                is ImplDeclaration -> validateEffectImpl(item, errors)
                is TypeDeclaration -> validateTypeDeclaration(item, errors)
                is EffectDeclaration -> validateEffectDeclaration(item, errors)
                is AbilityDeclaration -> validateAbilityDeclaration(item, errors)
                else -> { /* Other declarations (UseDeclaration, etc.) don't need content hash */ }
            }
        }

        return errors
    }

    private fun validateLetBinding(binding: LetBinding, errors: MutableList<ValidationError>) {
        // Only simple bindings (single variable pattern) get content hash
        if (binding.isSimpleBinding && binding.contentHash == null) {
            errors.add(
                ValidationError.MissingContentHash(
                    identifierName = binding.variableName,
                    kind = "let binding",
                    sourceLocation = null  // LetBinding doesn't track source location
                )
            )
        }
    }

    private fun validateAbilityImpl(impl: ImplAbilityDeclaration, errors: MutableList<ValidationError>) {
        if (impl.contentHash == null) {
            errors.add(
                ValidationError.MissingContentHash(
                    identifierName = "${impl.ability.localName} for ${impl.targetTypes.joinToString()}",
                    kind = "ability implementation",
                    sourceLocation = impl.sourceLocation
                )
            )
        }
    }

    private fun validateEffectImpl(impl: ImplDeclaration, errors: MutableList<ValidationError>) {
        if (impl.contentHash == null) {
            errors.add(
                ValidationError.MissingContentHash(
                    identifierName = impl.performerName,
                    kind = "effect handler",
                    sourceLocation = impl.sourceLocation
                )
            )
        }
    }

    private fun validateTypeDeclaration(decl: TypeDeclaration, errors: MutableList<ValidationError>) {
        if (decl.contentHash == null) {
            errors.add(
                ValidationError.MissingContentHash(
                    identifierName = decl.name,
                    kind = "type declaration",
                    sourceLocation = decl.sourceLocation
                )
            )
        }
    }

    private fun validateEffectDeclaration(decl: EffectDeclaration, errors: MutableList<ValidationError>) {
        if (decl.contentHash == null) {
            errors.add(
                ValidationError.MissingContentHash(
                    identifierName = decl.name,
                    kind = "effect declaration",
                    sourceLocation = decl.sourceLocation
                )
            )
        }
    }

    private fun validateAbilityDeclaration(decl: AbilityDeclaration, errors: MutableList<ValidationError>) {
        if (decl.contentHash == null) {
            errors.add(
                ValidationError.MissingContentHash(
                    identifierName = decl.name,
                    kind = "ability declaration",
                    sourceLocation = decl.sourceLocation
                )
            )
        }
    }
}
