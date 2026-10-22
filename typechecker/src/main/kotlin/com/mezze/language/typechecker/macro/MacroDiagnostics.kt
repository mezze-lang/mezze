package com.mezze.language.typechecker.macro

import com.mezze.language.parsedast.MacroDiagnostic
import com.mezze.language.parsedast.ParsedExpr
import com.mezze.language.parsedast.ParsedMacroCall
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.children
import com.mezze.language.parsedast.mapExprs

/** Every problem expanding the macro calls in a module, in written order. */
internal object MacroDiagnostics {
    fun of(module: ParsedModule): List<MacroDiagnostic> {
        val found = mutableListOf<MacroDiagnostic>()
        fun walk(e: ParsedExpr) {
            if (e is ParsedMacroCall) found += e.diagnostics
            e.children().forEach(::walk)
        }
        module.decls.forEach { d -> d.mapExprs { e -> walk(e); e } }
        return found
    }
}
