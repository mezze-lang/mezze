package com.mezze.language.typechecker.scope

import com.mezze.language.ast.*

/**
 * Constants for scoped effects detection.
 */
object ScopedEffectConstants {
    /** Name of the initialization function that creates the scope */
    const val INITIALIZE_SCOPE = "initialize_scope"

    /** Name of the finalization function that cleans up the scope */
    const val FINALIZE_SCOPE = "finalize_scope"
}

/**
 * Result of detecting scoped effect members in an impl declaration.
 *
 * This separates the special scope-related members (initialize_scope, finalize_scope)
 * from regular operations.
 */
data class ScopedEffectInfo(
    /**
     * The initialize_scope operation, if present.
     * When present, this impl uses scoped effects and 'scope' variable
     * will be available in operations and finalize_scope.
     */
    val initializeScope: PerformerOperation?,

    /**
     * The finalize_scope operation, if present.
     * Only valid when initializeScope is also present.
     * Runs in a finally block at the end of perform.
     */
    val finalizeScope: PerformerOperation?,

    /**
     * Regular operations (not initialize_scope or finalize_scope).
     * These operations will have access to the 'scope' variable if
     * initializeScope is present.
     */
    val regularOperations: List<PerformerOperation>,

    /**
     * The inferred type of the scope variable.
     * Derived from the return type of initialize_scope.
     * Null if initializeScope is not present or type couldn't be inferred.
     */
    val scopeType: MezzeType? = null
) {
    /**
     * Whether this impl uses scoped effects (has initialize_scope).
     */
    val isScopedEffect: Boolean
        get() = initializeScope != null

    /**
     * Whether this impl has a finalizer.
     */
    val hasFinalizer: Boolean
        get() = finalizeScope != null
}

/**
 * Detects scoped effect members in an impl declaration.
 *
 * This function separates the special scope-related members from regular operations:
 * - `initialize_scope`: Creates the scope at the start of perform
 * - `finalize_scope`: Cleans up the scope at the end of perform (in finally block)
 * - All other operations are regular operations
 */
fun detectScopedEffectMembers(impl: ImplDeclaration): ScopedEffectInfo {
    var initializeScope: PerformerOperation? = null
    var finalizeScope: PerformerOperation? = null
    val regularOperations = mutableListOf<PerformerOperation>()

    for (operation in impl.operations) {
        when (operation.name) {
            ScopedEffectConstants.INITIALIZE_SCOPE -> initializeScope = operation
            ScopedEffectConstants.FINALIZE_SCOPE -> finalizeScope = operation
            else -> regularOperations.add(operation)
        }
    }

    return ScopedEffectInfo(
        initializeScope = initializeScope,
        finalizeScope = finalizeScope,
        regularOperations = regularOperations
    )
}

/**
 * Error from validating scoped effect signatures.
 */
sealed class ScopedEffectValidationError {
    abstract val message: String
    abstract val sourceLocation: SourceLocation?

    /**
     * initialize_scope has wrong parameter type (must be empty record `{}`).
     */
    data class InitializeScopeWrongParams(
        val actualParams: TypeAnnotation?,
        override val sourceLocation: SourceLocation?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "initialize_scope has wrong signature: expected {} -> { ... }, " +
                    "but found non-empty parameters. " +
                    "initialize_scope must take no parameters."
    }

    /**
     * initialize_scope has wrong return type (must return a record).
     */
    data class InitializeScopeMustReturnRecord(
        val actualReturnType: TypeAnnotation?,
        override val sourceLocation: SourceLocation?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "initialize_scope must return a record type like { field1 = value1, ... }. " +
                    "The returned record becomes the 'scope' variable available in operations."
    }

    /**
     * finalize_scope has wrong parameter type (must be empty record `{}`).
     */
    data class FinalizeScopeWrongParams(
        val actualParams: TypeAnnotation?,
        override val sourceLocation: SourceLocation?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "finalize_scope has wrong signature: expected {} -> {}, " +
                    "but found non-empty parameters. " +
                    "finalize_scope must take no parameters (use 'scope' variable instead)."
    }

    /**
     * finalize_scope has wrong return type (must return empty record `{}`).
     */
    data class FinalizeScopeMustReturnUnit(
        val actualReturnType: TypeAnnotation?,
        override val sourceLocation: SourceLocation?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "finalize_scope must return {} (unit). " +
                    "It's for cleanup side effects only, not for returning values."
    }

    /**
     * finalize_scope without initialize_scope.
     */
    data class FinalizeScopeWithoutInitialize(
        override val sourceLocation: SourceLocation?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "finalize_scope requires initialize_scope. " +
                    "Add initialize_scope to create the scope that finalize_scope cleans up."
    }

    /**
     * initialize_scope without finalize_scope.
     * Resource acquisition requires cleanup for safety.
     */
    data class InitializeScopeWithoutFinalize(
        override val sourceLocation: SourceLocation?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "initialize_scope requires finalize_scope for resource safety. " +
                    "Add 'finalize_scope = {} -> {}' to clean up resources when the perform block exits."
    }
}

/**
 * Validates the signature of initialize_scope.
 *
 * Expected signature: {} -> { ... }
 * - Must take no parameters (empty record)
 * - Must return a record type
 * - Must have a corresponding finalize_scope
 *
 * @return List of validation errors (empty if valid)
 */
fun validateInitializeScopeSignature(
    operation: PerformerOperation,
    hasFinalizeScope: Boolean,
    inferredReturnType: MezzeType? = null
): List<ScopedEffectValidationError> {
    val errors = mutableListOf<ScopedEffectValidationError>()
    val location = operation.implementation.sourceLocation

    // initialize_scope requires finalize_scope for resource safety
    if (!hasFinalizeScope) {
        errors.add(ScopedEffectValidationError.InitializeScopeWithoutFinalize(location))
    }

    // Check parameter type is empty record {}
    val paramType = operation.paramType
    if (paramType != null && !isEmptyRecordAnnotation(paramType)) {
        errors.add(ScopedEffectValidationError.InitializeScopeWrongParams(paramType, location))
    }

    // Check return type is a record
    // If explicit annotation exists, check it
    // Otherwise, check inferred type
    val returnType = operation.returnType
    if (returnType != null) {
        if (!isRecordAnnotation(returnType)) {
            errors.add(ScopedEffectValidationError.InitializeScopeMustReturnRecord(returnType, location))
        }
    } else if (inferredReturnType != null) {
        if (inferredReturnType !is MezzeType.RecordType) {
            errors.add(ScopedEffectValidationError.InitializeScopeMustReturnRecord(null, location))
        }
    }

    return errors
}

/**
 * Validates the signature of finalize_scope.
 *
 * Expected signature: {} -> {}
 * - Must take no parameters (empty record)
 * - Must return empty record (unit)
 *
 * @return List of validation errors (empty if valid)
 */
fun validateFinalizeScopeSignature(
    operation: PerformerOperation,
    hasInitializeScope: Boolean
): List<ScopedEffectValidationError> {
    val errors = mutableListOf<ScopedEffectValidationError>()
    val location = operation.implementation.sourceLocation

    // finalize_scope requires initialize_scope
    if (!hasInitializeScope) {
        errors.add(ScopedEffectValidationError.FinalizeScopeWithoutInitialize(location))
    }

    // Check parameter type is empty record {}
    val paramType = operation.paramType
    if (paramType != null && !isEmptyRecordAnnotation(paramType)) {
        errors.add(ScopedEffectValidationError.FinalizeScopeWrongParams(paramType, location))
    }

    // Check return type is empty record {}
    val returnType = operation.returnType
    if (returnType != null && !isEmptyRecordAnnotation(returnType)) {
        errors.add(ScopedEffectValidationError.FinalizeScopeMustReturnUnit(returnType, location))
    }

    return errors
}

/**
 * Validates all scoped effect signatures in an impl declaration.
 *
 * @return List of validation errors (empty if valid)
 */
fun validateScopedEffectSignatures(info: ScopedEffectInfo): List<ScopedEffectValidationError> {
    val errors = mutableListOf<ScopedEffectValidationError>()

    // Validate initialize_scope if present
    info.initializeScope?.let { initOp ->
        errors.addAll(validateInitializeScopeSignature(initOp, info.hasFinalizer))
    }

    // Validate finalize_scope if present
    info.finalizeScope?.let { finalOp ->
        errors.addAll(validateFinalizeScopeSignature(finalOp, info.isScopedEffect))
    }

    return errors
}

/**
 * Checks if a type annotation is an empty record {}.
 */
private fun isEmptyRecordAnnotation(annotation: TypeAnnotation): Boolean {
    return annotation is RecordTypeAnnotation &&
            annotation.entries.isEmpty() &&
            annotation.restField == null
}

/**
 * Checks if a type annotation is a record type (possibly with fields).
 */
private fun isRecordAnnotation(annotation: TypeAnnotation): Boolean {
    return annotation is RecordTypeAnnotation
}

/**
 * Extracts the scope type from initialize_scope's return type annotation.
 *
 * @param initializeScope The initialize_scope operation
 * @param typeConverter A function to convert TypeAnnotation to MezzeType
 * @return The scope type as MezzeType, or null if can't be determined
 */
fun extractScopeTypeFromAnnotation(
    initializeScope: PerformerOperation,
    typeConverter: (TypeAnnotation) -> MezzeType
): MezzeType? {
    val returnType = initializeScope.returnType ?: return null

    // Only accept record type annotations
    if (returnType !is RecordTypeAnnotation) {
        return null
    }

    return typeConverter(returnType)
}

/**
 * Creates a ScopedEffectInfo with inferred scope type.
 *
 * This is called after type inference to populate the scope type
 * from the inferred return type of initialize_scope.
 *
 * @param info The base ScopedEffectInfo from detection
 * @param inferredScopeType The inferred type of the scope (from initialize_scope body)
 * @return Updated ScopedEffectInfo with scopeType populated
 */
fun ScopedEffectInfo.withScopeType(inferredScopeType: MezzeType?): ScopedEffectInfo {
    return this.copy(scopeType = inferredScopeType)
}
