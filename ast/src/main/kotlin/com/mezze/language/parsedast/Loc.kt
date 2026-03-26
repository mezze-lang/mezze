package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * Source location with full span — required on every AST node.
 *
 * Both start and end are inclusive, 1-indexed.
 * Maps directly to Truffle SourceSection.
 */
@Serializable
data class Loc(
    val file: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int
) {
    override fun toString(): String = "$file:$startLine:$startCol-$endLine:$endCol"

    /**
     * Create a Loc spanning from this loc's start to another loc's end.
     */
    fun spanTo(other: Loc): Loc = Loc(file, startLine, startCol, other.endLine, other.endCol)
}

/**
 * Visibility modifier — pub or private (default).
 */
@Serializable
enum class Visibility {
    PUBLIC, PRIVATE
}

/**
 * A comment in source code.
 *
 * `#`  = regular comment (formatter-only, dropped after formatting)
 * `##` = doc comment (carried into TypedAST, used for docs/IDE hover)
 */
@Serializable
data class ParsedComment(
    val text: String,
    val isDoc: Boolean,
    val loc: Loc
)
