package com.funk.language.parser

import com.funk.language.ast.*

/**
 * Pattern parsers for all pattern matching constructs.
 *
 * This file contains internal parsers for:
 * - Wildcard patterns (_)
 * - Variable patterns (x)
 * - Constructor patterns (Cons { head, tail })
 * - Record patterns ({ x, y = pattern })
 * - Literal patterns (42, "hello", true)
 * - Array patterns ([x, y, ...rest])
 * - Let binding patterns (irrefutable patterns only)
 * - Pattern utilities and validation
 *
 * All parsers return Pattern (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in FunkParser.kt.
 */

// Pattern parsers will be moved here in Task 12
