package com.mezze.language.formatter

import com.mezze.language.parsedast.*

/**
 * Pretty printer for Mezze ParsedAST.
 *
 * Converts ParsedModule back to formatted source code with consistent style.
 * Comments are attached to declarations via leadingComments/trailingComment fields.
 */
class PrettyPrinter(private val config: FormatterWidths = FormatterConfig) {
    private companion object {
        /** A `~( … )` splice's placeholder name in a quote's template. */
        val SPLICE_NAME = Regex("""~\((\d+)\)""")
    }

    private val output = StringBuilder()
    private var indentLevel = 0
    private var currentColumn = 0

    fun printModule(module: ParsedModule): String {
        output.clear()
        indentLevel = 0
        currentColumn = 0

        var isFirst = true
        var lastWasUse = false
        var prevDecl: ParsedDecl? = null

        for (decl in module.decls) {
            val isUse = decl is ParsedUseDecl || decl is ParsedPubUseDecl

            if (!isFirst) {
                // Blank line BETWEEN top-level declarations, balanced (not
                // always-on). Insert one when ANY of:
                //   - crossing the use-block boundary (imports end);
                //   - the author left a blank line in the source (their
                //     grouping is preserved; multiple blanks collapse to one);
                //   - either decl spans multiple lines (functions, type/ability/
                //     impl decls always get breathing room).
                // Otherwise adjacent one-liner lets stay packed. `use`s never
                // get blanks between them.
                val crossingUseBoundary = lastWasUse && !isUse
                val bothUse = lastWasUse && isUse
                val insertBlank = crossingUseBoundary ||
                    (!bothUse && (
                        authorLeftBlankLine(prevDecl, decl) ||
                        isMultiLineDecl(prevDecl) ||
                        isMultiLineDecl(decl)
                    ))
                if (insertBlank) newLine()
            }
            isFirst = false
            lastWasUse = isUse
            prevDecl = decl

            printLeadingComments(decl)
            printDocComment(decl)
            printMetadata(decl)
            printDecl(decl)
            printTrailingComment(decl)
        }

        // Print orphan comments (not attached to any declaration)
        for (comment in module.orphanComments) {
            if (output.isNotEmpty() && !output.endsWith("\n")) newLine()
            append(comment.text)
            newLine()
        }

        return output.toString().trimEnd() + "\n"
    }

    /**
     * Did the author leave at least one blank line between [prev] and [next] in
     * the source? Measured from [prev]'s last line to the top of [next]'s block
     * (its first leading/doc comment if any, else the decl itself), so a comment
     * sitting directly above a decl with no blank line isn't miscounted as one.
     * A gap of ≥ 2 lines means a blank line was present.
     */
    private fun authorLeftBlankLine(prev: ParsedDecl?, next: ParsedDecl): Boolean {
        if (prev == null) return false
        val nextTop = next.leadingComments.minOfOrNull { it.loc.startLine }
            ?: next.loc.startLine
        return nextTop - prev.loc.endLine >= 2
    }

    /** A declaration that occupies more than one source line. */
    private fun isMultiLineDecl(decl: ParsedDecl?): Boolean {
        if (decl == null) return false
        return decl.loc.endLine > decl.loc.startLine
    }

    // =========================================================================
    // DECLARATIONS
    // =========================================================================

    private fun printDecl(decl: ParsedDecl) {
        when (decl) {
            is ParsedLetDecl -> printLetDecl(decl)
            is ParsedTypeDecl -> printTypeDecl(decl)
            is ParsedEffectDecl -> printEffectDecl(decl)
            is ParsedAbilityDecl -> printAbilityDecl(decl)
            is ParsedImplAbilityDecl -> printImplAbilityDecl(decl)
            is ParsedPerformerDecl -> printPerformerDecl(decl)
            is ParsedUseDecl -> printUseDecl(decl)
            is ParsedPubUseDecl -> printPubUseDecl(decl)
            is ParsedTopLevelExpr -> {
                printExpr(decl.expression)
                newLine()
            }
        }
    }

    private fun printLetDecl(decl: ParsedLetDecl) {
        if (decl.visibility == Visibility.PUBLIC) append("pub ")
        append("let ")
        printPattern(decl.pattern)

        val fnType = decl.typeAnnotation as? ParsedFunctionType
        // #78 — wrap an over-budget function-type signature across lines at the
        // top-level `:`, `->`, and `=`. The decl keyword + name stay on the
        // anchor line (P0126); the wrapped `:`/`->`/`=` lines are indented one
        // level. Only function-type signatures wrap (that's where the `->`
        // break earns its keep); everything else stays single-line.
        if (fnType != null && letSignatureExceedsBudget(decl, fnType)) {
            printWrappedLetSignature(decl, fnType)
            return
        }

        decl.typeAnnotation?.let {
            append(": ")
            printTypeAnnotation(it)
        }
        append(" = ")
        printExpr(decl.value)
        newLine()
    }

    /** Would the one-line `[pub ]let name : T -> U = body` exceed the budget? */
    private fun letSignatureExceedsBudget(decl: ParsedLetDecl, fnType: ParsedFunctionType): Boolean {
        val prefix = (if (decl.visibility == Visibility.PUBLIC) 4 else 0) + 4 // "pub " + "let "
        val nameW = estimatePatternWidth(decl.pattern)
        val typeW = estimateTypeWidth(fnType)
        val bodyW = estimateExprWidth(decl.value)
        // name + ": " + type + " = " + body
        return prefix + nameW + 2 + typeW + 3 + bodyW > config.MAX_LINE_LENGTH
    }

    /**
     * `pub let name\n  : Param [with C]\n  -> Ret [<eff>]\n  = body`. The parser
     * accepts arbitrary newlines between `:`, the type, `->`, the effect row,
     * `=`, and the body once the anchor line is parsed (#78).
     */
    private fun printWrappedLetSignature(decl: ParsedLetDecl, fnType: ParsedFunctionType) {
        newLine()
        indent {
            printIndent()
            append(": ")
            printTypeAnnotation(fnType.paramType)
            if (fnType.abilityConstraints.isNotEmpty()) {
                append(" with ")
                printAbilityConstraints(fnType.abilityConstraints)
            }
            newLine()
            printIndent()
            append("-> ")
            printTypeAnnotation(fnType.returnType)
            fnType.effects?.let { append(" "); printEffectRow(it) }
            newLine()
            printIndent()
            append("= ")
            printExpr(decl.value)
        }
        newLine()
    }

    private fun estimatePatternWidth(pattern: ParsedPattern): Int = when (pattern) {
        is ParsedVariablePattern -> pattern.name.length
        is ParsedWildcardPattern -> 1
        is ParsedRecordPattern -> 4 + pattern.entries.sumOf { it.name.length + 2 }
        else -> 8
    }

    private fun printTypeDecl(decl: ParsedTypeDecl) {
        if (decl.visibility == Visibility.PUBLIC) append("pub ")
        when (val def = decl.definition) {
            is ParsedNewtypeDef -> {
                // The parser sets constructor == decl.name, so the old
                // formatter form `newtype X = X <wrappedType>` double-printed
                // the name whenever wrappedType was a bare `ParsedTypeApp(X, …)`
                // (old syntax `newtype UserId = UserId Int`). New syntax is
                // `newtype X = <wrappedType>` — just print the wrapped type.
                append("newtype ")
                append(decl.name)
                printTypeParams(decl.typeParams)
                append(" = ")
                printTypeAnnotation(def.wrappedType)
            }
            else -> {
                append("type ")
                append(decl.name)
                printTypeParams(decl.typeParams)
                append(" = ")
                printTypeDef(decl.definition)
            }
        }
        newLine()
    }

    private fun printTypeParams(typeParams: List<String>) {
        if (typeParams.isNotEmpty()) {
            append(" ")
            append(typeParams.joinToString(" "))
        }
    }

    private fun printTypeDef(def: ParsedTypeDef) {
        when (def) {
            is ParsedRecordDef -> printRecordDef(def)
            is ParsedVariantDef -> printVariantDef(def)
            is ParsedNewtypeDef -> { /* handled in printTypeDecl */ }
            is ParsedTypeAliasDef -> printTypeAnnotation(def.aliasedType)
        }
    }

    private fun printRecordDef(def: ParsedRecordDef) {
        if (def.fields.isEmpty() && !def.isOpen) {
            append("{}")
            return
        }
        val width = 4 + def.fields.sumOf { it.name.length + 2 + estimateTypeWidth(it.type) + 2 }
        if (width <= config.RECORD_SINGLE_LINE_THRESHOLD) {
            append("{ ")
            def.fields.forEachIndexed { i, field ->
                append(field.name)
                append(": ")
                printTypeAnnotation(field.type)
                if (i < def.fields.size - 1) append(", ")
            }
            if (def.isOpen) { append(", ...") }
            append(" }")
        } else {
            append("{")
            newLine()
            indent {
                def.fields.forEachIndexed { i, field ->
                    printIndent()
                    append(field.name)
                    append(": ")
                    printTypeAnnotation(field.type)
                    if (config.TRAILING_COMMA_IN_MULTILINE || i < def.fields.size - 1) append(",")
                    newLine()
                }
                if (def.isOpen) { printIndent(); append("..."); newLine() }
            }
            printIndent()
            append("}")
        }
    }

    private fun printVariantDef(def: ParsedVariantDef) {
        val width = 4 + def.constructors.sumOf { c ->
            c.name.length + (c.dataType?.let { 1 + estimateTypeWidth(it) } ?: 0) + 2
        }
        if (width <= config.RECORD_SINGLE_LINE_THRESHOLD && def.constructors.isNotEmpty()) {
            append("| ")
            def.constructors.forEachIndexed { i, c ->
                if (i > 0) append(", ")
                append(c.name)
                c.dataType?.let { append(" "); printTypeAnnotation(it) }
            }
            if (def.isOpen) append(", ...")
            append(" |")
        } else {
            append("|")
            newLine()
            indent {
                def.constructors.forEach { c ->
                    printIndent()
                    append(c.name)
                    c.dataType?.let { append(" "); printTypeAnnotation(it) }
                    append(",")
                    newLine()
                }
                if (def.isOpen) { printIndent(); append("..."); newLine() }
            }
            printIndent()
            append("|")
        }
    }

    private fun printEffectDecl(decl: ParsedEffectDecl) {
        append("effect ")
        append(decl.name)
        if (decl.associatedTypes.isNotEmpty()) {
            append(" having ")
            append(decl.associatedTypes.joinToString(", "))
        }
        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { i, op ->
                printMemberDoc(op.doc)
                printIndent()
                append(op.name)
                append(": ")
                printTypeAnnotation(op.paramType)
                append(" -> ")
                printTypeAnnotation(op.returnType)
                if (op.effects.isNotEmpty()) {
                    append(" <")
                    append(op.effects.joinToString(", "))
                    append(">")
                }
                if (i < decl.operations.size - 1) append(",")
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printAbilityDecl(decl: ParsedAbilityDecl) {
        append("ability ")
        append(decl.name)
        val receiverParam = decl.receiverTypeParam
        val genericParams = if (receiverParam != null) {
            decl.typeParams.filter { it != receiverParam }
        } else {
            decl.typeParams.dropLast(1)
        }
        if (genericParams.isNotEmpty()) {
            append(" ")
            append(genericParams.joinToString(" "))
        }
        append(" for ")
        append(receiverParam ?: decl.typeParams.lastOrNull() ?: "'a")

        if (decl.associatedTypes.isNotEmpty()) {
            append(" having ")
            append(decl.associatedTypes.joinToString(", "))
        }

        // Ability-level dependencies were removed in #212; the parser
        // rejects ability-level `with` clauses with P0119.

        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { i, op ->
                printMemberDoc(op.doc)
                printIndent()
                append(op.name)
                append(": ")
                printTypeAnnotation(op.paramType)
                if (op.abilityConstraints.isNotEmpty()) {
                    append(" with ")
                    printAbilityConstraints(op.abilityConstraints)
                }
                append(" -> ")
                printTypeAnnotation(op.returnType)
                op.effects?.let { append(" "); printEffectRow(it) }
                if (i < decl.operations.size - 1) append(",")
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printImplAbilityDecl(decl: ParsedImplAbilityDecl) {
        append("impl ")
        // No `forall …` prefix: Mezze surface syntax has no explicit
        // quantifier keyword, and `forallVars` on ParsedImplAbilityDecl is
        // derived by the parser from the type variables that appear in
        // `abilityTypeArgs` / `targetTypes`. Round-tripping through the
        // formatter is sound — the next parse re-derives the same list.
        when (val target = decl.target) {
            is ParsedImplTarget.Named -> {
                if (target.ability.qualifier != null) {
                    append(target.ability.qualifier!!.segments.joinToString("::"))
                    append("::")
                }
                append(target.ability.localName)
                for (typeArg in decl.abilityTypeArgs) {
                    append(" ")
                    printTypeAnnotation(typeArg)
                }
                append(" for ")
                decl.targetTypes.forEachIndexed { i, type ->
                    if (i > 0) append(" ")
                    printTypeAnnotation(type)
                }
            }
            is ParsedImplTarget.Inherent -> {
                decl.targetTypes.forEachIndexed { i, type ->
                    if (i > 0) append(" ")
                    printTypeAnnotation(type)
                }
            }
        }
        if (decl.associatedTypeBindings.isNotEmpty()) {
            append(" having ")
            decl.associatedTypeBindings.entries.forEachIndexed { i, (name, type) ->
                if (i > 0) append(", ")
                append("$name = ")
                printTypeAnnotation(type)
            }
        }
        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { i, op ->
                printMemberDoc(op.doc)
                printIndent()
                append(op.name)
                // Print type annotation if present (the bug fix: don't drop these)
                val pType = op.paramType
                val rType = op.returnType
                if (pType != null && rType != null) {
                    append(": ")
                    printTypeAnnotation(pType)
                    if (op.abilityConstraints.isNotEmpty()) {
                        append(" with ")
                        printAbilityConstraints(op.abilityConstraints)
                    }
                    append(" -> ")
                    printTypeAnnotation(rType)
                }
                append(" = ")
                printExpr(op.implementation)
                if (decl.operations.size > 1 || i < decl.operations.size - 1) {
                    trimTrailingNewlines()
                    append(",")
                }
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printPerformerDecl(decl: ParsedPerformerDecl) {
        append("impl ")
        if (decl.effect.qualifier != null) {
            append(decl.effect.qualifier!!.segments.joinToString("::"))
            append("::")
        }
        append(decl.effect.localName)
        append(" for ")
        append(decl.performerName)
        if (decl.params.isNotEmpty()) {
            append(" { ")
            decl.params.forEachIndexed { i, p ->
                if (i > 0) append(", ")
                append(p.name)
                append(": ")
                printTypeAnnotation(p.type)
            }
            append(" }")
        }
        if (decl.associatedTypeBindings.isNotEmpty()) {
            append(" having ")
            decl.associatedTypeBindings.entries.forEachIndexed { i, (name, type) ->
                if (i > 0) append(", ")
                append("$name = ")
                printTypeAnnotation(type)
            }
        }
        append(" where {")
        newLine()
        indent {
            decl.operations.forEachIndexed { i, op ->
                printMemberDoc(op.doc)
                printIndent()
                append(op.name)
                // Preserve type annotations if present
                val pType = op.paramType
                val rType = op.returnType
                if (pType != null && rType != null) {
                    append(": ")
                    printTypeAnnotation(pType)
                    append(" -> ")
                    printTypeAnnotation(rType)
                    op.effects?.let { append(" "); printEffectRow(it) }
                }
                append(" = ")
                printExpr(op.implementation)
                if (decl.operations.size > 1 || i < decl.operations.size - 1) {
                    trimTrailingNewlines()
                    append(",")
                }
                newLine()
            }
        }
        printIndent()
        append("}")
        newLine()
    }

    private fun printUseDecl(decl: ParsedUseDecl) {
        append("use ")
        printUsePath(decl.path)
        decl.alias?.let { append(" as "); append(it) }
        newLine()
    }

    private fun printPubUseDecl(decl: ParsedPubUseDecl) {
        append("pub use ")
        printUsePath(decl.path)
        decl.alias?.let { append(" as "); append(it) }
        newLine()
    }

    private fun printUsePath(path: ParsedUsePath) {
        when (path) {
            is ParsedUsePath.Module -> append(path.path.segments.joinToString("::"))
            is ParsedUsePath.Selective -> {
                append(path.modulePath.segments.joinToString("::"))
                append("::{ ")
                path.items.forEachIndexed { i, item ->
                    if (i > 0) append(", ")
                    append(item.name)
                    item.alias?.let { append(" as "); append(it) }
                }
                append(" }")
            }
        }
    }

    // =========================================================================
    // EXPRESSIONS
    // =========================================================================

    private fun printExpr(expr: ParsedExpr) {
        when (expr) {
            is ParsedIntLit -> append(expr.value.toString())
            is ParsedNatLit -> append("${expr.value}n")
            is ParsedFloatLit -> append(formatFloat(expr.value))
            is ParsedFracLit -> append("${expr.numerator}//${expr.denominator}")
            is ParsedStringLit -> printStringLit(expr)
            is ParsedCharLit -> { append("'"); append(expr.value.toString()); append("'") }
            is ParsedIdentifier -> printIdentifier(expr)
            is ParsedRecordLit -> printRecordLit(expr)
            is ParsedSeqLit -> printSeqLit(expr)
            is ParsedMemberAccess -> { printExpr(expr.receiver); append("."); append(expr.member) }
            is ParsedVariantConstruct -> printVariantConstruct(expr)
            is ParsedNewtypeConstruct -> { append(expr.typeName); append(".("); printExpr(expr.value); append(")") }
            is ParsedLambda -> printLambda(expr)
            is ParsedFunctionCall -> printFunctionCall(expr)
            is ParsedBinaryOp -> printBinaryOp(expr)
            is ParsedIf -> printIf(expr)
            is ParsedMatch -> printMatch(expr)
            is ParsedDo -> printDo(expr)
            is ParsedPerform -> printPerform(expr)
            is ParsedEffectCall -> printEffectCall(expr)
            is ParsedPipe -> printPipe(expr)
            is ParsedPipePlaceholder -> append("@")
            is ParsedParens -> { append("("); printExpr(expr.expr); append(")") }
            is ParsedQuote -> printQuote(expr)
            // A macro's input is the macro's own language: it is kept exactly as written.
            is ParsedMacroCall -> { printIdentifier(expr.macro); append("!("); append(expr.input); append(")") }
        }
    }

    /**
     * `` `( … ) ``, as written. A `~x` splice is a name and prints as one; a
     * `~( … )` splice is the name `~(N)` in the template, so its expression is
     * put back in its place once the template is printed.
     */
    private fun printQuote(expr: ParsedQuote) {
        val start = output.length
        append("`(")
        when (val t = expr.template) {
            is ParsedCode.Expr -> printExpr(t.expr)
            is ParsedCode.Decls -> {
                newLine()
                indent {
                    for (decl in t.decls) {
                        printIndent()
                        printDocComment(decl)
                        printDecl(decl)
                        if (!output.endsWith("\n")) newLine()
                    }
                }
                printIndent()
            }
        }
        append(")")
        if (expr.splices.isEmpty()) return
        val printed = SPLICE_NAME.replace(output.substring(start)) { m ->
            "~(" + PrettyPrinter(config).exprText(expr.splices[m.groupValues[1].toInt()]) + ")"
        }
        output.setLength(start)
        append(printed)
    }

    /** [expr] on its own, as text. */
    internal fun exprText(expr: ParsedExpr): String {
        output.clear()
        printExpr(expr)
        return output.toString()
    }

    private fun printStringLit(expr: ParsedStringLit) {
        append("\"")
        for (part in expr.parts) {
            when (part) {
                is ParsedStringPart.Literal -> append(escapeString(part.value))
                is ParsedStringPart.Interpolation -> {
                    append("\$(")
                    printExpr(part.expr)
                    append(")")
                }
            }
        }
        append("\"")
    }

    private fun printIdentifier(expr: ParsedIdentifier) {
        if (expr.qualifier != null) {
            append(expr.qualifier!!.segments.joinToString("::"))
            append("::")
        }
        append(expr.localName)
    }

    private fun printRecordLit(expr: ParsedRecordLit) {
        if (expr.entries.isEmpty()) { append("{}"); return }

        // A comment anywhere in the record forces multi-line — a comment can't
        // ride on a collapsed one-liner.
        val hasComments = expr.entries.any { entryComments(it).let { c -> c.first.isNotEmpty() || c.second != null } }
        val width = estimateRecordLitWidth(expr)
        if (!hasComments && width <= config.RECORD_SINGLE_LINE_THRESHOLD) {
            append("{ ")
            var first = true
            for (entry in expr.entries) {
                if (!first) append(", ")
                first = false
                printRecordEntry(entry)
            }
            append(" }")
        } else {
            append("{")
            newLine()
            indent {
                for ((i, entry) in expr.entries.withIndex()) {
                    val (leading, trailing) = entryComments(entry)
                    for (c in leading) { printIndent(); append(c.text); newLine() }
                    printIndent()
                    printRecordEntry(entry)
                    if (config.TRAILING_COMMA_IN_MULTILINE || i < expr.entries.size - 1) append(",")
                    trailing?.let { append("  "); append(it.text) }
                    newLine()
                }
            }
            printIndent()
            append("}")
        }
    }

    /** (leadingComments, trailingComment) for a record entry, regardless of variant. */
    private fun entryComments(entry: ParsedRecordEntry): Pair<List<ParsedComment>, ParsedComment?> = when (entry) {
        is ParsedRecordEntry.Field -> entry.leadingComments to entry.trailingComment
        is ParsedRecordEntry.Spread -> entry.leadingComments to entry.trailingComment
    }

    private fun printRecordEntry(entry: ParsedRecordEntry) {
        when (entry) {
            is ParsedRecordEntry.Field -> {
                append(entry.name)
                entry.value?.let { append(" = "); printExpr(it) }
            }
            is ParsedRecordEntry.Spread -> { append("..."); printExpr(entry.expr) }
        }
    }

    private fun printSeqLit(expr: ParsedSeqLit) {
        if (expr.elements.isEmpty()) { append("[]"); return }

        val width = estimateSeqLitWidth(expr)
        if (width <= config.ARRAY_SINGLE_LINE_THRESHOLD) {
            append("[")
            expr.elements.forEachIndexed { i, elem ->
                if (i > 0) append(", ")
                printSeqElement(elem)
            }
            append("]")
        } else {
            append("[")
            newLine()
            indent {
                expr.elements.forEachIndexed { i, elem ->
                    printIndent()
                    printSeqElement(elem)
                    if (config.TRAILING_COMMA_IN_MULTILINE || i < expr.elements.size - 1) append(",")
                    newLine()
                }
            }
            printIndent()
            append("]")
        }
    }

    private fun printSeqElement(elem: ParsedSeqElement) {
        when (elem) {
            is ParsedSeqElement.Regular -> printExpr(elem.expr)
            is ParsedSeqElement.Spread -> { append("..."); printExpr(elem.expr) }
        }
    }

    private fun printVariantConstruct(expr: ParsedVariantConstruct) {
        append(expr.constructor)
        expr.value?.let {
            append(" ")
            val needsParens = it is ParsedFunctionCall || it is ParsedIf || it is ParsedMatch
            if (needsParens) append("(")
            printExpr(it)
            if (needsParens) append(")")
        }
    }

    private fun printLambda(expr: ParsedLambda) {
        printLambdaParams(expr.params)

        if (expr.abilityConstraints.isNotEmpty()) {
            append(" with ")
            printAbilityConstraints(expr.abilityConstraints)
        }

        expr.returnType?.let {
            append(" -> ")
            printTypeAnnotation(it)
        }

        val bodyWidth = estimateExprWidth(expr.body)
        val wouldExceed = currentColumn + 4 + bodyWidth > config.MAX_LINE_LENGTH

        if (wouldExceed && !isSimpleExpr(expr.body)) {
            append(" ->")
            newLine()
            indent { printIndent(); printExpr(expr.body) }
        } else {
            append(" -> ")
            printExpr(expr.body)
        }
    }

    private fun printLambdaParams(params: ParsedLambdaParams) {
        when (params) {
            is ParsedLambdaParams.Untyped -> {
                if (params.names.isEmpty() && !params.isOpen) {
                    append("{}")
                } else {
                    append("{ ")
                    append(params.names.joinToString(", "))
                    if (params.isOpen) {
                        if (params.names.isNotEmpty()) append(", ")
                        append("...")
                    }
                    append(" }")
                }
            }
            is ParsedLambdaParams.Typed -> {
                // Typed params are record type syntax
                printRecordTypeAnnotation(ParsedRecordType(params.fields, false, params.fields.firstOrNull()?.loc ?: Loc("", 0, 0, 0, 0)))
            }
        }
    }

    private fun printFunctionCall(expr: ParsedFunctionCall) {
        // Check for chained method calls: x.foo {..}.bar {..}
        val chain = tryExtractMethodChain(expr)
        if (chain != null && chain.second.size >= 2) {
            printMethodChain(chain)
            return
        }

        val needsParens = expr.function is ParsedLambda || expr.function is ParsedIf
        if (needsParens) append("(")
        printExpr(expr.function)
        if (needsParens) append(")")
        append(" ")
        printExpr(expr.arguments)
    }

    private fun printBinaryOp(expr: ParsedBinaryOp) {
        val needsParensLeft = needsParens(expr.left, expr.op.symbol, isLeft = true)
        val needsParensRight = needsParens(expr.right, expr.op.symbol, isLeft = false)

        if (needsParensLeft) append("(")
        printExpr(expr.left)
        if (needsParensLeft) append(")")

        append(" ${expr.op.symbol} ")

        if (needsParensRight) append("(")
        printExpr(expr.right)
        if (needsParensRight) append(")")
    }

    private data class MethodChainSegment(val method: String, val args: ParsedExpr)

    private fun tryExtractMethodChain(expr: ParsedFunctionCall): Pair<ParsedExpr, List<MethodChainSegment>>? {
        val func = expr.function
        if (func !is ParsedMemberAccess) return null
        val segments = mutableListOf(MethodChainSegment(func.member, expr.arguments))
        var current = func.receiver
        while (current is ParsedFunctionCall && current.function is ParsedMemberAccess) {
            val ma = current.function as ParsedMemberAccess
            segments.add(0, MethodChainSegment(ma.member, current.arguments))
            current = ma.receiver
        }
        if (segments.size < 2) return null
        return Pair(current, segments)
    }

    private fun printMethodChain(chain: Pair<ParsedExpr, List<MethodChainSegment>>) {
        val (root, segments) = chain
        var estLen = estimateExprWidth(root)
        for (seg in segments) estLen += 1 + seg.method.length + 1 + estimateExprWidth(seg.args)

        if (estLen > config.MAX_LINE_LENGTH || segments.size > 3) {
            printExpr(root)
            newLine()
            indent {
                for ((i, seg) in segments.withIndex()) {
                    printIndent()
                    append(".${seg.method} ")
                    printExpr(seg.args)
                    if (i < segments.size - 1) newLine()
                }
            }
        } else {
            printExpr(root)
            for (seg in segments) { append(".${seg.method} "); printExpr(seg.args) }
        }
    }

    private fun printPipe(expr: ParsedPipe) {
        // Flatten `((a |> b) |> c)` to segments, pairing each stage with the
        // inline comment that trailed it (`|> b   # note`). A ParsedPipe's
        // trailingComments belong to its own `right`, i.e. the stage we add as
        // we descend through `.left`. segments[0] (the head) has no `|>` and so
        // never carries one.
        val segments = mutableListOf<ParsedExpr>()
        val comments = mutableListOf<List<ParsedComment>>()
        var current: ParsedExpr = expr
        while (current is ParsedPipe) {
            segments.add(0, current.right)
            comments.add(0, current.trailingComments)
            current = current.left
        }
        segments.add(0, current)
        comments.add(0, emptyList())

        val estLen = currentColumn + segments.sumOf { estimateExprWidth(it) } + (segments.size - 1) * 4
        // A stage comment can't sit inside a collapsed one-liner, so its
        // presence forces the multi-line layout (where each stage owns a line).
        val hasStageComment = comments.any { it.isNotEmpty() }
        val useMultiLine = hasStageComment || estLen > config.MAX_LINE_LENGTH || segments.size > 3

        if (useMultiLine) {
            printExpr(segments[0])
            comments[0].forEach { append("  "); append(it.text) }
            newLine()
            indent {
                for (i in 1 until segments.size) {
                    printIndent()
                    append("|> ")
                    printExpr(segments[i])
                    comments[i].forEach { append("  "); append(it.text) }
                    if (i < segments.size - 1) newLine()
                }
            }
        } else {
            printExpr(segments[0])
            for (i in 1 until segments.size) { append(" |> "); printExpr(segments[i]) }
        }
    }

    private fun printIf(expr: ParsedIf) {
        append("if ")
        printExpr(expr.condition)
        append(" then ")
        printExpr(expr.thenBranch)
        append(" else ")
        printExpr(expr.elseBranch)
    }

    private fun printMatch(expr: ParsedMatch) {
        append("match ")
        printExpr(expr.scrutinee)
        append(" is")
        newLine()
        indent {
            for (case in expr.cases) {
                printInBlockComments(case.leadingComments, case.docComment, case.metadata)
                printIndent()
                case.patterns.forEachIndexed { i, p ->
                    if (i > 0) append(" | ")
                    printPattern(p)
                }
                case.guard?.let { append(" if "); printExpr(it) }
                for (ifLet in case.ifLetBindings) {
                    append(" if let ")
                    printPattern(ifLet.pattern)
                    append(" = ")
                    printExpr(ifLet.expr)
                }
                append(" -> ")
                printExpr(case.body)
                trimTrailingNewlines()
                newLine()
            }
        }
    }

    private fun printDo(expr: ParsedDo) {
        append("do")
        newLine()
        indent {
            var prevEndLine: Int? = null
            for (stmt in expr.statements) {
                // Preserve the author's blank lines between statements. Measured
                // to the top of this statement's comment block, so a comment
                // sitting directly above a statement is not miscounted as a gap.
                if (prevEndLine != null && authorLeftBlankLineBefore(prevEndLine, stmt.leadingComments, stmt.loc)) newLine()
                printStatementComments(stmt)
                printIndent()
                printStatement(stmt)
                stmt.trailingComment?.let { append("  "); append(it.text) }
                newLine()
                prevEndLine = stmt.loc.endLine
            }
            if (prevEndLine != null &&
                authorLeftBlankLineBefore(prevEndLine, expr.resultLeadingComments, expr.result.loc)) newLine()
            // Comments written above the block's final expression. The result
            // is not a ParsedStatement, so it does not go through
            // printStatementComments — without this they are dropped.
            printInBlockComments(expr.resultLeadingComments, null, null)
            printIndent()
            printExpr(expr.result)
        }
    }

    /**
     * Did the author leave a blank line before this in-block node? Measured
     * from [prevEndLine] to the top of the node's block — its first leading
     * comment if it has one, else the node itself — so a comment directly
     * above a statement is not mistaken for a gap. Mirrors
     * [authorLeftBlankLine] for top-level decls.
     */
    private fun authorLeftBlankLineBefore(
        prevEndLine: Int,
        leadingComments: List<ParsedComment>,
        loc: Loc,
    ): Boolean {
        val top = leadingComments.minOfOrNull { it.loc.startLine } ?: loc.startLine
        return top - prevEndLine >= 2
    }

    private fun printStatementComments(stmt: ParsedStatement) =
        printInBlockComments(stmt.leadingComments, stmt.docComment, stmt.metadata)

    /**
     * Emit an in-block node's preserved comments (do-block statement, match arm,
     * record entry) in source order: `##` doc lines, then `#{}` metadata, then
     * regular `#` comments — each on its own indented line, mirroring how
     * declarations print theirs.
     */
    private fun printInBlockComments(
        leadingComments: List<ParsedComment>,
        docComment: String?,
        metadata: ParsedMetadata?,
    ) {
        docComment?.let { doc ->
            for (line in doc.split("\n")) {
                printIndent()
                if (line.isEmpty()) append("##") else append("## $line")
                newLine()
            }
        }
        metadata?.let { meta ->
            printIndent()
            val content = formatMetadataFields(meta.fields)
            append(if (content.isEmpty()) "#{}" else "#{ $content }")
            newLine()
        }
        for (comment in leadingComments) {
            printIndent()
            append(comment.text)
            newLine()
        }
    }

    /**
     * Print a member operation's `## doc` block (ability/effect/impl/performer
     * ops), indented at the current level, one `## line` per doc line. Blank
     * doc lines round-trip as a bare `##`.
     */
    private fun printMemberDoc(doc: String?) {
        doc ?: return
        for (line in doc.split("\n")) {
            printIndent()
            if (line.isEmpty()) append("##") else append("## $line")
            newLine()
        }
    }

    private fun printStatement(stmt: ParsedStatement) {
        when (stmt) {
            is ParsedStatement.Let -> {
                append("let ")
                printPattern(stmt.pattern)
                stmt.typeAnnotation?.let { append(": "); printTypeAnnotation(it) }
                append(" = ")
                printExpr(stmt.value)
            }
            is ParsedStatement.Expr -> printExpr(stmt.expr)
        }
    }

    private fun printPerform(expr: ParsedPerform) {
        append("perform ")
        expr.handlers.forEachIndexed { i, handler ->
            if (i > 0) append(", ")
            if (handler.performer.qualifier != null) {
                append(handler.performer.qualifier!!.segments.joinToString("::"))
                append("::")
            }
            append(handler.performer.localName)
            handler.params?.let { append(" "); printRecordLit(it) }
        }
        append(" in ")
        printExpr(expr.body)
    }

    private fun printEffectCall(expr: ParsedEffectCall) {
        if (expr.operation.qualifier != null) {
            append(expr.operation.qualifier!!.segments.joinToString("::"))
            append("::")
        }
        append(expr.operation.localName)
        append(" ")
        printExpr(expr.arguments)
    }

    // =========================================================================
    // PATTERNS
    // =========================================================================

    private fun printPattern(pattern: ParsedPattern) {
        when (pattern) {
            is ParsedWildcardPattern -> append("_")
            is ParsedVariablePattern -> append(pattern.name)
            is ParsedConstructorPattern -> {
                append(pattern.constructor)
                pattern.payload?.let { append(" "); printPattern(it) }
            }
            is ParsedNewtypePattern -> { append(pattern.typeName); append(".("); printPattern(pattern.inner); append(")") }
            is ParsedRecordPattern -> printRecordPattern(pattern)
            is ParsedLiteralPattern -> printLiteralValue(pattern.value)
            is ParsedSeqPattern -> printSeqPattern(pattern)
            is ParsedStringInterpPattern -> printStringInterpPattern(pattern)
        }
    }

    private fun printRecordPattern(pattern: ParsedRecordPattern) {
        if (pattern.entries.isEmpty() && !pattern.isOpen) { append("{}"); return }
        append("{ ")
        pattern.entries.forEachIndexed { i, entry ->
            if (i > 0) append(", ")
            append(entry.name)
            entry.pattern?.let { append(" = "); printPattern(it) }
        }
        if (pattern.isOpen) {
            if (pattern.entries.isNotEmpty()) append(", ")
            append("...")
        }
        append(" }")
    }

    private fun printSeqPattern(pattern: ParsedSeqPattern) {
        append("[")
        pattern.elements.forEachIndexed { i, elem ->
            if (i > 0) append(", ")
            printPattern(elem)
        }
        pattern.restPattern?.let {
            if (pattern.elements.isNotEmpty()) append(", ")
            append("...")
            append(it)
        }
        append("]")
    }

    private fun printStringInterpPattern(pattern: ParsedStringInterpPattern) {
        append("\"")
        for (part in pattern.parts) {
            when (part) {
                is ParsedStringPatternPart.Literal -> append(escapeString(part.value))
                is ParsedStringPatternPart.Capture -> { append("\$("); append(part.variableName); append(")") }
            }
        }
        append("\"")
    }

    private fun printLiteralValue(value: ParsedLiteralValue) {
        when (value) {
            is ParsedLiteralValue.IntValue -> append(value.value.toString())
            is ParsedLiteralValue.NatValue -> append("${value.value}n")
            is ParsedLiteralValue.FloatValue -> append(formatFloat(value.value))
            is ParsedLiteralValue.StringValue -> { append("\""); append(escapeString(value.value)); append("\"") }
            is ParsedLiteralValue.BoolValue -> append(if (value.value) "True" else "False")
        }
    }

    // =========================================================================
    // TYPE ANNOTATIONS
    // =========================================================================

    private fun printTypeAnnotation(type: ParsedTypeAnnotation) {
        when (type) {
            is ParsedNamedType -> append(type.name)
            is ParsedQualifiedType -> {
                append(type.modulePath.joinToString("::"))
                append("::")
                append(type.typeName)
                if (type.args.isNotEmpty()) {
                    append(" ")
                    type.args.forEachIndexed { i, arg ->
                        if (i > 0) append(" ")
                        val needsParens = arg is ParsedFunctionType || (arg is ParsedTypeApp && arg.args.isNotEmpty())
                        if (needsParens) append("(")
                        printTypeAnnotation(arg)
                        if (needsParens) append(")")
                    }
                }
            }
            is ParsedTypeApp -> {
                append(type.constructor)
                if (type.args.isNotEmpty()) {
                    append(" ")
                    type.args.forEachIndexed { i, arg ->
                        if (i > 0) append(" ")
                        val needsParens = arg is ParsedFunctionType || (arg is ParsedTypeApp && arg.args.isNotEmpty())
                        if (needsParens) append("(")
                        printTypeAnnotation(arg)
                        if (needsParens) append(")")
                    }
                }
            }
            is ParsedRecordType -> printRecordTypeAnnotation(type)
            is ParsedVariantType -> printVariantTypeAnnotation(type)
            is ParsedFunctionType -> printFunctionTypeAnnotation(type)
            is ParsedSeqType -> { append("["); printTypeAnnotation(type.elementType); append("]") }
        }
    }

    private fun printRecordTypeAnnotation(type: ParsedRecordType) {
        if (type.entries.isEmpty() && !type.isOpen) { append("{}"); return }
        // Record type annotations MUST be single-line (parser uses indentation-based parsing)
        append("{ ")
        type.entries.forEachIndexed { i, entry ->
            if (i > 0) append(", ")
            append(entry.name)
            append(": ")
            printTypeAnnotation(entry.type)
        }
        if (type.isOpen) {
            if (type.entries.isNotEmpty()) append(", ")
            append("...")
        }
        append(" }")
    }

    private fun printVariantTypeAnnotation(type: ParsedVariantType) {
        append("| ")
        type.constructors.forEachIndexed { i, c ->
            if (i > 0) append(", ")
            append(c.name)
            c.dataType?.let { append(" "); printTypeAnnotation(it) }
        }
        if (type.isOpen) append(", ...")
        append(" |")
    }

    private fun printFunctionTypeAnnotation(type: ParsedFunctionType) {
        printTypeAnnotation(type.paramType)
        if (type.abilityConstraints.isNotEmpty()) {
            append(" with ")
            printAbilityConstraints(type.abilityConstraints)
        }
        append(" -> ")
        printTypeAnnotation(type.returnType)
        type.effects?.let { append(" "); printEffectRow(it) }
    }

    private fun printEffectRow(row: ParsedEffectRow) {
        append("<")
        val allEffects = row.effects.toMutableList()
        for (ann in row.effectAnnotations) {
            val sb = StringBuilder(ann.name)
            if (ann.associatedTypes.isNotEmpty()) {
                sb.append(" having ")
                ann.associatedTypes.entries.forEachIndexed { i, (name, typeAnn) ->
                    if (i > 0) sb.append(", ")
                    sb.append("$name = ")
                    sb.append(formatTypeSimple(typeAnn))
                }
            }
            allEffects.add(sb.toString())
        }
        append(allEffects.joinToString(", "))
        if (row.isPolymorphic) { if (allEffects.isNotEmpty()) append(", "); append("...") }
        if (row.isPerformed) { if (allEffects.isNotEmpty() || row.isPolymorphic) append(", "); append("*") }
        append(">")
    }

    private fun printAbilityConstraints(constraints: List<ParsedFnAbilityConstraint>) {
        constraints.forEachIndexed { i, c ->
            if (i > 0) append(", ")
            append(c.abilityName)
            if (c.typeArgs.isNotEmpty()) {
                append(" ")
                c.typeArgs.forEachIndexed { j, arg ->
                    if (j > 0) append(" ")
                    printTypeAnnotation(arg)
                }
            }
            // Associated-type bindings: `having 'tv = T, 'tv2 = T2`. Preserve
            // map insertion order so user-written ordering survives round-trip.
            // Without this clause the formatter silently drops `having` bindings,
            // turning `Container 'c having 'item = Int` into `Container 'c`.
            if (c.associatedTypeBindings.isNotEmpty()) {
                append(" having ")
                c.associatedTypeBindings.entries.forEachIndexed { j, (name, type) ->
                    if (j > 0) append(", ")
                    append("$name = ")
                    printTypeAnnotation(type)
                }
            }
        }
    }

    // =========================================================================
    // COMMENTS
    // =========================================================================

    private fun printLeadingComments(decl: ParsedDecl) {
        for (comment in decl.leadingComments) {
            printIndent()
            append(comment.text)
            newLine()
        }
    }

    private fun printDocComment(decl: ParsedDecl) {
        val doc = decl.docComment ?: return
        for (line in doc.split("\n")) {
            printIndent()
            if (line.isEmpty()) {
                append("##")
            } else {
                append("## $line")
            }
            newLine()
        }
    }

    private fun printMetadata(decl: ParsedDecl) {
        val meta = decl.metadata ?: return
        printIndent()
        val content = formatMetadataFields(meta.fields)
        if (content.isEmpty()) {
            append("#{}")
        } else {
            append("#{ $content }")
        }
        newLine()
    }

    private fun formatMetadataFields(fields: Map<String, ParsedMetadataValue>): String {
        return fields.entries.joinToString(", ") { (key, value) ->
            "$key: ${formatMetadataValue(value)}"
        }
    }

    private fun formatMetadataValue(value: ParsedMetadataValue): String {
        return when (value) {
            is ParsedMetadataValue.StringVal -> "\"${value.value.replace("\"", "\\\"")}\""
            is ParsedMetadataValue.IntVal -> value.value.toString()
            is ParsedMetadataValue.BoolVal -> if (value.value) "True" else "False"
            is ParsedMetadataValue.FloatVal -> value.value.toString()
            is ParsedMetadataValue.ListVal -> "[${value.values.joinToString(", ") { formatMetadataValue(it) }}]"
            is ParsedMetadataValue.RecordVal -> "{${formatMetadataFields(value.fields)}}"
        }
    }

    private fun printTrailingComment(decl: ParsedDecl) {
        val comment = decl.trailingComment
        if (comment != null) {
            // Remove the last newline, add trailing comment, then re-add newline
            if (output.isNotEmpty() && output.last() == '\n') {
                output.deleteCharAt(output.length - 1)
                append("  ")
                append(comment.text)
                output.append('\n')
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun append(s: String) {
        output.append(s)
        val lastNewline = s.lastIndexOf('\n')
        if (lastNewline >= 0) currentColumn = s.length - lastNewline - 1
        else currentColumn += s.length
    }

    private fun newLine() {
        output.append("\n")
        currentColumn = 0
    }

    private fun trimTrailingNewlines() {
        while (output.isNotEmpty() && output.last() in listOf('\n', ' ', '\t')) {
            output.deleteCharAt(output.length - 1)
        }
        val lastNewline = output.lastIndexOf('\n')
        currentColumn = if (lastNewline >= 0) output.length - lastNewline - 1 else output.length
    }

    private fun printIndent() {
        repeat(indentLevel) { output.append(config.INDENT_STRING) }
        currentColumn = indentLevel * config.INDENT_WIDTH
    }

    private inline fun indent(block: () -> Unit) { indentLevel++; block(); indentLevel-- }

    private fun escapeString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\$", "\\\$")

    private fun formatFloat(value: Double): String {
        val s = value.toString()
        return if (!s.contains('.') && !s.contains('e') && !s.contains('E')) "$s.0" else s
    }

    private fun formatTypeSimple(type: ParsedTypeAnnotation): String = when (type) {
        is ParsedNamedType -> type.name
        is ParsedTypeApp -> if (type.args.isEmpty()) type.constructor
            else "${type.constructor} ${type.args.joinToString(" ") { formatTypeSimple(it) }}"
        else -> type.toString()
    }

    private fun needsParens(expr: ParsedExpr, op: String, isLeft: Boolean): Boolean {
        if (expr !is ParsedBinaryOp) return false
        val outerPrec = operatorPrecedence(op)
        val innerPrec = operatorPrecedence(expr.op.symbol)
        return if (isLeft) innerPrec < outerPrec else innerPrec <= outerPrec
    }

    private fun operatorPrecedence(op: String): Int = when (op) {
        "||" -> 1; "&&" -> 2; "==", "!=" -> 3; "<", ">", "<=", ">=" -> 4
        "+", "-" -> 5; "*", "/", "%" -> 6; else -> 0
    }

    private fun isSimpleExpr(expr: ParsedExpr): Boolean = when (expr) {
        is ParsedIntLit, is ParsedFloatLit, is ParsedStringLit,
        is ParsedNatLit, is ParsedFracLit, is ParsedCharLit,
        is ParsedIdentifier, is ParsedPipePlaceholder -> true
        is ParsedVariantConstruct -> expr.value == null || isSimpleExpr(expr.value!!)
        is ParsedMemberAccess -> isSimpleExpr(expr.receiver)
        else -> false
    }

    private fun estimateExprWidth(expr: ParsedExpr): Int = when (expr) {
        is ParsedIntLit -> expr.value.toString().length
        is ParsedNatLit -> expr.value.toString().length + 1
        is ParsedFloatLit -> expr.value.toString().length
        is ParsedFracLit -> expr.numerator.toString().length + expr.denominator.toString().length + 2
        is ParsedStringLit -> expr.parts.sumOf { when (it) { is ParsedStringPart.Literal -> it.value.length; is ParsedStringPart.Interpolation -> 4 } } + 2
        is ParsedCharLit -> 3
        is ParsedIdentifier -> expr.localName.length + (expr.qualifier?.segments?.sumOf { it.length + 2 } ?: 0)
        is ParsedPipePlaceholder -> 1
        is ParsedVariantConstruct -> expr.constructor.length + (expr.value?.let { estimateExprWidth(it) + 1 } ?: 0)
        is ParsedMemberAccess -> estimateExprWidth(expr.receiver) + expr.member.length + 1
        is ParsedFunctionCall -> estimateExprWidth(expr.function) + estimateExprWidth(expr.arguments) + 1
        is ParsedBinaryOp -> estimateExprWidth(expr.left) + expr.op.symbol.length + 2 + estimateExprWidth(expr.right)
        is ParsedRecordLit -> estimateRecordLitWidth(expr)
        is ParsedParens -> estimateExprWidth(expr.expr) + 2
        is ParsedPipe -> estimateExprWidth(expr.left) + estimateExprWidth(expr.right) + 4
        else -> 20
    }

    private fun estimateRecordLitWidth(expr: ParsedRecordLit): Int {
        if (expr.entries.isEmpty()) return 2
        return 4 + expr.entries.sumOf { entry ->
            when (entry) {
                is ParsedRecordEntry.Field -> entry.name.length + (entry.value?.let { 3 + estimateExprWidth(it) } ?: 0) + 2
                is ParsedRecordEntry.Spread -> 3 + estimateExprWidth(entry.expr) + 2
            }
        }
    }

    private fun estimateSeqLitWidth(expr: ParsedSeqLit): Int {
        if (expr.elements.isEmpty()) return 2
        return 2 + expr.elements.sumOf { elem ->
            when (elem) {
                is ParsedSeqElement.Regular -> estimateExprWidth(elem.expr) + 2
                is ParsedSeqElement.Spread -> 3 + estimateExprWidth(elem.expr) + 2
            }
        }
    }

    private fun estimateTypeWidth(type: ParsedTypeAnnotation): Int = when (type) {
        is ParsedNamedType -> type.name.length
        is ParsedTypeApp -> type.constructor.length + 1 + type.args.sumOf { estimateTypeWidth(it) + 1 }
        is ParsedRecordType -> 4 + type.entries.sumOf { it.name.length + 2 + estimateTypeWidth(it.type) + 2 }
        is ParsedFunctionType -> estimateTypeWidth(type.paramType) + 4 + estimateTypeWidth(type.returnType)
        else -> 10
    }
}
