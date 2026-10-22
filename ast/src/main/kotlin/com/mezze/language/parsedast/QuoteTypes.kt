package com.mezze.language.parsedast

/**
 * The `std::syntax` types a quote's value is held to, in scope wherever a quote is.
 *
 * A quote builds `std::syntax` values, so its code names `std::syntax`'s types —
 * but the module it is written in need not import them, and may use those names
 * for something else. So a module with a quote gets one more import, under
 * names no source can spell or shadow. It is added before the module is
 * identified, so identities and type checking see it; the parser and the
 * formatter never do, so it is never written back.
 */
object QuoteTypes {
    /** `std::syntax::Expr`, as a quote's code names it. */
    const val EXPR = "~Expr"

    /** `std::syntax::SyntaxDecl`, as a quote's code names it. */
    const val SYNTAX_DECL = "~SyntaxDecl"

    /** The name [name] is declared under: a hidden quote name's, without its `~`; any other unchanged. */
    fun declaredName(name: String): String = if (name == EXPR || name == SYNTAX_DECL) name.substring(1) else name

    /** [module] with the import its quotes need, when it has any. */
    fun inScope(module: ParsedModule): ParsedModule {
        if (module.decls.none { it.hasQuote() }) return module
        val at = module.decls.first().loc.let { Loc(it.file, 1, 1, 1, 1) }
        val import = ParsedUseDecl(
            path = ParsedUsePath.Selective(
                ParsedModulePath(listOf("std", "syntax")),
                listOf(ParsedImportItem("Expr", EXPR, at), ParsedImportItem("SyntaxDecl", SYNTAX_DECL, at)),
                at,
            ),
            loc = at,
        )
        return module.copy(decls = listOf(import) + module.decls)
    }

    private fun ParsedDecl.hasQuote(): Boolean {
        var found = false
        mapExprs { e -> if (!found && e.hasQuote()) found = true; e }
        return found
    }

    private fun ParsedExpr.hasQuote(): Boolean = this is ParsedQuote || children().any { it.hasQuote() }
}
