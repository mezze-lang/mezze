package com.mezze.language.cli.commands

import com.mezze.language.langservice.printing.ParseErrorFormatter
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedImplAbilityDecl
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.parsedast.ParsedPubUseDecl
import com.mezze.language.parsedast.ParsedTopLevelExpr
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedUseDecl
import com.mezze.language.parser.MezzeParser

/**
 * Decide what one REPL input is — an expression, one or more imports, one or more
 * definitions, or a syntax error — by PARSING it, never by matching leading
 * keywords. A bare expression parses to a single [ParsedTopLevelExpr]; a `use` to a
 * [ParsedUseDecl]; everything else is a definition. The parser is the authority on
 * Mezze's grammar, so the classifier stays correct as the grammar grows.
 *
 * Pure: no connection, no compile, no I/O — which is what lets [ReplClassifierTest]
 * pin the whole decision table without a runtime.
 */
internal object ReplClassifier {

    sealed interface Input {
        /** `use` lines, each already its own single-line statement. */
        data class Import(val lines: List<String>) : Input
        data class Definition(
            /** Shadow key per decl — the name it binds, or its own text when nameless. */
            val keys: List<String>,
            /** The source text of each definition decl. */
            val texts: List<String>,
            /** A human label per decl for the acknowledgement. */
            val names: List<String>,
            /** The bound name when the whole input is a single simple `let`, else null. */
            val singleLetName: String?,
            /** `use` lines that shared the input with the definitions. */
            val importLines: List<String>,
        ) : Input
        data class Expression(val text: String) : Input
        data class SyntaxError(val message: String) : Input
    }

    fun classify(src: String): Input {
        val parsed = MezzeParser().parseWithRecovery(src, file = "<repl>")
        if (parsed.errors.isNotEmpty()) {
            return Input.SyntaxError(ParseErrorFormatter.formatAll(parsed.errors))
        }
        val decls = parsed.module.decls
        if (decls.isEmpty()) return Input.Expression(src)
        if (decls.size == 1 && decls[0] is ParsedTopLevelExpr) return Input.Expression(src)

        // Each decl's own text, sliced from its line span, so a pasted block of
        // several declarations is split back into per-decl units — and a `use` mixed
        // in among definitions lands in the import set, never in the appended-decls
        // position where a `use` is invalid.
        val lines = src.lines()
        fun textOf(d: ParsedDecl): String {
            val from = (d.loc.startLine - 1).coerceIn(0, lines.size)
            val to = d.loc.endLine.coerceIn(from, lines.size)
            return lines.subList(from, to).joinToString("\n").trim()
        }

        val isUse = { d: ParsedDecl -> d is ParsedUseDecl || d is ParsedPubUseDecl }
        val importLines = decls.filter(isUse).map(::textOf)
        val defDecls = decls.filterNot(isUse)
        if (defDecls.isEmpty()) return Input.Import(importLines)

        val keys = mutableListOf<String>()
        val names = mutableListOf<String>()
        val texts = mutableListOf<String>()
        for (d in defDecls) {
            val name = declName(d)
            keys.add(name ?: textOf(d))
            names.add(name ?: declLabel(d))
            texts.add(textOf(d))
        }
        val singleLet = (defDecls.singleOrNull() as? ParsedLetDecl)?.takeIf { it.isSimpleBinding }?.variableName
        return Input.Definition(keys, texts, names, singleLet, importLines)
    }

    /** The name a decl binds, when it has a simple one a redefinition would shadow. */
    fun declName(d: ParsedDecl): String? = when (d) {
        is ParsedLetDecl -> if (d.isSimpleBinding) d.variableName else null
        is ParsedTypeDecl -> d.name
        is ParsedEffectDecl -> d.name
        is ParsedAbilityDecl -> d.name
        is ParsedPerformerDecl -> d.performerName
        else -> null
    }

    private fun declLabel(d: ParsedDecl): String = when (d) {
        is ParsedImplAbilityDecl -> "impl"
        is ParsedLetDecl -> "binding"
        else -> d::class.simpleName?.removePrefix("Parsed")?.removeSuffix("Decl")?.lowercase() ?: "declaration"
    }
}
