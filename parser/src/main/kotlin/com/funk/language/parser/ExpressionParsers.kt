package com.funk.language.parser

import com.funk.language.ast.*

/**
 * Expression parsers for all expression types.
 *
 * This file contains internal parsers for:
 * - Lambda expressions ({ x } -> body)
 * - Record literals ({ x = 1, y = 2 })
 * - Array literals ([1, 2, 3])
 * - If expressions (if cond then a else b)
 * - Match expressions (match expr { pattern -> result })
 * - Do expressions (do { stmt1; stmt2; result })
 * - Perform expressions (perform operation)
 * - Primary expressions (identifiers, literals, member access, etc.)
 * - Lambda vs Record disambiguation logic
 *
 * All parsers return Expression (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in FunkParser.kt.
 */

// Expression parsers will be moved here in Task 11
