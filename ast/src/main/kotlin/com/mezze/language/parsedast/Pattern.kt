package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * Pattern nodes for match expressions, let bindings, and function parameters.
 *
 * All patterns have a required Loc.
 */
@Serializable
sealed interface ParsedPattern {
    val loc: Loc
}

/** Wildcard: `_` */
@Serializable
data class ParsedWildcardPattern(
    override val loc: Loc
) : ParsedPattern

/** Variable binding: `x` */
@Serializable
data class ParsedVariablePattern(
    val name: String,
    override val loc: Loc
) : ParsedPattern

/**
 * Constructor pattern: `Some { val = x }`, `Some x`, `None`
 *
 * The parser sees any uppercase identifier as a constructor.
 * The typechecker later resolves whether it's a variant, ability, effect, etc.
 */
@Serializable
data class ParsedConstructorPattern(
    val constructor: String,
    val payload: ParsedPattern? = null,
    override val loc: Loc
) : ParsedPattern

/**
 * Newtype destructuring pattern: `Email.(val)`, `Point.({ x, y })`
 *
 * Only valid in the module that defines the newtype.
 */
@Serializable
data class ParsedNewtypePattern(
    val typeName: String,
    val inner: ParsedPattern,
    override val loc: Loc
) : ParsedPattern

/**
 * Record pattern: `{ x, y }`, `{ x = a, y = b }`, `{ x, ... }`
 *
 * - Punning: `{ x }` means `{ x = x }` (pattern=null on entry)
 * - Open: `{ x, ... }` means match records with at least field x
 */
@Serializable
data class ParsedRecordPattern(
    val entries: List<ParsedRecordPatternEntry>,
    val isOpen: Boolean = false,
    override val loc: Loc
) : ParsedPattern

@Serializable
data class ParsedRecordPatternEntry(
    val name: String,
    val pattern: ParsedPattern? = null,
    val loc: Loc
)

/**
 * Literal pattern: `42`, `"hello"`, `3.14`, `true`, `42n`
 */
@Serializable
data class ParsedLiteralPattern(
    val value: ParsedLiteralValue,
    override val loc: Loc
) : ParsedPattern

@Serializable
sealed interface ParsedLiteralValue {
    @Serializable data class IntValue(val value: Long) : ParsedLiteralValue
    @Serializable data class NatValue(val value: Long) : ParsedLiteralValue
    @Serializable data class FloatValue(val value: Double) : ParsedLiteralValue
    @Serializable data class StringValue(val value: String) : ParsedLiteralValue
    @Serializable data class BoolValue(val value: Boolean) : ParsedLiteralValue
}

/**
 * Seq pattern: `[]`, `[x, y]`, `[head, ...tail]`
 */
@Serializable
data class ParsedSeqPattern(
    val elements: List<ParsedPattern>,
    val restPattern: String? = null,
    override val loc: Loc
) : ParsedPattern

/**
 * String interpolation pattern: `"prefix$(capture)suffix"`
 *
 * Only variable names in captures (not expressions).
 * Each capture binds a String.
 */
@Serializable
data class ParsedStringInterpPattern(
    val parts: List<ParsedStringPatternPart>,
    override val loc: Loc
) : ParsedPattern

@Serializable
sealed interface ParsedStringPatternPart {
    @Serializable data class Literal(val value: String) : ParsedStringPatternPart
    @Serializable data class Capture(val variableName: String) : ParsedStringPatternPart
}


fun ParsedPattern.isIrrefutable(): Boolean = when (this) {
    is ParsedVariablePattern -> true
    is ParsedWildcardPattern -> true
    is ParsedRecordPattern -> entries.all { it.pattern?.isIrrefutable() ?: true }
    is ParsedConstructorPattern -> false
    is ParsedNewtypePattern -> false
    is ParsedLiteralPattern -> false
    is ParsedSeqPattern -> false
    is ParsedStringInterpPattern -> false
}

fun ParsedPattern.extractBindings(): List<String> = when (this) {
    is ParsedVariablePattern -> listOf(name)
    is ParsedWildcardPattern -> emptyList()
    is ParsedRecordPattern -> entries.flatMap { it.pattern?.extractBindings() ?: listOf(it.name) }
    is ParsedConstructorPattern -> payload?.extractBindings() ?: emptyList()
    is ParsedNewtypePattern -> inner.extractBindings()
    is ParsedLiteralPattern -> emptyList()
    is ParsedSeqPattern -> {
        // "_" is the tail-ignoring rest (`..._`) — it matches but binds nothing.
        elements.flatMap { it.extractBindings() } + listOfNotNull(restPattern?.takeIf { it != "_" })
    }
    is ParsedStringInterpPattern -> parts.mapNotNull {
        (it as? ParsedStringPatternPart.Capture)?.variableName
    }
}

fun ParsedPattern.kindDescription(): String = when (this) {
    is ParsedVariablePattern -> "variable pattern"
    is ParsedWildcardPattern -> "wildcard pattern"
    is ParsedRecordPattern -> "record pattern"
    is ParsedConstructorPattern -> "constructor pattern"
    is ParsedNewtypePattern -> "newtype pattern"
    is ParsedLiteralPattern -> "literal pattern"
    is ParsedSeqPattern -> "seq pattern"
    is ParsedStringInterpPattern -> "string interpolation pattern"
}
