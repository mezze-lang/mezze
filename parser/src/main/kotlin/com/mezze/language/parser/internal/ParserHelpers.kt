package com.mezze.language.parser.internal

import com.mezze.language.ast.*
import com.mezze.language.parser.OperatorMapping
import com.mezze.language.parser.ParseException
import com.mezze.language.parser.internal.lib.*
/**
 * Parser helper functions and utilities.
 *
 * This file contains internal helper functions for:
 * - Desugaring (operators, expressions, program items)
 * - Source location tracking and fixup
 * - Exception creation with context
 *
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in MezzeParser.kt.
 */

// ============================================================================
// RESERVED WORDS
// ============================================================================

/**
 * Set of reserved words that cannot be used as variable/parameter names.
 * These are reserved for Mezze language features.
 */
internal val RESERVED_WORDS = setOf(
    "scope"  // Reserved for scoped effects
)

/**
 * Check if a name is reserved.
 * Returns an error message if reserved, null otherwise.
 */
internal fun checkReservedName(name: String): String? {
    // Check __native prefix
    if (name.startsWith("__native")) {
        return "Names starting with '__native' are reserved for Mezze internals"
    }
    // Check reserved words
    if (name in RESERVED_WORDS) {
        return "'$name' is a reserved word"
    }
    return null
}

// ============================================================================
// SOURCE LOCATION HELPERS
// ============================================================================

/**
 * Create a SourceLocation from a token
 */
internal fun tokenLocation(token: Token, file: String?): SourceLocation {
    return SourceLocation(token.line, token.column, file)
}

/**
 * Create a SourceLocation from a token stream's current position
 */
internal fun streamLocation(stream: TokenStream, file: String?): SourceLocation {
    return SourceLocation(stream.getSourceLine(), stream.getSourceColumn(), file)
}

/**
 * Create a ParseException with full context
 */
internal fun createParseException(
    message: String,
    stream: TokenStream,
    source: String?,
    file: String?
): ParseException {
    return ParseException(
        message,
        stream.getSourcePosition(),
        stream.getSourceLine(),
        stream.getSourceColumn(),
        source,
        file
    )
}

/**
 * Fix source locations for expressions parsed from string interpolations.
 * This recursively updates sourceLocation fields to reflect the actual position in the original file.
 */
internal fun fixInterpolationSourceLocation(
    expr: Expression,
    lineOffset: Int,
    columnOffset: Int,
    file: String?
): Expression {
    return when (expr) {
        is Identifier -> {
            val newLoc = expr.sourceLocation?.let { loc ->
                if (loc.line == 1) {
                    SourceLocation(lineOffset, columnOffset + loc.column - 1, file)
                } else {
                    SourceLocation(lineOffset + loc.line - 1, loc.column, file)
                }
            }
            expr.copy(sourceLocation = newLoc)
        }
        is FunctionCall -> {
            val newFunc = fixInterpolationSourceLocation(expr.function, lineOffset, columnOffset, file)
            val newArgs = fixInterpolationSourceLocation(expr.arguments, lineOffset, columnOffset, file)
            val newLoc = expr.sourceLocation?.let { loc ->
                if (loc.line == 1) {
                    SourceLocation(lineOffset, columnOffset + loc.column - 1, file)
                } else {
                    SourceLocation(lineOffset + loc.line - 1, loc.column, file)
                }
            }
            expr.copy(function = newFunc, arguments = newArgs, sourceLocation = newLoc)
        }
        is RecordLiteral -> {
            val newEntries = expr.entries.map { entry ->
                when (entry) {
                    is RecordFieldEntry -> {
                        entry.value?.let { v ->
                            RecordFieldEntry(entry.name, fixInterpolationSourceLocation(v, lineOffset, columnOffset, file))
                        } ?: entry
                    }
                    is RecordSpreadEntry -> {
                        RecordSpreadEntry(fixInterpolationSourceLocation(entry.expression, lineOffset, columnOffset, file))
                    }
                }
            }
            val newLoc = expr.sourceLocation?.let { loc ->
                if (loc.line == 1) {
                    SourceLocation(lineOffset, columnOffset + loc.column - 1, file)
                } else {
                    SourceLocation(lineOffset + loc.line - 1, loc.column, file)
                }
            }
            expr.copy(entries = newEntries, sourceLocation = newLoc)
        }
        is MemberAccess -> {
            val newReceiver = fixInterpolationSourceLocation(expr.receiver, lineOffset, columnOffset, file)
            val newLoc = expr.sourceLocation?.let { loc ->
                if (loc.line == 1) {
                    SourceLocation(lineOffset, columnOffset + loc.column - 1, file)
                } else {
                    SourceLocation(lineOffset + loc.line - 1, loc.column, file)
                }
            }
            expr.copy(receiver = newReceiver, sourceLocation = newLoc)
        }
        // For other expression types, just return as-is
        else -> expr
    }
}

// ============================================================================
// DESUGARING - OPERATORS
// ============================================================================

/**
 * Desugar a binary operator into an ability method call.
 * For example: x + y becomes NumAdd.add {l = x, r = y}
 */
internal fun desugarBinaryOperator(
    operator: String,
    left: Expression,
    right: Expression,
    location: SourceLocation?
): Expression {
    val op = OperatorMapping.getBinaryOp(operator)
        ?: throw IllegalArgumentException("Unknown binary operator: $operator")
    val (abilityName, operationName) = op.ability to op.method

    val desugared = FunctionCall(
        function = MemberAccess(
            receiver = Identifier(localName = abilityName, sourceLocation = location),
            member = operationName,
            sourceLocation = location
        ),
        arguments = RecordLiteral(
            entries = listOf(
                RecordFieldEntry("l", left),
                RecordFieldEntry("r", right)
            ),
            sourceLocation = location
        ),
        sourceLocation = location
    )

    // Mark this expression as coming from operator syntax for better error messages
    if (location != null) {
        SyntaxMetadata.markAsOperator(location, operator)
    }

    return desugared
}

/**
 * Desugar a unary operator into an ability method call.
 * For example: -x becomes NumSub.neg {n = x}, !x becomes LogicalNot.not {val = x}
 */
internal fun desugarUnaryOperator(
    operator: String,
    operand: Expression,
    location: SourceLocation?
): Expression {
    val op = OperatorMapping.getUnaryOp(operator)
        ?: throw IllegalArgumentException("Unknown unary operator: $operator")
    val (abilityName, operationName, fieldName) = Triple(op.ability, op.method, op.fieldName)

    val desugared = FunctionCall(
        function = MemberAccess(
            receiver = Identifier(localName = abilityName, sourceLocation = location),
            member = operationName,
            sourceLocation = location
        ),
        arguments = RecordLiteral(
            entries = listOf(
                RecordFieldEntry(fieldName, operand)
            ),
            sourceLocation = location
        ),
        sourceLocation = location
    )

    // Mark this expression as coming from operator syntax for better error messages
    if (location != null) {
        SyntaxMetadata.markAsOperator(location, operator)
    }

    return desugared
}

// ============================================================================
// DESUGARING - PROGRAM, ITEMS, EXPRESSIONS
// ============================================================================

/**
 * Desugar an entire program by desugaring all program items.
 * Preserves comments from the original program.
 */
internal fun desugarProgram(program: Program): Program {
    return Program(
        items = program.items.map { desugarProgramItem(it) },
        comments = program.comments
    )
}

/**
 * Desugar a program item (recursive desugaring of contained expressions).
 * EXHAUSTIVE on ProgramItem sealed interface - all 9 cases handled explicitly.
 */
internal fun desugarProgramItem(item: ProgramItem): ProgramItem {
    return when (item) {
        is LetBinding -> item.copy(expression = desugarExpression(item.expression))
        is TopLevelExpression -> item.copy(expression = desugarExpression(item.expression))
        is ImplDeclaration -> item.copy(operations = item.operations.map { op ->
            op.copy(implementation = desugarExpression(op.implementation))
        })
        // No desugaring needed for these declaration types
        is TypeDeclaration -> item
        is EffectDeclaration -> item
        is AbilityDeclaration -> item
        is ImplAbilityDeclaration -> item
        is UseDeclaration -> item
        is PubUseDeclaration -> item
    }
}

/**
 * Desugar an expression (recursive desugaring of nested expressions).
 * EXHAUSTIVE on Expression sealed interface - all 23 cases handled explicitly.
 */
internal fun desugarExpression(expr: Expression): Expression {
    return when (expr) {
        // Expressions that need recursive desugaring
        is FunctionCall -> expr.copy(
            function = desugarExpression(expr.function),
            arguments = desugarExpression(expr.arguments)
        )
        is RecordLiteral -> expr.copy(
            entries = expr.entries.map { entry ->
                when (entry) {
                    is RecordFieldEntry -> entry.copy(
                        value = entry.value?.let { desugarExpression(it) }
                    )
                    is RecordSpreadEntry -> entry.copy(
                        expression = desugarExpression(entry.expression)
                    )
                }
            }
        )
        is IfExpression -> expr.copy(
            condition = desugarExpression(expr.condition),
            thenBranch = desugarExpression(expr.thenBranch),
            elseBranch = desugarExpression(expr.elseBranch)
        )
        is LambdaExpression -> expr.copy(body = desugarExpression(expr.body))
        is MatchExpression -> expr.copy(
            expression = desugarExpression(expr.expression),
            cases = expr.cases.map { case ->
                case.copy(expression = desugarExpression(case.expression))
            }
        )
        is DoExpression -> expr.copy(
            statements = expr.statements.map { desugarStatement(it) },
            result = desugarExpression(expr.result)
        )
        is ArrayLiteral -> expr.copy(
            elements = expr.elements.map { element ->
                when (element) {
                    is ArrayRegularElement -> ArrayRegularElement(desugarExpression(element.expression))
                    is ArraySpreadElement -> ArraySpreadElement(desugarExpression(element.expression))
                }
            }
        )
        is PerformExpression -> expr.copy(
            expression = desugarExpression(expr.expression)
        )
        is RecordUpdateExpression -> expr.copy(
            baseRecord = desugarExpression(expr.baseRecord),
            updates = expr.updates.map { update ->
                update.copy(value = desugarExpression(update.value))
            }
        )
        is ArrayAccess -> expr.copy(
            array = desugarExpression(expr.array),
            index = desugarExpression(expr.index)
        )
        is MemberAccess -> expr.copy(
            receiver = desugarExpression(expr.receiver)
        )
        is RecordUpdate -> expr.copy(
            record = desugarExpression(expr.record),
            updates = expr.updates.map { update ->
                update.copy(value = update.value?.let { desugarExpression(it) })
            }
        )
        is VariantConstruction -> expr.copy(
            value = expr.value?.let { desugarExpression(it) }
        )
        is EffectCallExpression -> expr.copy(
            arguments = desugarExpression(expr.arguments)
        )
        is StateMachineExpression -> expr.copy(
            states = expr.states.map { state ->
                when (state) {
                    is ComputeState -> state.copy(
                        computation = desugarExpression(state.computation)
                    )
                    is YieldState -> state.copy(
                        valueExpression = desugarExpression(state.valueExpression)
                    )
                    is CompleteState -> state.copy(
                        resultExpression = desugarExpression(state.resultExpression)
                    )
                }
            }
        )
        is StringLiteral -> {
            val parts = expr.parts
            if (expr.isInterpolated && parts != null) {
                expr.copy(
                    parts = parts.map { part ->
                        when (part) {
                            is StringPart -> part
                            is ExpressionPart -> ExpressionPart(desugarExpression(part.expression))
                        }
                    }
                )
            } else {
                expr
            }
        }
        // Parenthesized - recurse into inner
        is ParenthesizedExpression -> expr.copy(
            expression = desugarExpression(expr.expression)
        )
        // Literals and simple expressions (no desugaring needed)
        is IntLiteral -> expr
        is NatLiteral -> expr
        is FloatLiteral -> expr
        is BooleanLiteral -> expr
        is FractionLiteral -> expr
        is Identifier -> expr
        is PlaceholderExpression -> expr
    }
}

/**
 * Desugar a statement (recursive desugaring of contained expressions).
 * EXHAUSTIVE on Statement sealed interface - all 2 cases handled explicitly.
 */
internal fun desugarStatement(stmt: Statement): Statement {
    return when (stmt) {
        is ExpressionStatement -> ExpressionStatement(desugarExpression(stmt.expression))
        is LetStatement -> LetStatement(stmt.pattern, stmt.typeAnnotation, desugarExpression(stmt.expression))
    }
}
