package com.mezze.language.typechecker.module

import com.mezze.language.ast.*

/**
 * Module qualifier - qualifies module references to prevent name conflicts.
 *
 * When a module defines functions, we qualify all internal references
 * with the module's name to prevent conflicts with other modules.
 *
 * ## Example:
 *
 * **Original module** (std::collections::seq):
 * ```mezze
 * let concat = {x, y} -> ...
 * let flatten = {list} -> fold {init = [], f = concat, list}
 * ```
 *
 * **Qualified module**:
 * ```mezze
 * let seq__concat = {x, y} -> ...
 * let seq__flatten = {list} -> fold {init = [], f = seq__concat, list}
 * ```
 *
 * Now when another module imports `seq`, the internal calls don't
 * conflict with the importing module's own `concat` function.
 *
 * ## Design:
 * - Pure functions (creates new AST)
 * - Immutable transformations
 * - Recursive qualification
 * - Only qualifies module-defined names
 *
 * ## Usage:
 * ```kotlin
 * val qualifier = ModuleQualifier()
 * val qualifiedProgram = qualifier.qualifyModule(
 *     program = module.program,
 *     modulePath = "std::collections::seq",
 *     definedNames = setOf("concat", "map", "filter")
 * )
 * ```
 */
class ModuleQualifier {
    /**
     * Qualify all references in a module.
     *
     * This transforms the program so that all references to module-defined
     * names are qualified with the module prefix.
     *
     * @param program The program to qualify
     * @param modulePath The module path (e.g., "std::collections::seq")
     * @param definedNames Set of names defined in this module
     * @return Qualified program
     */
    fun qualifyModule(
        program: Program,
        modulePath: String,
        definedNames: Set<String>
    ): Program {
        // Convert module path to qualified prefix using structured path
        // Example: "std::collections::seq" -> "std__collections__array__"
        val pathSegments = ModulePath.parse(modulePath).segments
        val prefix = pathSegments.joinToString("__") + "__"

        // Transform all program items
        val qualifiedItems = program.items.map { item ->
            qualifyProgramItem(item, definedNames, prefix)
        }

        return Program(qualifiedItems)
    }

    /**
     * Qualify a single program item.
     */
    private fun qualifyProgramItem(
        item: ProgramItem,
        moduleNames: Set<String>,
        prefix: String
    ): ProgramItem {
        return when (item) {
            is LetBinding -> item.copy(
                // Qualify the binding name itself
                pattern = qualifyPattern(item.pattern, moduleNames, prefix),
                // Qualify references in the expression
                expression = qualifyExpression(item.expression, moduleNames, prefix)
            )
            is TopLevelExpression -> item.copy(
                expression = qualifyExpression(item.expression, moduleNames, prefix)
            )
            is ImplDeclaration -> item.copy(
                operations = item.operations.map { op ->
                    op.copy(
                        implementation = qualifyExpression(op.implementation, moduleNames, prefix)
                    )
                }
            )
            is ImplAbilityDeclaration -> item.copy(
                operations = item.operations.map { op ->
                    op.copy(
                        implementation = qualifyExpression(op.implementation, moduleNames, prefix)
                    )
                }
            )
            // Other items don't need qualification
            else -> item
        }
    }

    /**
     * Qualify a pattern (for let bindings).
     */
    private fun qualifyPattern(
        pattern: Pattern,
        moduleNames: Set<String>,
        prefix: String
    ): Pattern {
        return when (pattern) {
            is VariablePattern -> {
                // Qualify the variable name if it's a module-defined name
                if (pattern.name in moduleNames) {
                    pattern.copy(name = prefix + pattern.name)
                } else {
                    pattern
                }
            }
            // Other patterns don't need qualification for now
            else -> pattern
        }
    }

    /**
     * Recursively qualify variable references in an expression.
     */
    private fun qualifyExpression(
        expr: Expression,
        moduleNames: Set<String>,
        prefix: String
    ): Expression {
        return when (expr) {
            // Identifier - qualify if it references a module-defined name
            is Identifier -> {
                if (expr.localName in moduleNames && expr.qualifier == null) {
                    // Convert prefix like "module__" to ModulePath
                    val qualifierSegments = prefix.trimEnd('_').split("__")
                    expr.copy(qualifier = ModulePath(qualifierSegments))
                } else {
                    expr // Leave external references unchanged
                }
            }

            // Function call - qualify function and arguments
            is FunctionCall -> {
                val qualifiedFunction = qualifyExpression(expr.function, moduleNames, prefix)
                val qualifiedArguments = qualifyExpression(expr.arguments, moduleNames, prefix)

                // For monomorphic calls with content hash, the hash is already globally unique
                // so we don't need to qualify the resolvedFunctionName.
                // The content hash is the authoritative identifier for cross-module lookup.
                //
                // We only need to qualify resolvedFunctionName when:
                // 1. There's no content hash (legacy/local calls)
                // 2. The function is a module-local name that needs prefixing
                val qualifiedResolvedName = if (expr.resolvedFunctionContentHash != null) {
                    // Content hash exists - it's the authoritative lookup key
                    // Keep resolvedFunctionName as-is (used only for debugging/slot naming)
                    expr.resolvedFunctionName
                } else {
                    // No content hash - need to qualify string name for local lookup
                    expr.resolvedFunctionName?.let { resolvedName ->
                        val baseName = when (val func = expr.function) {
                            is Identifier -> func.localName
                            else -> return@let resolvedName  // Can't determine base name
                        }
                        if (baseName in moduleNames) {
                            prefix + baseName
                        } else {
                            resolvedName
                        }
                    }
                }

                expr.copy(
                    function = qualifiedFunction,
                    arguments = qualifiedArguments,
                    resolvedFunctionName = qualifiedResolvedName
                )
            }

            // Lambda - qualify body, but exclude lambda parameters from qualification
            is LambdaExpression -> {
                // Extract lambda parameter names
                val paramNames = when (val params = expr.parameters) {
                    is TypedParameters -> params.recordType.entries.map { it.name }.toSet()
                    is UntypedParameters -> params.names.toSet()
                }

                // Module names excluding lambda parameters
                val filteredModuleNames = moduleNames - paramNames

                expr.copy(
                    body = qualifyExpression(expr.body, filteredModuleNames, prefix)
                )
            }

            // Do expression - qualify statements and result
            is DoExpression -> expr.copy(
                statements = expr.statements.map { stmt ->
                    qualifyStatement(stmt, moduleNames, prefix)
                },
                result = qualifyExpression(expr.result, moduleNames, prefix)
            )

            // If expression - qualify all branches
            is IfExpression -> expr.copy(
                condition = qualifyExpression(expr.condition, moduleNames, prefix),
                thenBranch = qualifyExpression(expr.thenBranch, moduleNames, prefix),
                elseBranch = qualifyExpression(expr.elseBranch, moduleNames, prefix)
            )

            // Match expression - qualify expression and cases
            is MatchExpression -> expr.copy(
                expression = qualifyExpression(expr.expression, moduleNames, prefix),
                cases = expr.cases.map { case ->
                    case.copy(
                        guard = case.guard?.let { qualifyExpression(it, moduleNames, prefix) },
                        expression = qualifyExpression(case.expression, moduleNames, prefix)
                    )
                }
            )

            // Record literal - qualify field values
            is RecordLiteral -> expr.copy(
                entries = expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.copy(
                            value = entry.value?.let { qualifyExpression(it, moduleNames, prefix) }
                        )
                        is RecordSpreadEntry -> entry.copy(
                            expression = qualifyExpression(entry.expression, moduleNames, prefix)
                        )
                    }
                }
            )

            // Member access - qualify receiver
            is MemberAccess -> expr.copy(
                receiver = qualifyExpression(expr.receiver, moduleNames, prefix)
            )

            // Variant construction - qualify value
            is VariantConstruction -> expr.copy(
                value = expr.value?.let { qualifyExpression(it, moduleNames, prefix) }
            )

            // Seq operations - qualify elements/indices
            is SeqLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is SeqRegularElement -> SeqRegularElement(qualifyExpression(element.expression, moduleNames, prefix))
                        is SeqSpreadElement -> SeqSpreadElement(qualifyExpression(element.expression, moduleNames, prefix))
                    }
                }
            )

            // String literal - qualify interpolated expressions
            is StringLiteral -> {
                expr.parts?.let { parts ->
                    expr.copy(
                        parts = parts.map { part ->
                            when (part) {
                                is ExpressionPart -> ExpressionPart(
                                    qualifyExpression(part.expression, moduleNames, prefix)
                                )
                                else -> part
                            }
                        }
                    )
                } ?: expr
            }

            // Effect operations - qualify expressions
            is PerformExpression -> expr.copy(
                expression = qualifyExpression(expr.expression, moduleNames, prefix)
            )

            is EffectCallExpression -> expr.copy(
                arguments = qualifyExpression(expr.arguments, moduleNames, prefix)
            )

            // State machine - qualify all states
            is StateMachineExpression -> expr.copy(
                states = expr.states.map { state ->
                    when (state) {
                        is ComputeState -> state.copy(
                            computation = qualifyExpression(state.computation, moduleNames, prefix)
                        )
                        is YieldState -> state.copy(
                            valueExpression = qualifyExpression(state.valueExpression, moduleNames, prefix)
                        )
                        is CompleteState -> state.copy(
                            resultExpression = qualifyExpression(state.resultExpression, moduleNames, prefix)
                        )
                    }
                }
            )

            // Literals don't need qualification
            is IntLiteral, is NatLiteral, is FloatLiteral,
            is FractionLiteral, is PlaceholderExpression -> expr

            // Parenthesized - recurse into inner
            is ParenthesizedExpression -> expr.copy(
                expression = qualifyExpression(expr.expression, moduleNames, prefix)
            )
        }
    }

    /**
     * Qualify variable references in a statement.
     */
    private fun qualifyStatement(
        stmt: Statement,
        moduleNames: Set<String>,
        prefix: String
    ): Statement {
        return when (stmt) {
            is LetStatement -> stmt.copy(
                expression = qualifyExpression(stmt.expression, moduleNames, prefix)
            )
            is ExpressionStatement -> stmt.copy(
                expression = qualifyExpression(stmt.expression, moduleNames, prefix)
            )
        }
    }

    companion object {
        /**
         * Create a module qualifier.
         */
        fun create(): ModuleQualifier {
            return ModuleQualifier()
        }
    }
}
