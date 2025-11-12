package com.funk.language.typechecker

import com.funk.language.ast.SourceLocation
import com.funk.language.ast.FunkType

/**
 * Exception thrown when ability constraints cannot be satisfied
 * Note: AbilityConstraintError may already exist elsewhere, so we define it conditionally
 */

/**
 * Exception thrown when qualified call constraint propagation fails
 */
class QualifiedCallConstraintError(
    message: String,
    val qualifiedName: String,
    val argumentType: FunkType,
    val expectedType: FunkType,
    val sourceLocation: SourceLocation? = null
) : Exception(message)

/**
 * Exception thrown when constraint propagation encounters conflicting type requirements
 */
class ConstraintConflictError(
    message: String,
    val conflictingTypes: List<FunkType>,
    val sourceLocation: SourceLocation? = null
) : Exception(message)

/**
 * Exception thrown when an unknown qualified module is referenced
 */
class UnknownModuleError(
    message: String,
    val moduleName: String,
    val sourceLocation: SourceLocation? = null
) : Exception(message)

/**
 * Exception thrown when an unknown function is called on a known module
 */
class UnknownFunctionError(
    message: String,
    val moduleName: String,
    val functionName: String,
    val availableFunctions: List<String> = emptyList(),
    val sourceLocation: SourceLocation? = null
) : Exception(message)