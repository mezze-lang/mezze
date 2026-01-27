package com.funk.language.parser.internal

import com.funk.language.ast.*
import com.funk.language.parser.*

/**
 * Type annotation parsers for all type constructs.
 *
 * This file contains internal parsers for:
 * - Type annotations (main entry point)
 * - Function types ({ x: Int } -> String)
 * - Record types ({ x: Int, y: String })
 * - Variant types (Result 'a 'e)
 * - Array types ([Int])
 * - Effect row annotations (effects, effect variables)
 * - Ability constraints (with NumAdd 'a, Eq 'b)
 * - Type parameters and bounds
 *
 * All parsers return TypeAnnotation (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in FunkParser.kt.
 */

// Type parsers will be moved here in Task 13
