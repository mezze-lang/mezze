package com.mezze.language.parser.internal

import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.parser.ParseException
import com.mezze.language.parser.internal.lib.*

internal val RESERVED_WORDS = setOf(
    "scope"
)

internal fun checkReservedName(name: String): String? {
    if (name.startsWith("__native")) {
        return "Names starting with '__native' are reserved for Mezze internals"
    }
    if (name in RESERVED_WORDS) {
        return "'$name' is a reserved word"
    }
    return null
}

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

internal fun tokenLoc(token: Token, file: String): Loc {
    return Loc(
        file = file,
        startLine = token.line,
        startCol = token.column,
        endLine = tokenEndLine(token),
        endCol = tokenEndCol(token)
    )
}

internal fun spanLoc(startToken: Token, endToken: Token, file: String): Loc {
    return Loc(
        file = file,
        startLine = startToken.line,
        startCol = startToken.column,
        endLine = tokenEndLine(endToken),
        endCol = tokenEndCol(endToken)
    )
}

internal fun spanLocTo(startToken: Token, stream: TokenStream, file: String): Loc {
    val endToken = if (stream.position > 0) {
        stream.tokens.getOrNull(stream.position - 1) ?: startToken
    } else {
        startToken
    }
    return spanLoc(startToken, endToken, file)
}

internal fun streamLoc(stream: TokenStream, file: String): Loc {
    val token = stream.current() ?: stream.tokens.lastOrNull()
    return if (token != null) tokenLoc(token, file) else Loc(file, 1, 1, 1, 1)
}

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
 * Post-processing: convert ParsedPerformerDecl that reference abilities into ParsedImplAbilityDecl.
 *
 * Since ability impls and effect impls share the `impl X for Y where { ... }` syntax,
 * the parser tries the effect impl parser first (which produces ParsedPerformerDecl). This pass
 * checks which ones actually reference a declared ability (not an effect) and converts those to
 * ParsedImplAbilityDecl.
 */
internal fun resolveParsedImplDeclarations(module: ParsedModule): ParsedModule {
    val abilityNames = module.decls.filterIsInstance<ParsedAbilityDecl>().map { it.name }.toSet()
    val effectNames = module.decls.filterIsInstance<ParsedEffectDecl>().map { it.name }.toSet()

    if (abilityNames.isEmpty()) return module

    val resolvedDecls = module.decls.map { decl ->
        if (decl is ParsedPerformerDecl &&
            decl.effect.localName in abilityNames &&
            decl.effect.localName !in effectNames) {
            val targetType = ParsedNamedType(decl.performerName, decl.effect.loc)
            val targetTypes = listOf(targetType)

            ParsedImplAbilityDecl(
                target = ParsedImplTarget.Named(decl.effect),
                targetTypes = targetTypes,
                abilityTypeArgs = emptyList(),
                forallVars = emptyList(),
                associatedTypeBindings = decl.associatedTypeBindings,
                operations = decl.operations.map { op ->
                    ParsedImplOp(
                        name = op.name,
                        paramType = op.paramType,
                        returnType = op.returnType,
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
