package com.mezze.language.typechecker

import com.mezze.language.ast.Program
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parser.MezzeParser

/**
 * TypeCheckerV2 — bridge for converting ParsedModule (v2) to v1 Program.
 *
 * Used by callers that still need v1 Program (ModuleTypeChecker, checkParsedModuleWithDeps).
 * Will be deleted once all callers migrate to v2-only paths.
 */
class TypeCheckerV2 {

    /** Convert ParsedModule to v1 Program (using parser's built-in conversion) */
    fun parsedModuleToV1(module: ParsedModule): Program {
        return MezzeParser().parsedModuleToProgram(module)
    }
}
