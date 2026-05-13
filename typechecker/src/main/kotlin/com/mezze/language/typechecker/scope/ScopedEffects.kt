package com.mezze.language.typechecker.scope

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedPerformerOp
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedTypeAnnotation

/**
 * Constants for effect-handler scope hooks and runtime-bound names.
 *
 * Single source of truth — every site that reads/writes these names (the
 * typechecker, the constraint generator, the runtime driver, anywhere
 * that walks impl bodies) should reference these constants rather than
 * inlining the strings.
 */
object ScopedEffectConstants {
    /** Hook that creates the scope value, invoked once before body runs. */
    const val INITIALIZE_SCOPE = "initialize_scope"

    /** Hook that cleans up the scope value, invoked once after body completes. */
    const val FINALIZE_SCOPE = "finalize_scope"

    /** Name under which the scope value is bound inside op handler bodies. */
    const val SCOPE = "scope"

    /**
     * Runtime-bound name available inside op handler bodies. Calling
     * `abort {val = X}` ends the perform with X as its result, discarding
     * the body. See EffectHandlerBindings for the type shape.
     */
    const val ABORT = "abort"

    /**
     * Runtime-bound name available inside op handler bodies. Calling
     * `resume {val = X}` drives the body forward with X as the op's return.
     * Falling off the op body normally is treated as `resume {val = <last expr>}`.
     */
    const val RESUME = "resume"

    /** Field name carried by `abort` and `resume` records. */
    const val VAL_FIELD = "val"
}

/**
 * Result of detecting scoped effect members in an impl declaration.
 *
 * This separates the special scope-related members (initialize_scope, finalize_scope)
 * from regular operations.
 */
data class ScopedEffectInfo(
    val initializeScope: ParsedPerformerOp?,
    val finalizeScope: ParsedPerformerOp?,
    val regularOperations: List<ParsedPerformerOp>,
    val scopeType: MezzeType? = null
) {
    val isScopedEffect: Boolean get() = initializeScope != null
    val hasFinalizer: Boolean get() = finalizeScope != null
}

/**
 * Detects scoped effect members in a list of performer operations.
 */
fun detectScopedEffectMembers(operations: List<ParsedPerformerOp>): ScopedEffectInfo {
    var initializeScope: ParsedPerformerOp? = null
    var finalizeScope: ParsedPerformerOp? = null
    val regularOperations = mutableListOf<ParsedPerformerOp>()

    for (operation in operations) {
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
    abstract val loc: Loc?

    data class InitializeScopeWrongParams(
        val actualParams: ParsedTypeAnnotation?,
        override val loc: Loc?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "initialize_scope has wrong signature: expected {} -> { ... }, " +
                    "but found non-empty parameters. " +
                    "initialize_scope must take no parameters."
    }

    data class InitializeScopeMustReturnRecord(
        val actualReturnType: ParsedTypeAnnotation?,
        override val loc: Loc?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "initialize_scope must return a record type like { field1 = value1, ... }. " +
                    "The returned record becomes the 'scope' variable available in operations."
    }

    data class FinalizeScopeWrongParams(
        val actualParams: ParsedTypeAnnotation?,
        override val loc: Loc?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "finalize_scope has wrong signature: expected {} -> {}, " +
                    "but found non-empty parameters. " +
                    "finalize_scope must take no parameters (use 'scope' variable instead)."
    }

    data class FinalizeScopeMustReturnUnit(
        val actualReturnType: ParsedTypeAnnotation?,
        override val loc: Loc?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "finalize_scope must return {} (unit). " +
                    "It's for cleanup side effects only, not for returning values."
    }

    data class FinalizeScopeWithoutInitialize(
        override val loc: Loc?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "finalize_scope requires initialize_scope. " +
                    "Add initialize_scope to create the scope that finalize_scope cleans up."
    }

    data class InitializeScopeWithoutFinalize(
        override val loc: Loc?
    ) : ScopedEffectValidationError() {
        override val message: String
            get() = "initialize_scope requires finalize_scope for resource safety. " +
                    "Add 'finalize_scope = {} -> {}' to clean up resources when the perform block exits."
    }
}

fun validateInitializeScopeSignature(
    operation: ParsedPerformerOp,
    hasFinalizeScope: Boolean,
    inferredReturnType: MezzeType? = null
): List<ScopedEffectValidationError> {
    val errors = mutableListOf<ScopedEffectValidationError>()
    val location = operation.loc

    if (!hasFinalizeScope) {
        errors.add(ScopedEffectValidationError.InitializeScopeWithoutFinalize(location))
    }

    val paramType = operation.paramType
    if (paramType != null && !isEmptyRecordAnnotation(paramType)) {
        errors.add(ScopedEffectValidationError.InitializeScopeWrongParams(paramType, location))
    }

    val returnType = operation.returnType
    if (returnType != null) {
        if (returnType !is ParsedRecordType) {
            errors.add(ScopedEffectValidationError.InitializeScopeMustReturnRecord(returnType, location))
        }
    } else if (inferredReturnType != null) {
        if (inferredReturnType !is MezzeType.RecordType) {
            errors.add(ScopedEffectValidationError.InitializeScopeMustReturnRecord(null, location))
        }
    }

    return errors
}

fun validateFinalizeScopeSignature(
    operation: ParsedPerformerOp,
    hasInitializeScope: Boolean
): List<ScopedEffectValidationError> {
    val errors = mutableListOf<ScopedEffectValidationError>()
    val location = operation.loc

    if (!hasInitializeScope) {
        errors.add(ScopedEffectValidationError.FinalizeScopeWithoutInitialize(location))
    }

    val paramType = operation.paramType
    if (paramType != null && !isEmptyRecordAnnotation(paramType)) {
        errors.add(ScopedEffectValidationError.FinalizeScopeWrongParams(paramType, location))
    }

    val returnType = operation.returnType
    if (returnType != null && !isEmptyRecordAnnotation(returnType)) {
        errors.add(ScopedEffectValidationError.FinalizeScopeMustReturnUnit(returnType, location))
    }

    return errors
}

fun validateScopedEffectSignatures(info: ScopedEffectInfo): List<ScopedEffectValidationError> {
    val errors = mutableListOf<ScopedEffectValidationError>()

    info.initializeScope?.let { initOp ->
        errors.addAll(validateInitializeScopeSignature(initOp, info.hasFinalizer))
    }

    info.finalizeScope?.let { finalOp ->
        errors.addAll(validateFinalizeScopeSignature(finalOp, info.isScopedEffect))
    }

    return errors
}

private fun isEmptyRecordAnnotation(annotation: ParsedTypeAnnotation): Boolean {
    return annotation is ParsedRecordType &&
            annotation.entries.isEmpty() &&
            !annotation.isOpen
}

fun extractScopeTypeFromAnnotation(
    initializeScope: ParsedPerformerOp,
    typeConverter: (ParsedTypeAnnotation) -> MezzeType
): MezzeType? {
    val returnType = initializeScope.returnType ?: return null

    if (returnType !is ParsedRecordType) {
        return null
    }

    return typeConverter(returnType)
}

fun ScopedEffectInfo.withScopeType(inferredScopeType: MezzeType?): ScopedEffectInfo {
    return this.copy(scopeType = inferredScopeType)
}
