package com.funk.language.typechecker.module

import com.funk.language.ast.*

/**
 * Module qualifier - qualifies module references to prevent name conflicts.
 *
 * When a module defines functions, we qualify all internal references
 * with the module's name to prevent conflicts with other modules.
 *
 * ## Example:
 *
 * **Original module** (std::collections::array):
 * ```funk
 * let concat = {x, y} -> ...
 * let flatten = {list} -> fold {init = [], f = concat, list}
 * ```
 *
 * **Qualified module**:
 * ```funk
 * let array__concat = {x, y} -> ...
 * let array__flatten = {list} -> fold {init = [], f = array__concat, list}
 * ```
 *
 * Now when another module imports `array`, the internal calls don't
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
 *     modulePath = "std::collections::array",
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
     * @param modulePath The module path (e.g., "std::collections::array")
     * @param definedNames Set of names defined in this module
     * @return Qualified program
     */
    fun qualifyModule(
        program: Program,
        modulePath: String,
        definedNames: Set<String>
    ): Program {
        // Convert module path to qualified prefix
        // Example: "std::collections::array" -> "std__collections__array__"
        val prefix = modulePath.replace("::", "__") + "__"

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
                if (expr.name in moduleNames) {
                    expr.copy(name = prefix + expr.name)
                } else {
                    expr // Leave external references unchanged
                }
            }

            // Function call - qualify function and arguments
            is FunctionCall -> {
                val qualifiedFunction = qualifyExpression(expr.function, moduleNames, prefix)
                val qualifiedArguments = qualifyExpression(expr.arguments, moduleNames, prefix)

                // Also update resolvedFunctionName if it references a module-defined function
                val qualifiedResolvedName = expr.resolvedFunctionName?.let { resolvedName ->
                    // Extract the base name (before monomorphization suffix)
                    val baseName = resolvedName.substringBefore('$')
                    if (baseName in moduleNames) {
                        // Qualify the base name, keep the suffix
                        val suffix = if ('$' in resolvedName) {
                            resolvedName.substringAfter('$', "")
                        } else ""
                        if (suffix.isNotEmpty()) {
                            "${prefix}${baseName}\$$suffix"
                        } else {
                            prefix + baseName
                        }
                    } else {
                        resolvedName
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

            // Record update - qualify record and updates
            is RecordUpdate -> expr.copy(
                record = qualifyExpression(expr.record, moduleNames, prefix),
                updates = expr.updates.map { update ->
                    update.copy(
                        value = update.value?.let { qualifyExpression(it, moduleNames, prefix) }
                    )
                }
            )

            is RecordUpdateExpression -> expr.copy(
                baseRecord = qualifyExpression(expr.baseRecord, moduleNames, prefix),
                updates = expr.updates.map { update ->
                    update.copy(
                        value = qualifyExpression(update.value, moduleNames, prefix)
                    )
                }
            )

            // Variant construction - qualify value
            is VariantConstruction -> expr.copy(
                value = expr.value?.let { qualifyExpression(it, moduleNames, prefix) }
            )

            // Array operations - qualify elements/indices
            is ArrayLiteral -> expr.copy(
                elements = expr.elements.map { qualifyExpression(it, moduleNames, prefix) }
            )

            is ArrayAccess -> expr.copy(
                array = qualifyExpression(expr.array, moduleNames, prefix),
                index = qualifyExpression(expr.index, moduleNames, prefix)
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
            is BooleanLiteral, is FractionLiteral, is PlaceholderExpression -> expr

            // Fallback for any other expression types
            else -> expr
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
            else -> stmt // Other statement types don't need qualification
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
