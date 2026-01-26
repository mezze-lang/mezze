package com.funk.language.parser

import com.funk.language.ast.*

/**
 * Declaration parsers for module-level constructs.
 *
 * This file contains internal parsers for:
 * - Let bindings (let x = ...)
 * - Constrained lambdas (function types with ability constraints)
 * - Effect declarations (effect Name where { ... })
 * - Type declarations (type/newtype)
 * - Impl declarations (impl Type for Ability { ... })
 * - Ability declarations (ability Name { ... })
 * - Impl ability declarations (impl ability Name for Type { ... })
 * - Use/import declarations (use path::to::module)
 * - Top-level item dispatch
 *
 * All parsers return ProgramItem (sealed interface from AST).
 * All functions are internal - only used within the parser module.
 *
 * Part of the refactored parser module - previously in FunkParser.kt.
 */

// Declaration parsers will be moved here in Task 10
