package com.mezze.language.parser.internal

import com.mezze.language.ast.SourceLocation
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Loc
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
