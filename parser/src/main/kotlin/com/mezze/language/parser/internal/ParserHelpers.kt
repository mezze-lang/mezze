package com.mezze.language.parser.internal

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Loc
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

// ============================================================================
// LOC HELPERS (for parsedast)
// ============================================================================

/**
 * Compute end line/col for a single token.
 * For single-line tokens, endCol = startCol + text.length - 1.
 * For multi-line tokens (strings with newlines), scan the text.
 */
internal fun tokenEndLine(token: Token): Int {
    val newlines = token.text.count { it == '\n' }
    return token.line + newlines
}

internal fun tokenEndCol(token: Token): Int {
    val lastNewline = token.text.lastIndexOf('\n')
    return if (lastNewline == -1) {
        token.column + token.text.length - 1
    } else {
        token.text.length - lastNewline - 1
    }
}

/**
 * Create a Loc from a single token.
 */
internal fun tokenLoc(token: Token, file: String): Loc {
    return Loc(
        file = file,
        startLine = token.line,
        startCol = token.column,
        endLine = tokenEndLine(token),
        endCol = tokenEndCol(token)
    )
}

/**
 * Create a Loc spanning from startToken to endToken.
 */
internal fun spanLoc(startToken: Token, endToken: Token, file: String): Loc {
    return Loc(
        file = file,
        startLine = startToken.line,
        startCol = startToken.column,
        endLine = tokenEndLine(endToken),
        endCol = tokenEndCol(endToken)
    )
}

/**
 * Create a Loc spanning from a start token to the previous token in the stream.
 * Useful when you've already advanced past the end token.
 */
internal fun spanLocTo(startToken: Token, stream: TokenStream, file: String): Loc {
    val endToken = if (stream.position > 0) {
        stream.tokens.getOrNull(stream.position - 1) ?: startToken
    } else {
        startToken
    }
    return spanLoc(startToken, endToken, file)
}

/**
 * Create a Loc from the stream's current token (single token loc).
 */
internal fun streamLoc(stream: TokenStream, file: String): Loc {
    val token = stream.current() ?: stream.tokens.lastOrNull()
    return if (token != null) tokenLoc(token, file) else Loc(file, 1, 1, 1, 1)
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
    val operationName = op.method

    // Desugar to method call syntax: x + y → x.add { r = y }
    // The left operand is the receiver (bound via `it` in the ability system),
    // the right operand is the explicit argument.
    val desugared = FunctionCall(
        function = MemberAccess(
            receiver = left,
            member = operationName,
            sourceLocation = location
        ),
        arguments = RecordLiteral(
            entries = listOf(
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
 * Desugar a unary operator into a method call.
 * For example: -x becomes x.neg {}, !x becomes x.not {}
 */
internal fun desugarUnaryOperator(
    operator: String,
    operand: Expression,
    location: SourceLocation?
): Expression {
    val op = OperatorMapping.getUnaryOp(operator)
        ?: throw IllegalArgumentException("Unknown unary operator: $operator")
    val operationName = op.method

    // Desugar to method call syntax: -x → x.neg {}, !x → x.not {}
    // The operand is the receiver (bound via `it`), no additional arguments.
    val desugared = FunctionCall(
        function = MemberAccess(
            receiver = operand,
            member = operationName,
            sourceLocation = location
        ),
        arguments = RecordLiteral(
            entries = emptyList(),
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
/**
 * Post-processing: convert ImplDeclarations that reference abilities into ImplAbilityDeclarations.
 *
 * Since ability impls and effect impls share the `impl X for Y where { ... }` syntax,
 * the parser tries the effect impl parser first (which produces ImplDeclaration). This pass checks
 * which ones actually reference a declared ability (not an effect) and converts those to
 * ImplAbilityDeclaration.
 *
 * The heuristic: if the name is NOT a known effect in this program, treat it as an ability impl.
 */
internal fun resolveImplDeclarations(program: Program): Program {
    // Collect ability and effect names declared in this program
    val abilityNames = program.items.filterIsInstance<AbilityDeclaration>().map { it.name }.toSet()
    val effectNames = program.items.filterIsInstance<EffectDeclaration>().map { it.name }.toSet()

    if (abilityNames.isEmpty()) return program

    val resolvedItems = program.items.map { item ->
        if (item is ImplDeclaration && item.effect.localName in abilityNames && item.effect.localName !in effectNames) {
            // This is actually an ability impl — convert to ImplAbilityDeclaration
            val targetType = IdentifierTypeAnnotation(item.performerName)
            val targetTypes = listOf(targetType)

            ImplAbilityDeclaration(
                target = ImplTarget.Named(item.effect),
                targetTypes = targetTypes,
                forallVars = emptyList(),
                associatedTypeBindings = item.associatedTypeBindings,
                operations = item.operations.map { op ->
                    // The performerOperationParser uses placeholder empty records {} when no
                    // type annotation is given, but ImplAbilityOperation uses null for inference.
                    val paramType = op.paramType?.takeUnless { it is RecordTypeAnnotation && it.entries.isEmpty() }
                    val returnType = op.returnType?.takeUnless { it is RecordTypeAnnotation && it.entries.isEmpty() }
                    ImplAbilityOperation(
                        name = op.name,
                        paramType = paramType,
                        returnType = returnType,
                        implementation = op.implementation
                    )
                },
                inferredType = null,
                sourceLocation = item.sourceLocation
            )
        } else {
            item
        }
    }

    return program.copy(items = resolvedItems)
}

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
        is ImplAbilityDeclaration -> item.copy(operations = item.operations.map { op ->
            op.copy(implementation = desugarExpression(op.implementation))
        })
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
        is SeqLiteral -> expr.copy(
            elements = expr.elements.map { element ->
                when (element) {
                    is SeqRegularElement -> SeqRegularElement(desugarExpression(element.expression))
                    is SeqSpreadElement -> SeqSpreadElement(desugarExpression(element.expression))
                }
            }
        )
        is PerformExpression -> expr.copy(
            expression = desugarExpression(expr.expression)
        )
        is MemberAccess -> expr.copy(
            receiver = desugarExpression(expr.receiver)
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
        // Pipe expression - recurse into both sides
        is PipeExpression -> expr.copy(
            left = desugarExpression(expr.left),
            right = desugarExpression(expr.right)
        )
        // Literals and simple expressions (no desugaring needed)
        is IntLiteral -> expr
        is NatLiteral -> expr
        is FloatLiteral -> expr
        is FractionLiteral -> expr
        is Identifier -> expr
        is PipePlaceholder -> expr
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

// ============================================================================
// PARSEDAST RESOLUTION
// ============================================================================

/**
 * Post-processing: convert ParsedPerformerDecl that reference abilities into ParsedImplAbilityDecl.
 *
 * Since ability impls and effect impls share the `impl X for Y where { ... }` syntax,
 * the parser tries the effect impl parser first (which produces ParsedPerformerDecl). This pass
 * checks which ones actually reference a declared ability (not an effect) and converts those to
 * ParsedImplAbilityDecl.
 *
 * The heuristic: if the name is NOT a known effect in this module, treat it as an ability impl.
 */
internal fun resolveParsedImplDeclarations(module: ParsedModule): ParsedModule {
    // Collect ability and effect names declared in this module
    val abilityNames = module.decls.filterIsInstance<ParsedAbilityDecl>().map { it.name }.toSet()
    val effectNames = module.decls.filterIsInstance<ParsedEffectDecl>().map { it.name }.toSet()

    if (abilityNames.isEmpty()) return module

    val resolvedDecls = module.decls.map { decl ->
        if (decl is ParsedPerformerDecl &&
            decl.effect.localName in abilityNames &&
            decl.effect.localName !in effectNames) {
            // This is actually an ability impl — convert to ParsedImplAbilityDecl
            val targetType = ParsedNamedType(decl.performerName, decl.effect.loc)
            val targetTypes = listOf(targetType)

            ParsedImplAbilityDecl(
                target = ParsedImplTarget.Named(decl.effect),
                targetTypes = targetTypes,
                abilityTypeArgs = emptyList(),
                forallVars = emptyList(),
                associatedTypeBindings = decl.associatedTypeBindings,
                operations = decl.operations.map { op ->
                    // ParsedPerformerOp with null types → ParsedImplOp with null types (for inference)
                    val paramType = op.paramType
                    val returnType = op.returnType
                    ParsedImplOp(
                        name = op.name,
                        paramType = paramType,
                        returnType = returnType,
                        implementation = op.implementation,
                        abilityConstraints = emptyList(),
                        loc = op.loc
                    )
                },
                loc = decl.loc
            )
        } else {
            decl
        }
    }

    return module.copy(decls = resolvedDecls)
}
